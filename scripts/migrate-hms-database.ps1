# Applies the reviewed fresh HMS schema to an explicitly confirmed PostgreSQL target.
[CmdletBinding(DefaultParameterSetName = 'Apply')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Validate')]
    [switch]$ValidateManifestOnly,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [ValidateSet('Disposable', 'LocalDevelopment', 'Deployment')]
    [string]$Mode,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [string]$DbHost,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [ValidateRange(1, 65535)]
    [int]$Port,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [string]$DatabaseName,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [uint32]$ExpectedDatabaseOid,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [string]$ConfirmTarget,

    [Parameter(Mandatory, ParameterSetName = 'Apply')]
    [string]$MigrationUser,

    [Parameter(ParameterSetName = 'Apply')]
    [string]$PsqlPath = 'psql'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$utf8 = [Text.UTF8Encoding]::new($false, $true)

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
        throw "SHA-256 mismatch for $expectedPath. Stop and review the manifest and SQL together."
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

$expected = @(
    @{ id = 'BASELINE';  path = 'database/pre-sch-02-baseline.sql'; verifier = $null },
    @{ id = 'SCH-02';    path = 'database/sch-02-availability-revisions.sql'; verifier = $null },
    @{ id = 'DISCO-01';  path = 'database/disco-01-doctor-discovery.sql'; verifier = $null },
    @{ id = 'DISCO-02';  path = 'database/disco-02-clinic-lgd-district.sql'; verifier = $null },
    @{ id = 'DOC-01';    path = 'database/doc-01-verification-catalog.sql'; verifier = $null },
    @{ id = 'AI-01';     path = 'database/ai-01-patient-assistant.sql'; verifier = $null },
    @{ id = 'AI-02';     path = 'database/ai-02-cloudflare-source.sql'; verifier = 'database/ai-02-verify.sql' },
    @{ id = 'APPT-CANCEL-01'; path = 'database/appt-cancel-01-workflow.sql'; verifier = 'database/appt-cancel-01-verify.sql' },
    @{ id = 'BILL-01';   path = 'database/bill-01-schema.sql'; verifier = 'database/bill-01-verify.sql' }
)

$manifest = Get-Content -LiteralPath (Join-Path $repoRoot 'database/migration-manifest.json') -Raw | ConvertFrom-Json
if ($manifest.formatVersion -ne 1 -or $manifest.minimumServerVersion -ne 180001 -or
    $manifest.migrations.Count -ne $expected.Count) {
    throw 'Unexpected migration manifest version, server requirement or length.'
}
$digestSql = Read-ReviewedSql $manifest.catalogDigest 'database/migration-catalog-digest.sql'
$releaseVerify = Get-TransactionBody (Read-ReviewedSql $manifest.releaseVerifier 'database/migration-release-verify.sql') 'BEGIN READ ONLY'
$steps = for ($i = 0; $i -lt $expected.Count; $i++) {
    $entry = $manifest.migrations[$i]
    $reviewed = $expected[$i]
    if ($entry.id -cne $reviewed.id -or $entry.version -ne ($i + 1)) {
        throw "Unexpected migration order at version $($i + 1)."
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
if ($ValidateManifestOnly) {
    Write-Output 'Validated the nine ordered migrations and all manifest SHA-256 checksums.'
    exit 0
}

if ($DatabaseName -cnotmatch '\A[a-zA-Z][a-zA-Z0-9_]{0,62}\z' -or
    $DatabaseName -match 'hospital_management' -or $DatabaseName -match '\A(postgres|template[01])\z' -or
    $MigrationUser -cnotmatch '\A[a-zA-Z][a-zA-Z0-9_]{0,62}\z' -or
    !$ExpectedDatabaseOid -or $ConfirmTarget -cne "${DatabaseName}:$ExpectedDatabaseOid") {
    throw 'Supply a safe database and role plus the exact -ConfirmTarget database_name:oid.'
}

$isLocal = $Mode -ne 'Deployment'
if ($isLocal) {
    if ($DbHost -cne '127.0.0.1' -or $Port -ne 5433 -or $ExpectedDatabaseOid -eq 16560) {
        throw 'Local migration modes require the approved loopback endpoint and a disposable database OID.'
    }
    if ($Mode -eq 'Disposable') {
        if ($DatabaseName -cnotmatch '\Ahms_dev_test_[a-f0-9]{32}\z') {
            throw 'Disposable mode requires a unique hms_dev_test_ name with 32 lowercase hex digits.'
        }
        $purpose = 'DISPOSABLE_TEST'
    } else {
        if ($DatabaseName -cnotmatch '\Ahms_dev_[A-Za-z0-9_]+\z' -or $DatabaseName -match '\Ahms_dev_test_') {
            throw 'LocalDevelopment mode requires an hms_dev_ name outside the reserved test namespace.'
        }
        $purpose = 'LOCAL_DEVELOPMENT'
    }
} else {
    if ($DbHost -cnotmatch '\A(?=.{1,253}\z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]*\z' -or
        $DbHost -match '(^|\.)localhost(\.|$)|-pooler\.' -or $DatabaseName -match '\Ahms_dev_' -or
        $MigrationUser -ieq 'postgres') {
        throw 'Deployment requires a direct remote DNS endpoint and a dedicated migration role.'
    }
    $purpose = 'DEPLOYMENT'
}

$password = [Environment]::GetEnvironmentVariable('HMS_MIGRATION_PASSWORD', 'Process')
if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'Set the process environment variable HMS_MIGRATION_PASSWORD before applying migrations.'
}

$targetPreflight = @'
SET search_path TO pg_catalog, public;
SET lock_timeout TO '5s';
SET statement_timeout TO '60s';
SET idle_in_transaction_session_timeout TO '60s';

DO $target$
BEGIN
    IF current_database()::text <> '{1}' OR session_user::text <> '{3}' OR current_user::text <> '{3}'
       OR current_setting('server_version_num')::integer < 180001
       OR current_setting('server_encoding') <> 'UTF8'
       OR current_setting('transaction_read_only') <> 'off' OR pg_is_in_recovery()
       OR (SELECT oid FROM pg_database WHERE datname = current_database()) IS DISTINCT FROM {2}::oid THEN
        RAISE EXCEPTION 'Connected database identity, role, encoding, or PostgreSQL version differs from the confirmed target';
    END IF;
    IF {0} AND (inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR inet_client_addr() IS DISTINCT FROM '127.0.0.1'::inet OR inet_server_port() IS DISTINCT FROM 5433) THEN
        RAISE EXCEPTION 'Disposable and local development migrations require 127.0.0.1:5433';
    END IF;
    IF NOT {0} AND (
         coalesce(inet_server_addr() << '127.0.0.0/8'::inet, false)
         OR coalesce(inet_server_addr() = '::1'::inet, false)
         OR NOT coalesce((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false)
    ) THEN
        RAISE EXCEPTION 'Deployment requires a non-loopback TLS PostgreSQL connection';
    END IF;
    IF NOT pg_try_advisory_lock(86031920261117001::bigint) THEN
        RAISE EXCEPTION 'An HMS migration runner already holds the database lock';
    END IF;
END;
$target$;
SET hms.migration_target TO '{1}';

CREATE TEMP TABLE hms_expected_migration (
    version integer PRIMARY KEY,
    migration_id text NOT NULL,
    sha256 text NOT NULL
);
'@ -f $isLocal.ToString().ToUpperInvariant(), $DatabaseName, $ExpectedDatabaseOid, $MigrationUser

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
            RAISE EXCEPTION 'Unmanaged objects exist in public. Use a fresh database or review its recovery offline';
        END IF;
        RETURN;
    END IF;
    IF to_regclass('hms_migration.release_target') IS NULL OR
       to_regclass('hms_migration.migration_ledger') IS NULL THEN
        RAISE EXCEPTION 'Unrecognized migration metadata. Stop and review the database';
    END IF;
    IF (SELECT count(*) FROM hms_migration.release_target) <> 1 OR NOT EXISTS (
        SELECT 1 FROM hms_migration.release_target
        WHERE database_name = current_database()
          AND database_oid = (SELECT oid FROM pg_database WHERE datname = current_database())
          AND purpose = '{0}'
    ) THEN
        RAISE EXCEPTION 'Migration target attestation differs from the confirmed target and mode';
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
        RAISE EXCEPTION 'Catalog drift since the last recorded migration. Stop and review the database';
    END IF;
END;
$preflight$;

SELECT to_regnamespace('hms_migration') IS NULL AS hms_bootstrap \gset
'@ -f $purpose

$metadata = @'
CREATE SCHEMA hms_migration;
REVOKE ALL ON SCHEMA hms_migration FROM PUBLIC;

CREATE TABLE hms_migration.release_target (
    database_name name PRIMARY KEY,
    database_oid oid NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('DISPOSABLE_TEST', 'LOCAL_DEVELOPMENT', 'DEPLOYMENT'))
);
INSERT INTO hms_migration.release_target
SELECT current_database(), oid, '{0}' FROM pg_database WHERE datname = current_database();

CREATE TABLE hms_migration.migration_ledger (
    version integer PRIMARY KEY CHECK (version > 0),
    migration_id text UNIQUE NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{{64}}$'),
    catalog_sha256 text NOT NULL CHECK (catalog_sha256 ~ '^[0-9a-f]{{64}}$'),
    applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
'@ -f $purpose

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
[void]$payload.AppendLine('\echo HMS migration ledger and release verification complete.')

$psql = Get-Command -Name $PsqlPath -CommandType Application | Select-Object -First 1
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $psql.Source
# Every expanded argument is constrained by the validation above.
$start.Arguments = "-X -w -v ON_ERROR_STOP=1 --host=$DbHost --port=$Port --username=$MigrationUser --dbname=$DatabaseName --file=-"
$start.UseShellExecute = $false
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
foreach ($key in @($start.EnvironmentVariables.Keys)) {
    if ($key -match '^PG' -or $key -eq 'HMS_MIGRATION_PASSWORD') { $start.EnvironmentVariables.Remove($key) }
}
$start.EnvironmentVariables['PGPASSWORD'] = $password
$start.EnvironmentVariables['PGPASSFILE'] = Join-Path ([IO.Path]::GetTempPath()) "hms-no-credentials-$([guid]::NewGuid().ToString('N'))"
$start.EnvironmentVariables['PGCONNECT_TIMEOUT'] = '10'
$start.EnvironmentVariables['PGGSSENCMODE'] = 'disable'
$start.EnvironmentVariables['PGCLIENTENCODING'] = 'UTF8'
$start.EnvironmentVariables['PGSSLMODE'] = if ($isLocal) { 'prefer' } else { 'verify-full' }
if (!$isLocal) { $start.EnvironmentVariables['PGSSLROOTCERT'] = 'system' }

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
        throw 'Migration stopped. Inspect the error and existing ledger before any retry.'
    }
} finally {
    $process.Dispose()
}
