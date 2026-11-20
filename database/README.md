# SCH-02 PostgreSQL deployment

## Fresh database migration contract

Fresh PostgreSQL 18+ environments must use `scripts/migrate-hms-database.ps1` with an
explicit target confirmation. The runner validates `database/migration-manifest.json`,
checks every normalized-LF SHA-256, pins `search_path` to `pg_catalog, public`, uses
`ON_ERROR_STOP`, and records an attested target plus an ordered ledger in the private
`hms_migration` schema. It never reads `pgpass.conf` or falls back to an application
database. Supply the migration credential only as the current-process
`HMS_MIGRATION_PASSWORD` variable.

The canonical fresh-database order is:

1. `pre-sch-02-baseline.sql`
2. `sch-02-availability-revisions.sql`
3. `disco-01-doctor-discovery.sql`
4. `disco-02-clinic-lgd-district.sql`
5. `doc-01-verification-catalog.sql`
6. `ai-01-patient-assistant.sql`
7. `ai-02-cloudflare-source.sql`
8. `appt-cancel-01-workflow.sql`
9. `bill-01-schema.sql`

Historical reconciliation scripts are not part of this sequence. A failed run is
left in place for inspection. Review the migration ledger and catalog digest before
retrying rather than silently rerunning a partial application. PostgreSQL tests use
the separate, opt-in `HMS_POSTGRES_TEST_*` contract and require a database name in
the `hms_dev_test_<32 lowercase hex>` namespace, its OID, an exact confirmation, a
dedicated user and a complete `hms_migration` disposable attestation. Without the
opt-in, those tests are skipped and ordinary tests remain on H2.

### Roles and recovery controls

The local bootstrap administrator is used only to create the explicitly named local
database and capture its OID. The deployment migration role is a separate dedicated
role whose privileges must be reviewed from the SQL objects in this repository. The
runtime application role is separate and must not own the schema or have DDL
privileges. The runner records one ledger row only after a migration and its verifier
complete successfully; an interrupted step is therefore resumable, while a checksum
or catalog mismatch fails closed. `-SkipMigrations` is a local-only fixture option:
it creates an unattested database and must never be supplied to a release or
deployment path. Hikari attestation currently occurs when a PostgreSQL test pool is
created, avoiding per-query overhead; a newly created physical connection should be
re-attested if the test environment uses a long-lived pool.

HMS deployment mode (`HMS_DEPLOYMENT_MODE=deployment`) requires the `prod` Spring
profile, eager bean initialization, and an independently verified database OID.
Set `HMS_RELEASE_ATTESTATION_EXPECTED_DATABASE_OID` to the positive OID of the
target database. The production profile also recognizes `HMS_EXPECTED_DATABASE_OID`
as a legacy fallback when the primary setting is absent.

`ReleaseAttestationGate` runs during application-context refresh under `prod`
and blocks startup if the resolved expected OID is missing, invalid, or differs
from the connected database's OID. The gate also verifies database identity, a
single `DEPLOYMENT` release target, all nine ordered migration IDs and checksums,
and the final catalog digest. Missing, partial, or mismatched attestation
prevents the application from serving HTTP traffic. Attestation runs no migrations.

Ordinary local development and H2 tests outside the `prod` profile do not use
this deployment attestation or require the deployment OID setting. Opt-in local
PostgreSQL tests still require the separate disposable-database checks above.

The application currently uses Hibernate schema updates. This migration is manual because the project has no migration runner. Rehearse the migration and the scheduling tests on a disposable PostgreSQL database before deployment.

1. Back up the target database and stop all application instances and other schedule writers. Verify the target schema contains `doctor_availability`, `appointment_slots`, and `uk_doctor_slot_time` and that the SCH-02 columns are absent.
2. Run `psql -X -v ON_ERROR_STOP=1 -f database/sch-02-availability-revisions.sql` with an approved connection to the target database and schema. Apply the script once, before starting the new application. The script runs in a transaction and can acquire table locks.
3. Verify that existing availability revisions are zero and all existing slots are retired with null provenance. Verify the existing slot and appointment counts, booking statuses, and slot references against the backup. Deploy the new version to every instance before accepting traffic.
4. Regenerate slots for current availability windows using the doctor or admin generation action or the authenticated `POST /api/appointment-slots/{availabilityId}/generate` endpoint. Generation only republishes eligible times. It may reissue an exact retired, unoccupied slot with the current revision to preserve the existing unique constraint. Existing bookings keep their original time, doctor, and slot. Cancelling a retired booking leaves its slot withdrawn until eligible generation explicitly republishes that interval.

Review any pre-existing overlapping confirmed bookings with scheduling staff. The migration preserves those bookings, and the new code rejects further bookings that overlap them. Keep the application stopped during rollback planning. An older application version would ignore retirement and could offer withdrawn slots.

