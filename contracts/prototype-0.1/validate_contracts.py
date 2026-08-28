#!/usr/bin/env python3
"""Bounded machine checks for the ANEB Prototype 0.1 binding contracts.

The helper is a review fixture, not a product implementation.  It validates
the four published contracts, regenerates the schedules from the checked-in
profile manifest, exercises schema counterexamples, and verifies a temporary
raw-evidence chain.  It deliberately stays inside the Prototype 0.1 scope.
"""

from __future__ import annotations

import copy
import csv
import hashlib
import io
import json
import math
import re
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable, Iterable


ROOT = Path(__file__).resolve().parent
CONDITION_ORDER = ["baseline_v0.1", "slow_v0.1", "unstable_v0.1"]
EXPECTED_SCHEDULE_HASHES = {
    "baseline_v0.1": "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
    "slow_v0.1": "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
    "unstable_v0.1": "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
}
EXPECTED_CONDITION_ORACLE = {
    "baseline_v0.1": {
        "version": "0.1",
        "initial_delay_ms": 200,
        "nominal_interval_ms": 50,
        "scheduled_pauses": [],
        "terminal_delay_ms": 50,
        "planned_first_event_offset_ms": 200,
        "planned_last_event_offset_ms": 6150,
        "planned_terminal_offset_ms": 6200,
    },
    "slow_v0.1": {
        "version": "0.1",
        "initial_delay_ms": 650,
        "nominal_interval_ms": 125,
        "scheduled_pauses": [],
        "terminal_delay_ms": 125,
        "planned_first_event_offset_ms": 650,
        "planned_last_event_offset_ms": 15525,
        "planned_terminal_offset_ms": 15650,
    },
    "unstable_v0.1": {
        "version": "0.1",
        "initial_delay_ms": 350,
        "nominal_interval_ms": 65,
        "scheduled_pauses": [
            {"after_seq": 40, "extra_delay_ms": 900},
            {"after_seq": 85, "extra_delay_ms": 1400},
        ],
        "terminal_delay_ms": 65,
        "planned_first_event_offset_ms": 350,
        "planned_last_event_offset_ms": 10385,
        "planned_terminal_offset_ms": 10450,
    },
}
EXPECTED_NULL_PRECEDENCE = [
    "campaign_incomplete",
    "contract_mismatch",
    "invalid_evidence",
    "no_successful_baseline",
    "no_successful_condition_run",
    "mandatory_metric_missing",
    "non_positive_metric",
    "score_policy_unsupported",
]
METRIC_KEYS = [
    "ttft_ms",
    "completion_ms",
    "stream_span_ms",
    "stream_event_rate_eps",
    "stall_threshold_ms",
    "stall_count",
    "stall_duration_ms",
    "stall_fraction",
]
RUN_COLUMNS = [
    "schema_version",
    "campaign_id",
    "run_id",
    "campaign_mode",
    "run_index",
    "profile_manifest_sha256",
    "condition_id",
    "condition_version",
    "nominal_interval_ms",
    "run_status",
    "task_success",
    "clock_source",
    "clock_epoch",
    "clock_domain_id",
    "t0_monotonic_ns",
    "attempt_started_at_utc",
    "attempt_ended_at_utc",
    "events_expected",
    "events_received",
    "ttft_ms",
    "completion_ms",
    "stream_span_ms",
    "stream_event_rate_eps",
    "stall_threshold_ms",
    "stall_count",
    "stall_duration_ms",
    "stall_fraction",
    "schedule_hash",
    "terminal_receipt_valid",
    "score_eligible",
    "failure_reason",
]
SUMMARY_COLUMNS = [
    "schema_version",
    "campaign_id",
    "campaign_mode",
    "campaign_status",
    "condition_id",
    "planned_runs",
    "attempted_runs",
    "successful_runs",
    "failed_runs",
    "not_started_runs",
    "success_rate",
    "confidence",
    "median_ttft_ms",
    "min_ttft_ms",
    "max_ttft_ms",
    "median_completion_ms",
    "min_completion_ms",
    "max_completion_ms",
    "median_stream_event_rate_eps",
    "median_stall_count",
    "median_stall_duration_ms",
    "median_stall_fraction",
    "rpi",
    "rpi_policy_id",
    "primary_null_reason",
    "all_null_reasons",
]
CONTRACT_FILES = [
    "profile-manifest.json",
    "capabilities.schema.json",
    "run-record.schema.json",
    "score-policy.json",
]
ALLOWED_EVENT_TYPES = {
    "campaign_started",
    "run_planned",
    "run_started",
    "response_headers",
    "content_event",
    "terminal_event",
    "run_completed",
    "run_failed",
    "run_cancelled",
    "evidence_upload_started",
    "evidence_upload_completed",
    "campaign_completed",
    "campaign_failed",
    "diagnostic",
}


def load_json(name: str) -> dict[str, Any]:
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def canonical_file_bytes(name: str) -> bytes:
    data = (ROOT / name).read_bytes().replace(b"\r\n", b"\n")
    if data.startswith(b"\xef\xbb\xbf"):
        raise AssertionError(f"{name} has a UTF-8 BOM")
    return data


def condition_from_manifest(profile: dict[str, Any], condition_id: str) -> dict[str, Any]:
    matches = [item for item in profile["conditions"] if item["id"] == condition_id]
    if len(matches) != 1:
        raise AssertionError(f"manifest must contain exactly one {condition_id}")
    return matches[0]


def schedule_offsets(profile: dict[str, Any], condition_id: str) -> tuple[list[int], int]:
    """Generate offsets only from the loaded manifest, never a second table."""
    condition = condition_from_manifest(profile, condition_id)
    offsets: list[int] = []
    for seq in range(1, 121):
        if seq == 1:
            offset = condition["initial_delay_ms"]
        else:
            offset = offsets[-1] + condition["nominal_interval_ms"]
            offset += sum(
                pause["extra_delay_ms"]
                for pause in condition["scheduled_pauses"]
                if seq == pause["after_seq"] + 1
            )
        offsets.append(offset)
    terminal = offsets[-1] + condition["terminal_delay_ms"]
    return offsets, terminal


def canonical_schedule(profile: dict[str, Any], condition_id: str) -> bytes:
    offsets, _ = schedule_offsets(profile, condition_id)
    rows = ["seq,planned_offset_ms,payload_id"]
    rows.extend(f"{seq},{offset},ref-{seq:04d}" for seq, offset in enumerate(offsets, 1))
    return ("\n".join(rows) + "\n").encode("utf-8")


def validate_instance(schema: dict[str, Any], instance: dict[str, Any]) -> list[str]:
    try:
        from jsonschema import Draft202012Validator, FormatChecker
    except ModuleNotFoundError as exc:  # pragma: no cover
        raise RuntimeError("jsonschema is required") from exc
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    return [error.message for error in validator.iter_errors(instance)]


def expect_valid(schema: dict[str, Any], instance: dict[str, Any], label: str) -> None:
    errors = validate_instance(schema, instance)
    if errors:
        raise AssertionError(f"{label} unexpectedly failed: {errors}")


def expect_invalid(schema: dict[str, Any], instance: dict[str, Any], label: str) -> None:
    errors = validate_instance(schema, instance)
    if not errors:
        raise AssertionError(f"{label} unexpectedly passed")


def expect_failure(action: Callable[[], Any], label: str) -> None:
    try:
        action()
    except (AssertionError, ValueError, KeyError, TypeError, IndexError, csv.Error):
        return
    raise AssertionError(f"{label} unexpectedly passed")


