#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "namespace integration test must run as root" >&2
  exit 1
fi
for command in ip tc curl python3 ping openssl sha256sum awk install; do
  command -v "$command" >/dev/null || { echo "missing command: $command" >&2; exit 1; }
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BINARY="${ANEB_GATEWAY_BINARY:-$GATEWAY_DIR/aneb-gateway-linux}"
PROFILE_DIR="${ANEB_GATEWAY_PROFILES:-$GATEWAY_DIR/profiles}"
CA_SOURCE="${ANEB_GATEWAY_TEST_CA:-$GATEWAY_DIR/trust/aneb_gateway_ca.pem}"
CERT_SOURCE="${ANEB_GATEWAY_TEST_CERT:-}"
KEY_SOURCE="${ANEB_GATEWAY_TEST_KEY:-}"
EXPECTED_CA_DER_SHA256=2089A92C77B04FA392E24D1D71819EF1AC3D86B5131B0C6064BD6B092F5AD361
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

if [ -z "$CERT_SOURCE" ] || [ -z "$KEY_SOURCE" ]; then
  echo "namespace_gateway_test=BLOCKED_EXTERNAL provide a CA-signed field leaf with ANEB_GATEWAY_TEST_CERT and ANEB_GATEWAY_TEST_KEY" >&2
  exit 4
fi
for source_file in "$CA_SOURCE" "$CERT_SOURCE" "$KEY_SOURCE"; do
  [ -f "$source_file" ] && [ ! -L "$source_file" ] || {
    echo "namespace_gateway_test=BLOCKED_EXTERNAL missing or unsafe external TLS input: $source_file" >&2
    exit 4
  }
done
install -m 0644 "$CA_SOURCE" "$TMP/ca.pem"
install -m 0644 "$CERT_SOURCE" "$TMP/cert.pem"
install -m 0600 "$KEY_SOURCE" "$TMP/key.pem"
CA_DER_SHA256="$(openssl x509 -in "$TMP/ca.pem" -outform DER | sha256sum | awk '{print toupper($1)}')"
[ "$CA_DER_SHA256" = "$EXPECTED_CA_DER_SHA256" ] || {
  echo "namespace_gateway_test=BLOCKED_EXTERNAL fixed CA fingerprint mismatch" >&2
  exit 4
}
openssl verify -no-CApath -CAfile "$TMP/ca.pem" -purpose sslserver -verify_ip 192.168.77.1 "$TMP/cert.pem" >/dev/null || {
  echo "namespace_gateway_test=BLOCKED_EXTERNAL field leaf is not trusted for 192.168.77.1" >&2
  exit 4
}
CERT_PUB="$(openssl x509 -in "$TMP/cert.pem" -pubkey -noout | sha256sum | awk '{print $1}')"
KEY_PUB="$(openssl pkey -in "$TMP/key.pem" -pubout 2>/dev/null | sha256sum | awk '{print $1}')"
[ "$CERT_PUB" = "$KEY_PUB" ] || {
  echo "namespace_gateway_test=BLOCKED_EXTERNAL field leaf and private key do not match" >&2
  exit 4
}

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 1 \
  -subj '/CN=untrusted namespace gateway/O=ANEB' \
  -addext 'basicConstraints=critical,CA:FALSE' \
  -addext 'keyUsage=critical,digitalSignature,keyAgreement' \
  -addext 'extendedKeyUsage=serverAuth' \
  -addext 'subjectAltName=IP:192.168.77.1' \
  -keyout "$TMP/untrusted-key.pem" -out "$TMP/untrusted-cert.pem" >/dev/null 2>&1
chmod 600 "$TMP/untrusted-key.pem"

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

ip -n "$CLIENT" addr add 192.168.77.2/24 dev "$C_LAN"
ip -n "$CLIENT" link set lo up
ip -n "$CLIENT" link set "$C_LAN" up
ip -n "$CLIENT" route add default via 192.168.77.1
ip -n "$GATEWAY" addr add 192.168.77.1/24 dev "$G_LAN"
ip -n "$GATEWAY" addr add 192.168.78.1/24 dev "$G_WAN"
ip -n "$GATEWAY" link set lo up
ip -n "$GATEWAY" link set "$G_LAN" up
ip -n "$GATEWAY" link set "$G_WAN" up
ip netns exec "$GATEWAY" sysctl -q -w net.ipv4.ip_forward=1
ip -n "$GATEWAY" route add default via 192.168.78.2
ip -n "$SERVER" addr add 192.168.78.2/24 dev "$S_WAN"
ip -n "$SERVER" link set lo up
ip -n "$SERVER" link set "$S_WAN" up
ip -n "$SERVER" route add 192.168.77.0/24 via 192.168.78.1

