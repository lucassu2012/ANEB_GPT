# 02 — Workload and Synthetic Impairment Specification

Status: **G0 rework — reviewable exact head**
Primary issues: #14 and #15

## 1. Workload identity

| Field | Value |
|---|---|
| Workload id | `streaming_text_reference_v0.1` |
| Workload type | deterministic logical text-event stream |
| Formal execution target | self-hosted ANEB probe node |
| Claim scope | `application_end_to_end_to_probe_node` |
| Evidence mode | `synthetic_application_impairment` |
| Fixed seed | `20260828` |
| Logical content events | `120` |
| Terminal events | one `done` receipt, not counted as content |

The workload simulates the arrival pattern of a streaming text response. It does not run a language model, measure semantic quality, represent billable tokens or reproduce a named vendor's implementation.

The measured unit is a **logical stream event**. UI text may say “stream event”; it must not relabel the unit as a true model token.

## 2. Event contract

Each content event contains at least:

```json
{
  "protocol_version": "prototype-stream-0.1",
  "campaign_id": "<uuid>",
  "run_id": "<uuid>",
  "condition_id": "baseline_v0.1",
  "profile_manifest_sha256": "<bare lowercase 64-hex>",
  "schedule_hash": "<bare lowercase 64-hex>",
  "clock_domain_id": "<opaque boot/session id>",
  "seq": 1,
  "planned_offset_ms": 200,
  "payload_id": "ref-0001"
}
```

Normative rules:

- `seq` starts at 1 and increments by exactly 1 through 120.
- `planned_offset_ms` is measured from the server's logical run start.
- The client measures arrival time independently using its monotonic clock.
- Duplicate, missing, out-of-order or cross-run events invalidate terminal success while retaining raw evidence.
- Payload content is deterministic and carries no user data.

The terminal `done` event contains:

```json
{
  "protocol_version": "prototype-stream-0.1",
  "campaign_id": "<uuid>",
  "run_id": "<uuid>",
  "condition_id": "baseline_v0.1",
  "profile_id": "streaming_text_reference_v0.1",
  "profile_version": "0.1",
  "profile_manifest_sha256": "<bare lowercase 64-hex>",
  "schedule_hash": "<bare lowercase 64-hex>",
  "clock_domain_id": "<opaque boot/session id>",
  "planned_event_count": 120,
  "emitted_event_count": 120,
  "terminal_status": "complete"
}
```

EOF without a valid matching `done` event is `interrupted`, not success.

## 3. Condition definitions

Synthetic conditions alter only server-side application behavior: initial response delay, logical event pacing and scheduled pauses. They do not alter IP packets, radio conditions or operating-system traffic control.

### 3.1 `baseline_v0.1`

| Parameter | Value |
|---|---:|
| Initial delay to event 1 | 200 ms |
| Nominal interval | 50 ms |
| Scheduled extra pauses | none |
| Terminal delay after event 120 | 50 ms |
| Planned first-event offset | 200 ms |
| Planned terminal offset | 6,200 ms |

Purpose: local reference for the same campaign and device.

### 3.2 `slow_v0.1`

| Parameter | Value |
|---|---:|
| Initial delay to event 1 | 650 ms |
| Nominal interval | 125 ms |
| Scheduled extra pauses | none |
| Terminal delay after event 120 | 125 ms |
| Planned first-event offset | 650 ms |
| Planned terminal offset | 15,650 ms |

Purpose: demonstrate slower first response and lower continuous event-arrival rate.

### 3.3 `unstable_v0.1`

| Parameter | Value |
|---|---:|
| Initial delay to event 1 | 350 ms |
| Nominal interval | 65 ms |
| Extra pause after event 40 | 900 ms |
| Extra pause after event 85 | 1,400 ms |
| Terminal delay after event 120 | 65 ms |
| Planned first-event offset | 350 ms |
| Planned terminal offset | 10,450 ms |

The gap from event 40 to 41 is `65 + 900 = 965 ms`; the gap from event 85 to 86 is `65 + 1,400 = 1,465 ms`.

Purpose: demonstrate visible mid-stream stalls while still completing successfully.

## 4. Schedule generation and hash

For each condition, the server generates one canonical byte sequence. The
following rules are normative and are part of the hash identity:

