[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PythonExecutable,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$SourceCommit
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$entryPoint = Join-Path $repo 'tools\aneb_evidence_runtime.py'
$contractRoot = Join-Path $repo 'contracts\prototype-0.1'
$contractFiles = @(
    'profile-manifest.json',
    'capabilities.schema.json',
    'run-record.schema.json',
    'score-policy.json'
)
$outputFull = [System.IO.Path]::GetFullPath($OutputDirectory)
$pythonFull = [System.IO.Path]::GetFullPath($PythonExecutable)

if ($SourceCommit -cnotmatch '^[0-9a-f]{40}$') {
    throw 'P014_EVIDENCE_BUILD_FAILED source commit is invalid'
}
$headLines = @(& git.exe -C $repo rev-parse HEAD 2>$null)
if ($LASTEXITCODE -ne 0 -or $headLines.Count -ne 1 -or [string]$headLines[0] -cne $SourceCommit) {
    throw 'P014_EVIDENCE_BUILD_FAILED source commit does not match HEAD'
}
$dirty = [string]::Join("`n", @(& git.exe -C $repo status --porcelain=v1 --untracked-files=all 2>$null))
if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrEmpty($dirty)) {
    throw 'P014_EVIDENCE_BUILD_FAILED source worktree is not clean'
}

if (-not (Test-Path -LiteralPath $pythonFull -PathType Leaf)) {
    throw 'P014_EVIDENCE_BUILD_FAILED Python executable is missing'
}
if (-not (Test-Path -LiteralPath $entryPoint -PathType Leaf)) {
    throw 'P014_EVIDENCE_BUILD_FAILED contract verifier entry point is missing'
}
if (Test-Path -LiteralPath $outputFull) {
    throw 'P014_EVIDENCE_BUILD_FAILED output directory already exists'
}

$parent = Split-Path -Parent $outputFull
if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
    throw 'P014_EVIDENCE_BUILD_FAILED output parent is missing'
}
New-Item -ItemType Directory -Path $outputFull -ErrorAction Stop | Out-Null
$workRoot = Join-Path $outputFull '.pyinstaller-work'
$distRoot = Join-Path $outputFull 'dist'
$generatedEntryPoint = Join-Path $workRoot 'aneb_evidence_runtime.py'
$entryTemplate = [System.IO.File]::ReadAllText($entryPoint)
if ([regex]::Matches($entryTemplate, '__ANEB_BUILD_SOURCE_COMMIT__').Count -ne 1) {
    throw 'P014_EVIDENCE_BUILD_FAILED source commit placeholder is not exact'
}
$generatedEntryParent = Split-Path -Parent $generatedEntryPoint
New-Item -ItemType Directory -Path $generatedEntryParent -Force -ErrorAction Stop | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false, $true)
[System.IO.File]::WriteAllText($generatedEntryPoint, $entryTemplate.Replace('__ANEB_BUILD_SOURCE_COMMIT__', $SourceCommit), $utf8)
$dataArguments = foreach ($contractFile in $contractFiles) {
    $contractPath = Join-Path $contractRoot $contractFile
    if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) {
        throw ('P014_EVIDENCE_BUILD_FAILED required contract is missing: ' + $contractFile)
    }
    '--add-data'
    ($contractPath + ';.')
}

try {
    & $pythonFull -B -m PyInstaller `
        '--clean' `
        '--noconfirm' `
        '--onedir' `
        '--console' `
        '--name' 'aneb-evidence' `
        '--distpath' $distRoot `
        '--workpath' (Join-Path $workRoot 'build') `
        '--specpath' (Join-Path $workRoot 'spec') `
        '--paths' $contractRoot `
        @dataArguments `
        $generatedEntryPoint
    if ($LASTEXITCODE -ne 0) {
        throw ('P014_EVIDENCE_BUILD_FAILED PyInstaller exit=' + $LASTEXITCODE)
    }
    $builtRoot = Join-Path $distRoot 'aneb-evidence'
    $built = Join-Path $builtRoot 'aneb-evidence.exe'
    if (-not (Test-Path -LiteralPath $built -PathType Leaf)) {
        throw 'P014_EVIDENCE_BUILD_FAILED verifier executable was not produced'
    }
    $finalRoot = Join-Path $outputFull 'aneb-evidence'
    Move-Item -LiteralPath $builtRoot -Destination $finalRoot -ErrorAction Stop
    $final = Join-Path $finalRoot 'aneb-evidence.exe'
    Write-Output ('PASS EVIDENCE_VERIFIER_BUILT sha256=' + (Get-FileHash -LiteralPath $final -Algorithm SHA256).Hash.ToLowerInvariant())
}
finally {
    if (Test-Path -LiteralPath $workRoot) {
        Remove-Item -LiteralPath $workRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $distRoot) {
        Remove-Item -LiteralPath $distRoot -Recurse -Force
    }
}
