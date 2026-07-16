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
try {
    # Room's KSP verifier loads sqlite-jdbc native code. Serial workers plus in-process,
    # non-incremental KSP avoid Windows DLL and lookup-cache races during parallel development.
    & .\gradlew.bat `
        ':probe:testDebugUnitTest' `
        ':probe:lintDebug' `
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
    Pop-Location
}

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) {
    throw 'Python 3.11+ is required for the behavior-model quality gate.'
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
}

Write-Host 'ANEB Probe Codex quality gate: PASS'
