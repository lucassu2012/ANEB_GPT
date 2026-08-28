# 04 — Evidence and Report Specification

Status: **G0 rework — reviewable exact head**
Primary issues: #17 and #18  
Schema id: `aneb-prototype-evidence-0.1`

## 1. Canonical campaign directory

```text
results/
└── <campaign_id>/
    ├── meta.json
    ├── events.jsonl
    ├── runs.csv
    ├── summary.csv
    ├── report.html
    ├── run.log
    └── manifest.json
```

All seven files are mandatory for a published campaign directory. A partial or failed campaign still publishes all files, with explicit status and nullable fields.

## 2. Atomic publication

1. Create `results/<campaign_id>.partial/`.
2. Write and close all non-manifest files.
3. Validate JSON/JSONL/CSV syntax and cross-file identities.
4. Generate `manifest.json` last.
5. Re-read every listed artifact, verify size and SHA-256.
6. Atomically rename the directory to `results/<campaign_id>/` where supported.
7. If atomic rename is unavailable, publish a terminal marker inside `manifest.json` only after all verification completes.

An existing published campaign id is immutable. Re-publication with different bytes fails closed and uses a new campaign id.

## 3. `meta.json`

Required top-level fields:

```json
{
  "schema_version": "aneb-prototype-evidence-0.1",
  "campaign_id": "<uuid>",
  "campaign_mode": "quick",
  "campaign_status": "complete",
  "started_at_utc": "2026-08-28T10:00:00Z",
  "ended_at_utc": "2026-08-28T10:01:00Z",
  "claim_scope": "application_end_to_end_to_probe_node",
  "evidence_mode": "synthetic_application_impairment",
  "impairment_layer": "application",
  "score_policy_id": "rpi-0.1",
  "profile_manifest_sha256": "<bare lowercase 64-hex>",
  "clock_contract": {
    "source": "android.os.SystemClock.elapsedRealtimeNanos",
    "unit": "ns",
    "epoch": "device_boot",
    "includes_deep_sleep": true,
    "domain_identity": "per-run opaque boot/session clock_domain_id"
  },
  "profile": {
    "id": "streaming_text_reference_v0.1",
    "version": "0.1",
    "sha256": "<bare lowercase 64-hex>"
  },
  "product": {
    "version": "prototype-0.1",
    "git_commit": "<40-hex>"
  },
  "android_app": {
    "version_name": "<string>",
    "version_code": 1,
    "apk_sha256": "<bare lowercase 64-hex>"
  },
  "server": {
    "version": "<string>",
    "binary_sha256": "<bare lowercase 64-hex>",
    "protocol_version": "prototype-stream-0.1"
  },
  "device": {
    "manufacturer": "HUAWEI",
    "model": "<model>",
    "os_release": "<string>",
    "sdk_int": 29
  },
  "transport": {
    "mode": "lan",
    "acceptance_path": true
  },
  "run_plan": {
    "planned_runs": 3,
    "order": ["baseline_v0.1", "slow_v0.1", "unstable_v0.1"]
  },
  "conditions": []
}
```

The profile `sha256` is the exact SHA-256 of the checked-in
`profile-manifest.json` bytes. The profile binds the fixed condition order and
the following schedule identities; every event, terminal receipt and run record
must repeat the matching bare lowercase digest:

| Condition | `schedule_sha256` |
|---|---|
| `baseline_v0.1` | `46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e` |
| `slow_v0.1` | `b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062` |
| `unstable_v0.1` | `d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58` |

The required claim disclosure is: **ANEB Prototype 0.1 measures Android-client-
observed timing against a local ANEB probe under deterministic synthetic
application-layer schedules. It does not emulate or measure packet loss,
RAN/core/operator quality, public-Internet quality, a real application, or model
inference.**

### Campaign status closed set

- `complete`;
- `partial`;
- `cancelled`;
- `failed`;
- `invalid`.

Device metadata must exclude IMEI, serial number, phone number, advertising id and account identifiers.

## 4. `events.jsonl`

Each line is one UTF-8 JSON object. Required common fields:

