-- SCH-02 + DISCO-01 reconciliation CANDIDATE. Disposable validation only.
-- Baseline: dd31c10, PostgreSQL 18.1, audited 2026-11-06.
-- This is NOT an idempotent migration and is NOT authorized for hospital_management.
-- Invoke with psql -X -w -v ON_ERROR_STOP=1
--   -v reconciliation_database=<session-created hms_reconcile_validation_...> -f this-file
-- Existing fields/FKs/PKs/enum checks remain in place. AI-01 is never altered.
-- Impact: all 62 legacy slots become retired; all 4 doctors become UNVERIFIED.
-- No provenance, clinic, fee, offer token or verification history is fabricated.
\if :{?reconciliation_database}
\else
\echo 'Missing explicit reconciliation_database; refusing to run'
\quit 3
\endif

BEGIN;
SET LOCAL search_path TO public, pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SELECT set_config('hms.reconciliation_database', :'reconciliation_database', true);

DO $preflight$
DECLARE
    catalog_state jsonb;
    rows_state jsonb := '{}';
    sequences_state jsonb := '{}';
    item jsonb;
    r record;
BEGIN
    IF current_database() <> current_setting('hms.reconciliation_database')
       OR current_database() !~ '^hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}$'
       OR inet_server_port() <> 5433
       OR current_setting('server_version_num')::integer <> 180001 THEN
        RAISE EXCEPTION 'Wrong target or PostgreSQL version; disposable PostgreSQL 18.1 only';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()) THEN
        RAISE EXCEPTION 'Other database sessions exist; require isolated validation target';
    END IF;
    LOCK TABLE public.doctors, public.doctor_availability, public.appointment_slots,
        public.appointments, public.doctor_locations, public.doctor_verification_history
        IN ACCESS EXCLUSIVE MODE;

    SELECT jsonb_build_object(
 'columns',(SELECT jsonb_agg(jsonb_build_object('table',table_name,'column',column_name,'type',udt_name,'length',character_maximum_length,'precision',numeric_precision,'scale',numeric_scale,'time_precision',datetime_precision,'nullable',is_nullable,'default',column_default,'identity',is_identity) ORDER BY table_name,ordinal_position) FROM information_schema.columns WHERE table_schema='public'),
 'constraints',(SELECT jsonb_agg(jsonb_build_object('table',t.relname,'name',c.conname,'kind',c.contype,'validated',c.convalidated,'definition',pg_get_constraintdef(c.oid,true)) ORDER BY t.relname,c.conname) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public'),
 'indexes',(SELECT jsonb_agg(jsonb_build_object('table',tablename,'name',indexname,'definition',indexdef) ORDER BY tablename,indexname) FROM pg_indexes WHERE schemaname='public'),
 'triggers',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'name',t.tgname,'definition',pg_get_triggerdef(t.oid,true)) ORDER BY c.relname,t.tgname),'[]'::jsonb) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT t.tgisinternal),
 'functions',(SELECT coalesce(jsonb_agg(jsonb_build_object('name',p.proname,'definition',pg_get_functiondef(p.oid))),'[]'::jsonb) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prokind='f')
) INTO catalog_state;
    -- Exact catalog fingerprint includes all public column types/type modifiers,
    -- nullability/defaults/identities, validated constraints, indexes, functions and triggers.
    -- It was computed read-only from the audited database and must match the restore.
    -- pg_dump/restore redistributes varchar-to-text casts in eleven existing
    -- enum checks (array cast versus identical element casts). A full object diff
    -- confirmed that these are the ONLY restored-schema differences. Accept only
    -- these two independently reviewed, exact catalogs; do not normalize drift.
    IF md5(catalog_state::text) NOT IN (
        '3f30aea3e9ef0f23ea733d5ff4541d2d', -- audited live catalog
        '501b38c152b5c89944ec1fea0bbe0c66'  -- verified fresh restore
    ) THEN
        RAISE EXCEPTION 'Schema differs from audited baseline; stop and re-audit';
    END IF;
    IF (SELECT count(*) FROM public.doctors) <> 4
       OR (SELECT count(*) FROM public.doctor_availability) <> 15
       OR (SELECT count(*) FROM public.appointment_slots) <> 62
       OR (SELECT count(*) FROM public.appointments) <> 13
       OR EXISTS (SELECT 1 FROM public.doctor_locations)
       OR EXISTS (SELECT 1 FROM public.doctor_verification_history) THEN
        RAISE EXCEPTION 'Business-data baseline changed; stop and reassess impact';
    END IF;
    IF EXISTS (SELECT 1 FROM public.appointment_slots
               WHERE availability_id IS NOT NULL OR availability_revision IS NOT NULL
                  OR location_id IS NOT NULL OR consultation_mode IS NOT NULL
                  OR fee IS NOT NULL OR offer_version IS NOT NULL)
       OR EXISTS (SELECT 1 FROM public.appointments
               WHERE location_id IS NOT NULL OR consultation_mode IS NOT NULL
                  OR clinic_name_snapshot IS NOT NULL OR clinic_address_snapshot IS NOT NULL
                  OR consultation_fee IS NOT NULL OR offer_version IS NOT NULL)
       OR EXISTS (SELECT 1 FROM public.doctors WHERE
               NOT (registration_year BETWEEN 1000 AND 9999)
               OR NOT (video_consultation_fee BETWEEN 0 AND 9999999999.99)) THEN
        RAISE EXCEPTION 'Data fails audited legacy/constraint preconditions; no data repair permitted';
    END IF;

    -- Fingerprint every original row projection, without exposing patient data.
    -- New defaulted fields are excluded; all pre-existing fields remain protected.
    FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename LOOP
        EXECUTE format(
            'SELECT jsonb_build_object(''rows'', count(*), ''digest'', md5(coalesce(string_agg(md5((to_jsonb(t) - CASE %L WHEN ''doctors'' THEN ARRAY[''verification_status''] WHEN ''doctor_availability'' THEN ARRAY[''revision'',''consultation_mode''] WHEN ''appointment_slots'' THEN ARRAY[''retired''] ELSE ARRAY[]::text[] END)::text), '''' ORDER BY t.id), ''''))) FROM public.%I t',
            r.tablename, r.tablename) INTO item;
        rows_state := rows_state || jsonb_build_object(r.tablename, item);
    END LOOP;
    FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' ORDER BY sequencename LOOP
        EXECUTE format('SELECT jsonb_build_object(''last_value'',last_value,''is_called'',is_called) FROM public.%I',r.sequencename) INTO item;
        sequences_state := sequences_state || jsonb_build_object(r.sequencename,item);
    END LOOP;
    PERFORM set_config('hms.reconcile_catalog_before',catalog_state::text,true);
    PERFORM set_config('hms.reconcile_rows_before',rows_state::text,true);
    PERFORM set_config('hms.reconcile_sequences_before',sequences_state::text,true);
