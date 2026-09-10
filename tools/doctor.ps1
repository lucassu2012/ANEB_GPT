[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [ValidateRange(1, 65535)][int]$Port = 18088,
    [switch]$RequireExternalAdmission,
    [string]$AdmissionReceiptPath = '',
    [string]$ExpectedAdmissionReceiptSha256 = '',
    [string]$PackageZipPath = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

function Stop-AnEbDoctor {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message
    )
    Write-Output "FAIL $Code $Message"
    exit 1
}

try {
    $rootFull = Get-AnEbFullPath -Path $Root
    Assert-AnEbDirectory -Path $rootFull | Out-Null

    $verify = Join-Path $PSScriptRoot 'verify-package.ps1'
    $verifyArguments = @('-Root', $rootFull)
    $hasExternalAdmissionInput = -not [string]::IsNullOrWhiteSpace($AdmissionReceiptPath) -or
        -not [string]::IsNullOrWhiteSpace($ExpectedAdmissionReceiptSha256) -or
        -not [string]::IsNullOrWhiteSpace($PackageZipPath)
    if (-not $RequireExternalAdmission -and $hasExternalAdmissionInput) {
        Stop-AnEbDoctor -Code 'P007_ARTIFACT_ADMISSION' -Message 'external admission inputs require the formal-gate switch'
    }
    if ($RequireExternalAdmission) {
        if ([string]::IsNullOrWhiteSpace($AdmissionReceiptPath) -or
            [string]::IsNullOrWhiteSpace($ExpectedAdmissionReceiptSha256) -or
            [string]::IsNullOrWhiteSpace($PackageZipPath)) {
            Stop-AnEbDoctor -Code 'P007_ARTIFACT_ADMISSION' -Message 'formal admission requires receipt, receipt SHA-256, and immutable package ZIP'
        }
        $verifyArguments += @(
            '-RequireExternalAdmission',
            '-AdmissionReceiptPath', $AdmissionReceiptPath,
            '-ExpectedAdmissionReceiptSha256', $ExpectedAdmissionReceiptSha256,
            '-PackageZipPath', $PackageZipPath
        )
    }
    $verifyOutput = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $verify @verifyArguments)
    $verifyCode = $LASTEXITCODE
    $verifyOutput | ForEach-Object { Write-Output $_ }
    if ($verifyCode -ne 0) {
        Stop-AnEbDoctor -Code 'P001_PACKAGE_INTEGRITY' -Message 'package verification failed'
    }

    $resultsDir = Join-Path $rootFull 'results'
    if (-not (Test-Path -LiteralPath $resultsDir)) {
        New-Item -ItemType Directory -Path $resultsDir -ErrorAction Stop | Out-Null
    }
    Assert-AnEbDirectory -Path $resultsDir | Out-Null
    $probePath = Join-Path $resultsDir ('.doctor-write-' + $PID + '-' + ([Guid]::NewGuid().ToString('N')) + '.tmp')
    try {
        Write-AnEbCreateNewUtf8 -Path $probePath -Text 'doctor-write-probe'
    }
    catch {
        Stop-AnEbDoctor -Code 'P002_OUTPUT_NOT_WRITABLE' -Message 'results directory is not writable'
    }
    finally {
        if (Test-Path -LiteralPath $probePath) {
            Remove-Item -LiteralPath $probePath -ErrorAction SilentlyContinue
        }
    }
    Write-Output 'PASS P002_OUTPUT_NOT_WRITABLE results directory is writable'

    if (-not [Environment]::Is64BitOperatingSystem) {
        Stop-AnEbDoctor -Code 'P001_PACKAGE_INTEGRITY' -Message '64-bit Windows is required'
    }
    Write-Output 'PASS PLATFORM Windows 64-bit'

    $listener = New-Object System.Net.Sockets.TcpListener(
        [System.Net.IPAddress]::Loopback,
        $Port
    )
    try {
        $listener.Start()
    }
    catch {
        Stop-AnEbDoctor -Code 'P003_PORT_IN_USE' -Message ("port " + $Port + " is unavailable")
    }
    finally {
        if ($listener) {
            $listener.Stop()
        }
    }
    Write-Output ("PASS P003_PORT_AVAILABLE " + $Port)

    $candidates = @(Get-AnEbLanCandidates)
    $addresses = @($candidates | ForEach-Object { $_.Address })
    if ($addresses.Count -eq 0) {
        Stop-AnEbDoctor -Code 'P005_NO_LAN_ADDRESS' -Message 'no usable LAN IPv4 address was found'
    }
    Write-Output ("PASS LAN_ADDRESSES " + ($addresses -join ','))
    Write-Output ('INFO LAN_CANDIDATES ' + (ConvertTo-Json -InputObject $candidates -Compress))
    Write-Output 'PASS DOCTOR package, output, port and LAN preflight'
    exit 0
}
catch {
    Write-Output ("FAIL P001_PACKAGE_INTEGRITY " + $_.Exception.Message)
    exit 1
}