- `schema_version`;
- `campaign_id`;
- `run_id` when applicable;
- `condition_id` when applicable;
- `event_type`;
- `client_monotonic_ns` for every Android-observed scoring event, with
  `clock_source=android.os.SystemClock.elapsedRealtimeNanos`,
  `clock_unit=ns`, `clock_epoch=device_boot`, `clock_domain_id` and one run
  clock domain; `run_started.details.t0_monotonic_ns` is the nonzero-or-zero
  boot-absolute t0 used for all deltas in that run;
- `observed_at_utc` when available;
- `source` = `android` for the supported scoring and diagnostic events;
- `details` object.

`clock_domain_id` is an opaque boot/session identity generated for the run's
measurement session. A reboot, process-loss recovery or clock-domain change
creates a new identity; a verifier rejects a run whose events, terminal receipt,
run record and `t0_monotonic_ns` do not all carry the same identity. A timestamp
regression is invalid evidence, not a sortable event.

Allowed event types for v0.1:

- `run_started`;
- `content_event`;
- `terminal_event`;
- `run_failed`;
- `run_cancelled`;
- `diagnostic.<non-empty-suffix>` for non-scoring diagnostics only.

The three scoring event types use one common run-scoped envelope: campaign/run
identity, campaign mode/index, profile/condition/schedule identity, Android
clock source/unit/epoch/domain, integer `client_monotonic_ns`, `source=android`,
and a `details` object. `content_event.details` contains `seq`,
`planned_offset_ms` and the deterministic `payload_id`; the terminal event's
`details` is the receipt object below. A diagnostic event may use the same
envelope but is never counted as a scoring event. `run_failed` and
`run_cancelled` are run-scoped status events only and are invalid alongside a
complete run. The bundle does not claim campaign-level or server-side event
types that this machine contract cannot represent. Events must not include
third-party content or credentials.

Unknown event types are rejected by the v0.1 finalizer unless explicitly namespaced as non-scoring diagnostics.

### Terminal receipt vocabulary

The matching `terminal_event.details` is the sole canonical terminal receipt
object; no separate receipt sidecar is part of the v0.1 bundle. `receipt_version` is exactly
`prototype-terminal-receipt-0.1`; the protocol payload uses
`protocol_version=prototype-stream-0.1`, `profile_id=streaming_text_reference_v0.1`,
`profile_version=0.1`, `planned_event_count=120`,
`emitted_event_count=120` and `terminal_status=complete`. The evidence envelope
also carries `events_expected=120`, `events_received=120`, the profile,
condition, schedule and clock identity fields, `t0_monotonic_ns`, and the
terminal `client_monotonic_ns`. `planned_event_count` is the normative planned
content count; the two `events_*` fields are receipt accounting and must agree
with it. No alternate field names are accepted.

## 5. `runs.csv`

UTF-8 with header, comma delimiter, RFC 4180 quoting and LF line endings.

Required columns in order:

```text
schema_version,campaign_id,run_id,campaign_mode,run_index,profile_manifest_sha256,condition_id,condition_version,nominal_interval_ms,run_status,task_success,clock_source,clock_epoch,clock_domain_id,t0_monotonic_ns,attempt_started_at_utc,attempt_ended_at_utc,events_expected,events_received,ttft_ms,completion_ms,stream_span_ms,stream_event_rate_eps,stall_threshold_ms,stall_count,stall_duration_ms,stall_fraction,schedule_hash,terminal_receipt_valid,score_eligible,failure_reason
```

Null numeric or boolean values are empty CSV fields. Literal `0` is used only for a measured zero or a count that is truly zero.

## 6. `summary.csv`

One row per condition. Required columns in order:

```text
schema_version,campaign_id,campaign_mode,campaign_status,condition_id,planned_runs,attempted_runs,successful_runs,failed_runs,not_started_runs,success_rate,confidence,median_ttft_ms,min_ttft_ms,max_ttft_ms,median_completion_ms,min_completion_ms,max_completion_ms,median_stream_event_rate_eps,median_stall_count,median_stall_duration_ms,median_stall_fraction,rpi,rpi_policy_id,primary_null_reason,all_null_reasons
```

The Baseline row appears first, then Slow, then Unstable.

`all_null_reasons` is a compact JSON array of unique values from the `rpi-0.1`
closed set, ordered by the precedence in `03_METRICS_SCORING_SPEC.md`;
`primary_null_reason` is its first value. If RPI is numeric both fields are
empty/null. No reason is free text.

