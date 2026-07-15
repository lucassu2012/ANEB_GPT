# deploy_server.ps1 -- repeatable deployment of aneb-server to E-01 (shared Aliyun lightweight server)
# ASCII-only. PowerShell 5.1 compatible (no &&, no ternary).
#
# What it does:
#   1. Cross-compiles server/ for linux/amd64 (CGO disabled) into $env:TEMP\aneb-server-linux
#   2. scp's binary + profiles/*.json + aneb-server.service to /tmp on the server
#   3. ssh's an idempotent install script: system user, /opt/aneb tree, sysctl drop-in,
#      systemd unit install, daemon-reload, enable+restart, local curl smoke test
#
# Isolation rules for the shared host (DO NOT VIOLATE):
#   - never touch existing services (node/mongod/python, user admin/1001)
#   - no ufw/iptables changes, no chrony changes
#   - only global change allowed: /etc/sysctl.d/99-aneb.conf (one tcp tuning entry)

$ErrorActionPreference = 'Stop'

$RepoRoot  = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'server'
$Profiles  = Join-Path $RepoRoot 'profiles'
$Unit      = Join-Path $ServerDir 'aneb-server.service'
$Bin       = Join-Path $env:TEMP 'aneb-server-linux'

$SshKey  = Join-Path $env:USERPROFILE '.ssh\aneb_e01'
$Remote  = 'root@120.79.148.0'
$SshOpts = @('-i', $SshKey, '-o', 'BatchMode=yes')

# SNI dual-path (phase 3): self-signed IP-SAN cert+key (IP:120.79.148.0) for the
# bare-IP cellular channel, installed to /opt/aneb/tls/ip/. Must be the SAME cert
# whose public half is the client trust anchor (app debug res/raw/aneb_ip_ca.pem).
# Default source dir = server/tls/ip (git-ignored); generate once with:
#   go run ./tools/gencert -mode=ip -ip=120.79.148.0 -out <dir>
# then copy aneb_ip_cert.pem -> app .../res/raw/aneb_ip_ca.pem. When absent, the
# IP-SAN stage is skipped (server falls back to default cert on bare-IP + logs a warning).
$IpCertDir = Join-Path $ServerDir 'tls\ip'
$IpCert    = Join-Path $IpCertDir 'aneb_ip_cert.pem'
$IpKey     = Join-Path $IpCertDir 'aneb_ip_key.pem'

# --- 1. cross-compile -------------------------------------------------------
Write-Host '== [1/3] cross-compile linux/amd64 =='
Push-Location $ServerDir
$env:GOOS = 'linux'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
& 'C:\Program Files\Go\bin\go.exe' build -o $Bin .
$buildRc = $LASTEXITCODE
$env:GOOS = $null; $env:GOARCH = $null; $env:CGO_ENABLED = $null
Pop-Location
if ($buildRc -ne 0) { throw "go build failed with exit code $buildRc" }
Write-Host ("built: {0} ({1} bytes)" -f $Bin, (Get-Item $Bin).Length)

# --- 2. upload artifacts to /tmp --------------------------------------------
Write-Host '== [2/3] scp artifacts to /tmp =='
& scp @SshOpts $Bin "${Remote}:/tmp/aneb-server-linux"
if ($LASTEXITCODE -ne 0) { throw 'scp binary failed' }
& scp @SshOpts (Join-Path $Profiles '*.json') "${Remote}:/tmp/"
if ($LASTEXITCODE -ne 0) { throw 'scp profiles failed' }
& scp @SshOpts $Unit "${Remote}:/tmp/aneb-server.service"
if ($LASTEXITCODE -ne 0) { throw 'scp service file failed' }

