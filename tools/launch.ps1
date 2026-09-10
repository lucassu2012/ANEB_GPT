[CmdletBinding()]
param(
    [string]$Root,
    [ValidateRange(1, 65535)][int]$Port = 18088,
    [ValidateRange(1, 60)][int]$HealthTimeoutSeconds = 10,
    [switch]$RequireExternalAdmission,
    [string]$AdmissionReceiptPath = '',
    [string]$ExpectedAdmissionReceiptSha256 = '',
    [string]$PackageZipPath = '',
    [switch]$ExitAfterReady
)

$ErrorActionPreference = 'Stop'
# Explicit UTF-8 console output also works on an English Windows installation.
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
. (Join-Path $PSScriptRoot 'common.ps1')

function Stop-AnEbLaunch {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message
    )
    throw ("FAIL " + $Code + " " + $Message)
}

function Stop-AnEbOwnedServer {
    param([Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process)
    try {
        $Process.Refresh()
        if (-not $Process.HasExited) {
            try {
                $null = $Process.CloseMainWindow()
            }
            catch {
                # Console-only servers may not expose a window; the bounded kill below is still owned.
            }
            if (-not $Process.WaitForExit(1000)) {
                $Process.Kill()
                if (-not $Process.WaitForExit(1000)) {
                    throw 'owned server did not exit after the bounded stop'
                }
            }
        }
        $Process.Refresh()
        if (-not $Process.HasExited) {
            throw 'owned server remains alive after cleanup'
        }
        return $true
    }
    catch {
        return $false
    }
}

function Invoke-AnEbLocalHttpGet {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )
    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = 'GET'
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = $TimeoutSeconds * 1000
    $request.ReadWriteTimeout = $TimeoutSeconds * 1000
    $response = $null
    $stream = $null
    $reader = $null
    try {
        $response = $request.GetResponse()
        $stream = $response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::UTF8, $true)
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = $reader.ReadToEnd()
        }
    }
    finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        }
        elseif ($null -ne $stream) {
            $stream.Dispose()
        }
        if ($null -ne $response) {
            $response.Dispose()
        }
    }
}

function Assert-AnEbJsonObjectFields {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [System.Array]) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message $Message
    }
    $actual = @($Value.PSObject.Properties | ForEach-Object { [string]$_.Name })
    $missing = @($Expected | Where-Object { -not ($actual -ccontains [string]$_) })
    $unexpected = @($actual | Where-Object { -not ($Expected -ccontains [string]$_) })
    if ($actual.Count -ne $Expected.Count -or $missing.Count -ne 0 -or $unexpected.Count -ne 0) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message $Message
    }
}

function Assert-AnEbJsonStringEquals {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Expected,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $property.Value -isnot [string] -or
        -not [string]::Equals([string]$property.Value, $Expected, [System.StringComparison]::Ordinal)) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message $Message
    }
}

function Assert-AnEbJsonIntegerEquals {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int64]$Expected,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $property = $Object.PSObject.Properties[$Name]
    $value = if ($null -eq $property) { $null } else { $property.Value }
    if ($null -eq $property -or $value -is [bool] -or
        (($value -isnot [int]) -and ($value -isnot [long]) -and ($value -isnot [decimal])) -or
        [int64]$value -ne $Expected) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message $Message
    }
}

function Assert-AnEbServerInfoResponse {
    param(
        [Parameter(Mandatory = $true)]$Response,
        [Parameter(Mandatory = $true)]$Version
    )
    if ($null -eq $Response -or [int]$Response.StatusCode -ne 200) {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'serverinfo endpoint did not return HTTP 200'
    }
    $serverInfo = $null
    try {
        $serverInfo = ConvertFrom-Json -InputObject ([string]$Response.Content)
    }
    catch {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'serverinfo response is not valid JSON'
    }
    Assert-AnEbJsonObjectFields -Value $serverInfo -Expected @(
        'version',
        'srv_ts_us',
        'anchor_wall_unix_ns',
        'uptime_s',
        'goos',
        'goarch',
        'h3_enabled',
        'tcp_slow_start_after_idle',
        'congestion_control'
    ) -Message 'serverinfo response fields are not exact'
    if ($null -eq $Version.PSObject.Properties['server_version'] -or
        $Version.server_version -isnot [string] -or
        [string]::IsNullOrWhiteSpace([string]$Version.server_version)) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'VERSION server_version is missing'
    }
    Assert-AnEbJsonStringEquals -Object $serverInfo -Name 'version' -Expected ([string]$Version.server_version) -Message 'serverinfo response version is not the packaged server version'
    foreach ($name in @('srv_ts_us', 'anchor_wall_unix_ns', 'uptime_s')) {
        $property = $serverInfo.PSObject.Properties[$name]
        $value = if ($null -eq $property) { $null } else { $property.Value }
        if ($null -eq $property -or $value -is [bool] -or
            (($value -isnot [int]) -and ($value -isnot [long]) -and ($value -isnot [decimal])) -or
            [int64]$value -lt 0) {
            Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message ('serverinfo ' + $name + ' is not a nonnegative integer')
        }
    }
    foreach ($name in @('goos', 'goarch', 'tcp_slow_start_after_idle', 'congestion_control')) {
        $property = $serverInfo.PSObject.Properties[$name]
        if ($null -eq $property -or $property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message ('serverinfo ' + $name + ' is missing')
        }
    }
    $h3Property = $serverInfo.PSObject.Properties['h3_enabled']
    if ($null -eq $h3Property -or $h3Property.Value -isnot [bool] -or $h3Property.Value) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'serverinfo h3_enabled is not exact false'
    }
}

