-- Supply the exact database name and OID recorded at clone creation.
\set ON_ERROR_STOP on
\if :{?rehearsal_database}
\else
\set rehearsal_database ''
\endif
\if :{?rehearsal_oid}
\else
\set rehearsal_oid ''
\endif

BEGIN READ WRITE;
SET LOCAL hms.deployment_mode = 'disposable';
SELECT set_config('hms.rehearsal_database', :'rehearsal_database', true) AS database_name,
       set_config('hms.rehearsal_oid', :'rehearsal_oid', true) AS database_oid \gset
\ir schema.sql
\ir audit.sql
COMMIT;
