# SCH-02 PostgreSQL deployment

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
