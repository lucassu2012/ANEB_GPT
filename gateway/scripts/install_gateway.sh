#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

usage() {
  cat >&2 <<'EOF'
usage: install_gateway.sh \
  --binary /path/to/aneb-gateway-linux \
  --wan eth0 --management eth1 \
  --listen 192.168.77.1:9444 --client-subnet 192.168.77.0/24 \
  --tls-cert /path/to/cert.pem --tls-key /path/to/key.pem \
  [--token-file /path/to/64-hex-token] \
  --accept-dedicated-appliance

The Debug ADB-only lab contract fixes the management IP at 192.168.77.1 and
the exclusive client subnet at 192.168.77.0/24. The TCP port remains configurable.
This installer never configures NAT, DHCP, Wi-Fi AP, IP addresses, routes, or
firewall rules. Those prerequisites must already be active and are verified
read-only.
EOF
  exit 2
}

die() {
  echo "$*" >&2
  exit 1
}

EXPECTED_APP_CA_DER_SHA256=2089A92C77B04FA392E24D1D71819EF1AC3D86B5131B0C6064BD6B092F5AD361

certificate_der_sha256() {
  local path="$1"
  openssl x509 -in "$path" -outform DER | sha256sum | awk '{print toupper($1)}'
}

verify_app_ca() {
  local path="$1" actual
  actual="$(certificate_der_sha256 "$path")" || return 1
  [ "$actual" = "$EXPECTED_APP_CA_DER_SHA256" ] || {
    echo "App gateway CA fingerprint mismatch: expected $EXPECTED_APP_CA_DER_SHA256, got ${actual:-unreadable}" >&2
    return 1
  }
}

verify_field_leaf() {
  local ca_path="$1" cert_path="$2" listen_ip="$3" basic_constraints
  basic_constraints="$(openssl x509 -in "$cert_path" -noout -ext basicConstraints)" || return 1
  grep -q 'CA:FALSE' <<<"$basic_constraints" || {
    echo "TLS certificate must be a CA:FALSE field leaf" >&2
    return 1
  }
  openssl verify -no-CApath -CAfile "$ca_path" -purpose sslserver -verify_ip "$listen_ip" "$cert_path" >/dev/null
}

