# Node-E02 Evaluation and Deployment Plan

> **STATUS: PLANNING ONLY — NOT PROVISIONED**
>
> This document is an engineering evaluation and pre-provisioning plan for a second public ANEB probe server (`Node-E02`). **No VM, public IP, DNS record, ACME certificate, or Android catalog entry has been provisioned.** Per `ProbeNodeCatalog.kt` (`只登记已经真实部署并有证据的 Probe 节点；禁止把设计稿示例节点带进产品`), `Node-E02` MUST NOT be registered in `ProbeNodeCatalog` or set as a client preset until it is provisioned, hardened, and live-verified against all `aneb-server/0.8.4+` contracts.

Last updated: 2026-11-21 (CST)

---

## 1. Current Production Baseline & Lineage Boundary

### 1.1 Active Production Node `miw2` (`Node-E01` Logical Slot)

| Attribute | Verified Baseline (`miw2`) |
|---|---|
| Public Endpoint | `https://120.77.174.207:8443` |
| Cloud / Region | Alibaba Cloud (`cn-shenzhen`), ECS `iZwz94cnv67bzsj770zn9fZ`, BGP IPv4 `120.77.174.207` |
| Server Binary | `aneb-server/0.8.4` (`linux/amd64`), built from `release/miw2-server-0.8` (PR #113, `96104a8a`, git root `75533687`) |
| Transport Listeners | TCP `:8443` (`TLSv1.3`, `TLSNextProto` non-nil empty map pinning `ALPN: http/1.1`) + UDP `:8443` (`-h3` + `-udp-echo-addr :8443`, advertising `Alt-Svc: h3=":8443"; ma=86400`) |
| Kernel / TCP Tuning | `tcp_slow_start_after_idle = "0"`, `congestion_control = "cubic"` |
| Capabilities & Profiles | `-execution-profiles /opt/aneb/execution-profiles` (6/6 validated profiles, 6 primitives), 4 legacy profiles (`/api/v1/profiles`) |
| Result Ingress Guard | `/api/v1/results` strictly returns `HTTP 503` (`result upload unavailable`) when `-result-upload-token-file` is unset |
| TLS Certificate | Let's Encrypt short-lived IP-SAN (`SAN: IP:120.77.174.207`, empty `Subject`, ~160h / 6.67d lifetime) renewed via `/etc/cron.d/miw2-acme` (`flock` + `acme.sh --cron` HTTP-01 on TCP `:80`) + `/opt/miw2/aneb-cert-deploy.sh` |
| Automated Monitoring | `tools/miw2-monitor/check_miw2.py` + `.github/workflows/miw2-monitor.yml` (daily `<48h` cert threshold + 6-invariant live probe) |

### 1.2 Retired Node & Lineage Redlines

1. **Retired `120.79.148.0` (Legacy E-01)**: Officially retired on `2026-09-25` (`/opt/aneb/README-已迁移.md`); `aneb-server.service` is `disabled` / `inactive (dead)` and `120-79-148-0.sslip.io` expired on `2026-10-11`. Never restart, re-point traffic to, or provision `Node-E02` on `120.79.148.0`.
2. **Dual-Lineage Boundary (`docs/SERVER_LINEAGE_BOUNDARY.md`)**:
   - **NEVER deploy `product/prototype-0.1/server` (`0.1.0`, git root `73e4e999`) onto `miw2` or `Node-E02`**. The prototype server lacks `-execution-profiles`, `-udp-echo-addr`, `/api/v1/token-sim`, `/api/v1/impairments`, `/synthetic/*`, and `/api/v1/results` `503` hardening.
   - All `Node-E02` binaries MUST be built from `release/miw2-server-0.8` (or a PR-reviewed additive commit cut from `release/miw2-server-0.8`) and remain contract-compatible across the 7 shared endpoints guarded by `server/cross_lineage_parity_test.go`.

---

## 2. Why `Node-E02` Is Needed (SPOF & Measurement Diversity)

1. **Single-Region / Single-Cloud SPOF**: `miw2` is the sole live public node, hosted on Aliyun `cn-shenzhen` with a ~6.67-day Let's Encrypt IP-SAN certificate. Any Aliyun South China routing disruption, ECS maintenance window, or TCP `:80` ACME renewal interruption causes a 100% public-probe outage.
2. **Backbone & Carrier Routing Separation**: With only Shenzhen (`miw2`), ANEB cannot separate last-mile wireless variance (Wi-Fi vs 5G NR) from regional backbone / cross-ISP transit effects when devices test from other provinces.
3. **Controlled Canary Target**: Having a second production-lineage node allows staged server validation (`0.8.x`) on one node before rolling out to the other.

---

## 3. Selection Criteria & Target Specifications

### 3.1 Diversity Matrix

| Dimension | `miw2` (Current) | `Node-E02` Target Requirement | Rationale |
|---|---|---|---|
| **Cloud Provider** | Alibaba Cloud (Aliyun) | Non-Aliyun mainland cloud (e.g., Tencent Cloud, Huawei Cloud, or Volcano Engine) | Eliminates single-vendor control-plane / BGP outage correlation |
| **Region** | South China (`cn-shenzhen`) | East China (Shanghai / Hangzhou) or North China (Beijing / Tianjin) | Provides ~25–45 ms inter-region RTT separation vs South China |
| **IP & BGP** | Single static public IPv4 (`120.77.174.207`) | Dedicated static BGP multi-line IPv4 (China Telecom / Unicom / Mobile balanced peering) | Avoids single-carrier asymmetric peering skew |
| **Certificate Model** | Let's Encrypt short-lived IP-SAN | Let's Encrypt short-lived IP-SAN (`SAN: IP:<E02_IP>`) | Matches Android `system` trust anchors with zero `@raw/` CA pin updates |

### 3.2 Minimum VM & Network Specifications

- **OS & Arch**: Ubuntu 24.04 LTS (or Debian 12) `linux/amd64`.
- **Compute & Memory**: 2 vCPU, 2–4 GiB RAM, >= 20 GiB SSD (comfortably accommodates `systemd` `MemoryMax=384M`, `CPUQuota=120%`, `TasksMax=256`, and `/opt/aneb/data` audit logs).
- **Network Bandwidth**: >= 100 Mbps peak or burstable public egress/ingress with unmetered or >= 1 TB/month traffic pack (sufficient for `download`/`upload` bursts and `LoadedResponsiveness` campaigns without shaping artifacts from low-bandwidth VM throttling).
- **Firewall / Security Group Inbound Rules**:
  - `80/tcp` (open or dedicated ACME webroot for Let's Encrypt HTTP-01 IP-SAN challenge)
  - `8443/tcp` (ANEB HTTPS + `HTTP/1.1` ALPN + WSS `/api/v1/realtime-sim`)
  - `8443/udp` (ANEB HTTP/3 + UDP echo `-udp-echo-addr :8443`)
  - `22/tcp` (restricted to operator/bastion source IPs only, key-only SSH)

---

## 4. Server Runtime, Hardening, and ACME Parity Plan

1. **Build Artifact**:
   - Cross-compile strictly from `release/miw2-server-0.8`:
     ```bash
     GOOS=linux GOARCH=amd64 CGO_ENABLED=0 GOFIPS140=off go build -trimpath -o aneb-server ./
     ```
   - Deploy binary to `/opt/aneb/bin/aneb-server` with SHA-256 receipt and rollback copy under `/opt/aneb/backups/`.
2. **Systemd Sandboxing (`server/aneb-server.service` Parity)**:
   - Run under dedicated unprivileged `User=aneb`, `Group=aneb`.
   - Enforce `NoNewPrivileges=true`, `ProtectSystem=strict`, `ProtectHome=true`, `PrivateTmp=true`, `ReadWritePaths=/opt/aneb/data`, `MemoryMax=384M`, `CPUQuota=120%`, `TasksMax=256`.
   - Pass `-execution-profiles /opt/aneb/execution-profiles`, `-h3`, `-udp-echo-addr :8443`, and enable `ANEB_REQUEST_AUDIT`; omit `-result-upload-token-file` so `/api/v1/results` stays strictly `HTTP 503`.
3. **Kernel / Sysctl Parity**:
   - Configure `net.ipv4.tcp_slow_start_after_idle = 0` so idle-resume burst behavior matches `miw2` and is observable in `/api/v1/serverinfo`.
   - Default `congestion_control = cubic` for apples-to-apples comparison against `miw2` (any future `bbr` experiment must be explicitly logged via `/api/v1/serverinfo`).
4. **ACME HTTP-01 IP-SAN Automation & Monitoring**:
   - Configure `/etc/cron.d/node-e02-acme` with `flock` + `acme.sh --cron` and reload hook restarting/reloading `aneb-server` cleanly.
   - Extend `tools/miw2-monitor/check_miw2.py` (already parameterized via `--base-url`) and `.github/workflows/miw2-monitor.yml` with a matrix over `[miw2, Node-E02]` once `Node-E02` is live, enforcing the exact same `<48h` cert expiry threshold and 6-invariant checks.

---

## 5. Android Client Multi-Node Selection & Comparison Plan

Code inspection of `app/probe/` identifies the exact symbols involved and one pre-existing reachability gap that should be resolved when `Node-E02` is added:

| Component / File | Current State | Planned Multi-Node Change (Post-Provisioning Only) |
|---|---|---|
| `app/probe/.../ui/ProbeNodeCatalog.kt` | Currently defines only `MIW2_IP = "120.77.174.207"` and `val e01 = Node(id = "E-01", ...)` (no `deployedNodes` list yet); `nodeForUrl(url)` checks `host == MIW2_IP \|\| deriveE01Pair(url) != null`. | Introduce `val deployedNodes: List<Node>` and register `e02` **only after** `Node-E02` provisioning and live verification receipt exist; update `nodeForUrl(url)` to iterate `deployedNodes`. |
| `app/probe/.../net/ReachabilityProbe.kt` & `MainActivity.kt` (`574-600`, `1096-1112`) | **Gap**: `MainActivity.refreshNodeReachability()` is gated on `ReachabilityProbe.deriveE01Pair(serverUrl)`, which ONLY matches legacy `120.79.148.0` / `120-79-148-0.sslip.io` (`ReachabilityProbe.kt:95-119`), so `miw2` returns `null` and shows `"双通道检测仅适用于 E-01"`. | Add `probeNodes(nodes: List<ProbeNodeCatalog.Node>)` calling `probeOne(node.defaultUrl)` for deployed IP-SAN nodes (`miw2` and `Node-E02`) to surface live TCP/TLS RTT and health per node while retaining `deriveE01Pair` only for legacy `120.79.148.0` compatibility if needed. |
| `app/probe/.../ui/ServerScreen.kt` (`35-195`) | Hard-coded single `onSelectE01: () -> Unit` callback plus custom-node fallback row. | Generalize to `nodes: List<ProbeNodeCatalog.Node>` + `onSelectNode: (ProbeNodeCatalog.Node) -> Unit` with per-node RTT/status badges and optional "Select lowest-RTT healthy node" action. |
| `app/probe/.../ui/SettingsScreen.kt` (`131-165`, `388-395`, `458-466`) | `SettingsRootScreen` displays `"测试节点"` via `ProbeNodeCatalog.labelForUrl(serverUrl)`; legacy `showBack=true` branch (`142-145`) still lists retired `"https://120.79.148.0:8443"` preset. | Remove/retire the dead `120.79.148.0` preset and surface deployed nodes via `ProbeNodeCatalog`. |
| `app/probe/.../res/xml/network_security_config.xml` | Global `base-config` (`cleartextTrafficPermitted="false"`) trusts `system` CAs; `@raw/aneb_ip_ca` is pinned only for `120.79.148.0`. | **Zero change required**: Let's Encrypt IP-SAN certificates chain to `system` root CAs (ISRG Root X1/X2). |
| `app/probe/.../data/Entities.kt` (`19, 87, 576-597`) & `ui/PairedComparisonFormat.kt` (`355, 369, 384`) | `TestRun` (`Entities.kt:19`) and `BasicSpeedResultEntity` (`Entities.kt:87`) already store `serverBase` in Room v15 and render via `ProbeNodeCatalog.labelForUrl(serverBase)` (`PairedComparisonFormat.kt:355, 369`), whereas `VoiceResultEntity` (`Entities.kt:576-597`) currently lacks `serverBase` and `PairedComparisonFormat.kt:384` hardcodes `nodeLabel = "未记录节点"`. | **Zero Room v15 schema migration needed** for Token and Basic Speed cross-node paired comparison (`Node-E01` vs `Node-E02`) once `ProbeNodeCatalog.labelForUrl()` recognizes `Node-E02`; cross-node Voice node labeling would require an additive Room migration if needed later. |

---

## 6. Cost, Risk, Cutover & Rollback Checklist

### 6.1 Indicative Resource & Risk Matrix

- **Cost Gate (PO Approval Required)**: Mainland 2 vCPU / 2–4 GiB RAM cloud VM with >= 100 Mbps burstable bandwidth or traffic package (indicative range ~100–350 RMB/month depending on vendor promotions and egress billing). Requires explicit PO budget sign-off before provisioning.
- **Key Risks & Mitigations**:
  1. **Port 80 blocked / ICP filing / cloud security policy**: Verify during VM trial that Let's Encrypt HTTP-01 ACME (`/.well-known/acme-challenge/`) on TCP `:80` for bare IP is permitted by the cloud provider before committing to annual billing.
  2. **UDP `:8443` QoS / carrier throttling**: Run `tools/miw2-monitor/check_miw2.py` + `LoadedResponsiveness` + Cronet HTTP/3 verification from P40 Pro over both Wi-Fi and 5G NR before declaring `Node-E02` production-ready.
  3. **Client regression**: Keep `ProbeSettings.DEFAULT_SERVER_URL = "https://120.77.174.207:8443"` (`miw2`) as the default fallback until `Node-E02` accumulates >= 7 days of green daily monitor runs.

### 6.2 Cutover & Rollback Checklist

1. [ ] PO approves vendor, region, and budget; provision VM with TCP `80`, TCP `8443`, UDP `8443`.
2. [ ] Build `aneb-server` from `release/miw2-server-0.8`, deploy systemd service (`User=aneb`, `/api/v1/results` `503`), and verify `tcp_slow_start_after_idle=0`.
3. [ ] Issue Let's Encrypt IP-SAN cert via `acme.sh` HTTP-01 and install `/etc/cron.d/node-e02-acme` (`flock` + reload hook).
4. [ ] Run `python -B tools/miw2-monitor/check_miw2.py --base-url https://<E02_IP>:8443 --min-cert-hours 48 --format json` -> must pass 6/6.
5. [ ] Add `Node-E02` to `.github/workflows/miw2-monitor.yml` and verify live P40 Pro canary run (Wi-Fi + 5G NR) per `AGENTS.md`.
6. [ ] Update `ProbeNodeCatalog`, `ReachabilityProbe`, `ServerScreen`, and `SettingsScreen` in a dedicated Android PR after live receipt is archived.
7. **Rollback**: Remove `Node-E02` from `ProbeNodeCatalog` / monitor matrix (or revert `DEFAULT_SERVER_URL` to `miw2`), stop `aneb-server` on `Node-E02`, and preserve `/opt/aneb/data` audit logs for post-mortem.
