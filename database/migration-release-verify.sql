-- Read-only structural checks for the full fresh-database manifest.
BEGIN READ ONLY;
SET LOCAL search_path TO pg_catalog, public;

DO $verify$
DECLARE
    expected record;
    public_tables text[];
BEGIN
    SELECT array_agg(relname::text ORDER BY relname::text COLLATE "C") INTO public_tables
    FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r', 'p');
    IF public_tables IS DISTINCT FROM ARRAY[
        'ai_chat_messages', 'ai_chat_sessions', 'appointment_slots', 'appointments', 'audit_logs',
        'bill_items', 'bills', 'doctor_applications', 'doctor_availability', 'doctor_locations',
        'doctor_verification_document_events', 'doctor_verification_documents', 'doctor_verification_history',
        'doctors', 'patients', 'payment_records', 'specializations', 'users'
    ] THEN
        RAISE EXCEPTION 'Unexpected public tables for the HMS migration manifest';
    END IF;

    FOR expected IN SELECT * FROM (VALUES
        ('doctor_availability', 'revision', 'bigint', true),
        ('appointment_slots', 'availability_id', 'bigint', false),
        ('appointment_slots', 'availability_revision', 'bigint', false),
        ('appointment_slots', 'retired', 'boolean', true),
        ('doctor_availability', 'consultation_mode', 'character varying(16)', true),
        ('doctor_locations', 'lgd_district_code', 'integer', false),
        ('doctor_verification_history', 'evidence_metadata', 'jsonb', false),
        ('doctor_verification_documents', 'id', 'uuid', true),
        ('doctor_verification_documents', 'storage_key', 'character varying(128)', true),
        ('doctor_verification_documents', 'purge_deadline', 'timestamp with time zone', false),
        ('doctor_verification_document_events', 'document_id', 'uuid', false),
        ('doctors', 'verification_status', 'character varying(16)', true),
        ('appointments', 'offer_version', 'uuid', false),
        ('appointments', 'consultation_fee', 'numeric(12,2)', false),
        ('ai_chat_messages', 'request_id', 'uuid', true),
        ('ai_chat_messages', 'include_appointments', 'boolean', true)
    ) AS columns(table_name, column_name, column_type, required)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = to_regclass(format('public.%I', expected.table_name))
              AND attname = expected.column_name AND NOT attisdropped
              AND format_type(atttypid, atttypmod) = expected.column_type AND attnotnull = expected.required
        ) THEN
            RAISE EXCEPTION 'Unexpected release column %.%', expected.table_name, expected.column_name;
        END IF;
    END LOOP;

    FOR expected IN SELECT * FROM (VALUES
        ('appointment_slots', 'fk_appointment_slot_availability', 'f'),
        ('appointment_slots', 'uk_doctor_slot_time', 'u'),
        ('appointment_slots', 'fk_appointment_slots_location', 'f'),
        ('doctor_availability', 'fk_doctor_availability_location', 'f'),
        ('appointments', 'fk_appointments_location', 'f'),
        ('doctor_locations', 'ck_doctor_locations_lgd_district_code', 'c'),
        ('doctor_verification_document_events', 'ck_doctor_verification_document_events_actor', 'c'),
        ('ai_chat_messages', 'uk_ai_chat_message_request_role', 'u')
    ) AS constraints(table_name, constraint_name, kind)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conrelid = to_regclass(format('public.%I', expected.table_name))
              AND conname = expected.constraint_name AND contype::text = expected.kind AND convalidated AND conenforced
        ) THEN
            RAISE EXCEPTION 'Unexpected release constraint % on %', expected.constraint_name, expected.table_name;
        END IF;
    END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.ai_chat_messages'::regclass
          AND confrelid = 'public.ai_chat_sessions'::regclass
          AND contype = 'f' AND confdeltype = 'c' AND convalidated AND conenforced
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_index WHERE indexrelid = 'public.ix_appointment_slots_availability_revision'::regclass
          AND indrelid = 'public.appointment_slots'::regclass AND indisvalid AND indisready
          AND pg_get_expr(indpred, indrelid, true) = 'NOT retired'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_index WHERE indexrelid = 'public.uk_doctor_verification_documents_active'::regclass
          AND indrelid = 'public.doctor_verification_documents'::regclass
          AND indisvalid AND indisready AND indisunique
          AND pg_get_expr(indpred, indrelid, true) = 'retired_at IS NULL'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.appointments'::regclass AND NOT tgisinternal
          AND tgname = 'trg_appointments_immutable_offer_snapshot' AND tgenabled = 'O'
          AND tgfoid = 'public.reject_appointment_offer_snapshot_change()'::regprocedure
    ) THEN
        RAISE EXCEPTION 'Required release cascade, indexes or immutable offer trigger is missing';
    END IF;
    RAISE NOTICE 'PASS: 18 public tables and core scheduling, verification and chat invariants';
END;
$verify$;

COMMIT;