END;
$preflight$;

-- Only the four missing mapped columns. Legacy defaults are authoritative.
ALTER TABLE public.doctor_availability
    ADD COLUMN revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN consultation_mode varchar(16) NOT NULL DEFAULT 'IN_CLINIC';
ALTER TABLE public.appointment_slots
    ADD COLUMN retired boolean NOT NULL DEFAULT true;
ALTER TABLE public.doctors
    ADD COLUMN verification_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED';

-- Existing non-null columns: install only their missing migration defaults.
ALTER TABLE public.doctor_locations
    ALTER COLUMN active SET DEFAULT false,
    ALTER COLUMN archived SET DEFAULT false;
ALTER TABLE public.doctor_verification_history
    ALTER COLUMN occurred_at SET DEFAULT CURRENT_TIMESTAMP;

-- Composite ownership is intentionally enforced by SQL, not a single-column JPA FK.
ALTER TABLE public.doctor_availability
    ADD CONSTRAINT fk_doctor_availability_location FOREIGN KEY (doctor_id, location_id)
        REFERENCES public.doctor_locations (doctor_id, id);
ALTER TABLE public.appointment_slots
    ADD CONSTRAINT fk_appointment_slots_location FOREIGN KEY (doctor_id, location_id)
        REFERENCES public.doctor_locations (doctor_id, id);
