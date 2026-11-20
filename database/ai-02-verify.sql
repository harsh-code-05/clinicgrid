-- Read-only verification for AI-02. The manifest runner enforces the target.
BEGIN READ ONLY;
SET LOCAL search_path TO pg_catalog;

DO $verify$
DECLARE
    source_check text;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'ai_chat_messages'
          AND column_name = 'source' AND data_type = 'character varying'
          AND character_maximum_length = 32 AND is_nullable = 'NO'
          AND column_default = '''FAQ_FALLBACK''::character varying'
    ) THEN
        RAISE EXCEPTION 'AI-02 source column type, nullability or default differs from the manifest';
    END IF;

    SELECT regexp_replace(
        regexp_replace(pg_get_expr(conbin, conrelid, true), '::(character varying|text\[\]|text)', '', 'g'),
        '[[:space:]()]', '', 'g'
    ) INTO source_check
    FROM pg_constraint
    WHERE conrelid = 'public.ai_chat_messages'::regclass AND conname = 'ck_ai_chat_messages_source'
      AND contype = 'c' AND convalidated AND conenforced;

    IF source_check IS DISTINCT FROM $expected$source=ANYARRAY['CLOUDFLARE_LLM','FAQ_FALLBACK','EMERGENCY_ESCALATION','USER']$expected$ THEN
        RAISE EXCEPTION 'AI-02 source CHECK differs from the manifest';
    END IF;
    RAISE NOTICE 'PASS: AI-02 source column and validated CHECK';
END;
$verify$;

COMMIT;
