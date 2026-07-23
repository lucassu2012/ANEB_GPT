# collect_token_quick_evidence.ps1
# D-82 bounded Token Quick evidence collector. PowerShell 5.1 compatible.
#
# PreflightOnly is deliberately local-only: it validates immutable input and tool
# availability, but it never calls ADB, SSH, curl, apksigner, or Python and never
# creates an evidence directory.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AdbSerial,
    [Parameter(Mandatory = $true)][string]$ServerBase,
    [Parameter(Mandatory = $true)][string]$Remote,
    [Parameter(Mandatory = $true)][string]$SshKey,
    [Parameter(Mandatory = $true)][string]$KnownHostsPath,
    [Parameter(Mandatory = $true)][string]$DevicePolicyPath,
    [Parameter(Mandatory = $true)][string]$CandidateDirectory,
    [Parameter(Mandatory = $true)][string]$GhPath,
    [Parameter(Mandatory = $true)][string]$ExpectedServerBinarySha256,
    [string]$ExpectedServerVersion = 'aneb-server/0.8.0',
    [ValidateSet('positive', 'negative')][string]$EvidenceMode = 'positive',
    [ValidateSet('auto', 'wifi', 'cellular')][string]$Transport = 'auto',
    [string]$EvidenceRoot = (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ANEB\ValidationEvidence'),
    [switch]$PreflightOnly,
    [ValidateRange(60, 1800)][int]$RunTimeoutSeconds = 900,
    [ValidateRange(120, 3600)][int]$LockTtlSeconds = 1200,
    [ValidateRange(1, 120)][int]$AdbCommandTimeoutSeconds = 30,
    [ValidateRange(5, 300)][int]$SshCommandTimeoutSeconds = 60,
    [ValidateRange(5, 600)][int]$ToolCommandTimeoutSeconds = 120,
    [string]$AdbPath = 'adb',
    [string]$SshPath = 'ssh',
    [string]$CurlPath = 'curl.exe',
    [string]$ApksignerPath = 'apksigner',
    [string]$PythonPath = 'python',
    [string]$AndroidBuildToolsDir = '',
    [string]$ServerCaPath = '',
    [string]$DeriveHelperPath = (Join-Path $PSScriptRoot 'prepare_token_run_evidence.py'),
    [string]$AuditVerifierPath = (Join-Path $PSScriptRoot 'verify_token_run_audit.py'),
    [string]$ClientDbVerifierPath = (Join-Path $PSScriptRoot 'verify_token_quick_client_db.py'),
    [string]$NegativeProxyPath = (Join-Path $PSScriptRoot 'token_serverinfo_negative_proxy.py'),
    [string]$NegativeProxyEvidenceVerifierPath = '',
    [string]$NegativeClientDbVerifierPath = (Join-Path $PSScriptRoot 'verify_token_quick_negative_client_db.py'),
    [string]$BundleVerifierPath = (Join-Path $PSScriptRoot 'verify_token_quick_evidence_bundle.py'),
    [string]$CiProvenanceVerifierPath = '',
    [string]$ResultJsonlVerifierPath = (Join-Path $PSScriptRoot 'verify_result_jsonl.py')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$PackageName = 'com.aneb.probe.codex'
$ClaudePackageName = 'com.aneb.probe'
$ExpectedVersionName = '0.5.12-codex'
$ExpectedVersionCode = 44
$ProfileContract = 'token_multimodal_quick@1.2.1'
$LauncherComponent = 'com.huawei.android.launcher/.unihome.UniHomeLauncher'
$RemoteLockPath = '/run/lock/aneb-deploy.lock'
$CurlParentTimeoutSeconds = 30
$NegativeProxyDevicePort = 18765
$NegativeClientServerBase = "http://127.0.0.1:$NegativeProxyDevicePort"
$script:ExecutionMode = if ($EvidenceMode -ceq 'negative') {
    'negative_receipt_missing'
} else {
    'positive'
}
$script:EvidenceScope = if ($EvidenceMode -ceq 'negative') {
    'd82_token_quick_contract_rejection_acceptance'
} else {
    'd82_token_quick_cross_bound_acceptance'
}
$CandidateInstallNotesName = 'ANEB-' +
    [char]0x5B89 + [char]0x88C5 + [char]0x8BF4 + [char]0x660E + '.txt'
$CandidatePayloadNames = @(
    'ANEB-Probe-0.5.12-codex-debug.apk',
    'build-manifest.json',
    'checksums.sha256',
    'provenance.sigstore.json',
    $CandidateInstallNotesName
)
$DevicePropertyKeys = @(
    'ro.serialno',
    'ro.boot.serialno',
    'ro.product.manufacturer',
    'ro.product.model',
    'ro.product.device',
    'ro.product.name',
    'ro.build.fingerprint',
    'ro.build.version.security_patch',
    'ro.boot.verifiedbootstate',
    'ro.boot.vbmeta.device_state',
    'ro.boot.flash.locked',
    'ro.boot.veritymode'
)
$DeviceOptionalPropertyKeys = @(
    'ro.serialno', 'ro.boot.serialno',
    'ro.boot.verifiedbootstate', 'ro.boot.vbmeta.device_state',
    'ro.boot.flash.locked', 'ro.boot.veritymode'
)
$CatalogPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'spec\catalog.json'
if ([string]::IsNullOrWhiteSpace($CiProvenanceVerifierPath)) {
    $CiProvenanceVerifierPath = Join-Path $PSScriptRoot 'verify_ci_apk_provenance.py'
}
if ([string]::IsNullOrWhiteSpace($NegativeProxyEvidenceVerifierPath)) {
    $NegativeProxyEvidenceVerifierPath = Join-Path `
        $PSScriptRoot `
        'verify_token_quick_negative_proxy_evidence.py'
}
if ([string]::IsNullOrWhiteSpace($ServerCaPath)) {
    $ServerCaPath = Join-Path `
        (Split-Path -Parent $PSScriptRoot) `
        'app\probe\src\main\res\raw\aneb_ip_ca.pem'
}
$ConflictPackages = @(
    $ClaudePackageName,
    $PackageName,
    'com.emanuelef.remote_capture',
    'com.pcapdroid.mitm',
    'com.wireguard.android'
)

function Assert-NonEmptyFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
    if ((Get-Item -LiteralPath $Path).Length -le 0) {
        throw "$Label is empty: $Path"
    }
}

function Resolve-RegularNonReparseFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ReasonPrefix
    )
    try {
        $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
        if ($resolved.Provider.Name -cne 'FileSystem') {
            throw 'not_filesystem'
        }
        $item = Get-Item -LiteralPath $resolved.ProviderPath -Force -ErrorAction Stop
    } catch {
        throw "${ReasonPrefix}_unavailable"
    }
    if ($item.PSIsContainer -or $item.Length -le 0 -or
        (($item.Attributes -band [IO.FileAttributes]::Directory) -ne 0) -or
        (($item.Attributes -band [IO.FileAttributes]::Device) -ne 0) -or
        (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw "${ReasonPrefix}_not_regular_nonreparse_file"
    }
    $current = $item.FullName
    while ($true) {
        try {
            $component = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        } catch {
            throw "${ReasonPrefix}_unavailable"
        }
        if (($component.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "${ReasonPrefix}_not_regular_nonreparse_file"
        }
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) {
            break
        }
        $current = $parent.FullName
    }
    return $item.FullName
}

function Get-DevicePolicyIdentity {
    param([Parameter(Mandatory = $true)][string]$Path)
    $resolved = Resolve-RegularNonReparseFile `
        -Path $Path `
        -ReasonPrefix 'device_policy'
    $item = Get-Item -LiteralPath $resolved -Force
    if ($item.Length -gt 65536) {
        throw 'device_policy_invalid'
    }
    try {
        $raw = [IO.File]::ReadAllBytes($resolved)
        $utf8 = New-Object Text.UTF8Encoding($false, $true)
        $text = $utf8.GetString($raw)
        Add-Type -AssemblyName System.Runtime.Serialization -ErrorAction Stop
        $reader = [Runtime.Serialization.Json.JsonReaderWriterFactory]::CreateJsonReader(
            $raw,
            [Xml.XmlDictionaryReaderQuotas]::Max
        )
        $document = New-Object Xml.XmlDocument
        $document.Load($reader)
        $reader.Dispose()
    } catch {
        throw 'device_policy_invalid'
    }
    $visit = $null
    $visit = {
        param([Xml.XmlNode]$Node)
        if ($Node.NodeType -eq [Xml.XmlNodeType]::Element -and
            $null -ne $Node.Attributes['type'] -and
            $Node.Attributes['type'].Value -ceq 'object') {
            $names = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
            foreach ($child in @($Node.ChildNodes | Where-Object {
                $_.NodeType -eq [Xml.XmlNodeType]::Element
            })) {
                if (-not $names.Add([string]$child.LocalName)) {
                    throw 'device_policy_invalid'
                }
            }
        }
        foreach ($child in @($Node.ChildNodes)) {
            & $visit $child
        }
    }
    try {
        & $visit $document.DocumentElement
        $policy = $text | ConvertFrom-Json
    } catch {
        throw 'device_policy_invalid'
    }
    $expectedTop = @(
        'adb_serial_sha256', 'device_alias', 'properties',
        'schema', 'schema_version'
    )
    $actualTop = @($policy.PSObject.Properties.Name | Sort-Object)
    if (($expectedTop -join "`0") -cne ($actualTop -join "`0") -or
        [string]$policy.schema -cne 'aneb-device-identity-policy' -or
        [string]$policy.schema_version -cne '1.0.0' -or
        [string]$policy.device_alias -cne 'P40 Pro' -or
        [string]$policy.adb_serial_sha256 -notmatch '^[0-9a-f]{64}$' -or
        $null -eq $policy.properties) {
        throw 'device_policy_invalid'
    }
    $expectedProperties = @($DevicePropertyKeys | Sort-Object)
    $actualProperties = @($policy.properties.PSObject.Properties.Name | Sort-Object)
    if (($expectedProperties -join "`0") -cne ($actualProperties -join "`0")) {
        throw 'device_policy_invalid'
    }
    foreach ($key in $DevicePropertyKeys) {
        $property = $policy.properties.PSObject.Properties[$key]
        if ($null -eq $property -or $property.Value -isnot [string]) {
            throw 'device_policy_invalid'
        }
        $value = [string]$property.Value
        if ((New-Object Text.UTF8Encoding($false)).GetByteCount($value) -gt 2048 -or
            $value -match "[`r`n`0]" -or
            ($DeviceOptionalPropertyKeys -cnotcontains $key -and $value.Length -eq 0)) {
            throw 'device_policy_invalid'
        }
    }
    return [pscustomobject]@{
        Path = $resolved
        Sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
        AdbSerialSha256 = [string]$policy.adb_serial_sha256
        Properties = $policy.properties
        Alias = [string]$policy.device_alias
    }
}

function Assert-CanonicalRepositoryAsset {
    param(
        [Parameter(Mandatory = $true)][string]$CandidatePath,
        [Parameter(Mandatory = $true)][string]$ExpectedPath,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$Label
    )
    try {
        $rootFull = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
        $candidateFull = [IO.Path]::GetFullPath($CandidatePath)
        $expectedFull = [IO.Path]::GetFullPath($ExpectedPath)
    } catch {
        throw "repository_asset_path_invalid label=$Label"
    }
    if (-not $candidateFull.Equals($expectedFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "repository_asset_path_mismatch label=$Label"
    }
    $repositoryPrefix = $rootFull + [IO.Path]::DirectorySeparatorChar
    if (-not $expectedFull.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "repository_asset_outside_root label=$Label"
    }
    if (-not (Test-Path -LiteralPath $expectedFull -PathType Leaf)) {
        throw "repository_asset_missing label=$Label"
    }
    $leaf = Get-Item -LiteralPath $expectedFull -Force
    if ($leaf.Length -le 0) {
        throw "repository_asset_empty label=$Label"
    }

    $current = $expectedFull
    while ($true) {
        try {
            $item = Get-Item -LiteralPath $current -Force
        } catch {
            throw "repository_asset_component_unavailable label=$Label"
        }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "repository_asset_reparse_point_forbidden label=$Label"
        }
        if ($current.Equals($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) {
            throw "repository_asset_parent_chain_invalid label=$Label"
        }
        $current = $parent.FullName.TrimEnd('\', '/')
        if (-not $current.Equals($rootFull, [StringComparison]::OrdinalIgnoreCase) -and
            -not $current.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "repository_asset_parent_chain_invalid label=$Label"
        }
    }
    return $expectedFull
}

function Resolve-Executable {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Label command is empty."
    }
    if (Test-Path -LiteralPath $Value -PathType Leaf) {
        return (Get-Item -LiteralPath $Value).FullName
    }
    $command = Get-Command -Name $Value -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $command) {
        throw "$Label command is unavailable: $Value"
    }
    return $command.Source
}

function Resolve-CiCandidateDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
        if ($resolved.Provider.Name -cne 'FileSystem') {
            throw 'not_filesystem'
        }
        $directory = Get-Item -LiteralPath $resolved.ProviderPath -Force -ErrorAction Stop
    } catch {
        throw 'ci_candidate_directory_invalid'
    }
    if (-not $directory.PSIsContainer -or
        ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'ci_candidate_directory_invalid'
    }
    $current = $directory.FullName
    while ($true) {
        $component = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($component.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'ci_candidate_reparse_point_forbidden'
        }
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) {
            break
        }
        $current = $parent.FullName
    }
    $children = @(Get-ChildItem -LiteralPath $directory.FullName -Force)
    if ($children.Count -ne $CandidatePayloadNames.Count) {
        throw 'ci_candidate_file_set_invalid'
    }
    $expected = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($name in $CandidatePayloadNames) {
        $null = $expected.Add($name)
    }
    foreach ($child in $children) {
        if (-not $expected.Remove([string]$child.Name) -or
            $child.PSIsContainer -or
            ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $child.Length -le 0) {
            throw 'ci_candidate_file_set_invalid'
        }
    }
    if ($expected.Count -ne 0) {
        throw 'ci_candidate_file_set_invalid'
    }
    return $directory.FullName
}

function Resolve-AndroidBuildToolsDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$ResolvedApksignerPath,
        [AllowEmptyString()][string]$CandidateDirectory
    )
    try {
        $directory = if ([string]::IsNullOrWhiteSpace($CandidateDirectory)) {
            [IO.Path]::GetFullPath((Split-Path -Parent $ResolvedApksignerPath))
        } else {
            [IO.Path]::GetFullPath($CandidateDirectory)
        }
    } catch {
        throw 'android_build_tools_path_invalid'
    }
    if ((Split-Path -Leaf $directory) -cne '35.0.0' -or
        -not (Test-Path -LiteralPath $directory -PathType Container)) {
        throw 'android_build_tools_35_0_0_required'
    }
    $directoryItem = Get-Item -LiteralPath $directory -Force
    if (($directoryItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'android_build_tools_reparse_point_forbidden'
    }
    $apksignerParent = [IO.Path]::GetFullPath((Split-Path -Parent $ResolvedApksignerPath))
    $apksignerName = Split-Path -Leaf $ResolvedApksignerPath
    if (-not $apksignerParent.Equals($directory, [StringComparison]::OrdinalIgnoreCase) -or
        $apksignerName -notmatch '^apksigner(?:\.bat|\.cmd|\.exe)?$') {
        throw 'apksigner_not_from_android_build_tools_35_0_0'
    }
    foreach ($required in @(
        (Join-Path $directory 'aapt2.exe'),
        (Join-Path $directory 'lib\apksigner.jar')
    )) {
        Assert-NonEmptyFile -Path $required -Label 'Android build-tools 35.0.0 identity tool'
        if (((Get-Item -LiteralPath $required -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'android_build_tools_reparse_point_forbidden'
        }
    }
    return $directoryItem.FullName
}

function Get-ExpectedProfileContractDefinitionSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    Assert-NonEmptyFile -Path $Path -Label 'spec catalog'
    try {
        $catalog = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw 'spec_catalog_json_invalid'
    }
    if ([string]$catalog.catalog_id -cne 'aneb-spec-catalog' -or
        [string]$catalog.catalog_version -notmatch '^1\.[0-9]+\.[0-9]+$') {
        throw 'spec_catalog_identity_invalid'
    }
    $matches = @($catalog.execution_evidence_contracts | Where-Object {
        [string]$_.contract_id -ceq 'aneb-token-quick-request-entry-counts' -and
        [string]$_.version -ceq '1.0.0' -and
        [string]$_.path -ceq 'spec/execution-contracts/token_multimodal_quick-1.2.1.request-entry.json'
    })
    if ($matches.Count -ne 1) {
        throw "spec_catalog_token_quick_contract_count_invalid count=$($matches.Count)"
    }
    $digest = [string]$matches[0].canonical_sha256
    if ($digest -notmatch '^[0-9a-f]{64}$') {
        throw 'spec_catalog_token_quick_contract_digest_invalid'
    }
    return $digest
}

function Get-LocalGitIdentity {
    param(
        [Parameter(Mandatory = $true)][string]$GitPath,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )
    $commitOutput = @(& $GitPath '-C' $RepositoryRoot 'rev-parse' '--verify' 'HEAD' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git_head_unavailable output=$($commitOutput -join '|')"
    }
    $commit = ($commitOutput -join '').Trim().ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{40}$') {
        throw 'git_head_invalid'
    }
    $statusOutput = @(& $GitPath '-C' $RepositoryRoot 'status' '--porcelain=v1' '--untracked-files=all' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git_status_unavailable output=$($statusOutput -join '|')"
    }
    return [pscustomobject]@{
        Commit = $commit
        Dirty = $statusOutput.Count -gt 0
    }
}

function Assert-ToolingFileStable {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($ExpectedSha256 -notmatch '^[0-9a-f]{64}$' -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "tooling_identity_invalid label=$Label"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $ExpectedSha256) {
        throw "tooling_digest_drift label=$Label expected=$ExpectedSha256 actual=$actual"
    }
}

function Get-ServerCaIdentity {
    param([Parameter(Mandatory = $true)][string]$Path)
    Assert-NonEmptyFile -Path $Path -Label 'ANEB server CA certificate'
    if ((Get-Item -LiteralPath $Path).Length -gt 65536) {
        throw 'server_ca_file_too_large'
    }
    try {
        $certificate = New-Object `
            Security.Cryptography.X509Certificates.X509Certificate2 `
            -ArgumentList @((Resolve-Path -LiteralPath $Path).Path)
    } catch {
        throw 'server_ca_certificate_invalid'
    }
    $basicConstraints = @($certificate.Extensions | Where-Object {
        $_ -is [Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]
    })
    if ($certificate.HasPrivateKey -or
        $certificate.Subject -cne $certificate.Issuer -or
        $basicConstraints.Count -ne 1 -or
        -not $basicConstraints[0].CertificateAuthority -or
        [DateTime]::UtcNow -lt $certificate.NotBefore.ToUniversalTime() -or
        [DateTime]::UtcNow -gt $certificate.NotAfter.ToUniversalTime()) {
        throw 'server_ca_certificate_contract_invalid'
    }
    return [pscustomobject]@{
        Sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        Thumbprint = $certificate.Thumbprint.ToLowerInvariant()
        Subject = $certificate.Subject
        NotAfterUtc = $certificate.NotAfter.ToUniversalTime().ToString(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            [Globalization.CultureInfo]::InvariantCulture
        )
    }
}

function Assert-ExpectedFileSha256 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$' -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "expected_file_identity_invalid label=$Label"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $ExpectedSha256.ToLowerInvariant()) {
        throw "expected_file_sha256_mismatch label=$Label expected=$($ExpectedSha256.ToLowerInvariant()) actual=$actual"
    }
    return $actual
}

