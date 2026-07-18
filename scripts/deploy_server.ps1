# deploy_server.ps1 -- guarded upgrade of aneb-server on E-01.
# ASCII-only. PowerShell 5.1 compatible.
#
# Safety sequence:
#   1. Validate the catalog, published Token Quick bundle, and all Go server tests locally.
#   2. Cross-compile into a unique local temporary directory.
#   3. Upload into a unique remote staging directory.
#   4. Start the candidate on a loopback-only temporary port and validate its receipt.
#   5. Snapshot the bounded live surface, replace it, restart, and smoke-test.
#   6. Restore the complete snapshot automatically if restart or smoke-test fails.
#
# This script changes only ANEB-owned files and the aneb-server systemd unit. It does
# not change host networking, co-tenant services, or shared infrastructure controls.

[CmdletBinding()]
param(
    [switch]$LocalValidationOnly,
    [switch]$EnableIpCertificateReplacement,
    [string]$ExpectedIpCertificateSha256,
    [string]$ExpectedIpPrivateKeySha256
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'server'
$ProfilesDir = Join-Path $RepoRoot 'profiles'
$TokenQuickBundle = Join-Path $ProfilesDir 'published\token_multimodal_quick'
$TokenQuickProfile = Join-Path $TokenQuickBundle 'profile.json'
$TokenQuickRuntimePlan = Join-Path $TokenQuickBundle 'runtime_plan.json'
$TokenQuickManifest = Join-Path $TokenQuickBundle 'manifest.sha256'
$Unit = Join-Path $ServerDir 'aneb-server.service'
$VerifyCatalog = Join-Path $PSScriptRoot 'verify_spec_catalog.py'
$BuildCandidate = Join-Path $PSScriptRoot 'build_server_candidate.py'

$RootProfileFiles = @(
    (Join-Path $ProfilesDir 'basic_network.json'),
    (Join-Path $ProfilesDir 's1_chat.json'),
    (Join-Path $ProfilesDir 's2_coding_agent.json'),
    (Join-Path $ProfilesDir 's3_multimodal.json')
)

$IpCertDir = Join-Path $ServerDir 'tls\ip'
$IpCert = Join-Path $IpCertDir 'aneb_ip_cert.pem'
$IpKey = Join-Path $IpCertDir 'aneb_ip_key.pem'
$script:HaveIpCert = $false
$script:ExpectedSourceCommit = $null

$SshKey = Join-Path $env:USERPROFILE '.ssh\aneb_e01'
$Remote = 'root@120.79.148.0'
$SshOpts = @(
    '-i', $SshKey,
    '-o', 'BatchMode=yes',
    '-o', 'ConnectTimeout=10',
    '-o', 'ServerAliveInterval=10',
    '-o', 'ServerAliveCountMax=3'
)

function Assert-RequiredFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required $Label is missing: $Path"
    }
    if ((Get-Item -LiteralPath $Path).Length -le 0) {
        throw "Required $Label is empty: $Path"
    }
}

function Get-RepositoryHead {
    $gitCommand = Get-Command git -ErrorAction Stop
    $head = @(& $gitCommand.Source -C $RepoRoot rev-parse --verify 'HEAD^{commit}' 2>$null)
    if ($LASTEXITCODE -ne 0 -or $head.Count -ne 1 -or
        [string]$head[0] -notmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') {
        throw 'Unable to resolve one canonical source commit.'
    }
    return [string]$head[0]
}

