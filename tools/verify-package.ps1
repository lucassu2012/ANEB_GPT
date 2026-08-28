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

function Get-AnEbNamedValue {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    if ($null -eq $Object) {
        Stop-AnEbPackageCheck -Code 'P007_CONTRACT_MISMATCH' -Message $FailureMessage
    }
    foreach ($property in $Object.PSObject.Properties) {
        if ($property.Name -ceq $Name) {
            return $property.Value
        }
    }
    Stop-AnEbPackageCheck -Code 'P007_CONTRACT_MISMATCH' -Message $FailureMessage
}

function Get-AnEbCanonicalContractBytes {
    param([Parameter(Mandatory = $true)][string]$Path)
    Assert-AnEbRegularFile -Path $Path | Out-Null
    $raw = [System.IO.File]::ReadAllBytes((Get-AnEbFullPath -Path $Path))
    $text = ConvertFrom-AnEbUtf8Strict -Bytes $raw
    $crlf = [string][char]13 + [char]10
    $lf = [string][char]10
    $text = $text.Replace($crlf, $lf)
    if ($text.Contains([char]13)) {
        throw 'CONTRACT_BARE_CR_REJECTED'
    }
    if (-not $text.EndsWith($lf) -or $text.EndsWith($lf + $lf)) {
        throw 'CONTRACT_FINAL_LF_INVALID'
    }
    return (ConvertTo-AnEbUtf8NoBomBytes -Text $text)
}

function Assert-AnEbExactNameSet {
    param(
        [Parameter(Mandatory = $true)][string[]]$Actual,
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string]$FailureCode,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    if ($Actual.Count -ne $Expected.Count) {
        Stop-AnEbPackageCheck -Code $FailureCode -Message $FailureMessage
    }
    $seen = @()
    foreach ($item in $Actual) {
        foreach ($previous in $seen) {
            if ([string]::Equals($previous, $item, [System.StringComparison]::OrdinalIgnoreCase)) {
                Stop-AnEbPackageCheck -Code $FailureCode -Message $FailureMessage
            }
        }
        $seen += $item
        $matched = $false
        foreach ($expectedItem in $Expected) {
            if ([string]::Equals($expectedItem, $item, [System.StringComparison]::Ordinal)) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            Stop-AnEbPackageCheck -Code $FailureCode -Message $FailureMessage
        }
    }
    foreach ($expectedItem in $Expected) {
        $matched = $false
        foreach ($item in $Actual) {
            if ([string]::Equals($expectedItem, $item, [System.StringComparison]::Ordinal)) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            Stop-AnEbPackageCheck -Code $FailureCode -Message $FailureMessage
        }
    }
}

