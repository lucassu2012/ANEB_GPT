#!/usr/bin/env python3
"""Fail-closed verifier for spec/catalog.json and every indexed asset.

The verifier intentionally uses only Python's standard library so it can run in
CI before Android, Go, or behavior-model dependencies are installed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


SEMVER_RE = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MANIFEST_LINE_RE = re.compile(r"^([0-9a-f]{64})  ([A-Za-z0-9_.-]+)$")
EXPECTED_CONSUMERS = {"P1", "P2", "P3", "Profile"}


class DuplicateJsonKey(ValueError):
    """Raised when a JSON object repeats a key."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def load_json(path: Path, label: str, errors: list[str]) -> Any | None:
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        errors.append(f"{label}: invalid UTF-8 JSON: {exc}")
        return None


def canonical_json_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _strict_keys(
    value: Any,
    required: set[str],
    optional: set[str],
    label: str,
    errors: list[str],
) -> bool:
    if not isinstance(value, dict):
        errors.append(f"{label}: expected object")
        return False
    missing = required - value.keys()
    unknown = value.keys() - required - optional
    if missing:
        errors.append(f"{label}: missing keys {sorted(missing)}")
    if unknown:
        errors.append(f"{label}: unverified keys {sorted(unknown)}")
    return not missing and not unknown


def _semver(value: Any, label: str, errors: list[str]) -> tuple[int, int, int] | None:
    if not isinstance(value, str) or not SEMVER_RE.fullmatch(value):
        errors.append(f"{label}: expected strict major.minor.patch version")
        return None
    return tuple(int(part) for part in value.split("."))  # type: ignore[return-value]


def _validate_range(
    value: Any,
    member: str,
    label: str,
    errors: list[str],
) -> None:
    if not _strict_keys(value, {"min_inclusive", "max_exclusive"}, set(), label, errors):
        return
    minimum = _semver(value.get("min_inclusive"), f"{label}.min_inclusive", errors)
    maximum = _semver(value.get("max_exclusive"), f"{label}.max_exclusive", errors)
    current = _semver(member, f"{label}.member", errors)
    if minimum is not None and maximum is not None and minimum >= maximum:
        errors.append(f"{label}: empty or reversed compatibility range")
    if current is not None and minimum is not None and maximum is not None:
        if not minimum <= current < maximum:
            errors.append(f"{label}: {member} is outside [{value['min_inclusive']}, {value['max_exclusive']})")


def _string_list(value: Any, label: str, errors: list[str], *, nonempty: bool = True) -> list[str]:
    if not isinstance(value, list) or (nonempty and not value):
        errors.append(f"{label}: expected {'non-empty ' if nonempty else ''}array")
        return []
    if any(not isinstance(item, str) or not item for item in value):
        errors.append(f"{label}: every item must be a non-empty string")
        return []
    if len(value) != len(set(value)):
        errors.append(f"{label}: duplicate items")
    return value


def _resolve_file(root: Path, ref: Any, label: str, errors: list[str]) -> Path | None:
    if not isinstance(ref, str) or not ref or "\\" in ref:
        errors.append(f"{label}: expected non-empty repository-relative POSIX path")
        return None
    relative = Path(ref)
    if relative.is_absolute() or ".." in relative.parts:
        errors.append(f"{label}: path must stay inside the repository")
        return None
    candidate = root.joinpath(*ref.split("/"))
    try:
        resolved_root = root.resolve(strict=True)
        resolved = candidate.resolve(strict=True)
    except OSError as exc:
        errors.append(f"{label}: referenced file does not exist: {ref} ({exc})")
        return None
    if resolved != resolved_root and resolved_root not in resolved.parents:
        errors.append(f"{label}: path escapes repository: {ref}")
        return None
    if not resolved.is_file():
        errors.append(f"{label}: reference is not a regular file: {ref}")
        return None
    return resolved


def _relative_set(root: Path, paths: Iterable[Path]) -> set[str]:
    return {path.relative_to(root).as_posix() for path in paths if path.is_file()}


def _check_inventory(label: str, declared: set[str], actual: set[str], errors: list[str]) -> None:
    missing = actual - declared
    stale = declared - actual
    if missing:
        errors.append(f"{label}: unindexed assets {sorted(missing)}")
    if stale:
        errors.append(f"{label}: catalog references outside inventory {sorted(stale)}")