def median(values: Iterable[float]) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        raise ValueError("median requires at least one value")
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def is_stall(gap_ns: int, nominal_interval_ms: int) -> bool:
    threshold_ns = max(500_000_000, 4 * nominal_interval_ms * 1_000_000)
    return gap_ns > threshold_ns


def derive_raw_metrics(
    event_times_ns: list[int],
    done_ns: int,
    nominal_interval_ms: int,
    t0_ns: int,
) -> dict[str, float | int]:
    """Derive metrics from boot-absolute timestamps using same-run t0 deltas."""
    if t0_ns < 0 or len(event_times_ns) < 2:
        raise ValueError("at least two events and a non-negative t0 are required")
    if any(timestamp < t0_ns for timestamp in event_times_ns) or done_ns < t0_ns:
        raise ValueError("timestamps precede t0")
    gaps = [right - left for left, right in zip(event_times_ns, event_times_ns[1:])]
    if any(gap <= 0 for gap in gaps) or done_ns < event_times_ns[-1]:
        raise ValueError("timestamp regression or terminal before last event")
    nominal_ns = nominal_interval_ms * 1_000_000
    stalls = [gap for gap in gaps if is_stall(gap, nominal_interval_ms)]
    span_ns = event_times_ns[-1] - event_times_ns[0]
    if span_ns <= 0:
        raise ValueError("non-positive stream span")
    duration_ns = sum(gap - nominal_ns for gap in stalls)
    return {
        "ttft_ms": (event_times_ns[0] - t0_ns) / 1_000_000,
        "completion_ms": (done_ns - t0_ns) / 1_000_000,
        "stream_span_ms": span_ns / 1_000_000,
        "stream_event_rate_eps": (len(event_times_ns) - 1) * 1000 / (span_ns / 1_000_000),
        "stall_threshold_ms": max(500, 4 * nominal_interval_ms),
        "stall_count": len(stalls),
        "stall_duration_ms": duration_ns / 1_000_000,
        "stall_fraction": max(0.0, min(1.0, duration_ns / span_ns)),
    }


def assert_raw_metrics_match(
    record: dict[str, Any],
    event_times_ns: list[int],
    done_ns: int,
    nominal_interval_ms: int,
    t0_ns: int,
) -> None:
    expected = derive_raw_metrics(event_times_ns, done_ns, nominal_interval_ms, t0_ns)
    actual = record["metrics"]
    for key, value in expected.items():
        if isinstance(value, float):
            if not math.isclose(float(actual[key]), value, rel_tol=0, abs_tol=1e-9):
                raise ValueError(f"raw-event mismatch for {key}")
        elif actual[key] != value:
            raise ValueError(f"raw-event mismatch for {key}")


def base_clock(t0_ns: int | None, domain_id: str = "boot-session-0001") -> dict[str, Any]:
    return {
        "source": "android.os.SystemClock.elapsedRealtimeNanos",
        "unit": "ns",
        "epoch": "device_boot",
        "includes_deep_sleep": True,
        "domain_id": domain_id,
        "t0_monotonic_ns": t0_ns,
        "t0_boundary": "immediately_before_transport_dispatch",
        "event_boundary": "after_complete_sse_content_event_decode_and_identity_validation",
        "done_boundary": "after_complete_done_event_decode_and_identity_validation",
    }


def valid_capabilities(profile_hash: str, profile: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "aneb-prototype-capabilities-0.1",
        "product_version": "prototype-0.1",
        "protocol_version": "prototype-stream-0.1",
        "server_version": "dev",
        "server_binary_sha256": "a" * 64,
        "claim_scope": "application_end_to_end_to_probe_node",
        "evidence_mode": "synthetic_application_impairment",
        "impairment_layer": "application",
        "profile_manifest_sha256": profile_hash,
        "workload": {
            "id": profile["workload"]["id"],
            "version": profile["workload"]["version"],
            "content_event_count": profile["workload"]["content_event_count"],
        },
        "conditions": [
            {
                "id": item["id"],
                "version": item["version"],
                "nominal_interval_ms": item["nominal_interval_ms"],
                "schedule_sha256": item["schedule_sha256"],
            }
            for item in profile["conditions"]
        ],
        "evidence_schema_version": "aneb-prototype-evidence-0.1",
        "score_policy_id": "rpi-0.1",
        "terminal_receipt_version": "prototype-terminal-receipt-0.1",
    }


def condition_for_index(campaign_mode: str, run_index: int) -> str:
    if campaign_mode == "quick":
        if not 1 <= run_index <= 3:
            raise ValueError("quick run index outside B/S/U cycle")
    elif campaign_mode == "acceptance":
        if not 1 <= run_index <= 9:
            raise ValueError("acceptance run index outside B/S/U cycle")
    else:
        raise ValueError("unknown campaign mode")
    return CONDITION_ORDER[(run_index - 1) % 3]


def valid_run(
    profile_hash: str,
    profile: dict[str, Any],
    condition_id: str | None = None,
    run_index: int = 1,
    campaign_mode: str = "acceptance",
    run_id: str | None = None,
    t0_ns: int | None = None,
    domain_id: str = "boot-session-0001",
) -> dict[str, Any]:
    if condition_id is None:
        condition_id = condition_for_index(campaign_mode, run_index)
    if condition_for_index(campaign_mode, run_index) != condition_id:
        raise ValueError("mode/index/condition mismatch in fixture")
    condition = condition_from_manifest(profile, condition_id)
    offsets, terminal = schedule_offsets(profile, condition_id)
    t0_ns = 9_000_000_000_000 + run_index * 1_000_000_000 if t0_ns is None else t0_ns
    raw = derive_raw_metrics(
        [t0_ns + offset * 1_000_000 for offset in offsets],
        t0_ns + terminal * 1_000_000,
        condition["nominal_interval_ms"],
        t0_ns,
    )
    return {
        "schema_version": "aneb-prototype-run-record-0.1",
        "campaign_id": "campaign-0001",
        "run_id": run_id or f"run-{campaign_mode}-{run_index:02d}",
        "campaign_mode": campaign_mode,
        "run_index": run_index,
        "profile_manifest_sha256": profile_hash,
        "condition": {
            "id": condition_id,
            "version": condition["version"],
            "nominal_interval_ms": condition["nominal_interval_ms"],
        },
        "run_status": "complete",
        "task_success": True,
        "clock": base_clock(t0_ns, domain_id),
        "attempt_started_at_utc": "2026-08-28T10:00:00Z",
        "attempt_ended_at_utc": "2026-08-28T10:00:07Z",
        "events_expected": 120,
        "events_received": 120,
        "metrics": raw,
        "schedule_hash": condition["schedule_sha256"],
        "terminal_receipt_valid": True,
        "score_eligible": True,
        "failure_reason": None,
    }


def not_started_run(profile_hash: str, profile: dict[str, Any]) -> dict[str, Any]:
    result = valid_run(
        profile_hash,
        profile,
        "baseline_v0.1",
        run_index=1,
        campaign_mode="quick",
        run_id="run-quick-01",
    )
    result.update(
        {
            "run_status": "not_started",
            "task_success": False,
            "attempt_started_at_utc": None,
            "attempt_ended_at_utc": None,
            "events_received": 0,
            "terminal_receipt_valid": None,
            "score_eligible": False,
            "failure_reason": "not_started",
        }
    )
    result["clock"]["t0_monotonic_ns"] = None
    result["metrics"] = {key: None for key in result["metrics"]}
    return result


