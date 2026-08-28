[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [ValidateRange(1, 65535)][int]$Port = 18088,
    [ValidateRange(1, 60)][int]$HealthTimeoutSeconds = 10
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

function Stop-AnEbLaunch {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message
    )
    Write-Output "FAIL $Code $Message"
    exit 1
}

$serverProcess = $null
try {
    $rootFull = Get-AnEbFullPath -Path $Root
    Assert-AnEbDirectory -Path $rootFull | Out-Null

    $doctor = Join-Path $PSScriptRoot 'doctor.ps1'
    $doctorOutput = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $doctor -Root $rootFull -Port $Port)
    $doctorCode = $LASTEXITCODE
    $doctorOutput | ForEach-Object { Write-Output $_ }
    if ($doctorCode -ne 0) {
        Stop-AnEbLaunch -Code 'P001_PACKAGE_INTEGRITY' -Message 'doctor did not pass'
    }

    $version = $null
    try {
        $version = Read-AnEbUtf8Strict -Path (Join-Path $rootFull 'VERSION.json') | ConvertFrom-Json
    }
    catch {
        Stop-AnEbLaunch -Code 'P001_PACKAGE_INTEGRITY' -Message 'VERSION.json is invalid'
    }
    if ($version.release_state -eq 'SKELETON_NOT_RELEASE') {
        Stop-AnEbLaunch -Code 'P001_PACKAGE_INTEGRITY' -Message 'skeleton has no launchable artifacts'
    }

    $serverPath = Join-Path $rootFull 'bin\aneb-server.exe'
    Assert-AnEbRegularFile -Path $serverPath | Out-Null
    $healthPath = [string]$version.health_endpoint
    $capabilityPath = [string]$version.capability_endpoint
    $serverArgs = @($version.server_args)
    if ([string]::IsNullOrWhiteSpace($healthPath) -or
        [string]::IsNullOrWhiteSpace($capabilityPath) -or
        $serverArgs.Count -eq 0) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'server launch and capability contract is not admitted'
    }
    if (-not $healthPath.StartsWith('/') -or -not $capabilityPath.StartsWith('/')) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'server endpoints must be relative paths'
    }

    $serverProcess = Start-Process -FilePath $serverPath -ArgumentList $serverArgs -WorkingDirectory $rootFull -PassThru -WindowStyle Hidden
    if ($null -eq $serverProcess) {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'server process was not created'
    }
    Start-Sleep -Milliseconds 200
    $serverProcess.Refresh()
    if ($serverProcess.HasExited) {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'server exited before health check'
    }

    $healthUri = "http://127.0.0.1:$Port$healthPath"
    $capabilityUri = "http://127.0.0.1:$Port$capabilityPath"
    $deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
    $health = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri $healthUri -Method Get -TimeoutSec 2
            if ([int]$health.StatusCode -eq 200) {
                break
            }
        }
        catch {
            Start-Sleep -Milliseconds 200
        }
    }
    if ($null -eq $health -or [int]$health.StatusCode -ne 200) {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'health endpoint did not return HTTP 200'
    }

    $capability = $null
    try {
        $capability = ($health = Invoke-WebRequest -UseBasicParsing -Uri $capabilityUri -Method Get -TimeoutSec 2).Content | ConvertFrom-Json
    }
    catch {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'capability response is not valid JSON'
    }
    foreach ($field in @('schema_version', 'product_version', 'protocol_version', 'claim_scope', 'evidence_mode', 'impairment_layer', 'workload', 'conditions', 'evidence_schema_version', 'score_policy_id')) {
        if ($null -eq $capability.$field) {
            Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message ("capability field missing: " + $field)
        }
    }
    if ($capability.product_version -ne 'prototype-0.1' -or
        $capability.protocol_version -ne 'prototype-stream-0.1' -or
        $capability.claim_scope -ne 'application_end_to_end_to_probe_node' -or
        $capability.evidence_mode -ne 'synthetic_application_impairment' -or
        $capability.impairment_layer -ne 'application' -or
        @($capability.conditions).Count -ne 3) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'capability response is incompatible'
    }

    Write-Output 'ANEB Prototype 0.1 - READY'
    Write-Output ("Node URL: http://127.0.0.1:" + $Port)
    Write-Output 'Android: open ANEB Prototype Mode and enter the displayed node URL'
    Write-Output 'Results: results'
    Write-Output 'Scope: deterministic application-layer synthetic conditions'
    Write-Output 'Press Q and Enter to stop ANEB cleanly.'
    $input = Read-Host
    if ($input -notmatch '^(?i)q$') {
        Write-Output 'Stop action is Q; stopping the owned server before exit.'
    }
    exit 0
}
catch {
    if ($serverProcess) {
        try {
            $serverProcess.Refresh()
            if (-not $serverProcess.HasExited) {
                $serverProcess.CloseMainWindow()
                if (-not $serverProcess.WaitForExit(1000)) {
                    $serverProcess.Kill()
                    $serverProcess.WaitForExit(1000)
                }
            }
        }
        catch {
            Write-Output 'WARN P004_SERVER_START_FAILED owned server cleanup was incomplete'
        }
    }
    if ($_.Exception.Message -match '^FAIL ') {
        Write-Output $_.Exception.Message
    }
    else {
        Write-Output ("FAIL P004_SERVER_START_FAILED " + $_.Exception.Message)
    }
    exit 1
}
