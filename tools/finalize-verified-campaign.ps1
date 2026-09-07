[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$CampaignId,
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

& $verifier finalize-bundle `
    --input ([System.IO.Path]::GetFullPath($InputDirectory)) `
    --output-root ([System.IO.Path]::GetFullPath($OutputRoot)) `
    --campaign-id $CampaignId
if ($LASTEXITCODE -ne 0) {
    throw ('P017_EVIDENCE_FINALIZATION_FAILED exit=' + $LASTEXITCODE)
}