TOKEN='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
printf '%s\n' "$TOKEN" >"$TMP/token"
chmod 600 "$TMP/token"
cat >"$TMP/attestation.json" <<EOF
{"contract_version":"aneb-dedicated-gateway-v1","dedicated_gateway":true,"wan_interface":"$G_WAN","management_interface":"$G_LAN","exclusive_client_subnet":"192.168.77.0/24"}
EOF
chmod 600 "$TMP/attestation.json"
GATEWAY_QDISC_BEFORE="$(ip netns exec "$GATEWAY" tc qdisc show dev "$G_WAN")"
set +e
ip netns exec "$GATEWAY" "$BINARY" \
  -listen=192.168.77.1:19444 \
  -profiles="$PROFILE_DIR" \
  -wan="$G_WAN" -ifb="$IFB" \
  -attestation="$TMP/attestation.json" \
  -token-file="$TMP/token" -audit="$TMP/negative-audit.jsonl" \
  -tc-state="$TMP/negative-tc-state.json" \
  -tls-ca="$TMP/ca.pem" \
  -tls-cert="$TMP/untrusted-cert.pem" -tls-key="$TMP/untrusted-key.pem" >"$TMP/negative.log" 2>&1
NEGATIVE_STATUS=$?
set -e
[ "$NEGATIVE_STATUS" -ne 0 ]
[ "$(ip netns exec "$GATEWAY" tc qdisc show dev "$G_WAN")" = "$GATEWAY_QDISC_BEFORE" ]
[ ! -e "$TMP/negative-tc-state.json" ]
if ip netns exec "$GATEWAY" ip link show dev "$IFB" >/dev/null 2>&1; then
  echo "failed prerequisite created IFB" >&2
  exit 1
fi

ip netns exec "$GATEWAY" "$BINARY" \
  -listen=192.168.77.1:19444 \
  -profiles="$PROFILE_DIR" \
  -wan="$G_WAN" -ifb="$IFB" \
  -attestation="$TMP/attestation.json" \
  -token-file="$TMP/token" -audit="$TMP/audit.jsonl" \
  -tc-state="$TMP/tc-state.json" \
  -tls-ca="$TMP/ca.pem" \
  -tls-cert="$TMP/cert.pem" -tls-key="$TMP/key.pem" >"$TMP/gateway.log" 2>&1 &
PID=$!

for _ in $(seq 1 50); do
  if ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS https://192.168.77.1:19444/healthz >/dev/null 2>&1; then break; fi
  sleep 0.1
done
ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS https://192.168.77.1:19444/healthz >/dev/null

ping_avg() {
  ip netns exec "$CLIENT" ping -q -c 5 -W 1 192.168.78.2 | awk -F'/' '/^rtt|^round-trip/ {print $5}'
}

BASELINE="$(ping_avg)"
python3 - "$BASELINE" <<'PY'
import sys
value=float(sys.argv[1])
assert value < 20, f"baseline RTT too high: {value}ms"
PY

AUTH="Authorization: Bearer $TOKEN"
START="$(ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"run_id":"namespace-loss","profile_ref":"ip_loss_latency@1.0.0"}' \
  https://192.168.77.1:19444/v1/experiments)"
EXPERIMENT_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])' <<<"$START")"
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS -H "$AUTH" "https://192.168.77.1:19444/v1/experiments/$EXPERIMENT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
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
ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS -X DELETE -H "$AUTH" "https://192.168.77.1:19444/v1/experiments/$EXPERIMENT_ID" >/dev/null
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS -H "$AUTH" "https://192.168.77.1:19444/v1/experiments/$EXPERIMENT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
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

OUTAGE="$(ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"run_id":"namespace-outage","profile_ref":"ip_outage_recovery@1.0.0"}' \
  https://192.168.77.1:19444/v1/experiments)"
OUTAGE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])' <<<"$OUTAGE")"
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS -H "$AUTH" "https://192.168.77.1:19444/v1/experiments/$OUTAGE_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
  [ "$PHASE" = active ] && break
  sleep 0.1
done
[ "$PHASE" = active ]
if ip netns exec "$CLIENT" ping -q -c 1 -W 1 192.168.78.2 >/dev/null; then
  echo "100% outage did not block traffic" >&2
  exit 1
fi
for _ in $(seq 1 50); do
  PHASE="$(ip netns exec "$GATEWAY" curl --cacert "$TMP/ca.pem" -fsS -H "$AUTH" "https://192.168.77.1:19444/v1/experiments/$OUTAGE_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["phase"])')"
  [ "$PHASE" = completed ] && break
  sleep 0.1
done
[ "$PHASE" = completed ]
ip netns exec "$CLIENT" ping -q -c 1 -W 1 192.168.78.2 >/dev/null

LINES="$(wc -l <"$TMP/audit.jsonl")"
[ "$LINES" -ge 10 ]
[ ! -e "$TMP/tc-state.json" ]
echo "namespace_gateway_test=PASS baseline_ms=$BASELINE impaired_ms=$IMPAIRED restored_ms=$POST audit_events=$LINES prerequisite_failure_no_mutation=yes host_qdisc_unchanged=yes"
