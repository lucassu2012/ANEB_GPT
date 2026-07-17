#!/usr/bin/env bash
set -euo pipefail
umask 077
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

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
    echo "installed App gateway CA fingerprint mismatch: expected $EXPECTED_APP_CA_DER_SHA256, got ${actual:-unreadable}" >&2
    return 1
  }
}

verify_field_leaf() {
  local ca_path="$1" cert_path="$2" listen_ip="$3" basic_constraints
  basic_constraints="$(openssl x509 -in "$cert_path" -noout -ext basicConstraints)" || return 1
  grep -q 'CA:FALSE' <<<"$basic_constraints" || {
    echo "installed TLS certificate must be a CA:FALSE field leaf" >&2
    return 1
  }
  openssl x509 -in "$cert_path" -noout -checkend 86400 >/dev/null || return 1
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
  local path="$1" max_size="${2:-0}" mode size
  [ -f "$path" ] && [ ! -L "$path" ] || return 1
  [ "$(stat -c '%u' -- "$path")" = 0 ] || return 1
  mode="$(stat -c '%a' -- "$path")"
  mode_is_safe "$mode" || return 1
  if [ "$max_size" -gt 0 ]; then
    size="$(stat -c '%s' -- "$path")"
    [ "$size" -gt 0 ] && [ "$size" -le "$max_size" ] || return 1
  fi
}

secure_service_directory() {
  local path="$1" expected_uid="$2" expected_gid="$3"
  [ -d "$path" ] && [ ! -L "$path" ] || return 1
  mountpoint -q "$path" && return 1
  [ "$(stat -c '%u' -- "$path")" = "$expected_uid" ] || return 1
  [ "$(stat -c '%g' -- "$path")" = "$expected_gid" ] || return 1
  [ "$(stat -c '%a' -- "$path")" = 750 ]
}

secure_service_regular() {
  local path="$1" expected_uid="$2" expected_gid="$3" max_size="$4" size
  [ -f "$path" ] && [ ! -L "$path" ] || return 1
  [ "$(stat -c '%u' -- "$path")" = "$expected_uid" ] || return 1
  [ "$(stat -c '%g' -- "$path")" = "$expected_gid" ] || return 1
  [ "$(stat -c '%a' -- "$path")" = 600 ] || return 1
  size="$(stat -c '%s' -- "$path")"
  [ "$size" -le "$max_size" ]
}

secure_optional_service_regular() {
  local path="$1"
  shift
  if [ ! -e "$path" ] && [ ! -L "$path" ]; then
    return 0
  fi
  secure_service_regular "$path" "$@"
}