function Assert-ToolingProvenanceStable {
    param(
        [Parameter(Mandatory = $true)]$ResolvedTools
    )
    if ($script:ResolvedTools.ToolingFiles.Count -ne 25 -or
        $script:ResolvedTools.ToolingProvenance.files.Count -ne 25) {
        throw 'tooling_closure_count_invalid'
    }
    foreach ($entry in $ResolvedTools.ToolingFiles.GetEnumerator()) {
        $null = Assert-CanonicalRepositoryAsset `
            -CandidatePath ([string]$entry.Value) `
            -ExpectedPath ([string]$entry.Value) `
            -RepositoryRoot ([string]$ResolvedTools.RepositoryRoot) `
            -Label ([string]$entry.Key)
        Assert-ToolingFileStable `
            -Path ([string]$entry.Value) `
            -ExpectedSha256 ([string]$ResolvedTools.ToolingProvenance.files[$entry.Key]) `
            -Label ([string]$entry.Key)
    }
    $stableKnownHosts = Resolve-RegularNonReparseFile `
        -Path ([string]$ResolvedTools.KnownHosts) `
        -ReasonPrefix 'ssh_known_hosts'
    if (-not $stableKnownHosts.Equals(
        [string]$ResolvedTools.KnownHosts,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw 'ssh_known_hosts_path_drift'
    }
    Assert-ToolingFileStable `
        -Path $stableKnownHosts `
        -ExpectedSha256 ([string]$ResolvedTools.ToolingProvenance.external_inputs.ssh_known_hosts_sha256) `
        -Label 'ssh_known_hosts'
    $stableDevicePolicy = Resolve-RegularNonReparseFile `
        -Path ([string]$ResolvedTools.DevicePolicy.Path) `
        -ReasonPrefix 'device_policy'
    if (-not $stableDevicePolicy.Equals(
        [string]$ResolvedTools.DevicePolicy.Path,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw 'device_policy_path_drift'
    }
    Assert-ToolingFileStable `
        -Path $stableDevicePolicy `
        -ExpectedSha256 ([string]$ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256) `
        -Label 'device_policy'
    $gitIdentity = Get-LocalGitIdentity `
        -GitPath $ResolvedTools.Git `
        -RepositoryRoot $ResolvedTools.RepositoryRoot
    if ($gitIdentity.Commit -cne [string]$ResolvedTools.ToolingProvenance.source_commit -or
        [bool]$gitIdentity.Dirty) {
        throw (
            'source_identity_drift expected_commit={0} actual_commit={1} dirty={2}' -f
            [string]$ResolvedTools.ToolingProvenance.source_commit,
            [string]$gitIdentity.Commit,
            [bool]$gitIdentity.Dirty
        )
    }
}

function Assert-NonReparseDirectoryChain {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ReasonPrefix
    )
    try {
        $fullPath = [IO.Path]::GetFullPath($Path)
    } catch {
        throw "${ReasonPrefix}_path_invalid"
    }
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        if ($fullPath -notmatch '^[A-Za-z]:[\\/]' -or
            $fullPath.StartsWith('\\', [StringComparison]::Ordinal) -or
            $fullPath.Substring(2).Contains(':')) {
            throw "${ReasonPrefix}_path_invalid"
        }
    } elseif (-not [IO.Path]::IsPathRooted($fullPath)) {
        throw "${ReasonPrefix}_path_invalid"
    }

    $current = $fullPath
    while (-not (Test-Path -LiteralPath $current)) {
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) {
            throw "${ReasonPrefix}_parent_chain_invalid"
        }
        $current = $parent.FullName
    }
    while ($true) {
        try {
            $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        } catch {
            throw "${ReasonPrefix}_parent_chain_unavailable"
        }
        if (-not $item.PSIsContainer) {
            throw "${ReasonPrefix}_parent_chain_invalid"
        }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "${ReasonPrefix}_reparse_point_forbidden component=$current"
        }
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) {
            break
        }
        $current = $parent.FullName
    }
    return $fullPath
}

function Assert-PrivateEvidenceRoot {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        $acl = Get-Acl -LiteralPath $Path
    } catch {
        throw 'evidence_root_acl_unavailable'
    }
    try {
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $ownerText = [string]$acl.Owner
        try {
            $ownerSid = [Security.Principal.SecurityIdentifier]::new($ownerText).Value
        } catch {
            $ownerSid = [Security.Principal.NTAccount]::new($ownerText).
                Translate([Security.Principal.SecurityIdentifier]).Value
        }
    } catch {
        throw 'evidence_root_acl_owner_unverifiable'
    }
    if ([string]::IsNullOrWhiteSpace($currentSid) -or $ownerSid -cne $currentSid) {
        throw 'evidence_root_owner_invalid'
    }
    $allowedWriterSids = @($currentSid, 'S-1-5-18', 'S-1-5-32-544')
    $writeMask = [Security.AccessControl.FileSystemRights]::WriteData -bor
        [Security.AccessControl.FileSystemRights]::AppendData -bor
        [Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
        [Security.AccessControl.FileSystemRights]::WriteAttributes -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    foreach ($rule in $acl.Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            continue
        }
        try {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        } catch {
            throw 'evidence_root_acl_identity_unverifiable'
        }
        if ($allowedWriterSids -cnotcontains $sid -and
            (($rule.FileSystemRights -band $writeMask) -ne 0)) {
            throw "evidence_root_acl_too_permissive sid=$sid"
        }
    }
}

function Assert-RemoteServerHostBinding {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteValue,
        [Parameter(Mandatory = $true)][string]$ServerBaseValue,
        [Parameter(Mandatory = $true)][Uri]$ServerUri
    )
    if ($RemoteValue -notmatch '^([A-Za-z0-9._-]+)@([A-Za-z0-9.-]+)$') {
        throw 'remote_server_host_binding_invalid'
    }
    $remoteHost = [string]$Matches[2]
    if ($ServerBaseValue -notmatch '^https://(?<host>[A-Za-z0-9.-]+)(?::(?<port>[1-9][0-9]{0,4}))?/?$') {
        throw 'remote_server_host_binding_invalid'
    }
    $originHost = [string]$Matches['host']
    $originPort = [string]$Matches['port']
    if (-not [string]::IsNullOrEmpty($originPort)) {
        $parsedPort = 0
        if (-not [int]::TryParse($originPort, [ref]$parsedPort) -or
            $parsedPort -lt 1 -or $parsedPort -gt 65535 -or
            $parsedPort.ToString() -cne $originPort) {
            throw 'remote_server_host_binding_invalid'
        }
    }
    if (-not $originHost.Equals($ServerUri.Host, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'remote_server_host_binding_invalid'
    }
    if ($remoteHost.Length -gt 253 -or
        $remoteHost.StartsWith('.') -or
        $remoteHost.EndsWith('.') -or
        $remoteHost.Contains('..')) {
        throw 'remote_server_host_binding_invalid'
    }
    $parsedAddress = $null
    if ([Net.IPAddress]::TryParse($remoteHost, [ref]$parsedAddress)) {
        if ($parsedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
            $parsedAddress.ToString() -cne $remoteHost) {
            throw 'remote_server_host_binding_invalid'
        }
    } else {
        foreach ($label in $remoteHost.Split('.')) {
            if ($label.Length -lt 1 -or $label.Length -gt 63 -or
                $label -notmatch '^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$') {
                throw 'remote_server_host_binding_invalid'
            }
        }
    }
    if (-not $remoteHost.Equals($originHost, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'remote_server_host_binding_invalid'
    }
    return $remoteHost.ToLowerInvariant()
}

function Assert-LocalPreflight {
    if ($AdbSerial -notmatch '^[A-Za-z0-9._:-]{4,128}$') {
        throw 'AdbSerial contains unsafe characters.'
    }
    $uri = $null
    if (-not [Uri]::TryCreate($ServerBase, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -cne 'https' -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment) -or
        $uri.AbsolutePath -ne '/') {
        throw 'ServerBase must be an origin-only HTTPS URL without credentials, path, query, or fragment.'
    }
    $negativeProxyUpstreamUrl = $null
    if ($EvidenceMode -ceq 'negative') {
        $negativeUpstreamAddress = $null
        if (-not [Net.IPAddress]::TryParse($uri.Host, [ref]$negativeUpstreamAddress) -or
            $negativeUpstreamAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
            $negativeUpstreamAddress.ToString() -cne $uri.Host) {
            throw 'negative_proxy_upstream_must_be_ipv4_literal'
        }
        $negativeProxyUpstreamUrl = "https://$($uri.Host):$($uri.Port)/api/v1/serverinfo"
    }
    $boundRemoteHost = Assert-RemoteServerHostBinding `
        -RemoteValue $Remote `
        -ServerBaseValue $ServerBase `
        -ServerUri $uri
    if ($ExpectedServerVersion -notmatch '^aneb-server/[0-9]+\.[0-9]+\.[0-9]+$') {
        throw 'ExpectedServerVersion must be a full aneb-server/<semver> identity.'
    }
    foreach ($digest in @(
        @{ Label = 'ExpectedServerBinarySha256'; Value = $ExpectedServerBinarySha256 }
    )) {
        if ([string]$digest.Value -notmatch '^[0-9a-fA-F]{64}$') {
            throw ("{0} must be exactly 64 hexadecimal characters." -f $digest.Label)
        }
    }
    if ($LockTtlSeconds -le $RunTimeoutSeconds + 60) {
        throw 'LockTtlSeconds must exceed RunTimeoutSeconds by more than 60 seconds.'
    }
    Assert-NonEmptyFile -Path $SshKey -Label 'SSH private key'
    $resolvedKnownHosts = Resolve-RegularNonReparseFile `
        -Path $KnownHostsPath `
        -ReasonPrefix 'ssh_known_hosts'
    $devicePolicy = Get-DevicePolicyIdentity -Path $DevicePolicyPath
    if ([string]$devicePolicy.AdbSerialSha256 -cne (Get-Utf8StringSha256 -Value $AdbSerial)) {
        throw 'device_policy_input_serial_mismatch'
    }
    $repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\', '/')
    $collectorPath = Join-Path $repositoryRoot 'scripts\collect_token_quick_evidence.ps1'
    $deriveHelperExpectedPath = Join-Path $repositoryRoot 'scripts\prepare_token_run_evidence.py'
    $auditVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_run_audit.py'
    $clientDbVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_quick_client_db.py'
    $negativeProxyExpectedPath = Join-Path $repositoryRoot 'scripts\token_serverinfo_negative_proxy.py'
    $negativeProxyEvidenceVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_quick_negative_proxy_evidence.py'
    $negativeClientDbVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_quick_negative_client_db.py'
    $bundleVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_quick_evidence_bundle.py'
    $ciProvenanceVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_ci_apk_provenance.py'
    $ciWorkflowExpectedPath = Join-Path $repositoryRoot '.github\workflows\ci.yml'
    $debugCandidatePackagerExpectedPath = Join-Path $repositoryRoot 'scripts\package_debug_candidate.py'
    $resultJsonlVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_result_jsonl.py'
    $timeChainVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_quick_time_chain.py'
    $rawStateVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_quick_raw_state.py'
    $deviceIdentityVerifierExpectedPath = Join-Path $repositoryRoot 'scripts\verify_token_quick_device_identity.py'
    $catalogExpectedPath = Join-Path $repositoryRoot 'spec\catalog.json'
    $serverCaExpectedPath = Join-Path $repositoryRoot 'app\probe\src\main\res\raw\aneb_ip_ca.pem'
    $requestEntryContractPath = Join-Path $repositoryRoot 'spec\execution-contracts\token_multimodal_quick-1.2.1.request-entry.json'
    $profileBundleRoot = Join-Path $repositoryRoot 'profiles\published\token_multimodal_quick'
    $profileManifestPath = Join-Path $profileBundleRoot 'manifest.sha256'
    $profileDefinitionPath = Join-Path $profileBundleRoot 'profile.json'
    $runtimePlanPath = Join-Path $profileBundleRoot 'runtime_plan.json'
    $resultSchemaCorePath = Join-Path $repositoryRoot 'spec\schemas\aneb-result-core-v1.schema.json'
    $resultSchemaV1Path = Join-Path $repositoryRoot 'spec\schemas\aneb-result-v1.schema.json'
    $resultSchemaV2Path = Join-Path $repositoryRoot 'spec\schemas\aneb-result-v2.schema.json'
    $roomSchemaV19Path = Join-Path $repositoryRoot 'app\probe\schemas\com.aneb.probe.data.AnebDatabase\19.json'

    $assetCandidates = [ordered]@{
        collector = $PSCommandPath
        derive_helper = $DeriveHelperPath
        audit_verifier = $AuditVerifierPath
        client_db_verifier = $ClientDbVerifierPath
        negative_proxy = $NegativeProxyPath
        negative_proxy_evidence_verifier = $NegativeProxyEvidenceVerifierPath
        negative_client_db_verifier = $NegativeClientDbVerifierPath
        bundle_verifier = $BundleVerifierPath
        ci_provenance_verifier = $CiProvenanceVerifierPath
        ci_workflow = $ciWorkflowExpectedPath
        debug_candidate_packager = $debugCandidatePackagerExpectedPath
        result_jsonl_verifier = $ResultJsonlVerifierPath
        time_chain_verifier = $timeChainVerifierExpectedPath
        raw_state_verifier = $rawStateVerifierExpectedPath
        device_identity_verifier = $deviceIdentityVerifierExpectedPath
        request_entry_contract = $requestEntryContractPath
        profile_manifest = $profileManifestPath
        profile_definition = $profileDefinitionPath
        runtime_plan = $runtimePlanPath
        result_schema_core_v1 = $resultSchemaCorePath
        result_schema_v1 = $resultSchemaV1Path
        result_schema_v2 = $resultSchemaV2Path
        room_schema_v19 = $roomSchemaV19Path
        spec_catalog = $CatalogPath
        server_ca = $ServerCaPath
    }
    $assetExpectedPaths = [ordered]@{
        collector = $collectorPath
        derive_helper = $deriveHelperExpectedPath
        audit_verifier = $auditVerifierExpectedPath
        client_db_verifier = $clientDbVerifierExpectedPath
        negative_proxy = $negativeProxyExpectedPath
        negative_proxy_evidence_verifier = $negativeProxyEvidenceVerifierExpectedPath
        negative_client_db_verifier = $negativeClientDbVerifierExpectedPath
        bundle_verifier = $bundleVerifierExpectedPath
        ci_provenance_verifier = $ciProvenanceVerifierExpectedPath
        ci_workflow = $ciWorkflowExpectedPath
        debug_candidate_packager = $debugCandidatePackagerExpectedPath
        result_jsonl_verifier = $resultJsonlVerifierExpectedPath
        time_chain_verifier = $timeChainVerifierExpectedPath
        raw_state_verifier = $rawStateVerifierExpectedPath
        device_identity_verifier = $deviceIdentityVerifierExpectedPath
        request_entry_contract = $requestEntryContractPath
        profile_manifest = $profileManifestPath
        profile_definition = $profileDefinitionPath
        runtime_plan = $runtimePlanPath
        result_schema_core_v1 = $resultSchemaCorePath
        result_schema_v1 = $resultSchemaV1Path
        result_schema_v2 = $resultSchemaV2Path
        room_schema_v19 = $roomSchemaV19Path
        spec_catalog = $catalogExpectedPath
        server_ca = $serverCaExpectedPath
    }
    $assetLabels = [ordered]@{
        collector = 'D-82 collector'
        derive_helper = 'D-82 derivation helper'
        audit_verifier = 'request-entry audit verifier'
        client_db_verifier = 'client Room verifier'
        negative_proxy = 'negative serverinfo proxy'
        negative_proxy_evidence_verifier = 'negative proxy evidence verifier'
        negative_client_db_verifier = 'negative client Room verifier'
        bundle_verifier = 'independent bundle verifier'
        ci_provenance_verifier = 'CI APK provenance verifier'
        ci_workflow = 'CI workflow'
        debug_candidate_packager = 'debug candidate packager'
        result_jsonl_verifier = 'strict result JSONL verifier'
        time_chain_verifier = 'time-chain verifier'
        raw_state_verifier = 'raw device-state verifier'
        device_identity_verifier = 'P40 device identity verifier'
        request_entry_contract = 'request-entry contract'
        profile_manifest = 'published Profile manifest'
        profile_definition = 'published Profile definition'
        runtime_plan = 'published runtime plan'
        result_schema_core_v1 = 'result core schema'
        result_schema_v1 = 'result v1 schema'
        result_schema_v2 = 'result v2 schema'
        room_schema_v19 = 'Room schema v19 export'
        spec_catalog = 'spec catalog'
        server_ca = 'ANEB server CA certificate'
    }
    $toolingFiles = [ordered]@{}
    foreach ($assetName in $assetExpectedPaths.Keys) {
        $toolingFiles[$assetName] = Assert-CanonicalRepositoryAsset `
            -CandidatePath ([string]$assetCandidates[$assetName]) `
            -ExpectedPath ([string]$assetExpectedPaths[$assetName]) `
            -RepositoryRoot $repositoryRoot `
            -Label ([string]$assetLabels[$assetName])
    }
    if ($toolingFiles.Count -ne 25) {
        throw "tooling_closure_count_invalid count=$($toolingFiles.Count)"
    }
    $script:DeriveHelperPath = [string]$toolingFiles['derive_helper']
    $script:AuditVerifierPath = [string]$toolingFiles['audit_verifier']
    $script:ClientDbVerifierPath = [string]$toolingFiles['client_db_verifier']
    $script:NegativeProxyPath = [string]$toolingFiles['negative_proxy']
    $script:NegativeProxyEvidenceVerifierPath = [string]$toolingFiles['negative_proxy_evidence_verifier']
    $script:NegativeClientDbVerifierPath = [string]$toolingFiles['negative_client_db_verifier']
    $script:BundleVerifierPath = [string]$toolingFiles['bundle_verifier']
    $script:CiProvenanceVerifierPath = [string]$toolingFiles['ci_provenance_verifier']
    $script:ResultJsonlVerifierPath = [string]$toolingFiles['result_jsonl_verifier']
    $script:DeviceIdentityVerifierPath = [string]$toolingFiles['device_identity_verifier']
    $script:CatalogPath = [string]$toolingFiles['spec_catalog']
    $script:ServerCaPath = [string]$toolingFiles['server_ca']
    $serverCa = Get-ServerCaIdentity -Path $script:ServerCaPath
    $contractDigest = Get-ExpectedProfileContractDefinitionSha256 -Path $script:CatalogPath
    $evidenceFull = Assert-NonReparseDirectoryChain `
        -Path $EvidenceRoot `
        -ReasonPrefix 'evidence_root'
    $repositoryPrefix = $repositoryRoot + [IO.Path]::DirectorySeparatorChar
    if ($evidenceFull -ieq $repositoryRoot -or
        $evidenceFull.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'evidence_root_inside_git_worktree_forbidden'
    }
    $git = Resolve-Executable -Value 'git' -Label 'Git'
    $gitIdentity = Get-LocalGitIdentity -GitPath $git -RepositoryRoot $repositoryRoot

    $toolingDigests = [ordered]@{}
    foreach ($entry in $toolingFiles.GetEnumerator()) {
        $toolingDigests[$entry.Key] = (Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $toolingProvenance = [ordered]@{
        source_commit = $gitIdentity.Commit
        source_dirty = [bool]$gitIdentity.Dirty
        files = $toolingDigests
        external_inputs = [ordered]@{
            ssh_known_hosts_sha256 = (Get-FileHash -LiteralPath $resolvedKnownHosts -Algorithm SHA256).Hash.ToLowerInvariant()
            device_policy_sha256 = [string]$devicePolicy.Sha256
        }
    }

    $resolvedApksigner = Resolve-Executable -Value $ApksignerPath -Label 'apksigner'
    $resolvedAndroidBuildTools = Resolve-AndroidBuildToolsDirectory `
        -ResolvedApksignerPath $resolvedApksigner `
        -CandidateDirectory $AndroidBuildToolsDir
    $resolvedGh = Resolve-RegularNonReparseFile `
        -Path (Resolve-Executable -Value $GhPath -Label 'GitHub CLI') `
        -ReasonPrefix 'gh_executable'
    $resolved = @{
        Adb = Resolve-Executable -Value $AdbPath -Label 'ADB'
        Ssh = Resolve-Executable -Value $SshPath -Label 'SSH'
        Curl = Resolve-Executable -Value $CurlPath -Label 'curl'
        Apksigner = $resolvedApksigner
        AndroidBuildToolsDir = $resolvedAndroidBuildTools
        Python = Resolve-Executable -Value $PythonPath -Label 'Python'
        Gh = $resolvedGh
        GhSha256 = (Get-FileHash -LiteralPath $resolvedGh -Algorithm SHA256).Hash.ToLowerInvariant()
        Git = $git
        RepositoryRoot = $repositoryRoot
        ProfileContractDefinitionSha256 = $contractDigest
        ToolingFiles = $toolingFiles
        ToolingProvenance = $toolingProvenance
        ServerCa = $serverCa
        BoundRemoteHost = $boundRemoteHost
        NegativeProxyUpstreamUrl = $negativeProxyUpstreamUrl
        KnownHosts = $resolvedKnownHosts
        DevicePolicy = $devicePolicy
        CiCandidateDirectory = Resolve-CiCandidateDirectory -Path $CandidateDirectory
    }
    if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
        throw 'EvidenceRoot is empty.'
    }
    return $resolved
}

function ConvertTo-NativeArgument {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Argument)
    if ($Argument.Length -gt 0 -and $Argument -notmatch '[\s"]') {
        return $Argument
    }
    $escaped = [regex]::Replace($Argument, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-BoundedNativeTextOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][ValidateRange(1, 600)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][string]$TimeoutReason,
        [Parameter(Mandatory = $true)][string]$LaunchReason
    )
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Command
    $startInfo.Arguments = (($Arguments | ForEach-Object {
        ConvertTo-NativeArgument -Argument ([string]$_)
    }) -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        try {
            $started = $process.Start()
        } catch {
            throw "$LaunchReason detail=$($_.Exception.GetType().Name)"
        }
        if (-not $started) {
            throw $LaunchReason
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try {
                $process.Kill()
                $null = $process.WaitForExit(5000)
            } catch { }
            throw "$TimeoutReason timeout_seconds=$TimeoutSeconds"
        }
        # Parameterless WaitForExit flushes both redirected async readers after
        # the bounded wait has established that the child itself exited.
        $process.WaitForExit()
        $stdout = [string]$stdoutTask.Result
        $stderr = [string]$stderrTask.Result
        $parts = New-Object System.Collections.Generic.List[string]
        if (-not [string]::IsNullOrEmpty($stdout)) {
            $parts.Add((($stdout -replace "`r`n", "`n" -replace "`r", "`n").TrimEnd("`n")))
        }
        if (-not [string]::IsNullOrEmpty($stderr)) {
            $parts.Add((($stderr -replace "`r`n", "`n" -replace "`r", "`n").TrimEnd("`n")))
        }
        return [pscustomobject]@{
            ExitCode = [int]$process.ExitCode
            Text = ($parts -join "`n")
        }
    } finally {
        $process.Dispose()
    }
}

function Test-ExactPropertyNames {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string[]]$Expected
    )
    if ($null -eq $Value) {
        return $false
    }
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    return ($actual -join "`0") -ceq ($wanted -join "`0")
}

function Assert-GhExecutableStable {
    $resolved = Resolve-RegularNonReparseFile `
        -Path ([string]$script:ResolvedTools.Gh) `
        -ReasonPrefix 'gh_executable'
    if (-not $resolved.Equals([string]$script:ResolvedTools.Gh, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'gh_executable_drift'
    }
    Assert-ToolingFileStable `
        -Path $resolved `
        -ExpectedSha256 ([string]$script:ResolvedTools.GhSha256) `
        -Label 'gh_executable'
}

function Get-CiCandidateDigestSnapshot {
    param([Parameter(Mandatory = $true)][string]$Directory)
    $resolved = Resolve-CiCandidateDirectory -Path $Directory
    $digests = [ordered]@{}
    foreach ($name in $CandidatePayloadNames) {
        $path = Resolve-RegularNonReparseFile `
            -Path (Join-Path $resolved $name) `
            -ReasonPrefix 'ci_candidate_payload'
        $digests[$name] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return [pscustomobject]@{
        Directory = $resolved
        Digests = $digests
    }
}

function Assert-CiCandidateSnapshotStable {
    param([Parameter(Mandatory = $true)]$Expected)
    $current = Get-CiCandidateDigestSnapshot -Directory ([string]$Expected.Directory)
    foreach ($name in $CandidatePayloadNames) {
        if ([string]$current.Digests[$name] -cne [string]$Expected.Digests[$name]) {
            throw "ci_candidate_drift payload=$name"
        }
    }
}

function Copy-ExclusiveRegularFile {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$ReasonPrefix
    )
    $resolvedSource = Resolve-RegularNonReparseFile `
        -Path $Source `
        -ReasonPrefix $ReasonPrefix
    try {
        if ([IO.Path]::GetFullPath($resolvedSource).Equals(
            [IO.Path]::GetFullPath($Destination),
            [StringComparison]::OrdinalIgnoreCase
        )) {
            throw 'same_path'
        }
        $input = New-Object IO.FileStream(
            $resolvedSource,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            [IO.FileShare]::Read
        )
        try {
            $output = New-Object IO.FileStream(
                $Destination,
                [IO.FileMode]::CreateNew,
                [IO.FileAccess]::Write,
                [IO.FileShare]::None
            )
            try {
                $input.CopyTo($output)
                $output.Flush($true)
            } finally {
                $output.Dispose()
            }
        } finally {
            $input.Dispose()
        }
    } catch {
        throw "${ReasonPrefix}_copy_failed"
    }
    $resolvedDestination = Resolve-RegularNonReparseFile `
        -Path $Destination `
        -ReasonPrefix $ReasonPrefix
    if ((Get-FileHash -LiteralPath $resolvedSource -Algorithm SHA256).Hash -cne
        (Get-FileHash -LiteralPath $resolvedDestination -Algorithm SHA256).Hash) {
        throw "${ReasonPrefix}_copy_mismatch"
    }
    return $resolvedDestination
}

function Copy-DevicePolicyToEvidence {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    $destination = Join-Path $EvidenceDirectory 'device-policy.json'
    $null = Copy-ExclusiveRegularFile `
        -Source ([string]$script:ResolvedTools.DevicePolicy.Path) `
        -Destination $destination `
        -ReasonPrefix 'device_policy'
    $bundled = Get-DevicePolicyIdentity -Path $destination
    if ([string]$bundled.Sha256 -cne [string]$script:ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256) {
        throw 'device_policy_copy_mismatch'
    }
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    return $bundled
}

function Assert-BundledDevicePolicyStable {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $external = Resolve-RegularNonReparseFile `
        -Path ([string]$script:ResolvedTools.DevicePolicy.Path) `
        -ReasonPrefix 'device_policy'
    $bundled = Resolve-RegularNonReparseFile `
        -Path (Join-Path $EvidenceDirectory 'device-policy.json') `
        -ReasonPrefix 'device_policy_copy'
    if ($external.Equals($bundled, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'device_policy_copy_same_as_external'
    }
    foreach ($path in @($external, $bundled)) {
        Assert-ToolingFileStable `
            -Path $path `
            -ExpectedSha256 ([string]$script:ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256) `
            -Label 'device_policy'
    }
}

function Copy-CiCandidateToEvidence {
    param(
        [Parameter(Mandatory = $true)]$SourceSnapshot,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )
    $destination = Join-Path $EvidenceDirectory 'ci-candidate'
    $null = New-Item -ItemType Directory -Path $destination
    $destinationItem = Get-Item -LiteralPath $destination -Force
    if (($destinationItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'ci_candidate_copy_reparse_point_forbidden'
    }
    foreach ($name in $CandidatePayloadNames) {
        $copied = Copy-ExclusiveRegularFile `
            -Source (Join-Path ([string]$SourceSnapshot.Directory) $name) `
            -Destination (Join-Path $destination $name) `
            -ReasonPrefix 'ci_candidate_payload'
        if ((Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            [string]$SourceSnapshot.Digests[$name]) {
            throw "ci_candidate_copy_mismatch payload=$name"
        }
    }
    Assert-CiCandidateSnapshotStable -Expected $SourceSnapshot
    return Resolve-CiCandidateDirectory -Path $destination
}

function Assert-CiProvenanceReport {
    param(
        [Parameter(Mandatory = $true)][object[]]$OutputLines,
        [Parameter(Mandatory = $true)][int]$ExitCode,
        [Parameter(Mandatory = $true)][string]$SourceCommit
    )
    if ($OutputLines.Count -ne 1 -or
        [string]::IsNullOrWhiteSpace([string]$OutputLines[0]) -or
        [string]$OutputLines[0] -match "[`r`n]" -or
        [string]$OutputLines[0] -cne ([string]$OutputLines[0]).Trim()) {
        throw 'ci_provenance_verifier_output_invalid'
    }
    try {
        $report = ([string]$OutputLines[0]) | ConvertFrom-Json
    } catch {
        throw 'ci_provenance_verifier_json_invalid'
    }
    if ($ExitCode -ne 0 -or
        -not (Test-ExactPropertyNames -Value $report -Expected @(
            'schema', 'schema_version', 'status', 'reason_code',
            'candidate_provenance_reverified', 'repository', 'signer_workflow',
            'predicate_type', 'source_commit', 'source_ref', 'workflow_run_id',
            'workflow_run_url', 'apk', 'files', 'gh'
        )) -or
        [string]$report.schema -cne 'aneb-ci-apk-provenance-report' -or
        [string]$report.schema_version -cne '1.0.0' -or
        [string]$report.status -cne 'pass' -or
        [string]$report.reason_code -cne 'ok' -or
        $report.candidate_provenance_reverified -isnot [bool] -or
        -not [bool]$report.candidate_provenance_reverified -or
        [string]$report.repository -cne 'lucassu2012/ANEB_GPT' -or
        [string]$report.signer_workflow -cne 'lucassu2012/ANEB_GPT/.github/workflows/ci.yml' -or
        [string]$report.predicate_type -cne 'https://slsa.dev/provenance/v1' -or
        [string]$report.source_commit -cne $SourceCommit -or
        [string]$report.source_ref -notmatch '^refs/(?:heads|tags)/[^\r\n]+$' -or
        $report.workflow_run_id -is [string] -or
        [int64]$report.workflow_run_id -le 0 -or
        [string]$report.workflow_run_url -cne (
            'https://github.com/lucassu2012/ANEB_GPT/actions/runs/' +
            [string]([int64]$report.workflow_run_id)
        ) -or
        -not (Test-ExactPropertyNames -Value $report.apk -Expected @(
            'file_name', 'sha256', 'size_bytes', 'package_name',
            'version_name', 'version_code', 'signer_sha256'
        )) -or
        [string]$report.apk.file_name -cne 'ANEB-Probe-0.5.12-codex-debug.apk' -or
        [string]$report.apk.package_name -cne $PackageName -or
        [string]$report.apk.version_name -cne $ExpectedVersionName -or
        $report.apk.version_code -is [string] -or
        [int64]$report.apk.version_code -ne $ExpectedVersionCode -or
        $report.apk.size_bytes -is [string] -or
        [int64]$report.apk.size_bytes -le 0 -or
        [string]$report.apk.sha256 -notmatch '^[0-9a-f]{64}$' -or
        [string]$report.apk.signer_sha256 -notmatch '^[0-9a-f]{64}$' -or
        -not (Test-ExactPropertyNames -Value $report.files -Expected @(
            'attestation_bundle_sha256', 'build_manifest_sha256', 'checksums_sha256'
        )) -or
        [string]$report.files.attestation_bundle_sha256 -notmatch '^[0-9a-f]{64}$' -or
        [string]$report.files.build_manifest_sha256 -notmatch '^[0-9a-f]{64}$' -or
        [string]$report.files.checksums_sha256 -notmatch '^[0-9a-f]{64}$' -or
        -not (Test-ExactPropertyNames -Value $report.gh -Expected @(
            'version', 'executable_sha256', 'certificate_issuer', 'oidc_issuer',
            'runner_environment', 'run_invocation_uri', 'subject_alternative_name',
            'verified_timestamp_count'
        )) -or
        [string]$report.gh.executable_sha256 -cne [string]$script:ResolvedTools.GhSha256) {
        throw 'ci_provenance_verifier_report_binding_mismatch'
    }
    return $report
}

function Invoke-CiProvenanceVerification {
    param([Parameter(Mandatory = $true)][string]$Directory)
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    Assert-GhExecutableStable
    $innerTimeout = [Math]::Max(1, [Math]::Min(30, $ToolCommandTimeoutSeconds - 1))
    $verification = Invoke-BoundedNativeTextOnce `
        -Command ([string]$script:ResolvedTools.Python) `
        -Arguments @(
            $script:CiProvenanceVerifierPath,
            $Directory,
            '--source-commit', [string]$script:ResolvedTools.ToolingProvenance.source_commit,
            '--gh-path', [string]$script:ResolvedTools.Gh,
            '--timeout-seconds', [string]$innerTimeout
        ) `
        -TimeoutSeconds $ToolCommandTimeoutSeconds `
        -TimeoutReason 'tool_timeout stage=verify_ci_apk_provenance.py' `
        -LaunchReason 'tool_launch_failed stage=verify_ci_apk_provenance.py'
    $output = @(
        if (-not [string]::IsNullOrEmpty([string]$verification.Text)) {
            ([string]$verification.Text) -split "`n"
        }
    )
    $report = Assert-CiProvenanceReport `
        -OutputLines $output `
        -ExitCode ([int]$verification.ExitCode) `
        -SourceCommit ([string]$script:ResolvedTools.ToolingProvenance.source_commit)
    Assert-GhExecutableStable
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    return [pscustomobject]@{
        Report = $report
        JsonLine = [string]$output[0]
    }
}

function Assert-CiProvenanceReportsEquivalent {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual
    )
    $expectedJson = $Expected | ConvertTo-Json -Compress -Depth 8
    $actualJson = $Actual | ConvertTo-Json -Compress -Depth 8
    if ($expectedJson -cne $actualJson) {
        throw 'ci_candidate_verification_drift'
    }
}

function Initialize-CiCandidateEvidence {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $null = Copy-DevicePolicyToEvidence -EvidenceDirectory $EvidenceDirectory
    $sourceSnapshot = Get-CiCandidateDigestSnapshot `
        -Directory ([string]$script:ResolvedTools.CiCandidateDirectory)
    $sourceBefore = Invoke-CiProvenanceVerification `
        -Directory ([string]$sourceSnapshot.Directory)
    Assert-CiCandidateSnapshotStable -Expected $sourceSnapshot
    $bundledDirectory = Copy-CiCandidateToEvidence `
        -SourceSnapshot $sourceSnapshot `
        -EvidenceDirectory $EvidenceDirectory
    $bundled = Invoke-CiProvenanceVerification -Directory $bundledDirectory
    Assert-CiCandidateSnapshotStable -Expected $sourceSnapshot
    $sourceAfter = Invoke-CiProvenanceVerification `
        -Directory ([string]$sourceSnapshot.Directory)
    Assert-CiCandidateSnapshotStable -Expected $sourceSnapshot
    Assert-CiProvenanceReportsEquivalent `
        -Expected $sourceBefore.Report `
        -Actual $bundled.Report
    Assert-CiProvenanceReportsEquivalent `
        -Expected $sourceBefore.Report `
        -Actual $sourceAfter.Report
    Assert-BundledDevicePolicyStable -EvidenceDirectory $EvidenceDirectory
    Write-NewTextNoBom `
        -Path (Join-Path $EvidenceDirectory 'ci-candidate-verification.json') `
        -Text ($bundled.JsonLine + "`n")
    $script:ExpectedApkSha256 = [string]$bundled.Report.apk.sha256
    $script:ExpectedSignerSha256 = [string]$bundled.Report.apk.signer_sha256
    return $bundled.Report
}

function Assert-SshKnownHostsStable {
    $resolved = Resolve-RegularNonReparseFile `
        -Path ([string]$script:ResolvedTools.KnownHosts) `
        -ReasonPrefix 'ssh_known_hosts'
    if (-not $resolved.Equals(
        [string]$script:ResolvedTools.KnownHosts,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw 'ssh_known_hosts_path_drift'
    }
    Assert-ToolingFileStable `
        -Path $resolved `
        -ExpectedSha256 ([string]$script:ResolvedTools.ToolingProvenance.external_inputs.ssh_known_hosts_sha256) `
        -Label 'ssh_known_hosts'
}

function Get-SshArguments {
    param([Parameter(Mandatory = $true)][string]$RemoteCommand)
    Assert-SshKnownHostsStable
    return @(
        '-T',
        '-i', $SshKey,
        '-o', 'BatchMode=yes',
        '-o', 'IdentitiesOnly=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'CheckHostIP=yes',
        '-o', "HostName=$($script:ResolvedTools.BoundRemoteHost)",
        '-o', 'CanonicalizeHostname=no',
        '-o', 'Port=22',
        '-o', 'ProxyCommand=none',
        '-o', 'ProxyJump=none',
        '-o', "UserKnownHostsFile=$($script:ResolvedTools.KnownHosts)",
        '-o', "GlobalKnownHostsFile=$($script:ResolvedTools.KnownHosts)",
        '-o', 'KnownHostsCommand=none',
        '-o', 'ConnectTimeout=10',
        '-o', 'ServerAliveInterval=10',
        '-o', 'ServerAliveCountMax=3',
        $Remote,
        $RemoteCommand
    )
}

function Invoke-SshTextOnce {
    param(
        [Parameter(Mandatory = $true)][string]$RemoteCommand,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    $result = Invoke-BoundedNativeTextOnce `
        -Command $script:ResolvedTools.Ssh `
        -Arguments @(Get-SshArguments -RemoteCommand $RemoteCommand) `
        -TimeoutSeconds $SshCommandTimeoutSeconds `
        -TimeoutReason "ssh_timeout stage=$Stage" `
        -LaunchReason "ssh_launch_failed stage=$Stage"
    if ($result.ExitCode -ne 0) {
        throw "ssh_failed stage=$Stage rc=$($result.ExitCode) output=$($result.Text -replace '\r?\n', '|')"
    }
    return [string]$result.Text
}

function Start-PersistentAuditLock {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$Nonce
    )
    if ($Nonce -notmatch '^[0-9a-f]{32}$') {
        throw 'lock_nonce_invalid'
    }
    $remoteLockHolder = @'
set -Eeuo pipefail
IFS=$'\n\t'
umask 077
NONCE="${1:?nonce required}"
TTL_SECONDS="${2:?TTL required}"
LOCK_PATH="${3:?lock path required}"
[[ "$NONCE" =~ ^[0-9a-f]{32}$ ]] || exit 64
[[ "$TTL_SECONDS" =~ ^[0-9]+$ ]] || exit 64
[[ "$LOCK_PATH" == '/run/lock/aneb-deploy.lock' ]] || exit 64
MARKER="/run/aneb-token-audit-$NONCE.lock"
GUARD_PID=''
cleanup() {
    if [[ -n "$GUARD_PID" ]]; then
        kill "$GUARD_PID" >/dev/null 2>&1 || true
        wait "$GUARD_PID" 2>/dev/null || true
    fi
    rm -f -- "$MARKER"
}
trap cleanup EXIT HUP INT TERM
exec 9>"$LOCK_PATH"
if ! flock -n 9; then
    printf 'LOCK_BUSY path=%s\n' "$LOCK_PATH" >&2
    exit 75
fi
printf '%s %s\n' "$NONCE" "$$" > "$MARKER"
chmod 0600 "$MARKER"
printf 'LOCK_ACQUIRED nonce=%s pid=%s marker=%s\n' "$NONCE" "$$" "$MARKER"
PARENT_PID=$$
(
    sleep "$TTL_SECONDS"
    kill -TERM "$PARENT_PID" >/dev/null 2>&1 || true
) &
GUARD_PID=$!
while IFS= read -r command; do
    if [[ "$command" == "RELEASE $NONCE" ]]; then
        printf 'LOCK_RELEASED nonce=%s\n' "$NONCE"
        exit 0
    fi
    printf 'LOCK_PROTOCOL_ERROR\n' >&2
    exit 64
done
exit 76
'@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteLockHolder))
    $remoteCommand = "bash -c `"`$(printf '%s' '$encoded' | base64 -d)`" -- '$Nonce' '$LockTtlSeconds' '$RemoteLockPath'"
    $arguments = Get-SshArguments -RemoteCommand $remoteCommand
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $script:ResolvedTools.Ssh
    $startInfo.Arguments = (($arguments | ForEach-Object { ConvertTo-NativeArgument -Argument ([string]$_) }) -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw 'lock_ssh_launch_failed'
    }
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $receiptTask = $process.StandardOutput.ReadLineAsync()
    if (-not $receiptTask.Wait(15000)) {
        try { $process.Kill() } catch { }
        throw 'lock_receipt_timeout'
    }
    $receipt = [string]$receiptTask.Result
    if ($receipt -notmatch ('^LOCK_ACQUIRED nonce=' + [regex]::Escape($Nonce) + ' pid=([0-9]+) marker=(/run/aneb-token-audit-[0-9a-f]{32}\.lock)$')) {
        try { $process.Kill() } catch { }
        $stderr = if ($stderrTask.IsCompleted) { [string]$stderrTask.Result } else { '' }
        throw "lock_receipt_invalid receipt=$receipt stderr=$stderr"
    }
    $remotePid = [int64]$Matches[1]
    $marker = [string]$Matches[2]
    [System.IO.File]::WriteAllText(
        (Join-Path $EvidenceDirectory 'lock-acquired.txt'),
        ($receipt + "`n"),
        (New-Object System.Text.UTF8Encoding($false))
    )
    return [pscustomobject]@{
        Process = $process
        Nonce = $Nonce
        RemotePid = $remotePid
        Marker = $marker
        Receipt = $receipt
        StderrTask = $stderrTask
    }
}

function Assert-PersistentAuditLock {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    if ($null -eq $Lock.Process -or $Lock.Process.HasExited) {
        throw "audit_lock_process_lost stage=$Stage"
    }
    $command = @(
        'set -Eeuo pipefail',
        "LOCK_PATH='$RemoteLockPath'",
        "MARKER='$($Lock.Marker)'",
        "EXPECTED='$($Lock.Nonce) $($Lock.RemotePid)'",
        '[ "$(cat -- "$MARKER")" = "$EXPECTED" ]',
        "kill -0 '$($Lock.RemotePid)'",
        'set +e',
        'flock -n "$LOCK_PATH" -c true',
        'flock_rc=$?',
        'set -e',
        '[ "$flock_rc" -eq 1 ]',
        "printf 'LOCK_HEALTHY nonce=%s pid=%s\\n' '$($Lock.Nonce)' '$($Lock.RemotePid)'"
    ) -join '; '
    $output = Invoke-SshTextOnce -RemoteCommand $command -Stage "lock_health_$Stage"
    $expected = "LOCK_HEALTHY nonce=$($Lock.Nonce) pid=$($Lock.RemotePid)"
    if ($output.Trim() -cne $expected) {
        throw "audit_lock_health_invalid stage=$Stage output=$output"
    }
}

function Write-TextNoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text
    )
    [System.IO.File]::WriteAllText(
        $Path,
        $Text,
        (New-Object System.Text.UTF8Encoding($false))
    )
}

