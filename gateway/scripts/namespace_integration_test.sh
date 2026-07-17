#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "namespace integration test must run as root" >&2
  exit 1
fi
for command in ip tc curl python3 ping; do
  command -v "$command" >/dev/null || { echo "missing command: $command" >&2; exit 1; }
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BINARY="${ANEB_GATEWAY_BINARY:-$GATEWAY_DIR/aneb-gateway-linux}"
PROFILE_DIR="${ANEB_GATEWAY_PROFILES:-$GATEWAY_DIR/profiles}"
if [ ! -x "$BINARY" ]; then
  echo "gateway binary is not executable: $BINARY" >&2
  exit 1
fi

SUFFIX="$$"
CLIENT="aneb-c-$SUFFIX"
GATEWAY="aneb-g-$SUFFIX"
SERVER="aneb-s-$SUFFIX"
C_LAN="ac${SUFFIX}c"
G_LAN="ac${SUFFIX}l"
G_WAN="ac${SUFFIX}w"
S_WAN="ac${SUFFIX}s"
IFB="ac${SUFFIX}i"
TMP="$(mktemp -d /tmp/aneb-gateway-test.XXXXXX)"
PID=""
HOST_DEFAULT_DEV="$(ip route show default | awk 'NR==1 {print $5}')"
HOST_QDISC_BEFORE=""
if [ -n "$HOST_DEFAULT_DEV" ]; then
  HOST_QDISC_BEFORE="$(tc qdisc show dev "$HOST_DEFAULT_DEV")"
fi

cleanup() {
  STATUS=$?
  set +e
  if [ -n "$PID" ]; then kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; fi
  ip netns del "$CLIENT" 2>/dev/null
  ip netns del "$GATEWAY" 2>/dev/null
  ip netns del "$SERVER" 2>/dev/null
  rm -rf "$TMP"
  if [ -n "$HOST_DEFAULT_DEV" ] && [ "$(tc qdisc show dev "$HOST_DEFAULT_DEV")" != "$HOST_QDISC_BEFORE" ]; then
    echo "host default qdisc changed unexpectedly" >&2
    STATUS=1
  fi
  if ip netns list | grep -Eq "^($CLIENT|$GATEWAY|$SERVER)( |$)"; then
    echo "test network namespace cleanup failed" >&2
    STATUS=1
  fi
  trap - EXIT
  exit "$STATUS"
}
trap cleanup EXIT INT TERM

modprobe ifb >/dev/null 2>&1 || true
ip netns add "$CLIENT"
ip netns add "$GATEWAY"
ip netns add "$SERVER"
ip link add "$C_LAN" type veth peer name "$G_LAN"
ip link add "$G_WAN" type veth peer name "$S_WAN"
ip link set "$C_LAN" netns "$CLIENT"
ip link set "$G_LAN" netns "$GATEWAY"
ip link set "$G_WAN" netns "$GATEWAY"
ip link set "$S_WAN" netns "$SERVER"

ip -n "$CLIENT" addr add 10.77.0.2/24 dev "$C_LAN"
ip -n "$CLIENT" link set lo up
ip -n "$CLIENT" link set "$C_LAN" up
ip -n "$CLIENT" route add default via 10.77.0.1
ip -n "$GATEWAY" addr add 10.77.0.1/24 dev "$G_LAN"
ip -n "$GATEWAY" addr add 10.78.0.1/24 dev "$G_WAN"
ip -n "$GATEWAY" link set lo up
ip -n "$GATEWAY" link set "$G_LAN" up
ip -n "$GATEWAY" link set "$G_WAN" up
ip netns exec "$GATEWAY" sysctl -q -w net.ipv4.ip_forward=1
ip -n "$SERVER" addr add 10.78.0.2/24 dev "$S_WAN"
ip -n "$SERVER" link set lo up
ip -n "$SERVER" link set "$S_WAN" up
ip -n "$SERVER" route add 10.77.0.0/24 via 10.78.0.1

printf '%s\n' '0123456789abcdef0123456789abcdef' >"$TMP/token"
chmod 600 "$TMP/token"
cat >"$TMP/attestation.json" <<EOF
{"contract_version":"aneb-dedicated-gateway-v1","dedicated_gateway":true,"wan_interface":"$G_WAN","management_interface":"$G_LAN","exclusive_client_subnet":"10.77.0.0/24"}
EOF