function Assert-RepositoryAtCommit {
    param(
        [Parameter(Mandatory = $true)][string]$ExpectedCommit,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    $actualCommit = Get-RepositoryHead
    if ($actualCommit -cne $ExpectedCommit) {
        throw "Repository commit changed $Stage."
    }
    $gitCommand = Get-Command git -ErrorAction Stop
    $status = @(& $gitCommand.Source -C $RepoRoot status --porcelain=v1 `
        --untracked-files=all --ignore-submodules=none 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "Repository cleanliness check failed $Stage."
    }
    if ($status.Count -ne 0) {
        throw "Repository must be clean $Stage."
    }
}

function Write-ArtifactDigestManifest {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Artifacts,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($entry in ($Artifacts.GetEnumerator() | Sort-Object Key)) {
        $name = [string]$entry.Key
        $path = [string]$entry.Value
        if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*(/[A-Za-z0-9][A-Za-z0-9_.-]*)*$' -or
            $name.Split('/') -contains '..') {
            throw "Unsafe artifact manifest name: $name"
        }
        Assert-RequiredFile -Path $path -Label "deployment artifact $name"
        $digest = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $lines.Add(("{0}  {1}" -f $digest, $name))
    }
    if ($lines.Count -le 0) {
        throw 'Artifact digest manifest cannot be empty.'
    }
    [System.IO.File]::WriteAllText(
        $OutputPath,
        (($lines -join "`n") + "`n"),
        (New-Object System.Text.UTF8Encoding($false))
    )
}

function Invoke-LocalSafetyGates {
    Write-Host '== [1/5] local safety gates =='

    Assert-RequiredFile -Path $VerifyCatalog -Label 'catalog verifier'
    Assert-RequiredFile -Path $BuildCandidate -Label 'server candidate provenance builder'
    Assert-RequiredFile -Path $Unit -Label 'systemd unit'
    Assert-RequiredFile -Path $TokenQuickProfile -Label 'Token Quick profile'
    Assert-RequiredFile -Path $TokenQuickRuntimePlan -Label 'Token Quick runtime plan'
    Assert-RequiredFile -Path $TokenQuickManifest -Label 'Token Quick manifest'
    foreach ($profilePath in $RootProfileFiles) {
        Assert-RequiredFile -Path $profilePath -Label 'root profile'
    }
    $ipCertPresent = Test-Path -LiteralPath $IpCert -PathType Leaf
    $ipKeyPresent = Test-Path -LiteralPath $IpKey -PathType Leaf
    if ($ipCertPresent -xor $ipKeyPresent) {
        throw "IP-SAN certificate and key must either both exist or both be absent: $IpCertDir"
    }
    $script:HaveIpCert = $ipCertPresent -and $ipKeyPresent
    if ($script:HaveIpCert -and -not $EnableIpCertificateReplacement) {
        throw 'IP-SAN certificate files are present, but replacement was not explicitly enabled.'
    }
    if ($EnableIpCertificateReplacement -and -not $script:HaveIpCert) {
        throw 'IP-SAN certificate replacement was enabled, but the certificate/key pair is absent.'
    }
    if ($script:HaveIpCert) {
        if ($ExpectedIpCertificateSha256 -notmatch '^[0-9a-fA-F]{64}$' -or
            $ExpectedIpPrivateKeySha256 -notmatch '^[0-9a-fA-F]{64}$') {
            throw 'Explicit 64-hex SHA-256 pins are required for both the IP certificate and private key.'
        }
        $actualKeySha = (Get-FileHash -LiteralPath $IpKey -Algorithm SHA256).Hash
        if ($actualKeySha -ine $ExpectedIpPrivateKeySha256) {
            throw 'IP-SAN private-key replacement SHA-256 pin mismatch.'
        }
    }

    $script:ExpectedSourceCommit = Get-RepositoryHead
    Assert-RepositoryAtCommit -ExpectedCommit $script:ExpectedSourceCommit -Stage 'before local safety gates'

    $pythonCommand = Get-Command python -ErrorAction Stop
    & $pythonCommand.Source $VerifyCatalog
    if ($LASTEXITCODE -ne 0) {
        throw "Spec-catalog verification failed with exit code $LASTEXITCODE"
    }

    $goCommand = Get-Command go -ErrorAction Stop
    $goEnvironmentNames = @(
        'CGO_ENABLED', 'GOARCH', 'GOAMD64', 'GOENV', 'GOEXPERIMENT',
        'GOFIPS140', 'GOFLAGS', 'GOOS', 'GOTOOLCHAIN', 'GOWORK'
    )
    $previousGoEnvironment = @{}
    foreach ($name in $goEnvironmentNames) {
        $previousGoEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    Push-Location $ServerDir
    try {
        $env:CGO_ENABLED = '0'
        $env:GOENV = 'off'
        $env:GOEXPERIMENT = ''
        $env:GOFIPS140 = 'off'
        $env:GOFLAGS = ''
        $env:GOTOOLCHAIN = 'local'
        $env:GOWORK = 'off'
        foreach ($name in @('GOOS', 'GOARCH', 'GOAMD64')) {
            Remove-Item -LiteralPath ("Env:{0}" -f $name) -ErrorAction SilentlyContinue
        }
        & $goCommand.Source test -count=1 ./...
        if ($LASTEXITCODE -ne 0) {
            throw "Go server tests failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
        foreach ($name in $goEnvironmentNames) {
            $previous = $previousGoEnvironment[$name]
            if ($null -eq $previous) {
                Remove-Item -LiteralPath ("Env:{0}" -f $name) -ErrorAction SilentlyContinue
            } else {
                Set-Item -LiteralPath ("Env:{0}" -f $name) -Value $previous
            }
        }
    }

    Assert-RepositoryAtCommit -ExpectedCommit $script:ExpectedSourceCommit -Stage 'after local safety gates'

    Write-Host 'local_safety_gates=pass catalog=verified server_tests=pass quick_bundle=complete'
}

function Copy-ToRemote {
    param(
        [Parameter(Mandatory = $true)][string]$LocalPath,
        [Parameter(Mandatory = $true)][string]$RemotePath,
        [Parameter(Mandatory = $true)][string]$Label
    )
    & scp @SshOpts $LocalPath ("{0}:{1}" -f $Remote, $RemotePath)
    if ($LASTEXITCODE -ne 0) {
        throw "scp failed for $Label with exit code $LASTEXITCODE"
    }
}

# No ssh/scp call is made before this function returns successfully.
Invoke-LocalSafetyGates

$DeploymentId = (Get-Date -Format 'yyyyMMddHHmmss') + '-' + [guid]::NewGuid().ToString('N')
$LocalStage = Join-Path $env:TEMP ("aneb-deploy-" + $DeploymentId)
$Bin = Join-Path $LocalStage 'aneb-server-linux'
$BuildProvenance = Join-Path $LocalStage 'build-provenance.json'
$GoBuildInfo = Join-Path $LocalStage 'go-buildinfo.json'
$ArtifactSnapshotRoot = Join-Path $LocalStage 'upload-artifacts'
$ArtifactManifest = Join-Path $LocalStage 'artifact-manifest.sha256'
$RemoteStage = "/tmp/aneb-deploy-$DeploymentId"
$RemoteStageCreated = $false
$RemoteCleanupWatchdogArmed = $false

if (-not ($DeploymentId -match '^[0-9]{14}-[0-9a-f]{32}$')) {
    throw "Unsafe deployment identifier: $DeploymentId"
}

New-Item -ItemType Directory -Path $LocalStage | Out-Null
try {
    Write-Host '== [2/5] build commit-bound linux/amd64 candidate =='
    $CandidateArtifacts = @{
        'aneb-server.service' = $Unit
        'root-profiles/basic_network.json' = $RootProfileFiles[0]
        'root-profiles/s1_chat.json' = $RootProfileFiles[1]
        'root-profiles/s2_coding_agent.json' = $RootProfileFiles[2]
        'root-profiles/s3_multimodal.json' = $RootProfileFiles[3]
        'execution-profiles/token_multimodal_quick/profile.json' = $TokenQuickProfile
        'execution-profiles/token_multimodal_quick/runtime_plan.json' = $TokenQuickRuntimePlan
        'execution-profiles/token_multimodal_quick/manifest.sha256' = $TokenQuickManifest
    }
    if ($script:HaveIpCert) {
        $CandidateArtifacts['tls/ip-cert.pem'] = $IpCert
    }
    $pythonCommand = Get-Command python -ErrorAction Stop
    $candidateArguments = New-Object System.Collections.Generic.List[string]
    foreach ($argument in @(
        $BuildCandidate,
        '--repo-root', $RepoRoot,
        '--expected-commit', $script:ExpectedSourceCommit,
        '--server-dir', $ServerDir,
        '--output-bin', $Bin,
        '--provenance', $BuildProvenance,
        '--buildinfo', $GoBuildInfo,
        '--artifact-snapshot-root', $ArtifactSnapshotRoot
    )) {
        $candidateArguments.Add([string]$argument)
    }
    foreach ($entry in ($CandidateArtifacts.GetEnumerator() | Sort-Object Key)) {
        $candidateArguments.Add('--artifact')
        $candidateArguments.Add(("{0}={1}" -f $entry.Key, $entry.Value))
    }
    & $pythonCommand.Source @candidateArguments
    if ($LASTEXITCODE -ne 0) {
        throw "commit-bound candidate build failed with exit code $LASTEXITCODE"
    }
    Assert-RequiredFile -Path $Bin -Label 'linux server binary'
    Assert-RequiredFile -Path $BuildProvenance -Label 'build provenance'
    Assert-RequiredFile -Path $GoBuildInfo -Label 'Go build info'

    $CandidateUploadArtifacts = @{}
    foreach ($entry in $CandidateArtifacts.GetEnumerator()) {
        $snapshotRelativePath = ([string]$entry.Key).Replace('/', '\')
        $snapshotPath = Join-Path $ArtifactSnapshotRoot $snapshotRelativePath
        Assert-RequiredFile -Path $snapshotPath -Label ("candidate snapshot {0}" -f $entry.Key)
        $CandidateUploadArtifacts[$entry.Key] = $snapshotPath
    }
    if ($script:HaveIpCert) {
        $actualCertSha = (Get-FileHash -LiteralPath $CandidateUploadArtifacts['tls/ip-cert.pem'] -Algorithm SHA256).Hash
        if ($actualCertSha -ine $ExpectedIpCertificateSha256) {
            throw 'IP-SAN certificate replacement SHA-256 pin mismatch.'
        }
    }

    $ArtifactPayloads = @{}
    foreach ($entry in $CandidateUploadArtifacts.GetEnumerator()) {
        $ArtifactPayloads[$entry.Key] = $entry.Value
    }
    $ArtifactPayloads['aneb-server-linux'] = $Bin
    $ArtifactPayloads['build-provenance.json'] = $BuildProvenance
    $ArtifactPayloads['go-buildinfo.json'] = $GoBuildInfo
    Write-ArtifactDigestManifest -Artifacts $ArtifactPayloads -OutputPath $ArtifactManifest
    Assert-RequiredFile -Path $ArtifactManifest -Label 'artifact digest manifest'
    $ArtifactManifestSha = (Get-FileHash -LiteralPath $ArtifactManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host ("built={0} bytes={1} artifact_manifest_sha256={2}" -f $Bin, (Get-Item -LiteralPath $Bin).Length, $ArtifactManifestSha)

    if ($LocalValidationOnly) {
        Write-Host 'LOCAL_VALIDATION_ONLY_OK'
        return
    }

    Assert-RequiredFile -Path $SshKey -Label 'E-01 SSH private key'

    Write-Host '== [3/5] create isolated remote staging directory =='
    $WatchdogUnit = "aneb-deploy-expire-$DeploymentId"
    $createStage = "set -Eeuo pipefail; umask 077; install -d -m 0700 '$RemoteStage' '$RemoteStage/root-profiles' '$RemoteStage/execution-profiles/token_multimodal_quick' '$RemoteStage/tls'"
    & ssh @SshOpts $Remote $createStage
    if ($LASTEXITCODE -ne 0) {
        throw "remote staging creation failed with exit code $LASTEXITCODE"
    }
    $RemoteStageCreated = $true

    $armWatchdog = @(
        'set -Eeuo pipefail',
        "test -d '$RemoteStage'",
        "systemd-run --quiet --collect --unit '$WatchdogUnit' --on-active=30m /bin/rm -rf -- '$RemoteStage'",
        "systemctl is-active --quiet '$WatchdogUnit.timer'",
        ": > '$RemoteStage/.cleanup-watchdog-armed'"
    ) -join '; '
    & ssh @SshOpts $Remote $armWatchdog
    if ($LASTEXITCODE -ne 0) {
        throw "remote staging cleanup watchdog failed with exit code $LASTEXITCODE"
    }
    $RemoteCleanupWatchdogArmed = $true

    Write-Host '== [4/5] upload candidate into staging =='
    Copy-ToRemote -LocalPath $Bin -RemotePath "$RemoteStage/aneb-server-linux" -Label 'server binary'
    Copy-ToRemote -LocalPath $BuildProvenance -RemotePath "$RemoteStage/build-provenance.json" -Label 'build provenance'
    Copy-ToRemote -LocalPath $GoBuildInfo -RemotePath "$RemoteStage/go-buildinfo.json" -Label 'Go build info'
    Copy-ToRemote -LocalPath $ArtifactManifest -RemotePath "$RemoteStage/artifact-manifest.sha256" -Label 'artifact digest manifest'
    foreach ($entry in ($CandidateUploadArtifacts.GetEnumerator() | Sort-Object Key)) {
        $logicalPath = [string]$entry.Key
        Copy-ToRemote -LocalPath ([string]$entry.Value) -RemotePath "$RemoteStage/$logicalPath" -Label "candidate snapshot $logicalPath"
    }

    $HaveIpCert = $script:HaveIpCert

    $remoteScript = @'
#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
umask 077

DEPLOY_ID="${1:?deployment id required}"
SHIP_IP_CERT="${2:?IP certificate marker required}"
EXPECTED_IP_CERT_SHA="${3:?IP certificate SHA-256 marker required}"
EXPECTED_IP_KEY_SHA="${4:?IP private-key SHA-256 marker required}"
EXPECTED_ARTIFACT_MANIFEST_SHA="${5:?artifact manifest SHA-256 required}"
if [[ ! "$DEPLOY_ID" =~ ^[0-9]{14}-[0-9a-f]{32}$ ]]; then
    echo "unsafe deployment id" >&2
    exit 2
fi
if [[ "$SHIP_IP_CERT" != "0" && "$SHIP_IP_CERT" != "1" ]]; then
    echo "invalid IP certificate marker" >&2
    exit 2
fi
if [[ "$SHIP_IP_CERT" == "1" ]]; then
    [[ "$EXPECTED_IP_CERT_SHA" =~ ^[0-9a-f]{64}$ ]] || {
        echo 'invalid expected IP certificate SHA-256' >&2
        exit 2
    }
    [[ "$EXPECTED_IP_KEY_SHA" =~ ^[0-9a-f]{64}$ ]] || {
        echo 'invalid expected IP private-key SHA-256' >&2
        exit 2
    }
elif [[ "$EXPECTED_IP_CERT_SHA" != "none" || "$EXPECTED_IP_KEY_SHA" != "none" ]]; then
    echo 'unexpected IP certificate pins without replacement marker' >&2
    exit 2
fi
[[ "$EXPECTED_ARTIFACT_MANIFEST_SHA" =~ ^[0-9a-f]{64}$ ]] || {
    echo 'invalid expected artifact manifest SHA-256' >&2
    exit 2
}

STAGE="/tmp/aneb-deploy-$DEPLOY_ID"
BACKUP_ROOT="/opt/aneb/backups"
BACKUP="$BACKUP_ROOT/aneb-deploy-$DEPLOY_ID"
EVIDENCE_ROOT="/var/lib/aneb-deploy-evidence"
EVIDENCE="$EVIDENCE_ROOT/aneb-deploy-$DEPLOY_ID"
WATCHDOG_UNIT="aneb-deploy-expire-$DEPLOY_ID"
WATCHDOG_TIMER="$WATCHDOG_UNIT.timer"
WATCHDOG_SERVICE="$WATCHDOG_UNIT.service"
STAGE_PID=""
LIVE_TOUCHED=0
ROLLBACK_FAILED=0
DEPLOY_RESULT="failed"
FINAL_EVIDENCE_COMMITTED=0
DEPLOY_SUCCESS_MESSAGE=""
STAGED_BINARY_SHA=""
LIVE_BINARY_SHA=""
STAGED_RECEIPT_SHA=""
LIVE_RECEIPT_SHA=""
BASE_BINARY_SHA=""
BASE_ROOT_PROFILES_SHA=""
BASE_QUICK_BUNDLE_SHA=""
BASE_SERVICE_UNIT_SHA=""
BASE_IP_CERT_SHA=""
BASE_IP_KEY_SHA=""
BASE_DOCKER_FIREWALL_SHA=""
BASE_ETH0_QDISC_SHA=""
BASE_IPTABLES_V4_FIREWALL_SHA=""
BASE_IPTABLES_V6_FIREWALL_SHA=""
BASE_NFT_FIREWALL_SHA=""
BASE_FIREWALL_SHA=""
DEPLOY_LOCK_PATH="/run/lock/aneb-deploy.lock"

export LC_ALL=C

acquire_deploy_lock() {
    command -v flock >/dev/null 2>&1 || {
        echo 'DEPLOY_LOCK_UNAVAILABLE exit=74' >&2
        return 74
    }
    exec 9>"$DEPLOY_LOCK_PATH" || {
        echo 'DEPLOY_LOCK_OPEN_FAILED exit=74' >&2
        return 74
    }
    if ! flock -n 9; then
        echo "DEPLOY_LOCK_BUSY path=$DEPLOY_LOCK_PATH exit=75" >&2
        return 75
    fi
    echo "DEPLOY_LOCK_ACQUIRED path=$DEPLOY_LOCK_PATH"
}

# One kernel lock covers preflight evidence, backup, live replacement, rollback,
# final evidence publication, and cleanup. A second deploy fails before any of
# those shared surfaces can be changed.
set +e
acquire_deploy_lock
DEPLOY_LOCK_RC=$?
set -e
if [[ $DEPLOY_LOCK_RC -ne 0 ]]; then
    rm -f -- "$STAGE/tls/ip-key.pem"
    rm -rf -- "$STAGE"
    systemctl stop "$WATCHDOG_TIMER" "$WATCHDOG_SERVICE" >/dev/null 2>&1 || true
    exit "$DEPLOY_LOCK_RC"
fi

cancel_cleanup_watchdog() {
    systemctl stop "$WATCHDOG_TIMER" "$WATCHDOG_SERVICE" >/dev/null 2>&1 || true
    systemctl reset-failed "$WATCHDOG_TIMER" "$WATCHDOG_SERVICE" >/dev/null 2>&1 || true
}

early_cleanup() {
    local rc=$?
    trap - EXIT
    trap '' HUP INT TERM
    set +e
    rm -f -- "$STAGE/tls/ip-key.pem"
    if rm -rf -- "$STAGE"; then
        cancel_cleanup_watchdog
    else
        echo 'EARLY_STAGE_CLEANUP_FAILED watchdog_retained=1' >&2
        if [[ $rc -eq 0 ]]; then
            rc=99
        fi
    fi
    exit "$rc"
}
trap early_cleanup EXIT INT TERM HUP
test -f "$STAGE/.cleanup-watchdog-armed"
systemctl is-active --quiet "$WATCHDOG_TIMER"
if [[ "$SHIP_IP_CERT" == "1" ]]; then
    test -f "$STAGE/.key-transfer-authorized"
fi
: > "$STAGE/.remote-trap-ready"

stop_staged_server() {
    if [[ -n "$STAGE_PID" ]]; then
        kill -TERM -- "-$STAGE_PID" >/dev/null 2>&1 || \
            kill "$STAGE_PID" >/dev/null 2>&1 || true
        wait "$STAGE_PID" >/dev/null 2>&1 || true
        STAGE_PID=""
    fi
}

hash_stream() {
    python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'
}

file_sha256() {
    python3 - "$1" <<'PY'
import hashlib
from pathlib import Path
import sys

path = Path(sys.argv[1])
digest = hashlib.sha256()
with path.open('rb') as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b''):
        digest.update(chunk)
print(digest.hexdigest())
PY
}

validate_uploaded_artifacts() {
    python3 - \
        "$STAGE" "$STAGE/artifact-manifest.sha256" \
        "$EXPECTED_ARTIFACT_MANIFEST_SHA" "$SHIP_IP_CERT" <<'PY'
import hashlib
from pathlib import Path
import re
import stat
import sys

stage = Path(sys.argv[1])
manifest = Path(sys.argv[2])
expected_manifest_sha = sys.argv[3]
ship_ip_cert = sys.argv[4]
if ship_ip_cert not in {'0', '1'}:
    raise SystemExit('invalid certificate marker for artifact validation')
if stage.is_symlink() or not stage.is_dir():
    raise SystemExit('unsafe staging root')
stage = stage.resolve()
if manifest.is_symlink() or not manifest.is_file():
    raise SystemExit('artifact manifest must be a regular file')
manifest_bytes = manifest.read_bytes()
if len(manifest_bytes) > 262144:
    raise SystemExit('artifact manifest exceeds size bound')
if hashlib.sha256(manifest_bytes).hexdigest() != expected_manifest_sha:
    raise SystemExit('artifact manifest pin mismatch')

expected = {
    'aneb-server-linux',
    'aneb-server.service',
    'build-provenance.json',
    'go-buildinfo.json',
    'root-profiles/basic_network.json',
    'root-profiles/s1_chat.json',
    'root-profiles/s2_coding_agent.json',
    'root-profiles/s3_multimodal.json',
    'execution-profiles/token_multimodal_quick/profile.json',
    'execution-profiles/token_multimodal_quick/runtime_plan.json',
    'execution-profiles/token_multimodal_quick/manifest.sha256',
}
if ship_ip_cert == '1':
    expected.add('tls/ip-cert.pem')

line_pattern = re.compile(
    r'([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*)'
)
entries = {}
try:
    lines = manifest_bytes.decode('ascii').splitlines()
except UnicodeDecodeError as error:
    raise SystemExit(f'artifact manifest must be ASCII: {error}') from error
for line in lines:
    match = line_pattern.fullmatch(line)
    if match is None:
        raise SystemExit(f'invalid artifact manifest line: {line!r}')
    digest, relative = match.groups()
    if relative in entries:
        raise SystemExit(f'duplicate artifact manifest path: {relative}')
    entries[relative] = digest
if set(entries) != expected:
    raise SystemExit(
        f'artifact manifest coverage mismatch: actual={set(entries)!r} expected={expected!r}'
    )

for relative, expected_sha in sorted(entries.items()):
    path = stage.joinpath(*relative.split('/'))
    metadata = path.lstat()
    if path.is_symlink() or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(f'artifact must be a regular file: {relative}')
    resolved = path.resolve()
    try:
        resolved.relative_to(stage)
    except ValueError as error:
        raise SystemExit(f'artifact escapes staging root: {relative}') from error
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected_sha:
        raise SystemExit(f'artifact digest mismatch: {relative}')
print(f'uploaded_artifacts=verified count={len(entries)} manifest_sha256={expected_manifest_sha}')
PY
}

validate_live_artifacts() {
    python3 - \
        "$STAGE" "$SHIP_IP_CERT" "$EXPECTED_IP_KEY_SHA" \
        "$STAGE/live-artifact-manifest.sha256" <<'PY'
import hashlib
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

stage = Path(sys.argv[1]).resolve()
ship_ip_cert = sys.argv[2]
expected_key_sha = sys.argv[3]
output = Path(sys.argv[4])
line_pattern = re.compile(
    r'([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*)'
)
entries = {}
for line in (stage / 'artifact-manifest.sha256').read_text(encoding='ascii').splitlines():
    match = line_pattern.fullmatch(line)
    if match is None or match.group(2) in entries:
        raise SystemExit('invalid staged artifact manifest during live comparison')
    entries[match.group(2)] = match.group(1)

live_paths = {
    'aneb-server-linux': Path('/opt/aneb/bin/aneb-server'),
    'aneb-server.service': Path('/etc/systemd/system/aneb-server.service'),
    'root-profiles/basic_network.json': Path('/opt/aneb/profiles/basic_network.json'),
    'root-profiles/s1_chat.json': Path('/opt/aneb/profiles/s1_chat.json'),
    'root-profiles/s2_coding_agent.json': Path('/opt/aneb/profiles/s2_coding_agent.json'),
    'root-profiles/s3_multimodal.json': Path('/opt/aneb/profiles/s3_multimodal.json'),
    'execution-profiles/token_multimodal_quick/profile.json': Path(
        '/opt/aneb/execution-profiles/token_multimodal_quick/profile.json'
    ),
    'execution-profiles/token_multimodal_quick/runtime_plan.json': Path(
        '/opt/aneb/execution-profiles/token_multimodal_quick/runtime_plan.json'
    ),
    'execution-profiles/token_multimodal_quick/manifest.sha256': Path(
        '/opt/aneb/execution-profiles/token_multimodal_quick/manifest.sha256'
    ),
}
if ship_ip_cert == '1':
    live_paths['tls/ip-cert.pem'] = Path('/opt/aneb/tls/ip/cert.pem')
elif ship_ip_cert != '0':
    raise SystemExit('invalid certificate marker during live comparison')

expected_live = set(entries) - {'build-provenance.json', 'go-buildinfo.json'}
if set(live_paths) != expected_live:
    raise SystemExit(
        f'live artifact coverage mismatch: actual={set(live_paths)!r} '
        f'expected={expected_live!r}'
    )

def regular_sha(path):
    metadata = path.lstat()
    if path.is_symlink() or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(f'live artifact must be a regular file: {path}')
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()

verified = {}
for logical, live_path in sorted(live_paths.items()):
    staged_path = stage.joinpath(*logical.split('/'))
    staged_sha = regular_sha(staged_path)
    live_sha = regular_sha(live_path)
    expected_sha = entries[logical]
    if staged_sha != expected_sha or live_sha != expected_sha:
        raise SystemExit(
            f'artifact digest mismatch after install: {logical} '
            f'staged={staged_sha} live={live_sha} expected={expected_sha}'
        )
    verified[logical] = live_sha

if ship_ip_cert == '1':
    staged_key = stage / 'tls/ip-key.pem'
    live_key = Path('/opt/aneb/tls/ip/key.pem')
    staged_key_sha = regular_sha(staged_key)
    live_key_sha = regular_sha(live_key)
    if staged_key_sha != expected_key_sha or live_key_sha != expected_key_sha:
        raise SystemExit(
            'artifact digest mismatch after install: tls/ip-key.pem '
            f'staged={staged_key_sha} live={live_key_sha} expected={expected_key_sha}'
        )
    verified['tls/ip-key.pem'] = live_key_sha

rendered = ''.join(
    f'{digest}  {logical}\n' for logical, digest in sorted(verified.items())
).encode('ascii')
descriptor, temporary_name = tempfile.mkstemp(
    dir=output.parent, prefix=f'.{output.name}.', suffix='.tmp'
)
temporary = Path(temporary_name)
try:
    with os.fdopen(descriptor, 'wb') as handle:
        handle.write(rendered)
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, output)
finally:
    if temporary.exists():
        temporary.unlink()
print(f'live_artifacts=verified count={len(verified)}')
PY
}

validate_build_evidence() {
    go version -m -json "$STAGE/aneb-server-linux" \
        > "$STAGE/go-buildinfo.remote.json"
    python3 - \
        "$STAGE" "$SHIP_IP_CERT" \
        "$STAGE/build-provenance.json" \
        "$STAGE/go-buildinfo.json" \
        "$STAGE/go-buildinfo.remote.json" \
        "$STAGE/artifact-manifest.sha256" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import stat
import sys

stage = Path(sys.argv[1]).resolve()
ship_ip_cert = sys.argv[2]
provenance_path = Path(sys.argv[3])
buildinfo_path = Path(sys.argv[4])
remote_buildinfo_path = Path(sys.argv[5])
manifest_path = Path(sys.argv[6])

def reject_duplicate_members(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON member: {key!r}')
        result[key] = value
    return result

def reject_constant(value):
    raise ValueError(f'non-finite JSON number: {value}')

def load_json_object(path, *, canonical):
    metadata = path.lstat()
    if path.is_symlink() or not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 262144:
        raise SystemExit(f'unsafe build evidence file: {path.name}')
    raw = path.read_bytes()
    try:
        value = json.loads(
            raw.decode('utf-8'),
            object_pairs_hook=reject_duplicate_members,
            parse_constant=reject_constant,
        )
    except (UnicodeDecodeError, ValueError) as error:
        raise SystemExit(f'invalid build evidence JSON: {path.name}: {error}') from error
    if not isinstance(value, dict):
        raise SystemExit(f'build evidence must be an object: {path.name}')
    if canonical:
        rendered = (
            json.dumps(
                value,
                ensure_ascii=False,
                sort_keys=True,
                separators=(',', ':'),
                allow_nan=False,
            )
            + '\n'
        ).encode('utf-8')
        if raw != rendered:
            raise SystemExit(f'non-canonical build evidence JSON: {path.name}')
    return value, raw

def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()

def file_record(path):
    metadata = path.lstat()
    if path.is_symlink() or not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(f'build evidence artifact must be regular: {path}')
    raw = path.read_bytes()
    return {'bytes': len(raw), 'sha256': sha256_bytes(raw)}

provenance, provenance_raw = load_json_object(provenance_path, canonical=True)
buildinfo, buildinfo_raw = load_json_object(buildinfo_path, canonical=True)
remote_buildinfo, _ = load_json_object(remote_buildinfo_path, canonical=False)
if buildinfo != remote_buildinfo:
    raise SystemExit('uploaded Go build info does not match staged binary')

expected_provenance_keys = {
    'schema',
    'commit',
    'GoVersion',
    'canonical_flags',
    'environment',
    'binary',
    'module_files',
    'artifacts',
    'go_buildinfo',
}
if set(provenance) != expected_provenance_keys:
    raise SystemExit(f'unexpected provenance structure: {set(provenance)!r}')
if provenance['schema'] != 'aneb-server-build-provenance-v1':
    raise SystemExit('provenance schema mismatch')
commit = provenance['commit']
if not isinstance(commit, str) or re.fullmatch(r'(?:[0-9a-f]{40}|[0-9a-f]{64})', commit) is None:
    raise SystemExit('provenance commit invalid')
go_version = provenance['GoVersion']
if not isinstance(go_version, str) or re.fullmatch(
    r'go[0-9]+(?:\.[0-9]+)+(?:[A-Za-z0-9._-]*)?', go_version
) is None:
    raise SystemExit('provenance Go version invalid')
if provenance['canonical_flags'] != [
    '-trimpath',
    '-buildvcs=true',
    '-mod=readonly',
    '-pgo=off',
]:
    raise SystemExit('provenance build flags mismatch')
expected_environment = {
    'GOOS': 'linux',
    'GOARCH': 'amd64',
    'GOAMD64': 'v1',
    'CGO_ENABLED': '0',
    'GOENV': 'off',
    'GOFLAGS': '',
    'GOEXPERIMENT': '',
    'GOFIPS140': 'off',
    'GOWORK': 'off',
    'GOTOOLCHAIN': 'local',
}
if provenance['environment'] != expected_environment:
    raise SystemExit('provenance build environment mismatch')

manifest_pattern = re.compile(
    r'([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*)'
)
manifest_entries = {}
for line in manifest_path.read_text(encoding='ascii').splitlines():
    match = manifest_pattern.fullmatch(line)
    if match is None or match.group(2) in manifest_entries:
        raise SystemExit('invalid artifact manifest during provenance validation')
    manifest_entries[match.group(2)] = match.group(1)

binary = provenance['binary']
if not isinstance(binary, dict) or set(binary) != {'bytes', 'sha256'}:
    raise SystemExit('provenance binary structure mismatch')
actual_binary = file_record(stage / 'aneb-server-linux')
if binary != actual_binary:
    raise SystemExit('provenance binary record mismatch')
if manifest_entries.get('aneb-server-linux') != actual_binary['sha256']:
    raise SystemExit('manifest/provenance binary mismatch')

buildinfo_record = provenance['go_buildinfo']
if not isinstance(buildinfo_record, dict) or set(buildinfo_record) != {'bytes', 'sha256'}:
    raise SystemExit('provenance Go build info structure mismatch')
actual_buildinfo = {'bytes': len(buildinfo_raw), 'sha256': sha256_bytes(buildinfo_raw)}
if buildinfo_record != actual_buildinfo:
    raise SystemExit('provenance Go build info record mismatch')
if manifest_entries.get('go-buildinfo.json') != actual_buildinfo['sha256']:
    raise SystemExit('manifest/provenance Go build info mismatch')
if manifest_entries.get('build-provenance.json') != sha256_bytes(provenance_raw):
    raise SystemExit('manifest provenance digest mismatch')

module_files = provenance['module_files']
if not isinstance(module_files, dict) or set(module_files) != {'go.mod', 'go.sum'}:
    raise SystemExit('provenance module file set mismatch')
for name, expected_path in {'go.mod': 'server/go.mod', 'go.sum': 'server/go.sum'}.items():
    record = module_files[name]
    if not isinstance(record, dict) or set(record) != {'path', 'sha256', 'bytes'}:
        raise SystemExit(f'provenance module record structure mismatch: {name}')
    if (
        record['path'] != expected_path
        or not isinstance(record['bytes'], int)
        or isinstance(record['bytes'], bool)
        or record['bytes'] < 0
        or not isinstance(record['sha256'], str)
        or re.fullmatch(r'[0-9a-f]{64}', record['sha256']) is None
    ):
        raise SystemExit(f'provenance module record invalid: {name}')

artifact_source_paths = {
    'aneb-server.service': 'server/aneb-server.service',
    'root-profiles/basic_network.json': 'profiles/basic_network.json',
    'root-profiles/s1_chat.json': 'profiles/s1_chat.json',
    'root-profiles/s2_coding_agent.json': 'profiles/s2_coding_agent.json',
    'root-profiles/s3_multimodal.json': 'profiles/s3_multimodal.json',
    'execution-profiles/token_multimodal_quick/profile.json': (
        'profiles/published/token_multimodal_quick/profile.json'
    ),
    'execution-profiles/token_multimodal_quick/runtime_plan.json': (
        'profiles/published/token_multimodal_quick/runtime_plan.json'
    ),
    'execution-profiles/token_multimodal_quick/manifest.sha256': (
        'profiles/published/token_multimodal_quick/manifest.sha256'
    ),
}
if ship_ip_cert == '1':
    artifact_source_paths['tls/ip-cert.pem'] = 'server/tls/ip/aneb_ip_cert.pem'
artifacts = provenance['artifacts']
if not isinstance(artifacts, list):
    raise SystemExit('provenance artifacts must be an array')
if [item.get('name') if isinstance(item, dict) else None for item in artifacts] != sorted(
    artifact_source_paths
):
    raise SystemExit('provenance artifact order or coverage mismatch')
for item in artifacts:
    if not isinstance(item, dict) or set(item) != {'name', 'path', 'sha256', 'bytes'}:
        raise SystemExit(f'provenance artifact structure mismatch: {item!r}')
    name = item['name']
    if item['path'] != artifact_source_paths[name]:
        raise SystemExit(f'provenance artifact source path mismatch: {name}')
    actual = file_record(stage.joinpath(*name.split('/')))
    if item['bytes'] != actual['bytes'] or item['sha256'] != actual['sha256']:
        raise SystemExit(f'provenance artifact record mismatch: {name}')
    if manifest_entries.get(name) != actual['sha256']:
        raise SystemExit(f'manifest/provenance artifact mismatch: {name}')

if buildinfo.get('GoVersion') != go_version:
    raise SystemExit('Go build info version mismatch')
settings_value = buildinfo.get('Settings')
if not isinstance(settings_value, list):
    raise SystemExit('Go build info settings missing')
settings = {}
for item in settings_value:
    if not isinstance(item, dict) or set(item) != {'Key', 'Value'}:
        raise SystemExit(f'Go build info setting structure mismatch: {item!r}')
    key = item['Key']
    value = item['Value']
    if not isinstance(key, str) or not isinstance(value, str) or key in settings:
        raise SystemExit('duplicate or invalid Go build info setting')
    settings[key] = value
expected_settings = {
    'vcs': 'git',
    'vcs.revision': commit,
    'vcs.modified': 'false',
    'GOOS': 'linux',
    'GOARCH': 'amd64',
    'GOAMD64': 'v1',
    'CGO_ENABLED': '0',
    '-trimpath': 'true',
}
for key, expected in expected_settings.items():
    if settings.get(key) != expected:
        raise SystemExit(f'Go build info setting mismatch: {key}')
if settings.get('GOFIPS140', 'off') != 'off':
    raise SystemExit('Go FIPS build setting mismatch')
print(
    'build_evidence=verified platform=linux/amd64 '
    f'commit={commit} binary_sha256={actual_binary["sha256"]}'
)
PY
}

validate_ip_certificate_bundle() {
    local cert="$STAGE/tls/ip-cert.pem"
    local key="$STAGE/tls/ip-key.pem"
    local cert_public_sha key_public_sha

    # checkip validates the SAN entry rather than accepting a CN fallback.
    openssl x509 -in "$cert" -noout -checkip 120.79.148.0 >/dev/null
    openssl x509 -in "$cert" -noout -startdate -enddate \
        > "$STAGE/tls/ip-cert-validity.txt"
    python3 - "$STAGE/tls/ip-cert-validity.txt" <<'PY'
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
import sys

values = {}
for line in Path(sys.argv[1]).read_text(encoding='ascii').splitlines():
    key, separator, value = line.partition('=')
    if not separator or key in values:
        raise SystemExit('invalid certificate validity output')
    values[key] = parsedate_to_datetime(value)
if set(values) != {'notBefore', 'notAfter'}:
    raise SystemExit(f'incomplete certificate validity output: {set(values)!r}')
now = datetime.now(timezone.utc)
if not values['notBefore'] <= now <= values['notAfter']:
    raise SystemExit(
        f'IP certificate is not currently valid: '
        f'{values["notBefore"].isoformat()} <= {now.isoformat()} <= '
        f'{values["notAfter"].isoformat()}'
    )
PY
    cert_public_sha="$(
        openssl x509 -in "$cert" -pubkey -noout \
            | openssl pkey -pubin -outform DER \
            | hash_stream
    )"
    key_public_sha="$(
        openssl pkey -in "$key" -passin pass: -pubout -outform DER \
            | hash_stream
    )"
    [[ -n "$cert_public_sha" && "$cert_public_sha" == "$key_public_sha" ]] || {
        echo 'IP certificate/private-key public key mismatch' >&2
        return 1
    }
    echo 'ip_certificate_bundle=verified key_match=1 san_ip=120.79.148.0 validity=current'
}

path_fingerprint() {
    python3 - "$1" <<'PY'
import hashlib
import os
from pathlib import Path
import stat
import sys

root = Path(sys.argv[1])
digest = hashlib.sha256()
if not root.exists() and not root.is_symlink():
    digest.update(b'ABSENT\0')
else:
    base = root.parent
    paths = [root]
    if root.is_dir() and not root.is_symlink():
        paths.extend(sorted(root.rglob('*'), key=lambda item: item.as_posix()))
    for path in paths:
        relative = path.relative_to(base).as_posix().encode('utf-8')
        metadata = path.lstat()
        ownership = f'{metadata.st_uid}:{metadata.st_gid}'.encode('ascii')
        digest.update(
            relative
            + b'\0'
            + oct(stat.S_IMODE(metadata.st_mode)).encode()
            + b'\0'
            + ownership
            + b'\0'
        )
        if path.is_symlink():
            digest.update(b'L\0' + os.readlink(path).encode('utf-8') + b'\0')
        elif path.is_dir():
            digest.update(b'D\0')
        elif path.is_file():
            digest.update(b'F\0')
            with path.open('rb') as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b''):
                    digest.update(chunk)
        else:
            digest.update(b'O\0')
print(digest.hexdigest())
PY
}

normalized_path_fingerprint() {
    python3 - "$1" <<'PY'
import hashlib
import os
from pathlib import Path
import stat
import sys

root = Path(sys.argv[1])
digest = hashlib.sha256()
if not root.exists() and not root.is_symlink():
    digest.update(b'ABSENT\0')
else:
    paths = [root]
    if root.is_dir() and not root.is_symlink():
        paths.extend(sorted(root.rglob('*'), key=lambda item: item.as_posix()))
    for path in paths:
        relative = b'.' if path == root else path.relative_to(root).as_posix().encode('utf-8')
        metadata = path.lstat()
        digest.update(
            relative
            + b'\0'
            + oct(stat.S_IMODE(metadata.st_mode)).encode('ascii')
            + b'\0'
            + f'{metadata.st_uid}:{metadata.st_gid}'.encode('ascii')
            + b'\0'
        )
        if path.is_symlink():
            digest.update(b'L\0' + os.readlink(path).encode('utf-8') + b'\0')
        elif path.is_dir():
            digest.update(b'D\0')
        elif path.is_file():
            digest.update(b'F\0')
            with path.open('rb') as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b''):
                    digest.update(chunk)
        else:
            digest.update(b'O\0')
print(digest.hexdigest())
PY
}

atomic_replace_candidate() {
    local candidate="${1:?replacement candidate required}"
    local target="${2:?replacement target required}"
    python3 - "$candidate" "$target" <<'PY'
import ctypes
import os
from pathlib import Path
import sys

candidate = Path(sys.argv[1])
target = Path(sys.argv[2])
if candidate.parent.resolve() != target.parent.resolve():
    raise SystemExit('replacement candidate must share the target directory')
if not candidate.exists() and not candidate.is_symlink():
    raise SystemExit('replacement candidate is missing')
if not target.exists() and not target.is_symlink():
    os.replace(candidate, target)
    raise SystemExit(0)

libc = ctypes.CDLL(None, use_errno=True)
renameat2 = getattr(libc, 'renameat2', None)
if renameat2 is None:
    raise SystemExit('renameat2 is unavailable; refusing non-atomic replacement')
renameat2.argtypes = [
    ctypes.c_int,
    ctypes.c_char_p,
    ctypes.c_int,
    ctypes.c_char_p,
    ctypes.c_uint,
]
renameat2.restype = ctypes.c_int
at_fdcwd = -100
rename_exchange = 2
result = renameat2(
    at_fdcwd,
    os.fsencode(candidate),
    at_fdcwd,
    os.fsencode(target),
    rename_exchange,
)
if result != 0:
    error = ctypes.get_errno()
    raise SystemExit(
        f'RENAME_EXCHANGE failed: errno={error} {os.strerror(error)}'
    )
PY
}

canonicalize_iptables_save() {
    local expected_tool="${1:?expected iptables-save tool required}"
    [[ "$expected_tool" == "iptables-save" || "$expected_tool" == "ip6tables-save" ]] || {
        echo "invalid iptables-save tool identity: $expected_tool" >&2
        return 1
    }
    # iptables-save adds wall-clock header/footer comments on every invocation.
    # Trim only the Generated timestamp and remove the time-only Completed line;
    # preserve tool version/backend, chains, policies, rules, warnings, and rule
    # comments byte-for-byte for fail-closed comparison.
    python3 -c '
from datetime import datetime
import re
import sys

expected_tool = sys.argv[1].encode("ascii")
data = sys.stdin.buffer.read()
if not data:
    raise SystemExit("empty iptables-save snapshot")
lines = data.splitlines(keepends=True)
if len(lines) < 2:
    raise SystemExit("incomplete iptables-save snapshot")
bodies = [line.rstrip(b"\r\n") for line in lines]
generated = [index for index, body in enumerate(bodies) if body.startswith(b"# Generated by")]
completed = [index for index, body in enumerate(bodies) if body.startswith(b"# Completed on")]
if not generated or len(generated) != len(completed):
    raise SystemExit("iptables-save wrapper positions invalid")

header_prefix = b"# Generated by " + expected_tool + b" "
completed_prefix = b"# Completed on "

timestamp_pattern = re.compile(
    rb"(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) "
    rb"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) "
    rb"(?: [1-9]|[12][0-9]|3[01]) "
    rb"(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9] [0-9]{4}"
)

def validate_timestamp(value, label):
    if not timestamp_pattern.fullmatch(value):
        raise SystemExit(f"malformed {label} timestamp")
    try:
        text = value.decode("ascii")
        parsed = datetime.strptime(text, "%a %b %d %H:%M:%S %Y")
    except (UnicodeDecodeError, ValueError) as error:
        raise SystemExit(f"invalid {label} timestamp: {error}") from error
    canonical = "{} {:2d} {}".format(
        parsed.strftime("%a %b"),
        parsed.day,
        parsed.strftime("%H:%M:%S %Y"),
    )
    if text != canonical:
        raise SystemExit(f"non-canonical {label} timestamp")

previous_completed = -1
for block_index, (generated_index, completed_index) in enumerate(zip(generated, completed)):
    expected_generated = 0 if block_index == 0 else previous_completed + 1
    if generated_index != expected_generated or completed_index <= generated_index + 1:
        raise SystemExit("iptables-save wrapper positions invalid")
    if block_index == len(generated) - 1 and completed_index != len(lines) - 1:
        raise SystemExit("iptables-save wrapper positions invalid")

    header = bodies[generated_index]
    if not header.startswith(header_prefix):
        raise SystemExit("iptables-save Generated tool identity mismatch")
    identity, separator, generated_timestamp = header.rpartition(b" on ")
    if (
        not separator
        or not identity.startswith(header_prefix)
        or not identity[len(header_prefix):].strip()
    ):
        raise SystemExit("malformed iptables-save Generated header")
    footer = bodies[completed_index]
    if not footer.startswith(completed_prefix):
        raise SystemExit("malformed iptables-save Completed footer")
    validate_timestamp(generated_timestamp, "Generated")
    validate_timestamp(footer[len(completed_prefix):], "Completed")

    newline = b"\r\n" if lines[generated_index].endswith(b"\r\n") else b"\n" if lines[generated_index].endswith(b"\n") else b""
    if not newline:
        raise SystemExit("Generated header must end with a newline")
    sys.stdout.buffer.write(identity + newline)
    for line in lines[generated_index + 1:completed_index]:
        sys.stdout.buffer.write(line)
    previous_completed = completed_index
' "$expected_tool"
}

capture_clean_firewall_command() {
    local label="${1:?firewall capture label required}"
    shift
    local stdout_path stderr_path
    [[ "$label" =~ ^[a-z0-9-]+$ && "$#" -gt 0 ]] || {
        echo 'invalid firewall capture command' >&2
        return 1
    }
    stdout_path="$STAGE/firewall-capture-$label.stdout"
    stderr_path="$STAGE/firewall-capture-$label.stderr"
    if ! "$@" >"$stdout_path" 2>"$stderr_path"; then
        echo "$label snapshot command failed" >&2
        return 1
    fi
    if [[ -s "$stderr_path" ]]; then
        echo "$label snapshot emitted stderr" >&2
        return 1
    fi
    cat -- "$stdout_path"
}

firewall_snapshot() {
    printf '%s\n' '--- iptables-save ---'
    capture_clean_firewall_command iptables-save iptables-save | \
        canonicalize_iptables_save iptables-save || {
        echo 'iptables-save snapshot failed' >&2
        return 1
    }
    printf '%s\n' '--- ip6tables-save ---'
    capture_clean_firewall_command ip6tables-save ip6tables-save | \
        canonicalize_iptables_save ip6tables-save || {
        echo 'ip6tables-save snapshot failed' >&2
        return 1
    }
    printf '%s\n' '--- nft-list-ruleset ---'
    if command -v nft >/dev/null 2>&1; then
        capture_clean_firewall_command nft-list-ruleset \
            nft --stateless list ruleset || {
            echo 'nft ruleset snapshot failed' >&2
            return 1
        }
    else
        printf '%s\n' 'nft=absent'
    fi
}

firewall_snapshot_fingerprints() {
    python3 - "$1" <<'PY'
import hashlib
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
v4_marker = b'--- iptables-save ---\n'
v6_marker = b'--- ip6tables-save ---\n'
nft_marker = b'--- nft-list-ruleset ---\n'
if not data.startswith(v4_marker):
    raise SystemExit('firewall snapshot missing leading IPv4 marker')
if data.count(v4_marker) != 1 or data.count(v6_marker) != 1 or data.count(nft_marker) != 1:
    raise SystemExit('firewall snapshot marker count mismatch')
v6_offset = data.index(v6_marker, len(v4_marker))
nft_offset = data.index(nft_marker, v6_offset + len(v6_marker))
v4 = data[len(v4_marker):v6_offset]
v6 = data[v6_offset + len(v6_marker):nft_offset]
nft = data[nft_offset + len(nft_marker):]
docker = (
    b'--- iptables-save docker rules ---\n'
    + b''.join(line for line in v4.splitlines(keepends=True) if b'DOCKER' in line)
    + b'--- ip6tables-save docker rules ---\n'
    + b''.join(line for line in v6.splitlines(keepends=True) if b'DOCKER' in line)
)
digest = lambda value: hashlib.sha256(value).hexdigest()
print(digest(data), digest(v4), digest(v6), digest(nft), digest(docker))
PY
}

docker_firewall_snapshot() {
    printf '%s\n' '--- iptables-save docker rules ---'
    capture_clean_firewall_command docker-iptables-save iptables-save | \
        awk '/(^:DOCKER|DOCKER)/ { print }' || {
        echo 'iptables-save Docker snapshot failed' >&2
        return 1
    }
    printf '%s\n' '--- ip6tables-save docker rules ---'
    capture_clean_firewall_command docker-ip6tables-save ip6tables-save | \
        awk '/(^:DOCKER|DOCKER)/ { print }' || {
        echo 'ip6tables-save Docker snapshot failed' >&2
        return 1
    }
}

docker_firewall_fingerprint() {
    docker_firewall_snapshot | hash_stream
}

eth0_qdisc_fingerprint() {
    tc qdisc show dev eth0 | hash_stream
}

validate_server_identity() {
    local expected="$1"
    local label="$2"
    local headers="$STAGE/$label-serverinfo.headers"
    local body="$STAGE/$label-serverinfo.json"
    curl -fksS --connect-timeout 3 --max-time 10 \
        -D "$headers" -o "$body" \
        https://127.0.0.1:8443/api/v1/serverinfo
    python3 - "$body" "$headers" "$expected" <<'PY'
import json
from pathlib import Path
import sys

body = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
expected = sys.argv[3]
if body.get('version') != expected:
    raise SystemExit(f'serverinfo version mismatch: {body.get("version")!r} != {expected!r}')
if body.get('h3_enabled') is not True:
    raise SystemExit(f'serverinfo h3_enabled must be true on live E-01: {body.get("h3_enabled")!r}')
values = []
for line in Path(sys.argv[2]).read_text(encoding='iso-8859-1').splitlines():
    if ':' not in line:
        continue
    name, value = line.split(':', 1)
    if name.strip().lower() == 'x-aneb-server':
        values.append(value.strip())
if values != [expected]:
    raise SystemExit(f'X-Aneb-Server mismatch: {values!r} != {[expected]!r}')
print(f'server_identity=verified version={expected}')
PY
}

wait_live_server() {
    local ready=0
    for _ in $(seq 1 50); do
        if curl -fksS --connect-timeout 1 --max-time 2 \
            https://127.0.0.1:8443/api/v1/serverinfo >/dev/null 2>&1; then
            ready=1
            break
        fi
        sleep 0.1
    done
    [[ $ready -eq 1 ]]
}

freeze_live_baseline() {
    local sample1="$STAGE/pre-switch-firewall-sample1.txt"
    local sample2="$STAGE/pre-switch-firewall-sample2.txt"
    local sample1_fingerprints="$STAGE/pre-switch-firewall-sample1.sha256"
    local sample2_fingerprints="$STAGE/pre-switch-firewall-sample2.sha256"
    local sample1_full sample1_v4 sample1_v6 sample1_nft sample1_docker sample1_extra
    local sample2_full sample2_v4 sample2_v6 sample2_nft sample2_docker sample2_extra
    local digest verify_docker_sha
    validate_server_identity 'aneb-server/0.7.0' pre-switch
    validate_legacy_surface pre-switch-0.7
    BASE_BINARY_SHA="$(file_sha256 /opt/aneb/bin/aneb-server)"
    BASE_ROOT_PROFILES_SHA="$(path_fingerprint /opt/aneb/profiles)"
    BASE_QUICK_BUNDLE_SHA="$(path_fingerprint /opt/aneb/execution-profiles/token_multimodal_quick)"
    BASE_SERVICE_UNIT_SHA="$(path_fingerprint /etc/systemd/system/aneb-server.service)"
    if [[ "$SHIP_IP_CERT" == "1" ]]; then
        BASE_IP_CERT_SHA="$(path_fingerprint /opt/aneb/tls/ip/cert.pem)"
        BASE_IP_KEY_SHA="$(path_fingerprint /opt/aneb/tls/ip/key.pem)"
    fi
    BASE_ETH0_QDISC_SHA="$(eth0_qdisc_fingerprint)"
    if ! firewall_snapshot > "$sample1"; then
        echo 'PRE_SWITCH_BASELINE_CAPTURE_FAILED sample=1' >&2
        return 1
    fi
    if ! firewall_snapshot > "$sample2"; then
        echo 'PRE_SWITCH_BASELINE_CAPTURE_FAILED sample=2' >&2
        return 1
    fi
    if ! firewall_snapshot_fingerprints "$sample1" > "$sample1_fingerprints"; then
        echo 'PRE_SWITCH_BASELINE_FINGERPRINT_FAILED sample=1' >&2
        return 1
    fi
    if ! firewall_snapshot_fingerprints "$sample2" > "$sample2_fingerprints"; then
        echo 'PRE_SWITCH_BASELINE_FINGERPRINT_FAILED sample=2' >&2
        return 1
    fi
    if ! IFS=' ' read -r sample1_full sample1_v4 sample1_v6 sample1_nft sample1_docker sample1_extra \
        < "$sample1_fingerprints"; then
        echo 'PRE_SWITCH_BASELINE_FINGERPRINT_READ_FAILED sample=1' >&2
        return 1
    fi
    if ! IFS=' ' read -r sample2_full sample2_v4 sample2_v6 sample2_nft sample2_docker sample2_extra \
        < "$sample2_fingerprints"; then
        echo 'PRE_SWITCH_BASELINE_FINGERPRINT_READ_FAILED sample=2' >&2
        return 1
    fi
    for digest in \
        "$sample1_full" "$sample1_v4" "$sample1_v6" "$sample1_nft" "$sample1_docker" \
        "$sample2_full" "$sample2_v4" "$sample2_v6" "$sample2_nft" "$sample2_docker"; do
        [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || {
            echo 'PRE_SWITCH_BASELINE_FINGERPRINT_INVALID' >&2
            return 1
        }
    done
    if [[ -n "$sample1_extra" || -n "$sample2_extra" ]]; then
        echo 'PRE_SWITCH_BASELINE_FINGERPRINT_FIELD_COUNT_INVALID' >&2
        return 1
    fi
    if ! cmp -s "$sample1" "$sample2"; then
        echo "PRE_SWITCH_BASELINE_UNSTABLE surface=firewall samples=2 sample1_full=$sample1_full sample2_full=$sample2_full" >&2
        echo "PRE_SWITCH_COMPONENTS sample=1 iptables_v4=$sample1_v4 iptables_v6=$sample1_v6 nft_ruleset=$sample1_nft docker=$sample1_docker" >&2
        echo "PRE_SWITCH_COMPONENTS sample=2 iptables_v4=$sample2_v4 iptables_v6=$sample2_v6 nft_ruleset=$sample2_nft docker=$sample2_docker" >&2
        return 1
    fi
    verify_docker_sha="$(docker_firewall_fingerprint)"
    if [[ ! "$verify_docker_sha" =~ ^[0-9a-f]{64}$ || "$verify_docker_sha" != "$sample1_docker" ]]; then
        echo "PRE_SWITCH_BASELINE_UNSTABLE surface=docker_iptables sample1=$sample1_docker verify=$verify_docker_sha" >&2
        return 1
    fi
    BASE_FIREWALL_SHA="$sample1_full"
    BASE_IPTABLES_V4_FIREWALL_SHA="$sample1_v4"
    BASE_IPTABLES_V6_FIREWALL_SHA="$sample1_v6"
    BASE_NFT_FIREWALL_SHA="$sample1_nft"
    BASE_DOCKER_FIREWALL_SHA="$sample1_docker"
    printf '%s\n' \
        "binary_sha256=$BASE_BINARY_SHA" \
        "docker_iptables_fingerprint=$BASE_DOCKER_FIREWALL_SHA" \
        "eth0_qdisc_fingerprint=$BASE_ETH0_QDISC_SHA" \
        "iptables_v4_fingerprint=$BASE_IPTABLES_V4_FIREWALL_SHA" \
        "iptables_v6_fingerprint=$BASE_IPTABLES_V6_FIREWALL_SHA" \
        "nft_ruleset_fingerprint=$BASE_NFT_FIREWALL_SHA" \
        "firewall_fingerprint=$BASE_FIREWALL_SHA" \
        > "$STAGE/pre-switch-fingerprints.txt"
    echo 'pre_switch_firewall_stability=verified samples=2'
    echo 'pre_switch_baseline=verified version=0.7.0 shared_host=frozen'
}

assert_shared_host_baseline() {
    local label="$1"
    local current_snapshot current_fingerprints
    local current_firewall current_v4 current_v6 current_nft current_docker current_extra
    local current_qdisc current_binary digest
    [[ "$label" =~ ^(live|rollback)$ ]] || {
        echo "SHARED_BASELINE_INVALID_LABEL label=$label" >&2
        return 1
    }
    current_snapshot="$STAGE/$label-firewall.txt"
    current_fingerprints="$STAGE/$label-firewall.sha256"
    current_qdisc="$(eth0_qdisc_fingerprint)"
    if ! firewall_snapshot > "$current_snapshot"; then
        echo "SHARED_BASELINE_CAPTURE_FAILED label=$label surface=firewall" >&2
        return 1
    fi
    if ! firewall_snapshot_fingerprints "$current_snapshot" > "$current_fingerprints"; then
        echo "SHARED_BASELINE_FINGERPRINT_FAILED label=$label surface=firewall" >&2
        return 1
    fi
    if ! IFS=' ' read -r current_firewall current_v4 current_v6 current_nft current_docker current_extra \
        < "$current_fingerprints"; then
        echo "SHARED_BASELINE_FINGERPRINT_READ_FAILED label=$label surface=firewall" >&2
        return 1
    fi
    for digest in "$current_firewall" "$current_v4" "$current_v6" "$current_nft" "$current_docker"; do
        [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || {
            echo "SHARED_BASELINE_FINGERPRINT_INVALID label=$label surface=firewall" >&2
            return 1
        }
    done
    if [[ -n "$current_extra" ]]; then
        echo "SHARED_BASELINE_FINGERPRINT_FIELD_COUNT_INVALID label=$label surface=firewall" >&2
        return 1
    fi
    if [[ "$current_docker" != "$BASE_DOCKER_FIREWALL_SHA" ]]; then
        echo "SHARED_BASELINE_MISMATCH label=$label surface=docker_iptables expected=$BASE_DOCKER_FIREWALL_SHA actual=$current_docker" >&2
        return 1
    fi
    if [[ "$current_qdisc" != "$BASE_ETH0_QDISC_SHA" ]]; then
        echo "SHARED_BASELINE_MISMATCH label=$label surface=eth0_qdisc" >&2
        return 1
    fi
    if [[ "$current_v4" != "$BASE_IPTABLES_V4_FIREWALL_SHA" ]]; then
        echo "SHARED_BASELINE_MISMATCH label=$label surface=iptables_v4 expected=$BASE_IPTABLES_V4_FIREWALL_SHA actual=$current_v4" >&2
        return 1
    fi
    if [[ "$current_v6" != "$BASE_IPTABLES_V6_FIREWALL_SHA" ]]; then
        echo "SHARED_BASELINE_MISMATCH label=$label surface=iptables_v6 expected=$BASE_IPTABLES_V6_FIREWALL_SHA actual=$current_v6" >&2
        return 1
    fi
    if [[ "$current_nft" != "$BASE_NFT_FIREWALL_SHA" ]]; then
        echo "SHARED_BASELINE_MISMATCH label=$label surface=nft_ruleset expected=$BASE_NFT_FIREWALL_SHA actual=$current_nft" >&2
        return 1
    fi
    if [[ "$current_firewall" != "$BASE_FIREWALL_SHA" ]]; then
        echo "SHARED_BASELINE_MISMATCH label=$label surface=firewall expected=$BASE_FIREWALL_SHA actual=$current_firewall" >&2
        return 1
    fi
    current_binary="$(file_sha256 /opt/aneb/bin/aneb-server)"
    printf '%s\n' \
        "binary_sha256=$current_binary" \
        "docker_iptables_fingerprint=$current_docker" \
        "eth0_qdisc_fingerprint=$current_qdisc" \
        "iptables_v4_fingerprint=$current_v4" \
        "iptables_v6_fingerprint=$current_v6" \
        "nft_ruleset_fingerprint=$current_nft" \
        "firewall_fingerprint=$current_firewall" \
        > "$STAGE/$label-fingerprints.txt"
    echo "shared_host_baseline=match label=$label"
}

assert_restored_aneb_baseline() {
    local current
    current="$(file_sha256 /opt/aneb/bin/aneb-server)"
    if [[ "$current" != "$BASE_BINARY_SHA" ]]; then
        echo 'ROLLBACK_VERIFY_FAILED surface=aneb_binary' >&2
        return 1
    fi
    [[ "$(path_fingerprint /opt/aneb/profiles)" == "$BASE_ROOT_PROFILES_SHA" ]] || {
        echo 'ROLLBACK_VERIFY_FAILED surface=root_profiles' >&2
        return 1
    }
    [[ "$(path_fingerprint /opt/aneb/execution-profiles/token_multimodal_quick)" == "$BASE_QUICK_BUNDLE_SHA" ]] || {
        echo 'ROLLBACK_VERIFY_FAILED surface=quick_bundle' >&2
        return 1
    }
    [[ "$(path_fingerprint /etc/systemd/system/aneb-server.service)" == "$BASE_SERVICE_UNIT_SHA" ]] || {
        echo 'ROLLBACK_VERIFY_FAILED surface=service_unit' >&2
        return 1
    }
    if [[ "$SHIP_IP_CERT" == "1" ]]; then
        [[ "$(path_fingerprint /opt/aneb/tls/ip/cert.pem)" == "$BASE_IP_CERT_SHA" ]] || {
            echo 'ROLLBACK_VERIFY_FAILED surface=ip_certificate' >&2
            return 1
        }
        [[ "$(path_fingerprint /opt/aneb/tls/ip/key.pem)" == "$BASE_IP_KEY_SHA" ]] || {
            echo 'ROLLBACK_VERIFY_FAILED surface=ip_private_key' >&2
            return 1
        }
    fi
    echo 'rollback_aneb_baseline=match'
}

validate_legacy_surface() {
    local label="$1"
    local prefix="$STAGE/$label"
    [[ "$label" =~ ^[a-z0-9.-]+$ ]]

    curl -fksS --connect-timeout 2 --max-time 10 \
        https://127.0.0.1:8443/api/v1/profiles -o "$prefix-profiles.json"
    python3 - "$prefix-profiles.json" <<'PY'
import json
from pathlib import Path
import sys

body = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
profiles = {item['profile_id']: item for item in body['profiles']}
if set(profiles) != {'basic_network', 's1_chat', 's2_coding_agent', 's3_multimodal'}:
    raise SystemExit(f'root profile set mismatch: {set(profiles)!r}')
if profiles['s3_multimodal'].get('version') != '0.3.0':
    raise SystemExit('s3_multimodal version mismatch')
s3_phases = profiles['s3_multimodal'].get('phases', [])
expected_phase_types = [
    'clock_sync',
    'upload_burst',
    'think_pause',
    'token_stream',
    'download_burst',
    'upload_burst',
    'think_pause',
    'token_stream',
    'download_burst',
    'clock_sync',
]
if [phase.get('type') for phase in s3_phases] != expected_phase_types:
    raise SystemExit('s3_multimodal phase order mismatch')
for index in (4, 8):
    phase = s3_phases[index]
    if phase.get('bytes') != 12582912 or phase.get('chunk_kb') != 256:
        raise SystemExit(f's3_multimodal download phase {index} mismatch: {phase!r}')
PY

    curl -fksS --connect-timeout 2 --max-time 10 -X POST --data 'ping' \
        https://127.0.0.1:8443/api/v1/echo >/dev/null
    local download_bytes
    download_bytes="$(curl -fksS --connect-timeout 2 --max-time 15 \
        'https://127.0.0.1:8443/api/v1/download?bytes=1048576' | wc -c)"
    [[ "$download_bytes" -eq 1048576 ]]

    curl -fksS --connect-timeout 2 --max-time 10 \
        https://127.0.0.1:8443/api/v1/impairments -o "$prefix-impairments.json"
    python3 - "$prefix-impairments.json" <<'PY'
import json
from pathlib import Path
import sys

body = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
policies = {item['route_id']: item for item in body['policies']}
if set(policies) != {'weak-capacity-latency-v1', 'weak-recovery-v1'}:
    raise SystemExit(f'impairment route set mismatch: {set(policies)!r}')
capacity = policies['weak-capacity-latency-v1']
if capacity.get('contract_version') != 'aneb-synthetic-impairment-v1':
    raise SystemExit('capacity contract version mismatch')
if capacity.get('profile_id') != 'network_comprehensive_weak_capacity_latency':
    raise SystemExit('capacity profile mismatch')
if capacity.get('version') != '1.1.0':
    raise SystemExit('capacity profile version mismatch')
if tuple(capacity.get(key) for key in ('downlink_mbps', 'uplink_mbps', 'added_rtt_ms', 'jitter_ms')) != (3, 1, 120, 30):
    raise SystemExit('capacity parameters mismatch')
recovery = policies['weak-recovery-v1']
if recovery.get('profile_id') != 'network_comprehensive_weak_recovery':
    raise SystemExit('recovery profile mismatch')
if recovery.get('version') != '1.1.0' or recovery.get('outage_duration_ms') != 2000:
    raise SystemExit('recovery timing mismatch')
if tuple(recovery.get(key) for key in ('downlink_mbps', 'uplink_mbps', 'added_rtt_ms', 'jitter_ms')) != (5, 2, 80, 20):
    raise SystemExit('recovery parameters mismatch')
PY

    local synthetic_base capacity_query synthetic_bytes
    synthetic_base='https://127.0.0.1:8443/synthetic/weak-capacity-latency-v1'
    capacity_query="impair_run=deploy-$DEPLOY_ID-$label-capacity&impair_seed=20260718&impair_seq=1"
    curl -fksS --connect-timeout 2 --max-time 10 \
        -D "$prefix-capacity-echo.headers" -o "$prefix-capacity-echo.json" \
        -X POST --data 'ping' "$synthetic_base/api/v1/echo?$capacity_query"
    grep -qi '^X-Aneb-Synthetic-Impairment: network_comprehensive_weak_capacity_latency@1.1.0' \
        "$prefix-capacity-echo.headers"
    python3 - "$prefix-capacity-echo.json" <<'PY'
import json
from pathlib import Path
import sys
json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
PY
    capacity_query="impair_run=deploy-$DEPLOY_ID-$label-capacity-download&impair_seed=20260718&impair_seq=1"
    synthetic_bytes="$(curl -fksS --connect-timeout 2 --max-time 15 \
        "$synthetic_base/api/v1/download?bytes=65536&chunk_kb=16&$capacity_query" | wc -c)"
    [[ "$synthetic_bytes" -eq 65536 ]]

    local recovery_base recovery_run recovery_query recovery_code other_query
    local other_code normal_code recovered_code
    recovery_base='https://127.0.0.1:8443/synthetic/weak-recovery-v1'
    recovery_run="deploy-$DEPLOY_ID-$label-recovery"
    recovery_query="impair_run=$recovery_run&impair_seed=20260718&impair_seq=1"
    recovery_code="$(curl -ksS --connect-timeout 2 --max-time 10 \
        -D "$prefix-recovery-trigger.headers" -o "$prefix-recovery-trigger.json" \
        -w '%{http_code}' -X POST --data '{}' "$recovery_base/api/v1/recovery?$recovery_query")"
    [[ "$recovery_code" -eq 202 ]]
    grep -qi '^X-Aneb-Synthetic-Impairment: network_comprehensive_weak_recovery@1.1.0' \
        "$prefix-recovery-trigger.headers"
    grep -qi '^X-Aneb-Outage-Duration-Ms: 2000' "$prefix-recovery-trigger.headers"

    recovery_query="impair_run=$recovery_run&impair_seed=20260718&impair_seq=2"
    recovery_code="$(curl -ksS --connect-timeout 2 --max-time 10 \
        -D "$prefix-recovery-blocked.headers" -o /dev/null -w '%{http_code}' \
        -X POST --data '{}' "$recovery_base/api/v1/echo?$recovery_query")"
    [[ "$recovery_code" -eq 503 ]]
    grep -qi '^X-Aneb-Synthetic-Outage: active' "$prefix-recovery-blocked.headers"

    other_query="impair_run=deploy-$DEPLOY_ID-$label-recovery-other&impair_seed=20260718&impair_seq=1"
    other_code="$(curl -ksS --connect-timeout 2 --max-time 10 -o /dev/null -w '%{http_code}' \
        -X POST --data '{}' "$recovery_base/api/v1/echo?$other_query")"
    [[ "$other_code" -eq 200 ]]
    normal_code="$(curl -ksS --connect-timeout 2 --max-time 10 -o /dev/null -w '%{http_code}' \
        -X POST --data '{}' https://127.0.0.1:8443/api/v1/echo)"
    [[ "$normal_code" -eq 200 ]]
    sleep 3
    recovery_query="impair_run=$recovery_run&impair_seed=20260718&impair_seq=3"
    recovered_code="$(curl -ksS --connect-timeout 2 --max-time 10 -o /dev/null -w '%{http_code}' \
        -X POST --data '{}' "$recovery_base/api/v1/echo?$recovery_query")"
    [[ "$recovered_code" -eq 200 ]]

    python3 - <<'PY'
import socket
import struct
import time

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(2)
    packet = b'ANEB1' + struct.pack('>Iq', 7, time.monotonic_ns()) + bytes(47)
    sock.sendto(packet, ('127.0.0.1', 8443))
    reply, _ = sock.recvfrom(512)
    if reply != packet:
        raise SystemExit('UDP echo payload mismatch')
PY
    echo "legacy_surface_smoke=pass label=$label profiles+echo+download+impairment+recovery+udp"
}

restore_item() {
    local label="$1"
    local target="$2"
    local candidate="${target}.restore-$DEPLOY_ID"
    local tombstone="${target}.absent-$DEPLOY_ID"
    local backup_sha candidate_sha
    case "$target" in
        /opt/aneb/bin/aneb-server|\
        /opt/aneb/profiles|\
        /opt/aneb/execution-profiles/token_multimodal_quick|\
        /etc/systemd/system/aneb-server.service|\
        /opt/aneb/tls/ip/cert.pem|\
        /opt/aneb/tls/ip/key.pem) ;;
        *)
            echo "unsafe rollback target: $target" >&2
            return 1
            ;;
    esac
    rm -rf -- "$candidate" "$tombstone" || return 1
    if [[ -f "$BACKUP/$label.present" ]]; then
        mkdir -p -- "$(dirname "$target")" || return 1
        cp -a -- "$BACKUP/$label" "$candidate" || {
            rm -rf -- "$candidate"
            return 1
        }
        backup_sha="$(normalized_path_fingerprint "$BACKUP/$label")" || return 1
        candidate_sha="$(normalized_path_fingerprint "$candidate")" || return 1
        if [[ "$candidate_sha" != "$backup_sha" ]]; then
            echo "rollback candidate fingerprint mismatch: $label" >&2
            rm -rf -- "$candidate"
            return 1
        fi
        atomic_replace_candidate "$candidate" "$target" || {
            rm -rf -- "$candidate"
            return 1
        }
        # After RENAME_EXCHANGE this path contains the displaced live item; if
        # target was absent, the candidate path no longer exists.
        rm -rf -- "$candidate" || return 1
    elif [[ -f "$BACKUP/$label.absent" ]]; then
        if [[ -e "$target" || -L "$target" ]]; then
            mv -- "$target" "$tombstone" || return 1
            rm -rf -- "$tombstone" || return 1
        fi
    else
        echo "rollback snapshot marker missing: $label" >&2
        return 1
    fi
    return 0
}

rollback_live() {
    echo 'ROLLBACK_BEGIN' >&2
    set +e
    local rollback_rc=0
    systemctl stop aneb-server >/dev/null 2>&1
    restore_item live-binary /opt/aneb/bin/aneb-server || rollback_rc=1
    restore_item root-profiles /opt/aneb/profiles || rollback_rc=1
    restore_item quick-bundle /opt/aneb/execution-profiles/token_multimodal_quick || rollback_rc=1
    restore_item service-unit /etc/systemd/system/aneb-server.service || rollback_rc=1
    if [[ "$SHIP_IP_CERT" == "1" ]]; then
        restore_item ip-cert /opt/aneb/tls/ip/cert.pem || rollback_rc=1
        restore_item ip-key /opt/aneb/tls/ip/key.pem || rollback_rc=1
    fi
    systemctl daemon-reload || rollback_rc=1
    systemctl restart aneb-server || rollback_rc=1
    systemctl is-active --quiet aneb-server || rollback_rc=1
    if [[ $rollback_rc -eq 0 ]]; then
        wait_live_server || rollback_rc=1
    fi
    if [[ $rollback_rc -eq 0 ]]; then
        validate_server_identity 'aneb-server/0.7.0' rollback || rollback_rc=1
    fi
    if [[ $rollback_rc -eq 0 ]]; then
        ( set -Eeuo pipefail; validate_legacy_surface rollback-0.7 )
        local legacy_smoke_rc=$?
        if [[ $legacy_smoke_rc -ne 0 ]]; then
            rollback_rc=1
        fi
    fi
    if [[ $rollback_rc -eq 0 ]]; then
        assert_restored_aneb_baseline || rollback_rc=1
    fi
    if [[ $rollback_rc -eq 0 ]]; then
        assert_shared_host_baseline rollback || rollback_rc=1
    fi
    if [[ $rollback_rc -ne 0 ]]; then
        echo 'ROLLBACK_FAILED verification=identity+legacy_surface+fingerprints exit=97' >&2
        return 1
    fi
    echo 'ROLLBACK_OK version=0.7.0 legacy_surface=pass fingerprints=match' >&2
    return 0
}

prune_backups() {
    python3 - "$BACKUP_ROOT" <<'PY'
from pathlib import Path
import re
import shutil
import sys

root = Path(sys.argv[1]).resolve()
if root != Path('/opt/aneb/backups'):
    raise SystemExit('unsafe backup root')
pattern = re.compile(r'aneb-deploy-[0-9]{14}-[0-9a-f]{32}')
items = [item for item in root.iterdir() if item.is_dir() and pattern.fullmatch(item.name)]
items.sort(key=lambda item: (item.stat().st_mtime_ns, item.name), reverse=True)
for item in items[3:]:
    resolved = item.resolve()
    if resolved.parent != root or not pattern.fullmatch(resolved.name):
        raise SystemExit(f'unsafe backup path: {resolved}')
    shutil.rmtree(resolved)
PY
}

persist_stage_evidence() {
    local outcome="${1:?evidence outcome required}"
    python3 - \
        "$STAGE" "$EVIDENCE_ROOT" "$EVIDENCE" "$DEPLOY_ID" "$outcome" \
        "$STAGED_BINARY_SHA" "$STAGED_RECEIPT_SHA" \
        "$LIVE_BINARY_SHA" "$LIVE_RECEIPT_SHA" "$LIVE_TOUCHED" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import time
from datetime import datetime, timezone

(
    stage_arg,
    root_arg,
    evidence_arg,
    deploy_id,
    outcome,
    staged_binary_sha,
    staged_receipt_sha,
    live_binary_sha,
    live_receipt_sha,
    live_touched,
) = sys.argv[1:]

deploy_pattern = re.compile(r'[0-9]{14}-[0-9a-f]{32}')
if not deploy_pattern.fullmatch(deploy_id):
    raise SystemExit('unsafe evidence deployment id')
if outcome not in {
    'staged_validated',
    'success',
    'failed',
    'failed_rolled_back',
    'rollback_failed',
}:
    raise SystemExit('invalid evidence outcome')
for label, value in {
    'staged_binary_sha': staged_binary_sha,
    'staged_receipt_sha': staged_receipt_sha,
    'live_binary_sha': live_binary_sha,
    'live_receipt_sha': live_receipt_sha,
}.items():
    if value and not re.fullmatch(r'[0-9a-f]{64}', value):
        raise SystemExit(f'invalid {label}')
if live_touched not in {'0', '1'}:
    raise SystemExit('invalid live_touched marker')

stage = Path(stage_arg)
root = Path(root_arg)
evidence = Path(evidence_arg)
expected_stage = Path('/tmp') / f'aneb-deploy-{deploy_id}'
expected_root = Path('/var/lib/aneb-deploy-evidence')
expected_evidence = expected_root / f'aneb-deploy-{deploy_id}'
if stage != expected_stage or root != expected_root or evidence != expected_evidence:
    raise SystemExit('unsafe evidence path')
if not stage.is_dir() or stage.is_symlink() or stage.resolve() != expected_stage:
    raise SystemExit('unsafe or missing staging directory')

root.mkdir(mode=0o700, parents=True, exist_ok=True)
if root.is_symlink() or root.resolve() != expected_root:
    raise SystemExit('unsafe evidence root')
os.chmod(root, 0o700)

secret_pattern = re.compile(
    r'(?i)(?:\.ssh[/\\]|authorization\s*[:=]|'
    r'(?:password|secret|private[ _-]?key)\s*[:=]|https?://[^\s/@:]+:[^\s/@]+@)'
)
pem_pattern = re.compile(
    r'-----BEGIN [^-\r\n]+-----.*?-----END [^-\r\n]+-----',
    flags=re.IGNORECASE | re.DOTALL,
)

def read_regular(path, limit):
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or path.is_symlink():
        raise SystemExit(f'evidence source must be a regular file: {path.name}')
    if metadata.st_size > limit:
        raise SystemExit(f'evidence source exceeds bound: {path.name}')
    return path.read_bytes()

def checked_payload(relative, limit):
    path = stage / relative
    data = read_regular(path, limit)
    text = data.decode('utf-8', errors='strict')
    if pem_pattern.search(text) or secret_pattern.search(text):
        raise SystemExit(f'sensitive content rejected from evidence: {relative}')
    return data

def redacted_candidate_log():
    path = stage / 'candidate.log'
    if not path.exists():
        return b''
    raw = read_regular(path, 1024 * 1024)
    text = raw.decode('utf-8', errors='replace')
    text = pem_pattern.sub('[REDACTED PEM BLOCK]', text)
    text = re.sub(
        r'(?im)\b(authorization|password|secret|token|private[ _-]?key)\b'
        r'(\s*[:=]\s*)\S+',
        r'\1\2[REDACTED]',
        text,
    )
    text = re.sub(r'https?://[^\s/@:]+:[^\s/@]+@', 'https://[REDACTED]@', text)
    return text.encode('utf-8')[:262144]

def unavailable_payload(relative):
    if relative.endswith('.json'):
        return (
            json.dumps(
                {'status': 'unavailable', 'source': relative},
                sort_keys=True,
                separators=(',', ':'),
            )
            + '\n'
        ).encode('ascii')
    return f'status=unavailable source={relative}\n'.encode('ascii')

def stage_payload(relative, limit, *, required):
    path = stage / relative
    if not path.exists():
        if required:
            raise SystemExit(f'required evidence source is missing: {relative}')
        return unavailable_payload(relative)
    data = checked_payload(relative, limit)
    if relative.endswith('.json'):
        json.loads(data.decode('utf-8'))
    return data

def base_payloads(*, staged_required):
    return {
        'build-provenance.json': checked_payload('build-provenance.json', 262144),
        'go-buildinfo.json': checked_payload('go-buildinfo.json', 262144),
        'staged-serverinfo.json': stage_payload(
            'staged-serverinfo.json', 262144, required=staged_required
        ),
        'staged-serverinfo.headers': stage_payload(
            'staged-serverinfo.headers', 65536, required=staged_required
        ),
        'candidate.log': redacted_candidate_log(),
        'artifact-manifest.sha256': checked_payload(
            'artifact-manifest.sha256', 262144
        ),
    }

def terminal_payloads():
    required = outcome == 'success'
    payloads = base_payloads(staged_required=required)
    for relative, limit in {
        'pre-switch-serverinfo.json': 262144,
        'pre-switch-serverinfo.headers': 65536,
        'live-serverinfo.json': 262144,
        'live-serverinfo.headers': 65536,
        'pre-switch-fingerprints.txt': 65536,
        'live-fingerprints.txt': 65536,
        'live-artifact-manifest.sha256': 262144,
    }.items():
        payloads[relative] = stage_payload(
            relative, limit, required=required
        )
    return payloads

def summary_bytes(payloads):
    files = {
        name: {
            'bytes': len(data),
            'sha256': hashlib.sha256(data).hexdigest(),
        }
        for name, data in sorted(payloads.items())
    }
    summary = {
        'schema_version': 'aneb-deploy-evidence-v1',
        'deployment_id': deploy_id,
        'recorded_at_utc': datetime.now(timezone.utc).isoformat(),
        'outcome': outcome,
        'live_touched_at_record': live_touched == '1',
        'staged_binary_sha256': staged_binary_sha or None,
        'staged_receipt_sha256': staged_receipt_sha or None,
        'live_binary_sha256': live_binary_sha or None,
        'live_receipt_sha256': live_receipt_sha or None,
        'files': files,
    }
    return (
        json.dumps(summary, sort_keys=True, separators=(',', ':'), ensure_ascii=True)
        + '\n'
    ).encode('ascii')

def write_file(directory, name, data):
    path = directory / name
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, 'O_NOFOLLOW'):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    try:
        with os.fdopen(descriptor, 'wb', closefd=False) as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
    finally:
        os.close(descriptor)
    os.chmod(path, 0o600)

def sync_directory(path):
    flags = os.O_RDONLY
    if hasattr(os, 'O_DIRECTORY'):
        flags |= os.O_DIRECTORY
    descriptor = os.open(path, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)

def verify_record(directory, expected_outcome):
    if directory.is_symlink() or not directory.is_dir():
        raise SystemExit('unsafe evidence record')
    complete = read_regular(directory / 'COMPLETE', 256).decode('ascii')
    if complete != expected_outcome + '\n':
        raise SystemExit('evidence COMPLETE mismatch')
    summary = json.loads(read_regular(directory / 'summary.json', 262144))
    if summary.get('outcome') != expected_outcome:
        raise SystemExit('evidence summary outcome mismatch')
    files = summary.get('files')
    if not isinstance(files, dict):
        raise SystemExit('evidence file manifest missing')
    actual_names = {
        item.name
        for item in directory.iterdir()
        if item.name not in {'summary.json', 'COMPLETE'}
    }
    if actual_names != set(files):
        raise SystemExit('evidence payload set mismatch')
    for name, metadata in files.items():
        if '/' in name or name in {'.', '..'}:
            raise SystemExit('unsafe evidence payload name')
        data = read_regular(directory / name, 1024 * 1024)
        if metadata != {
            'bytes': len(data),
            'sha256': hashlib.sha256(data).hexdigest(),
        }:
            raise SystemExit(f'evidence payload digest mismatch: {name}')

record_pattern = re.compile(
    r'record-([0-9]{6})-(staged_validated|success|failed|failed_rolled_back|rollback_failed)'
)
orphan_pattern = re.compile(
    r'\.aneb-deploy-([0-9]{14}-[0-9a-f]{32})\.tmp-[A-Za-z0-9_-]+'
)
record_orphan_pattern = re.compile(
    r'\.record-[0-9]{6}-(?:staged_validated|success|failed|failed_rolled_back|rollback_failed)\.tmp-[A-Za-z0-9_-]+'
)

if outcome == 'staged_validated':
    if evidence.exists() or evidence.is_symlink():
        raise SystemExit('evidence deployment id already exists')
    payloads = base_payloads(staged_required=True)
    temporary = Path(tempfile.mkdtemp(dir=root, prefix=f'.aneb-deploy-{deploy_id}.tmp-'))
    try:
        os.chmod(temporary, 0o700)
        for name, data in sorted(payloads.items()):
            write_file(temporary, name, data)
        write_file(temporary, 'summary.json', summary_bytes(payloads))
        # COMPLETE is deliberately written last inside the transaction.
        write_file(temporary, 'COMPLETE', (outcome + '\n').encode('ascii'))
        sync_directory(temporary)
        os.replace(temporary, evidence)
        sync_directory(root)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
else:
    if evidence.is_symlink() or not evidence.is_dir() or evidence.resolve() != expected_evidence:
        raise SystemExit('safe staged evidence is required before terminal evidence')
    if read_regular(evidence / 'COMPLETE', 256) != b'staged_validated\n':
        raise SystemExit('staged evidence is incomplete')
    records = evidence / 'records'
    records.mkdir(mode=0o700, exist_ok=True)
    if records.is_symlink() or not records.is_dir() or records.resolve().parent != evidence:
        raise SystemExit('unsafe evidence records directory')
    os.chmod(records, 0o700)
    existing_records = [
        item
        for item in records.iterdir()
        if item.is_dir() and not item.is_symlink() and record_pattern.fullmatch(item.name)
    ]
    sequence = max(
        (int(record_pattern.fullmatch(item.name).group(1)) for item in existing_records),
        default=0,
    ) + 1
    if sequence > 999999:
        raise SystemExit('evidence record sequence exhausted')
    target = records / f'record-{sequence:06d}-{outcome}'
    payloads = terminal_payloads()
    temporary = Path(
        tempfile.mkdtemp(
            dir=records,
            prefix=f'.record-{sequence:06d}-{outcome}.tmp-',
        )
    )
    try:
        os.chmod(temporary, 0o700)
        for name, data in sorted(payloads.items()):
            write_file(temporary, name, data)
        write_file(temporary, 'summary.json', summary_bytes(payloads))
        write_file(temporary, 'COMPLETE', (outcome + '\n').encode('ascii'))
        sync_directory(temporary)
        os.replace(temporary, target)
        sync_directory(records)
        verify_record(target, outcome)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)

pattern = re.compile(r'aneb-deploy-[0-9]{14}-[0-9a-f]{32}')
items = [
    item
    for item in root.iterdir()
    if item.is_dir() and not item.is_symlink() and pattern.fullmatch(item.name)
]
items.sort(key=lambda item: (item.stat().st_mtime_ns, item.name), reverse=True)
for item in items[10:]:
    resolved = item.resolve()
    if resolved.parent != root or not pattern.fullmatch(resolved.name):
        raise SystemExit(f'unsafe retained evidence path: {resolved}')
    shutil.rmtree(resolved)

# A killed publisher can leave only its owned hidden temporary directory. Prune
# stale (>24h) root/record temporaries, never symlinks, fresh entries, or names
# outside the strict deployment namespace.
cutoff = time.time() - 24 * 60 * 60
for item in list(root.iterdir()):
    try:
        metadata = item.lstat()
    except FileNotFoundError:
        continue
    if (
        stat.S_ISDIR(metadata.st_mode)
        and not item.is_symlink()
        and orphan_pattern.fullmatch(item.name)
        and metadata.st_mtime < cutoff
    ):
        resolved = item.resolve()
        if resolved.parent == root:
            shutil.rmtree(resolved)
records = evidence / 'records'
if records.is_dir() and not records.is_symlink():
    for item in list(records.iterdir()):
        try:
            metadata = item.lstat()
        except FileNotFoundError:
            continue
        if (
            stat.S_ISDIR(metadata.st_mode)
            and not item.is_symlink()
            and record_orphan_pattern.fullmatch(item.name)
            and metadata.st_mtime < cutoff
        ):
            resolved = item.resolve()
            if resolved.parent == records:
                shutil.rmtree(resolved)
sync_directory(root)
PY
}

verify_terminal_evidence() {
    local expected_outcome="${1:?terminal evidence outcome required}"
    python3 - "$EVIDENCE" "$expected_outcome" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import stat
import sys

evidence = Path(sys.argv[1])
expected = sys.argv[2]
if expected not in {'success', 'failed', 'failed_rolled_back', 'rollback_failed'}:
    raise SystemExit('invalid terminal evidence outcome')
records = evidence / 'records'
pattern = re.compile(
    r'record-([0-9]{6})-(success|failed|failed_rolled_back|rollback_failed)'
)
items = [
    item for item in records.iterdir()
    if item.is_dir() and not item.is_symlink() and pattern.fullmatch(item.name)
]
if not items:
    raise SystemExit('terminal evidence record is missing')
latest = max(items, key=lambda item: int(pattern.fullmatch(item.name).group(1)))
if pattern.fullmatch(latest.name).group(2) != expected:
    raise SystemExit('terminal evidence latest outcome mismatch')
if (latest / 'COMPLETE').read_text(encoding='ascii') != expected + '\n':
    raise SystemExit('terminal evidence COMPLETE mismatch')
summary = json.loads((latest / 'summary.json').read_text(encoding='ascii'))
if summary.get('outcome') != expected:
    raise SystemExit('terminal evidence summary mismatch')
files = summary.get('files')
if not isinstance(files, dict):
    raise SystemExit('terminal evidence file manifest missing')
for name, metadata in files.items():
    path = latest / name
    file_metadata = path.lstat()
    if not stat.S_ISREG(file_metadata.st_mode) or path.is_symlink():
        raise SystemExit(f'unsafe terminal evidence payload: {name}')
    data = path.read_bytes()
    if metadata != {
        'bytes': len(data),
        'sha256': hashlib.sha256(data).hexdigest(),
    }:
        raise SystemExit(f'terminal evidence digest mismatch: {name}')
if expected == 'success':
    required = {
        'pre-switch-serverinfo.json',
        'pre-switch-serverinfo.headers',
        'staged-serverinfo.json',
        'staged-serverinfo.headers',
        'live-serverinfo.json',
        'live-serverinfo.headers',
        'pre-switch-fingerprints.txt',
        'live-fingerprints.txt',
        'live-artifact-manifest.sha256',
    }
    if not required.issubset(files):
        raise SystemExit('terminal success evidence is incomplete')
PY
}

commit_terminal_evidence() {
    local outcome="${1:?terminal evidence outcome required}"
    persist_stage_evidence "$outcome" || return 1
    verify_terminal_evidence "$outcome" || return 1
    FINAL_EVIDENCE_COMMITTED=1
}

cleanup() {
    local rc=$?
    local cleanup_failed=0
    local evidence_failed=0
    trap - EXIT
    trap '' HUP INT TERM
    set +e
    stop_staged_server
    if [[ $rc -ne 0 && $LIVE_TOUCHED -eq 1 ]]; then
        ( rollback_live ) || ROLLBACK_FAILED=1
    fi
    set +e
    rm -f -- "$STAGE/tls/ip-key.pem" || cleanup_failed=1
    if [[ $ROLLBACK_FAILED -ne 0 ]]; then
        DEPLOY_RESULT='rollback_failed'
    elif [[ $rc -ne 0 && $LIVE_TOUCHED -eq 1 ]]; then
        DEPLOY_RESULT='failed_rolled_back'
    elif [[ $rc -ne 0 ]]; then
        DEPLOY_RESULT='failed'
    fi
    if [[ $FINAL_EVIDENCE_COMMITTED -eq 0 ]]; then
        commit_terminal_evidence "$DEPLOY_RESULT" || evidence_failed=1
    fi
    rm -rf -- \
        "/opt/aneb/bin/aneb-server.new-$DEPLOY_ID" \
        "/opt/aneb/profiles.new-$DEPLOY_ID" \
        "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID" \
        "/etc/systemd/system/aneb-server.service.new-$DEPLOY_ID" \
        "/opt/aneb/tls/ip/cert.pem.new-$DEPLOY_ID" \
        "/opt/aneb/tls/ip/key.pem.new-$DEPLOY_ID" \
        "/opt/aneb/bin/aneb-server.restore-$DEPLOY_ID" \
        "/opt/aneb/bin/aneb-server.absent-$DEPLOY_ID" \
        "/opt/aneb/profiles.restore-$DEPLOY_ID" \
        "/opt/aneb/profiles.absent-$DEPLOY_ID" \
        "/opt/aneb/execution-profiles/token_multimodal_quick.restore-$DEPLOY_ID" \
        "/opt/aneb/execution-profiles/token_multimodal_quick.absent-$DEPLOY_ID" \
        "/etc/systemd/system/aneb-server.service.restore-$DEPLOY_ID" \
        "/etc/systemd/system/aneb-server.service.absent-$DEPLOY_ID" \
        "/opt/aneb/tls/ip/cert.pem.restore-$DEPLOY_ID" \
        "/opt/aneb/tls/ip/cert.pem.absent-$DEPLOY_ID" \
        "/opt/aneb/tls/ip/key.pem.restore-$DEPLOY_ID" \
        "/opt/aneb/tls/ip/key.pem.absent-$DEPLOY_ID" || cleanup_failed=1
    if [[ -d "$BACKUP_ROOT" ]]; then
        prune_backups || echo 'WARNING backup_prune_failed maintenance_required=1' >&2
    fi
    if rm -rf -- "$STAGE"; then
        cancel_cleanup_watchdog
    else
        cleanup_failed=1
        echo 'STAGE_CLEANUP_FAILED watchdog_retained=1' >&2
    fi
    if [[ $ROLLBACK_FAILED -ne 0 ]]; then
        exit 97
    fi
    if [[ $evidence_failed -ne 0 ]]; then
        echo 'DEPLOY_EVIDENCE_PERSIST_FAILED terminal=1 maintenance_required=1' >&2
        if [[ $rc -eq 0 ]]; then
            exit 98
        fi
    fi
    if [[ $cleanup_failed -ne 0 ]]; then
        echo 'DEPLOY_CLEANUP_FAILED maintenance_required=1' >&2
        if [[ $rc -eq 0 ]]; then
            exit 99
        fi
    fi
    if [[ $rc -eq 0 ]]; then
        if [[ $FINAL_EVIDENCE_COMMITTED -ne 1 || -z "$DEPLOY_SUCCESS_MESSAGE" ]]; then
            echo 'DEPLOY_SUCCESS_GATE_INCOMPLETE exit=98' >&2
            exit 98
        fi
        echo "$DEPLOY_SUCCESS_MESSAGE"
    fi
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
trap 'exit 129' HUP

snapshot_item() {
    local label="$1"
    local source="$2"
    if [[ -e "$source" ]]; then
        : > "$BACKUP/$label.present"
        cp -a -- "$source" "$BACKUP/$label"
    else
        : > "$BACKUP/$label.absent"
    fi
}

choose_loopback_port() {
    python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
}

validate_receipt() {
    local base_url="$1"
    local manifest="$2"
    local output="$3"
    local expected_h3="$4"
    local receipt_sha_path="$5"
    local headers="${output%.json}.headers"
    curl -fksS --connect-timeout 2 --max-time 10 \
        -D "$headers" -o "$output" "$base_url/api/v1/serverinfo"
    python3 - "$output" "$manifest" "$expected_h3" "$receipt_sha_path" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile

def reject_duplicate_members(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON member: {key!r}')
        result[key] = value
    return result

def reject_constant(value):
    raise ValueError(f'non-finite JSON number: {value}')

body = json.loads(
    Path(sys.argv[1]).read_text(encoding='utf-8'),
    object_pairs_hook=reject_duplicate_members,
    parse_constant=reject_constant,
)
if not isinstance(body, dict):
    raise SystemExit('serverinfo body must be an object')
if body.get('version') != 'aneb-server/0.8.0':
    raise SystemExit(f'unexpected server version: {body.get("version")!r}')
expected_h3_text = sys.argv[3]
if expected_h3_text not in {'true', 'false'}:
    raise SystemExit('invalid expected h3 marker')
expected_h3 = expected_h3_text == 'true'
if body.get('h3_enabled') is not expected_h3:
    raise SystemExit(
        f'unexpected h3_enabled: {body.get("h3_enabled")!r} != {expected_h3!r}'
    )
receipt = body.get('execution_capabilities')
if not isinstance(receipt, dict):
    raise SystemExit('execution capability receipt missing')
expected_receipt_keys = {
    'contract_id',
    'contract_version',
    'primitives',
    'validated_profiles',
}
if set(receipt) != expected_receipt_keys:
    raise SystemExit(f'unexpected receipt structure: {set(receipt)!r}')
if receipt['contract_id'] != 'aneb-server-capability-receipt':
    raise SystemExit('receipt contract id mismatch')
if receipt['contract_version'] != '1.0.0':
    raise SystemExit('receipt contract version mismatch')
expected_primitives = [
    {'primitive_id': 'download', 'wire_contract_id': 'aneb-download-v1'},
    {'primitive_id': 'echo', 'wire_contract_id': 'aneb-echo-v1'},
    {'primitive_id': 'token_sim', 'wire_contract_id': 'aneb-token-task-v1'},
]
actual_primitives = receipt['primitives']
if not isinstance(actual_primitives, list):
    raise SystemExit('receipt primitives must be an array')
for item in actual_primitives:
    if not isinstance(item, dict) or set(item) != {'primitive_id', 'wire_contract_id'}:
        raise SystemExit(f'unexpected primitive structure: {item!r}')
if actual_primitives != expected_primitives:
    raise SystemExit(f'primitive receipt mismatch: {actual_primitives!r}')

manifest_entries = {}
manifest_pattern = re.compile(r'([0-9a-f]{64})  ([A-Za-z0-9_.-]+)')
manifest_lines = Path(sys.argv[2]).read_text(encoding='utf-8').splitlines()
for line in manifest_lines:
    match = manifest_pattern.fullmatch(line)
    if match is None or match.group(2) in manifest_entries:
        raise SystemExit('invalid or duplicate manifest entry')
    manifest_entries[match.group(2)] = match.group(1)
if set(manifest_entries) != {'profile.json', 'runtime_plan.json'}:
    raise SystemExit(f'unexpected manifest coverage: {set(manifest_entries)!r}')

profiles = receipt['validated_profiles']
if not isinstance(profiles, list) or len(profiles) != 1:
    raise SystemExit(f'unexpected validated profiles: {profiles!r}')
profile = profiles[0]
expected = {
    'profile_id': 'token_multimodal_quick',
    'profile_version': '1.2.1',
    'profile_sha256': 'sha256:' + manifest_entries['profile.json'],
}
if profile != expected:
    raise SystemExit(f'validated profile mismatch: {profile!r} != {expected!r}')
canonical = json.dumps(
    receipt,
    sort_keys=True,
    separators=(',', ':'),
    ensure_ascii=True,
).encode('ascii')
receipt_sha = hashlib.sha256(canonical).hexdigest()
receipt_sha_path = Path(sys.argv[4])
descriptor, temporary_name = tempfile.mkstemp(
    dir=receipt_sha_path.parent,
    prefix=f'.{receipt_sha_path.name}.',
    suffix='.tmp',
)
temporary = Path(temporary_name)
try:
    with os.fdopen(descriptor, 'w', encoding='ascii', newline='\n') as handle:
        handle.write(receipt_sha + '\n')
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, receipt_sha_path)
finally:
    if temporary.exists():
        temporary.unlink()
print(
    'execution_receipt=verified '
    f'profile=token_multimodal_quick@1.2.1 receipt_sha256={receipt_sha}'
)
PY
}

test -d "$STAGE"
test -x /usr/bin/python3
test -x /usr/bin/curl
command -v runuser >/dev/null 2>&1
command -v setsid >/dev/null 2>&1
command -v go >/dev/null 2>&1
command -v awk >/dev/null 2>&1
command -v cmp >/dev/null 2>&1
command -v iptables-save >/dev/null 2>&1
command -v ip6tables-save >/dev/null 2>&1
command -v tc >/dev/null 2>&1
test -f "$STAGE/aneb-server-linux"
test -f "$STAGE/aneb-server.service"
test -f "$STAGE/build-provenance.json"
test -f "$STAGE/go-buildinfo.json"
test -f "$STAGE/artifact-manifest.sha256"
test -f "$STAGE/execution-profiles/token_multimodal_quick/profile.json"
test -f "$STAGE/execution-profiles/token_multimodal_quick/runtime_plan.json"
test -f "$STAGE/execution-profiles/token_multimodal_quick/manifest.sha256"
validate_uploaded_artifacts
STAGED_BINARY_SHA="$(file_sha256 "$STAGE/aneb-server-linux")"
[[ "$STAGED_BINARY_SHA" =~ ^[0-9a-f]{64}$ ]]
validate_build_evidence
if [[ "$SHIP_IP_CERT" == "1" ]]; then
    command -v openssl >/dev/null 2>&1
    test -f "$STAGE/tls/ip-cert.pem"
    test -f "$STAGE/tls/ip-key.pem"
    [[ "$(file_sha256 "$STAGE/tls/ip-cert.pem")" == "$EXPECTED_IP_CERT_SHA" ]]
    [[ "$(file_sha256 "$STAGE/tls/ip-key.pem")" == "$EXPECTED_IP_KEY_SHA" ]]
    validate_ip_certificate_bundle
fi

# Upgrade is fail-closed: an existing healthy ANEB service is the rollback baseline.
id -u aneb >/dev/null 2>&1
systemctl is-active --quiet aneb-server
test -f /opt/aneb/bin/aneb-server
test -d /opt/aneb/profiles
test -f /etc/systemd/system/aneb-server.service

chmod 0755 "$STAGE" "$STAGE/aneb-server-linux"
chmod -R a+rX "$STAGE/root-profiles" "$STAGE/execution-profiles"
install -d -m 0750 -o aneb -g aneb "$STAGE/data"

STAGE_PORT="$(choose_loopback_port)"
setsid runuser -u aneb -- "$STAGE/aneb-server-linux" \
    -addr "127.0.0.1:$STAGE_PORT" \
    -profiles "$STAGE/root-profiles" \
    -execution-profiles "$STAGE/execution-profiles" \
    -data "$STAGE/data" \
    -udp-echo-addr '' \
    >"$STAGE/candidate.log" 2>&1 &
STAGE_PID=$!

STAGE_READY=0
for _ in $(seq 1 50); do
    if ! kill -0 "$STAGE_PID" >/dev/null 2>&1; then
        cat "$STAGE/candidate.log" >&2
        exit 1
    fi
    if curl -fsS --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:$STAGE_PORT/api/v1/serverinfo" >/dev/null 2>&1; then
        STAGE_READY=1
        break
    fi
    sleep 0.1
done
if [[ $STAGE_READY -ne 1 ]]; then
    cat "$STAGE/candidate.log" >&2
    exit 1
fi
validate_receipt \
    "http://127.0.0.1:$STAGE_PORT" \
    "$STAGE/execution-profiles/token_multimodal_quick/manifest.sha256" \
    "$STAGE/staged-serverinfo.json" \
    false \
    "$STAGE/staged-receipt.sha256"
STAGED_RECEIPT_SHA="$(cat "$STAGE/staged-receipt.sha256")"
[[ "$STAGED_RECEIPT_SHA" =~ ^[0-9a-f]{64}$ ]]
echo "STAGING_OK loopback_port=$STAGE_PORT"
stop_staged_server

# Durable, redacted staging evidence is a hard gate. The private key is never
# copied into evidence; if persistence fails, delete its staged copy before exit.
if ! persist_stage_evidence staged_validated; then
    rm -f -- "$STAGE/tls/ip-key.pem"
    echo 'DEPLOY_EVIDENCE_PERSIST_FAILED live_touched=0 staged_private_key=removed' >&2
    exit 96
fi

# Nothing above this line mutates the live ANEB installation.
# Freeze the exact 0.7 rollback identity and every shared-host surface before
# replacing any ANEB-owned live file. PID is intentionally not frozen because
# both upgrade and rollback restart the ANEB service.
freeze_live_baseline
install -d -m 0700 "$BACKUP_ROOT"
mkdir -m 0700 "$BACKUP"
snapshot_item live-binary /opt/aneb/bin/aneb-server
snapshot_item root-profiles /opt/aneb/profiles
snapshot_item quick-bundle /opt/aneb/execution-profiles/token_multimodal_quick
snapshot_item service-unit /etc/systemd/system/aneb-server.service
if [[ "$SHIP_IP_CERT" == "1" ]]; then
    test -f "$STAGE/tls/ip-cert.pem"
    test -f "$STAGE/tls/ip-key.pem"
    snapshot_item ip-cert /opt/aneb/tls/ip/cert.pem
    snapshot_item ip-key /opt/aneb/tls/ip/key.pem
fi

LIVE_TOUCHED=1

install -m 0755 "$STAGE/aneb-server-linux" \
    "/opt/aneb/bin/aneb-server.new-$DEPLOY_ID"
atomic_replace_candidate \
    "/opt/aneb/bin/aneb-server.new-$DEPLOY_ID" /opt/aneb/bin/aneb-server
rm -rf -- "/opt/aneb/bin/aneb-server.new-$DEPLOY_ID"
LIVE_BINARY_SHA="$(file_sha256 /opt/aneb/bin/aneb-server)"
[[ "$LIVE_BINARY_SHA" == "$STAGED_BINARY_SHA" ]] || {
    echo "LIVE_BINARY_MISMATCH staged=$STAGED_BINARY_SHA live=$LIVE_BINARY_SHA" >&2
    exit 1
}

cp -a -- "$STAGE/root-profiles" "/opt/aneb/profiles.new-$DEPLOY_ID"
chown -R aneb:aneb "/opt/aneb/profiles.new-$DEPLOY_ID"
atomic_replace_candidate "/opt/aneb/profiles.new-$DEPLOY_ID" /opt/aneb/profiles
rm -rf -- "/opt/aneb/profiles.new-$DEPLOY_ID"

install -d -m 0755 /opt/aneb/execution-profiles
cp -a -- "$STAGE/execution-profiles/token_multimodal_quick" \
    "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID"
chown -R aneb:aneb "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID"
atomic_replace_candidate \
    "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID" \
    /opt/aneb/execution-profiles/token_multimodal_quick
rm -rf -- "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID"

install -m 0644 "$STAGE/aneb-server.service" \
    "/etc/systemd/system/aneb-server.service.new-$DEPLOY_ID"
atomic_replace_candidate "/etc/systemd/system/aneb-server.service.new-$DEPLOY_ID" \
    /etc/systemd/system/aneb-server.service
rm -rf -- "/etc/systemd/system/aneb-server.service.new-$DEPLOY_ID"

if [[ "$SHIP_IP_CERT" == "1" ]]; then
    install -d -m 0750 -o aneb -g aneb /opt/aneb/tls/ip
    install -m 0644 -o aneb -g aneb "$STAGE/tls/ip-cert.pem" \
        "/opt/aneb/tls/ip/cert.pem.new-$DEPLOY_ID"
    install -m 0600 -o aneb -g aneb "$STAGE/tls/ip-key.pem" \
        "/opt/aneb/tls/ip/key.pem.new-$DEPLOY_ID"
    atomic_replace_candidate "/opt/aneb/tls/ip/cert.pem.new-$DEPLOY_ID" \
        /opt/aneb/tls/ip/cert.pem
    rm -rf -- "/opt/aneb/tls/ip/cert.pem.new-$DEPLOY_ID"
    atomic_replace_candidate "/opt/aneb/tls/ip/key.pem.new-$DEPLOY_ID" \
        /opt/aneb/tls/ip/key.pem
    rm -rf -- "/opt/aneb/tls/ip/key.pem.new-$DEPLOY_ID"
fi

validate_live_artifacts
systemctl daemon-reload
systemctl restart aneb-server
systemctl is-active --quiet aneb-server

if ! wait_live_server; then
    journalctl -u aneb-server -n 80 --no-pager >&2 || true
    exit 1
fi

validate_server_identity 'aneb-server/0.8.0' live
validate_receipt \
    'https://127.0.0.1:8443' \
    /opt/aneb/execution-profiles/token_multimodal_quick/manifest.sha256 \
    "$STAGE/live-serverinfo.json" \
    true \
    "$STAGE/live-receipt.sha256"
LIVE_RECEIPT_SHA="$(cat "$STAGE/live-receipt.sha256")"
[[ "$LIVE_RECEIPT_SHA" == "$STAGED_RECEIPT_SHA" ]] || {
    echo "LIVE_RECEIPT_MISMATCH staged=$STAGED_RECEIPT_SHA live=$LIVE_RECEIPT_SHA" >&2
    exit 1
}

curl -fksS --connect-timeout 2 --max-time 10 \
    https://127.0.0.1:8443/api/v1/profiles -o "$STAGE/live-profiles.json"
python3 - "$STAGE/live-profiles.json" <<'PY'
import json
from pathlib import Path
import sys

body = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
profiles = {item['profile_id']: item for item in body['profiles']}
if set(profiles) != {'basic_network', 's1_chat', 's2_coding_agent', 's3_multimodal'}:
    raise SystemExit(f'root profile set mismatch: {set(profiles)!r}')
if profiles['s3_multimodal'].get('version') != '0.3.0':
    raise SystemExit('s3_multimodal version mismatch')
s3_phases = profiles['s3_multimodal'].get('phases', [])
expected_phase_types = [
    'clock_sync',
    'upload_burst',
    'think_pause',
    'token_stream',
    'download_burst',
    'upload_burst',
    'think_pause',
    'token_stream',
    'download_burst',
    'clock_sync',
]
if [phase.get('type') for phase in s3_phases] != expected_phase_types:
    raise SystemExit('s3_multimodal phase order mismatch')
for index in (4, 8):
    phase = s3_phases[index]
    if phase.get('bytes') != 12582912 or phase.get('chunk_kb') != 256:
        raise SystemExit(f's3_multimodal download phase {index} mismatch: {phase!r}')
PY

curl -fksS --connect-timeout 2 --max-time 10 -X POST --data 'ping' \
    https://127.0.0.1:8443/api/v1/echo >/dev/null
download_bytes="$(curl -fksS --connect-timeout 2 --max-time 10 \
    'https://127.0.0.1:8443/api/v1/download?bytes=1024' | wc -c)"
[[ "$download_bytes" -eq 1024 ]]

# Preserve the deployed 0.7 weak-network route contract. These are bounded,
# run-scoped application requests; they do not change host network controls.
curl -fksS --connect-timeout 2 --max-time 10 \
    https://127.0.0.1:8443/api/v1/impairments -o "$STAGE/impairments.json"
python3 - "$STAGE/impairments.json" <<'PY'
import json
from pathlib import Path
import sys

body = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
policies = {item['route_id']: item for item in body['policies']}
if set(policies) != {'weak-capacity-latency-v1', 'weak-recovery-v1'}:
    raise SystemExit(f'impairment route set mismatch: {set(policies)!r}')
capacity = policies['weak-capacity-latency-v1']
if capacity.get('contract_version') != 'aneb-synthetic-impairment-v1':
    raise SystemExit('capacity contract version mismatch')
if capacity.get('profile_id') != 'network_comprehensive_weak_capacity_latency':
    raise SystemExit('capacity profile mismatch')
if capacity.get('version') != '1.1.0':
    raise SystemExit('capacity profile version mismatch')
if tuple(capacity.get(key) for key in ('downlink_mbps', 'uplink_mbps', 'added_rtt_ms', 'jitter_ms')) != (3, 1, 120, 30):
    raise SystemExit('capacity parameters mismatch')
recovery = policies['weak-recovery-v1']
if recovery.get('profile_id') != 'network_comprehensive_weak_recovery':
    raise SystemExit('recovery profile mismatch')
if recovery.get('version') != '1.1.0' or recovery.get('outage_duration_ms') != 2000:
    raise SystemExit('recovery timing mismatch')
if tuple(recovery.get(key) for key in ('downlink_mbps', 'uplink_mbps', 'added_rtt_ms', 'jitter_ms')) != (5, 2, 80, 20):
    raise SystemExit('recovery parameters mismatch')
PY

SYNTHETIC_BASE='https://127.0.0.1:8443/synthetic/weak-capacity-latency-v1'
CAPACITY_QUERY="impair_run=deploy-$DEPLOY_ID-capacity&impair_seed=20260718&impair_seq=1"
curl -fksS --connect-timeout 2 --max-time 10 \
    -D "$STAGE/capacity-echo.headers" -o "$STAGE/capacity-echo.json" \
    -X POST --data 'ping' "$SYNTHETIC_BASE/api/v1/echo?$CAPACITY_QUERY"
grep -qi '^X-Aneb-Synthetic-Impairment: network_comprehensive_weak_capacity_latency@1.1.0' \
    "$STAGE/capacity-echo.headers"
python3 - "$STAGE/capacity-echo.json" <<'PY'
import json
from pathlib import Path
import sys
json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
PY
CAPACITY_QUERY="impair_run=deploy-$DEPLOY_ID-capacity-download&impair_seed=20260718&impair_seq=1"
synthetic_bytes="$(curl -fksS --connect-timeout 2 --max-time 15 \
    "$SYNTHETIC_BASE/api/v1/download?bytes=65536&chunk_kb=16&$CAPACITY_QUERY" | wc -c)"
[[ "$synthetic_bytes" -eq 65536 ]]

RECOVERY_BASE='https://127.0.0.1:8443/synthetic/weak-recovery-v1'
RECOVERY_RUN="deploy-$DEPLOY_ID-recovery"
RECOVERY_QUERY="impair_run=$RECOVERY_RUN&impair_seed=20260718&impair_seq=1"
recovery_code="$(curl -ksS --connect-timeout 2 --max-time 10 \
    -D "$STAGE/recovery-trigger.headers" -o "$STAGE/recovery-trigger.json" \
    -w '%{http_code}' -X POST --data '{}' "$RECOVERY_BASE/api/v1/recovery?$RECOVERY_QUERY")"
[[ "$recovery_code" -eq 202 ]]
grep -qi '^X-Aneb-Synthetic-Impairment: network_comprehensive_weak_recovery@1.1.0' \
    "$STAGE/recovery-trigger.headers"
grep -qi '^X-Aneb-Outage-Duration-Ms: 2000' "$STAGE/recovery-trigger.headers"

RECOVERY_QUERY="impair_run=$RECOVERY_RUN&impair_seed=20260718&impair_seq=2"
recovery_code="$(curl -ksS --connect-timeout 2 --max-time 10 \
    -D "$STAGE/recovery-blocked.headers" -o /dev/null -w '%{http_code}' \
    -X POST --data '{}' "$RECOVERY_BASE/api/v1/echo?$RECOVERY_QUERY")"
[[ "$recovery_code" -eq 503 ]]
grep -qi '^X-Aneb-Synthetic-Outage: active' "$STAGE/recovery-blocked.headers"

OTHER_QUERY="impair_run=deploy-$DEPLOY_ID-recovery-other&impair_seed=20260718&impair_seq=1"
other_code="$(curl -ksS --connect-timeout 2 --max-time 10 -o /dev/null -w '%{http_code}' \
    -X POST --data '{}' "$RECOVERY_BASE/api/v1/echo?$OTHER_QUERY")"
[[ "$other_code" -eq 200 ]]
normal_code="$(curl -ksS --connect-timeout 2 --max-time 10 -o /dev/null -w '%{http_code}' \
    -X POST --data '{}' https://127.0.0.1:8443/api/v1/echo)"
[[ "$normal_code" -eq 200 ]]
sleep 3
RECOVERY_QUERY="impair_run=$RECOVERY_RUN&impair_seed=20260718&impair_seq=3"
recovered_code="$(curl -ksS --connect-timeout 2 --max-time 10 -o /dev/null -w '%{http_code}' \
    -X POST --data '{}' "$RECOVERY_BASE/api/v1/echo?$RECOVERY_QUERY")"
[[ "$recovered_code" -eq 200 ]]
echo 'weak_network_smoke=capacity_echo+download,recovery_same503,other200,normal200,recovered200'

python3 - <<'PY'
import socket
import struct
import time

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(2)
    packet = b'ANEB1' + struct.pack('>Iq', 7, time.monotonic_ns()) + bytes(47)
    sock.sendto(packet, ('127.0.0.1', 8443))
    reply, _ = sock.recvfrom(512)
    if reply != packet:
        raise SystemExit('UDP echo payload mismatch')
PY

# Reuse the exact rollback smoke on the accepted candidate as well. The earlier
# focused checks make diagnostics local; this shared gate prevents the success
# and rollback contracts from drifting apart (including the full 1 MiB download).
validate_legacy_surface live-0.8
# This is the final deployment acceptance boundary. A shared-host mismatch is
# treated as a deployment failure while rollback is still armed.
assert_shared_host_baseline live
DEPLOY_RESULT='success'
if ! commit_terminal_evidence success; then
    echo 'FINAL_EVIDENCE_COMMIT_FAILED rollback_armed=1 exit=98' >&2
    exit 98
fi
LIVE_TOUCHED=0
PRUNE_RESULT='ok'
if ! prune_backups; then
    PRUNE_RESULT='warning'
    echo 'WARNING backup_prune_failed maintenance_required=1' >&2
fi
DEPLOY_SUCCESS_MESSAGE="DEPLOY_OK backup=$BACKUP retained_backups=3 backup_prune=$PRUNE_RESULT"
'@

    $remoteScriptPath = Join-Path $LocalStage 'remote_deploy.sh'
    [System.IO.File]::WriteAllText(
        $remoteScriptPath,
        ($remoteScript -replace "`r`n", "`n"),
        (New-Object System.Text.UTF8Encoding($false))
    )
    Copy-ToRemote -LocalPath $remoteScriptPath -RemotePath "$RemoteStage/remote_deploy.sh" -Label 'remote deployment script'

    if ($HaveIpCert) {
        # The private key is the last upload. A remote TTL cleanup fuse is already
        # active, and this handshake proves that its stage and script are intact.
        $keyTransferHandshake = @(
            'set -Eeuo pipefail',
            "test -f '$RemoteStage/.cleanup-watchdog-armed'",
            "systemctl is-active --quiet '$WatchdogUnit.timer'",
            "test -s '$RemoteStage/remote_deploy.sh'",
            ": > '$RemoteStage/.key-transfer-authorized'"
        ) -join '; '
        & ssh @SshOpts $Remote $keyTransferHandshake
        if ($LASTEXITCODE -ne 0) {
            throw "remote private-key transfer handshake failed with exit code $LASTEXITCODE"
        }
        Copy-ToRemote -LocalPath $IpKey -RemotePath "$RemoteStage/tls/ip-key.pem" -Label 'IP-SAN private key'
    }

    Write-Host '== [5/5] staged validation, guarded live restart, and smoke test =='
    $ipMarker = if ($HaveIpCert) { '1' } else { '0' }
    $certPin = if ($HaveIpCert) { $ExpectedIpCertificateSha256.ToLowerInvariant() } else { 'none' }
    $keyPin = if ($HaveIpCert) { $ExpectedIpPrivateKeySha256.ToLowerInvariant() } else { 'none' }
    & ssh @SshOpts $Remote "bash '$RemoteStage/remote_deploy.sh' '$DeploymentId' '$ipMarker' '$certPin' '$keyPin' '$ArtifactManifestSha'"
    if ($LASTEXITCODE -ne 0) {
        throw "guarded remote deployment failed with exit code $LASTEXITCODE"
    }
    Write-Host '== deploy complete =='
}
finally {
    if ($RemoteStageCreated -and -not $RemoteCleanupWatchdogArmed) {
        $cleanupCommand = "case '$RemoteStage' in /tmp/aneb-deploy-[0-9]*) if rm -rf -- '$RemoteStage'; then systemctl stop '$WatchdogUnit.timer' '$WatchdogUnit.service' >/dev/null 2>&1 || true; else exit 99; fi ;; *) exit 2 ;; esac"
        & ssh @SshOpts $Remote $cleanupCommand 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Remote staging cleanup could not be confirmed: $RemoteStage"
        }
    }
    if (Test-Path -LiteralPath $LocalStage) {
        $tempRoot = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
        $localStageFull = [System.IO.Path]::GetFullPath($LocalStage)
        if (-not $localStageFull.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean local staging path outside TEMP: $localStageFull"
        }
        Remove-Item -LiteralPath $localStageFull -Recurse -Force
    }
}
