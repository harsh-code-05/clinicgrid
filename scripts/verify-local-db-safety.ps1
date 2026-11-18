<#
.SYNOPSIS
    Validates a local development target. With CheckLiveConnection, also verifies its
    exact catalog identity using the explicit HMS_MIGRATION_PASSWORD credential.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)][AllowEmptyString()][string]$DatabaseName,
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 5433,
    [string]$PsqlPath = 'C:\Program Files\PostgreSQL\18\bin\psql.exe',
    [string]$MigrationUser = 'postgres',
    [uint32]$ExpectedDatabaseOid,
    [string]$ConfirmTarget,
    [switch]$CheckLiveConnection
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($DatabaseName)) {
    Write-Error 'SAFETY VIOLATION: DatabaseName cannot be empty or whitespace.' -ErrorAction Continue
    exit 1
}
if ($DatabaseName -ilike '*hospital_management*') {
    Write-Error "CRITICAL SAFETY VIOLATION: Target database '$DatabaseName' references 'hospital_management'. Connection strictly forbidden." -ErrorAction Continue
    exit 2
}
if ($DatabaseName -cnotmatch '\Ahms_dev_[A-Za-z0-9_]{1,55}\z') {
    Write-Error "SAFETY VIOLATION: Database name '$DatabaseName' does not match the mandatory allowlist pattern." -ErrorAction Continue
    exit 3
}
if ($DbHost -notin @('127.0.0.1', 'localhost', '::1', '[::1]')) {
    Write-Error "SAFETY VIOLATION: Target host '$DbHost' is not permitted." -ErrorAction Continue
    exit 4
}
if ($Port -ne 5433) {
    Write-Error "SAFETY VIOLATION: Target port $Port is not permitted." -ErrorAction Continue
    exit 5
}

if ($CheckLiveConnection) {
    if ($MigrationUser -cnotmatch '\A[a-z_][a-z0-9_]{0,62}\z' -or
        $ExpectedDatabaseOid -eq 0 -or $ExpectedDatabaseOid -eq 16560 -or
        $ConfirmTarget -cne ('{0}:{1}' -f $DatabaseName, $ExpectedDatabaseOid)) {
        throw 'Live verification requires a role, expected OID and exact database:oid confirmation.'
    }
    . (Join-Path $PSScriptRoot 'hms-local-psql.ps1')
    $verifySql = @'
BEGIN READ ONLY;
DO $check$
BEGIN
    IF current_database() IS DISTINCT FROM '{0}'
       OR current_user IS DISTINCT FROM '{1}'
       OR NOT EXISTS (
           SELECT 1 FROM pg_catalog.pg_database WHERE datname = current_database() AND oid = {2}::oid
       )
       OR current_setting('server_version_num')::integer < 180001
       OR pg_catalog.inet_server_port() IS DISTINCT FROM 5433
       OR pg_catalog.host(pg_catalog.inet_server_addr()) IS DISTINCT FROM '127.0.0.1' THEN
        RAISE EXCEPTION 'Local database identity verification failed';
    END IF;
END;
$check$;
COMMIT;
'@ -f $DatabaseName, $MigrationUser, $ExpectedDatabaseOid
    $connection = @{
        PsqlPath = $PsqlPath
        DatabaseName = $DatabaseName
        MigrationUser = $MigrationUser
        Password = $env:HMS_MIGRATION_PASSWORD
        Sql = $verifySql
    }
    $null = Invoke-HmsLocalPsql @connection
    Write-Host "Live database identity verified: $DatabaseName (OID $ExpectedDatabaseOid)"
}

Write-Host '=== Database Safety Check: PASSED ==='
exit 0
