#!/usr/bin/env python3
"""Machine checks for the Prototype 0.1 binding contracts.

This is a development/review helper.  It validates the four published JSON
contracts, regenerates the three canonical schedules, and exercises the
counterexamples called out in Issue #14.  It is not a runtime implementation
and is intentionally not part of the release package.
"""

from __future__ import annotations

import copy
import hashlib
import json
import math
import sys
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parent
EXPECTED_SCHEDULE_HASHES = {
    "baseline_v0.1": "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
    "slow_v0.1": "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
    "unstable_v0.1": "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
}
CONDITIONS = {
    "baseline_v0.1": {"version": "0.1", "nominal_interval_ms": 50, "initial_delay_ms": 200, "terminal_delay_ms": 50, "pauses": []},
    "slow_v0.1": {"version": "0.1", "nominal_interval_ms": 125, "initial_delay_ms": 650, "terminal_delay_ms": 125, "pauses": []},
    "unstable_v0.1": {"version": "0.1", "nominal_interval_ms": 65, "initial_delay_ms": 350, "terminal_delay_ms": 65, "pauses": [(40, 900), (85, 1400)]},
}


def load_json(name: str) -> dict[str, Any]:
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def canonical_file_bytes(name: str) -> bytes:
    data = (ROOT / name).read_bytes().replace(b"\r\n", b"\n")
    if data.startswith(b"\xef\xbb\xbf"):
        raise AssertionError(f"{name} has a UTF-8 BOM")
    return data


def schedule_offsets(condition_id: str) -> tuple[list[int], int]:
    condition = CONDITIONS[condition_id]
    offsets: list[int] = []
    for seq in range(1, 121):
        if seq == 1:
            offset = condition["initial_delay_ms"]
        else:
            offset = offsets[-1] + condition["nominal_interval_ms"]
            # A pause after N changes only the N -> N+1 transition.  Multiple
            # pauses on one transition would add, hence the explicit sum.
            offset += sum(extra for after, extra in condition["pauses"] if seq == after + 1)
        offsets.append(offset)
    terminal = offsets[-1] + condition["terminal_delay_ms"]
    return offsets, terminal


def canonical_schedule(condition_id: str) -> bytes:
    offsets, _ = schedule_offsets(condition_id)
    rows = ["seq,planned_offset_ms,payload_id"]
    rows.extend(f"{seq},{offset},ref-{seq:04d}" for seq, offset in enumerate(offsets, 1))
    return ("\n".join(rows) + "\n").encode("utf-8")


def validate_instance(schema: dict[str, Any], instance: dict[str, Any]) -> list[str]:
    try:
        from jsonschema import Draft202012Validator, FormatChecker
    except ModuleNotFoundError as exc:  # pragma: no cover - environment guard
        raise RuntimeError("jsonschema is required: python -m pip install jsonschema") from exc
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


def median(values: Iterable[float]) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("median requires at least one value")
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return float(ordered[middle])
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def is_stall(gap_ns: int, nominal_interval_ms: int) -> bool:
    threshold_ns = max(500_000_000, 4 * nominal_interval_ms * 1_000_000)
    return gap_ns > threshold_ns


def derive_raw_metrics(event_times_ns: list[int], done_ns: int, nominal_interval_ms: int) -> dict[str, float | int]:
    if len(event_times_ns) < 2:
        raise ValueError("at least two content events are required")
    gaps = [right - left for left, right in zip(event_times_ns, event_times_ns[1:])]
    nominal_ns = nominal_interval_ms * 1_000_000
    stalls = [gap for gap in gaps if is_stall(gap, nominal_interval_ms)]
    span_ns = event_times_ns[-1] - event_times_ns[0]
    duration_ns = sum(gap - nominal_ns for gap in stalls)
    return {
        "ttft_ms": event_times_ns[0] / 1_000_000,
        "completion_ms": done_ns / 1_000_000,
        "stream_span_ms": span_ns / 1_000_000,
        "stream_event_rate_eps": 119_000 / (span_ns / 1_000_000),
        "stall_threshold_ms": max(500, 4 * nominal_interval_ms),
        "stall_count": len(stalls),
        "stall_duration_ms": duration_ns / 1_000_000,
        "stall_fraction": max(0.0, min(1.0, duration_ns / span_ns)),
    }


