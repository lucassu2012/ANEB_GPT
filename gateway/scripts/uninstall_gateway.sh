#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

die() {
  echo "$*" >&2
  exit 1
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

service_is_inactive() {
  local state
  state="$(systemctl show aneb-gateway.service -p ActiveState --value 2>/dev/null)" || return 1
  case "$state" in
    inactive|failed) return 0 ;;
    *) return 1 ;;
  esac
}

get_enable_state() {
  local state
  state="$(systemctl is-enabled aneb-gateway.service 2>/dev/null || true)"
  if [ -n "$state" ]; then printf '%s\n' "$state"; else printf '%s\n' not-found; fi
}

validate_service_account_for_deletion() {
  local entry uid home shell
  getent passwd aneb-gateway >/dev/null || return 0
  getent group aneb-gateway >/dev/null || return 1
  entry="$(getent passwd aneb-gateway)"
  IFS=: read -r _ _ uid _ _ home shell <<<"$entry"
  [ "$uid" -ne 0 ] && [ "$home" = /nonexistent ] || return 1
  case "$shell" in
    /usr/sbin/nologin|/sbin/nologin|/bin/false) return 0 ;;
    *) return 1 ;;
  esac
}

purge_preserved_data() {
  validate_service_account_for_deletion || die "refusing to delete an unexpected aneb-gateway account"
  if [ -e /var/lib/aneb-gateway ] || [ -L /var/lib/aneb-gateway ]; then
    [ -d /var/lib/aneb-gateway ] && [ ! -L /var/lib/aneb-gateway ] || die "refusing to purge unsafe /var/lib/aneb-gateway"
    mountpoint -q /var/lib/aneb-gateway && die "refusing to purge mounted /var/lib/aneb-gateway"
    rm -rf -- /var/lib/aneb-gateway
  fi
  if getent passwd aneb-gateway >/dev/null; then
    userdel aneb-gateway
  fi
}

PURGE_DATA=0
case "${1:-}" in
  "") ;;
  --purge-data=YES) PURGE_DATA=1 ;;
  *) echo "usage: uninstall_gateway.sh [--purge-data=YES]" >&2; exit 2 ;;
esac
if [ "$(id -u)" -ne 0 ] || [ "$(uname -s)" != Linux ]; then
  die "uninstaller requires root on Linux"
fi
for command in systemctl flock install stat mountpoint getent userdel mktemp cp rm; do
  command -v "$command" >/dev/null || die "missing command: $command"
done
acquire_lifecycle_lock

SERVICE=aneb-gateway.service
RUNTIME_PRESENT=0
for path in \
  /opt/aneb-gateway \
  /etc/aneb-gateway \
  /etc/systemd/system/aneb-gateway.service \
  /etc/systemd/system/multi-user.target.wants/aneb-gateway.service \
  /etc/modules-load.d/aneb-gateway.conf \
  /etc/logrotate.d/aneb-gateway \
  /var/lib/aneb-gateway/tc-state.json; do
  if [ -e "$path" ] || [ -L "$path" ]; then RUNTIME_PRESENT=1; fi
done
LOAD_STATE="$(systemctl show "$SERVICE" -p LoadState --value 2>/dev/null)" || die "cannot query systemd service state"
case "$LOAD_STATE" in
  not-found) ;;
  *) RUNTIME_PRESENT=1 ;;
esac

if [ "$RUNTIME_PRESENT" -eq 0 ]; then
  if [ "$PURGE_DATA" -eq 1 ]; then
    purge_preserved_data
    echo "gateway_uninstall=PASS state=already_absent audit=purged"
  else
    echo "gateway_uninstall=PASS state=already_absent audit=preserved path=/var/lib/aneb-gateway"
  fi
  exit 0
fi

# Any partial install must retain a verified cleanup binary and strict config.
secure_root_directory /opt/aneb-gateway || die "partial/unsafe install: /opt/aneb-gateway is unavailable; refusing deletion"
secure_root_directory /opt/aneb-gateway/bin || die "partial/unsafe install: gateway binary directory is unavailable; refusing deletion"
secure_root_regular /opt/aneb-gateway/bin/aneb-gateway || die "partial/unsafe install: verified cleanup binary is unavailable; refusing deletion"
[ -x /opt/aneb-gateway/bin/aneb-gateway ] || die "partial install: cleanup binary is not executable; refusing deletion"
parse_gateway_env /etc/aneb-gateway/gateway.env || die "partial/unsafe install: gateway.env cannot be strictly validated; refusing deletion"
mountpoint -q /opt/aneb-gateway && die "refusing to uninstall mounted /opt/aneb-gateway"
mountpoint -q /etc/aneb-gateway && die "refusing to uninstall mounted /etc/aneb-gateway"

WAS_ACTIVE=0
systemctl is-active --quiet "$SERVICE" && WAS_ACTIVE=1 || true
ORIGINAL_ENABLE_STATE="$(get_enable_state)"
BACKUP="$(mktemp -d /var/tmp/aneb-gateway-uninstall.XXXXXX)"
COMMITTED=0
SERVICE_STOP_ATTEMPTED=0
CLEANUP_VERIFIED=0
DELETION_STARTED=0

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

