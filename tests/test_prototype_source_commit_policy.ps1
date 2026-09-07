[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Invoke-SourceCommitGate {
    param([AllowNull()][string]$SourceCommit)
    $arguments = @(
        ':probe:verifyPrototypeSourceCommit',
        '--no-daemon',
        '--no-parallel',
        '--max-workers=1'
    )
    if ($null -ne $SourceCommit) {
        $arguments += ('-Paneb.prototype.sourceCommit=' + $SourceCommit)
    }
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location (Join-Path $repo 'app')
    try {
        $output = @(& .\gradlew.bat @arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = [string]::Join([char]10, @($output | ForEach-Object { $_.ToString() }))
    }
}

foreach ($invalid in @($null, '', ('A' * 40), ('a' * 39), ('g' * 40), ('a' * 40 + ' '))) {
    $result = Invoke-SourceCommitGate -SourceCommit $invalid
    if ($result.ExitCode -eq 0 -or $result.Output -notmatch 'P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND') {
        throw ('ASSERTION_FAILED invalid Prototype source commit was not rejected output=' + $result.Output)
    }
}
Write-Output 'PASS invalid Prototype source commits fail closed with P019'

$valid = Invoke-SourceCommitGate -SourceCommit ('0123456789abcdef0123456789abcdef01234567')
if ($valid.ExitCode -ne 0) {
    throw ('ASSERTION_FAILED exact lowercase Prototype source commit was rejected output=' + $valid.Output)
}
Write-Output 'PASS exact lowercase Prototype source commit is admitted'