def partial_interrupted_run(profile_hash: str, profile: dict[str, Any]) -> dict[str, Any]:
    result = valid_run(
        profile_hash,
        profile,
        "baseline_v0.1",
        run_index=1,
        campaign_mode="quick",
        run_id="run-quick-01",
    )
    result.update(
        {
            "run_status": "interrupted",
            "task_success": False,
            "events_received": 2,
            "terminal_receipt_valid": None,
            "score_eligible": False,
            "failure_reason": "stream_interrupted",
        }
    )
    t0_ns = result["clock"]["t0_monotonic_ns"]
    first = t0_ns + 200 * 1_000_000
    second = t0_ns + 250 * 1_000_000
    result["metrics"] = {
        "ttft_ms": 200.0,
        "completion_ms": None,
        "stream_span_ms": 50.0,
        "stream_event_rate_eps": 2380.0,
        "stall_threshold_ms": 500,
        "stall_count": 0,
        "stall_duration_ms": 0.0,
        "stall_fraction": 0.0,
    }
    assert first < second
    return result


def ordered_null_reasons(reasons: Iterable[str]) -> list[str]:
    unique = set(reasons)
    if not unique.issubset(set(EXPECTED_NULL_PRECEDENCE)):
        raise ValueError("unknown null reason")
    return [reason for reason in EXPECTED_NULL_PRECEDENCE if reason in unique]


def scalar(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        # The evidence contract emits derived milliseconds/rates/fractions with
        # at most six decimal places; integer-valued floats stay canonical.
        if value.is_integer():
            return str(int(value))
        return format(value, ".6f").rstrip("0").rstrip(".")
    return str(value)


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=fieldnames,
            extrasaction="raise",
            lineterminator="\n",
        )
        writer.writeheader()
        for row in rows:
            writer.writerow({key: scalar(row.get(key)) for key in fieldnames})


def read_csv(path: Path, fieldnames: list[str]) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != fieldnames:
            raise ValueError(f"{path.name} header is not the frozen RFC4180 header")
        rows = list(reader)
    if any(set(row) != set(fieldnames) for row in rows):
        raise ValueError(f"{path.name} has an unexpected row shape")
    return rows


def parse_bool(value: str, label: str) -> bool | None:
    if value == "":
        return None
    if value == "true":
        return True
    if value == "false":
        return False
    raise ValueError(f"{label} is not a canonical boolean")


def parse_number(value: str, label: str) -> float | None:
    if value == "":
        return None
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"{label} is not finite")
    return number


def csv_row_to_run(row: dict[str, str]) -> dict[str, Any]:
    metrics: dict[str, Any] = {}
    for key in METRIC_KEYS:
        if key == "stall_count":
            parsed = parse_number(row[key], key)
            metrics[key] = None if parsed is None else int(parsed)
        else:
            metrics[key] = parse_number(row[key], key)
    t0 = parse_number(row["t0_monotonic_ns"], "t0_monotonic_ns")
    return {
        "schema_version": row["schema_version"],
        "campaign_id": row["campaign_id"],
        "run_id": row["run_id"],
        "campaign_mode": row["campaign_mode"],
        "run_index": int(row["run_index"]),
        "profile_manifest_sha256": row["profile_manifest_sha256"],
        "condition": {
            "id": row["condition_id"],
            "version": row["condition_version"],
            "nominal_interval_ms": int(row["nominal_interval_ms"]),
        },
        "run_status": row["run_status"],
        "task_success": parse_bool(row["task_success"], "task_success"),
        "clock": {
            "source": row["clock_source"],
            "unit": "ns",
            "epoch": row["clock_epoch"],
            "includes_deep_sleep": True,
            "domain_id": row["clock_domain_id"],
            "t0_monotonic_ns": None if t0 is None else int(t0),
            "t0_boundary": "immediately_before_transport_dispatch",
            "event_boundary": "after_complete_sse_content_event_decode_and_identity_validation",
            "done_boundary": "after_complete_done_event_decode_and_identity_validation",
        },
        "attempt_started_at_utc": row["attempt_started_at_utc"] or None,
        "attempt_ended_at_utc": row["attempt_ended_at_utc"] or None,
        "events_expected": int(row["events_expected"]),
        "events_received": int(row["events_received"]),
        "metrics": metrics,
        "schedule_hash": row["schedule_hash"],
        "terminal_receipt_valid": parse_bool(row["terminal_receipt_valid"], "terminal_receipt_valid"),
        "score_eligible": parse_bool(row["score_eligible"], "score_eligible"),
        "failure_reason": row["failure_reason"] or None,
    }


def run_to_csv_row(record: dict[str, Any]) -> dict[str, Any]:
    clock = record["clock"]
    condition = record["condition"]
    row = {
        "schema_version": record["schema_version"],
        "campaign_id": record["campaign_id"],
        "run_id": record["run_id"],
        "campaign_mode": record["campaign_mode"],
        "run_index": record["run_index"],
        "profile_manifest_sha256": record["profile_manifest_sha256"],
        "condition_id": condition["id"],
        "condition_version": condition["version"],
        "nominal_interval_ms": condition["nominal_interval_ms"],
        "run_status": record["run_status"],
        "task_success": record["task_success"],
        "clock_source": clock["source"],
        "clock_epoch": clock["epoch"],
        "clock_domain_id": clock["domain_id"],
        "t0_monotonic_ns": clock["t0_monotonic_ns"],
        "attempt_started_at_utc": record["attempt_started_at_utc"],
        "attempt_ended_at_utc": record["attempt_ended_at_utc"],
        "events_expected": record["events_expected"],
        "events_received": record["events_received"],
        **record["metrics"],
        "schedule_hash": record["schedule_hash"],
        "terminal_receipt_valid": record["terminal_receipt_valid"],
        "score_eligible": record["score_eligible"],
        "failure_reason": record["failure_reason"],
    }
    return row


def confidence_for(mode: str, successes: int) -> str:
    if mode == "quick":
        return {0: "NONE", 1: "LOW"}.get(successes, "LOW")
    return {0: "NONE", 1: "LOW", 2: "MEDIUM", 3: "HIGH"}.get(successes, "HIGH")


def round_half_away_from_zero(value: float) -> int:
    return math.floor(value + 0.5) if value >= 0 else math.ceil(value - 0.5)


def rpi_value(
    baseline: dict[str, Any],
    current: dict[str, Any],
    success_rate: float,
) -> int:
    ttft_quality = min(1.0, baseline["median_ttft_ms"] / current["median_ttft_ms"])
    completion_quality = min(1.0, baseline["median_completion_ms"] / current["median_completion_ms"])
    stall_quality = max(0.0, min(1.0, 1.0 - current["median_stall_fraction"]))
    raw = 100 * success_rate * (
        0.45 * ttft_quality + 0.35 * completion_quality + 0.20 * stall_quality
    )
    return round_half_away_from_zero(max(0.0, min(100.0, raw)))


