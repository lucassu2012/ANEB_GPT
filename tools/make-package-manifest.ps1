[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

try {
    $rootFull = Get-AnEbFullPath -Path $Root
    Assert-AnEbDirectory -Path $rootFull | Out-Null
    $versionPath = Join-Path $rootFull 'VERSION.json'
    $version = Read-AnEbUtf8Strict -Path $versionPath | ConvertFrom-Json
    if ($version.release_state -ne 'RELEASE_CANDIDATE') {
        throw 'PACKAGE_NOT_RELEASE_CANDIDATE'
    }
    foreach ($required in @('bin\aneb-server.exe', 'android\aneb-prototype-0.1.apk')) {
        Assert-AnEbRegularFile -Path (Join-Path $rootFull $required) | Out-Null
    }

    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $OutputPath = Join-Path $rootFull 'SHA256SUMS.txt'
    }
    $outputFull = Get-AnEbFullPath -Path $OutputPath
    if (Test-Path -LiteralPath $outputFull) {
        throw 'PACKAGE_MANIFEST_TARGET_EXISTS'
    }
    $records = @(Get-AnEbRelativeFiles -Root $rootFull |
        Where-Object {
            $_.RelativePath -ne 'SHA256SUMS.txt' -and
            -not $_.RelativePath.StartsWith('results/', [System.StringComparison]::Ordinal)
        } |
        Sort-Object RelativePath)
    if ($records.Count -eq 0) {
        throw 'PACKAGE_MANIFEST_EMPTY'
    }
    $lines = @()
    foreach ($record in $records) {
        $hash = Get-AnEbSha256File -Path $record.FullPath
        $lines += ($hash + '  ' + $record.RelativePath)
    }
    $text = [string]::Join([char]10, $lines) + [char]10
    Write-AnEbCreateNewUtf8 -Path $outputFull -Text $text
    Write-Output ("PASS PACKAGE_MANIFEST files=" + $records.Count + " path=" + $outputFull)
    exit 0
}
catch {
    Write-Output ("FAIL P001_PACKAGE_INTEGRITY " + $_.Exception.Message)
    exit 1
}