uninstall_exit() {
  local status=$? restored=1 current_state
  trap - EXIT INT TERM
  set +e
  if [ "$COMMITTED" -eq 1 ]; then
    rm -rf -- "$BACKUP"
    exit "$status"
  fi
  if [ "$status" -eq 0 ]; then status=1; fi
  if [ "$SERVICE_STOP_ATTEMPTED" -eq 1 ] && [ "$CLEANUP_VERIFIED" -ne 1 ]; then
    rm -rf -- "$BACKUP"
    echo "gateway_uninstall=FAILED_CLOSED cleanup_verified=0; installed recovery assets preserved and service not restarted" >&2
    exit "$status"
  fi
  if [ "$DELETION_STARTED" -eq 1 ]; then
    restore_path /opt/aneb-gateway opt || restored=0
    restore_path /etc/aneb-gateway etc || restored=0
    restore_path /etc/systemd/system/aneb-gateway.service unit || restored=0
    restore_path /etc/systemd/system/multi-user.target.wants/aneb-gateway.service wants || restored=0
    restore_path /etc/modules-load.d/aneb-gateway.conf module || restored=0
    restore_path /etc/logrotate.d/aneb-gateway logrotate || restored=0
    systemctl daemon-reload || restored=0
  fi
  case "$ORIGINAL_ENABLE_STATE" in
    enabled) systemctl enable "$SERVICE" >/dev/null 2>&1 || restored=0 ;;
    enabled-runtime) systemctl enable --runtime "$SERVICE" >/dev/null 2>&1 || restored=0 ;;
    *) ;;
  esac
  current_state="$(get_enable_state)"
  [ "$current_state" = "$ORIGINAL_ENABLE_STATE" ] || restored=0
  if [ "$restored" -eq 1 ] && [ "$WAS_ACTIVE" -eq 1 ]; then
    systemctl start "$SERVICE" >/dev/null 2>&1 || restored=0
    systemctl is-active --quiet "$SERVICE" || restored=0
  fi
  if [ "$restored" -eq 1 ]; then
    rm -rf -- "$BACKUP"
    echo "gateway_uninstall=ROLLED_BACK original_enable=$ORIGINAL_ENABLE_STATE original_active=$WAS_ACTIVE" >&2
  else
    echo "gateway_uninstall=ROLLBACK_INCOMPLETE backup_preserved=$BACKUP original_enable=$ORIGINAL_ENABLE_STATE current_enable=$current_state" >&2
  fi
  exit "$status"
}

trap uninstall_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
backup_path /opt/aneb-gateway opt
backup_path /etc/aneb-gateway etc
backup_path /etc/systemd/system/aneb-gateway.service unit
backup_path /etc/systemd/system/multi-user.target.wants/aneb-gateway.service wants
backup_path /etc/modules-load.d/aneb-gateway.conf module
backup_path /etc/logrotate.d/aneb-gateway logrotate

SERVICE_STOP_ATTEMPTED=1
systemctl stop "$SERVICE" >/dev/null 2>&1 || true
service_is_inactive || die "gateway service did not reach an inactive state; no files were deleted"
/opt/aneb-gateway/bin/aneb-gateway -cleanup-only \
  -wan="$ANEB_GATEWAY_WAN" -ifb="$ANEB_GATEWAY_IFB" \
  -tc-state=/var/lib/aneb-gateway/tc-state.json || \
  die "owned traffic-control cleanup was not verified; service remains stopped and no files were deleted"
CLEANUP_VERIFIED=1
DELETION_STARTED=1
systemctl disable "$SERVICE" >/dev/null 2>&1 || true
rm -f -- \
  /etc/systemd/system/aneb-gateway.service \
  /etc/systemd/system/multi-user.target.wants/aneb-gateway.service \
  /etc/modules-load.d/aneb-gateway.conf \
  /etc/logrotate.d/aneb-gateway
rm -rf -- /opt/aneb-gateway /etc/aneb-gateway
systemctl daemon-reload
systemctl reset-failed "$SERVICE" >/dev/null 2>&1 || true

for path in \
  /opt/aneb-gateway \
  /etc/aneb-gateway \
  /etc/systemd/system/aneb-gateway.service \
  /etc/systemd/system/multi-user.target.wants/aneb-gateway.service \
  /etc/modules-load.d/aneb-gateway.conf \
  /etc/logrotate.d/aneb-gateway \
  /var/lib/aneb-gateway/tc-state.json; do
  [ ! -e "$path" ] && [ ! -L "$path" ] || die "uninstall verification failed; runtime artifact remains: $path"
done
service_is_inactive || die "uninstall verification failed; service is still active"

COMMITTED=1
trap - EXIT INT TERM
rm -rf -- "$BACKUP"

if [ "$PURGE_DATA" -eq 1 ]; then
  purge_preserved_data
  echo "gateway_uninstall=PASS audit=purged"
else
  echo "gateway_uninstall=PASS audit=preserved path=/var/lib/aneb-gateway"
fi
echo "routing_prerequisites=unchanged NAT/DHCP/AP/firewall configuration was not modified"
