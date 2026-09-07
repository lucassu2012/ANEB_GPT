[CmdletBinding()]
param(
    [switch]$P0Only,
    [ValidateSet('health-wrong-body', 'cap-subset', 'cap-malformed', 'cap-extra')][string]$P0LauncherCase = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$verify = Join-Path $repo 'tools\verify-package.ps1'
$launcher = Join-Path $repo 'tools\launch.ps1'
$finalizer = Join-Path $repo 'tools\finalize-campaign.ps1'
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('aneb-prototype-0.1-skeleton-test-' + ([Guid]::NewGuid().ToString('N')))
$passed = 0

function Assert-AnEbTest {
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

function Write-AnEbTestText {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    [System.IO.File]::WriteAllBytes($Path, $encoding.GetBytes($Text))
}

function Write-AnEbTestChecksumList {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot
    )
    $rootFull = [System.IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
    $records = @(Get-ChildItem -LiteralPath $rootFull -Recurse -File -Force |
        Where-Object {
            $_.Name -ne 'SHA256SUMS.txt' -and
            -not $_.FullName.Substring($rootFull.Length + 1).Replace('\', '/').StartsWith('results/', [System.StringComparison]::Ordinal)
        } |
        Sort-Object FullName)
    $lines = foreach ($record in $records) {
        $relative = $record.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
        (Get-FileHash -LiteralPath $record.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative
    }
    Write-AnEbTestText -Path (Join-Path $rootFull 'SHA256SUMS.txt') -Text ([string]::Join([char]10, $lines) + [char]10)
}

function New-AnEbTestJunction {
    param(
        [Parameter(Mandatory = $true)][string]$Link,
        [Parameter(Mandatory = $true)][string]$Target
    )
    $mklinkOutput = @(& cmd.exe /d /c mklink /J $Link $Target 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw ('JUNCTION_FIXTURE_FAILED ' + ([string]::Join([char]10, $mklinkOutput)))
    }
}

function New-AnEbTestApk {
    param([Parameter(Mandatory = $true)][string]$Path)
    Add-Type -AssemblyName System.IO.Compression
    $fileStream = New-Object System.IO.FileStream(
        $Path,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $fileStream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        $entry = $archive.CreateEntry('AndroidManifest.xml')
        $writer = New-Object System.IO.StreamWriter($entry.Open(), (New-Object System.Text.UTF8Encoding($false)))
        try {
            $writer.Write('<manifest package="com.aneb.prototype" />')
        }
        finally {
            $writer.Dispose()
        }
    }
    finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
        else {
            $fileStream.Dispose()
        }
    }
}

function New-AnEbTestServerArguments {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][ValidateSet('success', 'health-fail', 'health-wrong-body', 'cap-fail', 'cap-subset', 'cap-malformed', 'cap-extra')][string]$Mode,
        [Parameter(Mandatory = $true)][string]$ServerBinarySha256,
        [Parameter(Mandatory = $true)][string]$ScriptPath
    )
    $scriptTemplate = @'
param(
    [switch]$prototypeOnly,
    [string]$addr
)
$port = __PORT__
$mode = '__MODE__'
$serverBinarySha256 = '__SERVER_BINARY_SHA256__'
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $port)
$listener.Start()
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $requestLine = $reader.ReadLine()
            while ($null -ne ($header = $reader.ReadLine()) -and $header -ne '') { }
            if ($requestLine -like 'GET /api/v1/serverinfo*') {
                if ($mode -eq 'health-fail') {
                    $status = '500 Internal Server Error'
                    $body = 'health-failure'
                }
                elseif ($mode -eq 'health-wrong-body') {
                    $status = '200 OK'
                    $body = 'not-the-canonical-health-response'
                }
                else {
                    $status = '200 OK'
                    $body = '{"version":"synthetic-test-server","srv_ts_us":1,"anchor_wall_unix_ns":1,"uptime_s":0,"goos":"windows","goarch":"amd64","h3_enabled":false,"tcp_slow_start_after_idle":"n/a","congestion_control":"n/a"}'
                }
            }
            elseif ($requestLine -like 'GET /api/v1/prototype/capabilities*') {
                if ($mode -eq 'cap-fail') {
                    $status = '200 OK'
                    $body = '{}'
                }
                elseif ($mode -eq 'cap-subset') {
                    $status = '200 OK'
                    $body = '{"schema_version":"aneb-prototype-evidence-0.1"}'
                }
                elseif ($mode -eq 'cap-malformed') {
                    $status = '200 OK'
                    $body = '{"schema_version":'
                }
                else {
                    $status = '200 OK'
                    $body = '{"schema_version":"aneb-prototype-capabilities-0.1","product_version":"prototype-0.1","protocol_version":"prototype-stream-0.1","server_version":"synthetic-test-server","server_binary_sha256":"__SERVER_BINARY_SHA256__","claim_scope":"application_end_to_end_to_probe_node","evidence_mode":"synthetic_application_impairment","impairment_layer":"application","profile_manifest_sha256":"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc","workload":{"id":"streaming_text_reference_v0.1","version":"0.1","content_event_count":120},"conditions":[{"id":"baseline_v0.1","version":"0.1","nominal_interval_ms":50,"schedule_sha256":"46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"},{"id":"slow_v0.1","version":"0.1","nominal_interval_ms":125,"schedule_sha256":"b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062"},{"id":"unstable_v0.1","version":"0.1","nominal_interval_ms":65,"schedule_sha256":"d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58"}],"evidence_schema_version":"aneb-prototype-evidence-0.1","score_policy_id":"rpi-0.1","terminal_receipt_version":"prototype-terminal-receipt-0.1"}'
                    if ($mode -eq 'cap-extra') {
                        $body = $body.Substring(0, $body.Length - 1) + ',"unexpected_extra":true}'
                    }
                }
            }
            else {
                $status = '404 Not Found'
                $body = 'not-found'
            }
            $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
            $response = 'HTTP/1.1 ' + $status + "`r`nContent-Length: " + $bodyBytes.Length + "`r`nConnection: close`r`n`r`n"
            $responseBytes = [System.Text.Encoding]::ASCII.GetBytes($response)
            $stream.Write($responseBytes, 0, $responseBytes.Length)
            $stream.Write($bodyBytes, 0, $bodyBytes.Length)
            $stream.Flush()
        }
        finally {
            $client.Close()
        }
    }
}
finally {
    $listener.Stop()
}
'@
    $scriptText = $scriptTemplate.Replace('__PORT__', $Port.ToString([Globalization.CultureInfo]::InvariantCulture)).Replace('__MODE__', $Mode).Replace('__SERVER_BINARY_SHA256__', $ServerBinarySha256)
    Write-AnEbTestText -Path $ScriptPath -Text $scriptText
    return [string[]]@('-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', '.\tools\synthetic-server.ps1', '-addr', (':' + $Port))
}

function Set-AnEbTestVersionField {
    param(
        [Parameter(Mandatory = $true)]$Version,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Value
    )
    $property = $Version.PSObject.Properties[$Name]
    if ($null -eq $property) {
        $Version | Add-Member -MemberType NoteProperty -Name $Name -Value $Value
    }
    else {
        $Version.$Name = $Value
    }
}