# AI-01 Patient Assistant

`ai-01-patient-assistant.sql` is an additive, apply-once migration for the existing `public.patients` schema. It creates `ai_chat_sessions` and `ai_chat_messages`; it does not change appointments, patient records, SCH-02, or DISCO-01. Apply it manually to a reviewed target before deploying the assistant. There is no automatic migration runner.

The assistant now answers from a local predefined FAQ knowledge base. Its existing conversation history and ownership model are retained. No new migration is needed for this simplification, and existing AI-01 installations must not reapply the script. The legacy `include_appointments` column remains for schema compatibility; new chat messages write false, and the FAQ does not query appointment records or use personal profile details.

Rehearse against a disposable PostgreSQL database first. Do not apply `pre-sch-02-baseline.sql` to an existing database. On the reviewed, already migrated target, stop application writers, take the usual backup, and run the following from the repository root. Use pgpass for authentication. Substitute the explicit reviewed target; do not use the real HMS database for testing.

```powershell
$psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
$targetDatabase = Read-Host 'Explicit reviewed target database name'
if ([string]::IsNullOrWhiteSpace($targetDatabase)) { throw 'A target database is required' }
& $psql -h localhost -p 5433 -U postgres -d $targetDatabase -X -w -v ON_ERROR_STOP=1 -f .\database\ai-01-patient-assistant.sql
if ($LASTEXITCODE -ne 0) { throw 'AI-01 migration failed; stop and inspect the target' }
```

The script uses a transaction, a five-second DDL lock timeout, bigint identities, UUID request IDs, and microsecond local timestamps consistent with existing tables. The message request/role unique constraint supports retries. Deleting an owned conversation deletes its messages; deleting a patient with conversations remains restricted. Both indexes support bounded history queries. PostgreSQL 14+ supports this syntax, including the project's previously verified PostgreSQL 18.1 server. The AI-01 script has not been executed as part of implementation verification.

Use `spring.jpa.hibernate.ddl-auto=validate` after approved migrations. See [local FAQ knowledge base, API contracts, and deployment checks](../docs/patient-ai-assistant.md). Do not rely on the existing application's `update` setting to install production constraints.

# APPT-CANCEL-01 Appointment Cancellation Workflow

`database/appt-cancel-01-workflow.sql` is a standalone, apply-once PostgreSQL 18.1+ migration for `public.appointments`. Do not modify `database/reconciliation-deployment/*` (those scripts remain pinned to the prior reconciled baseline) and do not run migrations against the live database without an approved deployment window and backup.

1. Run a fail-closed rehearsal on a disposable PostgreSQL database after the existing baseline schema is installed:
   - `psql -h localhost -p 5433 -U postgres -d <disposable_db> -X -w -v ON_ERROR_STOP=1 -f .\database\appt-cancel-01-workflow.sql`
   - `psql -h localhost -p 5433 -U postgres -d <disposable_db> -X -w -v ON_ERROR_STOP=1 -f .\database\appt-cancel-01-verify.sql`
2. The migration fails closed if duplicate `CONFIRMED` bookings exist for the same `slot_id`, if any `CONFIRMED` appointment points to a non-`BOOKED` slot, or if overlapping `CONFIRMED` bookings exist for the same doctor and date. It installs the five cancellation metadata columns (`cancellation_reason`, `cancelled_at` as `timestamp with time zone`, `cancelled_by_user_id` with `ON DELETE SET NULL`, `cancelled_by_role`, `cancellation_slot_outcome`), consistency/role/outcome check constraints permitting legacy null-metadata `CANCELLED` rows, `uq_appointments_confirmed_slot`, and `ix_appointments_doctor_cancelled_at`.
3. Configure `hms.scheduling.zone-id=Asia/Kolkata` explicitly (or set an approved IANA zone ID) so slot `(date, start_time/end_time)` boundaries convert deterministically to UTC `Instant` timestamps regardless of JVM host timezone. Run the application with `spring.jpa.hibernate.ddl-auto=validate` after applying the migration.

# Release attestation digest and runtime role

Production startup attestation verifies the packaged `database/migration-catalog-digest.sql` checksum and runs the SQL function's unchanged query body directly. It pins one backend for that query in a read-only transaction and applies the original `pg_catalog` search path only within the transaction. Startup creates no function and leaves no session-scoped setting. The migration runners still use the original temporary helper and write the same catalog digest to the ledger.

The runtime role admission policy remains unchanged: it requires database `TEMP` and disallows privileged memberships, database/schema/object ownership, schema `CREATE`, and database `CREATE`. The application never grants privileges automatically.
