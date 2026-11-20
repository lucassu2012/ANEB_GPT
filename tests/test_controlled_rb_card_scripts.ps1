[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$tmplDir = Join-Path $repoRoot 'tools/controlled-rb-card/templates'
$passed = 0

function Assert-Card([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL $Message" }
    $script:passed++
    Write-Output "PASS $Message"
}

function Render-DummyTemplate([string]$Text) {
    $map = @{
        '{{CARD_TITLE}}'                  = 'Test Card'
        '{{CARD_ROOT}}'                   = 'C:/temp/test-card'
        '{{CASE_ROOT}}'                   = 'C:/temp/test-case'
        '{{ADB_EXE}}'                     = 'C:/tools/adb.exe'
        '{{ADB_SERIAL}}'                  = 'TESTSERIAL'
        '{{NATIVE_TTL_SECONDS}}'          = '100'
        '{{SOURCE_COMMIT}}'               = ('a' * 40)
        '{{PROFILE_SHA256}}'              = ('b' * 64)
        '{{BUSINESS_PACKAGE}}'            = 'com.example.biz'
        '{{BUSINESS_VERSION_CODE}}'       = '100'
        '{{TARGET_PACKAGE}}'              = 'com.aneb.probe'
        '{{TEST_PACKAGE}}'                = 'com.aneb.probe.test'
        '{{RUN_ID_PREFIX}}'               = 'test-rb-'
        '{{UI_OBSERVATION_METHOD}}'       = 'TEST_METHOD'
        '{{B_DOWNLINK_IP_BYTES_PER_SECOND}}' = '187500'
        '{{B_DOWNLINK_DELAY_MS}}'         = '0'
        '{{B_DOWNLINK_LOSS_BPS}}'         = '0'
        '{{B_IMPAIRMENT_SEED}}'           = '0'
        '{{B_QUEUE_OVERFLOW_POLICY}}'     = ''
        '{{B_QUEUE_LIMIT_BYTES}}'         = '0'
        '{{B_UPLINK_IP_BYTES_PER_SECOND}}' = '0'
        '{{B_UPLINK_DELAY_MS}}'           = '0'
        '{{B_UPLINK_LOSS_BPS}}'           = '0'
        '{{B_UPLINK_IMPAIRMENT_SEED}}'    = '0'
        '{{CANDIDATE_ARTIFACTS_SHA256}}'  = ('c' * 64)
        '{{UI_BINDING_FILENAME}}'         = 'UI_BINDING.json'
        '{{UI_BINDING_SHA256}}'           = ('d' * 64)
        '{{TARGET_APK_SHA256}}'           = ('e' * 64)
        '{{TEST_APK_SHA256}}'             = ('f' * 64)
        '{{HELPER_SHA256}}'               = ('0' * 64)
        '{{CONNECTION_ADMISSION_SHA256}}' = ('1' * 64)
        '{{TOPOLOGY_ADMISSION_SHA256}}'   = ('2' * 64)
        '{{UI_VALIDATOR_FN}}'             = 'Assert-TestUiBinding'
        '{{OUTCOME_FORMAT}}'              = 'test-outcome-1'
        '{{PROBE_CANDIDATE_HOSTS}}'       = "'120.77.174.207'"
        '{{CARD_FORMAT}}'                 = 'test-card-1'
        '{{CARD_PO}}'                     = 'test-po'
        '{{PHASE_ORDER_PS}}'              = "'R', 'B'"
        '{{INSTRUMENTATION_CLASS}}'       = 'com.aneb.probe.TestClass'
        '{{COMMON_SHA256}}'               = ('3' * 64)
        '{{EXECUTE_ONCE_SHA256}}'         = ('4' * 64)
        '{{VERIFY_CLEANUP_SHA256}}'       = ('5' * 64)
        '{{OUTER_FORMAT}}'                = 'test-outer-1'
        '{{CARD_BANNER}}'                 = 'TEST BANNER'
    }
    $rendered = $Text
    foreach ($k in $map.Keys) {
        $rendered = $rendered.Replace($k, $map[$k])
    }
    return $rendered
}

# Part A: Static & syntax validation of all 4 templates
$templateNames = @('common.ps1.tmpl', 'execute-once.ps1.tmpl', 'run-once.ps1.tmpl', 'verify-cleanup-readonly.ps1.tmpl')
foreach ($name in $templateNames) {
    $path = Join-Path $tmplDir $name
    Assert-Card (Test-Path -LiteralPath $path) "template exists: $name"
    $bytes = [IO.File]::ReadAllBytes($path)
    $nonAscii = @($bytes | Where-Object { $_ -gt 127 }).Count
    Assert-Card ($nonAscii -eq 0) "template is pure ASCII: $name"
    $raw = [Text.Encoding]::ASCII.GetString($bytes)
    $rendered = Render-DummyTemplate $raw
    Assert-Card ($rendered -notmatch '\{\{[A-Z0-9_]+\}\}') "all template tokens substituted: $name"
    $tokens = $null; $errors = $null
    $null = [Management.Automation.Language.Parser]::ParseInput($rendered, [ref]$tokens, [ref]$errors)
    Assert-Card ($errors.Count -eq 0) "PowerShell 5.1 AST parses cleanly: $name"
}

$execTmpl = [IO.File]::ReadAllText((Join-Path $tmplDir 'execute-once.ps1.tmpl'), [Text.Encoding]::ASCII)
foreach ($literal in @('250000', '125000', '187500', '500000')) {
    Assert-Card (-not $execTmpl.Contains($literal)) "execute-once.ps1.tmpl has no hardcoded rate literal $literal"
}
$execBrRendered = (Render-DummyTemplate $execTmpl).Replace("phases                            = @('R', 'B')", "phases                            = @('B', 'R')").Replace("foreach (`$conditionId in @('R', 'B'))", "foreach (`$conditionId in @('B', 'R'))")
$brTokens = $null; $brErrors = $null
$null = [Management.Automation.Language.Parser]::ParseInput($execBrRendered, [ref]$brTokens, [ref]$brErrors)
Assert-Card ($brErrors.Count -eq 0 -and $execBrRendered.Contains("phases                            = @('B', 'R')") -and $execBrRendered.Contains("foreach (`$conditionId in @('B', 'R'))")) 'execute-once.ps1.tmpl renders B->R cleanly and parses'
Assert-Card ($execTmpl.Contains('if ($bUplinkIpBytesPerSecond -gt 0)') -and $execTmpl.Contains("`$reqPayload['uplink_ip_bytes_per_second'] = `$bUplinkIpBytesPerSecond") -and (-not $execTmpl.Contains('uplink_queue_overflow_policy'))) 'execute-once.ps1.tmpl guards uplink_* request.json emission cleanly'
$natLookupGuardOk = $execTmpl -match '(?s)\$uplinkNatAddrs = @\(\)\r?\n\s*if \(\$bUplinkIpBytesPerSecond -gt 0\) \{\r?\n\s*\$uplinkNatAddrs = @\(Get-NetIPAddress -InterfaceIndex \$uplinkIfIndex -AddressFamily IPv4'
Assert-Card $natLookupGuardOk 'execute-once.ps1.tmpl guards Get-NetIPAddress for uplink_nat_addresses inside bUplinkIpBytesPerSecond > 0'

# Part B: Unit test extracted Get-AnebPhaseInstrumentationConditionArgs, Assert-AnebPhaseResultConditionMatch & Assert-AnebDoubaoUiBinding
$commonRendered = Render-DummyTemplate ([IO.File]::ReadAllText((Join-Path $tmplDir 'common.ps1.tmpl'), [Text.Encoding]::ASCII))
$tokens = $null; $errors = $null
$commonAst = [Management.Automation.Language.Parser]::ParseInput($commonRendered, [ref]$tokens, [ref]$errors)
foreach ($fnName in @('Get-AnebPhaseInstrumentationConditionArgs', 'Assert-AnebPhaseResultConditionMatch', 'Assert-AnebDoubaoUiBinding')) {
    $fnAst = @($commonAst.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $fnName }, $true))
    Assert-Card ($fnAst.Count -eq 1) "extracted $fnName from common.ps1.tmpl"
    . ([scriptblock]::Create($fnAst[0].Extent.Text))
}
$doubaoBindingPath = Join-Path $repoRoot 'tools/controlled-rb-card/bindings/ui-binding-doubao-15080040-v1.json'
Assert-Card (Test-Path -LiteralPath $doubaoBindingPath) 'doubao UI binding JSON exists in repo'
$doubaoBindingBytes = [IO.File]::ReadAllBytes($doubaoBindingPath)
$doubaoBindingText = [Text.UTF8Encoding]::new($false).GetString($doubaoBindingBytes)
$shaAlg = [Security.Cryptography.SHA256]::Create()
try { $doubaoBindingSha = [BitConverter]::ToString($shaAlg.ComputeHash($doubaoBindingBytes)).Replace('-', '').ToLowerInvariant() } finally { $shaAlg.Dispose() }
$doubaoVal = Assert-AnebDoubaoUiBinding $doubaoBindingText $doubaoBindingSha
Assert-Card ($doubaoVal.valid -eq $true -and $doubaoVal.query_plan_count -eq 5) 'Assert-AnebDoubaoUiBinding validates repo Doubao binding'
$doubaoBadThrew = $false
try { [void](Assert-AnebDoubaoUiBinding $doubaoBindingText ('0' * 64)) } catch { $doubaoBadThrew = $true }
Assert-Card $doubaoBadThrew 'Assert-AnebDoubaoUiBinding rejects SHA-256 mismatch'

