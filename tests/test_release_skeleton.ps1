[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$verify = Join-Path $repo 'tools\verify-package.ps1'
$launcher = Join-Path $repo 'tools\launch.ps1'
$finalizer = Join-Path $repo 'tools\finalize-campaign.ps1'
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('aneb-prototype-0.1-skeleton-test-' + ([Guid]::NewGuid().ToString('N')))
$passed = 0

function Assert-AnEbTest {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw ('ASSERTION_FAILED ' + $Message)
    }
    $script:passed++
    Write-Output ('PASS ' + $Message)
}

function Write-AnEbTestText {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    [System.IO.File]::WriteAllBytes($Path, $encoding.GetBytes($Text))
}

function Invoke-AnEbTool {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $argumentJson = $Arguments | ConvertTo-Json -Compress
    $argumentBase64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($argumentJson))
    $scriptBase64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($ScriptPath))
    $childCommand = @'
$ErrorActionPreference = 'Stop'
$toolPath = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('__SCRIPT__'))
$json = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('__ARGS__'))
$toolArguments = [string[]](ConvertFrom-Json -InputObject $json)
$toolParameters = @{}
$argumentIndex = 0
while ($argumentIndex -lt $toolArguments.Count) {
    $parameterToken = $toolArguments[$argumentIndex]
    if ($parameterToken -notmatch '^-[A-Za-z]') { throw 'invalid test parameter token' }
    $parameterName = $parameterToken.Substring(1)
    $argumentIndex++
    $parameterValues = @()
    while ($argumentIndex -lt $toolArguments.Count -and $toolArguments[$argumentIndex] -notmatch '^-[A-Za-z]') {
        $parameterValues += $toolArguments[$argumentIndex]
        $argumentIndex++
    }
    if ($parameterValues.Count -eq 0) {
        $toolParameters[$parameterName] = $true
    }
    elseif ($parameterValues.Count -eq 1) {
        $toolParameters[$parameterName] = $parameterValues[0]
    }
    else {
        $toolParameters[$parameterName] = [string[]]$parameterValues
    }
}
$toolExitCode = 0
try {
    & $toolPath @toolParameters
    if (-not $?) { $toolExitCode = 1 }
}
catch {
    Write-Error $_
    $toolExitCode = 1
}
exit $toolExitCode
'@
    $childCommand = $childCommand.Replace('__SCRIPT__', $scriptBase64).Replace('__ARGS__', $argumentBase64)
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))
    $output = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encodedCommand 2>&1)
    $code = $LASTEXITCODE
    return [pscustomobject]@{
        Output = [string]::Join([char]10, $output)
        ExitCode = $code
    }
}

