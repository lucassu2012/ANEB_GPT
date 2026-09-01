# ANEB Probe 0.2.0 release build

## Approved signing identity

The production signing key belongs to the Product Owner and stays outside this repository. The only approved identity for this release line is:

- key alias: `aneb-production`
- certificate SHA-256: `b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`

The certificate fingerprint is public release metadata. The keystore, DPAPI credential files, passwords, and their absolute paths are secrets. Do not copy them into this repository, a build artifact, a command line, a Gradle properties file, a transcript, CI output, screenshots, or chat.

The Gradle signing check fails closed when the alias or certificate fingerprint does not match the approved identity.

## Prepare one isolated signing process

Use a dedicated, clean release worktree and a new output directory for each candidate. Do not reuse artifacts from an earlier build.

Restore the locally held DPAPI credential only inside the PowerShell process that will run Gradle. Map the restored values to these **Process-scoped** variables:

```powershell
$env:ANEB_RELEASE_STORE_FILE = $storeFileOutsideRepository
$env:ANEB_RELEASE_STORE_PASSWORD = $storePasswordRestoredFromDpapi
$env:ANEB_RELEASE_KEY_ALIAS = 'aneb-production'
$env:ANEB_RELEASE_KEY_PASSWORD = $keyPasswordRestoredFromDpapi
```

The names on the right are placeholders, not persistent configuration. The release operator's local restore procedure supplies them without printing them. Do not use `setx`, User/Machine-scoped environment variables, `-P...Password=...`, shell history, or `Start-Transcript` for a signing session.

Always clear the process variables, including when the build fails:

```powershell
try {
    # Run the build commands from the next section here.
}
finally {
    Remove-Item Env:ANEB_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:ANEB_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:ANEB_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:ANEB_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    $storePasswordRestoredFromDpapi = $null
    $keyPasswordRestoredFromDpapi = $null
}
```

Never print the environment variables or include the secret source path in retained release logs. Retained evidence may contain only the public alias, public certificate fingerprint, artifact names, artifact hashes, task results, and timestamps.

## Build the local-first signed APK

From `app/`, in the same short-lived process that restored the credential:

```powershell
$sourceCommit = '<exact-lowercase-40-hex-commit-being-built>'
.\gradlew.bat `
    ('-Paneb.prototype.sourceCommit=' + $sourceCommit) `
    :probe:verifyReleaseSigning `
    :probe:verifyPrototypeSourceCommit `
    :probe:assemblePrototypeRelease
if ($LASTEXITCODE -ne 0) { throw 'prototypeRelease build failed' }
```

The source commit is public provenance, not a secret. It must be the exact committed source tree
used for the candidate and must match `^[0-9a-f]{40}$`. A missing, abbreviated, uppercase or dirty
worktree-only identity fails closed with `P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND`. Do not substitute
the branch name, PR number or a pre-commit parent.

`prototypeRelease` is the installable Prototype 0.1 candidate. It uses the production package identity and approved production signer while allowing the app's narrowly constrained local Prototype server path. It is not the debug-signed `prototypeEngineering` variant.

The ordinary `release` variant remains HTTPS-only. Do not substitute `assembleRelease`, `assembleDebug`, or `assemblePrototypeEngineering` for the Prototype 0.1 release candidate.

After Gradle succeeds, copy the newly produced `prototypeRelease` APK into a new candidate-specific staging directory outside the worktree. Record its SHA-256 immediately:

```powershell
$apk = Resolve-Path '.\probe\build\outputs\apk\prototypeRelease\probe-prototypeRelease.apk'
$candidateRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('aneb-prototype-release-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $candidateRoot -ErrorAction Stop | Out-Null
$stagedApk = Join-Path $candidateRoot 'probe-prototypeRelease.apk'
Copy-Item -LiteralPath $apk -Destination $stagedApk -ErrorAction Stop

$sourceSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$stagedSha256 = (Get-FileHash -LiteralPath $stagedApk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($stagedSha256 -ne $sourceSha256) { throw 'staged APK hash mismatch' }
Write-Output ("APK_SHA256={0}" -f $stagedSha256)
```

