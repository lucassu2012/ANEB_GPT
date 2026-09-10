[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$unicodePathLabel = [string][char]0x4E2D + [char]0x6587
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('ANEB G3 launcher ' + $unicodePathLabel + ' ' + [Guid]::NewGuid().ToString('N'))
$passed = 0

function Assert-AnEbG3Test {
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

function Write-AnEbG3Utf8 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    [System.IO.File]::WriteAllBytes($Path, $encoding.GetBytes($Text))
}

function Get-AnEbG3FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Invoke-AnEbG3Tool {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $output = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1)
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = [string]::Join([char]10, @($output | ForEach-Object { [string]$_ }))
    }
}

function New-AnEbG3LaunchFixture {
    param(
        [Parameter(Mandatory = $true)][string]$FixturePath,
        [Parameter(Mandatory = $true)][string]$ServerBinary,
        [Parameter(Mandatory = $true)][int]$Port,
        [string]$ServerInfoEndpoint = '/api/v1/serverinfo',
        [string]$CapabilityEndpoint = '/api/v1/prototype/capabilities',
        [bool]$IncludePrototypeOnly = $true,
        [string[]]$LanAddresses = @('192.0.2.10', '198.51.100.20'),
        [string]$LanMetadata = '',
        [string[]]$ServerArguments
    )
    New-Item -ItemType Directory -Path $FixturePath | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $FixturePath 'tools') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $FixturePath 'bin') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $FixturePath 'results') | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'START_ANEB.bat') -Destination (Join-Path $FixturePath 'START_ANEB.bat')
    Copy-Item -LiteralPath (Join-Path $repo 'tools\launch.ps1') -Destination (Join-Path $FixturePath 'tools\launch.ps1')
    Copy-Item -LiteralPath (Join-Path $repo 'tools\common.ps1') -Destination (Join-Path $FixturePath 'tools\common.ps1')
    Copy-Item -LiteralPath $ServerBinary -Destination (Join-Path $FixturePath 'bin\aneb-server.exe')
    $lanAddressText = [string]::Join(',', $LanAddresses)
    Write-AnEbG3Utf8 -Path (Join-Path $FixturePath 'tools\doctor.ps1') -Text (@'
param(
    [string]$Root,
    [int]$Port,
    [string]$AdmissionReceiptPath,
    [string]$ExpectedAdmissionReceiptSha256,
    [switch]$RequireExternalAdmission,
    [string]$PackageZipPath
)
$invocation = [ordered]@{
    require_external_admission = [bool]$RequireExternalAdmission
    admission_receipt_path = [string]$AdmissionReceiptPath
    expected_admission_receipt_sha256 = [string]$ExpectedAdmissionReceiptSha256
    package_zip_path = [string]$PackageZipPath
}
[System.IO.File]::WriteAllText(
    (Join-Path $Root 'doctor-invocation.json'),
    (($invocation | ConvertTo-Json -Compress) + [char]10),
    (New-Object System.Text.UTF8Encoding($false, $true))
)
Write-Output 'PASS TEST_ONLY_DOCTOR_STUB'
Write-Output 'PASS LAN_ADDRESSES __LAN_ADDRESSES__'
if ('__LAN_METADATA__') { Write-Output 'INFO LAN_CANDIDATES __LAN_METADATA__' }
exit 0
'@).Replace('__LAN_ADDRESSES__', $lanAddressText).Replace('__LAN_METADATA__', $LanMetadata)
    $serverArguments = if ($PSBoundParameters.ContainsKey('ServerArguments')) {
        @($ServerArguments)
    }
    else {
        @('-addr', (':' + $Port))
    }
    if ($IncludePrototypeOnly) {
        $serverArguments += '-prototype-only'
    }
    $serverArguments += @('-data', './results')
    $version = [ordered]@{
        release_state = 'RELEASE_CANDIDATE'
        server_version = 'aneb-server/0.1.0'
        health_endpoint = $ServerInfoEndpoint
        capability_endpoint = $CapabilityEndpoint
        server_args = $serverArguments
    }
    Write-AnEbG3Utf8 -Path (Join-Path $FixturePath 'VERSION.json') -Text (($version | ConvertTo-Json -Compress -Depth 8) + [char]10)
}

