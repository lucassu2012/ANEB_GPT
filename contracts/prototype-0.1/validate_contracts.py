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
EXPECTED_PROFILE_HASH = "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc"
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
FAILURE_REASON_DOMAIN = [
    "contract_mismatch",
    "invalid_sequence",
    "stream_interrupted",
    "cancelled",
    "ttft_timeout",
    "server_rejected",
    "not_started",
    "clock_domain_invalid",
    "clock_domain_mismatch",
    "mandatory_metric_missing",
    "invalid_evidence",
    None,
]
ALLOWED_FAILURE_REASONS_BY_STATUS = {
    "complete": {None},
    "interrupted": {"stream_interrupted", "mandatory_metric_missing"},
    "cancelled": {"cancelled"},
    "incompatible": {
        "contract_mismatch",
        "clock_domain_invalid",
        "clock_domain_mismatch",
        "invalid_evidence",
    },
    "invalid_sequence": {"invalid_sequence"},
    "ttft_timeout": {"ttft_timeout"},
    "server_rejected": {"server_rejected"},
    "not_started": {"not_started"},
}
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
CANONICAL_BUNDLE_FILES = [
    "meta.json",
    "events.jsonl",
    "runs.csv",
    "summary.csv",
    "report.html",
    "run.log",
    "manifest.json",
]
ALLOWED_EVENT_TYPES = {
    "run_started",
    "content_event",
    "terminal_event",
    "run_failed",
    "run_cancelled",
}
EVIDENCE_SCHEMA_VERSION = "aneb-prototype-evidence-0.1"
PROTOCOL_VERSION = "prototype-stream-0.1"
PROFILE_ID = "streaming_text_reference_v0.1"
PROFILE_VERSION = "0.1"
TERMINAL_RECEIPT_VERSION = "prototype-terminal-receipt-0.1"
SCORING_EVENT_TYPES = {"run_started", "content_event", "terminal_event"}
SERIALIZED_NUMBER_TOLERANCE = 0.5e-6 + 1e-12


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


