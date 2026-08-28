# 01 — Architecture Specification

Status: **Draft for G0 approval**  
Primary issues: #15, #16, #17

## 1. Architecture principle

Prototype 0.1 extends the existing ANEB_GPT monorepo. It must not introduce a second Android application, a second scoring truth source or a separate controller product.

```text
Windows release package
┌──────────────────────────────────────────────────────────────┐
│ START_ANEB.bat                                               │
│   ├─ doctor/preflight                                        │
│   ├─ aneb-server.exe                                         │
│   ├─ local report/evidence publisher                         │
│   └─ release manifest                                        │
└──────────────────────┬───────────────────────────────────────┘
                       │ HTTP on declared local path
                       │ application_end_to_end_to_probe_node
┌──────────────────────▼───────────────────────────────────────┐
│ Huawei P40 Pro — existing native ANEB Android app             │
│   ├─ Prototype Mode                                           │
│   ├─ capability/profile validation                            │
│   ├─ campaign runner in foreground service                    │
│   ├─ monotonic event measurement                              │
│   ├─ Room/raw evidence retention                              │
│   └─ export/share                                             │
└──────────────────────────────────────────────────────────────┘
```

## 2. Component responsibilities

### 2.1 Windows launcher

The launcher owns setup and lifecycle only. It must:

- resolve paths relative to its own directory;
- verify package manifest and required files;
- verify output-directory writability;
- detect whether the configured port is available;
- determine and display candidate LAN URLs;
- start the exact packaged Go binary;
- poll health and capability endpoints before declaring READY;
- display concise connection and shutdown instructions;
- stop the owned server process cleanly;
- retain sanitized operational logs.

The launcher must not calculate client metrics, modify scoring rules or require a source checkout.

### 2.2 Go ANEB node

The Go node is the sole authority for:

- workload and condition capability advertisement;
- deterministic planned event schedules;
- SSE/event-stream generation;
- application-layer initial delay, pacing and scheduled stalls;
- server-side run receipt and schedule hash;
- collection of uploaded client evidence;
- campaign export assembly and offline report generation, unless export is performed in the Android app using the same schema.

One implementation must be chosen for final evidence publication; Android and server must not independently publish conflicting summaries. The recommended ownership is:

- Android: raw event capture and per-run metric calculation;
- server: receipt binding, bundle assembly, manifest and HTML report;
- Android local export: fallback copy of the same canonical JSON/CSV records.

### 2.3 Android app

The existing Android app remains the primary UI and measurement client. It owns:

- node URL configuration and persistence;
- capability handshake and exact contract validation;
- campaign orchestration;
- monotonic arrival timestamps;
- SSE sequence and terminal receipt validation;
- per-run metric calculation;
- condition aggregation and `RPI-0.1` calculation;
- foreground execution, cancellation and resume state;
- local raw evidence retention;
- upload/export of canonical evidence records;
- user-facing result and error states.

It must reuse current Compose, Room and foreground-service architecture. Prototype Mode is an isolated mode and must not silently change existing Basic, Token, Continuity, Protocol A/B or AQS behavior.

## 3. Runtime topology

### 3.1 Normal path

- PC and P40 are on the same local network.
- Server binds to an explicit configurable port; recommended default: `18088`.
- Launcher displays an IPv4 URL such as `http://192.168.1.20:18088`.
- The user enters or selects this URL in Prototype Mode.
- Cleartext local HTTP may be permitted only for explicitly configured local/private addresses in the Prototype build. Production/release security behavior outside Prototype Mode remains unchanged.

### 3.2 Functional fallback

ADB reverse may expose the local node at `http://127.0.0.1:<port>` for development or recovery. Runs using this path must record:

- `transport_mode = adb_reverse`;
- `acceptance_path = false`.

ADB reverse evidence can prove function but cannot be used as the normal P40 release acceptance path.

### 3.3 Cloud node

The existing E-01 node may be used for regression, but Prototype 0.1 completion must not depend on cloud credentials, public CA, UDP 8443 or external infrastructure.

## 4. Contract handshake

Before sending business traffic, Android calls a capability endpoint and validates at least:

- product protocol version;
- workload id/version/hash;
- condition ids/versions/hashes;
- evidence schema version;
- score policy id;
- server build version and binary identity;
- supported terminal receipt version;
- `claim_scope` and `evidence_mode`.

Unknown, missing or incompatible mandatory values fail closed. The app may show diagnostics but must not execute or score the campaign.

## 5. Run protocol

Each run has a unique `run_id` and belongs to one `campaign_id`.

```text
client creates run request
  -> server validates exact profile/condition
  -> server returns/streams a signed-by-hash plan identity
  -> client starts monotonic request clock
  -> server emits deterministic SSE events
  -> client records arrival events and validates sequence
  -> server emits terminal done receipt
  -> client finalizes metrics and status
  -> client uploads canonical run record
  -> server binds client record to server receipt
```

Cryptographic signing is not required for Prototype 0.1. SHA-256 content binding is required.

A stream EOF without a valid terminal done receipt is not success.

## 6. Timing ownership

- User-visible duration metrics use the Android monotonic clock only.
- Server planned offsets describe the deterministic schedule and are not subtracted from client measurements.
- Wall-clock UTC is metadata, not a duration source.
- Cross-device clock synchronization is not required.

## 7. Data ownership and publication

- Android retains raw event evidence locally even when upload fails.
- Server stores accepted uploads under a campaign-specific temporary directory.
- Final campaign publication is atomic as defined in `04_EVIDENCE_REPORT_SPEC.md`.
- Duplicate upload of the same immutable run record is idempotent.
- A conflicting upload for an existing run id fails closed and retains diagnostic evidence.

## 8. Build and branch strategy

- `main`: current product baseline; no direct Prototype implementation pushes.
- `product/prototype-0.1`: integration branch for accepted Prototype work.
- `product/prototype-0.1-specs`: specification branch.
- Recommended implementation branches:
  - `prototype-0.1/core-issue-15`;
  - `prototype-0.1/app-issue-16`;
  - `prototype-0.1/release-issue-17`;
  - `prototype-0.1/qa-issue-18` only for small approved fixes.

All workstream PRs target `product/prototype-0.1`. After G5 passes, one audited final PR targets `main`.

Stacked PRs based on unrelated long-running feature branches are not accepted into Prototype 0.1.

## 9. Dependency rule

The implementation dependency order is:

```text
#14 contract freeze
  -> #15 server contract
     -> #16 Android integration
        -> #17 fixed package/report
           -> #18 P40 acceptance
              -> #13 Product Owner acceptance
```

Work may begin in parallel against drafts, but no downstream gate may be declared PASS until the upstream contract is frozen and the exact candidate is identified.

## 10. Security and privacy boundary

- no credentials in logs, Room, exports, reports or Git;
- no signing keys in the release package;
- no third-party App content or user prompts in Prototype evidence;
- no remote fonts, scripts or analytics in the offline report;
- local server accepts only the minimum Prototype endpoints and enforces body-size limits;
- exported device metadata excludes stable hardware identifiers such as IMEI, serial number and advertising ID.
