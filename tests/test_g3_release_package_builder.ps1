[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$builder = Join-Path $repo 'tools\build-release-package.ps1'
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('aneb-g3-builder-test-' + [Guid]::NewGuid().ToString('N'))
$approvedSigner = 'b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003'
$passed = 0

function Assert-AnEbBuilderTest {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw "ASSERTION_FAILED $Message"
    }
    $script:passed++
}

function Write-AnEbBuilderUtf8 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    [System.IO.File]::WriteAllText($Path, $Text, $encoding)
}

function Write-AnEbBuilderBytes {
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

function Invoke-AnEbBuilderTool {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    if (-not ($Arguments -ccontains '-OutputZipPath')) {
        $candidateIndex = [Array]::IndexOf($Arguments, '-ReleaseCandidate')
        if ($candidateIndex -lt 0 -or $candidateIndex + 1 -ge $Arguments.Count) {
            throw 'test invocation is missing ReleaseCandidate'
        }
        $candidate = $Arguments[$candidateIndex + 1]
        $autoZip = Join-Path $tempRoot ('auto-zips\' + $candidate + '\ANEB-Prototype-0.1-' + $candidate + '-windows-x64.zip')
        $Arguments += @('-OutputZipPath', $autoZip)
    }
    if (-not ($Arguments -ccontains '-ApkAnalyzerPath') -and -not [string]::IsNullOrWhiteSpace($script:defaultApkAnalyzer)) {
        $Arguments += @('-ApkAnalyzerPath', $script:defaultApkAnalyzer)
    }
    if (-not ($Arguments -ccontains '-GoPath') -and -not [string]::IsNullOrWhiteSpace($script:defaultGoTool)) {
        $Arguments += @('-GoPath', $script:defaultGoTool)
    }
    if (-not ($Arguments -ccontains '-EvidenceCharacterizationBundlePath') -and -not [string]::IsNullOrWhiteSpace($script:defaultEvidenceFixture)) {
        $Arguments += @('-EvidenceCharacterizationBundlePath', $script:defaultEvidenceFixture)
    }
    if (-not ($Arguments -ccontains '-ArtifactBuildReceiptPath') -and -not [string]::IsNullOrWhiteSpace($script:defaultBuildReceipt)) {
        $Arguments += @('-ArtifactBuildReceiptPath', $script:defaultBuildReceipt, '-ExpectedArtifactBuildReceiptSha256', $script:defaultBuildReceiptSha256)
    }
    $output = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $builder @Arguments 2>&1)
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = [string]::Join("`n", @($output | ForEach-Object { $_.ToString() }))
    }
}

function Get-AnEbBuilderPackageFingerprint {
    param([Parameter(Mandatory = $true)][string]$Root)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    $rows = @(Get-ChildItem -LiteralPath $rootFull -Recurse -File -Force |
        ForEach-Object {
            $relative = $_.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            $relative + '=' + $hash
        } | Sort-Object)
    return [string]::Join("`n", $rows)
}

