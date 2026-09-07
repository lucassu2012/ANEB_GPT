[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [Parameter(Mandatory = $true)][string]$SourceCommit,
    [Parameter(Mandatory = $true)][string]$ServerPath,
    [Parameter(Mandatory = $true)][string]$AndroidApkPath,
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
    [string]$ApkSignerPath = '',
    [string]$ApkAnalyzerPath = '',
    [string]$GoPath = '',
    [Parameter(Mandatory = $true)][string]$EvidenceCharacterizationBundlePath,
    [Parameter(Mandatory = $true)][string]$ArtifactBuildReceiptPath,
    [Parameter(Mandatory = $true)][string]$ExpectedArtifactBuildReceiptSha256,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$OutputZipPath = '',
    [Parameter(Mandatory = $true)][string]$AdmissionReceiptPath,
    [Parameter(Mandatory = $true)][string]$ReleaseCandidate,
    [Parameter(Mandatory = $true)][string]$BuiltAtUtc,
    [Parameter(Mandatory = $true)][string]$ServerVersion,
    [Parameter(Mandatory = $true)][string]$AndroidVersionName,
    [Parameter(Mandatory = $true)][int]$AndroidVersionCode,
    [ValidateRange(1, 65535)][int]$Port = 18088
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
. (Join-Path $PSScriptRoot 'common.ps1')

$approvedSignerCertificateSha256 = 'b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003'
$stagingRoot = $null
$zipStagingPath = $null
$zipStagingRoot = $null
$receiptCreated = $false
$outputCreated = $false
$zipCreated = $false
$evidenceInvalidRoot = $null
$outputFull = $null
$zipFull = $null
$receiptFull = $null
$stagingParentFull = $null
$zipParentFull = $null
$receiptParentFull = $null
$evidenceTempParentFull = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
$outputOwnershipToken = $null
$outputOwnershipMarker = $null
$zipSha256 = $null
$receiptSha256 = $null

function Test-AnEbCleanupTargetShape {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedParent,
        [Parameter(Mandatory = $true)][string]$ExpectedLeafPattern
    )
    $full = Get-AnEbFullPath -Path $Path
    $parentFull = (Get-AnEbFullPath -Path $ExpectedParent).TrimEnd('\')
    if (-not [string]::Equals((Split-Path -Parent $full).TrimEnd('\'), $parentFull, [System.StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetFileName($full) -cnotmatch $ExpectedLeafPattern) {
        return $false
    }
    return $true
}

function Remove-AnEbOwnedTemporaryDirectory {
    param(
        [string]$Path,
        [string]$ExpectedParent,
        [Parameter(Mandatory = $true)][string]$ExpectedLeafPattern
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or [string]::IsNullOrWhiteSpace($ExpectedParent) -or
        -not (Test-Path -LiteralPath $Path -PathType Container)) {
        return
    }
    try {
        if (-not (Test-AnEbCleanupTargetShape -Path $Path -ExpectedParent $ExpectedParent -ExpectedLeafPattern $ExpectedLeafPattern)) { return }
        Assert-AnEbNoReparseAncestors -Path $Path
        Assert-AnEbTreeNoReparse -Root $Path
        Remove-Item -LiteralPath (Get-AnEbFullPath -Path $Path) -Recurse -Force -ErrorAction Stop
    }
    catch { }
}

function Remove-AnEbPublishedOwnedDirectory {
    param(
        [string]$Path,
        [string]$ExpectedParent,
        [string]$OwnershipMarkerPath,
        [string]$OwnershipToken
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or [string]::IsNullOrWhiteSpace($ExpectedParent) -or
        [string]::IsNullOrWhiteSpace($OwnershipMarkerPath) -or [string]::IsNullOrWhiteSpace($OwnershipToken) -or
        -not (Test-Path -LiteralPath $Path -PathType Container)) {
        return
    }
    try {
        $full = Get-AnEbFullPath -Path $Path
        $parentFull = (Get-AnEbFullPath -Path $ExpectedParent).TrimEnd('\')
        if (-not [string]::Equals((Split-Path -Parent $full).TrimEnd('\'), $parentFull, [System.StringComparison]::OrdinalIgnoreCase)) { return }
        Assert-AnEbNoReparseAncestors -Path $full
        Assert-AnEbTreeNoReparse -Root $full
        $markerFull = Get-AnEbFullPath -Path $OwnershipMarkerPath
        if (-not [string]::Equals((Split-Path -Parent $markerFull), $full, [System.StringComparison]::OrdinalIgnoreCase)) { return }
        Assert-AnEbRegularFile -Path $markerFull | Out-Null
        if ((Read-AnEbUtf8Strict -Path $markerFull).TrimEnd("`r", "`n") -cne $OwnershipToken) { return }
        Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
    }
    catch { }
}

function Remove-AnEbPublishedOwnedFile {
    param(
        [string]$Path,
        [string]$ExpectedParent,
        [string]$ExpectedSha256
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or [string]::IsNullOrWhiteSpace($ExpectedParent) -or
        $ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$' -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return
    }
    try {
        $full = Get-AnEbFullPath -Path $Path
        $parentFull = (Get-AnEbFullPath -Path $ExpectedParent).TrimEnd('\')
        if (-not [string]::Equals((Split-Path -Parent $full).TrimEnd('\'), $parentFull, [System.StringComparison]::OrdinalIgnoreCase)) { return }
        Assert-AnEbNoReparseAncestors -Path $full
        if ((Get-AnEbSha256File -Path $full) -cne $ExpectedSha256) { return }
        Remove-Item -LiteralPath $full -Force -ErrorAction Stop
    }
    catch { }
}

function Assert-AnEbReleaseMetadata {
    if ($SourceCommit -cnotmatch '^[0-9a-f]{40}$') {
        throw 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND'
    }
    if ($ReleaseCandidate -cnotmatch '^[a-z0-9][a-z0-9._-]{0,63}$') {
        throw 'RELEASE_CANDIDATE_INVALID'
    }
    if ($BuiltAtUtc -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$') {
        throw 'BUILT_AT_UTC_INVALID'
    }
    foreach ($value in @($ServerVersion, $AndroidVersionName)) {
        if ([string]::IsNullOrWhiteSpace($value) -or $value.IndexOfAny(@([char]0, [char]10, [char]13)) -ge 0) {
            throw 'ARTIFACT_VERSION_INVALID'
        }
    }
    if ($AndroidVersionCode -lt 1) {
        throw 'ANDROID_VERSION_CODE_INVALID'
    }
}

function Assert-AnEbFreshTargetParent {
    param([Parameter(Mandatory = $true)][string]$Path)
    $full = Get-AnEbFullPath -Path $Path
    if (Test-Path -LiteralPath $full) {
        throw 'TARGET_ALREADY_EXISTS'
    }
    $parent = Split-Path -Parent $full
    $nearest = $parent
    while (-not (Test-Path -LiteralPath $nearest)) {
        $next = Split-Path -Parent $nearest
        if ([string]::IsNullOrWhiteSpace($next) -or $next -eq $nearest) {
            throw 'TARGET_PARENT_MISSING'
        }
        $nearest = $next
    }
    Assert-AnEbDirectory -Path $nearest | Out-Null
    Assert-AnEbNoReparseAncestors -Path $nearest
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force -ErrorAction Stop | Out-Null
    }
    Assert-AnEbDirectory -Path $parent | Out-Null
    Assert-AnEbNoReparseAncestors -Path $parent
    return $full
}

function Invoke-AnEbGitText {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $output = @(& git.exe -C $WorkingDirectory @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'SOURCE_GIT_QUERY_FAILED'
    }
    return [string]::Join("`n", @($output | ForEach-Object { $_.ToString() })).Trim()
}

function Read-AnEbGitBlob {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    if ($RelativePath -notmatch '^[A-Za-z0-9_./-]+$') {
        throw 'SOURCE_BLOB_PATH_INVALID'
    }
    $blob = Invoke-AnEbGitText -WorkingDirectory $WorkingDirectory -Arguments @('rev-parse', ($Commit + ':' + $RelativePath))
    if ($blob -cnotmatch '^[0-9a-f]{40,64}$') {
        throw 'SOURCE_BLOB_ID_INVALID'
    }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = 'git.exe'
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.Arguments = 'cat-file blob ' + $blob
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $memory = New-Object System.IO.MemoryStream
    try {
        if (-not $process.Start()) {
            throw 'SOURCE_BLOB_READ_FAILED'
        }
        $process.StandardOutput.BaseStream.CopyTo($memory)
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw ('SOURCE_BLOB_READ_FAILED ' + $errorText.Trim())
        }
        return $memory.ToArray()
    }
    finally {
        $memory.Dispose()
        $process.Dispose()
    }
}

function Write-AnEbGitBlob {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$TargetPath
    )
    Write-AnEbCreateNewBytes -Path $TargetPath -Bytes (Read-AnEbGitBlob -WorkingDirectory $WorkingDirectory -Commit $Commit -RelativePath $RelativePath)
}

function Invoke-AnEbChildPowerShell {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$ExpectedPassMarker
    )
    $output = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = [string]::Join("`n", @($output | ForEach-Object { $_.ToString() }))
    if ($exitCode -ne 0 -or $text -notmatch [regex]::Escape($ExpectedPassMarker)) {
        throw ('PACKAGE_TOOL_FAILED ' + $ExpectedPassMarker)
    }
}

function Get-AnEbApprovedApkSigner {
    param(
        [Parameter(Mandatory = $true)][string]$ToolPath,
        [Parameter(Mandatory = $true)][string]$ApkPath
    )
    $output = @(& $ToolPath verify --verbose --print-certs $ApkPath 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'APK_SIGNATURE_VERIFICATION_FAILED'
    }
    $text = [string]::Join("`n", @($output | ForEach-Object { $_.ToString() }))
    $matches = [regex]::Matches($text, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$')
    if ($matches.Count -ne 1) {
        throw 'APK_SIGNER_CERTIFICATE_SET_INVALID'
    }
    $actual = ($matches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
    if (-not [string]::Equals($actual, $approvedSignerCertificateSha256, [System.StringComparison]::Ordinal)) {
        throw 'P011_RELEASE_SIGNER_NOT_APPROVED'
    }
    return $actual
}

function Invoke-AnEbArtifactToolText {
    param(
        [Parameter(Mandatory = $true)][string]$ToolPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureCode
    )
    $output = @(& $ToolPath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw $FailureCode
    }
    return [string]::Join("`n", @($output | ForEach-Object { $_.ToString() })).Trim()
}

function Assert-AnEbApkIdentity {
    param(
        [Parameter(Mandatory = $true)][string]$ToolPath,
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][string]$ExpectedVersionName,
        [Parameter(Mandatory = $true)][int]$ExpectedVersionCode,
        [Parameter(Mandatory = $true)][string]$ExpectedSourceCommit
    )
    $packageName = Invoke-AnEbArtifactToolText -ToolPath $ToolPath -Arguments @('manifest', 'application-id', $ApkPath) -FailureCode 'APK_IDENTITY_EXTRACTION_FAILED'
    $versionName = Invoke-AnEbArtifactToolText -ToolPath $ToolPath -Arguments @('manifest', 'version-name', $ApkPath) -FailureCode 'APK_IDENTITY_EXTRACTION_FAILED'
    $versionCode = Invoke-AnEbArtifactToolText -ToolPath $ToolPath -Arguments @('manifest', 'version-code', $ApkPath) -FailureCode 'APK_IDENTITY_EXTRACTION_FAILED'
    $buildConfig = Invoke-AnEbArtifactToolText -ToolPath $ToolPath -Arguments @('dex', 'code', '--class', 'com.aneb.probe.BuildConfig', $ApkPath) -FailureCode 'APK_SOURCE_COMMIT_EXTRACTION_FAILED'
    if ($packageName -cne 'com.aneb.probe' -or
        $versionName -cne $ExpectedVersionName -or
        $versionCode -cne $ExpectedVersionCode.ToString([Globalization.CultureInfo]::InvariantCulture)) {
        throw 'APK_PACKAGE_VERSION_MISMATCH'
    }
    $sourcePattern = '(?m)\bPROTOTYPE_SOURCE_COMMIT\b[^\r\n]{0,256}\b' + [regex]::Escape($ExpectedSourceCommit) + '\b'
    if ($buildConfig -cnotmatch $sourcePattern) {
        throw 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND'
    }
}

function Assert-AnEbServerBuildProvenance {
    param(
        [Parameter(Mandatory = $true)][string]$ToolPath,
        [Parameter(Mandatory = $true)][string]$ServerPath,
        [Parameter(Mandatory = $true)][string]$ExpectedSourceCommit
    )
    $buildInfo = Invoke-AnEbArtifactToolText -ToolPath $ToolPath -Arguments @('version', '-m', $ServerPath) -FailureCode 'SERVER_BUILD_INFO_UNREADABLE'
    $revisionMatches = [regex]::Matches($buildInfo, '(?m)^\s*build\s+vcs\.revision=([0-9a-f]{40})\s*$')
    $modifiedMatches = [regex]::Matches($buildInfo, '(?m)^\s*build\s+vcs\.modified=(true|false)\s*$')
    if ($revisionMatches.Count -ne 1 -or $modifiedMatches.Count -ne 1 -or
        $revisionMatches[0].Groups[1].Value -cne $ExpectedSourceCommit -or
        $modifiedMatches[0].Groups[1].Value -cne 'false') {
        throw 'SERVER_SOURCE_PROVENANCE_MISMATCH'
    }
}

function Get-AnEbTreeSha256 {
    param([Parameter(Mandatory = $true)][string]$Root)
    [string[]]$rows = @(Get-AnEbRelativeFiles -Root $Root | ForEach-Object {
        $_.RelativePath + '=' + (Get-AnEbSha256File -Path $_.FullPath)
    })
    [Array]::Sort($rows, [System.StringComparer]::Ordinal)
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    return Get-AnEbSha256Bytes -Bytes $encoding.GetBytes(([string]::Join([char]10, $rows) + [char]10))
}

function Assert-AnEbEvidenceRuntimeCharacterization {
    param(
        [Parameter(Mandatory = $true)][string]$ExecutablePath,
        [Parameter(Mandatory = $true)][string]$ValidBundlePath,
        [Parameter(Mandatory = $true)][string]$InvalidBundlePath,
        [Parameter(Mandatory = $true)][string]$ExpectedSourceCommit
    )
    $validOutput = @(& $ExecutablePath verify-bundle --bundle $ValidBundlePath 2>&1)
    $validExit = $LASTEXITCODE
    $validText = [string]::Join("`n", @($validOutput | ForEach-Object { $_.ToString() }))
    if ($validExit -ne 0 -or $validText -notmatch '(?m)^G0_VERIFY_OK\s*$') {
        throw 'EVIDENCE_RUNTIME_CHARACTERIZATION_FAILED'
    }
    $buildInfoMatches = [regex]::Matches($validText, '(?m)^ANEB_EVIDENCE_BUILD_INFO\s+(\{[^\r\n]+\})\s*$')
    if ($buildInfoMatches.Count -ne 1) {
        throw 'EVIDENCE_BUILD_INFO_UNREADABLE'
    }
    try {
        $buildInfo = $buildInfoMatches[0].Groups[1].Value | ConvertFrom-Json
    }
    catch {
        throw 'EVIDENCE_BUILD_INFO_UNREADABLE'
    }
    $actualFields = @($buildInfo.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    $expectedFields = @('schema_version', 'source_commit') | Sort-Object
    if ([string]::Join("`n", $actualFields) -cne [string]::Join("`n", $expectedFields) -or
        $buildInfo.schema_version -cne 'aneb-prototype-evidence-build-0.1' -or
        -not ($buildInfo.source_commit -is [string]) -or
        $buildInfo.source_commit -cne $ExpectedSourceCommit) {
        throw 'EVIDENCE_SOURCE_PROVENANCE_MISMATCH'
    }
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell promotes native stderr to a non-terminating ErrorRecord.
        # The invalid fixture is expected to write a diagnostic and return non-zero,
        # so observe that exit code before restoring the script's fail-fast policy.
        $ErrorActionPreference = 'Continue'
        $invalidOutput = @(& $ExecutablePath verify-bundle --bundle $InvalidBundlePath 2>&1)
        $invalidExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($invalidExit -eq 0) {
        throw 'EVIDENCE_RUNTIME_INVALID_BUNDLE_ACCEPTED'
    }
    return [string]$buildInfo.source_commit
}

function Read-AnEbArtifactBuildReceipt {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256
    )
    if ($ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'ARTIFACT_BUILD_RECEIPT_PIN_INVALID'
    }
    $full = Get-AnEbFullPath -Path $Path
    Assert-AnEbRegularFile -Path $full | Out-Null
    Assert-AnEbNoReparseAncestors -Path $full
    $receiptBytes = Read-AnEbBytes -Path $full
    if ((Get-AnEbSha256Bytes -Bytes $receiptBytes) -cne $ExpectedSha256) {
        throw 'ARTIFACT_BUILD_RECEIPT_PIN_MISMATCH'
    }
    try {
        $receipt = ConvertFrom-AnEbUtf8Strict -Bytes $receiptBytes | ConvertFrom-Json
    }
    catch {
        throw 'ARTIFACT_BUILD_RECEIPT_INVALID'
    }
    $expectedFields = @(
        'schema_version', 'source_commit', 'server_sha256', 'server_version',
        'android_sha256', 'android_package_name', 'android_version_name',
        'android_version_code', 'android_source_commit', 'evidence_runtime_sha256',
        'evidence_runtime_tree_sha256', 'evidence_runtime_characterization',
        'evidence_source_commit'
    ) | Sort-Object
    $actualFields = @($receipt.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    if ([string]::Join("`n", $actualFields) -cne [string]::Join("`n", $expectedFields)) {
        throw 'ARTIFACT_BUILD_RECEIPT_FIELDS_INVALID'
    }
    return $receipt
}

function New-AnEbDeterministicReleaseZip {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$ZipPath
    )
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $fixedTimestamp = [DateTimeOffset]::new(2000, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
    $archive = [System.IO.Compression.ZipFile]::Open($ZipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entrySpecs = @([pscustomobject]@{
            Name = 'ANEB-Prototype-0.1/results/'
            FullPath = $null
            IsDirectory = $true
        }) + @(Get-AnEbRelativeFiles -Root $PackageRoot | ForEach-Object {
            [pscustomobject]@{
                Name = 'ANEB-Prototype-0.1/' + $_.RelativePath
                FullPath = $_.FullPath
                IsDirectory = $false
            }
        })
        [string[]]$entryNames = @($entrySpecs | ForEach-Object { [string]$_.Name })
        [Array]::Sort($entryNames, [System.StringComparer]::Ordinal)
        foreach ($entryName in $entryNames) {
            $spec = @($entrySpecs | Where-Object { $_.Name -ceq $entryName })[0]
            $compression = if ($spec.IsDirectory) { [System.IO.Compression.CompressionLevel]::NoCompression } else { [System.IO.Compression.CompressionLevel]::Optimal }
            $entry = $archive.CreateEntry($spec.Name, $compression)
            $entry.LastWriteTime = $fixedTimestamp
            $entry.ExternalAttributes = 0
            if ($spec.IsDirectory) {
                continue
            }
            $input = [System.IO.File]::OpenRead($spec.FullPath)
            $output = $entry.Open()
            try {
                $input.CopyTo($output)
            }
            finally {
                $output.Dispose()
                $input.Dispose()
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}

try {
    Assert-AnEbReleaseMetadata

    $sourceFull = Get-AnEbFullPath -Path $SourceRoot
    Assert-AnEbDirectory -Path $sourceFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $sourceFull
    $headCommit = Invoke-AnEbGitText -WorkingDirectory $sourceFull -Arguments @('rev-parse', 'HEAD')
    if (-not [string]::Equals($headCommit, $SourceCommit, [System.StringComparison]::Ordinal)) {
        throw 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND'
    }
    $dirty = Invoke-AnEbGitText -WorkingDirectory $sourceFull -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
    if (-not [string]::IsNullOrEmpty($dirty)) {
        throw 'SOURCE_WORKTREE_NOT_CLEAN'
    }

    $serverFull = Get-AnEbFullPath -Path $ServerPath
    $apkFull = Get-AnEbFullPath -Path $AndroidApkPath
    $evidenceFull = Get-AnEbFullPath -Path $EvidenceDirectory
    foreach ($artifact in @($serverFull, $apkFull)) {
        Assert-AnEbRegularFile -Path $artifact | Out-Null
        Assert-AnEbNoReparseAncestors -Path $artifact
    }
    Assert-AnEbDirectory -Path $evidenceFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $evidenceFull
    Assert-AnEbTreeNoReparse -Root $evidenceFull
    Assert-AnEbRegularFile -Path (Join-Path $evidenceFull 'aneb-evidence.exe') | Out-Null
    $evidenceCharacterizationFull = Get-AnEbFullPath -Path $EvidenceCharacterizationBundlePath
    Assert-AnEbDirectory -Path $evidenceCharacterizationFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $evidenceCharacterizationFull
    Assert-AnEbTreeNoReparse -Root $evidenceCharacterizationFull
    $artifactBuildReceipt = Read-AnEbArtifactBuildReceipt -Path $ArtifactBuildReceiptPath -ExpectedSha256 $ExpectedArtifactBuildReceiptSha256
    $inputServerSha256 = Get-AnEbSha256File -Path $serverFull
    $inputAndroidSha256 = Get-AnEbSha256File -Path $apkFull
    $inputEvidenceTreeSha256 = Get-AnEbTreeSha256 -Root $evidenceFull
    $inputEvidenceExecutableSha256 = Get-AnEbSha256File -Path (Join-Path $evidenceFull 'aneb-evidence.exe')
    if ($artifactBuildReceipt.schema_version -cne 'aneb-prototype-build-artifacts-0.1' -or
        $artifactBuildReceipt.source_commit -cne $SourceCommit -or
        $artifactBuildReceipt.server_sha256 -cne $inputServerSha256 -or
        $artifactBuildReceipt.server_version -cne $ServerVersion -or
        $artifactBuildReceipt.android_sha256 -cne $inputAndroidSha256 -or
        $artifactBuildReceipt.android_package_name -cne 'com.aneb.probe' -or
        $artifactBuildReceipt.android_version_name -cne $AndroidVersionName -or
        ($artifactBuildReceipt.android_version_code -isnot [int] -and $artifactBuildReceipt.android_version_code -isnot [long]) -or
        [int64]$artifactBuildReceipt.android_version_code -ne $AndroidVersionCode -or
        $artifactBuildReceipt.android_source_commit -cne $SourceCommit -or
        $artifactBuildReceipt.evidence_source_commit -cne $SourceCommit -or
        $artifactBuildReceipt.evidence_runtime_sha256 -cne $inputEvidenceExecutableSha256 -or
        $artifactBuildReceipt.evidence_runtime_tree_sha256 -cne $inputEvidenceTreeSha256 -or
        $artifactBuildReceipt.evidence_runtime_characterization -cne 'valid_bundle_pass_invalid_bundle_reject') {
        throw 'ARTIFACT_BUILD_RECEIPT_PROVENANCE_MISMATCH'
    }

    if ([string]::IsNullOrWhiteSpace($ApkSignerPath)) {
        $ApkSignerPath = (Get-Command 'apksigner.bat' -ErrorAction Stop).Source
    }
    $apkSignerFull = Get-AnEbFullPath -Path $ApkSignerPath
    Assert-AnEbRegularFile -Path $apkSignerFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $apkSignerFull
    if ([string]::IsNullOrWhiteSpace($ApkAnalyzerPath)) {
        $ApkAnalyzerPath = (Get-Command 'apkanalyzer.bat' -ErrorAction Stop).Source
    }
    $apkAnalyzerFull = Get-AnEbFullPath -Path $ApkAnalyzerPath
    Assert-AnEbRegularFile -Path $apkAnalyzerFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $apkAnalyzerFull
    if ([string]::IsNullOrWhiteSpace($GoPath)) {
        $GoPath = (Get-Command 'go.exe' -ErrorAction Stop).Source
    }
    $goFull = Get-AnEbFullPath -Path $GoPath
    Assert-AnEbRegularFile -Path $goFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $goFull

    $outputFull = Get-AnEbFullPath -Path $OutputDirectory
    if ([string]::IsNullOrWhiteSpace($OutputZipPath)) {
        throw 'OUTPUT_ZIP_PATH_REQUIRED'
    }
    $zipFull = Get-AnEbFullPath -Path $OutputZipPath
    $expectedZipName = 'ANEB-Prototype-0.1-' + $ReleaseCandidate + '-windows-x64.zip'
    if (-not [string]::Equals([System.IO.Path]::GetFileName($zipFull), $expectedZipName, [System.StringComparison]::Ordinal)) {
        throw 'OUTPUT_ZIP_NAME_INVALID'
    }
    $receiptFull = Get-AnEbFullPath -Path $AdmissionReceiptPath
    $stagingParentFull = Split-Path -Parent $outputFull
    $zipParentFull = Split-Path -Parent $zipFull
    $receiptParentFull = Split-Path -Parent $receiptFull
    if (Test-Path -LiteralPath $outputFull) {
        throw 'TARGET_ALREADY_EXISTS'
    }
    if (Test-Path -LiteralPath $receiptFull) {
        throw 'TARGET_ALREADY_EXISTS'
    }
    if (Test-Path -LiteralPath $zipFull) {
        throw 'TARGET_ALREADY_EXISTS'
    }
    $outputPrefix = $outputFull.TrimEnd('\') + '\'
    $receiptPrefix = $receiptFull.TrimEnd('\') + '\'
    if ([string]::Equals($outputFull.TrimEnd('\'), $receiptFull.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase) -or
        $receiptFull.StartsWith($outputPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
        $outputFull.StartsWith($receiptPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'ADMISSION_RECEIPT_MUST_BE_EXTERNAL'
    }
    $outputFull = Assert-AnEbFreshTargetParent -Path $outputFull
    $zipFull = Assert-AnEbFreshTargetParent -Path $zipFull
    $receiptFull = Assert-AnEbFreshTargetParent -Path $receiptFull

    $stagingRoot = Join-Path (Split-Path -Parent $outputFull) ('.aneb-package-building-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stagingRoot -ErrorAction Stop | Out-Null
    foreach ($directory in @('android', 'bin', 'bin\evidence', 'contracts', 'static', 'tools')) {
        New-Item -ItemType Directory -Path (Join-Path $stagingRoot $directory) -ErrorAction Stop | Out-Null
    }

    $fixedSourceFiles = [ordered]@{
        'START_ANEB.bat' = 'START_ANEB.bat'
        'README_FIRST.md' = 'README_FIRST.md'
        'static/report-template.html' = 'static/report-template.html'
        'tools/common.ps1' = 'tools/common.ps1'
        'tools/doctor.ps1' = 'tools/doctor.ps1'
        'tools/finalize-campaign.ps1' = 'tools/finalize-campaign.ps1'
        'tools/finalize-verified-campaign.ps1' = 'tools/finalize-verified-campaign.ps1'
        'tools/launch.ps1' = 'tools/launch.ps1'
        'tools/make-package-manifest.ps1' = 'tools/make-package-manifest.ps1'
        'tools/verify-evidence.ps1' = 'tools/verify-evidence.ps1'
        'tools/verify-package.ps1' = 'tools/verify-package.ps1'
    }
    foreach ($targetRelative in $fixedSourceFiles.Keys) {
        Write-AnEbGitBlob -WorkingDirectory $sourceFull -Commit $SourceCommit -RelativePath $fixedSourceFiles[$targetRelative] -TargetPath (Join-Path $stagingRoot ($targetRelative -replace '/', '\'))
    }

    $contractNames = @('profile-manifest.json', 'capabilities.schema.json', 'run-record.schema.json', 'score-policy.json')
    foreach ($contractName in $contractNames) {
        Write-AnEbGitBlob -WorkingDirectory $sourceFull -Commit $SourceCommit -RelativePath ('contracts/prototype-0.1/' + $contractName) -TargetPath (Join-Path $stagingRoot ('contracts\' + $contractName))
    }

    $stagedServerPath = Join-Path $stagingRoot 'bin\aneb-server.exe'
    Write-AnEbCreateNewBytes -Path $stagedServerPath -Bytes (Read-AnEbBytes -Path $serverFull)
    $stagedServerSha256BeforeVerification = Get-AnEbSha256File -Path $stagedServerPath
    if ($stagedServerSha256BeforeVerification -cne $artifactBuildReceipt.server_sha256) {
        throw 'SERVER_CHANGED_BEFORE_STAGING'
    }
    Assert-AnEbServerBuildProvenance -ToolPath $goFull -ServerPath $stagedServerPath -ExpectedSourceCommit $SourceCommit
    if ((Get-AnEbSha256File -Path $stagedServerPath) -cne $stagedServerSha256BeforeVerification) {
        throw 'SERVER_CHANGED_DURING_VERIFICATION'
    }
    $stagedApkPath = Join-Path $stagingRoot 'android\aneb-prototype-0.1.apk'
    Write-AnEbCreateNewBytes -Path $stagedApkPath -Bytes (Read-AnEbBytes -Path $apkFull)
    $stagedApkSha256BeforeVerification = Get-AnEbSha256File -Path $stagedApkPath
    if ($stagedApkSha256BeforeVerification -cne $artifactBuildReceipt.android_sha256) {
        throw 'APK_CHANGED_BEFORE_STAGING'
    }
    $actualSignerSha256 = Get-AnEbApprovedApkSigner -ToolPath $apkSignerFull -ApkPath $stagedApkPath
    Assert-AnEbApkIdentity -ToolPath $apkAnalyzerFull -ApkPath $stagedApkPath -ExpectedVersionName $AndroidVersionName -ExpectedVersionCode $AndroidVersionCode -ExpectedSourceCommit $SourceCommit
    if ((Get-AnEbSha256File -Path $stagedApkPath) -cne $stagedApkSha256BeforeVerification) {
        throw 'APK_CHANGED_DURING_VERIFICATION'
    }
    foreach ($directory in @(Get-ChildItem -LiteralPath $evidenceFull -Directory -Recurse -Force | Sort-Object FullName)) {
        $relative = $directory.FullName.Substring($evidenceFull.TrimEnd('\').Length + 1)
        New-Item -ItemType Directory -Path (Join-Path $stagingRoot ('bin\evidence\' + $relative)) -ErrorAction Stop | Out-Null
    }
    foreach ($file in @(Get-AnEbRelativeFiles -Root $evidenceFull)) {
        Write-AnEbCreateNewBytes -Path (Join-Path $stagingRoot ('bin\evidence\' + ($file.RelativePath -replace '/', '\'))) -Bytes (Read-AnEbBytes -Path $file.FullPath)
    }
    $stagedEvidenceRoot = Join-Path $stagingRoot 'bin\evidence'
    $stagedEvidenceExecutable = Join-Path $stagedEvidenceRoot 'aneb-evidence.exe'
    $evidenceTreeSha256BeforeCharacterization = Get-AnEbTreeSha256 -Root $stagedEvidenceRoot
    if ($evidenceTreeSha256BeforeCharacterization -cne $artifactBuildReceipt.evidence_runtime_tree_sha256 -or
        (Get-AnEbSha256File -Path $stagedEvidenceExecutable) -cne $artifactBuildReceipt.evidence_runtime_sha256) {
        throw 'EVIDENCE_RUNTIME_CHANGED_BEFORE_STAGING'
    }
    $evidenceInvalidRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('.aneb-evidence-invalid-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $evidenceInvalidRoot -ErrorAction Stop | Out-Null
    $evidenceSourceCommit = Assert-AnEbEvidenceRuntimeCharacterization -ExecutablePath $stagedEvidenceExecutable -ValidBundlePath $evidenceCharacterizationFull -InvalidBundlePath $evidenceInvalidRoot -ExpectedSourceCommit $SourceCommit
    Remove-Item -LiteralPath $evidenceInvalidRoot -Recurse -Force -ErrorAction Stop
    $evidenceInvalidRoot = $null
    if ((Get-AnEbTreeSha256 -Root $stagedEvidenceRoot) -cne $evidenceTreeSha256BeforeCharacterization) {
        throw 'EVIDENCE_RUNTIME_CHANGED_DURING_CHARACTERIZATION'
    }

    $contractPaths = [ordered]@{}
    $contractSizes = [ordered]@{}
    $contractHashes = [ordered]@{}
    foreach ($contractName in $contractNames) {
        $relative = 'contracts/' + $contractName
        $path = Join-Path $stagingRoot ($relative -replace '/', '\')
        $contractPaths[$contractName] = $relative
        $contractSizes[$contractName] = [int64](Get-Item -LiteralPath $path).Length
        $contractHashes[$contractName] = Get-AnEbSha256File -Path $path
    }

    $version = [ordered]@{
        format = 'aneb-prototype-0.1-release-v1'
        release_state = 'RELEASE_CANDIDATE'
        specification_status = 'G0_APPROVED'
        product_version = 'prototype-0.1'
        release_candidate = $ReleaseCandidate
        source_commit = $SourceCommit
        artifact_build_receipt_sha256 = $ExpectedArtifactBuildReceiptSha256
        built_at_utc = $BuiltAtUtc
        server_version = $ServerVersion
        server_source_commit = $SourceCommit
        android_version_name = $AndroidVersionName
        android_version_code = $AndroidVersionCode
        android_package_name = 'com.aneb.probe'
        android_source_commit = $SourceCommit
        server_artifact = 'bin/aneb-server.exe'
        android_artifact = 'android/aneb-prototype-0.1.apk'
        evidence_verifier_artifact = 'bin/evidence/aneb-evidence.exe'
        evidence_source_commit = $evidenceSourceCommit
        evidence_runtime_tree_sha256 = $evidenceTreeSha256BeforeCharacterization
        evidence_runtime_characterization = 'valid_bundle_pass_invalid_bundle_reject'
        health_endpoint = '/api/v1/serverinfo'
        capability_endpoint = '/api/v1/prototype/capabilities'
        server_args = @(
            '-addr', (':' + $Port),
            '-prototype-only',
            '-prototype-evidence-runtime', './bin/evidence/aneb-evidence.exe',
            '-prototype-results-root', './results',
            '-data', './results/legacy'
        )
        workload_id = 'streaming_text_reference_v0.1'
        condition_versions = @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')
        contract_files = $contractNames
        contract_paths = $contractPaths
        contract_sizes = $contractSizes
        contract_hashes = $contractHashes
        schedule_hashes = [ordered]@{
            'baseline_v0.1' = '46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e'
            'slow_v0.1' = 'b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062'
            'unstable_v0.1' = 'd11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58'
        }
        evidence_schema = 'aneb-prototype-evidence-0.1'
        score_policy = 'rpi-0.1'
        artifact_admission = 'REAL_ARTIFACTS_BOUND'
    }
    Write-AnEbCreateNewUtf8 -Path (Join-Path $stagingRoot 'VERSION.json') -Text (($version | ConvertTo-Json -Compress -Depth 12) + [char]10)

    $manifestTool = Join-Path $stagingRoot 'tools\make-package-manifest.ps1'
    Invoke-AnEbChildPowerShell -ScriptPath $manifestTool -Arguments @('-Root', $stagingRoot) -ExpectedPassMarker 'PASS PACKAGE_MANIFEST'
    Assert-AnEbNoAbsoluteDeveloperPath -Root $stagingRoot
    $zipStagingRoot = Join-Path (Split-Path -Parent $zipFull) ('.aneb-package-zip-building-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $zipStagingRoot -ErrorAction Stop | Out-Null
    $zipStagingPath = Join-Path $zipStagingRoot $expectedZipName
    New-AnEbDeterministicReleaseZip -PackageRoot $stagingRoot -ZipPath $zipStagingPath
    $zipSha256 = Get-AnEbSha256File -Path $zipStagingPath

    $serverSha256 = Get-AnEbSha256File -Path (Join-Path $stagingRoot 'bin\aneb-server.exe')
    $androidSha256 = Get-AnEbSha256File -Path (Join-Path $stagingRoot 'android\aneb-prototype-0.1.apk')
    $receipt = [ordered]@{
        schema_version = 'aneb-prototype-artifact-admission-0.1'
        admission_status = 'G0_ARTIFACT_ADMITTED'
        source_commit = $SourceCommit
        artifact_build_receipt_sha256 = $ExpectedArtifactBuildReceiptSha256
        server_path = 'bin/aneb-server.exe'
        server_version = $ServerVersion
        server_source_commit = $SourceCommit
        server_sha256 = $serverSha256
        android_path = 'android/aneb-prototype-0.1.apk'
        android_version_name = $AndroidVersionName
        android_version_code = $AndroidVersionCode
        android_package_name = 'com.aneb.probe'
        android_source_commit = $SourceCommit
        android_sha256 = $androidSha256
        android_signer_cert_sha256 = $actualSignerSha256
        evidence_runtime_path = 'bin/evidence/aneb-evidence.exe'
        evidence_source_commit = $evidenceSourceCommit
        evidence_runtime_sha256 = (Get-AnEbSha256File -Path $stagedEvidenceExecutable)
        evidence_runtime_tree_sha256 = $evidenceTreeSha256BeforeCharacterization
        evidence_runtime_characterization = 'valid_bundle_pass_invalid_bundle_reject'
        package_zip_name = $expectedZipName
        package_zip_sha256 = $zipSha256
    }
    $receiptText = ($receipt | ConvertTo-Json -Compress -Depth 8) + [char]10
    $receiptBytes = ConvertTo-AnEbUtf8NoBomBytes -Text $receiptText
    $receiptSha256 = Get-AnEbSha256Bytes -Bytes $receiptBytes
    Write-AnEbCreateNewBytes -Path $receiptFull -Bytes $receiptBytes
    $receiptCreated = $true

    $verifyTool = Join-Path $stagingRoot 'tools\verify-package.ps1'
    Invoke-AnEbChildPowerShell -ScriptPath $verifyTool -Arguments @(
        '-Root', $stagingRoot,
        '-RequireExternalAdmission',
        '-AdmissionReceiptPath', $receiptFull,
        '-ExpectedAdmissionReceiptSha256', $receiptSha256,
        '-PackageZipPath', $zipStagingPath
    ) -ExpectedPassMarker 'PASS PACKAGE_INTEGRITY'

    $outputOwnershipToken = [Guid]::NewGuid().ToString('N')
    $outputOwnershipMarker = Join-Path $stagingRoot '.aneb-builder-owned'
    Write-AnEbCreateNewUtf8 -Path $outputOwnershipMarker -Text ($outputOwnershipToken + [char]10)
    Move-Item -LiteralPath $stagingRoot -Destination $outputFull -ErrorAction Stop
    $stagingRoot = $null
    $outputOwnershipMarker = Join-Path $outputFull '.aneb-builder-owned'
    $outputCreated = $true
    Move-Item -LiteralPath $zipStagingPath -Destination $zipFull -ErrorAction Stop
    $zipCreated = $true
    $zipStagingPath = $null
    Remove-Item -LiteralPath $zipStagingRoot -Force -ErrorAction Stop
    $zipStagingRoot = $null
    Assert-AnEbRegularFile -Path $outputOwnershipMarker | Out-Null
    if ((Read-AnEbUtf8Strict -Path $outputOwnershipMarker).TrimEnd("`r", "`n") -cne $outputOwnershipToken) {
        throw 'OUTPUT_OWNERSHIP_MARKER_INVALID'
    }
    Remove-Item -LiteralPath $outputOwnershipMarker -Force -ErrorAction Stop
    $outputOwnershipMarker = $null
    Write-Output ('PASS RELEASE_PACKAGE_BUILT receipt_sha256=' + $receiptSha256 + ' zip_sha256=' + $zipSha256)
    exit 0
}
catch {
    Remove-AnEbOwnedTemporaryDirectory -Path $stagingRoot -ExpectedParent $stagingParentFull -ExpectedLeafPattern '^\.aneb-package-building-[0-9a-f]{32}$'
    if ($null -ne $zipStagingPath -and (Test-Path -LiteralPath $zipStagingPath -PathType Leaf)) {
        Remove-AnEbPublishedOwnedFile -Path $zipStagingPath -ExpectedParent $zipStagingRoot -ExpectedSha256 $zipSha256
    }
    Remove-AnEbOwnedTemporaryDirectory -Path $zipStagingRoot -ExpectedParent $zipParentFull -ExpectedLeafPattern '^\.aneb-package-zip-building-[0-9a-f]{32}$'
    Remove-AnEbOwnedTemporaryDirectory -Path $evidenceInvalidRoot -ExpectedParent $evidenceTempParentFull -ExpectedLeafPattern '^\.aneb-evidence-invalid-[0-9a-f]{32}$'
    if ($zipCreated) { Remove-AnEbPublishedOwnedFile -Path $zipFull -ExpectedParent $zipParentFull -ExpectedSha256 $zipSha256 }
    if ($outputCreated) { Remove-AnEbPublishedOwnedDirectory -Path $outputFull -ExpectedParent $stagingParentFull -OwnershipMarkerPath $outputOwnershipMarker -OwnershipToken $outputOwnershipToken }
    if ($receiptCreated) { Remove-AnEbPublishedOwnedFile -Path $receiptFull -ExpectedParent $receiptParentFull -ExpectedSha256 $receiptSha256 }
    Write-Output ('FAIL P007_RELEASE_PACKAGE_BUILD ' + $_.Exception.Message)
    exit 1
}