def assert_raw_metrics_match(record: dict[str, Any], event_times_ns: list[int], done_ns: int, nominal_interval_ms: int) -> None:
    expected = derive_raw_metrics(event_times_ns, done_ns, nominal_interval_ms)
    actual = record["metrics"]
    for key, value in expected.items():
        if isinstance(value, float):
            if not math.isclose(float(actual[key]), value, rel_tol=0, abs_tol=1e-9):
                raise ValueError(f"raw-event mismatch for {key}: {actual[key]} != {value}")
        elif actual[key] != value:
            raise ValueError(f"raw-event mismatch for {key}: {actual[key]} != {value}")


def base_clock() -> dict[str, Any]:
    return {
        "source": "android.os.SystemClock.elapsedRealtimeNanos",
        "unit": "ns",
        "epoch": "device_boot",
        "includes_deep_sleep": True,
        "t0_boundary": "immediately_before_transport_dispatch",
        "event_boundary": "after_complete_sse_content_event_decode_and_identity_validation",
        "done_boundary": "after_complete_done_event_decode_and_identity_validation",
    }


def valid_capabilities(profile_hash: str) -> dict[str, Any]:
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
        "workload": {"id": "streaming_text_reference_v0.1", "version": "0.1", "content_event_count": 120},
        "conditions": [
            {"id": "baseline_v0.1", "version": "0.1", "nominal_interval_ms": 50, "schedule_sha256": EXPECTED_SCHEDULE_HASHES["baseline_v0.1"]},
            {"id": "slow_v0.1", "version": "0.1", "nominal_interval_ms": 125, "schedule_sha256": EXPECTED_SCHEDULE_HASHES["slow_v0.1"]},
            {"id": "unstable_v0.1", "version": "0.1", "nominal_interval_ms": 65, "schedule_sha256": EXPECTED_SCHEDULE_HASHES["unstable_v0.1"]},
        ],
        "evidence_schema_version": "aneb-prototype-evidence-0.1",
        "score_policy_id": "rpi-0.1",
        "terminal_receipt_version": "prototype-terminal-receipt-0.1",
    }


def valid_run(profile_hash: str, condition_id: str = "baseline_v0.1") -> dict[str, Any]:
    condition = CONDITIONS[condition_id]
    offsets, terminal = schedule_offsets(condition_id)
    raw = derive_raw_metrics([offset * 1_000_000 for offset in offsets], terminal * 1_000_000, condition["nominal_interval_ms"])
    return {
        "schema_version": "aneb-prototype-run-record-0.1",
        "campaign_id": "campaign-0001",
        "run_id": "run-0001",
        "campaign_mode": "acceptance",
        "run_index": 1,
        "profile_manifest_sha256": profile_hash,
        "condition": {"id": condition_id, "version": "0.1", "nominal_interval_ms": condition["nominal_interval_ms"]},
        "run_status": "complete",
        "task_success": True,
        "clock": base_clock(),
        "attempt_started_at_utc": "2026-08-28T10:00:00Z",
        "attempt_ended_at_utc": "2026-08-28T10:00:07Z",
        "events_expected": 120,
        "events_received": 120,
        "metrics": raw,
        "schedule_hash": EXPECTED_SCHEDULE_HASHES[condition_id],
        "terminal_receipt_valid": True,
        "score_eligible": True,
        "failure_reason": None,
    }


