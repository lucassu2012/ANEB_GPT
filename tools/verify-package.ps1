[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$AllowSkeleton,
    [string]$AdmissionReceiptPath = '',
    [string]$ExpectedAdmissionReceiptSha256 = ''
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
    $frozenContractSizes = @{
        'profile-manifest.json' = [int64]3797
        'capabilities.schema.json' = [int64]3535
        'run-record.schema.json' = [int64]21873
        'score-policy.json' = [int64]3074
    }
    $frozenContractHashes = @{
        'profile-manifest.json' = '44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc'
        'capabilities.schema.json' = 'f73c974a70b4b3ba457c619bf6f06d56ad2af4af5f91c364612e04cac12ccee6'
        'run-record.schema.json' = '8106dacc0b2600f768bb5e8fd21fb99b5bae5611dc7ea2cbafdb416bedb74c66'
        'score-policy.json' = '0e00a049861e9598c7bc4b76d25713dbfca59c266924ab0d17d4a00078d993cc'
    }
    $contractRoot = Join-Path $Root 'contracts'
    try {
        Assert-AnEbDirectory -Path $contractRoot | Out-Null
    }
    catch {
        Stop-AnEbPackageCheck -Code 'P007_CONTRACT_LAYOUT_MISSING' -Message 'DN5A_MISSING_CONTRACT contract directory'
    }
    $contractEntries = @(Get-ChildItem -LiteralPath $Root -Force -ErrorAction Stop |
        Where-Object {
            $_.PSIsContainer -and
            [string]::Equals([string]$_.Name, 'contracts', [System.StringComparison]::OrdinalIgnoreCase)
        })
    if ($contractEntries.Count -ne 1 -or
        -not [string]::Equals([string]$contractEntries[0].Name, 'contracts', [System.StringComparison]::Ordinal)) {
        Stop-AnEbPackageCheck -Code 'P007_CONTRACT_CASE_MISMATCH' -Message 'contract directory casing is not the canonical exact spelling'
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
        $expectedRelative = 'contracts/' + $name
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
        $rawBytes = $null
        $canonicalBytes = $null
        try {
            $rawBytes = Read-AnEbBytes -Path $path
            $canonicalBytes = Get-AnEbCanonicalContractBytes -Path $path
            if ($rawBytes.Length -ne $canonicalBytes.Length) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_HASH_MISMATCH' -Message ('VERSION contract bytes are not canonical for the G0 frozen trust root: ' + $name)
            }
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
        if (-not $frozenContractSizes.ContainsKey($name) -or [int64]$expectedSize -ne [int64]$frozenContractSizes[$name]) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_SIZE_BINDING_MISMATCH' -Message ('VERSION contract size does not match the G0 frozen trust root: ' + $name)
        }
        if ([int64]$expectedSize -ne [int64]$rawBytes.Length) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_SIZE_BINDING_MISMATCH' -Message ('DN5A_HASH_BINDING_MISMATCH size ' + $name)
        }
        $expectedHash = Get-AnEbNamedValue -Object $declaredHashes -Name $name -FailureMessage ('VERSION contract hash missing: ' + $name)
        if (-not ($expectedHash -is [string]) -or $expectedHash -cnotmatch '^[0-9a-f]{64}$') {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_HASH_BINDING_MISMATCH' -Message ('DN5A_HASH_BINDING_MISMATCH VERSION hash format ' + $name)
        }
        if (-not $frozenContractHashes.ContainsKey($name) -or -not [string]::Equals($expectedHash, $frozenContractHashes[$name], [System.StringComparison]::Ordinal)) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_HASH_BINDING_MISMATCH' -Message ('VERSION contract hash does not match the G0 frozen trust root: ' + $name)
        }
        $actualHash = Get-AnEbSha256Bytes -Bytes $rawBytes
        if (-not [string]::Equals($actualHash, $expectedHash, [System.StringComparison]::Ordinal)) {
            Stop-AnEbPackageCheck -Code 'P007_CONTRACT_HASH_MISMATCH' -Message ('DN5A_HASH_MISMATCH VERSION contract hash ' + $name)
        }
    }
    $scheduleNames = @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')
    $declaredSchedules = Get-AnEbNamedValue -Object $Version -Name 'schedule_hashes' -FailureMessage 'VERSION schedule_hashes missing'
    Assert-AnEbExactNameSet -Actual @($declaredSchedules.PSObject.Properties | ForEach-Object { $_.Name }) -Expected $scheduleNames -FailureCode 'P007_CONTRACT_MISMATCH' -FailureMessage 'VERSION schedule_hashes set is not exact'
    $profilePath = Join-Path $Root 'contracts\profile-manifest.json'
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