$rArgs = @(Get-AnebPhaseInstrumentationConditionArgs 'R' ([pscustomobject]@{ downlink_ip_bytes_per_second = 187500 }))
Assert-Card ($rArgs.Count -eq 0) 'phase R emits no host_downlink_ip_bytes_per_second arg'

foreach ($rate in @('187500', '125000', '500000')) {
    $req = [pscustomobject]@{ downlink_ip_bytes_per_second = [uint64]$rate }
    $bArgs = @(Get-AnebPhaseInstrumentationConditionArgs 'B' $req)
    Assert-Card (($bArgs -join '|') -ceq ('-e|host_downlink_ip_bytes_per_second|' + $rate)) "phase B derives $rate from request.json"
    $goodDev = [pscustomobject]@{ hostNetworkCondition = [pscustomobject]@{ downlinkIpBytesPerSecond = [uint64]$rate } }
    Assert-AnebPhaseResultConditionMatch 'B' $goodDev $req
    Assert-Card $true "phase B provenance accepts matching downlinkIpBytesPerSecond=$rate"
}

$badDev = [pscustomobject]@{ hostNetworkCondition = [pscustomobject]@{ downlinkIpBytesPerSecond = 250000 } }
$mismatchThrew = $false
try {
    Assert-AnebPhaseResultConditionMatch 'B' $badDev ([pscustomobject]@{ downlink_ip_bytes_per_second = 187500 })
} catch {
    $mismatchThrew = $true
}
Assert-Card $mismatchThrew 'phase B provenance throws on 250000 vs 187500 drift'

