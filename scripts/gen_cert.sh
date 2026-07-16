#!/usr/bin/env bash
# gen_cert.sh — 一条龙生成 aneb-server 用的 10 年自签 TLS 证书（E-01 部署用）。
#
# 用法:
#   sudo ./gen_cert.sh [输出目录] [CN] [额外SAN...]
# 缺省:
#   输出目录 = /opt/aneb/tls   CN = aneb-server
# 例（给公网 IP 签 SAN，客户端按 IP 校验时必须带上）:
#   sudo ./gen_cert.sh /opt/aneb/tls aneb-server IP:203.0.113.7 DNS:aneb.example.com
#
# 产物: $DIR/cert.pem + $DIR/key.pem（key 权限 600）。
# 服务端: aneb-server -tls-cert /opt/aneb/tls/cert.pem -tls-key /opt/aneb/tls/key.pem [-h3]
# 注意: h3（QUIC）是 TLS-only —— -h3 无证书会直接 fail-closed 拒绝启动。
set -euo pipefail

DIR="${1:-/opt/aneb/tls}"
CN="${2:-aneb-server}"
shift $(( $# > 2 ? 2 : $# )) || true

# SAN 列表：固定含 CN 与本机环回，可追加任意 IP:x.x.x.x / DNS:name。
SAN="DNS:${CN},IP:127.0.0.1"
for extra in "$@"; do
  SAN="${SAN},${extra}"
done

command -v openssl >/dev/null || { echo "ERROR: openssl not found" >&2; exit 1; }
mkdir -p "$DIR"

# ECDSA P-256 + 3650 天（10 年）自签；-nodes 免口令（systemd 无人值守加载）。
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
  -keyout "$DIR/key.pem" -out "$DIR/cert.pem" \
  -days 3650 -nodes \
  -subj "/CN=${CN}" \
  -addext "subjectAltName=${SAN}" \
  -addext "extendedKeyUsage=serverAuth"

chmod 600 "$DIR/key.pem"
chmod 644 "$DIR/cert.pem"

echo "OK: cert=$DIR/cert.pem key=$DIR/key.pem (10y self-signed, SAN=${SAN})"
openssl x509 -in "$DIR/cert.pem" -noout -subject -dates -ext subjectAltName