function Get-AnEbTestTreeSha256 {
    param([Parameter(Mandatory = $true)][string]$Root)
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    [string[]]$rows = @(Get-ChildItem -LiteralPath $rootFull -Recurse -File -Force | ForEach-Object {
        $relative = $_.FullName.Substring($rootFull.Length + 1).Replace('\', '/')
        $relative + '=' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    [Array]::Sort($rows, [System.StringComparer]::Ordinal)
    $bytes = [System.Text.UTF8Encoding]::new($false, $true).GetBytes(([string]::Join([char]10, $rows) + [char]10))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function New-AnEbTestImmutableZip {
    param(
        [Parameter(Mandatory = $true)][string]$FixturePath,
        [Parameter(Mandatory = $true)][string]$ZipPath
    )
    $zipParent = Split-Path -Parent $ZipPath
    if (-not (Test-Path -LiteralPath $zipParent -PathType Container)) {
        New-Item -ItemType Directory -Path $zipParent -Force | Out-Null
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open($ZipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $fixtureFull = [System.IO.Path]::GetFullPath($FixturePath).TrimEnd('\')
        $entrySpecs = @([pscustomobject]@{
            Name = 'ANEB-Prototype-0.1/results/'
            FullPath = $null
            IsDirectory = $true
        }) + @(Get-ChildItem -LiteralPath $fixtureFull -Recurse -File -Force | ForEach-Object {
            $relative = $_.FullName.Substring($fixtureFull.Length + 1).Replace('\', '/')
            if ($relative.StartsWith('results/', [System.StringComparison]::Ordinal)) {
                return
            }
            [pscustomobject]@{
                Name = 'ANEB-Prototype-0.1/' + $relative
                FullPath = $_.FullName
                IsDirectory = $false
            }
        })
        [string[]]$entryNames = @($entrySpecs | ForEach-Object { [string]$_.Name })
        [Array]::Sort($entryNames, [System.StringComparer]::Ordinal)
        foreach ($entryName in $entryNames) {
            $spec = @($entrySpecs | Where-Object { $_.Name -ceq $entryName })[0]
            $compression = if ($spec.IsDirectory) { [System.IO.Compression.CompressionLevel]::NoCompression } else { [System.IO.Compression.CompressionLevel]::Optimal }
            $entry = $archive.CreateEntry($spec.Name, $compression)
            $entry.LastWriteTime = [datetimeoffset]'2000-01-01T00:00:00Z'
            $entry.ExternalAttributes = 0
            if ($spec.IsDirectory) {
                continue
            }
            $input = [System.IO.File]::OpenRead($spec.FullPath)
            $output = $entry.Open()
            try {
                $input.CopyTo($output)
            }
            finally {
                $output.Dispose()
                $input.Dispose()
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}

function New-AnEbTestReleaseFixture {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePackage,
        [Parameter(Mandatory = $true)][string]$FixturePath,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][ValidateSet('success', 'health-fail', 'health-wrong-body', 'cap-fail', 'cap-subset', 'cap-malformed', 'cap-extra')][string]$Mode
    )
    Copy-Item -LiteralPath $SourcePackage -Destination $FixturePath -Recurse
    $versionPath = Join-Path $FixturePath 'VERSION.json'
    $version = [System.IO.File]::ReadAllText($versionPath) | ConvertFrom-Json
    Set-AnEbTestVersionField -Version $version -Name 'release_state' -Value 'RELEASE_CANDIDATE'
    Set-AnEbTestVersionField -Version $version -Name 'release_candidate' -Value 'rc.synthetic'
    Set-AnEbTestVersionField -Version $version -Name 'source_commit' -Value ('a' * 40)
    Set-AnEbTestVersionField -Version $version -Name 'artifact_build_receipt_sha256' -Value ('b' * 64)
    Set-AnEbTestVersionField -Version $version -Name 'built_at_utc' -Value '2026-08-29T00:00:00Z'
    Set-AnEbTestVersionField -Version $version -Name 'server_version' -Value 'synthetic-test-server'
    Set-AnEbTestVersionField -Version $version -Name 'server_source_commit' -Value ('a' * 40)
    Set-AnEbTestVersionField -Version $version -Name 'android_version_name' -Value '0.1.0-test'
    Set-AnEbTestVersionField -Version $version -Name 'android_version_code' -Value 1
    Set-AnEbTestVersionField -Version $version -Name 'android_package_name' -Value 'com.aneb.probe'
    Set-AnEbTestVersionField -Version $version -Name 'android_source_commit' -Value ('a' * 40)
    Set-AnEbTestVersionField -Version $version -Name 'evidence_source_commit' -Value ('a' * 40)
    Set-AnEbTestVersionField -Version $version -Name 'workload_id' -Value 'streaming_text_reference_v0.1'
    Set-AnEbTestVersionField -Version $version -Name 'condition_versions' -Value @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')
    Set-AnEbTestVersionField -Version $version -Name 'server_artifact' -Value 'bin/aneb-server.exe'
    Set-AnEbTestVersionField -Version $version -Name 'android_artifact' -Value 'android/aneb-prototype-0.1.apk'
    Set-AnEbTestVersionField -Version $version -Name 'evidence_verifier_artifact' -Value 'bin/evidence/aneb-evidence.exe'
    Set-AnEbTestVersionField -Version $version -Name 'evidence_runtime_characterization' -Value 'valid_bundle_pass_invalid_bundle_reject'
    Set-AnEbTestVersionField -Version $version -Name 'artifact_admission' -Value 'REAL_ARTIFACTS_BOUND'
    Set-AnEbTestVersionField -Version $version -Name 'health_endpoint' -Value '/api/v1/serverinfo'
    Set-AnEbTestVersionField -Version $version -Name 'capability_endpoint' -Value '/api/v1/prototype/capabilities'
    New-Item -ItemType Directory -Path (Join-Path $FixturePath 'bin') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $FixturePath 'bin\evidence') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $FixturePath 'android') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $FixturePath 'results') -Force | Out-Null
    $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Source
    $serverPath = Join-Path $FixturePath 'bin\aneb-server.exe'
    Copy-Item -LiteralPath $powershellPath -Destination $serverPath
    Copy-Item -LiteralPath $powershellPath -Destination (Join-Path $FixturePath 'bin\evidence\aneb-evidence.exe')
    Write-AnEbTestText -Path (Join-Path $FixturePath 'bin\evidence\runtime.dat') -Text "synthetic evidence runtime member`n"
    $serverHash = (Get-FileHash -LiteralPath $serverPath -Algorithm SHA256).Hash.ToLowerInvariant()
    New-AnEbTestApk -Path (Join-Path $FixturePath 'android\aneb-prototype-0.1.apk')
    Set-AnEbTestVersionField -Version $version -Name 'evidence_runtime_tree_sha256' -Value (Get-AnEbTestTreeSha256 -Root (Join-Path $FixturePath 'bin\evidence'))
    Set-AnEbTestVersionField -Version $version -Name 'server_args' -Value (New-AnEbTestServerArguments -Port $Port -Mode $Mode -ServerBinarySha256 $serverHash -ScriptPath (Join-Path $FixturePath 'tools\synthetic-server.ps1'))
    Write-AnEbTestText -Path $versionPath -Text (($version | ConvertTo-Json -Compress -Depth 12) + [char]10)
    Write-AnEbTestChecksumList -PackageRoot $FixturePath
}

function New-AnEbTestAdmissionReceipt {
    param(
        [Parameter(Mandatory = $true)][string]$FixturePath,
        [Parameter(Mandatory = $true)][string]$ReceiptPath,
        [string]$AndroidSignerCertSha256 = 'b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003'
    )
    $version = [System.IO.File]::ReadAllText((Join-Path $FixturePath 'VERSION.json')) | ConvertFrom-Json
    $zipDirectory = Join-Path (Split-Path -Parent $ReceiptPath) (([System.IO.Path]::GetFileNameWithoutExtension($ReceiptPath)) + '-zip')
    $zipPath = Join-Path $zipDirectory ('ANEB-Prototype-0.1-' + [string]$version.release_candidate + '-windows-x64.zip')
    New-AnEbTestImmutableZip -FixturePath $FixturePath -ZipPath $zipPath
    $receipt = [ordered]@{
        schema_version = 'aneb-prototype-artifact-admission-0.1'
        admission_status = 'G0_ARTIFACT_ADMITTED'
        source_commit = [string]$version.source_commit
        artifact_build_receipt_sha256 = [string]$version.artifact_build_receipt_sha256
        server_path = 'bin/aneb-server.exe'
        server_version = [string]$version.server_version
        server_source_commit = [string]$version.server_source_commit
        server_sha256 = (Get-FileHash -LiteralPath (Join-Path (Join-Path $FixturePath 'bin') 'aneb-server.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
        android_path = 'android/aneb-prototype-0.1.apk'
        android_version_name = [string]$version.android_version_name
        android_version_code = [int]$version.android_version_code
        android_package_name = [string]$version.android_package_name
        android_source_commit = [string]$version.android_source_commit
        evidence_source_commit = [string]$version.evidence_source_commit
        android_sha256 = (Get-FileHash -LiteralPath (Join-Path (Join-Path $FixturePath 'android') 'aneb-prototype-0.1.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
        android_signer_cert_sha256 = $AndroidSignerCertSha256
        evidence_runtime_path = 'bin/evidence/aneb-evidence.exe'
        evidence_runtime_sha256 = (Get-FileHash -LiteralPath (Join-Path $FixturePath 'bin\evidence\aneb-evidence.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
        evidence_runtime_tree_sha256 = [string]$version.evidence_runtime_tree_sha256
        evidence_runtime_characterization = [string]$version.evidence_runtime_characterization
        package_zip_name = [System.IO.Path]::GetFileName($zipPath)
        package_zip_sha256 = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    Write-AnEbTestText -Path $ReceiptPath -Text (($receipt | ConvertTo-Json -Compress -Depth 8) + [char]10)
    return [pscustomobject]@{
        ReceiptSha256 = (Get-FileHash -LiteralPath $ReceiptPath -Algorithm SHA256).Hash.ToLowerInvariant()
        PackageZipPath = $zipPath
    }
}

function Invoke-AnEbTool {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $argumentJson = $Arguments | ConvertTo-Json -Compress
    $argumentBase64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($argumentJson))
    $scriptBase64 = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($ScriptPath))
    $childCommand = @'
$ErrorActionPreference = 'Stop'
$toolPath = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('__SCRIPT__'))
$json = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('__ARGS__'))
$toolArguments = [string[]](ConvertFrom-Json -InputObject $json)
$toolParameters = @{}
$argumentIndex = 0
while ($argumentIndex -lt $toolArguments.Count) {
    $parameterToken = $toolArguments[$argumentIndex]
    if ($parameterToken -notmatch '^-[A-Za-z]') { throw 'invalid test parameter token' }
    $parameterName = $parameterToken.Substring(1)
    $argumentIndex++
    $parameterValues = @()
    while ($argumentIndex -lt $toolArguments.Count -and $toolArguments[$argumentIndex] -notmatch '^-[A-Za-z]') {
        $parameterValues += $toolArguments[$argumentIndex]
        $argumentIndex++
    }
    if ($parameterValues.Count -eq 0) {
        $toolParameters[$parameterName] = $true
    }
    elseif ($parameterValues.Count -eq 1) {
        $toolParameters[$parameterName] = $parameterValues[0]
    }
    else {
        $toolParameters[$parameterName] = [string[]]$parameterValues
    }
}
$toolExitCode = 0
try {
    & $toolPath @toolParameters
    if (-not $?) { $toolExitCode = 1 }
}
catch {
    Write-Error $_
    $toolExitCode = 1
}
exit $toolExitCode
'@
    $childCommand = $childCommand.Replace('__SCRIPT__', $scriptBase64).Replace('__ARGS__', $argumentBase64)
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))
    $output = @(& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encodedCommand 2>&1)
    $code = $LASTEXITCODE
    return [pscustomobject]@{
        Output = [string]::Join([char]10, $output)
        ExitCode = $code
    }
}

function Invoke-AnEbPythonFile {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $python = Get-Command python.exe -ErrorAction Stop
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $python.Source -B $ScriptPath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $outputText = [string]::Join([char]10, @($output | ForEach-Object { $_.ToString() }))
    return [pscustomobject]@{
        Output = $outputText
        ExitCode = $exitCode
    }
}

function Copy-AnEbSkeletonFile {
    param(
        [Parameter(Mandatory = $true)][string]$PackageRoot,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    $sourceRelative = $RelativePath
    $targetRelative = $RelativePath
    if ($RelativePath.StartsWith('contracts/prototype-0.1/', [System.StringComparison]::Ordinal)) {
        $sourceRelative = $RelativePath
        $targetRelative = $RelativePath.Replace('contracts/prototype-0.1/', 'contracts/')
    }
    $source = Join-Path $repo ($sourceRelative -replace '/', '\')
    $target = Join-Path $PackageRoot ($targetRelative -replace '/', '\')
    $parent = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    if ($RelativePath.StartsWith('contracts/prototype-0.1/', [System.StringComparison]::Ordinal)) {
        $raw = [System.IO.File]::ReadAllBytes($source)
        $encoding = New-Object System.Text.UTF8Encoding($false, $true)
        $text = $encoding.GetString($raw)
        $text = $text.Replace(([string][char]13 + [char]10), [string][char]10)
        if ($text.Contains([char]13)) {
            throw ('TEST_CONTRACT_BARE_CR ' + $RelativePath)
        }
        [System.IO.File]::WriteAllBytes($target, $encoding.GetBytes($text))
    }
    else {
        Copy-Item -LiteralPath $source -Destination $target
    }
}

try {
    New-Item -ItemType Directory -Path $tempRoot -ErrorAction Stop | Out-Null
    $packageRoot = Join-Path $tempRoot 'package'
    New-Item -ItemType Directory -Path $packageRoot | Out-Null
    $packageFiles = @(
        'START_ANEB.bat',
        'README_FIRST.md',
        'VERSION.json',
        'SHA256SUMS.txt',
        'contracts/prototype-0.1/capabilities.schema.json',
        'contracts/prototype-0.1/profile-manifest.json',
        'contracts/prototype-0.1/run-record.schema.json',
        'contracts/prototype-0.1/score-policy.json',
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
    foreach ($relative in $packageFiles) {
        Copy-AnEbSkeletonFile -PackageRoot $packageRoot -RelativePath $relative
    }

    $skeletonResult = Invoke-AnEbTool -ScriptPath $verify -Arguments @('-Root', $packageRoot, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($skeletonResult.ExitCode -eq 0) -Message 'package verifier accepts only explicit skeleton mode'
    Assert-AnEbTest -Condition ($skeletonResult.Output -match 'BLOCKED_ARTIFACTS') -Message 'skeleton verifier states that server and APK are absent'
    Assert-AnEbTest -Condition ($skeletonResult.Output -match 'PASS CONTRACT_BINDING') -Message 'skeleton verifier validates the exact four-contract binding'

    $freshPackage = Join-Path $tempRoot 'package-copy'
    Copy-Item -LiteralPath $packageRoot -Destination $freshPackage -Recurse
    $freshResult = Invoke-AnEbTool -ScriptPath (Join-Path $freshPackage 'tools\verify-package.ps1') -Arguments @('-Root', $freshPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($freshResult.ExitCode -eq 0) -Message 'fresh copied package preserves contract binding'

    $missingContractPackage = Join-Path $tempRoot 'package-missing-contract'
    Copy-Item -LiteralPath $packageRoot -Destination $missingContractPackage -Recurse
    Remove-Item -LiteralPath (Join-Path $missingContractPackage 'contracts\run-record.schema.json') -Force
    $missingContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $missingContractPackage 'tools\verify-package.ps1') -Arguments @('-Root', $missingContractPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($missingContractResult.ExitCode -ne 0) -Message 'verifier rejects a missing machine contract'
    Assert-AnEbTest -Condition ($missingContractResult.Output -match 'CONTRACT') -Message 'missing contract failure is explicit'

    $extraContractPackage = Join-Path $tempRoot 'package-extra-contract'
    Copy-Item -LiteralPath $packageRoot -Destination $extraContractPackage -Recurse
    Write-AnEbTestText -Path (Join-Path $extraContractPackage 'contracts\evidence-schema.json') -Text ('{}' + [char]10)
    $extraContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $extraContractPackage 'tools\verify-package.ps1') -Arguments @('-Root', $extraContractPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($extraContractResult.ExitCode -ne 0) -Message 'verifier rejects an undeclared evidence schema artifact'
    Assert-AnEbTest -Condition ($extraContractResult.Output -match 'CONTRACT') -Message 'extra contract failure is explicit'

    $nestedExtraContractPackage = Join-Path $tempRoot 'package-nested-extra-contract'
    Copy-Item -LiteralPath $packageRoot -Destination $nestedExtraContractPackage -Recurse
    New-Item -ItemType Directory -Path (Join-Path $nestedExtraContractPackage 'contracts\other') | Out-Null
    Write-AnEbTestText -Path (Join-Path $nestedExtraContractPackage 'contracts\other\evidence-schema.json') -Text ('{}' + [char]10)
    $nestedExtraContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $nestedExtraContractPackage 'tools\verify-package.ps1') -Arguments @('-Root', $nestedExtraContractPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($nestedExtraContractResult.ExitCode -ne 0) -Message 'verifier rejects an extra contract subtree'
    Assert-AnEbTest -Condition ($nestedExtraContractResult.Output -match 'CONTRACT') -Message 'nested extra contract failure is explicit'

    $caseVariantPackage = Join-Path $tempRoot 'package-contracts-case-variant'
    Copy-Item -LiteralPath $packageRoot -Destination $caseVariantPackage -Recurse
    Rename-Item -LiteralPath (Join-Path $caseVariantPackage 'contracts') -NewName 'contracts-case-temporary'
    Rename-Item -LiteralPath (Join-Path $caseVariantPackage 'contracts-case-temporary') -NewName 'Contracts'
    $caseVariantResult = Invoke-AnEbTool -ScriptPath (Join-Path $caseVariantPackage 'tools\verify-package.ps1') -Arguments @('-Root', $caseVariantPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($caseVariantResult.ExitCode -ne 0) -Message 'verifier rejects a case-variant Contracts directory'
    Assert-AnEbTest -Condition ($caseVariantResult.Output -match 'CONTRACT|CASE') -Message 'case-sensitive contract directory failure is explicit'

    $tamperedContractPackage = Join-Path $tempRoot 'package-tampered-contract'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedContractPackage -Recurse
    Write-AnEbTestText -Path (Join-Path $tamperedContractPackage 'contracts\profile-manifest.json') -Text ('{"tampered":true}' + [char]10)
    $tamperedContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedContractPackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedContractPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedContractResult.ExitCode -ne 0) -Message 'verifier rejects a tampered contract byte'
    Assert-AnEbTest -Condition ($tamperedContractResult.Output -match 'HASH') -Message 'tampered contract failure is hash-bound'

    $tamperedVersionPackage = Join-Path $tempRoot 'package-tampered-version'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedVersionPackage -Recurse
    $versionText = [System.IO.File]::ReadAllText((Join-Path $tamperedVersionPackage 'VERSION.json'))
    $versionText = $versionText.Replace('44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc', ('0' * 64))
    Write-AnEbTestText -Path (Join-Path $tamperedVersionPackage 'VERSION.json') -Text $versionText
    $tamperedVersionResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedVersionPackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedVersionPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedVersionResult.ExitCode -ne 0) -Message 'verifier rejects a VERSION contract hash drift'
    Assert-AnEbTest -Condition ($tamperedVersionResult.Output -match 'VERSION') -Message 'VERSION hash failure is explicit'

    $tamperedSizePackage = Join-Path $tempRoot 'package-tampered-contract-size'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedSizePackage -Recurse
    $sizeText = [System.IO.File]::ReadAllText((Join-Path $tamperedSizePackage 'VERSION.json'))
    $sizeText = $sizeText.Replace('"profile-manifest.json": 3797', '"profile-manifest.json": 1')
    Write-AnEbTestText -Path (Join-Path $tamperedSizePackage 'VERSION.json') -Text $sizeText
    $tamperedSizeResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedSizePackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedSizePackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedSizeResult.ExitCode -ne 0) -Message 'verifier rejects a VERSION contract size drift'
    Assert-AnEbTest -Condition ($tamperedSizeResult.Output -match 'SIZE') -Message 'VERSION size failure is explicit'

    $tamperedPathPackage = Join-Path $tempRoot 'package-tampered-contract-path'
    Copy-Item -LiteralPath $packageRoot -Destination $tamperedPathPackage -Recurse
    $pathText = [System.IO.File]::ReadAllText((Join-Path $tamperedPathPackage 'VERSION.json'))
    $pathText = $pathText.Replace('"profile-manifest.json": "contracts/profile-manifest.json"', '"profile-manifest.json": "profile-manifest.json"')
    Write-AnEbTestText -Path (Join-Path $tamperedPathPackage 'VERSION.json') -Text $pathText
    $tamperedPathResult = Invoke-AnEbTool -ScriptPath (Join-Path $tamperedPathPackage 'tools\verify-package.ps1') -Arguments @('-Root', $tamperedPathPackage, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($tamperedPathResult.ExitCode -ne 0) -Message 'verifier rejects a VERSION contract path drift'
    Assert-AnEbTest -Condition ($tamperedPathResult.Output -match 'PATH') -Message 'VERSION path failure is explicit'

    $incompleteReleasePackage = Join-Path $tempRoot 'package-incomplete-release'
    Copy-Item -LiteralPath $packageRoot -Destination $incompleteReleasePackage -Recurse
    $incompleteVersionText = [System.IO.File]::ReadAllText((Join-Path $incompleteReleasePackage 'VERSION.json'))
    $incompleteVersionText = $incompleteVersionText.Replace('"release_state": "SKELETON_NOT_RELEASE"', '"release_state": "RELEASE_CANDIDATE"')
    Write-AnEbTestText -Path (Join-Path $incompleteReleasePackage 'VERSION.json') -Text $incompleteVersionText
    New-Item -ItemType Directory -Path (Join-Path $incompleteReleasePackage 'bin') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $incompleteReleasePackage 'android') | Out-Null
    [System.IO.File]::WriteAllBytes((Join-Path $incompleteReleasePackage 'bin\aneb-server.exe'), [byte[]](0x4d, 0x5a, 0x00))
    [System.IO.File]::WriteAllBytes((Join-Path $incompleteReleasePackage 'android\aneb-prototype-0.1.apk'), [byte[]](0x50, 0x4b, 0x03, 0x04, 0x00))
    Write-AnEbTestChecksumList -PackageRoot $incompleteReleasePackage
    $incompleteReleaseResult = Invoke-AnEbTool -ScriptPath (Join-Path $incompleteReleasePackage 'tools\verify-package.ps1') -Arguments @('-Root', $incompleteReleasePackage)
    Assert-AnEbTest -Condition ($incompleteReleaseResult.ExitCode -ne 0) -Message 'strict verifier rejects incomplete release metadata and fake artifacts'
    Assert-AnEbTest -Condition ($incompleteReleaseResult.Output -match 'VERSION|ARTIFACT|ADMISSION') -Message 'strict release rejection identifies metadata or artifact provenance'

    $releaseResult = Invoke-AnEbTool -ScriptPath $verify -Arguments @('-Root', $packageRoot)
    Assert-AnEbTest -Condition ($releaseResult.ExitCode -ne 0) -Message 'package verifier rejects skeleton as a release'
    Assert-AnEbTest -Condition ($releaseResult.Output -match 'P001_PACKAGE_INTEGRITY') -Message 'release rejection uses stable package code'

    $launchResult = Invoke-AnEbTool -ScriptPath $launcher -Arguments @('-Root', $packageRoot)
    Assert-AnEbTest -Condition ($launchResult.ExitCode -ne 0) -Message 'launcher stops before starting absent artifacts'
    Assert-AnEbTest -Condition ($launchResult.Output -notmatch 'READY') -Message 'launcher cannot print READY for skeleton'

    $batchText = [System.IO.File]::ReadAllText((Join-Path $packageRoot 'START_ANEB.bat'))
    Assert-AnEbTest -Condition ($batchText.Contains('%~dp0')) -Message 'Windows launcher resolves its own relative directory'
    Assert-AnEbTest -Condition ($batchText -notmatch '(?i)[A-Z]:\\') -Message 'Windows launcher has no developer absolute path'

    $campaignId = 'campaign-test-01'
    $input = Join-Path $tempRoot 'campaign-input'
    $output = Join-Path $tempRoot 'campaign-output'
    New-Item -ItemType Directory -Path $input | Out-Null
    New-Item -ItemType Directory -Path $output | Out-Null
    $hashB = -join (1..64 | ForEach-Object { 'b' })
    $hashC = -join (1..64 | ForEach-Object { 'c' })
    $meta = [ordered]@{
        schema_version = 'aneb-prototype-evidence-0.1'
        campaign_id = $campaignId
        campaign_mode = 'quick'
        campaign_status = 'partial'
        started_at_utc = '2026-08-28T00:00:00Z'
        ended_at_utc = '2026-08-28T00:00:01Z'
        claim_scope = 'application_end_to_end_to_probe_node'
        evidence_mode = 'synthetic_application_impairment'
        impairment_layer = 'application'
        score_policy_id = 'rpi-0.1'
        profile = [ordered]@{
            id = 'streaming_text_reference_v0.1'
            version = '0.1'
            sha256 = $hashB
        }
        product = [ordered]@{
            version = 'prototype-0.1'
            commit = (-join (1..40 | ForEach-Object { 'a' }))
        }
        run_plan = [ordered]@{
            planned_runs = 3
            order = @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')
        }
    }
    Write-AnEbTestText -Path (Join-Path $input 'meta.json') -Text (($meta | ConvertTo-Json -Compress -Depth 8) + [Environment]::NewLine)
    $event1 = '{"schema_version":"aneb-prototype-evidence-0.1","campaign_id":"' + $campaignId + '","event_type":"campaign_started"}'
    $event2 = '{"schema_version":"aneb-prototype-evidence-0.1","campaign_id":"' + $campaignId + '","event_type":"campaign_failed"}'
    Write-AnEbTestText -Path (Join-Path $input 'events.jsonl') -Text ($event1 + [char]10 + $event2 + [char]10)

    $runHeader = 'schema_version,campaign_id,run_id,campaign_mode,run_index,condition_id,condition_version,condition_hash,run_status,task_success,attempt_started_at_utc,attempt_ended_at_utc,events_expected,events_received,ttft_ms,completion_ms,stream_span_ms,stream_event_rate_eps,stall_threshold_ms,stall_count,stall_duration_ms,stall_fraction,schedule_hash,terminal_receipt_valid,score_eligible,failure_reason'
    $runRows = @()
    $runIndex = 1
    foreach ($condition in @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')) {
        $values = @(
            'aneb-prototype-run-record-0.1',
            $campaignId,
            ('run-test-' + $runIndex.ToString('00')),
            'quick',
            $runIndex,
            $condition,
            '0.1',
            $hashB,
            'not_started',
            'false',
            '',
            '',
            '120',
            '0',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            $hashC,
            '',
            'false',
            'not_started'
        )
        $runRows += [string]::Join(',', $values)
        $runIndex++
    }
    Write-AnEbTestText -Path (Join-Path $input 'runs.csv') -Text ([string]::Join([char]10, @($runHeader) + $runRows) + [char]10)

    $summaryHeader = 'schema_version,campaign_id,campaign_mode,campaign_status,condition_id,planned_runs,attempted_runs,successful_runs,failed_runs,not_started_runs,success_rate,confidence,median_ttft_ms,min_ttft_ms,max_ttft_ms,median_completion_ms,min_completion_ms,max_completion_ms,median_stream_event_rate_eps,median_stall_count,median_stall_duration_ms,median_stall_fraction,rpi,rpi_policy_id,rpi_null_reason'
    $summaryRows = @()
    foreach ($condition in @('baseline_v0.1', 'slow_v0.1', 'unstable_v0.1')) {
        $values = @(
            'aneb-prototype-evidence-0.1',
            $campaignId,
            'quick',
            'partial',
            $condition,
            '1',
            '0',
            '0',
            '0',
            '1',
            '',
            'NONE',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            '',
            'rpi-0.1',
            'campaign_incomplete'
        )
        $summaryRows += [string]::Join(',', $values)
    }
    Write-AnEbTestText -Path (Join-Path $input 'summary.csv') -Text ([string]::Join([char]10, @($summaryHeader) + $summaryRows) + [char]10)
    Write-AnEbTestText -Path (Join-Path $input 'run.log') -Text ('2026-08-28T00:00:00Z INFO finalizer campaign_started' + [char]10)
    $reportParts = @('<!doctype html><html><head><meta charset="utf-8"><script id="canonical-summary" type="application/json">{}</script></head><body>')
    foreach ($sectionId in @('campaign-verdict', 'disclosure', 'condition-comparison', 'run-table', 'environment', 'evidence-integrity', 'failure-details')) {
        $reportParts += '<section id="' + $sectionId + '"><span data-kind="canonical-section">' + $sectionId + ' campaign-test-01</span></section>'
    }
    $reportParts += '</body></html>'
    Write-AnEbTestText -Path (Join-Path $input 'report.html') -Text ([string]::Join([char]10, $reportParts) + [char]10)

    $finalizeResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $input,
        '-OutputRoot', $output,
        '-CampaignId', $campaignId,
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($finalizeResult.ExitCode -eq 0) -Message 'finalizer publishes a real partial campaign fixture'
    Assert-AnEbTest -Condition ($finalizeResult.Output -match 'NON_RC_ASSEMBLED_UNVERIFIED' -and $finalizeResult.Output -notmatch 'PUBLISHED') -Message 'finalizer never advertises a non-G0 fixture as verified'
    $published = Join-Path $output $campaignId
    $publishedFiles = @(Get-ChildItem -LiteralPath $published -File -Force)
    Assert-AnEbTest -Condition ($publishedFiles.Count -eq 7) -Message 'published campaign contains exactly seven files'
    $manifest = Get-Content -LiteralPath (Join-Path $published 'manifest.json') -Raw | ConvertFrom-Json
    Assert-AnEbTest -Condition ($manifest.publication_status -eq 'NON_RC_ASSEMBLED_UNVERIFIED') -Message 'finalizer manifest records the non-RC status before the real artifact and G0 validators exist'
    Assert-AnEbTest -Condition (@($manifest.artifacts).Count -eq 6) -Message 'manifest lists six non-self artifacts'
    Assert-AnEbTest -Condition (-not (@($manifest.artifacts.path) -contains 'manifest.json')) -Message 'manifest does not self-reference'
    Assert-AnEbTest -Condition ($manifest.manifest_version -eq 'aneb-prototype-manifest-0.1') -Message 'manifest uses the frozen Prototype 0.1 version'
    $manifestRaw = [System.IO.File]::ReadAllText((Join-Path $published 'manifest.json'))
    $createdAtMatch = [regex]::Match($manifestRaw, '"created_at_utc":"([^"]+)"')
    Assert-AnEbTest -Condition ($createdAtMatch.Success -and $createdAtMatch.Groups[1].Value -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$') -Message 'manifest carries a canonical UTC creation timestamp'
    $expectedMediaTypes = @{
        'meta.json' = 'application/json'
        'events.jsonl' = 'application/x-ndjson'
        'runs.csv' = 'text/csv'
        'summary.csv' = 'text/csv'
        'report.html' = 'text/html'
        'run.log' = 'text/plain'
    }
    foreach ($artifact in @($manifest.artifacts)) {
        Assert-AnEbTest -Condition ($artifact.PSObject.Properties.Name.Count -eq 4) -Message ('manifest artifact has exact four fields for ' + $artifact.path)
        Assert-AnEbTest -Condition ($artifact.media_type -eq $expectedMediaTypes[[string]$artifact.path]) -Message ('manifest artifact media type is canonical for ' + $artifact.path)
    }
    $report = Get-Content -LiteralPath (Join-Path $published 'report.html') -Raw
    Assert-AnEbTest -Condition ($report -match 'canonical-summary' -and $report -notmatch '(?i)href\s*=|src\s*=|https?://') -Message 'generated report uses the canonical offline grammar'
    Assert-AnEbTest -Condition ($report -match 'campaign-test-01') -Message 'generated report carries actual campaign identity'

    $collisionResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $input,
        '-OutputRoot', $output,
        '-CampaignId', $campaignId,
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($collisionResult.ExitCode -ne 0) -Message 'finalizer rejects publication collision'
    Assert-AnEbTest -Condition ($collisionResult.Output -match 'already exists') -Message 'collision failure is explicit and no-clobber'

    $badInput = Join-Path $tempRoot 'campaign-input-extra'
    Copy-Item -LiteralPath $input -Destination $badInput -Recurse
    Write-AnEbTestText -Path (Join-Path $badInput 'unexpected.txt') -Text 'not part of evidence'
    $badOutput = Join-Path $tempRoot 'campaign-output-extra'
    New-Item -ItemType Directory -Path $badOutput | Out-Null
    $badResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $badInput,
        '-OutputRoot', $badOutput,
        '-CampaignId', 'campaign-test-02',
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($badResult.ExitCode -ne 0) -Message 'finalizer rejects an extra input file before publication'
    Assert-AnEbTest -Condition (-not (Test-Path -LiteralPath (Join-Path $badOutput 'campaign-test-02'))) -Message 'rejected input creates no accepted campaign'

    $arbitraryInput = Join-Path $tempRoot 'campaign-input-arbitrary'
    New-Item -ItemType Directory -Path $arbitraryInput | Out-Null
    $arbitraryNames = @('alpha.json', 'beta.json', 'gamma.csv', 'delta.csv', 'epsilon.html', 'zeta.log')
    foreach ($name in $arbitraryNames) {
        $text = if ($name -eq 'epsilon.html') { '<!doctype html><html><body>offline fixture</body></html>' } else { 'arbitrary fixture' }
        Write-AnEbTestText -Path (Join-Path $arbitraryInput $name) -Text $text
    }
    $arbitraryOutput = Join-Path $tempRoot 'campaign-output-arbitrary'
    New-Item -ItemType Directory -Path $arbitraryOutput | Out-Null
    $arbitraryArguments = @(
        '-InputDirectory', $arbitraryInput,
        '-OutputRoot', $arbitraryOutput,
        '-CampaignId', 'campaign-test-arbitrary',
        '-PayloadFiles'
    )
    foreach ($arbitraryName in $arbitraryNames) {
        $arbitraryArguments += $arbitraryName
    }
    $arbitraryArguments += @('-OfflineReportFile', 'epsilon.html')
    $arbitraryResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments $arbitraryArguments
    Assert-AnEbTest -Condition ($arbitraryResult.ExitCode -ne 0) -Message 'finalizer rejects a noncanonical six-file evidence set'
    Assert-AnEbTest -Condition ($arbitraryResult.Output -match 'CANONICAL|EVIDENCE|PAYLOAD') -Message 'noncanonical evidence failure is explicit'

    $protocolInput = Join-Path $tempRoot 'campaign-input-protocol-relative'
    Copy-Item -LiteralPath $input -Destination $protocolInput -Recurse
    $protocolReportPath = Join-Path $protocolInput 'report.html'
    $protocolReport = [System.IO.File]::ReadAllText($protocolReportPath)
    Write-AnEbTestText -Path $protocolReportPath -Text $protocolReport.Replace('<meta charset="utf-8">', '<meta charset="utf-8"><link href="//evil.invalid/payload.css">')
    $protocolOutput = Join-Path $tempRoot 'campaign-output-protocol-relative'
    New-Item -ItemType Directory -Path $protocolOutput | Out-Null
    $protocolResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $protocolInput,
        '-OutputRoot', $protocolOutput,
        '-CampaignId', 'campaign-test-protocol-relative',
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($protocolResult.ExitCode -ne 0) -Message 'finalizer rejects protocol-relative report resources'
    Assert-AnEbTest -Condition ($protocolResult.Output -match 'REPORT|OFFLINE|RESOURCE') -Message 'protocol-relative report failure is explicit'

    $rootParentJunctionTarget = Join-Path $tempRoot 'root-parent-junction-target'
    New-Item -ItemType Directory -Path $rootParentJunctionTarget | Out-Null
    Copy-Item -LiteralPath $packageRoot -Destination (Join-Path $rootParentJunctionTarget 'package') -Recurse
    $rootParentJunction = Join-Path $tempRoot 'root-parent-junction'
    New-AnEbTestJunction -Link $rootParentJunction -Target $rootParentJunctionTarget
    $rootThroughJunction = Join-Path $rootParentJunction 'package'
    $rootParentJunctionResult = Invoke-AnEbTool -ScriptPath (Join-Path $rootThroughJunction 'tools\verify-package.ps1') -Arguments @('-Root', $rootThroughJunction, '-AllowSkeleton')
    Assert-AnEbTest -Condition ($rootParentJunctionResult.ExitCode -ne 0) -Message 'verifier rejects a package whose parent path is a junction'
    Assert-AnEbTest -Condition ($rootParentJunctionResult.Output -match 'REPARSE|INTEGRITY|PACKAGE') -Message 'physical parent containment failure is explicit'

    $runtimeIntegrityRelease = Join-Path $tempRoot 'package-runtime-integrity-only'
    $runtimeIntegrityPort = Get-Random -Minimum 20000 -Maximum 40000
    New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $runtimeIntegrityRelease -Port $runtimeIntegrityPort -Mode 'success'
    $runtimeIntegrityResult = Invoke-AnEbTool -ScriptPath (Join-Path $runtimeIntegrityRelease 'tools\verify-package.ps1') -Arguments @(
        '-Root', $runtimeIntegrityRelease
    )
    Assert-AnEbTest -Condition ($runtimeIntegrityResult.ExitCode -eq 0) -Message 'ordinary release candidate verifies package integrity without an external admission receipt'
    Assert-AnEbTest -Condition ($runtimeIntegrityResult.Output -match 'PASS PACKAGE_INTEGRITY.*runtime integrity only') -Message 'ordinary release verification limits its claim to runtime integrity'

    $missingAdmissionResult = Invoke-AnEbTool -ScriptPath (Join-Path $runtimeIntegrityRelease 'tools\verify-package.ps1') -Arguments @(
        '-Root', $runtimeIntegrityRelease,
        '-RequireExternalAdmission'
    )
    Assert-AnEbTest -Condition ($missingAdmissionResult.ExitCode -ne 0) -Message 'formal release gate rejects a missing external admission receipt'
    Assert-AnEbTest -Condition ($missingAdmissionResult.Output -match 'P007_ARTIFACT_ADMISSION') -Message 'missing external admission uses the stable admission failure code'

    $p0CallerControlledRelease = Join-Path $tempRoot 'package-p0-caller-controlled-admission'
    $p0CallerControlledPort = Get-Random -Minimum 20000 -Maximum 40000
    New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $p0CallerControlledRelease -Port $p0CallerControlledPort -Mode 'success'
    $p0CallerControlledReceipt = Join-Path $tempRoot 'p0-caller-controlled-admission.json'
    $p0CallerControlledAdmission = New-AnEbTestAdmissionReceipt -FixturePath $p0CallerControlledRelease -ReceiptPath $p0CallerControlledReceipt
    $p0CallerControlledResult = Invoke-AnEbTool -ScriptPath (Join-Path $p0CallerControlledRelease 'tools\verify-package.ps1') -Arguments @(
        '-Root', $p0CallerControlledRelease,
        '-RequireExternalAdmission',
        '-AdmissionReceiptPath', $p0CallerControlledReceipt,
        '-ExpectedAdmissionReceiptSha256', $p0CallerControlledAdmission.ReceiptSha256,
        '-PackageZipPath', $p0CallerControlledAdmission.PackageZipPath
    )
    Assert-AnEbTest -Condition ($p0CallerControlledResult.ExitCode -eq 0) -Message 'formal release gate accepts an exact externally pinned admission receipt'
    Assert-AnEbTest -Condition ($p0CallerControlledResult.Output -match 'PASS EXTERNAL_ARTIFACT_ADMISSION') -Message 'formal release gate reports external admission separately from runtime integrity'

    Write-AnEbTestText -Path (Join-Path $p0CallerControlledRelease 'bin\evidence\runtime.dat') -Text "tampered evidence runtime member`n"
    Write-AnEbTestChecksumList -PackageRoot $p0CallerControlledRelease
    Remove-Item -LiteralPath $p0CallerControlledAdmission.PackageZipPath -Force
    New-AnEbTestImmutableZip -FixturePath $p0CallerControlledRelease -ZipPath $p0CallerControlledAdmission.PackageZipPath
    $staleTreeReceipt = [System.IO.File]::ReadAllText($p0CallerControlledReceipt) | ConvertFrom-Json
    $staleTreeReceipt.package_zip_sha256 = (Get-FileHash -LiteralPath $p0CallerControlledAdmission.PackageZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-AnEbTestText -Path $p0CallerControlledReceipt -Text (($staleTreeReceipt | ConvertTo-Json -Compress -Depth 8) + [char]10)
    $staleTreeReceiptSha256 = (Get-FileHash -LiteralPath $p0CallerControlledReceipt -Algorithm SHA256).Hash.ToLowerInvariant()
    $staleTreeResult = Invoke-AnEbTool -ScriptPath (Join-Path $p0CallerControlledRelease 'tools\verify-package.ps1') -Arguments @(
        '-Root', $p0CallerControlledRelease,
        '-RequireExternalAdmission',
        '-AdmissionReceiptPath', $p0CallerControlledReceipt,
        '-ExpectedAdmissionReceiptSha256', $staleTreeReceiptSha256,
        '-PackageZipPath', $p0CallerControlledAdmission.PackageZipPath
    )
    Assert-AnEbTest -Condition ($staleTreeResult.ExitCode -ne 0 -and $staleTreeResult.Output -match 'P007_ARTIFACT_ADMISSION') -Message 'formal release gate recomputes the complete evidence runtime tree instead of trusting stale tree claims'

    $p0ReceiptRaceRelease = Join-Path $tempRoot 'package-p0-receipt-replacement'
    $p0ReceiptRacePort = Get-Random -Minimum 20000 -Maximum 40000
    New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $p0ReceiptRaceRelease -Port $p0ReceiptRacePort -Mode 'success'
    $p0ReceiptRacePath = Join-Path $tempRoot 'p0-receipt-replacement.json'
    $p0ReceiptRaceAdmission = New-AnEbTestAdmissionReceipt -FixturePath $p0ReceiptRaceRelease -ReceiptPath $p0ReceiptRacePath
    $p0ReceiptRaceObject = [System.IO.File]::ReadAllText($p0ReceiptRacePath) | ConvertFrom-Json
    $p0ReceiptRaceObject.android_signer_cert_sha256 = ('d' * 64)
    Write-AnEbTestText -Path $p0ReceiptRacePath -Text (($p0ReceiptRaceObject | ConvertTo-Json -Compress -Depth 8) + [char]10)
    $p0ReceiptRaceResult = Invoke-AnEbTool -ScriptPath (Join-Path $p0ReceiptRaceRelease 'tools\verify-package.ps1') -Arguments @(
        '-Root', $p0ReceiptRaceRelease,
        '-RequireExternalAdmission',
        '-AdmissionReceiptPath', $p0ReceiptRacePath,
        '-ExpectedAdmissionReceiptSha256', $p0ReceiptRaceAdmission.ReceiptSha256,
        '-PackageZipPath', $p0ReceiptRaceAdmission.PackageZipPath
    )
    Assert-AnEbTest -Condition ($p0ReceiptRaceResult.ExitCode -ne 0) -Message 'strict verifier rejects a replaced receipt instead of accepting a stale pin'
    Assert-AnEbTest -Condition ($p0ReceiptRaceResult.Output -notmatch 'PASS PACKAGE_INTEGRITY|READY') -Message 'receipt replacement cannot produce a formal success marker'

    $verifySourceText = [System.IO.File]::ReadAllText($verify)
    $sameByteAdmissionFunction = [regex]::Match(
        $verifySourceText,
        '(?s)function Assert-AnEbExternalArtifactAdmission\s*\{(?<body>.*?)\r?\n\}\r?\n\r?\nfunction Assert-AnEbImmutableZipClosure'
    )
    Assert-AnEbTest -Condition $sameByteAdmissionFunction.Success -Message 'external admission reader has one auditable function boundary'
    $sameByteAdmissionBody = $sameByteAdmissionFunction.Groups['body'].Value
    Assert-AnEbTest -Condition ($sameByteAdmissionBody -match '\$receiptBytes\s*=\s*Read-AnEbBytes\s+-Path\s+\$receiptFull') -Message 'external admission receipt is captured into one byte snapshot'
    Assert-AnEbTest -Condition ($sameByteAdmissionBody -match 'Get-AnEbSha256Bytes\s+-Bytes\s+\$receiptBytes') -Message 'external admission pin hashes the captured byte snapshot'
    Assert-AnEbTest -Condition ($sameByteAdmissionBody -match 'ConvertFrom-AnEbUtf8Strict\s+-Bytes\s+\$receiptBytes') -Message 'external admission JSON parses the same captured byte snapshot'
    Assert-AnEbTest -Condition ($sameByteAdmissionBody -notmatch 'Get-AnEbSha256File\s+-Path\s+\$receiptFull|Read-AnEbUtf8Strict\s+-Path\s+\$receiptFull') -Message 'external admission never reopens the receipt path for hash or parse'

    Assert-AnEbTest -Condition ($sameByteAdmissionBody -match '\$packageZipStream\s*=\s*\[System\.IO\.FileStream\]::new\(') -Message 'external admission captures the immutable ZIP with one shared file handle'
    Assert-AnEbTest -Condition ($sameByteAdmissionBody -match 'Get-AnEbSha256Stream\s+-Stream\s+\$packageZipStream') -Message 'external admission hashes the shared ZIP stream'
    Assert-AnEbTest -Condition ($sameByteAdmissionBody -notmatch 'Get-AnEbSha256File\s+-Path\s+\$packageZipFull') -Message 'external admission never reopens the ZIP path for receipt hash validation'
    $sameByteZipClosureFunction = [regex]::Match(
        $verifySourceText,
        '(?s)function Assert-AnEbImmutableZipClosure\s*\{(?<body>.*?)\r?\n\}\r?\n\r?\n\$packageZipSnapshot\s*=\s*\$null\r?\ntry\s*\{'
    )
    Assert-AnEbTest -Condition $sameByteZipClosureFunction.Success -Message 'immutable ZIP closure has one auditable function boundary'
    $sameByteZipClosureBody = $sameByteZipClosureFunction.Groups['body'].Value
    Assert-AnEbTest -Condition ($sameByteZipClosureBody -match '\[System\.IO\.Stream\]\$PackageZipStream') -Message 'immutable ZIP closure consumes the shared ZIP stream'
    Assert-AnEbTest -Condition ($sameByteZipClosureBody -match 'ZipArchive\]::new\(\$PackageZipStream') -Message 'immutable ZIP closure opens the already admitted stream rather than the caller path'
    Assert-AnEbTest -Condition ($sameByteZipClosureBody -notmatch 'PackageZipPath|OpenRead\s*\(') -Message 'immutable ZIP closure cannot reopen a replaced ZIP path'
    Assert-AnEbTest -Condition ($verifySourceText -match '\$packageZipSnapshot\s*=\s*Assert-AnEbExternalArtifactAdmission') -Message 'formal gate retains the admission ZIP snapshot for closure'
    Assert-AnEbTest -Condition ($verifySourceText -match 'Assert-AnEbImmutableZipClosure[^\r\n]*-PackageZipStream\s+\$packageZipSnapshot\.Stream') -Message 'formal gate closes the exact ZIP stream admitted by the receipt'

    $zipReplacementRelease = Join-Path $tempRoot 'package-zip-replacement-race'
    $zipReplacementPort = Get-Random -Minimum 20000 -Maximum 40000
    New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $zipReplacementRelease -Port $zipReplacementPort -Mode 'success'
    $zipReplacementVerifier = Join-Path $zipReplacementRelease 'tools\verify-package.ps1'
    $zipReplacementSource = [System.IO.File]::ReadAllText($zipReplacementVerifier)
    $zipReplacementInstrumented = [regex]::Replace(
        $zipReplacementSource,
        '(?m)^(\s*\$packageZipSnapshot\s*=\s*Assert-AnEbExternalArtifactAdmission[^\r\n]+)$',
        ('$1' + "`r`n" + '        if (-not [string]::IsNullOrWhiteSpace($env:ANEB_TEST_REPLACE_ADMITTED_ZIP_WITH)) { Move-Item -LiteralPath $env:ANEB_TEST_REPLACE_ADMITTED_ZIP_WITH -Destination $PackageZipPath -Force }'),
        1
    )
    Assert-AnEbTest -Condition ($zipReplacementInstrumented -cne $zipReplacementSource) -Message 'ZIP replacement regression instruments the exact post-admission boundary'
    Write-AnEbTestText -Path $zipReplacementVerifier -Text $zipReplacementInstrumented
    Write-AnEbTestChecksumList -PackageRoot $zipReplacementRelease
    $zipReplacementReceipt = Join-Path $tempRoot 'zip-replacement-race-admission.json'
    $zipReplacementAdmission = New-AnEbTestAdmissionReceipt -FixturePath $zipReplacementRelease -ReceiptPath $zipReplacementReceipt
    $validReplacementZip = Join-Path $tempRoot 'zip-replacement-valid-closure.zip'
    Copy-Item -LiteralPath $zipReplacementAdmission.PackageZipPath -Destination $validReplacementZip
    Write-AnEbTestText -Path $zipReplacementAdmission.PackageZipPath -Text "receipt-only bytes that are not the admitted closure`n"
    $zipReplacementReceiptObject = [System.IO.File]::ReadAllText($zipReplacementReceipt) | ConvertFrom-Json
    $zipReplacementReceiptObject.package_zip_sha256 = (Get-FileHash -LiteralPath $zipReplacementAdmission.PackageZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-AnEbTestText -Path $zipReplacementReceipt -Text (($zipReplacementReceiptObject | ConvertTo-Json -Compress -Depth 8) + [char]10)
    $zipReplacementReceiptSha256 = (Get-FileHash -LiteralPath $zipReplacementReceipt -Algorithm SHA256).Hash.ToLowerInvariant()
    $oldZipReplacementEnvironment = $env:ANEB_TEST_REPLACE_ADMITTED_ZIP_WITH
    try {
        $env:ANEB_TEST_REPLACE_ADMITTED_ZIP_WITH = $validReplacementZip
        $zipReplacementResult = Invoke-AnEbTool -ScriptPath $zipReplacementVerifier -Arguments @(
            '-Root', $zipReplacementRelease,
            '-RequireExternalAdmission',
            '-AdmissionReceiptPath', $zipReplacementReceipt,
            '-ExpectedAdmissionReceiptSha256', $zipReplacementReceiptSha256,
            '-PackageZipPath', $zipReplacementAdmission.PackageZipPath
        )
    }
    finally {
        $env:ANEB_TEST_REPLACE_ADMITTED_ZIP_WITH = $oldZipReplacementEnvironment
    }
    Assert-AnEbTest -Condition ($zipReplacementResult.ExitCode -ne 0) -Message 'formal gate rejects adversarial ZIP replacement between receipt hash and closure'
    Assert-AnEbTest -Condition ($zipReplacementResult.Output -notmatch 'PASS PACKAGE_INTEGRITY') -Message 'adversarial ZIP replacement cannot combine receipt bytes with another closure'
    Assert-AnEbTest -Condition ($zipReplacementResult.Output -notmatch '(?m)^READY(?:\s|$)') -Message 'adversarial ZIP replacement cannot produce a READY marker'

    $wrongSignerRelease = Join-Path $tempRoot 'package-wrong-approved-signer'
    $wrongSignerPort = Get-Random -Minimum 20000 -Maximum 40000
    New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $wrongSignerRelease -Port $wrongSignerPort -Mode 'success'
    $wrongSignerReceipt = Join-Path $tempRoot 'wrong-approved-signer.json'
    $wrongSignerAdmission = New-AnEbTestAdmissionReceipt -FixturePath $wrongSignerRelease -ReceiptPath $wrongSignerReceipt -AndroidSignerCertSha256 ('d' * 64)
    $wrongSignerResult = Invoke-AnEbTool -ScriptPath (Join-Path $wrongSignerRelease 'tools\verify-package.ps1') -Arguments @(
        '-Root', $wrongSignerRelease,
        '-RequireExternalAdmission',
        '-AdmissionReceiptPath', $wrongSignerReceipt,
        '-ExpectedAdmissionReceiptSha256', $wrongSignerAdmission.ReceiptSha256,
        '-PackageZipPath', $wrongSignerAdmission.PackageZipPath
    )
    Assert-AnEbTest -Condition ($wrongSignerResult.ExitCode -ne 0) -Message 'formal release gate rejects an exactly pinned receipt for an unapproved Android signer'
    Assert-AnEbTest -Condition ($wrongSignerResult.Output -match 'P007_ARTIFACT_ADMISSION' -and $wrongSignerResult.Output -notmatch 'PASS EXTERNAL_ARTIFACT_ADMISSION') -Message 'unapproved signer rejection uses the stable external admission boundary'

    $launchDoctorStub = @'
param(
    [string]$Root,
    [int]$Port,
    [switch]$RequireExternalAdmission,
    [string]$AdmissionReceiptPath,
    [string]$ExpectedAdmissionReceiptSha256,
    [string]$PackageZipPath
)
Write-Output 'PASS TEST_ONLY_DOCTOR_STUB'
Write-Output 'PASS LAN_ADDRESSES 192.0.2.10'
exit 0
'@
    $launchContractCases = @('health-wrong-body', 'cap-subset', 'cap-malformed', 'cap-extra')
    if (-not [string]::IsNullOrWhiteSpace($P0LauncherCase)) {
        $launchContractCases = @($P0LauncherCase)
    }
    foreach ($launchContractCase in $launchContractCases) {
        $launchContractFixture = Join-Path $tempRoot ('package-p0-launch-' + $launchContractCase)
        $launchContractPort = Get-Random -Minimum 20000 -Maximum 40000
        New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $launchContractFixture -Port $launchContractPort -Mode $launchContractCase
        Write-AnEbTestText -Path (Join-Path $launchContractFixture 'tools\doctor.ps1') -Text $launchDoctorStub
        $launchContractResult = Invoke-AnEbTool -ScriptPath (Join-Path $launchContractFixture 'tools\launch.ps1') -Arguments @(
            '-Root', $launchContractFixture,
            '-Port', [string]$launchContractPort,
            '-HealthTimeoutSeconds', '3',
            '-ExitAfterReady'
        )
        $launchContractPidLine = @($launchContractResult.Output -split '\r?\n' | Where-Object { $_ -match '^OWNED_SERVER_PID ' })
        if ($launchContractPidLine.Count -eq 1) {
            $launchContractPid = [int]($launchContractPidLine[0].Substring('OWNED_SERVER_PID '.Length).Trim())
            Start-Sleep -Milliseconds 300
            $launchContractOwnedProcess = Get-Process -Id $launchContractPid -ErrorAction SilentlyContinue
            Assert-AnEbTest -Condition ($null -eq $launchContractOwnedProcess) -Message ('launcher test cleanup leaves no owned process for ' + $launchContractCase)
        }
        Assert-AnEbTest -Condition ($launchContractResult.ExitCode -ne 0 -and $launchContractResult.Output -notmatch 'ANEB Prototype 0.1 - READY') -Message ('launcher rejects noncanonical ' + $launchContractCase + ' response')
    }
    if ($P0Only) {
        Write-Output ("PASS P0_S0_RED_ASSERTIONS count=" + $passed)
        exit 0
    }

    $g0Input = Join-Path $tempRoot 'g0-validator-input'
    $g0Output = Join-Path $tempRoot 'g0-validator-output'
    $contractValidator = Join-Path $repo 'contracts\prototype-0.1\validate_contracts.py'
    $g0BuildResult = Invoke-AnEbPythonFile -ScriptPath $contractValidator -Arguments @(
        'emit-bundle',
        '--scenario', 'quick-interrupted',
        '--output', $g0Input,
        '--omit-manifest'
    )
    Assert-AnEbTest -Condition ($g0BuildResult.ExitCode -eq 0 -and $g0BuildResult.Output -match 'G0_BUILD_OK') -Message ('G0 validator generates the canonical six-payload fixture output=' + $g0BuildResult.Output)
    $g0InputFiles = @(Get-ChildItem -LiteralPath $g0Input -File -Force | Sort-Object Name)
    Assert-AnEbTest -Condition ($g0InputFiles.Count -eq 6 -and (@($g0InputFiles.Name) -notcontains 'manifest.json')) -Message 'G0 fixture exposes exactly six finalizer inputs'
    $g0Meta = [System.IO.File]::ReadAllText((Join-Path $g0Input 'meta.json')) | ConvertFrom-Json
    $g0CampaignId = [string]$g0Meta.campaign_id
    Assert-AnEbTest -Condition (-not [string]::IsNullOrWhiteSpace($g0CampaignId)) -Message 'G0 fixture carries a concrete campaign identity for finalizer binding'
    $g0FinalizeResult = Invoke-AnEbTool -ScriptPath $finalizer -Arguments @(
        '-InputDirectory', $g0Input,
        '-OutputRoot', $g0Output,
        '-CampaignId', $g0CampaignId,
        '-PayloadFiles', 'meta.json', 'events.jsonl', 'runs.csv', 'summary.csv', 'report.html', 'run.log',
        '-OfflineReportFile', 'report.html'
    )
    Assert-AnEbTest -Condition ($g0FinalizeResult.ExitCode -eq 0) -Message 'finalizer publishes the G0 validator fixture'
    $g0Published = Join-Path $g0Output $g0CampaignId
    $g0VerifyResult = Invoke-AnEbPythonFile -ScriptPath $contractValidator -Arguments @(
        'verify-bundle',
        '--bundle', $g0Published
    )
    Assert-AnEbTest -Condition ($g0VerifyResult.ExitCode -ne 0 -and $g0VerifyResult.Output -match 'campaign manifest shape is not canonical') -Message 'G0 validator rejects the skeleton NON_RC finalizer output'

    foreach ($junctionName in @('tools', 'static', 'bin', 'android')) {
        $junctionPackage = Join-Path $tempRoot ('package-junction-' + $junctionName)
        Copy-Item -LiteralPath $packageRoot -Destination $junctionPackage -Recurse
        $junctionTarget = Join-Path $tempRoot ('junction-target-' + $junctionName)
        New-Item -ItemType Directory -Path $junctionTarget | Out-Null
        $sourceDirectory = Join-Path $packageRoot $junctionName
        if (Test-Path -LiteralPath $sourceDirectory -PathType Container) {
            Copy-Item -LiteralPath $sourceDirectory -Destination (Join-Path $junctionTarget 'content') -Recurse
        }
        $junctionPath = Join-Path $junctionPackage $junctionName
        if (-not (Test-Path -LiteralPath $junctionPath -PathType Container)) {
            New-Item -ItemType Directory -Path $junctionPath | Out-Null
        }
        Remove-Item -LiteralPath $junctionPath -Force -Recurse
        New-AnEbTestJunction -Link $junctionPath -Target $junctionTarget
        $junctionScript = $verify
        $junctionResult = Invoke-AnEbTool -ScriptPath $junctionScript -Arguments @('-Root', $junctionPackage, '-AllowSkeleton')
        Assert-AnEbTest -Condition ($junctionResult.ExitCode -ne 0) -Message ('verifier rejects a parent junction at ' + $junctionName)
        Assert-AnEbTest -Condition ($junctionResult.Output -match 'REPARSE|INTEGRITY|PACKAGE') -Message ('parent junction rejection is explicit for ' + $junctionName)
    }

    $untrustedRelease = Join-Path $tempRoot 'package-untrusted-release'
    $untrustedPort = Get-Random -Minimum 20000 -Maximum 40000
    New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $untrustedRelease -Port $untrustedPort -Mode 'success'
    $untrustedResult = Invoke-AnEbTool -ScriptPath (Join-Path $untrustedRelease 'tools\verify-package.ps1') -Arguments @('-Root', $untrustedRelease)
    Assert-AnEbTest -Condition ($untrustedResult.ExitCode -eq 0) -Message 'ordinary user verification accepts a self-contained release candidate after runtime integrity checks'
    Assert-AnEbTest -Condition ($untrustedResult.Output -match 'runtime integrity only' -and $untrustedResult.Output -notmatch 'PASS EXTERNAL_ARTIFACT_ADMISSION') -Message 'ordinary user verification never claims external artifact admission'

    foreach ($launchMode in @('success', 'health-fail', 'cap-fail')) {
        $launchFixture = Join-Path $tempRoot ('package-launch-' + $launchMode)
        $launchPort = Get-Random -Minimum 20000 -Maximum 40000
        New-AnEbTestReleaseFixture -SourcePackage $packageRoot -FixturePath $launchFixture -Port $launchPort -Mode $launchMode
        Write-AnEbTestText -Path (Join-Path $launchFixture 'tools\doctor.ps1') -Text $launchDoctorStub
        $admissionPath = Join-Path $tempRoot ('admission-' + $launchMode + '.json')
        $admission = New-AnEbTestAdmissionReceipt -FixturePath $launchFixture -ReceiptPath $admissionPath
        $launchTestResult = Invoke-AnEbTool -ScriptPath (Join-Path $launchFixture 'tools\launch.ps1') -Arguments @(
            '-Root', $launchFixture,
            '-Port', [string]$launchPort,
            '-HealthTimeoutSeconds', '3',
            '-RequireExternalAdmission',
            '-AdmissionReceiptPath', $admissionPath,
            '-ExpectedAdmissionReceiptSha256', $admission.ReceiptSha256,
            '-PackageZipPath', $admission.PackageZipPath,
            '-ExitAfterReady'
        )
        $pidLine = @($launchTestResult.Output -split '\r?\n' | Where-Object { $_ -match '^OWNED_SERVER_PID ' })
        Assert-AnEbTest -Condition ($pidLine.Count -eq 1) -Message ('launcher records one owned server PID for ' + $launchMode)
        $ownedPid = [int]($pidLine[0].Substring('OWNED_SERVER_PID '.Length).Trim())
        Start-Sleep -Milliseconds 300
        $ownedProcess = Get-Process -Id $ownedPid -ErrorAction SilentlyContinue
        Assert-AnEbTest -Condition ($null -eq $ownedProcess) -Message ('launcher cleanup leaves no owned process for ' + $launchMode)
        if ($launchMode -eq 'success') {
            Assert-AnEbTest -Condition ($launchTestResult.ExitCode -eq 0 -and $launchTestResult.Output -match 'ANEB Prototype 0.1 - READY') -Message 'launcher success path exits cleanly after readiness'
        }
        else {
            Assert-AnEbTest -Condition ($launchTestResult.ExitCode -ne 0 -and $launchTestResult.Output -notmatch 'ANEB Prototype 0.1 - READY') -Message ('launcher rejects ' + $launchMode + ' and still cleans up')
        }
    }

    Write-Output ("PASS RELEASE_SKELETON_TESTS count=" + $passed)
    exit 0
}
catch {
    Write-Output ('FAIL RELEASE_SKELETON_TESTS ' + $_.Exception.Message)
    exit 1
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