function Write-JsonNoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $json = $Value | ConvertTo-Json -Depth 12
    Write-TextNoBom -Path $Path -Text ($json + "`n")
}

function Invoke-AdbTextOnce {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    $result = Invoke-BoundedNativeTextOnce `
        -Command $script:ResolvedTools.Adb `
        -Arguments (@('-s', $AdbSerial) + $Arguments) `
        -TimeoutSeconds $AdbCommandTimeoutSeconds `
        -TimeoutReason "adb_timeout stage=$Stage" `
        -LaunchReason "adb_launch_failed stage=$Stage"
    if ($result.ExitCode -ne 0) {
        throw "adb_failed stage=$Stage rc=$($result.ExitCode) output=$($result.Text -replace '\r?\n', '|')"
    }
    return ([string]$result.Text).Trim()
}

function Invoke-ToolTextOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    $result = Invoke-BoundedNativeTextOnce `
        -Command $Command `
        -Arguments $Arguments `
        -TimeoutSeconds $ToolCommandTimeoutSeconds `
        -TimeoutReason "tool_timeout stage=$Stage" `
        -LaunchReason "tool_launch_failed stage=$Stage"
    if ($result.ExitCode -ne 0) {
        throw "tool_failed stage=$Stage rc=$($result.ExitCode) output=$($result.Text -replace '\r?\n', '|')"
    }
    return ([string]$result.Text).Trim()
}

function ConvertTo-CanonicalAndroidComponent {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Reason
    )
    $match = [regex]::Match(
        $Value,
        '^(?<package>[A-Za-z0-9._]+)/(?<class>[A-Za-z0-9._$]+)$'
    )
    if (-not $match.Success) {
        throw $Reason
    }
    $packageName = [string]$match.Groups['package'].Value
    $className = [string]$match.Groups['class'].Value
    if ($className.StartsWith('.', [StringComparison]::Ordinal)) {
        $className = $packageName + $className
    }
    return $packageName + '/' + $className
}

function Assert-HuaweiLauncherFocused {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$WindowDump,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ActivityDump
    )
    $componentPattern = '(?<component>[A-Za-z0-9._]+/[A-Za-z0-9._$]+)'
    $extractComponent = {
        param([string]$Line, [string]$Label)
        $match = [regex]::Match($Line, $componentPattern)
        if (-not $match.Success) {
            throw "live_state_focus_component_invalid source=$Label"
        }
        return ConvertTo-CanonicalAndroidComponent `
            -Value ([string]$match.Groups['component'].Value) `
            -Reason "live_state_focus_component_invalid source=$Label"
    }

    $windowLines = @($WindowDump -split "`r?`n" | Where-Object {
        $_ -match '^\s*mCurrentFocus='
    })
    $focusedLines = @($ActivityDump -split "`r?`n" | Where-Object {
        $_ -match '^\s*mFocusedApp='
    })
    $resumedLines = @($ActivityDump -split "`r?`n" | Where-Object {
        $_ -match '^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]'
    })
    if ($windowLines.Count -ne 1 -or $focusedLines.Count -ne 1 -or $resumedLines.Count -lt 1) {
        throw (
            'live_state_focus_ambiguous window={0} focused={1} resumed={2}' -f
            $windowLines.Count, $focusedLines.Count, $resumedLines.Count
        )
    }
    $components = New-Object System.Collections.Generic.List[string]
    $components.Add((& $extractComponent $windowLines[0] 'mCurrentFocus'))
    $components.Add((& $extractComponent $focusedLines[0] 'mFocusedApp'))
    foreach ($line in $resumedLines) {
        $components.Add((& $extractComponent $line 'resumedActivity'))
    }
    $expectedLauncher = ConvertTo-CanonicalAndroidComponent `
        -Value $LauncherComponent `
        -Reason 'launcher_component_invalid'
    foreach ($component in $components) {
        if ($component -cne $expectedLauncher) {
            throw "live_state_not_launcher component=$component"
        }
    }
    return [pscustomobject]@{
        WindowComponent = [string]$components[0]
        FocusedAppComponent = [string]$components[1]
        ResumedComponents = @($components | Select-Object -Skip 2)
    }
}

function Assert-NoActiveVpn {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ConnectivityDump,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$VpnDump
    )
    $agentLines = @($ConnectivityDump -split "`r?`n" | Where-Object {
        $_ -match 'NetworkAgentInfo\{'
    })
    foreach ($agentLine in $agentLines) {
        $isVpn = $agentLine -match '(?i)(?:Transports?:\s*VPN|type:\s*VPN)'
        $isActive = $agentLine -match '(?i)(?:state:\s*CONNECTED(?:/CONNECTED)?|CONNECTED/CONNECTED|\bVALIDATED\b)'
        $isDisconnected = $agentLine -match '(?i)(?:state:\s*DISCONNECTED|DISCONNECTED/DISCONNECTED)'
        if ($isVpn -and $isActive -and -not $isDisconnected) {
            throw 'live_state_active_vpn source=connectivity_network_agent'
        }
    }
    $vpnStateLines = @($VpnDump -split "`r?`n" | Where-Object {
        $_ -notmatch '(?i)\bLISTEN\b' -and
        ($_ -match '(?i)(?:state\s*[:=]\s*CONNECTED|mNetworkInfo.*\bCONNECTED\b)')
    })
    if ($vpnStateLines.Count -gt 0) {
        throw 'live_state_active_vpn source=vpn_service'
    }
}

function Get-Utf8StringSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($Value)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Assert-DevicePolicyStable {
    $resolved = Get-DevicePolicyIdentity -Path ([string]$script:ResolvedTools.DevicePolicy.Path)
    if (-not ([string]$resolved.Path).Equals(
        [string]$script:ResolvedTools.DevicePolicy.Path,
        [StringComparison]::OrdinalIgnoreCase
    ) -or
        [string]$resolved.Sha256 -cne [string]$script:ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256) {
        throw 'device_policy_drift'
    }
    return $resolved
}

function Write-DeviceIdentitySnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][ValidateSet('preflight', 'final')][string]$Stage
    )
    $policy = Assert-DevicePolicyStable
    $serial = Invoke-AdbTextOnce -Arguments @('get-serialno') -Stage "device_serial_$Stage"
    if ($serial -notmatch '^[A-Za-z0-9._:-]{4,128}$' -or $serial -cne $AdbSerial) {
        throw "device_serial_mismatch stage=$Stage"
    }
    $serialSha256 = Get-Utf8StringSha256 -Value $serial
    if ($serialSha256 -cne [string]$policy.AdbSerialSha256) {
        throw "device_policy_mismatch stage=$Stage field=adb_serial_sha256"
    }
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory "device-adb-serial-$Stage.txt") `
        -Text ($serial + "`n")

    $properties = [ordered]@{}
    $transcript = New-Object Text.StringBuilder
    foreach ($key in $DevicePropertyKeys) {
        $value = Invoke-AdbTextOnce -Arguments @(
            'shell', "getprop '$key'"
        ) -Stage "device_property_${Stage}_$key"
        if ($value -match "[`r`n`0]" -or
            (New-Object Text.UTF8Encoding($false)).GetByteCount($value) -gt 2048 -or
            ($DeviceOptionalPropertyKeys -cnotcontains $key -and $value.Length -eq 0)) {
            throw "device_properties_invalid stage=$Stage key=$key"
        }
        $expectedProperty = $policy.Properties.PSObject.Properties[$key]
        if ($null -eq $expectedProperty -or [string]$expectedProperty.Value -cne $value) {
            throw "device_policy_mismatch stage=$Stage field=$key"
        }
        if ($key -in @('ro.serialno', 'ro.boot.serialno') -and
            $value.Length -gt 0 -and $value -cne $serial) {
            throw "device_serial_mismatch stage=$Stage field=$key"
        }
        $properties[$key] = $value
        $null = $transcript.Append($key).Append('=').Append($value).Append("`n")
    }
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory "device-getprop-$Stage.txt") `
        -Text $transcript.ToString()

    $bootId = Invoke-AdbTextOnce -Arguments @(
        'shell', 'cat /proc/sys/kernel/random/boot_id'
    ) -Stage "device_boot_id_$Stage"
    if ($bootId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw "device_boot_id_invalid stage=$Stage"
    }
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory "device-boot-id-$Stage.txt") `
        -Text ($bootId + "`n")
    return [pscustomobject]@{
        SerialSha256 = $serialSha256
        BootId = $bootId
        Properties = [pscustomobject]$properties
    }
}

function Assert-DeviceIdentityReport {
    param([Parameter(Mandatory = $true)]$Report)
    $expectedKeys = @(
        'adb_serial_sha256', 'android_boot_id', 'device_alias',
        'device_policy_sha256', 'properties_sha256', 'raw_files_verified',
        'reason_code', 'schema', 'schema_version', 'serial_property_confirmed',
        'status', 'verified_boot_observed_complete', 'verified_boot_secure'
    ) | Sort-Object
    $actualKeys = @($Report.PSObject.Properties.Name | Sort-Object)
    if (($expectedKeys -join "`0") -cne ($actualKeys -join "`0") -or
        [string]$Report.schema -cne 'aneb-token-quick-device-identity-verification' -or
        [string]$Report.schema_version -cne '1.0.0' -or
        [string]$Report.status -cne 'pass' -or
        [string]$Report.reason_code -cne 'ok' -or
        [string]$Report.device_alias -cne 'P40 Pro' -or
        [string]$Report.device_policy_sha256 -cne [string]$script:ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256 -or
        [string]$Report.adb_serial_sha256 -cne (Get-Utf8StringSha256 -Value $AdbSerial) -or
        [string]$Report.android_boot_id -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
        [string]$Report.properties_sha256 -notmatch '^[0-9a-f]{64}$' -or
        $Report.serial_property_confirmed -isnot [bool] -or
        $Report.verified_boot_observed_complete -isnot [bool] -or
        $Report.verified_boot_secure -isnot [bool] -or
        ([bool]$Report.verified_boot_secure -and -not [bool]$Report.verified_boot_observed_complete) -or
        ($Report.raw_files_verified -isnot [int] -and
            $Report.raw_files_verified -isnot [long]) -or
        [long]$Report.raw_files_verified -ne 6) {
        throw 'device_identity_report_invalid'
    }
    return $Report
}

function Invoke-DeviceIdentityVerification {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    $inlineVerifier = @'
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

module_path, bundle_path, policy_path, expected_serial_sha256 = sys.argv[1:5]
spec = importlib.util.spec_from_file_location("aneb_d82_device_identity", module_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
try:
    serial = (Path(bundle_path) / "device-adb-serial-preflight.txt").read_text(encoding="utf-8").rstrip("\n")
    if hashlib.sha256(serial.encode("utf-8")).hexdigest() != expected_serial_sha256:
        module.fail("device_serial_mismatch")
    report = module.verify_device_identity(
        Path(bundle_path),
        policy_path=Path(policy_path),
        expected_input_serial=serial,
    )
except module.DeviceIdentityFailure as error:
    print(json.dumps({"status":"fail","reason_code":error.reason_code}, separators=(",", ":")))
    raise SystemExit(2)
print(json.dumps(report, sort_keys=True, separators=(",", ":")))
'@
    $verification = Invoke-BoundedNativeTextOnce `
        -Command $script:ResolvedTools.Python `
        -Arguments @(
            '-c', $inlineVerifier,
            $script:DeviceIdentityVerifierPath,
            $EvidenceDirectory,
            [string]$script:ResolvedTools.DevicePolicy.Path,
            (Get-Utf8StringSha256 -Value $AdbSerial)
        ) `
        -TimeoutSeconds $ToolCommandTimeoutSeconds `
        -TimeoutReason 'tool_timeout stage=verify_token_quick_device_identity.py' `
        -LaunchReason 'tool_launch_failed stage=verify_token_quick_device_identity.py'
    $text = ([string]$verification.Text).Trim()
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory 'device-identity-report.json') `
        -Text ($text + "`n")
    if ($verification.ExitCode -ne 0) {
        $reason = 'unknown'
        try { $reason = [string]($text | ConvertFrom-Json).reason_code } catch { }
        throw "device_identity_verifier_failed reason=$reason"
    }
    try {
        $report = $text | ConvertFrom-Json
    } catch {
        throw 'device_identity_report_json_invalid'
    }
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    return Assert-DeviceIdentityReport -Report $report
}

function Assert-LiveDevicePreflight {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $state = Invoke-AdbTextOnce -Arguments @('get-state') -Stage 'device_state'
    if ($state -cne 'device') {
        throw "device_not_online state=$state"
    }
    $null = Write-DeviceIdentitySnapshot `
        -EvidenceDirectory $EvidenceDirectory `
        -Stage 'preflight'

    $window = Invoke-AdbTextOnce -Arguments @('shell', 'dumpsys window') -Stage 'focused_window'
    $activity = Invoke-AdbTextOnce -Arguments @('shell', 'dumpsys activity activities') -Stage 'focused_activity'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-window-preflight.txt') -Text ($window + "`n")
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-activity-preflight.txt') -Text ($activity + "`n")
    $null = Assert-HuaweiLauncherFocused -WindowDump $window -ActivityDump $activity

    $processEvidence = [ordered]@{}
    $serviceEvidence = [ordered]@{}
    foreach ($package in $ConflictPackages) {
        $pid = Invoke-AdbTextOnce -Arguments @(
            'shell', "pidof '$package' 2>/dev/null || true"
        ) -Stage "pidof_$package"
        $processEvidence[$package] = $pid
        if (-not [string]::IsNullOrWhiteSpace($pid)) {
            throw "live_state_conflict_process package=$package pid=$pid"
        }
        $services = Invoke-AdbTextOnce -Arguments @(
            'shell', "dumpsys activity services '$package'"
        ) -Stage "services_$package"
        $serviceEvidence[$package] = $services
        if ($services -match 'ServiceRecord\{') {
            throw "live_state_conflict_service package=$package"
        }
    }
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'device-processes-preflight.json') -Value $processEvidence
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'device-services-preflight.json') -Value $serviceEvidence

    $enabledAccessibility = Invoke-AdbTextOnce -Arguments @(
        'shell', 'settings get secure enabled_accessibility_services'
    ) -Stage 'enabled_accessibility_services'
    $accessibilityDump = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys accessibility'
    ) -Stage 'accessibility_state'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-accessibility-preflight.txt') -Text (
        "enabled_accessibility_services=$enabledAccessibility`n$accessibilityDump`n"
    )
    foreach ($package in @($ClaudePackageName, $PackageName)) {
        if ($enabledAccessibility -match [regex]::Escape($package)) {
            throw "live_state_accessibility_enabled package=$package"
        }
        $boundLine = @($accessibilityDump -split "`r?`n" | Where-Object {
            $_ -match '(?i)bound services?' -and $_ -match [regex]::Escape($package)
        })
        if ($boundLine.Count -gt 0) {
            throw "live_state_accessibility_bound package=$package"
        }
    }

    $tunState = Invoke-AdbTextOnce -Arguments @(
        'shell', 'found=0; for path in /sys/class/net/tun*; do if [ -e "$path" ]; then basename "$path"; found=1; fi; done; [ "$found" -eq 0 ] && echo absent'
    ) -Stage 'tun_state'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-tun-preflight.txt') -Text ($tunState + "`n")
    if ($tunState -cne 'absent') {
        throw "live_state_tunnel_present state=$tunState"
    }
    $connectivity = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys connectivity'
    ) -Stage 'connectivity_state'
    $vpn = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys vpn 2>&1 || true'
    ) -Stage 'vpn_state'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-connectivity-preflight.txt') -Text ($connectivity + "`n")
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-vpn-preflight.txt') -Text ($vpn + "`n")
    Assert-NoActiveVpn -ConnectivityDump $connectivity -VpnDump $vpn

    $stayon = Invoke-AdbTextOnce -Arguments @(
        'shell', 'settings get global stay_on_while_plugged_in'
    ) -Stage 'stay_on_while_plugged_in'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-stayon-preflight.txt') -Text ($stayon + "`n")
    if ($stayon -notmatch '^(?:null|[0-9]+)$') {
        throw "stayon_state_invalid value=$stayon"
    }

    $packageDump = Invoke-AdbTextOnce -Arguments @(
        'shell', "dumpsys package '$PackageName'"
    ) -Stage 'package_identity'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-package-preflight.txt') -Text ($packageDump + "`n")
    $versionCodeMatch = [regex]::Match($packageDump, '(?m)^\s*versionCode=([0-9]+)\b')
    $versionNameMatch = [regex]::Match($packageDump, '(?m)^\s*versionName=([^\r\n]+)$')
    if (-not $versionCodeMatch.Success -or [int64]$versionCodeMatch.Groups[1].Value -ne $ExpectedVersionCode) {
        throw 'installed_version_code_mismatch'
    }
    if (-not $versionNameMatch.Success -or $versionNameMatch.Groups[1].Value.Trim() -cne $ExpectedVersionName) {
        throw 'installed_version_name_mismatch'
    }
    $packagePath = Invoke-AdbTextOnce -Arguments @(
        'shell', "pm path '$PackageName'"
    ) -Stage 'pm_path'
    if ($packagePath -notmatch '^package:(/[^\r\n]+/base\.apk)$') {
        throw "installed_apk_path_invalid output=$packagePath"
    }
    $remoteApk = $Matches[1]
    $installedApk = Join-Path $EvidenceDirectory 'installed-base.apk'
    $pullOutput = Invoke-AdbTextOnce -Arguments @(
        'pull', $remoteApk, $installedApk
    ) -Stage 'pull_installed_apk'
    if (-not (Test-Path -LiteralPath $installedApk -PathType Leaf) -or
        (Get-Item -LiteralPath $installedApk).Length -le 0) {
        throw "installed_apk_pull_missing output=$pullOutput"
    }
    $installedApkSha256 = Assert-ExpectedFileSha256 `
        -Path $installedApk `
        -ExpectedSha256 $ExpectedApkSha256 `
        -Label 'installed_apk'
    $signerOutput = Invoke-ToolTextOnce -Command $script:ResolvedTools.Apksigner -Arguments @(
        'verify', '--print-certs', $installedApk
    ) -Stage 'installed_apk_signer'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'installed-apk-signer.txt') -Text ($signerOutput + "`n")
    $signerMatch = [regex]::Match(
        $signerOutput,
        '(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$'
    )
    if (-not $signerMatch.Success -or $signerMatch.Groups[1].Value -ine $ExpectedSignerSha256) {
        throw 'installed_signer_mismatch'
    }
    $runAs = Invoke-AdbTextOnce -Arguments @(
        'shell', "run-as '$PackageName' id"
    ) -Stage 'run_as'
    if ($runAs -notmatch '^uid=[0-9]+\(') {
        throw "run_as_unavailable output=$runAs"
    }

    $result = [ordered]@{
        schema = 'aneb-p40-live-preflight'
        schema_version = '1.0.0'
        captured_at_utc = [DateTime]::UtcNow.ToString('o')
        adb_serial = $AdbSerial
        launcher = $LauncherComponent
        stay_on_while_plugged_in = $stayon
        package_name = $PackageName
        version_name = $ExpectedVersionName
        version_code = $ExpectedVersionCode
        signer_sha256 = $signerMatch.Groups[1].Value.ToLowerInvariant()
        apk_sha256 = $installedApkSha256
        run_as = 'available'
        tun0 = 'absent'
        active_vpn = $false
    }
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'device-preflight.json') -Value $result
    return [pscustomobject]$result
}

function Assert-ServerInfoBody {
    param(
        [Parameter(Mandatory = $true)][string]$BodyPath,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    Assert-NonEmptyFile -Path $BodyPath -Label "$Stage serverinfo body"
    try {
        $body = Get-Content -LiteralPath $BodyPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw "serverinfo_json_invalid stage=$Stage"
    }
    $expectedKeys = @(
        'anchor_wall_unix_ns', 'congestion_control', 'execution_capabilities',
        'goarch', 'goos', 'h3_enabled', 'srv_ts_us',
        'tcp_slow_start_after_idle', 'uptime_s', 'version'
    ) | Sort-Object
    $actualKeys = @($body.PSObject.Properties.Name | Sort-Object)
    if (($expectedKeys -join "`0") -cne ($actualKeys -join "`0")) {
        throw "serverinfo_fields_mismatch stage=$Stage"
    }
    if ([string]$body.version -cne $ExpectedServerVersion) {
        throw "serverinfo_version_mismatch stage=$Stage actual=$($body.version)"
    }
    if ([string]$body.goos -cne 'linux' -or [string]$body.goarch -cne 'amd64') {
        throw "serverinfo_platform_mismatch stage=$Stage"
    }
    if ($body.h3_enabled -ne $true) {
        throw "serverinfo_h3_disabled stage=$Stage"
    }
    if ([int64]$body.srv_ts_us -le 0 -or
        [int64]$body.anchor_wall_unix_ns -le 0 -or
        [int64]$body.uptime_s -le 0 -or
        [string]$body.tcp_slow_start_after_idle -cne '0' -or
        [string]$body.congestion_control -cne 'cubic') {
        throw "serverinfo_runtime_contract_mismatch stage=$Stage"
    }
    $capabilities = $body.execution_capabilities
    if ($null -eq $capabilities -or
        [string]$capabilities.contract_id -cne 'aneb-server-capability-receipt' -or
        [string]$capabilities.contract_version -cne '1.0.0') {
        throw "serverinfo_capability_contract_mismatch stage=$Stage"
    }
    $capabilityKeys = @($capabilities.PSObject.Properties.Name | Sort-Object)
    $expectedCapabilityKeys = @(
        'contract_id', 'contract_version', 'primitives', 'validated_profiles'
    ) | Sort-Object
    if (($capabilityKeys -join "`0") -cne ($expectedCapabilityKeys -join "`0")) {
        throw "serverinfo_capability_fields_mismatch stage=$Stage"
    }
    $primitiveMap = @{}
    foreach ($primitive in @($capabilities.primitives)) {
        $primitiveKeys = @($primitive.PSObject.Properties.Name | Sort-Object)
        $expectedPrimitiveKeys = @('primitive_id', 'wire_contract_id') | Sort-Object
        if (($primitiveKeys -join "`0") -cne ($expectedPrimitiveKeys -join "`0") -or
            $primitiveMap.ContainsKey([string]$primitive.primitive_id)) {
            throw "serverinfo_primitive_contract_invalid stage=$Stage"
        }
        $primitiveMap[[string]$primitive.primitive_id] = [string]$primitive.wire_contract_id
    }
    $expectedPrimitives = [ordered]@{
        download = 'aneb-download-v1'
        echo = 'aneb-echo-v1'
        token_sim = 'aneb-token-task-v1'
    }
    if ($primitiveMap.Count -ne $expectedPrimitives.Count) {
        throw "serverinfo_primitive_count_mismatch stage=$Stage"
    }
    foreach ($entry in $expectedPrimitives.GetEnumerator()) {
        if (-not $primitiveMap.ContainsKey($entry.Key) -or
            [string]$primitiveMap[$entry.Key] -cne [string]$entry.Value) {
            throw "serverinfo_primitive_mismatch stage=$Stage primitive=$($entry.Key)"
        }
    }
    $profiles = @($capabilities.validated_profiles)
    if ($profiles.Count -ne 1) {
        throw "serverinfo_validated_profile_count_mismatch stage=$Stage"
    }
    $profileKeys = @($profiles[0].PSObject.Properties.Name | Sort-Object)
    $expectedProfileKeys = @('profile_id', 'profile_sha256', 'profile_version') | Sort-Object
    if (($profileKeys -join "`0") -cne ($expectedProfileKeys -join "`0") -or
        [string]$profiles[0].profile_id -cne 'token_multimodal_quick' -or
        [string]$profiles[0].profile_version -cne '1.2.1' -or
        [string]$profiles[0].profile_sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "serverinfo_validated_profile_mismatch stage=$Stage"
    }
    return $body
}

function Assert-ServerInfoSequence {
    param(
        [Parameter(Mandatory = $true)]$Identity,
        [Parameter(Mandatory = $true)]$StartBarrier,
        [Parameter(Mandatory = $true)]$EndBarrier,
        [Parameter(Mandatory = $true)][string]$ExpectedProfileSha256
    )
    if ($ExpectedProfileSha256 -notmatch '^sha256:([0-9a-f]{64})$') {
        throw 'serverinfo_client_profile_sha256_invalid'
    }
    $profileSha256 = [string]$Matches[1]
    $responses = @($Identity, $StartBarrier, $EndBarrier)
    $stages = @('identity', 'start_barrier', 'end_barrier')
    for ($index = 0; $index -lt $responses.Count; $index++) {
        $profiles = @($responses[$index].execution_capabilities.validated_profiles)
        if ($profiles.Count -ne 1 -or
            [string]$profiles[0].profile_sha256 -cne $profileSha256) {
            throw "serverinfo_profile_binding_mismatch stage=$($stages[$index])"
        }
    }
    if ([int64]$Identity.anchor_wall_unix_ns -ne [int64]$StartBarrier.anchor_wall_unix_ns -or
        [int64]$Identity.anchor_wall_unix_ns -ne [int64]$EndBarrier.anchor_wall_unix_ns) {
        throw 'serverinfo_anchor_changed'
    }
    if ([int64]$Identity.srv_ts_us -ge [int64]$StartBarrier.srv_ts_us -or
        [int64]$StartBarrier.srv_ts_us -ge [int64]$EndBarrier.srv_ts_us) {
        throw 'serverinfo_chronology_invalid'
    }
    if ([int64]$Identity.uptime_s -gt [int64]$StartBarrier.uptime_s -or
        [int64]$StartBarrier.uptime_s -gt [int64]$EndBarrier.uptime_s) {
        throw 'serverinfo_uptime_regressed'
    }
}

function Assert-CapturedHttp200Headers {
    param(
        [Parameter(Mandatory = $true)][string]$HeadersPath,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    Assert-NonEmptyFile -Path $HeadersPath -Label "$Stage serverinfo headers"
    $headers = Get-Content -LiteralPath $HeadersPath -Raw -Encoding UTF8
    $statuses = @([regex]::Matches($headers, '(?m)^HTTP/[0-9.]+\s+([0-9]{3})\b') | ForEach-Object {
        [int]$_.Groups[1].Value
    })
    if ($statuses.Count -lt 1 -or $statuses[-1] -ne 200) {
        throw "serverinfo_headers_status_mismatch stage=$Stage"
    }
}

function Assert-ServerInfoReceiptBinding {
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$BodyPath
    )
    Assert-NonEmptyFile -Path $BodyPath -Label 'identity serverinfo body'
    $actual = (Get-FileHash -LiteralPath $BodyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]$Receipt.serverinfo_body_sha256 -notmatch '^[0-9a-f]{64}$' -or
        [string]$Receipt.serverinfo_body_sha256 -cne $actual) {
        throw "serverinfo_receipt_digest_mismatch expected=$($Receipt.serverinfo_body_sha256) actual=$actual"
    }
    return Assert-ServerInfoBody -BodyPath $BodyPath -Stage 'final_identity'
}

function Invoke-ServerInfoOnce {
    param(
        [Parameter(Mandatory = $true)][string]$HeadersPath,
        [Parameter(Mandatory = $true)][string]$BodyPath,
        [Parameter(Mandatory = $true)][string]$Stage,
        [string[]]$Headers = @()
    )
    $arguments = New-Object System.Collections.Generic.List[string]
    foreach ($argument in @(
        '--fail-with-body', '--silent', '--show-error',
        '--max-time', '20', '--proto', '=https', '--tlsv1.2',
        '--cacert', $ServerCaPath,
        '--dump-header', $HeadersPath,
        '--output', $BodyPath,
        '--write-out', '%{http_code}'
    )) {
        $arguments.Add([string]$argument)
    }
    foreach ($header in $Headers) {
        $arguments.Add('--header')
        $arguments.Add([string]$header)
    }
    $arguments.Add("$($ServerBase.TrimEnd('/'))/api/v1/serverinfo")
    $curlResult = Invoke-BoundedNativeTextOnce `
        -Command $script:ResolvedTools.Curl `
        -Arguments $arguments.ToArray() `
        -TimeoutSeconds $CurlParentTimeoutSeconds `
        -TimeoutReason "serverinfo_curl_timeout stage=$Stage" `
        -LaunchReason "serverinfo_curl_launch_failed stage=$Stage"
    if ($curlResult.ExitCode -ne 0) {
        throw "serverinfo_http_failed stage=$Stage rc=$($curlResult.ExitCode) output=$($curlResult.Text -replace '\r?\n', '|')"
    }
    $httpCode = ([string]$curlResult.Text).Trim()
    if ($httpCode -cne '200') {
        throw "serverinfo_http_status stage=$Stage status=$httpCode"
    }
    Assert-NonEmptyFile -Path $HeadersPath -Label "$Stage serverinfo headers"
    return Assert-ServerInfoBody -BodyPath $BodyPath -Stage $Stage
}

function Invoke-BarrierOnce {
    param(
        [Parameter(Mandatory = $true)][ref]$Attempted,
        [Parameter(Mandatory = $true)][string]$BarrierId,
        [Parameter(Mandatory = $true)][ValidateSet('window_start', 'window_end')][string]$Role,
        [Parameter(Mandatory = $true)][string]$HeadersPath,
        [Parameter(Mandatory = $true)][string]$BodyPath
    )
    if ($Attempted.Value) {
        throw "barrier_already_attempted role=$Role"
    }
    # Set before curl. A transport failure is evidence of one failed attempt, never
    # permission to replay the same control boundary.
    $Attempted.Value = $true
    if ($BarrierId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') {
        throw "barrier_uuid_not_v4 role=$Role"
    }
    $arguments = @(
        '--fail-with-body', '--silent', '--show-error',
        '--max-time', '20', '--proto', '=https', '--tlsv1.2',
        '--cacert', $ServerCaPath,
        '--dump-header', $HeadersPath,
        '--output', $BodyPath,
        '--write-out', '%{http_code}',
        '--header', "X-Aneb-Run-Id: $BarrierId",
        '--header', "X-Aneb-Audit-Role: $Role",
        "$($ServerBase.TrimEnd('/'))/api/v1/serverinfo"
    )
    $curlResult = Invoke-BoundedNativeTextOnce `
        -Command $script:ResolvedTools.Curl `
        -Arguments $arguments `
        -TimeoutSeconds $CurlParentTimeoutSeconds `
        -TimeoutReason "barrier_curl_timeout role=$Role" `
        -LaunchReason "barrier_curl_launch_failed role=$Role"
    if ($curlResult.ExitCode -ne 0) {
        throw "barrier_http_failed role=$Role rc=$($curlResult.ExitCode) output=$($curlResult.Text -replace '\r?\n', '|')"
    }
    $httpCode = ([string]$curlResult.Text).Trim()
    if ($httpCode -cne '200') {
        throw "barrier_http_status role=$Role status=$httpCode"
    }
    Assert-NonEmptyFile -Path $HeadersPath -Label "$Role barrier headers"
    return Assert-ServerInfoBody -BodyPath $BodyPath -Stage $Role
}