def derive_partial_metrics(
    event_times_ns: list[int],
    nominal_interval_ms: int,
    t0_ns: int,
) -> dict[str, float | int | None]:
    """Derive the field-by-field partial matrix without inventing completion."""
    if t0_ns < 0 or not event_times_ns:
        raise ValueError("at least one event and a non-negative t0 are required")
    if any(timestamp < t0_ns for timestamp in event_times_ns):
        raise ValueError("timestamps precede t0")
    gaps = [right - left for left, right in zip(event_times_ns, event_times_ns[1:])]
    if any(gap <= 0 for gap in gaps):
        raise ValueError("timestamp regression in partial stream")
    first_ns = event_times_ns[0]
    if len(event_times_ns) == 1:
        return {
            "ttft_ms": (first_ns - t0_ns) / 1_000_000,
            "completion_ms": None,
            "stream_span_ms": None,
            "stream_event_rate_eps": None,
            "stall_threshold_ms": None,
            "stall_count": None,
            "stall_duration_ms": None,
            "stall_fraction": None,
        }
    nominal_ns = nominal_interval_ms * 1_000_000
    stalls = [gap for gap in gaps if is_stall(gap, nominal_interval_ms)]
    span_ns = event_times_ns[-1] - first_ns
    if span_ns <= 0:
        raise ValueError("non-positive partial stream span")
    duration_ns = sum(gap - nominal_ns for gap in stalls)
    return {
        "ttft_ms": (first_ns - t0_ns) / 1_000_000,
        "completion_ms": None,
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
    result["metrics"] = derive_partial_metrics([first, second], 50, t0_ns)
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


def write_bundle_metadata(
    root: Path,
    runs: list[dict[str, Any]],
    profile: dict[str, Any],
    profile_hash: str,
    campaign_mode: str,
    campaign_status: str,
) -> None:
    """Create the seven-file campaign envelope used by the review fixture."""
    plan = next(item for item in profile["campaign_plans"] if item["mode"] == campaign_mode)
    campaign_id = runs[0]["campaign_id"] if runs else "campaign-0001"
    meta = {
        "schema_version": EVIDENCE_SCHEMA_VERSION,
        "campaign_id": campaign_id,
        "campaign_mode": campaign_mode,
        "campaign_status": campaign_status,
        "started_at_utc": "2026-08-28T10:00:00Z",
        "ended_at_utc": "2026-08-28T10:01:00Z",
        "claim_scope": "application_end_to_end_to_probe_node",
        "evidence_mode": "synthetic_application_impairment",
        "impairment_layer": "application",
        "score_policy_id": "rpi-0.1",
        "profile_manifest_sha256": profile_hash,
        "clock_contract": {
            "source": "android.os.SystemClock.elapsedRealtimeNanos",
            "unit": "ns",
            "epoch": "device_boot",
            "includes_deep_sleep": True,
            "domain_identity": "per-run opaque boot/session clock_domain_id",
        },
        "profile": {
            "id": profile["workload"]["id"],
            "version": profile["workload"]["version"],
            "sha256": profile_hash,
        },
        "product": {"version": "prototype-0.1", "git_commit": "0" * 40},
        "android_app": {"version_name": "0.1.0", "version_code": 1, "apk_sha256": "0" * 64},
        "server": {
            "version": "dev",
            "binary_sha256": "a" * 64,
            "protocol_version": PROTOCOL_VERSION,
        },
        "device": {"manufacturer": "fixture", "model": "fixture", "os_release": "fixture", "sdk_int": 29},
        "transport": {"mode": "lan", "acceptance_path": True},
        "run_plan": {"planned_runs": len(plan["condition_order"]), "order": list(plan["condition_order"])},
        "conditions": [
            {
                "id": condition["id"],
                "version": condition["version"],
                "nominal_interval_ms": condition["nominal_interval_ms"],
                "schedule_sha256": condition["schedule_sha256"],
            }
            for condition in profile["conditions"]
        ],
    }
    (root / "meta.json").write_text(
        json.dumps(meta, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    (root / "run.log").write_text(
        "2026-08-28T10:01:00Z INFO fixture campaign.verified\n",
        encoding="utf-8",
    )
    media_types = {
        "meta.json": "application/json",
        "events.jsonl": "application/x-ndjson",
        "runs.csv": "text/csv",
        "summary.csv": "text/csv",
        "report.html": "text/html",
        "run.log": "text/plain",
    }
    artifacts = []
    for name in CANONICAL_BUNDLE_FILES:
        if name == "manifest.json":
            continue
        data = (root / name).read_bytes()
        artifacts.append(
            {
                "path": name,
                "media_type": media_types[name],
                "size_bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )
    manifest = {
        "manifest_version": "aneb-prototype-manifest-0.1",
        "campaign_id": campaign_id,
        "publication_status": "verified",
        "created_at_utc": "2026-08-28T10:01:00Z",
        "artifacts": artifacts,
    }
    (root / "manifest.json").write_text(
        json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def refresh_bundle_manifest(root: Path) -> None:
    """Refresh only fixture artifact hashes after an intentional test mutation."""
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["artifacts"] = []
    media_types = {
        "meta.json": "application/json",
        "events.jsonl": "application/x-ndjson",
        "runs.csv": "text/csv",
        "summary.csv": "text/csv",
        "report.html": "text/html",
        "run.log": "text/plain",
    }
    for name in CANONICAL_BUNDLE_FILES:
        if name == "manifest.json":
            continue
        data = (root / name).read_bytes()
        manifest["artifacts"].append(
            {
                "path": name,
                "media_type": media_types[name],
                "size_bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )
    manifest_path.write_text(
        json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def refresh_bundle_metadata_for_test(root: Path, profile: dict[str, Any]) -> None:
    """Keep metadata aligned when a regression mutator is intentionally coordinated."""
    meta_path = root / "meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    events = [
        json.loads(line)
        for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines()
        if line
    ]
    rows = read_csv(root / "runs.csv", RUN_COLUMNS)
    if events:
        meta["campaign_id"] = events[0]["campaign_id"]
    if rows:
        mode = rows[0]["campaign_mode"]
        meta["campaign_mode"] = mode
        plans = [item for item in profile["campaign_plans"] if item["mode"] == mode]
        if len(plans) == 1:
            meta["run_plan"] = {
                "planned_runs": len(plans[0]["condition_order"]),
                "order": list(plans[0]["condition_order"]),
            }
        try:
            parsed_runs = [csv_row_to_run(row) for row in rows]
        except (TypeError, ValueError):
            parsed_runs = []
        if parsed_runs:
            meta["campaign_status"] = campaign_status_from_runs(parsed_runs)
    meta_path.write_text(
        json.dumps(meta, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


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


def parse_integer(value: str, label: str, nullable: bool = True) -> int | None:
    if value == "":
        if nullable:
            return None
        raise ValueError(f"{label} is unexpectedly null")
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)", value):
        raise ValueError(f"{label} is not canonical base-10 integer text")
    return int(value)


def parse_number(value: str, label: str) -> float | None:
    if value == "":
        return None
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?", value):
        raise ValueError(f"{label} is not a canonical decimal with at most six places")
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"{label} is not finite")
    return number


def csv_row_to_run(row: dict[str, str]) -> dict[str, Any]:
    metrics: dict[str, Any] = {}
    for key in METRIC_KEYS:
        if key == "stall_count":
            metrics[key] = parse_integer(row[key], key)
        else:
            metrics[key] = parse_number(row[key], key)
    t0 = parse_integer(row["t0_monotonic_ns"], "t0_monotonic_ns")
    return {
        "schema_version": row["schema_version"],
        "campaign_id": row["campaign_id"],
        "run_id": row["run_id"],
        "campaign_mode": row["campaign_mode"],
        "run_index": parse_integer(row["run_index"], "run_index", nullable=False),
        "profile_manifest_sha256": row["profile_manifest_sha256"],
        "condition": {
            "id": row["condition_id"],
            "version": row["condition_version"],
            "nominal_interval_ms": parse_integer(row["nominal_interval_ms"], "nominal_interval_ms", nullable=False),
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
        "events_expected": parse_integer(row["events_expected"], "events_expected", nullable=False),
        "events_received": parse_integer(row["events_received"], "events_received", nullable=False),
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
    # RPI is decided independently for each condition row.  A failed or
    # incomplete condition therefore gets its own null reasons without
    # erasing eligible rows from the same complete Acceptance campaign.
    baseline = rows[0]
    if baseline["primary_null_reason"] is None:
        for row in rows:
            if row["primary_null_reason"] is None:
                row["rpi"] = rpi_value(baseline, row, float(row["success_rate"]))
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
        "schema_version": EVIDENCE_SCHEMA_VERSION,
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


def status_record(base: dict[str, Any], status: str, profile: dict[str, Any]) -> dict[str, Any]:
    """Build an otherwise-valid run record for a closed failure topology."""
    if status == "complete":
        return base
    if status == "not_started":
        result = copy.deepcopy(base)
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
        result["metrics"] = {key: None for key in METRIC_KEYS}
        return result
    result = copy.deepcopy(base)
    result.update(
        {
            "run_status": status,
            "task_success": False,
            "score_eligible": False,
            "terminal_receipt_valid": None,
        }
    )
    if status in {"interrupted", "cancelled"}:
        offsets, _ = schedule_offsets(profile, result["condition"]["id"])
        partial_count = 2
        t0_ns = result["clock"]["t0_monotonic_ns"]
        times = [t0_ns + offset * 1_000_000 for offset in offsets[:partial_count]]
        result["events_received"] = partial_count
        result["failure_reason"] = "cancelled" if status == "cancelled" else "stream_interrupted"
        result["metrics"] = derive_partial_metrics(
            times,
            result["condition"]["nominal_interval_ms"],
            t0_ns,
        )
        return result
    if status not in {"incompatible", "invalid_sequence", "ttft_timeout", "server_rejected"}:
        raise ValueError(f"unsupported fixture run status: {status}")
    result.update(
        {
            "events_received": 0,
            "failure_reason": {
                "incompatible": "contract_mismatch",
                "invalid_sequence": "invalid_sequence",
                "ttft_timeout": "ttft_timeout",
                "server_rejected": "server_rejected",
            }[status],
            "metrics": {key: None for key in METRIC_KEYS},
        }
    )
    return result


def build_e2e_bundle(
    root: Path,
    profile: dict[str, Any],
    profile_hash: str,
    campaign_mode: str = "quick",
    statuses: dict[int, str] | None = None,
    campaign_status_override: str | None = None,
) -> list[dict[str, Any]]:
    campaign_plan = next(
        (plan for plan in profile["campaign_plans"] if plan["mode"] == campaign_mode),
        None,
    )
    if campaign_plan is None:
        raise ValueError(f"unknown campaign mode {campaign_mode}")
    condition_order = campaign_plan["condition_order"]
    statuses = dict(statuses or {})
    if any(index not in range(1, len(condition_order) + 1) for index in statuses):
        raise ValueError("fixture status index is outside the frozen campaign plan")
    if campaign_status_override not in {None, "failed", "invalid"}:
        raise ValueError("only explicit failed/invalid campaign status overrides are allowed")
    runs: list[dict[str, Any]] = []
    next_t0_ns = 9_000_000_000_000
    cooldown_ns = campaign_plan["cooldown_ms"] * 1_000_000
    for index, condition_id in enumerate(condition_order, 1):
        status = statuses.get(index, "complete")
        base = valid_run(
            profile_hash,
            profile,
            condition_id,
            run_index=index,
            campaign_mode=campaign_mode,
            run_id=f"run-{campaign_mode}-{index:02d}",
            t0_ns=next_t0_ns,
        )
        run = status_record(base, status, profile)
        runs.append(run)
        if status == "not_started":
            continue
        t0 = base["clock"]["t0_monotonic_ns"]
        offsets, terminal_offset = schedule_offsets(profile, condition_id)
        if status in {"interrupted", "cancelled"}:
            end_ns = t0 + offsets[run["events_received"] - 1] * 1_000_000 + 1_000_000
        elif status in {"incompatible", "invalid_sequence", "ttft_timeout", "server_rejected"}:
            end_ns = t0 + 1_000_000
        else:
            end_ns = t0 + terminal_offset * 1_000_000
        next_t0_ns = end_ns + cooldown_ns
    events: list[dict[str, Any]] = []
    for run in runs:
        if run["run_status"] == "not_started":
            continue
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
        for seq, offset in enumerate(offsets[: run["events_received"]], 1):
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
        if run["run_status"] != "complete":
            failure_type = "run_cancelled" if run["run_status"] == "cancelled" else "run_failed"
            last_observed = (
                clock["t0_monotonic_ns"]
                + offsets[run["events_received"] - 1] * 1_000_000
                if run["events_received"]
                else clock["t0_monotonic_ns"]
            )
            events.append(
                make_event(
                    run,
                    failure_type,
                    last_observed + 1_000_000,
                    {
                        "failure_reason": run["failure_reason"],
                        "events_received": run["events_received"],
                    },
                )
            )
            continue
        receipt = {
            "receipt_version": TERMINAL_RECEIPT_VERSION,
            "protocol_version": PROTOCOL_VERSION,
            "profile_id": profile["workload"]["id"],
            "profile_version": profile["workload"]["version"],
            "terminal_status": "complete",
            "campaign_id": run["campaign_id"],
            "run_id": run["run_id"],
            "campaign_mode": run["campaign_mode"],
            "run_index": run["run_index"],
            "events_expected": 120,
            "events_received": 120,
            "planned_event_count": 120,
            "emitted_event_count": 120,
            "profile_manifest_sha256": profile_hash,
            "condition_id": condition["id"],
            "condition_version": condition["version"],
            "nominal_interval_ms": condition["nominal_interval_ms"],
            "schedule_hash": run["schedule_hash"],
            "clock_domain_id": clock["domain_id"],
            "clock_source": clock["source"],
            "clock_unit": clock["unit"],
            "clock_epoch": clock["epoch"],
            "t0_monotonic_ns": clock["t0_monotonic_ns"],
            "client_monotonic_ns": clock["t0_monotonic_ns"] + terminal_offset * 1_000_000,
        }
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
    write_csv(root / "runs.csv", RUN_COLUMNS, [run_to_csv_row(run) for run in runs])
    # The report embeds the same decimal representation that survives the
    # RFC4180 runs.csv round trip; this makes the byte chain canonical.
    parsed_runs = [csv_row_to_run(row) for row in read_csv(root / "runs.csv", RUN_COLUMNS)]
    campaign_status = campaign_status_override or campaign_status_from_runs(parsed_runs)
    summary_rows = compute_summary_rows(parsed_runs, profile, campaign_mode, campaign_status)
    write_csv(root / "summary.csv", SUMMARY_COLUMNS, [summary_to_csv_row(row) for row in summary_rows])
    if (root / "summary_partial.csv").exists():
        (root / "summary_partial.csv").unlink()
    report_payload = {"campaign_mode": campaign_mode, "campaign_status": campaign_status, "summary": summary_rows}
    report_json = json.dumps(report_payload, sort_keys=True, separators=(",", ":"))
    visible_rpi = "".join(
        f'<span data-condition="{row["condition_id"]}" data-rpi="{scalar(row["rpi"])}">'
        f'RPI: {scalar(row["rpi"])}</span>'
        for row in summary_rows
    )
    (root / "report.html").write_text(
        "<!doctype html><meta charset=\"utf-8\"><script id=\"canonical-summary\" "
        "type=\"application/json\">" + report_json + "</script>"
        "<section id=\"rpi-values\">" + visible_rpi + "</section>\n",
        encoding="utf-8",
    )
    write_bundle_metadata(root, runs, profile, profile_hash, campaign_mode, campaign_status)
    return runs


def assert_campaign_plan(runs: list[dict[str, Any]], profile: dict[str, Any], campaign_mode: str) -> dict[str, Any]:
    plans = [plan for plan in profile["campaign_plans"] if plan["mode"] == campaign_mode]
    if len(plans) != 1:
        raise ValueError("campaign mode has no unique frozen plan")
    plan = plans[0]
    planned_order = plan["condition_order"]
    expected_indices = list(range(1, len(planned_order) + 1))
    if len(runs) != len(planned_order):
        raise ValueError("run cardinality does not match the frozen campaign plan")
    run_ids = [run["run_id"] for run in runs]
    if len(set(run_ids)) != len(run_ids):
        raise ValueError("run_ids are not unique")
    indices = [run["run_index"] for run in runs]
    if sorted(indices) != expected_indices:
        raise ValueError("run indexes are not exactly the frozen campaign plan")
    for run in runs:
        index = run["run_index"]
        if run["campaign_mode"] != campaign_mode:
            raise ValueError("run campaign mode does not match the frozen plan")
        expected_condition = planned_order[index - 1]
        if run["condition"]["id"] != expected_condition:
            raise ValueError("run condition does not match the frozen plan")
    expected_counts = {condition_id: planned_order.count(condition_id) for condition_id in CONDITION_ORDER}
    actual_counts = {
        condition_id: sum(run["condition"]["id"] == condition_id for run in runs)
        for condition_id in CONDITION_ORDER
    }
    if actual_counts != expected_counts:
        raise ValueError("per-condition run counts do not match the frozen plan")
    return plan


def campaign_status_from_runs(runs: list[dict[str, Any]]) -> str:
    """Derive execution completeness, not aggregate run success.

    A failed/interrupted run is still an attempted plan slot.  Only a missing
    ``not_started`` slot makes this inferred campaign incomplete; explicit
    campaign-level ``failed``/``invalid`` values belong to the canonical meta
    authority and are not guessed from run status alone.
    """
    statuses = {run["run_status"] for run in runs}
    if "not_started" in statuses:
        return "cancelled" if "cancelled" in statuses else "partial"
    return "complete"


def verify_e2e_bundle(
    root: Path,
    profile: dict[str, Any],
    profile_hash: str,
    run_schema: dict[str, Any] | None = None,
) -> None:
    if run_schema is None:
        run_schema = load_json("run-record.schema.json")
    actual_files = {path.name for path in root.iterdir() if path.is_file()}
    expected_files = set(CANONICAL_BUNDLE_FILES)
    if actual_files != expected_files:
        extra = sorted(actual_files - expected_files)
        missing = sorted(expected_files - actual_files)
        raise ValueError(f"campaign bundle file set is not the frozen seven-file set (extra={extra}, missing={missing})")
    try:
        meta = json.loads((root / "meta.json").read_text(encoding="utf-8"))
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("campaign metadata or manifest is malformed") from exc
    if (
        not isinstance(meta, dict)
        or meta.get("schema_version") != EVIDENCE_SCHEMA_VERSION
        or meta.get("profile_manifest_sha256") != profile_hash
    ):
        raise ValueError("campaign metadata does not bind the published profile")
    artifact_names = [item.get("path") for item in manifest.get("artifacts", [])]
    expected_artifacts = [name for name in CANONICAL_BUNDLE_FILES if name != "manifest.json"]
    if (
        manifest.get("manifest_version") != "aneb-prototype-manifest-0.1"
        or manifest.get("publication_status") != "verified"
        or manifest.get("campaign_id") != meta.get("campaign_id")
        or artifact_names != expected_artifacts
        or len(artifact_names) != len(set(artifact_names))
    ):
        raise ValueError("campaign manifest shape is not canonical")
    for item in manifest["artifacts"]:
        path = item["path"]
        if not isinstance(path, str) or path not in expected_artifacts or "/" in path or "\\" in path or ".." in path:
            raise ValueError("campaign manifest path is not canonical")
        data = (root / path).read_bytes()
        if item.get("size_bytes") != len(data) or item.get("sha256") != hashlib.sha256(data).hexdigest():
            raise ValueError("campaign manifest artifact hash/size mismatch")
    events: list[dict[str, Any]] = []
    for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines():
        if line:
            events.append(json.loads(line))
    run_rows = read_csv(root / "runs.csv", RUN_COLUMNS)
    runs = [csv_row_to_run(row) for row in run_rows]
    for run in runs:
        # CSV is an evidence artifact, not a trust boundary.  Reconstructed
        # records must pass the formal Draft 2020-12 run-record contract before
        # any cross-layer recomputation is accepted.
        expect_valid(run_schema, run, f"runs.csv schema_version for {run['run_id']}")
    if not runs:
        raise ValueError("runs.csv has no run records")
    campaign_modes = {run["campaign_mode"] for run in runs}
    if len(campaign_modes) != 1:
        raise ValueError("run records disagree on campaign mode")
    campaign_mode = next(iter(campaign_modes))
    if meta.get("campaign_mode") != campaign_mode:
        raise ValueError("campaign metadata mode does not match run authority")
    plan = assert_campaign_plan(runs, profile, campaign_mode)
    inferred_campaign_status = campaign_status_from_runs(runs)
    declared_campaign_status = meta.get("campaign_status")
    if declared_campaign_status in {"failed", "invalid"}:
        campaign_status = declared_campaign_status
    elif declared_campaign_status == inferred_campaign_status:
        campaign_status = inferred_campaign_status
    else:
        raise ValueError("campaign status is not backed by run execution or explicit campaign authority")
    if not events:
        raise ValueError("raw evidence bundle is empty or malformed")
    for event in events:
        if event.get("schema_version") != EVIDENCE_SCHEMA_VERSION:
            raise ValueError("raw event schema_version is not the published evidence version")
        event_type = event.get("event_type")
        is_diagnostic = isinstance(event_type, str) and re.fullmatch(
            r"diagnostic\.[a-z0-9][a-z0-9._-]*", event_type
        )
        if not (isinstance(event_type, str) and (event_type in ALLOWED_EVENT_TYPES or is_diagnostic)):
            raise ValueError("unknown non-namespaced event type")
        if not isinstance(event.get("run_id"), str):
            raise ValueError("raw event has no run identity")
        if type(event.get("run_index")) is not int or type(event.get("nominal_interval_ms")) is not int:
            raise ValueError("event integer identity fields are not JSON integers")
        if event.get("clock_source") != "android.os.SystemClock.elapsedRealtimeNanos":
            raise ValueError("event clock source is not Android elapsedRealtimeNanos")
        if event.get("clock_unit") != "ns" or event.get("clock_epoch") != "device_boot":
            raise ValueError("event clock unit/epoch is not the published contract")
        if not isinstance(event.get("clock_domain_id"), str):
            raise ValueError("event has no clock-domain identity")
        if type(event.get("client_monotonic_ns")) is not int:
            raise ValueError("event timestamp is not a JSON integer")
    raw_campaign_ids = {event.get("campaign_id") for event in events}
    if len(raw_campaign_ids) != 1 or None in raw_campaign_ids:
        raise ValueError("raw events do not share one campaign identity")
    raw_campaign_id = next(iter(raw_campaign_ids))
    if meta.get("campaign_id") != raw_campaign_id:
        raise ValueError("campaign metadata campaign identity does not match raw authority")
    groups: dict[str, list[dict[str, Any]]] = {}
    for event in events:
        groups.setdefault(event["run_id"], []).append(event)
    run_ids = {run["run_id"] for run in runs}
    if not set(groups).issubset(run_ids):
        raise ValueError("raw event/run id sets disagree")
    if not groups:
        raise ValueError("raw evidence bundle has no attempted run events")
    end_by_index: dict[int, int] = {}
    for run in runs:
        run_id = run["run_id"]
        if run["campaign_id"] != raw_campaign_id:
            raise ValueError("runs.csv campaign identity does not match raw authority")
        if run["run_status"] == "not_started":
            if run_id in groups:
                raise ValueError("not-started run has raw evidence")
            continue
        group = groups.get(run_id)
        if group is None:
            raise ValueError("attempted run is absent from events.jsonl")
        started = [event for event in group if event["event_type"] == "run_started"]
        content = [event for event in group if event["event_type"] == "content_event"]
        terminal = [event for event in group if event["event_type"] == "terminal_event"]
        failures = [event for event in group if event["event_type"] in {"run_failed", "run_cancelled"}]
        diagnostics = [
            event
            for event in group
            if isinstance(event.get("event_type"), str) and event["event_type"].startswith("diagnostic.")
        ]
        if len(started) != 1:
            raise ValueError("event topology is invalid: exactly one run_started is required")
        if len(diagnostics) and any(event["event_type"] in SCORING_EVENT_TYPES for event in diagnostics):
            raise ValueError("diagnostic event was counted as scoring")
        start = started[0]
        if not isinstance(start.get("details"), dict) or set(start["details"]) != {"t0_monotonic_ns"}:
            raise ValueError("run_started details are not canonical")
        t0 = start["details"].get("t0_monotonic_ns")
        if type(t0) is not int or t0 < 0 or t0 != run["clock"]["t0_monotonic_ns"]:
            raise ValueError("run t0 does not match raw evidence")
        if start["client_monotonic_ns"] != t0:
            raise ValueError("run_started timestamp/t0 boundary does not match")
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
            if (
                event.get("source") != "android"
                or type(event.get("client_monotonic_ns")) is not int
                or not isinstance(event.get("details"), dict)
            ):
                raise ValueError("raw evidence source/timestamp shape mismatch")
            event_identity = (
                event.get("campaign_id"),
                event.get("run_id"),
                event.get("campaign_mode"),
                event.get("run_index"),
                event.get("profile_manifest_sha256"),
                event.get("condition_id"),
                event.get("condition_version"),
                event.get("nominal_interval_ms"),
                event.get("schedule_hash"),
                event.get("clock_source"),
                event.get("clock_epoch"),
                event.get("clock_domain_id"),
            )
            if event_identity != identity:
                raise ValueError("raw evidence identity mismatch")
            if event["client_monotonic_ns"] < t0:
                raise ValueError("raw event precedes t0")
        condition_id = run["condition"]["id"]
        offsets, _ = schedule_offsets(profile, condition_id)
        times: list[int] = []
        for expected_seq, (event, expected_offset) in enumerate(zip(content, offsets), 1):
            details = event["details"]
            if set(details) != {"seq", "planned_offset_ms", "payload_id"}:
                raise ValueError("content details are not canonical")
            if type(details.get("seq")) is not int or type(details.get("planned_offset_ms")) is not int:
                raise ValueError("content sequence/offset fields are not JSON integers")
            if details["seq"] != expected_seq or details["planned_offset_ms"] != expected_offset:
                raise ValueError("content sequence/schedule mismatch")
            if details["payload_id"] != f"ref-{expected_seq:04d}":
                raise ValueError("content payload identity mismatch")
            times.append(event["client_monotonic_ns"])
        if any(right <= left for left, right in zip(times, times[1:])):
            raise ValueError("content timestamps are not strictly increasing")
        scoring_types = [event["event_type"] for event in group if event["event_type"] in SCORING_EVENT_TYPES]
        if run["run_status"] == "complete":
            if len(content) != 120 or len(terminal) != 1 or failures:
                raise ValueError("complete event topology is invalid")
            expected_types = ["run_started"] + ["content_event"] * 120 + ["terminal_event"]
            if scoring_types != expected_types:
                raise ValueError("per-run scoring event order is invalid")
            receipt = terminal[0]["details"]
            if set(receipt) != {
                "receipt_version", "protocol_version", "profile_id", "profile_version", "terminal_status",
                "campaign_id", "run_id", "campaign_mode", "run_index", "events_expected",
                "events_received", "planned_event_count", "emitted_event_count", "profile_manifest_sha256",
                "condition_id", "condition_version", "nominal_interval_ms", "schedule_hash", "clock_domain_id",
                "clock_source", "clock_unit", "clock_epoch", "t0_monotonic_ns", "client_monotonic_ns",
            }:
                raise ValueError("terminal receipt has missing or extra fields")
            for field in [
                "run_index", "events_expected", "events_received", "planned_event_count",
                "emitted_event_count", "nominal_interval_ms", "t0_monotonic_ns", "client_monotonic_ns",
            ]:
                if type(receipt.get(field)) is not int:
                    raise ValueError(f"terminal receipt {field} is not a JSON integer")
            for field in [
                "receipt_version", "protocol_version", "profile_id", "profile_version", "terminal_status",
                "campaign_id", "run_id", "campaign_mode", "profile_manifest_sha256", "condition_id",
                "condition_version", "schedule_hash", "clock_source", "clock_unit", "clock_epoch",
                "clock_domain_id",
            ]:
                if not isinstance(receipt.get(field), str):
                    raise ValueError(f"terminal receipt {field} is not a string")
            expected_receipt = {
                "receipt_version": TERMINAL_RECEIPT_VERSION,
                "protocol_version": PROTOCOL_VERSION,
                "profile_id": profile["workload"]["id"],
                "profile_version": profile["workload"]["version"],
                "terminal_status": "complete",
                "campaign_id": run["campaign_id"],
                "run_id": run_id,
                "campaign_mode": run["campaign_mode"],
                "run_index": run["run_index"],
                "events_expected": 120,
                "events_received": 120,
                "planned_event_count": 120,
                "emitted_event_count": 120,
                "profile_manifest_sha256": profile_hash,
                "condition_id": run["condition"]["id"],
                "condition_version": run["condition"]["version"],
                "nominal_interval_ms": run["condition"]["nominal_interval_ms"],
                "schedule_hash": run["schedule_hash"],
                "clock_source": run["clock"]["source"],
                "clock_unit": run["clock"]["unit"],
                "clock_epoch": run["clock"]["epoch"],
                "clock_domain_id": run["clock"]["domain_id"],
                "t0_monotonic_ns": t0,
                "client_monotonic_ns": receipt["client_monotonic_ns"],
            }
            if receipt != expected_receipt:
                raise ValueError("terminal receipt fields do not match the protocol authority")
            if terminal[0]["client_monotonic_ns"] != receipt["client_monotonic_ns"]:
                raise ValueError("terminal event timestamp does not match terminal receipt")
            if receipt["client_monotonic_ns"] <= times[-1]:
                raise ValueError("terminal timestamp is not after the last observed content event")
            expected = derive_raw_metrics(
                times,
                receipt["client_monotonic_ns"],
                run["condition"]["nominal_interval_ms"],
                t0,
            )
            for key, value in expected.items():
                if not math.isclose(
                    float(run["metrics"][key]),
                    float(value),
                    rel_tol=0,
                    abs_tol=SERIALIZED_NUMBER_TOLERANCE,
                ):
                    raise ValueError(f"runs.csv metric mismatch for {key}")
            end_by_index[run["run_index"]] = receipt["client_monotonic_ns"]
            continue

        if len(terminal) != 0 or len(failures) != 1:
            raise ValueError("failed/partial event topology is invalid")
        expected_count = run["events_received"]
        if expected_count != len(content) or not 0 <= expected_count < 120:
            raise ValueError("failed/partial content count does not match run record")
        expected_failure_type = "run_cancelled" if run["run_status"] == "cancelled" else "run_failed"
        if failures[0]["event_type"] != expected_failure_type:
            raise ValueError("failure event type does not match run status")
        failure_details = failures[0]["details"]
        if set(failure_details) != {"failure_reason", "events_received"}:
            raise ValueError("failure event details are not canonical")
        if (
            failure_details["failure_reason"] != run["failure_reason"]
            or type(failure_details["events_received"]) is not int
            or failure_details["events_received"] != expected_count
        ):
            raise ValueError("failure event reason/count does not match run record")
        expected_types = ["run_started"] + ["content_event"] * expected_count + [expected_failure_type]
        scoring_and_failure = [
            event["event_type"]
            for event in group
            if event["event_type"] in SCORING_EVENT_TYPES or event["event_type"] in {"run_failed", "run_cancelled"}
        ]
        if scoring_and_failure != expected_types:
            raise ValueError("failed/partial event order is invalid")
        if failures[0]["client_monotonic_ns"] <= (times[-1] if times else t0):
            raise ValueError("failure event does not follow observed content")
        if expected_count == 0:
            if any(value is not None for value in run["metrics"].values()):
                raise ValueError("zero-event failure retains numeric metrics")
        elif run["run_status"] in {"interrupted", "cancelled"}:
            expected_partial = derive_partial_metrics(
                times,
                run["condition"]["nominal_interval_ms"],
                t0,
            )
            for key, value in expected_partial.items():
                if value is None:
                    if run["metrics"][key] is not None:
                        raise ValueError(f"partial metric {key} must be null")
                elif not math.isclose(
                    float(run["metrics"][key]),
                    float(value),
                    rel_tol=0,
                    abs_tol=SERIALIZED_NUMBER_TOLERANCE,
                ):
                    raise ValueError(f"partial metric mismatch for {key}")
        else:
            raise ValueError("non-partial failure status has observed content")
        end_by_index[run["run_index"]] = failures[0]["client_monotonic_ns"]

    previous_end: int | None = None
    cooldown_ns = plan["cooldown_ms"] * 1_000_000
    for run in sorted(runs, key=lambda item: item["run_index"]):
        if run["run_status"] == "not_started":
            continue
        t0 = run["clock"]["t0_monotonic_ns"]
        if type(t0) is not int:
            raise ValueError("attempted run has no integer monotonic t0")
        if previous_end is not None and t0 - previous_end < cooldown_ns:
            raise ValueError("inter-run chronology/cooldown is invalid")
        previous_end = end_by_index[run["run_index"]]
    expected_summary = compute_summary_rows(runs, profile, campaign_mode, campaign_status)
    actual_summary = read_csv(root / "summary.csv", SUMMARY_COLUMNS)
    expected_csv = [summary_to_csv_row(row) for row in expected_summary]
    if len(actual_summary) != len(CONDITION_ORDER) or [
        row["condition_id"] for row in actual_summary
    ] != CONDITION_ORDER:
        raise ValueError("summary.csv does not have one row per unique condition in frozen order")
    for actual, expected in zip(actual_summary, expected_csv):
        if actual["condition_id"] not in CONDITION_ORDER:
            raise ValueError("summary contains an unknown condition")
        if actual["campaign_mode"] != campaign_mode or actual["campaign_status"] != campaign_status:
            raise ValueError("summary mode/status does not match raw run authority")
        planned = parse_integer(actual["planned_runs"], f"{actual['condition_id']} planned_runs", nullable=False)
        attempted = parse_integer(actual["attempted_runs"], f"{actual['condition_id']} attempted_runs", nullable=False)
        successful = parse_integer(actual["successful_runs"], f"{actual['condition_id']} successful_runs", nullable=False)
        failed = parse_integer(actual["failed_runs"], f"{actual['condition_id']} failed_runs", nullable=False)
        not_started = parse_integer(actual["not_started_runs"], f"{actual['condition_id']} not_started_runs", nullable=False)
        expected_planned = plan["condition_order"].count(actual["condition_id"])
        if planned != expected_planned or not 0 <= attempted <= planned:
            raise ValueError("summary counts exceed the frozen campaign plan")
        if not 0 <= successful <= attempted or failed != attempted - successful or not_started != planned - attempted:
            raise ValueError("summary success/failure counts are inconsistent with the plan")
        if actual["success_rate"] == "":
            raise ValueError("planned condition has no success rate")
        parsed_rate = parse_number(actual["success_rate"], f"{actual['condition_id']} success_rate")
        if parsed_rate is None:
            raise ValueError("planned condition has no success rate")
        success_rate = parsed_rate
        if not 0 <= success_rate <= 1:
            raise ValueError("summary success rate is outside [0,1]")
        if actual != {key: scalar(expected.get(key)) for key in SUMMARY_COLUMNS}:
            raise ValueError("summary.csv is not recomputed from runs")
        validate_null_reason_row(actual)
    if len(actual_summary) != len(expected_csv):
        raise ValueError("summary.csv row count mismatch")
    if (root / "summary_partial.csv").exists():
        raise ValueError("summary_partial.csv is outside the seven-file campaign contract")
    report_text = (root / "report.html").read_text(encoding="utf-8")
    if re.search(r"(?:https?:)?//", report_text, flags=re.IGNORECASE):
        raise ValueError("offline report contains a remote URL")
    if re.search(r"<[^>]+\b(?:src|href|action)\s*=", report_text, flags=re.IGNORECASE):
        raise ValueError("offline report contains a resource or form URL attribute")
    if re.search(
        r"(?:\burl\s*\(|@import\b|\b(?:fetch|XMLHttpRequest|WebSocket)\s*\()",
        report_text,
        flags=re.IGNORECASE,
    ):
        raise ValueError("offline report contains an active resource/API reference")
    if re.search(r"<(?:iframe|object|embed)\b", report_text, flags=re.IGNORECASE):
        raise ValueError("offline report contains remote active content")
    matches = re.findall(
        r'<script id="canonical-summary" type="application/json">(.*?)</script>',
        report_text,
        flags=re.DOTALL,
    )
    if len(matches) != 1:
        raise ValueError("report has no embedded canonical summary")
    report_payload = json.loads(matches[0])
    if report_payload != {
        "campaign_mode": campaign_mode,
        "campaign_status": campaign_status,
        "summary": expected_summary,
    }:
        raise ValueError("report summary is not the canonical summary")
    visible = re.findall(
        r'<span data-condition="([^"]+)" data-rpi="([^"]*)">RPI: ([^<]*)</span>',
        report_text,
    )
    if len(visible) != len(expected_summary) or report_text.count("RPI:") != len(expected_summary):
        raise ValueError("report visible RPI values are not a single canonical rendering")
    expected_visible = [(row["condition_id"], scalar(row["rpi"]), scalar(row["rpi"])) for row in expected_summary]
    if visible != expected_visible:
        raise ValueError("report visible RPI values are not derived from canonical summary")


def mutate_and_expect_bundle_failure(
    root: Path,
    profile: dict[str, Any],
    profile_hash: str,
    label: str,
    mutate: Callable[[Path], None],
) -> None:
    build_e2e_bundle(root, profile, profile_hash)
    mutate(root)
    refresh_bundle_metadata_for_test(root, profile)
    refresh_bundle_manifest(root)
    expect_failure(lambda: verify_e2e_bundle(root, profile, profile_hash), label)


def mutate_existing_bundle_and_expect_failure(
    root: Path,
    profile: dict[str, Any],
    profile_hash: str,
    label: str,
    mutate: Callable[[Path], None],
) -> None:
    mutate(root)
    refresh_bundle_metadata_for_test(root, profile)
    refresh_bundle_manifest(root)
    expect_failure(lambda: verify_e2e_bundle(root, profile, profile_hash), label)


def version_fixture(contract_hashes: dict[str, str]) -> dict[str, Any]:
    return {
        "product_version": "prototype-0.1",
        "release_candidate": "rc.1",
        "source_commit": "0" * 40,
        "built_at_utc": "2026-08-28T00:00:00Z",
        "server_version": "dev",
        "android_version_name": "0.1.0",
        "android_version_code": 1,
        "workload_id": PROFILE_ID,
        "condition_versions": list(CONDITION_ORDER),
        "contract_files": list(CONTRACT_FILES),
        "contract_hashes": dict(contract_hashes),
        "schedule_hashes": dict(EXPECTED_SCHEDULE_HASHES),
        "evidence_schema": EVIDENCE_SCHEMA_VERSION,
        "score_policy": "rpi-0.1",
    }


def validate_version_fixture(version: dict[str, Any], contract_hashes: dict[str, str]) -> None:
    required = {
        "product_version",
        "release_candidate",
        "source_commit",
        "built_at_utc",
        "server_version",
        "android_version_name",
        "android_version_code",
        "workload_id",
        "condition_versions",
        "contract_files",
        "contract_hashes",
        "schedule_hashes",
        "evidence_schema",
        "score_policy",
    }
    if not required.issubset(version):
        raise ValueError("VERSION is missing a normative field")
    if version["product_version"] != "prototype-0.1" or version["workload_id"] != PROFILE_ID:
        raise ValueError("VERSION product/workload identity is not exact")
    if version["condition_versions"] != CONDITION_ORDER:
        raise ValueError("VERSION condition order is not exact")
    if not isinstance(version["source_commit"], str) or not re.fullmatch(r"[0-9a-f]{40}", version["source_commit"]):
        raise ValueError("VERSION source_commit is not a bare 40-hex value")
    if not isinstance(version["android_version_code"], int) or version["android_version_code"] < 1:
        raise ValueError("VERSION android version code is invalid")
    if version["evidence_schema"] != EVIDENCE_SCHEMA_VERSION or version["score_policy"] != "rpi-0.1":
        raise ValueError("VERSION evidence/score identity is not exact")
    if version["contract_files"] != CONTRACT_FILES:
        raise ValueError("VERSION contract set/order is not exact")
    if set(version["contract_hashes"]) != set(CONTRACT_FILES):
        raise ValueError("VERSION contract hash set is not exact")
    if version["contract_hashes"] != contract_hashes:
        raise ValueError("VERSION contract hashes do not match canonical bytes")
    if set(version["schedule_hashes"]) != set(EXPECTED_SCHEDULE_HASHES):
        raise ValueError("VERSION schedule hash set is not exact")
    if version["schedule_hashes"] != EXPECTED_SCHEDULE_HASHES:
        raise ValueError("VERSION schedule hash bindings do not match")
    if "evidence-schema.json" in version["contract_files"]:
        raise ValueError("evidence-schema.json is outside the four-contract package")


def assert_manifest_oracle(profile: dict[str, Any]) -> None:
    binding = profile["condition_binding"]
    if binding["binding"] != "profile_manifest_sha256_plus_condition_id_version_plus_schedule_sha256":
        raise AssertionError("condition binding description drifts from the approved identity tuple")
    if binding["identity_fields"] != ["id", "version", "schedule_sha256"]:
        raise AssertionError("condition identity fields drift")
    if binding["separate_condition_hash"] is not False:
        raise AssertionError("separate condition hash is outside the contract")
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
    assert profile_hash == EXPECTED_PROFILE_HASH
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
    legacy_version = copy.deepcopy(version)
    legacy_version["contract_sha256"] = legacy_version.pop("contract_hashes")
    legacy_version["schedule_sha256"] = legacy_version.pop("schedule_hashes")
    expect_failure(lambda: validate_version_fixture(legacy_version, contract_hashes), "VERSION legacy hash shape")
    checks.append("VERSION normative product fields and exact four-contract set/hash fixture")

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
    partial = partial_interrupted_run(profile_hash, profile)
    expect_valid(schemas["run-record"], partial, "partial interrupted matrix")
    partial_t0 = partial["clock"]["t0_monotonic_ns"]
    partial_expected = derive_raw_metrics(
        [partial_t0 + 200_000_000, partial_t0 + 250_000_000],
        partial_t0 + 250_000_000,
        50,
        partial_t0,
    )
    assert partial["metrics"]["stream_event_rate_eps"] == partial_expected["stream_event_rate_eps"] == 20.0
    mandatory_missing = partial_interrupted_run(profile_hash, profile)
    mandatory_missing["failure_reason"] = "mandatory_metric_missing"
    expect_valid(schemas["run-record"], mandatory_missing, "interrupted mandatory metric missing topology")
    interrupted_null_t0 = copy.deepcopy(partial_interrupted_run(profile_hash, profile))
    interrupted_null_t0["clock"]["t0_monotonic_ns"] = None
    expect_invalid(schemas["run-record"], interrupted_null_t0, "interrupted numeric metrics without t0")

    def zero_event_failure(status: str, reason: str) -> dict[str, Any]:
        candidate = copy.deepcopy(run)
        candidate.update(
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
        return candidate

    def status_fixture(status: str) -> dict[str, Any]:
        if status == "complete":
            return copy.deepcopy(run)
        if status == "interrupted":
            return partial_interrupted_run(profile_hash, profile)
        if status == "cancelled":
            return status_record(
                valid_run(profile_hash, profile, "baseline_v0.1", 1, "quick"),
                "cancelled",
                profile,
            )
        if status == "invalid_sequence":
            return zero_event_failure("invalid_sequence", "invalid_sequence")
        if status == "not_started":
            return not_started_run(profile_hash, profile)
        return zero_event_failure(status, next(iter(ALLOWED_FAILURE_REASONS_BY_STATUS[status])))

    for status, allowed_reasons in ALLOWED_FAILURE_REASONS_BY_STATUS.items():
        fixture = status_fixture(status)
        for reason in FAILURE_REASON_DOMAIN:
            candidate = copy.deepcopy(fixture)
            candidate["failure_reason"] = reason
            if reason in allowed_reasons:
                expect_valid(schemas["run-record"], candidate, f"{status} allowed reason {reason}")
            else:
                expect_invalid(schemas["run-record"], candidate, f"{status} forbidden reason {reason}")

    # Each branch gets one reason-only mutation from a fixture that already has
    # the branch's valid status/event/metric topology.  Removing that branch's
    # reason constraint must therefore turn this suite red rather than being
    # masked by an unrelated receipt or evidence failure.
    attributable_wrong_reason = {
        "complete": "contract_mismatch",
        "interrupted": "cancelled",
        "cancelled": "stream_interrupted",
        "incompatible": "server_rejected",
        "invalid_sequence": "cancelled",
        "ttft_timeout": "cancelled",
        "server_rejected": None,
        "not_started": None,
    }
    for status, wrong_reason in attributable_wrong_reason.items():
        candidate = status_fixture(status)
        candidate["failure_reason"] = wrong_reason
        expect_invalid(schemas["run-record"], candidate, f"{status} attributable wrong reason")
    for status, wrong_reason in attributable_wrong_reason.items():
        patched_schema = copy.deepcopy(schemas["run-record"])
        patched_branch = False
        for branch in patched_schema["allOf"]:
            status_const = (
                branch.get("if", {})
                .get("properties", {})
                .get("run_status", {})
                .get("const")
            )
            if status_const == status:
                branch["then"].get("properties", {}).pop("failure_reason", None)
                patched_branch = True
                break
        if not patched_branch:
            raise AssertionError(f"missing failure-reason branch for {status}")
        candidate = status_fixture(status)
        candidate["failure_reason"] = wrong_reason
        expect_failure(
            lambda candidate=candidate, patched_schema=patched_schema, status=status: expect_invalid(
                patched_schema,
                candidate,
                f"{status} patched reason constraint",
            ),
            f"{status} reason constraint attribution",
        )
    checks.append("closed failure status/reason/receipt/event/metric topology")
    checks.append("all eight status reason domains and attributable branch regressions")
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
    invalid_sequence_receipt = copy.deepcopy(invalid_sequence)
    invalid_sequence_receipt["terminal_receipt_valid"] = True
    expect_invalid(schemas["run-record"], invalid_sequence_receipt, "invalid sequence valid receipt survivor")
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

    acceptance_runs = [
        valid_run(
            profile_hash,
            profile,
            run_index=index,
            campaign_mode="acceptance",
            run_id=f"run-acceptance-{index:02d}",
            t0_ns=9_100_000_000_000 + index * 1_000_000_000,
        )
        for index in range(1, 10)
    ]
    mixed_acceptance = acceptance_runs[:]
    for failed_index in [1, 4, 7]:
        mixed_acceptance[failed_index] = copy.deepcopy(mixed_acceptance[failed_index])
        mixed_acceptance[failed_index].update(
            {
                "run_status": "incompatible",
                "task_success": False,
                "score_eligible": False,
                "events_received": 0,
                "terminal_receipt_valid": None,
                "failure_reason": "contract_mismatch",
                "metrics": {key: None for key in METRIC_KEYS},
            }
        )
    mixed_rows = compute_summary_rows(mixed_acceptance, profile, "acceptance", "complete")
    assert mixed_rows[0]["rpi"] is not None and mixed_rows[0]["primary_null_reason"] is None
    assert mixed_rows[1]["rpi"] is None and mixed_rows[1]["primary_null_reason"] is not None
    assert mixed_rows[2]["rpi"] is not None and mixed_rows[2]["primary_null_reason"] is None
    checks.append("mixed-eligibility Acceptance RPI is computed per row")

    with tempfile.TemporaryDirectory(prefix="aneb-prototype-evidence-") as directory:
        bundle = Path(directory)
        build_e2e_bundle(bundle, profile, profile_hash)
        assert not (bundle / "terminal_receipts.json").exists()
        verify_e2e_bundle(bundle, profile, profile_hash)

        def mutate_raw(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                # Change the first observed arrival so the derived TTFT must
                # disagree with the published runs.csv metrics.  A middle
                # event may legitimately jitter when the metrics are
                # recomputed from raw observations (planned offsets are not
                # observed timestamps).
                if event["event_type"] == "content_event" and event["details"]["seq"] == 1:
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
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "terminal_event":
                    event["details"]["terminal_status"] = "tampered"
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_terminal_domain(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            first = next(json.loads(line)["run_id"] for line in lines if json.loads(line)["event_type"] == "terminal_event")
            forged_domain = "boot-session-forged"
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

        def mutate_event_schema_version(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "content_event":
                    event["schema_version"] = "forged"
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_event_clock_unit(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "content_event":
                    event["clock_unit"] = "ms"
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_receipt_version(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "terminal_event":
                    event["details"]["receipt_version"] = "forged"
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_receipt_count(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "terminal_event":
                    event["details"]["events_received"] = 119
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_receipt_missing_field(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "terminal_event":
                    event["details"].pop("events_received", None)
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_receipt_extra_field(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "terminal_event":
                    event["details"]["forged_extra"] = "reject-me"
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_receipt_float_field(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "terminal_event":
                    event["details"]["run_index"] = float(event["details"]["run_index"])
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_run_started_timestamp(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "run_started":
                    event["client_monotonic_ns"] += 1
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_terminal_timestamp(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                event = json.loads(line)
                if event["event_type"] == "terminal_event":
                    event["client_monotonic_ns"] += 1
                    lines[index] = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    break
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_terminal_before_content(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            events = [json.loads(line) for line in lines]
            first_run = events[0]["run_id"]
            terminal_index = next(
                index for index, event in enumerate(events)
                if event["run_id"] == first_run and event["event_type"] == "terminal_event"
            )
            content_index = next(
                index for index, event in enumerate(events)
                if event["run_id"] == first_run and event["event_type"] == "content_event"
            )
            terminal_event = events.pop(terminal_index)
            if terminal_index < content_index:
                content_index -= 1
            events.insert(content_index, terminal_event)
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )

        def mutate_start_after_terminal(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            events = [json.loads(line) for line in lines]
            first_run = events[0]["run_id"]
            start_index = next(
                index for index, event in enumerate(events)
                if event["run_id"] == first_run and event["event_type"] == "run_started"
            )
            terminal_index = next(
                index for index, event in enumerate(events)
                if event["run_id"] == first_run and event["event_type"] == "terminal_event"
            )
            start_event = events.pop(start_index)
            terminal_index -= 1
            events.insert(terminal_index + 1, start_event)
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )

        def mutate_partial_missing_failure(path: Path) -> None:
            events = [
                json.loads(line)
                for line in (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
                if line
            ]
            for index, event in enumerate(events):
                if event["run_id"] == "run-quick-01" and event["event_type"] == "run_failed":
                    events.pop(index)
                    break
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )

        def mutate_not_started_evidence(path: Path) -> None:
            events = [
                json.loads(line)
                for line in (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
                if line
            ]
            source = next(event for event in events if event["run_id"] == "run-quick-02")
            forged = copy.deepcopy(source)
            forged["run_id"] = "run-quick-01"
            forged["run_index"] = 1
            forged["condition_id"] = "baseline_v0.1"
            events.append(forged)
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )

        def shift_run_timeline(path: Path, run_id: str, new_t0: int) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            row = next(row for row in rows if row["run_id"] == run_id)
            old_t0 = parse_integer(row["t0_monotonic_ns"], "t0_monotonic_ns", nullable=False)
            delta = new_t0 - old_t0
            row["t0_monotonic_ns"] = str(new_t0)
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)
            events = [
                json.loads(line)
                for line in (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
                if line
            ]
            for event in events:
                if event["run_id"] != run_id:
                    continue
                event["client_monotonic_ns"] += delta
                if event["event_type"] == "run_started":
                    event["details"]["t0_monotonic_ns"] = new_t0
                elif event["event_type"] == "terminal_event":
                    event["details"]["t0_monotonic_ns"] = new_t0
                    event["details"]["client_monotonic_ns"] += delta
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )

        def mutate_interrun_overlap(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            first_t0 = parse_integer(
                next(row for row in rows if row["run_id"] == "run-quick-01")["t0_monotonic_ns"],
                "t0_monotonic_ns",
                nullable=False,
            )
            shift_run_timeline(path, "run-quick-02", first_t0)

        def mutate_interrun_reorder(path: Path) -> None:
            events = [
                json.loads(line)
                for line in (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
                if line
            ]
            run3_terminal = next(
                event["client_monotonic_ns"]
                for event in events
                if event["run_id"] == "run-quick-03" and event["event_type"] == "terminal_event"
            )
            shift_run_timeline(path, "run-quick-02", run3_terminal + 1_000_000_000)

        def rewrite_summary_report(path: Path, rows: list[dict[str, str]], campaign_mode: str = "quick") -> None:
            parsed_runs = [csv_row_to_run(row) for row in rows]
            summary = compute_summary_rows(parsed_runs, profile, campaign_mode, "complete")
            write_csv(path / "summary.csv", SUMMARY_COLUMNS, [summary_to_csv_row(row) for row in summary])
            report_payload = {"campaign_mode": campaign_mode, "campaign_status": "complete", "summary": summary}
            report_json = json.dumps(report_payload, sort_keys=True, separators=(",", ":"))
            visible_rpi = "".join(
                f'<span data-condition="{row["condition_id"]}" data-rpi="{scalar(row["rpi"])}">'
                f'RPI: {scalar(row["rpi"])}</span>'
                for row in summary
            )
            (path / "report.html").write_text(
                "<!doctype html><meta charset=\"utf-8\"><script id=\"canonical-summary\" "
                "type=\"application/json\">" + report_json + "</script>"
                "<section id=\"rpi-values\">" + visible_rpi + "</section>\n",
                encoding="utf-8",
            )

        def mutate_observed_jitter(path: Path) -> None:
            """Jitter an observed arrival, then recompute the downstream chain."""
            events = [
                json.loads(line)
                for line in (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
                if line
            ]
            first_run = events[0]["run_id"]
            for event in events:
                if (
                    event["run_id"] == first_run
                    and event["event_type"] == "content_event"
                    and event["details"]["seq"] == 1
                ):
                    event["client_monotonic_ns"] += 1_000_000
                    break
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            grouped: dict[str, list[dict[str, Any]]] = {}
            for event in events:
                grouped.setdefault(event["run_id"], []).append(event)
            for row in rows:
                group = grouped[row["run_id"]]
                observed = [event["client_monotonic_ns"] for event in group if event["event_type"] == "content_event"]
                receipt = next(event["details"] for event in group if event["event_type"] == "terminal_event")
                metrics = derive_raw_metrics(
                    observed,
                    receipt["client_monotonic_ns"],
                    parse_integer(row["nominal_interval_ms"], "nominal_interval_ms", nullable=False),
                    parse_integer(row["t0_monotonic_ns"], "t0_monotonic_ns", nullable=False),
                )
                for key in METRIC_KEYS:
                    row[key] = scalar(metrics[key])
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)
            rewrite_summary_report(path, rows)

        build_e2e_bundle(bundle, profile, profile_hash)
        mutate_observed_jitter(bundle)
        refresh_bundle_metadata_for_test(bundle, profile)
        refresh_bundle_manifest(bundle)
        verify_e2e_bundle(bundle, profile, profile_hash)

        acceptance_bundle = bundle / "acceptance"
        acceptance_bundle.mkdir()
        build_e2e_bundle(acceptance_bundle, profile, profile_hash, "acceptance")
        assert not (acceptance_bundle / "terminal_receipts.json").exists()
        verify_e2e_bundle(acceptance_bundle, profile, profile_hash)

        mixed_acceptance_bundle = bundle / "acceptance-mixed"
        mixed_acceptance_bundle.mkdir()
        build_e2e_bundle(
            mixed_acceptance_bundle,
            profile,
            profile_hash,
            "acceptance",
            {2: "incompatible", 5: "incompatible", 8: "incompatible"},
        )
        verify_e2e_bundle(mixed_acceptance_bundle, profile, profile_hash)
        mixed_summary = read_csv(mixed_acceptance_bundle / "summary.csv", SUMMARY_COLUMNS)
        slow_row = next(row for row in mixed_summary if row["condition_id"] == "slow_v0.1")
        baseline_row = next(row for row in mixed_summary if row["condition_id"] == "baseline_v0.1")
        unstable_row = next(row for row in mixed_summary if row["condition_id"] == "unstable_v0.1")
        assert (
            mixed_summary[0]["campaign_status"] == "complete"
            and slow_row["attempted_runs"] == "3"
            and slow_row["successful_runs"] == "0"
            and slow_row["failed_runs"] == "3"
            and slow_row["rpi"] == ""
            and baseline_row["rpi"] != ""
            and unstable_row["rpi"] != ""
        )

        partial_bundle = bundle / "partial-interrupted"
        partial_bundle.mkdir()
        build_e2e_bundle(partial_bundle, profile, profile_hash, "quick", {1: "interrupted"})
        assert not (partial_bundle / "summary_partial.csv").exists()
        verify_e2e_bundle(partial_bundle, profile, profile_hash)

        cancelled_bundle = bundle / "partial-cancelled"
        cancelled_bundle.mkdir()
        build_e2e_bundle(
            cancelled_bundle,
            profile,
            profile_hash,
            "quick",
            {1: "cancelled", 2: "not_started", 3: "not_started"},
        )
        verify_e2e_bundle(cancelled_bundle, profile, profile_hash)

        failed_bundle = bundle / "partial-server-rejected"
        failed_bundle.mkdir()
        build_e2e_bundle(
            failed_bundle,
            profile,
            profile_hash,
            "quick",
            {1: "server_rejected", 2: "not_started", 3: "not_started"},
            "failed",
        )
        verify_e2e_bundle(failed_bundle, profile, profile_hash)
        failed_summary = read_csv(failed_bundle / "summary.csv", SUMMARY_COLUMNS)
        assert all(row["campaign_status"] == "failed" and row["rpi"] == "" for row in failed_summary)

        not_started_bundle = bundle / "partial-not-started"
        not_started_bundle.mkdir()
        build_e2e_bundle(not_started_bundle, profile, profile_hash, "quick", {1: "not_started"})
        verify_e2e_bundle(not_started_bundle, profile, profile_hash)

        def mutate_duplicate_run_row(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            rows.append(dict(rows[0]))
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)
            rewrite_summary_report(path, rows)

        def mutate_acceptance_mode_three_runs(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            for row in rows:
                row["campaign_mode"] = "acceptance"
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            events = [json.loads(line) for line in lines]
            for event in events:
                event["campaign_mode"] = "acceptance"
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            events = [json.loads(line) for line in lines]
            for event in events:
                if event["event_type"] == "terminal_event":
                    event["details"]["campaign_mode"] = "acceptance"
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )
            rewrite_summary_report(path, rows, "acceptance")

        def mutate_run_failed_event(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            forged = json.loads(lines[1])
            forged["event_type"] = "run_failed"
            lines.append(json.dumps(forged, sort_keys=True, separators=(",", ":")))
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_empty_diagnostic(path: Path) -> None:
            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            forged = json.loads(lines[1])
            forged["event_type"] = "diagnostic."
            lines.append(json.dumps(forged, sort_keys=True, separators=(",", ":")))
            (path / "events.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

        def mutate_event_run_index_float(path: Path) -> None:
            _mutate_event_field(path, "content_event", 2, "run_index", 1.0)

        def mutate_event_nominal_float(path: Path) -> None:
            _mutate_event_field(path, "content_event", 2, "nominal_interval_ms", 50.0)

        def mutate_event_seq_float(path: Path) -> None:
            _mutate_event_details_field(path, "content_event", 2, "seq", 2.0)

        def mutate_event_offset_float(path: Path) -> None:
            _mutate_event_details_field(path, "content_event", 2, "planned_offset_ms", 250.0)

        def mutate_csv_t0_fraction(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            rows[0]["t0_monotonic_ns"] += ".5"
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)

        def mutate_csv_stall_count_fraction(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            rows[0]["stall_count"] = "0.0"
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)

        def mutate_csv_metric_precision(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            rows[0]["completion_ms"] = "6200.0000001"
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)

        def mutate_report_remote_script(path: Path) -> None:
            report = (path / "report.html").read_text(encoding="utf-8")
            (path / "report.html").write_text(
                '<script src="https://example.invalid/remote.js"></script>' + report,
                encoding="utf-8",
            )

        def mutate_report_remote_image(path: Path) -> None:
            report = (path / "report.html").read_text(encoding="utf-8")
            (path / "report.html").write_text(
                '<img src="https://example.invalid/tracker.png">' + report,
                encoding="utf-8",
            )

        def mutate_report_duplicate_summary(path: Path) -> None:
            report = (path / "report.html").read_text(encoding="utf-8")
            match = re.search(
                r'<script id="canonical-summary" type="application/json">.*?</script>',
                report,
                flags=re.DOTALL,
            )
            if not match:
                raise ValueError("report has no canonical summary to duplicate")
            (path / "report.html").write_text(report + match.group(0) + "\n", encoding="utf-8")

        def mutate_report_visible_rpi(path: Path) -> None:
            report = (path / "report.html").read_text(encoding="utf-8")
            replaced = report.replace(
                'data-condition="baseline_v0.1" data-rpi="100">RPI: 100',
                'data-condition="baseline_v0.1" data-rpi="99">RPI: 99',
                1,
            )
            if replaced == report:
                raise ValueError("report has no baseline visible RPI")
            (path / "report.html").write_text(replaced, encoding="utf-8")

        def mutate_unique_extra_run(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            first_row = dict(rows[0])
            first_run_id = first_row["run_id"]
            extra_run_id = "run-quick-extra"
            old_t0 = int(first_row["t0_monotonic_ns"])
            new_t0 = old_t0 + 10_000_000_000
            first_row.update(
                {
                    "run_id": extra_run_id,
                    "clock_domain_id": "boot-session-extra",
                    "t0_monotonic_ns": str(new_t0),
                }
            )
            rows.append(first_row)
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)

            lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
            cloned: list[dict[str, Any]] = []
            for line in lines:
                event = json.loads(line)
                if event["run_id"] != first_run_id:
                    continue
                event["run_id"] = extra_run_id
                event["clock_domain_id"] = "boot-session-extra"
                if event["event_type"] == "run_started":
                    event["client_monotonic_ns"] = new_t0
                    event["details"]["t0_monotonic_ns"] = new_t0
                else:
                    event["client_monotonic_ns"] += new_t0 - old_t0
                if event["event_type"] == "terminal_event":
                    for key in ["run_id", "clock_domain_id", "t0_monotonic_ns", "client_monotonic_ns"]:
                        if key in event["details"]:
                            if key == "run_id":
                                event["details"][key] = extra_run_id
                            elif key == "clock_domain_id":
                                event["details"][key] = "boot-session-extra"
                            elif key == "t0_monotonic_ns":
                                event["details"][key] = new_t0
                            else:
                                event["details"][key] += new_t0 - old_t0
                cloned.append(event)
            events = [json.loads(line) for line in lines] + cloned
            (path / "events.jsonl").write_text(
                "\n".join(json.dumps(event, sort_keys=True, separators=(",", ":")) for event in events) + "\n",
                encoding="utf-8",
            )
            rewrite_summary_report(path, rows)

        def mutate_downstream_campaign(path: Path) -> None:
            rows = read_csv(path / "runs.csv", RUN_COLUMNS)
            for row in rows:
                row["campaign_id"] = "campaign-forged"
            write_csv(path / "runs.csv", RUN_COLUMNS, rows)
            forged_runs = [csv_row_to_run(row) for row in rows]
            forged_summary = compute_summary_rows(forged_runs, profile, "quick", "complete")
            write_csv(path / "summary.csv", SUMMARY_COLUMNS, [summary_to_csv_row(row) for row in forged_summary])
            report_payload = {"campaign_mode": "quick", "campaign_status": "complete", "summary": forged_summary}
            report_json = json.dumps(report_payload, sort_keys=True, separators=(",", ":"))
            visible_rpi = "".join(
                f'<span data-condition="{row["condition_id"]}" data-rpi="{scalar(row["rpi"])}">'
                f'RPI: {scalar(row["rpi"])}</span>'
                for row in forged_summary
            )
            (path / "report.html").write_text(
                "<!doctype html><meta charset=\"utf-8\"><script id=\"canonical-summary\" "
                "type=\"application/json\">" + report_json + "</script>"
                "<section id=\"rpi-values\">" + visible_rpi + "</section>\n",
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
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "raw event schema-version tamper", mutate_event_schema_version)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "raw event clock-unit tamper", mutate_event_clock_unit)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal receipt-version tamper", mutate_receipt_version)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal receipt event-count tamper", mutate_receipt_count)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal receipt missing field", mutate_receipt_missing_field)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal receipt extra field", mutate_receipt_extra_field)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal receipt float field", mutate_receipt_float_field)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "run-started timestamp tamper", mutate_run_started_timestamp)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal timestamp tamper", mutate_terminal_timestamp)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "terminal-before-content ordering", mutate_terminal_before_content)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "start-after-terminal ordering", mutate_start_after_terminal)
        mutate_existing_bundle_and_expect_failure(
            partial_bundle,
            profile,
            profile_hash,
            "partial interrupted missing failure event",
            mutate_partial_missing_failure,
        )
        mutate_existing_bundle_and_expect_failure(
            not_started_bundle,
            profile,
            profile_hash,
            "not-started raw evidence",
            mutate_not_started_evidence,
        )
        mutate_and_expect_bundle_failure(
            bundle,
            profile,
            profile_hash,
            "inter-run overlap/cooldown",
            mutate_interrun_overlap,
        )
        mutate_and_expect_bundle_failure(
            bundle,
            profile,
            profile_hash,
            "inter-run reorder/cooldown",
            mutate_interrun_reorder,
        )
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "duplicate run row/cardinality", mutate_duplicate_run_row)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "Acceptance three-run plan forgery", mutate_acceptance_mode_three_runs)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "unsupported run-failed event", mutate_run_failed_event)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "empty diagnostic namespace", mutate_empty_diagnostic)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "float run_index event field", mutate_event_run_index_float)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "float nominal event field", mutate_event_nominal_float)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "float sequence event field", mutate_event_seq_float)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "float planned-offset event field", mutate_event_offset_float)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "fractional CSV t0", mutate_csv_t0_fraction)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "fractional CSV stall count", mutate_csv_stall_count_fraction)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "over-precise CSV metric", mutate_csv_metric_precision)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "unique extra run/plan cardinality", mutate_unique_extra_run)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "downstream campaign rewrite", mutate_downstream_campaign)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "summary.csv tamper", mutate_summary)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "report tamper", mutate_report)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "remote report script", mutate_report_remote_script)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "remote report image", mutate_report_remote_image)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "duplicate canonical report summary", mutate_report_duplicate_summary)
        mutate_and_expect_bundle_failure(bundle, profile, profile_hash, "forged visible report RPI", mutate_report_visible_rpi)
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
    checks.append("actual complete/partial/failure/not-started events/runs/summary/RPI/report chains and formal run schema")
    checks.append("Quick and full 9-run Acceptance positives, mixed-eligibility RPI and 3-run Acceptance forgery negative")
    checks.append("observed timestamp jitter is accepted only after raw-metric recomputation")
    checks.append("event schema/unit, receipt vocabulary/counts, timestamp/order and frozen-plan cardinality negatives")
    checks.append("clock-domain splice, timestamp-regression and inter-run cooldown chronology negatives")

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


def _mutate_event_details_field(path: Path, event_type: str, seq: int, field: str, value: Any) -> None:
    lines = (path / "events.jsonl").read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        event = json.loads(line)
        if event["event_type"] == event_type and event["details"].get("seq") == seq:
            event["details"][field] = value
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
