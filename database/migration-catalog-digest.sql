-- Session-scoped helper, loaded by the migration runner after target verification.
CREATE FUNCTION pg_temp.hms_catalog_digest() RETURNS text
LANGUAGE sql STABLE
SET search_path TO pg_catalog
AS $digest$
    WITH namespaces AS (
        SELECT oid, nspname FROM pg_namespace WHERE nspname IN ('public', 'hms_migration')
    ), relation_catalog AS (
        SELECT c.*, n.nspname FROM pg_class c JOIN namespaces n ON n.oid = c.relnamespace
    ), definitions AS (
        SELECT nspname, relname, 'relation' AS kind, '' AS name,
               jsonb_build_array(relkind, relpersistence, relreplident, relrowsecurity, relforcerowsecurity,
                   relispartition, reloptions, pg_get_partkeydef(oid),
                   CASE WHEN relkind IN ('m', 'v') THEN pg_get_viewdef(oid) END)
        FROM relation_catalog
        UNION ALL
        SELECT c.nspname, c.relname, 'column', a.attname,
               jsonb_build_array(a.attnum, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   a.attidentity, a.attgenerated, a.attcollation, pg_get_expr(d.adbin, d.adrelid))
        FROM relation_catalog c JOIN pg_attribute a ON a.attrelid = c.oid
        LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
        WHERE a.attnum > 0 AND NOT a.attisdropped
        UNION ALL
        SELECT c.nspname, c.relname, 'constraint', con.conname,
               jsonb_build_array(pg_get_constraintdef(con.oid), con.convalidated, con.conenforced)
        FROM relation_catalog c JOIN pg_constraint con ON con.conrelid = c.oid
        UNION ALL
        SELECT c.nspname, c.relname, 'index', ic.relname,
               jsonb_build_array(pg_get_indexdef(i.indexrelid), i.indisvalid, i.indisready)
        FROM relation_catalog c JOIN pg_index i ON i.indrelid = c.oid
        JOIN pg_class ic ON ic.oid = i.indexrelid
        UNION ALL
        SELECT c.nspname, c.relname, 'trigger', t.tgname,
               jsonb_build_array(pg_get_triggerdef(t.oid), t.tgenabled)
        FROM relation_catalog c JOIN pg_trigger t ON t.tgrelid = c.oid WHERE NOT t.tgisinternal
        UNION ALL
        SELECT c.nspname, c.relname, 'sequence', '',
               jsonb_build_array(format_type(s.seqtypid, NULL), s.seqstart, s.seqincrement,
                   s.seqmax, s.seqmin, s.seqcache, s.seqcycle)
        FROM relation_catalog c JOIN pg_sequence s ON s.seqrelid = c.oid
        UNION ALL
        SELECT n.nspname, '', 'routine', p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
               to_jsonb(CASE WHEN p.prokind <> 'a' THEN pg_get_functiondef(p.oid) ELSE p.prosrc END)
        FROM namespaces n JOIN pg_proc p ON p.pronamespace = n.oid
        UNION ALL
        SELECT n.nspname, '', 'type', t.typname, jsonb_build_array(t.typtype, t.typnotnull,
            format_type(t.typbasetype, t.typtypmod), t.typdefault, ARRAY(
                SELECT e.enumlabel FROM pg_enum e WHERE e.enumtypid = t.oid ORDER BY e.enumsortorder))
        FROM namespaces n JOIN pg_type t ON t.typnamespace = n.oid
        WHERE t.typrelid = 0
        UNION ALL
        SELECT c.nspname, c.relname, 'policy', p.polname, jsonb_build_array(
            p.polcmd, p.polpermissive, p.polroles, pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid))
        FROM relation_catalog c JOIN pg_policy p ON p.polrelid = c.oid
        UNION ALL
        SELECT n.nspname, '', 'extension', e.extname, to_jsonb(e.extversion)
        FROM namespaces n JOIN pg_extension e ON e.extnamespace = n.oid
    )
    SELECT encode(sha256(convert_to(
        coalesce(jsonb_agg(to_jsonb(d) ORDER BY nspname, relname, kind, name)::text, '[]'), 'UTF8')), 'hex')
    FROM definitions d
$digest$;
