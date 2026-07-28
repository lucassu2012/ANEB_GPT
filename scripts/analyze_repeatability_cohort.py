#!/usr/bin/env python3
"""Validate homogeneous strict-v2 ANEB cohorts and emit repeatability diagnostics.

The legacy ``analyze`` entry point preserves the D-58-only diagnostic contract.
The separate ``analyze_qualification`` entry point applies the catalog-bound
D-110 engineering policy without promoting single-run confidence or formal-
baseline eligibility.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import sys
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

if not __package__:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.analyze_ttft_repeatability import RepeatabilityError, analyze as analyze_ttft
from scripts.verify_result_jsonl import _load_schema_validators
from scripts.verify_spec_catalog import canonical_json_sha256, load_json, validate_catalog


SCHEMA_VERSION = "aneb-repeatability-cohort-v1"
QUALIFICATION_SCHEMA_VERSION = "aneb-repeatability-qualification-v1"
QUALIFICATION_POLICY_ID = "aneb-repeatability-qualification-balanced-v1"
QUALIFICATION_POLICY_VERSION = "1.0.0"

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


def load_json_document(path: Path, *, label: str) -> dict[str, Any]:
    """Load one strict JSON object for an analyzer-to-analyzer handoff."""

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_object_without_duplicates,
            parse_constant=_reject_non_finite,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, CohortError, ValueError) as exc:
        raise CohortError(f"{label}_read_failed:{path}:{exc}") from exc
    if not isinstance(value, dict):
        raise CohortError(f"{label}_not_object:{path}")
    return value


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

    capabilities = network.get("capabilities")
    if not isinstance(capabilities, list) or any(
        not isinstance(item, str) for item in capabilities
    ):
        raise CohortError("network_capabilities_invalid")
    stable_capabilities = [
        item
        for item in capabilities
        if re.fullmatch(r"(?:up|down)_kbps=[0-9]+", item) is None
    ]

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
            **{
                key: network.get(key)
                for key in (
                    "availability",
                    "requested_transport",
                    "active_transport",
                    "interface_name",
                    "validated",
                    "not_suspended",
                    "metered",
                    "vpn_active",
                    "private_dns_mode",
                )
            },
            "capabilities": stable_capabilities,
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


def _load_qualification_policy(
    root: Path,
) -> tuple[dict[str, Any], str, Draft202012Validator]:
    """Load the catalog-bound D-110 policy and report schema."""

    resolved_root = root.resolve()
    catalog_errors = validate_catalog(resolved_root)
    if catalog_errors:
        raise CohortError(f"qualification_catalog_invalid:{catalog_errors[0]}")

    load_errors: list[str] = []
    catalog = load_json(resolved_root / "spec/catalog.json", "catalog", load_errors)
    if load_errors or not isinstance(catalog, dict):
        reason = load_errors[0] if load_errors else "catalog_not_object"
        raise CohortError(f"qualification_catalog_invalid:{reason}")
    entries = catalog.get("repeatability_qualification_policies")
    if not isinstance(entries, list):
        raise CohortError("qualification_policy_catalog_missing")
    matches = [
        entry
        for entry in entries
        if isinstance(entry, dict)
        and entry.get("policy_id") == QUALIFICATION_POLICY_ID
        and entry.get("version") == QUALIFICATION_POLICY_VERSION
    ]
    if len(matches) != 1:
        raise CohortError("qualification_policy_catalog_identity_invalid")
    entry = matches[0]
    policy_path = entry.get("path")
    expected_sha = entry.get("canonical_sha256")
    if not isinstance(policy_path, str) or not isinstance(expected_sha, str):
        raise CohortError("qualification_policy_catalog_binding_invalid")

    policy = load_json(resolved_root / policy_path, "qualification_policy", load_errors)
    if load_errors or not isinstance(policy, dict):
        reason = load_errors[0] if load_errors else "policy_not_object"
        raise CohortError(f"qualification_policy_invalid:{reason}")
    actual_sha = canonical_json_sha256(policy)
    if actual_sha != expected_sha:
        raise CohortError("qualification_policy_sha_mismatch")
    if (
        policy.get("policy_id") != QUALIFICATION_POLICY_ID
        or policy.get("version") != QUALIFICATION_POLICY_VERSION
        or policy.get("decision_id") != "D-110"
        or policy.get("status") != "approved"
    ):
        raise CohortError("qualification_policy_identity_invalid")

    schema_entries = catalog.get("schemas")
    if not isinstance(schema_entries, list):
        raise CohortError("qualification_schema_catalog_missing")
    schema_matches = [
        item
        for item in schema_entries
        if isinstance(item, dict)
        and item.get("contract_version") == QUALIFICATION_SCHEMA_VERSION
    ]
    if len(schema_matches) != 1:
        raise CohortError("qualification_schema_catalog_identity_invalid")
    schema_path = schema_matches[0].get("path")
    if not isinstance(schema_path, str):
        raise CohortError("qualification_schema_catalog_binding_invalid")
    qualification_schema = load_json(
        resolved_root / schema_path,
        "qualification_schema",
        load_errors,
    )
    if load_errors or not isinstance(qualification_schema, dict):
        reason = load_errors[0] if load_errors else "schema_not_object"
        raise CohortError(f"qualification_schema_invalid:{reason}")
    try:
        Draft202012Validator.check_schema(qualification_schema)
    except SchemaError as exc:
        raise CohortError("qualification_schema_invalid") from exc
    return policy, actual_sha, Draft202012Validator(qualification_schema)


def _qualification_radio_gate(
    diagnostic: dict[str, Any], policy: dict[str, Any]
) -> dict[str, Any]:
    radio_policy = _object(
        _required(policy, "radio_integrity", "$/policy"),
        "$/policy/radio_integrity",
    )
    runs: list[dict[str, Any]] = []
    for raw_run in diagnostic["runs"]:
        run = dict(raw_run)
        passed = (
            run["p95_gap_seconds"]
            <= radio_policy["p95_gap_seconds_max_inclusive"]
            and run["maximum_gap_seconds"]
            < radio_policy["max_gap_seconds_max_exclusive"]
            and run["stale_sample_count"] <= radio_policy["stale_samples_max"]
            and run["subscription_switch_sample_count"]
            <= radio_policy["subscription_switches_max"]
        )
        run["cadence_verdict"] = "pass" if passed else "fail"
        runs.append(run)
    return {
        "policy_mode": "authorized_threshold",
        "status": (
            "pass" if all(run["cadence_verdict"] == "pass" for run in runs) else "fail"
        ),
        "formal_baseline_eligible": False,
        "thresholds": radio_policy,
        "runs": runs,
    }


def _qualification_profile_quality_gate(
    documents: list[dict[str, Any]], run_ids: list[str]
) -> dict[str, Any]:
    failures: list[dict[str, str]] = []
    for document, run_id in zip(documents, run_ids, strict=True):
        metrics = _object(document["evaluation"]["metrics"], "$/evaluation/metrics")
        for metric_id, raw_metric in metrics.items():
            metric = _object(raw_metric, f"$/evaluation/metrics/{metric_id}")
            if metric.get("required_for_score") is not True:
                continue
            actual = metric.get("sample_count")
            minimum = metric.get("minimum_sample_count")
            target = metric.get("quality_target")
            compliance = metric.get("compliance_ratio")
            required_ratio = (
                target.get("required_compliance_ratio")
                if isinstance(target, dict)
                else None
            )
            reason = None
            if metric.get("state") != "observed":
                reason = "metric_not_observed"
            elif (
                isinstance(actual, bool)
                or not isinstance(actual, int)
                or isinstance(minimum, bool)
                or not isinstance(minimum, int)
                or actual < minimum
            ):
                reason = "minimum_sample_count_not_met"
            elif (
                isinstance(compliance, bool)
                or not isinstance(compliance, (int, float))
                or not math.isfinite(float(compliance))
                or isinstance(required_ratio, bool)
                or not isinstance(required_ratio, (int, float))
                or not math.isfinite(float(required_ratio))
                or float(compliance) < float(required_ratio)
            ):
                reason = "quality_target_not_met"
            if reason is not None:
                failures.append(
                    {"run_id": run_id, "metric_id": metric_id, "reason": reason}
                )
    return {
        "status": "pass" if not failures else "fail",
        "independent_from_repeatability": True,
        "failures": failures,
    }


def _qualification_metric_gate(
    diagnostic: dict[str, Any], metric_policy: dict[str, Any]
) -> dict[str, Any]:
    statistic = metric_policy.get("statistic")
    if statistic == "absolute_range":
        value = max(diagnostic["run_values"]) - min(diagnostic["run_values"])
    elif statistic == "sample_cv":
        value = diagnostic["sample_cv"]
    else:
        raise CohortError(f"qualification_statistic_unsupported:{statistic}")
    threshold = metric_policy.get("max_inclusive")
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(float(value))
        or isinstance(threshold, bool)
        or not isinstance(threshold, (int, float))
        or not math.isfinite(float(threshold))
    ):
        passed = False
    else:
        passed = (
            float(value) <= float(threshold)
            and diagnostic["all_run_minimum_samples_satisfied"] is True
        )
    return {
        "metric_id": diagnostic["metric_id"],
        "statistic": statistic,
        "value": value,
        "max_inclusive": threshold,
        "minimum_samples_satisfied": diagnostic[
            "all_run_minimum_samples_satisfied"
        ],
        "status": "pass" if passed else "fail",
    }


def _qualification_token_gate(
    documents: list[dict[str, Any]], family_policy: dict[str, Any]
) -> dict[str, Any]:
    subcohort_policy = _object(
        _required(family_policy, "subcohort", "$/policy/family"),
        "$/policy/family/subcohort",
    )
    pooled_policy = _object(
        _required(family_policy, "pooled", "$/policy/family"),
        "$/policy/family/pooled",
    )
    ordered_documents = sorted(
        documents,
        key=lambda item: (
            item["run"]["started_at_epoch_ms"],
            item["run"]["run_id"],
        ),
    )
    subcohorts: list[dict[str, Any]] = []
    offset = 0
    for raw_batch in family_policy["batches"]:
        batch = _object(raw_batch, "$/policy/family/batches")
        run_count = batch["runs"]
        batch_documents = ordered_documents[offset : offset + run_count]
        offset += run_count
        try:
            evaluation = analyze_ttft(
                batch_documents,
                minimum_runs=run_count,
                cv_limit=subcohort_policy["max_inclusive"],
                maximum_span_minutes=subcohort_policy["max_start_span_minutes"],
            )
        except RepeatabilityError as exc:
            raise CohortError(
                f"d110_token_batch_invalid:{batch['batch_id']}:{exc}"
            ) from exc
        subcohorts.append(
            {
                "batch_id": batch["batch_id"],
                "authority": subcohort_policy["authority"],
                "run_count": len(batch_documents),
                "status": evaluation["status"],
                "evaluation": evaluation,
            }
        )
    if offset != len(ordered_documents):
        raise CohortError("d110_token_batch_partition_mismatch")
    try:
        pooled_evaluation = analyze_ttft(
            ordered_documents,
            minimum_runs=pooled_policy["runs"],
            cv_limit=pooled_policy["max_inclusive"],
            maximum_span_minutes=pooled_policy["max_start_span_minutes"],
        )
    except RepeatabilityError as exc:
        raise CohortError(f"d110_token_pooled_invalid:{exc}") from exc
    pooled = {
        "authority": subcohort_policy["authority"],
        "run_count": len(ordered_documents),
        "status": pooled_evaluation["status"],
        "evaluation": pooled_evaluation,
    }
    passed = (
        all(item["status"] == "pass" for item in subcohorts)
        and pooled["status"] == "pass"
    )
    return {
        "mode": "two_subcohorts_and_pooled",
        "status": "pass" if passed else "fail",
        "subcohorts": subcohorts,
        "pooled": pooled,
    }


def _qualification_cross_transport_identity(identity: dict[str, Any]) -> dict[str, Any]:
    return {
        key: identity[key]
        for key in (
            "schema_version",
            "test_type",
            "producer",
            "profile",
            "claim",
            "device",
            "endpoint",
            "algorithm_versions",
        )
    }


def _qualification_prerequisite_gate(
    prerequisite_report: dict[str, Any] | None,
    *,
    stage_id: str,
    identity: dict[str, Any],
    policy: dict[str, Any],
    policy_sha: str,
    qualification_validator: Draft202012Validator,
) -> dict[str, Any]:
    if stage_id == "Q1_WIFI":
        if prerequisite_report is not None:
            raise CohortError("q1_prerequisite_not_allowed")
        return {"status": "not_required", "stage_id": None, "report_sha256": None}
    if prerequisite_report is None:
        raise CohortError("q2_prerequisite_evidence_required")
    if not isinstance(prerequisite_report, dict):
        raise CohortError("q2_prerequisite_report_invalid")
    if not qualification_validator.is_valid(prerequisite_report):
        raise CohortError("q2_prerequisite_report_invalid")
    prerequisite_policy = prerequisite_report.get("policy")
    prerequisite_cohort = prerequisite_report.get("cohort")
    if (
        prerequisite_report.get("schema_version") != QUALIFICATION_SCHEMA_VERSION
        or prerequisite_report.get("contract_version")
        != QUALIFICATION_SCHEMA_VERSION
        or prerequisite_report.get("status") != "repeatability_passed"
        or not isinstance(prerequisite_policy, dict)
        or prerequisite_policy.get("policy_id") != policy["policy_id"]
        or prerequisite_policy.get("version") != policy["version"]
        or prerequisite_policy.get("decision_id") != policy["decision_id"]
        or prerequisite_policy.get("canonical_sha256") != policy_sha
        or prerequisite_policy.get("stage_id") != "Q1_WIFI"
        or not isinstance(prerequisite_cohort, dict)
        or not isinstance(prerequisite_cohort.get("identity"), dict)
    ):
        raise CohortError("q2_prerequisite_report_invalid")
    prerequisite_repeatability = prerequisite_report.get("repeatability_gate")
    prerequisite_radio = prerequisite_report.get("radio_integrity")
    if (
        not isinstance(prerequisite_repeatability, dict)
        or not isinstance(prerequisite_radio, dict)
    ):
        raise CohortError("q2_prerequisite_report_invalid")
    if (
        prerequisite_repeatability.get("status") != "pass"
        or prerequisite_radio.get("status") != "pass"
    ):
        raise CohortError("q2_prerequisite_not_passed")
    prerequisite_identity = prerequisite_cohort["identity"]
    prerequisite_network = prerequisite_identity.get("network")
    if not isinstance(prerequisite_network, dict):
        raise CohortError("q2_prerequisite_report_invalid")
    if prerequisite_network.get("active_transport") != "wifi":
        raise CohortError("q2_prerequisite_transport_invalid")
    try:
        prerequisite_cross_transport_identity = (
            _qualification_cross_transport_identity(prerequisite_identity)
        )
    except KeyError as exc:
        raise CohortError("q2_prerequisite_report_invalid") from exc
    if prerequisite_cross_transport_identity != _qualification_cross_transport_identity(
        identity
    ):
        raise CohortError("q2_prerequisite_identity_mismatch")
    return {
        "status": "pass",
        "stage_id": "Q1_WIFI",
        "report_sha256": _canonical_digest(prerequisite_report),
    }


def analyze_qualification(
    documents: list[dict[str, Any]],
    *,
    root: Path,
    stage_id: str,
    prerequisite_report: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Evaluate a D-110 engineering qualification cohort without promotion."""

    policy, policy_sha, qualification_validator = _load_qualification_policy(root)
    base = _analyze_common(documents, root=root, evaluate_legacy_policy=False)
    stage_definitions = _object(
        policy["stages"]["definitions"], "$/policy/stages/definitions"
    )
    if stage_id not in stage_definitions:
        raise CohortError(f"qualification_stage_invalid:{stage_id}")
    stage = _object(
        stage_definitions[stage_id], f"$/policy/stages/definitions/{stage_id}"
    )
    if len(documents) != stage.get("runs_per_family"):
        raise CohortError("qualification_run_count_mismatch")

    identity = base["cohort"]["identity"]
    if identity["network"]["active_transport"] != stage.get("transport"):
        raise CohortError("qualification_transport_mismatch")
    if identity["claim"].get("scope") != policy.get("claim_scope"):
        raise CohortError("qualification_claim_scope_mismatch")
    family_id = identity["test_type"]
    family_policy = _object(
        _required(
            _object(policy["families"], "$/policy/families"),
            family_id,
            "$/policy/families",
        ),
        f"$/policy/families/{family_id}",
    )
    profile_identity = identity["profile"]
    if (
        profile_identity["profile_id"] != family_policy.get("qualification_profile_id")
        or profile_identity["profile_version"]
        != family_policy.get("qualification_profile_version")
    ):
        raise CohortError("qualification_profile_mismatch")
    prerequisite_gate = _qualification_prerequisite_gate(
        prerequisite_report,
        stage_id=stage_id,
        identity=identity,
        policy=policy,
        policy_sha=policy_sha,
        qualification_validator=qualification_validator,
    )

    if family_id == "token_simulation":
        repeatability_gate = _qualification_token_gate(documents, family_policy)
    else:
        metric_gates = [
            _qualification_metric_gate(
                base["metric_diagnostics"][metric_policy["metric_id"]],
                metric_policy,
            )
            for metric_policy in family_policy["metrics"]
        ]
        metric_status = (
            "pass"
            if all(item["status"] == "pass" for item in metric_gates)
            else "fail"
        )
        repeatability_gate = {
            "status": metric_status,
            "combine_policy": family_policy["combine_policy"],
            "metric_gates": metric_gates,
        }
    radio_gate = _qualification_radio_gate(base["radio_integrity"], policy)
    profile_quality_gate = _qualification_profile_quality_gate(
        documents, base["cohort"]["run_ids"]
    )
    status = (
        "repeatability_passed"
        if repeatability_gate["status"] == "pass" and radio_gate["status"] == "pass"
        else "repeatability_failed"
    )
    report = {
        "schema_version": QUALIFICATION_SCHEMA_VERSION,
        "contract_version": QUALIFICATION_SCHEMA_VERSION,
        "status": status,
        "policy": {
            "policy_id": policy["policy_id"],
            "version": policy["version"],
            "decision_id": policy["decision_id"],
            "canonical_sha256": policy_sha,
            "stage_id": stage_id,
        },
        "cohort": base["cohort"],
        "prerequisite_gate": prerequisite_gate,
        "repeatability_gate": repeatability_gate,
        "radio_integrity": radio_gate,
        "profile_quality_gate": profile_quality_gate,
        "formal_baseline_eligible": False,
        "single_run_confidence_unchanged": True,
    }
    if not qualification_validator.is_valid(report):
        raise CohortError("qualification_report_schema_invalid")
    return report


