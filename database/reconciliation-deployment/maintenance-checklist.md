# HMS local database reconciliation

Use this checklist after explicit approval to apply the migration. The entrypoint is `live.sql`. It runs the shared SCH-02/DISCO-01 and audit SQL in one transaction. The two earlier disposable candidates remain available as the reference for the reviewed DDL.

## Before the window

- Review `live.sql`, `schema.sql`, `audit.sql`, and `verify.sql` together. The disposable entrypoint uses the same schema and audit files.
- Confirm the intended impact. All 62 legacy slots become retired. The 51 AVAILABLE slots cease to be bookable, and the 11 BOOKED slots retain their statuses and references. The 4 doctors start as UNVERIFIED. The 15 availability rows start at revision zero and mode IN_CLINIC. The 13 appointments retain their statuses and references, and existing NULL provenance, clinic, fee and snapshot fields remain NULL.
- Confirm that the reviewed application build is ready to start with Hibernate `ddl-auto=validate`.

## During the window

1. Gracefully pause incoming traffic and scheduled work. Close the application JDBC pool and any other clients through their normal shutdown controls. Keep writers and automatic reconnects paused through post-commit verification.
2. From a read-only preflight session, confirm `hospital_management` at loopback TCP port `5433`, PostgreSQL `18.1` (`180001`), database OID `16560`, and both roles `postgres`. Confirm zero other sessions, zero active or idle transactions, and zero prepared transactions. Close the preflight session before invoking the entrypoint.
3. Recompute the public catalog fingerprint using the same projection as `schema.sql`. The live expected MD5 is `3f30aea3e9ef0f23ea733d5ff4541d2d`. Recheck the legacy statuses, reference integrity, NULL fields and eight-event audit allowlist. If the schema, legacy scheduling data, or assumptions have changed, stop for review.
4. Verify the latest full custom-format backup with `pg_restore --list`, and record its exact path, byte size and SHA-256. The rehearsal archive was `hospital_management_final_preflight_20261106_141639_313ca8d6.dump` with SHA-256 `6B3AEE2B9D55648772F854C161C5EEC97C9EC60DD3425EC0F1BC0E80DEB27EA9`. Take and verify a new backup while writers are paused if the recorded archive predates the current data. Record the pre-migration row, sequence, and appointment reference fingerprints for the maintenance log.
5. From the repository root, run the approved entrypoint with the configured `pgpass` credentials. Keep the complete output and require exit code 0 and a successful `COMMIT`.

   ```powershell
   $psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
   & $psql -X -w -h localhost -p 5433 -U postgres -d hospital_management -v ON_ERROR_STOP=1 -v confirm_live_database=hospital_management -f database/reconciliation-deployment/live.sql
   if ($LASTEXITCODE -ne 0) { throw 'Reconciliation failed. Keep traffic paused and verify the database state.' }
   ```

   The entrypoint checks target identity, catalog fingerprint, business counts, isolation and the legacy audit CHECK before commit. The scheduling DDL and its preservation checks run first, followed by the audit CHECK replacement and audit row verification. AI-01 stays as restored or repaired.

6. While traffic is still paused, run the read-only post-commit assertions and compare the pre- and post-migration row and sequence fingerprints and appointment references. The row comparison excludes only the four newly added columns. Confirm the exact 12-event audit CHECK matches `AuditEventType.java` and the AI-01 catalog and rows are preserved.

   ```powershell
   & $psql -X -w -h localhost -p 5433 -U postgres -d hospital_management -v ON_ERROR_STOP=1 -f database/reconciliation-deployment/verify.sql
   if ($LASTEXITCODE -ne 0) { throw 'Post-commit verification failed. Keep traffic paused.' }
   ```

7. Restart the reviewed application with `ddl-auto=validate`. Check startup, then confirm the expected scheduling and audit behavior before reopening traffic.

## If anything fails

Keep traffic paused. A SQL failure before commit rolls back both reconciliation steps. Reconnect read-only and compare the schema and fingerprints with the preflight. If the connection failed around commit, establish whether the full migration committed before retrying. The package is intentionally non-idempotent.

If the full transaction committed and post-commit checks fail, retain the logs and verified archive. Review the discrepancy and agree on recovery before any further write or restore.