# optional IP-SAN cert+key for the bare-IP cellular channel
$HaveIpCert = (Test-Path $IpCert) -and (Test-Path $IpKey)
if ($HaveIpCert) {
    Write-Host '   shipping IP-SAN cert+key (bare-IP channel)'
    & scp @SshOpts $IpCert "${Remote}:/tmp/aneb_ip_cert.pem"
    if ($LASTEXITCODE -ne 0) { throw 'scp IP-SAN cert failed' }
    & scp @SshOpts $IpKey "${Remote}:/tmp/aneb_ip_key.pem"
    if ($LASTEXITCODE -ne 0) { throw 'scp IP-SAN key failed' }
} else {
    Write-Host ("   WARNING: no IP-SAN cert at {0} -- bare-IP channel will use default cert (SNI dual-path degraded)" -f $IpCertDir)
}

# --- 3. remote idempotent install -------------------------------------------
Write-Host '== [3/3] remote install =='
$remoteScript = @'
set -e
# system user (idempotent)
if ! id -u aneb >/dev/null 2>&1; then
    useradd --system --home /opt/aneb --shell /usr/sbin/nologin aneb
    echo "created system user aneb"
else
    echo "user aneb already exists"
fi
mkdir -p /opt/aneb/bin /opt/aneb/profiles /opt/aneb/data /opt/aneb/tls/ip
install -m 755 /tmp/aneb-server-linux /opt/aneb/bin/aneb-server
mv -f /tmp/s1_chat.json /tmp/s2_coding_agent.json /tmp/s3_multimodal.json /opt/aneb/profiles/
# SNI dual-path: install IP-SAN cert+key for the bare-IP cellular channel if shipped
# (key 600, cert 644). Absent => keep whatever is already there (or none => server warns).
if [ -f /tmp/aneb_ip_cert.pem ] && [ -f /tmp/aneb_ip_key.pem ]; then
    install -m 644 /tmp/aneb_ip_cert.pem /opt/aneb/tls/ip/cert.pem
    install -m 600 /tmp/aneb_ip_key.pem  /opt/aneb/tls/ip/key.pem
    rm -f /tmp/aneb_ip_cert.pem /tmp/aneb_ip_key.pem
    echo "installed IP-SAN cert /opt/aneb/tls/ip/{cert,key}.pem"
else
    echo "no IP-SAN cert shipped; leaving /opt/aneb/tls/ip as-is"
fi
chown -R aneb:aneb /opt/aneb
install -m 644 /tmp/aneb-server.service /etc/systemd/system/aneb-server.service
rm -f /tmp/aneb-server-linux

# sysctl drop-in: keep cwnd across idle periods so paced token streams are not
# re-throttled after inter-token gaps (measurement fidelity for ANEB probes).
cat > /etc/sysctl.d/99-aneb.conf <<'EOF'
# ANEB: do not collapse the congestion window after idle; the probe server
# streams tokens with deliberate gaps and slow-start-after-idle would distort
# observed pacing. Safe for co-tenant services (affects only idle-restart cwnd).
net.ipv4.tcp_slow_start_after_idle=0
EOF
sysctl --system 2>&1 | grep -E '99-aneb|slow_start' || true

systemctl daemon-reload
systemctl enable aneb-server >/dev/null 2>&1
systemctl restart aneb-server
sleep 1
systemctl is-active aneb-server
echo '--- smoke: /api/v1/profiles ---'
curl -sk https://127.0.0.1:8443/api/v1/profiles
echo ''
echo '--- smoke: /api/v1/echo ---'
curl -sk -X POST --data ping https://127.0.0.1:8443/api/v1/echo
echo ''
echo 'DEPLOY_OK'
'@

# ship the script as a file to avoid quote-stripping through PowerShell->ssh
$tmpScript = Join-Path $env:TEMP 'aneb_install.sh'
# LF endings, no BOM (ASCII content)
[System.IO.File]::WriteAllText($tmpScript, ($remoteScript -replace "`r`n", "`n"))
& scp @SshOpts $tmpScript "${Remote}:/tmp/aneb_install.sh"
if ($LASTEXITCODE -ne 0) { throw 'scp install script failed' }
& ssh @SshOpts $Remote 'bash /tmp/aneb_install.sh; rc=$?; rm -f /tmp/aneb_install.sh; exit $rc'
if ($LASTEXITCODE -ne 0) { throw "remote install failed with exit code $LASTEXITCODE" }
Write-Host '== deploy complete =='