mode_is_safe() {
  local mode="$1"
  (( (8#$mode & 0022) == 0 ))
}

secure_root_directory() {
  local path="$1" mode
  [ -d "$path" ] && [ ! -L "$path" ] || return 1
  [ "$(stat -c '%u' -- "$path")" = 0 ] || return 1
  mode="$(stat -c '%a' -- "$path")"
  mode_is_safe "$mode"
}

secure_root_regular() {
  local path="$1" max_size="${2:-0}" parent mode size
  [ -f "$path" ] && [ ! -L "$path" ] || return 1
  [ "$(stat -c '%u' -- "$path")" = 0 ] || return 1
  mode="$(stat -c '%a' -- "$path")"
  mode_is_safe "$mode" || return 1
  if [ "$max_size" -gt 0 ]; then
    size="$(stat -c '%s' -- "$path")"
    [ "$size" -gt 0 ] && [ "$size" -le "$max_size" ] || return 1
  fi
  parent="$(dirname -- "$path")"
  [ -d "$parent" ] && [ ! -L "$parent" ] || return 1
  [ "$(stat -c '%u' -- "$parent")" = 0 ] || return 1
  mode="$(stat -c '%a' -- "$parent")"
  mode_is_safe "$mode"
}

secure_root_secret_source() {
  local path="$1" max_size="$2"
  secure_root_regular "$path" "$max_size" || return 1
  [ "$(stat -c '%a' -- "$path")" = 600 ]
}

validate_token_value() {
  local path="$1"
  python3 - "$path" <<'PY'
import pathlib, re, sys
value = pathlib.Path(sys.argv[1]).read_bytes()
if re.fullmatch(rb"[0-9A-Fa-f]{64}(?:\n)?", value) is None:
    raise SystemExit(1)
PY
}

secure_token_source() {
  local path="$1"
  secure_root_secret_source "$path" 65 || return 1
  validate_token_value "$path"
}

ensure_state_directory() {
  local path="$1" expected_uid="$2" expected_gid="$3" parent
  if [ -e "$path" ] || [ -L "$path" ]; then
    [ -d "$path" ] && [ ! -L "$path" ] || return 1
    mountpoint -q "$path" && return 1
    [ "$(stat -c '%u' -- "$path")" = "$expected_uid" ] || return 1
    [ "$(stat -c '%g' -- "$path")" = "$expected_gid" ] || return 1
    [ "$(stat -c '%a' -- "$path")" = 750 ] || return 1
    STATE_DIR_READY=1
    return 0
  fi
  parent="$(dirname -- "$path")"
  secure_root_directory "$parent" || return 1
  install -d -o "$expected_uid" -g "$expected_gid" -m 0750 "$path" || return 1
  [ -d "$path" ] && [ ! -L "$path" ] || return 1
  mountpoint -q "$path" && return 1
  [ "$(stat -c '%u' -- "$path")" = "$expected_uid" ] || return 1
  [ "$(stat -c '%g' -- "$path")" = "$expected_gid" ] || return 1
  [ "$(stat -c '%a' -- "$path")" = 750 ] || return 1
  STATE_DIR_CREATED=1
  STATE_DIR_READY=1
}

validate_gateway_env_values() {
  local ip port
  [[ "$ANEB_GATEWAY_WAN" =~ ^[A-Za-z0-9_.-]{1,15}$ ]] || return 1
  [ "$ANEB_GATEWAY_WAN" != lo ] || return 1
  [ "$ANEB_GATEWAY_IFB" = ifb-aneb0 ] || return 1
  [[ "$ANEB_GATEWAY_LISTEN" =~ ^192\.168\.77\.1:[0-9]{1,5}$ ]] || return 1
  ip="${ANEB_GATEWAY_LISTEN%:*}"
  port="${ANEB_GATEWAY_LISTEN##*:}"
  [ "$ip" = 192.168.77.1 ] || return 1
  (( 10#$port >= 1 && 10#$port <= 65535 ))
}

parse_gateway_env() {
  local path="$1" line value
  local seen_listen=0 seen_wan=0 seen_ifb=0 lines=0
  secure_root_regular "$path" 1024 || {
    echo "gateway.env must be a bounded root-owned non-symlink file with no group/world write: $path" >&2
    return 1
  }
  ANEB_GATEWAY_LISTEN=""
  ANEB_GATEWAY_WAN=""
  ANEB_GATEWAY_IFB=""
  while IFS= read -r line || [ -n "$line" ]; do
    lines=$((lines + 1))
    case "$line" in
      ANEB_GATEWAY_LISTEN=*)
        [ "$seen_listen" -eq 0 ] || return 1
        value="${line#ANEB_GATEWAY_LISTEN=}"
        ANEB_GATEWAY_LISTEN="$value"
        seen_listen=1
        ;;
      ANEB_GATEWAY_WAN=*)
        [ "$seen_wan" -eq 0 ] || return 1
        value="${line#ANEB_GATEWAY_WAN=}"
        ANEB_GATEWAY_WAN="$value"
        seen_wan=1
        ;;
      ANEB_GATEWAY_IFB=*)
        [ "$seen_ifb" -eq 0 ] || return 1
        value="${line#ANEB_GATEWAY_IFB=}"
        ANEB_GATEWAY_IFB="$value"
        seen_ifb=1
        ;;
      *) return 1 ;;
    esac
  done <"$path"
  [ "$lines" -eq 3 ] && [ "$seen_listen" -eq 1 ] && [ "$seen_wan" -eq 1 ] && [ "$seen_ifb" -eq 1 ] || return 1
  validate_gateway_env_values
}

acquire_lifecycle_lock() {
  local lock_dir=/run/aneb-gateway-lifecycle lock_file mode
  lock_file="$lock_dir/lifecycle.lock"
  if [ -e "$lock_dir" ] || [ -L "$lock_dir" ]; then
    [ -d "$lock_dir" ] && [ ! -L "$lock_dir" ] || die "unsafe lifecycle lock directory"
  else
    install -d -o root -g root -m 0700 "$lock_dir"
  fi
  [ "$(stat -c '%u' -- "$lock_dir")" = 0 ] || die "lifecycle lock directory is not root-owned"
  mode="$(stat -c '%a' -- "$lock_dir")"
  [ "$mode" = 700 ] || die "lifecycle lock directory must have mode 0700"
  if [ -e "$lock_file" ] || [ -L "$lock_file" ]; then
    [ -f "$lock_file" ] && [ ! -L "$lock_file" ] || die "unsafe lifecycle lock file"
    [ "$(stat -c '%u' -- "$lock_file")" = 0 ] || die "lifecycle lock file is not root-owned"
    [ "$(stat -c '%a' -- "$lock_file")" = 600 ] || die "lifecycle lock file must have mode 0600"
  else
    install -o root -g root -m 0600 /dev/null "$lock_file"
  fi
  exec 9<>"$lock_file"
  flock -n 9 || die "another ANEB gateway lifecycle operation is running"
}

if [ "${ANEB_GATEWAY_INSTALL_FUNCTIONS_ONLY:-0}" = 1 ] && [ "${BASH_SOURCE[0]}" != "$0" ]; then
  return 0
fi

if [ "$(id -u)" -ne 0 ] || [ "$(uname -s)" != Linux ]; then
  die "installer requires root on Linux"
fi

BINARY=""
WAN=""
MANAGEMENT=""
LISTEN=""
CLIENT_SUBNET=""
TLS_CERT=""
TLS_KEY=""
TOKEN_SOURCE=""
ACCEPTED=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --binary) BINARY="${2:-}"; shift 2 ;;
    --wan) WAN="${2:-}"; shift 2 ;;
    --management) MANAGEMENT="${2:-}"; shift 2 ;;
    --listen) LISTEN="${2:-}"; shift 2 ;;
    --client-subnet) CLIENT_SUBNET="${2:-}"; shift 2 ;;
    --tls-cert) TLS_CERT="${2:-}"; shift 2 ;;
    --tls-key) TLS_KEY="${2:-}"; shift 2 ;;
    --token-file) TOKEN_SOURCE="${2:-}"; shift 2 ;;
    --accept-dedicated-appliance) ACCEPTED=1; shift ;;
    *) usage ;;
  esac
