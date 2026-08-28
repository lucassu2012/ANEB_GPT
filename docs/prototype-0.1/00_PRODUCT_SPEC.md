# 00 — Product Specification

Status: **Draft for G0 approval**  
Owner: Program control conversation / issue #13  
Approved product direction: 2026-08-28

## 1. Objective

Deliver a usable ANEB research prototype that a non-developer can run with:

- one Windows PC;
- one Huawei P40 Pro;
- the packaged ANEB Android APK;
- a local, self-hosted ANEB node;
- no source-tree edits and no commercial infrastructure.

A successful user can execute a deterministic streaming-text campaign under three synthetic application-layer conditions and receive a complete evidence bundle plus an offline report.

## 2. Target user

The primary user is a researcher or network-experience engineer who wants to verify the ANEB measurement loop before investing in full real-App testing, IP-layer impairment, broad device coverage or standards calibration.

The user is allowed to perform two manual setup actions:

1. install the provided APK on the P40 Pro;
2. enter or select the local ANEB node URL in the app.

Manual phone unlock and the normal Windows firewall prompt are acceptable. Editing code, installing build tools or configuring a Windows hotspot is not.

## 3. Required end-to-end outcome

```text
Unzip fixed release package
  -> run START_ANEB.bat
  -> launcher passes preflight and starts local node
  -> install/open ANEB Prototype APK
  -> enter/select local node URL
  -> app validates exact capability contract
  -> run Quick or Acceptance campaign
  -> observe live progress for Baseline / Slow / Unstable
  -> save and export evidence
  -> open offline report.html
```

## 4. Must-have scope

### 4.1 Runtime

- Native Android app using the existing ANEB_GPT Kotlin/Compose product.
- Existing Go self-hosted probe node extended for Prototype 0.1.
- Windows package with a single launcher entry point.
- Local LAN path as the normal connection method.
- ADB reverse may exist as a clearly labeled functional fallback, but it is not the normal acceptance path.

### 4.2 Campaign

- One workload: `streaming_text_reference_v0.1`.
- Three deterministic conditions:
  - `baseline_v0.1`;
  - `slow_v0.1`;
  - `unstable_v0.1`.
- Quick mode: one run per condition.
- Acceptance mode: three runs per condition in the fixed order specified by the workload contract.
- Cancellation, server-unavailable and interrupted-stream states.

### 4.3 Measurements

- task success;
- time to first stream event (TTFT proxy);
- completion time;
- stream event rate;
- stall count;
- stall duration;
- condition success rate;
- confidence;
- Relative Prototype Index `RPI-0.1`.

All metrics must use exact clock boundaries from `03_METRICS_SCORING_SPEC.md`.

### 4.4 Evidence

Every completed or partial campaign retains raw evidence and publishes the artifacts defined by `04_EVIDENCE_REPORT_SPEC.md`. Failed or invalid runs must not be silently discarded.

### 4.5 User experience

- explicit Prototype Mode;
- persistent synthetic-condition disclosure;
- live condition/run progress;
- cancellable foreground execution;
- clear result comparison;
- friendly actionable errors;
- no fake values, mock timers or hard-coded production results.

## 5. Non-goals

Prototype 0.1 does **not** include:

- automated testing of Doubao, Kimi, ChatGPT or any third-party App;
- third-party LLM API calls in the formal flow;
- IP-layer packet loss, tc/netem, traffic control, packet injection or RAN emulation;
- automatic Windows hotspot setup;
- administrator rights as a normal prerequisite;
- mandatory PCAP;
- VPN traffic observation, TLS decryption or Accessibility automation;
- Play Integrity;
- real-device CI;
- multi-phone or iOS support;
- cloud accounts, subscriptions, payments or commercial deployment;
- industry-standard thresholds or operator-wide claims;
- the historical 720-run research matrix.

These exclusions are deliberate acceleration decisions, not forgotten work.

## 6. Claim boundary

The existing ANEB claim scope remains:

`application_end_to_end_to_probe_node`

Prototype results must additionally carry:

- `evidence_mode = synthetic_application_impairment`;
- `impairment_layer = application`;
- user-facing badge: `Synthetic application path` / `应用层合成条件`.

The prototype must never describe its conditions as physical packet loss, radio impairment, operator SLA evidence, real vendor performance or model-quality measurement.

## 7. Product success criteria

Prototype 0.1 is complete only when all are true:

1. A fixed release candidate is identified by immutable hashes.
2. A fresh Windows directory launches without source edits.
3. The P40 Pro performs a fresh install and connects through the declared normal path.
4. Quick mode completes all three conditions.
5. Acceptance mode completes nine planned runs.
6. The result bundle contains all mandatory files.
7. Machine-readable summaries and `report.html` agree.
8. Missing values remain `null` and render unavailable.
9. Negative paths produce explicit failed/partial states without scores based on incompatible evidence.
10. The repository quality gate passes.
11. Issue #18 publishes a final P40 verdict of `PASS`.
12. The Product Owner accepts the release candidate under issue #13.

## 8. Priority rule

When schedule or complexity conflicts with scope, preserve this order:

1. repeatable end-to-end completion;
2. measurement and evidence correctness;
3. clear error handling;
4. packaging and installability;
5. visual polish;
6. optional pairing convenience.

QR pairing, expanded charts and additional conditions may be removed without Product Owner escalation if the binding end-to-end outcome remains intact. Metrics, score semantics, evidence files and claim boundaries may not.
