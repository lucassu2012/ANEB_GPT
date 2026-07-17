#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

if [ "$(id -u)" -ne 0 ] || [ "$(uname -s)" != Linux ]; then
  echo "install security regression test requires root on Linux" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="$SCRIPT_DIR/install_gateway.sh"
export ANEB_GATEWAY_INSTALL_FUNCTIONS_ONLY=1
# shellcheck source=install_gateway.sh
source "$INSTALLER"
unset ANEB_GATEWAY_INSTALL_FUNCTIONS_ONLY

TMP="$(mktemp -d /tmp/aneb-install-security.XXXXXX)"
cleanup() {
  rm -rf -- "$TMP"
}
trap cleanup EXIT INT TERM

expect_failure() {
  if "$@"; then
    echo "expected security check to fail: $*" >&2
    exit 1
  fi
}

SAFE="$TMP/safe"
install -d -o root -g root -m 0700 "$SAFE"
printf '%064d\n' 0 >"$SAFE/token"
chmod 0600 "$SAFE/token"
secure_token_source "$SAFE/token"

printf '%s\n' 'private-key-fixture' >"$SAFE/tls-key.pem"
chmod 0600 "$SAFE/tls-key.pem"
secure_root_secret_source "$SAFE/tls-key.pem" 65536
chmod 0644 "$SAFE/tls-key.pem"
expect_failure secure_root_secret_source "$SAFE/tls-key.pem" 65536

chmod 0644 "$SAFE/token"
expect_failure secure_token_source "$SAFE/token"
chmod 0600 "$SAFE/token"

printf '%065d\n' 0 >"$SAFE/token"
expect_failure secure_token_source "$SAFE/token"
printf '%064d\n\n' 0 >"$SAFE/token"
expect_failure secure_token_source "$SAFE/token"
printf '%064d\n' 0 >"$SAFE/token"

ln -s "$SAFE/token" "$SAFE/token-link"
expect_failure secure_token_source "$SAFE/token-link"

UNSAFE_PARENT="$TMP/unsafe-parent"
install -d -o root -g root -m 0777 "$UNSAFE_PARENT"
printf '%064d\n' 0 >"$UNSAFE_PARENT/token"
chmod 0600 "$UNSAFE_PARENT/token"
expect_failure secure_token_source "$UNSAFE_PARENT/token"

chown 65534:65534 "$SAFE/token"
expect_failure secure_token_source "$SAFE/token"

STATE_DIR_CREATED=0
STATE_DIR_READY=0
ensure_state_directory "$SAFE/new-state" 0 0
[ "$STATE_DIR_CREATED" -eq 1 ] && [ "$STATE_DIR_READY" -eq 1 ]
[ "$(stat -c '%u:%g:%a' -- "$SAFE/new-state")" = "0:0:750" ]

install -d -o root -g root -m 0755 "$SAFE/wrong-mode"
expect_failure ensure_state_directory "$SAFE/wrong-mode" 0 0
[ "$(stat -c '%a' -- "$SAFE/wrong-mode")" = 755 ] || {
  echo "existing state directory metadata was silently changed" >&2
  exit 1
}

install -d -o root -g root -m 0750 "$SAFE/external-target"
ln -s "$SAFE/external-target" "$SAFE/state-link"
expect_failure ensure_state_directory "$SAFE/state-link" 0 0
[ "$(stat -c '%a' -- "$SAFE/external-target")" = 750 ] || {
  echo "symlink target metadata was changed" >&2
  exit 1
}

install -d -o root -g root -m 0750 "$SAFE/mounted"
mountpoint() {
  [ "${1:-}" = -q ] && [ "${2:-}" = "$SAFE/mounted" ]
}
expect_failure ensure_state_directory "$SAFE/mounted" 0 0

if grep -Eq 'install .* /etc/aneb-gateway/token .*STAGE_ETC/token' "$INSTALLER"; then
  echo "installer still silently reuses the previously installed token" >&2
  exit 1
fi

echo "install_security_test=PASS token_source=strict state_directory=fail_closed previous_token_reused=no"