done

[ "$ACCEPTED" -eq 1 ] || { echo "explicit --accept-dedicated-appliance is required" >&2; exit 2; }
[ -n "$BINARY" ] && [ -n "$WAN" ] && [ -n "$MANAGEMENT" ] && [ -n "$LISTEN" ] && \
  [ -n "$CLIENT_SUBNET" ] && [ -n "$TLS_CERT" ] && [ -n "$TLS_KEY" ] || usage
[[ "$WAN" =~ ^[A-Za-z0-9_.-]{1,15}$ ]] || die "invalid WAN interface"
[[ "$MANAGEMENT" =~ ^[A-Za-z0-9_.-]{1,15}$ ]] || die "invalid management interface"
[ "$WAN" != "$MANAGEMENT" ] && [ "$WAN" != lo ] && [ "$MANAGEMENT" != lo ] || \
  die "WAN and management interfaces must be distinct non-loopback interfaces"
[[ "$LISTEN" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$ ]] || \
  die "listen must be a literal IPv4 address and port"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROFILE_SOURCE="$GATEWAY_DIR/profiles"
APP_CA_SOURCE="$GATEWAY_DIR/trust/aneb_gateway_ca.pem"
UNIT_SOURCE="$GATEWAY_DIR/systemd/aneb-gateway.service"
LOGROTATE_SOURCE="$GATEWAY_DIR/systemd/aneb-gateway.logrotate"
for command in install ip tc openssl python3 systemctl useradd getent flock curl modprobe sha256sum stat mktemp mountpoint cp mv rm awk grep seq sleep tr chmod chown dirname; do
  command -v "$command" >/dev/null || die "missing command: $command"
done
[ -f "$BINARY" ] && [ -x "$BINARY" ] && [ ! -L "$BINARY" ] || \
  die "gateway binary must be an executable non-symlink file: $BINARY"
