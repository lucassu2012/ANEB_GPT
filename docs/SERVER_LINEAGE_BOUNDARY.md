# ANEB Server Lineage Boundary (`0.8.x` Production vs. `0.1.0` Prototype)

Last updated: 2026-11-20 (CST)

## 1. Two Distinct Server Lineages (Why They Are Tracked Separately)

ANEB currently maintains two explicitly bounded Go server lineages inside `ANEB_GPT`, corresponding to different operational roles and Git histories:

| Lineage | Active Branch / Merged Base | Git Root | Deployment Role | Key Endpoints & Guarantees |
|---|---|---|---|---|
| **Production `miw2` `0.8.x`** | `release/miw2-server-0.8` (merged via PR #113 from `codex/miw2-server-0.8.4` @ `96104a8a`) | `75533687` | Live public node **`miw2` (`https://120.77.174.207:8443`)**, serving real Android client tests | `aneb-server/0.8.4`; `-execution-profiles` (6/6 profiles), `-udp-echo-addr :8443`, `-h3`, `/api/v1/realtime-sim`, `/api/v1/token-sim`, `/api/v1/impairments`, `/synthetic/*`, `/api/v1/artifact_stream`, high-fidelity `tokengen`, TCP `TLSNextProto` HTTP/1.1 pinning, `ANEB_REQUEST_AUDIT`, disabled-by-default `HTTP 503` `/api/v1/results` |
| **Prototype / Local `0.1.0`** | `product/prototype-0.1` (merged via PR #112 from `codex/business-video-baseline-1024` @ `2738bf25`) | `73e4e999` | Local Windows Prototype 0.1 package (`ANEB-Prototype-0.1`) & offline/CI Android verification | `aneb-server/0.1.0`; `/api/v1/prototype/*` local campaign endpoints, `/api/v1/realtime-sim`, `/api/v1/artifact_stream`, high-fidelity `tokengen`, TCP `TLSNextProto` HTTP/1.1 pinning, local JSONL `/api/v1/results` |

## 2. Operational Rules

1. **Never deploy `product/prototype-0.1/server` onto `miw2`**: it lacks `-execution-profiles`, `-udp-echo-addr`, `/api/v1/token-sim`, `/api/v1/impairments`, `/synthetic/*`, and the production `503` result-ingress hardening expected by `/etc/systemd/system/aneb-server.service`.
2. **Never deploy onto retired `120.79.148.0`**: retired on `2026-09-25` (`/opt/aneb/README-已迁移.md`); all live traffic targets `miw2` (`120.77.174.207:8443`), which holds a Let's Encrypt short-lived IP-SAN certificate renewed automatically by `/etc/cron.d/miw2-acme` + `/opt/miw2/aneb-cert-deploy.sh`.
3. **Production server changes flow strictly through `release/miw2-server-0.8`**: cut additive branches from `release/miw2-server-0.8`, run full `go test -count=1 ./...`, take a timestamped backup under `/opt/aneb/backups/`, verify external HTTPS (`ALPN: http/1.1`, endpoints, `503` results guard), and merge via PR.
4. **Shared endpoint contract parity**: `/api/v1/stream`, `/api/v1/artifact_stream`, `/api/v1/realtime-sim`, `/api/v1/echo`, `/api/v1/upload`, `/api/v1/download`, and TCP `TLSNextProto` HTTP/1.1 pinning are kept contract-compatible across both lineages so the Android client (`com.aneb.probe`) works seamlessly against both local Prototype servers and live `miw2`.