If the expected APK path does not exist, stop. Do not fall back to another variant or an older file.

## Verify independently before packaging

Clear the four signing environment variables first. Then verify the copied APK in a separate clean PowerShell process with the Android SDK `apksigner`; verification must not reuse Gradle's signing result as proof.

```powershell
$stagedApk = Resolve-Path '<candidate-staging-directory>\probe-prototypeRelease.apk'
$apksigner = (Get-Command 'apksigner.bat' -ErrorAction Stop).Source
$approved = 'b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003'
$verifyOutput = @(& $apksigner verify --verbose --print-certs $stagedApk 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw 'APK signature verification failed'
}

$verifyText = [string]::Join("`n", $verifyOutput)
$matches = [regex]::Matches(
    $verifyText,
    '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$'
)
if ($matches.Count -ne 1) {
    throw 'APK must contain exactly one signer certificate'
}

$actual = ($matches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
if ($actual -ne $approved) {
    throw 'P011_RELEASE_SIGNER_NOT_APPROVED'
}

$stagedSha256 = (Get-FileHash -LiteralPath $stagedApk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output ("APPROVED_CERT_SHA256={0}" -f $actual)
Write-Output ("APK_SHA256={0}" -f $stagedSha256)
```

Retain the successful `apksigner` result, approved certificate SHA-256, exact APK SHA-256, source commit, and build-gate receipt together. A candidate is not releasable if any value is missing or if the APK changes after verification.

## Build the immutable Windows package

The packaging step is offline and local-first. It accepts only a clean exact source commit and caller-supplied artifacts whose independent build receipt is pinned by SHA-256. The receipt is not a free-form operator assertion: before packaging, the Android lane must extract package name, version name/code, and embedded source commit from the built APK; the Go lane must retain `go version -m` provenance; and the evidence lane must retain the executable/tree hashes plus a real valid-pass/invalid-reject CLI characterization. If any lane cannot produce that evidence, stop rather than filling the receipt from expected values.

Build the evidence onedir from that same clean commit so the executable embeds independently queryable provenance:

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\build-evidence-verifier.ps1 `
  -PythonExecutable '<pinned-python.exe>' `
  -OutputDirectory '<fresh-evidence-build-directory>' `
  -SourceCommit '<exact-lowercase-40-hex-commit>'
if ($LASTEXITCODE -ne 0) { throw 'evidence verifier build failed' }
```

The external build receipt schema is `aneb-prototype-build-artifacts-0.1` and has exactly these fields: `schema_version`, `source_commit`, `server_sha256`, `server_version`, `android_sha256`, `android_package_name`, `android_version_name`, `android_version_code`, `android_source_commit`, `evidence_source_commit`, `evidence_runtime_sha256`, `evidence_runtime_tree_sha256`, and `evidence_runtime_characterization`. The evidence executable must return that exact commit from `build-info --json`; a sidecar or operator assertion is not sufficient. The characterization value for Prototype 0.1 is `valid_bundle_pass_invalid_bundle_reject`.

From the clean source worktree, with fresh artifact paths and no signing secrets in the command line:

```powershell
$candidate = 'rc.<candidate-id>'
$zip = Join-Path '<fresh-output-parent>' ("ANEB-Prototype-0.1-$candidate-windows-x64.zip")

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\build-release-package.ps1 `
  -SourceRoot . `
  -SourceCommit '<exact-lowercase-40-hex-commit>' `
  -ServerPath '<fresh-aneb-server.exe>' `
  -AndroidApkPath '<fresh-signed-prototypeRelease.apk>' `
  -EvidenceDirectory '<fresh-evidence-runtime-directory>' `
  -ApkSignerPath '<apksigner.bat>' `
  -ApkAnalyzerPath '<apkanalyzer.bat>' `
  -GoPath '<go.exe>' `
  -EvidenceCharacterizationBundlePath '<known-valid-evidence-bundle>' `
  -ArtifactBuildReceiptPath '<external-build-receipt.json>' `
  -ExpectedArtifactBuildReceiptSha256 '<64-lowercase-hex>' `
  -OutputDirectory '<fresh-output-parent>\ANEB-Prototype-0.1' `
  -OutputZipPath $zip `
  -AdmissionReceiptPath '<external-admission-receipt.json>' `
  -ReleaseCandidate $candidate `
  -BuiltAtUtc '<RFC3339-UTC>' `
  -ServerVersion 'aneb-server/0.1.0' `
  -AndroidVersionName '0.2.0' `
  -AndroidVersionCode 20
if ($LASTEXITCODE -ne 0) { throw 'release packaging failed' }
```

The builder copies each artifact once into a private staging tree, then performs signer, APK identity, Go provenance, and evidence CLI checks against those staged bytes. It rejects any artifact that changes during checking. It publishes no partial candidate on failure and never overwrites an existing directory, ZIP, or receipt.

The output ZIP name is fixed by the candidate ID. It contains exactly one `ANEB-Prototype-0.1/` top-level directory, sorted entries, fixed metadata, and an initially empty `results/`. Two builds with identical inputs must have identical package files, admission receipt, ZIP bytes, and ZIP SHA-256.

## Formal admission and evidence verification

After packaging, use a separate process and the exact receipt SHA printed by the build record. Formal admission requires the external receipt and the original immutable ZIP together:

```powershell
$packageRoot = '<fresh-output-parent>\ANEB-Prototype-0.1'
$receipt = '<external-admission-receipt.json>'
$receiptSha256 = '<64-lowercase-hex>'
$zip = '<fresh-output-parent>\ANEB-Prototype-0.1-rc.<candidate-id>-windows-x64.zip'

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File (Join-Path $packageRoot 'tools\doctor.ps1') `
  -Root $packageRoot `
  -RequireExternalAdmission `
  -AdmissionReceiptPath $receipt `
  -ExpectedAdmissionReceiptSha256 $receiptSha256 `
  -PackageZipPath $zip
if ($LASTEXITCODE -ne 0) { throw 'formal package admission failed' }
```

For a completed campaign, run the packaged evidence CLI rather than inspecting the report visually:

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File (Join-Path $packageRoot 'tools\verify-evidence.ps1') `
  -Bundle (Join-Path $packageRoot 'results\<campaign_id>')
if ($LASTEXITCODE -ne 0) { throw 'evidence verification failed' }
```

An ordinary `verify-package.ps1 -Root <package>` run deliberately claims only runtime integrity. It is useful after extraction but is not a substitute for the formal external admission gate.

## Network trust boundary

- `prototypeRelease` may use cleartext only for the app's local-first Prototype server route on loopback or literal private-network IPv4 destinations permitted by the in-app policy.
- Ordinary internet cleartext remains denied; the Prototype allowance must not become a general HTTP bypass.
- The ordinary production `release` variant remains HTTPS-only.
- `120.79.148.0` separately uses the project-owned public CA required by the D-22/D-25 measurement channel. The CA certificate is public material and must be rotated with the server certificate before expiry.

## Current artifact identities

- Debug package: `com.aneb.probe.codex`
- Production and `prototypeRelease` package: `com.aneb.probe`
- Version: `0.2.0` (`versionCode=20`)
- Prototype candidate task: `:probe:assemblePrototypeRelease`
- Prototype candidate output: `app/probe/build/outputs/apk/prototypeRelease/probe-prototypeRelease.apk`

Do not publish a hard-coded APK hash in this document. Each candidate's exact SHA-256 belongs in that candidate's immutable release receipt.