[ -d "$PROFILE_SOURCE" ] && [ ! -L "$PROFILE_SOURCE" ] || die "profile directory is missing or unsafe: $PROFILE_SOURCE"
[ -f "$UNIT_SOURCE" ] && [ ! -L "$UNIT_SOURCE" ] || die "systemd unit is missing or unsafe: $UNIT_SOURCE"
[ -f "$LOGROTATE_SOURCE" ] && [ ! -L "$LOGROTATE_SOURCE" ] || die "logrotate policy is missing or unsafe: $LOGROTATE_SOURCE"
[ -f "$GATEWAY_DIR/README.md" ] && [ ! -L "$GATEWAY_DIR/README.md" ] || die "gateway README is missing or unsafe"
[ -f "$APP_CA_SOURCE" ] && [ ! -L "$APP_CA_SOURCE" ] || die "fixed App gateway CA is missing or unsafe: $APP_CA_SOURCE"
[ -f "$TLS_CERT" ] && [ ! -L "$TLS_CERT" ] || die "TLS certificate must be a regular non-symlink file"
secure_root_secret_source "$TLS_KEY" 65536 || \
  die "TLS private-key source must be root-owned mode 0600 in a safe root-owned directory and at most 65536 bytes"
if [ -n "$TOKEN_SOURCE" ]; then
  secure_token_source "$TOKEN_SOURCE" || \
    die "token source must be root-owned mode 0600 in a safe root-owned directory, at most 65 bytes, and exactly 64 hexadecimal characters"
fi

LISTEN_IP="${LISTEN%:*}"
LISTEN_PORT="${LISTEN##*:}"
[ "$LISTEN_IP" = 192.168.77.1 ] || die "Debug lab listen IP is fixed at 192.168.77.1 by the App trust contract"
[ "$CLIENT_SUBNET" = 192.168.77.0/24 ] || die "Debug lab client subnet is fixed at 192.168.77.0/24"
python3 - "$LISTEN_IP" "$LISTEN_PORT" "$CLIENT_SUBNET" <<'PY'
import ipaddress, sys
ip = ipaddress.ip_address(sys.argv[1])
port = int(sys.argv[2])
network = ipaddress.ip_network(sys.argv[3], strict=True)
assert str(ip) == "192.168.77.1"
assert 1 <= port <= 65535
assert str(network) == "192.168.77.0/24"
assert ip in network
PY

openssl x509 -in "$TLS_CERT" -noout -checkend 86400 >/dev/null || die "TLS certificate expires within 24 hours or is invalid"
openssl x509 -in "$TLS_CERT" -noout -checkip "$LISTEN_IP" >/dev/null || die "TLS certificate IP SAN does not contain $LISTEN_IP"
verify_app_ca "$APP_CA_SOURCE" || die "fixed App gateway CA validation failed"
verify_field_leaf "$APP_CA_SOURCE" "$TLS_CERT" "$LISTEN_IP" || \
  die "TLS field leaf is not trusted by the fixed Debug App CA for $LISTEN_IP"
CERT_PUB="$(openssl x509 -in "$TLS_CERT" -pubkey -noout | sha256sum | awk '{print $1}')"
KEY_PUB="$(openssl pkey -in "$TLS_KEY" -pubout 2>/dev/null | sha256sum | awk '{print $1}')"
[ "$CERT_PUB" = "$KEY_PUB" ] || die "TLS certificate and private key do not match"

acquire_lifecycle_lock

for owned_directory in /opt/aneb-gateway /etc/aneb-gateway; do
  [ ! -L "$owned_directory" ] || die "refusing to replace symlinked product directory: $owned_directory"
  if [ -d "$owned_directory" ]; then
    mountpoint -q "$owned_directory" && die "refusing to replace mounted product directory: $owned_directory"
  fi
done

modprobe ifb
ip link show dev "$WAN" >/dev/null
ip link show dev "$MANAGEMENT" >/dev/null
ip -o link show dev "$WAN" | grep -q '<[^>]*UP[^>]*>' || die "WAN interface is not administratively up"
ip -o link show dev "$MANAGEMENT" | grep -q '<[^>]*UP[^>]*>' || die "management interface is not administratively up"
[ "$(cat /proc/sys/net/ipv4/ip_forward)" = 1 ] || die "IPv4 forwarding is disabled"