function Assert-AnEbCapabilityResponse {
    param(
        [Parameter(Mandatory = $true)]$Capability,
        [Parameter(Mandatory = $true)]$Version,
        [Parameter(Mandatory = $true)][string]$ServerPath
    )
    $topLevelFields = @(
        'schema_version',
        'product_version',
        'protocol_version',
        'server_version',
        'server_binary_sha256',
        'claim_scope',
        'evidence_mode',
        'impairment_layer',
        'profile_manifest_sha256',
        'workload',
        'conditions',
        'evidence_schema_version',
        'score_policy_id',
        'terminal_receipt_version'
    )
    Assert-AnEbJsonObjectFields -Value $Capability -Expected $topLevelFields -Message 'capability response fields are not exact'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'schema_version' -Expected 'aneb-prototype-capabilities-0.1' -Message 'capability schema version is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'product_version' -Expected 'prototype-0.1' -Message 'capability product version is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'protocol_version' -Expected 'prototype-stream-0.1' -Message 'capability protocol version is not supported'
    if ($null -eq $Version.PSObject.Properties['server_version'] -or
        $Version.server_version -isnot [string] -or
        [string]::IsNullOrWhiteSpace([string]$Version.server_version)) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'VERSION server_version is missing'
    }
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'server_version' -Expected ([string]$Version.server_version) -Message 'capability server version is not the packaged version'
    $serverHash = Get-AnEbSha256File -Path $ServerPath
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'server_binary_sha256' -Expected $serverHash -Message 'capability server binary identity does not match the packaged binary'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'claim_scope' -Expected 'application_end_to_end_to_probe_node' -Message 'capability claim scope is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'evidence_mode' -Expected 'synthetic_application_impairment' -Message 'capability evidence mode is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'impairment_layer' -Expected 'application' -Message 'capability impairment layer is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'profile_manifest_sha256' -Expected '44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc' -Message 'capability profile identity is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'evidence_schema_version' -Expected 'aneb-prototype-evidence-0.1' -Message 'capability evidence schema is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'score_policy_id' -Expected 'rpi-0.1' -Message 'capability score policy is not supported'
    Assert-AnEbJsonStringEquals -Object $Capability -Name 'terminal_receipt_version' -Expected 'prototype-terminal-receipt-0.1' -Message 'capability terminal receipt is not supported'

    $workloadProperty = $Capability.PSObject.Properties['workload']
    if ($null -eq $workloadProperty) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'capability workload is missing'
    }
    $workload = $workloadProperty.Value
    Assert-AnEbJsonObjectFields -Value $workload -Expected @('id', 'version', 'content_event_count') -Message 'capability workload fields are not exact'
    Assert-AnEbJsonStringEquals -Object $workload -Name 'id' -Expected 'streaming_text_reference_v0.1' -Message 'capability workload id is not supported'
    Assert-AnEbJsonStringEquals -Object $workload -Name 'version' -Expected '0.1' -Message 'capability workload version is not supported'
    Assert-AnEbJsonIntegerEquals -Object $workload -Name 'content_event_count' -Expected 120 -Message 'capability workload event count is not supported'

    $conditionsProperty = $Capability.PSObject.Properties['conditions']
    if ($null -eq $conditionsProperty -or $conditionsProperty.Value -isnot [System.Array] -or $conditionsProperty.Value.Count -ne 3) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'capability condition list is not exact'
    }
    $expectedConditions = @(
        [pscustomobject]@{ Id = 'baseline_v0.1'; NominalIntervalMs = 50; ScheduleSha256 = '46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e' },
        [pscustomobject]@{ Id = 'slow_v0.1'; NominalIntervalMs = 125; ScheduleSha256 = 'b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062' },
        [pscustomobject]@{ Id = 'unstable_v0.1'; NominalIntervalMs = 65; ScheduleSha256 = 'd11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58' }
    )
    for ($index = 0; $index -lt $expectedConditions.Count; $index++) {
        $condition = $conditionsProperty.Value[$index]
        $expected = $expectedConditions[$index]
        Assert-AnEbJsonObjectFields -Value $condition -Expected @('id', 'version', 'nominal_interval_ms', 'schedule_sha256') -Message 'capability condition fields are not exact'
        Assert-AnEbJsonStringEquals -Object $condition -Name 'id' -Expected $expected.Id -Message 'capability condition id/order is not supported'
        Assert-AnEbJsonStringEquals -Object $condition -Name 'version' -Expected '0.1' -Message 'capability condition version is not supported'
        Assert-AnEbJsonIntegerEquals -Object $condition -Name 'nominal_interval_ms' -Expected $expected.NominalIntervalMs -Message 'capability condition interval is not supported'
        Assert-AnEbJsonStringEquals -Object $condition -Name 'schedule_sha256' -Expected $expected.ScheduleSha256 -Message 'capability condition schedule is not supported'
    }
}