def not_started_run(profile_hash: str) -> dict[str, Any]:
    result = valid_run(profile_hash)
    result.update({
        "campaign_mode": "quick",
        "run_status": "not_started",
        "task_success": False,
        "attempt_started_at_utc": None,
        "attempt_ended_at_utc": None,
        "events_received": 0,
        "terminal_receipt_valid": None,
        "score_eligible": False,
        "failure_reason": "not_started",
    })
    result["metrics"] = {key: None for key in result["metrics"]}
    return result


def main() -> int:
    schemas = {
        "capabilities": load_json("capabilities.schema.json"),
        "run-record": load_json("run-record.schema.json"),
    }
    policy = load_json("score-policy.json")
    try:
        from jsonschema import Draft202012Validator
    except ModuleNotFoundError as exc:  # pragma: no cover - environment guard
        raise RuntimeError("jsonschema is required: python -m pip install jsonschema") from exc
    for name, schema in schemas.items():
        Draft202012Validator.check_schema(schema)
    checks: list[str] = ["Draft 2020-12 schemas valid"]
    profile_bytes = (ROOT / "profile-manifest.json").read_bytes()
    # Git stores these text contracts as UTF-8 LF bytes.  Normalize a Windows
    # checkout before hashing so the identity is stable across worktrees.
    profile_canonical_bytes = profile_bytes.replace(b"\r\n", b"\n")
    assert not profile_canonical_bytes.startswith(b"\xef\xbb\xbf")
    profile_hash = hashlib.sha256(profile_canonical_bytes).hexdigest()
    profile = json.loads(profile_bytes)
    assert policy["policy_id"] == "rpi-0.1"
    assert policy["display_name"] == "Relative Prototype Index (same-campaign synthetic comparison)"
    assert policy["success_rate_definition"] == "current_condition_successful_runs_divided_by_current_condition_planned_runs"
    assert policy["median"]["algorithm"] == "arithmetic_mean_of_two_middle_sorted_values"
    assert policy["null_reason_fields"] == ["primary_null_reason", "all_null_reasons"]
    assert "clamp" in policy["final_expression"] and "round_half_away_from_zero" in policy["final_expression"]
    for condition_id, expected in EXPECTED_SCHEDULE_HASHES.items():
        data = canonical_schedule(condition_id)
        actual = hashlib.sha256(data).hexdigest()
        assert actual == expected, f"{condition_id} hash {actual} != {expected}"
        assert data.startswith(b"seq,planned_offset_ms,payload_id\n") and data.endswith(b"\n")
        assert len(data.splitlines()) == 121
        checks.append(f"canonical {condition_id} hash")
        manifest_condition = next(item for item in profile["conditions"] if item["id"] == condition_id)
        assert manifest_condition["schedule_sha256"] == expected

    assert profile["workload"]["schedule_canonicalization"] == {
        "format": "csv", "encoding": "utf-8", "line_endings": "lf", "bom": False,
        "final_newline": True, "delimiter": ",", "quote_fields": False, "space_policy": "none",
        "integer_encoding": "base10_ascii", "header": "seq,planned_offset_ms,payload_id",
        "row_template": "<seq>,<planned_offset_ms>,ref-<seq:04d>", "payload_id_template": "ref-%04d",
        "included_event_types": ["content"], "terminal_event_included": False,
        "pause_rule": "extra_delay_applies_once_to_event_seq_after_seq", "pause_accumulation": "additive_once_per_pause",
        "hash_algorithm": "sha256", "hash_encoding": "bare_lowercase_hex_64",
    }
    checks.append("profile canonicalization and manifest hashes")
    assert schemas["capabilities"]["properties"]["profile_manifest_sha256"]["const"] == profile_hash
    assert schemas["run-record"]["properties"]["profile_manifest_sha256"]["const"] == profile_hash
    checks.append("score policy and profile binding valid")

    capabilities = valid_capabilities(profile_hash)
    expect_valid(schemas["capabilities"], capabilities, "canonical capabilities")
    checks.append("canonical capabilities valid")
    duplicate = copy.deepcopy(capabilities)
    duplicate["conditions"] = [duplicate["conditions"][0]] * 3
    expect_invalid(schemas["capabilities"], duplicate, "duplicate condition counterexample")
    prefixed = copy.deepcopy(capabilities)
    prefixed["conditions"][0]["schedule_sha256"] = "sha256:" + prefixed["conditions"][0]["schedule_sha256"]
    expect_invalid(schemas["capabilities"], prefixed, "prefixed hash counterexample")
    checks.append("capability negative vectors rejected")

    run = valid_run(profile_hash)
    expect_valid(schemas["run-record"], run, "canonical successful run")
    checks.append("canonical successful run valid")
    malicious = copy.deepcopy(run)
    malicious["campaign_mode"] = "quick"
    malicious["run_index"] = 9
    malicious["attempt_started_at_utc"] = None
    malicious["attempt_ended_at_utc"] = None
    malicious["metrics"] = {key: None for key in malicious["metrics"]}
    expect_invalid(schemas["run-record"], malicious, "quick index/null success counterexample")
    bad_condition = copy.deepcopy(run)
    bad_condition["condition"] = {"id": "slow_v0.1", "version": "0.1", "nominal_interval_ms": 125}
    expect_invalid(schemas["run-record"], bad_condition, "condition/schedule mismatch counterexample")
    complete_false = copy.deepcopy(run)
    complete_false["task_success"] = False
    expect_invalid(schemas["run-record"], complete_false, "complete/failure status contradiction")
    expect_valid(schemas["run-record"], not_started_run(profile_hash), "not-started null semantics")
    checks.append("run negative and null vectors")

    assert median([1, 3]) == 2.0 and median([1, 3, 5]) == 3.0
    assert not is_stall(500_000_000, 50)
    assert is_stall(500_000_001, 50)
    assert not is_stall(800_000_000, 200)
    assert is_stall(800_000_001, 200)
    checks.append("arithmetic even median and strict stall boundaries")

    baseline_offsets, baseline_done = schedule_offsets("baseline_v0.1")
    baseline_metrics = derive_raw_metrics([x * 1_000_000 for x in baseline_offsets], baseline_done * 1_000_000, 50)
    assert baseline_metrics["ttft_ms"] == 200 and baseline_metrics["completion_ms"] == 6200
    assert baseline_metrics["stream_span_ms"] == 5950 and baseline_metrics["stall_count"] == 0
    unstable_offsets, unstable_done = schedule_offsets("unstable_v0.1")
    unstable_metrics = derive_raw_metrics([x * 1_000_000 for x in unstable_offsets], unstable_done * 1_000_000, 65)
    assert unstable_metrics["stall_count"] == 2 and unstable_metrics["stall_duration_ms"] == 2300
    assert math.isclose(float(unstable_metrics["stall_fraction"]), 2300 / 10035, rel_tol=0, abs_tol=1e-12)
    assert_raw_metrics_match(run, [x * 1_000_000 for x in baseline_offsets], baseline_done * 1_000_000, 50)
    mutated = copy.deepcopy(run)
    mutated["metrics"]["completion_ms"] += 1
    try:
        assert_raw_metrics_match(mutated, [x * 1_000_000 for x in baseline_offsets], baseline_done * 1_000_000, 50)
    except ValueError:
        pass
    else:
        raise AssertionError("mutated run metric was not rejected by raw-event verifier")
    checks.append("raw-events-to-report recomputation authority")

    print(f"PASS: {len(checks)} contract checks")
    for item in checks:
        print(f"  - {item}")
    print(f"profile_manifest_sha256={profile_hash}")
    for name in ("capabilities.schema.json", "run-record.schema.json", "score-policy.json"):
        print(f"{name}_sha256={hashlib.sha256(canonical_file_bytes(name)).hexdigest()}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, RuntimeError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
