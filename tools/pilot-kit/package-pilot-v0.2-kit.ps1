[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [Parameter(Mandatory = $true)][string]$SourceCommit,
    [Parameter(Mandatory = $true)][string]$ReleaseApkPath,
    [Parameter(Mandatory = $true)][string]$PrototypeReleaseApkPath,
    [string]$ReleaseApkSignerReceiptPath = '',
    [string]$PrototypeReleaseApkSignerReceiptPath = '',
    [Parameter(Mandatory = $true)][string]$PrototypeLauncherDirectory,
    [string]$PrototypePackageZipPath = '',
    [string]$PrototypeAdmissionReceiptPath = '',
    [string]$ExpectedPrototypeAdmissionReceiptSha256 = '',
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$OutputZipPath,
    [Parameter(Mandatory = $true)][string]$ReleaseCandidate,
    [Parameter(Mandatory = $true)][string]$BuiltAtUtc,
    [string]$ApkSignerPath = '',
    [string]$ApkAnalyzerPath = '',
    [string]$GoPath = '',
    [switch]$AllowMissingSdkTools,
    [switch]$AllowMissingGoTool,
    [switch]$IncludeBaselineReport
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$commonPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'common.ps1'
. $commonPath

$approvedSignerCertificateSha256 = 'b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003'
$stagingRoot = $null
$zipStagingRoot = $null
$zipStagingPath = $null
$outputFull = $null
$zipFull = $null
$stagingParentFull = $null
$zipParentFull = $null
$outputCreated = $false
$zipCreated = $false
$outputOwnershipToken = $null
$outputOwnershipMarker = $null
$zipSha256 = $null

function Remove-AnEbKitOwnedTempDirectory {
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
        $full = Get-AnEbFullPath -Path $Path
        $parentFull = (Get-AnEbFullPath -Path $ExpectedParent).TrimEnd('\')
        if (-not [string]::Equals((Split-Path -Parent $full).TrimEnd('\'), $parentFull, [System.StringComparison]::OrdinalIgnoreCase) -or
            [System.IO.Path]::GetFileName($full) -cnotmatch $ExpectedLeafPattern) {
            return
        }
        Assert-AnEbNoReparseAncestors -Path $full
        Assert-AnEbTreeNoReparse -Root $full
        Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
    }
    catch { }
}

function Remove-AnEbKitPublishedDirectory {
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

function Remove-AnEbKitPublishedFile {
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

function Assert-AnEbKitFreshTargetParent {
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

function Invoke-AnEbKitGitText {
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

function Read-AnEbKitGitBlob {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    if ($RelativePath -notmatch '^[A-Za-z0-9_./-]+$') {
        throw ('SOURCE_BLOB_PATH_INVALID: ' + $RelativePath)
    }
    $blob = Invoke-AnEbKitGitText -WorkingDirectory $WorkingDirectory -Arguments @('rev-parse', ($Commit + ':' + $RelativePath))
    if ($blob -cnotmatch '^[0-9a-f]{40,64}$') {
        throw ('SOURCE_BLOB_ID_INVALID: ' + $RelativePath)
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

function Write-AnEbKitGitBlob {
    param(
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$TargetPath
    )
    Write-AnEbCreateNewBytes -Path $TargetPath -Bytes (Read-AnEbKitGitBlob -WorkingDirectory $WorkingDirectory -Commit $Commit -RelativePath $RelativePath)
}

function Resolve-AnEbKitSdkTool {
    param(
        [string]$ExplicitPath,
        [Parameter(Mandatory = $true)][string]$ToolKind
    )
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $full = Get-AnEbFullPath -Path $ExplicitPath
        Assert-AnEbRegularFile -Path $full | Out-Null
        Assert-AnEbNoReparseAncestors -Path $full
        return $full
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and (Test-Path -LiteralPath $env:ANDROID_HOME -PathType Container)) {
        if ($ToolKind -eq 'apksigner') {
            $btRoot = Join-Path $env:ANDROID_HOME 'build-tools'
            if (Test-Path -LiteralPath $btRoot -PathType Container) {
                $found = @(Get-ChildItem -LiteralPath $btRoot -Directory -ErrorAction SilentlyContinue |
                    Sort-Object Name -Descending |
                    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
                    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
                if ($found.Count -gt 0) {
                    $full = Get-AnEbFullPath -Path $found[0]
                    Assert-AnEbRegularFile -Path $full | Out-Null
                    return $full
                }
            }
        }
        elseif ($ToolKind -eq 'apkanalyzer') {
            $ctRoot = Join-Path $env:ANDROID_HOME 'cmdline-tools'
            if (Test-Path -LiteralPath $ctRoot -PathType Container) {
                $found = @(Get-ChildItem -LiteralPath $ctRoot -Directory -ErrorAction SilentlyContinue |
                    Sort-Object Name -Descending |
                    ForEach-Object { Join-Path $_.FullName 'bin\apkanalyzer.bat' } |
                    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
                if ($found.Count -gt 0) {
                    $full = Get-AnEbFullPath -Path $found[0]
                    Assert-AnEbRegularFile -Path $full | Out-Null
                    return $full
                }
            }
        }
    }
    $cmdName = if ($ToolKind -eq 'apksigner') { 'apksigner.bat' } else { 'apkanalyzer.bat' }
    $cmd = Get-Command $cmdName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $cmd) {
        $full = Get-AnEbFullPath -Path $cmd.Source
        Assert-AnEbRegularFile -Path $full | Out-Null
        return $full
    }
    return ''
}

function Resolve-AnEbKitGoTool {
    param([string]$ExplicitPath)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        $full = Get-AnEbFullPath -Path $ExplicitPath
        Assert-AnEbRegularFile -Path $full | Out-Null
        Assert-AnEbNoReparseAncestors -Path $full
        return $full
    }
    $cmd = Get-Command 'go.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $cmd) {
        $full = Get-AnEbFullPath -Path $cmd.Source
        Assert-AnEbRegularFile -Path $full | Out-Null
        return $full
    }
    return ''
}

function Invoke-AnEbKitToolText {
    param(
        [Parameter(Mandatory = $true)][string]$ToolPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureCode
    )
    $output = @(& $ToolPath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw ($FailureCode + ': ' + ([string]::Join(' | ', @($output | ForEach-Object { $_.ToString() }))))
    }
    return [string]::Join("`n", @($output | ForEach-Object { $_.ToString() }))
}

function Assert-AnEbKitSignerOutput {
    param([Parameter(Mandatory = $true)][string]$Text)
    if ($Text -notmatch '(?m)^Verifies\s*$') {
        throw 'APK_SIGNER_OUTPUT_NOT_VERIFIED'
    }
    $matches = [regex]::Matches($Text, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$')
    if ($matches.Count -ne 1) {
        throw 'APK_SIGNER_CERTIFICATE_SET_INVALID'
    }
    $actual = ($matches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
    if ($actual -cne $approvedSignerCertificateSha256) {
        throw 'P011_RELEASE_SIGNER_NOT_APPROVED'
    }
}

function Assert-AnEbKitBuildConfigVariant {
    param(
        [Parameter(Mandatory = $true)][string]$BuildConfigText,
        [Parameter(Mandatory = $true)][string]$Variant,
        [Parameter(Mandatory = $true)][string]$ExpectedSourceCommit
    )
    $commitPattern = '(?m)\bPROTOTYPE_SOURCE_COMMIT\b[^\r\n]{0,256}\b' + [regex]::Escape($ExpectedSourceCommit) + '\b'
    if ($BuildConfigText -cnotmatch $commitPattern) {
        throw 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND'
    }
    if ($Variant -ceq 'release') {
        $expectedBuildType = 'release'
        $expectedProtoFlag = 'false'
    }
    elseif ($Variant -ceq 'prototypeRelease') {
        $expectedBuildType = 'prototypeRelease'
        $expectedProtoFlag = 'true'
    }
    else {
        throw ('APK_VARIANT_UNKNOWN: ' + $Variant)
    }
    $buildTypePattern = '(?m)^\.field\s+[^\r\n]*\bBUILD_TYPE:Ljava/lang/String;\s*=\s*"' + [regex]::Escape($expectedBuildType) + '"\s*$'
    $protoReleasePattern = '(?m)^\.field\s+[^\r\n]*\bPROTOTYPE_RELEASE:Z\s*=\s*' + $expectedProtoFlag + '\s*$'
    $cleartextPattern = '(?m)^\.field\s+[^\r\n]*\bPROTOTYPE_PRIVATE_CLEARTEXT:Z\s*=\s*' + $expectedProtoFlag + '\s*$'
    if ($BuildConfigText -cnotmatch $buildTypePattern -or
        $BuildConfigText -cnotmatch $protoReleasePattern -or
        $BuildConfigText -cnotmatch $cleartextPattern) {
        throw ('APK_VARIANT_MISMATCH: ' + $Variant)
    }
}

function New-AnEbDeterministicPilotKitZip {
    param(
        [Parameter(Mandatory = $true)][string]$KitRoot,
        [Parameter(Mandatory = $true)][string]$TopLevelDirName,
        [Parameter(Mandatory = $true)][string]$ZipPath
    )
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $fixedTimestamp = [DateTimeOffset]::new(2000, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
    $archive = [System.IO.Compression.ZipFile]::Open($ZipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entrySpecs = @([pscustomobject]@{
            Name = ($TopLevelDirName + '/prototype-launcher/results/')
            FullPath = $null
            IsDirectory = $true
        }) + @(Get-AnEbRelativeFiles -Root $KitRoot | ForEach-Object {
            [pscustomobject]@{
                Name = ($TopLevelDirName + '/' + $_.RelativePath)
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
            if ($spec.IsDirectory) { continue }
            $input = [System.IO.File]::OpenRead($spec.FullPath)
            $output = $entry.Open()
            try { $input.CopyTo($output) }
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
    if ($SourceCommit -cnotmatch '^[0-9a-f]{40}$') {
        throw 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND'
    }
    if ($ReleaseCandidate -cnotmatch '^[a-z0-9][a-z0-9._-]{0,63}$') {
        throw 'RELEASE_CANDIDATE_INVALID'
    }
    if ($BuiltAtUtc -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$') {
        throw 'BUILT_AT_UTC_INVALID'
    }

    $sourceFull = Get-AnEbFullPath -Path $SourceRoot
    Assert-AnEbDirectory -Path $sourceFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $sourceFull
    $headCommit = Invoke-AnEbKitGitText -WorkingDirectory $sourceFull -Arguments @('rev-parse', 'HEAD')
    if (-not [string]::Equals($headCommit, $SourceCommit, [System.StringComparison]::Ordinal)) {
        throw 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND'
    }
    $dirty = Invoke-AnEbKitGitText -WorkingDirectory $sourceFull -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
    if (-not [string]::IsNullOrEmpty($dirty)) {
        throw 'SOURCE_WORKTREE_NOT_CLEAN'
    }

    $releaseApkFull = Get-AnEbFullPath -Path $ReleaseApkPath
    $protoApkFull = Get-AnEbFullPath -Path $PrototypeReleaseApkPath
    foreach ($f in @($releaseApkFull, $protoApkFull)) {
        Assert-AnEbRegularFile -Path $f | Out-Null
        Assert-AnEbNoReparseAncestors -Path $f
    }
    if ([string]::Equals($releaseApkFull, $protoApkFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'PILOT_KIT_DUPLICATE_APK_PATH'
    }
    $releaseApkSha256 = Get-AnEbSha256File -Path $releaseApkFull
    $protoReleaseApkSha256 = Get-AnEbSha256File -Path $protoApkFull
    if ($releaseApkSha256 -ceq $protoReleaseApkSha256) {
        throw 'PILOT_KIT_DUPLICATE_APK_HASH'
    }

    $launcherFull = Get-AnEbFullPath -Path $PrototypeLauncherDirectory
    Assert-AnEbDirectory -Path $launcherFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $launcherFull
    Assert-AnEbTreeNoReparse -Root $launcherFull

    # Verify prototype-launcher via its own verify-package.ps1
    $launcherVerify = Join-Path $launcherFull 'tools\verify-package.ps1'
    Assert-AnEbRegularFile -Path $launcherVerify | Out-Null
    $verifyLauncherArgs = @('-Root', $launcherFull)
    $hasAdmission = (-not [string]::IsNullOrWhiteSpace($PrototypePackageZipPath)) -or
        (-not [string]::IsNullOrWhiteSpace($PrototypeAdmissionReceiptPath)) -or
        (-not [string]::IsNullOrWhiteSpace($ExpectedPrototypeAdmissionReceiptSha256))
    if ($hasAdmission) {
        if ([string]::IsNullOrWhiteSpace($PrototypePackageZipPath) -or
            [string]::IsNullOrWhiteSpace($PrototypeAdmissionReceiptPath) -or
            [string]::IsNullOrWhiteSpace($ExpectedPrototypeAdmissionReceiptSha256)) {
            throw 'PROTOTYPE_ADMISSION_TUPLE_INCOMPLETE'
        }
        $protoZipFull = Get-AnEbFullPath -Path $PrototypePackageZipPath
        $protoAdmFull = Get-AnEbFullPath -Path $PrototypeAdmissionReceiptPath
        Assert-AnEbRegularFile -Path $protoZipFull | Out-Null
        Assert-AnEbRegularFile -Path $protoAdmFull | Out-Null
        $verifyLauncherArgs += @(
            '-RequireExternalAdmission',
            '-PackageZipPath', $protoZipFull,
            '-AdmissionReceiptPath', $protoAdmFull,
            '-ExpectedAdmissionReceiptSha256', $ExpectedPrototypeAdmissionReceiptSha256
        )
    }
    $lvOut = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $launcherVerify @verifyLauncherArgs 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw ('PROTOTYPE_LAUNCHER_VERIFY_FAILED: ' + ([string]::Join(' | ', @($lvOut | ForEach-Object { $_.ToString() }))))
    }

    $launcherVersion = Read-AnEbUtf8Strict -Path (Join-Path $launcherFull 'VERSION.json') | ConvertFrom-Json
    if ($launcherVersion.source_commit -cne $SourceCommit -or
        $launcherVersion.server_source_commit -cne $SourceCommit -or
        $launcherVersion.android_source_commit -cne $SourceCommit -or
        $launcherVersion.evidence_source_commit -cne $SourceCommit) {
        throw 'LAUNCHER_VERSION_PROVENANCE_MISMATCH'
    }

    $bundledApkPath = Join-Path $launcherFull 'android\aneb-prototype-0.1.apk'
    if ((Get-AnEbSha256File -Path $protoApkFull) -cne (Get-AnEbSha256File -Path $bundledApkPath)) {
        throw 'PROTOTYPE_APK_PARITY_MISMATCH'
    }

    $resolvedSigner = Resolve-AnEbKitSdkTool -ExplicitPath $ApkSignerPath -ToolKind 'apksigner'
    $resolvedAnalyzer = Resolve-AnEbKitSdkTool -ExplicitPath $ApkAnalyzerPath -ToolKind 'apkanalyzer'
    if ([string]::IsNullOrWhiteSpace($resolvedSigner) -or [string]::IsNullOrWhiteSpace($resolvedAnalyzer)) {
        if (-not $AllowMissingSdkTools) {
            throw 'SDK_TOOLS_UNAVAILABLE'
        }
        if ([string]::IsNullOrWhiteSpace($ReleaseApkSignerReceiptPath) -or [string]::IsNullOrWhiteSpace($PrototypeReleaseApkSignerReceiptPath)) {
            throw 'APKSIGNER_RECEIPT_REQUIRED_WHEN_SDK_SKIPPED'
        }
    }
    $resolvedGo = Resolve-AnEbKitGoTool -ExplicitPath $GoPath
    if ([string]::IsNullOrWhiteSpace($resolvedGo) -and (-not $AllowMissingGoTool)) {
        throw 'GO_TOOL_UNAVAILABLE'
    }

    $outputFull = Get-AnEbFullPath -Path $OutputDirectory
    $zipFull = Get-AnEbFullPath -Path $OutputZipPath
    $expectedZipName = 'ANEB-Pilot-v0.2-' + $ReleaseCandidate + '-windows-x64.zip'
    if (-not [string]::Equals([System.IO.Path]::GetFileName($zipFull), $expectedZipName, [System.StringComparison]::Ordinal)) {
        throw ('OUTPUT_ZIP_NAME_INVALID: expected ' + $expectedZipName)
    }
    $stagingParentFull = Split-Path -Parent $outputFull
    $zipParentFull = Split-Path -Parent $zipFull
    $outputFull = Assert-AnEbKitFreshTargetParent -Path $outputFull
    $zipFull = Assert-AnEbKitFreshTargetParent -Path $zipFull

    $stagingRoot = Join-Path $stagingParentFull ('.aneb-pilot-kit-building-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stagingRoot -ErrorAction Stop | Out-Null
    foreach ($sub in @('apks', 'docs', 'templates', 'prototype-launcher')) {
        New-Item -ItemType Directory -Path (Join-Path $stagingRoot $sub) -ErrorAction Stop | Out-Null
    }

    # Copy APKs and .apksigner.txt receipts
    $stagedReleaseApk = Join-Path $stagingRoot 'apks\probe-release.apk'
    $stagedProtoApk = Join-Path $stagingRoot 'apks\probe-prototypeRelease.apk'
    $stagedReleaseRec = Join-Path $stagingRoot 'apks\probe-release.apk.apksigner.txt'
    $stagedProtoRec = Join-Path $stagingRoot 'apks\probe-prototypeRelease.apk.apksigner.txt'
    Write-AnEbCreateNewBytes -Path $stagedReleaseApk -Bytes (Read-AnEbBytes -Path $releaseApkFull)
    Write-AnEbCreateNewBytes -Path $stagedProtoApk -Bytes (Read-AnEbBytes -Path $protoApkFull)

    $apkSignerVerified = $false
    $apkAnalyzerVerified = $false
    if (-not [string]::IsNullOrWhiteSpace($resolvedSigner) -and -not [string]::IsNullOrWhiteSpace($resolvedAnalyzer)) {
        foreach ($pair in @(
            @{ Variant = 'release'; Apk = $stagedReleaseApk; RecOut = $stagedReleaseRec; RecIn = $ReleaseApkSignerReceiptPath },
            @{ Variant = 'prototypeRelease'; Apk = $stagedProtoApk; RecOut = $stagedProtoRec; RecIn = $PrototypeReleaseApkSignerReceiptPath }
        )) {
            $signerOut = Invoke-AnEbKitToolText -ToolPath $resolvedSigner -Arguments @('verify', '--verbose', '--print-certs', $pair.Apk) -FailureCode 'APKSIGNER_VERIFY_FAILED'
            Assert-AnEbKitSignerOutput -Text $signerOut
            if (-not [string]::IsNullOrWhiteSpace($pair.RecIn)) {
                $recBytes = Read-AnEbBytes -Path (Get-AnEbFullPath -Path $pair.RecIn)
                $recText = ConvertFrom-AnEbUtf8Strict -Bytes $recBytes
                Assert-AnEbKitSignerOutput -Text $recText
                Write-AnEbCreateNewBytes -Path $pair.RecOut -Bytes $recBytes
            }
            else {
                $normText = ($signerOut.Replace("`r`n", "`n").TrimEnd("`r", "`n")) + "`n"
                Write-AnEbCreateNewUtf8 -Path $pair.RecOut -Text $normText
            }
            $pkg = (Invoke-AnEbKitToolText -ToolPath $resolvedAnalyzer -Arguments @('manifest', 'application-id', $pair.Apk) -FailureCode 'APKANALYZER_FAILED').Trim()
            $vName = (Invoke-AnEbKitToolText -ToolPath $resolvedAnalyzer -Arguments @('manifest', 'version-name', $pair.Apk) -FailureCode 'APKANALYZER_FAILED').Trim()
            $vCode = (Invoke-AnEbKitToolText -ToolPath $resolvedAnalyzer -Arguments @('manifest', 'version-code', $pair.Apk) -FailureCode 'APKANALYZER_FAILED').Trim()
            $cfg = Invoke-AnEbKitToolText -ToolPath $resolvedAnalyzer -Arguments @('dex', 'code', '--class', 'com.aneb.probe.BuildConfig', $pair.Apk) -FailureCode 'APKANALYZER_FAILED'
            if ($pkg -cne 'com.aneb.probe' -or $vName -cne '0.2.0' -or $vCode -cne '20') {
                throw 'APK_IDENTITY_MISMATCH'
            }
            Assert-AnEbKitBuildConfigVariant -BuildConfigText $cfg -Variant $pair.Variant -ExpectedSourceCommit $SourceCommit
        }
        $apkSignerVerified = $true
        $apkAnalyzerVerified = $true
    }
    else {
        foreach ($pair in @(
            @{ RecOut = $stagedReleaseRec; RecIn = (Get-AnEbFullPath -Path $ReleaseApkSignerReceiptPath) },
            @{ RecOut = $stagedProtoRec; RecIn = (Get-AnEbFullPath -Path $PrototypeReleaseApkSignerReceiptPath) }
        )) {
            Assert-AnEbRegularFile -Path $pair.RecIn | Out-Null
            $recText = Read-AnEbUtf8Strict -Path $pair.RecIn
            Assert-AnEbKitSignerOutput -Text $recText
            Write-AnEbCreateNewBytes -Path $pair.RecOut -Bytes (Read-AnEbBytes -Path $pair.RecIn)
        }
    }

    # Copy prototype-launcher files (excluding results/ contents)
    $stagedLauncher = Join-Path $stagingRoot 'prototype-launcher'
    foreach ($file in @(Get-AnEbRelativeFiles -Root $launcherFull)) {
        if ($file.RelativePath.StartsWith('results/', [System.StringComparison]::Ordinal)) {
            continue
        }
        $dest = Join-Path $stagedLauncher ($file.RelativePath -replace '/', '\')
        $destParent = Split-Path -Parent $dest
        if (-not (Test-Path -LiteralPath $destParent -PathType Container)) {
            New-Item -ItemType Directory -Path $destParent -Force -ErrorAction Stop | Out-Null
        }
        Write-AnEbCreateNewBytes -Path $dest -Bytes (Read-AnEbBytes -Path $file.FullPath)
    }
    New-Item -ItemType Directory -Path (Join-Path $stagedLauncher 'results') -Force -ErrorAction Stop | Out-Null

    $goVcsVerified = $false
    $stagedServerExe = Join-Path $stagedLauncher 'bin\aneb-server.exe'
    if (-not [string]::IsNullOrWhiteSpace($resolvedGo)) {
        $goOut = Invoke-AnEbKitToolText -ToolPath $resolvedGo -Arguments @('version', '-m', $stagedServerExe) -FailureCode 'SERVER_BUILD_INFO_UNREADABLE'
        $revMatches = [regex]::Matches($goOut, '(?m)^\s*build\s+vcs\.revision=([0-9a-f]{40})\s*$')
        $modMatches = [regex]::Matches($goOut, '(?m)^\s*build\s+vcs\.modified=(true|false)\s*$')
        if ($revMatches.Count -ne 1 -or $modMatches.Count -ne 1 -or
            $revMatches[0].Groups[1].Value -cne $SourceCommit -or
            $modMatches[0].Groups[1].Value -cne 'false') {
            throw 'SERVER_SOURCE_PROVENANCE_MISMATCH'
        }
        $goVcsVerified = $true
    }

    # Copy git-pinned docs, templates, and verifier
    $docMap = [ordered]@{
        'docs/PILOT_V02_QUICKSTART_ZH.md' = 'docs/pilot-readiness/PILOT_V02_QUICKSTART_ZH.md'
        'docs/PILOT_READINESS_README.md' = 'docs/pilot-readiness/README.md'
        'templates/PILOT_V02_FEEDBACK_TEMPLATE.md' = 'docs/pilot-readiness/PILOT_V02_FEEDBACK_TEMPLATE.md'
        'templates/PILOT_V02_FEEDBACK_TEMPLATE.json' = 'docs/pilot-readiness/PILOT_V02_FEEDBACK_TEMPLATE.json'
    }
    if ($IncludeBaselineReport) {
        $docMap['docs/ANEB_AI_NETWORK_EXPERIENCE_BASELINE_REPORT_V02.md'] = 'docs/research/ANEB_AI_NETWORK_EXPERIENCE_BASELINE_REPORT_V02.md'
    }
    $documentsReceipt = [ordered]@{}
    foreach ($targetRel in $docMap.Keys) {
        $targetFull = Join-Path $stagingRoot ($targetRel -replace '/', '\')
        Write-AnEbKitGitBlob -WorkingDirectory $sourceFull -Commit $SourceCommit -RelativePath $docMap[$targetRel] -TargetPath $targetFull
        $documentsReceipt[$targetRel] = [ordered]@{
            bytes = [int64](Get-Item -LiteralPath $targetFull -Force).Length
            sha256 = (Get-AnEbSha256File -Path $targetFull)
        }
    }
    Write-AnEbKitGitBlob -WorkingDirectory $sourceFull -Commit $SourceCommit -RelativePath 'tools/pilot-kit/verify-pilot-v0.2-kit.ps1' -TargetPath (Join-Path $stagingRoot 'verify-pilot-v0.2-kit.ps1')

    $protoZipName = ''
    $protoZipSha = ''
    $protoAdmissionSha = ''
    if ($hasAdmission) {
        $protoZipName = [System.IO.Path]::GetFileName($protoZipFull)
        $protoZipSha = Get-AnEbSha256File -Path $protoZipFull
        $protoAdmissionSha = Get-AnEbSha256File -Path $protoAdmFull
    }

    $kitReceipt = [ordered]@{
        schema_version = 'aneb-pilot-v0.2-kit-receipt-0.1'
        kit_version = 'pilot-v0.2'
        release_candidate = $ReleaseCandidate
        source_commit = $SourceCommit
        built_at_utc = $BuiltAtUtc
        approved_signer_cert_sha256 = $approvedSignerCertificateSha256
        apks = @(
            [ordered]@{
                relative_path = 'apks/probe-release.apk'
                receipt_path = 'apks/probe-release.apk.apksigner.txt'
                variant = 'release'
                package_name = 'com.aneb.probe'
                version_name = '0.2.0'
                version_code = 20
                source_commit = $SourceCommit
                approved_signer_sha256 = $approvedSignerCertificateSha256
                bytes = [int64](Get-Item -LiteralPath $stagedReleaseApk -Force).Length
                sha256 = (Get-AnEbSha256File -Path $stagedReleaseApk)
                apksigner_verified = $apkSignerVerified
                apkanalyzer_verified = $apkAnalyzerVerified
            },
            [ordered]@{
                relative_path = 'apks/probe-prototypeRelease.apk'
                receipt_path = 'apks/probe-prototypeRelease.apk.apksigner.txt'
                variant = 'prototypeRelease'
                package_name = 'com.aneb.probe'
                version_name = '0.2.0'
                version_code = 20
                source_commit = $SourceCommit
                approved_signer_sha256 = $approvedSignerCertificateSha256
                bytes = [int64](Get-Item -LiteralPath $stagedProtoApk -Force).Length
                sha256 = (Get-AnEbSha256File -Path $stagedProtoApk)
                apksigner_verified = $apkSignerVerified
                apkanalyzer_verified = $apkAnalyzerVerified
            }
        )
        prototype_launcher = [ordered]@{
            directory = 'prototype-launcher'
            version_json_path = 'prototype-launcher/VERSION.json'
            source_commit = $SourceCommit
            server_path = 'prototype-launcher/bin/aneb-server.exe'
            server_version = 'aneb-server/0.1.0'
            server_sha256 = (Get-AnEbSha256File -Path $stagedServerExe)
            server_go_vcs_verified = $goVcsVerified
            bundled_apk_path = 'prototype-launcher/android/aneb-prototype-0.1.apk'
            bundled_apk_sha256 = (Get-AnEbSha256File -Path (Join-Path $stagedLauncher 'android\aneb-prototype-0.1.apk'))
            evidence_verifier_path = 'prototype-launcher/bin/evidence/aneb-evidence.exe'
            evidence_source_commit = [string]$launcherVersion.evidence_source_commit
            prototype_package_zip_name = $protoZipName
            prototype_package_zip_sha256 = $protoZipSha
            prototype_admission_receipt_sha256 = $protoAdmissionSha
        }
        documents = $documentsReceipt
    }
    Write-AnEbCreateNewUtf8 -Path (Join-Path $stagingRoot 'KIT_RECEIPT.json') -Text (($kitReceipt | ConvertTo-Json -Compress -Depth 10) + [char]10)

    # Generate SHA256SUMS.txt (strict UTF-8 no-BOM, LF, sorted ordinal)
    $staticRecords = @(Get-AnEbRelativeFiles -Root $stagingRoot |
        Where-Object {
            $_.RelativePath -cne 'SHA256SUMS.txt' -and
            -not $_.RelativePath.StartsWith('prototype-launcher/results/', [System.StringComparison]::Ordinal)
        })
    $relPaths = @($staticRecords | ForEach-Object { [string]$_.RelativePath })
    [Array]::Sort($relPaths, [System.StringComparer]::Ordinal)
    $sumLines = @()
    foreach ($rel in $relPaths) {
        $rec = @($staticRecords | Where-Object { $_.RelativePath -ceq $rel })[0]
        $sumLines += ((Get-AnEbSha256File -Path $rec.FullPath) + '  ' + $rel)
    }
    Write-AnEbCreateNewUtf8 -Path (Join-Path $stagingRoot 'SHA256SUMS.txt') -Text ([string]::Join([char]10, $sumLines) + [char]10)

    # Run staged verify-pilot-v0.2-kit.ps1 before publishing
    $stagedVerifier = Join-Path $stagingRoot 'verify-pilot-v0.2-kit.ps1'
    $verifyArgs = @('-KitRoot', $stagingRoot, '-ExpectedSourceCommit', $SourceCommit)
    if (-not [string]::IsNullOrWhiteSpace($resolvedSigner) -and -not [string]::IsNullOrWhiteSpace($resolvedAnalyzer)) {
        $verifyArgs += @('-RequireSdkTools', '-ApkSignerPath', $resolvedSigner, '-ApkAnalyzerPath', $resolvedAnalyzer)
    }
    if (-not [string]::IsNullOrWhiteSpace($resolvedGo)) {
        $verifyArgs += @('-RequireGoTool', '-GoPath', $resolvedGo)
    }
    if ($hasAdmission) {
        $verifyArgs += @(
            '-PrototypePackageZipPath', $protoZipFull,
            '-PrototypeAdmissionReceiptPath', $protoAdmFull,
            '-ExpectedPrototypeAdmissionReceiptSha256', $ExpectedPrototypeAdmissionReceiptSha256
        )
    }
    $verifyOut = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $stagedVerifier @verifyArgs 2>&1)
    $verifyText = [string]::Join("`n", @($verifyOut | ForEach-Object { $_.ToString() }))
    if ($LASTEXITCODE -ne 0 -or $verifyText -notmatch 'PASS PILOT_V02_KIT_VERIFIED') {
        throw ('KIT_SELF_VERIFICATION_FAILED: ' + $verifyText)
    }

    # Build deterministic ZIP
    $zipStagingRoot = Join-Path $zipParentFull ('.aneb-pilot-kit-zip-building-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $zipStagingRoot -ErrorAction Stop | Out-Null
    $zipStagingPath = Join-Path $zipStagingRoot $expectedZipName
    $topDirName = 'ANEB-Pilot-v0.2-' + $ReleaseCandidate
    New-AnEbDeterministicPilotKitZip -KitRoot $stagingRoot -TopLevelDirName $topDirName -ZipPath $zipStagingPath
    $zipSha256 = Get-AnEbSha256File -Path $zipStagingPath

    # Publish atomically
    $outputOwnershipToken = [Guid]::NewGuid().ToString('N')
    $outputOwnershipMarker = Join-Path $stagingRoot '.aneb-pilot-kit-owned'
    Write-AnEbCreateNewUtf8 -Path $outputOwnershipMarker -Text ($outputOwnershipToken + [char]10)
    Move-Item -LiteralPath $stagingRoot -Destination $outputFull -ErrorAction Stop
    $stagingRoot = $null
    $outputOwnershipMarker = Join-Path $outputFull '.aneb-pilot-kit-owned'
    $outputCreated = $true
    Move-Item -LiteralPath $zipStagingPath -Destination $zipFull -ErrorAction Stop
    $zipCreated = $true
    $zipStagingPath = $null
    Remove-Item -LiteralPath $zipStagingRoot -Force -ErrorAction Stop
    $zipStagingRoot = $null
    if ((Read-AnEbUtf8Strict -Path $outputOwnershipMarker).TrimEnd("`r", "`n") -cne $outputOwnershipToken) {
        throw 'OUTPUT_OWNERSHIP_MARKER_INVALID'
    }
    Remove-Item -LiteralPath $outputOwnershipMarker -Force -ErrorAction Stop
    $outputOwnershipMarker = $null

    Write-Output ('PASS PILOT_V02_KIT_BUILT source_commit=' + $SourceCommit + ' zip_sha256=' + $zipSha256)
    exit 0
}
catch {
    Remove-AnEbKitOwnedTempDirectory -Path $stagingRoot -ExpectedParent $stagingParentFull -ExpectedLeafPattern '^\.aneb-pilot-kit-building-[0-9a-f]{32}$'
    if ($null -ne $zipStagingPath -and (Test-Path -LiteralPath $zipStagingPath -PathType Leaf)) {
        Remove-AnEbKitPublishedFile -Path $zipStagingPath -ExpectedParent $zipStagingRoot -ExpectedSha256 $zipSha256
    }
    Remove-AnEbKitOwnedTempDirectory -Path $zipStagingRoot -ExpectedParent $zipParentFull -ExpectedLeafPattern '^\.aneb-pilot-kit-zip-building-[0-9a-f]{32}$'
    if ($zipCreated) { Remove-AnEbKitPublishedFile -Path $zipFull -ExpectedParent $zipParentFull -ExpectedSha256 $zipSha256 }
    if ($outputCreated) { Remove-AnEbKitPublishedDirectory -Path $outputFull -ExpectedParent $stagingParentFull -OwnershipMarkerPath $outputOwnershipMarker -OwnershipToken $outputOwnershipToken }
    Write-Output ('FAIL PILOT_KIT_BUILD ' + $_.Exception.Message)
    exit 1
}
