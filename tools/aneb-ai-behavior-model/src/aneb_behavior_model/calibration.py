"""Authorized Token observation packaging, holdout validation, and promotion."""

from __future__ import annotations

import hashlib
import json
import math
import re
from copy import deepcopy
from pathlib import Path
from typing import Any

from .fitting import STATES, fit_token_interval_markov, fit_token_model, interval_state
from .model import canonical_json_bytes, model_sha256, validate_model
from .statistics import quantile, relative_error


OBSERVATION_CONTRACT = "aneb-token-observation-v1"
METADATA_CONTRACT = "aneb-calibration-metadata-v1"
DATASET_CONTRACT = "aneb-calibration-dataset-v1"
VALIDATION_CONTRACT = "aneb-model-validation-v1"
VALIDATION_POLICY = "token-holdout-validation-v1"
MIN_TRAINING_PER_WORKLOAD = 20
MIN_HOLDOUT_PER_WORKLOAD = 10
PRIMARY_RELATIVE_ERROR_LIMIT = 0.20
PAUSE_RATIO_ABSOLUTE_ERROR_LIMIT = 0.05
TRANSITION_ROW_TVD_LIMIT = 0.15

_SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
_OPAQUE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_SUBJECT_GROUP = re.compile(r"^hmac-sha256:[0-9a-f]{64}$")
_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
_RFC3339_UTC = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_WORKLOAD_KINDS = {"text", "document", "image", "video"}