## 7. `report.html`

The report is a single offline HTML file with embedded CSS and embedded canonical summary data. It must not fetch remote fonts, JavaScript, analytics, images or APIs.

Required sections:

1. **Campaign verdict** — complete/partial/failed, mode and confidence.
2. **Disclosure** — synthetic application-layer scope and non-vendor/non-industry boundary.
3. **Condition comparison** — TTFT, completion, event rate, stalls, success rate and RPI.
4. **Run table** — all planned runs and statuses.
5. **Environment** — app/server/profile hashes, device model/OS and transport mode.
6. **Evidence integrity** — manifest verification state.
7. **Failure details** — present for partial/failed/invalid campaigns.

The report may use simple native SVG or CSS charts. Every chart value must be sourced from `summary.csv`/canonical summary JSON and must have a textual table equivalent.

No letter grade is shown.

## 8. `run.log`

Operational log requirements:

- UTF-8 text;
- UTC timestamp, severity, component and stable event key;
- no API keys, credentials, user content or stable device identifiers;
- no unredacted full query string if it may contain a token;
- failures include one primary machine reason and an actionable message;
- stack traces may be included only in a clearly marked diagnostic section.

## 9. `manifest.json`

Required fields:

```json
{
  "manifest_version": "aneb-prototype-manifest-0.1",
  "campaign_id": "<uuid>",
  "publication_status": "verified",
  "created_at_utc": "<RFC3339>",
  "artifacts": [
    {
      "path": "meta.json",
      "media_type": "application/json",
      "size_bytes": 1234,
      "sha256": "<bare lowercase 64-hex>"
    }
  ]
}
```

`manifest.json` does not list itself. Artifact paths are relative, normalized, contain no `..`, and use `/` separators.

A verifier must reject:

- a missing or extra mandatory file;
- size/hash mismatch;
- duplicate paths;
- absolute or traversal paths;
- mismatched campaign ids;
- malformed JSON/JSONL/CSV;
- summary values that do not recompute from `runs.csv`;
- report embedded values that disagree with canonical summary data.

## 10. Evidence consistency rules

- One `campaign_id` across all files.
- One immutable `run_id` per planned run.
- Every raw event, nested terminal-event details, terminal receipt and run row
  must agree on `campaign_id`, `run_id`, campaign mode/index, condition
  id/version/nominal interval, profile/schedule hashes and (for scoring) the
  same `clock_domain_id` and `t0_monotonic_ns`; a downstream coordinated
  rewrite cannot establish identity.
- Event types outside the v0.1 allow-list are rejected unless explicitly
  namespaced as `diagnostic.*` and excluded from scoring.
- Profile, condition id/version/nominal interval and schedule hashes match the
  four contracts and server receipts exactly (bare lowercase hex; no
  `sha256:` prefix).
- `task_success=true` implies `run_status=complete`, exact 120 events and valid terminal receipt.
- `score_eligible=true` implies task success, all mandatory metrics non-null and
  valid, exact profile/condition/schedule/policy identities, a valid single
  Android monotonic clock domain and a matching terminal receipt.
- Quick indexes 1..3 and Acceptance indexes 1..9 must each occur exactly once
  in the frozen B/S/U plan; duplicate or extra runs are invalid evidence.
- A complete Quick campaign has 3 planned attempts; a complete Acceptance campaign has 9;
  summary attempted/successful/failed/not-started counts are bounded by the plan
  and `success_rate` is recomputed in `[0,1]`.
- A partial campaign has `rpi` empty/null in every summary row.
- JSON null maps to blank CSV, not zero or the string `null`.
- The verifier is authoritative in this order: raw Android scoring events and
  the nested terminal receipt in `terminal_event.details` -> recomputed per-run
  metrics (including strict stall) -> `runs.csv` -> condition summaries/RPI ->
  `report.html`.
  A self-consistent but tampered run record cannot override raw events.
- `RPI-0.1` is independent of AQS and is not an AQS input, conversion or
  operator/vendor/industry score.

## 11. Retention and sharing

Prototype 0.1 keeps evidence locally. Cloud upload is outside scope. The user may share the complete campaign directory or a ZIP produced from it. The HTML report alone is readable but is not sufficient verification evidence without its manifest-bound source files.