$missingRateThrew = $false
try {
    [void](Get-AnebPhaseInstrumentationConditionArgs 'B' ([pscustomobject]@{ downlink_ip_bytes_per_second = 0 }))
} catch {
    $missingRateThrew = $true
}
Assert-Card $missingRateThrew 'phase B rejects zero downlink_ip_bytes_per_second'

# Part C: End-to-end execution of rendered verify-cleanup-readonly.ps1
$verifyTmplText = [IO.File]::ReadAllText((Join-Path $tmplDir 'verify-cleanup-readonly.ps1.tmpl'), [Text.Encoding]::ASCII)
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('aneb-rb-card-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

function New-VerifyScenario {
    param(
        [string]$Name,
        [hashtable]$TerminalData,
        [hashtable]$BeforeData = $null,
        [hashtable]$MainIntent = $null,
        [hashtable]$TestIntent = $null,
        [hashtable]$PointerIntent = $null,
        [bool]$CreateHotspotIntent = $false,
        [string]$MockTargetSha = ('a' * 64),
        [string]$MockTestSha = ('b' * 64),
        [string]$MockPointer = '0',
        [bool]$MockHostClean = $true,
        [bool]$CreateCardJson = $false
    )
    $dir = Join-Path $tempRoot $Name
    $rt = Join-Path $dir 'runtime'
    New-Item -ItemType Directory -Path $rt -Force | Out-Null

    $mockCommon = @"
`$ErrorActionPreference = 'Stop'
`$root = '$($dir.Replace('\', '/'))'
`$runtime = '$($rt.Replace('\', '/'))'
`$targetPkg = 'com.aneb.probe'
`$testPkg = 'com.aneb.probe.test'
`$pins = @()
`$readDeadlineTicks = 0
function Save-New(`$path, `$value) {
    `$json = (`$value | ConvertTo-Json -Depth 16) + "`n"
    [IO.File]::WriteAllText(`$path, `$json, [Text.UTF8Encoding]::new(`$false))
}
function Read-JSON(`$path) {
    return ([IO.File]::ReadAllText(`$path, [Text.Encoding]::UTF8) | ConvertFrom-Json -ErrorAction Stop)
}
function Get-AnebReadBudget([int]`$cap) {
    if (`$script:readDeadlineTicks -le [Diagnostics.Stopwatch]::GetTimestamp()) { throw 'Read budget expired' }
    return `$cap
}
function Phone-Clean(`$prefix) { }
function Host-NoResidue {
    if (-not `$$($MockHostClean.ToString().ToLowerInvariant())) { throw 'Simulated host residue' }
}
function Test-AnebWifiConnectedToSaved(`$wifi, `$ssid, `$netId) {
    return (`$wifi -match [regex]::Escape(`$ssid) -and `$wifi -match ('Net ID: ' + `$netId + ','))
}
function Read-AnebEthernetOffTopology {
    return [ordered]@{ hotspot_state = 'Off'; ap_identity_matches = `$true; start_stop_invoked = `$false }
}
function Read-AnebSharingSnapshot(`$tag) {
    return @([ordered]@{ name = 'Ethernet'; sharing_enabled = `$false })
}
function Assert-AnebSharingDisabled(`$snap) {
    foreach (`$s in `$snap) { if (`$s.sharing_enabled) { throw 'Sharing enabled' } }
}
function Assert-AnebSharingRestored(`$before, `$after) { }
function Package-Hash(`$pkg, `$name) {
    if (`$pkg -ceq 'com.aneb.probe') { return @{ path = '/data/app/target/base.apk'; sha = '$MockTargetSha' } }
    return @{ path = '/data/app/test/base.apk'; sha = '$MockTestSha' }
}
function Adb(`$name, [string[]]`$arguments, [int]`$budget = 10000) {
    `$cmd = `$arguments -join ' '
    if (`$cmd -ceq 'shell cmd wifi status') { return 'Wifi is connected to "LabWifi" Net ID: 7,' }
    if (`$cmd -ceq 'shell settings get global wifi_on') { return '1' }
    if (`$cmd -ceq 'shell settings get global mobile_data') { return '1' }
    if (`$cmd -ceq 'shell settings get global stay_on_while_plugged_in') { return '0' }
    if (`$cmd -ceq 'shell settings get system pointer_location') { return '$MockPointer' }
    if (`$cmd -ceq 'shell settings get system screen_off_timeout') { return '60000' }
    throw ('Unexpected mock Adb call: ' + `$cmd)
}
"@
    $commonPath = Join-Path $dir 'common.ps1'
    [IO.File]::WriteAllText($commonPath, $mockCommon, [Text.Encoding]::ASCII)
    $commonSha = (Get-FileHash -LiteralPath $commonPath -Algorithm SHA256).Hash.ToLowerInvariant()

    $renderedVerify = $verifyTmplText.Replace('{{CARD_ROOT}}', $dir.Replace('\', '/')).Replace('{{COMMON_SHA256}}', $commonSha)
    $verifyPath = Join-Path $dir 'verify-cleanup-readonly.ps1'
    [IO.File]::WriteAllText($verifyPath, $renderedVerify, [Text.Encoding]::ASCII)

    [IO.File]::WriteAllText((Join-Path $rt 'TERMINAL.json'), ($TerminalData | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    if ($null -ne $BeforeData) {
        [IO.File]::WriteAllText((Join-Path $rt 'before.json'), ($BeforeData | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    }
    if ($null -ne $MainIntent) {
        [IO.File]::WriteAllText((Join-Path $rt 'main-install-intent.json'), ($MainIntent | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    }
    if ($null -ne $TestIntent) {
        [IO.File]::WriteAllText((Join-Path $rt 'test-install-intent.json'), ($TestIntent | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    }
    if ($null -ne $PointerIntent) {
        [IO.File]::WriteAllText((Join-Path $rt 'pointer-intent.json'), ($PointerIntent | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    }
    if ($CreateHotspotIntent) {
        [IO.File]::WriteAllText((Join-Path $rt 'hotspot-start-intent.json'), '{"own_start_attempted":true}', [Text.Encoding]::ASCII)
    }
    if ($CreateCardJson) {
        [IO.File]::WriteAllText((Join-Path $rt 'CARD.json'), '{"format":"test"}', [Text.Encoding]::ASCII)
    }

    $freq = [Diagnostics.Stopwatch]::Frequency
    $deadline = [Diagnostics.Stopwatch]::GetTimestamp() + (60 * $freq)
    $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyPath -ParentDeadlineTicks $deadline -ParentDeadlineFrequency $freq 2>&1
    $code = $LASTEXITCODE
    $icPath = Join-Path $dir 'INDEPENDENT_CLEANUP.json'
    $ic = if (Test-Path -LiteralPath $icPath) { Get-Content -LiteralPath $icPath -Raw | ConvertFrom-Json } else { $null }
    return @{ ExitCode = $code; Cleanup = $ic; Output = ($out -join "`n") }
}

try {
    # Scenario 1: Early-abort without before.json passes cleanly
    $s1 = New-VerifyScenario -Name 'early-abort-clean' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $true
        cleanup_errors = @()
        failure = 'System.Exception:Target business app versionCode required'
    }
    Assert-Card ($s1.ExitCode -eq 0) "early-abort without before.json exits 0 ($($s1.Output))"
    Assert-Card ($s1.Cleanup.all_checked_true -eq $true -and $s1.Cleanup.before_json_present -eq $false -and $s1.Cleanup.early_abort_verified -eq $true) 'early-abort INDEPENDENT_CLEANUP flags set'
    Assert-Card ($s1.Cleanup.checks.phone_idle_no_conflicting_session_vpn_or_projection -eq $true -and $s1.Cleanup.checks.original_hotspot_off_no_start_stop -eq $true -and $s1.Cleanup.checks.original_sharing_disabled -eq $true) 'early-abort runs baseline-free safety checks'

    # Scenario 2: Early-abort with rolled-back intents passes
    $s2 = New-VerifyScenario -Name 'early-abort-intents-ok' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $true
        cleanup_errors = @()
        failure = 'System.Exception:Simulated abort after pointer'
    } -MainIntent @{ before_sha256 = ('a' * 64) } -TestIntent @{ before_sha256 = ('b' * 64) } -PointerIntent @{ before_value = '0' }
    Assert-Card ($s2.ExitCode -eq 0 -and $s2.Cleanup.all_checked_true -eq $true) 'early-abort with rolled-back intents passes'
    Assert-Card ($s2.Cleanup.checks.early_abort_target_restored -eq $true -and $s2.Cleanup.checks.early_abort_test_restored -eq $true -and $s2.Cleanup.checks.early_abort_pointer_restored -eq $true) 'early-abort checks all 3 intent rollbacks'

    # Scenario 3: Early-abort with un-rolled-back target APK fails closed
    $s3 = New-VerifyScenario -Name 'early-abort-bad-target' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $true
        cleanup_errors = @()
        failure = 'System.Exception:Simulated abort'
    } -MainIntent @{ before_sha256 = ('9' * 64) }
    Assert-Card ($s3.ExitCode -eq 2 -and $s3.Cleanup.all_checked_true -eq $false -and $s3.Cleanup.checks.early_abort_target_restored -eq $false) 'early-abort un-restored target fails closed with exit 2'

    # Scenario 4: Missing before.json despite hotspot-start-intent fails closed
    $s4 = New-VerifyScenario -Name 'missing-before-hotspot' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $true
        cleanup_errors = @()
    } -CreateHotspotIntent $true
    Assert-Card ($s4.ExitCode -eq 2 -and $s4.Cleanup.all_checked_true -eq $false) 'missing before.json with hotspot-start-intent fails closed'

    # Scenario 5a & 5b: Missing before.json despite pass=true or clean_state_verified=false fails closed
    $s5a = New-VerifyScenario -Name 'missing-before-pass-true' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $true
        rb_pair_captured = $true
        clean_state_verified = $true
        cleanup_errors = @()
    }
    Assert-Card ($s5a.ExitCode -eq 2 -and $s5a.Cleanup.all_checked_true -eq $false) 'missing before.json with terminal pass=true fails closed'

    $s5b = New-VerifyScenario -Name 'missing-before-unverified' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $false
        cleanup_errors = @('cleanup err')
    }
    Assert-Card ($s5b.ExitCode -eq 2 -and $s5b.Cleanup.all_checked_true -eq $false) 'missing before.json with clean_state_verified=false fails closed'

    # Scenarios 5c, 5d, 5e: Missing before.json despite captured phase, dispatch permit, or CARD.json fails closed
    $s5c = New-VerifyScenario -Name 'missing-before-phases' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $true
        cleanup_errors = @()
        phases = @(@{ condition_id = 'R' })
    }
    Assert-Card ($s5c.ExitCode -eq 2 -and $s5c.Cleanup.all_checked_true -eq $false) 'missing before.json with captured phases fails closed'

    $s5d = New-VerifyScenario -Name 'missing-before-dispatch-permit' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $true
        cleanup_errors = @()
        instrumentation_dispatch_permit_count = 1
    }
    Assert-Card ($s5d.ExitCode -eq 2 -and $s5d.Cleanup.all_checked_true -eq $false) 'missing before.json with instrumentation_dispatch_permit_count>0 fails closed'

    $s5e = New-VerifyScenario -Name 'missing-before-card-json' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $false
        rb_pair_captured = $false
        clean_state_verified = $true
        cleanup_errors = @()
    } -CreateCardJson $true
    Assert-Card ($s5e.ExitCode -eq 2 -and $s5e.Cleanup.all_checked_true -eq $false) 'missing before.json with runtime/CARD.json fails closed'

    # Scenario 6: Normal full run with before.json present passes all 9 checks
    $s6 = New-VerifyScenario -Name 'normal-full-run' -TerminalData @{
        finished_utc = '2026-11-20T00:00:00Z'
        pass = $true
        rb_pair_captured = $true
        clean_state_verified = $true
        cleanup_errors = @()
    } -BeforeData @{
        original_wifi_ssid = 'LabWifi'
        original_wifi_net_id = '7'
        settings = @{ wifi_on = '1'; mobile_data = '1'; stay_on_while_plugged_in = '0' }
        pointer_location = '0'
        screen_off_timeout = '60000'
        prior_target_sha256 = ('a' * 64)
        prior_test_sha256 = ('b' * 64)
        sharing = @(@{ name = 'Ethernet'; sharing_enabled = $false })
    }
    Assert-Card ($s6.ExitCode -eq 0 -and $s6.Cleanup.all_checked_true -eq $true -and $s6.Cleanup.before_json_present -eq $true -and $s6.Cleanup.early_abort_verified -eq $false) 'normal full run with before.json passes'
    Assert-Card ($s6.Cleanup.checks.original_wifi_restored -eq $true -and $s6.Cleanup.checks.original_target_restored -eq $true -and $s6.Cleanup.checks.original_sharing_bindings_restored -eq $true) 'normal full run verifies wifi, APKs, and sharing bindings'
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Output "PASS CONTROLLED_RB_CARD_SCRIPTS count=$passed"