class CalibrationError(ValueError):
    """Raised when calibration evidence is incomplete, mixed, or unauthorized."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CalibrationError(f"duplicate_json_key:{key}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise CalibrationError(f"non_finite_json_number:{value}")


def _parse_json(text: str, label: str) -> Any:
    try:
        return json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except (json.JSONDecodeError, CalibrationError) as exc:
        raise CalibrationError(f"invalid_json:{label}:{exc}") from exc


def load_json_object(path: Path) -> dict[str, Any]:
    try:
        value = _parse_json(path.read_text(encoding="utf-8"), str(path))
    except (OSError, UnicodeError) as exc:
        raise CalibrationError(f"input_read_failed:{path}:{exc}") from exc
    if not isinstance(value, dict):
        raise CalibrationError(f"expected_object:{path}")
    return value


def load_token_observations(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise CalibrationError(f"input_read_failed:{path}:{exc}") from exc
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        value = _parse_json(line, f"{path}:{line_number}")
        if not isinstance(value, dict):
            raise CalibrationError(f"expected_object:{path}:{line_number}")
        validate_token_observation(value, f"{path}:{line_number}")
        rows.append(value)
    if not rows:
        raise CalibrationError(f"empty_observation_file:{path}")
    return rows


def _strict_keys(value: dict[str, Any], required: set[str], optional: set[str], label: str) -> None:
    missing = required - value.keys()
    unknown = value.keys() - required - optional
    if missing:
        raise CalibrationError(f"missing_fields:{label}:{sorted(missing)}")
    if unknown:
        raise CalibrationError(f"unverified_fields:{label}:{sorted(unknown)}")


def _finite_number(value: Any, label: str, *, minimum: float = 0.0) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CalibrationError(f"expected_number:{label}")
    result = float(value)
    if not math.isfinite(result) or result < minimum:
        raise CalibrationError(f"number_out_of_range:{label}")
    return result


def validate_token_observation(value: dict[str, Any], label: str = "observation") -> None:
    required = {
        "observation_contract_version",
        "observation_id",
        "subject_group_id",
        "workload_kind",
        "payload_bytes",
        "processing_delay_ms",
        "output_token_count",
        "token_intervals_ms",
    }
    _strict_keys(value, required, {"response_artifact_bytes"}, label)
    if value["observation_contract_version"] != OBSERVATION_CONTRACT:
        raise CalibrationError(f"unsupported_observation_contract:{label}")
    if not isinstance(value["observation_id"], str) or not _OPAQUE_ID.fullmatch(value["observation_id"]):
        raise CalibrationError(f"invalid_observation_id:{label}")
    if not isinstance(value["subject_group_id"], str) or not _SUBJECT_GROUP.fullmatch(value["subject_group_id"]):
        raise CalibrationError(f"subject_group_must_be_hmac_sha256:{label}")
    if value["workload_kind"] not in _WORKLOAD_KINDS:
        raise CalibrationError(f"unsupported_workload_kind:{label}")
    if isinstance(value["payload_bytes"], bool) or not isinstance(value["payload_bytes"], int) or value["payload_bytes"] <= 0:
        raise CalibrationError(f"invalid_payload_bytes:{label}")
    _finite_number(value["processing_delay_ms"], f"{label}/processing_delay_ms")
    if isinstance(value["output_token_count"], bool) or not isinstance(value["output_token_count"], int) or value["output_token_count"] <= 0:
        raise CalibrationError(f"invalid_output_token_count:{label}")
    intervals = value["token_intervals_ms"]
    if not isinstance(intervals, list) or not intervals:
        raise CalibrationError(f"empty_token_intervals:{label}")
    for index, interval in enumerate(intervals):
        _finite_number(interval, f"{label}/token_intervals_ms/{index}", minimum=1e-12)
    if "response_artifact_bytes" in value:
        artifact = value["response_artifact_bytes"]
        if isinstance(artifact, bool) or not isinstance(artifact, int) or artifact < 0:
            raise CalibrationError(f"invalid_response_artifact_bytes:{label}")


def _validate_string_list(value: Any, label: str, *, nonempty: bool = True) -> list[str]:
    if not isinstance(value, list) or (nonempty and not value):
        raise CalibrationError(f"expected_string_array:{label}")
    if any(not isinstance(item, str) or not item for item in value):
        raise CalibrationError(f"invalid_string_array:{label}")
    if len(value) != len(set(value)):
        raise CalibrationError(f"duplicate_string_array_item:{label}")
    return value


def validate_calibration_metadata(metadata: dict[str, Any]) -> None:
    _strict_keys(metadata, {"metadata_contract_version", "prepared_at", "authorization", "scope"}, set(), "metadata")
    if metadata["metadata_contract_version"] != METADATA_CONTRACT:
        raise CalibrationError("unsupported_metadata_contract")
    if not isinstance(metadata["prepared_at"], str) or not _RFC3339_UTC.fullmatch(metadata["prepared_at"]):
        raise CalibrationError("invalid_prepared_at")

    authorization = metadata["authorization"]
    if not isinstance(authorization, dict):
        raise CalibrationError("authorization_not_object")
    _strict_keys(
        authorization,
        {"status", "basis", "approved_by", "approved_at", "allowed_purposes", "content_policy", "content_retained"},
        {"expires_at", "reference_id"},
        "authorization",
    )
    if authorization["status"] != "authorized":
        raise CalibrationError("dataset_not_authorized")
    if authorization["basis"] not in {
        "first_party_measurement",
        "documented_consent",
        "licensed_dataset",
        "public_dataset_permitted_use",
    }:
        raise CalibrationError("unsupported_authorization_basis")
    if not isinstance(authorization["approved_by"], str) or not _OPAQUE_ID.fullmatch(authorization["approved_by"]):
        raise CalibrationError("invalid_authorization_approver")
    if not isinstance(authorization["approved_at"], str) or not _RFC3339_UTC.fullmatch(authorization["approved_at"]):
        raise CalibrationError("invalid_authorization_time")
    purposes = _validate_string_list(authorization["allowed_purposes"], "authorization/allowed_purposes")
    if "behavior_model_calibration" not in purposes:
        raise CalibrationError("calibration_purpose_not_authorized")
    if authorization["content_policy"] != "derived_statistics_only" or authorization["content_retained"] is not False:
        raise CalibrationError("raw_content_not_permitted")
    expires_at = authorization.get("expires_at")
    if expires_at is not None and (not isinstance(expires_at, str) or not _RFC3339_UTC.fullmatch(expires_at)):
        raise CalibrationError("invalid_authorization_expiry")
    if authorization["approved_at"] > metadata["prepared_at"]:
        raise CalibrationError("authorization_approved_after_dataset_preparation")
    if expires_at is not None and expires_at < metadata["prepared_at"]:
        raise CalibrationError("authorization_expired_before_dataset_preparation")

    scope = metadata["scope"]
    if not isinstance(scope, dict):
        raise CalibrationError("scope_not_object")
    _strict_keys(
        scope,
        {
            "source_kind",
            "provider_labels",
            "geography_labels",
            "device_classes",
            "observation_window_start",
            "observation_window_end",
            "collection_method",
        },
        set(),
        "scope",
    )
    if scope["source_kind"] not in {
        "real_application_observation",
        "controlled_api_observation",
        "licensed_dataset",
    }:
        raise CalibrationError("unsupported_source_kind")
    for key in ("provider_labels", "geography_labels", "device_classes"):
        _validate_string_list(scope[key], f"scope/{key}")
    for key in ("observation_window_start", "observation_window_end"):
        if not isinstance(scope[key], str) or not _RFC3339_UTC.fullmatch(scope[key]):
            raise CalibrationError(f"invalid_scope_time:{key}")
    if scope["observation_window_start"] > scope["observation_window_end"]:
        raise CalibrationError("observation_window_reversed")
    if scope["observation_window_end"] > metadata["prepared_at"]:
        raise CalibrationError("observation_window_ends_after_dataset_preparation")
    if not isinstance(scope["collection_method"], str) or not _OPAQUE_ID.fullmatch(scope["collection_method"]):
        raise CalibrationError("invalid_collection_method")


def canonical_jsonl_sha256(rows: list[dict[str, Any]]) -> str:
    payload = b"".join(canonical_json_bytes(row) + b"\n" for row in rows)
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def _partition_summary(path: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    counts: dict[str, int] = {}
    for row in rows:
        kind = row["workload_kind"]
        counts[kind] = counts.get(kind, 0) + 1
    return {
        "path": path,
        "canonical_sha256": canonical_jsonl_sha256(rows),
        "observation_count": len(rows),
        "workload_counts": dict(sorted(counts.items())),
    }


def prepare_token_dataset(
    training_path: Path,
    holdout_path: Path,
    metadata_path: Path,
    *,
    dataset_id: str,
    dataset_version: str,
    output_dir: Path,
) -> dict[str, Any]:
    if not _OPAQUE_ID.fullmatch(dataset_id):
        raise CalibrationError("invalid_dataset_id")
    if not _SEMVER.fullmatch(dataset_version):
        raise CalibrationError("invalid_dataset_version")
    metadata = load_json_object(metadata_path)
    validate_calibration_metadata(metadata)
    training = load_token_observations(training_path)
    holdout = load_token_observations(holdout_path)
    _validate_partition_disjointness(training, holdout)

    output_dir.mkdir(parents=True, exist_ok=True)
    _write_canonical_jsonl(output_dir / "training.jsonl", training)
    _write_canonical_jsonl(output_dir / "holdout.jsonl", holdout)
    manifest = {
        "dataset_contract_version": DATASET_CONTRACT,
        "dataset_id": dataset_id,
        "dataset_version": dataset_version,
        "business_type": "token_multimodal",
        "prepared_at": metadata["prepared_at"],
        "authorization": metadata["authorization"],
        "scope": metadata["scope"],
        "split": {
            "strategy": "preassigned_subject_disjoint-v1",
            "observation_disjoint": True,
            "subject_group_disjoint": True,
        },
        "partitions": {
            "training": _partition_summary("training.jsonl", training),
            "holdout": _partition_summary("holdout.jsonl", holdout),
        },
    }
    _write_json(output_dir / "dataset_manifest.json", manifest)
    return manifest


def _write_canonical_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_bytes(b"".join(canonical_json_bytes(row) + b"\n" for row in rows))


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def _validate_partition_disjointness(training: list[dict[str, Any]], holdout: list[dict[str, Any]]) -> None:
    training_ids = [row["observation_id"] for row in training]
    holdout_ids = [row["observation_id"] for row in holdout]
    if len(training_ids) != len(set(training_ids)) or len(holdout_ids) != len(set(holdout_ids)):
        raise CalibrationError("duplicate_observation_id_within_partition")
    if set(training_ids) & set(holdout_ids):
        raise CalibrationError("training_holdout_observation_overlap")
    training_subjects = {row["subject_group_id"] for row in training}
    holdout_subjects = {row["subject_group_id"] for row in holdout}
    if training_subjects & holdout_subjects:
        raise CalibrationError("training_holdout_subject_overlap")


def load_token_dataset(manifest_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    manifest = load_json_object(manifest_path)
    _validate_dataset_manifest(manifest)
    base = manifest_path.resolve().parent
    loaded: dict[str, list[dict[str, Any]]] = {}
    for name in ("training", "holdout"):
        partition = manifest["partitions"][name]
        relative = Path(partition["path"])
        if relative.is_absolute() or ".." in relative.parts or len(relative.parts) != 1:
            raise CalibrationError(f"unsafe_partition_path:{name}")
        rows = load_token_observations(base / relative)
        if canonical_jsonl_sha256(rows) != partition["canonical_sha256"]:
            raise CalibrationError(f"partition_digest_mismatch:{name}")
        if len(rows) != partition["observation_count"]:
            raise CalibrationError(f"partition_count_mismatch:{name}")
        if _partition_summary(partition["path"], rows)["workload_counts"] != partition["workload_counts"]:
            raise CalibrationError(f"partition_workload_counts_mismatch:{name}")
        loaded[name] = rows
    _validate_partition_disjointness(loaded["training"], loaded["holdout"])
    return manifest, loaded["training"], loaded["holdout"]


def _validate_dataset_manifest(manifest: dict[str, Any]) -> None:
    _strict_keys(
        manifest,
        {
            "dataset_contract_version",
            "dataset_id",
            "dataset_version",
            "business_type",
            "prepared_at",
            "authorization",
            "scope",
            "split",
            "partitions",
        },
        set(),
        "dataset_manifest",
    )
    if manifest["dataset_contract_version"] != DATASET_CONTRACT or manifest["business_type"] != "token_multimodal":
        raise CalibrationError("unsupported_dataset_contract")
    if not isinstance(manifest["dataset_id"], str) or not _OPAQUE_ID.fullmatch(manifest["dataset_id"]):
        raise CalibrationError("invalid_dataset_id")
    if not isinstance(manifest["dataset_version"], str) or not _SEMVER.fullmatch(manifest["dataset_version"]):
        raise CalibrationError("invalid_dataset_version")
    validate_calibration_metadata(
        {
            "metadata_contract_version": METADATA_CONTRACT,
            "prepared_at": manifest["prepared_at"],
            "authorization": manifest["authorization"],
            "scope": manifest["scope"],
        }
    )
    split = manifest["split"]
    if split != {
        "strategy": "preassigned_subject_disjoint-v1",
        "observation_disjoint": True,
        "subject_group_disjoint": True,
    }:
        raise CalibrationError("unsupported_or_unsafe_split")
    partitions = manifest["partitions"]
    if not isinstance(partitions, dict) or set(partitions) != {"training", "holdout"}:
        raise CalibrationError("invalid_partitions")
    for name, partition in partitions.items():
        if not isinstance(partition, dict):
            raise CalibrationError(f"partition_not_object:{name}")
        _strict_keys(partition, {"path", "canonical_sha256", "observation_count", "workload_counts"}, set(), name)
        if not isinstance(partition["path"], str) or not partition["path"]:
            raise CalibrationError(f"invalid_partition_path:{name}")
        expected_path = "training.jsonl" if name == "training" else "holdout.jsonl"
        if partition["path"] != expected_path:
            raise CalibrationError(f"unexpected_partition_path:{name}")
        if not isinstance(partition["canonical_sha256"], str) or not _DIGEST.fullmatch(partition["canonical_sha256"]):
            raise CalibrationError(f"invalid_partition_digest:{name}")
        if isinstance(partition["observation_count"], bool) or not isinstance(partition["observation_count"], int) or partition["observation_count"] <= 0:
            raise CalibrationError(f"invalid_partition_count:{name}")
        counts = partition["workload_counts"]
        if not isinstance(counts, dict) or not counts or any(kind not in _WORKLOAD_KINDS or isinstance(count, bool) or not isinstance(count, int) or count <= 0 for kind, count in counts.items()):
            raise CalibrationError(f"invalid_workload_counts:{name}")


def calibrate_and_validate_token(
    template: dict[str, Any],
    manifest_path: Path,
    *,
    candidate_version: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if template.get("business_type") != "token_multimodal":
        raise CalibrationError("token_calibration_requires_token_template")
    if not _SEMVER.fullmatch(candidate_version):
        raise CalibrationError("invalid_candidate_version")
    if tuple(map(int, candidate_version.split("."))) <= tuple(map(int, str(template["model_version"]).split("."))):
        raise CalibrationError("candidate_version_must_increase")
    manifest, training, holdout = load_token_dataset(manifest_path)
    template_workloads = {workload["kind"] for workload in template["generation"]["workloads"]}
    _require_workload_coverage(training, template_workloads, MIN_TRAINING_PER_WORKLOAD, "training")
    _require_workload_coverage(holdout, template_workloads, MIN_HOLDOUT_PER_WORKLOAD, "holdout")
    manifest_digest = "sha256:" + hashlib.sha256(canonical_json_bytes(manifest)).hexdigest()
    source = {
        "kind": "authorized_observation_dataset",
        "dataset_id": manifest["dataset_id"],
        "dataset_version": manifest["dataset_version"],
        "dataset_manifest_sha256": manifest_digest,
        "authorization_basis": manifest["authorization"]["basis"],
        "training_partition": {
            "canonical_sha256": manifest["partitions"]["training"]["canonical_sha256"],
            "observation_count": len(training),
        },
        "content_retained": False,
    }
    fitted = fit_token_model(
        training,
        template,
        calibration_source=source,
        candidate_version=candidate_version,
    )
    validate_model(fitted)
    report = validate_token_holdout(fitted, manifest, manifest_digest, holdout)
    return fitted, report


def _require_workload_coverage(rows: list[dict[str, Any]], expected: set[str], minimum: int, partition: str) -> None:
    observed = {row["workload_kind"] for row in rows}
    if observed - expected:
        raise CalibrationError(f"unexpected_{partition}_workloads:{sorted(observed - expected)}")
    counts = {kind: 0 for kind in expected}
    for row in rows:
        if row["workload_kind"] in counts:
            counts[row["workload_kind"]] += 1
    failures = {kind: count for kind, count in counts.items() if count < minimum}
    if failures:
        raise CalibrationError(f"insufficient_{partition}_coverage:{failures}:minimum={minimum}")


def _model_values(workload: dict[str, Any], field: str) -> list[float]:
    return [float(value) for value in workload[field]["values"]]


def _relative_checks(candidate: list[float], holdout: list[float], prefix: str) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    for name, probability in (("p50", 0.50), ("p95", 0.95)):
        candidate_value = quantile(candidate, probability)
        holdout_value = quantile(holdout, probability)
        error = relative_error(candidate_value, holdout_value)
        checks.append(
            {
                "check_id": f"{prefix}_{name}_relative_error",
                "candidate_value": candidate_value,
                "holdout_value": holdout_value,
                "error": error,
                "limit": PRIMARY_RELATIVE_ERROR_LIMIT,
                "pass": error is not None and error <= PRIMARY_RELATIVE_ERROR_LIMIT,
            }
        )
    return checks


def validate_token_holdout(
    model: dict[str, Any],
    manifest: dict[str, Any],
    manifest_digest: str,
    holdout: list[dict[str, Any]],
) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in holdout:
        grouped.setdefault(row["workload_kind"], []).append(row)
    checks: list[dict[str, Any]] = []
    workloads: dict[str, Any] = {}
    for workload in model["generation"]["workloads"]:
        kind = workload["kind"]
        rows = grouped[kind]
        kind_checks: list[dict[str, Any]] = []
        for field in ("payload_bytes", "processing_delay_ms", "output_token_count"):
            kind_checks += _relative_checks(
                _model_values(workload, field),
                [float(row[field]) for row in rows],
                f"{kind}_{field}",
            )
        model_intervals = [
            float(value)
            for state in STATES
            for value in workload["token_interval_model"]["states"][state]["values"]
        ]
        holdout_intervals = [float(value) for row in rows for value in row["token_intervals_ms"]]
        kind_checks += _relative_checks(model_intervals, holdout_intervals, f"{kind}_token_interval_ms")

        model_pause = sum(1 for value in model_intervals if interval_state(value) == "PAUSE") / len(model_intervals)
        holdout_pause = sum(1 for value in holdout_intervals if interval_state(value) == "PAUSE") / len(holdout_intervals)
        pause_error = abs(model_pause - holdout_pause)
        kind_checks.append(
            {
                "check_id": f"{kind}_pause_ratio_absolute_error",
                "candidate_value": model_pause,
                "holdout_value": holdout_pause,
                "error": pause_error,
                "limit": PAUSE_RATIO_ABSOLUTE_ERROR_LIMIT,
                "pass": pause_error <= PAUSE_RATIO_ABSOLUTE_ERROR_LIMIT,
            }
        )
        holdout_markov = fit_token_interval_markov(
            [[float(value) for value in row["token_intervals_ms"]] for row in rows]
        )
        transition_tvds: dict[str, float] = {}
        for state in STATES:
            candidate_row = workload["token_interval_model"]["transition_probabilities"][state]
            holdout_row = holdout_markov["transition_probabilities"][state]
            tvd = 0.5 * sum(abs(float(candidate_row[target]) - float(holdout_row[target])) for target in STATES)
            transition_tvds[state] = tvd
            kind_checks.append(
                {
                    "check_id": f"{kind}_transition_{state.lower()}_tvd",
                    "candidate_value": candidate_row,
                    "holdout_value": holdout_row,
                    "error": tvd,
                    "limit": TRANSITION_ROW_TVD_LIMIT,
                    "pass": tvd <= TRANSITION_ROW_TVD_LIMIT,
                }
            )
        checks += kind_checks
        workloads[kind] = {
            "holdout_observation_count": len(rows),
            "holdout_interval_count": len(holdout_intervals),
            "maximum_transition_row_tvd": max(transition_tvds.values()),
            "checks": kind_checks,
        }

    failed = [check["check_id"] for check in checks if not check["pass"]]
    numeric_primary_errors = [
        float(check["error"])
        for check in checks
        if check["check_id"].endswith("relative_error") and check["error"] is not None
    ]
    return {
        "validation_contract_version": VALIDATION_CONTRACT,
        "policy_id": VALIDATION_POLICY,
        "status": "pass" if not failed else "fail",
        "candidate": {
            "model_id": model["model_id"],
            "model_version": model["model_version"],
            "status": model["status"],
            "canonical_sha256": model_sha256(model),
        },
        "dataset": {
            "dataset_id": manifest["dataset_id"],
            "dataset_version": manifest["dataset_version"],
            "manifest_canonical_sha256": manifest_digest,
            "training_partition": manifest["partitions"]["training"],
            "holdout_partition": manifest["partitions"]["holdout"],
        },
        "criteria": {
            "minimum_training_observations_per_workload": MIN_TRAINING_PER_WORKLOAD,
            "minimum_holdout_observations_per_workload": MIN_HOLDOUT_PER_WORKLOAD,
            "primary_p50_p95_relative_error_limit": PRIMARY_RELATIVE_ERROR_LIMIT,
            "pause_ratio_absolute_error_limit": PAUSE_RATIO_ABSOLUTE_ERROR_LIMIT,
            "transition_row_tvd_limit": TRANSITION_ROW_TVD_LIMIT,
        },
        "summary": {
            "check_count": len(checks),
            "failed_check_count": len(failed),
            "failed_check_ids": failed,
            "maximum_primary_relative_error": max(numeric_primary_errors),
        },
        "workloads": workloads,
        "claim": "Holdout validation of authorized derived statistics; not a provider-official benchmark.",
    }


def promote_validated_model(
    model: dict[str, Any],
    validation: dict[str, Any],
    manifest_path: Path,
) -> dict[str, Any]:
    if model.get("status") != "calibrated":
        raise CalibrationError("promotion_requires_calibrated_model")
    _validate_validation_report_shape(validation)
    if validation["status"] != "pass":
        raise CalibrationError("holdout_validation_not_passed")
    if validation["candidate"]["canonical_sha256"] != model_sha256(model):
        raise CalibrationError("validation_candidate_digest_mismatch")
    if validation["candidate"]["model_id"] != model["model_id"] or validation["candidate"]["model_version"] != model["model_version"]:
        raise CalibrationError("validation_candidate_identity_mismatch")
    source = model.get("source", {})
    if validation["dataset"]["manifest_canonical_sha256"] != source.get("dataset_manifest_sha256"):
        raise CalibrationError("validation_dataset_mismatch")
    manifest, _, holdout = load_token_dataset(manifest_path)
    manifest_digest = "sha256:" + hashlib.sha256(canonical_json_bytes(manifest)).hexdigest()
    expected_report = validate_token_holdout(model, manifest, manifest_digest, holdout)
    if canonical_json_bytes(validation) != canonical_json_bytes(expected_report):
        raise CalibrationError("validation_report_not_reproducible")
    report_digest = "sha256:" + hashlib.sha256(canonical_json_bytes(validation)).hexdigest()
    promoted = deepcopy(model)
    promoted["status"] = "validated"
    promoted["source"]["validation"] = {
        "validation_contract_version": VALIDATION_CONTRACT,
        "policy_id": VALIDATION_POLICY,
        "report_canonical_sha256": report_digest,
        "calibrated_model_sha256": validation["candidate"]["canonical_sha256"],
        "holdout_partition_sha256": validation["dataset"]["holdout_partition"]["canonical_sha256"],
        "holdout_observation_count": validation["dataset"]["holdout_partition"]["observation_count"],
    }
    validate_model(promoted)
    return promoted


def verify_validated_model(
    model: dict[str, Any],
    validation: dict[str, Any],
    manifest_path: Path,
) -> None:
    if model.get("status") != "validated":
        raise CalibrationError("validated_release_requires_validated_model")
    calibrated = deepcopy(model)
    calibrated["status"] = "calibrated"
    evidence = calibrated.get("source", {}).pop("validation", None)
    if not isinstance(evidence, dict):
        raise CalibrationError("validated_model_missing_evidence")
    validate_model(calibrated)
    if evidence.get("calibrated_model_sha256") != model_sha256(calibrated):
        raise CalibrationError("validated_model_calibrated_digest_mismatch")
    expected = promote_validated_model(calibrated, validation, manifest_path)
    if canonical_json_bytes(expected) != canonical_json_bytes(model):
        raise CalibrationError("validated_model_not_reproducible")


def _validate_validation_report_shape(validation: dict[str, Any]) -> None:
    required = {
        "validation_contract_version",
        "policy_id",
        "status",
        "candidate",
        "dataset",
        "criteria",
        "summary",
        "workloads",
        "claim",
    }
    _strict_keys(validation, required, set(), "validation_report")
    if validation["validation_contract_version"] != VALIDATION_CONTRACT or validation["policy_id"] != VALIDATION_POLICY:
        raise CalibrationError("unsupported_validation_contract")
    if validation["status"] not in {"pass", "fail"}:
        raise CalibrationError("invalid_validation_status")