function Get-PostMarkerLogText {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$MarkerNonce
    )
    if ($MarkerNonce -notmatch '^[0-9a-f]{32}$') {
        throw 'logcat_marker_nonce_invalid'
    }
    $markerPattern = '(?m)^[^\r\n]*D82_CAPTURE_MARKER nonce=' +
        [regex]::Escape($MarkerNonce) + '\s*$'
    $matches = [regex]::Matches($Text, $markerPattern)
    if ($matches.Count -ne 1) {
        throw "logcat_marker_count_invalid count=$($matches.Count)"
    }
    $offset = $matches[0].Index + $matches[0].Length
    return $Text.Substring($offset)
}

function Start-LogcatCapture {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $deviceEpoch = Invoke-AdbTextOnce -Arguments @('shell', 'date +%s') -Stage 'logcat_epoch'
    if ($deviceEpoch -notmatch '^[0-9]{9,12}$') {
        throw "device_epoch_invalid value=$deviceEpoch"
    }
    $stdoutPath = Join-Path $EvidenceDirectory 'app-logcat.txt'
    $stderrPath = Join-Path $EvidenceDirectory 'app-logcat.stderr.txt'
    $arguments = @(
        '-s', $AdbSerial,
        'logcat', '-v', 'epoch', '-T', "$deviceEpoch.000",
        '-s', 'AnebProbe:I', 'AnebD82:I', '*:S'
    )
    $process = Start-Process -FilePath $script:ResolvedTools.Adb `
        -ArgumentList (($arguments | ForEach-Object { ConvertTo-NativeArgument -Argument ([string]$_) }) -join ' ') `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru
    if ($null -eq $process) {
        throw 'logcat_launch_failed'
    }
    return [pscustomobject]@{
        Process = $process
        OutputPath = $stdoutPath
        ErrorPath = $stderrPath
        DeviceEpoch = [int64]$deviceEpoch
    }
}

function Write-LogcatCaptureMarker {
    param(
        [Parameter(Mandatory = $true)]$Logcat,
        [Parameter(Mandatory = $true)][string]$MarkerNonce,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )
    if ($MarkerNonce -notmatch '^[0-9a-f]{32}$') {
        throw 'logcat_marker_nonce_invalid'
    }
    if ($Logcat.Process.HasExited) {
        throw "logcat_exited_before_marker rc=$($Logcat.Process.ExitCode)"
    }
    $marker = "D82_CAPTURE_MARKER nonce=$MarkerNonce"
    $null = Invoke-AdbTextOnce -Arguments @(
        'shell', "log -p i -t AnebD82 '$marker'"
    ) -Stage 'write_logcat_capture_marker'
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Logcat.Process.HasExited) {
            throw "logcat_exited_before_marker_visible rc=$($Logcat.Process.ExitCode)"
        }
        $text = if (Test-Path -LiteralPath $Logcat.OutputPath -PathType Leaf) {
            Get-Content -LiteralPath $Logcat.OutputPath -Raw -Encoding UTF8
        } else { '' }
        $markerMatches = [regex]::Matches(
            $text,
            '(?m)^[^\r\n]*D82_CAPTURE_MARKER nonce=' + [regex]::Escape($MarkerNonce) + '\s*$'
        )
        if ($markerMatches.Count -gt 1) {
            throw "logcat_marker_count_invalid count=$($markerMatches.Count)"
        }
        if ($markerMatches.Count -eq 1) {
            $null = Get-PostMarkerLogText -Text $text -MarkerNonce $MarkerNonce
            Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'logcat-capture-marker.json') -Value ([ordered]@{
                schema = 'aneb-d82-logcat-capture-marker'
                schema_version = '1.0.0'
                captured_at_utc = [DateTime]::UtcNow.ToString(
                    "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
                    [Globalization.CultureInfo]::InvariantCulture
                )
                marker_nonce = $MarkerNonce
                marker = $marker
            })
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw 'logcat_marker_not_visible'
}

function Start-TokenQuickRun {
    $script:AppLaunchAttempted = $true
    $component = "$PackageName/com.aneb.probe.ui.MainActivity"
    $clientServerBase = if ($EvidenceMode -ceq 'negative') {
        $NegativeClientServerBase
    } else {
        $ServerBase
    }
    $command = "am start -W -n '$component' --es server '$clientServerBase' --ez autorun true --es mode quick --es transport '$Transport' --es test_mode token"
    $output = Invoke-AdbTextOnce -Arguments @('shell', $command) -Stage 'start_token_quick'
    if ($output -notmatch '(?m)^Status:\s*ok\s*$') {
        throw "app_launch_not_ok output=$output"
    }
    $script:AppStarted = $true
    return $output
}

function Assert-NegativeAdbReverseState {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][ValidateSet('preflight', 'active', 'final')][string]$Stage,
        [Parameter(Mandatory = $true)][ValidateRange(1024, 65535)][int]$DevicePort,
        [Parameter(Mandatory = $true)][ValidateRange(0, 65535)][int]$HostPort
    )
    $lines = @($Text -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($Stage -in @('preflight', 'final')) {
        if ($lines.Count -ne 0) {
            if ($Stage -ceq 'preflight') {
                throw 'negative_reverse_preexisting_mapping'
            }
            throw 'negative_reverse_residual_mapping'
        }
        return [pscustomobject]@{ Stage = $Stage; MappingCount = 0 }
    }
    if ($HostPort -lt 1 -or $lines.Count -ne 1) {
        throw 'negative_reverse_active_mapping_invalid'
    }
    $pattern = '^\S+\s+tcp:' + [regex]::Escape([string]$DevicePort) +
        '\s+tcp:' + [regex]::Escape([string]$HostPort) + '$'
    if ([string]$lines[0] -notmatch $pattern) {
        throw 'negative_reverse_active_mapping_invalid'
    }
    return [pscustomobject]@{
        Stage = $Stage
        MappingCount = 1
        DevicePort = $DevicePort
        HostPort = $HostPort
    }
}

function Assert-NegativeProxyReadyOutput {
    param([Parameter(Mandatory = $true)][string]$Text)
    $lines = @($Text -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($lines.Count -ne 1) {
        throw 'negative_proxy_ready_line_count_invalid'
    }
    try {
        $ready = [string]$lines[0] | ConvertFrom-Json
    } catch {
        throw 'negative_proxy_ready_json_invalid'
    }
    $expected = @('listen_host', 'listen_port', 'status')
    $actual = @($ready.PSObject.Properties.Name | Sort-Object)
    if (($actual -join "`0") -cne (($expected | Sort-Object) -join "`0") -or
        [string]$ready.status -cne 'ready' -or
        [string]$ready.listen_host -cne '127.0.0.1' -or
        ($ready.listen_port -isnot [int] -and $ready.listen_port -isnot [long]) -or
        [int64]$ready.listen_port -lt 1 -or [int64]$ready.listen_port -gt 65535) {
        throw 'negative_proxy_ready_contract_invalid'
    }
    return [pscustomobject]@{
        ListenHost = '127.0.0.1'
        ListenPort = [int]$ready.listen_port
    }
}

function Assert-NegativeProxyFinalOutput {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$ExpectedRunId,
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$ExpectedPort,
        [Parameter(Mandatory = $true)][int]$ExitCode,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$StderrText
    )
    $lines = @($Text -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($lines.Count -ne 2) {
        throw 'negative_proxy_output_line_count_invalid'
    }
    if ($ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($StderrText)) {
        throw 'negative_proxy_process_failed'
    }
    $ready = Assert-NegativeProxyReadyOutput -Text ([string]$lines[0])
    if ([int]$ready.ListenPort -ne $ExpectedPort) {
        throw 'negative_proxy_port_binding_mismatch'
    }
    try {
        $final = [string]$lines[1] | ConvertFrom-Json
    } catch {
        throw 'negative_proxy_final_json_invalid'
    }
    $expected = @('listen_host', 'listen_port', 'reason_code', 'run_id', 'status')
    $actual = @($final.PSObject.Properties.Name | Sort-Object)
    if (($actual -join "`0") -cne (($expected | Sort-Object) -join "`0") -or
        [string]$final.status -cne 'pass' -or
        [string]$final.reason_code -cne 'ok' -or
        [string]$final.run_id -cne $ExpectedRunId -or
        [string]$final.listen_host -cne '127.0.0.1' -or
        ($final.listen_port -isnot [int] -and $final.listen_port -isnot [long]) -or
        [int64]$final.listen_port -ne $ExpectedPort) {
        throw 'negative_proxy_final_contract_invalid'
    }
    return [pscustomobject]@{
        RunId = [string]$final.run_id
        ListenPort = [int]$final.listen_port
    }
}

function Start-NegativeProxyAndReverse {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    if ($EvidenceMode -cne 'negative') {
        return
    }
    if ($null -ne $script:NegativeProxy -or $script:NegativeReversePreflightCaptured) {
        throw 'negative_proxy_duplicate_start'
    }
    $reversePreflight = Invoke-AdbTextOnce `
        -Arguments @('reverse', '--list') `
        -Stage 'negative_reverse_preflight'
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory 'adb-reverse-preflight.txt') `
        -Text ($reversePreflight + "`n")
    $null = Assert-NegativeAdbReverseState `
        -Text $reversePreflight `
        -Stage 'preflight' `
        -DevicePort $NegativeProxyDevicePort `
        -HostPort 0
    $script:NegativeReversePreflightCaptured = $true

    $proxyEvidenceDirectory = Join-Path $EvidenceDirectory 'negative-proxy'
    $stdoutPath = Join-Path $EvidenceDirectory 'negative-proxy.stdout.jsonl'
    $stderrPath = Join-Path $EvidenceDirectory 'negative-proxy.stderr.txt'
    foreach ($candidate in @($proxyEvidenceDirectory, $stdoutPath, $stderrPath)) {
        if (Test-Path -LiteralPath $candidate) {
            throw 'negative_proxy_evidence_path_collision'
        }
    }
    $arguments = @(
        $script:NegativeProxyPath,
        '--upstream-url', [string]$script:ResolvedTools.NegativeProxyUpstreamUrl,
        '--ca-file', $script:ServerCaPath,
        '--evidence-dir', $proxyEvidenceDirectory,
        '--listen-port', '0',
        '--request-timeout-seconds', '60',
        '--upstream-timeout-seconds', '15'
    )
    $process = Start-Process `
        -FilePath $script:ResolvedTools.Python `
        -ArgumentList (($arguments | ForEach-Object {
            ConvertTo-NativeArgument -Argument ([string]$_)
        }) -join ' ') `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru
    if ($null -eq $process) {
        throw 'negative_proxy_launch_failed'
    }
    $script:NegativeProxy = [pscustomobject]@{
        Process = $process
        EvidenceDirectory = $proxyEvidenceDirectory
        OutputPath = $stdoutPath
        ErrorPath = $stderrPath
        ListenPort = $null
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    $ready = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        $output = if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) {
            Get-Content -LiteralPath $stdoutPath -Raw -Encoding UTF8
        } else { '' }
        $readyLines = @($output -split "`r?`n" | Where-Object {
            -not [string]::IsNullOrWhiteSpace([string]$_)
        })
        if ($readyLines.Count -gt 1) {
            throw 'negative_proxy_ready_line_count_invalid'
        }
        if ($readyLines.Count -eq 1) {
            $ready = Assert-NegativeProxyReadyOutput -Text ([string]$readyLines[0])
            break
        }
        if ($process.HasExited) {
            $process.WaitForExit()
            $stderr = if (Test-Path -LiteralPath $stderrPath -PathType Leaf) {
                (Get-Content -LiteralPath $stderrPath -Raw -Encoding UTF8).Trim()
            } else { '' }
            throw "negative_proxy_exited_before_ready rc=$($process.ExitCode) error=$stderr"
        }
        Start-Sleep -Milliseconds 100
    }
    if ($null -eq $ready) {
        throw 'negative_proxy_ready_timeout'
    }
    $script:NegativeProxy.ListenPort = [int]$ready.ListenPort

    $script:NegativeReverseMutationAttempted = $true
    $null = Invoke-AdbTextOnce `
        -Arguments @(
            'reverse', '--no-rebind',
            "tcp:$NegativeProxyDevicePort",
            "tcp:$([int]$ready.ListenPort)"
        ) `
        -Stage 'negative_reverse_add'
    $script:NegativeReverseAdded = $true
    $active = Invoke-AdbTextOnce `
        -Arguments @('reverse', '--list') `
        -Stage 'negative_reverse_active'
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory 'adb-reverse-active.txt') `
        -Text ($active + "`n")
    $null = Assert-NegativeAdbReverseState `
        -Text $active `
        -Stage 'active' `
        -DevicePort $NegativeProxyDevicePort `
        -HostPort ([int]$ready.ListenPort)
}

function Wait-NegativeProxyCompletion {
    param([Parameter(Mandatory = $true)][string]$RunId)
    if ($EvidenceMode -cne 'negative') {
        return
    }
    if ($null -eq $script:NegativeProxy -or
        $script:NegativeProxy.ListenPort -isnot [int]) {
        throw 'negative_proxy_not_started'
    }
    $process = $script:NegativeProxy.Process
    if (-not $process.WaitForExit(10000)) {
        throw 'negative_proxy_completion_timeout'
    }
    $process.WaitForExit()
    $stdout = if (Test-Path -LiteralPath $script:NegativeProxy.OutputPath -PathType Leaf) {
        Get-Content -LiteralPath $script:NegativeProxy.OutputPath -Raw -Encoding UTF8
    } else { '' }
    $stderr = if (Test-Path -LiteralPath $script:NegativeProxy.ErrorPath -PathType Leaf) {
        Get-Content -LiteralPath $script:NegativeProxy.ErrorPath -Raw -Encoding UTF8
    } else { '' }
    $null = Assert-NegativeProxyFinalOutput `
        -Text $stdout `
        -ExpectedRunId $RunId `
        -ExpectedPort ([int]$script:NegativeProxy.ListenPort) `
        -ExitCode ([int]$process.ExitCode) `
        -StderrText $stderr
    foreach ($name in @(
        'upstream-serverinfo.raw', 'filtered-serverinfo.json',
        'upstream-serverinfo.headers.json', 'peer-certificate.sha256',
        'request-ledger.json', 'proxy-receipt.json'
    )) {
        Assert-NonEmptyFile `
            -Path (Join-Path $script:NegativeProxy.EvidenceDirectory $name) `
            -Label "negative proxy evidence $name"
    }
    $script:NegativeProxyCompleted = $true
}

function Remove-NegativeAdbReverseOnce {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    if ($EvidenceMode -cne 'negative' -or $script:NegativeReverseFinalCaptured) {
        return
    }
    if (-not $script:NegativeReversePreflightCaptured) {
        return
    }
    $script:NegativeReverseRemoveAttempted = $true
    $beforeRemove = Invoke-AdbTextOnce `
        -Arguments @('reverse', '--list') `
        -Stage 'negative_reverse_before_remove'
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory 'adb-reverse-before-remove.txt') `
        -Text ($beforeRemove + "`n")
    $beforeLines = @($beforeRemove -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($beforeLines.Count -gt 0) {
        if (-not $script:NegativeReverseMutationAttempted -or
            $null -eq $script:NegativeProxy -or
            $script:NegativeProxy.ListenPort -isnot [int]) {
            throw 'negative_reverse_unowned_mapping_before_remove'
        }
        $null = Assert-NegativeAdbReverseState `
            -Text $beforeRemove `
            -Stage 'active' `
            -DevicePort $NegativeProxyDevicePort `
            -HostPort ([int]$script:NegativeProxy.ListenPort)
        $null = Invoke-AdbTextOnce `
            -Arguments @('reverse', '--remove', "tcp:$NegativeProxyDevicePort") `
            -Stage 'negative_reverse_remove'
        $script:NegativeReverseAdded = $false
    }
    $final = Invoke-AdbTextOnce `
        -Arguments @('reverse', '--list') `
        -Stage 'negative_reverse_final'
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory 'adb-reverse-final.txt') `
        -Text ($final + "`n")
    $null = Assert-NegativeAdbReverseState `
        -Text $final `
        -Stage 'final' `
        -DevicePort $NegativeProxyDevicePort `
        -HostPort 0
    $script:NegativeReverseFinalCaptured = $true
}

function Stop-NegativeProxyOnce {
    if ($EvidenceMode -cne 'negative' -or $script:NegativeProxyStopSucceeded) {
        return
    }
    if ($null -eq $script:NegativeProxy) {
        return
    }
    $script:NegativeProxyStopAttempted = $true
    $process = $script:NegativeProxy.Process
    if (-not $process.HasExited) {
        $process.Kill()
        if (-not $process.WaitForExit(5000)) {
            throw 'negative_proxy_stop_timeout'
        }
    }
    $process.WaitForExit()
    $script:NegativeProxyStopSucceeded = $true
}

function Get-TokenQuickCompletionFromLog {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$MarkerNonce,
        [Parameter(Mandatory = $true)][ValidateSet('positive', 'negative')][string]$EvidenceModeValue
    )
    $postMarkerText = Get-PostMarkerLogText -Text $Text -MarkerNonce $MarkerNonce
    $uuidv7Pattern = '[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}'
    $startMatches = [regex]::Matches($postMarkerText, "TOKEN_V2_START run_id=($uuidv7Pattern)\b")
    $runIds = @($startMatches | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($runIds.Count -gt 1 -or $startMatches.Count -gt 1) {
        throw 'multiple_token_runs_observed'
    }
    if ($runIds.Count -eq 0) {
        return $null
    }
    $runId = [string]$runIds[0]
    $escaped = [regex]::Escape($runId)
    if ($EvidenceModeValue -ceq 'negative') {
        foreach ($forbidden in @(
            "TOKEN_V2_RESULT run_id=$escaped\b",
            "TOKEN_V2_CONTRACT run_id=$escaped status=validated_receipt\b",
            "TOKEN_V2_FAILED run_id=$escaped\b",
            "TOKEN_V2_TASK_(?:START|END) run_id=$escaped\b",
            '(?m)^.*TOKEN_V2_PROFILE\b'
        )) {
            if ([regex]::IsMatch($postMarkerText, $forbidden)) {
                throw 'negative_token_business_log_observed'
            }
        }
        $terminal = [regex]::Matches(
            $postMarkerText,
            "TOKEN_V2_END run_id=$escaped status=([^\s]+)"
        )
        if ($terminal.Count -gt 1 -or
            ($terminal.Count -eq 1 -and [string]$terminal[0].Groups[1].Value -cne 'contract_rejected')) {
            throw 'negative_token_terminal_status_invalid'
        }
        $required = @(
            "TOKEN_V2_RADIO run_id=$escaped status=[^\s]+ samples=[0-9]+\b",
            "TOKEN_V2_DB_WRITE run_id=$escaped ok=true\b",
            "TOKEN_V2_CONTRACT run_id=$escaped status=rejected reason=receipt_missing\b",
            "TOKEN_V2_END run_id=$escaped status=contract_rejected\b"
        )
        $positions = New-Object System.Collections.Generic.List[int]
        $positions.Add([int]$startMatches[0].Index)
        foreach ($pattern in $required) {
            $matches = [regex]::Matches($postMarkerText, $pattern)
            if ($matches.Count -gt 1) {
                throw 'negative_token_log_cardinality_invalid'
            }
            if ($matches.Count -ne 1) {
                return $null
            }
            $positions.Add([int]$matches[0].Index)
        }
        for ($index = 1; $index -lt $positions.Count; $index++) {
            if ($positions[$index - 1] -ge $positions[$index]) {
                throw 'negative_token_log_order_invalid'
            }
        }
        return $runId
    }

    $failedEnd = [regex]::Match($postMarkerText, "TOKEN_V2_END run_id=$escaped status=(?!completed\b)[^\s]+")
    if ($failedEnd.Success) {
        throw "token_run_not_completed line=$($failedEnd.Value)"
    }
    foreach ($pattern in @(
        "TOKEN_V2_CONTRACT run_id=$escaped status=validated_receipt\b",
        "TOKEN_V2_DB_WRITE run_id=$escaped ok=true\b",
        "TOKEN_V2_RESULT run_id=$escaped ",
        "TOKEN_V2_END run_id=$escaped status=completed\b"
    )) {
        if ([regex]::Matches($postMarkerText, $pattern).Count -ne 1) {
            return $null
        }
    }
    return $runId
}

function Wait-TokenQuickCompletion {
    param(
        [Parameter(Mandatory = $true)]$Logcat,
        [Parameter(Mandatory = $true)][string]$MarkerNonce
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($RunTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Logcat.Process.HasExited) {
            throw "logcat_exited_early rc=$($Logcat.Process.ExitCode)"
        }
        $text = if (Test-Path -LiteralPath $Logcat.OutputPath -PathType Leaf) {
            Get-Content -LiteralPath $Logcat.OutputPath -Raw -Encoding UTF8
        } else { '' }
        $runId = Get-TokenQuickCompletionFromLog `
            -Text $text `
            -MarkerNonce $MarkerNonce `
            -EvidenceModeValue $EvidenceMode
        if (-not [string]::IsNullOrWhiteSpace([string]$runId)) {
            # The version nibble is fixed to 7: the target run comes from this
            # App launch's fresh UUIDv7 log, never from a CLI argument.
            return [string]$runId
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'token_run_timeout'
}

function Invoke-NativeToFileOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$Stage,
        [ValidateRange(1, 300)][int]$TimeoutSeconds = 60
    )
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Command
    $startInfo.Arguments = (($Arguments | ForEach-Object {
        ConvertTo-NativeArgument -Argument ([string]$_)
    }) -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "process_launch_failed stage=$Stage"
    }
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $stream = [System.IO.File]::Open(
        $OutputPath,
        [System.IO.FileMode]::Create,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    try {
        $copyTask = $process.StandardOutput.BaseStream.CopyToAsync($stream)
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            throw "process_timeout stage=$Stage"
        }
        if (-not $copyTask.Wait(5000)) {
            throw "stdout_copy_timeout stage=$Stage"
        }
    } finally {
        $stream.Dispose()
    }
    $stderr = if ($stderrTask.Wait(5000)) { [string]$stderrTask.Result } else { '' }
    if ($process.ExitCode -ne 0) {
        throw "process_failed stage=$Stage rc=$($process.ExitCode) stderr=$stderr"
    }
}

function Get-RemotePreStartSnapshot {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )
    Assert-PersistentAuditLock -Lock $Lock -Stage 'pre_start_snapshot'
    $command = @'
set -Eeuo pipefail
unit='aneb-server.service'
active="$(systemctl show "$unit" --property=ActiveState --value)"
[[ "$active" == 'active' ]]
boot_id="$(cat /proc/sys/kernel/random/boot_id)"
boot_id="${boot_id//-/}"
invocation_id="$(systemctl show "$unit" --property=InvocationID --value)"
main_pid="$(systemctl show "$unit" --property=MainPID --value)"
binary_sha256="$(sha256sum /opt/aneb/bin/aneb-server | awk '{print $1}')"
remote_realtime_anchor_usec="$(date +%s%6N)"
journal_anchor_json="$(journalctl --unit "$unit" --lines=1 --output=json --output-fields=__CURSOR,__MONOTONIC_TIMESTAMP --no-pager)"
[[ -n "$journal_anchor_json" ]]
journal_anchor_json_base64="$(printf '%s' "$journal_anchor_json" | base64 -w0)"
printf 'boot_id=%s\n' "$boot_id"
printf 'systemd_invocation_id=%s\n' "$invocation_id"
printf 'main_pid=%s\n' "$main_pid"
printf 'server_binary_sha256=%s\n' "$binary_sha256"
printf 'remote_realtime_anchor_usec=%s\n' "$remote_realtime_anchor_usec"
printf 'journal_anchor_json_base64=%s\n' "$journal_anchor_json_base64"
'@
    $raw = Invoke-SshTextOnce -RemoteCommand $command -Stage 'pre_start_snapshot'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'remote-pre-start.txt') -Text ($raw.TrimEnd() + "`n")
    $values = @{}
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -notmatch '^([a-z0-9_]+)=(.+)$') {
            throw "remote_snapshot_line_invalid line=$line"
        }
        if ($values.ContainsKey($Matches[1])) {
            throw "remote_snapshot_duplicate key=$($Matches[1])"
        }
        $values[$Matches[1]] = $Matches[2]
    }
    foreach ($required in @(
        'boot_id', 'systemd_invocation_id', 'main_pid',
        'server_binary_sha256', 'remote_realtime_anchor_usec',
        'journal_anchor_json_base64'
    )) {
        if (-not $values.ContainsKey($required)) {
            throw "remote_snapshot_missing key=$required"
        }
    }
    if ($values.boot_id -notmatch '^[0-9a-f]{32}$' -or
        $values.systemd_invocation_id -notmatch '^[0-9a-f]{32}$' -or
        $values.main_pid -notmatch '^[1-9][0-9]*$' -or
        $values.server_binary_sha256 -notmatch '^[0-9a-f]{64}$' -or
        $values.remote_realtime_anchor_usec -notmatch '^[1-9][0-9]{0,19}$' -or
        $values.journal_anchor_json_base64 -notmatch '^[A-Za-z0-9+/]+={0,2}$') {
        throw 'remote_snapshot_field_invalid'
    }
    try {
        $anchorBytes = [Convert]::FromBase64String([string]$values.journal_anchor_json_base64)
        $anchorText = [Text.Encoding]::UTF8.GetString($anchorBytes)
        $anchorRecord = $anchorText | ConvertFrom-Json
    } catch {
        throw 'remote_snapshot_journal_anchor_invalid'
    }
    $anchorProperties = @($anchorRecord.PSObject.Properties.Name)
    if ($anchorProperties -notcontains '__CURSOR' -or
        $anchorProperties -notcontains '__MONOTONIC_TIMESTAMP') {
        throw 'remote_snapshot_journal_anchor_missing_fields'
    }
    $journalCursor = [string]$anchorRecord.__CURSOR
    $journalMonotonicAnchor = [string]$anchorRecord.__MONOTONIC_TIMESTAMP
    if ($journalCursor -notmatch '^[A-Za-z0-9;:_.=-]{10,1024}$' -or
        $journalMonotonicAnchor -notmatch '^[1-9][0-9]{0,19}$') {
        throw 'remote_snapshot_journal_anchor_field_invalid'
    }
    try {
        $remoteRealtimeAnchorUsec = [uint64]$values.remote_realtime_anchor_usec
        $journalMonotonicAnchorValue = [uint64]$journalMonotonicAnchor
    } catch {
        throw 'remote_snapshot_journal_anchor_range_invalid'
    }
    if ($values.server_binary_sha256 -ine $ExpectedServerBinarySha256) {
        throw "server_binary_sha256_mismatch actual=$($values.server_binary_sha256)"
    }
    return [pscustomobject]@{
        BootId = [string]$values.boot_id
        InvocationId = [string]$values.systemd_invocation_id
        MainPid = [int64]$values.main_pid
        BinarySha256 = ([string]$values.server_binary_sha256).ToLowerInvariant()
        JournalCursor = $journalCursor
        JournalMonotonicAnchor = $journalMonotonicAnchorValue
        RemoteRealtimeAnchorUsec = $remoteRealtimeAnchorUsec
    }
}

function Write-PreStartReceipt {
    param(
        [Parameter(Mandatory = $true)]$Snapshot,
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)][string]$ServerInfoBodyPath,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $bodySha = (Get-FileHash -LiteralPath $ServerInfoBodyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $receipt = [ordered]@{
        schema = 'aneb-token-evidence-pre-start-receipt'
        schema_version = '1.0.0'
        captured_at_utc = [DateTime]::UtcNow.ToString(
            "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
            [Globalization.CultureInfo]::InvariantCulture
        )
        journal_cursor = $Snapshot.JournalCursor
        journal_monotonic_anchor = [uint64]$Snapshot.JournalMonotonicAnchor
        remote_realtime_anchor_usec = [uint64]$Snapshot.RemoteRealtimeAnchorUsec
        boot_id = $Snapshot.BootId
        systemd_invocation_id = $Snapshot.InvocationId
        unit = 'aneb-server.service'
        main_pid = $Snapshot.MainPid
        server_base = $ServerBase.TrimEnd('/')
        server_version = $ExpectedServerVersion
        server_binary_sha256 = $Snapshot.BinarySha256
        serverinfo_body_sha256 = $bodySha
        lock_path = $RemoteLockPath
        lock_nonce = $Lock.Nonce
        lock_remote_pid = $Lock.RemotePid
        lock_marker = $Lock.Marker
    }
    Write-JsonNoBom -Path $OutputPath -Value $receipt
    return [pscustomobject]$receipt
}