def compute_summary_rows(
    runs: list[dict[str, Any]],
    profile: dict[str, Any],
    campaign_mode: str,
    campaign_status: str,
) -> list[dict[str, Any]]:
    plan = next(item for item in profile["campaign_plans"] if item["mode"] == campaign_mode)
    planned_by_condition = {condition_id: plan["condition_order"].count(condition_id) for condition_id in CONDITION_ORDER}
    rows: list[dict[str, Any]] = []
    for condition_id in CONDITION_ORDER:
        condition_runs = [run for run in runs if run["condition"]["id"] == condition_id]
        successful = [run for run in condition_runs if run["task_success"] is True and run["score_eligible"] is True]
        attempted = [run for run in condition_runs if run["run_status"] != "not_started"]
        not_started = [run for run in condition_runs if run["run_status"] == "not_started"]
        planned = planned_by_condition[condition_id]
        values = {
            key: [float(run["metrics"][key]) for run in successful if run["metrics"][key] is not None]
            for key in METRIC_KEYS
        }
        medians = {key: (median(value) if value else None) for key, value in values.items()}
        reasons: list[str] = []
        if campaign_status != "complete":
            reasons.append("campaign_incomplete")
        baseline_success = any(
            run["condition"]["id"] == "baseline_v0.1"
            and run["task_success"] is True
            and run["score_eligible"] is True
            for run in runs
        )
        if not baseline_success:
            reasons.append("no_successful_baseline")
        if not successful:
            reasons.append("no_successful_condition_run")
        mandatory = ["ttft_ms", "completion_ms", "stall_fraction"]
        if any(medians[key] is None for key in mandatory):
            reasons.append("mandatory_metric_missing")
        if any(
            medians[key] is not None and medians[key] <= 0
            for key in ["ttft_ms", "completion_ms"]
        ):
            reasons.append("non_positive_metric")
        reasons = ordered_null_reasons(reasons)
        success_rate = (len(successful) / planned) if planned else None
        row: dict[str, Any] = {
            "schema_version": "aneb-prototype-summary-0.1",
            "campaign_id": runs[0]["campaign_id"] if runs else "campaign-0001",
            "campaign_mode": campaign_mode,
            "campaign_status": campaign_status,
            "condition_id": condition_id,
            "planned_runs": planned,
            "attempted_runs": len(attempted),
            "successful_runs": len(successful),
            "failed_runs": len(attempted) - len(successful),
            "not_started_runs": len(not_started),
            "success_rate": success_rate,
            "confidence": confidence_for(campaign_mode, len(successful)),
            "median_ttft_ms": medians["ttft_ms"],
            "min_ttft_ms": min(values["ttft_ms"]) if values["ttft_ms"] else None,
            "max_ttft_ms": max(values["ttft_ms"]) if values["ttft_ms"] else None,
            "median_completion_ms": medians["completion_ms"],
            "min_completion_ms": min(values["completion_ms"]) if values["completion_ms"] else None,
            "max_completion_ms": max(values["completion_ms"]) if values["completion_ms"] else None,
            "median_stream_event_rate_eps": medians["stream_event_rate_eps"],
            "median_stall_count": medians["stall_count"],
            "median_stall_duration_ms": medians["stall_duration_ms"],
            "median_stall_fraction": medians["stall_fraction"],
            "rpi": None,
            "rpi_policy_id": "rpi-0.1",
            "primary_null_reason": reasons[0] if reasons else None,
            "all_null_reasons": reasons or None,
        }
        rows.append(row)
    if not any(row["primary_null_reason"] for row in rows):
        baseline = rows[0]
        for row in rows:
            row["rpi"] = 100 if row["condition_id"] == "baseline_v0.1" else rpi_value(
                baseline, row, float(row["success_rate"])
            )
    return rows


def summary_to_csv_row(row: dict[str, Any]) -> dict[str, Any]:
    result = {key: row.get(key) for key in SUMMARY_COLUMNS}
    if row.get("all_null_reasons") is not None:
        result["all_null_reasons"] = json.dumps(row["all_null_reasons"], separators=(",", ":"))
    return result


def validate_null_reason_row(row: dict[str, str]) -> None:
    primary = row["primary_null_reason"]
    all_reasons = row["all_null_reasons"]
    if row["rpi"] != "":
        if primary != "" or all_reasons != "":
            raise ValueError("numeric RPI must have empty null reasons")
        return
    if primary == "" or all_reasons == "":
        raise ValueError("null RPI needs primary and all reasons")
    parsed = json.loads(all_reasons)
    if not isinstance(parsed, list) or parsed != ordered_null_reasons(parsed):
        raise ValueError("null reasons are not ordered/deduplicated")
    if parsed[0] != primary:
        raise ValueError("primary reason is not the first reason")


def make_event(
    record: dict[str, Any],
    event_type: str,
    client_ns: int,
    details: dict[str, Any],
) -> dict[str, Any]:
    condition = record["condition"]
    clock = record["clock"]
    return {
        "schema_version": "aneb-prototype-evidence-0.1",
        "campaign_id": record["campaign_id"],
        "run_id": record["run_id"],
        "campaign_mode": record["campaign_mode"],
        "run_index": record["run_index"],
        "condition_id": condition["id"],
        "condition_version": condition["version"],
        "nominal_interval_ms": condition["nominal_interval_ms"],
        "profile_manifest_sha256": record["profile_manifest_sha256"],
        "schedule_hash": record["schedule_hash"],
        "event_type": event_type,
        "client_monotonic_ns": client_ns,
        "clock_source": clock["source"],
        "clock_unit": clock["unit"],
        "clock_epoch": clock["epoch"],
        "clock_domain_id": clock["domain_id"],
        "source": "android",
        "details": details,
    }


