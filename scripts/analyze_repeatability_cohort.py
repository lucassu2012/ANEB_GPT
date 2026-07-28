#!/usr/bin/env python3
"""Validate homogeneous strict-v2 ANEB cohorts and emit repeatability diagnostics.

Only Token TTFT has an approved repeatability threshold (D-58). Realtime and
Network values are descriptive diagnostics until the Product Owner approves a
family-specific policy; this module never promotes single-run confidence or
formal-baseline eligibility.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import sys
from pathlib import Path
from typing import Any, Iterable

from scripts.analyze_ttft_repeatability import RepeatabilityError, analyze as analyze_ttft
from scripts.verify_result_jsonl import _load_schema_validators


SCHEMA_VERSION = "aneb-repeatability-cohort-v1"

PRIMARY_METRICS: dict[str, tuple[str, ...]] = {
    "token_simulation": ("TOK-B04",),
    "ai_realtime_simulation": ("LIVE-B05", "LIVE-N02", "LIVE-B08"),
    "network_comprehensive": ("NET-B01", "NET-B02", "NET-B04"),
}


class CohortError(ValueError):
    """Raised when input cannot support a homogeneous repeatability cohort."""


def _object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CohortError(f"duplicate_json_key:{key}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise CohortError(f"non_finite_json_number:{value}")


def load_jsonl(paths: Iterable[Path]) -> list[dict[str, Any]]:
    """Load JSONL without accepting duplicate keys or non-finite numbers."""

    documents: list[dict[str, Any]] = []
    for path in paths:
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError) as exc:
            raise CohortError(f"input_read_failed:{path}:{exc}") from exc
        for line_number, line in enumerate(lines, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(
                    line,
                    object_pairs_hook=_object_without_duplicates,
                    parse_constant=_reject_non_finite,
                )
            except (json.JSONDecodeError, CohortError, ValueError) as exc:
                raise CohortError(f"invalid_json:{path}:{line_number}:{exc}") from exc
            if not isinstance(value, dict):
                raise CohortError(f"result_not_object:{path}:{line_number}")
            documents.append(value)
    if not documents:
        raise CohortError("empty_cohort")
    return documents


def _object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CohortError(f"expected_object:{path}")
    return value


def _required(mapping: dict[str, Any], key: str, path: str) -> Any:
    if key not in mapping:
        raise CohortError(f"missing_field:{path}/{key}")
    return mapping[key]


def _canonical_digest(value: Any) -> str:
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise CohortError(f"canonical_json_failed:{exc}") from exc
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def _validate_strict_v2(document: dict[str, Any], *, root: Path, index: int) -> None:
    if document.get("schema_version") != "aneb-result-v2":
        raise CohortError(f"strict_v2_required:run_index={index}")
    validator = _load_schema_validators(root.resolve())["aneb-result-v2"]
    errors = sorted(validator.iter_errors(document), key=lambda item: list(item.absolute_path))
    if errors:
        error = errors[0]
        pointer = "/" + "/".join(str(part) for part in error.absolute_path)
        raise CohortError(f"schema_invalid:run_index={index}:{pointer}:{error.message}")


def _cohort_identity(document: dict[str, Any]) -> dict[str, Any]:
    producer = _object(_required(document, "producer", "$"), "$/producer")
    profile = _object(_required(document, "profile", "$"), "$/profile")
    claim = _object(_required(document, "claim", "$"), "$/claim")
    context = _object(_required(document, "context", "$"), "$/context")
    endpoint = _object(_required(context, "endpoint", "$/context"), "$/context/endpoint")
    device = _object(_required(context, "device", "$/context"), "$/context/device")
    network = _object(_required(context, "network", "$/context"), "$/context/network")
    evaluation = _object(_required(document, "evaluation", "$"), "$/evaluation")
    algorithms = _object(
        _required(evaluation, "algorithm_versions", "$/evaluation"),
        "$/evaluation/algorithm_versions",
    )

    profile_fingerprint = _object(
        _required(profile, "profile_fingerprint", "$/profile"),
        "$/profile/profile_fingerprint",
    )
    runtime_hash_value = profile.get("runtime_artifact_hash")
    runtime_hash = None
    if runtime_hash_value is not None:
        runtime_hash = _object(runtime_hash_value, "$/profile/runtime_artifact_hash")

    if network.get("availability") != "observed":
        raise CohortError("network_context_not_observed")
    if network.get("active_transport") not in {"wifi", "cellular"}:
        raise CohortError("active_transport_not_observed")
    if network.get("vpn_active") is not False:
        raise CohortError("vpn_active_or_unknown")
    if device.get("availability") != "observed":
        raise CohortError("device_context_not_observed")

    stable_algorithms = {
        key: value for key, value in algorithms.items() if key != "finalized_at_epoch_ms"
    }
    return {
        "schema_version": document["schema_version"],
        "test_type": _required(document, "test_type", "$"),
        "producer": {
            key: _required(producer, key, "$/producer")
            for key in (
                "component",
                "component_version",
                "exporter_version",
                "build_type",
            )
        },
        "profile": {
            "contract_version": _required(profile, "contract_version", "$/profile"),
            "profile_id": _required(profile, "profile_id", "$/profile"),
            "profile_version": _required(profile, "profile_version", "$/profile"),
            "variant": _required(profile, "variant", "$/profile"),
            "profile_fingerprint": profile_fingerprint,
            "runtime_artifact_hash": runtime_hash,
        },
        "claim": claim,
        "device": device,
        "endpoint": endpoint,
        "network": {
            key: network.get(key)
            for key in (
                "availability",
                "requested_transport",
                "active_transport",
                "capabilities",
                "interface_name",
                "validated",
                "not_suspended",
                "metered",
                "vpn_active",
                "private_dns_mode",
            )
        },
        "algorithm_versions": stable_algorithms,
    }


def _validate_run(document: dict[str, Any], *, index: int) -> tuple[str, int]:
    run = _object(_required(document, "run", "$"), "$/run")
    run_id = _required(run, "run_id", "$/run")
    if not isinstance(run_id, str) or not run_id:
        raise CohortError(f"invalid_run_id:run_index={index}")
    if run.get("status") != "completed" or run.get("validity") != "valid":
        raise CohortError(f"run_not_completed_valid:{run_id}")
    started = run.get("started_at_epoch_ms")
    if isinstance(started, bool) or not isinstance(started, int):
        raise CohortError(f"invalid_started_at:{run_id}")
    score = _object(
        _required(_object(document["evaluation"], "$/evaluation"), "score", "$/evaluation"),
        "$/evaluation/score",
    )
    if score.get("verdict") == "invalid":
        raise CohortError(f"invalid_evaluation:{run_id}")
    return run_id, started


def _diagnostic(
    documents: list[dict[str, Any]], metric_id: str, run_ids: list[str]
) -> dict[str, Any]:
    values: list[float] = []
    sample_counts: list[int] = []
    minimum_counts: list[int] = []
    for document, run_id in zip(documents, run_ids, strict=True):
        evaluation = _object(document["evaluation"], "$/evaluation")
        metrics = _object(_required(evaluation, "metrics", "$/evaluation"), "$/evaluation/metrics")
        metric = _object(
            _required(metrics, metric_id, "$/evaluation/metrics"),
            f"$/evaluation/metrics/{metric_id}",
        )
        if metric.get("state") != "observed":
            raise CohortError(f"metric_not_observed:{metric_id}:{run_id}")
        value = metric.get("value")
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            raise CohortError(f"metric_value_invalid:{metric_id}:{run_id}")
        numeric_value = float(value)
        if numeric_value < 0.0:
            raise CohortError(f"metric_value_negative:{metric_id}:{run_id}")
        sample_count = metric.get("sample_count")
        minimum_count = metric.get("minimum_sample_count")
        if (
            isinstance(sample_count, bool)
            or not isinstance(sample_count, int)
            or sample_count <= 0
            or isinstance(minimum_count, bool)
            or not isinstance(minimum_count, int)
            or minimum_count < 0
        ):
            raise CohortError(f"metric_sample_count_invalid:{metric_id}:{run_id}")
        values.append(numeric_value)
        sample_counts.append(sample_count)
        minimum_counts.append(minimum_count)

    mean = statistics.fmean(values)
    sample_stddev = statistics.stdev(values) if len(values) > 1 else None
    if mean == 0.0:
        cv_state = "undefined_zero_mean"
        sample_cv = None
    elif sample_stddev is None:
        cv_state = "undefined_single_sample"
        sample_cv = None
    else:
        cv_state = "computed"
        sample_cv = sample_stddev / mean
    return {
        "metric_id": metric_id,
        "run_values": values,
        "run_sample_counts": sample_counts,
        "run_minimum_sample_counts": minimum_counts,
        "all_run_minimum_samples_satisfied": all(
            actual >= required for actual, required in zip(sample_counts, minimum_counts, strict=True)
        ),
        "mean": mean,
        "median": statistics.median(values),
        "sample_stddev": sample_stddev,
        "cv_state": cv_state,
        "sample_cv": sample_cv,
    }


def _radio_integrity(
    documents: list[dict[str, Any]], run_ids: list[str]
) -> dict[str, Any]:
    runs: list[dict[str, Any]] = []
    for document, run_id in zip(documents, run_ids, strict=True):
        context = _object(_required(document, "context", "$"), "$/context")
        radio = _object(_required(context, "radio", "$/context"), "$/context/radio")
        samples = _required(radio, "samples", "$/context/radio")
        if not isinstance(samples, list):
            raise CohortError(f"radio_samples_invalid:{run_id}")
        sample_count = radio.get("sample_count")
        if (
            radio.get("collection_status") != "collected"
            or isinstance(sample_count, bool)
            or not isinstance(sample_count, int)
            or sample_count != len(samples)
            or sample_count < 2
        ):
            raise CohortError(f"radio_inline_series_required:{run_id}")

        timestamps: list[int] = []
        stale_count = 0
        switched_count = 0
        for index, raw_sample in enumerate(samples):
            sample = _object(raw_sample, f"$/context/radio/samples/{index}")
            timestamp = sample.get("elapsed_realtime_nanos")
            if isinstance(timestamp, bool) or not isinstance(timestamp, int):
                raise CohortError(f"radio_timestamp_invalid:{run_id}:{index}")
            timestamps.append(timestamp)
            stale_count += int(sample.get("stale") is True)
            switched_count += int(sample.get("sub_switched") is True)
        if any(right <= left for left, right in zip(timestamps, timestamps[1:])):
            raise CohortError(f"radio_timestamps_not_strictly_increasing:{run_id}")

        gaps = [
            (right - left) / 1_000_000_000.0
            for left, right in zip(timestamps, timestamps[1:])
        ]
        median_gap = statistics.median(gaps)
        ordered_gaps = sorted(gaps)
        p95_gap = ordered_gaps[math.ceil(0.95 * len(ordered_gaps)) - 1]
        runs.append(
            {
                "run_id": run_id,
                "status": "structurally_valid",
                "sample_count": sample_count,
                "span_seconds": (timestamps[-1] - timestamps[0]) / 1_000_000_000.0,
                "median_gap_seconds": median_gap,
                "observed_median_frequency_hz": 1.0 / median_gap,
                "p95_gap_seconds": p95_gap,
                "minimum_gap_seconds": min(gaps),
                "maximum_gap_seconds": max(gaps),
                "stale_sample_count": stale_count,
                "subscription_switch_sample_count": switched_count,
                "cadence_verdict": None,
            }
        )
    return {
        "policy_mode": "diagnostic_only",
        "nominal_frequency_hz": 1.0,
        "formal_baseline_eligible": False,
        "runs": runs,
    }


def analyze(documents: list[dict[str, Any]], *, root: Path) -> dict[str, Any]:
    """Return a fail-closed repeatability cohort report for one result family."""

    if not documents:
        raise CohortError("empty_cohort")
    for index, document in enumerate(documents):
        if not isinstance(document, dict):
            raise CohortError(f"result_not_object:run_index={index}")
        _validate_strict_v2(document, root=root, index=index)

    identities = [_cohort_identity(document) for document in documents]
    identity = identities[0]
    family = identity["test_type"]
    if family not in PRIMARY_METRICS:
        raise CohortError(f"unsupported_result_family:{family}")
    for index, candidate in enumerate(identities[1:], 1):
        if candidate != identity:
            raise CohortError(f"heterogeneous_cohort:run_index={index}")

    run_ids: list[str] = []
    starts: list[int] = []
    digests: list[str] = []
    for index, document in enumerate(documents):
        run_id, started = _validate_run(document, index=index)
        if run_id in run_ids:
            raise CohortError(f"duplicate_run_id:{run_id}")
        run_ids.append(run_id)
        starts.append(started)
        digests.append(_canonical_digest(document))

    diagnostics = {
        metric_id: _diagnostic(documents, metric_id, run_ids)
        for metric_id in PRIMARY_METRICS[family]
    }
    policy: dict[str, Any] = {
        "authority": None,
        "mode": "diagnostic_only",
        "metric_id": None,
        "formal_baseline_eligible": False,
        "single_run_confidence_unchanged": True,
    }
    status = "policy_pending"
    authorized_evaluation = None
    if family == "token_simulation":
        try:
            authorized_evaluation = analyze_ttft(documents)
        except RepeatabilityError as exc:
            raise CohortError(f"d58_invalid:{exc}") from exc
        policy.update(authority="D-58", mode="authorized_threshold", metric_id="TOK-B04")
        status = authorized_evaluation["status"]

    return {
        "schema_version": SCHEMA_VERSION,
        "status": status,
        "policy": policy,
        "cohort": {
            "identity": identity,
            "identity_sha256": _canonical_digest(identity),
            "run_count": len(documents),
            "run_ids": run_ids,
            "run_canonical_sha256": digests,
            "started_at_span_ms": max(starts) - min(starts),
        },
        "metric_diagnostics": diagnostics,
        "radio_integrity": _radio_integrity(documents, run_ids),
        "authorized_evaluation": authorized_evaluation,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Strict-v2 ANEB result JSONL files")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="ANEB repository root")
    parser.add_argument("--output", type=Path, help="Write UTF-8 JSON report to this path")
    args = parser.parse_args(argv)

    try:
        report = analyze(load_jsonl(args.inputs), root=args.root)
        exit_code = 1 if report["status"] == "fail" else 0
    except CohortError as exc:
        report = {
            "schema_version": SCHEMA_VERSION,
            "status": "invalid",
            "error": str(exc),
        }
        exit_code = 2

    rendered = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.output is None:
        sys.stdout.write(rendered)
    else:
        args.output.write_text(rendered, encoding="utf-8")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