function Export-LockedJournalOnce {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)][string]$JournalCursor,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    if ($JournalCursor -notmatch '^[A-Za-z0-9;:_.=-]{10,1024}$') {
        throw 'journal_cursor_unsafe'
    }
    Assert-PersistentAuditLock -Lock $Lock -Stage 'journal_export'
    $remoteCommand = "set -Eeuo pipefail; journalctl --unit aneb-server.service --after-cursor '$JournalCursor' --output=json --output-fields=__CURSOR,__REALTIME_TIMESTAMP,__MONOTONIC_TIMESTAMP,_BOOT_ID,_SYSTEMD_INVOCATION_ID,_SYSTEMD_UNIT,_PID,MESSAGE --no-pager"
    Invoke-NativeToFileOnce `
        -Command $script:ResolvedTools.Ssh `
        -Arguments (Get-SshArguments -RemoteCommand $remoteCommand) `
        -OutputPath $OutputPath `
        -Stage 'journal_export' `
        -TimeoutSeconds 60
    Assert-NonEmptyFile -Path $OutputPath -Label 'raw journald JSONL export'
}

function Assert-RequestEntryAuditReport {
    param(
        [Parameter(Mandatory = $true)]$Audit,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$StartBarrierId,
        [Parameter(Mandatory = $true)][string]$EndBarrierId,
        [Parameter(Mandatory = $true)][string]$ExpectedContractDigest
    )
    if ($ExpectedContractDigest -notmatch '^[0-9a-f]{64}$') {
        throw 'expected_profile_contract_definition_sha256_invalid'
    }
    if ([string]$Audit.profile_contract_definition_sha256 -cne $ExpectedContractDigest) {
        throw (
            'profile_contract_definition_sha256_mismatch expected={0} actual={1}' -f
            $ExpectedContractDigest, [string]$Audit.profile_contract_definition_sha256
        )
    }
    $expectedEnforcement = if ($EvidenceMode -ceq 'negative') {
        'negative_zero_business'
    } else {
        'positive_exact_business_counts'
    }
    if ([string]$Audit.status -cne 'pass' -or
        [string]$Audit.reason_code -cne 'ok' -or
        [string]$Audit.schema -cne 'aneb-token-request-entry-audit-report' -or
        [string]$Audit.schema_version -cne '2.1.0' -or
        [string]$Audit.mode -cne $EvidenceMode -or
        [string]$Audit.run_id -cne $RunId -or
        [string]$Audit.start_barrier_id -cne $StartBarrierId -or
        [string]$Audit.barrier_id -cne $EndBarrierId -or
        [string]$Audit.profile_contract -cne $ProfileContract -or
        [string]$Audit.profile_contract_enforcement -cne $expectedEnforcement -or
        [string]$Audit.evidence_scope -cne 'request_entry_coverage_only') {
        throw 'request_entry_audit_identity_or_enforcement_mismatch'
    }
    if ([int64]$Audit.expected_business_counts.echo -ne 20 -or
        [int64]$Audit.expected_business_counts.token_sim -ne 3 -or
        [int64]$Audit.expected_business_counts.download -ne 1) {
        throw 'request_entry_audit_expected_business_counts_mismatch'
    }
    if ($EvidenceMode -ceq 'negative') {
        if ([int64]$Audit.counts.control -ne 1 -or
            [int64]$Audit.counts.business.echo -ne 0 -or
            [int64]$Audit.counts.business.token_sim -ne 0 -or
            [int64]$Audit.counts.business.download -ne 0 -or
            [int64]$Audit.counts.business.unexpected -ne 0 -or
            [int64]$Audit.counts.business_total -ne 0 -or
            [int64]$Audit.counts.unattributed_business -ne 0 -or
            [int64]$Audit.counts.unexpected_control -ne 0) {
            throw 'request_entry_audit_negative_counts_mismatch'
        }
    } elseif ([int64]$Audit.counts.business.echo -ne 20 -or
        [int64]$Audit.counts.business.token_sim -ne 3 -or
        [int64]$Audit.counts.business.download -ne 1 -or
        [int64]$Audit.counts.business.unexpected -ne 0 -or
        [int64]$Audit.counts.business_total -ne 24 -or
        [int64]$Audit.counts.unattributed_business -ne 0 -or
        [int64]$Audit.counts.unexpected_control -ne 0) {
        throw 'request_entry_audit_observed_business_counts_mismatch'
    }
}

function Invoke-EvidenceDerivationAndAudit {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$StartBarrierId,
        [Parameter(Mandatory = $true)][string]$EndBarrierId
    )
    if ((Split-Path -Leaf $DeriveHelperPath) -cne 'prepare_token_run_evidence.py') {
        throw 'derive_helper_identity_invalid'
    }
    if ((Split-Path -Leaf $AuditVerifierPath) -cne 'verify_token_run_audit.py') {
        throw 'audit_verifier_identity_invalid'
    }
    $journal = Join-Path $EvidenceDirectory 'journal.raw.jsonl'
    $receipt = Join-Path $EvidenceDirectory 'pre-start-receipt.json'
    $messageLog = Join-Path $EvidenceDirectory 'token-run-audit.log'
    $derivation = Join-Path $EvidenceDirectory 'journal-derivation.json'
    $deriveOutput = Invoke-ToolTextOnce -Command $script:ResolvedTools.Python -Arguments @(
        $DeriveHelperPath,
        'derive',
        '--journal', $journal,
        '--pre-start-receipt', $receipt,
        '--run-id', $RunId,
        '--start-barrier-id', $StartBarrierId,
        '--end-barrier-id', $EndBarrierId,
        '--message-output', $messageLog,
        '--derivation-output', $derivation
    ) -Stage 'prepare_token_run_evidence.py_derive'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'journal-derivation.stdout.txt') -Text ($deriveOutput + "`n")
    Assert-NonEmptyFile -Path $messageLog -Label 'derived audit message log'
    Assert-NonEmptyFile -Path $derivation -Label 'journal derivation report'

    $auditOutput = Invoke-ToolTextOnce -Command $script:ResolvedTools.Python -Arguments @(
        $AuditVerifierPath,
        $messageLog,
        '--run-id', $RunId,
        '--start-barrier-id', $StartBarrierId,
        '--barrier-id', $EndBarrierId,
        '--mode', $EvidenceMode,
        '--profile-contract', 'token_multimodal_quick@1.2.1'
    ) -Stage 'verify_token_run_audit.py'
    $auditPath = Join-Path $EvidenceDirectory 'request-entry-audit.json'
    Write-TextNoBom -Path $auditPath -Text ($auditOutput + "`n")
    try {
        $audit = $auditOutput | ConvertFrom-Json
    } catch {
        throw 'request_entry_audit_json_invalid'
    }
    Assert-RequestEntryAuditReport `
        -Audit $audit `
        -RunId $RunId `
        -StartBarrierId $StartBarrierId `
        -EndBarrierId $EndBarrierId `
        -ExpectedContractDigest $script:ResolvedTools.ProfileContractDefinitionSha256
    return $audit
}

function Copy-FrozenRoomDatabase {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $remoteFiles = [ordered]@{
        'aneb-probe.db' = 'databases/aneb-probe.db'
        'aneb-probe.db-wal' = 'databases/aneb-probe.db-wal'
        'aneb-probe.db-shm' = 'databases/aneb-probe.db-shm'
    }
    $states = @{}
    foreach ($entry in $remoteFiles.GetEnumerator()) {
        $remotePath = [string]$entry.Value
        $state = Invoke-AdbTextOnce -Arguments @(
            'shell',
            "run-as '$PackageName' sh -c 'if [ -r `"$remotePath`" ]; then echo present; else echo absent; fi'"
        ) -Stage "room_state_$($entry.Key)"
        if ($state -notin @('present', 'absent')) {
            throw "room_file_state_invalid file=$($entry.Key) state=$state"
        }
        $states[$entry.Key] = $state
    }
    if ($states['aneb-probe.db'] -cne 'present') {
        throw 'room_main_database_missing'
    }
    if ($states['aneb-probe.db-wal'] -cne $states['aneb-probe.db-shm']) {
        throw 'room_wal_shm_state_mismatch'
    }

    $inventory = New-Object System.Collections.Generic.List[object]
    foreach ($entry in $remoteFiles.GetEnumerator()) {
        $name = [string]$entry.Key
        $remotePath = [string]$entry.Value
        if ($states[$name] -cne 'present') {
            $inventory.Add([ordered]@{ name = $name; state = 'absent' })
            continue
        }
        $digestBefore = Invoke-AdbTextOnce -Arguments @(
            'shell', "run-as '$PackageName' sha256sum '$remotePath'"
        ) -Stage "room_digest_before_$name"
        if ($digestBefore -notmatch '^([0-9a-f]{64})\s+') {
            throw "room_remote_digest_invalid file=$name"
        }
        $remoteSha = $Matches[1]
        $localPath = Join-Path $EvidenceDirectory $name
        Invoke-NativeToFileOnce `
            -Command $script:ResolvedTools.Adb `
            -Arguments @('-s', $AdbSerial, 'exec-out', 'run-as', $PackageName, 'cat', $remotePath) `
            -OutputPath $localPath `
            -Stage "room_copy_$name" `
            -TimeoutSeconds 60
        Assert-NonEmptyFile -Path $localPath -Label "frozen Room file $name"
        $localSha = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $digestAfter = Invoke-AdbTextOnce -Arguments @(
            'shell', "run-as '$PackageName' sha256sum '$remotePath'"
        ) -Stage "room_digest_after_$name"
        if ($digestAfter -notmatch '^([0-9a-f]{64})\s+' -or
            $Matches[1] -cne $remoteSha -or
            $localSha -cne $remoteSha) {
            throw "room_file_digest_mismatch file=$name"
        }
        $inventory.Add([ordered]@{
            name = $name
            state = 'present'
            bytes = (Get-Item -LiteralPath $localPath).Length
            sha256 = $localSha
        })
    }
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'room-copy-inventory.json') -Value ([ordered]@{
        schema = 'aneb-frozen-room-copy'
        schema_version = '1.0.0'
        captured_at_utc = [DateTime]::UtcNow.ToString('o')
        app_process_state = 'stopped_before_copy'
        files = @($inventory)
    })
}

function Invoke-ClientDbVerification {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$RunId
    )
    $negative = $EvidenceMode -ceq 'negative'
    $verifierPath = if ($negative) {
        $NegativeClientDbVerifierPath
    } else {
        $ClientDbVerifierPath
    }
    $expectedVerifierName = if ($negative) {
        'verify_token_quick_negative_client_db.py'
    } else {
        'verify_token_quick_client_db.py'
    }
    if ((Split-Path -Leaf $verifierPath) -cne $expectedVerifierName) {
        throw 'client_db_verifier_identity_invalid'
    }
    $database = Join-Path $EvidenceDirectory 'aneb-probe.db'
    $resultPath = Join-Path $EvidenceDirectory 'client-result.json'
    $reportPath = Join-Path $EvidenceDirectory 'client-db-report.json'
    $arguments = New-Object System.Collections.Generic.List[string]
    $arguments.Add([string]$verifierPath)
    $arguments.Add([string]$database)
    if ($negative) {
        foreach ($argument in @(
            '--inventory', (Join-Path $EvidenceDirectory 'room-copy-inventory.json'),
            '--run-id', $RunId,
            '--manifest', ([string]$script:ResolvedTools.ToolingFiles['profile_manifest']),
            '--expected-server-base', $NegativeClientServerBase,
            '--result-output', $resultPath
        )) {
            $arguments.Add([string]$argument)
        }
    } else {
        foreach ($argument in @(
            '--run-id', $RunId,
            '--expected-server-base', $ServerBase.TrimEnd('/'),
            '--result-output', $resultPath
        )) {
            $arguments.Add([string]$argument)
        }
    }
    $verification = Invoke-BoundedNativeTextOnce `
        -Command $script:ResolvedTools.Python `
        -Arguments $arguments.ToArray() `
        -TimeoutSeconds $ToolCommandTimeoutSeconds `
        -TimeoutReason "tool_timeout stage=$expectedVerifierName" `
        -LaunchReason "tool_launch_failed stage=$expectedVerifierName"
    $rc = [int]$verification.ExitCode
    $text = ([string]$verification.Text).Trim()
    Write-TextNoBom -Path $reportPath -Text ($text + "`n")
    if ($rc -ne 0) {
        throw "client_db_verifier_failed rc=$rc"
    }
    try {
        $report = $text | ConvertFrom-Json
    } catch {
        throw 'client_db_report_json_invalid'
    }
    $runUuidUnixMs = if ($RunId -match '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') {
        [Convert]::ToInt64(($RunId -replace '-', '').Substring(0, 12), 16)
    } else {
        throw 'client_db_run_id_not_uuidv7'
    }
    if ([string]$report.status -cne 'pass' -or
        [string]$report.reason_code -cne 'ok' -or
        [string]$report.run_id -cne $RunId -or
        [int64]$report.run_uuid_unix_ms -ne $runUuidUnixMs -or
        [int64]$report.run_start_delta_ms -lt 0 -or
        [int64]$report.run_start_delta_ms -gt 5000 -or
        [int64]$report.started_at_epoch_ms -ne $runUuidUnixMs + [int64]$report.run_start_delta_ms -or
        [int64]$report.ended_at_epoch_ms -lt [int64]$report.started_at_epoch_ms -or
        [int64]$report.serialized_at_epoch_ms -ne [int64]$report.ended_at_epoch_ms -or
        [int]$report.room_user_version -ne 19 -or
        $report.frozen_source_unchanged -ne $true -or
        $report.analysis_copy_used -ne $true -or
        [string]$report.strict_result_schema -cne 'pass') {
        throw 'client_db_report_not_pass'
    }
    if ($negative) {
        if ([string]$report.schema -cne 'aneb-token-quick-negative-client-db-report' -or
            [string]$report.schema_version -cne '1.0.0' -or
            [string]$report.negative_reason_code -cne 'receipt_missing' -or
            [string]$report.endpoint_server_base -cne $NegativeClientServerBase -or
            [string]$report.profile_sha256 -cne (
                'sha256:' + [string]$script:ResolvedTools.ProfileContractDefinitionSha256
            ) -or
            [int]$report.business_task_count -ne 0 -or
            [int]$report.business_kpi_observation_count -ne 0 -or
            [int]$report.business_artifact_count -ne 0 -or
            [int]$report.network_score_count -ne 0) {
            throw 'client_db_report_not_pass'
        }
    } elseif ([string]$report.schema -cne 'aneb-token-quick-client-db-report' -or
        [string]$report.schema_version -cne '1.2.0' -or
        [int]$report.typed_metrics_verified -ne 14 -or
        [int]$report.envelope_metrics_verified -ne 26 -or
        [int]$report.typed_conclusions_verified -le 0 -or
        [int]$report.successful_task_count -ne 3 -or
        [int64]$report.task_0006_response_artifact_bytes -ne 1048576) {
        throw 'client_db_report_not_pass'
    }
    Assert-NonEmptyFile -Path $resultPath -Label 'frozen client result JSON'
    return $report
}

function Enable-StayonForRun {
    if ($null -eq $script:OriginalStayon) {
        throw 'original_stayon_unknown'
    }
    if ([string]$script:OriginalStayon -ceq '7') {
        return
    }
    $script:StayonMutationAttempted = $true
    $null = Invoke-AdbTextOnce -Arguments @(
        'shell', 'settings put global stay_on_while_plugged_in 7'
    ) -Stage 'enable_stayon'
    $script:StayonChanged = $true
    $actual = Invoke-AdbTextOnce -Arguments @(
        'shell', 'settings get global stay_on_while_plugged_in'
    ) -Stage 'verify_enabled_stayon'
    if ($actual -cne '7') {
        throw "stayon_enable_verification_failed actual=$actual"
    }
}

function Stop-LogcatCaptureOnce {
    if ($script:LogcatStopSucceeded) {
        return
    }
    $script:LogcatStopAttempted = $true
    if ($null -eq $script:Logcat) {
        $script:LogcatStopSucceeded = $true
        return
    }
    $process = $script:Logcat.Process
    if (-not $process.HasExited) {
        $process.Kill()
        if (-not $process.WaitForExit(5000)) {
            throw 'logcat_stop_timeout'
        }
    }
    $script:LogcatStopSucceeded = $true
}

function Assert-TargetStopped {
    param([Parameter(Mandatory = $true)][string]$Stage)
    $pid = Invoke-AdbTextOnce -Arguments @(
        'shell', "pidof '$PackageName' 2>/dev/null || true"
    ) -Stage "target_pid_$Stage"
    $services = Invoke-AdbTextOnce -Arguments @(
        'shell', "dumpsys activity services '$PackageName'"
    ) -Stage "target_services_$Stage"
    if (-not [string]::IsNullOrWhiteSpace($pid) -or $services -match 'ServiceRecord\{') {
        throw "target_app_not_stopped stage=$Stage pid=$pid"
    }
}

function Stop-TargetAppOnce {
    if ($script:TargetStopSucceeded -or -not $script:AppLaunchAttempted) {
        return
    }
    $script:TargetStopAttempted = $true
    $null = Invoke-AdbTextOnce -Arguments @(
        'shell', "am force-stop '$PackageName'"
    ) -Stage 'force_stop_target_package'
    Assert-TargetStopped -Stage 'after_force_stop'
    $script:AppStarted = $false
    $script:TargetStopSucceeded = $true
}

function Add-BusySentinelObservation {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$Stage,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$WindowDump,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ActivityDump,
        [Parameter(Mandatory = $true)][string[]]$ObservedComponents,
        [Parameter(Mandatory = $true)][bool]$Matched
    )
    if ($Stage -notmatch '^[a-z0-9_]{1,64}$') {
        throw 'busy_sentinel_observation_stage_invalid'
    }
    $path = Join-Path $EvidenceDirectory 'busy-sentinel-observations.jsonl'
    $entry = [ordered]@{
        schema = 'aneb-d82-busy-sentinel-observation'
        schema_version = '1.0.0'
        captured_at_utc = [DateTime]::UtcNow.ToString(
            "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
            [Globalization.CultureInfo]::InvariantCulture
        )
        stage = $Stage
        expected_component = [string]$script:BusySentinelComponent
        observed_components = @($ObservedComponents)
        matched = $Matched
        window_dump = $WindowDump
        activity_dump = $ActivityDump
    }
    $line = $entry | ConvertTo-Json -Depth 6 -Compress
    [IO.File]::AppendAllText(
        $path,
        ($line + "`n"),
        (New-Object Text.UTF8Encoding($false))
    )
}

function Assert-BusySentinelFocused {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    if (-not $script:BusySentinelStarted -or
        [string]::IsNullOrWhiteSpace([string]$script:BusySentinelComponent) -or
        [string]$script:BusySentinelComponent -ceq $LauncherComponent) {
        throw "busy_sentinel_not_owned stage=$Stage"
    }
    $window = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys window'
    ) -Stage "busy_sentinel_window_$Stage"
    $activity = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys activity activities'
    ) -Stage "busy_sentinel_activity_$Stage"
    $componentPattern = '(?<component>[A-Za-z0-9._]+/[A-Za-z0-9._$]+)'
    $windowLines = @($window -split "`r?`n" | Where-Object {
        $_ -match '^\s*mCurrentFocus='
    })
    $focusedLines = @($activity -split "`r?`n" | Where-Object {
        $_ -match '^\s*mFocusedApp='
    })
    $resumedLines = @($activity -split "`r?`n" | Where-Object {
        $_ -match '^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]'
    })
    $observed = New-Object System.Collections.Generic.List[string]
    $focusShapeValid = $windowLines.Count -eq 1 -and
        $focusedLines.Count -eq 1 -and $resumedLines.Count -ge 1
    if ($focusShapeValid) {
        foreach ($line in @($windowLines[0], $focusedLines[0]) + $resumedLines) {
            $match = [regex]::Match([string]$line, $componentPattern)
            if (-not $match.Success) {
                $focusShapeValid = $false
                break
            }
            try {
                $observed.Add((ConvertTo-CanonicalAndroidComponent `
                    -Value ([string]$match.Groups['component'].Value) `
                    -Reason 'busy_sentinel_focus_component_invalid'))
            } catch {
                $focusShapeValid = $false
                break
            }
        }
    }
    $expectedSentinel = ConvertTo-CanonicalAndroidComponent `
        -Value ([string]$script:BusySentinelComponent) `
        -Reason 'busy_sentinel_component_invalid'
    $expectedLauncher = ConvertTo-CanonicalAndroidComponent `
        -Value $LauncherComponent `
        -Reason 'launcher_component_invalid'
    $matched = $focusShapeValid -and $observed.Count -ge 3
    if ($matched) {
        foreach ($component in $observed) {
            if ([string]$component -cne $expectedSentinel -or
                [string]$component -ceq $expectedLauncher) {
                $matched = $false
            }
        }
    }
    Add-BusySentinelObservation `
        -EvidenceDirectory $EvidenceDirectory `
        -Stage $Stage `
        -WindowDump $window `
        -ActivityDump $activity `
        -ObservedComponents @($observed) `
        -Matched $matched
    if (-not $matched) {
        $script:BusySentinelLost = $true
        throw (
            'busy_sentinel_focus_changed stage={0} expected={1} observed={2}' -f
            $Stage,
            [string]$script:BusySentinelComponent,
            (@($observed) -join ',')
        )
    }
    return [pscustomobject]@{
        Component = [string]$script:BusySentinelComponent
        Stage = $Stage
    }
}

function Start-BusySentinel {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    if ($script:BusySentinelStarted) {
        throw 'busy_sentinel_duplicate_start'
    }
    if ($script:AppLaunchAttempted) {
        throw 'busy_sentinel_late_start_forbidden'
    }
    $script:BusySentinelStartAttempted = $true
    $output = Invoke-AdbTextOnce -Arguments @(
        'shell', 'am start -W -a android.settings.SETTINGS'
    ) -Stage 'start_busy_sentinel_settings'
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory 'device-busy-sentinel-launch.txt') `
        -Text ($output + "`n")
    $activities = @([regex]::Matches(
        $output,
        '(?m)^Activity:\s*(?<component>[A-Za-z0-9._]+/[A-Za-z0-9._$]+)\s*$'
    ))
    if ($output -notmatch '(?m)^Status:\s*ok\s*$' -or $activities.Count -ne 1) {
        throw 'busy_sentinel_launch_receipt_invalid'
    }
    $component = [string]$activities[0].Groups['component'].Value
    if ($component -notmatch '^com\.android\.settings/' -or
        $component -ceq $LauncherComponent) {
        throw "busy_sentinel_component_invalid component=$component"
    }
    $script:BusySentinelComponent = $component
    $script:BusySentinelEvidenceDirectory = $EvidenceDirectory
    $script:BusySentinelStarted = $true
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'device-busy-sentinel.json') -Value ([ordered]@{
        schema = 'aneb-d82-busy-sentinel'
        schema_version = '1.0.0'
        started_at_utc = [DateTime]::UtcNow.ToString(
            "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
            [Globalization.CultureInfo]::InvariantCulture
        )
        intent_action = 'android.settings.SETTINGS'
        component = $component
        launcher_component = $LauncherComponent
    })
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $EvidenceDirectory `
        -Stage 'sentinel_started'
    $script:BusySentinelVerified = $true
}

function Restore-BusySentinelAfterTarget {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    if ($script:BusySentinelRestoredAfterTarget) {
        return
    }
    if (-not $script:BusySentinelStarted) {
        throw 'busy_sentinel_restore_without_initial_start'
    }
    if (-not $script:AppLaunchAttempted -or -not $script:TargetStopSucceeded) {
        throw 'busy_sentinel_restore_target_not_stopped'
    }
    $output = Invoke-AdbTextOnce -Arguments @(
        'shell', 'am start -W -a android.settings.SETTINGS'
    ) -Stage 'restore_busy_sentinel_settings'
    Write-TextNoBom `
        -Path (Join-Path $EvidenceDirectory 'device-busy-sentinel-restore.txt') `
        -Text ($output + "`n")
    $activities = @([regex]::Matches(
        $output,
        '(?m)^Activity:\s*(?<component>[A-Za-z0-9._]+/[A-Za-z0-9._$]+)\s*$'
    ))
    if ($output -notmatch '(?m)^Status:\s*ok\s*$' -or
        $activities.Count -ne 1 -or
        [string]$activities[0].Groups['component'].Value -cne
            [string]$script:BusySentinelComponent) {
        throw 'busy_sentinel_restore_receipt_invalid'
    }
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $EvidenceDirectory `
        -Stage 'sentinel_restored_after_target'
    $script:BusySentinelRestoredAfterTarget = $true
}

function Ensure-BusySentinelForCleanup {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    if (-not $script:BusySentinelStarted) {
        if ($script:AppLaunchAttempted) {
            throw 'busy_sentinel_missing_before_target'
        }
        Start-BusySentinel -EvidenceDirectory $EvidenceDirectory
        return
    }
    if (-not $script:AppLaunchAttempted) {
        return
    }
    if (-not $script:TargetStopSucceeded) {
        throw 'busy_sentinel_cleanup_target_not_stopped'
    }
    Restore-BusySentinelAfterTarget -EvidenceDirectory $EvidenceDirectory
}

function Assert-NoUnattributedSessionBeforeHome {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $processes = [ordered]@{}
    $servicesByPackage = [ordered]@{}
    $conflict = $false
    foreach ($package in $ConflictPackages) {
        $pid = Invoke-AdbTextOnce -Arguments @(
            'shell', "pidof '$package' 2>/dev/null || true"
        ) -Stage "busy_release_pidof_$package"
        $services = Invoke-AdbTextOnce -Arguments @(
            'shell', "dumpsys activity services '$package'"
        ) -Stage "busy_release_services_$package"
        $processes[$package] = $pid
        $servicesByPackage[$package] = $services
        if (-not [string]::IsNullOrWhiteSpace($pid) -or $services -match 'ServiceRecord\{') {
            $conflict = $true
        }
    }
    $tun = Invoke-AdbTextOnce -Arguments @(
        'shell', 'found=0; for path in /sys/class/net/tun*; do if [ -e "$path" ]; then basename "$path"; found=1; fi; done; [ "$found" -eq 0 ] && echo absent'
    ) -Stage 'busy_release_tun_state'
    $connectivity = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys connectivity'
    ) -Stage 'busy_release_connectivity'
    $vpn = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys vpn 2>&1 || true'
    ) -Stage 'busy_release_vpn'
    $vpnClean = $true
    try {
        Assert-NoActiveVpn -ConnectivityDump $connectivity -VpnDump $vpn
    } catch {
        $vpnClean = $false
    }
    Write-JsonNoBom `
        -Path (Join-Path $EvidenceDirectory 'device-busy-sentinel-release-guard.json') `
        -Value ([ordered]@{
            schema = 'aneb-d82-busy-sentinel-release-guard'
            schema_version = '1.0.0'
            captured_at_utc = [DateTime]::UtcNow.ToString('o')
            sentinel_component = [string]$script:BusySentinelComponent
            processes = $processes
            services = $servicesByPackage
            tun = $tun
            connectivity_dump = $connectivity
            vpn_dump = $vpn
            no_conflicting_session = -not $conflict -and $tun -ceq 'absent' -and $vpnClean
        })
    if ($conflict -or $tun -cne 'absent' -or -not $vpnClean) {
        throw 'busy_sentinel_unattributed_session_detected'
    }
}

function Release-BusySentinelToLauncherOnce {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    if (-not $script:BusySentinelStarted -or $script:BusySentinelHomeSucceeded) {
        return
    }
    if ($script:BusySentinelReleaseAttempted) {
        throw 'busy_sentinel_release_duplicate'
    }
    $script:BusySentinelReleaseAttempted = $true
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $EvidenceDirectory `
        -Stage 'before_release_home'
    Assert-NoUnattributedSessionBeforeHome -EvidenceDirectory $EvidenceDirectory
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $EvidenceDirectory `
        -Stage 'after_release_guard'
    $null = Invoke-AdbTextOnce -Arguments @(
        'shell', 'input keyevent HOME'
    ) -Stage 'release_busy_sentinel_to_home'
    $window = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys window'
    ) -Stage 'verify_launcher_after_busy_sentinel'
    $activity = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys activity activities'
    ) -Stage 'verify_launcher_activity_after_busy_sentinel'
    try {
        $null = Assert-HuaweiLauncherFocused -WindowDump $window -ActivityDump $activity
    } catch {
        throw "busy_sentinel_home_verification_failed detail=$($_.Exception.Message)"
    }
    $script:BusySentinelHomeSucceeded = $true
}

function Assert-BusySentinelEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$ExpectedComponent
    )
    $path = Join-Path $EvidenceDirectory 'busy-sentinel-observations.jsonl'
    Assert-NonEmptyFile -Path $path -Label 'busy sentinel observations'
    $requiredStages = @(
        'sentinel_started', 'before_target_handoff',
        'sentinel_restored_after_target', 'before_end_barrier', 'after_remote_snapshot',
        'before_room_freeze', 'after_room_freeze', 'after_client_verifier',
        'workflow_complete', 'cleanup_before_end_barrier',
        'cleanup_after_end_barrier', 'before_release_home',
        'after_release_guard'
    )
    $seen = @{}
    foreach ($line in @(Get-Content -LiteralPath $path -Encoding UTF8)) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) {
            throw 'busy_sentinel_evidence_blank_line'
        }
        try {
            $entry = [string]$line | ConvertFrom-Json
        } catch {
            throw 'busy_sentinel_evidence_json_invalid'
        }
        $stage = [string]$entry.stage
        if ([string]$entry.schema -cne 'aneb-d82-busy-sentinel-observation' -or
            [string]$entry.schema_version -cne '1.0.0' -or
            $requiredStages -cnotcontains $stage -or $seen.ContainsKey($stage) -or
            [string]$entry.expected_component -cne $ExpectedComponent -or
            $entry.matched -isnot [bool] -or -not [bool]$entry.matched -or
            [string]::IsNullOrWhiteSpace([string]$entry.window_dump) -or
            [string]::IsNullOrWhiteSpace([string]$entry.activity_dump)) {
            throw "busy_sentinel_evidence_entry_invalid stage=$stage"
        }
        $observed = @($entry.observed_components)
        if ($observed.Count -lt 3 -or @($observed | Where-Object {
            [string]$_ -cne $ExpectedComponent
        }).Count -gt 0) {
            throw "busy_sentinel_evidence_focus_invalid stage=$stage"
        }
        $seen[$stage] = $true
    }
    if ($seen.Count -ne $requiredStages.Count) {
        throw "busy_sentinel_evidence_stage_count_invalid count=$($seen.Count)"
    }
    foreach ($stage in $requiredStages) {
        if (-not $seen.ContainsKey($stage)) {
            throw "busy_sentinel_evidence_stage_missing stage=$stage"
        }
    }
}

function Restore-StayonOnce {
    if ($script:StayonRestoreSucceeded -or -not $script:StayonMutationAttempted) {
        return
    }
    $script:StayonRestoreAttempted = $true
    if ([string]$script:OriginalStayon -ceq 'null') {
        $null = Invoke-AdbTextOnce -Arguments @(
            'shell', 'settings delete global stay_on_while_plugged_in'
        ) -Stage 'restore_stayon_delete'
    } else {
        $null = Invoke-AdbTextOnce -Arguments @(
            'shell', "settings put global stay_on_while_plugged_in '$script:OriginalStayon'"
        ) -Stage 'restore_stayon_value'
    }
    $actual = Invoke-AdbTextOnce -Arguments @(
        'shell', 'settings get global stay_on_while_plugged_in'
    ) -Stage 'verify_restored_stayon'
    if ([string]$actual -cne [string]$script:OriginalStayon) {
        throw "stayon_restore_verification_failed expected=$script:OriginalStayon actual=$actual"
    }
    $script:StayonChanged = $false
    $script:StayonRestoreSucceeded = $true
}

function Assert-LiveDeviceCleanAfter {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $state = Invoke-AdbTextOnce -Arguments @('get-state') -Stage 'final_device_state'
    if ($state -cne 'device') {
        throw "final_device_offline state=$state"
    }
    $null = Write-DeviceIdentitySnapshot `
        -EvidenceDirectory $EvidenceDirectory `
        -Stage 'final'
    $window = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys window'
    ) -Stage 'final_focused_window'
    $activity = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys activity activities'
    ) -Stage 'final_focused_activity'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-window-final.txt') -Text ($window + "`n")
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-activity-final.txt') -Text ($activity + "`n")
    try {
        $null = Assert-HuaweiLauncherFocused -WindowDump $window -ActivityDump $activity
    } catch {
        throw "final_state_not_launcher detail=$($_.Exception.Message)"
    }
    $processes = [ordered]@{}
    $servicesByPackage = [ordered]@{}
    foreach ($package in $ConflictPackages) {
        $pid = Invoke-AdbTextOnce -Arguments @(
            'shell', "pidof '$package' 2>/dev/null || true"
        ) -Stage "final_pidof_$package"
        $services = Invoke-AdbTextOnce -Arguments @(
            'shell', "dumpsys activity services '$package'"
        ) -Stage "final_services_$package"
        $processes[$package] = $pid
        $servicesByPackage[$package] = $services
        if (-not [string]::IsNullOrWhiteSpace($pid) -or $services -match 'ServiceRecord\{') {
            throw "final_state_conflict package=$package pid=$pid"
        }
    }
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'device-processes-final.json') -Value $processes
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'device-services-final.json') -Value $servicesByPackage
    $tun = Invoke-AdbTextOnce -Arguments @(
        'shell', 'found=0; for path in /sys/class/net/tun*; do if [ -e "$path" ]; then basename "$path"; found=1; fi; done; [ "$found" -eq 0 ] && echo absent'
    ) -Stage 'final_tun_state'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-tun-final.txt') -Text ($tun + "`n")
    if ($tun -cne 'absent') {
        throw "final_tunnel_present state=$tun"
    }
    $connectivity = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys connectivity'
    ) -Stage 'final_connectivity'
    $vpn = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys vpn 2>&1 || true'
    ) -Stage 'final_vpn'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-connectivity-final.txt') -Text ($connectivity + "`n")
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-vpn-final.txt') -Text ($vpn + "`n")
    try {
        Assert-NoActiveVpn -ConnectivityDump $connectivity -VpnDump $vpn
    } catch {
        throw "final_active_vpn detail=$($_.Exception.Message)"
    }
    $enabledAccessibility = Invoke-AdbTextOnce -Arguments @(
        'shell', 'settings get secure enabled_accessibility_services'
    ) -Stage 'final_accessibility'
    $accessibilityDump = Invoke-AdbTextOnce -Arguments @(
        'shell', 'dumpsys accessibility'
    ) -Stage 'final_accessibility_bound_state'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-accessibility-final.txt') -Text (
        "ANEB_D82_DEVICE_ACCESSIBILITY_FINAL_V1`n" +
        "enabled_accessibility_services_command=settings get secure enabled_accessibility_services`n" +
        "enabled_accessibility_services_output_begin`n" +
        "$enabledAccessibility`n" +
        "enabled_accessibility_services_output_end`n" +
        "dumpsys_accessibility_command=dumpsys accessibility`n" +
        "dumpsys_accessibility_output_begin`n" +
        "$accessibilityDump`n" +
        "dumpsys_accessibility_output_end`n"
    )
    foreach ($package in @($ClaudePackageName, $PackageName)) {
        if ($enabledAccessibility -match [regex]::Escape($package)) {
            throw "final_accessibility_enabled package=$package"
        }
        $boundLine = @($accessibilityDump -split "`r?`n" | Where-Object {
            $_ -match '(?i)bound services?' -and $_ -match [regex]::Escape($package)
        })
        if ($boundLine.Count -gt 0) {
            throw "final_accessibility_bound package=$package"
        }
    }
    $stayon = Invoke-AdbTextOnce -Arguments @(
        'shell', 'settings get global stay_on_while_plugged_in'
    ) -Stage 'final_stayon'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'device-stayon-final.txt') -Text ($stayon + "`n")
    if ([string]$stayon -cne [string]$script:OriginalStayon) {
        throw "final_stayon_mismatch expected=$script:OriginalStayon actual=$stayon"
    }
    $deviceIdentity = Invoke-DeviceIdentityVerification -EvidenceDirectory $EvidenceDirectory
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'device-final-clean.json') -Value ([ordered]@{
        schema = 'aneb-p40-live-clean-after'
        schema_version = '1.0.0'
        captured_at_utc = [DateTime]::UtcNow.ToString('o')
        launcher = $LauncherComponent
        processes = $processes
        services = $servicesByPackage
        tun0 = 'absent'
        active_vpn = $false
        stay_on_while_plugged_in = $stayon
    })
}