if getent passwd aneb-gateway >/dev/null; then
  getent group aneb-gateway >/dev/null || die "existing aneb-gateway user has no matching aneb-gateway group"
  USER_ENTRY="$(getent passwd aneb-gateway)"
  IFS=: read -r _ _ EXISTING_UID _ _ EXISTING_HOME EXISTING_SHELL <<<"$USER_ENTRY"
  [ "$EXISTING_UID" -ne 0 ] || die "aneb-gateway must not be UID 0"
  [ "$EXISTING_HOME" = /nonexistent ] || die "existing aneb-gateway account has an unexpected home directory"
  case "$EXISTING_SHELL" in
    /usr/sbin/nologin|/sbin/nologin|/bin/false) ;;
    *) die "existing aneb-gateway account must use a non-login shell" ;;
  esac
else
  if getent group aneb-gateway >/dev/null; then
    useradd --system --gid aneb-gateway --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin aneb-gateway
  else
    useradd --system --user-group --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin aneb-gateway
  fi
fi
ANEB_UID="$(id -u aneb-gateway)"
ANEB_GID="$(getent group aneb-gateway | awk -F: '{print $3}')"
[ -n "$ANEB_GID" ] || die "cannot resolve aneb-gateway group"
[ "$ANEB_UID" -ne 0 ] && [ "$ANEB_GID" -ne 0 ] || die "aneb-gateway user/group must not resolve to root"

SERVICE=aneb-gateway.service
WAS_ACTIVE=0
systemctl show "$SERVICE" -p LoadState --value >/dev/null 2>&1 || die "cannot query systemd service state"
systemctl is-active --quiet "$SERVICE" && WAS_ACTIVE=1 || true
ORIGINAL_ENABLE_STATE="$(systemctl is-enabled "$SERVICE" 2>/dev/null || true)"
[ -n "$ORIGINAL_ENABLE_STATE" ] || ORIGINAL_ENABLE_STATE=not-found

BACKUP=""
STAGE_OPT=""
STAGE_ETC=""
SERVICE_TOUCHED=0
PATH_MUTATION_STARTED=0
COMMITTED=0
STATE_DIR_CREATED=0
STATE_DIR_READY=0
TOKEN_ORIGIN=generated

get_enable_state() {
  local state
  state="$(systemctl is-enabled "$SERVICE" 2>/dev/null || true)"
  if [ -n "$state" ]; then printf '%s\n' "$state"; else printf '%s\n' not-found; fi
}

service_is_inactive() {
  local state
  state="$(systemctl show "$SERVICE" -p ActiveState --value 2>/dev/null)" || return 1
  case "$state" in
    inactive|failed) return 0 ;;
    *) return 1 ;;
  esac
}

backup_path() {
  local path="$1" key="$2"
  if [ -e "$path" ] || [ -L "$path" ]; then
    printf 'present\n' >"$BACKUP/$key.present"
    cp -a -- "$path" "$BACKUP/$key"
  fi
}

restore_path() {
  local path="$1" key="$2"
  if [ -d "$path" ] && mountpoint -q "$path"; then return 1; fi
  rm -rf -- "$path" || return 1
  if [ -f "$BACKUP/$key.present" ]; then
    cp -a -- "$BACKUP/$key" "$path" || return 1
  fi
}

cleanup_with_recovery_assets() {
  local binary="" env_file=""
  if [ -n "$STAGE_OPT" ] && secure_root_directory "$STAGE_OPT" && secure_root_directory "$STAGE_OPT/bin" && \
      [ -x "$STAGE_OPT/bin/aneb-gateway" ] && secure_root_regular "$STAGE_OPT/bin/aneb-gateway"; then
    binary="$STAGE_OPT/bin/aneb-gateway"
  elif secure_root_directory /opt/aneb-gateway && secure_root_directory /opt/aneb-gateway/bin && \
      [ -x /opt/aneb-gateway/bin/aneb-gateway ] && secure_root_regular /opt/aneb-gateway/bin/aneb-gateway; then
    binary=/opt/aneb-gateway/bin/aneb-gateway
  fi
  if [ -n "$STAGE_ETC" ] && [ -e "$STAGE_ETC/gateway.env" ] && parse_gateway_env "$STAGE_ETC/gateway.env"; then
    env_file="$STAGE_ETC/gateway.env"
  elif [ -e /etc/aneb-gateway/gateway.env ] && parse_gateway_env /etc/aneb-gateway/gateway.env; then
    env_file=/etc/aneb-gateway/gateway.env
  fi
  [ -n "$binary" ] && [ -n "$env_file" ] || {
    echo "verified recovery binary and gateway.env are unavailable" >&2
    return 1
  }
  "$binary" -cleanup-only \
    -wan="$ANEB_GATEWAY_WAN" -ifb="$ANEB_GATEWAY_IFB" \
    -tc-state=/var/lib/aneb-gateway/tc-state.json
}

