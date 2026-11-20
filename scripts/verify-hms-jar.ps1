[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $JarPath
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $repo 'database/migration-manifest.json'
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "JAR not found: $JarPath" }
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Migration manifest not found' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($manifest.formatVersion -ne 1 -or @($manifest.migrations).Count -ne 9) { throw 'Manifest format or migration count is invalid' }
if (-not $manifest.catalogDigest -or -not $manifest.releaseVerifier) { throw 'Manifest auxiliary resource references are missing' }

$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath))
try {
    $classPathPrefix = 'BOOT-INF/classes/'
    $auxiliary = @($manifest.catalogDigest, $manifest.releaseVerifier)
    foreach ($resource in $auxiliary) {
        if ($resource.path -notmatch '^database/[^./][^\\]*$' -or $resource.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Malformed auxiliary resource reference' }
    }
    $verifiers = @($manifest.migrations | Where-Object { $_.PSObject.Properties.Name -contains 'verifier' -and $null -ne $_.verifier } | ForEach-Object { $_.verifier })
    foreach ($v in $verifiers) {
        if ($v.path -notmatch '^database/[^./][^\\]*$' -or $v.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Malformed verifier resource reference' }
    }
    $required = @('database/migration-manifest.json') + @($auxiliary | ForEach-Object { $_.path }) + @($manifest.migrations | ForEach-Object { $_.path }) + @($verifiers | ForEach-Object { $_.path })
    foreach ($path in $required) {
        if (-not $archive.GetEntry($classPathPrefix + $path)) { throw "Missing packaged resource: $path" }
    }

    $manifestEntry = $archive.GetEntry($classPathPrefix + 'database/migration-manifest.json')
    $manifestStream = $manifestEntry.Open()
    try {
        $packagedHash = (Get-FileHash -InputStream $manifestStream -Algorithm SHA256).Hash.ToLowerInvariant()
    } finally { $manifestStream.Dispose() }
    $sourceManifestBytes = [IO.File]::ReadAllBytes($manifestPath)
    $sourceSha = [Security.Cryptography.SHA256]::Create()
    try {
        $sourceManifestHash = [BitConverter]::ToString($sourceSha.ComputeHash($sourceManifestBytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sourceSha.Dispose()
    }
    if ($packagedHash -ne $sourceManifestHash) { throw 'Packaged manifest differs from source manifest' }

    foreach ($migration in $manifest.migrations) {
        $entry = $archive.GetEntry($classPathPrefix + $migration.path)
        $stream = $entry.Open()
        try {
            $hash = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash.ToLowerInvariant()
        } finally { $stream.Dispose() }
        if ($hash -ne $migration.sha256) { throw "Checksum mismatch: $($migration.path)" }
    }
    foreach ($v in $verifiers) {
        $entry = $archive.GetEntry($classPathPrefix + $v.path)
        $stream = $entry.Open()
        try {
            $hash = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash.ToLowerInvariant()
        } finally { $stream.Dispose() }
        if ($hash -ne $v.sha256) { throw "Checksum mismatch: $($v.path)" }
    }
    foreach ($resource in $auxiliary) {
        $entry = $archive.GetEntry($classPathPrefix + $resource.path)
        $stream = $entry.Open()
        try {
            $hash = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash.ToLowerInvariant()
        } finally { $stream.Dispose() }
        if ($hash -ne $resource.sha256) { throw "Checksum mismatch: $($resource.path)" }
    }
    Write-Output "PASS: packaged manifest, digest SQL, $(@($manifest.migrations).Count) migration SQL and $(@($verifiers).Count) verifier SQL resources verified"
} finally {
    $archive.Dispose()
}
