# ANEB AI Network Experience Baseline Report v0.2

- **Issue**: [#19](https://github.com/lucassu2012/ANEB_GPT/issues/19)
- **Date**: 2026-11-21 (CST)
- **Repo Head Baseline (M4)**: `6ff68432bf38bf6614a524171c6c281f1f1ab2cc` (`codex/business-video-baseline-1024` / `main`)
- **Target Android Client**: `com.aneb.probe` `0.2.0` (`versionCode = 20`)
- **Production Public Probe Node (`miw2`)**: `aneb-server/0.8.4` (`https://120.77.174.207:8443`, `release/miw2-server-0.8` @ `96104a8a`)
- **Reference Mobile Device**: Huawei P40 Pro (`ELS-AN00`, serial `8MY0221126002537`, Android 12 / API 31)
- **Immutable Evidence Archive**: `E:\G Project\ANEB\Archives\ExternalRoots\ANEB-Research\Business-Profile-Baseline-20261024\`

---

## 1. Executive Summary

This report synthesizes **100% primary-verified evidence** collected across ANEB Stages 0–3 (`befa87b8` -> `bb134903` -> `6ce3e883` -> `6ff68432`) and P2 field benchmarking. Every table and metric below was verified directly against immutable raw JSONs, CSVs, packet/unicast counters, decoded `MediaProjection` frames, and signed release receipts—reconciling all summary-document discrepancies noted in Section 9.

### Headline Findings

1. **Douyin `410101` (`versionName = 41.1.0`) Short-Video Inflection & Decoupling (10 Cards / 20 Phases)**:
   - Under `phone-forward-condition` downlink rate sweeps, the 3-second Post-Tap Response (`PTR`) startup cliff lies strictly in **`(1.000, 1.500] Mbps`**, and the steady-state smooth playback threshold lies in **`(1.500, 2.000] Mbps`**: `1.000 Mbps` fails `<= 3.0s` in both `R->B` (`[10.105, 10.139]s NOT_MET`, `9.11s` cover freeze) and `B->R` (`[7.573, 7.608]s NOT_MET`, `6,431.6 ms` cover freeze); `1.500 Mbps` (`r3` true video `@Daisy`) passes startup (`[1.987, 2.022]s MET`, `1,026.8 ms` hold) but exhausts buffer and stalls for **`7,790.2 ms`** mid-clip (`8,817.0 ms` total static); `2.000 Mbps` and `4.000 Mbps` deliver `0.00 ms` post-settle static pauses.
   - Multi-dimensional impairment at adequate throughput (`2.0M + 150ms + 2%` -> `[1.093, 1.143]s MET`; M2.1 10th card `4.0M + 200ms + 5% r3` -> `[2.072, 2.106]s MET`, `0` mid-clip freezes over `34.23s`) proves bandwidth starvation is **decoupled** from `150–200 ms` RTT + `2–5%` loss once downlink capacity exceeds `2.0 Mbps`.
2. **Doubao `com.larus.nova` & Tail-Drop AQM Starvation Inflection**:
   - Pre-AQM `r5`/`r6` runs exposed Cronet/QUIC small-packet bursts (~`161 B/pkt`) overflowing the bounded `2,048`-packet shaper queue (`USER_QUEUE_OVERFLOW`). Adding bounded **`tail_drop` AQM (`131,072 B`)** restored exact packet conservation (`UnconfirmedUserPackets = 0`) and isolated the AI chat starvation cliff: `r7` (`1.0M + 200ms + 3%`) completed in `[6.28, 7.22]s` (vs R `[5.58, 6.50]s`) at **`2.47×`** packet / **`2.23×`** AP Tx byte amplification, whereas both `256 kbps + 350 ms + 5%` (`v15.8.0`/`15080040`, `MediaProjection` 30 fps, `1,935` tail drops) and `r8` (`128 kbps + 400 ms + 5%`, `3,637` tail drops) starved streaming (`TTFR > 26.34s` / `> 26.53s`, `0` output characters). KimiChat (`kimi-controlled-rb-20261120-r3`) remained gated at pre-send preparation (`OBSERVATION_UNKNOWN`, `DEEPSEEK_CURRENT_CHILD_INCOMPLETE`, `send_calls = 0`).
3. **Field Paired `W1` (Hotel 5 GHz Wi-Fi) vs `C1` (5G NR) Multi-Layer Divergence & `TCP_NODELAY` Fix**:
   - Diagnosing and fixing missing `TCP_NODELAY` in OkHttp (`TcpNoDelaySocketFactory`, `1165320f`) reduced Profile 4 uplink frame jitter `M3` on `W1` by **`16.64×`** (`55.555 -> 3.339 ms`, `id=2->5`) / **`13.49×`** (`45.059 -> 3.339 ms`, `id=3->5`)—and **`23.15×`** in Pilot RC v2 (`2.4 ms` vs `id=2`)—and by **`3.45×`** on `C1` (`51.934 -> 15.046 ms`).
   - Even with `TCP_NODELAY=true`, `W1` (`44.75/44.82 Mbps`, Token `AQS = 92.59`, base RTT `47.64 ms`) exhibited a **`3.23×` voice inversion** against `C1` (`186.30/87.13 Mbps`, Token `AQS = 84.54`, base RTT `89.55 ms`): `C1` achieved `M1' = 218.93 ms` (`M4 = 137.99 ms`) vs `W1` `M1' = 706.41 ms` (`M4 = 626.03 ms`, vetoed). In Stage 2 RC `PairedComparisonCard` (`Wi-Fi 93.5 分` vs `5G NR 88.3 分`, `-5.1 分 / -5.5%`), Token AQS favored Wi-Fi while Voice favored 5G NR—demonstrating that Ping and bulk speed cannot substitute for workload-specific AI KPIs.
4. **Profile 4 Synthetic (`VoiceRunner.runSim`) vs Real Voice App (`com.larus.nova`) Rank Validity**:
   - Across same-topology `R`, `B1` (`256k + 120ms + 2%`), and `B2` (asymmetric `2.0M DL / 128k UL + 200ms + 5% UL`), synthetic `AQS_v02` (`78.76 -> 54.00 -> 47.20`) tracked real voice call completion (`3/3 -> 2/3 -> 0/3` turns, `connect_ready_ms` `374.8 -> 9685.1 -> >62000`) with **`Spearman rho = +1.000`**, **`Kendall tau-b = +1.000`**, and **`13/13`** weight-sensitivity order invariance (`R > B1 > B2`), validating the `M1' > 400 ms` veto (`54.0` cap) while bounding statistical significance (`N = 3`, minimum two-sided permutation `p = 0.333`).
5. **Stage 0–3 Production Readiness**:
   - `LoadedResponsiveness v2` resolved `<100 Mbps` `partial` failures (`status = "completed"`, `40.43 -> 196.36 / 208.98 ms` in RC v2); bidirectional shaper `v4` (`r4` `+112.682 ms` RTT delta, `UnconfirmedUserPackets = 0`) and `new_card.py` standardized controlled R/B experiments; 4 donor modules (`KeepScreenOnPolicy`, `ThermalSummary` + `EnvMonitors` [`replay = 8`] + `RttDominanceGuard` [`268.52`], `NegotiatedProtocolLog` [`138/138 http/1.1`], `ResultAqsBreakdown` + `AiScenarioAdvisor`) and `aneb-profile-v2` (`20/20`, `score = 89.4`) shipped with zero Room v15/v23 conflict; and `miw2` `0.8.4` daily monitoring (`check_miw2.py`), 7-endpoint cross-lineage parity (`server/cross_lineage_parity_test.go`), `Node-E02` planning, and cold-backup/disk governance (`E: > 40 GiB`) hardened production operations.

---

## 2. Experimental Methodology, Topology & Integrity Invariants

### 2.1 Controlled Same-Topology Shaper (`WinRT Mobile Hotspot` + `phone-forward-condition v4`)

All controlled R/B experiments (`Douyin`, `Doubao`, `KimiChat`, `bidir-r4`, and `Profile 4 voice correlation`) executed on a deterministic single-phone topology:

```
Huawei P40 Pro (192.168.137.113, wlan0)
  --[5 GHz Wi-Fi WinRT Mobile Hotspot, ifIndex=16]-->
Windows Host Shaper (WinDivert-2.2.2-A/x64, NETWORK_FORWARD on Ethernet ifIndex=12, NAT 10.10.8.9)
  --[Public Internet]-->
Target Service (Douyin / Doubao / KimiChat CDN & Edge, or miw2 120.77.174.207:8443)
```

Key shaper and verification invariants implemented in `tools/phone-forward-condition/`:
- **Bounded `tail_drop` AQM (Stage 0 `beead81`, `bcf4efd`)**: Replaced unbounded/hard-abort packet buffering with a byte-bounded queue (`queue_overflow_policy = "tail_drop"`, `queue_limit_bytes = 131072` or `262144`) so bursty QUIC/Cronet and WebRTC flows experience realistic tail drops rather than `USER_QUEUE_OVERFLOW` aborts.
- **Bidirectional Independent Shaping (`v4`, Stage 1 `688f141..9d3609f`)**: Independent `downlink_*` (ingress to phone) and `uplink_*` (egress from post-NAT `uplink_nat_addresses`, e.g., `10.10.8.9`, to `phone_ip`) token-bucket pacers, delay queues, and deterministic seeded loss generators (`impairment_seed`, `uplink_impairment_seed`).
- **Exact Packet/Byte Conservation & `iphlpapi.dll` Route Proof**: Every phase enforces `ReceivedPackets == InjectedPackets + DroppedPackets + QueueDroppedPackets + CleanupInjectedPackets` with **`UnconfirmedUserPackets == 0`**, while `unicast-delta.json` records host `AP Tx`/`AP Rx`/`Eth Rx`/`Eth Tx` byte deltas and `ifIndex = 12` unicast/route tables to prove traffic transited the shaped link without off-path leakage or significant `WLAN` background noise.

### 2.2 Immutable Card Automation, Offline Kotlin Verifiers & `AGENTS.md` Clean-State Discipline

- **Parameterized Card Generator (`tools/controlled-rb-card/new_card.py`)**: Emits self-contained immutable card directories pinning `common.ps1`, `execute-once.ps1`, `run-once.ps1`, `verify-cleanup-readonly.ps1`, `UI_BINDING`, `CANDIDATE_ARTIFACTS`, `request.json`, and `phone-condition.exe` by SHA-256. Consumed card directories are strictly read-only.
- **Dual-Layer Review (`review_video_rb.py` + Kotlin Offline Verifiers)**: Screen recordings (`screencap` burst or `MediaProjection` 30 fps `video.mp4` verified by `MediaExtractor` `FINAL_MEDIA.json`) are analyzed frame-by-frame alongside `ResearchRecordStore`, `ResearchAnalysisStore`, and `ResearchVideoOfflineReportExporter` (`reader-exported/`).
- **`AGENTS.md` Live Clean-State Protocol**: `SHARED_TEST_STATUS.md` was retired on `2026-07-19`. Every live P40 Pro test verifies pre-run and post-run clean state directly on the device (`UniHomeLauncher` focused, zero residual `aneb`/`aweme`/`larus`/VPN/capture processes, zero `tun0`/`ppp`/`wg` tunnels, `stay_on_while_plugged_in == 0`, `Media Projection: null`, `WinDivert` service unloaded `1060`, and WinRT Mobile Hotspot `Off`).

### 2.3 Claim-Scope Boundaries

- **Synthetic Probe Scope**: `LoadedResponsiveness v2`, `Token Experience (S1–S3)`, `aneb-profile-v2`, and `VoiceRunner.runSim()` measure `application_end_to_end_to_probe_node` performance between `com.aneb.probe` and a specific ANEB node (`miw2` or local Prototype server). They do **not** claim carrier-wide SLAs, radio-layer PHY/MAC KPIs, or subjective acoustic MOS.
- **Real-App Scope**: Douyin, Doubao, and KimiChat measurements capture **controlled UI/visual and shaper-level observables** (Post-Tap Response intervals, cover/mid-clip static pauses, TTFR, character completion, Accessibility status transitions) under pinned app versions and host NAT shaping—not internal server telemetry.

---

## 3. Controlled Business Baseline I — Short-Video Streaming (Douyin `410101`)

### 3.1 Scope & Package Reconciliation

- **App Identity**: Douyin (`com.ss.android.ugc.aweme`), `versionName = 41.1.0`, `versionCode = 410101`, `minSdk = 23`, `targetSdk = 34` (verified in `douyin-controlled-rb-1500kbps-410101-20261120-r3\runtime\adb-012-aweme-package.json` and `4mbps-200ms-5pct-...-r3\CARD_SPEC.json`; reconciles `INFLECTION_REPORT.md:4`'s typo `v41.0.1`).
- **Complete 10-Card / 20-Phase Coverage**: Prior `INFLECTION_REPORT.md` covered 9 cards (18 phases). This baseline integrates all **10 cards / 20 phases**, incorporating the M2.1 10th decoupling card `douyin-controlled-rb-4mbps-200ms-5pct-410101-20261120-r3`.
- **Stale `result.json` Field Provenance**: Raw `device-original\result.json` in `1.0M B->R (r4)` and `1.5M R->B (r2)` recorded stale `downlinkIpBytesPerSecond = 250000`, whereas the SHA-256-pinned shaper `request.json` enforced `125000 B/s` (`1.000 Mbps`) and `187500 B/s` (`1.500 Mbps`) respectively. Both `1.5M r3` (`187500`) and `4.0M+200ms+5% r3` (`500000`) match identically across `request.json`, `result.json`, and `helper-exit.json`.

### 3.2 Ten-Card Matrix: Visual Startup (`PTR <= 3.0s`), Playback & Cleanup

| Short Label | Exact Card Directory | Order | `OUTER_RESULT` (`pass` / `exit` / `finished_utc`) | `CLEANUP` (12/12) | Phase R `ptr_wait_s` (Eval / Kind) | Phase B `ptr_wait_s` (Eval / Kind) | Phase B Visual Content & Post-Settle Freeze (`review-B.json` / `scan-summary.json`) | Kotlin `reader-exported` |
|---|---|---|---|---|---|---|---|---|
| `1.0M R->B (r5)` | `douyin-controlled-rb-410101-20261119-r5` | `R->B` | `true` / `0` / `2026-11-19T14:55:20.7353159Z` | `true` | `[0.780, 1.026]s` (`MET`, `UPPER_BOUND`) | `[10.105, 10.139]s` (`NOT_MET`, `EXACT`) | 16:9 video `@蜜桃是唯一`; **9.11s** cover freeze (`n0198..n0646`) + 2 mid-clip freezes | 2 rec, 2 ana, 4 zip |
| `1.0M B->R (r2)` | `douyin-controlled-br-1mbps-410101-20261120-r2` | `B->R` | `true` / `0` / `2026-11-20T01:00:40.9349201Z` | `true` | `[1.556, 1.593]s` (`MET`, `UPPER_BOUND`) | `NA` (`NOT_QUALIFIED`) | Non-video single photo `@Zoe 限时日常`; black screen `[5.019, 5.070]s` | 2 rec, 2 ana, 2 zip |
| `1.0M B->R (r3)` | `douyin-controlled-br-1mbps-410101-20261120-r3` | `B->R` | `true` / `0` / `2026-11-20T01:19:58.7909781Z` | `true` | `[0.909, 0.942]s` (`MET`, `UPPER_BOUND`) | `NA` (`NOT_QUALIFIED`) | Non-video 2-photo carousel `@前尘镜 图文`; slide flip `[2.088, 2.137]s` | 2 rec, 2 ana, 2 zip |
| `1.0M B->R (r4)` | `douyin-controlled-br-1mbps-410101-20261120-r4` | `B->R` | `true` / `0` / `2026-11-20T03:53:26.9158120Z` | `true` | `[0.945, 0.976]s` (`MET`, `UPPER_BOUND`) | `[7.573, 7.608]s` (`NOT_MET`, `EXACT`) | Portrait video `@少说一句`; **6,431.6 ms** cover freeze (`n0185..n0479`) | 2 rec, 2 ana, 2 zip |
| `1.5M R->B (r2)` | `douyin-controlled-rb-1500kbps-410101-20261120-r2` | `R->B` | `true` / `0` / `2026-11-20T03:48:35.8714981Z` | `true` | `[0.978, 1.010]s` (`MET`, `UPPER_BOUND`) | `NA` (`NOT_QUALIFIED`) | Non-video 2-slide carousel `@4AM任 图文`; `advisory` flagged (`max_active_60f=4`, `active_ratio=0.0287`) | 2 rec, 2 ana, 2 zip |
| `1.5M R->B (r3)` | `douyin-controlled-rb-1500kbps-410101-20261120-r3` | `R->B` | `true` / `0` / `2026-11-20T09:52:43.0479939Z` | `true` | `[1.506, 1.540]s` (`MET`, `UPPER_BOUND`) | **`[1.987, 2.022]s` (`MET`, `EXACT`)** | Portrait video `@Daisy` (`active_ratio=0.3607`, `max_active_60f=34`); **1,026.8 ms** hold + **7,790.2 ms** mid-clip freeze (`n0400..n0869`, total static **8,817.0 ms**), resumed `n0880` | 2 rec, 2 ana, 2 zip |
| `2.0M R->B` | `douyin-controlled-rb-2mbps-410101-20261120` | `R->B` | `true` / `0` / `2026-11-20T01:05:07.1136458Z` | `true` | `[1.590, 1.623]s` (`MET`, `UPPER_BOUND`) | `[1.671, 1.722]s` (`MET`, `UPPER_BOUND`) | Portrait video `@是姐姐`; **0.00 ms** post-settle static pauses | 2 rec, 2 ana, 2 zip |
| `2.0M+150ms+2%` | `douyin-controlled-rb-2mbps-150ms-2pct-410101-20261120` | `R->B` | `true` / `0` / `2026-11-20T03:58:31.5154462Z` | `true` | `[1.869, 1.919]s` (`MET`, `UPPER_BOUND`) | `[1.093, 1.143]s` (`MET`, `EXACT`) | Video `@luna`; 97 ms startup hold + 1 mid-clip `363.6 ms` pause (`n0787..n0809`) | 2 rec, 2 ana, 2 zip |
| `4.0M R->B` | `douyin-controlled-rb-4mbps-410101-20261120` | `R->B` | `true` / `0` / `2026-11-20T01:09:31.1350634Z` | `true` | `[0.961, 0.993]s` (`MET`, `UPPER_BOUND`) | `[0.943, 0.975]s` (`MET`, `UPPER_BOUND`) | 16:9 video `@次鱼祖`; **0.00 ms** post-settle static pauses | 2 rec, 2 ana, 2 zip |
| `4.0M+200ms+5% (r3)` | `douyin-controlled-rb-4mbps-200ms-5pct-410101-20261120-r3` | `R->B` | `true` / `0` / `2026-11-20T12:07:15.3978670Z` | `true` | `[1.159, 1.191]s` (`MET`, `UPPER_BOUND`) | **`[2.072, 2.106]s` (`MET`, `EXACT`)** | Portrait video `@你的欲梦` (`active_ratio=0.5070`, `max_active_60f=33`); **795.0 ms** startup hold, **0** post-startup mid-clip freezes over `34.23s` | 2 rec, 2 ana, 2 zip |

### 3.3 Exact Packet Conservation & Link Telemetry (All 20 Phases)

| Short Label | Phase | `request.json` Rate (B/s) / Delay / Loss | `result.json` `downlinkIpBytesPerSecond` | `RxPkt` / `RxBytes` | `InjPkt` / `InjBytes` | `DropPkt` / `DropBytes` (Loss%) | `QDrop` / `Clean` / `Unconf` | `AP Tx` Delta (B) | `Eth Rx` Delta (B) | `WLAN Rx` Noise (B) | `ifIndex=12` Routes |
|---|---|---|---|---:|---:|---:|---|---:|---:|---:|---|
| `1.0M R->B (r5)` | R | `inspect` (req `125000`) | `null` | `16,588` / `22,581,461` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `22,889,816` | `25,471,153` | `7,080` | `8/8` |
| `1.0M R->B (r5)` | B | `125000` / `-` / `-` | `125000` | `4,274` / `4,296,696` | `4,274` / `4,296,696` | `0` / `0` | `0` / `0` / `0` | `4,393,641` | `5,823,835` | `11,655` | `8/8` |
| `1.0M B->R (r2)` | B | `125000` / `-` / `-` | `125000` | `5,242` / `4,199,292` | `5,242` / `4,199,292` | `0` / `0` | `0` / `0` / `0` | `4,318,124` | `6,061,440` | `1,319` | `8/8` |
| `1.0M B->R (r2)` | R | `inspect` (req `125000`) | `null` | `8,046` / `10,337,672` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `10,476,022` | `12,182,416` | `56` | `8/8` |
| `1.0M B->R (r3)` | B | `125000` / `-` / `-` | `125000` | `5,172` / `4,365,367` | `5,172` / `4,365,367` | `0` / `0` | `0` / `0` / `0` | `4,480,403` | `5,757,172` | `0` | `8/8` |
| `1.0M B->R (r3)` | R | `inspect` (req `125000`) | `null` | `7,559` / `9,643,385` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `9,874,301` | `12,181,987` | `14,775` | `8/8` |
| `1.0M B->R (r4)` | B | `125000` / `-` / `-` | `250000` (stale) | `4,843` / `4,386,877` | `4,843` / `4,386,877` | `0` / `0` | `0` / `0` / `0` | `4,490,835` | `6,106,523` | `3,055` | `8/8` |
| `1.0M B->R (r4)` | R | `inspect` (req `125000`) | `null` | `6,800` / `8,403,447` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `8,524,052` | `11,030,281` | `3,176` | `8/8` |
| `1.5M R->B (r2)` | R | `inspect` (req `187500`) | `null` | `7,708` / `9,687,674` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `9,831,150` | `11,235,977` | `2,996` | `8/8` |
| `1.5M R->B (r2)` | B | `187500` / `-` / `-` | `250000` (stale) | `5,587` / `6,209,091` | `5,587` / `6,209,091` | `0` / `0` | `0` / `0` / `0` | `6,385,371` | `6,968,755` | `4,463` | `8/8` |
| `1.5M R->B (r3)` | R | `inspect` (req `187500`) | `null` | `6,692` / `8,231,616` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `8,386,825` | `9,116,168` | `22,606` | `8/8` |
| `1.5M R->B (r3)` | B | `187500` / `-` / `-` | `187500` | `5,041` / `5,881,577` | `5,041` / `5,881,577` | `0` / `0` | `0` / `0` / `0` | `5,979,431` | `8,230,417` | `19,850` | `8/8` |
| `2.0M R->B` | R | `inspect` (req `250000`) | `null` | `14,336` / `20,006,671` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `20,372,652` | `21,909,349` | `751` | `7/7` |
| `2.0M R->B` | B | `250000` / `-` / `-` | `250000` | `6,561` / `7,911,366` | `6,561` / `7,911,366` | `0` / `0` | `0` / `0` / `0` | `8,042,679` | `9,123,767` | `0` | `7/7` |
| `2.0M+150ms+2%` | R | `inspect` (req `250000`) | `null` | `5,651` / `6,930,655` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `7,133,427` | `7,685,964` | `4,877` | `8/8` |
| `2.0M+150ms+2%` | B | `250000` / `150ms` / `200bps` (`seed=20261120`) | `250000` | `6,753` / `8,281,984` | `6,617` / `8,104,028` | `136` / `177,956` (**2.014%**) | `0` / `0` / `0` | `8,234,242` | `10,257,403` | `3,547` | `8/8` |
| `4.0M R->B` | R | `inspect` (req `500000`) | `null` | `8,576` / `11,231,108` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `11,375,198` | `11,703,061` | `56` | `8/8` |
| `4.0M R->B` | B | `500000` / `-` / `-` | `500000` | `10,550` / `14,131,700` | `10,550` / `14,131,700` | `0` / `0` | `0` / `0` / `0` | `14,314,594` | `15,492,115` | `56` | `8/8` |
| `4.0M+200ms+5% (r3)` | R | `inspect` (req `500000`) | `null` | `6,386` / `7,576,461` | `0` / `0` | `0` / `0` | `0` / `0` / `0` | `7,713,005` | `8,423,094` | `29,878` | `8/8` |
| `4.0M+200ms+5% (r3)` | B | `500000` / `200ms` / `500bps` (`seed=20261120`) | `500000` | `5,653` / `6,878,769` | `5,380` / `6,543,674` | `273` / `335,095` (**4.829%**) | `0` / `0` / `0` | `6,653,506` | `8,343,420` | `23,039` | `8/8` |

All 20/20 phases exited cleanly (`StopReason = TTL_EXPIRED`, `ExitCode = 0`, `Failures = null`, `DriverDrained = true`, `OwnedHandleClosed = true`, `WorkersJoined = true`, `UnconfirmedUserPackets = 0`, `20/20` Kotlin `reader-exported` verified).

### 3.4 Key Behavioral Conclusions (Symmetry, Shoulder & Decoupling)

1. **`R->B` / `B->R` Symmetry**: Running `1.000 Mbps` in forward order (`1.0M R->B r5`: `[10.105, 10.139]s NOT_MET`) and reverse order (`1.0M B->R r4`: `[7.573, 7.608]s NOT_MET`) both caused severe cover freezes (`9.11s` / `6.43s`) while unthrottled R remained `[0.780, 1.026]s` / `[0.945, 0.976]s MET`, ruling out thermal drift, phase-ordering, or cold-cache artifacts.
2. **`1.500 Mbps` Shoulder (`1.5M r3`)**: Unrounded `ptr_interval_s = [1.986644, 2.021789]s` (`sw_interval_s = [1.920011, 1.956744]s`) met the 3.0s startup SLA, but steady-state video playback drained the initial buffer (`1,026.8 ms` startup hold) and suffered a **`7,790.2 ms` mid-clip freeze** (`n0400..n0869`, resuming `n0880`). Thus `(1.000, 1.500] Mbps` is the startup cliff while `(1.500, 2.000] Mbps` is required for freeze-free continuous playback.
3. **Throughput-vs-Impairment Decoupling**: At `>= 2.0 Mbps`, adding `150 ms + 2%` loss (`2.0M+150ms+2%`, `[1.093, 1.143]s MET`, one minor `363.6 ms` pause) or `200 ms + 5%` loss (`4.0M+200ms+5% r3`, unrounded `ptr = [2.072222, 2.106033]s MET`, `sw = [2.018744, 2.055400]s`, `795.0 ms` startup hold, `0` mid-clip freezes) preserves both startup compliance and playback stability.

---

## 4. Controlled Business Baseline II — Multimodal & Streaming AI Chat (`Doubao` & `KimiChat`)

### 4.1 Doubao `com.larus.nova` Controlled Campaign (`r5`–`r8` + M2.1 `256kbps-15080040`)

Fixed prompt across all 5 cards: `"Write ten short numbered sentences explaining how network latency and packet loss hurt mobile AI chat."`

| Card Dir | App Ver (Code) | Capture Method | Phase B Config | Phase | `State` / `StopReason` | `RxPkt` / `RxB` | `InjPkt` / `InjB` | `DropPkt` / `DropB` | `QDropPkt` / `QDropB` | `CleanPkt` / `CleanB` | `Unconf` | `AP Tx` (B) | `WLAN` (B) | Visual TTFR / Completion |
|---|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `doubao-controlled-rb-20261120-r5` | `15.7.0` (`15070040`) | `screencap` burst | `8,000 B/s` (`64 kbps`) + `600ms` + `800bps` (`8%`) | R | `OBSERVED_NOT_QUALIFIED` / `TTL_EXPIRED` | `2,337` / `1,414,369` | `0` / `0` | `0` / `0` | `0` / `0` | `0` / `0` | `0` | `1,461,022` | `2,997` | Invalidated pair (`R` `53` frames) |
| `doubao-controlled-rb-20261120-r5` | `15.7.0` (`15070040`) | `screencap` burst | `8,000 B/s` (`64 kbps`) + `600ms` + `800bps` (`8%`) | B | **`INVALID` / `USER_QUEUE_OVERFLOW`** | `2,134` / `231,600` | `78` / `41,494` | `7` / `1,604` | `0` / `0` | `2,049` / `188,502` | `0` | `12,861,577` | `1,997` | Voided (queue overflow @ `2048+1` pkts) |
| `doubao-controlled-rb-20261120-r6` | `15.7.0` (`15070040`) | `screencap` burst | `32,000 B/s` (`256 kbps`) + `350ms` + `500bps` (`5%`) | R | `OBSERVED_NOT_QUALIFIED` / `TTL_EXPIRED` | `2,447` / `1,471,436` | `0` / `0` | `0` / `0` | `0` / `0` | `0` / `0` | `0` | `1,516,881` | `2,996` | Invalidated pair (`R` `54` frames) |
| `doubao-controlled-rb-20261120-r6` | `15.7.0` (`15070040`) | `screencap` burst | `32,000 B/s` (`256 kbps`) + `350ms` + `500bps` (`5%`) | B | **`INVALID` / `USER_QUEUE_OVERFLOW`** | `3,868` / `624,393` | `1,732` / `305,301` | `87` / `17,458` | `0` / `0` | `2,049` / `301,634` | `0` | `2,096,791` | `1,998` | Voided (avg pkt ~`161 B` burst) |
| `doubao-controlled-rb-20261120-r7` | `15.7.0` (`15070040`) | `screencap` burst | `125,000 B/s` (`1.0 Mbps`) + `200ms` + `300bps` (`3%`) | R | `OBSERVED_NOT_QUALIFIED` / `TTL_EXPIRED` | `2,425` / `1,495,493` | `0` / `0` | `0` / `0` | `0` / `0` | `0` / `0` | `0` | `1,539,454` | `2,996` | TTFR `[2.58, 3.40]s`, Comp `[5.58, 6.50]s` (`864` chars) |
| `doubao-controlled-rb-20261120-r7` | `15.7.0` (`15070040`) | `screencap` burst | `125,000 B/s` (`1.0 Mbps`) + `200ms` + `300bps` (`3%`) | B | `INJECTED_NOT_CALIBRATED` / `TTL_EXPIRED` | `5,989` / `3,393,550` | `5,814` / `3,306,631` | `172` / `85,979` (**2.873%**) | `0` / `0` | `3` / `940` | `0` | `3,428,863` | `2,997` | TTFR `[2.32, 3.15]s`, Comp `[6.28, 7.22]s` (**2.47×** pkts, **2.23×** AP Tx) |
| `doubao-controlled-rb-256kbps-15080040-20261120` | `15.8.0` (`15080040`) | `MediaProjection` 30 fps MP4 | `32,000 B/s` (`256 kbps`) + `350ms` + `500bps` (`5%`) + `131,072 B` `tail_drop` | R | `OBSERVED_NOT_QUALIFIED` / `TTL_EXPIRED` | `8,452` / `10,283,533` | `0` / `0` | `0` / `0` | `0` / `0` | `0` / `0` | `0` | `10,424,941` | `13,894` | Comp `n0273` `5.619s` (~`4.87s` post-send; `385` frames, `24.541s`) |
| `doubao-controlled-rb-256kbps-15080040-20261120` | `15.8.0` (`15080040`) | `MediaProjection` 30 fps MP4 | `32,000 B/s` (`256 kbps`) + `350ms` + `500bps` (`5%`) + `131,072 B` `tail_drop` | B | `INJECTED_NOT_CALIBRATED` / `TTL_EXPIRED` | `7,834` / `2,672,166` | `5,610` / `1,166,755` | `289` / `61,258` (**4.899%**) | **`1,935` / `1,444,153`** | `0` / `0` | `0` | `1,269,804` | `20,181` | **Starved `TTFR > 26.34s`** (`27.1363s` - `0.7944s`; `0` chars, stuck on `...`; `1,597` frames, `mean_dt = 17.00 ms`) |
| `doubao-controlled-rb-20261120-r8` | `15.7.0` (`15070040`) | `screencap` burst | `16,000 B/s` (`128 kbps`) + `400ms` + `500bps` (`5%`) + `131,072 B` `tail_drop` | R | `OBSERVED_NOT_QUALIFIED` / `TTL_EXPIRED` | `17,008` / `22,482,826` | `0` / `0` | `0` / `0` | `0` / `0` | `0` / `0` | `0` | `22,735,776` | `9,904` | TTFR `[1.86, 2.75]s`, Comp `[5.90, 6.35]s` (`1,000` chars) |
| `doubao-controlled-rb-20261120-r8` | `15.7.0` (`15070040`) | `screencap` burst | `16,000 B/s` (`128 kbps`) + `400ms` + `500bps` (`5%`) + `131,072 B` `tail_drop` | B | `INJECTED_NOT_CALIBRATED` / `TTL_EXPIRED` | `12,062` / `1,913,208` | `8,011` / `902,909` | `414` / `45,927` (**4.914%**) | **`3,637` / `964,372`** | `0` / `0` | `0` | `1,041,104` | `20,232` | **Starved `TTFR > 26.53s`** (`0` chars, `frame-0002..0051` stuck on `...`) |

#### Analytical & Reconciliation Notes for Doubao

1. **Pre-AQM `USER_QUEUE_OVERFLOW` (`r5` / `r6`) vs `tail_drop` (`256k` / `r8`)**: Under severe throttling (`64–256 kbps`), Doubao's Cronet/QUIC stack emits dense bursts of small ~`161 B` packets alongside background media/telemetry, overflowing the fixed `2,048`-packet user queue in `r5` and `r6`. Enabling `tail_drop` (`queue_limit_bytes = 131,072 B` = `128 KiB`) eliminated queue overflows (`0` abort, `UnconfirmedUserPackets = 0`), shedding `1,935` (`1,444,153 B`) and `3,637` (`964,372 B`) excess tail packets at `256 kbps` and `128 kbps` respectively.
2. **`r7` (`1.0 Mbps + 200 ms + 3%`) Amplification & `DOUBAO_RB_SUMMARY.json` `pass` Reconciliation**: At `1.0 Mbps`, Doubao preserved completion (`[6.28, 7.22]s` vs R `[5.58, 6.50]s`), but retransmissions and QUIC overhead increased packet count by **`2.47×`** (`5,989` vs `2,425`) and AP Tx bytes by **`2.23×`** (`3,428,863 B` vs `1,539,454 B`). Note a historical script nuance: `doubao-controlled-rb-20261120-r7\DOUBAO_RB_SUMMARY.json:76` marks `pass = false` solely because legacy `run-doubao-rb.ps1:402` required `CleanupInjectedPackets -eq 0`, whereas Phase B flushed `3` TTL-expiry tail packets (`940 B`, `StopReason = TTL_EXPIRED`, `UnconfirmedUserPackets = 0`, `Failures = null`). Conversely, `r5\DOUBAO_RB_SUMMARY.json` recorded `pass = true` despite `State = INVALID, StopReason = USER_QUEUE_OVERFLOW` before the `TTL_EXPIRED` gate was added in `r6/r7`.
3. **Mid-Weak-Net `256 kbps` Starvation (`doubao-controlled-rb-256kbps-15080040-20261120`) & `MediaProjection` Upgrade**: Upgrading capture from ~`1.9 fps` `screencap` bursts to 30 fps `MediaProjection` MP4 (`FINAL_MEDIA.json`: R `385` frames / `24.541s`; B `1,597` frames / `27.136s`, `pts_nondecreasing = true`, `mean_dt = 17.00 ms`) with `OUTER_RESULT.json pass = true` and `INDEPENDENT_CLEANUP.json 12/12` closed the timing uncertainty between `r7` (`1.0 Mbps`) and `r8` (`128 kbps`). At `256 kbps + 350 ms + 5%`, Doubao stalled identically to `128 kbps` (`TTFR > 26.34s`, `0` streamed tokens, stuck on `...`). Thus the AI chat streaming cliff under multi-flow app traffic lies in **`(256, 1000] kbps`**.
4. **SI vs IEC Unit Standardization**: Prior campaign reports mixed decimal (`1 KB = 1000 B` in `INFLECTION_REPORT.md`: `22,606 B -> 22.61 KB`) and binary (`1 KiB = 1024 B` in `DOUBAO_RB_REPORT.md`: `9,904 B -> 9.67 KB`, `20,232 B -> 19.76 KB`) conversions while reporting AP Tx in decimal MB (`1,041,104 B -> 1.04 MB`). All tables in this report state **exact byte counts** (and use explicit `KiB` = `1024 B` where binary queue limits apply).

### 4.2 KimiChat (`com.moonshot.kimichat`) Gate Survey

- **Primary Artifact (`kimi-controlled-rb-20261120-r3`)**: Executed Phase R `inspect` (`2026-11-20T04:11:48.5820450Z` -> `04:14:35.8616015Z`; earlier attempts `kimi-controlled-rb-20261120` and `-r2` aborted in preflight setup).
  - `KIMI_RB_SUMMARY.json`: `target_package = "com.moonshot.kimichat"`, `dev_state = "OBSERVATION_UNKNOWN"`, `dev_reason = "DEEPSEEK_CURRENT_CHILD_INCOMPLETE"`, `send_calls = 0`, `owned_cleanup = true`, `ReceivedPackets = 727` (`465,272 B`), `UnconfirmedUserPackets = 0`, `ap_tx_bytes = 492,581 B`, `wlan_noise_bytes = 3,995 B`.
  - `DEEPSEEK_CURRENT_GENERATION_FAILURE.json` & `ordinary-preparation-original-failure.json`: `KimiUiAdapter.prepareEmpty -> KimiUiAdapter.read -> deepSeekFrame -> currentDeepSeekGeneration` aborted cleanly on `androidx.compose.ui.platform.ComposeView` edge node `path = "0/0/0/0/0/0/0/0/0/0/1"` (`bounds = [0, 0, 2, 2640]`, `formal_new_slots = 0`).
- **Context Attested by `DOUBAO_RB_REPORT.md:6`**: `com.moonshot.kimichat` auto-updated on `2026-11-16` to `v3.1.8 (versionCode = 388)` and presented an unauthenticated `去登录` home gate (observed on device and recorded in `doubao-controlled-rb-20261120-r7\DOUBAO_RB_REPORT.md:6`; no separate `dumpsys package` or UI XML was stored in `kimi-controlled-rb-20261120-r3`).

---

## 5. Field Paired Benchmark (`W1` Hotel 5 GHz Wi-Fi vs `C1` 5G NR) & Stage 2 UI Comparison

### 5.1 Links & Causal Isolation of `TCP_NODELAY` (`commit 1165320f`)

Primary artifacts: `p2-miw2-w1-c1-paired-20261120\` (`P2_W1_C1_AND_VOICE_DIAGNOSIS.md`, `w1_c1_db_summary.json`, `voice_before_after_nodelay.json`, `w1-network-state.txt`, `c1-network-state.txt`).

- **Test Links** (P40 Pro `8MY0221126002537` -> `miw2` `https://120.77.174.207:8443`, `aneb-server/0.8.4`, `TLSv1.3 ALPN: http/1.1`):
  - **`W1` (Hotel 5 GHz Wi-Fi)**: `NetworkAgentInfo{network{513}}`, `SSID: "AscottStarRewards"`, Wi-Fi 5 (`5805 MHz`), `Link speed: 65 Mbps`, `RSSI: -64 dBm` (`-62~-63 dBm` across runs), `wlan0`.
  - **`C1` (China Telecom 5G NR)**: `NetworkAgentInfo{network{514}}`, `MOBILE[NR] CONNECTED extra: ctnet`, `rmnet0`.
- **Root-Cause Isolation (`P2_W1_C1_AND_VOICE_DIAGNOSIS.md:15-16`)**:
  - A PC wired direct baseline to `miw2 /api/v1/realtime-sim` verified strict server `dwell = 300.17–300.57 ms` (`M4 TTFB = 23.4–29.5 ms`, `M1' = 104.4–110.4 ms`, `nearZero = 0/49`), ruling out server-side dwell leakage.
  - Conversely, `okhttp-4.12.0` (`RealConnection` / `RealWebSocket`) left `TCP_NODELAY = false`, causing Nagle + Delayed-ACK coalescing on small `20 ms / 160 B` voice frames and `speech_commit`. Implementing `TcpNoDelaySocketFactory` (`commit 1165320fc86dfc193d09245d2fd1618bff5dd4bc`) fixed uplink frame pacing immediately:

| `id` | Condition | `TCP_NODELAY` | `rttMs` (`N1`) | `upFrameJitterMs` (`M3`) | `ttfbP50Ms` / `ttfbP95Ms` (`M4`) | `mouthEarProxyMs` (`M1'`) | `turnSwitchP50Ms` (`M5`) | `bargeStopMaxMs` (`M6`) | `m7MaxFrameGapMs` (`M7`) | `voiceNearZeroArrivalRatio` |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `1` | Local `adb reverse` | `false` | `9.507` | `12.641` | `6.995 / 12.416` | `87.815` | `11.981` | `-0.308` | `44.116` | `0.0031` |
| `2` | `W1` Wi-Fi (`0.8.3`) | `false` | `51.979` | `55.555` | `624.372 / 717.169` | `705.471` | `648.270` | `23.954` | `299.492` | `0.2881` |
| `3` | `W1` Wi-Fi (`0.8.4`) | `false` | `93.514` | `45.059` | `311.422 / 395.228` | `392.395` | `345.837` | `58.297` | `26.225` | `0.0773` |
| `5` | `W1` Wi-Fi (`0.8.4`) | **`true`** | `47.639` | **`3.339`** | `626.032 / 677.144` | **`706.411`** | `614.329` | `21.196` | `30.705` | `0.1490` |
| `4` | `C1` 5G NR (`0.8.4`) | `false` | `89.400` | `51.934` | `202.101 / 417.611` | `283.207` | `247.184` | `39.682` | `40.000` | `0.0464` |
| `6` | `C1` 5G NR (`0.8.4`) | **`true`** | `89.549` | **`15.046`** | **`137.991 / 306.040`** | **`218.932`** | `160.283` | `39.349` | `27.062` | `0.0542` |

*(All 6 runs: `caliber = "server-sim(aneb-realtime-session-v1)"`, `turnsOk = 8`, `framesSent = 150`.)*

- **Exact `M3` Reductions**: On `W1`, `M3` dropped **`16.64×`** (`55.555 -> 3.339 ms`, `id=2 -> id=5`) and **`13.49×`** (`45.059 -> 3.339 ms`, `id=3 -> id=5`); in Pilot RC v2 (`p3-signed-pilot-rc-v2-20261120/PILOT_RC_V2_QA_RECEIPT.md:37`, also `W1`), `M3` reached `2.4 ms` (**`23.15×`** vs `id=2`). On `C1`, `M3` dropped **`3.45×`** (`51.934 -> 15.046 ms`, `id=4 -> id=6`).

### 5.2 Multi-Layer Divergence: Basic Speed vs Token AQS vs Voice Inversion (`W1` vs `C1`)

| Layer / Mode | `W1` (Hotel 5 GHz Wi-Fi) | `C1` (China Telecom 5G NR) | Divergence & Explanation |
|---|---|---|---|
| **Basic / `LoadedResponsiveness v2`** (`w1_c1_db_summary.json`) | DL **`44.75 Mbps`** (`44.7455`), UL **`44.82 Mbps`** (`44.8201`), idle ping `39.88 ms`, post-load `41.18 ms`, jitter `1.55 ms` (`status="completed"`, `loss=0.0`) | DL **`186.30 Mbps`** (`186.2971`, **`4.16×`** `W1`), UL **`87.13 Mbps`** (`87.1348`, **`1.94×`** `W1`), idle ping `75.99 ms`, post-load `75.83 ms`, jitter `2.20 ms` | `W1` had ~`1.9×` lower idle latency; `C1` had `4.16×` DL / `1.94×` UL capacity. |
| **Token Experience (`quick`, `s1..s3`)** (`w1_c1_db_summary.json`) | **`AQS = 92.59`** (`92.5878`), `N1 = 46.68–47.26 ms`, `N2 = 5.29–8.70 ms`, `T1 = 37.28–42.77 ms`, `T2 = 10.62–26.38 ms`, `s2 u2ToolLoopP95Ms = 84.09 ms`, `bufferingScore = 0.6433` (`retrans_suspect`) | **`AQS = 84.54`** (`84.5367`), `N1 = 96.25–110.17 ms`, `N2 = 19.97–37.51 ms`, `T1 = 43.03–86.38 ms`, `T2 = 11.48–27.01 ms`, `s2 u2ToolLoopP95Ms = 427.15 ms`, `bufferingScore = 0.5377` (`retrans_suspect`) | Token Experience favored **`W1` (+8.05 pts)** due to lower RTT/TTFT and `5.08×` faster tool-loop `P95`. |
| **Profile 4 Voice (`TCP_NODELAY = true`)** (`voice_before_after_nodelay.json`) | `N1 = 47.64 ms`, `M3 = 3.34 ms`, `M4 TTFB = 626.03 ms`, **`M1' = 706.41 ms`** (veto > `400 ms` triggered), `M5 = 614.33 ms`, `nearZero = 0.1490` | `N1 = 89.55 ms`, `M3 = 15.05 ms`, `M4 TTFB = 137.99 ms` (**`4.54×` faster**), **`M1' = 218.93 ms`** (**`3.23×` faster**, no veto), `M5 = 160.28 ms`, `nearZero = 0.0542` | **3.23× Inversion**: Despite `1.88×` lower base RTT on `W1`, `C1` beat `W1` by `487.5 ms` on `M1'`. |

- **Observed `300 ms` Silent-Turn Penalty & Physical-Layer Hypothesis**: After `TCP_NODELAY=true` eliminated Nagle coalescing (`M3` down to `3.34 ms`), `W1` still suffered an empirical **`~300+ ms` turn-silence penalty** (`M4 TTFB = 626.03 ms`, `M1' = 706.41 ms`, `nearZero = 0.1490`, elevated Wi-Fi MAC retransmissions) following the `300 ms` client speech-end dwell (`P2_W1_C1_AND_VOICE_DIAGNOSIS.md:7,18`). Because no 802.11 MAC-frame air capture was taken, **AP power-save mode (802.11 PSM wake latency) and shared-channel Wi-Fi contention** are presented as a **physical-layer hypothesis** explaining why hotel Wi-Fi penalized intermittent voice turn transitions while continuous Token streaming stayed fast.

### 5.3 Stage 2 `PairedComparisonCard` Verification (`p3-signed-stage2-rc-20261120/`)

Verified against `PILOT_STAGE2_RC_QA_RECEIPT.md:50-54`, `aneb_compare_20261121_055147.md` (`1,348 B`), `aneb_compare_1795211513240.png` (`237,855 B`, `1080x1350`), and `aneb_01a1face_20261121_051554.json`:

- **Paired Comparison Output**: `A (Wi-Fi)` (`2026-11-21 05:13:47`, exact `score = 93.46147294086671` -> **`93.5 分`**) vs `B (5G NR)` (`2026-11-21 05:18:42`, **`88.3 分`** [badge integer `88 优秀`]): delta **`-5.1 分 (-5.5%)`**.
- **Per-KPI Deltas**: `TTFT 46.3 -> 55.8 ms (+9.5 ms / +20.5%)`, `ITL P95 10.8 -> 16.7 ms (+5.9 ms / +54.3%)`, `RTT P50 42.5 -> 55.8 ms (+13.3 ms / +31.4%)`, `Jitter 5.1 -> 14.3 ms (+9.1 ms / +177.1%)`, `Uplink 23.78 -> 3.99 Mbps (-19.80 Mbps / -83.2%)`, `Tool Loop P95 56.1 -> 76.4 ms (+20.2 ms / +36.0%)`.
- **UI Swap Parity**: Clicking `"⇄ 对调"` inverted the delta to `+5.1 分 (+5.8%)`, confirming symmetric side-by-side comparison and Markdown/PNG export readiness for `Pilot v0.2`.

---

## 6. Profile 4 Synthetic Voice (`VoiceRunner.runSim`) vs Real Voice App (`com.larus.nova`) Calibration

Primary sources: `docs/research/PROFILE4_VOICE_CORRELATION_REPORT.md`, `profile4-voice-correlation-20261120\analysis\{voice-correlation-summary.json, voice-correlation-input.json, voice-correlation-table.md, agents-clean-state-receipt.json}`, `runtime-sim-r2\{CAMPAIGN_RESULT.json, phase-{R,B1,B2}\phone-raw.json}`, `runtime-real-r2\{CAMPAIGN_RESULT.json, phase-{R,B1,B2}\{raw.json, ui_timeline.json, host_audio_events.json}}`, `tools/business-video-review/correlate_voice_rb.py`, and `DoubaoVoiceCallInstrumentation.kt` (note: pre-r2 `runtime-sim/` and `runtime-real/` directories are superseded).

### 6.1 Topology, Condition Matrix & Packet Conservation

All 6 paired phases (`3` synthetic `VoiceRunner.runSim` vs `miw2` `/api/v1/realtime-sim` + `3` real `com.larus.nova` `15.8.0`/`15080040` `RealtimeCallActivity` `62s` audio-only sessions) ran over the identical P40 Pro -> WinRT Hotspot (`ifIndex=16`) -> Ethernet (`ifIndex=12`, NAT `10.10.8.9`) -> `phone-forward-condition v4` (`9d3609f5`, `ttl_seconds=115`) path:

- **`R` (`inspect`)**: Unshaped (`0 ms`, `0%`, no `tail_drop`).
- **`B1` (`sym 256k + 120ms + 2%`)**: `32,000 B/s` (`256 kbps`) / `60 ms` / `200 bps` (`2%`) DL & UL (`+120 ms` RTT), `tail_drop` (`262,144 B` = `256 KiB`). Real-app DL logged `917` `tail_drop` + `248` loss on `13,825` DL packets (`8.43%` effective DL drop).
- **`B2` (`asym weak UL 128k + 200ms + 5%`)**: `250,000 B/s` (`2.0 Mbps`) / `50 ms` / `0%` DL + `16,000 B/s` (`128 kbps`) / `150 ms` / `500 bps` (`5%`) UL (`+200 ms` RTT), `tail_drop` (`262,144 B`). Real-app UL logged **`2,415` `tail_drop` + `526` loss** on `13,508` UL packets (**`21.77%` effective UL drop**).
- **Conservation**: `UnconfirmedUserPackets == 0` across aggregate, DL, and UL for all `6/6` runs (`agents-clean-state-receipt.json` `100%` clean pre/post).

### 6.2 Paired Measurements (`R`, `B1`, `B2`)

| Cond | `N1` RTT (ms) | `M1'` Proxy (ms) | `M3` UL Jitter (ms) | `M4` TTFB P50 / P95 (ms) | `M7` MaxGap (ms) | `AQS_v02` (Raw) | Veto (`M1'>400`) | Real App Status | Turns (`ratio`) | `connect_ready_ms` | `p3_onset_ms` |
|---|---:|---:|---:|---:|---:|---:|---|---|---:|---:|---:|
| **`R`** | `63.61` (`63.614`) | `291.87` (`291.871`) | `7.14` (`7.142`) | `210.37 / 358.67` | `49.49` (`49.489`) | **`78.76`** (`78.756`) | `false` | `COMPLETED_3_OF_3` | **3/3** (`1.000`) | `374.8` (`374.765`) | `1243.7` (`1243.745`) |
| **`B1`** | `213.59` (`213.590`) | `435.61` (`435.606`) | `20.00` (`19.999`) | `353.59 / 923.71` | `234.39` (`234.387`) | **`54.00`** (`63.836`) | **`true`** | `DEGRADED_CONNECT_2_OF_3` | **2/3** (`0.667`) | `9685.1` (`9685.110`) | `7411.6` (`7411.586`) |
| **`B2`** | `541.70` (`541.705`) | `816.38` (`816.376`) | `158.78` (`158.781`) | `734.96 / 1834.41` | `74.99` (`74.994`) | **`47.20`** (`47.201`) | **`true`** | `STARVED_CONNECTING_0_OF_3` | **0/3** (`0.000`) | `None` (`>62000`) | `None` |

### 6.3 Rank Correlation, Sensitivity & Methodological Caveats

1. **Perfect Monotonic Consistency (`|rho| = 1.000`, `|tau-b| = 1.000`)**: `AQS_v02` correlates at **`Spearman rho = +1.000`**, **`Kendall tau-b = +1.000`** with `turns_completed_ratio` and `-1.000` with severity / `connect_ready_or_cap_ms`. Individual KPIs `N1`, `M1'`, `M3`, `M4` (P50 & P95), `M5`, and `M6` all achieve `|rho| = 1.000`, `|tau-b| = 1.000`.
2. **Weight Sensitivity & Veto Validation**: Across **`13/13`** variants (`baseline_v02`, `no_veto_diagnostic`, `uplink_emphasized_v1`, `latency_uplink_v2`, and all 9 leave-one-KPI-out `loo_without_*`), ordering `R > B1 > B2` is 100% invariant. Without the `M1' > 400 ms` veto (`54.0` cap), `B1` scores `63.84` despite a `9.69 s` `"连接中..."` connect stall and losing Turn 1 (`2/3` turns); with the veto, `B1` drops to **`54.00`** (`Poor`), cleanly separating usable `R` (`78.76`) from degraded `B1` (`54.00`) and starved `B2` (`47.20`).
3. **Asymmetric Uplink Blind Spot & Real RTC Cliff**: `M7` (`49.49 -> 234.39 -> 74.99 ms`, `rho = 0.500`, `tau-b = 0.333`) and `M2'` (`18.93 -> 19.55 -> 18.28 ms`) are downlink-only metrics and stayed healthy in `B2` (`2.0 Mbps` DL), whereas uplink-sensitive **`M3`** (`22.23×` degradation in `B2`) and **`M4`/`M1'`** captured the bottleneck. Real WebRTC signaling + audio (`com.larus.nova`, `13,508` UL packets, `2,415` UL `tail_drop`) starved completely (`0/3`) at `128 kbps` UL where lean WebSocket `VoiceRunner.runSim()` (`784` UL packets) still finished `8/8` turns.
4. **Statistical & Instrumentation Caveats**:
   - **Statistical Power ($N=3$)**: With $3! = 6$ permutations, minimum two-sided permutation $p = 2/6 = 0.333$; correlations verify **directional monotonic consistency and structural sensitivity**, not frequentist $p < 0.05$ significance.
   - **UI Polling & Empty `subtitle`**: `DoubaoVoiceCallInstrumentation.kt:36,288,388` polls Accessibility nodes every **`100 ms`** (`SystemClock.sleep(100L)`), and `subtitle` was `""` in every event across `R`/`B1`/`B2`; turn timing relies on status transitions (`"连接中..."` -> `"你可以开始说话"` -> `"正在听..."` -> `"说话或点击打断"`).
   - **Free-Field Acoustic Coupling**: Voice prompts (`Microsoft Huihui Desktop` SAPI, `p1` ~`1.5s`, `p2` ~`21.5s`, `p3` ~`41.5s`, 16 kHz PCM) played over host speakers into the P40 Pro microphone in free-field air (not digital loopback), so `p3_response_onset_ms` includes constant device mic/AEC/VAD + cloud ASR/LLM/TTS latency, whereas `B2`'s `62s` `"连接中..."` stall occurred entirely at RTC handshake prior to speech turns.

---

## 7. Platform & Runtime Foundations (Stages 0–3)

### 7.1 Milestone Commit Lineage & Client Reliability

| Milestone | Merged Commit (PR) | Key Components & Verified Primary Evidence |
|---|---|---|
| **M1 (Stage 0)** | `befa87b88a63d16591d516431115e360f63fe457` (PR #115) | `LoadedResponsiveness v2` (`cbcaf4c2`: `NetworkSpeedEngine.kt`, `AnebClient.kt`, `NetworkSpeedEngineV2Test.kt`), bounded `tail_drop` AQM (`beead81d`, `bcf4efd3`), parameterized templates (`6d3aee86`, `4161e68c`). **Pilot RC v2** (`p3-signed-pilot-rc-v2-20261120/`): `probe-release.apk` (`52,672,487 B`, SHA `3c9d2def...`) + `probe-prototypeRelease.apk` (`52,672,475 B`, SHA `7efe1f49...`) live-verified against `miw2`: `aneb_loaded_01a1f879-7ac8-726e-a240-1824b4b14961.json` (`1,471,073 B`), `status = "completed"`, `errors = null`, `idleP50Ms = 40.426`, `downloadP95Ms = 196.363` (`+155.937 ms`, `24/25`), `uploadP95Ms = 208.981` (`+168.555 ms`, `13/23`), `stopReason = "phase_deadline"`, `1.26 ms` drain. |
| **M2 (Stage 1)** | `bb13490357b0a479b110b3c2a68e63b27cdd0598` (PR #116) | `tools/controlled-rb-card/new_card.py` (`659` lines, `f996500e`), Doubao `MediaProjection` 30 fps (`58077df`, `453e8a02`), bidirectional shaper `v4` (`688f1416..9d3609f5`), Douyin `4.0M+200ms+5% r3`, Profile 4 correlation (`92d38c7f`). **Live `bidir-r4` calibration** (`phone-calibration-winrt-bidir-20261120-r4`): `reference` DL `47.699` / UL `31.516 Mbps` / RTT P50 `39.533 ms`; `limited` (`2.0M + 50ms + 1%` DL, `0.5M + 50ms + 1%` UL, `256 KiB tail_drop`) DL **`1.894 Mbps`** (`1,900 = 1,881 + 19` pkts) / UL **`0.477 Mbps`** (`1,967 = 1,956 + 11` pkts) / `/echo` RTT P50 **`153.394 ms`** (**`+112.682 ms`** vs `recovery` `40.712 ms`; `+113.861 ms` vs `reference` `39.533 ms`); `recovery` DL `48.524` / UL `32.508 Mbps`; `UnconfirmedUserPackets = 0`, `INDEPENDENT_CLEANUP` `12/12`. |
| **M3 (Stage 2)** | `6ce3e8830a2347011ef4fcf489ca59fb7cce2926` (PR #117) | **4 selective donor ports** (`35b64f31`, zero Room v15/v23 conflict): (1) `KeepScreenOnPolicy.kt` (`KEEP_SCREEN_ON` `0x00000080` verified & cleared on `am force-stop`); (2) `ThermalSummary.kt` + `EnvMonitors.kt` (**`replay = 8`** per `EnvMonitors.kt:35`) + `RttDominanceGuard.kt` (`aneb_loaded_01a1facb-fc26-7f6e-87e3-12288dae9ca3.json` [`1,444,973 B`]: `idleP50Ms = 37.2405`, `downloadP95Ms = 224.5912`, `uploadP95Ms = 1027.5585`, `rttDominanceOk = true`, `rttDominanceRatio = 268.5249`); (3) `NegotiatedProtocolLog.kt` (`aneb_01a1face_20261121_051554.json`: `138/138` `http/1.1`, `HTTP/1.1;via=tcp`, `header_absent = 0`); (4) `ResultAqsBreakdown.kt` + `AiScenarioAdvisor.kt`. Also **`aneb-profile-v2`** (`ProfileCapability.kt` `TYPE_BEHAVIOR_TRACE`, `tools/aneb-ai-behavior-model/` PCG32 + 3-state Markov, `20/20` tasks, `computable=true`, **`score = 89.4`**, `TOK-B01 = 1.00`, `TOK-B11 = 1.00`, `TOK-N03 = 40.7 ms`), `PairedComparisonFormat.kt` (`7f09c23a`), and **`ApiProbe` -> `Settings -> Advanced`** (Issue #1, 4 bottom tabs `测试 / 结果 / 地图 / 设置`). **Stage 2 RC APKs** (`SIGNED_STAGE2_RC_RECEIPT.json`): `probe-release.apk` (`53,248,792 B`, SHA `bc0ce920...`) and `probe-prototypeRelease.apk` (`53,248,798 B`, SHA `1bde8020...`), `apksigner` `aneb-production` (`b33df25f...6003`). |
| **M4 (Stage 3)** | `6ff68432bf38bf6614a524171c6c281f1f1ab2cc` (PR #118) | `tools/miw2-monitor/check_miw2.py` (`811` lines, 6 live invariants: `<48h` TLS cert, `serverinfo`, `profiles`, `echo`, `/results` `HTTP 503`, `/realtime-sim` `101`) + `.github/workflows/miw2-monitor.yml` (daily `17 2 * * *`); `server/cross_lineage_parity_test.go` (`831` lines, 7-endpoint + tree parity between `release/miw2-server-0.8` and `product/prototype-0.1`); `main` aligned; `docs/SERVER_LINEAGE_BOUNDARY.md`; `docs/NODE_E02_EVALUATION_AND_PLAN.md` (**planning only — not provisioned**; flags `ReachabilityProbe.deriveE01Pair` and `VoiceResultEntity` missing `serverBase` pre-launch fixes); `docs/COLD_BACKUP_AND_DISK_GOVERNANCE.md` (`E: > 40 GiB` floor, 10 NTFS junctions inventoried, `E:\ANEB-Secure-Backup` metadata/SHA-256 baseline & DPAPI SOP). |

### 7.2 Production Server `miw2` `0.8.4` & Lineage Boundary

- `miw2-live-preflight-and-e2e-20261120/PREFLIGHT_AND_LIVE_RECEIPT.md` verified legacy `120.79.148.0` retired `2026-09-25` (`120-79-148-0.sslip.io` expired `2026-10-11`) and `miw2` E2E voice continuity (`detect_ms = 0.866`, `resume_ms = 474.212`).
- `miw2-deploy-0.8.4-20261120/DEPLOY_084_RECEIPT.md` upgraded `miw2` (`https://120.77.174.207:8443`) from `0.8.3` (`55554d0e`) to `0.8.4` (`96104a8a`, `12,983,897 B`, SHA `1d530101...`), enforcing TCP `TLSNextProto` HTTP/1.1 pinning, `/api/v1/artifact_stream`, high-fidelity `tokengen`, and `HTTP 503` on `/api/v1/results`, verified by zero-`--es` P40 Pro test (`27.92 Mbps` DL / `20.95 Mbps` UL / `49.68 ms` ping). `product/prototype-0.1/server` (`0.1.0`) is strictly restricted to local/prototype use.

---

## 8. Cross-Domain Synthesis & Actionable Implications for Pilot v0.2 and Node-E02

1. **Why Single-Metric Ping / Speed Tests Fail Agentic & Real-Time AI Workloads**:
   - **Short Video (`Douyin`)** hits a hard throughput cliff in `(1.000, 1.500] Mbps` and mid-clip buffer stall at `1.500 Mbps`, yet tolerates `200 ms` RTT + `5%` loss at `4.0 Mbps`.
   - **Streaming AI Chat (`Doubao`)** amplifies packet counts (`2.47×`) at `1.0 Mbps + 200 ms + 3%` and suffers total token starvation (`TTFR > 26s`) below `~256–1000 kbps` when competing background flows saturate bounded queues.
   - **Real-Time Voice (`Profile 4` & `W1 vs C1`)** exhibits a `3.23×` field inversion where low-RTT hotel Wi-Fi (`47.6 ms`) trails 5G NR (`89.6 ms`) by `+487.5 ms` on `M1'` due to an empirical `300 ms` silent-turn penalty, and fails RTC handshake (`0/3` turns) under `128 kbps` asymmetric weak uplink (`B2`, `21.77%` UL drop) where downlink-only `M7`/`M2'` look completely healthy.
2. **Immediate Guidance for `Pilot v0.2` Distribution (Issue #61)**:
   - Instruct operators/testers via `docs/pilot-readiness/PILOT_V02_QUICKSTART_ZH.md` to run `PairedComparisonCard` across Wi-Fi vs 5G NR (capturing both Token AQS and Voice `M1'`/`M3` instead of relying on speed alone), verify `EnvMonitors` (`thermal_polluting_event_count == 0`) and `NegotiatedProtocolLog` (`http/1.1` / `h2` / `h3`) hygiene, and verify every extracted kit offline via `verify-pilot-v0.2-kit.ps1`.
   - Keep `probe-release.apk` (`miw2` HTTPS) and `probe-prototypeRelease.apk` (LAN `prototype-launcher`) clearly separated (`adb install -r` switch rule under same `com.aneb.probe` package & `b33df25f...6003` signing cert), and preserve all `AGENTS.md` pre/post-run device cleanups.
3. **Actionable Roadmap for `Node-E02` & `aqs-voice-sim-v0.3`**:
   - **`Node-E02` Provisioning Prerequisites**: Build strictly from `release/miw2-server-0.8` (guarded by `server/cross_lineage_parity_test.go`), deploy on a non-Aliyun mainland region with BGP multi-line IPv4 and automated `<48h` ACME IP-SAN monitoring, and fix `ReachabilityProbe.deriveE01Pair` + `VoiceResultEntity` `serverBase` before adding `Node-E02` to `ProbeNodeCatalog.kt`.
   - **Future `aqs-voice-sim-v0.3` Calibration**: Keep `aqs-voice-sim-v0.2` untouched for backward compatibility (`13/13` rank stability already holds), and in a future additive `v0.3` investigate higher `M3` weight (`0.08 -> 0.12`) plus explicit `WebSocket/TLS Handshake Setup Latency` reporting to mirror real WebRTC `"连接中..."` setup cliffs on weak uplinks.

---

## 9. Reproducible Artifact Index, Discrepancy Log & Verification Commands

### 9.1 Primary Discrepancies Reconciled in This Report

1. **`INFLECTION_REPORT.md` 9-Card Scope & `v41.0.1` Typo**: Integrated all **10** Douyin cards (adding `4mbps-200ms-5pct-410101-20261120-r3`) and verified `versionName = 41.1.0` (`versionCode = 410101`).
2. **Stale `result.json = 250000`**: Noted `1M-r4` and `1.5M-r2` `device-original\result.json` stale values vs SHA-256-pinned `request.json` (`125000` / `187500`); `1.5M-r3` and `4M+200ms+5%-r3` match across all files.
3. **`DOUBAO_RB_SUMMARY.json` `pass` Flag (`r7` vs `r5`)**: Documented why `r7` (`TTL_EXPIRED`, `Unconfirmed=0`) shows `pass=false` (3 TTL-expiry tail packets `940 B` vs `CleanupInjectedPackets -eq 0`) while legacy `r5` shows `pass=true` despite `USER_QUEUE_OVERFLOW`.
4. **SI vs IEC Units & KimiChat Attribution**: Stated exact byte counts (`KiB = 1024 B` for queue bounds) and explicitly attributed KimiChat `v3.1.8 (versionCode = 388)` / `去登录` to `DOUBAO_RB_REPORT.md:6` alongside `kimi-controlled-rb-20261120-r3` raw `ComposeView` JSONs.
5. **`TCP_NODELAY` `M3` Factor, PSM Hypothesis & `EnvMonitors` `replay = 8`**: Reported exact `M3` reductions (`16.64×` `id=2->5`, `13.49×` `id=3->5`, `23.15×` RC v2 vs `id=2`, and `3.45×` `C1`) rather than ungrounded upper-bound figures, labeled 802.11 PSM/contention as a physical-layer hypothesis for the `300 ms` silent-turn penalty, cited `EnvMonitors.kt:35` `replay = 8`, cited exact `profile4-voice-correlation-20261120/{analysis,runtime-sim-r2,runtime-real-r2}` filenames, and distinguished RC v2 vs Stage 2 RC APK byte counts.

### 9.2 Artifact Index & Verification Commands

- **Repo Artifacts**:
  - `docs/research/PROFILE4_VOICE_CORRELATION_REPORT.md`, `docs/SERVER_LINEAGE_BOUNDARY.md`, `docs/NODE_E02_EVALUATION_AND_PLAN.md`, `docs/COLD_BACKUP_AND_DISK_GOVERNANCE.md`
  - `tools/phone-forward-condition/`, `tools/controlled-rb-card/new_card.py`, `tools/business-video-review/correlate_voice_rb.py`, `tools/miw2-monitor/check_miw2.py`, `server/cross_lineage_parity_test.go`
  - Key commits: `1165320f` (`TcpNoDelaySocketFactory`), `befa87b8` (M1 / PR #115), `bb134903` (M2 / PR #116), `6ce3e883` (M3 / PR #117), `6ff68432` (M4 / PR #118)
- **Immutable External Directories** (under `E:\G Project\ANEB\Archives\ExternalRoots\ANEB-Research\Business-Profile-Baseline-20261024\`):
  - Douyin 10 cards + `douyin-gradient-rb-410101-20261120-summary\` + `douyin-controlled-rb-4mbps-200ms-5pct-410101-20261120-r3-independent-review\`
  - Doubao `doubao-controlled-rb-20261120-{r5,r6,r7,r8}` + `doubao-controlled-rb-256kbps-15080040-20261120{,-review}`
  - KimiChat `kimi-controlled-rb-20261120{,-r2,-r3}`
  - Field & RC receipts: `p2-miw2-w1-c1-paired-20261120`, `p3-signed-pilot-rc-v2-20261120`, `p3-signed-stage2-rc-20261120`, `miw2-live-preflight-and-e2e-20261120`, `miw2-deploy-0.8.4-20261120`, `phone-calibration-winrt-bidir-20261120-r4`, `profile4-voice-correlation-20261120`
- **Offline Re-Verification Commands**:
  ```powershell
  python -B -m unittest tests.test_controlled_rb_card_templates tests.test_controlled_rb_card_generator tests.test_correlate_voice_rb tests.test_miw2_monitor -v
  go -C server test -count=1 ./...
  go -C tools/phone-forward-condition test -count=1 ./...
  ```
