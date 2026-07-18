#!/usr/bin/env python3
"""Route exported ANEB JSONL records to their immutable schema-version validator."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

import jsonschema
from referencing import Registry, Resource


SUPPORTED_SCHEMAS = {
    "aneb-result-v1": "aneb-result-v1.schema.json",
    "aneb-result-v2": "aneb-result-v2.schema.json",
}


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


def _load_schema_validators(root: Path) -> dict[str, jsonschema.Draft202012Validator]:
    schema_dir = root / "spec/schemas"
    paths = [schema_dir / "aneb-result-core-v1.schema.json"] + [
        schema_dir / filename for filename in SUPPORTED_SCHEMAS.values()
    ]
    schemas_by_name = {
        path.name: json.loads(path.read_text(encoding="utf-8")) for path in paths
    }
    schemas = list(schemas_by_name.values())
    registry = Registry().with_resources(
        (schema["$id"], Resource.from_contents(schema)) for schema in schemas
    )
    return {
        version: jsonschema.Draft202012Validator(
            schemas_by_name[filename],
            registry=registry,
        )
        for version, filename in SUPPORTED_SCHEMAS.items()
    }


def _json_lines(paths: Iterable[Path]) -> Iterable[tuple[Path, int, dict[str, Any]]]:
    for path in paths:
        with path.open("r", encoding="utf-8") as stream:
            for line_number, raw in enumerate(stream, 1):
                if not raw.strip():
                    continue
                try:
                    value = json.loads(
                        raw,
                        object_pairs_hook=_object_without_duplicates,
                        parse_constant=_reject_non_finite,
                    )
                except (json.JSONDecodeError, ValueError) as exc:
                    raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
                if not isinstance(value, dict):
                    raise ValueError(f"{path}:{line_number}: result line must be a JSON object")
                yield path, line_number, value


def validate_jsonl(root: Path, paths: Iterable[Path]) -> dict[str, Any]:
    validators = _load_schema_validators(root.resolve())
    counts: Counter[str] = Counter()
    errors: list[str] = []
    run_ids: set[str] = set()
    document_count = 0
    try:
        for path, line_number, value in _json_lines(paths):
            document_count += 1
            version = value.get("schema_version")
            if not isinstance(version, str) or version not in validators:
                errors.append(f"{path}:{line_number}: unsupported schema_version {version!r}")
                continue
            counts[version] += 1
            run_id = value.get("run", {}).get("run_id") if isinstance(value.get("run"), dict) else None
            if isinstance(run_id, str):
                if run_id in run_ids:
                    errors.append(f"{path}:{line_number}: duplicate run_id {run_id!r}")
                run_ids.add(run_id)
            for error in sorted(validators[version].iter_errors(value), key=lambda item: list(item.absolute_path)):
                pointer = "/" + "/".join(str(part) for part in error.absolute_path)
                errors.append(f"{path}:{line_number}:{pointer}: {error.message}")
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        errors.append(str(exc))
    if document_count == 0:
        errors.append("result JSONL contains no documents")
    return {
        "status": "pass" if not errors else "fail",
        "documents": document_count,
        "schema_versions": dict(sorted(counts.items())),
        "errors": errors,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate ANEB JSONL structure by each record's published schema version",
    )
    parser.add_argument("inputs", type=Path, nargs="+")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: inferred from this script)",
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)
    report = validate_jsonl(args.root, args.inputs)
    if not args.quiet or report["status"] != "pass":
        print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