function Assert-AnEbReleaseAdmission {
    param([Parameter(Mandatory = $true)]$Version)
    $requiredFields = @(
        'release_candidate',
        'source_commit',
        'built_at_utc',
        'server_version',
        'android_version_name',
        'android_version_code',
        'workload_id',
        'condition_versions',
        'evidence_schema',
        'score_policy',
        'artifact_admission'
    )
    foreach ($field in $requiredFields) {
        $null = Get-AnEbNamedValue -Object $Version -Name $field -FailureMessage ('VERSION release admission field missing: ' + $field)
    }
    if ($Version.specification_status -cne 'G0_APPROVED') {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION specification_status is not G0_APPROVED'
    }
    if (-not ($Version.release_candidate -is [string]) -or [string]::IsNullOrWhiteSpace([string]$Version.release_candidate)) {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION release_candidate is empty'
    }
    if (-not ($Version.source_commit -is [string]) -or $Version.source_commit -cnotmatch '^[0-9a-f]{40}$') {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION source_commit is not a lowercase commit hash'
    }
    if (-not ($Version.built_at_utc -is [string]) -or $Version.built_at_utc -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$') {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION built_at_utc is not RFC3339 UTC'
    }
    foreach ($field in @('server_version', 'android_version_name')) {
        if (-not ($Version.$field -is [string]) -or [string]::IsNullOrWhiteSpace([string]$Version.$field)) {
            Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message ('VERSION ' + $field + ' is empty')
        }
    }
    if (($Version.android_version_code -isnot [int]) -and ($Version.android_version_code -isnot [long]) -or [int64]$Version.android_version_code -lt 1) {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION android_version_code is invalid'
    }
    if ($Version.workload_id -cne 'streaming_text_reference_v0.1') {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION workload_id is not the G0 workload'
    }
    $expectedConditions = @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')
    $actualConditions = @($Version.condition_versions)
    if ($actualConditions.Count -ne $expectedConditions.Count) {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION condition_versions is not exact'
    }
    for ($index = 0; $index -lt $expectedConditions.Count; $index++) {
        if (-not [string]::Equals([string]$actualConditions[$index], $expectedConditions[$index], [System.StringComparison]::Ordinal)) {
            Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION condition_versions order is not exact'
        }
    }
    if ($Version.evidence_schema -cne 'aneb-prototype-evidence-0.1' -or $Version.score_policy -cne 'rpi-0.1') {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION evidence or score policy is not G0-bound'
    }
    if ($Version.server_artifact -cne 'bin/aneb-server.exe' -or $Version.android_artifact -cne 'android/aneb-prototype-0.1.apk') {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION artifact paths are not exact'
    }
    if ($Version.artifact_admission -cne 'REAL_ARTIFACTS_BOUND') {
        Stop-AnEbPackageCheck -Code 'P007_VERSION_ADMISSION' -Message 'VERSION artifact admission is not REAL_ARTIFACTS_BOUND'
    }
}

function Assert-AnEbArtifactFormat {
    param(
        [Parameter(Mandatory = $true)][string]$ServerPath,
        [Parameter(Mandatory = $true)][string]$AndroidPath
    )
    $serverBytes = Read-AnEbBytes -Path $ServerPath
    if ($serverBytes.Length -lt 64 -or $serverBytes[0] -ne 0x4d -or $serverBytes[1] -ne 0x5a) {
        Stop-AnEbPackageCheck -Code 'P001_ARTIFACT_FORMAT' -Message 'server artifact is not a valid PE container'
    }
    $peOffset = [int64]$serverBytes[0x3c] -bor ([int64]$serverBytes[0x3d] -shl 8) -bor ([int64]$serverBytes[0x3e] -shl 16) -bor ([int64]$serverBytes[0x3f] -shl 24)
    if ($peOffset -lt 64 -or $peOffset -gt ($serverBytes.Length - 4) -or
        $serverBytes[$peOffset] -ne 0x50 -or $serverBytes[$peOffset + 1] -ne 0x45 -or
        $serverBytes[$peOffset + 2] -ne 0x00 -or $serverBytes[$peOffset + 3] -ne 0x00) {
        Stop-AnEbPackageCheck -Code 'P001_ARTIFACT_FORMAT' -Message 'server artifact has no valid PE signature'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead((Get-AnEbFullPath -Path $AndroidPath))
        $manifestEntry = $archive.GetEntry('AndroidManifest.xml')
        if ($null -eq $manifestEntry -or $manifestEntry.Length -le 0) {
            Stop-AnEbPackageCheck -Code 'P001_ARTIFACT_FORMAT' -Message 'APK has no nonempty AndroidManifest.xml'
        }
    }
    catch {
        Stop-AnEbPackageCheck -Code 'P001_ARTIFACT_FORMAT' -Message 'Android artifact is not a readable APK archive'
    }
    finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
    }
}

