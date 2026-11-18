# Pester tests for HMS Neon Migration Deployment Script (scripts/deploy-neon-migrations.ps1)

$scriptDir = $PSScriptRoot
$deployScript = Join-Path $scriptDir "deploy-neon-migrations.ps1"

function Invoke-DeployScriptCapture {
    param(
        [string[]]$ArgumentList,
        [hashtable]$EnvVars = @{}
    )
    $allArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $deployScript) + $ArgumentList
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "powershell.exe"
    $psi.Arguments = [string]::Join(" ", ($allArgs | ForEach-Object {
        if ($_ -match '[\s"]') { '"{0}"' -f ($_ -replace '"', '\"') } else { $_ }
    }))
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    # Clear sensitive ambient variables
    $psi.EnvironmentVariables.Remove('HMS_MIGRATION_PASSWORD')
    $psi.EnvironmentVariables.Remove('PGPASSWORD')

    foreach ($key in $EnvVars.Keys) {
        $psi.EnvironmentVariables[$key] = $EnvVars[$key]
    }

    $p = [System.Diagnostics.Process]::Start($psi)
    $stdout = $p.StandardOutput.ReadToEnd()
    $stderr = $p.StandardError.ReadToEnd()
    $p.WaitForExit()

    return [PSCustomObject]@{
        ExitCode = $p.ExitCode
        Output = ($stdout + "`n" + $stderr).Trim()
    }
}

Describe "HMS Neon Deployment Script Preflight Safety Tests" {

    Context "Database name safety validation" {
        It "Rejects empty or whitespace database name" {
            $res = Invoke-DeployScriptCapture -ArgumentList @("-ValidateOnly", "-DatabaseName", "   ")
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "DatabaseName cannot be empty or whitespace"
        }

        It "Rejects forbidden production database name 'hospital_management'" {
            $res = Invoke-DeployScriptCapture -ArgumentList @("-ValidateOnly", "-DatabaseName", "hospital_management")
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "CRITICAL SAFETY VIOLATION: DatabaseName 'hospital_management' is forbidden"
        }

        It "Rejects development database name with prefix 'hms_dev_'" {
            $res = Invoke-DeployScriptCapture -ArgumentList @("-ValidateOnly", "-DatabaseName", "hms_dev_test_abc123")
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "CRITICAL SAFETY VIOLATION: DatabaseName 'hms_dev_test_abc123' is forbidden for Neon deployment"
        }

        It "Rejects reserved databases 'postgres' and 'template1'" {
            $res = Invoke-DeployScriptCapture -ArgumentList @("-ValidateOnly", "-DatabaseName", "postgres")
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "CRITICAL SAFETY VIOLATION"
        }
    }

    Context "Role privilege separation validation" {
        It "Rejects superuser 'postgres' as MigrationUser" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "postgres",
                "-ApplicationUser", "hms_app_role"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "Dedicated migration role required. 'postgres' and superuser roles are strictly prohibited"
        }

        It "Rejects identical MigrationUser and ApplicationUser" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_shared_user",
                "-ApplicationUser", "hms_shared_user"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "MigrationUser and ApplicationUser must be distinct roles"
        }

        It "Rejects superuser 'postgres' as ApplicationUser" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_user",
                "-ApplicationUser", "postgres"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "Application runtime role cannot be 'postgres' or superuser"
        }
    }

    Context "OID and ConfirmTarget verification" {
        It "Rejects missing or zero ExpectedDatabaseOid" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_role",
                "-ApplicationUser", "hms_app_role",
                "-ExpectedDatabaseOid", "0"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "ExpectedDatabaseOid must be a valid positive integer OID"
        }

        It "Rejects ConfirmTarget mismatch" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_role",
                "-ApplicationUser", "hms_app_role",
                "-ExpectedDatabaseOid", "30500",
                "-ConfirmTarget", "hms_prod_neon:99999"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "ConfirmTarget 'hms_prod_neon:99999' does not match required value 'hms_prod_neon:30500'"
        }
    }

    Context "Host endpoint and TLS network safety" {
        It "Rejects loopback host '127.0.0.1'" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_role",
                "-ApplicationUser", "hms_app_role",
                "-ExpectedDatabaseOid", "30500",
                "-ConfirmTarget", "hms_prod_neon:30500",
                "-DbHost", "127.0.0.1"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "Loopback hosts are forbidden"
        }

        It "Rejects 'localhost'" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_role",
                "-ApplicationUser", "hms_app_role",
                "-ExpectedDatabaseOid", "30500",
                "-ConfirmTarget", "hms_prod_neon:30500",
                "-DbHost", "localhost"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "Loopback hosts are forbidden"
        }

        It "Rejects pooled connection hosts containing '-pooler.'" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_role",
                "-ApplicationUser", "hms_app_role",
                "-ExpectedDatabaseOid", "30500",
                "-ConfirmTarget", "hms_prod_neon:30500",
                "-DbHost", "ep-weathered-wind-12345-pooler.us-east-2.aws.neon.tech"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "Migrations require direct database connections"
        }
    }

    Context "Password requirement on Apply execution" {
        It "Throws error when HMS_MIGRATION_PASSWORD process env var is missing during Apply" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_role",
                "-ApplicationUser", "hms_app_role",
                "-ExpectedDatabaseOid", "30500",
                "-ConfirmTarget", "hms_prod_neon:30500",
                "-DbHost", "ep-weathered-wind-12345.us-east-2.aws.neon.tech"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "Set the process environment variable HMS_MIGRATION_PASSWORD before applying migrations"
        }
    }

    Context "Manifest and preflight validation success" {
        It "Successfully validates canonical manifest and checksums under ValidateOnly mode" {
            $res = Invoke-DeployScriptCapture -ArgumentList @(
                "-ValidateOnly",
                "-DatabaseName", "hms_prod_neon",
                "-MigrationUser", "hms_mig_role",
                "-ApplicationUser", "hms_app_role",
                "-ExpectedDatabaseOid", "30500",
                "-ConfirmTarget", "hms_prod_neon:30500",
                "-DbHost", "ep-weathered-wind-12345.us-east-2.aws.neon.tech"
            )
            $res.ExitCode | Should Be 0
            $res.Output | Should Match "Successfully verified 9 canonical migrations and SHA-256 byte checksums against manifest"
            $res.Output | Should Match "Validation passed"
        }
    }
}
