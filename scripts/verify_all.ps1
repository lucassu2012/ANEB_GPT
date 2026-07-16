# ANEB Probe verify_all - phase 0 verification chain (ASCII-only for PS 5.1 compatibility)
# Runs: server vet/build/test, profile JSON validation, app toolchain probe.
# Writes: evidence/phase0/verify_all_<ts>.log (utf8) and regenerates evidence/phase0/sha256-manifest.txt
# Exit code: 0 if no FAIL (NOT_EXECUTED allowed), 1 otherwise.

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$evidenceDir = Join-Path $repo 'evidence\phase0'
New-Item -ItemType Directory -Force $evidenceDir | Out-Null
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$logPath = Join-Path $evidenceDir "verify_all_$ts.log"
$results = @()

function Add-Result([string]$check, [string]$state, [string]$detail) {
    $script:results += [pscustomobject]@{ check = $check; state = $state; detail = $detail }
    "$state  $check  $detail"
}

$log = @()
$log += "verify_all run at $ts"
$log += "repo: $repo"

# --- locate go ---
$goCandidates = @('C:\Program Files\Go\bin\go.exe', 'E:\tools\go\bin\go.exe')
$go = $null
foreach ($c in $goCandidates) { if (Test-Path $c) { $go = $c; break } }
if (-not $go) { try { $go = (Get-Command go -ErrorAction Stop).Source } catch {} }
$log += "go: $(if ($go) { $go } else { 'NOT FOUND' })"

# --- server checks ---
if ($go) {
    Push-Location (Join-Path $repo 'server')
    foreach ($step in @(@('vet', @('vet', './...')), @('build', @('build', './...')), @('test', @('test', '-count=1', './...')))) {
        $name = $step[0]; $goArgs = $step[1]
        $out = & $go @goArgs 2>&1 | Out-String
        $state = if ($LASTEXITCODE -eq 0) { 'PASS' } else { 'FAIL' }
        $log += "--- server-$name (exit $LASTEXITCODE) ---"
        $log += $out
        $log += Add-Result "server-$name" $state "go $($goArgs -join ' ')"
    }
    Pop-Location
} else {
    foreach ($name in 'vet', 'build', 'test') {
        $log += Add-Result "server-$name" 'NOT_EXECUTED' 'go toolchain not found'
    }
}

# --- profile validation ---
$profileErrors = @()
$profileFiles = Get-ChildItem (Join-Path $repo 'profiles') -Filter '*.json'
foreach ($f in $profileFiles) {
    try {
        $p = Get-Content -Raw -Encoding UTF8 $f.FullName | ConvertFrom-Json
        foreach ($field in 'profile_id', 'version', 'kpi_set', 'phases') {
            if ($null -eq $p.$field) { $profileErrors += "$($f.Name): missing $field" }
        }
        if ($p.phases.Count -lt 1) { $profileErrors += "$($f.Name): empty phases" }
    } catch { $profileErrors += "$($f.Name): parse error: $_" }
}
if ($profileFiles.Count -eq 0) { $profileErrors += 'no profile files found' }
$log += Add-Result 'profiles-valid' $(if ($profileErrors.Count -eq 0) { 'PASS' } else { 'FAIL' }) $(if ($profileErrors.Count -eq 0) { "$($profileFiles.Count) profiles ok" } else { $profileErrors -join '; ' })

# --- app toolchain probe (build requires JDK + Android SDK) ---
$jdk = $null; try { $jdk = (Get-Command java -ErrorAction Stop).Source } catch {}
$sdk = ($env:ANDROID_HOME) -or (Test-Path "$env:LOCALAPPDATA\Android\Sdk")
$wrapperJar = Test-Path (Join-Path $repo 'app\gradle\wrapper\gradle-wrapper.jar')
if ($jdk -and $sdk -and $wrapperJar) {
    Push-Location (Join-Path $repo 'app')
    $out = & .\gradlew.bat ':probe:assembleDebug' '--no-daemon' 2>&1 | Out-String
    $state = if ($LASTEXITCODE -eq 0) { 'PASS' } else { 'FAIL' }
    $log += $out
    $log += Add-Result 'app-assembleDebug' $state 'gradlew :probe:assembleDebug'
    Pop-Location
} else {
    $missing = @()
    if (-not $jdk) { $missing += 'JDK' }
    if (-not $sdk) { $missing += 'AndroidSDK' }
    if (-not $wrapperJar) { $missing += 'gradle-wrapper.jar' }
    $log += Add-Result 'app-assembleDebug' 'NOT_EXECUTED' ("missing: " + ($missing -join ', '))
}

# --- write log (utf8, never UTF-16) ---
$log -join "`r`n" | Out-File -Encoding utf8 $logPath

# --- regenerate sha256 manifest for evidence/phase0 (scripted, never manual) ---
$manifestPath = Join-Path $evidenceDir 'sha256-manifest.txt'
$lines = @()
Get-ChildItem $evidenceDir -Recurse -File | Where-Object { $_.Name -ne 'sha256-manifest.txt' } | Sort-Object FullName | ForEach-Object {
    $h = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower()
    $rel = $_.FullName.Substring($evidenceDir.Length + 1) -replace '\\', '/'
    $lines += "$h  $rel"
}
$lines -join "`r`n" | Out-File -Encoding utf8 $manifestPath

# --- summary ---
''
'=== verify_all summary ==='
$results | ForEach-Object { "{0,-14} {1}  {2}" -f $_.state, $_.check, $_.detail }
"log: $logPath"
"manifest: $manifestPath ($($lines.Count) files)"
$failed = @($results | Where-Object { $_.state -eq 'FAIL' })
if ($failed.Count -gt 0) { exit 1 } else { exit 0 }
