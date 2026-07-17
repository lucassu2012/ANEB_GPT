#!/usr/bin/env python3
"""Fail-closed TTFT repeatability analysis for homogeneous ANEB Token result JSONL."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import sys
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = "aneb-ttft-repeatability-v1"


class RepeatabilityError(ValueError):
    pass


def _object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise RepeatabilityError(f"duplicate_json_key:{key}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise RepeatabilityError(f"non_finite_json_number:{value}")


def load_jsonl(paths: Iterable[Path]) -> list[dict[str, Any]]:
    documents: list[dict[str, Any]] = []
    for path in paths:
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError) as exc:
            raise RepeatabilityError(f"input_read_failed:{path}:{exc}") from exc
        for line_number, line in enumerate(lines, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(
                    line,
                    object_pairs_hook=_object_without_duplicates,
                    parse_constant=_reject_non_finite,
                )
            except (json.JSONDecodeError, RepeatabilityError) as exc:
                raise RepeatabilityError(f"invalid_json:{path}:{line_number}:{exc}") from exc
            if not isinstance(value, dict):
                raise RepeatabilityError(f"result_not_object:{path}:{line_number}")
            documents.append(value)
    return documents


def _required(mapping: dict[str, Any], key: str, path: str) -> Any:
    if key not in mapping:
        raise RepeatabilityError(f"missing_field:{path}/{key}")
    return mapping[key]


def _object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise RepeatabilityError(f"expected_object:{path}")
    return value


def _array(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise RepeatabilityError(f"expected_array:{path}")
    return value


def _finite(value: Any, path: str, *, positive: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RepeatabilityError(f"expected_number:{path}")
    result = float(value)
    if not math.isfinite(result) or (positive and result <= 0.0):
        raise RepeatabilityError(f"invalid_number:{path}")
    return result


def _canonical_digest(document: dict[str, Any]) -> str:
    body = json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(body).hexdigest()


def _percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise RepeatabilityError("percentile_empty")
    if len(ordered) == 1:
        return ordered[0]
    position = min(max(q, 0.0), 1.0) * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def _cohort_identity(document: dict[str, Any]) -> dict[str, Any]:
    profile = _object(_required(document, "profile", "$"), "$/profile")
    producer = _object(_required(document, "producer", "$"), "$/producer")
    context = _object(_required(document, "context", "$"), "$/context")
    device = _object(_required(context, "device", "$/context"), "$/context/device")
    endpoint = _object(_required(context, "endpoint", "$/context"), "$/context/endpoint")
    network = _object(_required(context, "network", "$/context"), "$/context/network")
    claim = _object(_required(document, "claim", "$"), "$/claim")
    profile_hash = _object(_required(profile, "profile_fingerprint", "$/profile"), "$/profile/profile_fingerprint")
    runtime_hash = _object(_required(profile, "runtime_artifact_hash", "$/profile"), "$/profile/runtime_artifact_hash")
    return {
        "schema_version": _required(document, "schema_version", "$"),
        "test_type": _required(document, "test_type", "$"),
        "claim_scope": _required(claim, "scope", "$/claim"),
        "profile_id": _required(profile, "profile_id", "$/profile"),
        "profile_version": _required(profile, "profile_version", "$/profile"),
        "variant": _required(profile, "variant", "$/profile"),
        "profile_fingerprint": _required(profile_hash, "value", "$/profile/profile_fingerprint"),
        "runtime_artifact_hash": _required(runtime_hash, "value", "$/profile/runtime_artifact_hash"),
        "component_version": _required(producer, "component_version", "$/producer"),
        "device": {
            key: _required(device, key, "$/context/device")
            for key in ("manufacturer", "model", "os_release", "api_level", "app_package", "app_version_name", "app_version_code")
        },
        "server_base": _required(endpoint, "server_base", "$/context/endpoint"),
        "requested_transport": _required(network, "requested_transport", "$/context/network"),
        "active_transport": _required(network, "active_transport", "$/context/network"),
        "vpn_active": _required(network, "vpn_active", "$/context/network"),
    }


def analyze(
    documents: list[dict[str, Any]],
    *,
    minimum_runs: int = 5,
    cv_limit: float = 0.10,
    maximum_span_minutes: float = 30.0,
) -> dict[str, Any]:
    if minimum_runs < 3:
        raise RepeatabilityError("minimum_runs_below_3")
    if not 0.0 < cv_limit < 1.0:
        raise RepeatabilityError("cv_limit_out_of_range")
    if len(documents) < minimum_runs:
        raise RepeatabilityError(f"insufficient_runs:{len(documents)}<{minimum_runs}")

    identities = [_cohort_identity(document) for document in documents]
    identity = identities[0]
    if identity["schema_version"] != "aneb-result-v1" or identity["test_type"] != "token_simulation":
        raise RepeatabilityError("unsupported_result_family")
    if identity["active_transport"] not in {"wifi", "cellular"}:
        raise RepeatabilityError("active_transport_not_observed")
    if identity["vpn_active"] is not False:
        raise RepeatabilityError("vpn_active_or_unknown")
    for index, candidate in enumerate(identities[1:], 1):
        if candidate != identity:
            raise RepeatabilityError(f"heterogeneous_cohort:run_index={index}")

    run_ids: list[str] = []
    starts: list[int] = []
    run_digests: list[str] = []
    aligned: dict[str, dict[str, Any]] = {}
    expected_task_identity: dict[str, tuple[Any, ...]] | None = None

    for run_index, document in enumerate(documents):
        run = _object(_required(document, "run", "$"), "$/run")
        run_id = _required(run, "run_id", "$/run")
        if not isinstance(run_id, str) or not run_id:
            raise RepeatabilityError(f"invalid_run_id:run_index={run_index}")
        if run_id in run_ids:
            raise RepeatabilityError(f"duplicate_run_id:{run_id}")
        if _required(run, "status", "$/run") != "completed" or _required(run, "validity", "$/run") != "valid":
            raise RepeatabilityError(f"run_not_completed_valid:{run_id}")
        started = _required(run, "started_at_epoch_ms", "$/run")
        if isinstance(started, bool) or not isinstance(started, int):
            raise RepeatabilityError(f"invalid_started_at:{run_id}")

        evaluation = _object(_required(document, "evaluation", "$"), "$/evaluation")
        score = _object(_required(evaluation, "score", "$/evaluation"), "$/evaluation/score")
        if _required(score, "verdict", "$/evaluation/score") == "invalid":
            raise RepeatabilityError(f"invalid_evaluation:{run_id}")
        metrics = _object(_required(evaluation, "metrics", "$/evaluation"), "$/evaluation/metrics")
        b04 = _object(_required(metrics, "TOK-B04", "$/evaluation/metrics"), "$/evaluation/metrics/TOK-B04")
        if _required(b04, "state", "$/evaluation/metrics/TOK-B04") != "observed":
            raise RepeatabilityError(f"ttft_metric_not_observed:{run_id}")

        payload = _object(_required(document, "category_payload", "$"), "$/category_payload")
        raw = _object(_required(payload, "raw_evidence", "$/category_payload"), "$/category_payload/raw_evidence")
        tasks = _array(_required(raw, "tasks", "$/category_payload/raw_evidence"), "$/category_payload/raw_evidence/tasks")
        if not tasks:
            raise RepeatabilityError(f"no_tasks:{run_id}")

        task_identity: dict[str, tuple[Any, ...]] = {}
        run_ttft: list[float] = []
        for task_index, item in enumerate(tasks):
            task = _object(item, f"$/category_payload/raw_evidence/tasks/{task_index}")
            task_id = _required(task, "task_id", f"$/category_payload/raw_evidence/tasks/{task_index}")
            if not isinstance(task_id, str) or not task_id:
                raise RepeatabilityError(f"missing_task_id:{run_id}:{task_index}")
            if task_id in task_identity:
                raise RepeatabilityError(f"duplicate_task_id:{run_id}:{task_id}")
            identity_tuple = (
                _required(task, "workload_kind", f"task:{task_id}"),
                _required(task, "upload_bytes", f"task:{task_id}"),
                _required(task, "response_artifact_bytes", f"task:{task_id}"),
                _required(task, "expected_tokens", f"task:{task_id}"),
            )
            task_identity[task_id] = identity_tuple
            ttft = _finite(_required(task, "ttft_ms", f"task:{task_id}"), f"task:{task_id}/ttft_ms", positive=True)
            processing = _finite(
                _required(task, "server_processing_ms", f"task:{task_id}"),
                f"task:{task_id}/server_processing_ms",
            )
            run_ttft.append(ttft)
            bucket = aligned.setdefault(
                task_id,
                {
                    "task_id": task_id,
                    "workload_kind": identity_tuple[0],
                    "upload_bytes": identity_tuple[1],
                    "response_artifact_bytes": identity_tuple[2],
                    "expected_tokens": identity_tuple[3],
                    "ttft_ms": [],
                    "server_processing_ms": [],
                },
            )
            bucket["ttft_ms"].append(ttft)
            bucket["server_processing_ms"].append(processing)

        if expected_task_identity is None:
            expected_task_identity = task_identity
        elif task_identity != expected_task_identity:
            raise RepeatabilityError(f"task_plan_mismatch:{run_id}")

        metric_value = _finite(_required(b04, "value", "TOK-B04"), "TOK-B04/value", positive=True)
        metric_count = _required(b04, "sample_count", "TOK-B04")
        if metric_count != len(run_ttft):
            raise RepeatabilityError(f"ttft_sample_count_mismatch:{run_id}")
        if not math.isclose(metric_value, _percentile(run_ttft, 0.95), rel_tol=1e-9, abs_tol=1e-6):
            raise RepeatabilityError(f"ttft_metric_raw_mismatch:{run_id}")

        run_ids.append(run_id)
        starts.append(started)
        run_digests.append(_canonical_digest(document))

    span_ms = max(starts) - min(starts)
    maximum_span_ms = int(maximum_span_minutes * 60_000)
    if span_ms > maximum_span_ms:
        raise RepeatabilityError(f"cohort_time_span_exceeded:{span_ms}>{maximum_span_ms}")

    task_results: list[dict[str, Any]] = []
    cvs: list[float] = []
    for task_id in sorted(aligned):
        bucket = aligned[task_id]
        values = bucket["ttft_ms"]
        if len(values) != len(documents):
            raise RepeatabilityError(f"unaligned_task_samples:{task_id}")
        mean = statistics.fmean(values)
        if mean <= 0.0:
            raise RepeatabilityError(f"non_positive_ttft_mean:{task_id}")
        standard_deviation = statistics.stdev(values)
        cv = standard_deviation / mean
        cvs.append(cv)
        task_results.append(
            {
                **{key: value for key, value in bucket.items() if key not in {"ttft_ms", "server_processing_ms"}},
                "sample_count": len(values),
                "ttft_ms": values,
                "server_processing_ms": bucket["server_processing_ms"],
                "ttft_mean_ms": mean,
                "ttft_median_ms": statistics.median(values),
                "ttft_sample_stddev_ms": standard_deviation,
                "ttft_cv": cv,
            }
        )

    median_cv = statistics.median(cvs)
    status = "pass" if median_cv <= cv_limit else "fail"
    return {
        "schema_version": SCHEMA_VERSION,
        "status": status,
        "criterion": {
            "method": "task_aligned_sample_cv_median-v1",
            "minimum_run_count": minimum_runs,
            "cv_limit": cv_limit,
            "maximum_span_minutes": maximum_span_minutes,
        },
        "cohort": identity,
        "run_count": len(documents),
        "run_ids": run_ids,
        "run_canonical_sha256": run_digests,
        "started_at_span_ms": span_ms,
        "task_count": len(task_results),
        "total_ttft_samples": len(documents) * len(task_results),
        "median_task_ttft_cv": median_cv,
        "maximum_task_ttft_cv": max(cvs),
        "tasks": task_results,
        "conclusion": (
            f"同条件任务对齐 TTFT 变异系数中位数 {median_cv * 100:.2f}%，"
            f"{'达到' if status == 'pass' else '未达到'} ≤{cv_limit * 100:.2f}% 的 M1 重复性门限。"
        ),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="One or more ANEB result JSONL files")
    parser.add_argument("--output", type=Path, help="Write the audit report as UTF-8 JSON")
    parser.add_argument("--min-runs", type=int, default=5)
    parser.add_argument("--cv-limit", type=float, default=0.10)
    parser.add_argument("--max-span-minutes", type=float, default=30.0)
    args = parser.parse_args(argv)
    try:
        report = analyze(
            load_jsonl(args.inputs),
            minimum_runs=args.min_runs,
            cv_limit=args.cv_limit,
            maximum_span_minutes=args.max_span_minutes,
        )
        exit_code = 0 if report["status"] == "pass" else 1
    except RepeatabilityError as exc:
        report = {
            "schema_version": SCHEMA_VERSION,
            "status": "invalid",
            "error": str(exc),
        }
        exit_code = 2
    rendered = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