ip netns exec "$GATEWAY" "$BINARY" \
  -listen=127.0.0.1:19444 \
  -profiles="$PROFILE_DIR" \
  -wan="$G_WAN" -ifb="$IFB" \
  -attestation="$TMP/attestation.json" \
  -token-file="$TMP/token" -audit="$TMP/audit.jsonl" \
  -allow-insecure-loopback >"$TMP/gateway.log" 2>&1 &
PID=$!

for _ in $(seq 1 50); do
  if ip netns exec "$GATEWAY" curl -fsS http://127.0.0.1:19444/healthz >/dev/null 2>&1; then break; fi
  sleep 0.1
done
ip netns exec "$GATEWAY" curl -fsS http://127.0.0.1:19444/healthz >/dev/null

ping_avg() {
  ip netns exec "$CLIENT" ping -q -c 5 -W 1 10.78.0.2 | awk -F'/' '/^rtt|^round-trip/ {print $5}'
}

BASELINE="$(ping_avg)"
python3 - "$BASELINE" <<'PY'
import sys
value=float(sys.argv[1])
assert value < 20, f"baseline RTT too high: {value}ms"
PY

AUTH='Authorization: Bearer 0123456789abcdef0123456789abcdef'
START="$(ip netns exec "$GATEWAY" curl -fsS -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"run_id":"namespace-loss","profile_ref":"ip_loss_latency@1.0.0"}' \
  http://127.0.0.1:19444/v1/experiments)"
EXPERIMENT_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])' <<<"$START")"
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl -fsS -H "$AUTH" "http://127.0.0.1:19444/v1/experiments/$EXPERIMENT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
  [ "$PHASE" = active ] && break
  sleep 0.1
done
[ "$PHASE" = active ]
IMPAIRED="$(ping_avg)"
python3 - "$BASELINE" "$IMPAIRED" <<'PY'
import sys
baseline, impaired = map(float, sys.argv[1:])
assert impaired - baseline >= 70, f"RTT sensitivity missing: baseline={baseline} impaired={impaired}"
PY
ip netns exec "$GATEWAY" curl -fsS -X DELETE -H "$AUTH" "http://127.0.0.1:19444/v1/experiments/$EXPERIMENT_ID" >/dev/null
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl -fsS -H "$AUTH" "http://127.0.0.1:19444/v1/experiments/$EXPERIMENT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
  [ "$PHASE" = completed ] && break
  sleep 0.1
done
[ "$PHASE" = completed ]
POST="$(ping_avg)"
python3 - "$POST" <<'PY'
import sys
value=float(sys.argv[1])
assert value < 20, f"cleanup did not restore RTT: {value}ms"
PY

OUTAGE="$(ip netns exec "$GATEWAY" curl -fsS -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"run_id":"namespace-outage","profile_ref":"ip_outage_recovery@1.0.0"}' \
  http://127.0.0.1:19444/v1/experiments)"
OUTAGE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])' <<<"$OUTAGE")"
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl -fsS -H "$AUTH" "http://127.0.0.1:19444/v1/experiments/$OUTAGE_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
  [ "$PHASE" = active ] && break
  sleep 0.1
done
[ "$PHASE" = active ]
if ip netns exec "$CLIENT" ping -q -c 1 -W 1 10.78.0.2 >/dev/null; then
  echo "100% outage did not block traffic" >&2
  exit 1
fi
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl -fsS -H "$AUTH" "http://127.0.0.1:19444/v1/experiments/$OUTAGE_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
  [ "$PHASE" = completed ] && break
  sleep 0.1
done
[ "$PHASE" = completed ]
ip netns exec "$CLIENT" ping -q -c 1 -W 1 10.78.0.2 >/dev/null

LINES="$(wc -l <"$TMP/audit.jsonl")"
[ "$LINES" -ge 8 ]
echo "namespace_gateway_test=PASS baseline_ms=$BASELINE impaired_ms=$IMPAIRED restored_ms=$POST audit_events=$LINES host_qdisc_unchanged=yes"