ALTER TABLE public.appointments
    ADD CONSTRAINT fk_appointments_location FOREIGN KEY (doctor_id, location_id)
        REFERENCES public.doctor_locations (doctor_id, id);

-- Predicates copied from authoritative DISCO-01. ADD CHECK validates existing rows.
ALTER TABLE public.doctors
    ADD CONSTRAINT ck_doctors_registration_year CHECK (
        registration_year BETWEEN 1000 AND 9999
    );

ALTER TABLE public.doctors
    ADD CONSTRAINT ck_doctors_verification_status CHECK (
        verification_status IN ('UNVERIFIED', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED')
    );

ALTER TABLE public.doctors
    ADD CONSTRAINT ck_doctors_video_fee CHECK (
        video_consultation_fee BETWEEN 0 AND 9999999999.99
    );

ALTER TABLE public.doctor_availability
    ADD CONSTRAINT ck_doctor_availability_mode CHECK (
        consultation_mode = 'IN_CLINIC'
                OR (consultation_mode = 'VIDEO' AND location_id IS NULL)
    );

ALTER TABLE public.appointment_slots
    ADD CONSTRAINT ck_appointment_slots_discovery CHECK (
        (
                    location_id IS NULL AND consultation_mode IS NULL
                    AND fee IS NULL AND offer_version IS NULL
                )
                OR (
                    consultation_mode IS NOT NULL AND fee IS NOT NULL AND offer_version IS NOT NULL
                    AND fee BETWEEN 0 AND 9999999999.99
                    AND (
                        (consultation_mode = 'IN_CLINIC' AND location_id IS NOT NULL)
                        OR (consultation_mode = 'VIDEO' AND location_id IS NULL)
                    )
                )
    );

ALTER TABLE public.appointments
    ADD CONSTRAINT ck_appointments_discovery CHECK (
        (
                    location_id IS NULL AND consultation_mode IS NULL
                    AND clinic_name_snapshot IS NULL AND clinic_address_snapshot IS NULL
                    AND consultation_fee IS NULL AND offer_version IS NULL
                )
                OR (
                    consultation_mode IS NOT NULL
                    AND consultation_fee IS NOT NULL AND offer_version IS NOT NULL
                    AND consultation_fee BETWEEN 0 AND 9999999999.99
                    AND (
                        (
                            consultation_mode = 'IN_CLINIC' AND location_id IS NOT NULL
                            AND clinic_name_snapshot IS NOT NULL AND btrim(clinic_name_snapshot) <> ''
                            AND clinic_address_snapshot IS NOT NULL AND btrim(clinic_address_snapshot) <> ''
                        )
                        OR (
                            consultation_mode = 'VIDEO' AND location_id IS NULL
                            AND clinic_name_snapshot IS NULL AND clinic_address_snapshot IS NULL
                        )
                    )
                )
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_clinic_name_check CHECK (
        btrim(clinic_name) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_address_line_1_check CHECK (
        btrim(address_line_1) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_address_line_2_check CHECK (
        btrim(address_line_2) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_locality_check CHECK (
        btrim(locality) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_city_check CHECK (
        btrim(city) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_state_check CHECK (
        btrim(state) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_postal_code_check CHECK (
        btrim(postal_code) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_contact_phone_check CHECK (
        btrim(contact_phone) <> ''
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT doctor_locations_in_clinic_fee_check CHECK (
        in_clinic_fee BETWEEN 0 AND 9999999999.99
    );

ALTER TABLE public.doctor_locations
    ADD CONSTRAINT ck_doctor_locations_archive CHECK (
        NOT (active AND archived)
    );

ALTER TABLE public.doctor_verification_history
    ADD CONSTRAINT ck_doctor_verification_actor CHECK (
        (actor_role = 'SYSTEM' AND actor_user_id IS NULL)
                OR (actor_role IN ('DOCTOR', 'ADMIN') AND actor_user_id IS NOT NULL)
    );

ALTER TABLE public.doctor_verification_history
    ADD CONSTRAINT ck_doctor_verification_evidence CHECK (
        evidence_metadata IS NULL OR jsonb_typeof(evidence_metadata) = 'object'
    );

-- All seven missing indexes, including partial predicates and DESC ordering.
CREATE INDEX ix_appointment_slots_availability_revision
    ON public.appointment_slots (availability_id, availability_revision) WHERE NOT retired;

CREATE INDEX ix_doctor_locations_active_city
    ON public.doctor_locations (lower(city), doctor_id)
    WHERE active AND NOT archived;

CREATE INDEX ix_doctor_verification_history_doctor_time
    ON public.doctor_verification_history (doctor_id, occurred_at DESC, id DESC);

CREATE INDEX ix_doctor_verification_history_actor
    ON public.doctor_verification_history (actor_user_id)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX ix_doctor_availability_location
    ON public.doctor_availability (doctor_id, location_id) WHERE location_id IS NOT NULL;

CREATE INDEX ix_appointment_slots_location
    ON public.appointment_slots (doctor_id, location_id) WHERE location_id IS NOT NULL;

CREATE INDEX ix_appointments_location
    ON public.appointments (doctor_id, location_id) WHERE location_id IS NOT NULL;

-- Exact authoritative snapshot guard; no appointment values are changed.
CREATE FUNCTION public.reject_appointment_offer_snapshot_change()
RETURNS trigger
LANGUAGE plpgsql
AS $fn$
BEGIN
    IF ROW(
        NEW.location_id, NEW.consultation_mode, NEW.clinic_name_snapshot,
        NEW.clinic_address_snapshot, NEW.consultation_fee, NEW.offer_version
    ) IS DISTINCT FROM ROW(
        OLD.location_id, OLD.consultation_mode, OLD.clinic_name_snapshot,
        OLD.clinic_address_snapshot, OLD.consultation_fee, OLD.offer_version
    ) THEN
        RAISE EXCEPTION 'Appointment offer snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$fn$;

CREATE TRIGGER trg_appointments_immutable_offer_snapshot
    BEFORE UPDATE OF location_id, consultation_mode, clinic_name_snapshot,
        clinic_address_snapshot, consultation_fee, offer_version
    ON public.appointments
    FOR EACH ROW
    EXECUTE FUNCTION public.reject_appointment_offer_snapshot_change();

DO $verify$
DECLARE
    catalog_state jsonb;
    before_catalog jsonb := current_setting('hms.reconcile_catalog_before')::jsonb;
    rows_state jsonb := '{}';
    sequences_state jsonb := '{}';
    item jsonb;
    r record;
BEGIN

    -- Fingerprint every original row projection, without exposing patient data.
    -- New defaulted fields are excluded; all pre-existing fields remain protected.
    FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename LOOP
        EXECUTE format(
            'SELECT jsonb_build_object(''rows'', count(*), ''digest'', md5(coalesce(string_agg(md5((to_jsonb(t) - CASE %L WHEN ''doctors'' THEN ARRAY[''verification_status''] WHEN ''doctor_availability'' THEN ARRAY[''revision'',''consultation_mode''] WHEN ''appointment_slots'' THEN ARRAY[''retired''] ELSE ARRAY[]::text[] END)::text), '''' ORDER BY t.id), ''''))) FROM public.%I t',
            r.tablename, r.tablename) INTO item;
        rows_state := rows_state || jsonb_build_object(r.tablename, item);
    END LOOP;
    FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' ORDER BY sequencename LOOP
        EXECUTE format('SELECT jsonb_build_object(''last_value'',last_value,''is_called'',is_called) FROM public.%I',r.sequencename) INTO item;
        sequences_state := sequences_state || jsonb_build_object(r.sequencename,item);
    END LOOP;
    IF rows_state IS DISTINCT FROM current_setting('hms.reconcile_rows_before')::jsonb THEN
        RAISE EXCEPTION 'Existing row values or counts changed; rolling back';
    END IF;
    IF sequences_state IS DISTINCT FROM current_setting('hms.reconcile_sequences_before')::jsonb THEN
        RAISE EXCEPTION 'Identity sequence state changed; rolling back';
    END IF;
    IF EXISTS (SELECT 1 FROM public.appointment_slots WHERE retired IS DISTINCT FROM true)
       OR EXISTS (SELECT 1 FROM public.doctor_availability
                  WHERE revision IS DISTINCT FROM 0 OR consultation_mode IS DISTINCT FROM 'IN_CLINIC')
       OR EXISTS (SELECT 1 FROM public.doctors WHERE verification_status IS DISTINCT FROM 'UNVERIFIED') THEN
        RAISE EXCEPTION 'Authoritative new-column defaults were not preserved';
    END IF;
    SELECT jsonb_build_object(
 'columns',(SELECT jsonb_agg(jsonb_build_object('table',table_name,'column',column_name,'type',udt_name,'length',character_maximum_length,'precision',numeric_precision,'scale',numeric_scale,'time_precision',datetime_precision,'nullable',is_nullable,'default',column_default,'identity',is_identity) ORDER BY table_name,ordinal_position) FROM information_schema.columns WHERE table_schema='public'),
 'constraints',(SELECT jsonb_agg(jsonb_build_object('table',t.relname,'name',c.conname,'kind',c.contype,'validated',c.convalidated,'definition',pg_get_constraintdef(c.oid,true)) ORDER BY t.relname,c.conname) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public'),
 'indexes',(SELECT jsonb_agg(jsonb_build_object('table',tablename,'name',indexname,'definition',indexdef) ORDER BY tablename,indexname) FROM pg_indexes WHERE schemaname='public'),
 'triggers',(SELECT coalesce(jsonb_agg(jsonb_build_object('table',c.relname,'name',t.tgname,'definition',pg_get_triggerdef(t.oid,true)) ORDER BY c.relname,t.tgname),'[]'::jsonb) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT t.tgisinternal),
 'functions',(SELECT coalesce(jsonb_agg(jsonb_build_object('name',p.proname,'definition',pg_get_functiondef(p.oid))),'[]'::jsonb) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prokind='f')
) INTO catalog_state;

    -- Every previously existing constraint and index must remain unchanged.
    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(before_catalog->'constraints') e
        WHERE NOT (catalog_state->'constraints') @> jsonb_build_array(e)
    ) OR EXISTS (
        SELECT 1 FROM jsonb_array_elements(before_catalog->'indexes') e
        WHERE NOT (catalog_state->'indexes') @> jsonb_build_array(e)
    ) THEN
        RAISE EXCEPTION 'Pre-existing constraints/indexes changed';
    END IF;
    -- All original columns retain their exact type, identity and nullability;
    -- only the three explicitly approved existing defaults may change.
    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(before_catalog->'columns') old_col
        WHERE NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(catalog_state->'columns') new_col
            WHERE new_col->>'table'=old_col->>'table'
              AND new_col->>'column'=old_col->>'column'
              AND CASE
                    WHEN (old_col->>'table',old_col->>'column') IN
                         (('doctor_locations','active'),('doctor_locations','archived'),
                          ('doctor_verification_history','occurred_at'))
                    THEN new_col-'default'=old_col-'default'
                    ELSE new_col=old_col
                  END
        )
    ) THEN
        RAISE EXCEPTION 'Pre-existing column mappings changed unexpectedly';
    END IF;
    IF jsonb_array_length(catalog_state->'columns') <> jsonb_array_length(before_catalog->'columns')+4
       OR jsonb_array_length(catalog_state->'constraints') <> jsonb_array_length(before_catalog->'constraints')+25
       OR jsonb_array_length(catalog_state->'indexes') <> jsonb_array_length(before_catalog->'indexes')+7
       OR jsonb_array_length(catalog_state->'functions') <> 1
       OR jsonb_array_length(catalog_state->'triggers') <> 1 THEN
        RAISE EXCEPTION 'Unexpected schema object delta';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE connamespace='public'::regnamespace AND NOT convalidated) THEN
        RAISE EXCEPTION 'An unvalidated constraint remains';
    END IF;
    RAISE NOTICE 'PASS: four columns, seven defaults, eighteen checks, three ownership FKs, seven indexes and snapshot guard';
    RAISE NOTICE 'PASS: all original rows, appointment/slot references, AI-01 objects and identity sequence states unchanged';
END;
$verify$;
COMMIT;