function New-AnEbG3SkeletonFixture {
    param([Parameter(Mandatory = $true)][string]$FixturePath)
    New-Item -ItemType Directory -Path $FixturePath | Out-Null
    $files = @(
        'START_ANEB.bat',
        'README_FIRST.md',
        'VERSION.json',
        'SHA256SUMS.txt',
        'static/report-template.html',
        'tools/common.ps1',
        'tools/doctor.ps1',
        'tools/finalize-campaign.ps1',
        'tools/finalize-verified-campaign.ps1',
        'tools/launch.ps1',
        'tools/make-package-manifest.ps1',
        'tools/verify-evidence.ps1',
        'tools/verify-package.ps1'
    )
    foreach ($relative in $files) {
        $source = Join-Path $repo ($relative -replace '/', '\')
        $target = Join-Path $FixturePath ($relative -replace '/', '\')
        $targetParent = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $targetParent -PathType Container)) {
            New-Item -ItemType Directory -Path $targetParent -Force | Out-Null
        }
        Copy-Item -LiteralPath $source -Destination $target
    }
    foreach ($name in @('capabilities.schema.json', 'profile-manifest.json', 'run-record.schema.json', 'score-policy.json')) {
        $contractDirectory = Join-Path $FixturePath 'contracts'
        if (-not (Test-Path -LiteralPath $contractDirectory -PathType Container)) {
            New-Item -ItemType Directory -Path $contractDirectory | Out-Null
        }
        $sourceText = [System.IO.File]::ReadAllText((Join-Path $repo ('contracts\prototype-0.1\' + $name)))
        $sourceText = $sourceText.Replace(([string][char]13 + [char]10), [string][char]10)
        Write-AnEbG3Utf8 -Path (Join-Path $contractDirectory $name) -Text $sourceText
    }
}

try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $serverBinary = Join-Path $tempRoot 'aneb-server.exe'
    Push-Location (Join-Path $repo 'server')
    try {
        # Go writes normal cold-cache download progress to stderr. Windows
        # PowerShell 5.1 must judge the native exit code, not terminate on it.
        $buildErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $buildOutput = @(& go build -buildvcs=false -trimpath -o $serverBinary . 2>&1)
        $buildExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $buildErrorPreference
        Pop-Location
    }
    Assert-AnEbG3Test -Condition ($buildExitCode -eq 0 -and (Test-Path -LiteralPath $serverBinary -PathType Leaf)) -Message ('actual Prototype server builds for launcher test output=' + [string]::Join([char]10, $buildOutput))

    $port = Get-AnEbG3FreePort
    $fixture = Join-Path $tempRoot ('fresh ' + $unicodePathLabel + ' package')
    New-AnEbG3LaunchFixture -FixturePath $fixture -ServerBinary $serverBinary -Port $port
    # Exercise the shipped entrypoint without -Root, from outside the package.
    $entryCommand = '""' + (Join-Path $fixture 'START_ANEB.bat') + '" -Port ' + $port + ' -HealthTimeoutSeconds 5 -ExitAfterReady"'
    $entryProcess = New-Object System.Diagnostics.Process
    $entryProcess.StartInfo.FileName = 'cmd.exe'
    $entryProcess.StartInfo.Arguments = '/d /s /c ' + $entryCommand
    $entryProcess.StartInfo.WorkingDirectory = $repo
    $entryProcess.StartInfo.UseShellExecute = $false
    $entryProcess.StartInfo.CreateNoWindow = $true
    $entryProcess.StartInfo.RedirectStandardOutput = $true
    $entryProcess.StartInfo.RedirectStandardError = $true
    $entryProcess.StartInfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $entryProcess.StartInfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    try {
        $null = $entryProcess.Start()
        $entryStdout = $entryProcess.StandardOutput.ReadToEndAsync()
        $entryStderr = $entryProcess.StandardError.ReadToEndAsync()
        if (-not $entryProcess.WaitForExit(20000)) {
            # This PID is the cmd process created above, never an unrelated server.
            $null = & taskkill.exe /PID ([string]$entryProcess.Id) /T /F 2>&1
            $null = $entryProcess.WaitForExit(5000)
            throw 'default BAT launcher exceeded the 20-second test deadline'
        }
        if (-not $entryStdout.Wait(5000) -or -not $entryStderr.Wait(5000)) {
            throw 'default BAT launcher output did not close after exit'
        }
        $entryText = $entryStdout.Result + $entryStderr.Result
        $entryText = $entryText.Replace("`r`n", "`n")
        $entryExitCode = $entryProcess.ExitCode
    }
    finally {
        $entryProcess.Dispose()
    }
    Assert-AnEbG3Test -Condition ($entryExitCode -eq 0 -and $entryText -match 'ANEB Prototype 0\.1 - READY') -Message ('shipped BAT resolves its default package root in Windows PowerShell output=' + $entryText)
    Assert-AnEbG3Test -Condition ($entryText -match ('(?m)^Results: ' + [regex]::Escape((Join-Path ([System.IO.Path]::GetFullPath($fixture)) 'results')) + '$')) -Message 'default BAT root is the package directory, not the caller working directory'
    $entryPidMatch = [regex]::Match($entryText, '(?m)^OWNED_SERVER_PID ([0-9]+)')
    Assert-AnEbG3Test -Condition ($entryPidMatch.Success -and -not (Get-Process -Id ([int]$entryPidMatch.Groups[1].Value) -ErrorAction SilentlyContinue)) -Message 'default BAT launch cleans up its owned server'
    $result = Invoke-AnEbG3Tool -ScriptPath (Join-Path $fixture 'tools\launch.ps1') -Arguments @(
        '-Root', $fixture,
        '-Port', [string]$port,
        '-HealthTimeoutSeconds', '5',
        '-ExitAfterReady'
    )
    Assert-AnEbG3Test -Condition ($result.ExitCode -eq 0 -and $result.Output -match 'ANEB Prototype 0\.1 - READY') -Message ('launcher reaches READY through canonical serverinfo and prototype capabilities routes output=' + $result.Output)
    $nodeUrls = @([regex]::Matches($result.Output, 'http://(?:[0-9]+\.){3}[0-9]+:[0-9]+') | ForEach-Object { $_.Value })
    Assert-AnEbG3Test -Condition ($nodeUrls.Count -eq 2 -and
        $nodeUrls -ccontains ('http://192.0.2.10:' + $port) -and
        $nodeUrls -ccontains ('http://198.51.100.20:' + $port) -and
        $nodeUrls -notcontains ('http://127.0.0.1:' + $port)) -Message ('launcher preserves doctor-admitted LAN URLs and never presents loopback to the phone output=' + $result.Output)
    $readyChinese = [string][char]0x5DF2 + [char]0x5C31 + [char]0x7EEA
    Assert-AnEbG3Test -Condition ($entryText.Contains($readyChinese) -and $result.Output.Contains($readyChinese)) -Message 'Chinese Ready survives shipped BAT and Windows PowerShell UTF-8 output'
    Assert-AnEbG3Test -Condition ($result.Output -match ('(?m)^Results: ' + [regex]::Escape((Join-Path ([System.IO.Path]::GetFullPath($fixture)) 'results')) + '$') -and
        $result.Output -notmatch '(?m)^Results: results$') -Message ('launcher prints the exact absolute result root output=' + $result.Output)

    $hintPort = Get-AnEbG3FreePort
    $hintFixture = Join-Path $tempRoot 'wifi-priority-launch-package'
    New-AnEbG3LaunchFixture -FixturePath $hintFixture -ServerBinary $serverBinary -Port $hintPort -LanAddresses @('192.0.2.1', '192.0.2.20', '192.0.2.30') -LanMetadata '[{"Address":"192.0.2.1","Kind":"advanced"},{"Address":"192.0.2.20","Kind":"ethernet"},{"Address":"192.0.2.30","Kind":"wifi"},{"Address":"198.51.100.99","Kind":"wifi"}]'
    $hintResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $hintFixture 'tools\launch.ps1') -Arguments @('-Root', $hintFixture, '-Port', [string]$hintPort, '-ExitAfterReady')
    $hintUrls = @([regex]::Matches($hintResult.Output, 'http://(?:[0-9]+\.){3}[0-9]+:[0-9]+') | ForEach-Object { $_.Value })
    Assert-AnEbG3Test -Condition ($hintResult.ExitCode -eq 0 -and $hintUrls.Count -eq 3 -and $hintUrls[0] -ceq ('http://192.0.2.30:' + $hintPort) -and $hintUrls[2] -ceq ('http://192.0.2.1:' + $hintPort)) -Message ('real launcher presents Wi-Fi first, virtual last, and ignores unadmitted hint addresses output=' + $hintResult.Output)

    $strictPort = Get-AnEbG3FreePort
    $strictFixture = Join-Path $tempRoot 'strict-admission-launch-package'
    New-AnEbG3LaunchFixture -FixturePath $strictFixture -ServerBinary $serverBinary -Port $strictPort
    $strictReceipt = Join-Path $tempRoot 'strict-admission.json'
    $strictZip = Join-Path $tempRoot 'ANEB-Prototype-0.1-rc.test-windows-x64.zip'
    Write-AnEbG3Utf8 -Path $strictReceipt -Text "{}`n"
    Write-AnEbG3Utf8 -Path $strictZip -Text 'immutable zip placeholder'
    $strictReceiptSha = (Get-FileHash -LiteralPath $strictReceipt -Algorithm SHA256).Hash.ToLowerInvariant()
    $strictLaunchResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $strictFixture 'tools\launch.ps1') -Arguments @(
        '-Root', $strictFixture,
        '-Port', [string]$strictPort,
        '-HealthTimeoutSeconds', '5',
        '-RequireExternalAdmission',
        '-AdmissionReceiptPath', $strictReceipt,
        '-ExpectedAdmissionReceiptSha256', $strictReceiptSha,
        '-PackageZipPath', $strictZip,
        '-ExitAfterReady'
    )
    $strictDoctorInvocation = [System.IO.File]::ReadAllText((Join-Path $strictFixture 'doctor-invocation.json')) | ConvertFrom-Json
    Assert-AnEbG3Test -Condition ($strictLaunchResult.ExitCode -eq 0 -and
        $strictDoctorInvocation.require_external_admission -is [bool] -and
        $strictDoctorInvocation.require_external_admission -and
        $strictDoctorInvocation.admission_receipt_path -ceq $strictReceipt -and
        $strictDoctorInvocation.expected_admission_receipt_sha256 -ceq $strictReceiptSha -and
        $strictDoctorInvocation.package_zip_path -ceq $strictZip) -Message ('launcher forwards the complete formal admission tuple to doctor output=' + $strictLaunchResult.Output)

    $doctorFixture = Join-Path $tempRoot 'strict-admission-doctor-package'
    New-Item -ItemType Directory -Path (Join-Path $doctorFixture 'tools') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $doctorFixture 'results') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'tools\doctor.ps1') -Destination (Join-Path $doctorFixture 'tools\doctor.ps1')
    Copy-Item -LiteralPath (Join-Path $repo 'tools\common.ps1') -Destination (Join-Path $doctorFixture 'tools\common.ps1')
    Write-AnEbG3Utf8 -Path (Join-Path $doctorFixture 'tools\verify-package.ps1') -Text @'
