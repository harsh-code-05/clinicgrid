function New-HmsLocalPsqlStartInfo {
    param(
        [string]$PsqlPath,
        [string]$DatabaseName,
        [string]$MigrationUser,
        [string]$Password
    )

    if ([string]::IsNullOrWhiteSpace($Password)) {
        throw 'Set HMS_MIGRATION_PASSWORD explicitly for this process before connecting.'
    }
    if ($DatabaseName -cnotmatch '\A(?:postgres|hms_dev_[A-Za-z0-9_]{1,55})\z' -or
        $MigrationUser -cnotmatch '\A[a-z_][a-z0-9_]{0,62}\z') {
        throw 'Invalid local database or migration role.'
    }

    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = (Get-Item -LiteralPath $PsqlPath -ErrorAction Stop).FullName
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $info.Arguments = "-X -w -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5433 -U $MigrationUser -d $DatabaseName -f -"

    # Use an isolated child environment. Ignore inherited libpq service, host and auth settings.
    foreach ($key in @($info.EnvironmentVariables.Keys)) {
        if ($key -like 'PG*') {
            $info.EnvironmentVariables.Remove($key)
        }
    }
    $info.EnvironmentVariables.Remove('HMS_MIGRATION_PASSWORD')
    $info.EnvironmentVariables['PGPASSWORD'] = $Password
    $info.EnvironmentVariables['PGPASSFILE'] = Join-Path ([IO.Path]::GetTempPath()) ("hms-no-passfile-" + [Guid]::NewGuid().ToString('N'))
    $info.EnvironmentVariables['PGCONNECT_TIMEOUT'] = '5'
    $info.EnvironmentVariables['PGSSLMODE'] = 'disable'
    $info.EnvironmentVariables['PGCLIENTENCODING'] = 'UTF8'
    $info
}

function Invoke-HmsLocalPsql {
    param(
        [string]$PsqlPath,
        [string]$DatabaseName,
        [string]$MigrationUser,
        [string]$Password,
        [string]$Sql
    )

    $info = New-HmsLocalPsqlStartInfo $PsqlPath $DatabaseName $MigrationUser $Password
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) {
            throw 'Could not launch psql.'
        }
        $info.EnvironmentVariables.Remove('PGPASSWORD')
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        try {
            $process.StandardInput.WriteLine($Sql)
        } finally {
            $process.StandardInput.Close()
        }
        $process.WaitForExit()
        # Keep raw client errors out of the caller's console and logs.
        $null = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "psql rejected the local operation (exit $($process.ExitCode))."
        }
        $stdout.GetAwaiter().GetResult().Trim()
    } finally {
        $process.Dispose()
    }
}
