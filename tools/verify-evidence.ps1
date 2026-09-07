[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Bundle,
    [string]$VerifierExecutable = ''
)

$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $PSScriptRoot
$verifier = if ([string]::IsNullOrWhiteSpace($VerifierExecutable)) {
    Join-Path $packageRoot 'bin\evidence\aneb-evidence.exe'
} else {
    [System.IO.Path]::GetFullPath($VerifierExecutable)
}
if (-not (Test-Path -LiteralPath $verifier -PathType Leaf)) {
    throw 'P015_EVIDENCE_VERIFIER_MISSING'
}

& $verifier verify-bundle --bundle ([System.IO.Path]::GetFullPath($Bundle))
if ($LASTEXITCODE -ne 0) {
    throw ('P016_EVIDENCE_VERIFICATION_FAILED exit=' + $LASTEXITCODE)
}