restore_enable_state() {
  case "$ORIGINAL_ENABLE_STATE" in
    enabled) systemctl enable "$SERVICE" >/dev/null ;;
    enabled-runtime) systemctl enable --runtime "$SERVICE" >/dev/null ;;
    *) ;;
  esac
  [ "$(get_enable_state)" = "$ORIGINAL_ENABLE_STATE" ]
}

remove_temporary_assets() {
  [ -z "$STAGE_OPT" ] || rm -rf -- "$STAGE_OPT"
  [ -z "$STAGE_ETC" ] || rm -rf -- "$STAGE_ETC"
  [ -z "$BACKUP" ] || rm -rf -- "$BACKUP"
}

lifecycle_exit() {
  local status=$? stopped=1 cleaned=1 restored=1 current_state
  trap - EXIT INT TERM
  set +e
  if [ "$COMMITTED" -eq 1 ]; then
    remove_temporary_assets
    exit "$status"
  fi
  if [ "$status" -eq 0 ]; then status=1; fi
  if [ "$SERVICE_TOUCHED" -eq 1 ]; then
    systemctl stop "$SERVICE" >/dev/null 2>&1 || true
    service_is_inactive || stopped=0
    if [ "$stopped" -eq 1 ]; then
      cleanup_with_recovery_assets || cleaned=0
    else
      cleaned=0
    fi
  fi
  if [ "$SERVICE_TOUCHED" -eq 1 ] && { [ "$stopped" -ne 1 ] || [ "$cleaned" -ne 1 ]; }; then
    if [ "$PATH_MUTATION_STARTED" -eq 1 ]; then
      systemctl disable "$SERVICE" >/dev/null 2>&1 || true
    fi
    echo "gateway_install=FAILED_CLOSED stop_verified=$stopped cleanup_verified=$cleaned" >&2
    echo "recovery assets preserved: backup=$BACKUP stage_opt=$STAGE_OPT stage_etc=$STAGE_ETC current_opt=/opt/aneb-gateway current_etc=/etc/aneb-gateway" >&2
    [ "$STATE_DIR_READY" -ne 1 ] || echo "state/audit recovery evidence preserved: /var/lib/aneb-gateway created_by_this_install=$STATE_DIR_CREATED" >&2
    exit "$status"
  fi
  if [ "$PATH_MUTATION_STARTED" -eq 1 ]; then
    systemctl disable "$SERVICE" >/dev/null 2>&1 || true
    restore_path /opt/aneb-gateway opt || restored=0
    restore_path /etc/aneb-gateway etc || restored=0
    restore_path /etc/systemd/system/aneb-gateway.service unit || restored=0
    restore_path /etc/modules-load.d/aneb-gateway.conf module || restored=0
    restore_path /etc/logrotate.d/aneb-gateway logrotate || restored=0
    systemctl daemon-reload || restored=0
    if [ "$restored" -eq 1 ]; then
      restore_enable_state || restored=0
    fi
  fi
  if [ "$restored" -eq 1 ] && [ "$SERVICE_TOUCHED" -eq 1 ] && [ "$WAS_ACTIVE" -eq 1 ]; then
    systemctl start "$SERVICE" >/dev/null 2>&1 || restored=0
    systemctl is-active --quiet "$SERVICE" || restored=0
  fi
  if [ "$restored" -ne 1 ]; then
    current_state="$(get_enable_state)"
    echo "gateway_install=ROLLBACK_INCOMPLETE original_enable=$ORIGINAL_ENABLE_STATE current_enable=$current_state" >&2
    echo "recovery assets preserved: backup=$BACKUP stage_opt=$STAGE_OPT stage_etc=$STAGE_ETC" >&2
    [ "$STATE_DIR_READY" -ne 1 ] || echo "state/audit recovery evidence preserved: /var/lib/aneb-gateway created_by_this_install=$STATE_DIR_CREATED" >&2
    exit "$status"
  fi
  remove_temporary_assets
  echo "gateway_install=ROLLED_BACK original_enable=$ORIGINAL_ENABLE_STATE original_active=$WAS_ACTIVE" >&2
  [ "$STATE_DIR_READY" -ne 1 ] || echo "state/audit recovery evidence preserved: /var/lib/aneb-gateway created_by_this_install=$STATE_DIR_CREATED" >&2
  exit "$status"
}

