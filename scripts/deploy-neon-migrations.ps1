# ==============================================================================
# HMS Phase 2B — Neon Production Database Migration Script
# Applies the reviewed canonical HMS schema to an explicitly confirmed Neon PostgreSQL target.
# ==============================================================================
[CmdletBinding(DefaultParameterSetName = 'Apply')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Validate')]
    [switch]$ValidateOnly,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [string]$DbHost,

    [Parameter(ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [ValidateRange(1, 65535)]
    [int]$Port = 5432,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [string]$DatabaseName,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [uint32]$ExpectedDatabaseOid,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [string]$ConfirmTarget,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [string]$MigrationUser,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [string]$ApplicationUser,

    [Parameter(ParameterSetName = 'Apply')]
    [Parameter(ParameterSetName = 'Validate')]
    [string]$PsqlPath = 'psql'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$utf8 = [Text.UTF8Encoding]::new($false, $true)

# -----------------------------------------------------------------------------
# 1. Parameter Preflight & Safety Validation
# -----------------------------------------------------------------------------

# Database Name validation
if ([string]::IsNullOrWhiteSpace($DatabaseName)) {
    throw "DatabaseName cannot be empty or whitespace."
}
if ($DatabaseName -cnotmatch '\A[a-zA-Z][a-zA-Z0-9_]{0,62}\z') {
    throw "DatabaseName '$DatabaseName' is not a valid PostgreSQL identifier."
}
if ($DatabaseName -match 'hospital_management' -or $DatabaseName -match '\A(postgres|template[01])\z' -or $DatabaseName -match '\Ahms_dev_') {
    throw "CRITICAL SAFETY VIOLATION: DatabaseName '$DatabaseName' is forbidden for Neon deployment. Must not target development, template or default databases."
}

# Role validation
if ([string]::IsNullOrWhiteSpace($MigrationUser) -or $MigrationUser -cnotmatch '\A[a-zA-Z][a-zA-Z0-9_]{0,62}\z') {
    throw "MigrationUser '$MigrationUser' is invalid. Must be a valid PostgreSQL identifier."
}
if ($MigrationUser -ieq 'postgres' -or $MigrationUser -match '(?i)admin|superuser|root') {
    throw "CRITICAL SAFETY VIOLATION: Dedicated migration role required. 'postgres' and superuser roles are strictly prohibited."
}

if ([string]::IsNullOrWhiteSpace($ApplicationUser) -or $ApplicationUser -cnotmatch '\A[a-zA-Z][a-zA-Z0-9_]{0,62}\z') {
    throw "ApplicationUser '$ApplicationUser' is invalid. Must be a valid PostgreSQL identifier."
}
if ($ApplicationUser -ieq 'postgres' -or $ApplicationUser -match '(?i)admin|superuser|root') {
    throw "CRITICAL SAFETY VIOLATION: Application runtime role cannot be 'postgres' or superuser."
}
if ($MigrationUser -ieq $ApplicationUser) {
    throw "CRITICAL SAFETY VIOLATION: MigrationUser and ApplicationUser must be distinct roles. Privilege separation is strictly enforced."
}

# OID & Target Confirmation validation
if (-not $ExpectedDatabaseOid -or $ExpectedDatabaseOid -le 0) {
    throw "ExpectedDatabaseOid must be a valid positive integer OID."
}
$expectedConfirm = "${DatabaseName}:${ExpectedDatabaseOid}"
if ($ConfirmTarget -cne $expectedConfirm) {
    throw "CRITICAL SAFETY VIOLATION: ConfirmTarget '$ConfirmTarget' does not match required value '${expectedConfirm}'."
}

# Host endpoint validation (Direct TLS connection, no loopback, no pooler)
if ([string]::IsNullOrWhiteSpace($DbHost)) {
    throw "DbHost cannot be empty or whitespace."
}
if ($DbHost -match '(^|\.)localhost(\.|$)|127\.\d+\.\d+\.\d+|::1') {
    throw "CRITICAL SAFETY VIOLATION: Neon deployment requires a remote endpoint. Loopback hosts are forbidden."
}
if ($DbHost -match '-pooler\.') {
    throw "CRITICAL SAFETY VIOLATION: Migrations require direct database connections. Pooled connections (-pooler.) cannot hold advisory session locks."
}
if ($DbHost -cnotmatch '\A(?=.{1,253}\z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]*\z') {
    throw "DbHost '$DbHost' must be a valid DNS hostname."
}

# -----------------------------------------------------------------------------
# 2. Manifest & SQL Artifact Exact-Byte Verification
# -----------------------------------------------------------------------------
function Read-ReviewedSql($artifact, [string]$expectedPath) {
    if ($artifact.path -cne $expectedPath -or $artifact.sha256 -cnotmatch '\A[0-9a-f]{64}\z') {
        throw "Unexpected manifest entry for $expectedPath."
    }
    $path = Join-Path $repoRoot $expectedPath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "SQL artifact not found: $expectedPath"
    }
    $rawBytes = [IO.File]::ReadAllBytes($path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash($rawBytes)
        $actual = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    if ($actual -cne $artifact.sha256) {
        throw "SHA-256 mismatch for $expectedPath. Manifest: $($artifact.sha256) Actual: $actual."
    }
    return $utf8.GetString($rawBytes)
}

function Get-TransactionBody([string]$sql, [string]$beginPattern) {
    $sql = [regex]::Replace($sql, '(?m)^\s*\\set ON_ERROR_STOP on[^\S\n]*$', '')
    $wrapper = [regex]::Match($sql, '(?ms)\A(?:[ \t]*--[^\n]*\n|\s)*' + $beginPattern + ';\s*\n(.*)\nCOMMIT;\s*\z')
    if (!$wrapper.Success -or $wrapper.Groups[1].Value -match '(?im)^\s*(\\|(?:BEGIN|START TRANSACTION|COMMIT|END TRANSACTION|ROLLBACK|ABORT)\s*;)') {
        throw 'Unexpected transaction boundaries or psql command in a migration artifact.'
    }
    return $wrapper.Groups[1].Value
}

$expectedMigrations = @(
    @{ id = 'BASELINE';        path = 'database/pre-sch-02-baseline.sql'; verifier = $null },
    @{ id = 'SCH-02';          path = 'database/sch-02-availability-revisions.sql'; verifier = $null },
    @{ id = 'DISCO-01';        path = 'database/disco-01-doctor-discovery.sql'; verifier = $null },
    @{ id = 'DISCO-02';        path = 'database/disco-02-clinic-lgd-district.sql'; verifier = $null },
    @{ id = 'DOC-01';          path = 'database/doc-01-verification-catalog.sql'; verifier = $null },
    @{ id = 'AI-01';           path = 'database/ai-01-patient-assistant.sql'; verifier = $null },
    @{ id = 'AI-02';           path = 'database/ai-02-cloudflare-source.sql'; verifier = 'database/ai-02-verify.sql' },
    @{ id = 'APPT-CANCEL-01';  path = 'database/appt-cancel-01-workflow.sql'; verifier = 'database/appt-cancel-01-verify.sql' },
    @{ id = 'BILL-01';         path = 'database/bill-01-schema.sql'; verifier = 'database/bill-01-verify.sql' }
)

$manifestPath = Join-Path $repoRoot 'database/migration-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Migration manifest missing at $manifestPath"
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.formatVersion -ne 1 -or $manifest.minimumServerVersion -ne 180001 -or
    $manifest.migrations.Count -ne $expectedMigrations.Count) {
    throw 'Unexpected migration manifest version, minimum server version (must be >= 180001) or migration count.'
}

$digestSql = Read-ReviewedSql $manifest.catalogDigest 'database/migration-catalog-digest.sql'
$releaseVerify = Get-TransactionBody (Read-ReviewedSql $manifest.releaseVerifier 'database/migration-release-verify.sql') 'BEGIN READ ONLY'
$steps = for ($i = 0; $i -lt $expectedMigrations.Count; $i++) {
    $entry = $manifest.migrations[$i]
    $reviewed = $expectedMigrations[$i]
    if ($entry.id -cne $reviewed.id -or $entry.version -ne ($i + 1)) {
        throw "Unexpected migration order at version $($i + 1): expected $($reviewed.id), got $($entry.id)."
    }
    $body = Get-TransactionBody (Read-ReviewedSql $entry $reviewed.path) 'BEGIN'
    $verify = ''
    if ($reviewed.verifier) {
        $verify = Get-TransactionBody (Read-ReviewedSql $entry.verifier $reviewed.verifier) 'BEGIN(?: TRANSACTION ISOLATION LEVEL REPEATABLE READ)? READ ONLY'
    } elseif ($entry.PSObject.Properties.Name -contains 'verifier' -and $null -ne $entry.verifier) {
        throw "Unexpected verifier for $($entry.id)."
    }
    [pscustomobject]@{ Version = $entry.version; Id = $entry.id; Sha256 = $entry.sha256; Body = $body; Verify = $verify }
}

Write-Output "Successfully verified 9 canonical migrations and SHA-256 byte checksums against manifest."

if ($ValidateOnly) {
    Write-Output "Validation passed. Preflight checks and manifest integrity confirmed."
    exit 0
}

# -----------------------------------------------------------------------------
# 3. Process-Scoped Password Check
# -----------------------------------------------------------------------------
$password = [Environment]::GetEnvironmentVariable('HMS_MIGRATION_PASSWORD', 'Process')
if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'Set the process environment variable HMS_MIGRATION_PASSWORD before applying migrations.'
}

# -----------------------------------------------------------------------------
# 4. Construct SQL Payload with Preflight, Advisory Lock, Ledger, and Grant Checks
# -----------------------------------------------------------------------------
$targetPreflight = @'
SET search_path TO pg_catalog, public;
SET lock_timeout TO '10s';
SET statement_timeout TO '120s';
SET idle_in_transaction_session_timeout TO '60s';

DO $target$
BEGIN
    IF current_database()::text <> '{0}' OR session_user::text <> '{2}' OR current_user::text <> '{2}'
       OR current_setting('server_version_num')::integer < 180001
       OR current_setting('server_encoding') <> 'UTF8'
       OR current_setting('transaction_read_only') <> 'off' OR pg_is_in_recovery()
       OR (SELECT oid FROM pg_database WHERE datname = current_database()) IS DISTINCT FROM {1}::oid THEN
        RAISE EXCEPTION 'Connected database identity, role, encoding, or PostgreSQL version (< 18.1) differs from confirmed Neon target';
    END IF;

    -- Enforce non-loopback TLS connection
    IF coalesce(inet_server_addr() << '127.0.0.0/8'::inet, false)
       OR coalesce(inet_server_addr() = '::1'::inet, false)
       OR NOT coalesce((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false) THEN
        RAISE EXCEPTION 'Neon deployment requires a verified TLS connection to a non-loopback host';
    END IF;

    -- Verify application runtime role exists and is distinct
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{3}') THEN
        RAISE EXCEPTION 'Application runtime role "{3}" does not exist in target cluster';
    END IF;

    -- Acquire exclusive transaction advisory migration lock
    IF NOT pg_try_advisory_lock(86031920261117001::bigint) THEN
        RAISE EXCEPTION 'Another migration runner currently holds the HMS migration advisory lock';
    END IF;
END;
$target$;
SET hms.migration_target TO '{0}';

CREATE TEMP TABLE hms_expected_migration (
    version integer PRIMARY KEY,
    migration_id text NOT NULL,
    sha256 text NOT NULL
);
'@ -f $DatabaseName, $ExpectedDatabaseOid, $MigrationUser, $ApplicationUser

$preflight = @'
DO $preflight$
DECLARE
    recorded integer;
    latest_digest text;
BEGIN
    IF to_regnamespace('public') IS NULL THEN
        RAISE EXCEPTION 'HMS requires the public schema';
    END IF;
    IF to_regnamespace('hms_migration') IS NULL THEN
        IF pg_temp.hms_catalog_digest() IS DISTINCT FROM encode(sha256(convert_to('[]', 'UTF8')), 'hex') THEN
            RAISE EXCEPTION 'Unmanaged objects exist in public. Target database must be fresh or previously migrated';
        END IF;
        RETURN;
    END IF;
    IF to_regclass('hms_migration.release_target') IS NULL OR
       to_regclass('hms_migration.migration_ledger') IS NULL THEN
        RAISE EXCEPTION 'Unrecognized migration metadata schema';
    END IF;
    IF (SELECT count(*) FROM hms_migration.release_target) <> 1 OR NOT EXISTS (
        SELECT 1 FROM hms_migration.release_target
        WHERE database_name = current_database()
          AND database_oid = (SELECT oid FROM pg_database WHERE datname = current_database())
          AND purpose = 'DEPLOYMENT'
    ) THEN
        RAISE EXCEPTION 'Release target attestation mismatch: purpose must be DEPLOYMENT for this confirmed target';
    END IF;
    SELECT count(*) INTO recorded FROM hms_migration.migration_ledger;
    IF recorded = 0 OR recorded > (SELECT count(*) FROM pg_temp.hms_expected_migration) OR EXISTS (
        SELECT 1 FROM pg_temp.hms_expected_migration e
        FULL JOIN hms_migration.migration_ledger l USING (version)
        WHERE (l.version IS NOT NULL AND (e.version IS NULL OR l.version > recorded))
           OR (e.version <= recorded AND (l.migration_id IS DISTINCT FROM e.migration_id
               OR l.sha256 IS DISTINCT FROM e.sha256))
    ) THEN
        RAISE EXCEPTION 'Migration ledger is not a verified prefix of this manifest';
    END IF;
    SELECT catalog_sha256 INTO latest_digest FROM hms_migration.migration_ledger WHERE version = recorded;
    IF latest_digest IS DISTINCT FROM pg_temp.hms_catalog_digest() THEN
        RAISE EXCEPTION 'Catalog drift detected since the last recorded migration';
    END IF;
END;
$preflight$;

SELECT to_regnamespace('hms_migration') IS NULL AS hms_bootstrap \gset
'@

$metadata = @'
CREATE SCHEMA hms_migration;
REVOKE ALL ON SCHEMA hms_migration FROM PUBLIC;

CREATE TABLE hms_migration.release_target (
    database_name name PRIMARY KEY,
    database_oid oid NOT NULL,
    purpose text NOT NULL CHECK (purpose = 'DEPLOYMENT')
);
INSERT INTO hms_migration.release_target
SELECT current_database(), oid, 'DEPLOYMENT' FROM pg_database WHERE datname = current_database();

CREATE TABLE hms_migration.migration_ledger (
    version integer PRIMARY KEY CHECK (version > 0),
    migration_id text UNIQUE NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    catalog_sha256 text NOT NULL CHECK (catalog_sha256 ~ '^[0-9a-f]{64}$'),
    applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
REVOKE ALL ON SCHEMA hms_migration FROM PUBLIC;
'@

# Runtime role grants applied after migrations complete
$runtimeRoleGrants = @'
DO $grants$
BEGIN
    -- Grant runtime role schema usage and table DML on public schema
    GRANT USAGE ON SCHEMA public TO "{0}";
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "{0}";
    GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO "{0}";
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "{0}";
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO "{0}";

    -- Grant runtime role read-only access to migration metadata for release attestation
    GRANT USAGE ON SCHEMA hms_migration TO "{0}";
    GRANT SELECT ON hms_migration.release_target TO "{0}";
    GRANT SELECT ON hms_migration.migration_ledger TO "{0}";
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON ALL TABLES IN SCHEMA hms_migration FROM "{0}";
    REVOKE CREATE ON SCHEMA hms_migration FROM "{0}";

    -- Grant runtime role database CONNECT and TEMPORARY privileges for catalog digest session verification
    EXECUTE format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', current_database(), '{0}');
END;
$grants$;
'@ -f $ApplicationUser

$payload = [Text.StringBuilder]::new()
[void]$payload.AppendLine($targetPreflight)
[void]$payload.AppendLine($digestSql)
foreach ($step in $steps) {
    [void]$payload.AppendLine("INSERT INTO pg_temp.hms_expected_migration VALUES ($($step.Version), '$($step.Id)', '$($step.Sha256)');")
}
[void]$payload.AppendLine($preflight)
[void]$payload.AppendLine('BEGIN;')
[void]$payload.AppendLine('\if :hms_bootstrap')
[void]$payload.AppendLine($metadata)
[void]$payload.AppendLine('\endif')

foreach ($step in $steps) {
    if ($step.Version -ne 1) { [void]$payload.AppendLine('BEGIN;') }
    [void]$payload.AppendLine("SELECT NOT EXISTS (SELECT 1 FROM hms_migration.migration_ledger WHERE version = $($step.Version)) AS hms_apply \gset")
    [void]$payload.AppendLine('\if :hms_apply')
    [void]$payload.AppendLine("\echo Applying $($step.Id)")
    [void]$payload.AppendLine($step.Body)
    [void]$payload.AppendLine($step.Verify)
    if ($step.Version -eq $steps.Count) {
        [void]$payload.AppendLine($releaseVerify)
    }
    [void]$payload.AppendLine("INSERT INTO hms_migration.migration_ledger (version, migration_id, sha256, catalog_sha256) VALUES ($($step.Version), '$($step.Id)', '$($step.Sha256)', pg_temp.hms_catalog_digest());")
    [void]$payload.AppendLine('\endif')
    [void]$payload.AppendLine('COMMIT;')
}

[void]$payload.AppendLine('BEGIN READ ONLY;')
[void]$payload.AppendLine($releaseVerify)
foreach ($step in $steps) { [void]$payload.AppendLine($step.Verify) }
[void]$payload.AppendLine('COMMIT;')

# Apply runtime role privileges
[void]$payload.AppendLine('BEGIN;')
[void]$payload.AppendLine($runtimeRoleGrants)
[void]$payload.AppendLine('COMMIT;')

[void]$payload.AppendLine('\echo HMS Neon deployment migration and release verification complete.')

# -----------------------------------------------------------------------------
# 5. Execute via psql with Strict TLS & Environment Isolation
# -----------------------------------------------------------------------------
$psql = Get-Command -Name $PsqlPath -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $psql) {
    throw "PostgreSQL client 'psql' not found in PATH."
}

$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $psql.Source
$start.Arguments = "-X -w -v ON_ERROR_STOP=1 --host=$DbHost --port=$Port --username=$MigrationUser --dbname=$DatabaseName --file=-"
$start.UseShellExecute = $false
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true

# Clear any ambient PG credentials
foreach ($key in @($start.EnvironmentVariables.Keys)) {
    if ($key -match '^PG' -or $key -eq 'HMS_MIGRATION_PASSWORD') { $start.EnvironmentVariables.Remove($key) }
}

$start.EnvironmentVariables['PGPASSWORD'] = $password
$start.EnvironmentVariables['PGPASSFILE'] = Join-Path ([IO.Path]::GetTempPath()) "hms-no-credentials-$([guid]::NewGuid().ToString('N'))"
$start.EnvironmentVariables['PGCONNECT_TIMEOUT'] = '15'
$start.EnvironmentVariables['PGGSSENCMODE'] = 'disable'
$start.EnvironmentVariables['PGCLIENTENCODING'] = 'UTF8'
$start.EnvironmentVariables['PGSSLMODE'] = 'verify-full'
$start.EnvironmentVariables['PGSSLROOTCERT'] = 'system'

$process = [Diagnostics.Process]::Start($start)
try {
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $sendError = $null
    try {
        $process.StandardInput.Write($payload.ToString())
    } catch {
        $sendError = $_
    } finally {
        $process.StandardInput.Close()
    }
    $process.WaitForExit()
    $outText = $stdout.GetAwaiter().GetResult().Replace($password, '[redacted]')
    $errText = $stderr.GetAwaiter().GetResult().Replace($password, '[redacted]')
    if ($outText) { Write-Output $outText.TrimEnd() }
    if ($errText) { Write-Output $errText.TrimEnd() }
    if ($process.ExitCode -ne 0 -or $null -ne $sendError) {
        throw "Neon migration stopped with exit code $($process.ExitCode). Inspect error output and ledger state before retry."
    }
} finally {
    $process.Dispose()
}
