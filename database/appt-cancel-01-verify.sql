-- Read-only post-commit verification for APPT-CANCEL-01.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path TO public, pg_catalog;
SET LOCAL statement_timeout = '30s';

SELECT current_database() AS database, current_setting('server_version') AS server_version;

DO $verify$
DECLARE
    expected_ws constant text := U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000\FEFF';
    expected_ws_lit text;
    col_count integer;
    fk_def text;
    reason_ck_def text;
    norm_reason_ck text;
    role_ck_def text;
    norm_role_ck text;
    outcome_ck_def text;
    norm_outcome_ck text;
    consistency_ck_def text;
    norm_consistency_ck text;
    unique_idx_cols name[];
    unique_idx_pred text;
    norm_unique_pred text;
    cancelled_idx_cols name[];
    cancelled_col0_desc boolean;
    cancelled_col1_desc boolean;
    cancelled_col2_desc boolean;
    cancelled_idx_pred text;
    norm_cancelled_pred text;
    raw_exprs text[];
    norm_exprs text[];
    src text;
    out_buf text;
    src_len integer;
    pos integer;
    ch text;
    qch text;
    is_escape_str boolean;
    cast_match text;
    dup_confirmed_slot_count bigint;
    unbooked_confirmed_count bigint;
BEGIN
    IF current_setting('server_version_num')::integer < 180001 THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 verification requires PostgreSQL 18.1 or newer (found %)', current_setting('server_version');
    END IF;

    expected_ws_lit := '''' || expected_ws || '''';

    SELECT count(*)
    INTO col_count
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'appointments'
      AND (
          (column_name = 'cancellation_reason' AND data_type = 'character varying' AND character_maximum_length = 500 AND is_nullable = 'YES')
          OR (column_name = 'cancelled_at' AND data_type = 'timestamp with time zone' AND is_nullable = 'YES')
          OR (column_name = 'cancelled_by_user_id' AND udt_name = 'int8' AND is_nullable = 'YES')
          OR (column_name = 'cancelled_by_role' AND data_type = 'character varying' AND character_maximum_length = 16 AND is_nullable = 'YES')
          OR (column_name = 'cancellation_slot_outcome' AND data_type = 'character varying' AND character_maximum_length = 32 AND is_nullable = 'YES')
      );

    IF col_count <> 5 THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 column assertion failed (matched % of 5 columns)', col_count;
    END IF;

    SELECT pg_get_constraintdef(c.oid, true)
    INTO fk_def
    FROM pg_constraint c
    WHERE c.conrelid = 'public.appointments'::regclass
      AND c.conname = 'fk_appointments_cancelled_by_user'
      AND c.contype = 'f'
      AND c.convalidated
      AND c.confrelid = 'public.users'::regclass
      AND c.confdeltype = 'n'
      AND (
          SELECT array_agg(a.attname ORDER BY u.ord)
          FROM unnest(c.conkey) WITH ORDINALITY AS u(attnum, ord)
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = u.attnum
      ) = ARRAY['cancelled_by_user_id']::name[]
      AND (
          SELECT array_agg(a.attname ORDER BY u.ord)
          FROM unnest(c.confkey) WITH ORDINALITY AS u(attnum, ord)
          JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = u.attnum
      ) = ARRAY['id']::name[];

    IF fk_def IS NULL THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 FK assertion failed for fk_appointments_cancelled_by_user(cancelled_by_user_id) -> users(id) ON DELETE SET NULL';
    END IF;

    SELECT pg_get_constraintdef(c.oid, true)
    INTO reason_ck_def
    FROM pg_constraint c
    WHERE c.conrelid = 'public.appointments'::regclass
      AND c.conname = 'ck_appointments_cancellation_reason_trimmed'
      AND c.contype = 'c'
      AND c.convalidated;

    SELECT pg_get_constraintdef(c.oid, true)
    INTO role_ck_def
    FROM pg_constraint c
    WHERE c.conrelid = 'public.appointments'::regclass
      AND c.conname = 'ck_appointments_cancelled_by_role'
      AND c.contype = 'c'
      AND c.convalidated;

    SELECT pg_get_constraintdef(c.oid, true)
    INTO outcome_ck_def
    FROM pg_constraint c
    WHERE c.conrelid = 'public.appointments'::regclass
      AND c.conname = 'ck_appointments_cancellation_slot_outcome'
      AND c.contype = 'c'
      AND c.convalidated;

    SELECT pg_get_constraintdef(c.oid, true)
    INTO consistency_ck_def
    FROM pg_constraint c
    WHERE c.conrelid = 'public.appointments'::regclass
      AND c.conname = 'ck_appointments_cancellation_metadata_consistency'
      AND c.contype = 'c'
      AND c.convalidated;

    SELECT
        (
            SELECT array_agg(a.attname ORDER BY u.ord)
            FROM unnest(idx.indkey) WITH ORDINALITY AS u(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = idx.indrelid AND a.attnum = u.attnum
            WHERE u.ord <= idx.indnkeyatts
        ),
        pg_get_expr(idx.indpred, idx.indrelid, true)
    INTO unique_idx_cols, unique_idx_pred
    FROM pg_index idx
    JOIN pg_class ic ON ic.oid = idx.indexrelid
    JOIN pg_am am ON am.oid = ic.relam
    WHERE idx.indexrelid = to_regclass('public.uq_appointments_confirmed_slot')
      AND idx.indrelid = 'public.appointments'::regclass
      AND am.amname = 'btree'
      AND idx.indisunique
      AND idx.indisvalid
      AND idx.indisready
      AND idx.indislive
      AND idx.indnkeyatts = 1
      AND idx.indnatts = idx.indnkeyatts
      AND idx.indexprs IS NULL
      AND NOT (0 = ANY (idx.indkey::int2[]))
      AND idx.indpred IS NOT NULL;

    SELECT
        (
            SELECT array_agg(a.attname ORDER BY u.ord)
            FROM unnest(idx.indkey) WITH ORDINALITY AS u(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = idx.indrelid AND a.attnum = u.attnum
            WHERE u.ord <= idx.indnkeyatts
        ),
        (idx.indoption[0] & 1) = 1,
        (idx.indoption[1] & 1) = 1,
        (idx.indoption[2] & 1) = 1,
        pg_get_expr(idx.indpred, idx.indrelid, true)
    INTO cancelled_idx_cols, cancelled_col0_desc, cancelled_col1_desc, cancelled_col2_desc, cancelled_idx_pred
    FROM pg_index idx
    JOIN pg_class ic ON ic.oid = idx.indexrelid
    JOIN pg_am am ON am.oid = ic.relam
    WHERE idx.indexrelid = to_regclass('public.ix_appointments_doctor_cancelled_at')
      AND idx.indrelid = 'public.appointments'::regclass
      AND am.amname = 'btree'
      AND NOT idx.indisunique
      AND idx.indisvalid
      AND idx.indisready
      AND idx.indislive
      AND idx.indnkeyatts = 3
      AND idx.indnatts = idx.indnkeyatts
      AND idx.indexprs IS NULL
      AND NOT (0 = ANY (idx.indkey::int2[]))
      AND idx.indpred IS NOT NULL;

    -- Quote-aware normalization: preserves quoted literal contents and Boolean grouping parens,
    -- stripping only unquoted text/varchar casts and normalizing unquoted ASCII whitespace.
    raw_exprs := ARRAY[
        reason_ck_def,
        role_ck_def,
        outcome_ck_def,
        consistency_ck_def,
        unique_idx_pred,
        cancelled_idx_pred
    ];
    norm_exprs := ARRAY[]::text[];

    FOR idx_i IN 1..array_length(raw_exprs, 1) LOOP
        src := raw_exprs[idx_i];
        IF src IS NULL THEN
            norm_exprs := norm_exprs || ARRAY[NULL::text];
            CONTINUE;
        END IF;

        out_buf := '';
        src_len := length(src);
        pos := 1;

        WHILE pos <= src_len LOOP
            ch := substr(src, pos, 1);

            IF ch = '''' OR (ch IN ('E', 'e') AND pos < src_len AND substr(src, pos + 1, 1) = '''' AND (pos = 1 OR substr(src, pos - 1, 1) !~ '^[A-Za-z0-9_$]$')) THEN
                IF ch IN ('E', 'e') THEN
                    is_escape_str := true;
                    out_buf := out_buf || ch || '''';
                    pos := pos + 2;
                ELSE
                    is_escape_str := false;
                    out_buf := out_buf || '''';
                    pos := pos + 1;
                END IF;

                WHILE pos <= src_len LOOP
                    qch := substr(src, pos, 1);
                    IF is_escape_str AND qch = '\' AND pos < src_len THEN
                        out_buf := out_buf || substr(src, pos, 2);
                        pos := pos + 2;
                    ELSIF qch = '''' THEN
                        IF pos < src_len AND substr(src, pos + 1, 1) = '''' THEN
                            out_buf := out_buf || '''''';
                            pos := pos + 2;
                        ELSE
                            out_buf := out_buf || '''';
                            pos := pos + 1;
                            EXIT;
                        END IF;
                    ELSE
                        out_buf := out_buf || qch;
                        pos := pos + 1;
                    END IF;
                END LOOP;
            ELSIF ch = '"' THEN
                out_buf := out_buf || '"';
                pos := pos + 1;
                WHILE pos <= src_len LOOP
                    qch := substr(src, pos, 1);
                    IF qch = '"' THEN
                        IF pos < src_len AND substr(src, pos + 1, 1) = '"' THEN
                            out_buf := out_buf || '""';
                            pos := pos + 2;
                        ELSE
                            out_buf := out_buf || '"';
                            pos := pos + 1;
                            EXIT;
                        END IF;
                    ELSE
                        out_buf := out_buf || qch;
                        pos := pos + 1;
                    END IF;
                END LOOP;
            ELSIF ch = ':' AND pos < src_len AND substr(src, pos + 1, 1) = ':' THEN
                cast_match := substring(substr(src, pos) FROM '^::(?:pg_catalog\.)?(?:character varying|varchar|text)(?:\[\])?(?![A-Za-z0-9_])');
                IF cast_match IS NOT NULL THEN
                    pos := pos + length(cast_match);
                ELSE
                    out_buf := out_buf || ch;
                    pos := pos + 1;
                END IF;
            ELSIF ch IN (' ', chr(9), chr(10), chr(11), chr(12), chr(13)) THEN
                IF out_buf <> '' AND right(out_buf, 1) NOT IN (' ', '(', '[') THEN
                    out_buf := out_buf || ' ';
                END IF;
                pos := pos + 1;
            ELSIF ch IN (')', ']', ',') THEN
                IF right(out_buf, 1) = ' ' THEN
                    out_buf := left(out_buf, length(out_buf) - 1);
                END IF;
                out_buf := out_buf || ch;
                pos := pos + 1;
            ELSE
                out_buf := out_buf || ch;
                pos := pos + 1;
            END IF;
        END LOOP;

        norm_exprs := norm_exprs || ARRAY[trim(both ' ' FROM out_buf)];
    END LOOP;

    norm_reason_ck := norm_exprs[1];
    norm_role_ck := norm_exprs[2];
    norm_outcome_ck := norm_exprs[3];
    norm_consistency_ck := norm_exprs[4];
    norm_unique_pred := norm_exprs[5];
    norm_cancelled_pred := norm_exprs[6];

    IF reason_ck_def IS NULL
       OR norm_reason_ck NOT IN (
           'CHECK (cancellation_reason IS NULL OR length(cancellation_reason) >= 1 AND length(cancellation_reason) <= 500 AND btrim(cancellation_reason, ' || expected_ws_lit || ') <> '''' AND cancellation_reason = btrim(cancellation_reason, ' || expected_ws_lit || '))',
           'CHECK (cancellation_reason IS NULL OR (length(cancellation_reason) >= 1 AND length(cancellation_reason) <= 500 AND btrim(cancellation_reason, ' || expected_ws_lit || ') <> '''' AND cancellation_reason = btrim(cancellation_reason, ' || expected_ws_lit || ')))'
       ) THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 constraint ck_appointments_cancellation_reason_trimmed definition assertion failed: %', reason_ck_def;
    END IF;

    IF role_ck_def IS NULL
       OR norm_role_ck NOT IN (
           'CHECK (cancelled_by_role IS NULL OR cancelled_by_role = ANY (ARRAY[''PATIENT'', ''DOCTOR'', ''ADMIN'']))',
           'CHECK (cancelled_by_role IS NULL OR cancelled_by_role = ANY ((ARRAY[''PATIENT'', ''DOCTOR'', ''ADMIN''])))',
           'CHECK (cancelled_by_role IS NULL OR (cancelled_by_role = ANY (ARRAY[''PATIENT'', ''DOCTOR'', ''ADMIN''])))',
           'CHECK (cancelled_by_role IS NULL OR (cancelled_by_role = ANY ((ARRAY[''PATIENT'', ''DOCTOR'', ''ADMIN'']))))',
           'CHECK (cancelled_by_role IS NULL OR cancelled_by_role = ANY (''{PATIENT,DOCTOR,ADMIN}''))',
           'CHECK (cancelled_by_role IS NULL OR (cancelled_by_role = ANY (''{PATIENT,DOCTOR,ADMIN}'')))'
       ) THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 constraint ck_appointments_cancelled_by_role definition assertion failed: %', role_ck_def;
    END IF;

    IF outcome_ck_def IS NULL
       OR norm_outcome_ck NOT IN (
           'CHECK (cancellation_slot_outcome IS NULL OR cancellation_slot_outcome = ANY (ARRAY[''RELEASED'', ''WITHHELD'', ''RETAINED_UNAVAILABLE'', ''OVERLAP'']))',
           'CHECK (cancellation_slot_outcome IS NULL OR cancellation_slot_outcome = ANY ((ARRAY[''RELEASED'', ''WITHHELD'', ''RETAINED_UNAVAILABLE'', ''OVERLAP''])))',
           'CHECK (cancellation_slot_outcome IS NULL OR (cancellation_slot_outcome = ANY (ARRAY[''RELEASED'', ''WITHHELD'', ''RETAINED_UNAVAILABLE'', ''OVERLAP''])))',
           'CHECK (cancellation_slot_outcome IS NULL OR (cancellation_slot_outcome = ANY ((ARRAY[''RELEASED'', ''WITHHELD'', ''RETAINED_UNAVAILABLE'', ''OVERLAP'']))))',
           'CHECK (cancellation_slot_outcome IS NULL OR cancellation_slot_outcome = ANY (''{RELEASED,WITHHELD,RETAINED_UNAVAILABLE,OVERLAP}''))',
           'CHECK (cancellation_slot_outcome IS NULL OR (cancellation_slot_outcome = ANY (''{RELEASED,WITHHELD,RETAINED_UNAVAILABLE,OVERLAP}'')))'
       ) THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 constraint ck_appointments_cancellation_slot_outcome definition assertion failed: %', outcome_ck_def;
    END IF;

    IF consistency_ck_def IS NULL
       OR norm_consistency_ck NOT IN (
           'CHECK (status <> ''CANCELLED'' AND cancellation_reason IS NULL AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancelled_by_role IS NULL AND cancellation_slot_outcome IS NULL OR status = ''CANCELLED'' AND (cancellation_reason IS NULL AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancelled_by_role IS NULL AND cancellation_slot_outcome IS NULL OR cancelled_at IS NOT NULL AND cancelled_by_role IS NOT NULL AND cancellation_slot_outcome IS NOT NULL))',
           'CHECK ((status <> ''CANCELLED'' AND cancellation_reason IS NULL AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancelled_by_role IS NULL AND cancellation_slot_outcome IS NULL) OR (status = ''CANCELLED'' AND ((cancellation_reason IS NULL AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancelled_by_role IS NULL AND cancellation_slot_outcome IS NULL) OR (cancelled_at IS NOT NULL AND cancelled_by_role IS NOT NULL AND cancellation_slot_outcome IS NOT NULL))))'
       ) THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 constraint ck_appointments_cancellation_metadata_consistency definition assertion failed: %', consistency_ck_def;
    END IF;

    IF unique_idx_cols IS DISTINCT FROM ARRAY['slot_id']::name[]
       OR norm_unique_pred NOT IN (
           'status = ''CONFIRMED''',
           '(status = ''CONFIRMED'')'
       ) THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 unique partial index uq_appointments_confirmed_slot definition assertion failed (cols=%, pred=%)',
            unique_idx_cols, unique_idx_pred;
    END IF;

    IF cancelled_idx_cols IS DISTINCT FROM ARRAY['doctor_id', 'cancelled_at', 'id']::name[]
       OR cancelled_col0_desc IS DISTINCT FROM false
       OR cancelled_col1_desc IS DISTINCT FROM true
       OR cancelled_col2_desc IS DISTINCT FROM true
       OR norm_cancelled_pred NOT IN (
           'status = ''CANCELLED'' AND cancelled_at IS NOT NULL',
           'cancelled_at IS NOT NULL AND status = ''CANCELLED''',
           '(status = ''CANCELLED'') AND (cancelled_at IS NOT NULL)',
           '(cancelled_at IS NOT NULL) AND (status = ''CANCELLED'')'
       ) THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 index ix_appointments_doctor_cancelled_at definition assertion failed (cols=%, desc=[%,%,%], pred=%)',
            cancelled_idx_cols, cancelled_col0_desc, cancelled_col1_desc, cancelled_col2_desc, cancelled_idx_pred;
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

    SELECT count(*)
    INTO unbooked_confirmed_count
    FROM public.appointments a
    JOIN public.appointment_slots s ON s.id = a.slot_id
    WHERE a.status = 'CONFIRMED'
      AND s.status IS DISTINCT FROM 'BOOKED';

    IF dup_confirmed_slot_count <> 0 OR unbooked_confirmed_count <> 0 THEN
        RAISE EXCEPTION 'APPT-CANCEL-01 data invariant failed (dup_confirmed=%, unbooked_confirmed=%)',
            dup_confirmed_slot_count, unbooked_confirmed_count;
    END IF;

    RAISE NOTICE 'PASS: APPT-CANCEL-01 columns, constraint/FK definitions, index keys/predicates, and data invariants verified';
END;
$verify$;

COMMIT;