function Copy-AnEbSkeletonFile {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    $source = Join-Path $repo ($RelativePath -replace '/', '\')
    $target = Join-Path $PackageRoot ($RelativePath -replace '/', '\')
    $parent = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $source -Destination $target
}

try {
    New-Item -ItemType Directory -Path $tempRoot -ErrorAction Stop | Out-Null
    $packageRoot = Join-Path $tempRoot 'package'
    New-Item -ItemType Directory -Path $packageRoot | Out-Null
    $packageFiles = @(
        'START_ANEB.bat',
        'README_FIRST.md',
        'VERSION.json',
        'SHA256SUMS.txt',
        'contracts/prototype-0.1/capabilities.schema.json',
        'contracts/prototype-0.1/profile-manifest.json',
        'contracts/prototype-0.1/run-record.schema.json',
        'contracts/prototype-0.1/score-policy.json',
        'static/report-template.html',
        'tools/common.ps1',
        'tools/doctor.ps1',
        'tools/finalize-campaign.ps1',
        'tools/launch.ps1',
        'tools/make-package-manifest.ps1',
        'tools/verify-package.ps1'
    )
    foreach ($relative in $packageFiles) {
        Copy-AnEbSkeletonFile -PackageRoot $packageRoot -RelativePath $relative
    }

    $skeletonResult = Invoke-AnEbTool -ScriptPath $verify -Arguments @('-Root', $packageRoot, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($skeletonResult.ExitCode -eq 0) -Message 'package verifier accepts only explicit skeleton mode'
    Assert-AnEbTest -Condition ($skeletonResult.Output -match 'BLOCKED_ARTIFACTS') -Message 'skeleton verifier states that server and APK are absent'
    Assert-AnEbTest -Condition ($skeletonResult.Output -match 'PASS CONTRACT_BINDING') -Message 'skeleton verifier validates the exact four-contract binding'

    $freshPackage = Join-Path $tempRoot 'package-copy'
    Copy-Item -LiteralPath $packageRoot -Destination $freshPackage -Recurse
    $freshResult = Invoke-AnEbTool -ScriptPath (Join-Path $freshPackage 'tools\verify-package.ps1') -Arguments @('-Root', $freshPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($freshResult.ExitCode -eq 0) -Message 'fresh copied package preserves contract binding'

    $missingContractPackage = Join-Path $tempRoot 'package-missing-contract'
    Copy-Item -LiteralPath $packageRoot -Destination $missingContractPackage -Recurse
    Remove-Item -LiteralPath (Join-Path $missingContractPackage 'contracts\prototype-0.1\run-record.schema.json') -Force
    $missingContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $missingContractPackage 'tools\verify-package.ps1') -Arguments @('-Root', $missingContractPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($missingContractResult.ExitCode -ne 0) -Message 'verifier rejects a missing machine contract'
    Assert-AnEbTest -Condition ($missingContractResult.Output -match 'CONTRACT') -Message 'missing contract failure is explicit'

    $extraContractPackage = Join-Path $tempRoot 'package-extra-contract'
    Copy-Item -LiteralPath $packageRoot -Destination $extraContractPackage -Recurse
    Write-AnEbTestText -Path (Join-Path $extraContractPackage 'contracts\prototype-0.1\evidence-schema.json') -Text '{}'
    $extraContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $extraContractPackage 'tools\verify-package.ps1') -Arguments @('-Root', $extraContractPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($extraContractResult.ExitCode -ne 0) -Message 'verifier rejects an undeclared evidence schema artifact'
    Assert-AnEbTest -Condition ($extraContractResult.Output -match 'CONTRACT') -Message 'extra contract failure is explicit'

    $tamperedContractPackage = Join-Path $tempRoot 'package-tampered-contract'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedContractPackage -Recurse
    Write-AnEbTestText -Path (Join-Path $tamperedContractPackage 'contracts\prototype-0.1\profile-manifest.json') -Text ('{"tampered":true}' + [char]10)
    $tamperedContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedContractPackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedContractPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedContractResult.ExitCode -ne 0) -Message 'verifier rejects a tampered contract byte'
    Assert-AnEbTest -Condition ($tamperedContractResult.Output -match 'HASH') -Message 'tampered contract failure is hash-bound'

    $tamperedVersionPackage = Join-Path $tempRoot 'package-tampered-version'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedVersionPackage -Recurse
    $versionText = [System.IO.File]::ReadAllText((Join-Path $tamperedVersionPackage 'VERSION.json'))
    $versionText = $versionText.Replace('44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc', ('0' * 64))
    Write-AnEbTestText -Path (Join-Path $tamperedVersionPackage 'VERSION.json') -Text $versionText
    $tamperedVersionResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedVersionPackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedVersionPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedVersionResult.ExitCode -ne 0) -Message 'verifier rejects a VERSION contract hash drift'
    Assert-AnEbTest -Condition ($tamperedVersionResult.Output -match 'VERSION') -Message 'VERSION hash failure is explicit'

    $tamperedSizePackage = Join-Path $tempRoot 'package-tampered-contract-size'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedSizePackage -Recurse
    $sizeText = [System.IO.File]::ReadAllText((Join-Path $tamperedSizePackage 'VERSION.json'))
    $sizeText = $sizeText.Replace('"profile-manifest.json": 3797', '"profile-manifest.json": 1')
    Write-AnEbTestText -Path (Join-Path $tamperedSizePackage 'VERSION.json') -Text $sizeText
    $tamperedSizeResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedSizePackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedSizePackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedSizeResult.ExitCode -ne 0) -Message 'verifier rejects a VERSION contract size drift'
    Assert-AnEbTest -Condition ($tamperedSizeResult.Output -match 'SIZE') -Message 'VERSION size failure is explicit'

    $tamperedPathPackage = Join-Path $tempRoot 'package-tampered-contract-path'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedPathPackage -Recurse
    $pathText = [System.IO.File]::ReadAllText((Join-Path $tamperedPathPackage 'VERSION.json'))
    $pathText = $pathText.Replace('"profile-manifest.json": "contracts/prototype-0.1/profile-manifest.json"', '"profile-manifest.json": "profile-manifest.json"')
    Write-AnEbTestText -Path (Join-Path $tamperedPathPackage 'VERSION.json') -Text $pathText
    $tamperedPathResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedPathPackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedPathPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedPathResult.ExitCode -ne 0) -Message 'verifier rejects a VERSION contract path drift'
    Assert-AnEbTest -Condition ($tamperedPathResult.Output -match 'PATH') -Message 'VERSION path failure is explicit'

    $releaseResult = Invoke-AnEbTool -ScriptPath $verify -Arguments @('-Root', $packageRoot)
    Assert-AnEbTest -Condition ($releaseResult.ExitCode -ne 0) -Message 'package verifier rejects skeleton as a release'
    Assert-AnEbTest -Condition ($releaseResult.Output -match 'P001_PACKAGE_INTEGRITY') -Message 'release rejection uses stable package code'

    $launchResult = Invoke-AnEbTool -ScriptPath $launcher -Arguments @('-Root', $packageRoot)
    Assert-AnEbTest -Condition ($launchResult.ExitCode -ne 0) -Message 'launcher stops before starting absent artifacts'
    Assert-AnEbTest -Condition ($launchResult.Output -notmatch 'READY') -Message 'launcher cannot print READY for skeleton'

    $batchText = [System.IO.File]::ReadAllText((Join-Path $packageRoot 'START_ANEB.bat'))
    Assert-AnEbTest -Condition ($batchText.Contains('%~dp0')) -Message 'Windows launcher resolves its own relative directory'
    Assert-AnEbTest -Condition ($batchText -notmatch '(?i)[A-Z]:\\') -Message 'Windows launcher has no developer absolute path'

    $campaignId = 'campaign-test-01'
    $input = Join-Path $tempRoot 'campaign-input'
    $output = Join-Path $tempRoot 'campaign-output'
    New-Item -ItemType Directory -Path $input | Out-Null
    New-Item -ItemType Directory -Path $output | Out-Null
    $hashB = -join (1..64 | ForEach-Object { 'b' })
    $hashC = -join (1..64 | ForEach-Object { 'c' })
    $meta = [ordered]@{
        schema_version = 'aneb-prototype-evidence-0.1'
        campaign_id = $campaignId
        campaign_mode = 'quick'
        campaign_status = 'partial'
        started_at_utc = '2026-08-28T00:00:00Z'
        ended_at_utc = '2026-08-28T00:00:01Z'
        claim_scope = 'application_end_to_end_to_probe_node'
        evidence_mode = 'synthetic_application_impairment'
        impairment_layer = 'application'
        score_policy_id = 'rpi-0.1'
        profile = [ordered]@{
            id = 'streaming_text_reference_v0.1'
            version = '0.1'
            sha256 = $hashB
        }
        product = [ordered]@{
            version = 'prototype-0.1'
            commit = (-join (1..40 | ForEach-Object { 'a' }))
        }
        run_plan = [ordered]@{
            planned_runs = 3
            order = @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')
        }
    }
    Write-AnEbTestText -Path (Join-Path $input 'meta.json') -Text (($meta | ConvertTo-Json -Compress -Depth 8) + [Environment]::NewLine)
    $event1 = '{"schema_version":"aneb-prototype-evidence-0.1","campaign_id":"' + $campaignId + '","event_type":"campaign_started"}'
    $event2 = '{"schema_version":"aneb-prototype-evidence-0.1","campaign_id":"' + $campaignId + '","event_type":"campaign_failed"}'
    Write-AnEbTestText -Path (Join-Path $input 'events.jsonl') -Text ($event1 + [char]10 + $event2 + [char]10)

    $runHeader = 'schema_version,campaign_id,run_id,campaign_mode,run_index,condition_id,condition_version,condition_hash,run_status,task_success,attempt_started_at_utc,attempt_ended_at_utc,events_expected,events_received,ttft_ms,completion_ms,stream_span_ms,stream_event_rate_eps,stall_threshold_ms,stall_count,stall_duration_ms,stall_fraction,schedule_hash,terminal_receipt_valid,score_eligible,failure_reason'
    $runRows = @()
    $runIndex = 1
    foreach ($condition in @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')) {
        $values = @(
            'aneb-prototype-run-record-0.1',
            $campaignId,
            ('run-test-' + $runIndex.ToString('00')),
            'quick',
            $runIndex,
            $condition,
            '0.1',
            $hashB,
            'not_started',
            'false',
            '',
            '',
            '120',
            '0',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            $hashC,
            '',
            'false',
            'not_started'
        )
        $runRows += [string]::Join(',', $values)
        $runIndex++
    }
    Write-AnEbTestText -Path (Join-Path $input 'runs.csv') -Text ([string]::Join([char]10, @($runHeader) + $runRows) + [char]10)

    $summaryHeader = 'schema_version,campaign_id,campaign_mode,campaign_status,condition_id,planned_runs,attempted_runs,successful_runs,failed_runs,not_started_runs,success_rate,confidence,median_ttft_ms,min_ttft_ms,max_ttft_ms,median_completion_ms,min_completion_ms,max_completion_ms,median_stream_event_rate_eps,median_stall_count,median_stall_duration_ms,median_stall_fraction,rpi,rpi_policy_id,rpi_null_reason'
    $summaryRows = @()
    foreach ($condition in @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')) {
        $values = @(
            'aneb-prototype-evidence-0.1',
            $campaignId,
            'quick',
            'partial',
            $condition,
            '1',
            '0',
            '0',
            '0',
            '1',
            '',
            'NONE',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            'rpi-0.1',
            'campaign_incomplete'
        )
        $summaryRows += [string]::Join(',', $values)
    }
    Write-AnEbTestText -Path (Join-Path $input 'summary.csv') -Text ([string]::Join([char]10, @($summaryHeader) + $summaryRows) + [char]10)
    Write-AnEbTestText -Path (Join-Path $input 'run.log') -Text ('2026-08-28T00:00:00Z INFO finalizer campaign_started' + [char]10)
    Write-AnEbTestText -Path (Join-Path $input 'report.html') -Text '<!doctype html><html><body><h1>offline campaign report campaign-test-01</h1></body></html>'

    $finalizeResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $input,
        '-OutputRoot', $output,
        '-CampaignId', $campaignId,
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($finalizeResult.ExitCode -eq 0) -Message 'finalizer publishes a real partial campaign fixture'
    $published = Join-Path $output $campaignId
    $publishedFiles = @(Get-ChildItem -LiteralPath $published -File -Force)
    Assert-AnEbTest -Condition ($publishedFiles.Count -eq 7) -Message 'published campaign contains exactly seven files'
    $manifest = Get-Content -LiteralPath (Join-Path $published 'manifest.json') -Raw | ConvertFrom-Json
    Assert-AnEbTest -Condition (@($manifest.artifacts).Count -eq 6) -Message 'manifest lists six non-self artifacts'
    Assert-AnEbTest -Condition (-not (@($manifest.artifacts.path) -contains 'manifest.json')) -Message 'manifest does not self-reference'
    $report = Get-Content -LiteralPath (Join-Path $published 'report.html') -Raw
    Assert-AnEbTest -Condition ($report -notmatch '(?i)https?://|<script') -Message 'generated report is offline-only'
    Assert-AnEbTest -Condition ($report -match 'campaign-test-01') -Message 'generated report carries actual campaign identity'

    $collisionResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $input,
        '-OutputRoot', $output,
        '-CampaignId', $campaignId,
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($collisionResult.ExitCode -ne 0) -Message 'finalizer rejects publication collision'
    Assert-AnEbTest -Condition ($collisionResult.Output -match 'already exists') -Message 'collision failure is explicit and no-clobber'

    $badInput = Join-Path $tempRoot 'campaign-input-extra'
    Copy-Item -LiteralPath $input -Destination $badInput -Recurse
    Write-AnEbTestText -Path (Join-Path $badInput 'unexpected.txt') -Text 'not part of evidence'
    $badOutput = Join-Path $tempRoot 'campaign-output-extra'
    New-Item -ItemType Directory -Path $badOutput | Out-Null
    $badResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $badInput,
        '-OutputRoot', $badOutput,
        '-CampaignId', 'campaign-test-02',
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($badResult.ExitCode -ne 0) -Message 'finalizer rejects an extra input file before publication'
    Assert-AnEbTest -Condition (-not (Test-Path -LiteralPath (Join-Path $badOutput 'campaign-test-02'))) -Message 'rejected input creates no accepted campaign'

    Write-Output ("PASS RELEASE_SKELETON_TESTS count=" + $passed)
    exit 0
}
catch {
    Write-Output ('FAIL RELEASE_SKELETON_TESTS ' + $_.Exception.Message)
    exit 1
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