function Get-AnEbBuilderTreeSha256 {
    param([Parameter(Mandatory = $true)][string]$Root)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    [string[]]$rows = @(Get-ChildItem -LiteralPath $rootFull -Recurse -File -Force | ForEach-Object {
        $relative = $_.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
        $relative + '=' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    [Array]::Sort($rows, [System.StringComparer]::Ordinal)
    $bytes = [System.Text.UTF8Encoding]::new($false, $true).GetBytes(([string]::Join([char]10, $rows) + [char]10))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function New-AnEbSyntheticSource {
    param([Parameter(Mandatory = $true)][string]$Path)
    New-Item -ItemType Directory -Path $Path | Out-Null
    Write-AnEbBuilderUtf8 -Path (Join-Path $Path '.gitattributes') -Text "* text=auto eol=lf`n"

    foreach ($name in @('profile-manifest.json', 'capabilities.schema.json', 'run-record.schema.json', 'score-policy.json')) {
        $sourcePath = Join-Path $repo ('contracts\prototype-0.1\' + $name)
        $text = [System.IO.File]::ReadAllText($sourcePath).Replace("`r`n", "`n")
        Write-AnEbBuilderUtf8 -Path (Join-Path $Path ('contracts\prototype-0.1\' + $name)) -Text $text
    }

    Write-AnEbBuilderUtf8 -Path (Join-Path $Path 'START_ANEB.bat') -Text "@echo off`npowershell.exe -NoProfile -File `"%~dp0tools\launch.ps1`"`n"
    Write-AnEbBuilderUtf8 -Path (Join-Path $Path 'README_FIRST.md') -Text "# ANEB Prototype 0.1`n`nInstall the bundled APK, start the launcher, and use the displayed LAN URL.`n"
    Write-AnEbBuilderUtf8 -Path (Join-Path $Path 'static\report-template.html') -Text "<!doctype html><html><body>offline report</body></html>`n"
    foreach ($name in @(
        'common.ps1',
        'doctor.ps1',
        'finalize-campaign.ps1',
        'finalize-verified-campaign.ps1',
        'launch.ps1',
        'make-package-manifest.ps1',
        'verify-evidence.ps1',
        'verify-package.ps1'
    )) {
        $toolText = [System.IO.File]::ReadAllText((Join-Path $repo ('tools\' + $name))).Replace("`r`n", "`n")
        Write-AnEbBuilderUtf8 -Path (Join-Path $Path ('tools\' + $name)) -Text $toolText
    }
    Write-AnEbBuilderUtf8 -Path (Join-Path $Path 'DO_NOT_COPY.txt') -Text "TEST_SECRET_SENTINEL must not enter the package`n"

    & git.exe -C $Path init -q
    & git.exe -C $Path config user.name 'ANEB Builder Test'
    & git.exe -C $Path config user.email 'aneb-builder-test@example.invalid'
    & git.exe -C $Path config core.autocrlf false
    & git.exe -C $Path add --all
    & git.exe -C $Path commit -q -m 'synthetic clean source'
    if ($LASTEXITCODE -ne 0) { throw 'failed to create synthetic source commit' }
    return ((& git.exe -C $Path rev-parse HEAD).Trim())
}

function New-AnEbSyntheticArtifacts {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$SourceCommit
    )
    New-Item -ItemType Directory -Path $Path | Out-Null
    $serverBytes = New-Object byte[] 256
    $serverBytes[0] = 0x4d
    $serverBytes[1] = 0x5a
    $serverBytes[0x3c] = 0x80
    $serverBytes[0x80] = 0x50
    $serverBytes[0x81] = 0x45
    Write-AnEbBuilderBytes -Path (Join-Path $Path 'aneb-server.exe') -Bytes $serverBytes

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apkPath = Join-Path $Path 'probe-prototypeRelease.apk'
    $archive = [System.IO.Compression.ZipFile]::Open($apkPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $archive.CreateEntry('AndroidManifest.xml')
        $stream = $entry.Open()
        try {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes('synthetic-manifest')
            $stream.Write($bytes, 0, $bytes.Length)
        }
        finally { $stream.Dispose() }
    }
    finally { $archive.Dispose() }

    $evidenceExecutable = Join-Path $Path 'evidence\aneb-evidence.exe'
    New-Item -ItemType Directory -Path (Split-Path -Parent $evidenceExecutable) -Force | Out-Null
    $evidenceStub = @'
using System;
using System.IO;
public static class AnebEvidenceCharacterizationStub {
    public static int Main(string[] args) {
        if (args.Length == 2 && args[0] == "build-info" && args[1] == "--json") {
            Console.WriteLine("{\"schema_version\":\"aneb-prototype-evidence-build-0.1\",\"source_commit\":\"__SOURCE_COMMIT__\"}");
            return 0;
        }
        if (args.Length == 3 && args[0] == "verify-bundle" && args[1] == "--bundle") {
            if (Directory.Exists(args[2]) && File.Exists(Path.Combine(args[2], "valid.marker"))) {
                Console.WriteLine("G0_VERIFY_OK");
                Console.WriteLine("ANEB_EVIDENCE_BUILD_INFO {\"schema_version\":\"aneb-prototype-evidence-build-0.1\",\"source_commit\":\"__SOURCE_COMMIT__\"}");
                return 0;
            }
            return 1;
        }
        return 2;
    }
}
'@
    $evidenceStub = $evidenceStub.Replace('__SOURCE_COMMIT__', $SourceCommit)
    Add-Type -TypeDefinition $evidenceStub -Language CSharp -OutputAssembly $evidenceExecutable -OutputType ConsoleApplication
    Write-AnEbBuilderUtf8 -Path (Join-Path $Path 'evidence\_internal\runtime.dat') -Text "runtime`n"
    New-Item -ItemType Directory -Path (Join-Path $Path 'evidence\_internal\jsonschema.dist-info') -Force | Out-Null
    [System.IO.File]::WriteAllBytes(
        (Join-Path $Path 'evidence\_internal\jsonschema.dist-info\REQUESTED'),
        [byte[]]::new(0)
    )
    $evidenceFixture = Join-Path $Path 'evidence-characterization-bundle'
    Write-AnEbBuilderUtf8 -Path (Join-Path $evidenceFixture 'valid.marker') -Text "canonical characterization fixture`n"
    $apksigner = Join-Path $Path 'apksigner-test.cmd'
    Write-AnEbBuilderUtf8 -Path $apksigner -Text ("@echo off`r`necho Verifies`r`necho Signer #1 certificate SHA-256 digest: " + $approvedSigner + "`r`nexit /b 0`r`n")
    $apkanalyzer = Join-Path $Path 'apkanalyzer-test.cmd'
    Write-AnEbBuilderUtf8 -Path $apkanalyzer -Text ("@echo off`r`nif `%1==manifest if `%2==application-id echo com.aneb.probe`r`nif `%1==manifest if `%2==version-name echo 0.2.0`r`nif `%1==manifest if `%2==version-code echo 20`r`nif `%1==dex echo PROTOTYPE_SOURCE_COMMIT " + $SourceCommit + "`r`nexit /b 0`r`n")
    $goTool = Join-Path $Path 'go-test.cmd'
    Write-AnEbBuilderUtf8 -Path $goTool -Text ("@echo off`r`necho `%~3: go1.25.0`r`necho build vcs.revision=" + $SourceCommit + "`r`necho build vcs.modified=false`r`nexit /b 0`r`n")
    return [pscustomobject]@{
        Server = Join-Path $Path 'aneb-server.exe'
        Apk = $apkPath
        Evidence = Join-Path $Path 'evidence'
        EvidenceFixture = $evidenceFixture
        ApkSigner = $apksigner
        ApkAnalyzer = $apkanalyzer
        GoTool = $goTool
    }
}

try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $source = Join-Path $tempRoot 'source'
    $sourceCommit = New-AnEbSyntheticSource -Path $source
    $artifacts = New-AnEbSyntheticArtifacts -Path (Join-Path $tempRoot 'artifacts') -SourceCommit $sourceCommit
    $script:defaultApkAnalyzer = $artifacts.ApkAnalyzer
    $script:defaultGoTool = $artifacts.GoTool
    $script:defaultEvidenceFixture = $artifacts.EvidenceFixture
    $artifactBuildReceipt = Join-Path $tempRoot 'artifact-build-receipt.json'
    $artifactBuildReceiptValue = [ordered]@{
        schema_version = 'aneb-prototype-build-artifacts-0.1'
        source_commit = $sourceCommit
        server_sha256 = (Get-FileHash -LiteralPath $artifacts.Server -Algorithm SHA256).Hash.ToLowerInvariant()
        server_version = 'aneb-server/0.1.0'
        android_sha256 = (Get-FileHash -LiteralPath $artifacts.Apk -Algorithm SHA256).Hash.ToLowerInvariant()
        android_package_name = 'com.aneb.probe'
        android_version_name = '0.2.0'
        android_version_code = 20
        android_source_commit = $sourceCommit
        evidence_source_commit = $sourceCommit
        evidence_runtime_sha256 = (Get-FileHash -LiteralPath (Join-Path $artifacts.Evidence 'aneb-evidence.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
        evidence_runtime_tree_sha256 = (Get-AnEbBuilderTreeSha256 -Root $artifacts.Evidence)
        evidence_runtime_characterization = 'valid_bundle_pass_invalid_bundle_reject'
    }
    Write-AnEbBuilderUtf8 -Path $artifactBuildReceipt -Text (($artifactBuildReceiptValue | ConvertTo-Json -Compress -Depth 8) + "`n")
    $artifactBuildReceiptSha256 = (Get-FileHash -LiteralPath $artifactBuildReceipt -Algorithm SHA256).Hash.ToLowerInvariant()
    $script:defaultBuildReceipt = $artifactBuildReceipt
    $script:defaultBuildReceiptSha256 = $artifactBuildReceiptSha256
    $output = Join-Path $tempRoot 'package-rc1'
    $outputZip = Join-Path $tempRoot 'ANEB-Prototype-0.1-rc.1-windows-x64.zip'
    $receipt = Join-Path $tempRoot 'receipts\artifact-admission-rc1.json'

    $result = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-ApkAnalyzerPath', $artifacts.ApkAnalyzer,
        '-GoPath', $artifacts.GoTool,
        '-EvidenceCharacterizationBundlePath', $artifacts.EvidenceFixture,
        '-ArtifactBuildReceiptPath', $artifactBuildReceipt,
        '-ExpectedArtifactBuildReceiptSha256', $artifactBuildReceiptSha256,
        '-OutputDirectory', $output,
        '-OutputZipPath', $outputZip,
        '-AdmissionReceiptPath', $receipt,
        '-ReleaseCandidate', 'rc.1',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20',
        '-Port', '18088'
    )
    Assert-AnEbBuilderTest -Condition ($result.ExitCode -eq 0) -Message ('valid fixed artifacts build a release package output=' + $result.Output)
    Assert-AnEbBuilderTest -Condition ($result.Output -match 'PASS RELEASE_PACKAGE_BUILT') -Message 'builder emits one explicit success marker'
    Assert-AnEbBuilderTest -Condition (Test-Path -LiteralPath $output -PathType Container) -Message 'builder publishes the requested fresh package directory'
    Assert-AnEbBuilderTest -Condition (Test-Path -LiteralPath $outputZip -PathType Leaf) -Message 'builder publishes the immutable spec-named ZIP'
    Assert-AnEbBuilderTest -Condition (Test-Path -LiteralPath $receipt -PathType Leaf) -Message 'builder publishes the admission receipt outside the package'
    $zipSha256 = (Get-FileHash -LiteralPath $outputZip -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-AnEbBuilderTest -Condition ($result.Output -match ('zip_sha256=' + $zipSha256)) -Message 'builder emits the exact immutable ZIP SHA-256'

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($outputZip)
    try {
        $zipNames = @($zip.Entries | ForEach-Object { $_.FullName })
        Assert-AnEbBuilderTest -Condition ($zipNames.Count -gt 1 -and @($zipNames | Where-Object { -not $_.StartsWith('ANEB-Prototype-0.1/', [System.StringComparison]::Ordinal) }).Count -eq 0) -Message 'ZIP has one exact ANEB-Prototype-0.1 top-level directory'
        [string[]]$sortedZipNames = @($zipNames)
        [Array]::Sort($sortedZipNames, [System.StringComparer]::Ordinal)
        Assert-AnEbBuilderTest -Condition ([string]::Join("`n", $zipNames) -ceq [string]::Join("`n", $sortedZipNames)) -Message 'ZIP entries are globally sorted by ordinal exact path'
        Assert-AnEbBuilderTest -Condition ($zipNames -contains 'ANEB-Prototype-0.1/results/') -Message 'ZIP carries the required empty mutable results directory'
        Assert-AnEbBuilderTest -Condition (@($zip.Entries | Where-Object { $_.LastWriteTime.DateTime -ne [datetime]'2000-01-01T00:00:00' }).Count -eq 0) -Message 'ZIP entry timestamps are deterministic'
    }
    finally {
        $zip.Dispose()
    }

    $version = [System.IO.File]::ReadAllText((Join-Path $output 'VERSION.json')) | ConvertFrom-Json
    Assert-AnEbBuilderTest -Condition ($version.release_state -ceq 'RELEASE_CANDIDATE' -and $version.artifact_admission -ceq 'REAL_ARTIFACTS_BOUND') -Message 'VERSION marks real artifacts as bound'
    Assert-AnEbBuilderTest -Condition ($version.source_commit -ceq $sourceCommit) -Message 'VERSION binds the exact clean source commit'
    Assert-AnEbBuilderTest -Condition ($version.evidence_source_commit -ceq $sourceCommit) -Message 'VERSION binds the source commit extracted from the evidence runtime'
    Assert-AnEbBuilderTest -Condition ($version.server_artifact -ceq 'bin/aneb-server.exe' -and $version.android_artifact -ceq 'android/aneb-prototype-0.1.apk' -and $version.evidence_verifier_artifact -ceq 'bin/evidence/aneb-evidence.exe') -Message 'VERSION binds exact packaged artifact paths'
    Assert-AnEbBuilderTest -Condition ($version.server_args.Count -eq 9 -and $version.server_args[0] -ceq '-addr' -and $version.server_args[1] -ceq ':18088') -Message 'VERSION binds the deterministic Prototype-only launcher arguments'

    $admission = [System.IO.File]::ReadAllText($receipt) | ConvertFrom-Json
    Assert-AnEbBuilderTest -Condition ($admission.android_signer_cert_sha256 -ceq $approvedSigner) -Message 'external receipt binds the independently verified approved signer'
    Assert-AnEbBuilderTest -Condition ($admission.source_commit -ceq $sourceCommit) -Message 'external receipt binds the exact source commit'
    Assert-AnEbBuilderTest -Condition ($admission.evidence_source_commit -ceq $sourceCommit) -Message 'external receipt binds evidence runtime source provenance'
    Assert-AnEbBuilderTest -Condition ($admission.package_zip_name -ceq 'ANEB-Prototype-0.1-rc.1-windows-x64.zip' -and $admission.package_zip_sha256 -ceq $zipSha256) -Message 'external receipt binds the exact spec-named immutable ZIP'
    Assert-AnEbBuilderTest -Condition ($admission.artifact_build_receipt_sha256 -ceq $artifactBuildReceiptSha256) -Message 'external receipt carries the independently pinned artifact-build provenance'
    Assert-AnEbBuilderTest -Condition ((Get-FileHash -LiteralPath (Join-Path $output 'bin\aneb-server.exe') -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $admission.server_sha256) -Message 'receipt binds the copied server bytes'
    Assert-AnEbBuilderTest -Condition ((Get-FileHash -LiteralPath (Join-Path $output 'android\aneb-prototype-0.1.apk') -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $admission.android_sha256) -Message 'receipt binds the copied APK bytes'

    foreach ($relative in @(
        'START_ANEB.bat', 'README_FIRST.md', 'VERSION.json', 'SHA256SUMS.txt',
        'contracts/profile-manifest.json', 'contracts/capabilities.schema.json',
        'contracts/run-record.schema.json', 'contracts/score-policy.json',
        'bin/aneb-server.exe', 'bin/evidence/aneb-evidence.exe',
        'bin/evidence/_internal/runtime.dat', 'android/aneb-prototype-0.1.apk',
        'static/report-template.html', 'tools/launch.ps1', 'tools/verify-package.ps1'
    )) {
        Assert-AnEbBuilderTest -Condition (Test-Path -LiteralPath (Join-Path $output ($relative -replace '/', '\')) -PathType Leaf) -Message ('package contains ' + $relative)
    }
    $packagedRequested = Join-Path $output 'bin\evidence\_internal\jsonschema.dist-info\REQUESTED'
    Assert-AnEbBuilderTest -Condition (
        (Test-Path -LiteralPath $packagedRequested -PathType Leaf) -and
        (Get-Item -LiteralPath $packagedRequested -Force).Length -eq 0
    ) -Message 'package preserves a legitimate zero-byte evidence runtime metadata file'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath (Join-Path $output 'DO_NOT_COPY.txt'))) -Message 'fixed allowlist excludes unrelated source files and secret sentinel'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath (Join-Path $output 'contracts\prototype-0.1'))) -Message 'release package flattens only the frozen four contracts'
    $packageText = [string]::Join("`n", @(
        Get-ChildItem -LiteralPath $output -Recurse -File -Force |
            Where-Object { $_.Extension -in @('.md','.json','.txt','.ps1','.bat','.html') } |
            ForEach-Object { [System.IO.File]::ReadAllText($_.FullName) }
    ))
    Assert-AnEbBuilderTest -Condition ($packageText -notmatch [regex]::Escape($source) -and $packageText -notmatch 'TEST_SECRET_SENTINEL') -Message 'package contains no absolute source path or unrelated secret sentinel'

    $secondOutput = Join-Path $tempRoot 'package-rc1-repeat'
    $secondZip = Join-Path $tempRoot 'repeat\ANEB-Prototype-0.1-rc.1-windows-x64.zip'
    $secondReceipt = Join-Path $tempRoot 'receipts\artifact-admission-rc1-repeat.json'
    $secondResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $secondOutput,
        '-OutputZipPath', $secondZip,
        '-AdmissionReceiptPath', $secondReceipt,
        '-ReleaseCandidate', 'rc.1',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20',
        '-Port', '18088'
    )
    Assert-AnEbBuilderTest -Condition ($secondResult.ExitCode -eq 0) -Message ('same fixed inputs build a second package output=' + $secondResult.Output)
    Assert-AnEbBuilderTest -Condition ((Get-AnEbBuilderPackageFingerprint -Root $output) -ceq (Get-AnEbBuilderPackageFingerprint -Root $secondOutput)) -Message 'same fixed inputs produce byte-identical package files'
    Assert-AnEbBuilderTest -Condition ([System.IO.File]::ReadAllText($receipt) -ceq [System.IO.File]::ReadAllText($secondReceipt)) -Message 'same fixed inputs produce byte-identical external admission receipts'
    Assert-AnEbBuilderTest -Condition ((Get-FileHash -LiteralPath $outputZip -Algorithm SHA256).Hash -ceq (Get-FileHash -LiteralPath $secondZip -Algorithm SHA256).Hash) -Message 'same fixed inputs produce a byte-identical immutable ZIP'

    $nestedReceiptOutput = Join-Path $tempRoot 'package-nested-receipt-rejected'
    $nestedReceipt = Join-Path $nestedReceiptOutput 'artifact-admission.json'
    $nestedReceiptResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $nestedReceiptOutput,
        '-AdmissionReceiptPath', $nestedReceipt,
        '-ReleaseCandidate', 'rc.synthetic.nested-receipt',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($nestedReceiptResult.ExitCode -ne 0 -and $nestedReceiptResult.Output -match 'ADMISSION_RECEIPT_MUST_BE_EXTERNAL') -Message 'builder rejects an admission receipt path inside the package root'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $nestedReceiptOutput)) -Message 'nested receipt rejection creates no partial package directory'

    $receiptAncestor = Join-Path $tempRoot 'receipt-path-cannot-be-a-directory'
    $receiptAncestorOutput = Join-Path $receiptAncestor 'package'
    $receiptAncestorResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $receiptAncestorOutput,
        '-AdmissionReceiptPath', $receiptAncestor,
        '-ReleaseCandidate', 'rc.synthetic.receipt-ancestor',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($receiptAncestorResult.ExitCode -ne 0 -and $receiptAncestorResult.Output -match 'ADMISSION_RECEIPT_MUST_BE_EXTERNAL') -Message 'builder rejects an output nested beneath the intended receipt file path'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $receiptAncestor)) -Message 'receipt/output ancestor conflict creates no partial path'

    $invalidCommitOutput = Join-Path $tempRoot 'package-invalid-source-commit'
    $invalidCommitReceipt = Join-Path $tempRoot 'receipts\artifact-admission-invalid-source-commit.json'
    $invalidCommitResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit.Substring(0, 12),
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $invalidCommitOutput,
        '-AdmissionReceiptPath', $invalidCommitReceipt,
        '-ReleaseCandidate', 'rc.synthetic.invalid-source',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($invalidCommitResult.ExitCode -ne 0 -and $invalidCommitResult.Output -match 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND') -Message 'builder rejects a non-exact source commit'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $invalidCommitOutput) -and -not (Test-Path -LiteralPath $invalidCommitReceipt)) -Message 'invalid source commit creates no package or receipt'

    $wrongCommitOutput = Join-Path $tempRoot 'package-wrong-source-commit'
    $wrongCommitReceipt = Join-Path $tempRoot 'receipts\artifact-admission-wrong-source-commit.json'
    $wrongCommitResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', ('f' * 40),
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $wrongCommitOutput,
        '-AdmissionReceiptPath', $wrongCommitReceipt,
        '-ReleaseCandidate', 'rc.synthetic.wrong-source',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($wrongCommitResult.ExitCode -ne 0 -and $wrongCommitResult.Output -match 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND') -Message 'builder rejects a well-formed commit that is not the clean source HEAD'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $wrongCommitOutput) -and -not (Test-Path -LiteralPath $wrongCommitReceipt)) -Message 'wrong source commit creates no package or receipt'

    $dirtyMarker = Join-Path $source 'uncommitted-release-input.txt'
    Write-AnEbBuilderUtf8 -Path $dirtyMarker -Text "dirty`n"
    try {
        $dirtyOutput = Join-Path $tempRoot 'package-dirty-source'
        $dirtyReceipt = Join-Path $tempRoot 'receipts\artifact-admission-dirty-source.json'
        $dirtyResult = Invoke-AnEbBuilderTool -Arguments @(
            '-SourceRoot', $source,
            '-SourceCommit', $sourceCommit,
            '-ServerPath', $artifacts.Server,
            '-AndroidApkPath', $artifacts.Apk,
            '-EvidenceDirectory', $artifacts.Evidence,
            '-ApkSignerPath', $artifacts.ApkSigner,
            '-OutputDirectory', $dirtyOutput,
            '-AdmissionReceiptPath', $dirtyReceipt,
            '-ReleaseCandidate', 'rc.synthetic.dirty-source',
            '-BuiltAtUtc', '2026-09-01T00:00:00Z',
            '-ServerVersion', 'aneb-server/0.1.0',
            '-AndroidVersionName', '0.2.0',
            '-AndroidVersionCode', '20'
        )
        Assert-AnEbBuilderTest -Condition ($dirtyResult.ExitCode -ne 0 -and $dirtyResult.Output -match 'SOURCE_WORKTREE_NOT_CLEAN') -Message 'builder rejects a dirty source worktree'
        Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $dirtyOutput) -and -not (Test-Path -LiteralPath $dirtyReceipt)) -Message 'dirty source rejection creates no package or receipt'
    }
    finally {
        Remove-Item -LiteralPath $dirtyMarker -Force -ErrorAction SilentlyContinue
    }

    $existingOutput = Join-Path $tempRoot 'package-existing-target'
    New-Item -ItemType Directory -Path $existingOutput | Out-Null
    Write-AnEbBuilderUtf8 -Path (Join-Path $existingOutput 'owner-marker.txt') -Text "preserve`n"
    $existingReceipt = Join-Path $tempRoot 'receipts\artifact-admission-existing-target.json'
    $existingResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $existingOutput,
        '-AdmissionReceiptPath', $existingReceipt,
        '-ReleaseCandidate', 'rc.synthetic.existing-target',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($existingResult.ExitCode -ne 0 -and $existingResult.Output -match 'TARGET_ALREADY_EXISTS') -Message 'builder rejects an existing output directory instead of merging or overwriting it'
    Assert-AnEbBuilderTest -Condition ([System.IO.File]::ReadAllText((Join-Path $existingOutput 'owner-marker.txt')) -ceq "preserve`n" -and -not (Test-Path -LiteralPath $existingReceipt)) -Message 'existing output rejection preserves caller files and creates no receipt'

    $staleReceiptOutput = Join-Path $tempRoot 'package-stale-receipt'
    $staleReceipt = Join-Path $tempRoot 'receipts\artifact-admission-stale.json'
    Write-AnEbBuilderUtf8 -Path $staleReceipt -Text "stale-owner-receipt`n"
    $staleReceiptResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $staleReceiptOutput,
        '-AdmissionReceiptPath', $staleReceipt,
        '-ReleaseCandidate', 'rc.synthetic.stale-receipt',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($staleReceiptResult.ExitCode -ne 0 -and $staleReceiptResult.Output -match 'TARGET_ALREADY_EXISTS') -Message 'builder rejects an existing external admission receipt instead of overwriting it'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $staleReceiptOutput) -and [System.IO.File]::ReadAllText($staleReceipt) -ceq "stale-owner-receipt`n") -Message 'stale receipt rejection preserves the caller receipt and creates no package'

    $missingArtifactOutput = Join-Path $tempRoot 'package-missing-artifact'
    $missingArtifactReceipt = Join-Path $tempRoot 'receipts\artifact-admission-missing-artifact.json'
    $missingArtifactResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', (Join-Path $tempRoot 'artifacts\missing-server.exe'),
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-OutputDirectory', $missingArtifactOutput,
        '-AdmissionReceiptPath', $missingArtifactReceipt,
        '-ReleaseCandidate', 'rc.synthetic.missing-artifact',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($missingArtifactResult.ExitCode -ne 0 -and $missingArtifactResult.Output -match 'FILE_NOT_REGULAR') -Message 'builder rejects a missing required artifact'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $missingArtifactOutput) -and -not (Test-Path -LiteralPath $missingArtifactReceipt)) -Message 'missing artifact rejection creates no package or receipt'

    $evidenceJunction = Join-Path $tempRoot 'artifacts\evidence-junction'
    New-Item -ItemType Junction -Path $evidenceJunction -Target $artifacts.Evidence -ErrorAction Stop | Out-Null
    try {
        $reparseOutput = Join-Path $tempRoot 'package-reparse-artifact'
        $reparseReceipt = Join-Path $tempRoot 'receipts\artifact-admission-reparse-artifact.json'
        $reparseResult = Invoke-AnEbBuilderTool -Arguments @(
            '-SourceRoot', $source,
            '-SourceCommit', $sourceCommit,
            '-ServerPath', $artifacts.Server,
            '-AndroidApkPath', $artifacts.Apk,
            '-EvidenceDirectory', $evidenceJunction,
            '-ApkSignerPath', $artifacts.ApkSigner,
            '-OutputDirectory', $reparseOutput,
            '-AdmissionReceiptPath', $reparseReceipt,
            '-ReleaseCandidate', 'rc.synthetic.reparse-artifact',
            '-BuiltAtUtc', '2026-09-01T00:00:00Z',
            '-ServerVersion', 'aneb-server/0.1.0',
            '-AndroidVersionName', '0.2.0',
            '-AndroidVersionCode', '20'
        )
        Assert-AnEbBuilderTest -Condition ($reparseResult.ExitCode -ne 0 -and $reparseResult.Output -match 'REPARSE') -Message 'builder rejects a reparse-backed evidence runtime'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $reparseOutput) -and -not (Test-Path -LiteralPath $reparseReceipt)) -Message 'reparse artifact rejection creates no package or receipt'
    }
    finally {
        if (Test-Path -LiteralPath $evidenceJunction) {
            [System.IO.Directory]::Delete($evidenceJunction)
        }
    }

    $mutatedApk = Join-Path $tempRoot 'artifacts\probe-mutated-by-signer.apk'
    Copy-Item -LiteralPath $artifacts.Apk -Destination $mutatedApk
    $mutatingSigner = Join-Path $tempRoot 'artifacts\apksigner-mutates-target.cmd'
    Write-AnEbBuilderUtf8 -Path $mutatingSigner -Text ("@echo off`r`necho Verifies`r`necho Signer #1 certificate SHA-256 digest: " + $approvedSigner + "`r`necho changed>>`"%~4`"`r`nexit /b 0`r`n")
    $mutatingSignerOutput = Join-Path $tempRoot 'package-mutating-signer'
    $mutatingSignerReceipt = Join-Path $tempRoot 'receipts\artifact-admission-mutating-signer.json'
    $mutatingSignerResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $mutatedApk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $mutatingSigner,
        '-OutputDirectory', $mutatingSignerOutput,
        '-AdmissionReceiptPath', $mutatingSignerReceipt,
        '-ReleaseCandidate', 'rc.mutating-signer',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($mutatingSignerResult.ExitCode -ne 0 -and $mutatingSignerResult.Output -match 'APK_CHANGED_DURING_VERIFICATION') -Message 'builder rejects an APK changed during staged signature verification'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $mutatingSignerOutput) -and -not (Test-Path -LiteralPath $mutatingSignerReceipt)) -Message 'APK verification race creates no package or receipt'

    $staleBuildReceiptPath = Join-Path $tempRoot 'artifact-build-receipt-stale-source.json'
    $staleBuildReceipt = [System.IO.File]::ReadAllText($artifactBuildReceipt) | ConvertFrom-Json
    $staleBuildReceipt.android_source_commit = ('f' * 40)
    Write-AnEbBuilderUtf8 -Path $staleBuildReceiptPath -Text (($staleBuildReceipt | ConvertTo-Json -Compress -Depth 8) + "`n")
    $staleBuildReceiptSha256 = (Get-FileHash -LiteralPath $staleBuildReceiptPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $staleBuildOutput = Join-Path $tempRoot 'package-stale-build-receipt'
    $staleBuildAdmission = Join-Path $tempRoot 'receipts\artifact-admission-stale-build-receipt.json'
    $staleBuildResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $artifacts.ApkSigner,
        '-ArtifactBuildReceiptPath', $staleBuildReceiptPath,
        '-ExpectedArtifactBuildReceiptSha256', $staleBuildReceiptSha256,
        '-OutputDirectory', $staleBuildOutput,
        '-AdmissionReceiptPath', $staleBuildAdmission,
        '-ReleaseCandidate', 'rc.stale-build-receipt',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($staleBuildResult.ExitCode -ne 0 -and $staleBuildResult.Output -match 'ARTIFACT_BUILD_RECEIPT_PROVENANCE_MISMATCH') -Message 'builder rejects an exactly pinned artifact-build receipt whose APK source provenance is stale'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $staleBuildOutput) -and -not (Test-Path -LiteralPath $staleBuildAdmission)) -Message 'stale artifact-build receipt creates no package or admission receipt'

    $builderSourceText = [System.IO.File]::ReadAllText($builder)
    $sameByteReceiptFunction = [regex]::Match(
        $builderSourceText,
        '(?s)function Read-AnEbArtifactBuildReceipt\s*\{(?<body>.*?)\r?\n\}\r?\n\r?\nfunction New-AnEbDeterministicReleaseZip'
    )
    Assert-AnEbBuilderTest -Condition $sameByteReceiptFunction.Success -Message 'artifact build receipt reader has one auditable function boundary'
    $sameByteReceiptBody = $sameByteReceiptFunction.Groups['body'].Value
    Assert-AnEbBuilderTest -Condition ($sameByteReceiptBody -match '\$receiptBytes\s*=\s*Read-AnEbBytes\s+-Path\s+\$full') -Message 'artifact build receipt is captured into one byte snapshot'
    Assert-AnEbBuilderTest -Condition ($sameByteReceiptBody -match 'Get-AnEbSha256Bytes\s+-Bytes\s+\$receiptBytes') -Message 'artifact build receipt pin hashes the captured byte snapshot'
    Assert-AnEbBuilderTest -Condition ($sameByteReceiptBody -match 'ConvertFrom-AnEbUtf8Strict\s+-Bytes\s+\$receiptBytes') -Message 'artifact build receipt JSON parses the same captured byte snapshot'
    Assert-AnEbBuilderTest -Condition ($sameByteReceiptBody -notmatch 'Get-AnEbSha256File|Read-AnEbUtf8Strict\s+-Path') -Message 'artifact build receipt never reopens the path for hash or parse'
    Assert-AnEbBuilderTest -Condition ($builderSourceText -match "Move-Item\s+-LiteralPath\s+\`$zipStagingPath\s+-Destination\s+\`$zipFull\s+-ErrorAction\s+Stop\s*\r?\n\s*\`$zipCreated\s*=\s*\`$true") -Message 'published ZIP ownership is recorded before any later cleanup can fail'
    $builderCatch = [regex]::Match($builderSourceText, '(?s)\r?\ncatch\s*\{(?<body>.*?)\r?\n\}\s*$')
    Assert-AnEbBuilderTest -Condition $builderCatch.Success -Message 'builder has one auditable top-level failure cleanup boundary'
    Assert-AnEbBuilderTest -Condition ($builderCatch.Groups['body'].Value -notmatch '\$OutputDirectory|\$OutputZipPath|\$AdmissionReceiptPath') -Message 'failure cleanup never deletes through unresolved caller path strings'
    Assert-AnEbBuilderTest -Condition ($builderCatch.Groups['body'].Value -notmatch 'Remove-Item[^\r\n]*-Recurse') -Message 'failure cleanup routes recursive deletion through owned-target validation'
    Assert-AnEbBuilderTest -Condition ($builderSourceText -match 'function Remove-AnEbOwnedTemporaryDirectory' -and $builderSourceText -match 'function Remove-AnEbPublishedOwnedDirectory' -and $builderSourceText -match 'function Remove-AnEbPublishedOwnedFile') -Message 'builder has bounded cleanup primitives for internal directories and published artifacts'
    Assert-AnEbBuilderTest -Condition ($builderSourceText -match '\$outputOwnershipToken\s*=\s*\[Guid\]::NewGuid\(\)\.ToString\(''N''\)' -and $builderSourceText -match 'Write-AnEbCreateNewUtf8[^\r\n]+\$outputOwnershipMarker') -Message 'published directory cleanup is authorized by a per-build ownership marker'
    Assert-AnEbBuilderTest -Condition ($builderSourceText -match '\$stagedServerSha256BeforeVerification\s+-cne\s+\$artifactBuildReceipt\.server_sha256') -Message 'staged server bytes remain equal to the pinned build receipt'
    Assert-AnEbBuilderTest -Condition ($builderSourceText -match '\$stagedApkSha256BeforeVerification\s+-cne\s+\$artifactBuildReceipt\.android_sha256') -Message 'staged APK bytes remain equal to the pinned build receipt'
    Assert-AnEbBuilderTest -Condition ($builderSourceText -match '\$evidenceTreeSha256BeforeCharacterization\s+-cne\s+\$artifactBuildReceipt\.evidence_runtime_tree_sha256' -and $builderSourceText -match 'Get-AnEbSha256File\s+-Path\s+\$stagedEvidenceExecutable\)\s+-cne\s+\$artifactBuildReceipt\.evidence_runtime_sha256') -Message 'staged evidence executable and tree remain equal to the pinned build receipt'

    $staleApkAnalyzer = Join-Path $tempRoot 'artifacts\apkanalyzer-stale-package.cmd'
    $staleApkAnalyzerText = [System.IO.File]::ReadAllText($artifacts.ApkAnalyzer).Replace('com.aneb.probe', 'com.example.stale')
    Write-AnEbBuilderUtf8 -Path $staleApkAnalyzer -Text $staleApkAnalyzerText
    $staleApkOutput = Join-Path $tempRoot 'package-stale-apk-identity'
    $staleApkAdmission = Join-Path $tempRoot 'receipts\artifact-admission-stale-apk-identity.json'
    $staleApkResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source, '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server, '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence, '-ApkSignerPath', $artifacts.ApkSigner,
        '-ApkAnalyzerPath', $staleApkAnalyzer,
        '-OutputDirectory', $staleApkOutput, '-AdmissionReceiptPath', $staleApkAdmission,
        '-ReleaseCandidate', 'rc.stale-apk-identity', '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0', '-AndroidVersionName', '0.2.0', '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($staleApkResult.ExitCode -ne 0 -and $staleApkResult.Output -match 'APK_PACKAGE_VERSION_MISMATCH') -Message 'builder rejects a signed APK whose extracted package identity is stale'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $staleApkOutput) -and -not (Test-Path -LiteralPath $staleApkAdmission)) -Message 'stale APK identity creates no package or admission receipt'

    $staleGoTool = Join-Path $tempRoot 'artifacts\go-stale-source.cmd'
    $staleGoText = [System.IO.File]::ReadAllText($artifacts.GoTool).Replace($sourceCommit, ('f' * 40))
    Write-AnEbBuilderUtf8 -Path $staleGoTool -Text $staleGoText
    $staleServerOutput = Join-Path $tempRoot 'package-stale-server-provenance'
    $staleServerAdmission = Join-Path $tempRoot 'receipts\artifact-admission-stale-server-provenance.json'
    $staleServerResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source, '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server, '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence, '-ApkSignerPath', $artifacts.ApkSigner,
        '-GoPath', $staleGoTool,
        '-OutputDirectory', $staleServerOutput, '-AdmissionReceiptPath', $staleServerAdmission,
        '-ReleaseCandidate', 'rc.stale-server-provenance', '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0', '-AndroidVersionName', '0.2.0', '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($staleServerResult.ExitCode -ne 0 -and $staleServerResult.Output -match 'SERVER_SOURCE_PROVENANCE_MISMATCH') -Message 'builder rejects a server whose extracted Go source provenance is stale'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $staleServerOutput) -and -not (Test-Path -LiteralPath $staleServerAdmission)) -Message 'stale server provenance creates no package or admission receipt'

    $staleEvidenceRoot = Join-Path $tempRoot 'artifacts\evidence-stale-source'
    New-Item -ItemType Directory -Path (Join-Path $staleEvidenceRoot '_internal') -Force | Out-Null
    $staleEvidenceExecutable = Join-Path $staleEvidenceRoot 'aneb-evidence.exe'
    $staleEvidenceSource = @'
using System;
using System.IO;
public static class AnebEvidenceStaleSourceStub {
    public static int Main(string[] args) {
        if (args.Length == 2 && args[0] == "build-info" && args[1] == "--json") {
            Console.WriteLine("{\"schema_version\":\"aneb-prototype-evidence-build-0.1\",\"source_commit\":\"ffffffffffffffffffffffffffffffffffffffff\"}");
            return 0;
        }
        if (args.Length == 3 && args[0] == "verify-bundle" && args[1] == "--bundle") {
            if (Directory.Exists(args[2]) && File.Exists(Path.Combine(args[2], "valid.marker"))) {
                Console.WriteLine("G0_VERIFY_OK");
                Console.WriteLine("ANEB_EVIDENCE_BUILD_INFO {\"schema_version\":\"aneb-prototype-evidence-build-0.1\",\"source_commit\":\"ffffffffffffffffffffffffffffffffffffffff\"}");
                return 0;
            }
            return 1;
        }
        return 2;
    }
}
'@
    Add-Type -TypeDefinition $staleEvidenceSource -Language CSharp -OutputAssembly $staleEvidenceExecutable -OutputType ConsoleApplication
    Write-AnEbBuilderUtf8 -Path (Join-Path $staleEvidenceRoot '_internal\runtime.dat') -Text "runtime`n"
    $staleEvidenceBuildReceiptPath = Join-Path $tempRoot 'artifact-build-receipt-stale-evidence-source.json'
    $staleEvidenceBuildReceipt = [System.IO.File]::ReadAllText($artifactBuildReceipt) | ConvertFrom-Json
    $staleEvidenceBuildReceipt.evidence_runtime_sha256 = (Get-FileHash -LiteralPath $staleEvidenceExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
    $staleEvidenceBuildReceipt.evidence_runtime_tree_sha256 = Get-AnEbBuilderTreeSha256 -Root $staleEvidenceRoot
    Write-AnEbBuilderUtf8 -Path $staleEvidenceBuildReceiptPath -Text (($staleEvidenceBuildReceipt | ConvertTo-Json -Compress -Depth 8) + "`n")
    $staleEvidenceBuildReceiptSha256 = (Get-FileHash -LiteralPath $staleEvidenceBuildReceiptPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $staleEvidenceOutput = Join-Path $tempRoot 'package-stale-evidence-source'
    $staleEvidenceAdmission = Join-Path $tempRoot 'receipts\artifact-admission-stale-evidence-source.json'
    $staleEvidenceResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source, '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server, '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $staleEvidenceRoot, '-ApkSignerPath', $artifacts.ApkSigner,
        '-ArtifactBuildReceiptPath', $staleEvidenceBuildReceiptPath,
        '-ExpectedArtifactBuildReceiptSha256', $staleEvidenceBuildReceiptSha256,
        '-OutputDirectory', $staleEvidenceOutput, '-AdmissionReceiptPath', $staleEvidenceAdmission,
        '-ReleaseCandidate', 'rc.stale-evidence-source', '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0', '-AndroidVersionName', '0.2.0', '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($staleEvidenceResult.ExitCode -ne 0 -and $staleEvidenceResult.Output -match 'EVIDENCE_SOURCE_PROVENANCE_MISMATCH') -Message 'builder rejects an evidence runtime whose extracted source provenance is stale'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $staleEvidenceOutput) -and -not (Test-Path -LiteralPath $staleEvidenceAdmission)) -Message 'stale evidence provenance creates no package or admission receipt'

    $unapprovedSigner = Join-Path $tempRoot 'artifacts\apksigner-unapproved.cmd'
    Write-AnEbBuilderUtf8 -Path $unapprovedSigner -Text ("@echo off`r`necho Verifies`r`necho Signer #1 certificate SHA-256 digest: " + ('0' * 64) + "`r`nexit /b 0`r`n")
    $unapprovedOutput = Join-Path $tempRoot 'package-unapproved-signer'
    $unapprovedReceipt = Join-Path $tempRoot 'receipts\artifact-admission-unapproved-signer.json'
    $unapprovedResult = Invoke-AnEbBuilderTool -Arguments @(
        '-SourceRoot', $source,
        '-SourceCommit', $sourceCommit,
        '-ServerPath', $artifacts.Server,
        '-AndroidApkPath', $artifacts.Apk,
        '-EvidenceDirectory', $artifacts.Evidence,
        '-ApkSignerPath', $unapprovedSigner,
        '-OutputDirectory', $unapprovedOutput,
        '-AdmissionReceiptPath', $unapprovedReceipt,
        '-ReleaseCandidate', 'rc.synthetic.unapproved-signer',
        '-BuiltAtUtc', '2026-09-01T00:00:00Z',
        '-ServerVersion', 'aneb-server/0.1.0',
        '-AndroidVersionName', '0.2.0',
        '-AndroidVersionCode', '20'
    )
    Assert-AnEbBuilderTest -Condition ($unapprovedResult.ExitCode -ne 0 -and $unapprovedResult.Output -match 'P011_RELEASE_SIGNER_NOT_APPROVED') -Message 'builder rejects an APK outside the approved production signer identity'
    Assert-AnEbBuilderTest -Condition (-not (Test-Path -LiteralPath $unapprovedOutput) -and -not (Test-Path -LiteralPath $unapprovedReceipt)) -Message 'unapproved signer rejection creates no package or receipt'

    Write-Output ("PASS G3_RELEASE_PACKAGE_BUILDER_TESTS count=" + $passed)
    exit 0
}
catch {
    Write-Output ("FAIL G3_RELEASE_PACKAGE_BUILDER_TESTS " + $_.Exception.Message)
    exit 1
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
