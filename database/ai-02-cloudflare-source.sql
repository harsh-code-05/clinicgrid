-- AI-02: Persist the source of user and assistant messages. Apply through the migration runner.
BEGIN;
SET LOCAL search_path TO public, pg_catalog;
SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF strpos(lower(current_database()), 'hospital_management') > 0
       OR current_setting('hms.migration_target', true) IS DISTINCT FROM current_database() THEN
        RAISE EXCEPTION 'AI-02 requires the verified HMS migration runner target';
    END IF;
END $$;

ALTER TABLE public.ai_chat_messages
    ADD COLUMN source varchar(32);

UPDATE public.ai_chat_messages
SET source = CASE WHEN role = 'USER' THEN 'USER' ELSE 'FAQ_FALLBACK' END;

ALTER TABLE public.ai_chat_messages
    ALTER COLUMN source SET DEFAULT 'FAQ_FALLBACK',
    ALTER COLUMN source SET NOT NULL,
    ADD CONSTRAINT ck_ai_chat_messages_source CHECK (source::text = ANY (
        ARRAY['CLOUDFLARE_LLM', 'FAQ_FALLBACK', 'EMERGENCY_ESCALATION', 'USER']::text[]
    ));

COMMIT;