param(
    [string]$Root,
    [switch]$RequireExternalAdmission,
    [string]$AdmissionReceiptPath,
    [string]$ExpectedAdmissionReceiptSha256,
    [string]$PackageZipPath
)
$invocation = [ordered]@{
    require_external_admission = [bool]$RequireExternalAdmission
    admission_receipt_path = [string]$AdmissionReceiptPath
    expected_admission_receipt_sha256 = [string]$ExpectedAdmissionReceiptSha256
    package_zip_path = [string]$PackageZipPath
}
[System.IO.File]::WriteAllText(
    (Join-Path $Root 'verify-invocation.json'),
    (($invocation | ConvertTo-Json -Compress) + [char]10),
    (New-Object System.Text.UTF8Encoding($false, $true))
)
Write-Output 'PASS TEST_ONLY_VERIFY_STUB'
exit 0
'@
    $doctorPort = Get-AnEbG3FreePort
    $doctorResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $doctorFixture 'tools\doctor.ps1') -Arguments @(
        '-Root', $doctorFixture,
        '-Port', [string]$doctorPort,
        '-RequireExternalAdmission',
        '-AdmissionReceiptPath', $strictReceipt,
        '-ExpectedAdmissionReceiptSha256', $strictReceiptSha,
        '-PackageZipPath', $strictZip
    )
    $verifyInvocation = [System.IO.File]::ReadAllText((Join-Path $doctorFixture 'verify-invocation.json')) | ConvertFrom-Json
    Assert-AnEbG3Test -Condition ($doctorResult.ExitCode -eq 0 -and
        $verifyInvocation.require_external_admission -is [bool] -and
        $verifyInvocation.require_external_admission -and
        $verifyInvocation.admission_receipt_path -ceq $strictReceipt -and
        $verifyInvocation.expected_admission_receipt_sha256 -ceq $strictReceiptSha -and
        $verifyInvocation.package_zip_path -ceq $strictZip) -Message ('doctor invokes verifier with RequireExternalAdmission and the exact external tuple output=' + $doctorResult.Output)

    $doctorDowngradeResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $doctorFixture 'tools\doctor.ps1') -Arguments @(
        '-Root', $doctorFixture,
        '-Port', [string](Get-AnEbG3FreePort),
        '-AdmissionReceiptPath', $strictReceipt,
        '-ExpectedAdmissionReceiptSha256', $strictReceiptSha,
        '-PackageZipPath', $strictZip
    )
    Assert-AnEbG3Test -Condition ($doctorDowngradeResult.ExitCode -ne 0 -and $doctorDowngradeResult.Output -match 'P007_ARTIFACT_ADMISSION') -Message ('doctor rejects external admission inputs without the explicit formal-gate switch output=' + $doctorDowngradeResult.Output)

    $noLanPort = Get-AnEbG3FreePort
    $noLanFixture = Join-Path $tempRoot 'no-lan-package'
    New-AnEbG3LaunchFixture -FixturePath $noLanFixture -ServerBinary $serverBinary -Port $noLanPort -LanAddresses @()
    $noLanResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $noLanFixture 'tools\launch.ps1') -Arguments @(
        '-Root', $noLanFixture,
        '-Port', [string]$noLanPort,
        '-HealthTimeoutSeconds', '2',
        '-ExitAfterReady'
    )
    Assert-AnEbG3Test -Condition ($noLanResult.ExitCode -ne 0 -and $noLanResult.Output -match 'P005_NO_LAN_ADDRESS' -and $noLanResult.Output -notmatch '(?m)^OWNED_SERVER_PID ') -Message ('launcher rejects a doctor result without a LAN candidate before starting a server output=' + $noLanResult.Output)

    $missingAddrPort = Get-AnEbG3FreePort
    $missingAddrFixture = Join-Path $tempRoot 'missing-addr-package'
    New-AnEbG3LaunchFixture -FixturePath $missingAddrFixture -ServerBinary $serverBinary -Port $missingAddrPort -ServerArguments @('-data', './results')
    $missingAddrResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $missingAddrFixture 'tools\launch.ps1') -Arguments @(
        '-Root', $missingAddrFixture,
        '-Port', [string]$missingAddrPort,
        '-HealthTimeoutSeconds', '2',
        '-ExitAfterReady'
    )
    Assert-AnEbG3Test -Condition ($missingAddrResult.ExitCode -ne 0 -and $missingAddrResult.Output -match 'P007_CONTRACT_MISMATCH' -and $missingAddrResult.Output -notmatch '(?m)^OWNED_SERVER_PID ') -Message ('launcher rejects VERSION metadata without one admitted addr before starting a server output=' + $missingAddrResult.Output)

    $invalidAddrCases = @(
        [pscustomobject]@{ Name = 'duplicate-addr'; Arguments = @('-addr', (':' + $missingAddrPort), '-addr', (':' + $missingAddrPort)) },
        [pscustomobject]@{ Name = 'loopback-addr'; Arguments = @('-addr', ('127.0.0.1:' + $missingAddrPort)) },
        [pscustomobject]@{ Name = 'different-port'; Arguments = @('-addr', (':' + ($missingAddrPort + 1))) }
    )
    foreach ($invalidAddrCase in $invalidAddrCases) {
        $invalidAddrFixture = Join-Path $tempRoot ($invalidAddrCase.Name + '-package')
        New-AnEbG3LaunchFixture -FixturePath $invalidAddrFixture -ServerBinary $serverBinary -Port $missingAddrPort -ServerArguments $invalidAddrCase.Arguments
        $invalidAddrResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $invalidAddrFixture 'tools\launch.ps1') -Arguments @(
            '-Root', $invalidAddrFixture,
            '-Port', [string]$missingAddrPort,
            '-HealthTimeoutSeconds', '2',
            '-ExitAfterReady'
        )
        Assert-AnEbG3Test -Condition ($invalidAddrResult.ExitCode -ne 0 -and $invalidAddrResult.Output -match 'P007_CONTRACT_MISMATCH' -and $invalidAddrResult.Output -notmatch '(?m)^OWNED_SERVER_PID ') -Message ('launcher rejects ' + $invalidAddrCase.Name + ' before starting a server output=' + $invalidAddrResult.Output)
    }

    $wildcardAddrPort = Get-AnEbG3FreePort
    $wildcardAddrFixture = Join-Path $tempRoot 'wildcard-addr-package'
    New-AnEbG3LaunchFixture -FixturePath $wildcardAddrFixture -ServerBinary $serverBinary -Port $wildcardAddrPort -ServerArguments @('-addr', ('0.0.0.0:' + $wildcardAddrPort), '-data', './results')
    $wildcardAddrResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $wildcardAddrFixture 'tools\launch.ps1') -Arguments @(
        '-Root', $wildcardAddrFixture,
        '-Port', [string]$wildcardAddrPort,
        '-HealthTimeoutSeconds', '5',
        '-ExitAfterReady'
    )
    Assert-AnEbG3Test -Condition ($wildcardAddrResult.ExitCode -eq 0 -and $wildcardAddrResult.Output -match 'ANEB Prototype 0\.1 - READY') -Message ('launcher admits the explicit all-interface addr at its selected port output=' + $wildcardAddrResult.Output)

    $legacyPort = Get-AnEbG3FreePort
    $legacyFixture = Join-Path $tempRoot 'legacy-route-package'
    New-AnEbG3LaunchFixture -FixturePath $legacyFixture -ServerBinary $serverBinary -Port $legacyPort -ServerInfoEndpoint '/health' -CapabilityEndpoint '/capabilities'
    $legacyResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $legacyFixture 'tools\launch.ps1') -Arguments @(
        '-Root', $legacyFixture,
        '-Port', [string]$legacyPort,
        '-HealthTimeoutSeconds', '2',
        '-ExitAfterReady'
    )
    Assert-AnEbG3Test -Condition ($legacyResult.ExitCode -ne 0 -and $legacyResult.Output -match 'P007_CONTRACT_MISMATCH' -and $legacyResult.Output -notmatch '(?m)^OWNED_SERVER_PID ') -Message ('launcher rejects legacy route metadata before starting a server output=' + $legacyResult.Output)

    $prototypeOnlyPort = Get-AnEbG3FreePort
    $prototypeOnlyFixture = Join-Path $tempRoot 'prototype-only-injected-package'
    New-AnEbG3LaunchFixture -FixturePath $prototypeOnlyFixture -ServerBinary $serverBinary -Port $prototypeOnlyPort -IncludePrototypeOnly $false
    $prototypeOnlyResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $prototypeOnlyFixture 'tools\launch.ps1') -Arguments @(
        '-Root', $prototypeOnlyFixture,
        '-Port', [string]$prototypeOnlyPort,
        '-HealthTimeoutSeconds', '5',
        '-ExitAfterReady'
    )
    Assert-AnEbG3Test -Condition ($prototypeOnlyResult.ExitCode -eq 0 -and $prototypeOnlyResult.Output -match 'ANEB Prototype 0\.1 - READY') -Message ('launcher enforces prototype-only server mode when VERSION omits the runtime flag output=' + $prototypeOnlyResult.Output)

    $missingEvidencePackage = Join-Path $tempRoot 'missing-evidence-runtime-package'
    New-Item -ItemType Directory -Path $missingEvidencePackage | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $missingEvidencePackage 'bin') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $missingEvidencePackage 'android') | Out-Null
    Copy-Item -LiteralPath $serverBinary -Destination (Join-Path $missingEvidencePackage 'bin\aneb-server.exe')
    Write-AnEbG3Utf8 -Path (Join-Path $missingEvidencePackage 'android\aneb-prototype-0.1.apk') -Text 'synthetic APK payload'
    Write-AnEbG3Utf8 -Path (Join-Path $missingEvidencePackage 'VERSION.json') -Text (([ordered]@{
        release_state = 'RELEASE_CANDIDATE'
        evidence_verifier_artifact = 'bin/evidence/aneb-evidence.exe'
    } | ConvertTo-Json -Compress) + [char]10)
    $missingEvidenceManifest = Invoke-AnEbG3Tool -ScriptPath (Join-Path $repo 'tools\make-package-manifest.ps1') -Arguments @(
        '-Root', $missingEvidencePackage,
        '-OutputPath', (Join-Path $missingEvidencePackage 'PACKAGE-SHA256.txt')
    )
    Assert-AnEbG3Test -Condition ($missingEvidenceManifest.ExitCode -ne 0 -and $missingEvidenceManifest.Output -match 'aneb-evidence\.exe') -Message ('package manifest rejects a candidate without the bundled evidence runtime output=' + $missingEvidenceManifest.Output)

    $evidenceDirectory = Join-Path $missingEvidencePackage 'bin\evidence'
    New-Item -ItemType Directory -Path (Join-Path $evidenceDirectory '_internal') -Force | Out-Null
    Copy-Item -LiteralPath $serverBinary -Destination (Join-Path $evidenceDirectory 'aneb-evidence.exe')
    Write-AnEbG3Utf8 -Path (Join-Path $evidenceDirectory '_internal\python-runtime.dll') -Text 'bundled runtime member'
    $evidenceManifestPath = Join-Path $missingEvidencePackage 'PACKAGE-SHA256.txt'
    $evidenceManifest = Invoke-AnEbG3Tool -ScriptPath (Join-Path $repo 'tools\make-package-manifest.ps1') -Arguments @(
        '-Root', $missingEvidencePackage,
        '-OutputPath', $evidenceManifestPath
    )
    $evidenceManifestText = if (Test-Path -LiteralPath $evidenceManifestPath -PathType Leaf) { [System.IO.File]::ReadAllText($evidenceManifestPath) } else { '' }
    Assert-AnEbG3Test -Condition ($evidenceManifest.ExitCode -eq 0 -and
        $evidenceManifestText -match '(?m)^[0-9a-f]{64}  bin/evidence/aneb-evidence\.exe$' -and
        $evidenceManifestText -match '(?m)^[0-9a-f]{64}  bin/evidence/_internal/python-runtime\.dll$') -Message ('package manifest binds the evidence executable and bundled onedir runtime members output=' + $evidenceManifest.Output)

    $missingEvidenceMetadataPackage = Join-Path $tempRoot 'missing-evidence-metadata-package'
    Copy-Item -LiteralPath $missingEvidencePackage -Destination $missingEvidenceMetadataPackage -Recurse
    Remove-Item -LiteralPath (Join-Path $missingEvidenceMetadataPackage 'PACKAGE-SHA256.txt') -Force
    $missingEvidenceMetadataVersionPath = Join-Path $missingEvidenceMetadataPackage 'VERSION.json'
    $missingEvidenceMetadataVersion = [System.IO.File]::ReadAllText($missingEvidenceMetadataVersionPath) | ConvertFrom-Json
    $missingEvidenceMetadataVersion.PSObject.Properties.Remove('evidence_verifier_artifact')
    Write-AnEbG3Utf8 -Path $missingEvidenceMetadataVersionPath -Text (($missingEvidenceMetadataVersion | ConvertTo-Json -Compress -Depth 8) + [char]10)
    $missingEvidenceMetadataResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $repo 'tools\make-package-manifest.ps1') -Arguments @(
        '-Root', $missingEvidenceMetadataPackage,
        '-OutputPath', (Join-Path $missingEvidenceMetadataPackage 'PACKAGE-SHA256.txt')
    )
    Assert-AnEbG3Test -Condition ($missingEvidenceMetadataResult.ExitCode -ne 0 -and $missingEvidenceMetadataResult.Output -match 'evidence_verifier_artifact') -Message ('package manifest rejects an unbound evidence verifier path output=' + $missingEvidenceMetadataResult.Output)

    $missingEvidenceToolPackage = Join-Path $tempRoot 'missing-evidence-tool-package'
    New-AnEbG3SkeletonFixture -FixturePath $missingEvidenceToolPackage
    Remove-Item -LiteralPath (Join-Path $missingEvidenceToolPackage 'tools\verify-evidence.ps1') -Force
    $missingEvidenceToolResult = Invoke-AnEbG3Tool -ScriptPath (Join-Path $missingEvidenceToolPackage 'tools\verify-package.ps1') -Arguments @(
        '-Root', $missingEvidenceToolPackage,
        '-AllowSkeleton'
    )
    Assert-AnEbG3Test -Condition ($missingEvidenceToolResult.ExitCode -ne 0 -and $missingEvidenceToolResult.Output -match 'tools/verify-evidence\.ps1') -Message ('package verifier rejects a skeleton missing the packaged evidence entrypoint output=' + $missingEvidenceToolResult.Output)

    $readmeText = [System.IO.File]::ReadAllText((Join-Path $repo 'README_FIRST.md'))
    Assert-AnEbG3Test -Condition ($readmeText -match '`Q`.*Enter' -and $readmeText -match 'aneb-server\.exe') -Message 'operator guide documents the explicit Q/PID stop path instead of claiming close-window cleanup'
    Assert-AnEbG3Test -Condition ($readmeText -match 'tools\\verify-evidence\.ps1' -and $readmeText -match 'results\\<campaign_id>' -and $readmeText -match 'G0_VERIFY_OK') -Message 'operator guide documents the exact offline evidence command, result layout, and pass marker'
    Assert-AnEbG3Test -Condition ($readmeText -match 'partial' -and $readmeText -match 'verified.*evidence integrity only; it does not mean campaign or run success') -Message 'operator guide distinguishes verified evidence integrity from campaign success'

    Write-Output ('PASS G3_LAUNCHER_PACKAGE_TESTS count=' + $passed)
    exit 0
}
catch {
    Write-Output ('FAIL G3_LAUNCHER_PACKAGE_TESTS ' + $_.Exception.Message)
    exit 1
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
