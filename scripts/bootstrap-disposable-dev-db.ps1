<#
.SYNOPSIS
    Creates a fresh database on the local Windows PostgreSQL instance.
.DESCRIPTION
    The default generated name is reserved for test targets. An explicit hms_dev_ name
    outside that namespace creates a local development database. Existing databases
    cause an error. A failure leaves the new database for inspection.
    Set HMS_MIGRATION_PASSWORD in the caller's process for the explicit migration role.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$DatabaseName,
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 5433,
    [string]$PsqlPath = 'C:\Program Files\PostgreSQL\18\bin\psql.exe',
    [string]$MigrationUser = 'postgres',
    [switch]$SkipMigrations
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Port -ne 5433) {
    Write-Error "SAFETY VIOLATION: Target port $Port is not permitted." -ErrorAction Continue
    exit 1
}
if ($DbHost -notin @('127.0.0.1', 'localhost', '::1', '[::1]')) {
    Write-Error "SAFETY VIOLATION: Target host '$DbHost' is not permitted." -ErrorAction Continue
    exit 1
}

if ([string]::IsNullOrWhiteSpace($DatabaseName)) {
    $DatabaseName = 'hms_dev_test_' + [Guid]::NewGuid().ToString('N')
}
$verificationScript = Join-Path $PSScriptRoot 'verify-local-db-safety.ps1'
& $verificationScript -DatabaseName $DatabaseName -Port $Port -DbHost $DbHost
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Safety preflight failed. Aborting database creation.' -ErrorAction Continue
    exit 5
}

$mode = 'LocalDevelopment'
if ($DatabaseName -imatch '\Ahms_dev_test_') {
    if ($DatabaseName -cnotmatch '\Ahms_dev_test_[a-f0-9]{32}\z') {
        throw 'The hms_dev_test_ namespace requires a newly generated 32-character lowercase hex identifier.'
    }
    $mode = 'Disposable'
}
if ($MigrationUser -cnotmatch '\A[a-z_][a-z0-9_]{0,62}\z') {
    throw 'Specify a valid local migration role.'
}

$migrationRunner = Join-Path $PSScriptRoot 'migrate-hms-database.ps1'
if (-not $SkipMigrations) {
    & $migrationRunner -ValidateManifestOnly
    if ($LASTEXITCODE -ne 0) {
        throw 'Migration manifest verification failed before database creation.'
    }
}
if ([string]::IsNullOrWhiteSpace($env:HMS_MIGRATION_PASSWORD)) {
    throw 'Set HMS_MIGRATION_PASSWORD explicitly for this process before connecting.'
}
. (Join-Path $PSScriptRoot 'hms-local-psql.ps1')

# Use the same pinned maintenance session for identity check, collision check, creation and OID capture.
# A racing database creation fails at CREATE DATABASE itself.
$maintenanceSql = @'
DO $check$
BEGIN
    IF current_database() IS DISTINCT FROM 'postgres'
       OR current_user IS DISTINCT FROM '{0}'
       OR current_setting('server_version_num')::integer < 180001
       OR pg_catalog.inet_server_port() IS DISTINCT FROM 5433
       OR pg_catalog.host(pg_catalog.inet_server_addr()) IS DISTINCT FROM '127.0.0.1' THEN
        RAISE EXCEPTION 'Maintenance database identity did not match the expected local server';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = '{1}') THEN
        RAISE EXCEPTION 'Requested database already exists';
    END IF;
END;
$check$;

CREATE DATABASE "{1}" WITH OWNER = "{0}" ENCODING = 'UTF8' LC_COLLATE = 'C' LC_CTYPE = 'C' TEMPLATE = template0;
SELECT oid FROM pg_catalog.pg_database WHERE datname = '{1}';
'@ -f $MigrationUser, $DatabaseName

try {
    $maintenance = @{
        PsqlPath = $PsqlPath
        DatabaseName = 'postgres'
        MigrationUser = $MigrationUser
        Password = $env:HMS_MIGRATION_PASSWORD
        Sql = $maintenanceSql
    }
    $oidText = Invoke-HmsLocalPsql @maintenance
} catch {
    throw "Local database creation failed. If CREATE DATABASE completed, retain '$DatabaseName' for inspection."
}
$oid = [uint32]0
if (-not [uint32]::TryParse($oidText, [ref]$oid) -or $oid -eq 0 -or $oid -eq 16560) {
    throw "Could not verify the OID for '$DatabaseName'. Retain the database for inspection."
}
Write-Host "Created local PostgreSQL database '$DatabaseName' (OID $oid)."

if ($SkipMigrations) {
    Write-Host 'Schema installation was skipped. This database has no test attestation.'
    exit 0
}

$target = '{0}:{1}' -f $DatabaseName, $oid
$migration = @{
    Mode = $mode
    DbHost = '127.0.0.1'
    Port = $Port
    DatabaseName = $DatabaseName
    ExpectedDatabaseOid = $oid
    ConfirmTarget = $target
    MigrationUser = $MigrationUser
    PsqlPath = $PsqlPath
}
& $migrationRunner @migration
if ($LASTEXITCODE -ne 0) {
    throw "Migration failed. Retain '$DatabaseName' (OID $oid) and verify the ledger before retrying."
}

Write-Host "Migrated and verified '$DatabaseName' (OID $oid)."
if ($mode -eq 'Disposable') {
    Write-Host "Test confirmation (HMS_POSTGRES_TEST_CONFIRM): $target"
} else {
    Write-Host "Local development JDBC URL: jdbc:postgresql://127.0.0.1:5433/$DatabaseName"
}
exit 0