function Release-PersistentAuditLockOnce {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )
    if ($script:LockReleaseAttempted) {
        throw 'audit_lock_release_duplicate'
    }
    $script:LockReleaseAttempted = $true
    if ($Lock.Process.HasExited) {
        throw "audit_lock_process_lost_before_release rc=$($Lock.Process.ExitCode)"
    }
    Assert-PersistentAuditLock -Lock $Lock -Stage 'before_release'
    $Lock.Process.StandardInput.WriteLine("RELEASE $($Lock.Nonce)")
    $Lock.Process.StandardInput.Flush()
    $releaseTask = $Lock.Process.StandardOutput.ReadLineAsync()
    if (-not $releaseTask.Wait(15000)) {
        throw 'audit_lock_release_receipt_timeout'
    }
    $release = [string]$releaseTask.Result
    $expected = "LOCK_RELEASED nonce=$($Lock.Nonce)"
    if ($release -cne $expected) {
        throw "audit_lock_release_receipt_invalid output=$release"
    }
    if (-not $Lock.Process.WaitForExit(15000) -or $Lock.Process.ExitCode -ne 0) {
        throw 'audit_lock_holder_exit_invalid'
    }
    $stderr = if ($Lock.StderrTask.Wait(5000)) { [string]$Lock.StderrTask.Result } else { '' }
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        throw 'audit_lock_holder_stderr_nonempty'
    }
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'lock-released.txt') -Text (
        "$release`nprocess_exit=0`nstderr=`n"
    )
}

function Assert-AuditLockReleased {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )
    $command = @(
        'set -Eeuo pipefail',
        "test ! -e '$($Lock.Marker)'",
        "flock -n '$RemoteLockPath' -c true",
        "printf 'LOCK_RELEASE_VERIFIED nonce=%s\\n' '$($Lock.Nonce)'"
    ) -join '; '
    $output = Invoke-SshTextOnce -RemoteCommand $command -Stage 'lock_release_verification'
    if ($output.Trim() -cne "LOCK_RELEASE_VERIFIED nonce=$($Lock.Nonce)") {
        throw "lock_release_verification_invalid output=$output"
    }
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'lock-release-verified.txt') -Text ($output.Trim() + "`n")
}

function Assert-RemoteSnapshotStable {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)]$Snapshot,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )
    Assert-PersistentAuditLock -Lock $Lock -Stage 'remote_end_snapshot'
    $command = @'
set -Eeuo pipefail
unit='aneb-server.service'
printf 'boot_id=%s\n' "$(tr -d '-' < /proc/sys/kernel/random/boot_id)"
printf 'systemd_invocation_id=%s\n' "$(systemctl show "$unit" --property=InvocationID --value)"
printf 'main_pid=%s\n' "$(systemctl show "$unit" --property=MainPID --value)"
printf 'server_binary_sha256=%s\n' "$(sha256sum /opt/aneb/bin/aneb-server | awk '{print $1}')"
'@
    $raw = Invoke-SshTextOnce -RemoteCommand $command -Stage 'remote_end_snapshot'
    Write-TextNoBom -Path (Join-Path $EvidenceDirectory 'remote-end.txt') -Text ($raw.TrimEnd() + "`n")
    $expected = @{
        boot_id = [string]$Snapshot.BootId
        systemd_invocation_id = [string]$Snapshot.InvocationId
        main_pid = [string]$Snapshot.MainPid
        server_binary_sha256 = [string]$Snapshot.BinarySha256
    }
    $actual = @{}
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -notmatch '^([a-z0-9_]+)=(.+)$' -or $actual.ContainsKey($Matches[1])) {
            throw 'remote_end_snapshot_invalid'
        }
        $actual[$Matches[1]] = $Matches[2]
    }
    foreach ($key in $expected.Keys) {
        if (-not $actual.ContainsKey($key) -or [string]$actual[$key] -cne [string]$expected[$key]) {
            throw "remote_identity_changed key=$key"
        }
    }
}

function Wait-ForEndBarrierAudit {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [Parameter(Mandatory = $true)][string]$JournalCursor,
        [Parameter(Mandatory = $true)][string]$EndBarrierId
    )
    if ($JournalCursor -notmatch '^[A-Za-z0-9;:_.=-]{10,1024}$') {
        throw 'journal_cursor_unsafe'
    }
    Assert-PersistentAuditLock -Lock $Lock -Stage 'wait_end_barrier_audit'
    $pattern = "role=window_end scope=token_run run_id=$EndBarrierId"
    $command = @(
        'set -Eeuo pipefail',
        "cursor='$JournalCursor'",
        "pattern='$pattern'",
        'deadline=$((SECONDS + 15))',
        'while (( SECONDS < deadline )); do',
        '  if journalctl --unit aneb-server.service --after-cursor "$cursor" --output=cat --no-pager | grep -F -m 1 -- "$pattern" >/dev/null; then',
        "    printf 'END_BARRIER_AUDIT_VISIBLE\\n'",
        '    exit 0',
        '  fi',
        '  sleep 0.2',
        'done',
        'exit 78'
    ) -join "`n"
    $output = Invoke-SshTextOnce -RemoteCommand $command -Stage 'wait_end_barrier_audit'
    if ($output.Trim() -cne 'END_BARRIER_AUDIT_VISIBLE') {
        throw 'end_barrier_audit_not_visible'
    }
}

