[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('aneb-release-signing-policy-' + [Guid]::NewGuid().ToString('N'))
$dummyPassword = 'aneb-test-password'
$dummyAlias = 'aneb-production'
$environmentNames = @(
    'ANEB_RELEASE_STORE_FILE',
    'ANEB_RELEASE_STORE_PASSWORD',
    'ANEB_RELEASE_KEY_ALIAS',
    'ANEB_RELEASE_KEY_PASSWORD'
)
$originalEnvironment = @{}

function Invoke-AnEbSigningGradle {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    Push-Location (Join-Path $repo 'app')
    try {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $output = @(& .\gradlew.bat @Arguments 2>&1)
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    }
    finally {
        Pop-Location
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = [string]::Join([char]10, @($output | ForEach-Object { $_.ToString() }))
    }
}

function Assert-AnEbSigningFailureIsRedacted {
    param(
        [Parameter(Mandatory = $true)]$Result,
        [Parameter(Mandatory = $true)][string]$SecretPath,
        [Parameter(Mandatory = $true)][string]$SecretPassword
    )
    if ($Result.ExitCode -eq 0 -or $Result.Output -notmatch 'P010_RELEASE_SIGNING_STORE_UNAVAILABLE') {
        throw 'ASSERTION_FAILED unavailable signing store did not use the stable redacted code'
    }
    if ($Result.Output.IndexOf($SecretPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $Result.Output.IndexOf($SecretPassword, [System.StringComparison]::Ordinal) -ge 0) {
        throw 'ASSERTION_FAILED signing store failure disclosed a protected path or password'
    }
}

foreach ($name in $environmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    New-Item -ItemType Directory -Path $tempRoot -ErrorAction Stop | Out-Null
    $storePath = Join-Path $tempRoot 'unapproved-test-signer.p12'
    $keytool = Get-Command keytool.exe -ErrorAction Stop
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $keytoolOutput = @(& $keytool.Source `
            -genkeypair `
            -alias $dummyAlias `
            -keyalg RSA `
            -keysize 2048 `
            -sigalg SHA256withRSA `
            -validity 2 `
            -dname 'CN=ANEB Unapproved Test Signer' `
            -storetype PKCS12 `
            -keystore $storePath `
            -storepass $dummyPassword `
            -keypass $dummyPassword `
            -noprompt 2>&1)
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $storePath -PathType Leaf)) {
        throw ('TEST_SIGNER_CREATION_FAILED ' + [string]::Join([char]10, $keytoolOutput))
    }

    [Environment]::SetEnvironmentVariable('ANEB_RELEASE_STORE_FILE', $storePath, 'Process')
    [Environment]::SetEnvironmentVariable('ANEB_RELEASE_STORE_PASSWORD', $dummyPassword, 'Process')
    [Environment]::SetEnvironmentVariable('ANEB_RELEASE_KEY_ALIAS', $dummyAlias, 'Process')
    [Environment]::SetEnvironmentVariable('ANEB_RELEASE_KEY_PASSWORD', $dummyPassword, 'Process')

    $unapprovedResult = Invoke-AnEbSigningGradle -Arguments @(
        ':probe:verifyReleaseSigning',
        '--no-daemon',
        '--no-parallel',
        '--max-workers=1'
    )
    if ($unapprovedResult.ExitCode -eq 0) {
        throw 'ASSERTION_FAILED unapproved release signer was accepted'
    }
    if ($unapprovedResult.Output -notmatch 'P011_RELEASE_SIGNER_NOT_APPROVED') {
        throw 'ASSERTION_FAILED signer rejection did not use the stable code'
    }
    Write-Output 'PASS unapproved release signer is rejected with P011_RELEASE_SIGNER_NOT_APPROVED'

    $missingStorePath = Join-Path $tempRoot 'protected-missing-store-location.p12'
    [Environment]::SetEnvironmentVariable('ANEB_RELEASE_STORE_FILE', $missingStorePath, 'Process')
    $missingResult = Invoke-AnEbSigningGradle -Arguments @(
        ':probe:verifyReleaseSigning',
        '--no-daemon',
        '--no-parallel',
        '--max-workers=1'
    )
    Assert-AnEbSigningFailureIsRedacted -Result $missingResult -SecretPath $missingStorePath -SecretPassword $dummyPassword

    $malformedStorePath = Join-Path $tempRoot 'protected-malformed-store-location.p12'
    [System.IO.File]::WriteAllBytes($malformedStorePath, [System.Text.Encoding]::UTF8.GetBytes('not-a-pkcs12-store'))
    [Environment]::SetEnvironmentVariable('ANEB_RELEASE_STORE_FILE', $malformedStorePath, 'Process')
    $malformedResult = Invoke-AnEbSigningGradle -Arguments @(
        ':probe:verifyReleaseSigning',
        '--no-daemon',
        '--no-parallel',
        '--max-workers=1'
    )
    Assert-AnEbSigningFailureIsRedacted -Result $malformedResult -SecretPath $malformedStorePath -SecretPassword $dummyPassword
    Write-Output 'PASS unavailable release signing stores fail with a redacted stable code'

    [Environment]::SetEnvironmentVariable('ANEB_RELEASE_STORE_FILE', $storePath, 'Process')
    $artifactTasks = @(
        'packageRelease',
        'packagePrototypeRelease',
        'packageReleaseBundle',
        'packagePrototypeReleaseBundle',
        'signReleaseBundle',
        'signPrototypeReleaseBundle',
        'packageReleaseUniversalApk',
        'packagePrototypeReleaseUniversalApk'
    )
    $taskGraphResult = Invoke-AnEbSigningGradle -Arguments @(
        @($artifactTasks | ForEach-Object { ':probe:' + $_ }) +
        @(
            '--dry-run',
            '-Paneb.prototype.sourceCommit=0123456789abcdef0123456789abcdef01234567',
            '--no-daemon',
            '--no-parallel',
            '--max-workers=1'
        )
    )
    if ($taskGraphResult.ExitCode -ne 0) {
        throw 'ASSERTION_FAILED release artifact task graph could not be inspected'
    }
    $signingGateIndex = $taskGraphResult.Output.IndexOf(':probe:verifyReleaseSigning ', [System.StringComparison]::Ordinal)
    $sourceGateIndex = $taskGraphResult.Output.IndexOf(':probe:verifyPrototypeSourceCommit ', [System.StringComparison]::Ordinal)
    if ($signingGateIndex -lt 0 -or $sourceGateIndex -lt 0) {
        throw 'ASSERTION_FAILED release artifact tasks are not fail-closed behind signer and source gates'
    }
    foreach ($taskName in $artifactTasks) {
        $taskIndex = $taskGraphResult.Output.IndexOf((':probe:' + $taskName + ' '), [System.StringComparison]::Ordinal)
        if ($taskIndex -lt 0 -or $taskIndex -lt $signingGateIndex) {
            throw 'ASSERTION_FAILED release artifact task can run before signer verification'
        }
        if ($taskName.IndexOf('Prototype', [System.StringComparison]::Ordinal) -ge 0 -and $taskIndex -lt $sourceGateIndex) {
            throw 'ASSERTION_FAILED Prototype artifact task can run before source-commit verification'
        }
    }
    Write-Output 'PASS every release artifact-producing task is ordered behind required gates'
}
finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
