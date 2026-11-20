# Profile 4 Synthetic Voice (`VoiceRunner.runSim`) vs Real Voice App (`com.larus.nova`) Controlled Correlation Report

- **Date**: 2026-11-20 UTC (`profile4-voice-correlation-20261120`)
- **Workspace Commit (Base)**: `9d3609f50d1d6d571ef5bcb57aa0bb4707dbb91f` (`codex/business-video-baseline-1024`)
- **Immutable Evidence Root**: `E:\G Project\ANEB\Archives\ExternalRoots\ANEB-Research\Business-Profile-Baseline-20261024\profile4-voice-correlation-20261120\`
- **Analysis Tool & Outputs**:
  - `tools/business-video-review/correlate_voice_rb.py` (pure Python 3.12+ stdlib)
  - `profile4-voice-correlation-20261120\analysis\voice-correlation-summary.json` (`voice-correlation-summary-1`)
  - `profile4-voice-correlation-20261120\analysis\voice-correlation-table.md`

---

## 1. Test Topology, Controlled Matrix & Claim-Scope Boundary

### 1.1 Fixed Topology & Instrumentation
All 6 paired phases (`3` synthetic + `3` real-app) ran sequentially on the same physical path:
- **Phone**: Huawei P40 Pro (`8MY0221126002537`, `ELS-AN00`, Android 12 / API 31) connected over 5 GHz Wi-Fi to host WinRT Mobile Hotspot (`ifIndex 16`, adapter GUID `7213c2a9-63f1-4f03-971e-ef715be25db6`, phone IP `192.168.137.113/24`).
- **Host Gateway & Shaper**: Windows host Ethernet uplink (`ifIndex 12`, adapter GUID `4f832b1f-4bfd-4866-8f3e-ad0c6e8c2b5d`, NAT IP `10.10.8.9`) running **`phone-forward-condition v4`** (`tools/phone-forward-condition` compiled from `9d3609f5`, `phone-condition.exe` SHA-256 `63c1bb17e5e2885817a92062032e6b4df397bcad3dcbbe44134cc63908c7ee79`) with `WinDivert-2.2.2-A/x64` (`ttl_seconds = 115`). Every `request.json` passed `phone-condition.exe plan -config` prior to driver load.
- **Synthetic Arm (`VoiceRunner.runSim`)**: Debug instrumentation `Profile4VoiceCorrelationInstrumentation#runVoiceSimPhase` (`probe-debug.apk` SHA-256 `999248c4...`) pinned to `miw2` (`https://120.77.174.207:8443`, `aneb-server/0.8.4`) over explicit `BoundNetwork` (`wlan0`, `!VPN`, `VALIDATED`, `NO_PROXY`) + `PathMonitor`, invoking `VoiceRunner(AnebClient(bound)).runSim(...)` directly (fail-closed, zero fallback) with a `105s` deadline and exporting canonical `voice-export-1.0` + `aqs-voice-sim-v0.2` in-memory.
- **Real Voice App Arm (`com.larus.nova` `15.8.0` / `versionCode=15080040`)**: Debug instrumentation `DoubaoVoiceCallInstrumentation#observeVoiceCallPhase` capturing 30 fps `MediaProjection` (`video.mp4` verified by `MediaExtractor` `FINAL_MEDIA.json`) and 100 ms Accessibility UI timeline (`ui_timeline.json`) during a `62s` `RealtimeCallActivity` voice session (camera disabled via `voice_call_video` toggle so `B2`'s 128 kbps uplink isolates pure voice + RTC signaling), driven by a fixed 3-prompt Chinese SAPI (`Microsoft Huihui Desktop`) acoustic loop (`p1` at `1.5s`, `p2` at `21.5s`, `p3` at `41.5s`).

### 1.2 Controlled `phone-forward-condition v4` Parameter Matrix (`R`, `B1`, `B2`)

| Condition | Mode | Downlink Rate / Delay / Loss | Uplink Rate / Delay / Loss | Queue Policy (`queue_limit_bytes`) | Added RTT Delta |
|---|---|---|---|---|---|
| **`R`** (`inspect`) | `inspect` | Unshaped (sniff `250000 B/s`) / `0 ms` / `0%` | Unshaped (sniff `250000 B/s`, NAT `10.10.8.9`) / `0 ms` / `0%` | Omitted | `+0 ms` |
| **`B1`** (`sym 256k + 120ms + 2%`) | `limit` | `32000 B/s` (`256 kbps`) / `60 ms` / `200 bps` (`2%`, seed `20261120`) | `32000 B/s` (`256 kbps`) / `60 ms` / `200 bps` (`2%`, seed `20261121`) | `tail_drop` (`262144`) | `+120 ms` |
| **`B2`** (`asym weak UL 128k + 200ms + 5%`) | `limit` | `250000 B/s` (`2.0 Mbps`) / `50 ms` / `0 bps` (`0%`) | `16000 B/s` (`128 kbps`) / `150 ms` / `500 bps` (`5%`, seed `20261121`) | `tail_drop` (`262144`) | `+200 ms` |

### 1.3 `AGENTS.md` Clean-State & Packet Conservation Proof
- **Pre-run & Post-run Clean State (`analysis/agents-clean-state-receipt.json`)**: `adb get-state == device`, `UniHomeLauncher` focused, zero residual `aneb`/`larus`/`doubao`/VPN/capture processes or services, zero `tun0`/`ppp`/`wg` tunnels, `stay_on_while_plugged_in == 0`, `Media Projection: null`, `sc.exe query WinDivert -> 1060` (unloaded), host speaker restored muted (`vol=0.48, muted=True`), and Windows Mobile Hotspot `Off`.
- **Shaper Conservation (`UnconfirmedUserPackets == 0` aggregate, `Downlink`, and `Uplink` across all 6 runs)**:
  - **Synthetic**: `R` (`1459 DL / 1459 UL`, `0` drops); `B1` (`1380 = 1355 + 25 DL`, `1487 = 1463 + 24 UL`); `B2` (`1045 = 1045 + 0 DL`, `784 = 751 + 33 UL`).
  - **Real App**: `R` (`6899 DL / 7257 UL`, `0` drops); `B1` (`13825 = 12660 + 248 loss + 917 tail_drop DL`, `14844 = 14578 + 266 loss UL`); `B2` (`11758 = 11758 + 0 DL`, **`13508 = 10567 + 526 loss + 2415 tail_drop UL`**).

### 1.4 Honest Claim-Scope & Statistical Power Boundary
- **Claim Scope**: Synthetic metrics measure `application_end_to_end_to_probe_node_voice_observation` over WebSocket `/api/v1/realtime-sim` (`miw2`); real-app observables measure UI call-state & response transitions of `com.larus.nova` (`15080040`) over the identical host NAT + WinDivert shaper topology. Neither claims acoustic MOS, radio-layer KPIs, or carrier-wide SLA conclusions.
- **Statistical Power ($N=3$)**: With $N=3$ ordered conditions (`R`, `B1`, `B2`), there are $3! = 6$ permutations, so the minimum achievable two-sided permutation $p$-value is $2/6 = 0.333$. Rank correlations (`Spearman rho`, `Kendall tau-b`) evaluate **directional monotonic consistency and structural sensitivity**, not frequentist $p < 0.05$ significance.

---

## 2. Paired Measurements (`R`, `B1`, `B2`)

### 2.1 Synthetic `VoiceRunner.runSim()` & `AQS_v02` (`WEIGHTS_VOICE_SIM_V02`)

| Cond | `N1` RTT (ms) | `N2` Jitter (ms) | `M3` UL Jitter (ms) | `M2'` DL Jitter (ms) | `M4` TTFB P50 / P95 (ms) | `M1'` Proxy (ms) | `M5` Switch (ms) | `M6` Barge (ms) | `M7` MaxGap (ms) | NearZero | `AQS_v02` (Raw) | `M1 > 400` Veto |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| **`R`** | 63.61 | 8.50 | 7.14 | 18.93 | 210.37 / 358.67 | 291.87 | 225.00 | 40.56 | 49.49 | 0.0248 | **78.76** (78.76) | `false` |
| **`B1`** | 213.59 | 4.47 | 20.00 | 19.55 | 353.59 / 923.71 | 435.61 | 374.82 | 157.21 | 234.39 | 0.0835 | **54.00** (63.84) | **`true`** |
| **`B2`** | 541.70 | 7.43 | 158.78 | 18.28 | 734.96 / 1834.41 | 816.38 | 657.38 | 240.53 | 74.99 | 0.0241 | **47.20** (47.20) | **`true`** |

*(All 3 runs completed `8/8` turns, `low_confidence = false`, `caliber = "server-sim(aneb-realtime-session-v1)"`.)*

### 2.2 Real Voice App (`com.larus.nova` `15.8.0`) Observables

| Cond | Status | `turns_completed` | `connect_ready_ms` | `p3_response_onset_ms` | `timeline_event_count` | Shaper DL Drop Ratio | Shaper UL Drop Ratio | Observed Behavior (`ui_timeline.json` + `call-end.png`) |
|---|---|---:|---:|---:|---:|---:|---:|---|
| **`R`** | `COMPLETED_3_OF_3` | **3 / 3** (`1.000`) | 374.8 | 1243.7 | 10 | 0.000 | 0.000 | Immediate ready; all 3 prompts transcribed & answered cleanly. |
| **`B1`** | `DEGRADED_CONNECT_2_OF_3` | **2 / 3** (`0.667`) | 9685.1 | 7411.6 | 6 | 0.084 (`248+917`) | 0.018 (`266`) | Stuck `"连接中..."` for `9.69s` (missing `p1`); `p2` & `p3` answered with heavy delay; `917` DL `tail_drop` packets. |
| **`B2`** | `STARVED_CONNECTING_0_OF_3` | **0 / 3** (`0.000`) | `None` (`>62000`) | `None` | 1 | 0.000 | **0.218** (`526+2415`) | Stuck `"连接中..."` for entire `62s` window; `2,415` UL `tail_drop` + `526` UL loss starved RTC handshake. |

---

## 3. Correlation, Directional Ratios & Asymmetric vs Symmetric Insights

### 3.1 Monotonic Rank Consistency (`Spearman rho` / `Kendall tau-b`)
- **Synthetic `AQS_v02` vs Condition Severity (`R=0, B1=1, B2=2`)**: `rho = -1.000`, `tau-b = -1.000` (`78.76 -> 54.00 -> 47.20`).
- **Synthetic `AQS_v02` vs Real App `turns_completed_ratio` (`1.0 -> 0.667 -> 0.0`)**: **`rho = +1.000`, `tau-b = +1.000`**.
- **Synthetic `AQS_v02` vs Real App `connect_ready_or_cap_ms` (`374.8 -> 9685.1 -> 62000.0`)**: **`rho = -1.000`, `tau-b = -1.000`**.
- **Individual Synthetic KPIs achieving perfect monotonic agreement (`|rho| = 1.000`, `|tau-b| = 1.000`) with Real App degradation (`turns_completed_ratio` & `connect_ready_or_cap_ms`)**:
  - `N1` (`rtt_ms`: `63.61 -> 213.59 -> 541.70`)
  - `M1'` (`mouth_ear_proxy_ms`: `291.87 -> 435.61 -> 816.38`)
  - `M3` (`up_frame_jitter_ms`: `7.14 -> 20.00 -> 158.78`)
  - `M4` (`ttfb_p50_ms`: `210.37 -> 353.59 -> 734.96`; `ttfb_p95_ms`: `358.67 -> 923.71 -> 1834.41`)
  - `M5` (`turn_switch_p50_ms`: `225.00 -> 374.82 -> 657.38`)
  - `M6` (`barge_stop_max_ms`: `40.56 -> 157.21 -> 240.53`)
- **Non-monotonic KPIs across `B1` vs `B2` (explained by directional asymmetry)**:
  - `M7` (`m7_max_frame_gap_ms`: `49.49 -> 234.39 -> 74.99`, `rho = 0.500` vs severity) and `M2'` (`down_net_jitter_ms`: `18.93 -> 19.55 -> 18.28`) peaked in **`B1`** rather than **`B2`** (`B2/B1 = 0.32x` on `M7`), because `B1` imposed `256 kbps + 2%` loss on the **downlink** whereas `B2` kept the downlink clean (`2.0 Mbps + 0%` loss) and stressed only the **uplink** (`128 kbps + 5%` loss).

### 3.2 Directional Degradation Ratios (`B1/R`, `B2/R`, `B2/B1`)

| Metric | `R` | `B1 / R` | `B2 / R` | `B2 / B1` (Asym UL vs Sym) |
|---|---:|---:|---:|---:|
| `N1` RTT (ms) | 63.61 | `3.36x` (+150.0 ms) | `8.52x` (+478.1 ms) | `2.54x` |
| `M3` UL Frame Jitter (ms) | 7.14 | `2.80x` (+12.9 ms) | **`22.23x`** (+151.6 ms) | **`7.94x`** |
| `M4` TTFB P50 (ms) | 210.37 | `1.68x` (+143.2 ms) | **`3.49x`** (+524.6 ms) | **`2.08x`** |
| `M1'` Mouth-Ear Proxy (ms) | 291.87 | `1.49x` (+143.7 ms) | **`2.80x`** (+524.5 ms) | **`1.87x`** |
| `M7` Max Frame Gap (ms) | 49.49 | **`4.74x`** (+184.9 ms) | `1.52x` (+25.5 ms) | `0.32x` |
| `AQS_v02` Score | 78.76 | `0.69x` (-24.76 pts) | `0.60x` (-31.56 pts) | `0.87x` |
| Real App `connect_ready_or_cap_ms` | 374.8 | `25.84x` (+9.31 s) | `>165.4x` (starved `>62s`) | `>6.40x` |
| Real App `turns_completed_ratio` | 1.000 | `0.67x` (2/3) | **`0.00x`** (0/3) | `0.00x` |

---

## 4. Implications for `WEIGHTS_VOICE_SIM_V02` (`AqsScorer.kt`)

1. **`M1' > 400 ms` Hard Veto (`T4_VETO_CAP = 54.0`) Validated**:
   - Without the veto (`no_veto_diagnostic`), `B1` scores `63.84` (which would sit close to "Good/Fair") even though the real voice app suffered a `9.69 s` `"连接中..."` connect stall and lost Turn 1 (`2/3` turns). With the `M1' > 400 ms` veto (`B1 M1' = 435.61 ms`), `B1` is capped to **`54.00` (Poor)**, accurately separating usable `R` (`78.76`, `3/3` turns) from degraded `B1` (`54.00`, `2/3` turns) and starved `B2` (`47.20`, `0/3` turns).
2. **Weight Sensitivity Stability (`13/13` Variants Preserve `R > B1 > B2`)**:
   - Across `baseline_v02`, `no_veto_diagnostic`, two uplink-emphasized variants (`uplink_emphasized_v1`, `latency_uplink_v2`), and all 9 leave-one-KPI-out (`loo_without_*`) renormalizations, the best-to-worst condition order **`R > B1 > B2` is 100% invariant**.
   - Increasing uplink weight (`M3` `0.08 -> 0.15..0.16`, `M4` `0.15 -> 0.20`) widens the `B1`–`B2` separation (`47.20 -> 45.74` in `uplink_emphasized_v1`, `43.29` in `latency_uplink_v2`) without affecting `R` (`78.76 -> 79.40 / 78.55`).
3. **Asymmetric Uplink Blind Spot in Downlink-Only Metrics (`M2'`, `M7`) & Real-App RTC Cliff**:
   - `M7` (`400 -> 40.0` provisional anchor) and `M2'` are purely downlink-arrival metrics: they diagnose downlink congestion/loss (`B1 M7 = 234.39 ms`) well, but stay healthy (`B2 M7 = 74.99 ms`, `M2' = 18.28 ms`) when the bottleneck is purely uplink (`B2`). Under asymmetric weak uplink, **`M3` (`0.08` weight, `22.23x` degradation)** and **`M4`/`M1'` (`0.15`/`0.20` weight)** carry the diagnostic signal.
   - Real WebRTC/RTC voice (`com.larus.nova`) failed completely (`"连接中..."` for `>62s`, `2,415` UL `tail_drop` packets on `13,508` UL packets) at `128 kbps` uplink where `VoiceRunner.runSim()`'s lean WebSocket (`64 kbps` frames, `784` UL packets) still finished `8/8` turns. That confirms real RTC call signaling + audio needs a higher uplink headroom margin than raw 64 kbps Opus frames.
4. **Actionable Recommendation (Stage 2 / Stage 4)**:
   - **Keep `WEIGHTS_VOICE_SIM_V02` and `M1' > 400 ms` veto unchanged** in `AqsScorer.kt` for `aqs-voice-sim-v0.2` (backward compatibility & `AqsScorerVoiceTest` guards remain intact, and rank ordering `R > B1 > B2` is already 100% consistent).
   - For a future additive `aqs-voice-sim-v0.3` calibration after broader multi-app sampling, consider (a) modestly increasing `M3` weight (`0.08 -> 0.12`, balancing `N2`/`M2`) so asymmetric weak uplinks penalize un-vetoed scores even below `M1'=400 ms`, and (b) reporting `WebSocket/TLS Handshake Setup Latency` alongside `M4` as an explicit proxy for RTC `"连接中..."` call-setup risk.

---

## 5. Reproducible Artifact Index
- **Immutable Live Evidence**: `E:\G Project\ANEB\Archives\ExternalRoots\ANEB-Research\Business-Profile-Baseline-20261024\profile4-voice-correlation-20261120\`
  - Synthetic runs (`VoiceRunner.runSim` vs `miw2`): `runtime-sim-r2\phase-{R,B1,B2}\{request.json,plan-check.json,phone-raw.json,helper-exit.json,driver-cleanup.json,inspect\supervisor.json}` & `runtime-sim-r2\CAMPAIGN_RESULT.json`
  - Real Voice App runs (`com.larus.nova` `15080040`): `runtime-real-r2\phase-{R,B1,B2}\{request.json,READY.json,raw.json,ui_timeline.json,host_audio_events.json,host_mic.wav,video.mp4,FINAL_MEDIA.json,call-ready.png,call-end.png,inspect\supervisor.json}` & `runtime-real-r2\CAMPAIGN_RESULT.json`
  - Derived correlation outputs & clean-state receipt: `analysis\{voice-correlation-input.json,voice-correlation-summary.json,voice-correlation-table.md,agents-clean-state-receipt.json}`
- **Repo Files**:
  - `app/probe/src/debug/java/com/aneb/probe/engineering/Profile4VoiceCorrelationContract.kt`
  - `app/probe/src/androidTest/java/com/aneb/probe/engineering/Profile4VoiceCorrelationInstrumentation.kt`
  - `app/probe/src/androidTest/java/com/aneb/probe/research/business/DoubaoVoiceCallInstrumentation.kt`
  - `app/probe/src/testDebug/java/com/aneb/probe/engineering/Profile4VoiceCorrelationContractTest.kt`
  - `tools/business-video-review/correlate_voice_rb.py`
  - `tests/test_correlate_voice_rb.py`
  - `.github/workflows/ci.yml` (`tests.test_correlate_voice_rb` registered in `behavior-model`)
