-- Read-only post-commit assertions. Run before reopening application traffic.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path TO public, pg_catalog;
SET LOCAL statement_timeout = '30s';

SELECT current_database() AS database, current_setting('server_version') AS server_version;
DO $verify$
DECLARE
    audit_expression text;
    audit_events text[];
BEGIN
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name IN ('ai_chat_sessions','ai_chat_messages')
          AND (
              (column_name = 'id' AND is_identity = 'YES' AND identity_generation = 'BY DEFAULT')
              OR (column_name IN ('created_at','updated_at') AND column_default = 'LOCALTIMESTAMP')
              OR (table_name = 'ai_chat_messages' AND column_name = 'include_appointments' AND column_default = 'false')
          )) <> 6
       OR (SELECT count(*) FROM pg_constraint
           WHERE conrelid IN ('ai_chat_sessions'::regclass, 'ai_chat_messages'::regclass)
             AND convalidated AND contype IN ('p','u','c','f')) <> 8
       OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'ai_chat_messages'::regclass
                      AND confrelid = 'ai_chat_sessions'::regclass AND contype = 'f' AND confdeltype = 'c')
       OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'ai_chat_sessions'::regclass
                      AND confrelid = 'patients'::regclass AND contype = 'f')
       OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'ai_chat_messages'::regclass
                      AND conname = 'uk_ai_chat_message_request_role'
                      AND pg_get_constraintdef(oid) = 'UNIQUE (session_id, request_id, role)')
       OR NOT EXISTS (SELECT FROM pg_indexes WHERE schemaname = 'public'
                      AND indexname = 'ix_ai_chat_messages_session_id' AND indexdef LIKE '%(session_id, id DESC)')
       OR NOT EXISTS (SELECT FROM pg_indexes WHERE schemaname = 'public'
                      AND indexname = 'ix_ai_chat_sessions_patient_updated'
                      AND indexdef LIKE '%(patient_id, updated_at DESC, id DESC)') THEN
        RAISE EXCEPTION 'AI-01 schema assertion failed';
    END IF;
    RAISE NOTICE 'PASS: AI-01 identity, defaults, validated constraints, cascade, uniqueness and indexes';

    IF (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
        AND (table_name, column_name, udt_name) IN (
            ('doctor_availability','revision','int8'), ('appointment_slots','availability_id','int8'),
            ('appointment_slots','availability_revision','int8'), ('appointment_slots','retired','bool')
        )) <> 4
       OR NOT EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public'
                      AND table_name = 'doctor_availability' AND column_name = 'revision'
                      AND is_nullable = 'NO' AND column_default = '0')
       OR NOT EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public'
                      AND table_name = 'appointment_slots' AND column_name = 'retired'
                      AND is_nullable = 'NO' AND column_default = 'true')
       OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'appointment_slots'::regclass
                      AND confrelid = 'doctor_availability'::regclass
                      AND conname = 'fk_appointment_slot_availability' AND contype = 'f' AND convalidated)
       OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'appointment_slots'::regclass
                      AND conname = 'uk_doctor_slot_time' AND convalidated
                      AND pg_get_constraintdef(oid) = 'UNIQUE (doctor_id, date, start_time, end_time)')
       OR NOT EXISTS (SELECT FROM pg_index WHERE indexrelid = 'ix_appointment_slots_availability_revision'::regclass
                      AND indisvalid AND pg_get_expr(indpred, indrelid) = '(NOT retired)') THEN
        RAISE EXCEPTION 'SCH-02 schema assertion failed';
    END IF;
    RAISE NOTICE 'PASS: SCH-02 revision, provenance, retirement, FK, uniqueness and partial index';

    IF (SELECT count(*) FROM pg_constraint WHERE connamespace = 'public'::regnamespace
        AND convalidated AND contype = 'c' AND conname IN (
            'ck_doctors_registration_year', 'ck_doctors_verification_status', 'ck_doctors_video_fee',
            'ck_doctor_availability_mode', 'ck_appointment_slots_discovery', 'ck_appointments_discovery',
            'doctor_locations_clinic_name_check', 'doctor_locations_address_line_1_check',
            'doctor_locations_address_line_2_check', 'doctor_locations_locality_check',
            'doctor_locations_city_check', 'doctor_locations_state_check', 'doctor_locations_postal_code_check',
            'doctor_locations_contact_phone_check', 'doctor_locations_in_clinic_fee_check',
            'ck_doctor_locations_archive', 'ck_doctor_verification_actor', 'ck_doctor_verification_evidence'
        )) <> 18
       OR (SELECT count(*) FROM pg_constraint WHERE confrelid = 'doctor_locations'::regclass
           AND contype = 'f' AND convalidated AND conname IN (
               'fk_doctor_availability_location','fk_appointment_slots_location','fk_appointments_location'
           ) AND pg_get_constraintdef(oid) LIKE 'FOREIGN KEY (doctor_id, location_id)%') <> 3
       OR (SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
           WHERE c.relnamespace = 'public'::regnamespace AND i.indisvalid AND c.relname IN (
               'ix_doctor_locations_active_city', 'ix_doctor_verification_history_doctor_time',
               'ix_doctor_verification_history_actor', 'ix_doctor_availability_location',
               'ix_appointment_slots_location', 'ix_appointments_location'
           )) <> 6
       OR (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
           AND ((table_name = 'doctor_locations' AND column_name IN ('active','archived') AND column_default = 'false')
                OR (table_name = 'doctor_verification_history' AND column_name = 'occurred_at' AND column_default = 'CURRENT_TIMESTAMP')
                OR (table_name = 'doctor_availability' AND column_name = 'consultation_mode' AND column_default LIKE '%IN_CLINIC%')
                OR (table_name = 'doctors' AND column_name = 'verification_status' AND column_default LIKE '%UNVERIFIED%'))) <> 5
       OR NOT EXISTS (SELECT FROM pg_trigger WHERE tgrelid = 'appointments'::regclass
                      AND tgname = 'trg_appointments_immutable_offer_snapshot' AND tgenabled = 'O'
                      AND tgfoid = to_regprocedure('public.reject_appointment_offer_snapshot_change()')) THEN
        RAISE EXCEPTION 'DISCO-01 schema assertion failed';
    END IF;
    RAISE NOTICE 'PASS: DISCO-01 defaults, eighteen checks, ownership FKs, six indexes and enabled snapshot guard';

    SELECT pg_get_expr(conbin, conrelid, true) INTO audit_expression
    FROM pg_constraint WHERE conrelid = 'audit_logs'::regclass AND conname = 'audit_logs_event_type_check'
      AND contype = 'c' AND convalidated;
    SELECT array_agg(matches[1] ORDER BY matches[1] COLLATE "C") INTO audit_events
    FROM regexp_matches(audit_expression, $rx$'([^']*)'$rx$, 'g') AS matches;
    IF audit_events IS DISTINCT FROM ARRAY[
        'ADMIN_CANCELLED_APPOINTMENT', 'ADMIN_COMPLETED_APPOINTMENT', 'ADMIN_CREATED_DOCTOR',
        'ADMIN_UPDATED_DOCTOR', 'DOCTOR_CANCELLED_APPOINTMENT', 'DOCTOR_COMPLETED_APPOINTMENT',
        'DOCTOR_CREATED_AVAILABILITY', 'DOCTOR_UPDATED_AVAILABILITY', 'LOGIN_FAILURE',
        'LOGIN_SUCCESS', 'PATIENT_BOOKED_APPOINTMENT', 'PATIENT_CANCELLED_APPOINTMENT'
    ] OR regexp_replace(regexp_replace(audit_expression,
        $rx$::(?:character[[:space:]]+varying|text)(?:\[\])?$rx$, '', 'g'),
        '[[:space:]()]', '', 'g') !~ $rx$^event_type=ANYARRAY\['[A-Z_]+'(,'[A-Z_]+')*\]$$rx$
       OR EXISTS (SELECT FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND NOT convalidated) THEN
        RAISE EXCEPTION 'Audit allowlist or constraint validation assertion failed';
    END IF;
    RAISE NOTICE 'PASS: exact twelve audit events and validated public constraints';

    IF (SELECT count(*) FROM appointment_slots) <> 62
       OR (SELECT count(*) FROM appointment_slots WHERE status = 'AVAILABLE' AND retired) <> 51
       OR (SELECT count(*) FROM appointment_slots WHERE status = 'BOOKED' AND retired) <> 11
       OR EXISTS (SELECT FROM appointment_slots WHERE availability_id IS NOT NULL OR availability_revision IS NOT NULL
                  OR location_id IS NOT NULL OR consultation_mode IS NOT NULL OR fee IS NOT NULL OR offer_version IS NOT NULL)
       OR (SELECT count(*) FROM doctors) <> 4
       OR EXISTS (SELECT FROM doctors WHERE verification_status IS DISTINCT FROM 'UNVERIFIED')
       OR (SELECT count(*) FROM doctor_availability) <> 15
       OR EXISTS (SELECT FROM doctor_availability WHERE revision IS DISTINCT FROM 0
                  OR consultation_mode IS DISTINCT FROM 'IN_CLINIC' OR location_id IS NOT NULL)
       OR (SELECT count(*) FROM appointments) <> 13
       OR EXISTS (SELECT FROM appointments a LEFT JOIN appointment_slots s ON s.id = a.slot_id
                  WHERE s.id IS NULL OR a.doctor_id IS DISTINCT FROM s.doctor_id
                     OR a.location_id IS NOT NULL OR a.consultation_mode IS NOT NULL
                     OR a.clinic_name_snapshot IS NOT NULL OR a.clinic_address_snapshot IS NOT NULL
                     OR a.consultation_fee IS NOT NULL OR a.offer_version IS NOT NULL)
       OR EXISTS (SELECT FROM appointment_slots s WHERE s.status = 'BOOKED'
                  AND NOT EXISTS (SELECT FROM appointments a WHERE a.slot_id = s.id)) THEN
        RAISE EXCEPTION 'Legacy scheduling impact or appointment references differ from the reviewed baseline';
    END IF;
    RAISE NOTICE 'PASS: 51 AVAILABLE and 11 BOOKED slots retired, 4 UNVERIFIED doctors, 15 availability rows and 13 preserved appointments';
END;
$verify$;
COMMIT;