def _analyze_common(
    documents: list[dict[str, Any]],
    *,
    root: Path,
    evaluate_legacy_policy: bool,
) -> dict[str, Any]:
    """Build common cohort evidence, optionally evaluating the legacy policy."""

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
    if family == "token_simulation" and evaluate_legacy_policy:
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


def analyze(documents: list[dict[str, Any]], *, root: Path) -> dict[str, Any]:
    """Return the legacy fail-closed cohort report for one result family."""

    return _analyze_common(documents, root=root, evaluate_legacy_policy=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Strict-v2 ANEB result JSONL files")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="ANEB repository root")
    parser.add_argument("--output", type=Path, help="Write UTF-8 JSON report to this path")
    parser.add_argument(
        "--qualification-stage",
        choices=("Q1_WIFI", "Q2_CELLULAR"),
        help="Apply the catalog-bound D-110 engineering qualification stage",
    )
    parser.add_argument(
        "--prerequisite-report",
        type=Path,
        help="Strict Q1 qualification JSON required by Q2_CELLULAR",
    )
    args = parser.parse_args(argv)

    try:
        documents = load_jsonl(args.inputs)
        if args.qualification_stage is None:
            if args.prerequisite_report is not None:
                raise CohortError("prerequisite_report_requires_qualification_stage")
            report = analyze(documents, root=args.root)
            exit_code = 1 if report["status"] == "fail" else 0
        else:
            prerequisite_report = (
                load_json_document(
                    args.prerequisite_report,
                    label="prerequisite_report",
                )
                if args.prerequisite_report is not None
                else None
            )
            report = analyze_qualification(
                documents,
                root=args.root,
                stage_id=args.qualification_stage,
                prerequisite_report=prerequisite_report,
            )
            exit_code = 0 if report["status"] == "repeatability_passed" else 1
    except CohortError as exc:
        if args.qualification_stage is not None:
            report = {
                "schema_version": QUALIFICATION_SCHEMA_VERSION,
                "contract_version": QUALIFICATION_SCHEMA_VERSION,
                "status": "invalid",
                "error": str(exc),
            }
        else:
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