function Assert-AnEbExternalArtifactAdmission {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)]$Version,
        [Parameter(Mandatory = $true)][string]$ServerPath,
        [Parameter(Mandatory = $true)][string]$AndroidPath,
        [Parameter(Mandatory = $true)][string]$ReceiptPath,
        [Parameter(Mandatory = $true)][string]$ExpectedReceiptSha256
    )
    $packageFull = (Get-AnEbFullPath -Path $PackageRoot).TrimEnd('\')
    $receiptFull = Get-AnEbFullPath -Path $ReceiptPath
    if ([string]::Equals($packageFull, $receiptFull.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase) -or
        $receiptFull.StartsWith($packageFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'artifact admission receipt must be outside the package root'
    }
    if ($ExpectedReceiptSha256 -cnotmatch '^[0-9a-f]{64}$') {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'external artifact admission receipt pin is missing or invalid'
    }
    try {
        Assert-AnEbRegularFile -Path $receiptFull | Out-Null
        Assert-AnEbExistingParents -Path $receiptFull
        $receipt = Read-AnEbUtf8Strict -Path $receiptFull | ConvertFrom-Json
    }
    catch {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'external artifact admission receipt is unreadable'
    }
    if (-not [string]::Equals((Get-AnEbSha256File -Path $receiptFull), $ExpectedReceiptSha256, [System.StringComparison]::Ordinal)) {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'external artifact admission receipt pin mismatch'
    }
    $receiptFields = @(
        'schema_version',
        'admission_status',
        'source_commit',
        'server_path',
        'server_version',
        'server_sha256',
        'android_path',
        'android_version_name',
        'android_version_code',
        'android_sha256',
        'android_signer_cert_sha256'
    )
    Assert-AnEbExactNameSet -Actual @($receipt.PSObject.Properties | ForEach-Object { $_.Name }) -Expected $receiptFields -FailureCode 'P007_ARTIFACT_ADMISSION' -FailureMessage 'external artifact admission receipt fields are not exact'
    if ($receipt.schema_version -cne 'aneb-prototype-artifact-admission-0.1' -or $receipt.admission_status -cne 'G0_ARTIFACT_ADMITTED') {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'external artifact admission receipt status is not approved'
    }
    if ($receipt.source_commit -cne $Version.source_commit -or
        $receipt.server_path -cne 'bin/aneb-server.exe' -or
        $receipt.android_path -cne 'android/aneb-prototype-0.1.apk' -or
        $receipt.server_version -cne $Version.server_version -or
        $receipt.android_version_name -cne $Version.android_version_name -or
        [int64]$receipt.android_version_code -ne [int64]$Version.android_version_code) {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'external artifact admission receipt does not match VERSION provenance'
    }
    if ($receipt.server_sha256 -cnotmatch '^[0-9a-f]{64}$' -or $receipt.android_sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $receipt.android_signer_cert_sha256 -cnotmatch '^[0-9a-f]{64}$') {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'external artifact admission receipt hashes are invalid'
    }
    if ((Get-AnEbSha256File -Path $ServerPath) -cne $receipt.server_sha256 -or
        (Get-AnEbSha256File -Path $AndroidPath) -cne $receipt.android_sha256) {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'external artifact admission receipt artifact hash mismatch'
    }
}

try {
    $rootFull = Get-AnEbFullPath -Path $Root
    Assert-AnEbDirectory -Path $rootFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $rootFull
    Assert-AnEbTreeNoReparse -Root $rootFull

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
    Assert-AnEbReleaseAdmission -Version $version
    Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION_TRUST_ROOT_REQUIRED' -Message 'strict release remains NON_RC until an externally fixed artifact admission and verifier trust root is supplied'
    if ([string]::IsNullOrWhiteSpace($AdmissionReceiptPath)) {
        Stop-AnEbPackageCheck -Code 'P007_ARTIFACT_ADMISSION' -Message 'strict release requires an external frozen artifact admission receipt'
    }

    foreach ($relative in @('bin/aneb-server.exe', 'android/aneb-prototype-0.1.apk')) {
        $path = Join-Path $rootFull ($relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message ("missing required artifact " + $relative)
        }
        Assert-AnEbRegularFile -Path $path | Out-Null
    }
    Assert-AnEbExternalArtifactAdmission -PackageRoot $rootFull -Version $version -ServerPath (Join-Path $rootFull 'bin\aneb-server.exe') -AndroidPath (Join-Path $rootFull 'android\aneb-prototype-0.1.apk') -ReceiptPath $AdmissionReceiptPath -ExpectedReceiptSha256 $ExpectedAdmissionReceiptSha256
    Assert-AnEbArtifactFormat -ServerPath (Join-Path $rootFull 'bin\aneb-server.exe') -AndroidPath (Join-Path $rootFull 'android\aneb-prototype-0.1.apk')

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
    if ($_.Exception.Message -match '^FILE_REPARSE|^DIRECTORY_REPARSE|^DIRECTORY_MISSING|^PATH_REPARSE_ANCESTOR') {
        Stop-AnEbPackageCheck -Code 'P001_PACKAGE_INTEGRITY' -Message $_.Exception.Message
    }
    Write-Output ("FAIL P001_PACKAGE_INTEGRITY " + $_.Exception.Message)
    exit 1
}
