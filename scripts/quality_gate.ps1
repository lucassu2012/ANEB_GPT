[CmdletBinding()]
param(
    [switch]$SkipServer
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not $env:JAVA_HOME -and (Test-Path 'E:\tools\jdk-17.0.19+10')) {
    $env:JAVA_HOME = 'E:\tools\jdk-17.0.19+10'
}
if (-not $env:ANDROID_HOME -and (Test-Path 'E:\tools\android-sdk')) {
    $env:ANDROID_HOME = 'E:\tools\android-sdk'
}

if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    throw 'JAVA_HOME is missing or invalid.'
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    throw 'ANDROID_HOME is missing or invalid.'
}

Push-Location (Join-Path $repo 'app')
$sqliteTmp = Join-Path $env:TEMP ("aneb-sqlite-" + [guid]::NewGuid().ToString('N'))
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
New-Item -ItemType Directory -Path $sqliteTmp -Force | Out-Null
$sqliteOption = "-Dorg.sqlite.tmpdir=$sqliteTmp"
$env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
    $sqliteOption
} else {
    "$previousJavaToolOptions $sqliteOption"
}
try {
    # Room's KSP verifier loads sqlite-jdbc native code. Serial workers plus in-process,
    # non-incremental KSP avoid Windows DLL and lookup-cache races during parallel development.
    & .\gradlew.bat `
        ':probe:testDebugUnitTest' `
        ':probe:lintDebug' `
        ':probe:processReleaseMainManifest' `
        ':probe:assembleDebug' `
        '--no-daemon' `
        '--no-parallel' `
        '--max-workers=1' `
        '-Pkotlin.compiler.execution.strategy=in-process' `
        '-Pksp.incremental=false'
    if ($LASTEXITCODE -ne 0) {
        throw "Android quality gate failed with exit code $LASTEXITCODE."
    }
}
finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    if (Test-Path -LiteralPath $sqliteTmp) {
        Remove-Item -LiteralPath $sqliteTmp -Recurse -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}

& (Join-Path $PSScriptRoot 'verify_release_boundary.ps1')
if ($LASTEXITCODE -ne 0) {
    throw "Release-boundary verification failed with exit code $LASTEXITCODE."
}

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) {
    throw 'Python 3.11+ is required for the behavior-model quality gate.'
}

$candidateTmp = Join-Path $env:TEMP ("aneb-debug-candidate-gate-" + [guid]::NewGuid().ToString('N'))
try {
    & $pythonCommand.Source `
        (Join-Path $PSScriptRoot 'package_debug_candidate.py') `
        '--apk' (Join-Path $repo 'app\probe\build\outputs\apk\debug\probe-debug.apk') `
        '--metadata' (Join-Path $repo 'app\probe\build\outputs\apk\debug\output-metadata.json') `
        '--output' $candidateTmp `
        '--source-ref' 'local-quality-gate'
    if ($LASTEXITCODE -ne 0) {
        throw "Debug-candidate packaging failed with exit code $LASTEXITCODE."
    }
}
finally {
    if (Test-Path -LiteralPath $candidateTmp) {
        $tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
        $candidateFull = [IO.Path]::GetFullPath($candidateTmp)
        if (-not $candidateFull.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean candidate path outside TEMP: $candidateFull"
        }
        Remove-Item -LiteralPath $candidateFull -Recurse -Force -ErrorAction SilentlyContinue
    }
}

& $pythonCommand.Source (Join-Path $PSScriptRoot 'verify_spec_catalog.py')
if ($LASTEXITCODE -ne 0) {
    throw "Spec-catalog verification failed with exit code $LASTEXITCODE."
}

& $pythonCommand.Source (Join-Path $PSScriptRoot 'verify_result_schema.py')
if ($LASTEXITCODE -ne 0) {
    throw "Result-schema verification failed with exit code $LASTEXITCODE."
}

Push-Location $repo
try {
    & $pythonCommand.Source -m unittest discover -s scripts/tests
    if ($LASTEXITCODE -ne 0) {
        throw "Measurement-analysis tests failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

Push-Location (Join-Path $repo 'tools\aneb-ai-behavior-model')
try {
    $previousPythonPath = $env:PYTHONPATH
    $env:PYTHONPATH = 'src'
    & $pythonCommand.Source -m unittest discover -s tests
    if ($LASTEXITCODE -ne 0) {
        throw "Behavior-model tests failed with exit code $LASTEXITCODE."
    }
}
finally {
    $env:PYTHONPATH = $previousPythonPath
    Pop-Location
}

if (-not $SkipServer) {
    $goCommand = Get-Command go -ErrorAction SilentlyContinue
    $goExe = if ($goCommand) { $goCommand.Source } else { $null }
    if (-not $goExe -and (Test-Path 'C:\Program Files\Go\bin\go.exe')) {
        $goExe = 'C:\Program Files\Go\bin\go.exe'
    }
    if (-not $goExe) {
        throw 'Go is required for the server quality gate. Use -SkipServer only for Android-only iteration.'
    }

    Push-Location (Join-Path $repo 'server')
    try {
        & $goExe test -count=1 ./...
        if ($LASTEXITCODE -ne 0) {
            throw "Go server tests failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    Push-Location (Join-Path $repo 'gateway')
    try {
        & $goExe test -count=1 ./...
        if ($LASTEXITCODE -ne 0) {
            throw "Go gateway tests failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

Write-Host 'ANEB Probe Codex quality gate: PASS'
