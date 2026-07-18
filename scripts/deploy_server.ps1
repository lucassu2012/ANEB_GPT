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
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{32}$')]
    [string]$LeaseId,
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
$StatusUpdater = Join-Path $PSScriptRoot 'update_shared_test_status.py'
$SharedProjectRoot = Split-Path -Parent (Split-Path -Parent $RepoRoot)
$SharedTestStatus = Join-Path $SharedProjectRoot 'SHARED_TEST_STATUS.md'

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

function Invoke-LocalSafetyGates {
    Write-Host '== [1/5] local safety gates =='

    Assert-RequiredFile -Path $VerifyCatalog -Label 'catalog verifier'
    Assert-RequiredFile -Path $StatusUpdater -Label 'shared-status state machine'
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
        $actualCertSha = (Get-FileHash -LiteralPath $IpCert -Algorithm SHA256).Hash
        $actualKeySha = (Get-FileHash -LiteralPath $IpKey -Algorithm SHA256).Hash
        if ($actualCertSha -ine $ExpectedIpCertificateSha256 -or
            $actualKeySha -ine $ExpectedIpPrivateKeySha256) {
            throw 'IP-SAN certificate replacement SHA-256 pin mismatch.'
        }
    }

    $pythonCommand = Get-Command python -ErrorAction Stop
    & $pythonCommand.Source $VerifyCatalog
    if ($LASTEXITCODE -ne 0) {
        throw "Spec-catalog verification failed with exit code $LASTEXITCODE"
    }

    $goCommand = Get-Command go -ErrorAction Stop
    Push-Location $ServerDir
    try {
        & $goCommand.Source test -count=1 ./...
        if ($LASTEXITCODE -ne 0) {
            throw "Go server tests failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }

    Write-Host 'local_safety_gates=pass catalog=verified server_tests=pass quick_bundle=complete'
}

function Assert-SharedDeploymentLease {
    Assert-RequiredFile -Path $SharedTestStatus -Label 'shared test status'
    $pythonCommand = Get-Command python -ErrorAction Stop
    & $pythonCommand.Source $StatusUpdater `
        --status-file $SharedTestStatus `
        assert-lease `
        --executor Codex `
        --lease-id $LeaseId.ToLowerInvariant() `
        --resource E-01
    if ($LASTEXITCODE -ne 0) {
        throw "Shared deployment lease assertion failed with exit code $LASTEXITCODE. Claim a fresh lease automatically before deployment; never ask the Product Owner to edit the file."
    }
    Write-Host 'shared_test_lease=verified executor=Codex resource=E-01 lease=current'
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
if ($LocalValidationOnly) {
    Write-Host 'LOCAL_VALIDATION_ONLY_OK'
    return
}

Assert-SharedDeploymentLease
Assert-RequiredFile -Path $SshKey -Label 'E-01 SSH private key'

$DeploymentId = (Get-Date -Format 'yyyyMMddHHmmss') + '-' + [guid]::NewGuid().ToString('N')
$LocalStage = Join-Path $env:TEMP ("aneb-deploy-" + $DeploymentId)
$Bin = Join-Path $LocalStage 'aneb-server-linux'
$RemoteStage = "/tmp/aneb-deploy-$DeploymentId"
$RemoteStageCreated = $false

if (-not ($DeploymentId -match '^[0-9]{14}-[0-9a-f]{32}$')) {
    throw "Unsafe deployment identifier: $DeploymentId"
}

New-Item -ItemType Directory -Path $LocalStage | Out-Null
try {
    Write-Host '== [2/5] cross-compile linux/amd64 =='
    $goCommand = Get-Command go -ErrorAction Stop
    $oldGoos = $env:GOOS
    $oldGoarch = $env:GOARCH
    $oldCgo = $env:CGO_ENABLED
    Push-Location $ServerDir
    try {
        $env:GOOS = 'linux'
        $env:GOARCH = 'amd64'
        $env:CGO_ENABLED = '0'
        & $goCommand.Source build -trimpath -o $Bin .
        if ($LASTEXITCODE -ne 0) {
            throw "go build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        $env:GOOS = $oldGoos
        $env:GOARCH = $oldGoarch
        $env:CGO_ENABLED = $oldCgo
        Pop-Location
    }
    Assert-RequiredFile -Path $Bin -Label 'linux server binary'
    Write-Host ("built={0} bytes={1}" -f $Bin, (Get-Item -LiteralPath $Bin).Length)

    Write-Host '== [3/5] create isolated remote staging directory =='
    $createStage = "set -eu; umask 077; install -d -m 0700 '$RemoteStage' '$RemoteStage/root-profiles' '$RemoteStage/execution-profiles/token_multimodal_quick' '$RemoteStage/tls'"
    & ssh @SshOpts $Remote $createStage
    if ($LASTEXITCODE -ne 0) {
        throw "remote staging creation failed with exit code $LASTEXITCODE"
    }
    $RemoteStageCreated = $true

    Write-Host '== [4/5] upload candidate into staging =='
    Copy-ToRemote -LocalPath $Bin -RemotePath "$RemoteStage/aneb-server-linux" -Label 'server binary'
    foreach ($profilePath in $RootProfileFiles) {
        $profileName = Split-Path -Leaf $profilePath
        Copy-ToRemote -LocalPath $profilePath -RemotePath "$RemoteStage/root-profiles/$profileName" -Label "root profile $profileName"
    }
    Copy-ToRemote -LocalPath $TokenQuickProfile -RemotePath "$RemoteStage/execution-profiles/token_multimodal_quick/profile.json" -Label 'Token Quick profile'
    Copy-ToRemote -LocalPath $TokenQuickRuntimePlan -RemotePath "$RemoteStage/execution-profiles/token_multimodal_quick/runtime_plan.json" -Label 'Token Quick runtime plan'
    Copy-ToRemote -LocalPath $TokenQuickManifest -RemotePath "$RemoteStage/execution-profiles/token_multimodal_quick/manifest.sha256" -Label 'Token Quick manifest'
    Copy-ToRemote -LocalPath $Unit -RemotePath "$RemoteStage/aneb-server.service" -Label 'systemd unit'

    $HaveIpCert = $script:HaveIpCert
    if ($HaveIpCert) {
        Copy-ToRemote -LocalPath $IpCert -RemotePath "$RemoteStage/tls/ip-cert.pem" -Label 'IP-SAN certificate'
        Copy-ToRemote -LocalPath $IpKey -RemotePath "$RemoteStage/tls/ip-key.pem" -Label 'IP-SAN private key'
    }

    $remoteScript = @'
#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
umask 077

DEPLOY_ID="${1:?deployment id required}"
SHIP_IP_CERT="${2:?IP certificate marker required}"
EXPECTED_IP_CERT_SHA="${3:?IP certificate SHA-256 marker required}"
EXPECTED_IP_KEY_SHA="${4:?IP private-key SHA-256 marker required}"
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

STAGE="/tmp/aneb-deploy-$DEPLOY_ID"
BACKUP_ROOT="/opt/aneb/backups"
BACKUP="$BACKUP_ROOT/aneb-deploy-$DEPLOY_ID"
STAGE_PID=""
LIVE_TOUCHED=0
ROLLBACK_FAILED=0
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

export LC_ALL=C

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

canonicalize_iptables_save() {
    # iptables-save adds wall-clock header/footer comments on every invocation.
    # Trim only the Generated timestamp and remove the time-only Completed line;
    # preserve tool version/backend, chains, policies, rules, warnings, and rule
    # comments byte-for-byte for fail-closed comparison.
    python3 -c '
import sys

generated_prefixes = (
    b"# Generated by iptables-save ",
    b"# Generated by ip6tables-save ",
)
for line in sys.stdin.buffer:
    if line.startswith(b"# Completed on "):
        continue
    if line.startswith(generated_prefixes):
        body = line.rstrip(b"\r\n")
        identity, separator, timestamp = body.rpartition(b" on ")
        if separator and identity and timestamp:
            newline = b"\r\n" if line.endswith(b"\r\n") else b"\n"
            line = identity + newline
    sys.stdout.buffer.write(line)
'
}

firewall_snapshot() {
    printf '%s\n' '--- iptables-save ---'
    iptables-save | canonicalize_iptables_save || {
        echo 'iptables-save snapshot failed' >&2
        return 1
    }
    printf '%s\n' '--- ip6tables-save ---'
    ip6tables-save | canonicalize_iptables_save || {
        echo 'ip6tables-save snapshot failed' >&2
        return 1
    }
    printf '%s\n' '--- nft-list-ruleset ---'
    if command -v nft >/dev/null 2>&1; then
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
    iptables-save | awk '/(^:DOCKER|DOCKER)/ { print }' || {
        echo 'iptables-save Docker snapshot failed' >&2
        return 1
    }
    printf '%s\n' '--- ip6tables-save docker rules ---'
    ip6tables-save | awk '/(^:DOCKER|DOCKER)/ { print }' || {
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
    local current_qdisc digest
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
    rm -rf -- "$target" || return 1
    if [[ -f "$BACKUP/$label.present" ]]; then
        mkdir -p -- "$(dirname "$target")" || return 1
        cp -a -- "$BACKUP/$label" "$target" || return 1
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
    set -e
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

cleanup() {
    local rc=$?
    trap - EXIT INT TERM
    set +e
    stop_staged_server
    if [[ $rc -ne 0 && $LIVE_TOUCHED -eq 1 ]]; then
        rollback_live || ROLLBACK_FAILED=1
    fi
    rm -rf -- \
        "/opt/aneb/bin/aneb-server.new-$DEPLOY_ID" \
        "/opt/aneb/profiles.new-$DEPLOY_ID" \
        "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID" \
        "/etc/systemd/system/aneb-server.service.new-$DEPLOY_ID"
    if [[ -d "$BACKUP_ROOT" ]]; then
        prune_backups || echo 'WARNING backup_prune_failed maintenance_required=1' >&2
    fi
    rm -rf -- "$STAGE"
    if [[ $ROLLBACK_FAILED -ne 0 ]]; then
        exit 97
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
    curl -fksS --connect-timeout 2 --max-time 10 \
        "$base_url/api/v1/serverinfo" -o "$output"
    python3 - "$output" "$manifest" <<'PY'
import json
from pathlib import Path
import sys

body = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
if body.get('version') != 'aneb-server/0.8.0':
    raise SystemExit(f'unexpected server version: {body.get("version")!r}')
receipt = body.get('execution_capabilities')
if not isinstance(receipt, dict):
    raise SystemExit('execution capability receipt missing')
if receipt.get('contract_id') != 'aneb-server-capability-receipt':
    raise SystemExit('receipt contract id mismatch')
if receipt.get('contract_version') != '1.0.0':
    raise SystemExit('receipt contract version mismatch')
expected_primitives = {
    ('echo', 'aneb-echo-v1'),
    ('token_sim', 'aneb-token-task-v1'),
    ('download', 'aneb-download-v1'),
}
actual_primitives = {
    (item.get('primitive_id'), item.get('wire_contract_id'))
    for item in receipt.get('primitives', [])
}
if actual_primitives != expected_primitives:
    raise SystemExit(f'primitive receipt mismatch: {actual_primitives!r}')

manifest_entries = {}
for line in Path(sys.argv[2]).read_text(encoding='utf-8').splitlines():
    parts = line.split()
    if len(parts) != 2 or parts[1] in manifest_entries:
        raise SystemExit('invalid or duplicate manifest entry')
    manifest_entries[parts[1]] = parts[0]
if set(manifest_entries) != {'profile.json', 'runtime_plan.json'}:
    raise SystemExit(f'unexpected manifest coverage: {set(manifest_entries)!r}')

profiles = receipt.get('validated_profiles')
if not isinstance(profiles, list) or len(profiles) != 1:
    raise SystemExit(f'unexpected validated profiles: {profiles!r}')
profile = profiles[0]
expected = {
    'profile_id': 'token_multimodal_quick',
    'profile_version': '1.2.0',
    'profile_sha256': 'sha256:' + manifest_entries['profile.json'],
}
if profile != expected:
    raise SystemExit(f'validated profile mismatch: {profile!r} != {expected!r}')
print('execution_receipt=verified profile=token_multimodal_quick@1.2.0')
PY
}

test -d "$STAGE"
test -x /usr/bin/python3
test -x /usr/bin/curl
command -v runuser >/dev/null 2>&1
command -v setsid >/dev/null 2>&1
command -v awk >/dev/null 2>&1
command -v cmp >/dev/null 2>&1
command -v iptables-save >/dev/null 2>&1
command -v ip6tables-save >/dev/null 2>&1
command -v tc >/dev/null 2>&1
test -f "$STAGE/aneb-server-linux"
test -f "$STAGE/aneb-server.service"
test -f "$STAGE/execution-profiles/token_multimodal_quick/profile.json"
test -f "$STAGE/execution-profiles/token_multimodal_quick/runtime_plan.json"
test -f "$STAGE/execution-profiles/token_multimodal_quick/manifest.sha256"
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
    "$STAGE/staged-serverinfo.json"
echo "STAGING_OK loopback_port=$STAGE_PORT"
stop_staged_server

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
mv -f -- "/opt/aneb/bin/aneb-server.new-$DEPLOY_ID" /opt/aneb/bin/aneb-server

cp -a -- "$STAGE/root-profiles" "/opt/aneb/profiles.new-$DEPLOY_ID"
chown -R aneb:aneb "/opt/aneb/profiles.new-$DEPLOY_ID"
rm -rf -- /opt/aneb/profiles
mv -- "/opt/aneb/profiles.new-$DEPLOY_ID" /opt/aneb/profiles

install -d -m 0755 /opt/aneb/execution-profiles
cp -a -- "$STAGE/execution-profiles/token_multimodal_quick" \
    "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID"
chown -R aneb:aneb "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID"
rm -rf -- /opt/aneb/execution-profiles/token_multimodal_quick
mv -- "/opt/aneb/execution-profiles/token_multimodal_quick.new-$DEPLOY_ID" \
    /opt/aneb/execution-profiles/token_multimodal_quick

install -m 0644 "$STAGE/aneb-server.service" \
    "/etc/systemd/system/aneb-server.service.new-$DEPLOY_ID"
mv -f -- "/etc/systemd/system/aneb-server.service.new-$DEPLOY_ID" \
    /etc/systemd/system/aneb-server.service

if [[ "$SHIP_IP_CERT" == "1" ]]; then
    install -d -m 0750 -o aneb -g aneb /opt/aneb/tls/ip
    install -m 0644 -o aneb -g aneb "$STAGE/tls/ip-cert.pem" /opt/aneb/tls/ip/cert.pem
    install -m 0600 -o aneb -g aneb "$STAGE/tls/ip-key.pem" /opt/aneb/tls/ip/key.pem
fi

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
    "$STAGE/live-serverinfo.json"

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
LIVE_TOUCHED=0
PRUNE_RESULT='ok'
if ! prune_backups; then
    PRUNE_RESULT='warning'
    echo 'WARNING backup_prune_failed maintenance_required=1' >&2
fi
echo "DEPLOY_OK backup=$BACKUP retained_backups=3 backup_prune=$PRUNE_RESULT"
'@

    $remoteScriptPath = Join-Path $LocalStage 'remote_deploy.sh'
    [System.IO.File]::WriteAllText(
        $remoteScriptPath,
        ($remoteScript -replace "`r`n", "`n"),
        (New-Object System.Text.UTF8Encoding($false))
    )
    Copy-ToRemote -LocalPath $remoteScriptPath -RemotePath "$RemoteStage/remote_deploy.sh" -Label 'remote deployment script'

    Write-Host '== [5/5] staged validation, guarded live restart, and smoke test =='
    $ipMarker = if ($HaveIpCert) { '1' } else { '0' }
    $certPin = if ($HaveIpCert) { $ExpectedIpCertificateSha256.ToLowerInvariant() } else { 'none' }
    $keyPin = if ($HaveIpCert) { $ExpectedIpPrivateKeySha256.ToLowerInvariant() } else { 'none' }
    & ssh @SshOpts $Remote "bash '$RemoteStage/remote_deploy.sh' '$DeploymentId' '$ipMarker' '$certPin' '$keyPin'"
    if ($LASTEXITCODE -ne 0) {
        throw "guarded remote deployment failed with exit code $LASTEXITCODE"
    }
    Write-Host '== deploy complete =='
}
finally {
    if ($RemoteStageCreated) {
        $cleanupCommand = "case '$RemoteStage' in /tmp/aneb-deploy-[0-9]*) rm -rf -- '$RemoteStage' ;; *) exit 2 ;; esac"
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
