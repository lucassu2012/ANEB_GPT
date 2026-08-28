[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$AllowSkeleton
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

function Stop-AnEbPackageCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message
    )
    Write-Output "FAIL $Code $Message"
    exit 1
}

try {
    $rootFull = Get-AnEbFullPath -Path $Root
    Assert-AnEbDirectory -Path $rootFull | Out-Null

    $coreFiles = @(
        'START_ANEB.bat',
        'README_FIRST.md',
        'VERSION.json',
        'SHA256SUMS.txt',
        'static/report-template.html',
        'tools/common.ps1',
        'tools/doctor.ps1',
        'tools/finalize-campaign.ps1',
        'tools/launch.ps1',
        'tools/make-package-manifest.ps1',
        'tools/verify-package.ps1'
    )
    foreach ($relative in $coreFiles) {
        $path = Join-Path $rootFull ($relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message ("missing " + $relative)
        }
        try {
            Assert-AnEbRegularFile -Path $path | Out-Null
        }
        catch {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message ("unsafe " + $relative)
        }
    }

    $version = $null
    try {
        $version = Read-AnEbUtf8Strict -Path (Join-Path $rootFull 'VERSION.json') | ConvertFrom-Json
    }
    catch {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'VERSION.json is not strict UTF-8 JSON'
    }
    if ($version.product_version -ne 'prototype-0.1') {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'VERSION product_version mismatch'
    }
    if ($version.release_state -eq 'SKELETON_NOT_RELEASE') {
        $checksumText = Read-AnEbUtf8Strict -Path (Join-Path $rootFull 'SHA256SUMS.txt')
        if ($checksumText -notmatch '(?m)^# SKELETON_NOT_RELEASE$') {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'skeleton checksum marker missing'
        }
        if (-not $AllowSkeleton) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'skeleton is not a releasable package'
        }
        Assert-AnEbNoAbsoluteDeveloperPath -Root $rootFull
        Write-Output 'PASS SKELETON_CHECK core files and relative paths'
        Write-Output 'BLOCKED_ARTIFACTS server/APK are intentionally absent; no G3/G4 claim'
        exit 0
    }
    if ($version.release_state -ne 'RELEASE_CANDIDATE') {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'release_state is not RELEASE_CANDIDATE'
    }

    foreach ($relative in @('bin/aneb-server.exe', 'android/aneb-prototype-0.1.apk')) {
        $path = Join-Path $rootFull ($relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message ("missing required artifact " + $relative)
        }
        Assert-AnEbRegularFile -Path $path | Out-Null
    }

    $checksumPath = Join-Path $rootFull 'SHA256SUMS.txt'
    $checksumText = Read-AnEbUtf8Strict -Path $checksumPath
    $checksumLines = @($checksumText -split '\r?\n' | Where-Object { $_ -and -not $_.StartsWith('#') })
    if ($checksumLines.Count -eq 0) {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'checksum list is empty'
    }
    $seen = @{}
    foreach ($line in $checksumLines) {
        if ($line -notmatch '^([0-9a-fA-F]{64})  (.+)$') {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'invalid SHA256SUMS line'
        }
        $expectedHash = $Matches[1].ToLowerInvariant()
        $relative = $Matches[2]
        try {
            Assert-AnEbRelativePath -RelativePath $relative
        }
        catch {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'invalid checksum path'
        }
        if ($relative -eq 'SHA256SUMS.txt' -or $relative.StartsWith('results/')) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'checksum includes mutable or self file'
        }
        if ($seen.ContainsKey($relative)) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'duplicate checksum path'
        }
        $seen[$relative] = $expectedHash
        $filePath = Join-Path $rootFull ($relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message ("checksum target missing: " + $relative)
        }
        if ((Get-AnEbSha256File -Path $filePath) -ne $expectedHash) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message ("hash mismatch: " + $relative)
        }
    }
    $actualStatic = @(Get-AnEbRelativeFiles -Root $rootFull |
        Where-Object {
            $_.RelativePath -ne 'SHA256SUMS.txt' -and
            -not $_.RelativePath.StartsWith('results/', [System.StringComparison]::Ordinal)
        } |
        Select-Object -ExpandProperty RelativePath | Sort-Object)
    $listedStatic = @($seen.Keys | Sort-Object)
    $differences = @(Compare-Object -ReferenceObject $actualStatic -DifferenceObject $listedStatic)
    if ($differences.Count -ne 0) {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'checksum closure does not equal package files'
    }
    $template = Read-AnEbUtf8Strict -Path (Join-Path $rootFull 'static\report-template.html')
    if ($template -match '(?i)<script|src\s*=|href\s*=\s*["'']https?://|https?://') {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message 'offline report contains a remote dependency'
    }
    Assert-AnEbNoAbsoluteDeveloperPath -Root $rootFull
    Write-Output 'PASS PACKAGE_INTEGRITY checksum closure and artifact presence'
    Write-Output 'PASS OFFLINE_REPORT no remote dependency'
    exit 0
}
catch {
    if ($_.Exception.Message -match '^FILE_REPARSE|^DIRECTORY_REPARSE|^DIRECTORY_MISSING') {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message $_.Exception.Message
    }
    Write-Output ("FAIL P001_PACKAGE_INTEGRITY " + $_.Exception.Message)
    exit 1
}