trap lifecycle_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

BACKUP="$(mktemp -d /var/tmp/aneb-gateway-install.XXXXXX)"
STAGE_OPT="$(mktemp -d /opt/.aneb-gateway.new.XXXXXX)"
STAGE_ETC="$(mktemp -d /etc/.aneb-gateway.new.XXXXXX)"
chmod 0755 "$STAGE_OPT"
chmod 0750 "$STAGE_ETC"
chown root:"$ANEB_GID" "$STAGE_ETC"

backup_path /opt/aneb-gateway opt
backup_path /etc/aneb-gateway etc
backup_path /etc/systemd/system/aneb-gateway.service unit
backup_path /etc/modules-load.d/aneb-gateway.conf module
backup_path /etc/logrotate.d/aneb-gateway logrotate

install -d -m 0755 "$STAGE_OPT/bin"
install -d -m 0750 "$STAGE_ETC/profiles" "$STAGE_ETC/tls"
chown root:"$ANEB_GID" "$STAGE_ETC" "$STAGE_ETC/profiles" "$STAGE_ETC/tls"
install -m 0755 "$BINARY" "$STAGE_OPT/bin/aneb-gateway"
install -m 0644 "$GATEWAY_DIR/README.md" "$STAGE_OPT/README.md"
shopt -s nullglob
PROFILE_FILES=("$PROFILE_SOURCE"/*.json)
shopt -u nullglob
[ "${#PROFILE_FILES[@]}" -gt 0 ] || die "no gateway profiles were found"
for profile in "${PROFILE_FILES[@]}"; do
  [ -f "$profile" ] && [ ! -L "$profile" ] || die "profile must be a regular non-symlink file: $profile"
  install -m 0644 "$profile" "$STAGE_ETC/profiles/$(basename "$profile")"
done
install -m 0644 "$TLS_CERT" "$STAGE_ETC/tls/cert.pem"
install -m 0644 "$APP_CA_SOURCE" "$STAGE_ETC/tls/app-ca.pem"
install -o "$ANEB_UID" -g "$ANEB_GID" -m 0600 "$TLS_KEY" "$STAGE_ETC/tls/key.pem"
verify_app_ca "$STAGE_ETC/tls/app-ca.pem" || die "staged App gateway CA validation failed"
verify_field_leaf "$STAGE_ETC/tls/app-ca.pem" "$STAGE_ETC/tls/cert.pem" "$LISTEN_IP" || \
  die "staged TLS field leaf no longer satisfies the fixed Debug App trust contract"

if [ -n "$TOKEN_SOURCE" ]; then
  install -o "$ANEB_UID" -g "$ANEB_GID" -m 0600 "$TOKEN_SOURCE" "$STAGE_ETC/token"
  TOKEN_ORIGIN=operator
else
  openssl rand -hex 32 >"$STAGE_ETC/token"
  chown "$ANEB_UID:$ANEB_GID" "$STAGE_ETC/token"
  chmod 0600 "$STAGE_ETC/token"
fi
validate_token_value "$STAGE_ETC/token" || die "staged token must be exactly 64 hexadecimal characters with at most one trailing newline"

python3 - "$WAN" "$MANAGEMENT" "$CLIENT_SUBNET" >"$STAGE_ETC/dedicated-gateway.json" <<'PY'
import json, sys
print(json.dumps({
  "contract_version": "aneb-dedicated-gateway-v1",
  "dedicated_gateway": True,
  "wan_interface": sys.argv[1],
  "management_interface": sys.argv[2],
  "exclusive_client_subnet": sys.argv[3],
}, separators=(",", ":")))
PY
chown root:"$ANEB_GID" "$STAGE_ETC/dedicated-gateway.json"
chmod 0640 "$STAGE_ETC/dedicated-gateway.json"
printf 'ANEB_GATEWAY_LISTEN=%s\nANEB_GATEWAY_WAN=%s\nANEB_GATEWAY_IFB=ifb-aneb0\n' \
  "$LISTEN" "$WAN" >"$STAGE_ETC/gateway.env"
chown root:root "$STAGE_ETC/gateway.env"
chmod 0644 "$STAGE_ETC/gateway.env"
parse_gateway_env "$STAGE_ETC/gateway.env" || die "installer generated an invalid gateway.env"

ensure_state_directory /var/lib/aneb-gateway "$ANEB_UID" "$ANEB_GID" || \
  die "refusing unsafe /var/lib/aneb-gateway: existing path must be a non-mounted, non-symlink directory owned by the service account/group with mode 0750; metadata was not changed"

# From this point onward every explicit exit and command failure runs lifecycle_exit.
SERVICE_TOUCHED=1
if [ "$WAS_ACTIVE" -eq 1 ]; then
  systemctl stop "$SERVICE" || true
fi
service_is_inactive || die "existing gateway service did not reach an inactive state"
cleanup_with_recovery_assets || die "existing traffic-control state could not be verified clean"

"$STAGE_OPT/bin/aneb-gateway" -preflight-only \
  -listen="$LISTEN" -profiles="$STAGE_ETC/profiles" \
  -wan="$WAN" -ifb=ifb-aneb0 \
  -attestation="$STAGE_ETC/dedicated-gateway.json" \
  -token-file="$STAGE_ETC/token" -audit=/var/lib/aneb-gateway/audit.jsonl \
  -tc-state=/var/lib/aneb-gateway/tc-state.json \
  -tls-ca="$STAGE_ETC/tls/app-ca.pem" \
  -tls-cert="$STAGE_ETC/tls/cert.pem" -tls-key="$STAGE_ETC/tls/key.pem"

PATH_MUTATION_STARTED=1
rm -rf -- /opt/aneb-gateway /etc/aneb-gateway
mv "$STAGE_OPT" /opt/aneb-gateway
STAGE_OPT=""
mv "$STAGE_ETC" /etc/aneb-gateway
STAGE_ETC=""
install -d -m 0755 /etc/systemd/system /etc/modules-load.d /etc/logrotate.d
install -m 0644 "$UNIT_SOURCE" /etc/systemd/system/aneb-gateway.service
install -m 0644 "$LOGROTATE_SOURCE" /etc/logrotate.d/aneb-gateway
printf '%s\n' ifb >/etc/modules-load.d/aneb-gateway.conf
chmod 0644 /etc/modules-load.d/aneb-gateway.conf
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null
systemctl start "$SERVICE"
systemctl is-active --quiet "$SERVICE"

READY=0
for _ in $(seq 1 30); do
  if curl --noproxy '*' --cacert /etc/aneb-gateway/tls/app-ca.pem --connect-timeout 2 --max-time 3 -fsS \
      "https://$LISTEN/healthz" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "ready"' 2>/dev/null; then
    READY=1
    break
  fi
  sleep 0.2
done
[ "$READY" -eq 1 ] || die "gateway health verification failed"

COMMITTED=1
trap - EXIT INT TERM
remove_temporary_assets
echo "gateway_install=PASS version=0.2.0 listen=$LISTEN wan=$WAN management=$MANAGEMENT"
echo "gateway_token_path=/etc/aneb-gateway/token (value intentionally not printed)"
echo "gateway_token_origin=$TOKEN_ORIGIN previous_installed_token_reused=no"
echo "routing_prerequisites=external NAT/DHCP/AP/firewall configuration preserved"
