# 03 — Metrics and Relative Scoring Specification

Status: **G0 rework — reviewable exact head**
Primary issues: #14 and #16  
Policy id: `rpi-0.1`

## 1. Measurement clock

All user-visible durations use the Android
`android.os.SystemClock.elapsedRealtimeNanos()` clock. Its raw unit is integer
nanoseconds, its epoch is the device boot, and it includes deep sleep. Wall-clock
UTC is retained only for metadata and ordering.

For one run:

- `t0`: captured immediately before transport dispatch (the adapter's
  `Call.enqueue`/`Call.execute` or Cronet `UrlRequest.start` invocation).
- `t_headers`: response headers accepted; diagnostic only.
- `t_i`: complete SSE content event `i` decoded and identity-validated for the
  current run, immediately after the event boundary.
- `t_first = t_1`.
- `t_last = t_120` for a successful run.
- `t_done`: complete matching terminal `done` event decoded and identity-validated.

All timestamps in a run must be from one boot/clock domain. Reboot, process
reconstruction without the original clock-domain identity, clock regression or
domain mismatch makes the run invalid for scoring; timestamps are never spliced
across domains or runs. Duration conversion preserves raw nanoseconds and emits
milliseconds as `ns / 1e6`, with at most six decimal places and no second-based
rounding. UTC never participates in TTFT, completion, span, gap or stall math.

## 2. Per-run status

`task_success = true` only when all are true:

1. the request passed exact profile and condition validation;
2. content events 1 through 120 arrived exactly once in order;
3. each event matched campaign, run, condition and schedule hash;
4. a valid matching `done` event reported `terminal_status=complete` and `emitted_event_count=120`;
5. no transport, parse or cancellation error occurred before terminal completion.

Otherwise `task_success = false`. Partial timing evidence is retained but the run is not included in successful-run medians.

## 3. Per-run metrics

### 3.1 Time to first stream event

```text
ttft_ms = (t_first - t0) in milliseconds
```

This is an end-to-end first-stream-event proxy to the self-hosted node. It includes client dispatch, local path, server initial delay, response setup and client decoding. It is not model inference latency.

If no valid first event arrives, `ttft_ms = null`; no timeout cap or zero is
substituted.

### 3.2 Completion time

```text
completion_ms = (t_done - t0) in milliseconds
```

If the terminal receipt is missing or invalid, `completion_ms = null` even if some or all content events arrived.

### 3.3 Stream span

For at least two valid content events:

```text
stream_span_ms = (t_last_available - t_first) in milliseconds
```

For a successful run, `t_last_available = t_120`. With fewer than two valid
events, the value is `null`.

### 3.4 Stream event rate

For a successful run:

```text
stream_event_rate_eps = (event_count - 1) * 1000 / stream_span_ms
```

`event_count` is 120. The rate includes scheduled and incidental stalls because they affect user-visible delivery. It is logical events per second, not true tokens/s and not network throughput.

If `stream_span_ms <= 0`, the run is invalid and the rate is `null`.

### 3.5 Stall detection

For adjacent valid content events in their received sequence order:

```text
gap_i_ns = t_i - t_(i-1)
stall_threshold_ns = max(500,000,000 ns, 4 * nominal_interval_ms * 1,000,000 ns)
```

A stall exists when:

```text
gap_i_ns > stall_threshold_ns
```

The strict `>` operator is normative.

```text
stall_count = number of qualifying gaps
stall_duration_ms = sum((gap_i_ns - nominal_interval_ms * 1,000,000) / 1,000,000
                        for qualifying gaps)
```

The initial delay before event 1 and the terminal gap after event 120 are not stalls.
Exactly equal to the threshold is not a stall. Missing, duplicate or out-of-order
events are not repaired by sorting; the run becomes invalid while raw events are
retained.

For a successful run:

```text
stall_fraction = clamp(stall_duration_ms / stream_span_ms, 0, 1)
```

If a run has fewer than two valid content events, stall metrics are `null` except raw gaps retained in evidence.

## 4. Campaign completeness

Each condition summary records:

- `planned_runs`;
- `attempted_runs`;
- `successful_runs`;
- `failed_runs`;
- `not_started_runs`;
- `success_rate`.

For a completed campaign:

```text
success_rate = successful_runs / planned_runs
```

For a partial or cancelled campaign, `success_rate` remains
`successful_runs / planned_runs` when `planned_runs > 0`; if no run was planned it
is `null`. It must not switch denominators to make a partial campaign look
complete. All condition `RPI-0.1` values are `null` and the report labels the
campaign incomplete.

## 5. Condition aggregation

For each condition, calculate medians across successful runs only:

- `median_ttft_ms`;
- `median_completion_ms`;
- `median_stream_event_rate_eps`;
- `median_stall_count`;
- `median_stall_duration_ms`;
- `median_stall_fraction`.

The report also emits successful-run minimum and maximum for TTFT and completion as diagnostics. No failed duration is replaced with a timeout ceiling or numeric zero.