$serverProcess = $null
$exitCode = 1
$failureMessage = $null
$cleanupFailed = $false
try {
    # Windows PowerShell initializes the script directory after parameter defaults.
    if (-not $PSBoundParameters.ContainsKey('Root')) {
        $Root = Split-Path -Parent $PSScriptRoot
    }
    $rootFull = Get-AnEbFullPath -Path $Root
    Assert-AnEbDirectory -Path $rootFull | Out-Null

    $doctor = Join-Path $PSScriptRoot 'doctor.ps1'
    $doctorArguments = @('-Root', $rootFull, '-Port', $Port)
    if ($RequireExternalAdmission) {
        $doctorArguments += '-RequireExternalAdmission'
    }
    if (-not [string]::IsNullOrWhiteSpace($AdmissionReceiptPath)) {
        $doctorArguments += @('-AdmissionReceiptPath', $AdmissionReceiptPath)
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedAdmissionReceiptSha256)) {
        $doctorArguments += @('-ExpectedAdmissionReceiptSha256', $ExpectedAdmissionReceiptSha256)
    }
    if (-not [string]::IsNullOrWhiteSpace($PackageZipPath)) {
        $doctorArguments += @('-PackageZipPath', $PackageZipPath)
    }
    $doctorOutput = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $doctor @doctorArguments)
    $doctorCode = $LASTEXITCODE
    $doctorOutput | ForEach-Object { Write-Output $_ }
    if ($doctorCode -ne 0) {
        Stop-AnEbLaunch -Code 'P001_PACKAGE_INTEGRITY' -Message 'doctor did not pass'
    }
    $lanAddressLines = @($doctorOutput | ForEach-Object { [string]$_ } | Where-Object { $_ -cmatch '^PASS LAN_ADDRESSES .+$' })
    if ($lanAddressLines.Count -ne 1) {
        Stop-AnEbLaunch -Code 'P005_NO_LAN_ADDRESS' -Message 'doctor did not report one usable LAN address set'
    }
    $lanAddresses = @($lanAddressLines[0].Substring('PASS LAN_ADDRESSES '.Length).Split(','))
    if ($lanAddresses.Count -eq 0) {
        Stop-AnEbLaunch -Code 'P005_NO_LAN_ADDRESS' -Message 'doctor did not report a usable LAN IPv4 address'
    }
    foreach ($lanAddress in $lanAddresses) {
        $parsedLanAddress = $null
        if (-not [System.Net.IPAddress]::TryParse($lanAddress, [ref]$parsedLanAddress) -or
            $parsedLanAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or
            [System.Net.IPAddress]::IsLoopback($parsedLanAddress) -or
            $parsedLanAddress.ToString() -cne $lanAddress) {
            Stop-AnEbLaunch -Code 'P005_NO_LAN_ADDRESS' -Message 'doctor reported an invalid LAN IPv4 address'
        }
    }

    # Metadata only affects guidance; admitted addresses remain the source of URLs.
    # Legacy doctor output is usable but cannot establish a physical recommendation.
    $lanCandidates = @($lanAddresses | ForEach-Object { [pscustomobject]@{ Address = $_; Kind = 'advanced' } })
    $candidateLines = @($doctorOutput | Where-Object { [string]$_ -cmatch '^INFO LAN_CANDIDATES .+$' })
    if ($candidateLines.Count -eq 1) {
        try {
            $metadata = ConvertFrom-Json -InputObject (([string]$candidateLines[0]).Substring('INFO LAN_CANDIDATES '.Length))
            foreach ($candidate in $lanCandidates) {
                $match = @($metadata | Where-Object { $_.Address -ceq $candidate.Address })
                if ($match.Count -eq 1 -and $match[0].Kind -cin @('wifi', 'ethernet', 'advanced')) {
                    $candidate.Kind = $match[0].Kind
                }
            }
        }
        catch { } # An unavailable hint never promotes an unknown interface.
    }
    $lanCandidates = @($lanCandidates | Sort-Object @{ Expression = { switch ($_.Kind) { 'wifi' { 0 } 'ethernet' { 1 } default { 2 } } } }, Address)

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
    [string[]]$serverArgs = @($version.server_args)
    if ([string]::IsNullOrWhiteSpace($healthPath) -or
        [string]::IsNullOrWhiteSpace($capabilityPath) -or
        $serverArgs.Count -eq 0) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'server launch and capability contract is not admitted'
    }
    if (-not $healthPath.StartsWith('/') -or -not $capabilityPath.StartsWith('/')) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'server endpoints must be relative paths'
    }
    if (-not [string]::Equals($healthPath, '/api/v1/serverinfo', [System.StringComparison]::Ordinal) -or
        -not [string]::Equals($capabilityPath, '/api/v1/prototype/capabilities', [System.StringComparison]::Ordinal)) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'server endpoints are not the frozen Prototype routes'
    }
    $addrIndexes = @()
    for ($argumentIndex = 0; $argumentIndex -lt $serverArgs.Count; $argumentIndex++) {
        if ($serverArgs[$argumentIndex] -ceq '-addr') {
            $addrIndexes += $argumentIndex
        }
    }
    if ($addrIndexes.Count -ne 1 -or $addrIndexes[0] -ge ($serverArgs.Count - 1)) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'server_args must contain exactly one addr and value'
    }
    $addrValue = $serverArgs[$addrIndexes[0] + 1]
    if ($addrValue -cne (':' + $Port) -and $addrValue -cne ('0.0.0.0:' + $Port)) {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'server addr must listen on every interface at the launcher port'
    }
    if (-not ($serverArgs -ccontains '-prototype-only')) {
        $serverArgs = [string[]]($serverArgs + @('-prototype-only'))
    }

    $serverProcess = Start-Process -FilePath $serverPath -ArgumentList $serverArgs -WorkingDirectory $rootFull -PassThru -WindowStyle Hidden
    if ($null -eq $serverProcess) {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'server process was not created'
    }
    Write-Output ("OWNED_SERVER_PID " + $serverProcess.Id)
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
            $health = Invoke-AnEbLocalHttpGet -Uri $healthUri -TimeoutSeconds 2
        }
        catch {
            Start-Sleep -Milliseconds 200
            continue
        }
        if ([int]$health.StatusCode -eq 200) {
            Assert-AnEbServerInfoResponse -Response $health -Version $version
            break
        }
    }
    if ($null -eq $health -or [int]$health.StatusCode -ne 200) {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'health endpoint did not return HTTP 200'
    }

    $capabilityText = $null
    try {
        $capabilityResponse = Invoke-AnEbLocalHttpGet -Uri $capabilityUri -TimeoutSeconds 2
    }
    catch {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'capability endpoint request failed'
    }
    try {
        $capabilityText = [string]$capabilityResponse.Content
        $capability = ConvertFrom-Json -InputObject $capabilityText
    }
    catch {
        Stop-AnEbLaunch -Code 'P007_CONTRACT_MISMATCH' -Message 'capability response is not valid JSON'
    }
    if ($null -eq $capabilityResponse -or [int]$capabilityResponse.StatusCode -ne 200) {
        Stop-AnEbLaunch -Code 'P004_SERVER_START_FAILED' -Message 'capability endpoint did not return HTTP 200'
    }
    Assert-AnEbCapabilityResponse -Capability $capability -Version $version -ServerPath $serverPath

    Get-AnEbReadyGuide -Candidates $lanCandidates -Port $Port -ResultsDirectory (Join-Path $rootFull 'results')
    if (-not $ExitAfterReady) {
        $input = Read-Host
        if ($input -notmatch '^(?i)q$') {
            Write-Output 'Stop action is Q; stopping the owned server before exit.'
        }
    }
    $exitCode = 0
}
catch {
    $failureMessage = if ($_.Exception.Message -match '^FAIL ') { $_.Exception.Message } else { 'FAIL P004_SERVER_START_FAILED launch failed before readiness' }
}
finally {
    if ($null -ne $serverProcess) {
        $cleanupFailed = -not (Stop-AnEbOwnedServer -Process $serverProcess)
    }
}

if ($cleanupFailed) {
    $exitCode = 1
    if ($null -eq $failureMessage) {
        $failureMessage = 'FAIL P004_SERVER_START_FAILED owned server cleanup was incomplete'
    }
    else {
        Write-Output 'FAIL P004_SERVER_START_FAILED owned server cleanup was incomplete'
    }
}
if ($null -ne $failureMessage) {
    Write-Output $failureMessage
}
exit $exitCode
