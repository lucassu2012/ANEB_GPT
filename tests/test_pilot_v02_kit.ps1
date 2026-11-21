[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$packager = Join-Path $repo 'tools\pilot-kit\package-pilot-v0.2-kit.ps1'
$verifier = Join-Path $repo 'tools\pilot-kit\verify-pilot-v0.2-kit.ps1'
$launcherBuilder = Join-Path $repo 'tools\build-release-package.ps1'
$approvedSigner = 'b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003'
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('aneb-pilot-v02-kit-test-' + [Guid]::NewGuid().ToString('N'))
$passed = 0

function Assert-KitTest {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw "ASSERTION_FAILED $Message"
    }
    $script:passed++
    Write-Output ("PASS " + $Message)
}

function Write-KitUtf8 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $enc = New-Object System.Text.UTF8Encoding($false, $true)
    [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

function Write-KitBytes {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][byte[]]$Bytes
    )
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [System.IO.File]::WriteAllBytes($Path, $Bytes)
}

function Get-KitTreeSha256 {
    param([Parameter(Mandatory = $true)][string]$Root)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    [string[]]$rows = @(Get-ChildItem -LiteralPath $rootFull -Recurse -File -Force | ForEach-Object {
        $rel = $_.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
        $rel + '=' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    [Array]::Sort($rows, [System.StringComparer]::Ordinal)
    $bytes = [System.Text.UTF8Encoding]::new($false, $true).GetBytes(([string]::Join([char]10, $rows) + [char]10))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function New-SyntheticApk {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Marker
    )
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $archive.CreateEntry('AndroidManifest.xml', [System.IO.Compression.CompressionLevel]::NoCompression)
        $stream = $entry.Open()
        try {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($Marker)
            $stream.Write($bytes, 0, $bytes.Length)
        }
        finally { $stream.Dispose() }
    }
    finally { $archive.Dispose() }
}

function Copy-RepoFileStrict {
    param(
        [Parameter(Mandatory = $true)][string]$SrcRel,
        [Parameter(Mandatory = $true)][string]$DestRoot
    )
    $src = Join-Path $repo ($SrcRel -replace '/', '\')
    $dst = Join-Path $DestRoot ($SrcRel -replace '/', '\')
    $rawBytes = [System.IO.File]::ReadAllBytes($src)
    $hasBom = ($rawBytes.Length -ge 3 -and $rawBytes[0] -eq 0xEF -and $rawBytes[1] -eq 0xBB -and $rawBytes[2] -eq 0xBF)
    $text = [System.IO.File]::ReadAllText($src).Replace("`r`n", "`n")
    if ($hasBom) {
        $text = [string][char]0xFEFF + $text
    }
    Write-KitUtf8 -Path $dst -Text $text
}

function Invoke-ScriptCapture {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $output = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1)
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = [string]::Join("`n", @($output | ForEach-Object { $_.ToString() }))
    }
}

function Copy-DirectoryClean {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDir,
        [Parameter(Mandatory = $true)][string]$DestDir
    )
    New-Item -ItemType Directory -Path $DestDir -Force | Out-Null
    $srcFull = [System.IO.Path]::GetFullPath($SourceDir).TrimEnd('\')
    foreach ($file in @(Get-ChildItem -LiteralPath $srcFull -Recurse -File -Force)) {
        $rel = $file.FullName.Substring($srcFull.Length + 1)
        $target = Join-Path $DestDir $rel
        $parent = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        [System.IO.File]::Copy($file.FullName, $target, $true)
    }
}

function Update-KitChecksums {
    param([Parameter(Mandatory = $true)][string]$KitDir)
    $full = [System.IO.Path]::GetFullPath($KitDir).TrimEnd('\')
    $files = @(Get-ChildItem -LiteralPath $full -Recurse -File -Force | ForEach-Object {
        $rel = $_.FullName.Substring($full.Length + 1).Replace('\', '/')
        if ($rel -cne 'SHA256SUMS.txt' -and -not $rel.StartsWith('prototype-launcher/results/', [System.StringComparison]::Ordinal)) {
            [pscustomobject]@{ Rel = $rel; Path = $_.FullName }
        }
    } | Sort-Object Rel)
    $lines = @()
    foreach ($f in $files) {
        $h = (Get-FileHash -LiteralPath $f.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        $lines += ($h + '  ' + $f.Rel)
    }
    Write-KitUtf8 -Path (Join-Path $full 'SHA256SUMS.txt') -Text ([string]::Join("`n", $lines) + "`n")
}

try {
    # 0. Static pure ASCII & PowerShell 5.1 parse validation
    foreach ($scriptPath in @($packager, $verifier)) {
        $bytes = [System.IO.File]::ReadAllBytes($scriptPath)
        $nonAscii = @($bytes | Where-Object { $_ -gt 127 }).Count
        Assert-KitTest ($nonAscii -eq 0) ('script is pure ASCII: ' + (Split-Path -Leaf $scriptPath))
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        $tokens = $null; $errors = $null
        $null = [Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$errors)
        Assert-KitTest ($errors.Count -eq 0) ('PowerShell 5.1 AST parses cleanly: ' + (Split-Path -Leaf $scriptPath))
    }

    New-Item -ItemType Directory -Path $tempRoot | Out-Null

    # 1. Build synthetic clean git repo
    $sourceDir = Join-Path $tempRoot 'source'
    New-Item -ItemType Directory -Path $sourceDir | Out-Null
    Write-KitUtf8 -Path (Join-Path $sourceDir '.gitattributes') -Text "* text=auto eol=lf`n"
    foreach ($rel in @(
        'contracts/prototype-0.1/profile-manifest.json',
        'contracts/prototype-0.1/capabilities.schema.json',
        'contracts/prototype-0.1/run-record.schema.json',
        'contracts/prototype-0.1/score-policy.json',
        'START_ANEB.bat',
        'README_FIRST.md',
        'static/report-template.html',
        'tools/common.ps1',
        'tools/doctor.ps1',
        'tools/finalize-campaign.ps1',
        'tools/finalize-verified-campaign.ps1',
        'tools/launch.ps1',
        'tools/make-package-manifest.ps1',
        'tools/verify-evidence.ps1',
        'tools/verify-package.ps1',
        'tools/pilot-kit/package-pilot-v0.2-kit.ps1',
        'tools/pilot-kit/verify-pilot-v0.2-kit.ps1',
        'docs/pilot-readiness/README.md',
        'docs/pilot-readiness/PILOT_V02_QUICKSTART_ZH.md',
        'docs/pilot-readiness/PILOT_V02_FEEDBACK_TEMPLATE.md',
        'docs/pilot-readiness/PILOT_V02_FEEDBACK_TEMPLATE.json'
    )) {
        Copy-RepoFileStrict -SrcRel $rel -DestRoot $sourceDir
    }
    & git.exe -C $sourceDir init -q
    & git.exe -C $sourceDir config user.name 'ANEB Kit Test'
    & git.exe -C $sourceDir config user.email 'aneb-kit-test@example.invalid'
    & git.exe -C $sourceDir config core.autocrlf false
    & git.exe -C $sourceDir add --all
    & git.exe -C $sourceDir commit -q -m 'synthetic pilot v0.2 source'
    Assert-KitTest ($LASTEXITCODE -eq 0) 'synthetic git repo committed cleanly'
    $sourceCommit = (& git.exe -C $sourceDir rev-parse HEAD).Trim()

    # 2. Build synthetic binaries & mock tools
    $artDir = Join-Path $tempRoot 'artifacts'
    New-Item -ItemType Directory -Path $artDir | Out-Null
    $serverBytes = New-Object byte[] 256
    $serverBytes[0] = 0x4d; $serverBytes[1] = 0x5a; $serverBytes[0x3c] = 0x80; $serverBytes[0x80] = 0x50; $serverBytes[0x81] = 0x45
    $serverExe = Join-Path $artDir 'aneb-server.exe'
    Write-KitBytes -Path $serverExe -Bytes $serverBytes

    $releaseApk = Join-Path $artDir 'probe-release.apk'
    $protoApk = Join-Path $artDir 'probe-prototypeRelease.apk'
    New-SyntheticApk -Path $releaseApk -Marker 'synthetic-release-manifest|variant=release'
    New-SyntheticApk -Path $protoApk -Marker 'synthetic-prototype-release-manifest|variant=prototypeRelease'

    $evidenceDir = Join-Path $artDir 'evidence'
    $evidenceExe = Join-Path $evidenceDir 'aneb-evidence.exe'
    New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null
    $evidenceStub = @'
using System;
using System.IO;
public static class AnebKitEvidenceStub {
    public static int Main(string[] args) {
        if (args.Length == 3 && args[0] == "verify-bundle" && args[1] == "--bundle") {
            if (Directory.Exists(args[2]) && File.Exists(Path.Combine(args[2], "valid.marker"))) {
                Console.WriteLine("G0_VERIFY_OK");
                Console.WriteLine("ANEB_EVIDENCE_BUILD_INFO {\"schema_version\":\"aneb-prototype-evidence-build-0.1\",\"source_commit\":\"__SOURCE_COMMIT__\"}");
                return 0;
            }
            Console.Error.WriteLine("EXPECTED_INVALID_BUNDLE_REJECTED");
            return 1;
        }
        return 2;
    }
}
'@
    Add-Type -TypeDefinition ($evidenceStub.Replace('__SOURCE_COMMIT__', $sourceCommit)) -Language CSharp -OutputAssembly $evidenceExe -OutputType ConsoleApplication
    $evidenceFixture = Join-Path $artDir 'evidence-fixture'
    Write-KitUtf8 -Path (Join-Path $evidenceFixture 'valid.marker') -Text "fixture`n"

    $mockSigner = Join-Path $artDir 'apksigner-test.cmd'
    Write-KitUtf8 -Path $mockSigner -Text ("@echo off`r`necho Verifies`r`necho Verified using v3 scheme (APK Signature Scheme v3): true`r`necho Number of signers: 1`r`necho Signer #1 certificate SHA-256 digest: " + $approvedSigner + "`r`nexit /b 0`r`n")
    $mockAnalyzer = Join-Path $artDir 'apkanalyzer-test.cmd'
    $mockAnalyzerText = @"
@echo off
if "%~1"=="manifest" if "%~2"=="application-id" echo com.aneb.probe
if "%~1"=="manifest" if "%~2"=="version-name" echo 0.2.0
if "%~1"=="manifest" if "%~2"=="version-code" echo 20
if "%~1"=="dex" (
  echo # static fields
  echo .field public static final APPLICATION_ID:Ljava/lang/String; = "com.aneb.probe"
  echo .field public static final PROTOTYPE_SOURCE_COMMIT:Ljava/lang/String; = "__SOURCE_COMMIT__"
  findstr /C:"variant=prototypeRelease" "%~5" >nul 2>nul
  if not errorlevel 1 (
    echo .field public static final BUILD_TYPE:Ljava/lang/String; = "prototypeRelease"
    echo .field public static final PROTOTYPE_PRIVATE_CLEARTEXT:Z = true
    echo .field public static final PROTOTYPE_RELEASE:Z = true
  ) else (
    echo .field public static final BUILD_TYPE:Ljava/lang/String; = "release"
    echo .field public static final PROTOTYPE_PRIVATE_CLEARTEXT:Z = false
    echo .field public static final PROTOTYPE_RELEASE:Z = false
  )
)
exit /b 0
"@
    Write-KitUtf8 -Path $mockAnalyzer -Text ($mockAnalyzerText.Replace('__SOURCE_COMMIT__', $sourceCommit).Replace("`r`n", "`n").Replace("`n", "`r`n"))
    $mockGo = Join-Path $artDir 'go-test.cmd'
    Write-KitUtf8 -Path $mockGo -Text ("@echo off`r`necho `%~3: go1.26.5`r`necho `tbuild`tvcs.revision=" + $sourceCommit + "`r`necho `tbuild`tvcs.modified=false`r`nexit /b 0`r`n")

    $buildReceiptPath = Join-Path $tempRoot 'artifact-build-receipt.json'
    $buildReceiptObj = [ordered]@{
        schema_version = 'aneb-prototype-build-artifacts-0.1'
        source_commit = $sourceCommit
        server_sha256 = (Get-FileHash -LiteralPath $serverExe -Algorithm SHA256).Hash.ToLowerInvariant()
        server_version = 'aneb-server/0.1.0'
        android_sha256 = (Get-FileHash -LiteralPath $protoApk -Algorithm SHA256).Hash.ToLowerInvariant()
        android_package_name = 'com.aneb.probe'
        android_version_name = '0.2.0'
        android_version_code = 20
        android_source_commit = $sourceCommit
        evidence_runtime_sha256 = (Get-FileHash -LiteralPath $evidenceExe -Algorithm SHA256).Hash.ToLowerInvariant()
        evidence_runtime_tree_sha256 = (Get-KitTreeSha256 -Root $evidenceDir)
        evidence_runtime_characterization = 'valid_bundle_pass_invalid_bundle_reject'
        evidence_source_commit = $sourceCommit
    }
    Write-KitUtf8 -Path $buildReceiptPath -Text (($buildReceiptObj | ConvertTo-Json -Compress -Depth 6) + "`n")
    $buildReceiptSha = (Get-FileHash -LiteralPath $buildReceiptPath -Algorithm SHA256).Hash.ToLowerInvariant()

    # 3. Build Prototype-0.1 launcher subpackage
    $protoLauncherOut = Join-Path $tempRoot 'proto-launcher-rc1'
    $protoZipOut = Join-Path $tempRoot 'ANEB-Prototype-0.1-rc.1-windows-x64.zip'
    $protoAdmReceipt = Join-Path $tempRoot 'proto-admission-rc1.json'
    $lbRes = Invoke-ScriptCapture -ScriptPath $launcherBuilder -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $serverExe,
        '-AndroidApkPath', $protoApk,
        '-EvidenceDirectory', $evidenceDir,
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo,
        '-EvidenceCharacterizationBundlePath', $evidenceFixture,
        '-ArtifactBuildReceiptPath', $buildReceiptPath,
        '-ExpectedArtifactBuildReceiptSha256', $buildReceiptSha,
        '-OutputDirectory', $protoLauncherOut,
        '-OutputZipPath', $protoZipOut,
        '-AdmissionReceiptPath', $protoAdmReceipt,
        '-ReleaseCandidate', 'rc.1',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20',
        '-Port', '18088'
    )
    Assert-KitTest ($lbRes.ExitCode -eq 0) ('synthetic prototype-launcher built: ' + $lbRes.Output)
    $protoAdmSha = (Get-FileHash -LiteralPath $protoAdmReceipt -Algorithm SHA256).Hash.ToLowerInvariant()

    # 4. Build Pilot v0.2 Kit (positive test)
    $kitOut1 = Join-Path $tempRoot 'kit-rc1'
    $kitZip1 = Join-Path $tempRoot 'ANEB-Pilot-v0.2-rc.1-windows-x64.zip'
    $packRes1 = Invoke-ScriptCapture -ScriptPath $packager -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ReleaseApkPath', $releaseApk,
        '-PrototypeReleaseApkPath', $protoApk,
        '-PrototypeLauncherDirectory', $protoLauncherOut,
        '-PrototypePackageZipPath', $protoZipOut,
        '-PrototypeAdmissionReceiptPath', $protoAdmReceipt,
        '-ExpectedPrototypeAdmissionReceiptSha256', $protoAdmSha,
        '-OutputDirectory', $kitOut1,
        '-OutputZipPath', $kitZip1,
        '-ReleaseCandidate', 'rc.1',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo
    )
    Assert-KitTest ($packRes1.ExitCode -eq 0 -and $packRes1.Output -match 'PASS PILOT_V02_KIT_BUILT') ('package-pilot-v0.2-kit builds clean kit: ' + $packRes1.Output)
    Assert-KitTest ((Test-Path -LiteralPath $kitOut1 -PathType Container) -and (Test-Path -LiteralPath $kitZip1 -PathType Leaf)) 'kit output dir and zip created'

    # 5. Reproducibility test
    $kitOut2 = Join-Path $tempRoot 'kit-rc1-repeat'
    $kitZip2 = Join-Path $tempRoot 'repeat\ANEB-Pilot-v0.2-rc.1-windows-x64.zip'
    $packRes2 = Invoke-ScriptCapture -ScriptPath $packager -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ReleaseApkPath', $releaseApk,
        '-PrototypeReleaseApkPath', $protoApk,
        '-PrototypeLauncherDirectory', $protoLauncherOut,
        '-PrototypePackageZipPath', $protoZipOut,
        '-PrototypeAdmissionReceiptPath', $protoAdmReceipt,
        '-ExpectedPrototypeAdmissionReceiptSha256', $protoAdmSha,
        '-OutputDirectory', $kitOut2,
        '-OutputZipPath', $kitZip2,
        '-ReleaseCandidate', 'rc.1',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo
    )
    Assert-KitTest ($packRes2.ExitCode -eq 0) 'repeat kit build succeeds'
    $zipSha1 = (Get-FileHash -LiteralPath $kitZip1 -Algorithm SHA256).Hash.ToLowerInvariant()
    $zipSha2 = (Get-FileHash -LiteralPath $kitZip2 -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-KitTest ($zipSha1 -ceq $zipSha2) 'same inputs produce byte-identical Pilot v0.2 ZIP'

    # 6. Verify strict mode with -JsonOut
    $jsonOut = Join-Path $tempRoot 'verify-summary.json'
    $vStrict = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @(
        '-ExpectedSourceCommit', $sourceCommit,
        '-RequireSdkTools',
        '-RequireGoTool',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo,
        '-PrototypePackageZipPath', $protoZipOut,
        '-PrototypeAdmissionReceiptPath', $protoAdmReceipt,
        '-ExpectedPrototypeAdmissionReceiptSha256', $protoAdmSha,
        '-JsonOut', $jsonOut
    )
    Assert-KitTest ($vStrict.ExitCode -eq 0 -and $vStrict.Output -match 'PASS PILOT_V02_KIT_VERIFIED.*sdk_tools=verified go_vcs=verified') ('strict kit verification passes: ' + $vStrict.Output)
    $summaryObj = ([System.IO.File]::ReadAllText($jsonOut)) | ConvertFrom-Json
    Assert-KitTest ($summaryObj.status -ceq 'PASS' -and $summaryObj.source_commit -ceq $sourceCommit -and $summaryObj.sdk_tools -ceq 'verified' -and $summaryObj.go_vcs -ceq 'verified') 'JsonOut summary valid'

    # 7. Verify pure offline mode (no ANDROID_HOME and no go.exe on PATH)
    $origAndroidHome = $env:ANDROID_HOME
    $origPath = $env:PATH
    try {
        $env:ANDROID_HOME = ''
        $env:PATH = ($env:SystemRoot + '\System32;' + $env:SystemRoot + '\System32\WindowsPowerShell\v1.0')
        $vOffline = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @('-ExpectedSourceCommit', $sourceCommit)
        Assert-KitTest ($vOffline.ExitCode -eq 0 -and $vOffline.Output -match 'PASS PILOT_V02_KIT_VERIFIED.*sdk_tools=skipped go_vcs=skipped') ('offline verification succeeds without SDK/Go: ' + $vOffline.Output)

        $vMissingSdk = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @('-RequireSdkTools')
        Assert-KitTest ($vMissingSdk.ExitCode -ne 0 -and $vMissingSdk.Output -match 'PILOT_KIT_SDK_TOOLS_MISSING') 'RequireSdkTools fails closed when SDK absent'
    }
    finally {
        $env:ANDROID_HOME = $origAndroidHome
        $env:PATH = $origPath
    }

    # 8. Negative tests on copied kits
    $kitTamper = Join-Path $tempRoot 'kit-tamper'
    Copy-DirectoryClean -SourceDir $kitOut1 -DestDir $kitTamper
    Write-KitUtf8 -Path (Join-Path $kitTamper 'docs\PILOT_V02_QUICKSTART_ZH.md') -Text "tampered`n"
    $vTamper = Invoke-ScriptCapture -ScriptPath (Join-Path $kitTamper 'verify-pilot-v0.2-kit.ps1') -Arguments @('-ApkSignerPath', $mockSigner, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vTamper.ExitCode -ne 0 -and $vTamper.Output -match 'PILOT_KIT_CHECKSUM_MISMATCH') 'rejects tampered doc via SHA256SUMS'

    $kitExtra = Join-Path $tempRoot 'kit-extra'
    Copy-DirectoryClean -SourceDir $kitOut1 -DestDir $kitExtra
    Write-KitUtf8 -Path (Join-Path $kitExtra 'docs\EXTRA_UNTRACKED.md') -Text "extra`n"
    $vExtra = Invoke-ScriptCapture -ScriptPath (Join-Path $kitExtra 'verify-pilot-v0.2-kit.ps1') -Arguments @('-ApkSignerPath', $mockSigner, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vExtra.ExitCode -ne 0 -and $vExtra.Output -match 'PILOT_KIT_CHECKSUM_CLOSURE') 'rejects untracked extra file in kit'

    $kitAbs = Join-Path $tempRoot 'kit-abs-path'
    Copy-DirectoryClean -SourceDir $kitOut1 -DestDir $kitAbs
    $qsPath = Join-Path $kitAbs 'docs\PILOT_V02_QUICKSTART_ZH.md'
    [System.IO.File]::AppendAllText($qsPath, "`nC:\Users\dev\secret\n", [System.Text.UTF8Encoding]::new($false))
    $absBytes = (Get-Item -LiteralPath $qsPath).Length
    $absSha = (Get-FileHash -LiteralPath $qsPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $kr = ([System.IO.File]::ReadAllText((Join-Path $kitAbs 'KIT_RECEIPT.json'))) | ConvertFrom-Json
    $kr.documents.'docs/PILOT_V02_QUICKSTART_ZH.md'.bytes = $absBytes
    $kr.documents.'docs/PILOT_V02_QUICKSTART_ZH.md'.sha256 = $absSha
    Write-KitUtf8 -Path (Join-Path $kitAbs 'KIT_RECEIPT.json') -Text (($kr | ConvertTo-Json -Compress -Depth 10) + "`n")
    Update-KitChecksums -KitDir $kitAbs
    $vAbs = Invoke-ScriptCapture -ScriptPath (Join-Path $kitAbs 'verify-pilot-v0.2-kit.ps1') -Arguments @('-ApkSignerPath', $mockSigner, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vAbs.ExitCode -ne 0 -and $vAbs.Output -match 'ABSOLUTE_DEVELOPER_PATH') 'rejects absolute developer path inside kit'

    $vWrongCommit = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @('-ExpectedSourceCommit', ('0' * 40), '-ApkSignerPath', $mockSigner, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vWrongCommit.ExitCode -ne 0 -and $vWrongCommit.Output -match 'PILOT_KIT_SOURCE_COMMIT_MISMATCH') 'rejects ExpectedSourceCommit mismatch'

    # 9. Bad mock SDK/Go tools
    $badSigner = Join-Path $artDir 'apksigner-bad.cmd'
    Write-KitUtf8 -Path $badSigner -Text ("@echo off`r`necho Verifies`r`necho Number of signers: 1`r`necho Signer #1 certificate SHA-256 digest: " + ('f' * 64) + "`r`nexit /b 0`r`n")
    $vBadSigner = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @('-RequireSdkTools', '-ApkSignerPath', $badSigner, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vBadSigner.ExitCode -ne 0 -and $vBadSigner.Output -match 'PILOT_KIT_APKSIGNER_CERT') 'rejects unapproved APK signer'

    $badAnalyzer = Join-Path $artDir 'apkanalyzer-bad.cmd'
    $badAnalyzerText = @"
@echo off
if "%~1"=="manifest" if "%~2"=="application-id" echo com.aneb.probe
if "%~1"=="manifest" if "%~2"=="version-name" echo 0.2.0
if "%~1"=="manifest" if "%~2"=="version-code" echo 20
if "%~1"=="dex" (
  echo # static fields
  echo .field public static final PROTOTYPE_SOURCE_COMMIT:Ljava/lang/String; = "__BAD_COMMIT__"
  echo .field public static final BUILD_TYPE:Ljava/lang/String; = "release"
  echo .field public static final PROTOTYPE_PRIVATE_CLEARTEXT:Z = false
  echo .field public static final PROTOTYPE_RELEASE:Z = false
)
exit /b 0
"@
    Write-KitUtf8 -Path $badAnalyzer -Text ($badAnalyzerText.Replace('__BAD_COMMIT__', ('1' * 40)).Replace("`r`n", "`n").Replace("`n", "`r`n"))
    $vBadAnalyzer = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @('-RequireSdkTools', '-ApkSignerPath', $mockSigner, '-ApkAnalyzerPath', $badAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vBadAnalyzer.ExitCode -ne 0 -and $vBadAnalyzer.Output -match 'PILOT_KIT_APK_COMMIT') 'rejects APK PROTOTYPE_SOURCE_COMMIT mismatch'

    $badSignerNoVerifies = Join-Path $artDir 'apksigner-no-verifies.cmd'
    Write-KitUtf8 -Path $badSignerNoVerifies -Text ("@echo off`r`necho Number of signers: 1`r`necho Signer #1 certificate SHA-256 digest: " + $approvedSigner + "`r`nexit /b 0`r`n")
    $vNoVerifies = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @('-RequireSdkTools', '-ApkSignerPath', $badSignerNoVerifies, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vNoVerifies.ExitCode -ne 0 -and $vNoVerifies.Output -match 'PILOT_KIT_APKSIGNER_NOT_VERIFIED') 'rejects apksigner output missing Verifies'

    $badGo = Join-Path $artDir 'go-bad.cmd'
    Write-KitUtf8 -Path $badGo -Text ("@echo off`r`necho `%~3: go1.26.5`r`necho `tbuild`tvcs.revision=" + $sourceCommit + "`r`necho `tbuild`tvcs.modified=true`r`nexit /b 0`r`n")
    $vBadGo = Invoke-ScriptCapture -ScriptPath (Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1') -Arguments @('-RequireGoTool', '-ApkSignerPath', $mockSigner, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $badGo)
    Assert-KitTest ($vBadGo.ExitCode -ne 0 -and $vBadGo.Output -match 'PILOT_KIT_GO_VCS_MISMATCH') 'rejects Go vcs.modified=true'

    # 9b. Duplicate and swapped APK variant negative tests + syntax/reparse hardening
    $packDupSamePath = Invoke-ScriptCapture -ScriptPath $packager -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ReleaseApkPath', $protoApk,
        '-PrototypeReleaseApkPath', $protoApk,
        '-PrototypeLauncherDirectory', $protoLauncherOut,
        '-OutputDirectory', (Join-Path $tempRoot 'kit-dup-path'),
        '-OutputZipPath', (Join-Path $tempRoot 'ANEB-Pilot-v0.2-rc.dup1-windows-x64.zip'),
        '-ReleaseCandidate', 'rc.dup1',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo
    )
    Assert-KitTest ($packDupSamePath.ExitCode -ne 0 -and $packDupSamePath.Output -match 'PILOT_KIT_DUPLICATE_APK_PATH') 'packager rejects identical APK path for release and prototypeRelease'

    $protoApkDupCopy = Join-Path $artDir 'probe-release-copy-of-proto.apk'
    [System.IO.File]::Copy($protoApk, $protoApkDupCopy, $true)
    $packDupSameHash = Invoke-ScriptCapture -ScriptPath $packager -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ReleaseApkPath', $protoApkDupCopy,
        '-PrototypeReleaseApkPath', $protoApk,
        '-PrototypeLauncherDirectory', $protoLauncherOut,
        '-OutputDirectory', (Join-Path $tempRoot 'kit-dup-hash'),
        '-OutputZipPath', (Join-Path $tempRoot 'ANEB-Pilot-v0.2-rc.dup2-windows-x64.zip'),
        '-ReleaseCandidate', 'rc.dup2',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo
    )
    Assert-KitTest ($packDupSameHash.ExitCode -ne 0 -and $packDupSameHash.Output -match 'PILOT_KIT_DUPLICATE_APK_HASH') 'packager rejects duplicate APK bytes across distinct paths'

    # Offline mode verifier rejects duplicate APK SHA-256 in KIT_RECEIPT.json
    $kitDupOffline = Join-Path $tempRoot 'kit-dup-offline'
    Copy-DirectoryClean -SourceDir $kitOut1 -DestDir $kitDupOffline
    $dupRelApkPath = Join-Path $kitDupOffline 'apks\probe-release.apk'
    [System.IO.File]::Copy((Join-Path $kitDupOffline 'apks\probe-prototypeRelease.apk'), $dupRelApkPath, $true)
    $krDup = ([System.IO.File]::ReadAllText((Join-Path $kitDupOffline 'KIT_RECEIPT.json'))) | ConvertFrom-Json
    $krDup.apks[0].bytes = [int64](Get-Item -LiteralPath $dupRelApkPath).Length
    $krDup.apks[0].sha256 = (Get-FileHash -LiteralPath $dupRelApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-KitUtf8 -Path (Join-Path $kitDupOffline 'KIT_RECEIPT.json') -Text (($krDup | ConvertTo-Json -Compress -Depth 10) + "`n")
    Update-KitChecksums -KitDir $kitDupOffline
    $origAndroidHome2 = $env:ANDROID_HOME
    $origPath2 = $env:PATH
    try {
        $env:ANDROID_HOME = ''
        $env:PATH = ($env:SystemRoot + '\System32;' + $env:SystemRoot + '\System32\WindowsPowerShell\v1.0')
        $vDupOffline = Invoke-ScriptCapture -ScriptPath (Join-Path $kitDupOffline 'verify-pilot-v0.2-kit.ps1') -Arguments @('-ExpectedSourceCommit', $sourceCommit)
        Assert-KitTest ($vDupOffline.ExitCode -ne 0 -and $vDupOffline.Output -match 'PILOT_KIT_APK_DUPLICATE') 'verifier rejects duplicate APK SHA-256 in offline mode'
    }
    finally {
        $env:ANDROID_HOME = $origAndroidHome2
        $env:PATH = $origPath2
    }

    # Swapped BuildConfig variants rejected by verifier under SDK mode (and by packager)
    $buildReceiptSwappedPath = Join-Path $tempRoot 'artifact-build-receipt-swapped.json'
    $buildReceiptSwapped = [ordered]@{
        schema_version = 'aneb-prototype-build-artifacts-0.1'
        source_commit = $sourceCommit
        server_sha256 = (Get-FileHash -LiteralPath $serverExe -Algorithm SHA256).Hash.ToLowerInvariant()
        server_version = 'aneb-server/0.1.0'
        android_sha256 = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
        android_package_name = 'com.aneb.probe'
        android_version_name = '0.2.0'
        android_version_code = 20
        android_source_commit = $sourceCommit
        evidence_runtime_sha256 = (Get-FileHash -LiteralPath $evidenceExe -Algorithm SHA256).Hash.ToLowerInvariant()
        evidence_runtime_tree_sha256 = (Get-KitTreeSha256 -Root $evidenceDir)
        evidence_runtime_characterization = 'valid_bundle_pass_invalid_bundle_reject'
        evidence_source_commit = $sourceCommit
    }
    Write-KitUtf8 -Path $buildReceiptSwappedPath -Text (($buildReceiptSwapped | ConvertTo-Json -Compress -Depth 6) + "`n")
    $buildReceiptSwappedSha = (Get-FileHash -LiteralPath $buildReceiptSwappedPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $protoLauncherSwappedOut = Join-Path $tempRoot 'proto-launcher-swapped'
    $protoZipSwappedOut = Join-Path $tempRoot 'ANEB-Prototype-0.1-rc.swap-windows-x64.zip'
    $protoAdmSwappedReceipt = Join-Path $tempRoot 'proto-admission-swapped.json'
    $lbSwapRes = Invoke-ScriptCapture -ScriptPath $launcherBuilder -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $serverExe,
        '-AndroidApkPath', $releaseApk,
        '-EvidenceDirectory', $evidenceDir,
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo,
        '-EvidenceCharacterizationBundlePath', $evidenceFixture,
        '-ArtifactBuildReceiptPath', $buildReceiptSwappedPath,
        '-ExpectedArtifactBuildReceiptSha256', $buildReceiptSwappedSha,
        '-OutputDirectory', $protoLauncherSwappedOut,
        '-OutputZipPath', $protoZipSwappedOut,
        '-AdmissionReceiptPath', $protoAdmSwappedReceipt,
        '-ReleaseCandidate', 'rc.swap',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20',
        '-Port', '18089'
    )
    Assert-KitTest ($lbSwapRes.ExitCode -eq 0) ('swapped-apk prototype-launcher fixture built: ' + $lbSwapRes.Output)

    $packSwapped = Invoke-ScriptCapture -ScriptPath $packager -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ReleaseApkPath', $protoApk,
        '-PrototypeReleaseApkPath', $releaseApk,
        '-PrototypeLauncherDirectory', $protoLauncherSwappedOut,
        '-OutputDirectory', (Join-Path $tempRoot 'kit-pack-swapped'),
        '-OutputZipPath', (Join-Path $tempRoot 'ANEB-Pilot-v0.2-rc.swap-windows-x64.zip'),
        '-ReleaseCandidate', 'rc.swap',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo
    )
    Assert-KitTest ($packSwapped.ExitCode -ne 0 -and $packSwapped.Output -match 'APK_VARIANT_MISMATCH') 'packager rejects swapped release and prototypeRelease APKs'

    $kitSwapped = Join-Path $tempRoot 'kit-swapped'
    Copy-DirectoryClean -SourceDir $kitOut1 -DestDir $kitSwapped
    [System.IO.File]::Copy($protoApk, (Join-Path $kitSwapped 'apks\probe-release.apk'), $true)
    [System.IO.File]::Copy($releaseApk, (Join-Path $kitSwapped 'apks\probe-prototypeRelease.apk'), $true)
    Remove-Item -LiteralPath (Join-Path $kitSwapped 'prototype-launcher') -Recurse -Force
    Copy-DirectoryClean -SourceDir $protoLauncherSwappedOut -DestDir (Join-Path $kitSwapped 'prototype-launcher')
    $krSwap = ([System.IO.File]::ReadAllText((Join-Path $kitSwapped 'KIT_RECEIPT.json'))) | ConvertFrom-Json
    $krSwap.apks[0].bytes = [int64](Get-Item -LiteralPath (Join-Path $kitSwapped 'apks\probe-release.apk')).Length
    $krSwap.apks[0].sha256 = (Get-FileHash -LiteralPath (Join-Path $kitSwapped 'apks\probe-release.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
    $krSwap.apks[1].bytes = [int64](Get-Item -LiteralPath (Join-Path $kitSwapped 'apks\probe-prototypeRelease.apk')).Length
    $krSwap.apks[1].sha256 = (Get-FileHash -LiteralPath (Join-Path $kitSwapped 'apks\probe-prototypeRelease.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
    $krSwap.prototype_launcher.bundled_apk_sha256 = $krSwap.apks[1].sha256
    Write-KitUtf8 -Path (Join-Path $kitSwapped 'KIT_RECEIPT.json') -Text (($krSwap | ConvertTo-Json -Compress -Depth 10) + "`n")
    Update-KitChecksums -KitDir $kitSwapped
    $vSwapped = Invoke-ScriptCapture -ScriptPath (Join-Path $kitSwapped 'verify-pilot-v0.2-kit.ps1') -Arguments @(
        '-ExpectedSourceCommit', $sourceCommit,
        '-RequireSdkTools',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo
    )
    Assert-KitTest ($vSwapped.ExitCode -ne 0 -and $vSwapped.Output -match 'PILOT_KIT_APK_VARIANT_BUILDCONFIG') 'verifier rejects swapped BuildConfig variants under SDK mode'

    # Uppercase hex in SHA256SUMS.txt fails with PILOT_KIT_CHECKSUM_SYNTAX
    $kitUpper = Join-Path $tempRoot 'kit-upper-hex'
    Copy-DirectoryClean -SourceDir $kitOut1 -DestDir $kitUpper
    $sumsText = [System.IO.File]::ReadAllText((Join-Path $kitUpper 'SHA256SUMS.txt'))
    $sumsLines = @($sumsText -split "`n" | Where-Object { $_ -ne '' })
    $sumsLines[0] = $sumsLines[0].Substring(0, 64).ToUpperInvariant() + $sumsLines[0].Substring(64)
    Write-KitUtf8 -Path (Join-Path $kitUpper 'SHA256SUMS.txt') -Text ([string]::Join("`n", $sumsLines) + "`n")
    $vUpper = Invoke-ScriptCapture -ScriptPath (Join-Path $kitUpper 'verify-pilot-v0.2-kit.ps1') -Arguments @('-ApkSignerPath', $mockSigner, '-ApkAnalyzerPath', $mockAnalyzer, '-GoPath', $mockGo)
    Assert-KitTest ($vUpper.ExitCode -ne 0 -and $vUpper.Output -match 'PILOT_KIT_CHECKSUM_SYNTAX') 'verifier rejects uppercase hex in SHA256SUMS with PILOT_KIT_CHECKSUM_SYNTAX'

    # Resolve-AnEbCommonScript rejects reparse ancestor before dot-sourcing common.ps1
    $kitReparseCommon = Join-Path $tempRoot 'kit-reparse-common'
    $reparseTarget = Join-Path $tempRoot 'reparse-tools-target'
    Copy-DirectoryClean -SourceDir (Join-Path $kitOut1 'prototype-launcher\tools') -DestDir $reparseTarget
    New-Item -ItemType Directory -Path (Join-Path $kitReparseCommon 'prototype-launcher') -Force | Out-Null
    [System.IO.File]::Copy((Join-Path $kitOut1 'verify-pilot-v0.2-kit.ps1'), (Join-Path $kitReparseCommon 'verify-pilot-v0.2-kit.ps1'), $true)
    $toolsDir = Join-Path $kitReparseCommon 'prototype-launcher\tools'
    $null = & cmd.exe /c "mklink /J `"$toolsDir`" `"$reparseTarget`"" 2>&1
    try {
        $vReparse = Invoke-ScriptCapture -ScriptPath (Join-Path $kitReparseCommon 'verify-pilot-v0.2-kit.ps1') -Arguments @('-KitRoot', $kitReparseCommon)
        Assert-KitTest ($vReparse.ExitCode -ne 0 -and $vReparse.Output -match 'PILOT_KIT_REPARSE') 'Resolve-AnEbCommonScript rejects reparse ancestor before dot-sourcing'
    }
    finally {
        if (Test-Path -LiteralPath $toolsDir) {
            [System.IO.Directory]::Delete($toolsDir)
        }
    }

    # 10. Dirty worktree rejected by packager
    Write-KitUtf8 -Path (Join-Path $sourceDir 'DIRTY.txt') -Text "dirty`n"
    $packDirty = Invoke-ScriptCapture -ScriptPath $packager -Arguments @(
        '-SourceRoot', $sourceDir,
        '-SourceCommit', $sourceCommit,
        '-ReleaseApkPath', $releaseApk,
        '-PrototypeReleaseApkPath', $protoApk,
        '-PrototypeLauncherDirectory', $protoLauncherOut,
        '-OutputDirectory', (Join-Path $tempRoot 'kit-dirty'),
        '-OutputZipPath', (Join-Path $tempRoot 'ANEB-Pilot-v0.2-rc.dirty-windows-x64.zip'),
        '-ReleaseCandidate', 'rc.dirty',
        '-BuiltAtUtc', '2026-11-21T00:00:00Z',
        '-ApkSignerPath', $mockSigner,
        '-ApkAnalyzerPath', $mockAnalyzer,
        '-GoPath', $mockGo
    )
    Assert-KitTest ($packDirty.ExitCode -ne 0 -and $packDirty.Output -match 'SOURCE_WORKTREE_NOT_CLEAN') 'packager rejects dirty git worktree'

    Write-Output ('ALL_PASS passed=' + $passed)
    exit 0
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