The median is deterministic: sort the non-null successful values; for an odd
count use the middle value, and for an even count use the arithmetic mean of the
two middle values. Counts, durations and rates are retained as numeric zero only
when that zero was actually observed (for example Baseline `stall_count=0`);
missing, uncomputable and inapplicable values are `null`.

### 5.1 Partial/null matrix

| Observation state | `ttft_ms` | `completion_ms` | stream span/rate | stall metrics | score eligibility |
|---|---|---|---|---|---|
| no valid content event | `null` | `null` | `null` | `null` | false |
| first valid event, stream later fails | number | `null` | `null` unless ≥2 events | number only when ≥2 events | false |
| ≥2 valid events, terminal missing/invalid | number | `null` | number/rate | number | false |
| 120 valid events + valid terminal | number > 0 | number > 0 | number > 0 | measured values (fraction `[0,1]`) | eligible if all contract checks pass |
| clock domain invalid/mismatched | `null` | `null` | `null` | `null` | false |

Raw events and diagnostics are retained in every row. A null is never converted
to zero, a timeout ceiling or a sentinel.

## 6. Confidence

### Quick mode

A complete one-run-per-condition campaign has:

```text
confidence = LOW
```

Quick mode cannot claim MEDIUM or HIGH confidence.
With zero successful runs, Quick confidence is `NONE`; with its one successful
run per condition it is `LOW`.

### Acceptance mode

Per condition:

| Successful runs out of 3 | Confidence |
|---:|---|
| 3 | `HIGH` |
| 2 | `MEDIUM` |
| 1 | `LOW` |
| 0 | `NONE` |

Confidence describes repeat count and completion only. It is not statistical proof of a population threshold.

## 7. Relative Prototype Index — `RPI-0.1`

### 7.1 Purpose

`RPI-0.1` is a same-campaign comparison against the Baseline condition on the same device and node. It is not AQS, MOS, an industry score, a real-App score or an operator score.

### 7.2 Eligibility

A condition score is calculated only when:

- the campaign is complete and the condition has at least one successful run;
- at least one successful Baseline run exists;
- profile manifest, workload, condition id/version/nominal interval, schedule,
  evidence schema and score-policy identities match exactly;
- the run clock source/epoch/domain is valid and consistent;
- mandatory medians for TTFT, completion and stall fraction are non-null, with
  TTFT/completion strictly `> 0` and success rate/stall fraction in `[0,1]`.

Otherwise `rpi = null` with a machine-readable reason.

### 7.3 Sub-scores

Let Baseline medians be `B_ttft` and `B_completion`; current-condition medians be `C_ttft`, `C_completion`, and `C_stall_fraction`.

```text
ttft_quality       = min(1, B_ttft / C_ttft)
completion_quality = min(1, B_completion / C_completion)
stall_quality      = clamp(1 - C_stall_fraction, 0, 1)
```

All ratios require positive denominators; otherwise the score is null.

### 7.4 Formula

```text
RPI-0.1 = round_half_away_from_zero(
  clamp(
    100
    * success_rate
    * (
        0.45 * ttft_quality
      + 0.35 * completion_quality
      + 0.20 * stall_quality
    ),
    0,
    100
  )
)
```

The implementation order is `raw = 100 * ...`, `clamped = clamp(raw, 0, 100)`
and then nearest-integer rounding with `.5` away from zero. The result is still
bounded to `[0, 100]`.

The Baseline condition normally scores 100 when all Baseline runs succeed and no Baseline stalls are detected. A noisy Baseline may score below 100 through its own stall quality; this is intentional and visible.

## 8. Machine-readable score reasons

When `rpi = null`, one or more reasons must be emitted from this closed set:

- `campaign_incomplete`;
- `no_successful_baseline`;
- `no_successful_condition_run`;
- `mandatory_metric_missing`;
- `non_positive_metric`;
- `contract_mismatch`;
- `invalid_evidence`;
- `score_policy_unsupported`.

The report emits `primary_null_reason` and `all_null_reasons`. The latter is a
unique, compact JSON array sorted by this normative precedence (first matching
reason is primary): `contract_mismatch`, `invalid_evidence`,
`campaign_incomplete`, `no_successful_baseline`, `no_successful_condition_run`,
`mandatory_metric_missing`, `non_positive_metric`, `score_policy_unsupported`.
Reasons are evidence-derived, not free text; raw event evidence remains the
verifier's trust root.

## 9. UI and report wording

Required label:

> Relative Prototype Index (same-campaign synthetic comparison)

Required disclosure:

> This score compares deterministic application-layer conditions against this campaign's Baseline. It is not a formal ANEB industry score and does not represent a third-party AI application's network requirement.

Do not show letter grades such as Excellent/Good/Poor in Prototype 0.1.

## 10. Test vectors

At minimum, automated tests must cover:

1. perfect Baseline: RPI 100;
2. doubled TTFT with otherwise equal metrics;
3. doubled completion time;
4. non-zero stall fraction;
5. 2/3 success-rate penalty;
6. incomplete campaign -> null;
7. no successful Baseline -> null;
8. missing mandatory metric -> null;
9. exact 500 ms gap at a 50 ms nominal interval is not a stall; 501 ms is;
10. invalid event sequence cannot produce success or a score.
