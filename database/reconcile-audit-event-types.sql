-- Audit event constraint reconciliation CANDIDATE. Disposable validation only.
-- Application source of truth: AuditEventType enum in the repository.
-- This adds only the four application events absent from the current CHECK.
-- Invoke using psql -X -w -v ON_ERROR_STOP=1 and explicit session database:
--   -v audit_reconciliation_database=<session-created disposable validation database>
-- Existing audit rows are preserved and constraint validation is immediate.
\if :{?audit_reconciliation_database}
\else
\echo 'Missing explicit audit_reconciliation_database; refusing to run'
\quit 3
\endif

BEGIN;
SET LOCAL search_path TO public, pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SELECT set_config('hms.audit_reconciliation_database', :'audit_reconciliation_database', true);

DO $preflight$
DECLARE
    old_definition text;
BEGIN
    IF current_database() <> current_setting('hms.audit_reconciliation_database')
       OR current_database() !~ '^hms_(audit|reconcile)_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}$'
       OR inet_server_port() <> 5433
       OR current_setting('server_version_num')::integer <> 180001 THEN
        RAISE EXCEPTION 'Wrong target; disposable PostgreSQL 18.1 audit rehearsal only';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()) THEN
        RAISE EXCEPTION 'Other sessions exist; require isolated clone';
    END IF;
    LOCK TABLE public.audit_logs IN ACCESS EXCLUSIVE MODE;
    SELECT pg_get_constraintdef(oid,true) INTO old_definition
    FROM pg_constraint
    WHERE conrelid='public.audit_logs'::regclass
      AND conname='audit_logs_event_type_check'
      AND contype='c' AND convalidated;
    IF old_definition IS DISTINCT FROM
       'CHECK (event_type::text = ANY (ARRAY[''LOGIN_SUCCESS''::character varying::text, ''LOGIN_FAILURE''::character varying::text, ''PATIENT_BOOKED_APPOINTMENT''::character varying::text, ''PATIENT_CANCELLED_APPOINTMENT''::character varying::text, ''DOCTOR_CREATED_AVAILABILITY''::character varying::text, ''DOCTOR_UPDATED_AVAILABILITY''::character varying::text, ''ADMIN_CREATED_DOCTOR''::character varying::text, ''ADMIN_UPDATED_DOCTOR''::character varying::text]))' THEN
        RAISE EXCEPTION 'Audit constraint differs from inspected baseline; stop';
    END IF;
    IF EXISTS (SELECT 1 FROM public.audit_logs WHERE event_type::text NOT IN (
        'LOGIN_SUCCESS','LOGIN_FAILURE','PATIENT_BOOKED_APPOINTMENT','PATIENT_CANCELLED_APPOINTMENT',
        'DOCTOR_CREATED_AVAILABILITY','DOCTOR_UPDATED_AVAILABILITY','ADMIN_CREATED_DOCTOR','ADMIN_UPDATED_DOCTOR'
    )) THEN
        RAISE EXCEPTION 'Existing audit data violates the inspected legacy CHECK';
    END IF;
    PERFORM set_config('hms.audit_count_before',(SELECT count(*)::text FROM public.audit_logs),true);
    PERFORM set_config('hms.audit_rows_before',
        (SELECT md5(coalesce(string_agg(md5(to_jsonb(a)::text),'' ORDER BY a.id),'')) FROM public.audit_logs a),true);
END;
$preflight$;

ALTER TABLE public.audit_logs
    DROP CONSTRAINT audit_logs_event_type_check,
    ADD CONSTRAINT audit_logs_event_type_check CHECK (event_type IN (
        'LOGIN_SUCCESS',
        'LOGIN_FAILURE',
        'PATIENT_BOOKED_APPOINTMENT',
        'PATIENT_CANCELLED_APPOINTMENT',
        'DOCTOR_CANCELLED_APPOINTMENT',
        'ADMIN_CANCELLED_APPOINTMENT',
        'DOCTOR_COMPLETED_APPOINTMENT',
        'ADMIN_COMPLETED_APPOINTMENT',
        'DOCTOR_CREATED_AVAILABILITY',
        'DOCTOR_UPDATED_AVAILABILITY',
        'ADMIN_CREATED_DOCTOR',
        'ADMIN_UPDATED_DOCTOR'
    ));

DO $verify$
DECLARE
    new_definition text;
BEGIN
    SELECT pg_get_constraintdef(oid,true) INTO new_definition
    FROM pg_constraint
    WHERE conrelid='public.audit_logs'::regclass
      AND conname='audit_logs_event_type_check'
      AND contype='c' AND convalidated;
    IF new_definition IS NULL
       OR (SELECT count(*) FROM public.audit_logs)::text <> current_setting('hms.audit_count_before')
       OR (SELECT md5(coalesce(string_agg(md5(to_jsonb(a)::text),'' ORDER BY a.id),'')) FROM public.audit_logs a)
             <> current_setting('hms.audit_rows_before') THEN
        RAISE EXCEPTION 'Audit records or validated constraint were not preserved';
    END IF;
    RAISE NOTICE 'PASS: audit_logs_event_type_check now permits exactly the 12 Java enum values; all existing audit rows preserved';
END;
$verify$;
COMMIT;