def _parse_manifest(path: Path, label: str, errors: list[str]) -> dict[str, str]:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        errors.append(f"{label}: cannot read UTF-8 manifest: {exc}")
        return {}
    entries: dict[str, str] = {}
    lines = text.splitlines()
    if not lines:
        errors.append(f"{label}: empty manifest")
        return entries
    for number, line in enumerate(lines, start=1):
        match = MANIFEST_LINE_RE.fullmatch(line)
        if match is None:
            errors.append(f"{label}:{number}: expected '<sha256><two spaces><basename>'")
            continue
        digest, name = match.groups()
        if name in entries:
            errors.append(f"{label}:{number}: duplicate manifest entry {name!r}")
        entries[name] = digest
    return entries


def _component_versions_from_source(root: Path, errors: list[str]) -> dict[str, str]:
    sources = {
        "P1": (root / "app/probe/build.gradle.kts", re.compile(r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', re.MULTILINE)),
        "P2": (root / "server/main.go", re.compile(r'^const serverVersion = "aneb-server/([0-9]+\.[0-9]+\.[0-9]+)"', re.MULTILINE)),
        "P3": (root / "tools/aneb-ai-behavior-model/pyproject.toml", re.compile(r'^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', re.MULTILINE)),
    }
    versions: dict[str, str] = {}
    for consumer_id, (path, pattern) in sources.items():
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            errors.append(f"catalog.consumers.{consumer_id}: cannot read component version source: {exc}")
            continue
        match = pattern.search(text)
        if match is None:
            errors.append(f"catalog.consumers.{consumer_id}: component version source is unrecognized: {path}")
            continue
        versions[consumer_id] = match.group(1)
    return versions


def _validate_consumers(root: Path, catalog: dict[str, Any], errors: list[str]) -> None:
    consumers = catalog.get("consumers")
    if not isinstance(consumers, dict):
        errors.append("catalog.consumers: expected object")
        return
    if set(consumers) != EXPECTED_CONSUMERS:
        errors.append(f"catalog.consumers: expected exactly {sorted(EXPECTED_CONSUMERS)}")
    catalog_version = catalog.get("catalog_version")
    source_versions = _component_versions_from_source(root, errors)
    source_versions["Profile"] = catalog_version
    for consumer_id, value in consumers.items():
        label = f"catalog.consumers.{consumer_id}"
        if not _strict_keys(value, {"component", "current_version", "catalog_range", "roles"}, set(), label, errors):
            continue
        if not isinstance(value.get("component"), str) or not value["component"]:
            errors.append(f"{label}.component: expected non-empty string")
        _semver(value.get("current_version"), f"{label}.current_version", errors)
        if value.get("current_version") != source_versions.get(consumer_id):
            errors.append(
                f"{label}.current_version: catalog {value.get('current_version')!r} "
                f"does not match source {source_versions.get(consumer_id)!r}"
            )
        _validate_range(value.get("catalog_range"), catalog_version, f"{label}.catalog_range", errors)
        _string_list(value.get("roles"), f"{label}.roles", errors)


def _validate_schemas(root: Path, catalog: dict[str, Any], errors: list[str]) -> set[str]:
    schemas = catalog.get("schemas")
    if not isinstance(schemas, list) or not schemas:
        errors.append("catalog.schemas: expected non-empty array")
        return set()
    ids: set[str] = set()
    declared_paths: set[str] = set()
    for index, entry in enumerate(schemas):
        label = f"catalog.schemas[{index}]"
        if not _strict_keys(
            entry,
            {"schema_id", "schema_version", "path", "json_schema_id", "contract_version", "consumers"},
            set(),
            label,
            errors,
        ):
            continue
        schema_id = entry.get("schema_id")
        if not isinstance(schema_id, str) or not schema_id:
            errors.append(f"{label}.schema_id: expected non-empty string")
        elif schema_id in ids:
            errors.append(f"{label}.schema_id: duplicate {schema_id!r}")
        else:
            ids.add(schema_id)
        _semver(entry.get("schema_version"), f"{label}.schema_version", errors)
        consumers = set(_string_list(entry.get("consumers"), f"{label}.consumers", errors))
        if not consumers <= EXPECTED_CONSUMERS:
            errors.append(f"{label}.consumers: unknown consumers {sorted(consumers - EXPECTED_CONSUMERS)}")
        ref = entry.get("path")
        if isinstance(ref, str):
            declared_paths.add(ref)
        path = _resolve_file(root, ref, f"{label}.path", errors)
        if path is None:
            continue
        value = load_json(path, ref, errors)
        if not isinstance(value, dict):
            continue
        if value.get("$id") != entry.get("json_schema_id"):
            errors.append(f"{label}: JSON Schema $id does not match catalog")
        contract = entry.get("contract_version")
        property_name = {
            "aneb-behavior-trace-v1": "trace_contract_version",
            "aneb-result-v1": "schema_version",
            "aneb-token-observation-v1": "observation_contract_version",
            "aneb-calibration-dataset-v1": "dataset_contract_version",
            "aneb-model-validation-v1": "validation_contract_version",
        }.get(schema_id, "contract_version")
        declared_contract = value.get("properties", {}).get(property_name, {}).get("const")
        if declared_contract != contract:
            errors.append(f"{label}: schema {property_name}.const does not match catalog contract")
    actual = _relative_set(root, (root / "tools/aneb-ai-behavior-model/schemas").glob("*.json"))
    actual |= _relative_set(root, (root / "spec/schemas").glob("*.json"))
    _check_inventory("schema inventory", declared_paths, actual, errors)
    return ids


def _validate_hash_strategies(catalog: dict[str, Any], errors: list[str]) -> set[str]:
    entries = catalog.get("hash_strategies")
    if not isinstance(entries, list) or not entries:
        errors.append("catalog.hash_strategies: expected non-empty array")
        return set()
    ids: set[str] = set()
    for index, entry in enumerate(entries):
        label = f"catalog.hash_strategies[{index}]"
        required = {
            "strategy_id", "algorithm", "serialization", "digest_encoding",
            "manifest_line_format", "profile_reference_format",
        }
        if not _strict_keys(entry, required, set(), label, errors):
            continue
        strategy_id = entry.get("strategy_id")
        if not isinstance(strategy_id, str) or not strategy_id:
            errors.append(f"{label}.strategy_id: expected non-empty string")
        elif strategy_id in ids:
            errors.append(f"{label}.strategy_id: duplicate {strategy_id!r}")
        else:
            ids.add(strategy_id)
        expected = {
            "strategy_id": "canonical-json-sha256-v1",
            "algorithm": "sha256",
            "serialization": "utf8-json-sort-keys-compact-ensure-ascii-false",
            "digest_encoding": "lowercase-hex",
            "manifest_line_format": "<digest><two-spaces><basename>",
            "profile_reference_format": "sha256:<digest>",
        }
        if entry != expected:
            errors.append(f"{label}: verifier only implements the exact canonical-json-sha256-v1 strategy")
    return ids


def _validate_runtime_contracts(
    root: Path,
    catalog: dict[str, Any],
    hash_strategy_ids: set[str],
    errors: list[str],
) -> dict[str, dict[str, Any]]:
    entries = catalog.get("runtime_contracts")
    if not isinstance(entries, list) or not entries:
        errors.append("catalog.runtime_contracts: expected non-empty array")
        return {}
    contracts: dict[str, dict[str, Any]] = {}
    for index, entry in enumerate(entries):
        label = f"catalog.runtime_contracts[{index}]"
        required = {
            "contract_version", "version", "standalone_schema_path", "compatible_profile_modes",
            "hash_strategy_id", "consumers",
        }
        if not _strict_keys(entry, required, set(), label, errors):
            continue
        contract = entry.get("contract_version")
        if not isinstance(contract, str) or not contract:
            errors.append(f"{label}.contract_version: expected non-empty string")
        elif contract in contracts:
            errors.append(f"{label}.contract_version: duplicate {contract!r}")
        else:
            contracts[contract] = entry
        _semver(entry.get("version"), f"{label}.version", errors)
        modes = _string_list(entry.get("compatible_profile_modes"), f"{label}.compatible_profile_modes", errors)
        consumers = set(_string_list(entry.get("consumers"), f"{label}.consumers", errors))
        if not consumers <= EXPECTED_CONSUMERS:
            errors.append(f"{label}.consumers: unknown consumers {sorted(consumers - EXPECTED_CONSUMERS)}")
        if entry.get("hash_strategy_id") not in hash_strategy_ids:
            errors.append(f"{label}.hash_strategy_id: unknown hash strategy")
        schema_ref = entry.get("standalone_schema_path")
        if schema_ref is not None:
            _resolve_file(root, schema_ref, f"{label}.standalone_schema_path", errors)
        if not modes:
            errors.append(f"{label}: runtime contract has no compatible profile mode")
    return contracts


def _validate_models(
    root: Path,
    catalog: dict[str, Any],
    hash_strategy_ids: set[str],
    errors: list[str],
) -> dict[tuple[str, str], str]:
    entries = catalog.get("model_assets")
    if not isinstance(entries, list) or not entries:
        errors.append("catalog.model_assets: expected non-empty array")
        return {}
    models: dict[tuple[str, str], str] = {}
    declared_paths: set[str] = set()
    for index, entry in enumerate(entries):
        label = f"catalog.model_assets[{index}]"
        required = {"model_id", "version", "status", "path", "hash_strategy_id", "consumers"}
        if not _strict_keys(entry, required, set(), label, errors):
            continue
        model_id = entry.get("model_id")
        version = entry.get("version")
        _semver(version, f"{label}.version", errors)
        if not isinstance(model_id, str) or not model_id:
            errors.append(f"{label}.model_id: expected non-empty string")
            continue
        key = (model_id, version) if isinstance(version, str) else (model_id, "")
        if key in models:
            errors.append(f"{label}: duplicate model id/version {key}")
        if entry.get("hash_strategy_id") not in hash_strategy_ids:
            errors.append(f"{label}.hash_strategy_id: unknown hash strategy")
        consumers = set(_string_list(entry.get("consumers"), f"{label}.consumers", errors))
        if not consumers <= EXPECTED_CONSUMERS:
            errors.append(f"{label}.consumers: unknown consumers {sorted(consumers - EXPECTED_CONSUMERS)}")
        ref = entry.get("path")
        if isinstance(ref, str):
            declared_paths.add(ref)
        path = _resolve_file(root, ref, f"{label}.path", errors)
        if path is None:
            continue
        value = load_json(path, ref, errors)
        if not isinstance(value, dict):
            continue
        if value.get("model_id") != model_id or value.get("model_version") != version:
            errors.append(f"{label}: model id/version does not match catalog")
        if value.get("status") != entry.get("status"):
            errors.append(f"{label}: model status does not match catalog")
        if value.get("model_contract_version") != "aneb-behavior-model-v1":
            errors.append(f"{label}: unsupported model_contract_version")
        models[key] = canonical_json_sha256(value)
    actual = _relative_set(root, (root / "tools/aneb-ai-behavior-model/models").glob("*.json"))
    _check_inventory("behavior model inventory", declared_paths, actual, errors)
    return models


def _validate_profile_shape(
    profile: dict[str, Any],
    entry: dict[str, Any],
    family: dict[str, Any],
    group: dict[str, Any],
    label: str,
    errors: list[str],
) -> None:
    if profile.get("profile_id") != entry.get("profile_id"):
        errors.append(f"{label}: profile_id does not match catalog")
    if profile.get("version") != entry.get("version"):
        errors.append(f"{label}: profile version does not match catalog")
    version = profile.get("version")
    if isinstance(version, str):
        _validate_range(family.get("profile_version_range"), version, f"{label}.profile_version_range", errors)
    mode = profile.get("mode_id")
    if mode not in group.get("compatible_modes", []):
        errors.append(f"{label}: mode_id {mode!r} is not compatible with validation group")
    phases = profile.get("phases")
    if not isinstance(phases, list) or not phases:
        errors.append(f"{label}: phases must be a non-empty array")
    if family.get("contract_version") == "aneb-profile-v2":
        if profile.get("contract_version") != "aneb-profile-v2":
            errors.append(f"{label}: contract_version is not aneb-profile-v2")
        for key, expected_type in {
            "business": dict,
            "measurements": list,
            "live_presentation": dict,
            "evaluation": dict,
        }.items():
            if not isinstance(profile.get(key), expected_type) or not profile.get(key):
                errors.append(f"{label}: v2 field {key!r} is missing or empty")
        if profile.get("execution_target") != "aneb_probe_simulator":
            errors.append(f"{label}: unsupported execution_target")
    elif profile.get("contract_version") not in (None, ""):
        errors.append(f"{label}: server-root family must not masquerade as a versioned contract")


def _validate_runtime_bundle(
    root: Path,
    entry: dict[str, Any],
    profile: dict[str, Any],
    runtime_contracts: dict[str, dict[str, Any]],
    model_hashes: dict[tuple[str, str], str],
    label: str,
    errors: list[str],
) -> tuple[str | None, str | None]:
    profile_ref = entry.get("path")
    runtime_ref = entry.get("runtime_plan_path")
    manifest_ref = entry.get("manifest_path")
    runtime_path = _resolve_file(root, runtime_ref, f"{label}.runtime_plan_path", errors)
    manifest_path = _resolve_file(root, manifest_ref, f"{label}.manifest_path", errors)
    if runtime_path is None or manifest_path is None:
        return runtime_ref if isinstance(runtime_ref, str) else None, manifest_ref if isinstance(manifest_ref, str) else None
    profile_path = root.joinpath(*profile_ref.split("/")) if isinstance(profile_ref, str) else None
    if profile_path is None or runtime_path.parent != profile_path.parent or manifest_path.parent != profile_path.parent:
        errors.append(f"{label}: profile, runtime plan, and manifest must share one bundle directory")
    if runtime_path.name != "runtime_plan.json" or manifest_path.name != "manifest.sha256":
        errors.append(f"{label}: runtime bundle uses non-standard filenames")
    runtime = load_json(runtime_path, runtime_ref, errors)
    if not isinstance(runtime, dict):
        return runtime_ref, manifest_ref
    runtime_contract = runtime.get("contract_version")
    contract_entry = runtime_contracts.get(runtime_contract)
    if contract_entry is None:
        errors.append(f"{label}: unknown runtime contract {runtime_contract!r}")
    elif profile.get("mode_id") not in contract_entry.get("compatible_profile_modes", []):
        errors.append(f"{label}: runtime contract is incompatible with profile mode")

    business = profile.get("business") if isinstance(profile.get("business"), dict) else {}
    execution = profile.get("execution_plan") if isinstance(profile.get("execution_plan"), dict) else {}
    phase_items = profile.get("phases") if isinstance(profile.get("phases"), list) else []
    behavior_phases = [phase for phase in phase_items if isinstance(phase, dict) and phase.get("type") == "behavior_trace"]
    if len(behavior_phases) != 1:
        errors.append(f"{label}: runtime-bound profile must contain exactly one behavior_trace phase")
        behavior_phase: dict[str, Any] = {}
    else:
        behavior_phase = behavior_phases[0]
    if not execution:
        errors.append(f"{label}: runtime-bound profile is missing execution_plan")

    runtime_digest = canonical_json_sha256(runtime)
    profile_digest = canonical_json_sha256(profile)
    expected_runtime_ref = f"sha256:{runtime_digest}"
    pairs = [
        (runtime.get("model_id"), business.get("behavior_model_id"), "model_id"),
        (runtime.get("model_version"), business.get("behavior_model_version"), "model_version"),
        (runtime.get("model_hash"), business.get("behavior_model_hash"), "model_hash"),
        (runtime.get("calibration_status"), business.get("calibration_status"), "calibration_status"),
        (runtime.get("contract_version"), execution.get("contract_version"), "execution contract"),
        (runtime.get("seed"), execution.get("seed"), "seed"),
        (runtime.get("variant"), execution.get("variant"), "variant"),
        (runtime.get("model_id"), behavior_phase.get("model_id"), "phase model_id"),
        (runtime.get("model_version"), behavior_phase.get("model_version"), "phase model_version"),
        (runtime.get("model_hash"), behavior_phase.get("model_hash"), "phase model_hash"),
        (runtime.get("seed"), behavior_phase.get("seed"), "phase seed"),
    ]
    for left, right, name in pairs:
        if left != right:
            errors.append(f"{label}: runtime/profile {name} mismatch")
    if execution.get("artifact") != "runtime_plan.json" or behavior_phase.get("runtime_artifact") != "runtime_plan.json":
        errors.append(f"{label}: runtime artifact name mismatch")
    if execution.get("artifact_hash") != expected_runtime_ref or behavior_phase.get("runtime_artifact_hash") != expected_runtime_ref:
        errors.append(f"{label}: runtime semantic hash is not bound by profile and phase")

    model_key = (runtime.get("model_id"), runtime.get("model_version"))
    model_digest = model_hashes.get(model_key)
    if model_digest is None:
        errors.append(f"{label}: runtime references an unindexed behavior model {model_key}")
    elif runtime.get("model_hash") != f"sha256:{model_digest}":
        errors.append(f"{label}: runtime behavior model hash mismatch")

    if runtime_contract == "aneb-token-runtime-plan-v1":
        items, count = runtime.get("tasks"), runtime.get("task_count")
    elif runtime_contract == "aneb-realtime-runtime-plan-v1":
        items, count = runtime.get("sessions"), runtime.get("session_count")
    else:
        items, count = None, None
    if not isinstance(items, list) or not items or not isinstance(count, int) or count != len(items):
        errors.append(f"{label}: runtime plan item count is missing or inconsistent")

    manifest = _parse_manifest(manifest_path, manifest_ref, errors)
    expected_manifest = {"profile.json": profile_digest, "runtime_plan.json": runtime_digest}
    if manifest != expected_manifest:
        errors.append(f"{label}: manifest must contain exactly the canonical profile/runtime hashes")
    return runtime_ref, manifest_ref


def _validate_profiles(
    root: Path,
    catalog: dict[str, Any],
    schema_ids: set[str],
    hash_strategy_ids: set[str],
    runtime_contracts: dict[str, dict[str, Any]],
    model_hashes: dict[tuple[str, str], str],
    errors: list[str],
) -> None:
    families = catalog.get("profile_families")
    if not isinstance(families, list) or not families:
        errors.append("catalog.profile_families: expected non-empty array")
        return
    family_ids: set[str] = set()
    profile_ids: set[str] = set()
    declared_profile_paths: set[str] = set()
    declared_runtime_paths: set[str] = set()
    declared_manifest_paths: set[str] = set()
    group_counts: Counter[tuple[str, str]] = Counter()
    runtime_mode_counts: Counter[str] = Counter()

    for family_index, family in enumerate(families):
        family_label = f"catalog.profile_families[{family_index}]"
        required = {
            "family_id", "family_version", "contract_version", "profile_version_range",
            "schema_ref", "consumers", "validation_groups", "profiles",
        }
        if not _strict_keys(family, required, set(), family_label, errors):
            continue
        family_id = family.get("family_id")
        if not isinstance(family_id, str) or not family_id:
            errors.append(f"{family_label}.family_id: expected non-empty string")
            continue
        if family_id in family_ids:
            errors.append(f"{family_label}.family_id: duplicate {family_id!r}")
        family_ids.add(family_id)
        _semver(family.get("family_version"), f"{family_label}.family_version", errors)
        consumers = set(_string_list(family.get("consumers"), f"{family_label}.consumers", errors))
        if not consumers <= EXPECTED_CONSUMERS:
            errors.append(f"{family_label}.consumers: unknown consumers {sorted(consumers - EXPECTED_CONSUMERS)}")
        schema_ref = family.get("schema_ref")
        if schema_ref is not None and schema_ref not in schema_ids:
            errors.append(f"{family_label}.schema_ref: unknown schema {schema_ref!r}")
        if family.get("contract_version") == "aneb-profile-v2" and schema_ref != "aneb-profile-v2":
            errors.append(f"{family_label}: v2 family must bind the aneb-profile-v2 schema")
        if family.get("contract_version") != "aneb-profile-v2" and schema_ref is not None:
            errors.append(f"{family_label}: legacy family must not claim a v2 schema")
        expected_family = {
            "server-root-inline-profile-v1": {
                "contract_version": None,
                "schema_ref": None,
                "consumers": {"P1", "P2", "Profile"},
            },
            "published-profile-v2": {
                "contract_version": "aneb-profile-v2",
                "schema_ref": "aneb-profile-v2",
                "consumers": {"P1", "P3", "Profile"},
            },
        }.get(family_id)
        if expected_family is not None:
            if family.get("contract_version") != expected_family["contract_version"]:
                errors.append(f"{family_label}: contract_version does not match the indexed family")
            if schema_ref != expected_family["schema_ref"]:
                errors.append(f"{family_label}: schema_ref does not match the indexed family")
            if consumers != expected_family["consumers"]:
                errors.append(f"{family_label}: consumers do not match the indexed family")

        groups_value = family.get("validation_groups")
        groups: dict[str, dict[str, Any]] = {}
        if not isinstance(groups_value, list) or not groups_value:
            errors.append(f"{family_label}.validation_groups: expected non-empty array")
        else:
            for group_index, group in enumerate(groups_value):
                group_label = f"{family_label}.validation_groups[{group_index}]"
                if not _strict_keys(
                    group,
                    {
                        "group_id", "validation_strategy", "runtime_manifest_policy",
                        "hash_strategy_id", "compatible_modes",
                    },
                    set(),
                    group_label,
                    errors,
                ):
                    continue
                group_id = group.get("group_id")
                if not isinstance(group_id, str) or not group_id:
                    errors.append(f"{group_label}.group_id: expected non-empty string")
                    continue
                if group_id in groups:
                    errors.append(f"{group_label}.group_id: duplicate {group_id!r}")
                groups[group_id] = group
                if group.get("runtime_manifest_policy") not in {"required", "forbidden"}:
                    errors.append(f"{group_label}: invalid runtime_manifest_policy")
                if group.get("runtime_manifest_policy") == "required":
                    if group.get("hash_strategy_id") not in hash_strategy_ids:
                        errors.append(f"{group_label}: required runtime manifest has unknown hash strategy")
                elif group.get("hash_strategy_id") is not None:
                    errors.append(f"{group_label}: manifest-forbidden group must set hash_strategy_id to null")
                _string_list(group.get("compatible_modes"), f"{group_label}.compatible_modes", errors)

        expected_groups = {
            "server-root-inline-profile-v1": {
                "server_root_inline_phases": (
                    "legacy-required-fields-and-inline-phases-v1", "forbidden", None,
                    {"token_experience", "network_basic"},
                ),
            },
            "published-profile-v2": {
                "behavior_runtime_bound": (
                    "profile-runtime-manifest-canonical-hash-v1", "required",
                    "canonical-json-sha256-v1", {"token_simulation", "ai_realtime_simulation"},
                ),
                "network_embedded_phases": (
                    "profile-v2-embedded-phases-v1", "forbidden", None,
                    {"network_comprehensive"},
                ),
            },
        }.get(family_id, {})
        if set(groups) != set(expected_groups):
            errors.append(f"{family_label}.validation_groups: group set is not recognized")
        for group_id, expected in expected_groups.items():
            group = groups.get(group_id)
            if group is None:
                continue
            strategy, policy, hash_strategy, modes = expected
            if group.get("validation_strategy") != strategy:
                errors.append(f"{family_label}.{group_id}: validation_strategy is not recognized")
            if group.get("runtime_manifest_policy") != policy:
                errors.append(f"{family_label}.{group_id}: runtime manifest policy is not recognized")
            if group.get("hash_strategy_id") != hash_strategy:
                errors.append(f"{family_label}.{group_id}: hash strategy is not recognized")
            if set(group.get("compatible_modes", [])) != modes:
                errors.append(f"{family_label}.{group_id}: compatible mode set is not recognized")

        profiles = family.get("profiles")
        if not isinstance(profiles, list) or not profiles:
            errors.append(f"{family_label}.profiles: expected non-empty array")
            continue
        for profile_index, entry in enumerate(profiles):
            label = f"{family_label}.profiles[{profile_index}]"
            base_keys = {"profile_id", "version", "path", "validation_group_id"}
            runtime_keys = {"runtime_plan_path", "manifest_path"}
            if not _strict_keys(entry, base_keys, runtime_keys, label, errors):
                continue
            profile_id = entry.get("profile_id")
            if not isinstance(profile_id, str) or not profile_id:
                errors.append(f"{label}.profile_id: expected non-empty string")
            elif profile_id in profile_ids:
                errors.append(f"{label}.profile_id: duplicate across catalog {profile_id!r}")
            else:
                profile_ids.add(profile_id)
            _semver(entry.get("version"), f"{label}.version", errors)
            group_id = entry.get("validation_group_id")
            group = groups.get(group_id)
            if group is None:
                errors.append(f"{label}.validation_group_id: unknown group {group_id!r}")
                continue
            group_counts[(family_id, group_id)] += 1
            profile_ref = entry.get("path")
            if isinstance(profile_ref, str):
                declared_profile_paths.add(profile_ref)
            profile_path = _resolve_file(root, profile_ref, f"{label}.path", errors)
            if profile_path is None:
                continue
            profile = load_json(profile_path, profile_ref, errors)
            if not isinstance(profile, dict):
                continue
            _validate_profile_shape(profile, entry, family, group, profile_ref, errors)
            policy = group.get("runtime_manifest_policy")
            if policy == "required":
                if "runtime_plan_path" not in entry or "manifest_path" not in entry:
                    errors.append(f"{label}: required runtime bundle references are missing")
                runtime_ref, manifest_ref = _validate_runtime_bundle(
                    root, entry, profile, runtime_contracts, model_hashes, profile_ref, errors
                )
                if runtime_ref:
                    declared_runtime_paths.add(runtime_ref)
                if manifest_ref:
                    declared_manifest_paths.add(manifest_ref)
                runtime_mode_counts[profile.get("mode_id")] += 1
            elif policy == "forbidden":
                if "runtime_plan_path" in entry or "manifest_path" in entry:
                    errors.append(f"{label}: runtime references are forbidden for embedded-phase profiles")
                for forbidden_name in ("runtime_plan.json", "manifest.sha256"):
                    if (profile_path.parent / forbidden_name).exists():
                        errors.append(f"{label}: forbidden sibling artifact {forbidden_name!r} exists")
                if profile.get("execution_plan") is not None:
                    errors.append(f"{label}: embedded-phase profile must not declare execution_plan")
                phases = profile.get("phases", [])
                if isinstance(phases, list) and any(
                    isinstance(phase, dict) and phase.get("type") == "behavior_trace" for phase in phases
                ):
                    errors.append(f"{label}: embedded-phase profile must not use behavior_trace")

    expected_families = {"server-root-inline-profile-v1", "published-profile-v2"}
    if family_ids != expected_families:
        errors.append(f"profile families: expected exactly {sorted(expected_families)}")
    if group_counts[("server-root-inline-profile-v1", "server_root_inline_phases")] != 4:
        errors.append("server root validation group: expected 4 profiles")
    if group_counts[("published-profile-v2", "behavior_runtime_bound")] != 6:
        errors.append("runtime-bound validation group: expected 6 Token/AI realtime profiles")
    if runtime_mode_counts != Counter({"token_simulation": 3, "ai_realtime_simulation": 3}):
        errors.append("runtime-bound validation group: expected 3 Token and 3 AI realtime profiles")
    if group_counts[("published-profile-v2", "network_embedded_phases")] != 6:
        errors.append("embedded network validation group: expected 6 profiles")

    actual_root_profiles = _relative_set(root, (root / "profiles").glob("*.json"))
    actual_published_profiles = _relative_set(root, (root / "profiles/published").glob("*/profile.json"))
    _check_inventory("profile inventory", declared_profile_paths, actual_root_profiles | actual_published_profiles, errors)
    actual_runtime = _relative_set(root, (root / "profiles/published").glob("*/runtime_plan.json"))
    actual_manifests = _relative_set(root, (root / "profiles/published").glob("*/manifest.sha256"))
    _check_inventory("runtime plan inventory", declared_runtime_paths, actual_runtime, errors)
    _check_inventory("runtime manifest inventory", declared_manifest_paths, actual_manifests, errors)


def validate_catalog(root: Path, catalog_path: Path | None = None) -> list[str]:
    root = root.resolve()
    catalog_path = catalog_path or root / "spec/catalog.json"
    errors: list[str] = []
    catalog = load_json(catalog_path, str(catalog_path), errors)
    if not isinstance(catalog, dict):
        if catalog is not None:
            errors.append("catalog: root must be a JSON object")
        return errors
    required = {
        "catalog_id", "catalog_version", "compatibility", "consumers", "hash_strategies",
        "schemas", "runtime_contracts", "model_assets", "profile_families",
    }
    if not _strict_keys(catalog, required, set(), "catalog", errors):
        return errors
    if catalog.get("catalog_id") != "aneb-spec-catalog":
        errors.append("catalog.catalog_id: expected 'aneb-spec-catalog'")
    version = catalog.get("catalog_version")
    _semver(version, "catalog.catalog_version", errors)
    compatibility = catalog.get("compatibility")
    if _strict_keys(compatibility, {"policy", "catalog_range"}, set(), "catalog.compatibility", errors):
        if compatibility.get("policy") != "semver-half-open-v1":
            errors.append("catalog.compatibility.policy: unsupported policy")
        _validate_range(compatibility.get("catalog_range"), version, "catalog.compatibility.catalog_range", errors)
    _validate_consumers(root, catalog, errors)
    hash_strategy_ids = _validate_hash_strategies(catalog, errors)
    schema_ids = _validate_schemas(root, catalog, errors)
    runtime_contracts = _validate_runtime_contracts(root, catalog, hash_strategy_ids, errors)
    model_hashes = _validate_models(root, catalog, hash_strategy_ids, errors)
    _validate_profiles(root, catalog, schema_ids, hash_strategy_ids, runtime_contracts, model_hashes, errors)
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate ANEB spec catalog and indexed assets")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: inferred from this script)",
    )
    parser.add_argument("--catalog", type=Path, help="catalog path (default: <root>/spec/catalog.json)")
    parser.add_argument("--quiet", action="store_true", help="print only failures")
    args = parser.parse_args(argv)
    errors = validate_catalog(args.root, args.catalog)
    if errors:
        print(f"ANEB spec catalog FAILED ({len(errors)} error(s))", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    if not args.quiet:
        catalog_path = args.catalog or args.root / "spec/catalog.json"
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        families = catalog["profile_families"]
        profiles = [profile for family in families for profile in family["profiles"]]
        runtime_bound = [profile for profile in profiles if "runtime_plan_path" in profile]
        embedded_network = [
            profile for profile in profiles
            if profile.get("validation_group_id") == "network_embedded_phases"
        ]
        print(
            "ANEB spec catalog OK: "
            f"{len(catalog['schemas'])} schemas, {len(families)} families, "
            f"{len(profiles)} profiles, {len(runtime_bound)} runtime bundles, "
            f"{len(embedded_network)} embedded-network profiles, "
            f"{len(catalog['model_assets'])} behavior models"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
