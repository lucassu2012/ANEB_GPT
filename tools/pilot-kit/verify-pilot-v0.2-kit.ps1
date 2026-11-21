[CmdletBinding()]
param(
    [string]$KitRoot = '',
    [string]$ExpectedSourceCommit = '',
    [switch]$RequireSdkTools,
    [switch]$RequireGoTool,
    [string]$ApkSignerPath = '',
    [string]$ApkAnalyzerPath = '',
    [string]$GoPath = '',
    [string]$PrototypePackageZipPath = '',
    [string]$PrototypeAdmissionReceiptPath = '',
    [string]$ExpectedPrototypeAdmissionReceiptSha256 = '',
    [string]$JsonOut = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$approvedSignerCertificateSha256 = 'b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003'

function Stop-AnEbPilotKitCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message
    )
    Write-Output ("FAIL " + $Code + " " + $Message)
    exit 1
}

function Resolve-AnEbCommonScript {
    param([Parameter(Mandatory = $true)][string]$RootPath)
    $rootFull = [System.IO.Path]::GetFullPath($RootPath).TrimEnd('\')
    $scriptParentFull = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
    $candidates = @(
        (Join-Path $RootPath 'prototype-launcher\tools\common.ps1'),
        (Join-Path $PSScriptRoot 'prototype-launcher\tools\common.ps1'),
        (Join-Path $PSScriptRoot '..\common.ps1')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $fullCandidate = [System.IO.Path]::GetFullPath($candidate)
            $item = Get-Item -LiteralPath $fullCandidate -Force
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_REPARSE' -Message ("common.ps1 is a reparse point: " + $candidate)
            }
            $stopRoot = $null
            if ($fullCandidate.StartsWith(($rootFull + '\'), [System.StringComparison]::OrdinalIgnoreCase)) {
                $stopRoot = $rootFull
            }
            elseif ($fullCandidate.StartsWith(($scriptParentFull + '\'), [System.StringComparison]::OrdinalIgnoreCase)) {
                $stopRoot = $scriptParentFull
            }
            $dir = Split-Path -Parent $fullCandidate
            while (-not [string]::IsNullOrWhiteSpace($dir)) {
                $dirItem = Get-Item -LiteralPath $dir -Force
                if (($dirItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                    Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_REPARSE' -Message ("common.ps1 ancestor is a reparse point: " + $dir)
                }
                if ($null -ne $stopRoot -and [string]::Equals($dir.TrimEnd('\'), $stopRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                    break
                }
                $next = Split-Path -Parent $dir
                if ([string]::IsNullOrWhiteSpace($next) -or $next -eq $dir) {
                    break
                }
                $dir = $next
            }
            return $fullCandidate
        }
    }
    Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_COMMON_MISSING' -Message 'unable to locate tools/common.ps1'
}

function Resolve-AnEbSdkTool {
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

function Resolve-AnEbGoTool {
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

function Assert-AnEbPeExecutable {
    param([Parameter(Mandatory = $true)][string]$Path)
    $bytes = Read-AnEbBytes -Path $Path
    if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) {
        throw ('SERVER_NOT_PE_HEADER: ' + $Path)
    }
    $peOffset = [int64]$bytes[0x3c] -bor ([int64]$bytes[0x3d] -shl 8) -bor ([int64]$bytes[0x3e] -shl 16) -bor ([int64]$bytes[0x3f] -shl 24)
    if ($peOffset -lt 64 -or $peOffset -gt ($bytes.Length - 4) -or
        $bytes[$peOffset] -ne 0x50 -or $bytes[$peOffset + 1] -ne 0x45 -or
        $bytes[$peOffset + 2] -ne 0x00 -or $bytes[$peOffset + 3] -ne 0x00) {
        throw ('SERVER_NOT_PE_SIGNATURE: ' + $Path)
    }
}

function Assert-AnEbApkContainer {
    param([Parameter(Mandatory = $true)][string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead((Get-AnEbFullPath -Path $Path))
        $entry = $archive.GetEntry('AndroidManifest.xml')
        if ($null -eq $entry -or $entry.Length -le 0) {
            throw ('APK_MANIFEST_MISSING: ' + $Path)
        }
    }
    finally {
        if ($null -ne $archive) { $archive.Dispose() }
    }
}

function Assert-AnEbApkSignerReceiptText {
    param(
        [Parameter(Mandatory = $true)][string]$ReceiptPath,
        [Parameter(Mandatory = $true)][string]$ExpectedSignerSha256
    )
    $text = Read-AnEbUtf8Strict -Path $ReceiptPath
    if ($text -notmatch '(?m)^Verifies\s*$') {
        throw ('APKSIGNER_RECEIPT_NOT_VERIFIED: ' + $ReceiptPath)
    }
    if ($text -notmatch '(?m)^Number of signers:\s*1\s*$') {
        throw ('APKSIGNER_RECEIPT_SIGNER_COUNT_INVALID: ' + $ReceiptPath)
    }
    $matches = [regex]::Matches($text, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$')
    if ($matches.Count -ne 1) {
        throw ('APKSIGNER_RECEIPT_DIGEST_MISSING: ' + $ReceiptPath)
    }
    $actual = ($matches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
    if ($actual -cne $ExpectedSignerSha256) {
        throw ('APKSIGNER_RECEIPT_SIGNER_MISMATCH: ' + $ReceiptPath)
    }
}

function Invoke-AnEbToolVerify {
    param(
        [Parameter(Mandatory = $true)][string]$ToolPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureCode
    )
    $output = @(& $ToolPath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw ($FailureCode + ': ' + ([string]::Join(' | ', @($output | ForEach-Object { $_.ToString() }))))
    }
    return [string]::Join("`n", @($output | ForEach-Object { $_.ToString() })).Trim()
}

try {
    if ([string]::IsNullOrWhiteSpace($KitRoot)) {
        $KitRoot = $PSScriptRoot
    }
    $commonPath = Resolve-AnEbCommonScript -RootPath $KitRoot
    . $commonPath

    $kitFull = Get-AnEbFullPath -Path $KitRoot
    Assert-AnEbDirectory -Path $kitFull | Out-Null
    Assert-AnEbNoReparseAncestors -Path $kitFull
    Assert-AnEbTreeNoReparse -Root $kitFull

    if (-not [string]::IsNullOrWhiteSpace($ExpectedSourceCommit) -and $ExpectedSourceCommit -cnotmatch '^[0-9a-f]{40}$') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_EXPECTED_COMMIT_INVALID' -Message 'ExpectedSourceCommit must be 40 lowercase hex'
    }

    $requiredFiles = @(
        'KIT_RECEIPT.json',
        'SHA256SUMS.txt',
        'verify-pilot-v0.2-kit.ps1',
        'apks/probe-release.apk',
        'apks/probe-release.apk.apksigner.txt',
        'apks/probe-prototypeRelease.apk',
        'apks/probe-prototypeRelease.apk.apksigner.txt',
        'docs/PILOT_V02_QUICKSTART_ZH.md',
        'docs/PILOT_READINESS_README.md',
        'templates/PILOT_V02_FEEDBACK_TEMPLATE.md',
        'templates/PILOT_V02_FEEDBACK_TEMPLATE.json',
        'prototype-launcher/VERSION.json',
        'prototype-launcher/bin/aneb-server.exe',
        'prototype-launcher/bin/evidence/aneb-evidence.exe',
        'prototype-launcher/android/aneb-prototype-0.1.apk',
        'prototype-launcher/tools/verify-package.ps1'
    )
    foreach ($rel in $requiredFiles) {
        $full = Join-Path $kitFull ($rel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_MISSING_FILE' -Message ('missing required file: ' + $rel)
        }
        Assert-AnEbRegularFile -Path $full | Out-Null
    }

    # 1. Verify root SHA256SUMS.txt closure
    $checksumPath = Join-Path $kitFull 'SHA256SUMS.txt'
    $checksumText = Read-AnEbUtf8Strict -Path $checksumPath
    $checksumLines = @($checksumText -split '\r?\n' | Where-Object { $_ -and -not $_.StartsWith('#') })
    if ($checksumLines.Count -eq 0) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_CHECKSUM_EMPTY' -Message 'SHA256SUMS.txt is empty'
    }
    $seenHashes = @{}
    foreach ($line in $checksumLines) {
        if ($line -cnotmatch '^([0-9a-f]{64})  (.+)$') {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_CHECKSUM_SYNTAX' -Message ('invalid SHA256SUMS line: ' + $line)
        }
        $expectedHash = $Matches[1]
        $relative = $Matches[2]
        Assert-AnEbRelativePath -RelativePath $relative
        if ($relative -ceq 'SHA256SUMS.txt' -or $relative.StartsWith('prototype-launcher/results/', [System.StringComparison]::Ordinal)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_CHECKSUM_SCOPE' -Message ('forbidden path in SHA256SUMS: ' + $relative)
        }
        if ($seenHashes.ContainsKey($relative)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_CHECKSUM_DUPLICATE' -Message ('duplicate checksum path: ' + $relative)
        }
        $seenHashes[$relative] = $expectedHash
        $targetPath = Join-Path $kitFull ($relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_CHECKSUM_MISSING_TARGET' -Message ('missing checksum target: ' + $relative)
        }
        if ((Get-AnEbSha256File -Path $targetPath) -cne $expectedHash) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_CHECKSUM_MISMATCH' -Message ('SHA-256 mismatch: ' + $relative)
        }
    }
    $actualStatic = @(Get-AnEbRelativeFiles -Root $kitFull |
        Where-Object {
            $_.RelativePath -cne 'SHA256SUMS.txt' -and
            -not $_.RelativePath.StartsWith('prototype-launcher/results/', [System.StringComparison]::Ordinal)
        } |
        Select-Object -ExpandProperty RelativePath | Sort-Object)
    $listedStatic = @($seenHashes.Keys | Sort-Object)
    $closureDiff = @(Compare-Object -ReferenceObject $actualStatic -DifferenceObject $listedStatic)
    if ($closureDiff.Count -ne 0) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_CHECKSUM_CLOSURE' -Message 'SHA256SUMS.txt closure does not match kit files'
    }

    # 2. Validate KIT_RECEIPT.json
    $receipt = Read-AnEbUtf8Strict -Path (Join-Path $kitFull 'KIT_RECEIPT.json') | ConvertFrom-Json
    if ($receipt.schema_version -cne 'aneb-pilot-v0.2-kit-receipt-0.1') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_RECEIPT_SCHEMA' -Message 'KIT_RECEIPT schema_version mismatch'
    }
    if ($receipt.kit_version -cne 'pilot-v0.2') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_RECEIPT_VERSION' -Message 'KIT_RECEIPT kit_version mismatch'
    }
    if ($receipt.release_candidate -cnotmatch '^[a-z0-9][a-z0-9._-]{0,63}$') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_RECEIPT_RC' -Message 'KIT_RECEIPT release_candidate invalid'
    }
    if ($receipt.source_commit -cnotmatch '^[0-9a-f]{40}$') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_RECEIPT_COMMIT' -Message 'KIT_RECEIPT source_commit invalid'
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSourceCommit) -and $receipt.source_commit -cne $ExpectedSourceCommit) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_SOURCE_COMMIT_MISMATCH' -Message ('expected ' + $ExpectedSourceCommit + ', got ' + $receipt.source_commit)
    }
    if ($receipt.built_at_utc -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_RECEIPT_TIMESTAMP' -Message 'KIT_RECEIPT built_at_utc invalid'
    }
    if ($receipt.approved_signer_cert_sha256 -cne $approvedSignerCertificateSha256) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_SIGNER_PIN' -Message 'KIT_RECEIPT approved_signer_cert_sha256 mismatch'
    }

    $sourceCommit = [string]$receipt.source_commit
    $apks = @($receipt.apks)
    if ($apks.Count -ne 2) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APKS_COUNT' -Message 'KIT_RECEIPT must declare exactly 2 APKs'
    }
    $expectedVariants = @{
        'release' = @{
            rel = 'apks/probe-release.apk'
            rec = 'apks/probe-release.apk.apksigner.txt'
        }
        'prototypeRelease' = @{
            rel = 'apks/probe-prototypeRelease.apk'
            rec = 'apks/probe-prototypeRelease.apk.apksigner.txt'
        }
    }
    $seenVariants = @{}
    $apkFilePaths = @{}
    $apkSha256s = @{}
    foreach ($apk in $apks) {
        $variant = [string]$apk.variant
        if (-not $expectedVariants.ContainsKey($variant) -or $seenVariants.ContainsKey($variant)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APK_VARIANT' -Message ('invalid or duplicate APK variant: ' + $variant)
        }
        $seenVariants[$variant] = $true
        $spec = $expectedVariants[$variant]
        if ($apk.relative_path -cne $spec.rel -or
            $apk.receipt_path -cne $spec.rec -or
            $apk.package_name -cne 'com.aneb.probe' -or
            $apk.version_name -cne '0.2.0' -or
            [int64]$apk.version_code -ne 20 -or
            $apk.source_commit -cne $sourceCommit -or
            $apk.approved_signer_sha256 -cne $approvedSignerCertificateSha256) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APK_METADATA' -Message ('APK metadata mismatch for ' + $variant)
        }
        $apkFull = Join-Path $kitFull ($spec.rel -replace '/', '\')
        $recFull = Join-Path $kitFull ($spec.rec -replace '/', '\')
        $actualBytes = [int64](Get-Item -LiteralPath $apkFull -Force).Length
        $actualSha = Get-AnEbSha256File -Path $apkFull
        if ([int64]$apk.bytes -ne $actualBytes -or $apk.sha256 -cne $actualSha) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APK_HASH' -Message ('APK byte/hash mismatch for ' + $variant)
        }
        Assert-AnEbApkContainer -Path $apkFull
        Assert-AnEbApkSignerReceiptText -ReceiptPath $recFull -ExpectedSignerSha256 $approvedSignerCertificateSha256
        $apkFilePaths[$variant] = $apkFull
        $apkSha256s[$variant] = $actualSha
    }
    if ($apkSha256s['release'] -ceq $apkSha256s['prototypeRelease']) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APK_DUPLICATE' -Message 'release and prototypeRelease APKs must be distinct'
    }

    # 3. Validate prototype_launcher provenance & parity
    $pl = $receipt.prototype_launcher
    $plVersionPath = Join-Path $kitFull 'prototype-launcher\VERSION.json'
    $plVersion = Read-AnEbUtf8Strict -Path $plVersionPath | ConvertFrom-Json
    $plServerFull = Join-Path $kitFull 'prototype-launcher\bin\aneb-server.exe'
    $plApkFull = Join-Path $kitFull 'prototype-launcher\android\aneb-prototype-0.1.apk'
    Assert-AnEbPeExecutable -Path $plServerFull
    $actualServerSha = Get-AnEbSha256File -Path $plServerFull
    $actualBundledApkSha = Get-AnEbSha256File -Path $plApkFull
    $protoReleaseApkSha = Get-AnEbSha256File -Path $apkFilePaths['prototypeRelease']
    if ($actualBundledApkSha -cne $protoReleaseApkSha) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_PROTO_APK_PARITY' -Message 'probe-prototypeRelease.apk differs from prototype-launcher/android/aneb-prototype-0.1.apk'
    }
    if ($pl.directory -cne 'prototype-launcher' -or
        $pl.version_json_path -cne 'prototype-launcher/VERSION.json' -or
        $pl.source_commit -cne $sourceCommit -or
        $pl.server_path -cne 'prototype-launcher/bin/aneb-server.exe' -or
        $pl.server_version -cne 'aneb-server/0.1.0' -or
        $pl.server_sha256 -cne $actualServerSha -or
        $pl.bundled_apk_path -cne 'prototype-launcher/android/aneb-prototype-0.1.apk' -or
        $pl.bundled_apk_sha256 -cne $actualBundledApkSha -or
        $pl.evidence_verifier_path -cne 'prototype-launcher/bin/evidence/aneb-evidence.exe' -or
        $pl.evidence_source_commit -cne $sourceCommit) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_LAUNCHER_RECEIPT' -Message 'KIT_RECEIPT prototype_launcher metadata mismatch'
    }
    if ($plVersion.source_commit -cne $sourceCommit -or
        $plVersion.server_source_commit -cne $sourceCommit -or
        $plVersion.android_source_commit -cne $sourceCommit -or
        $plVersion.evidence_source_commit -cne $sourceCommit -or
        $plVersion.server_version -cne 'aneb-server/0.1.0') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_LAUNCHER_VERSION_PROVENANCE' -Message 'prototype-launcher/VERSION.json provenance mismatch'
    }

    # 4. Validate documents map and feedback template JSON
    $requiredDocs = @(
        'docs/PILOT_V02_QUICKSTART_ZH.md',
        'docs/PILOT_READINESS_README.md',
        'templates/PILOT_V02_FEEDBACK_TEMPLATE.md',
        'templates/PILOT_V02_FEEDBACK_TEMPLATE.json'
    )
    $docProps = @($receipt.documents.PSObject.Properties | ForEach-Object { $_.Name })
    foreach ($reqDoc in $requiredDocs) {
        if (-not ($docProps -ccontains $reqDoc)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_DOC_MISSING' -Message ('KIT_RECEIPT documents missing: ' + $reqDoc)
        }
    }
    foreach ($prop in $receipt.documents.PSObject.Properties) {
        $docRel = [string]$prop.Name
        Assert-AnEbRelativePath -RelativePath $docRel
        $docFull = Join-Path $kitFull ($docRel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $docFull -PathType Leaf)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_DOC_FILE_MISSING' -Message ('missing declared document: ' + $docRel)
        }
        $docEntry = $prop.Value
        $actualDocBytes = [int64](Get-Item -LiteralPath $docFull -Force).Length
        $actualDocSha = Get-AnEbSha256File -Path $docFull
        if ([int64]$docEntry.bytes -ne $actualDocBytes -or $docEntry.sha256 -cne $actualDocSha) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_DOC_HASH' -Message ('document byte/hash mismatch: ' + $docRel)
        }
    }

    $templateJson = Read-AnEbUtf8Strict -Path (Join-Path $kitFull 'templates\PILOT_V02_FEEDBACK_TEMPLATE.json') | ConvertFrom-Json
    if ($templateJson.schema_version -cne 'aneb-pilot-v0.2-feedback-0.1') {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_FEEDBACK_SCHEMA' -Message 'feedback template schema_version mismatch'
    }

    # 5. Enforce zero absolute developer paths across the whole kit
    Assert-AnEbNoAbsoluteDeveloperPath -Root $kitFull

    # 6. Sub-package verification via prototype-launcher/tools/verify-package.ps1
    $subVerifier = Join-Path $kitFull 'prototype-launcher\tools\verify-package.ps1'
    $subArgs = @('-Root', (Join-Path $kitFull 'prototype-launcher'))
    $hasAdmissionArg = (-not [string]::IsNullOrWhiteSpace($PrototypePackageZipPath)) -or
        (-not [string]::IsNullOrWhiteSpace($PrototypeAdmissionReceiptPath)) -or
        (-not [string]::IsNullOrWhiteSpace($ExpectedPrototypeAdmissionReceiptSha256))
    if ($hasAdmissionArg) {
        if ([string]::IsNullOrWhiteSpace($PrototypePackageZipPath) -or
            [string]::IsNullOrWhiteSpace($PrototypeAdmissionReceiptPath) -or
            [string]::IsNullOrWhiteSpace($ExpectedPrototypeAdmissionReceiptSha256)) {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_ADMISSION_ARGS' -Message 'external prototype admission check requires ZipPath, AdmissionReceiptPath, and ExpectedSha256'
        }
        $subArgs += @(
            '-RequireExternalAdmission',
            '-PackageZipPath', (Get-AnEbFullPath -Path $PrototypePackageZipPath),
            '-AdmissionReceiptPath', (Get-AnEbFullPath -Path $PrototypeAdmissionReceiptPath),
            '-ExpectedAdmissionReceiptSha256', $ExpectedPrototypeAdmissionReceiptSha256
        )
    }
    $subOut = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $subVerifier @subArgs 2>&1)
    if ($LASTEXITCODE -ne 0) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_SUBPACKAGE_FAILED' -Message ([string]::Join(' | ', @($subOut | ForEach-Object { $_.ToString() })))
    }

    # 7. Tool-backed deep checks (apksigner + apkanalyzer + go)
    $resolvedSigner = Resolve-AnEbSdkTool -ExplicitPath $ApkSignerPath -ToolKind 'apksigner'
    $resolvedAnalyzer = Resolve-AnEbSdkTool -ExplicitPath $ApkAnalyzerPath -ToolKind 'apkanalyzer'
    $sdkStatus = 'skipped'
    if (-not [string]::IsNullOrWhiteSpace($resolvedSigner) -and -not [string]::IsNullOrWhiteSpace($resolvedAnalyzer)) {
        foreach ($variant in @('release', 'prototypeRelease')) {
            $apkPath = $apkFilePaths[$variant]
            $signerOut = Invoke-AnEbToolVerify -ToolPath $resolvedSigner -Arguments @('verify', '--verbose', '--print-certs', $apkPath) -FailureCode 'PILOT_KIT_APKSIGNER_FAILED'
            if ($signerOut -notmatch '(?m)^Verifies\s*$') {
                Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APKSIGNER_NOT_VERIFIED' -Message ('apksigner output missing Verifies for ' + $variant)
            }
            $sigMatches = [regex]::Matches($signerOut, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$')
            if ($sigMatches.Count -ne 1) {
                Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APKSIGNER_COUNT' -Message ('unexpected signer count for ' + $variant)
            }
            $sigDigest = ($sigMatches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
            if ($sigDigest -cne $approvedSignerCertificateSha256) {
                Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APKSIGNER_CERT' -Message ('unapproved signer for ' + $variant + ': ' + $sigDigest)
            }
            $pkgId = Invoke-AnEbToolVerify -ToolPath $resolvedAnalyzer -Arguments @('manifest', 'application-id', $apkPath) -FailureCode 'PILOT_KIT_APKANALYZER_FAILED'
            $verName = Invoke-AnEbToolVerify -ToolPath $resolvedAnalyzer -Arguments @('manifest', 'version-name', $apkPath) -FailureCode 'PILOT_KIT_APKANALYZER_FAILED'
            $verCode = Invoke-AnEbToolVerify -ToolPath $resolvedAnalyzer -Arguments @('manifest', 'version-code', $apkPath) -FailureCode 'PILOT_KIT_APKANALYZER_FAILED'
            $buildCfg = Invoke-AnEbToolVerify -ToolPath $resolvedAnalyzer -Arguments @('dex', 'code', '--class', 'com.aneb.probe.BuildConfig', $apkPath) -FailureCode 'PILOT_KIT_APKANALYZER_FAILED'
            if ($pkgId -cne 'com.aneb.probe' -or $verName -cne '0.2.0' -or $verCode -cne '20') {
                Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APK_IDENTITY' -Message ('manifest mismatch for ' + $variant)
            }
            $commitPattern = '(?m)\bPROTOTYPE_SOURCE_COMMIT\b[^\r\n]{0,256}\b' + [regex]::Escape($sourceCommit) + '\b'
            if ($buildCfg -cnotmatch $commitPattern) {
                Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APK_COMMIT' -Message ('PROTOTYPE_SOURCE_COMMIT mismatch for ' + $variant)
            }
            $expectedBuildType = if ($variant -ceq 'release') { 'release' } else { 'prototypeRelease' }
            $expectedProtoFlag = if ($variant -ceq 'release') { 'false' } else { 'true' }
            $buildTypePattern = '(?m)^\.field\s+[^\r\n]*\bBUILD_TYPE:Ljava/lang/String;\s*=\s*"' + [regex]::Escape($expectedBuildType) + '"\s*$'
            $protoReleasePattern = '(?m)^\.field\s+[^\r\n]*\bPROTOTYPE_RELEASE:Z\s*=\s*' + $expectedProtoFlag + '\s*$'
            $cleartextPattern = '(?m)^\.field\s+[^\r\n]*\bPROTOTYPE_PRIVATE_CLEARTEXT:Z\s*=\s*' + $expectedProtoFlag + '\s*$'
            if ($buildCfg -cnotmatch $buildTypePattern -or
                $buildCfg -cnotmatch $protoReleasePattern -or
                $buildCfg -cnotmatch $cleartextPattern) {
                Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_APK_VARIANT_BUILDCONFIG' -Message ('BuildConfig variant mismatch for ' + $variant)
            }
        }
        $sdkStatus = 'verified'
    }
    elseif ($RequireSdkTools -or (-not [string]::IsNullOrWhiteSpace($ApkSignerPath)) -or (-not [string]::IsNullOrWhiteSpace($ApkAnalyzerPath))) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_SDK_TOOLS_MISSING' -Message 'apksigner.bat and apkanalyzer.bat are required but not available'
    }
    else {
        Write-Output 'SKIP SDK_TOOLS apksigner/apkanalyzer not found; offline receipt mode'
    }

    $resolvedGo = Resolve-AnEbGoTool -ExplicitPath $GoPath
    $goStatus = 'skipped'
    if (-not [string]::IsNullOrWhiteSpace($resolvedGo)) {
        $goOut = Invoke-AnEbToolVerify -ToolPath $resolvedGo -Arguments @('version', '-m', $plServerFull) -FailureCode 'PILOT_KIT_GO_VERSION_FAILED'
        $revMatches = [regex]::Matches($goOut, '(?m)^\s*build\s+vcs\.revision=([0-9a-f]{40})\s*$')
        $modMatches = [regex]::Matches($goOut, '(?m)^\s*build\s+vcs\.modified=(true|false)\s*$')
        if ($revMatches.Count -ne 1 -or $modMatches.Count -ne 1 -or
            $revMatches[0].Groups[1].Value -cne $sourceCommit -or
            $modMatches[0].Groups[1].Value -cne 'false') {
            Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_GO_VCS_MISMATCH' -Message 'aneb-server.exe vcs.revision/vcs.modified mismatch'
        }
        $goStatus = 'verified'
    }
    elseif ($RequireGoTool) {
        Stop-AnEbPilotKitCheck -Code 'PILOT_KIT_GO_TOOL_MISSING' -Message 'go.exe is required but not available'
    }
    else {
        Write-Output 'SKIP GO_TOOL go.exe not found; PE and SHA-256 verified'
    }

    if (-not [string]::IsNullOrWhiteSpace($JsonOut)) {
        $jsonFull = Get-AnEbFullPath -Path $JsonOut
        Assert-AnEbExistingParents -Path $jsonFull
        $summary = [ordered]@{
            status = 'PASS'
            kit_version = 'pilot-v0.2'
            release_candidate = [string]$receipt.release_candidate
            source_commit = $sourceCommit
            sdk_tools = $sdkStatus
            go_vcs = $goStatus
            verified_files = $actualStatic.Count
        }
        $jsonText = ($summary | ConvertTo-Json -Compress -Depth 6) + [char]10
        [System.IO.File]::WriteAllBytes($jsonFull, (ConvertTo-AnEbUtf8NoBomBytes -Text $jsonText))
    }

    Write-Output ('PASS PILOT_V02_KIT_VERIFIED source_commit=' + $sourceCommit + ' sdk_tools=' + $sdkStatus + ' go_vcs=' + $goStatus)
    exit 0
}
catch {
    Write-Output ('FAIL PILOT_KIT_VERIFICATION ' + $_.Exception.Message)
    exit 1
}
