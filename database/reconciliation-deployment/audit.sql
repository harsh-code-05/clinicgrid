-- Shared transaction body. Run only through live.sql or disposable.sql.
\set ON_ERROR_STOP on
SET LOCAL statement_timeout = '30s';

DO $audit$
DECLARE
    expected_legacy constant text[] := ARRAY[
        'ADMIN_CREATED_DOCTOR', 'ADMIN_UPDATED_DOCTOR', 'DOCTOR_CREATED_AVAILABILITY',
        'DOCTOR_UPDATED_AVAILABILITY', 'LOGIN_FAILURE', 'LOGIN_SUCCESS',
        'PATIENT_BOOKED_APPOINTMENT', 'PATIENT_CANCELLED_APPOINTMENT'
    ];
    expected_final constant text[] := ARRAY[
        'ADMIN_CANCELLED_APPOINTMENT', 'ADMIN_COMPLETED_APPOINTMENT', 'ADMIN_CREATED_DOCTOR',
        'ADMIN_UPDATED_DOCTOR', 'DOCTOR_CANCELLED_APPOINTMENT', 'DOCTOR_COMPLETED_APPOINTMENT',
        'DOCTOR_CREATED_AVAILABILITY', 'DOCTOR_UPDATED_AVAILABILITY', 'LOGIN_FAILURE',
        'LOGIN_SUCCESS', 'PATIENT_BOOKED_APPOINTMENT', 'PATIENT_CANCELLED_APPOINTMENT'
    ];
    check_expression text;
    check_shape text;
    actual_events text[];
    expected_events text[];
    audit_count bigint;
    audit_digest text;
    phase integer;
BEGIN
    IF current_setting('hms.reconcile_schema_verified', true) IS DISTINCT FROM 'yes' THEN
        RAISE EXCEPTION 'Run the schema and audit reconciliation together through a guarded entrypoint';
    END IF;
    SELECT count(*), md5(coalesce(string_agg(md5(to_jsonb(a)::text), '' ORDER BY a.id), ''))
    INTO audit_count, audit_digest
    FROM public.audit_logs a;

    FOR phase IN 1..2 LOOP
        expected_events := CASE phase WHEN 1 THEN expected_legacy ELSE expected_final END;
        SELECT pg_get_expr(c.conbin, c.conrelid, true)
        INTO check_expression
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = 'event_type' AND NOT a.attisdropped
        JOIN pg_collation col ON col.oid = a.attcollation
        WHERE c.conrelid = 'public.audit_logs'::regclass AND c.conname = 'audit_logs_event_type_check'
          AND c.contype = 'c' AND c.convalidated AND c.conkey = ARRAY[a.attnum]
          AND a.attnotnull AND a.atttypid = 'varchar'::regtype AND col.collisdeterministic;

        -- Accept only literal membership. PostgreSQL can print lossless casts
        -- on the array or on each element after a dump/restore.
        check_shape := regexp_replace(regexp_replace(check_expression,
            $rx$::(?:character[[:space:]]+varying|text)(?:\[\])?$rx$, '', 'g'),
            '[[:space:]()]', '', 'g');
        IF check_shape IS NULL OR check_shape !~ $rx$^event_type=ANYARRAY\['[A-Z_]+'(,'[A-Z_]+')*\]$$rx$ THEN
            RAISE EXCEPTION 'Unexpected audit CHECK shape or metadata in phase %', phase;
        END IF;
        -- Extract the unmodified literals so normalization cannot hide a changed value.
        SELECT array_agg(matches[1] ORDER BY matches[1] COLLATE "C")
        INTO actual_events
        FROM regexp_matches(check_expression, $rx$'([^']*)'$rx$, 'g') AS matches;
        IF actual_events IS DISTINCT FROM expected_events THEN
            RAISE EXCEPTION 'Unexpected audit CHECK allowlist in phase %', phase;
        END IF;

        IF phase = 1 THEN
            IF EXISTS (SELECT 1 FROM public.audit_logs WHERE NOT (event_type::text = ANY (expected_legacy))) THEN
                RAISE EXCEPTION 'Existing audit data violates the legacy allowlist';
            END IF;
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
        END IF;
    END LOOP;

    IF (SELECT count(*) FROM public.audit_logs) IS DISTINCT FROM audit_count
       OR (SELECT md5(coalesce(string_agg(md5(to_jsonb(a)::text), '' ORDER BY a.id), ''))
           FROM public.audit_logs a) IS DISTINCT FROM audit_digest THEN
        RAISE EXCEPTION 'Audit rows changed during reconciliation';
    END IF;
    PERFORM pg_stat_clear_snapshot();
    IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid())
       OR EXISTS (SELECT 1 FROM pg_prepared_xacts WHERE database = current_database()) THEN
        RAISE EXCEPTION 'A concurrent session or prepared transaction was detected before commit';
    END IF;
    RAISE NOTICE 'PASS: exact legacy eight and final twelve audit events verified; all audit rows preserved';
END;
$audit$;