validate_gateway_env_values() {
  local port
  [[ "$ANEB_GATEWAY_WAN" =~ ^[A-Za-z0-9_.-]{1,15}$ ]] || return 1
  [ "$ANEB_GATEWAY_WAN" != lo ] || return 1
  [ "$ANEB_GATEWAY_IFB" = ifb-aneb0 ] || return 1
  [[ "$ANEB_GATEWAY_LISTEN" =~ ^192\.168\.77\.1:[0-9]{1,5}$ ]] || return 1
  port="${ANEB_GATEWAY_LISTEN##*:}"
  (( 10#$port >= 1 && 10#$port <= 65535 ))
}

parse_gateway_env() {
  local path="$1" line value
  local seen_listen=0 seen_wan=0 seen_ifb=0 lines=0
  secure_root_directory /etc/aneb-gateway || return 1
  secure_root_regular "$path" 1024 || return 1
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

if [ "$(id -u)" -ne 0 ] || [ "$(uname -s)" != Linux ]; then
  die "preflight requires root on Linux"
fi
for command in install stat flock systemctl openssl sha256sum awk grep id mountpoint; do
  command -v "$command" >/dev/null || die "missing command: $command"
done
acquire_lifecycle_lock

ANEB_UID="$(id -u aneb-gateway 2>/dev/null)" || die "aneb-gateway service account is missing"
ANEB_GID="$(id -g aneb-gateway 2>/dev/null)" || die "aneb-gateway service group is missing"
[ "$ANEB_UID" -ne 0 ] && [ "$ANEB_GID" -ne 0 ] || die "aneb-gateway service identity must not be root"

secure_root_directory /opt/aneb-gateway || die "unsafe or missing /opt/aneb-gateway"
secure_root_directory /opt/aneb-gateway/bin || die "unsafe or missing gateway binary directory"
secure_root_regular /opt/aneb-gateway/bin/aneb-gateway || die "gateway binary must be a root-owned executable non-symlink"
[ -x /opt/aneb-gateway/bin/aneb-gateway ] || die "gateway binary is not executable"
parse_gateway_env /etc/aneb-gateway/gateway.env || die "gateway.env failed strict ownership, syntax, or Debug lab-contract validation"
secure_root_directory /etc/aneb-gateway/tls || die "unsafe or missing gateway TLS directory"
secure_root_regular /etc/aneb-gateway/tls/app-ca.pem 65536 || die "installed App gateway CA has unsafe ownership, permissions, type, or size"
secure_root_regular /etc/aneb-gateway/tls/cert.pem 1048576 || die "installed TLS field leaf has unsafe ownership, permissions, type, or size"
secure_service_regular /etc/aneb-gateway/tls/key.pem "$ANEB_UID" "$ANEB_GID" 65536 || \
  die "installed TLS private key must be a service-owned mode-0600 non-symlink file"
secure_service_regular /etc/aneb-gateway/token "$ANEB_UID" "$ANEB_GID" 65 || \
  die "installed bearer token must be a service-owned mode-0600 non-symlink file"
verify_app_ca /etc/aneb-gateway/tls/app-ca.pem || die "installed App gateway CA validation failed"
verify_field_leaf /etc/aneb-gateway/tls/app-ca.pem /etc/aneb-gateway/tls/cert.pem 192.168.77.1 || \
  die "installed TLS field leaf is not trusted by the fixed Debug App CA for 192.168.77.1"
secure_service_directory /var/lib/aneb-gateway "$ANEB_UID" "$ANEB_GID" || \
  die "/var/lib/aneb-gateway must be a non-mounted service-owned directory with mode 0750"
secure_optional_service_regular /var/lib/aneb-gateway/audit.jsonl "$ANEB_UID" "$ANEB_GID" 67108864 || \
  die "existing audit log must be a service-owned mode-0600 non-symlink file no larger than 64 MiB"
secure_optional_service_regular /var/lib/aneb-gateway/tc-state.json "$ANEB_UID" "$ANEB_GID" 16384 || \
  die "existing tc ownership state must be a service-owned mode-0600 non-symlink file no larger than 16 KiB"
ACTIVE_STATE="$(systemctl show aneb-gateway.service -p ActiveState --value 2>/dev/null)" || die "cannot query systemd service state"
if [ "$ACTIVE_STATE" != inactive ] && [ "$ACTIVE_STATE" != failed ]; then
  die "stop aneb-gateway.service before read-only resource preflight"
fi

exec /opt/aneb-gateway/bin/aneb-gateway -preflight-only \
  -listen="$ANEB_GATEWAY_LISTEN" \
  -profiles=/etc/aneb-gateway/profiles \
  -wan="$ANEB_GATEWAY_WAN" -ifb="$ANEB_GATEWAY_IFB" \
  -attestation=/etc/aneb-gateway/dedicated-gateway.json \
  -token-file=/etc/aneb-gateway/token \
  -audit=/var/lib/aneb-gateway/audit.jsonl \
  -tc-state=/var/lib/aneb-gateway/tc-state.json \
  -tls-ca=/etc/aneb-gateway/tls/app-ca.pem \
  -tls-cert=/etc/aneb-gateway/tls/cert.pem \
  -tls-key=/etc/aneb-gateway/tls/key.pem
