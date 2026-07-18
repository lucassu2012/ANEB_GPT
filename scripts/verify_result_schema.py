#!/usr/bin/env python3
"""Validate compatible result v1, strict v2, shared core, and cross-version invariants."""

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
    from referencing import Registry, Resource
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
    schema_paths = {
        "core": root / "spec/schemas/aneb-result-core-v1.schema.json",
        "v1": root / "spec/schemas/aneb-result-v1.schema.json",
        "v2": root / "spec/schemas/aneb-result-v2.schema.json",
    }
    valid_dir = root / "spec/examples/aneb-result-v1"
    invalid_dir = valid_dir / "invalid"
    cross_version_dir = root / "spec/examples/aneb-result-cross-version"
    errors: list[str] = []
    schemas: dict[str, Any] = {}
    for name, schema_path in schema_paths.items():
        try:
            schema = _load_json(schema_path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            return [f"{schema_path}: invalid UTF-8 JSON: {exc}"]
        try:
            jsonschema.Draft202012Validator.check_schema(schema)
        except jsonschema.SchemaError as exc:
            return [f"{schema_path}: invalid Draft 2020-12 schema: {exc.message}"]
        schemas[name] = schema
        _check_required_properties(schema, f"$[{name}]", errors)
    registry = Registry().with_resources(
        (schema["$id"], Resource.from_contents(schema)) for schema in schemas.values()
    )
    v1_validator = jsonschema.Draft202012Validator(schemas["v1"], registry=registry)
    v2_validator = jsonschema.Draft202012Validator(schemas["v2"], registry=registry)
    core_schema = schemas["core"]

    radio = core_schema.get("$defs", {}).get("radio_context", {})
    if "collection_status" not in radio.get("required", []):
        errors.append("radio_context: collection_status must be required")
    forbidden_location_keys = {"lat", "lon", "latitude", "longitude", "location"}
    radio_property_keys = set(radio.get("properties", {}))
    sample_property_keys = set(core_schema.get("$defs", {}).get("radio_sample", {}).get("properties", {}))
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
        fixture_errors = _schema_errors(v1_validator, value)
        if fixture_errors:
            errors.extend(f"{path.name}{error}" for error in fixture_errors)
        test_type = value.get("test_type")
        if isinstance(test_type, str):
            fixtures[test_type] = value
        v2_value = copy.deepcopy(value)
        v2_value["schema_version"] = "aneb-result-v2"
        v2_errors = _schema_errors(v2_validator, v2_value)
        if v2_errors:
            errors.extend(f"{path.name}[v2]{error}" for error in v2_errors)
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
        fixture_errors = list(v1_validator.iter_errors(value))
        if not fixture_errors:
            errors.append(f"{path.name}: negative fixture unexpectedly validates")
        elif path.name.startswith("invalid-run-with-computed-score"):
            paths = {tuple(error.absolute_path) for error in fixture_errors}
            if ("evaluation", "score", "state") not in paths:
                errors.append(f"{path.name}: rejection did not exercise invalid-score suppression")

    token = fixtures.get("token_simulation")
    if token is not None:
        try:
            legacy_task = _load_json(cross_version_dir / "token-task-legacy-v1.json")
            aligned_task = _load_json(cross_version_dir / "token-task-aligned-v2.json")
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            errors.append(f"cross-version token task vectors: invalid UTF-8 JSON: {exc}")
            legacy_task = aligned_task = None
        if legacy_task is not None and aligned_task is not None:
            legacy_v1 = copy.deepcopy(token)
            legacy_v1["category_payload"]["raw_evidence"]["tasks"] = [legacy_task]
            if _schema_errors(v1_validator, legacy_v1):
                errors.append("token task compatibility: published legacy task rejected by v1")
            aligned_v1 = copy.deepcopy(token)
            aligned_v1["category_payload"]["raw_evidence"]["tasks"] = [aligned_task]
            if _schema_errors(v1_validator, aligned_v1):
                errors.append("token task compatibility: aligned task rejected by v1 compatibility union")
            legacy_v2 = copy.deepcopy(legacy_v1)
            legacy_v2["schema_version"] = "aneb-result-v2"
            if not _schema_errors(v2_validator, legacy_v2):
                errors.append("token task version boundary: legacy task unexpectedly validates as v2")
            aligned_v2 = copy.deepcopy(aligned_v1)
            aligned_v2["schema_version"] = "aneb-result-v2"
            if _schema_errors(v2_validator, aligned_v2):
                errors.append("token task version boundary: aligned task unexpectedly rejected by v2")
            missing_identity_v2 = copy.deepcopy(aligned_v2)
            missing_identity_v2["category_payload"]["raw_evidence"]["tasks"][0]["task_id"] = None
            if not _schema_errors(v2_validator, missing_identity_v2):
                errors.append("token task version boundary: null task_id unexpectedly validates as v2")

        collected_empty = copy.deepcopy(token)
        collected_empty["context"]["radio"].update(
            collection_status="collected",
            unavailable_reason=None,
            sample_count=0,
            samples=[],
            evidence_ref_ids=[],
        )
        if not _schema_errors(v1_validator, collected_empty):
            errors.append("radio invariant: collected with zero evidence unexpectedly validates")

        fabricated_radio = copy.deepcopy(token)
        fabricated_radio["context"]["radio"]["rsrp_dbm"] = -80.0
        if not _schema_errors(v1_validator, fabricated_radio):
            errors.append("radio invariant: not_collected with RSRP unexpectedly validates")

        fabricated_metric = copy.deepcopy(token)
        fabricated_metric["evaluation"]["metrics"]["TOK-N03"]["value"] = 0.0
        if not _schema_errors(v1_validator, fabricated_metric):
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
        if not _schema_errors(v1_validator, scalar_with_null):
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
        if _schema_errors(v1_validator, referenced_series):
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
        print(
            "ANEB result schemas OK: compatible v1, strict v2, shared core, "
            "3 category fixtures and cross-version/null/radio invariants"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