function Complete-CollectorCleanup {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $errors = New-Object System.Collections.Generic.List[string]
    foreach ($cleanupStep in @(
        @{ Name = 'target_app'; Attempts = 3; Action = { Stop-TargetAppOnce } },
        @{ Name = 'negative_reverse'; Attempts = 3; Action = {
            Remove-NegativeAdbReverseOnce -EvidenceDirectory $EvidenceDirectory
        } },
        @{ Name = 'negative_proxy'; Attempts = 3; Action = { Stop-NegativeProxyOnce } },
        @{ Name = 'busy_sentinel_acquire'; Attempts = 1; Action = {
            Ensure-BusySentinelForCleanup -EvidenceDirectory $EvidenceDirectory
        } },
        @{ Name = 'end_barrier'; Action = {
            if ($script:BusySentinelStarted) {
                $null = Assert-BusySentinelFocused `
                    -EvidenceDirectory $EvidenceDirectory `
                    -Stage 'cleanup_before_end_barrier'
            }
            if ($script:StartBarrierAttempted -and -not $script:EndBarrierAttempted -and $null -ne $script:AuditLock) {
                Assert-PersistentAuditLock -Lock $script:AuditLock -Stage 'cleanup_end_barrier'
                $null = Invoke-BarrierOnce `
                    -Attempted ([ref]$script:EndBarrierAttempted) `
                    -BarrierId $script:EndBarrierId `
                    -Role 'window_end' `
                    -HeadersPath (Join-Path $EvidenceDirectory 'end-barrier.headers') `
                    -BodyPath (Join-Path $EvidenceDirectory 'end-barrier.json')
            }
            if ($script:BusySentinelStarted) {
                $null = Assert-BusySentinelFocused `
                    -EvidenceDirectory $EvidenceDirectory `
                    -Stage 'cleanup_after_end_barrier'
            }
        }; Attempts = 1 },
        @{ Name = 'logcat'; Attempts = 3; Action = { Stop-LogcatCaptureOnce } },
        @{ Name = 'stayon'; Attempts = 3; Action = { Restore-StayonOnce } },
        @{ Name = 'busy_sentinel_release'; Attempts = 1; Action = {
            Release-BusySentinelToLauncherOnce -EvidenceDirectory $EvidenceDirectory
        } },
        @{ Name = 'phone_final'; Attempts = 2; Action = {
            if ($null -ne $script:OriginalStayon) {
                Assert-LiveDeviceCleanAfter -EvidenceDirectory $EvidenceDirectory
            }
        } },
        @{ Name = 'lock_release'; Attempts = 1; Action = {
            if ($null -ne $script:AuditLock) {
                Release-PersistentAuditLockOnce -Lock $script:AuditLock -EvidenceDirectory $EvidenceDirectory
            }
        } },
        @{ Name = 'lock_postcheck'; Attempts = 2; Action = {
            if ($null -ne $script:AuditLock -and $script:LockReleaseAttempted) {
                Assert-AuditLockReleased -Lock $script:AuditLock -EvidenceDirectory $EvidenceDirectory
            }
        } }
    )) {
        $lastError = $null
        $completed = $false
        for ($attempt = 1; $attempt -le [int]$cleanupStep.Attempts; $attempt++) {
            try {
                & $cleanupStep.Action
                $completed = $true
                break
            } catch {
                $lastError = $_.Exception.Message
                if ($attempt -lt [int]$cleanupStep.Attempts) {
                    Start-Sleep -Milliseconds 250
                }
            }
        }
        if (-not $completed) {
            $errors.Add((
                "{0}:attempts={1}:{2}" -f
                $cleanupStep.Name,
                [int]$cleanupStep.Attempts,
                [string]$lastError
            ))
        }
    }
    $script:CleanupSucceeded = $errors.Count -eq 0
    Write-JsonNoBom -Path (Join-Path $EvidenceDirectory 'cleanup-report.json') -Value ([ordered]@{
        schema = 'aneb-d82-collector-cleanup'
        schema_version = '1.0.0'
        captured_at_utc = [DateTime]::UtcNow.ToString('o')
        status = if ($script:CleanupSucceeded) { 'pass' } else { 'fail' }
        errors = @($errors)
        target_stop_attempted = $script:TargetStopAttempted
        negative_reverse_preflight_captured = $script:NegativeReversePreflightCaptured
        negative_reverse_mutation_attempted = $script:NegativeReverseMutationAttempted
        negative_reverse_remove_attempted = $script:NegativeReverseRemoveAttempted
        negative_reverse_final_captured = $script:NegativeReverseFinalCaptured
        negative_proxy_completed = $script:NegativeProxyCompleted
        negative_proxy_stop_attempted = $script:NegativeProxyStopAttempted
        negative_proxy_stop_succeeded = $script:NegativeProxyStopSucceeded
        busy_sentinel_start_attempted = $script:BusySentinelStartAttempted
        busy_sentinel_started = $script:BusySentinelStarted
        busy_sentinel_verified = $script:BusySentinelVerified
        busy_sentinel_restored_after_target = $script:BusySentinelRestoredAfterTarget
        busy_sentinel_lost = $script:BusySentinelLost
        busy_sentinel_component = $script:BusySentinelComponent
        busy_sentinel_release_attempted = $script:BusySentinelReleaseAttempted
        busy_sentinel_home_succeeded = $script:BusySentinelHomeSucceeded
        stayon_restored = -not $script:StayonChanged
        lock_release_attempted = $script:LockReleaseAttempted
    })
}

function Get-RelativeEvidencePath {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $pathFull = [IO.Path]::GetFullPath($Path)
    if (-not $pathFull.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'evidence_path_outside_root'
    }
    return $pathFull.Substring($rootFull.Length).Replace('\', '/')
}

function Assert-EvidenceManifestDraft {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$DraftPath,
        [Parameter(Mandatory = $true)][string[]]$RequiredPaths
    )
    if ([IO.Path]::GetFileName($DraftPath) -cne 'evidence-inventory.draft.json' -or
        -not (Test-Path -LiteralPath $DraftPath -PathType Leaf)) {
        throw 'evidence_draft_missing_or_misnamed'
    }
    $draftItem = Get-Item -LiteralPath $DraftPath -Force
    if (($draftItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'evidence_draft_reparse_point_forbidden'
    }
    try {
        $draft = Get-Content -LiteralPath $DraftPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw 'evidence_draft_json_invalid'
    }
    $expectedDraftKeys = @(
        'acceptance_eligible', 'evidence_scope', 'file_count', 'files',
        'schema', 'schema_version', 'status', 'total_bytes'
    ) | Sort-Object
    $actualDraftKeys = @($draft.PSObject.Properties.Name | Sort-Object)
    if (($expectedDraftKeys -join "`0") -cne ($actualDraftKeys -join "`0") -or
        [string]$draft.schema -cne 'aneb-evidence-manifest-draft' -or
        [string]$draft.schema_version -cne '1.0.0' -or
        [string]$draft.status -cne 'draft' -or
        $draft.acceptance_eligible -isnot [bool] -or
        [bool]$draft.acceptance_eligible -or
        [string]$draft.evidence_scope -cne 'inventory_only_not_d82_acceptance') {
        throw 'evidence_draft_contract_invalid'
    }
    $rootItem = Get-Item -LiteralPath $EvidenceDirectory -Force
    if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'evidence_root_reparse_point_forbidden'
    }
    $reparseItems = @(Get-ChildItem -LiteralPath $EvidenceDirectory -Force -Recurse | Where-Object {
        ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
    })
    if ($reparseItems.Count -gt 0) {
        throw 'evidence_tree_reparse_point_forbidden'
    }
    $entries = @($draft.files)
    if ($draft.file_count -isnot [int] -and $draft.file_count -isnot [long]) {
        throw 'evidence_draft_file_count_type_invalid'
    }
    if ([int64]$draft.file_count -ne $entries.Count) {
        throw 'evidence_draft_file_count_mismatch'
    }
    $seen = @{}
    $computedTotal = [int64]0
    $previousPath = $null
    foreach ($entry in $entries) {
        $expectedEntryKeys = @('bytes', 'path', 'sha256')
        $actualEntryKeys = @($entry.PSObject.Properties.Name | Sort-Object)
        if (($expectedEntryKeys -join "`0") -cne ($actualEntryKeys -join "`0")) {
            throw 'evidence_draft_entry_contract_invalid'
        }
        $relative = [string]$entry.path
        if ($relative -notmatch '^[A-Za-z0-9._/-]+$' -or
            $relative.StartsWith('/') -or $relative.Contains('\') -or
            @($relative -split '/' | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
            throw 'evidence_draft_path_invalid'
        }
        if ($null -ne $previousPath -and
            [StringComparer]::Ordinal.Compare([string]$previousPath, $relative) -ge 0) {
            throw 'evidence_draft_paths_not_strictly_sorted'
        }
        $previousPath = $relative
        $key = $relative.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            throw 'evidence_draft_duplicate_path'
        }
        $seen[$key] = $true
        if ($entry.bytes -isnot [int] -and $entry.bytes -isnot [long]) {
            throw 'evidence_draft_bytes_type_invalid'
        }
        $expectedBytes = [int64]$entry.bytes
        if ($expectedBytes -lt 0 -or [string]$entry.sha256 -notmatch '^[0-9a-f]{64}$') {
            throw 'evidence_draft_entry_value_invalid'
        }
        $filePath = Join-Path $EvidenceDirectory ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            throw "evidence_draft_file_missing path=$relative"
        }
        $fileItem = Get-Item -LiteralPath $filePath -Force
        if (($fileItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            [int64]$fileItem.Length -ne $expectedBytes) {
            throw "evidence_draft_file_identity_mismatch path=$relative"
        }
        $actualDigest = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualDigest -cne [string]$entry.sha256) {
            throw "evidence_draft_digest_mismatch path=$relative"
        }
        $computedTotal += $expectedBytes
    }
    if ($draft.total_bytes -isnot [int] -and $draft.total_bytes -isnot [long]) {
        throw 'evidence_draft_total_bytes_type_invalid'
    }
    if ([int64]$draft.total_bytes -ne $computedTotal) {
        throw 'evidence_draft_total_bytes_mismatch'
    }
    $actualFiles = @(Get-ChildItem -LiteralPath $EvidenceDirectory -File -Force -Recurse | Where-Object {
        $_.FullName -cne $draftItem.FullName
    })
    if ($actualFiles.Count -ne $entries.Count) {
        throw 'evidence_draft_inventory_count_mismatch'
    }
    foreach ($file in $actualFiles) {
        $relative = Get-RelativeEvidencePath -Root $EvidenceDirectory -Path $file.FullName
        if (-not $seen.ContainsKey($relative.ToLowerInvariant())) {
            throw "evidence_draft_unlisted_file path=$relative"
        }
    }
    foreach ($required in $RequiredPaths) {
        if (-not $seen.ContainsKey($required.ToLowerInvariant())) {
            throw "evidence_draft_required_file_missing path=$required"
        }
    }
    return $draft
}

function Write-NewTextNoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text
    )
    $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($Text)
    $stream = New-Object IO.FileStream(
        $Path,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
}

function Assert-BundleVerificationReport {
    param(
        [Parameter(Mandatory = $true)][object[]]$OutputLines,
        [Parameter(Mandatory = $true)][int]$ExitCode,
        [Parameter(Mandatory = $true)][string]$CollectionId,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$FinalManifestSha256,
        [Parameter(Mandatory = $true)][string]$SourceCommit,
        [Parameter(Mandatory = $true)][string]$ServerVersion,
        [Parameter(Mandatory = $true)][string]$ServerBinarySha256,
        [Parameter(Mandatory = $true)][string]$ApkSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedRemoteHost,
        [Parameter(Mandatory = $true)][string]$ExpectedSshKnownHostsSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedPackageName,
        [Parameter(Mandatory = $true)][string]$ExpectedVersionName,
        [Parameter(Mandatory = $true)][int]$ExpectedVersionCode,
        [Parameter(Mandatory = $true)][string]$ExpectedSignerSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedDevicePolicySha256,
        [Parameter(Mandatory = $true)][string]$ExpectedAdbSerialSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedAttestationBundleSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedGhSha256,
        [Parameter(Mandatory = $true)][ValidateSet('positive', 'negative_receipt_missing')][string]$ExpectedExecutionMode,
        [Parameter(Mandatory = $true)][int]$ExpectedRunTimeoutSeconds,
        [Parameter(Mandatory = $true)][int]$ExpectedLockTtlSeconds
    )
    function Test-ExactJsonInteger {
        param($Value, [Parameter(Mandatory = $true)][int64]$Expected)
        if ($null -eq $Value) {
            return $false
        }
        $typeCode = [Type]::GetTypeCode($Value.GetType())
        if ($typeCode -notin @(
            [TypeCode]::SByte, [TypeCode]::Byte,
            [TypeCode]::Int16, [TypeCode]::UInt16,
            [TypeCode]::Int32, [TypeCode]::UInt32,
            [TypeCode]::Int64, [TypeCode]::UInt64
        )) {
            return $false
        }
        return [int64]$Value -eq $Expected
    }
    if ($OutputLines.Count -ne 1) {
        throw 'bundle_verifier_output_line_count_invalid'
    }
    $line = [string]$OutputLines[0]
    if ([string]::IsNullOrWhiteSpace($line) -or
        $line -match "[`r`n]" -or
        $line -cne $line.Trim()) {
        throw 'bundle_verifier_output_line_invalid'
    }
    try {
        $report = $line | ConvertFrom-Json
    } catch {
        throw 'bundle_verifier_report_json_invalid'
    }
    if ($ExitCode -ne 0 -or
        [string]$report.schema -cne 'aneb-d82-bundle-verification-report' -or
        [string]$report.schema_version -cne '1.1.0' -or
        [string]$report.status -cne 'pass' -or
        [string]$report.reason_code -cne 'ok') {
        throw (
            'bundle_verifier_rejected rc={0} schema={1} status={2} reason={3}' -f
            $ExitCode,
            [string]$report.schema,
            [string]$report.status,
            [string]$report.reason_code
        )
    }
    $requiredProperties = @(
        'collection_id', 'run_id', 'manifest_sha256', 'source_commit',
        'execution_mode',
        'server_version', 'server_binary_sha256', 'apk_sha256',
        'remote_host', 'ssh_known_hosts_sha256',
        'publication',
        'apk_identity_reverified', 'verified_apk_identity',
        'android_build_tools_version', 'accessibility_raw_reverified',
        'raw_state_reverified', 'raw_files_verified',
        'raw_state_files_verified', 'device_identity_raw_files_verified',
        'raw_files_verified_total', 'device_identity',
        'candidate_provenance_reverified', 'attestation_bundle_sha256',
        'gh_version', 'gh_executable_sha256',
        'evidence_time_chain_reverified', 'run_duration_ms',
        'run_start_delta_ms', 'remote_receipt_clock_delta_ms',
        'run_timeout_seconds', 'lock_ttl_seconds',
        'journal_derivation_recomputed', 'request_entry_audit_recomputed',
        'client_room_result_recomputed',
        'negative_proxy_evidence_recomputed', 'negative_reason_code',
        'client_delivery_proven', 'negative_proxy_raw_files_verified',
        'business_counts',
        'typed_metrics_verified', 'envelope_metrics_verified',
        'successful_task_count'
    )
    $missingProperties = @($requiredProperties | Where-Object {
        $report.PSObject.Properties.Name -cnotcontains $_
    })
    if ($missingProperties.Count -gt 0) {
        throw (
            'bundle_verifier_report_binding_mismatch fields={0}' -f
            ($missingProperties -join ',')
        )
    }
    $businessProperties = @()
    if ($null -ne $report.business_counts) {
        $businessProperties = @($report.business_counts.PSObject.Properties.Name)
    }
    $apkIdentityProperties = @()
    if ($null -ne $report.verified_apk_identity) {
        $apkIdentityProperties = @($report.verified_apk_identity.PSObject.Properties.Name)
    }
    $deviceIdentityProperties = @()
    if ($null -ne $report.device_identity) {
        $deviceIdentityProperties = @($report.device_identity.PSObject.Properties.Name)
    }
    $negativeMode = $ExpectedExecutionMode -ceq 'negative_receipt_missing'
    $expectedEchoCount = if ($negativeMode) { 0 } else { 20 }
    $expectedTokenSimCount = if ($negativeMode) { 0 } else { 3 }
    $expectedDownloadCount = if ($negativeMode) { 0 } else { 1 }
    $expectedTypedMetrics = if ($negativeMode) { 0 } else { 14 }
    $expectedEnvelopeMetrics = if ($negativeMode) { 0 } else { 26 }
    $expectedSuccessfulTasks = if ($negativeMode) { 0 } else { 3 }
    $negativeBindingsMatch = if ($negativeMode) {
        $report.negative_proxy_evidence_recomputed -is [bool] -and
        [bool]$report.negative_proxy_evidence_recomputed -and
        [string]$report.negative_reason_code -ceq 'receipt_missing' -and
        $report.client_delivery_proven -is [bool] -and
        -not [bool]$report.client_delivery_proven -and
        (Test-ExactJsonInteger -Value $report.negative_proxy_raw_files_verified -Expected 12)
    } else {
        $report.negative_proxy_evidence_recomputed -is [bool] -and
        -not [bool]$report.negative_proxy_evidence_recomputed -and
        $null -eq $report.negative_reason_code -and
        $null -eq $report.client_delivery_proven -and
        (Test-ExactJsonInteger -Value $report.negative_proxy_raw_files_verified -Expected 0)
    }
    $bindingsMatch = (
        [string]$report.collection_id -ceq $CollectionId -and
        [string]$report.execution_mode -ceq $ExpectedExecutionMode -and
        [string]$report.run_id -ceq $RunId -and
        [string]$report.manifest_sha256 -ceq $FinalManifestSha256 -and
        [string]$report.source_commit -ceq $SourceCommit -and
        [string]$report.server_version -ceq $ServerVersion -and
        [string]$report.server_binary_sha256 -ceq $ServerBinarySha256 -and
        [string]$report.apk_sha256 -ceq $ApkSha256 -and
        [string]$report.remote_host -ceq $ExpectedRemoteHost -and
        [string]$report.ssh_known_hosts_sha256 -ceq $ExpectedSshKnownHostsSha256 -and
        $report.publication -is [bool] -and
        [bool]$report.publication -and
        $report.apk_identity_reverified -is [bool] -and
        [bool]$report.apk_identity_reverified -and
        [string]$report.android_build_tools_version -ceq '35.0.0' -and
        $apkIdentityProperties.Count -eq 4 -and
        $apkIdentityProperties -ccontains 'package_name' -and
        $apkIdentityProperties -ccontains 'version_name' -and
        $apkIdentityProperties -ccontains 'version_code' -and
        $apkIdentityProperties -ccontains 'signer_sha256' -and
        [string]$report.verified_apk_identity.package_name -ceq $ExpectedPackageName -and
        [string]$report.verified_apk_identity.version_name -ceq $ExpectedVersionName -and
        (Test-ExactJsonInteger -Value $report.verified_apk_identity.version_code -Expected $ExpectedVersionCode) -and
        [string]$report.verified_apk_identity.signer_sha256 -ceq $ExpectedSignerSha256 -and
        $report.accessibility_raw_reverified -is [bool] -and
        [bool]$report.accessibility_raw_reverified -and
        $report.raw_state_reverified -is [bool] -and
        [bool]$report.raw_state_reverified -and
        (Test-ExactJsonInteger -Value $report.raw_files_verified -Expected 26) -and
        (Test-ExactJsonInteger -Value $report.raw_state_files_verified -Expected 26) -and
        (Test-ExactJsonInteger -Value $report.device_identity_raw_files_verified -Expected 6) -and
        (Test-ExactJsonInteger -Value $report.raw_files_verified_total -Expected 32) -and
        $deviceIdentityProperties.Count -eq 13 -and
        $deviceIdentityProperties -ccontains 'schema' -and
        $deviceIdentityProperties -ccontains 'schema_version' -and
        $deviceIdentityProperties -ccontains 'status' -and
        $deviceIdentityProperties -ccontains 'reason_code' -and
        $deviceIdentityProperties -ccontains 'device_alias' -and
        $deviceIdentityProperties -ccontains 'device_policy_sha256' -and
        $deviceIdentityProperties -ccontains 'adb_serial_sha256' -and
        $deviceIdentityProperties -ccontains 'android_boot_id' -and
        $deviceIdentityProperties -ccontains 'properties_sha256' -and
        $deviceIdentityProperties -ccontains 'serial_property_confirmed' -and
        $deviceIdentityProperties -ccontains 'verified_boot_observed_complete' -and
        $deviceIdentityProperties -ccontains 'verified_boot_secure' -and
        $deviceIdentityProperties -ccontains 'raw_files_verified' -and
        [string]$report.device_identity.schema -ceq 'aneb-token-quick-device-identity-verification' -and
        [string]$report.device_identity.schema_version -ceq '1.0.0' -and
        [string]$report.device_identity.status -ceq 'pass' -and
        [string]$report.device_identity.reason_code -ceq 'ok' -and
        [string]$report.device_identity.device_alias -ceq 'P40 Pro' -and
        [string]$report.device_identity.device_policy_sha256 -ceq $ExpectedDevicePolicySha256 -and
        [string]$report.device_identity.adb_serial_sha256 -ceq $ExpectedAdbSerialSha256 -and
        [string]$report.device_identity.android_boot_id -match '^[0-9a-f-]{36}$' -and
        [string]$report.device_identity.properties_sha256 -match '^[0-9a-f]{64}$' -and
        $report.device_identity.serial_property_confirmed -is [bool] -and
        $report.device_identity.verified_boot_observed_complete -is [bool] -and
        $report.device_identity.verified_boot_secure -is [bool] -and
        (Test-ExactJsonInteger -Value $report.device_identity.raw_files_verified -Expected 6) -and
        $report.candidate_provenance_reverified -is [bool] -and
        [bool]$report.candidate_provenance_reverified -and
        [string]$report.attestation_bundle_sha256 -ceq $ExpectedAttestationBundleSha256 -and
        -not [string]::IsNullOrWhiteSpace([string]$report.gh_version) -and
        [string]$report.gh_version -notmatch "[`r`n]" -and
        [string]$report.gh_executable_sha256 -ceq $ExpectedGhSha256 -and
        $report.evidence_time_chain_reverified -is [bool] -and
        [bool]$report.evidence_time_chain_reverified -and
        (Test-ExactJsonInteger -Value $report.run_timeout_seconds -Expected $ExpectedRunTimeoutSeconds) -and
        (Test-ExactJsonInteger -Value $report.lock_ttl_seconds -Expected $ExpectedLockTtlSeconds) -and
        (Test-ExactJsonInteger -Value $report.run_duration_ms -Expected ([int64]$report.run_duration_ms)) -and
        [int64]$report.run_duration_ms -ge 0 -and
        [int64]$report.run_duration_ms -le ([int64]$ExpectedRunTimeoutSeconds * 1000 + 5000) -and
        (Test-ExactJsonInteger -Value $report.run_start_delta_ms -Expected ([int64]$report.run_start_delta_ms)) -and
        [int64]$report.run_start_delta_ms -ge 0 -and
        [int64]$report.run_start_delta_ms -le 5000 -and
        (Test-ExactJsonInteger -Value $report.remote_receipt_clock_delta_ms -Expected ([int64]$report.remote_receipt_clock_delta_ms)) -and
        [int64]$report.remote_receipt_clock_delta_ms -ge 0 -and
        [int64]$report.remote_receipt_clock_delta_ms -le 60000 -and
        $report.journal_derivation_recomputed -is [bool] -and
        [bool]$report.journal_derivation_recomputed -and
        $report.request_entry_audit_recomputed -is [bool] -and
        [bool]$report.request_entry_audit_recomputed -and
        $report.client_room_result_recomputed -is [bool] -and
        [bool]$report.client_room_result_recomputed -and
        $negativeBindingsMatch -and
        $businessProperties.Count -eq 3 -and
        $businessProperties -ccontains 'echo' -and
        $businessProperties -ccontains 'token_sim' -and
        $businessProperties -ccontains 'download' -and
        (Test-ExactJsonInteger -Value $report.business_counts.echo -Expected $expectedEchoCount) -and
        (Test-ExactJsonInteger -Value $report.business_counts.token_sim -Expected $expectedTokenSimCount) -and
        (Test-ExactJsonInteger -Value $report.business_counts.download -Expected $expectedDownloadCount) -and
        (Test-ExactJsonInteger -Value $report.typed_metrics_verified -Expected $expectedTypedMetrics) -and
        (Test-ExactJsonInteger -Value $report.envelope_metrics_verified -Expected $expectedEnvelopeMetrics) -and
        (Test-ExactJsonInteger -Value $report.successful_task_count -Expected $expectedSuccessfulTasks)
    )
    if (-not $bindingsMatch) {
        throw 'bundle_verifier_report_binding_mismatch fields=identity_or_recomputed_counts'
    }
    return $report
}

function Invoke-PublishedBundleVerification {
    param(
        [Parameter(Mandatory = $true)][string]$CompleteDirectory,
        [Parameter(Mandatory = $true)][string]$CollectionId,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$FinalManifestSha256,
        [Parameter(Mandatory = $true)][string]$SourceCommit,
        [Parameter(Mandatory = $true)][string]$ServerVersion,
        [Parameter(Mandatory = $true)][string]$ServerBinarySha256,
        [Parameter(Mandatory = $true)][string]$ApkSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedRemoteHost,
        [Parameter(Mandatory = $true)][string]$ExpectedSshKnownHostsSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedPackageName,
        [Parameter(Mandatory = $true)][string]$ExpectedVersionName,
        [Parameter(Mandatory = $true)][int]$ExpectedVersionCode,
        [Parameter(Mandatory = $true)][string]$ExpectedSignerSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedDevicePolicySha256,
        [Parameter(Mandatory = $true)][string]$ExpectedAdbSerialSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedAttestationBundleSha256,
        [Parameter(Mandatory = $true)][string]$ExpectedGhSha256,
        [Parameter(Mandatory = $true)][ValidateSet('positive', 'negative_receipt_missing')][string]$ExpectedExecutionMode,
        [Parameter(Mandatory = $true)][int]$ExpectedRunTimeoutSeconds,
        [Parameter(Mandatory = $true)][int]$ExpectedLockTtlSeconds,
        [Parameter(Mandatory = $true)][string]$EvidenceRootPath,
        [Parameter(Mandatory = $true)][string]$PublishTarget
    )
    if ((Split-Path -Leaf $BundleVerifierPath) -cne 'verify_token_quick_evidence_bundle.py') {
        throw 'bundle_verifier_identity_invalid'
    }
    if (-not (Test-Path -LiteralPath $CompleteDirectory -PathType Container) -or
        (Split-Path -Leaf $CompleteDirectory) -cne ($CollectionId + '.complete')) {
        throw 'bundle_verification_target_invalid'
    }
    $reportPath = Join-Path $EvidenceRootPath ($CollectionId + '.verification.json')
    $reportTempPath = Join-Path $EvidenceRootPath ($CollectionId + '.verification.partial')
    if ((Test-Path -LiteralPath $reportPath) -or
        (Test-Path -LiteralPath $reportTempPath)) {
        throw 'bundle_verification_report_collision'
    }
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    Assert-BundledDevicePolicyStable -EvidenceDirectory $CompleteDirectory
    Assert-GhExecutableStable
    $verification = Invoke-BoundedNativeTextOnce `
        -Command $script:ResolvedTools.Python `
        -Arguments @(
            $BundleVerifierPath,
            $CompleteDirectory,
            '--repository-root', $script:ResolvedTools.RepositoryRoot,
            '--android-build-tools-dir', $script:ResolvedTools.AndroidBuildToolsDir,
            '--expected-remote-host', $ExpectedRemoteHost,
            '--expected-ssh-known-hosts-sha256', $ExpectedSshKnownHostsSha256,
            '--device-policy-path', [string]$script:ResolvedTools.DevicePolicy.Path,
            '--gh-path', [string]$script:ResolvedTools.Gh,
            '--expected-execution-mode', $ExpectedExecutionMode,
            '--publish',
            '--publish-target', $PublishTarget
        ) `
        -TimeoutSeconds $ToolCommandTimeoutSeconds `
        -TimeoutReason 'tool_timeout stage=verify_token_quick_evidence_bundle.py' `
        -LaunchReason 'tool_launch_failed stage=verify_token_quick_evidence_bundle.py'
    $rc = [int]$verification.ExitCode
    $output = @(
        if (-not [string]::IsNullOrEmpty([string]$verification.Text)) {
            ([string]$verification.Text) -split "`n"
        }
    )
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    $report = Assert-BundleVerificationReport `
        -OutputLines $output `
        -ExitCode $rc `
        -CollectionId $CollectionId `
        -RunId $RunId `
        -FinalManifestSha256 $FinalManifestSha256 `
        -SourceCommit $SourceCommit `
        -ServerVersion $ServerVersion `
        -ServerBinarySha256 $ServerBinarySha256 `
        -ApkSha256 $ApkSha256 `
        -ExpectedRemoteHost $ExpectedRemoteHost `
        -ExpectedSshKnownHostsSha256 $ExpectedSshKnownHostsSha256 `
        -ExpectedPackageName $ExpectedPackageName `
        -ExpectedVersionName $ExpectedVersionName `
        -ExpectedVersionCode $ExpectedVersionCode `
        -ExpectedSignerSha256 $ExpectedSignerSha256 `
        -ExpectedDevicePolicySha256 $ExpectedDevicePolicySha256 `
        -ExpectedAdbSerialSha256 $ExpectedAdbSerialSha256 `
        -ExpectedAttestationBundleSha256 $ExpectedAttestationBundleSha256 `
        -ExpectedGhSha256 $ExpectedGhSha256 `
        -ExpectedExecutionMode $ExpectedExecutionMode `
        -ExpectedRunTimeoutSeconds $ExpectedRunTimeoutSeconds `
        -ExpectedLockTtlSeconds $ExpectedLockTtlSeconds
    if (Test-Path -LiteralPath $CompleteDirectory) {
        throw 'bundle_verifier_publish_source_still_present'
    }
    if (-not (Test-Path -LiteralPath $PublishTarget -PathType Container) -or
        ((Get-Item -LiteralPath $PublishTarget -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $report.publication -isnot [bool] -or
         -not [bool]$report.publication) {
        throw 'bundle_verifier_publish_target_invalid'
    }
    try {
        Write-NewTextNoBom -Path $reportTempPath -Text (([string]$output[0]) + "`n")
        Assert-NonEmptyFile -Path $reportTempPath -Label 'independent bundle verification report'
    } catch {
        if (Test-Path -LiteralPath $reportTempPath -PathType Leaf) {
            Remove-Item -LiteralPath $reportTempPath -Force
        }
        throw
    }
    return [pscustomobject]@{
        Path = $reportPath
        TempPath = $reportTempPath
        Report = $report
    }
}

function Publish-EvidenceReleaseReady {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceRootPath,
        [Parameter(Mandatory = $true)][string]$CollectionId,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)]
        [ValidateSet('positive', 'negative_receipt_missing')][string]$ExecutionMode,
        [Parameter(Mandatory = $true)][string]$CompleteDirectory,
        [Parameter(Mandatory = $true)][string]$FinalManifestSha256,
        [Parameter(Mandatory = $true)][string]$VerificationReportPath
    )
    if ($CollectionId -notmatch '^d82-token-quick-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}$' -or
        $RunId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' -or
        $FinalManifestSha256 -notmatch '^[0-9a-f]{64}$') {
        throw 'evidence_release_identity_invalid'
    }
    $root = Assert-NonReparseDirectoryChain `
        -Path $EvidenceRootPath `
        -ReasonPrefix 'evidence_root'
    Assert-PrivateEvidenceRoot -Path $root
    $expectedComplete = Join-Path $root ($CollectionId + '.complete')
    $expectedReport = Join-Path $root ($CollectionId + '.verification.json')
    if (-not ([IO.Path]::GetFullPath($CompleteDirectory)).Equals(
            [IO.Path]::GetFullPath($expectedComplete),
            [StringComparison]::OrdinalIgnoreCase
        ) -or
        -not ([IO.Path]::GetFullPath($VerificationReportPath)).Equals(
            [IO.Path]::GetFullPath($expectedReport),
            [StringComparison]::OrdinalIgnoreCase
        )) {
        throw 'evidence_release_path_binding_invalid'
    }
    $null = Assert-NonReparseDirectoryChain `
        -Path $CompleteDirectory `
        -ReasonPrefix 'evidence_complete'
    $manifestPath = Join-Path $CompleteDirectory 'evidence-manifest.final.json'
    Assert-NonEmptyFile -Path $manifestPath -Label 'release final manifest'
    Assert-NonEmptyFile -Path $VerificationReportPath -Label 'release verification report'
    foreach ($leaf in @($manifestPath, $VerificationReportPath)) {
        if (((Get-Item -LiteralPath $leaf -Force).Attributes -band
                [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'evidence_release_reparse_point_forbidden'
        }
    }
    $actualManifestSha256 = (
        Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    if ($actualManifestSha256 -cne $FinalManifestSha256) {
        throw 'evidence_release_manifest_digest_mismatch'
    }
    $reportSha256 = (
        Get-FileHash -LiteralPath $VerificationReportPath -Algorithm SHA256
    ).Hash.ToLowerInvariant()
    $readyPath = Join-Path $root ($CollectionId + '.READY.json')
    $readyTempPath = Join-Path $root ($CollectionId + '.ready.partial')
    if ((Test-Path -LiteralPath $readyPath) -or
        (Test-Path -LiteralPath $readyTempPath)) {
        throw 'evidence_release_marker_collision'
    }
    $readyTempCreated = $false
    try {
        $marker = [ordered]@{
            schema = 'aneb-d82-evidence-release'
            schema_version = '1.0.0'
            status = 'ready'
            reason_code = 'ok'
            collection_id = $CollectionId
            run_id = $RunId
            execution_mode = $ExecutionMode
            bundle_leaf = Split-Path -Leaf $CompleteDirectory
            manifest_sha256 = $actualManifestSha256
            verification_report_leaf = Split-Path -Leaf $VerificationReportPath
            verification_report_sha256 = $reportSha256
            committed_at_utc = [DateTime]::UtcNow.ToString(
                "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
                [Globalization.CultureInfo]::InvariantCulture
            )
        }
        Write-NewTextNoBom `
            -Path $readyTempPath `
            -Text (($marker | ConvertTo-Json -Compress) + "`n")
        $readyTempCreated = $true
        Assert-NonEmptyFile -Path $readyTempPath -Label 'evidence release marker draft'
        $roundTrip = Get-Content -LiteralPath $readyTempPath -Raw -Encoding UTF8 |
            ConvertFrom-Json
        $expectedReadyProperties = @(
            'bundle_leaf', 'collection_id', 'committed_at_utc', 'execution_mode',
            'manifest_sha256', 'reason_code', 'run_id', 'schema', 'schema_version',
            'status', 'verification_report_leaf', 'verification_report_sha256'
        )
        $actualReadyProperties = @($roundTrip.PSObject.Properties.Name | Sort-Object)
        $roundTripCommittedAt = if ($roundTrip.committed_at_utc -is [DateTime]) {
            ([DateTime]$roundTrip.committed_at_utc).ToUniversalTime().ToString(
                "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
                [Globalization.CultureInfo]::InvariantCulture
            )
        } else {
            [string]$roundTrip.committed_at_utc
        }
        if (($actualReadyProperties -join "`0") -cne
                (($expectedReadyProperties | Sort-Object) -join "`0") -or
            [string]$roundTrip.schema -cne 'aneb-d82-evidence-release' -or
            [string]$roundTrip.schema_version -cne '1.0.0' -or
            [string]$roundTrip.status -cne 'ready' -or
            [string]$roundTrip.reason_code -cne 'ok' -or
            [string]$roundTrip.collection_id -cne $CollectionId -or
            [string]$roundTrip.run_id -cne $RunId -or
            [string]$roundTrip.execution_mode -cne $ExecutionMode -or
            [string]$roundTrip.bundle_leaf -cne (Split-Path -Leaf $CompleteDirectory) -or
            [string]$roundTrip.manifest_sha256 -cne $actualManifestSha256 -or
            [string]$roundTrip.verification_report_leaf -cne
                (Split-Path -Leaf $VerificationReportPath) -or
            [string]$roundTrip.verification_report_sha256 -cne $reportSha256 -or
            $roundTripCommittedAt -cne [string]$marker.committed_at_utc -or
            $roundTripCommittedAt -notmatch
                '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$') {
            throw 'evidence_release_marker_roundtrip_invalid'
        }
        [IO.File]::Move($readyTempPath, $readyPath)
        $readyTempCreated = $false
    } catch {
        if ($readyTempCreated -and
            (Test-Path -LiteralPath $readyTempPath -PathType Leaf)) {
            Remove-Item -LiteralPath $readyTempPath -Force
        }
        throw
    }
    return $readyPath
}

function Write-EvidenceManifestDraft {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $manifestPath = Join-Path $EvidenceDirectory 'evidence-inventory.draft.json'
    $files = @(Get-ChildItem -LiteralPath $EvidenceDirectory -File -Recurse | Where-Object {
        $_.FullName -cne $manifestPath
    } | Sort-Object FullName)
    if ($files.Count -le 0) {
        throw 'evidence_manifest_empty'
    }
    $arguments = New-Object System.Collections.Generic.List[string]
    foreach ($argument in @(
        $DeriveHelperPath, 'manifest',
        '--root', $EvidenceDirectory,
        '--output', $manifestPath
    )) {
        $arguments.Add([string]$argument)
    }
    foreach ($file in $files) {
        $arguments.Add('--file')
        $arguments.Add($file.FullName)
    }
    $null = Invoke-ToolTextOnce -Command $script:ResolvedTools.Python -Arguments $arguments.ToArray() -Stage 'evidence_manifest'
    Assert-NonEmptyFile -Path $manifestPath -Label 'evidence manifest draft'
    return $manifestPath
}

function Get-RequiredFrozenRoomSidecarPaths {
    param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
    $inventoryPath = Join-Path $EvidenceDirectory 'room-copy-inventory.json'
    Assert-NonEmptyFile -Path $inventoryPath -Label 'frozen Room inventory'
    try {
        $inventory = Get-Content -LiteralPath $inventoryPath -Raw -Encoding UTF8 |
            ConvertFrom-Json
    } catch {
        throw 'room_inventory_json_invalid'
    }
    $expectedTop = @(
        'app_process_state', 'captured_at_utc', 'files', 'schema', 'schema_version'
    )
    $actualTop = @($inventory.PSObject.Properties.Name | Sort-Object)
    if ((($expectedTop | Sort-Object) -join "`0") -cne ($actualTop -join "`0") -or
        [string]$inventory.schema -cne 'aneb-frozen-room-copy' -or
        [string]$inventory.schema_version -cne '1.0.0' -or
        [string]$inventory.app_process_state -cne 'stopped_before_copy') {
        throw 'room_inventory_identity_invalid'
    }
    $files = @($inventory.files)
    $expectedNames = @('aneb-probe.db', 'aneb-probe.db-wal', 'aneb-probe.db-shm')
    if ($files.Count -ne $expectedNames.Count) {
        throw 'room_inventory_files_invalid'
    }
    $states = @{}
    for ($index = 0; $index -lt $expectedNames.Count; $index++) {
        $entry = $files[$index]
        $name = $expectedNames[$index]
        if ($null -eq $entry -or [string]$entry.name -cne $name) {
            throw 'room_inventory_files_invalid'
        }
        $state = [string]$entry.state
        if ($state -cne 'present' -and $state -cne 'absent') {
            throw 'room_inventory_files_invalid'
        }
        $expectedEntry = if ($state -ceq 'present') {
            @('bytes', 'name', 'sha256', 'state')
        } else {
            @('name', 'state')
        }
        $actualEntry = @($entry.PSObject.Properties.Name | Sort-Object)
        if ((($expectedEntry | Sort-Object) -join "`0") -cne ($actualEntry -join "`0")) {
            throw 'room_inventory_files_invalid'
        }
        if ($state -ceq 'present' -and
            ([int64]$entry.bytes -le 0 -or
                [string]$entry.sha256 -notmatch '^[0-9a-f]{64}$')) {
            throw 'room_inventory_files_invalid'
        }
        $states[$name] = $state
    }
    if ([string]$states['aneb-probe.db'] -cne 'present' -or
        [string]$states['aneb-probe.db-wal'] -cne
            [string]$states['aneb-probe.db-shm']) {
        throw 'room_inventory_sidecar_state_invalid'
    }
    if ([string]$states['aneb-probe.db-wal'] -ceq 'present') {
        return @('aneb-probe.db-wal', 'aneb-probe.db-shm')
    }
    return
}

function Write-FinalEvidenceManifest {
    param(
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
        [Parameter(Mandatory = $true)][string]$DraftPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$CollectionId,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$StartBarrierId,
        [Parameter(Mandatory = $true)][string]$EndBarrierId
    )
    if ([IO.Path]::GetFileName($OutputPath) -cne 'evidence-manifest.final.json' -or
        (Test-Path -LiteralPath $OutputPath)) {
        throw 'final_manifest_output_invalid_or_exists'
    }
    Assert-ToolingProvenanceStable -ResolvedTools $script:ResolvedTools
    Assert-BundledDevicePolicyStable -EvidenceDirectory $EvidenceDirectory
    $requiredPaths = @(
        'collector-plan.json', 'collector-status.json', 'cleanup-report.json',
        'device-preflight.json', 'device-final-clean.json',
        'device-adb-serial-preflight.txt', 'device-adb-serial-final.txt',
        'device-getprop-preflight.txt', 'device-getprop-final.txt',
        'device-boot-id-preflight.txt', 'device-boot-id-final.txt',
        'device-processes-preflight.json', 'device-services-preflight.json',
        'device-processes-final.json', 'device-services-final.json',
        'device-tun-preflight.txt', 'device-tun-final.txt',
        'device-stayon-preflight.txt', 'device-stayon-final.txt',
        'device-window-preflight.txt', 'device-window-final.txt',
        'device-activity-preflight.txt', 'device-activity-final.txt',
        'device-accessibility-preflight.txt', 'device-accessibility-final.txt',
        'device-connectivity-preflight.txt', 'device-connectivity-final.txt',
        'device-vpn-preflight.txt', 'device-vpn-final.txt',
        'device-package-preflight.txt',
        'identity-serverinfo.headers', 'identity-serverinfo.json',
        'pre-start-receipt.json', 'remote-pre-start.txt', 'remote-end.txt',
        'start-barrier.headers', 'start-barrier.json',
        'end-barrier.headers', 'end-barrier.json',
        'journal.raw.jsonl', 'token-run-audit.log', 'journal-derivation.json',
        'request-entry-audit.json', 'app-logcat.txt',
        'logcat-capture-marker.json',
        'aneb-probe.db', 'room-copy-inventory.json',
        'client-result.json', 'client-db-report.json',
        'installed-base.apk', 'device-policy.json',
        'ci-candidate/ANEB-Probe-0.5.12-codex-debug.apk',
        'ci-candidate/build-manifest.json',
        'ci-candidate/checksums.sha256',
        'ci-candidate/provenance.sigstore.json',
        ("ci-candidate/$CandidateInstallNotesName"),
        'lock-acquired.txt', 'lock-released.txt', 'lock-release-verified.txt'
    )
    if ($EvidenceMode -ceq 'negative') {
        $requiredPaths += @(
            'negative-proxy/upstream-serverinfo.raw',
            'negative-proxy/filtered-serverinfo.json',
            'negative-proxy/upstream-serverinfo.headers.json',
            'negative-proxy/peer-certificate.sha256',
            'negative-proxy/request-ledger.json',
            'negative-proxy/proxy-receipt.json',
            'negative-proxy.stdout.jsonl',
            'negative-proxy.stderr.txt',
            'adb-reverse-preflight.txt', 'adb-reverse-active.txt',
            'adb-reverse-before-remove.txt', 'adb-reverse-final.txt'
        )
    }
    $requiredSidecars = @(
        Get-RequiredFrozenRoomSidecarPaths -EvidenceDirectory $EvidenceDirectory
    )
    $requiredPaths += $requiredSidecars
    $expectedRequiredCount = if ($EvidenceMode -ceq 'negative') { 71 } else { 59 }
    $expectedRequiredCount += $requiredSidecars.Count
    if ($requiredPaths.Count -ne $expectedRequiredCount) {
        throw "final_required_payload_count_invalid count=$($requiredPaths.Count)"
    }
    foreach ($sentinelEvidencePath in @(
        'device-busy-sentinel-launch.txt',
        'device-busy-sentinel-restore.txt',
        'device-busy-sentinel.json',
        'busy-sentinel-observations.jsonl',
        'device-busy-sentinel-release-guard.json',
        'device-identity-report.json',
        'ci-candidate-verification.json'
    )) {
        Assert-NonEmptyFile `
            -Path (Join-Path $EvidenceDirectory $sentinelEvidencePath) `
            -Label 'supplemental bound evidence'
    }
    $draft = Assert-EvidenceManifestDraft `
        -EvidenceDirectory $EvidenceDirectory `
        -DraftPath $DraftPath `
        -RequiredPaths $requiredPaths
    try {
        $collectorPlan = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'collector-plan.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw 'collector_plan_json_invalid'
    }
    if ([string]$collectorPlan.adb_serial_sha256 -cne (Get-Utf8StringSha256 -Value $AdbSerial) -or
        [string]$collectorPlan.device_policy_sha256 -cne [string]$script:ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256) {
        throw 'collector_plan_device_binding_mismatch'
    }
    if ([string]$collectorPlan.schema -cne 'aneb-d82-collector-plan' -or
        [string]$collectorPlan.schema_version -cne '1.1.0' -or
        [string]$collectorPlan.execution_mode -cne $script:ExecutionMode -or
        [string]$collectorPlan.collection_id -cne $CollectionId -or
        [string]$collectorPlan.remote_host -cne [string]$script:ResolvedTools.BoundRemoteHost -or
        [string]$collectorPlan.ssh_known_hosts_sha256 -cne [string]$script:ResolvedTools.ToolingProvenance.external_inputs.ssh_known_hosts_sha256 -or
        [string]$collectorPlan.start_barrier_id -cne $StartBarrierId -or
        [string]$collectorPlan.end_barrier_id -cne $EndBarrierId -or
        [string]$collectorPlan.android_build_tools_version -cne '35.0.0' -or
        $collectorPlan.run_timeout_seconds -isnot [int] -or
        [int]$collectorPlan.run_timeout_seconds -ne $RunTimeoutSeconds -or
        $collectorPlan.lock_ttl_seconds -isnot [int] -or
        [int]$collectorPlan.lock_ttl_seconds -ne $LockTtlSeconds -or
        $collectorPlan.adb_command_timeout_seconds -isnot [int] -or
        [int]$collectorPlan.adb_command_timeout_seconds -ne $AdbCommandTimeoutSeconds -or
        $collectorPlan.ssh_command_timeout_seconds -isnot [int] -or
        [int]$collectorPlan.ssh_command_timeout_seconds -ne $SshCommandTimeoutSeconds -or
        $collectorPlan.tool_command_timeout_seconds -isnot [int] -or
        [int]$collectorPlan.tool_command_timeout_seconds -ne $ToolCommandTimeoutSeconds -or
        [int]$collectorPlan.lock_ttl_seconds -le [int]$collectorPlan.run_timeout_seconds + 60) {
        throw 'collector_plan_time_binding_mismatch'
    }
    $expectedClientServerBase = if ($EvidenceMode -ceq 'negative') {
        $NegativeClientServerBase
    } else {
        $ServerBase.TrimEnd('/')
    }
    $expectedNegativeUpstream = if ($EvidenceMode -ceq 'negative') {
        [string]$script:ResolvedTools.NegativeProxyUpstreamUrl
    } else {
        $null
    }
    $expectedNegativeDevicePort = if ($EvidenceMode -ceq 'negative') {
        $NegativeProxyDevicePort
    } else {
        $null
    }
    if ([string]$collectorPlan.client_server_base -cne $expectedClientServerBase -or
        [string]$collectorPlan.negative_proxy_upstream_url -cne [string]$expectedNegativeUpstream -or
        (($null -eq $expectedNegativeDevicePort -and
          $null -ne $collectorPlan.negative_proxy_device_port) -or
         ($null -ne $expectedNegativeDevicePort -and
          ($collectorPlan.negative_proxy_device_port -isnot [int] -or
           [int]$collectorPlan.negative_proxy_device_port -ne $expectedNegativeDevicePort)))) {
        throw 'collector_plan_execution_binding_mismatch'
    }
    $receipt = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'pre-start-receipt.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $devicePreflight = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'device-preflight.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    try {
        $deviceIdentity = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'device-identity-report.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw 'device_identity_report_json_invalid'
    }
    $deviceIdentity = Assert-DeviceIdentityReport -Report $deviceIdentity
    try {
        $candidateVerificationLine = (Get-Content -LiteralPath (
            Join-Path $EvidenceDirectory 'ci-candidate-verification.json'
        ) -Raw -Encoding UTF8).TrimEnd("`r", "`n")
    } catch {
        throw 'ci_candidate_verification_report_invalid'
    }
    $candidateVerification = Assert-CiProvenanceReport `
        -OutputLines @($candidateVerificationLine) `
        -ExitCode 0 `
        -SourceCommit ([string]$script:ResolvedTools.ToolingProvenance.source_commit)
    $status = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'collector-status.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $cleanup = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'cleanup-report.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $busySentinel = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'device-busy-sentinel.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $busyReleaseGuard = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'device-busy-sentinel-release-guard.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $audit = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'request-entry-audit.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $client = Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'client-db-report.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $identityBodyPath = Join-Path $EvidenceDirectory 'identity-serverinfo.json'
    $startBodyPath = Join-Path $EvidenceDirectory 'start-barrier.json'
    $endBodyPath = Join-Path $EvidenceDirectory 'end-barrier.json'
    $identityInfo = Assert-ServerInfoReceiptBinding -Receipt $receipt -BodyPath $identityBodyPath
    $startInfo = Assert-ServerInfoBody -BodyPath $startBodyPath -Stage 'final_start_barrier'
    $endInfo = Assert-ServerInfoBody -BodyPath $endBodyPath -Stage 'final_end_barrier'
    Assert-ServerInfoSequence `
        -Identity $identityInfo `
        -StartBarrier $startInfo `
        -EndBarrier $endInfo `
        -ExpectedProfileSha256 ([string]$client.profile_sha256)
    Assert-CapturedHttp200Headers `
        -HeadersPath (Join-Path $EvidenceDirectory 'identity-serverinfo.headers') `
        -Stage 'final_identity'
    Assert-CapturedHttp200Headers `
        -HeadersPath (Join-Path $EvidenceDirectory 'start-barrier.headers') `
        -Stage 'final_start_barrier'
    Assert-CapturedHttp200Headers `
        -HeadersPath (Join-Path $EvidenceDirectory 'end-barrier.headers') `
        -Stage 'final_end_barrier'
    $busyComponent = [string]$busySentinel.component
    if ([string]$busySentinel.schema -cne 'aneb-d82-busy-sentinel' -or
        [string]$busySentinel.schema_version -cne '1.0.0' -or
        [string]$busySentinel.intent_action -cne 'android.settings.SETTINGS' -or
        $busyComponent -notmatch '^com\.android\.settings/' -or
        $busyComponent -ceq $LauncherComponent -or
        [string]$busyReleaseGuard.schema -cne 'aneb-d82-busy-sentinel-release-guard' -or
        [string]$busyReleaseGuard.schema_version -cne '1.0.0' -or
        [string]$busyReleaseGuard.sentinel_component -cne $busyComponent -or
        $busyReleaseGuard.no_conflicting_session -isnot [bool] -or
        -not [bool]$busyReleaseGuard.no_conflicting_session) {
        throw 'busy_sentinel_final_evidence_invalid'
    }
    Assert-BusySentinelEvidence `
        -EvidenceDirectory $EvidenceDirectory `
        -ExpectedComponent $busyComponent
    $serverInfoBodyDigests = [ordered]@{
        identity = (Get-FileHash -LiteralPath $identityBodyPath -Algorithm SHA256).Hash.ToLowerInvariant()
        start_barrier = (Get-FileHash -LiteralPath $startBodyPath -Algorithm SHA256).Hash.ToLowerInvariant()
        end_barrier = (Get-FileHash -LiteralPath $endBodyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if ([string]$status.status -cne 'pass' -or
        $status.workflow_succeeded -isnot [bool] -or -not [bool]$status.workflow_succeeded -or
        $status.cleanup_succeeded -isnot [bool] -or -not [bool]$status.cleanup_succeeded -or
        [string]$status.collection_id -cne $CollectionId -or
        [string]$status.run_id -cne $RunId -or
        [string]$status.start_barrier_id -cne $StartBarrierId -or
        [string]$status.end_barrier_id -cne $EndBarrierId -or
        [string]$cleanup.status -cne 'pass' -or
        $cleanup.busy_sentinel_started -isnot [bool] -or -not [bool]$cleanup.busy_sentinel_started -or
        $cleanup.busy_sentinel_verified -isnot [bool] -or -not [bool]$cleanup.busy_sentinel_verified -or
        $cleanup.busy_sentinel_lost -isnot [bool] -or [bool]$cleanup.busy_sentinel_lost -or
        [string]$cleanup.busy_sentinel_component -cne $busyComponent -or
        $cleanup.busy_sentinel_release_attempted -isnot [bool] -or -not [bool]$cleanup.busy_sentinel_release_attempted -or
        $cleanup.busy_sentinel_home_succeeded -isnot [bool] -or -not [bool]$cleanup.busy_sentinel_home_succeeded -or
        [string]$audit.status -cne 'pass' -or
        [string]$audit.reason_code -cne 'ok' -or
        [string]$audit.run_id -cne $RunId -or
        [string]$audit.start_barrier_id -cne $StartBarrierId -or
        [string]$audit.barrier_id -cne $EndBarrierId -or
        [string]$audit.profile_contract -cne $ProfileContract -or
        [string]$audit.profile_contract_definition_sha256 -cne $script:ResolvedTools.ProfileContractDefinitionSha256 -or
        [string]$client.status -cne 'pass' -or
        [string]$client.reason_code -cne 'ok' -or
        [string]$client.run_id -cne $RunId) {
        throw 'final_manifest_cross_binding_mismatch'
    }
    $installedApkPath = Join-Path $EvidenceDirectory 'installed-base.apk'
    $installedApkSha256 = Assert-ExpectedFileSha256 `
        -Path $installedApkPath `
        -ExpectedSha256 $ExpectedApkSha256 `
        -Label 'final_installed_apk'
    if ([string]$devicePreflight.package_name -cne $PackageName -or
        [string]$devicePreflight.version_name -cne $ExpectedVersionName -or
        [int64]$devicePreflight.version_code -ne $ExpectedVersionCode -or
        [string]$devicePreflight.signer_sha256 -cne $ExpectedSignerSha256.ToLowerInvariant() -or
        [string]$devicePreflight.apk_sha256 -cne $installedApkSha256) {
        throw 'final_manifest_client_identity_mismatch'
    }
    if ([string]$candidateVerification.apk.sha256 -cne $installedApkSha256 -or
        [string]$candidateVerification.apk.signer_sha256 -cne $ExpectedSignerSha256.ToLowerInvariant() -or
        [string]$candidateVerification.apk.package_name -cne $PackageName -or
        [string]$candidateVerification.apk.version_name -cne $ExpectedVersionName -or
        [int64]$candidateVerification.apk.version_code -ne $ExpectedVersionCode) {
        throw 'final_manifest_candidate_identity_mismatch'
    }
    if ([string]$devicePreflight.adb_serial -cne $AdbSerial -or
        [string]$deviceIdentity.adb_serial_sha256 -cne (Get-Utf8StringSha256 -Value ([string]$devicePreflight.adb_serial)) -or
        [string]$deviceIdentity.device_policy_sha256 -cne [string]$collectorPlan.device_policy_sha256 -or
        [string]$deviceIdentity.adb_serial_sha256 -cne [string]$collectorPlan.adb_serial_sha256) {
        throw 'final_manifest_device_identity_mismatch'
    }
    $fileEntries = @($draft.files | ForEach-Object {
        [ordered]@{
            bytes = [int64]$_.bytes
            path = [string]$_.path
            sha256 = [string]$_.sha256
        }
    })
    $final = [ordered]@{
        schema = 'aneb-d82-final-evidence-manifest'
        schema_version = '1.1.0'
        status = 'final'
        acceptance_eligible = $true
        evidence_scope = $script:EvidenceScope
        execution_mode = $script:ExecutionMode
        finalized_at_utc = [DateTime]::UtcNow.ToString(
            "yyyy-MM-dd'T'HH:mm:ss.fffffff'Z'",
            [Globalization.CultureInfo]::InvariantCulture
        )
        collection_id = $CollectionId
        run_id = $RunId
        start_barrier_id = $StartBarrierId
        end_barrier_id = $EndBarrierId
        profile_contract = $ProfileContract
        profile_contract_definition_sha256 = $script:ResolvedTools.ProfileContractDefinitionSha256
        tooling_provenance = $script:ResolvedTools.ToolingProvenance
        client = [ordered]@{
            package_name = $PackageName
            version_name = $ExpectedVersionName
            version_code = $ExpectedVersionCode
            signer_sha256 = $ExpectedSignerSha256.ToLowerInvariant()
            apk_sha256 = $installedApkSha256
        }
        device = [ordered]@{
            schema = [string]$deviceIdentity.schema
            schema_version = [string]$deviceIdentity.schema_version
            status = [string]$deviceIdentity.status
            reason_code = [string]$deviceIdentity.reason_code
            device_alias = [string]$deviceIdentity.device_alias
            device_policy_sha256 = [string]$deviceIdentity.device_policy_sha256
            adb_serial_sha256 = [string]$deviceIdentity.adb_serial_sha256
            android_boot_id = [string]$deviceIdentity.android_boot_id
            properties_sha256 = [string]$deviceIdentity.properties_sha256
            serial_property_confirmed = [bool]$deviceIdentity.serial_property_confirmed
            verified_boot_observed_complete = [bool]$deviceIdentity.verified_boot_observed_complete
            verified_boot_secure = [bool]$deviceIdentity.verified_boot_secure
            raw_files_verified = [int]$deviceIdentity.raw_files_verified
        }
        source = [ordered]@{
            server_base = [string]$receipt.server_base
            server_version = [string]$receipt.server_version
            server_binary_sha256 = [string]$receipt.server_binary_sha256
            boot_id = [string]$receipt.boot_id
            systemd_invocation_id = [string]$receipt.systemd_invocation_id
            main_pid = [int64]$receipt.main_pid
            journal_cursor = [string]$receipt.journal_cursor
            journal_monotonic_anchor = [uint64]$receipt.journal_monotonic_anchor
            remote_realtime_anchor_usec = [uint64]$receipt.remote_realtime_anchor_usec
            serverinfo_body_sha256 = $serverInfoBodyDigests
            server_ca_sha256 = [string]$script:ResolvedTools.ServerCa.Sha256
            server_ca_thumbprint = [string]$script:ResolvedTools.ServerCa.Thumbprint
        }
        draft_inventory_sha256 = (Get-FileHash -LiteralPath $DraftPath -Algorithm SHA256).Hash.ToLowerInvariant()
        client_result_body_sha256 = [string]$client.result_body_sha256
        file_count = [int64]$draft.file_count
        total_bytes = [int64]$draft.total_bytes
        files = $fileEntries
    }
    $json = $final | ConvertTo-Json -Depth 12
    Write-NewTextNoBom -Path $OutputPath -Text ($json + "`n")
    Assert-NonEmptyFile -Path $OutputPath -Label 'final evidence manifest'
    return $final
}

function New-EvidenceStagingDirectory {
    param([Parameter(Mandatory = $true)][string]$CollectionId)
    if ($CollectionId -notmatch '^d82-token-quick-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}$') {
        throw 'collection_id_invalid'
    }
    $rootPath = Assert-NonReparseDirectoryChain `
        -Path $EvidenceRoot `
        -ReasonPrefix 'evidence_root'
    if (Test-Path -LiteralPath $rootPath) {
        if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) {
            throw 'evidence_root_not_directory'
        }
    } else {
        $null = New-Item -ItemType Directory -Path $rootPath
    }
    $rootItem = Get-Item -LiteralPath $rootPath -Force
    $null = Assert-NonReparseDirectoryChain `
        -Path $rootItem.FullName `
        -ReasonPrefix 'evidence_root'
    if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'evidence_root_reparse_point_forbidden'
    }
    Assert-PrivateEvidenceRoot -Path $rootItem.FullName
    $partial = Join-Path $rootItem.FullName ($CollectionId + '.partial')
    $complete = Join-Path $rootItem.FullName ($CollectionId + '.complete')
    $verificationStage = Join-Path $rootItem.FullName ($CollectionId + '.verification-stage')
    $verificationFailed = Join-Path $rootItem.FullName ($CollectionId + '.verification-failed.partial')
    $verificationReport = Join-Path $rootItem.FullName ($CollectionId + '.verification.json')
    $verificationReportTemp = Join-Path $rootItem.FullName ($CollectionId + '.verification.partial')
    $ready = Join-Path $rootItem.FullName ($CollectionId + '.READY.json')
    $readyTemp = Join-Path $rootItem.FullName ($CollectionId + '.ready.partial')
    if ((Test-Path -LiteralPath $partial) -or
        (Test-Path -LiteralPath $complete) -or
        (Test-Path -LiteralPath $verificationStage) -or
        (Test-Path -LiteralPath $verificationFailed) -or
        (Test-Path -LiteralPath $verificationReport) -or
        (Test-Path -LiteralPath $verificationReportTemp) -or
        (Test-Path -LiteralPath $ready) -or
        (Test-Path -LiteralPath $readyTemp)) {
        throw 'evidence_collection_path_collision'
    }
    $null = New-Item -ItemType Directory -Path $partial
    $partialItem = Get-Item -LiteralPath $partial -Force
    $null = Assert-NonReparseDirectoryChain `
        -Path $partialItem.FullName `
        -ReasonPrefix 'evidence_partial'
    if (($partialItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'evidence_partial_reparse_point_forbidden'
    }
    return [pscustomobject]@{
        Root = $rootItem.FullName
        Partial = $partialItem.FullName
        Complete = $complete
        VerificationStage = $verificationStage
        VerificationCandidate = Join-Path $verificationStage ($CollectionId + '.complete')
        VerificationFailed = $verificationFailed
        VerificationReport = $verificationReport
        VerificationReportTemp = $verificationReportTemp
        Ready = $ready
        ReadyTemp = $readyTemp
    }
}

$script:ResolvedTools = Assert-LocalPreflight
if ($PreflightOnly) {
    Write-Output (
        'ANEB_D82_PREFLIGHT_OK schema=aneb-d82-collector-preflight-v1 ' +
        "profile_contract=$ProfileContract package=$PackageName evidence_mode=$EvidenceMode external_calls=0 evidence_writes=0"
    )
    return
}

if ([bool]$script:ResolvedTools.ToolingProvenance.source_dirty) {
    throw 'source_worktree_not_clean'
}

$script:WorkflowSucceeded = $false
$script:CleanupSucceeded = $false
$script:Published = $false
$script:ReadyPath = $null
$script:PrimaryFailure = $null
$script:AuditLock = $null
$script:Logcat = $null
$script:OriginalStayon = $null
$script:StayonMutationAttempted = $false
$script:StayonRestoreAttempted = $false
$script:StayonRestoreSucceeded = $false
$script:StayonChanged = $false
$script:AppLaunchAttempted = $false
$script:AppStarted = $false
$script:TargetStopAttempted = $false
$script:TargetStopSucceeded = $false
$script:NegativeProxy = $null
$script:NegativeProxyCompleted = $false
$script:NegativeProxyStopAttempted = $false
$script:NegativeProxyStopSucceeded = $false
$script:NegativeReversePreflightCaptured = $false
$script:NegativeReverseMutationAttempted = $false
$script:NegativeReverseAdded = $false
$script:NegativeReverseRemoveAttempted = $false
$script:NegativeReverseFinalCaptured = $false
$script:BusySentinelStartAttempted = $false
$script:BusySentinelStarted = $false
$script:BusySentinelVerified = $false
$script:BusySentinelRestoredAfterTarget = $false
$script:BusySentinelLost = $false
$script:BusySentinelComponent = $null
$script:BusySentinelEvidenceDirectory = $null
$script:BusySentinelReleaseAttempted = $false
$script:BusySentinelHomeSucceeded = $false
$script:LogcatStopAttempted = $false
$script:LogcatStopSucceeded = $false
$script:StartBarrierAttempted = $false
$script:EndBarrierAttempted = $false
$script:LockReleaseAttempted = $false
$script:RunId = $null
$script:LogcatMarkerNonce = [Guid]::NewGuid().ToString('N').ToLowerInvariant()
$script:StartBarrierId = [Guid]::NewGuid().ToString().ToLowerInvariant()
$script:EndBarrierId = [Guid]::NewGuid().ToString().ToLowerInvariant()
$collectionId = 'd82-token-quick-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' +
    [Guid]::NewGuid().ToString('N').ToLowerInvariant()
$paths = New-EvidenceStagingDirectory -CollectionId $collectionId
$PartialDirectory = [string]$paths.Partial
$CompleteDirectory = [string]$paths.Complete
$VerificationStageDirectory = [string]$paths.VerificationStage
$VerificationCandidateDirectory = [string]$paths.VerificationCandidate
$script:ExpectedApkSha256 = $null
$script:ExpectedSignerSha256 = $null
$script:CiCandidateReport = Initialize-CiCandidateEvidence `
    -EvidenceDirectory $PartialDirectory

Write-JsonNoBom -Path (Join-Path $PartialDirectory 'collector-plan.json') -Value ([ordered]@{
    schema = 'aneb-d82-collector-plan'
    schema_version = '1.1.0'
    execution_mode = $script:ExecutionMode
    created_at_utc = [DateTime]::UtcNow.ToString('o')
    collection_id = $collectionId
    profile_contract = $ProfileContract
    profile_contract_definition_sha256 = $script:ResolvedTools.ProfileContractDefinitionSha256
    tooling_provenance = $script:ResolvedTools.ToolingProvenance
    package_name = $PackageName
    version_name = $ExpectedVersionName
    version_code = $ExpectedVersionCode
    server_base = $ServerBase.TrimEnd('/')
    client_server_base = if ($EvidenceMode -ceq 'negative') {
        $NegativeClientServerBase
    } else {
        $ServerBase.TrimEnd('/')
    }
    negative_proxy_upstream_url = if ($EvidenceMode -ceq 'negative') {
        [string]$script:ResolvedTools.NegativeProxyUpstreamUrl
    } else {
        $null
    }
    negative_proxy_device_port = if ($EvidenceMode -ceq 'negative') {
        $NegativeProxyDevicePort
    } else {
        $null
    }
    remote_host = $script:ResolvedTools.BoundRemoteHost
    ssh_known_hosts_sha256 = [string]$script:ResolvedTools.ToolingProvenance.external_inputs.ssh_known_hosts_sha256
    adb_serial_sha256 = Get-Utf8StringSha256 -Value $AdbSerial
    device_policy_sha256 = [string]$script:ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256
    expected_server_version = $ExpectedServerVersion
    expected_server_binary_sha256 = $ExpectedServerBinarySha256.ToLowerInvariant()
    expected_signer_sha256 = $ExpectedSignerSha256.ToLowerInvariant()
    expected_apk_sha256 = $ExpectedApkSha256.ToLowerInvariant()
    candidate_source_commit = [string]$script:CiCandidateReport.source_commit
    candidate_workflow_run_id = [int64]$script:CiCandidateReport.workflow_run_id
    server_ca_sha256 = [string]$script:ResolvedTools.ServerCa.Sha256
    server_ca_thumbprint = [string]$script:ResolvedTools.ServerCa.Thumbprint
    android_build_tools_version = '35.0.0'
    transport = $Transport
    run_timeout_seconds = $RunTimeoutSeconds
    lock_ttl_seconds = $LockTtlSeconds
    adb_command_timeout_seconds = $AdbCommandTimeoutSeconds
    ssh_command_timeout_seconds = $SshCommandTimeoutSeconds
    tool_command_timeout_seconds = $ToolCommandTimeoutSeconds
    start_barrier_id = $script:StartBarrierId
    end_barrier_id = $script:EndBarrierId
})

try {
    $devicePreflight = Assert-LiveDevicePreflight -EvidenceDirectory $PartialDirectory
    $script:OriginalStayon = [string]$devicePreflight.stay_on_while_plugged_in
    Start-BusySentinel -EvidenceDirectory $PartialDirectory

    $lockNonce = [Guid]::NewGuid().ToString('N').ToLowerInvariant()
    $script:AuditLock = Start-PersistentAuditLock `
        -EvidenceDirectory $PartialDirectory `
        -Nonce $lockNonce

    $remoteSnapshot = Get-RemotePreStartSnapshot `
        -Lock $script:AuditLock `
        -EvidenceDirectory $PartialDirectory

    Assert-PersistentAuditLock -Lock $script:AuditLock -Stage 'identity_serverinfo'
    $identityHeaders = Join-Path $PartialDirectory 'identity-serverinfo.headers'
    $identityBody = Join-Path $PartialDirectory 'identity-serverinfo.json'
    $null = Invoke-ServerInfoOnce `
        -HeadersPath $identityHeaders `
        -BodyPath $identityBody `
        -Stage 'identity'
    $null = Write-PreStartReceipt `
        -Snapshot $remoteSnapshot `
        -Lock $script:AuditLock `
        -ServerInfoBodyPath $identityBody `
        -OutputPath (Join-Path $PartialDirectory 'pre-start-receipt.json')

    Enable-StayonForRun
    $script:Logcat = Start-LogcatCapture -EvidenceDirectory $PartialDirectory
    Write-LogcatCaptureMarker `
        -Logcat $script:Logcat `
        -MarkerNonce $script:LogcatMarkerNonce `
        -EvidenceDirectory $PartialDirectory

    Assert-PersistentAuditLock -Lock $script:AuditLock -Stage 'start_barrier'
    $null = Invoke-BarrierOnce `
        -Attempted ([ref]$script:StartBarrierAttempted) `
        -BarrierId $script:StartBarrierId `
        -Role 'window_start' `
        -HeadersPath (Join-Path $PartialDirectory 'start-barrier.headers') `
        -BodyPath (Join-Path $PartialDirectory 'start-barrier.json')

    Start-NegativeProxyAndReverse -EvidenceDirectory $PartialDirectory
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $PartialDirectory `
        -Stage 'before_target_handoff'
    $null = Start-TokenQuickRun
    $script:RunId = Wait-TokenQuickCompletion `
        -Logcat $script:Logcat `
        -MarkerNonce $script:LogcatMarkerNonce
    Wait-NegativeProxyCompletion -RunId $script:RunId
    Stop-TargetAppOnce
    Remove-NegativeAdbReverseOnce -EvidenceDirectory $PartialDirectory
    Stop-NegativeProxyOnce
    Restore-BusySentinelAfterTarget -EvidenceDirectory $PartialDirectory
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $PartialDirectory `
        -Stage 'before_end_barrier'

    Assert-PersistentAuditLock -Lock $script:AuditLock -Stage 'end_barrier'
    $null = Invoke-BarrierOnce `
        -Attempted ([ref]$script:EndBarrierAttempted) `
        -BarrierId $script:EndBarrierId `
        -Role 'window_end' `
        -HeadersPath (Join-Path $PartialDirectory 'end-barrier.headers') `
        -BodyPath (Join-Path $PartialDirectory 'end-barrier.json')
    Wait-ForEndBarrierAudit `
        -Lock $script:AuditLock `
        -JournalCursor $remoteSnapshot.JournalCursor `
        -EndBarrierId $script:EndBarrierId
    Assert-RemoteSnapshotStable `
        -Lock $script:AuditLock `
        -Snapshot $remoteSnapshot `
        -EvidenceDirectory $PartialDirectory
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $PartialDirectory `
        -Stage 'after_remote_snapshot'

    Stop-LogcatCaptureOnce
    Assert-NonEmptyFile -Path $script:Logcat.OutputPath -Label 'current-run app logcat'
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $PartialDirectory `
        -Stage 'before_room_freeze'
    Copy-FrozenRoomDatabase -EvidenceDirectory $PartialDirectory
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $PartialDirectory `
        -Stage 'after_room_freeze'
    $null = Invoke-ClientDbVerification `
        -EvidenceDirectory $PartialDirectory `
        -RunId $script:RunId
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $PartialDirectory `
        -Stage 'after_client_verifier'

    Export-LockedJournalOnce `
        -Lock $script:AuditLock `
        -JournalCursor $remoteSnapshot.JournalCursor `
        -OutputPath (Join-Path $PartialDirectory 'journal.raw.jsonl')
    Assert-PersistentAuditLock -Lock $script:AuditLock -Stage 'before_local_evidence_audit'
    $null = Invoke-EvidenceDerivationAndAudit `
        -EvidenceDirectory $PartialDirectory `
        -RunId $script:RunId `
        -StartBarrierId $script:StartBarrierId `
        -EndBarrierId $script:EndBarrierId
    $null = Assert-BusySentinelFocused `
        -EvidenceDirectory $PartialDirectory `
        -Stage 'workflow_complete'
    $script:WorkflowSucceeded = $true
} catch {
    $script:PrimaryFailure = $_.Exception.Message
} finally {
    try {
        Complete-CollectorCleanup -EvidenceDirectory $PartialDirectory
    } catch {
        $script:CleanupSucceeded = $false
        $cleanupFailure = "cleanup_orchestration_failure:$($_.Exception.Message)"
        if ([string]::IsNullOrWhiteSpace($script:PrimaryFailure)) {
            $script:PrimaryFailure = $cleanupFailure
        } else {
            $script:PrimaryFailure = $script:PrimaryFailure + ' | ' + $cleanupFailure
        }
    }

    $combinedPass = $script:WorkflowSucceeded -and $script:CleanupSucceeded
    Write-JsonNoBom -Path (Join-Path $PartialDirectory 'collector-status.json') -Value ([ordered]@{
        schema = 'aneb-d82-collector-status'
        schema_version = '1.0.0'
        completed_at_utc = [DateTime]::UtcNow.ToString('o')
        status = if ($combinedPass) { 'pass' } else { 'fail' }
        reason_code = if ($combinedPass) { 'ok' } else { 'collector_or_cleanup_failed' }
        failure = $script:PrimaryFailure
        workflow_succeeded = $script:WorkflowSucceeded
        cleanup_succeeded = $script:CleanupSucceeded
        collection_id = $collectionId
        run_id = $script:RunId
        start_barrier_id = $script:StartBarrierId
        end_barrier_id = $script:EndBarrierId
        partial_directory = $PartialDirectory
        complete_directory = $CompleteDirectory
    })

    if ($script:WorkflowSucceeded -and $script:CleanupSucceeded) {
        $verificationCandidateCreated = $false
        $verificationReportTempPath = $null
        $verificationReportPublished = $false
        try {
            $draftManifestPath = Write-EvidenceManifestDraft -EvidenceDirectory $PartialDirectory
            $finalManifestPath = Join-Path $PartialDirectory 'evidence-manifest.final.json'
            $null = Write-FinalEvidenceManifest `
                -EvidenceDirectory $PartialDirectory `
                -DraftPath $draftManifestPath `
                -OutputPath $finalManifestPath `
                -CollectionId $collectionId `
                -RunId $script:RunId `
                -StartBarrierId $script:StartBarrierId `
                -EndBarrierId $script:EndBarrierId
            $finalManifestSha256 = (Get-FileHash -LiteralPath $finalManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
            Write-NewTextNoBom -Path (Join-Path $PartialDirectory 'COMPLETE') -Text (
                "ANEB_D82_COMPLETE collection_id=$collectionId run_id=$($script:RunId) " +
                "manifest=evidence-manifest.final.json manifest_sha256=$finalManifestSha256`n"
            )
            $null = New-Item -ItemType Directory -Path $VerificationStageDirectory
            $null = Assert-NonReparseDirectoryChain `
                -Path $VerificationStageDirectory `
                -ReasonPrefix 'verification_stage'
            $stageItem = Get-Item -LiteralPath $VerificationStageDirectory -Force
            if (($stageItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'verification_stage_reparse_point_forbidden'
            }
            Move-Item -LiteralPath $PartialDirectory -Destination $VerificationCandidateDirectory
            $null = Assert-NonReparseDirectoryChain `
                -Path $VerificationCandidateDirectory `
                -ReasonPrefix 'verification_candidate'
            $verificationCandidateCreated = $true
            $verificationResult = Invoke-PublishedBundleVerification `
                -CompleteDirectory $VerificationCandidateDirectory `
                -CollectionId $collectionId `
                -RunId $script:RunId `
                -FinalManifestSha256 $finalManifestSha256 `
                -SourceCommit ([string]$script:ResolvedTools.ToolingProvenance.source_commit) `
                -ServerVersion $ExpectedServerVersion `
                -ServerBinarySha256 $ExpectedServerBinarySha256.ToLowerInvariant() `
                -ApkSha256 $ExpectedApkSha256.ToLowerInvariant() `
                -ExpectedRemoteHost ([string]$script:ResolvedTools.BoundRemoteHost) `
                -ExpectedSshKnownHostsSha256 ([string]$script:ResolvedTools.ToolingProvenance.external_inputs.ssh_known_hosts_sha256) `
                -ExpectedPackageName $PackageName `
                -ExpectedVersionName $ExpectedVersionName `
                -ExpectedVersionCode $ExpectedVersionCode `
                -ExpectedSignerSha256 $ExpectedSignerSha256.ToLowerInvariant() `
                -ExpectedDevicePolicySha256 ([string]$script:ResolvedTools.ToolingProvenance.external_inputs.device_policy_sha256) `
                -ExpectedAdbSerialSha256 (Get-Utf8StringSha256 -Value $AdbSerial) `
                -ExpectedAttestationBundleSha256 ([string]$script:CiCandidateReport.files.attestation_bundle_sha256) `
                -ExpectedGhSha256 ([string]$script:ResolvedTools.GhSha256) `
                -ExpectedExecutionMode $script:ExecutionMode `
                -ExpectedRunTimeoutSeconds $RunTimeoutSeconds `
                -ExpectedLockTtlSeconds $LockTtlSeconds `
                -EvidenceRootPath ([string]$paths.Root) `
                -PublishTarget $CompleteDirectory
            $verificationReportTempPath = [string]$verificationResult.TempPath
            $null = Assert-NonReparseDirectoryChain `
                -Path ([string]$paths.Root) `
                -ReasonPrefix 'evidence_root'
            $null = Assert-NonReparseDirectoryChain `
                -Path $CompleteDirectory `
                -ReasonPrefix 'evidence_complete'
            Remove-Item -LiteralPath $VerificationStageDirectory -Force
            [IO.File]::Move(
                [string]$verificationResult.TempPath,
                [string]$verificationResult.Path
            )
            $verificationReportTempPath = $null
            $verificationReportPublished = $true
            $script:ReadyPath = Publish-EvidenceReleaseReady `
                -EvidenceRootPath ([string]$paths.Root) `
                -CollectionId $collectionId `
                -RunId $script:RunId `
                -ExecutionMode $script:ExecutionMode `
                -CompleteDirectory $CompleteDirectory `
                -FinalManifestSha256 $finalManifestSha256 `
                -VerificationReportPath ([string]$verificationResult.Path)
            $script:Published = $true
        } catch {
            $script:PrimaryFailure = "atomic_publication_failed:$($_.Exception.Message)"
            if (-not [string]::IsNullOrWhiteSpace([string]$verificationReportTempPath) -and
                (Test-Path -LiteralPath $verificationReportTempPath -PathType Leaf)) {
                Remove-Item -LiteralPath $verificationReportTempPath -Force
            }
            if ($verificationReportPublished -and
                (Test-Path -LiteralPath ([string]$paths.VerificationReport) -PathType Leaf)) {
                Remove-Item -LiteralPath ([string]$paths.VerificationReport) -Force
            }
            $demotionSource = $null
            if ($verificationCandidateCreated -and
                (Test-Path -LiteralPath $VerificationCandidateDirectory -PathType Container)) {
                $demotionSource = $VerificationCandidateDirectory
            } elseif (Test-Path -LiteralPath $CompleteDirectory -PathType Container) {
                $demotionSource = $CompleteDirectory
            }
            if ($null -ne $demotionSource) {
                $completeMarker = Join-Path ([string]$demotionSource) 'COMPLETE'
                if (Test-Path -LiteralPath $completeMarker -PathType Leaf) {
                    Remove-Item -LiteralPath $completeMarker -Force
                }
                $verificationFailedDirectory = [string]$paths.VerificationFailed
                if (Test-Path -LiteralPath $verificationFailedDirectory) {
                    throw 'verification_failed_directory_collision'
                }
                Move-Item -LiteralPath ([string]$demotionSource) -Destination $verificationFailedDirectory
                $PartialDirectory = $verificationFailedDirectory
                if (Test-Path -LiteralPath $VerificationStageDirectory -PathType Container) {
                    Remove-Item -LiteralPath $VerificationStageDirectory -Force
                }
                Write-NewTextNoBom `
                    -Path (Join-Path $PartialDirectory 'VERIFICATION_FAILED') `
                    -Text ("ANEB_D82_VERIFICATION_FAILED failure=$($script:PrimaryFailure)`n")
            } else {
                if (Test-Path -LiteralPath $VerificationStageDirectory -PathType Container) {
                    Remove-Item -LiteralPath $VerificationStageDirectory -Force
                }
                foreach ($candidate in @(
                    'COMPLETE', 'evidence-manifest.final.json', 'evidence-inventory.draft.json'
                )) {
                    $candidatePath = Join-Path $PartialDirectory $candidate
                    if (Test-Path -LiteralPath $candidatePath -PathType Leaf) {
                        Remove-Item -LiteralPath $candidatePath -Force
                    }
                }
                if (Test-Path -LiteralPath $PartialDirectory -PathType Container) {
                    Write-JsonNoBom -Path (Join-Path $PartialDirectory 'collector-status.json') -Value ([ordered]@{
                        schema = 'aneb-d82-collector-status'
                        schema_version = '1.0.0'
                        completed_at_utc = [DateTime]::UtcNow.ToString('o')
                        status = 'fail'
                        reason_code = 'atomic_publication_failed'
                        failure = $script:PrimaryFailure
                        workflow_succeeded = $script:WorkflowSucceeded
                        cleanup_succeeded = $script:CleanupSucceeded
                        collection_id = $collectionId
                        run_id = $script:RunId
                        start_barrier_id = $script:StartBarrierId
                        end_barrier_id = $script:EndBarrierId
                    })
                }
            }
        }
    }
}

if ($script:Published) {
    Write-Output (
        'ANEB_D82_COLLECTOR_COMPLETE ' +
        "run_id=$($script:RunId) evidence=$CompleteDirectory ready=$($script:ReadyPath)"
    )
    return
}

[Console]::Error.WriteLine(
    "ANEB_D82_COLLECTOR_FAILED failure=$($script:PrimaryFailure) evidence=$PartialDirectory"
)
exit 1
