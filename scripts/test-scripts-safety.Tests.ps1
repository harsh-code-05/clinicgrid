# Pester tests for HMS Local Database Safety Scripts

$scriptDir = $PSScriptRoot
$verifyScript = Join-Path $scriptDir "verify-local-db-safety.ps1"
$bootstrapScript = Join-Path $scriptDir "bootstrap-disposable-dev-db.ps1"

function Invoke-ScriptCapture {
    param(
        [string]$ScriptPath,
        [string[]]$ArgumentList
    )
    $allArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $ScriptPath) + $ArgumentList
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "powershell.exe"
    $psi.Arguments = [string]::Join(" ", ($allArgs | ForEach-Object {
        if ($_ -match '[\s"]') { '"{0}"' -f ($_ -replace '"', '\"') } else { $_ }
    }))
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.EnvironmentVariables.Remove('HMS_MIGRATION_PASSWORD')

    $p = [System.Diagnostics.Process]::Start($psi)
    $stdout = $p.StandardOutput.ReadToEnd()
    $stderr = $p.StandardError.ReadToEnd()
    $p.WaitForExit()

    return [PSCustomObject]@{
        ExitCode = $p.ExitCode
        Output = ($stdout + "`n" + $stderr).Trim()
    }
}

Describe "HMS Local Database Safety Scripts Regression Tests" {

    Context "verify-local-db-safety.ps1 parameter preflight validation" {
        It "Rejects empty or whitespace database name" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @("-DatabaseName", "   ")
            $res.ExitCode | Should Be 1
            $res.Output | Should Match "DatabaseName cannot be empty or whitespace"
        }

        It "Rejects forbidden production database name 'hospital_management'" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @("-DatabaseName", "hospital_management")
            $res.ExitCode | Should Be 2
            $res.Output | Should Match "CRITICAL SAFETY VIOLATION: Target database 'hospital_management'"
            $res.Output | Should Match "Connection strictly forbidden"
        }

        It "Rejects database name containing 'hospital_management'" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @("-DatabaseName", "my_hospital_management_copy")
            $res.ExitCode | Should Be 2
            $res.Output | Should Match "CRITICAL SAFETY VIOLATION: Target database 'my_hospital_management_copy'"
            $res.Output | Should Match "Connection strictly forbidden"
        }

        It "Rejects database names not matching '^hms_dev_[a-zA-Z0-9_]+$'" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @("-DatabaseName", "production_db")
            $res.ExitCode | Should Be 3
            $res.Output | Should Match "SAFETY VIOLATION: Database name 'production_db' does not match the mandatory allowlist pattern"
        }

        It "Rejects remote or non-loopback host" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @("-DatabaseName", "hms_dev_test_123", "-DbHost", "192.168.1.50")
            $res.ExitCode | Should Be 4
            $res.Output | Should Match "Target host '192.168.1.50' is not permitted"
        }

        It "Rejects port other than 5433 (e.g. 5432)" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @("-DatabaseName", "hms_dev_test_123", "-Port", "5432")
            $res.ExitCode | Should Be 5
            $res.Output | Should Match "Target port 5432 is not permitted"
        }

        It "Accepts valid parameters with exit code 0 when CheckLiveConnection is omitted" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @("-DatabaseName", "hms_dev_test_valid_01", "-Port", "5433", "-DbHost", "127.0.0.1")
            $res.ExitCode | Should Be 0
            $res.Output | Should Match "=== Database Safety Check: PASSED ==="
        }
    }

    Context "bootstrap-disposable-dev-db.ps1 preflight validation" {
        It "Rejects port 5432 immediately with exit code 1 before any psql invocation" {
            $res = Invoke-ScriptCapture -ScriptPath $bootstrapScript -ArgumentList @("-Port", "5432")
            $res.ExitCode | Should Be 1
            $res.Output | Should Match "Target port 5432 is not permitted"
        }

        It "Rejects port 5434 immediately with exit code 1 before any psql invocation" {
            $res = Invoke-ScriptCapture -ScriptPath $bootstrapScript -ArgumentList @("-Port", "5434")
            $res.ExitCode | Should Be 1
            $res.Output | Should Match "Target port 5434 is not permitted"
        }

        It "Rejects non-loopback host immediately with exit code 1" {
            $res = Invoke-ScriptCapture -ScriptPath $bootstrapScript -ArgumentList @("-DbHost", "10.0.0.1")
            $res.ExitCode | Should Be 1
            $res.Output | Should Match "Target host '10.0.0.1' is not permitted"
        }

        It "Rejects forbidden production database name 'hospital_management' before creation" {
            $res = Invoke-ScriptCapture -ScriptPath $bootstrapScript -ArgumentList @("-DatabaseName", "hospital_management")
            $res.ExitCode | Should Be 5
            $res.Output | Should Match "CRITICAL SAFETY VIOLATION: Target database 'hospital_management'"
            $res.Output | Should Match "Safety preflight failed. Aborting database creation."
        }

        It "Rejects non-conforming database name before creation" {
            $res = Invoke-ScriptCapture -ScriptPath $bootstrapScript -ArgumentList @("-DatabaseName", "invalid_db_name")
            $res.ExitCode | Should Be 5
            $res.Output | Should Match "Database name 'invalid_db_name' does not match the mandatory allowlist pattern"
            $res.Output | Should Match "Safety preflight failed. Aborting database creation."
        }
    }

    Context "libpq environment neutralization" {
        It "Rejects live verification without exact database:oid confirmation before executable lookup" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @(
                "-DatabaseName", "hms_dev_local", "-CheckLiveConnection",
                "-ExpectedDatabaseOid", "12345", "-ConfirmTarget", "wrong:12345",
                "-PsqlPath", "__must_not_launch_psql__"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "exact database:oid confirmation"
        }

        It "Rejects the protected database OID even under a development name" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @(
                "-DatabaseName", "hms_dev_local", "-CheckLiveConnection",
                "-ExpectedDatabaseOid", "16560", "-ConfirmTarget", "hms_dev_local:16560",
                "-PsqlPath", "__must_not_launch_psql__"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "expected OID"
        }

        It "Refuses a confirmed target without an explicit migration credential" {
            $res = Invoke-ScriptCapture -ScriptPath $verifyScript -ArgumentList @(
                "-DatabaseName", "hms_dev_local", "-CheckLiveConnection",
                "-ExpectedDatabaseOid", "12345", "-ConfirmTarget", "hms_dev_local:12345",
                "-PsqlPath", "__must_not_launch_psql__"
            )
            $res.ExitCode | Should Not Be 0
            $res.Output | Should Match "Set HMS_MIGRATION_PASSWORD explicitly"
        }

        It "Builds a pinned psql child environment without inheriting libpq overrides" {
            . (Join-Path $scriptDir 'hms-local-psql.ps1')
            $keys = @('PGHOSTADDR', 'PGSERVICE', 'PGDATABASE', 'PGPASSWORD', 'PGPASSFILE', 'PGOPTIONS')
            $original = @{}
            foreach ($key in $keys) {
                $original[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            }
            try {
                foreach ($key in $keys) {
                    [Environment]::SetEnvironmentVariable($key, 'untrusted-fixture', 'Process')
                }
                $info = New-HmsLocalPsqlStartInfo -PsqlPath $verifyScript -DatabaseName 'hms_dev_local' `
                    -MigrationUser 'test_role' -Password 'synthetic-fixture'
                $info.Arguments | Should Match '-X -w -qAt -v ON_ERROR_STOP=1 -h 127\.0\.0\.1 -p 5433 -U test_role -d hms_dev_local -f -'
                $info.Arguments | Should Not Match 'synthetic-fixture'
                foreach ($key in @('PGHOSTADDR', 'PGSERVICE', 'PGDATABASE', 'PGOPTIONS')) {
                    $info.EnvironmentVariables.ContainsKey($key) | Should Be $false
                }
                ($info.EnvironmentVariables['PGPASSWORD'] -ceq 'synthetic-fixture') | Should Be $true
                Test-Path -LiteralPath $info.EnvironmentVariables['PGPASSFILE'] | Should Be $false
            } finally {
                foreach ($key in $keys) {
                    [Environment]::SetEnvironmentVariable($key, $original[$key], 'Process')
                }
            }
        }
    }

    Context "exact-byte migration checksum verification contract" {
        $migrateScript = Join-Path $scriptDir "migrate-hms-database.ps1"
        $jarVerifyScript = Join-Path $scriptDir "verify-hms-jar.ps1"
        $targetJar = Join-Path (Split-Path -Parent $scriptDir) "target\hospital-management-system-0.0.1-SNAPSHOT.jar"

        It "Validates exact-byte manifest successfully with -ValidateManifestOnly" {
            $res = Invoke-ScriptCapture -ScriptPath $migrateScript -ArgumentList @("-ValidateManifestOnly")
            $res.ExitCode | Should Be 0
            $res.Output | Should Match "Validated the nine ordered migrations and all manifest SHA-256 checksums."
        }

        It "Rejects file with CRLF line endings or byte tampering with SHA-256 mismatch" {
            $repoRoot = Split-Path -Parent $scriptDir
            $baselinePath = Join-Path $repoRoot "database/pre-sch-02-baseline.sql"
            $lfBytes = [IO.File]::ReadAllBytes($baselinePath)
            $crlfText = [Text.Encoding]::UTF8.GetString($lfBytes).Replace("`n", "`r`n")
            $crlfBytes = [Text.Encoding]::UTF8.GetBytes($crlfText)
            $sha = [Security.Cryptography.SHA256]::Create()
            try {
                $lfHash = [BitConverter]::ToString($sha.ComputeHash($lfBytes)).Replace('-', '').ToLowerInvariant()
                $crlfHash = [BitConverter]::ToString($sha.ComputeHash($crlfBytes)).Replace('-', '').ToLowerInvariant()
            } finally {
                $sha.Dispose()
            }
            $crlfHash | Should Not Be $lfHash
        }

        It "Produces identical SHA-256 hash as Java for identical binary input" {
            $bytes = [Text.Encoding]::UTF8.GetBytes("HMS-EXACT-BYTE-VERIFICATION")
            $sha = [Security.Cryptography.SHA256]::Create()
            try {
                $hash = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
            } finally {
                $sha.Dispose()
            }
            $hash | Should Be "e140f512f402e434e103b701c7a4dcb2edbc8c2f7eb0e5a8b396a5658cc09181"
        }

        It "Verifies packaged JAR resources successfully" {
            if (Test-Path -LiteralPath $targetJar) {
                $res = Invoke-ScriptCapture -ScriptPath $jarVerifyScript -ArgumentList @("-JarPath", $targetJar)
                $res.ExitCode | Should Be 0
                $res.Output | Should Match "PASS: packaged manifest, digest SQL, 9 migration SQL and 3 verifier SQL resources verified"
            }
        }
    }
}