- UTF-8 encoding, no BOM;
- LF (`0x0a`) after the header and after every row, including the final row;
- exact header `seq,planned_offset_ms,payload_id`;
- comma delimiter, no quoting, no spaces and no empty fields;
- `seq` and `planned_offset_ms` are base-10 ASCII integers;
- exactly 120 `content` rows are included; the terminal `done` event is not in
  the schedule CSV;
- `payload_id` is `ref-%04d` with the one-based sequence number;
- event 1 uses `initial_delay_ms`; each later event adds `nominal_interval_ms`;
- a pause declared after N is added once to the N -> N+1 transition; multiple
  pauses on one transition add arithmetically;
- the fixed seed adds no random jitter.

The resulting bytes are:

```csv
seq,planned_offset_ms,payload_id
1,200,ref-0001
2,250,ref-0002
...
120,6150,ref-0120
```

The schedule hash is the bare lowercase SHA-256 digest of those exact bytes:

```text
<lowercase hex SHA-256 of the exact canonical CSV bytes>
```

| Condition | `schedule_sha256` |
|---|---|
| `baseline_v0.1` | `46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e` |
| `slow_v0.1` | `b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062` |
| `unstable_v0.1` | `d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58` |

For this exact v0.1 contract head, `profile_manifest_sha256` is
`ed440d42dfcc849cb7bb24c52f6c0623057d83c4d97af1f86b024703eb9370eb`.
The profile-manifest digest plus `condition_id`, `version`,
`nominal_interval_ms` and this schedule digest form the complete condition
identity. There is no separate condition hash. The same version must always
produce identical canonical schedule bytes and hash; a timing semantic change
requires a new condition version.


## 5. Campaign plans

### 5.1 Quick mode

```text
B1 -> S1 -> U1
```

- one run per condition;
- 1,000 ms unmeasured cooldown between runs;
- intended for setup verification and demonstration;
- confidence cannot exceed LOW because each condition has one run.

### 5.2 Acceptance mode

```text
B1 -> S1 -> U1 -> B2 -> S2 -> U2 -> B3 -> S3 -> U3
```

- three runs per condition;
- 1,000 ms unmeasured cooldown between runs;
- fixed interleaving reduces simple time-order drift;
- condition summaries use successful runs only, with success rate reported
  separately as `successful_runs / planned_runs` for a complete campaign.

The client must not silently reorder or skip planned runs. `quick` has exactly
three planned indexes (1–3), and `acceptance` has exactly nine (1–9), in the
orders above. A cancelled or failed campaign records the remaining runs as
`not_started`, not success or zero. All contract, condition and schedule hashes
are compared byte-for-byte as bare lowercase hex.

## 6. Draft HTTP contract

Recommended endpoints:

- `GET /api/v1/prototype/capabilities`
- `POST /api/v1/prototype/runs` returning an SSE stream
- `POST /api/v1/prototype/evidence/runs`
- `POST /api/v1/prototype/evidence/finalize`
- `GET /api/v1/prototype/evidence/{campaign_id}`

A run request contains only versioned identifiers and run metadata. Arbitrary client-supplied timing parameters are rejected in the formal Prototype flow.

## 7. Failure semantics

| Condition | Run status | Score eligibility |
|---|---|---|
| exact 120 events + matching done receipt | `complete` | eligible |
| valid first event, then transport failure/EOF | `interrupted` | not a successful run; partial metrics retained |
| unknown/mismatched profile or condition | `incompatible` | no business traffic, no score |
| sequence missing/duplicate/out of order | `invalid_sequence` | not eligible |
| user cancellation | `cancelled` | not eligible |
| timeout before first event | `ttft_timeout` | not eligible |
| server rejects request | `server_rejected` | not eligible |

No failure duration is replaced by a timeout cap for aggregation. Available partial observations may be shown diagnostically but mandatory missing measurements remain `null`.

## 8. Naming restrictions

Allowed descriptions:

- application-layer synthetic condition;
- deterministic pacing;
- scheduled application pause;
- stream-event arrival;
- TTFT proxy to self-hosted node.

Forbidden descriptions:

- packet loss percentage;
- radio/RAN impairment;
- 4G/5G requirement;
- real Kimi/Doubao token rate;
- operator SLA score;
- model inference latency.