def build_e2e_bundle(root: Path, profile: dict[str, Any], profile_hash: str) -> list[dict[str, Any]]:
    runs = [
        valid_run(
            profile_hash,
            profile,
            condition_id,
            run_index=index,
            campaign_mode="quick",
            run_id=f"run-quick-{index:02d}",
            t0_ns=9_000_000_000_000 + index * 1_000_000_000,
        )
        for index, condition_id in enumerate(CONDITION_ORDER, 1)
    ]
    events: list[dict[str, Any]] = []
    receipts: dict[str, Any] = {}
    for run in runs:
        condition = run["condition"]
        clock = run["clock"]
        offsets, terminal_offset = schedule_offsets(profile, condition["id"])
        events.append(
            make_event(
                run,
                "run_started",
                clock["t0_monotonic_ns"],
                {"t0_monotonic_ns": clock["t0_monotonic_ns"]},
            )
        )
        for seq, offset in enumerate(offsets, 1):
            events.append(
                make_event(
                    run,
                    "content_event",
                    clock["t0_monotonic_ns"] + offset * 1_000_000,
                    {
                        "seq": seq,
                        "planned_offset_ms": offset,
                        "payload_id": f"ref-{seq:04d}",
                    },
                )
            )
        receipt = {
            "receipt_version": "prototype-terminal-receipt-0.1",
            "terminal_status": "complete",
            "campaign_id": run["campaign_id"],
            "run_id": run["run_id"],
            "campaign_mode": run["campaign_mode"],
            "run_index": run["run_index"],
            "events_expected": 120,
            "events_received": 120,
            "emitted_event_count": 120,
            "profile_manifest_sha256": profile_hash,
            "condition_id": condition["id"],
            "condition_version": condition["version"],
            "nominal_interval_ms": condition["nominal_interval_ms"],
            "schedule_hash": run["schedule_hash"],
            "clock_domain_id": clock["domain_id"],
            "t0_monotonic_ns": clock["t0_monotonic_ns"],
            "client_monotonic_ns": clock["t0_monotonic_ns"] + terminal_offset * 1_000_000,
        }
        receipts[run["run_id"]] = receipt
        events.append(
            make_event(
                run,
                "terminal_event",
                receipt["client_monotonic_ns"],
                receipt,
            )
        )
    with (root / "events.jsonl").open("w", encoding="utf-8", newline="\n") as handle:
        for event in events:
            handle.write(json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n")
    (root / "terminal_receipts.json").write_text(
        json.dumps(receipts, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    write_csv(root / "runs.csv", RUN_COLUMNS, [run_to_csv_row(run) for run in runs])
    # The report embeds the same decimal representation that survives the
    # RFC4180 runs.csv round trip; this makes the byte chain canonical.
    parsed_runs = [csv_row_to_run(row) for row in read_csv(root / "runs.csv", RUN_COLUMNS)]
    summary_rows = compute_summary_rows(parsed_runs, profile, "quick", "complete")
    write_csv(root / "summary.csv", SUMMARY_COLUMNS, [summary_to_csv_row(row) for row in summary_rows])
    partial = copy.deepcopy(summary_rows[0])
    partial.update(
        {
            "campaign_status": "partial",
            "rpi": None,
            "primary_null_reason": "campaign_incomplete",
            "all_null_reasons": ["campaign_incomplete"],
        }
    )
    write_csv(root / "summary_partial.csv", SUMMARY_COLUMNS, [summary_to_csv_row(partial)])
    report_payload = {"summary": summary_rows}
    report_json = json.dumps(report_payload, sort_keys=True, separators=(",", ":"))
    (root / "report.html").write_text(
        "<!doctype html><meta charset=\"utf-8\"><script id=\"canonical-summary\" "
        "type=\"application/json\">" + report_json + "</script>\n",
        encoding="utf-8",
    )
    return runs


def verify_e2e_bundle(
    root: Path,
    profile: dict[str, Any],
    profile_hash: str,
    run_schema: dict[str, Any] | None = None,
) -> None:
    if run_schema is None:
        run_schema = load_json("run-record.schema.json")
    events: list[dict[str, Any]] = []
    for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines():
        if line:
            events.append(json.loads(line))
    receipts = json.loads((root / "terminal_receipts.json").read_text(encoding="utf-8"))
    run_rows = read_csv(root / "runs.csv", RUN_COLUMNS)
    runs = [csv_row_to_run(row) for row in run_rows]
    for run in runs:
        # CSV is an evidence artifact, not a trust boundary.  Reconstructed
        # records must pass the formal Draft 2020-12 run-record contract before
        # any cross-layer recomputation is accepted.
        expect_valid(run_schema, run, f"runs.csv schema_version for {run['run_id']}")
    if not events or not isinstance(receipts, dict):
        raise ValueError("raw evidence bundle is empty or malformed")
    for event in events:
        event_type = event.get("event_type")
        if not (
            event_type in ALLOWED_EVENT_TYPES
            or (isinstance(event_type, str) and event_type.startswith("diagnostic."))
        ):
            raise ValueError("unknown non-namespaced event type")
        if not isinstance(event.get("run_id"), str):
            raise ValueError("raw event has no run identity")
    raw_campaign_ids = {event.get("campaign_id") for event in events}
    if len(raw_campaign_ids) != 1 or None in raw_campaign_ids:
        raise ValueError("raw events do not share one campaign identity")
    raw_campaign_id = next(iter(raw_campaign_ids))
    groups: dict[str, list[dict[str, Any]]] = {}
    for event in events:
        groups.setdefault(event["run_id"], []).append(event)
    run_ids = {run["run_id"] for run in runs}
    if set(groups) != run_ids or set(receipts) != run_ids:
        raise ValueError("raw event/receipt/run id sets disagree")
    for run in runs:
        run_id = run["run_id"]
        if run["campaign_id"] != raw_campaign_id:
            raise ValueError("runs.csv campaign identity does not match raw authority")
        group = groups.get(run_id)
        if group is None:
            raise ValueError("run is absent from events.jsonl")
        started = [event for event in group if event["event_type"] == "run_started"]
        content = [event for event in group if event["event_type"] == "content_event"]
        terminal = [event for event in group if event["event_type"] == "terminal_event"]
        if len(started) != 1 or len(content) != 120 or len(terminal) != 1:
            raise ValueError("event topology is invalid")
        start = started[0]
        t0 = start["details"]["t0_monotonic_ns"]
        if t0 != run["clock"]["t0_monotonic_ns"]:
            raise ValueError("run t0 does not match raw evidence")
        identity = (
            run["campaign_id"],
            run["run_id"],
            run["campaign_mode"],
            run["run_index"],
            run["profile_manifest_sha256"],
            run["condition"]["id"],
            run["condition"]["version"],
            run["condition"]["nominal_interval_ms"],
            run["schedule_hash"],
            run["clock"]["source"],
            run["clock"]["epoch"],
            run["clock"]["domain_id"],
        )
        for event in group:
            if event.get("source") != "android" or not isinstance(event.get("client_monotonic_ns"), int):
                raise ValueError("raw evidence source/timestamp shape mismatch")
            event_identity = (
                event["campaign_id"],
                event["run_id"],
                event["campaign_mode"],
                event["run_index"],
                event["profile_manifest_sha256"],
                event["condition_id"],
                event["condition_version"],
                event["nominal_interval_ms"],
                event["schedule_hash"],
                event["clock_source"],
                event["clock_epoch"],
                event["clock_domain_id"],
            )
            if event_identity != identity:
                raise ValueError("raw evidence identity mismatch")
            if event["client_monotonic_ns"] < t0:
                raise ValueError("raw event precedes t0")
        condition_id = run["condition"]["id"]
        offsets, terminal_offset = schedule_offsets(profile, condition_id)
        times: list[int] = []
        for expected_seq, (event, expected_offset) in enumerate(zip(content, offsets), 1):
            details = event["details"]
            if details["seq"] != expected_seq or details["planned_offset_ms"] != expected_offset:
                raise ValueError("content sequence/schedule mismatch")
            if details["payload_id"] != f"ref-{expected_seq:04d}":
                raise ValueError("content payload identity mismatch")
            expected_ns = t0 + expected_offset * 1_000_000
            if event["client_monotonic_ns"] != expected_ns:
                raise ValueError("content timestamp does not match schedule")
            times.append(event["client_monotonic_ns"])
        receipt = receipts.get(run_id)
        if receipt is None or terminal[0]["details"] != receipt:
            raise ValueError("terminal receipt is absent or mismatched")
        receipt_identity = (
            receipt.get("campaign_id"),
            receipt.get("run_id"),
            receipt.get("campaign_mode"),
            receipt.get("run_index"),
            receipt.get("profile_manifest_sha256"),
            receipt.get("condition_id"),
            receipt.get("condition_version"),
            receipt.get("nominal_interval_ms"),
            receipt.get("schedule_hash"),
            "android.os.SystemClock.elapsedRealtimeNanos",
            "device_boot",
            receipt.get("clock_domain_id"),
        )
        if receipt_identity != identity:
            raise ValueError("terminal receipt identity does not match raw/run identity")
        if receipt.get("t0_monotonic_ns") != t0:
            raise ValueError("terminal receipt t0 does not match run")
        if receipt.get("terminal_status") != "complete" or receipt.get("emitted_event_count") != 120:
            raise ValueError("terminal receipt completion fields are invalid")
        if receipt["client_monotonic_ns"] != t0 + terminal_offset * 1_000_000:
            raise ValueError("terminal timestamp does not match schedule")
        expected = derive_raw_metrics(
            times,
            receipt["client_monotonic_ns"],
            run["condition"]["nominal_interval_ms"],
            t0,
        )
        for key, value in expected.items():
            serialized_expected = float(scalar(value))
            if not math.isclose(float(run["metrics"][key]), serialized_expected, rel_tol=0, abs_tol=1e-9):
                raise ValueError(f"runs.csv metric mismatch for {key}")
    expected_summary = compute_summary_rows(runs, profile, "quick", "complete")
    actual_summary = read_csv(root / "summary.csv", SUMMARY_COLUMNS)
    expected_csv = [summary_to_csv_row(row) for row in expected_summary]
    for actual, expected in zip(actual_summary, expected_csv):
        if actual != {key: scalar(expected.get(key)) for key in SUMMARY_COLUMNS}:
            raise ValueError("summary.csv is not recomputed from runs")
        validate_null_reason_row(actual)
    if len(actual_summary) != len(expected_csv):
        raise ValueError("summary.csv row count mismatch")
    partial_rows = read_csv(root / "summary_partial.csv", SUMMARY_COLUMNS)
    if len(partial_rows) != 1:
        raise ValueError("partial summary fixture row count mismatch")
    validate_null_reason_row(partial_rows[0])
    if partial_rows[0]["rpi"] != "" or partial_rows[0]["primary_null_reason"] != "campaign_incomplete":
        raise ValueError("partial summary null semantics mismatch")
    report_text = (root / "report.html").read_text(encoding="utf-8")
    match = re.search(
        r'<script id="canonical-summary" type="application/json">(.*?)</script>',
        report_text,
        flags=re.DOTALL,
    )
    if not match:
        raise ValueError("report has no embedded canonical summary")
    report_payload = json.loads(match.group(1))
    if report_payload != {"summary": expected_summary}:
        raise ValueError("report summary is not the canonical summary")


def mutate_and_expect_bundle_failure(
    root: Path,
    profile: dict[str, Any],
    profile_hash: str,
    label: str,
    mutate: Callable[[Path], None],
) -> None:
    build_e2e_bundle(root, profile, profile_hash)
    mutate(root)
    expect_failure(lambda: verify_e2e_bundle(root, profile, profile_hash), label)


def version_fixture(contract_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "schema_version": "aneb-prototype-version-0.1",
        "contract_files": list(CONTRACT_FILES),
        "contract_sha256": dict(contract_hashes),
        "schedule_sha256": dict(EXPECTED_SCHEDULE_HASHES),
    }


def validate_version_fixture(version: dict[str, Any], contract_hashes: dict[str, str]) -> None:
    if version.get("schema_version") != "aneb-prototype-version-0.1":
        raise ValueError("VERSION schema version is not exact")
    if version["contract_files"] != CONTRACT_FILES:
        raise ValueError("VERSION contract set/order is not exact")
    if set(version["contract_sha256"]) != set(CONTRACT_FILES):
        raise ValueError("VERSION contract hash set is not exact")
    if version["contract_sha256"] != contract_hashes:
        raise ValueError("VERSION contract hashes do not match canonical bytes")
    if version["schedule_sha256"] != EXPECTED_SCHEDULE_HASHES:
        raise ValueError("VERSION schedule hash bindings do not match")
    if "evidence-schema.json" in version["contract_files"]:
        raise ValueError("evidence-schema.json is outside the four-contract package")


def assert_manifest_oracle(profile: dict[str, Any]) -> None:
    if [item["id"] for item in profile["conditions"]] != CONDITION_ORDER:
        raise AssertionError("manifest condition order drift")
    for condition_id in CONDITION_ORDER:
        actual = condition_from_manifest(profile, condition_id)
        expected = EXPECTED_CONDITION_ORACLE[condition_id]
        for key, value in expected.items():
            if actual[key] != value:
                raise AssertionError(f"{condition_id} manifest field {key} drift")
        offsets, terminal = schedule_offsets(profile, condition_id)
        if offsets[0] != expected["planned_first_event_offset_ms"]:
            raise AssertionError(f"{condition_id} first offset drift")
        if offsets[-1] != expected["planned_last_event_offset_ms"]:
            raise AssertionError(f"{condition_id} last offset drift")
        if terminal != expected["planned_terminal_offset_ms"]:
            raise AssertionError(f"{condition_id} terminal offset drift")
        digest = hashlib.sha256(canonical_schedule(profile, condition_id)).hexdigest()
        if digest != EXPECTED_SCHEDULE_HASHES[condition_id] or actual["schedule_sha256"] != digest:
            raise AssertionError(f"{condition_id} schedule identity drift")
    plans = {plan["mode"]: plan["condition_order"] for plan in profile["campaign_plans"]}
    if plans["quick"] != CONDITION_ORDER:
        raise AssertionError("quick campaign order drift")
    if plans["acceptance"] != CONDITION_ORDER * 3:
        raise AssertionError("acceptance campaign order drift")


def main() -> int:
    schemas = {
        "capabilities": load_json("capabilities.schema.json"),
        "run-record": load_json("run-record.schema.json"),
    }
    policy = load_json("score-policy.json")
    profile = load_json("profile-manifest.json")
    try:
        from jsonschema import Draft202012Validator
    except ModuleNotFoundError as exc:  # pragma: no cover
        raise RuntimeError("jsonschema is required") from exc
    for schema in schemas.values():
        Draft202012Validator.check_schema(schema)
    checks: list[str] = ["Draft 2020-12 schemas valid"]

    profile_hash = hashlib.sha256(canonical_file_bytes("profile-manifest.json")).hexdigest()
    assert profile_hash == "ed440d42dfcc849cb7bb24c52f6c0623057d83c4d97af1f86b024703eb9370eb"
    assert policy["policy_id"] == "rpi-0.1"
    assert policy["display_name"] == "Relative Prototype Index (same-campaign synthetic comparison)"
    assert policy["success_rate_definition"] == "current_condition_successful_runs_divided_by_current_condition_planned_runs"
    assert policy["median"]["algorithm"] == "arithmetic_mean_of_two_middle_sorted_values"
    assert policy["null_reasons"] == EXPECTED_NULL_PRECEDENCE
    assert policy["null_reason_precedence"] == EXPECTED_NULL_PRECEDENCE
    assert policy["null_reason_fields"] == ["primary_null_reason", "all_null_reasons"]
    assert "clamp" in policy["final_expression"] and "round_half_away_from_zero" in policy["final_expression"]
    checks.append("policy precedence, units, median and RPI boundaries")

    assert_manifest_oracle(profile)
    checks.append("loaded manifest schedule oracle and exact B/S/U parameters")
    drifted = copy.deepcopy(profile)
    drifted["conditions"][0]["initial_delay_ms"] = 201
    expect_failure(
        lambda: assert_manifest_oracle(drifted),
        "manifest parameter/schedule mismatch",
    )
    reordered = copy.deepcopy(profile)
    reordered["conditions"] = list(reversed(reordered["conditions"]))
    expect_failure(lambda: assert_manifest_oracle(reordered), "manifest condition order drift")
    for condition_id in CONDITION_ORDER:
        canonical = canonical_schedule(profile, condition_id)
        assert hashlib.sha256(canonical).hexdigest() == EXPECTED_SCHEDULE_HASHES[condition_id]
        assert canonical.startswith(b"seq,planned_offset_ms,payload_id\n")
        assert canonical.endswith(b"\n") and len(canonical.splitlines()) == 121
    checks.append("three canonical schedule hashes and bytes")

    contract_hashes = {
        name: hashlib.sha256(canonical_file_bytes(name)).hexdigest()
        for name in CONTRACT_FILES
    }
    version = version_fixture(contract_hashes)
    validate_version_fixture(version, contract_hashes)
    bad_version = copy.deepcopy(version)
    bad_version["contract_files"].append("evidence-schema.json")
    expect_failure(lambda: validate_version_fixture(bad_version, contract_hashes), "VERSION evidence-schema package")
    checks.append("VERSION exact four-contract set/hash fixture")

    capabilities = valid_capabilities(profile_hash, profile)
    expect_valid(schemas["capabilities"], capabilities, "canonical capabilities")
    duplicate = copy.deepcopy(capabilities)
    duplicate["conditions"][1]["id"] = duplicate["conditions"][0]["id"]
    expect_invalid(schemas["capabilities"], duplicate, "capability duplicate")
    reordered = copy.deepcopy(capabilities)
    reordered["conditions"] = [reordered["conditions"][1], reordered["conditions"][0], reordered["conditions"][2]]
    expect_invalid(schemas["capabilities"], reordered, "capability reorder")
    nominal_mismatch = copy.deepcopy(capabilities)
    nominal_mismatch["conditions"][0]["nominal_interval_ms"] = 51
    expect_invalid(schemas["capabilities"], nominal_mismatch, "capability nominal mismatch")
    hash_mismatch = copy.deepcopy(capabilities)
    hash_mismatch["conditions"][1]["schedule_sha256"] = "a" * 64
    expect_invalid(schemas["capabilities"], hash_mismatch, "capability schedule hash mismatch")
    profile_mismatch = copy.deepcopy(capabilities)
    profile_mismatch["profile_manifest_sha256"] = "b" * 64
    expect_invalid(schemas["capabilities"], profile_mismatch, "capability profile hash mismatch")
    prefixed = copy.deepcopy(capabilities)
    prefixed["conditions"][0]["schedule_sha256"] = "sha256:" + prefixed["conditions"][0]["schedule_sha256"]
    expect_invalid(schemas["capabilities"], prefixed, "prefixed hash counterexample")
    checks.append("capability order, nominal, hash and profile negatives")

    for mode, max_index in [("quick", 3), ("acceptance", 9)]:
        for index in range(1, max_index + 1):
            record = valid_run(profile_hash, profile, run_index=index, campaign_mode=mode)
            expect_valid(schemas["run-record"], record, f"{mode} index {index}")
    for mode, index, wrong_condition in [
        ("quick", 1, "slow_v0.1"),
        ("quick", 3, "baseline_v0.1"),
        ("acceptance", 9, "baseline_v0.1"),
    ]:
        candidate = valid_run(profile_hash, profile, run_index=index, campaign_mode=mode)
        wrong = condition_from_manifest(profile, wrong_condition)
        candidate["condition"] = {
            "id": wrong_condition,
            "version": wrong["version"],
            "nominal_interval_ms": wrong["nominal_interval_ms"],
        }
        candidate["schedule_hash"] = wrong["schedule_sha256"]
        expect_invalid(schemas["run-record"], candidate, f"{mode} index/condition survivor")
    checks.append("all 12 mode/index positives and three cycle negatives")

    run = valid_run(profile_hash, profile, run_index=1, campaign_mode="acceptance")
    expect_valid(schemas["run-record"], run, "canonical complete run")
    score_false = copy.deepcopy(run)
    score_false["score_eligible"] = False
    expect_invalid(schemas["run-record"], score_false, "complete score_eligible false")
    for metric in METRIC_KEYS:
        candidate = copy.deepcopy(run)
        candidate["metrics"][metric] = None
        expect_invalid(schemas["run-record"], candidate, f"successful {metric} null")
    for field in ["attempt_started_at_utc", "attempt_ended_at_utc"]:
        candidate = copy.deepcopy(run)
        candidate[field] = None
        expect_invalid(schemas["run-record"], candidate, f"successful {field} null")
    for value in [False, None]:
        candidate = copy.deepcopy(run)
        candidate["terminal_receipt_valid"] = value
        expect_invalid(schemas["run-record"], candidate, "successful invalid terminal receipt")
    interrupted_survivor = copy.deepcopy(run)
    interrupted_survivor.update(
        {
            "run_status": "interrupted",
            "task_success": False,
            "score_eligible": False,
            "failure_reason": None,
        }
    )
    expect_invalid(schemas["run-record"], interrupted_survivor, "interrupted valid-receipt/null-reason survivor")
    expect_valid(schemas["run-record"], partial_interrupted_run(profile_hash, profile), "partial interrupted matrix")
    for status, reason in [
        ("cancelled", "cancelled"),
        ("ttft_timeout", "ttft_timeout"),
        ("server_rejected", "server_rejected"),
        ("incompatible", "contract_mismatch"),
    ]:
        failed = copy.deepcopy(run)
        failed.update(
            {
                "run_status": status,
                "task_success": False,
                "score_eligible": False,
                "events_received": 0,
                "terminal_receipt_valid": None,
                "failure_reason": reason,
                "metrics": {key: None for key in METRIC_KEYS},
            }
        )
        expect_valid(schemas["run-record"], failed, f"{status} exact failure topology")
    cancelled_partial = partial_interrupted_run(profile_hash, profile)
    cancelled_partial["run_status"] = "cancelled"
    cancelled_partial["failure_reason"] = "cancelled"
    expect_valid(schemas["run-record"], cancelled_partial, "cancelled partial matrix")
    for status, wrong_reason in [
        ("cancelled", "stream_interrupted"),
        ("cancelled", None),
        ("ttft_timeout", "cancelled"),
        ("server_rejected", None),
        ("incompatible", "server_rejected"),
    ]:
        survivor = copy.deepcopy(run)
        survivor.update(
            {
                "run_status": status,
                "task_success": False,
                "score_eligible": False,
                "failure_reason": wrong_reason,
            }
        )
        expect_invalid(schemas["run-record"], survivor, f"{status} wrong failure reason survivor")
    checks.append("closed failure status/reason/receipt/event/metric topology")
    invalid_sequence = copy.deepcopy(run)
    invalid_sequence.update(
        {
            "run_status": "invalid_sequence",
            "task_success": False,
            "score_eligible": False,
            "terminal_receipt_valid": None,
            "failure_reason": "invalid_sequence",
            "metrics": {key: None for key in METRIC_KEYS},
        }
    )
    expect_valid(schemas["run-record"], invalid_sequence, "invalid sequence all-null matrix")
    invalid_sequence_numeric = copy.deepcopy(invalid_sequence)
    invalid_sequence_numeric["metrics"]["ttft_ms"] = 1.0
    expect_invalid(schemas["run-record"], invalid_sequence_numeric, "invalid sequence retained metrics")
    clock_invalid = copy.deepcopy(run)
    clock_invalid.update(
        {
            "run_status": "incompatible",
            "task_success": False,
            "score_eligible": False,
            "events_received": 0,
            "terminal_receipt_valid": None,
            "failure_reason": "clock_domain_invalid",
            "metrics": {key: None for key in METRIC_KEYS},
        }
    )
    expect_valid(schemas["run-record"], clock_invalid, "clock invalid all-null matrix")
    retained = copy.deepcopy(clock_invalid)
    retained["metrics"]["ttft_ms"] = 1.0
    expect_invalid(schemas["run-record"], retained, "clock invalid retained metrics")
    invalid_evidence = copy.deepcopy(clock_invalid)
    invalid_evidence["failure_reason"] = "invalid_evidence"
    expect_valid(schemas["run-record"], invalid_evidence, "invalid evidence all-null matrix")
    invalid_evidence_numeric = copy.deepcopy(invalid_evidence)
    invalid_evidence_numeric["metrics"]["ttft_ms"] = 1.0
    expect_invalid(schemas["run-record"], invalid_evidence_numeric, "invalid evidence retained metrics")
    not_started = not_started_run(profile_hash, profile)
    expect_valid(schemas["run-record"], not_started, "not-started all-null matrix")
    for metric in METRIC_KEYS:
        candidate = copy.deepcopy(not_started)
        candidate["metrics"][metric] = 0
        expect_invalid(schemas["run-record"], candidate, f"not-started {metric}=0")
    candidate = copy.deepcopy(not_started)
    candidate["terminal_receipt_valid"] = False
    expect_invalid(schemas["run-record"], candidate, "not-started receipt false")
    for field in ["attempt_started_at_utc", "attempt_ended_at_utc"]:
        candidate = copy.deepcopy(not_started)
        candidate[field] = "2026-08-28T10:00:00Z"
        expect_invalid(schemas["run-record"], candidate, f"not-started {field} non-null")
    checks.append("complete/partial/null/receipt/eligibility schema matrix")

    t0 = 9_000_000_000_000
    offsets, done_offset = schedule_offsets(profile, "baseline_v0.1")
    absolute = [t0 + offset * 1_000_000 for offset in offsets]
    shifted = [value + 77_000_000_000 for value in absolute]
    first_metrics = derive_raw_metrics(absolute, t0 + done_offset * 1_000_000, 50, t0)
    shifted_metrics = derive_raw_metrics(shifted, t0 + 77_000_000_000 + done_offset * 1_000_000, 50, t0 + 77_000_000_000)
    assert first_metrics == shifted_metrics
    utc_reversed = copy.deepcopy(run)
    utc_reversed["attempt_started_at_utc"], utc_reversed["attempt_ended_at_utc"] = (
        utc_reversed["attempt_ended_at_utc"],
        utc_reversed["attempt_started_at_utc"],
    )
    expect_valid(schemas["run-record"], utc_reversed, "UTC reversal remains schema-valid")
    assert utc_reversed["metrics"] == run["metrics"]
    regression = absolute.copy()
    regression[1] = regression[0] - 1
    expect_failure(lambda: derive_raw_metrics(regression, t0 + done_offset * 1_000_000, 50, t0), "timestamp regression")
    assert not is_stall(500_000_000, 50) and is_stall(500_000_001, 50)
    assert not is_stall(800_000_000, 200) and is_stall(800_000_001, 200)
    assert median([1, 3]) == 2.0 and median([1, 3, 5]) == 3.0
    assert ordered_null_reasons(["invalid_evidence", "campaign_incomplete", "invalid_evidence"]) == [
        "campaign_incomplete",
        "invalid_evidence",
    ]
    multi_reason = {
        "rpi": "",
        "primary_null_reason": "campaign_incomplete",
        "all_null_reasons": '["campaign_incomplete","invalid_evidence"]',
    }
    validate_null_reason_row(multi_reason)
    wrong_primary = dict(multi_reason)
    wrong_primary["primary_null_reason"] = "invalid_evidence"
    expect_failure(lambda: validate_null_reason_row(wrong_primary), "null primary precedence")
    duplicate_reason = dict(multi_reason)
    duplicate_reason["all_null_reasons"] = '["campaign_incomplete","campaign_incomplete"]'
    expect_failure(lambda: validate_null_reason_row(duplicate_reason), "null reason deduplication")
    checks.append("absolute t0 deltas, UTC independence, regression, strict stall and median")
    checks.append("ordered/deduplicated null reasons and primary-first precedence")

    with tempfile.TemporaryDirectory(prefix="aneb-prototype-evidence-") as directory:
        bundle = Path(directory)
        build_e2e_bundle(bundle, profile, profile_hash)
        verify_e2e_bundle(bundle, profile, profile_hash)

        def mutate_raw(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "content_event" and event["details"]["seq"] == 2:
                    event["client_monotonic_ns"] += 1_000_000
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_raw_campaign(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "content_event":
                    event["campaign_id"] = "campaign-forged"
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_terminal(path: Path) -> None:
            receipts = json.loads((path / "terminal_receipts.json").read_text(encoding="utf-8"))
            first = next(iter(receipts))
            receipts[first]["terminal_status"] = "tampered"
            (path / "terminal_receipts.json").write_text(
                json.dumps(receipts, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )

        def mutate_terminal_domain(path: Path) -> None:
            receipts = json.loads((path / "terminal_receipts.json").read_text(encoding="utf-8"))
            first = next(iter(receipts))
            forged_domain = "boot-session-forged"
            receipts[first]["clock_domain_id"] = forged_domain
            (path / "terminal_receipts.json").write_text(
                json.dumps(receipts, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["run_id"] == first and event["event_type"] == "terminal_event":
                    event["details"]["clock_domain_id"] = forged_domain
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_unknown_event(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            forged = json.loads(lines[1])
            forged["event_type"] = "forged"
            lines.append(json.dumps(forged, sort_keys=True, separators=(",", ":")))
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_runs(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            rows[0]["completion_ms"] = scalar(float(rows[0]["completion_ms"]) + 1)
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)

        def mutate_run_schema_version(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            rows[0]["schema_version"] = "forged"
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)

        def mutate_downstream_campaign(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            for row in rows:
                row["campaign_id"] = "campaign-forged"
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)
            forged_runs = [csv_row_to_run(row) for row in rows]
            forged_summary = compute_summary_rows(forged_runs, profile, "quick", "complete")
            write_csv(path / "summary.csv", SUMMARY_COLUMNS, [summary_to_csv_row(row) for row in forged_summary])
            report_payload = {"summary": forged_summary}
            report_json = json.dumps(report_payload, sort_keys=True, separators=(",", ":"))
            (path / "report.html").write_text(
                "<!doctype html><meta charset=\"utf-8\"><script id=\"canonical-summary\" "
                "type=\"application/json\">" + report_json + "</script>\n",
                encoding="utf-8",
            )

        def mutate_summary(path: Path) -> None:
            rows = read_csv(path / "summary.csv", SUMMARY_COLUMNS)
            rows[0]["rpi"] = "99"
            write_csv(path / "summary.csv", SUMMARY_COLUMNS, rows)

        def mutate_report(path: Path) -> None:
            text = (path / "report.html").read_text(encoding="utf-8")
            replaced = text.replace('"rpi":100', '"rpi":99', 1)
            if replaced == text:
                raise ValueError("report fixture has no RPI token")
            (path / "report.html").write_text(replaced, encoding="utf-8")

        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "raw event tamper", mutate_raw)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "raw event campaign tamper", mutate_raw_campaign)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal receipt tamper", mutate_terminal)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal nested domain tamper", mutate_terminal_domain)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "unknown event type", mutate_unknown_event)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "runs.csv tamper", mutate_runs)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "runs.csv schema-version tamper", mutate_run_schema_version)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "downstream campaign rewrite", mutate_downstream_campaign)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "summary.csv tamper", mutate_summary)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "report tamper", mutate_report)
        mutate_and_expect_bundle_failure(
            bundle,
            profile,
            profile_hash,
            "clock domain splice",
            lambda path: _mutate_event_field(path, "content_event", 2, "clock_domain_id", "boot-session-0002"),
        )
        mutate_and_expect_bundle_failure(
            bundle,
            profile,
            profile_hash,
            "clock timestamp regression",
            lambda path: _mutate_event_timestamp_regression(path),
        )
    checks.append("actual events/terminal/runs/summary/RPI/report chain, formal run schema and tamper stages")
    checks.append("clock-domain splice and timestamp-regression evidence negatives")

    print(f"PASS: {len(checks)} contract checks")
    for item in checks:
        print(f"  - {item}")
    print(f"profile_manifest_sha256={profile_hash}")
    for name in CONTRACT_FILES:
        print(f"{name}_sha256={contract_hashes[name]}")
    return 0


def _mutate_event_field(path: Path, event_type: str, seq: int, field: str, value: Any) -> None:
    lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        event = json.loads(line)
        if event["event_type"] == event_type and event["details"].get("seq") == seq:
            event[field] = value
            lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
            break
    (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


def _mutate_event_timestamp_regression(path: Path) -> None:
    lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
    previous: int | None = None
    for index, line in enumerate(lines):
        event = json.loads(line)
        if event["event_type"] != "content_event":
            continue
        if event["details"]["seq"] == 1:
            previous = event["client_monotonic_ns"]
        elif event["details"]["seq"] == 2:
            if previous is None:
                raise ValueError("missing first content event")
            event["client_monotonic_ns"] = previous - 1
            lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
            break
    (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, RuntimeError, ValueError, KeyError, TypeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
