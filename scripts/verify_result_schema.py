#!/usr/bin/env python3
"""Validate aneb-result-v1, positive fixtures, and fail-closed invariants."""

from __future__ import annotations

import argparse
import copy
import json
import math
import sys
from pathlib import Path
from typing import Any

try:
    import jsonschema
except ImportError as exc:  # pragma: no cover - exercised by setup failures
    raise SystemExit(
        "jsonschema is required; install the behavior-model test extra: "
        "python -m pip install -e 'tools/aneb-ai-behavior-model[test]'"
    ) from exc


class DuplicateJsonKey(ValueError):
    pass


def _object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def _load_json(path: Path) -> Any:
    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=_object_without_duplicates,
        parse_constant=_reject_non_finite,
    )


def _schema_errors(validator: jsonschema.Draft202012Validator, value: Any) -> list[str]:
    result: list[str] = []
    for error in sorted(validator.iter_errors(value), key=lambda item: list(item.absolute_path)):
        pointer = "/" + "/".join(str(part) for part in error.absolute_path)
        result.append(f"{pointer}: {error.message}")
    return result


def _check_required_properties(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        required = value.get("required")
        properties = value.get("properties")
        if isinstance(required, list) and isinstance(properties, dict):
            unknown = sorted(set(required) - set(properties))
            if unknown:
                errors.append(f"{path}: required keys have no property definition: {unknown}")
        for key, child in value.items():
            _check_required_properties(child, f"{path}/{key}", errors)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _check_required_properties(child, f"{path}/{index}", errors)


def _check_finite(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        errors.append(f"{path}: non-finite number")
    elif isinstance(value, dict):
        for key, child in value.items():
            _check_finite(child, f"{path}/{key}", errors)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _check_finite(child, f"{path}/{index}", errors)


def validate_repository(root: Path) -> list[str]:
    root = root.resolve()
    schema_path = root / "spec/schemas/aneb-result-v1.schema.json"
    valid_dir = root / "spec/examples/aneb-result-v1"
    invalid_dir = valid_dir / "invalid"
    errors: list[str] = []
    try:
        schema = _load_json(schema_path)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        return [f"{schema_path}: invalid UTF-8 JSON: {exc}"]

    try:
        jsonschema.Draft202012Validator.check_schema(schema)
    except jsonschema.SchemaError as exc:
        return [f"{schema_path}: invalid Draft 2020-12 schema: {exc.message}"]
    validator = jsonschema.Draft202012Validator(schema)
    _check_required_properties(schema, "$", errors)

    radio = schema.get("$defs", {}).get("radio_context", {})
    if "collection_status" not in radio.get("required", []):
        errors.append("radio_context: collection_status must be required")
    forbidden_location_keys = {"lat", "lon", "latitude", "longitude", "location"}
    radio_property_keys = set(radio.get("properties", {}))
    sample_property_keys = set(schema.get("$defs", {}).get("radio_sample", {}).get("properties", {}))
    leaked = sorted((radio_property_keys | sample_property_keys) & forbidden_location_keys)
    if leaked:
        errors.append(f"shareable radio contract contains location keys: {leaked}")

    valid_paths = sorted(valid_dir.glob("*.valid-schema.json"))
    if len(valid_paths) != 3:
        errors.append(f"valid fixtures: expected exactly 3, found {len(valid_paths)}")
    fixtures: dict[str, Any] = {}
    for path in valid_paths:
        try:
            value = _load_json(path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            errors.append(f"{path}: invalid UTF-8 JSON: {exc}")
            continue
        _check_finite(value, path.name, errors)
        fixture_errors = _schema_errors(validator, value)
        if fixture_errors:
            errors.extend(f"{path.name}{error}" for error in fixture_errors)
        test_type = value.get("test_type")
        if isinstance(test_type, str):
            fixtures[test_type] = value
    expected_types = {"token_simulation", "ai_realtime_simulation", "network_comprehensive"}
    if set(fixtures) != expected_types:
        errors.append(f"valid fixtures: expected test types {sorted(expected_types)}, found {sorted(fixtures)}")

    invalid_paths = sorted(invalid_dir.glob("*.invalid.json"))
    if not invalid_paths:
        errors.append("invalid fixtures: at least one negative fixture is required")
    for path in invalid_paths:
        try:
            value = _load_json(path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            errors.append(f"{path}: invalid UTF-8 JSON: {exc}")
            continue
        fixture_errors = list(validator.iter_errors(value))
        if not fixture_errors:
            errors.append(f"{path.name}: negative fixture unexpectedly validates")
        elif path.name.startswith("invalid-run-with-computed-score"):
            paths = {tuple(error.absolute_path) for error in fixture_errors}
            if ("evaluation", "score", "state") not in paths:
                errors.append(f"{path.name}: rejection did not exercise invalid-score suppression")

    token = fixtures.get("token_simulation")
    if token is not None:
        task_evidence = copy.deepcopy(token)
        task_evidence["category_payload"]["raw_evidence"]["tasks"] = [
            {
                "workload_kind": "text",
                "upload_bytes": 8192,
                "response_artifact_bytes": 0,
                "success": True,
                "network_failure": False,
                "error": None,
                "click_to_node_receive_ms": 12.0,
                "task_id": "quick-text-0",
                "server_processing_ms": 300.0,
                "ttft_ms": 340.0,
                "ttft_excess_ms": 40.0,
                "upload_goodput_mbps": 5.4,
                "download_goodput_mbps": None,
                "artifact_download_duration_ms": None,
                "expected_tokens": 10,
                "unique_tokens": 10,
                "duplicate_tokens": 0,
                "token_lateness_ms": [40.0],
                "itl_residual_ms": [2.0],
                "request_count": 1,
                "failed_request_count": 0,
            }
        ]
        if _schema_errors(validator, task_evidence):
            errors.append("token task invariant: aligned TTFT evidence unexpectedly rejected")
        missing_ttft = copy.deepcopy(task_evidence)
        del missing_ttft["category_payload"]["raw_evidence"]["tasks"][0]["ttft_ms"]
        if not _schema_errors(validator, missing_ttft):
            errors.append("token task invariant: task without ttft_ms unexpectedly validates")

        collected_empty = copy.deepcopy(token)
        collected_empty["context"]["radio"].update(
            collection_status="collected",
            unavailable_reason=None,
            sample_count=0,
            samples=[],
            evidence_ref_ids=[],
        )
        if not _schema_errors(validator, collected_empty):
            errors.append("radio invariant: collected with zero evidence unexpectedly validates")

        fabricated_radio = copy.deepcopy(token)
        fabricated_radio["context"]["radio"]["rsrp_dbm"] = -80.0
        if not _schema_errors(validator, fabricated_radio):
            errors.append("radio invariant: not_collected with RSRP unexpectedly validates")

        fabricated_metric = copy.deepcopy(token)
        fabricated_metric["evaluation"]["metrics"]["TOK-N03"]["value"] = 0.0
        if not _schema_errors(validator, fabricated_metric):
            errors.append("null invariant: missing metric encoded as zero unexpectedly validates")

        scalar_with_null = copy.deepcopy(token)
        scalar_metric = scalar_with_null["evaluation"]["metrics"]["TOK-N03"]
        scalar_metric.update(
            state="observed",
            value=None,
            sample_count=1,
            source_evidence_ref_ids=["token-raw"],
            invalid_reason=None,
        )
        if not _schema_errors(validator, scalar_with_null):
            errors.append("metric invariant: observed scalar with null value unexpectedly validates")

        referenced_series = copy.deepcopy(token)
        series_metric = referenced_series["evaluation"]["metrics"]["TOK-N03"]
        series_metric.update(
            unit="mixed",
            aggregation="time_series",
            state="observed",
            value=None,
            sample_count=1,
            source_evidence_ref_ids=["radio-context"],
            invalid_reason=None,
        )
        if _schema_errors(validator, referenced_series):
            errors.append("metric invariant: referenced mixed time series unexpectedly rejected")

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate the ANEB unified result schema")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: inferred from this script)",
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)
    errors = validate_repository(args.root)
    if errors:
        print(f"ANEB result schema FAILED ({len(errors)} error(s))", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    if not args.quiet:
        print("ANEB result schema OK: Draft 2020-12, 3 valid fixtures, negative and null/radio invariants")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