function Assert-AnEbContractBinding {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$Version
    )
    $contractNames = @(
        'profile-manifest.json',
        'capabilities.schema.json',
        'run-record.schema.json',
        'score-policy.json'
    )
    $contractRoot = Join-Path $Root 'contracts\prototype-0.1'
    try {
        Assert-AnEbDirectory -Path $contractRoot | Out-Null
    }
    catch {
        Stop-AnEbPackageCheck -Code 'P007_CONTRACT_LAYOUT_MISSING' -Message 'DN5A_MISSING_CONTRACT contract directory'
    }
    $actualRows = @(Get-AnEbRelativeFiles -Root $contractRoot)
    $actualNames = @($actualRows | Select-Object -ExpandProperty RelativePath)
    Assert-AnEbExactNameSet -Actual $actualNames -Expected $contractNames -FailureCode 'P007_CONTRACT_LAYOUT' -FailureMessage 'DN5A_EXTRA_CONTRACT or missing exact contract path'

    $declaredFiles = @(Get-AnEbNamedValue -Object $Version -Name 'contract_files' -FailureMessage 'VERSION contract_files missing')
    if ($declaredFiles.Count -ne $contractNames.Count) {
        Stop-AnEbPackageCheck -Code 'P007_CONTRACT_MISMATCH' -Message 'VERSION contract_files set is not exact'
    }
    for ($index = 0; $index -lt $contractNames.Count; $index++) {
        if (-not ($declaredFiles[$index] -is [string]) -or -not [string]::Equals($declaredFiles[$index], $contractNames[$index], [System.StringComparison]::Ordinal)) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_MISMATCH' -Message 'VERSION contract_files order is not exact'
        }
    }

    $declaredPaths = Get-AnEbNamedValue -Object $Version -Name 'contract_paths' -FailureMessage 'VERSION contract_paths missing'
    $declaredSizes = Get-AnEbNamedValue -Object $Version -Name 'contract_sizes' -FailureMessage 'VERSION contract_sizes missing'
    $declaredHashes = Get-AnEbNamedValue -Object $Version -Name 'contract_hashes' -FailureMessage 'VERSION contract_hashes missing'
    Assert-AnEbExactNameSet -Actual @($declaredPaths.PSObject.Properties | ForEach-Object { $_.Name }) -Expected $contractNames -FailureCode 'P007_CONTRACT_MISMATCH' -FailureMessage 'VERSION contract_paths set is not exact'
    Assert-AnEbExactNameSet -Actual @($declaredSizes.PSObject.Properties | ForEach-Object { $_.Name }) -Expected $contractNames -FailureCode 'P007_CONTRACT_MISMATCH' -FailureMessage 'VERSION contract_sizes set is not exact'
    Assert-AnEbExactNameSet -Actual @($declaredHashes.PSObject.Properties | ForEach-Object { $_.Name }) -Expected $contractNames -FailureCode 'P007_CONTRACT_MISMATCH' -FailureMessage 'VERSION contract_hashes set is not exact'

    foreach ($name in $contractNames) {
        $expectedRelative = 'contracts/prototype-0.1/' + $name
        $relativePath = Get-AnEbNamedValue -Object $declaredPaths -Name $name -FailureMessage ('VERSION contract path missing: ' + $name)
        if (-not ($relativePath -is [string]) -or -not [string]::Equals($relativePath, $expectedRelative, [System.StringComparison]::Ordinal)) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_PATH_BINDING_MISMATCH' -Message ('DN5A_HASH_BINDING_MISMATCH path ' + $name)
        }
        $path = Join-Path $Root ($expectedRelative -replace '/', '\')
        try {
            Assert-AnEbRegularFile -Path $path | Out-Null
        }
        catch {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_LAYOUT' -Message ('DN5A_UNSAFE_PATH ' + $name)
        }
        $canonicalBytes = $null
        try {
            $canonicalBytes = Get-AnEbCanonicalContractBytes -Path $path
            $canonicalText = ConvertFrom-AnEbUtf8Strict -Bytes $canonicalBytes
            $null = ($canonicalText | ConvertFrom-Json)
        }
        catch {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_JSON_INVALID' -Message ('contract is not strict canonical JSON: ' + $name)
        }
        $expectedSize = Get-AnEbNamedValue -Object $declaredSizes -Name $name -FailureMessage ('VERSION contract size missing: ' + $name)
        if (($expectedSize -isnot [int]) -and ($expectedSize -isnot [long])) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_SIZE_BINDING_MISMATCH' -Message ('DN5A_HASH_BINDING_MISMATCH size type ' + $name)
        }
        if ([int64]$expectedSize -ne [int64]$canonicalBytes.Length) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_SIZE_BINDING_MISMATCH' -Message ('DN5A_HASH_BINDING_MISMATCH size ' + $name)
        }
        $expectedHash = Get-AnEbNamedValue -Object $declaredHashes -Name $name -FailureMessage ('VERSION contract hash missing: ' + $name)
        if (-not ($expectedHash -is [string]) -or $expectedHash -cnotmatch '^[0-9a-f]{64}$') {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_HASH_BINDING_MISMATCH' -Message ('DN5A_HASH_BINDING_MISMATCH VERSION hash format ' + $name)
        }
        $actualHash = Get-AnEbSha256Bytes -Bytes $canonicalBytes
        if (-not [string]::Equals($actualHash, $expectedHash, [System.StringComparison]::Ordinal)) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_HASH_MISMATCH' -Message ('DN5A_HASH_MISMATCH VERSION contract hash ' + $name)
        }
    }
    $scheduleNames = @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')
    $declaredSchedules = Get-AnEbNamedValue -Object $Version -Name 'schedule_hashes' -FailureMessage 'VERSION schedule_hashes missing'
    Assert-AnEbExactNameSet -Actual @($declaredSchedules.PSObject.Properties | ForEach-Object { $_.Name }) -Expected $scheduleNames -FailureCode 'P007_CONTRACT_MISMATCH' -FailureMessage 'VERSION schedule_hashes set is not exact'
    $profilePath = Join-Path $Root 'contracts\prototype-0.1\profile-manifest.json'
    $profileBytes = Get-AnEbCanonicalContractBytes -Path $profilePath
    $profileText = ConvertFrom-AnEbUtf8Strict -Bytes $profileBytes
    $profile = $null
    try {
        $profile = $profileText | ConvertFrom-Json
    }
    catch {
        Stop-AnEbPackageCheck -Code 'P007_CONTRACT_JSON_INVALID' -Message 'profile manifest JSON is invalid'
    }
    $conditions = @(Get-AnEbNamedValue -Object $profile -Name 'conditions' -FailureMessage 'profile conditions missing')
    if ($conditions.Count -ne $scheduleNames.Count) {
        Stop-AnEbPackageCheck -Code 'P007_CONTRACT_MISMATCH' -Message 'profile condition set is not exact'
    }
    foreach ($condition in $conditions) {
        $conditionId = Get-AnEbNamedValue -Object $condition -Name 'id' -FailureMessage 'profile condition id missing'
        $conditionHash = Get-AnEbNamedValue -Object $condition -Name 'schedule_sha256' -FailureMessage ('profile schedule hash missing: ' + $conditionId)
        if (-not ($conditionId -is [string]) -or -not ($scheduleNames -contains $conditionId) -or
            -not ($conditionHash -is [string]) -or $conditionHash -cnotmatch '^[0-9a-f]{64}$') {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_MISMATCH' -Message 'profile schedule identity is not exact'
        }
        $declaredHash = Get-AnEbNamedValue -Object $declaredSchedules -Name $conditionId -FailureMessage ('VERSION schedule hash missing: ' + $conditionId)
        if (-not [string]::Equals($declaredHash, $conditionHash, [System.StringComparison]::Ordinal)) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_HASH_BINDING_MISMATCH' -Message ('DN5A_HASH_BINDING_MISMATCH VERSION schedule ' + $conditionId)
        }
    }
    Write-Output 'PASS CONTRACT_BINDING exact_four canonical_path_size_sha schedule_binding'
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
    Assert-AnEbContractBinding -Root $rootFull -Version $version
    if ($version.release_state -eq 'SKELETON_NOT_RELEASE') {
        $checksumText = Read-AnEbUtf8Strict -Path (Join-Path $rootFull 'SHA256SUMS.txt')
        $markerLines = @($checksumText -split '\r?\n' | Where-Object { $_ -ceq '# SKELETON_NOT_RELEASE' })
        if ($markerLines.Count -ne 1) {
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
