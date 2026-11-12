-- Only run during the approved maintenance window. See maintenance-checklist.md.
\set ON_ERROR_STOP on
\if :{?confirm_live_database}
\else
\set confirm_live_database ''
\endif

BEGIN READ WRITE;
SET LOCAL hms.deployment_mode = 'live';
SELECT set_config('hms.deployment_confirmation', :'confirm_live_database', true) AS confirmation \gset
\ir schema.sql
\ir audit.sql
COMMIT;
