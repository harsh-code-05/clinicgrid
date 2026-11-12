[CmdletBinding()]
param (
    [Parameter(Mandatory)]
    [string] $InputCsv,

    [Parameter(Mandatory)]
    [ValidateRange(1, 10000)]
    [int] $ExpectedDistrictCount
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$rows = @(Import-Csv -LiteralPath $InputCsv -Encoding UTF8)
if ($rows.Count -ne $ExpectedDistrictCount) {
    throw "Expected $ExpectedDistrictCount districts, found $($rows.Count)."
}

$districtCodes = [System.Collections.Generic.HashSet[int]]::new()
$stateNames = @{}
$districts = foreach ($row in $rows) {
    if ($row.district_code -notmatch '^[1-9][0-9]*$' -or $row.state_code -notmatch '^[1-9][0-9]*$') {
        throw 'The LGD export must contain positive numeric district and state codes.'
    }
    $districtCode = [int] $row.district_code
    $stateCode = [int] $row.state_code
    $districtName = $row.district_name_english
    $stateName = $row.state_name_english
    if ([string]::IsNullOrWhiteSpace($districtName) -or [string]::IsNullOrWhiteSpace($stateName) -or
        $districtName -match '[\t\r\n]' -or $stateName -match '[\t\r\n]') {
        throw "District $districtCode has a missing or invalid English name or state name."
    }
    if (-not $districtCodes.Add($districtCode)) {
        throw "Duplicate LGD district code $districtCode."
    }
    if ($stateNames.ContainsKey($stateCode) -and $stateNames[$stateCode] -cne $stateName) {
        throw "Inconsistent English names for LGD state code $stateCode."
    }
    $stateNames[$stateCode] = $stateName
    [pscustomobject]@{
        DistrictCode = $districtCode
        DistrictName = $districtName
        StateCode = $stateCode
        StateName = $stateName
    }
}

if ($stateNames.Count -ne 36) {
    throw "Expected coverage of 36 states and union territories, found $($stateNames.Count)."
}

$lines = @("district_code`tdistrict_name`tstate_code`tstate_name")
$lines += $districts | Sort-Object DistrictCode | ForEach-Object {
    "$($_.DistrictCode)`t$($_.DistrictName)`t$($_.StateCode)`t$($_.StateName)"
}
$output = Join-Path $PSScriptRoot '../src/main/resources/locations/lgd-districts.tsv'
[void] [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($output))
[System.IO.File]::WriteAllText($output, ($lines -join "`n") + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "Imported $($rows.Count) districts across $($stateNames.Count) states and union territories."
