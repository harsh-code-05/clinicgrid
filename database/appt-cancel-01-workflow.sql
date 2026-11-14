-- APPT-CANCEL-01: Appointment Cancellation Workflow schema hardening & metadata persistence.
-- Standalone manual apply-once migration for PostgreSQL 18.1+.
-- Do not run against an already migrated database.

BEGIN;
SET LOCAL search_path TO public, pg_catalog;
SET LOCAL lock_timeout = '5s';

DO $preflight$
DECLARE
    dup_confirmed_slot_count bigint;
    unbooked_confirmed_count bigint;
    overlapping_confirmed_count bigint;
BEGIN
    IF current_setting('server_version_num')::integer < 180001 THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 requires PostgreSQL 18.1 or newer (found %)', current_setting('server_version');
    END IF;

    IF to_regclass('public.appointments') IS NULL
       OR to_regclass('public.appointment_slots') IS NULL
       OR to_regclass('public.users') IS NULL THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 requires public.appointments, public.appointment_slots, and public.users';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'appointments'
          AND column_name IN (
              'cancellation_reason',
              'cancelled_at',
              'cancelled_by_user_id',
              'cancelled_by_role',
              'cancellation_slot_outcome'
          )
    ) OR to_regclass('public.uq_appointments_confirmed_slot') IS NOT NULL
      OR to_regclass('public.ix_appointments_doctor_cancelled_at') IS NOT NULL THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 appears to already be applied to public.appointments';
    END IF;

    SELECT count(*)
    INTO dup_confirmed_slot_count
    FROM (
        SELECT slot_id
        FROM public.appointments
        WHERE status = 'CONFIRMED'
        GROUP BY slot_id
        HAVING count(*) > 1
    ) d;
    IF dup_confirmed_slot_count > 0 THEN
        RAISE EXCEPTION 'Cannot install uq_appointments_confirmed_slot: % slot(s) already have multiple CONFIRMED appointments', dup_confirmed_slot_count;
    END IF;

    SELECT count(*)
    INTO unbooked_confirmed_count
    FROM public.appointments a
    JOIN public.appointment_slots s ON s.id = a.slot_id
    WHERE a.status = 'CONFIRMED'
      AND s.status IS DISTINCT FROM 'BOOKED';
    IF unbooked_confirmed_count > 0 THEN
        RAISE EXCEPTION 'Preflight failed: % CONFIRMED appointment(s) reference a slot whose status is not BOOKED', unbooked_confirmed_count;
    END IF;

    SELECT count(*)
    INTO overlapping_confirmed_count
    FROM public.appointments a1
    JOIN public.appointment_slots s1 ON s1.id = a1.slot_id
    JOIN public.appointments a2 ON a2.doctor_id = a1.doctor_id AND a2.id > a1.id
    JOIN public.appointment_slots s2 ON s2.id = a2.slot_id
    WHERE a1.status = 'CONFIRMED'
      AND a2.status = 'CONFIRMED'
      AND s1.date = s2.date
      AND s1.start_time < s2.end_time
      AND s1.end_time > s2.start_time;
    IF overlapping_confirmed_count > 0 THEN
        RAISE EXCEPTION 'Preflight failed: % overlapping CONFIRMED appointment pair(s) detected', overlapping_confirmed_count;
    END IF;
END;
$preflight$;

ALTER TABLE public.appointments
    ADD COLUMN cancellation_reason varchar(500),
    ADD COLUMN cancelled_at timestamp with time zone,
    ADD COLUMN cancelled_by_user_id bigint,
    ADD COLUMN cancelled_by_role varchar(16),
    ADD COLUMN cancellation_slot_outcome varchar(32),
    ADD CONSTRAINT fk_appointments_cancelled_by_user
        FOREIGN KEY (cancelled_by_user_id) REFERENCES public.users (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_appointments_cancellation_reason_trimmed CHECK (
        cancellation_reason IS NULL
        OR (
            length(cancellation_reason) BETWEEN 1 AND 500
            AND btrim(
                cancellation_reason,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000\FEFF'
            ) <> ''
            AND cancellation_reason = btrim(
                cancellation_reason,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000\FEFF'
            )
        )
    ),
    ADD CONSTRAINT ck_appointments_cancelled_by_role CHECK (
        cancelled_by_role IS NULL
        OR cancelled_by_role IN ('PATIENT', 'DOCTOR', 'ADMIN')
    ),
    ADD CONSTRAINT ck_appointments_cancellation_slot_outcome CHECK (
        cancellation_slot_outcome IS NULL
        OR cancellation_slot_outcome IN ('RELEASED', 'WITHHELD', 'RETAINED_UNAVAILABLE', 'OVERLAP')
    ),
    ADD CONSTRAINT ck_appointments_cancellation_metadata_consistency CHECK (
        (
            status <> 'CANCELLED'
            AND cancellation_reason IS NULL
            AND cancelled_at IS NULL
            AND cancelled_by_user_id IS NULL
            AND cancelled_by_role IS NULL
            AND cancellation_slot_outcome IS NULL
        )
        OR (
            status = 'CANCELLED'
            AND (
                (
                    cancellation_reason IS NULL
                    AND cancelled_at IS NULL
                    AND cancelled_by_user_id IS NULL
                    AND cancelled_by_role IS NULL
                    AND cancellation_slot_outcome IS NULL
                )
                OR (
                    cancelled_at IS NOT NULL
                    AND cancelled_by_role IS NOT NULL
                    AND cancellation_slot_outcome IS NOT NULL
                )
            )
        )
    );

CREATE UNIQUE INDEX uq_appointments_confirmed_slot
    ON public.appointments (slot_id)
    WHERE status = 'CONFIRMED';

CREATE INDEX ix_appointments_doctor_cancelled_at
    ON public.appointments (doctor_id, cancelled_at DESC, id DESC)
    WHERE status = 'CANCELLED' AND cancelled_at IS NOT NULL;

COMMIT;
