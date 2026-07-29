#!/usr/bin/env python3
"""Run one protected engineering repeatability campaign on the shared P40/E-01.

This is deliberately not a formal-baseline collector.  It reuses the frozen
M0 mechanics, holds the phone mutex and E-01 deployment flock for the whole
campaign, preserves/restores the pre-existing APK and private files, and emits
raw run IDs/logcat plus a frozen Room snapshot for later independent export.
"""

from __future__ import annotations

import argparse
import dataclasses
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import time
from typing import Literal, Sequence
import uuid

if __package__:
    from scripts import analyze_repeatability_cohort as repeatability
    from scripts import collect_network_quick_evidence as network
    from scripts import collect_realtime_quick_evidence as mechanics
else:
    import analyze_repeatability_cohort as repeatability
    import collect_network_quick_evidence as network
    import collect_realtime_quick_evidence as mechanics


PACKAGE_NAME = "com.aneb.probe.codex"
ACTIVITY_COMPONENT = f"{PACKAGE_NAME}/com.aneb.probe.ui.MainActivity"
ROOM_FILES = (
    "databases/aneb-probe.db",
    "databases/aneb-probe.db-wal",
    "databases/aneb-probe.db-shm",
    "files/profileInstalled",
    "shared_prefs/probe_settings_v1.xml",
)
UUID7_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


class CampaignError(mechanics.CollectorError):
    pass


@dataclasses.dataclass(frozen=True)
class TokenTerminal:
    run_id: str
    terminal_status: Literal["completed"]


@dataclasses.dataclass(frozen=True)
class QualificationProfileBinding:
    family: Literal["token", "realtime", "network"]
    policy_family: str
    profile_id: str
    profile_version: str
    profile_sha256: str
    runtime_plan_sha256: str
    execution_variant: Literal["repeatability_qualification"]


@dataclasses.dataclass(frozen=True)
class QualificationCampaignContract:
    policy_id: str
    policy_version: str
    policy_sha256: str
    stage_id: Literal["Q1_WIFI", "Q2_CELLULAR"]
    stage_order: tuple[str, ...]
    transport: Literal["wifi", "cellular"]
    runs_per_family: int
    transport_pooling_allowed: bool
    q2_requires_q1_pass: bool
    token_batches: tuple[tuple[str, int], ...]
    profile_bindings: tuple[QualificationProfileBinding, ...]


@dataclasses.dataclass(frozen=True)
class QualificationCampaignRun:
    stage_id: Literal["Q1_WIFI", "Q2_CELLULAR"]
    transport: Literal["wifi", "cellular"]
    policy_id: str
    policy_version: str
    policy_sha256: str
    family: Literal["token", "realtime", "network"]
    ordinal: int
    batch_id: str | None
    profile_id: str
    profile_version: str
    profile_sha256: str
    runtime_plan_sha256: str
    execution_variant: Literal["repeatability_qualification"]
    q1_pass_required: bool


@dataclasses.dataclass(frozen=True)
class QualificationPrerequisiteReport:
    family: Literal["token", "realtime", "network"]
    policy_family: str
    source_path: Path
    raw_sha256: str
    canonical_sha256: str
    canonical_bytes: bytes


@dataclasses.dataclass(frozen=True)
class PreparedQualificationCampaign:
    contract: QualificationCampaignContract
    plan: tuple[QualificationCampaignRun, ...]
    q1_prerequisites: tuple[QualificationPrerequisiteReport, ...]


QUALIFICATION_POLICY_ID = "aneb-repeatability-qualification-balanced-v1"
QUALIFICATION_POLICY_VERSION = "1.0.0"
QUALIFICATION_POLICY_SHA256 = "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa"
QUALIFICATION_FAMILIES = (
    ("token", "token_simulation"),
    ("realtime", "ai_realtime_simulation"),
    ("network", "network_comprehensive"),
)


def radio_permissions() -> tuple[str, ...]:
    return (
        "android.permission.READ_PHONE_STATE",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
    )


def parse_radio_permission_status(package_dump: str) -> dict[str, bool]:
    if not isinstance(package_dump, str) or "\x00" in package_dump:
        raise CampaignError("radio_permission_dump_invalid")
    status: dict[str, bool] = {}
    for permission in radio_permissions():
        rows = re.findall(
            rf"(?m)^\s*{re.escape(permission)}: granted=(true|false)(?:,|\s*$)",
            package_dump,
        )
        if len(rows) != 1:
            raise CampaignError(f"radio_permission_row_invalid:{permission}")
        status[permission] = rows[0] == "true"
    return status


def assert_radio_permissions_granted(package_dump: str) -> dict[str, bool]:
    status = parse_radio_permission_status(package_dump)
    denied = [permission for permission in radio_permissions() if not status[permission]]
    if denied:
        raise CampaignError(f"radio_permission_not_granted:{','.join(denied)}")
    return status


def build_radio_permission_receipt(package_dump: str) -> dict[str, object]:
    permissions = parse_radio_permission_status(package_dump)
    denied = [permission for permission in radio_permissions() if not permissions[permission]]
    return {
        "schema_version": "aneb-repeatability-radio-permissions-v1",
        "package_name": PACKAGE_NAME,
        "source": f"dumpsys package {PACKAGE_NAME}",
        "package_dump_sha256": hashlib.sha256(package_dump.encode("utf-8")).hexdigest(),
        "permissions": permissions,
        "denied_permissions": denied,
        "all_granted": not denied,
        "diagnostic_only": True,
        "formal_baseline_eligible": False,
    }


def record_radio_permission_preflight(
    package_dump: str, output: Path
) -> dict[str, object]:
    receipt = build_radio_permission_receipt(package_dump)
    _write_json(output, receipt)
    denied = receipt["denied_permissions"]
    if denied:
        if not isinstance(denied, list) or not all(isinstance(item, str) for item in denied):
            raise CampaignError("radio_permission_receipt_invalid")
        raise CampaignError(f"radio_permission_not_granted:{','.join(denied)}")
    return receipt


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=True, allow_nan=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("ascii")


def _coerce_receipt_bytes(value: str | bytes) -> bytes:
    if isinstance(value, bytes):
        return value
    if isinstance(value, str):
        return value.encode("utf-8")
    raise CampaignError("receipt_type_invalid")


def _write_exclusive(path: Path, value: bytes) -> None:
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0),
        0o600,
    )
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def _write_json(path: Path, value: object) -> None:
    _write_exclusive(path, _canonical_bytes(value))


def _catalog_canonical_bytes(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as error:
        raise CampaignError("qualification_canonical_json_invalid") from error


def _catalog_canonical_sha256(value: object) -> str:
    return _sha256_bytes(_catalog_canonical_bytes(value))


def _strict_json_object_with_bytes(
    path: Path, code: str
) -> tuple[bytes, dict[str, object]]:
    def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate_json_key")
            result[key] = value
        return result

    def reject_non_finite(value: str) -> None:
        raise ValueError(f"non_finite_json_number:{value}")

    try:
        raw = path.read_bytes()
        value = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=reject_duplicate_keys,
            parse_constant=reject_non_finite,
        )
    except (OSError, UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
        raise CampaignError(code) from error
    if not isinstance(value, dict):
        raise CampaignError(code)
    return raw, value


def _json_object(path: Path, code: str) -> dict[str, object]:
    return _strict_json_object_with_bytes(path, code)[1]


def _repository_file(repository_root: Path, relative: object, code: str) -> Path:
    if not isinstance(relative, str) or not relative or "\\" in relative:
        raise CampaignError(code)
    pure = PurePosixPath(relative)
    if pure.is_absolute() or ".." in pure.parts or "." in pure.parts:
        raise CampaignError(code)
    root = repository_root.resolve(strict=True)
    path = root.joinpath(*pure.parts).resolve(strict=True)
    if root != path and root not in path.parents:
        raise CampaignError(code)
    if not path.is_file():
        raise CampaignError(code)
    return path


def _manifest_hashes(path: Path) -> dict[str, str]:
    try:
        lines = path.read_bytes().decode("ascii", "strict").splitlines()
    except (OSError, UnicodeDecodeError) as error:
        raise CampaignError("qualification_manifest_invalid") from error
    if len(lines) != 2:
        raise CampaignError("qualification_manifest_invalid")
    hashes: dict[str, str] = {}
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  (profile\.json|runtime_plan\.json)", line)
        if match is None or match.group(2) in hashes:
            raise CampaignError("qualification_manifest_invalid")
        hashes[match.group(2)] = match.group(1)
    if set(hashes) != {"profile.json", "runtime_plan.json"}:
        raise CampaignError("qualification_manifest_invalid")
    return hashes


def _published_profile_catalog(catalog: dict[str, object]) -> list[dict[str, object]]:
    families = catalog.get("profile_families")
    if not isinstance(families, list):
        raise CampaignError("qualification_catalog_invalid")
    matches = [
        family
        for family in families
        if isinstance(family, dict) and family.get("family_id") == "published-profile-v2"
    ]
    if len(matches) != 1 or not isinstance(matches[0].get("profiles"), list):
        raise CampaignError("qualification_catalog_invalid")
    profiles = matches[0]["profiles"]
    if not all(isinstance(profile, dict) for profile in profiles):
        raise CampaignError("qualification_catalog_invalid")
    return profiles


def load_qualification_campaign_contract(
    *, repository_root: Path, stage_id: str
) -> QualificationCampaignContract:
    root = repository_root.resolve(strict=True)
    catalog = _json_object(root / "spec" / "catalog.json", "qualification_catalog_invalid")
    policy_entries = catalog.get("repeatability_qualification_policies")
    if not isinstance(policy_entries, list):
        raise CampaignError("qualification_catalog_invalid")
    selected_policy = [
        entry
        for entry in policy_entries
        if isinstance(entry, dict)
        and entry.get("policy_id") == QUALIFICATION_POLICY_ID
        and entry.get("version") == QUALIFICATION_POLICY_VERSION
    ]
    if len(selected_policy) != 1:
        raise CampaignError("qualification_policy_catalog_entry_invalid")
    policy_entry = selected_policy[0]
    policy_path = _repository_file(
        root, policy_entry.get("path"), "qualification_policy_path_invalid"
    )
    policy = _json_object(policy_path, "qualification_policy_invalid")
    policy_sha256 = _catalog_canonical_sha256(policy)
    if (
        policy_entry.get("canonical_sha256") != policy_sha256
        or policy_sha256 != QUALIFICATION_POLICY_SHA256
        or policy_entry.get("hash_strategy_id") != "canonical-json-sha256-v1"
        or policy.get("contract_version") != "aneb-repeatability-qualification-policy-v1"
        or policy.get("policy_id") != QUALIFICATION_POLICY_ID
        or policy.get("version") != QUALIFICATION_POLICY_VERSION
        or policy.get("decision_id") != "D-110"
        or policy.get("status") != "approved"
    ):
        raise CampaignError("qualification_policy_identity_invalid")

    stages = policy.get("stages")
    common = policy.get("common")
    policy_families = policy.get("families")
    if not isinstance(stages, dict) or not isinstance(common, dict) or not isinstance(policy_families, dict):
        raise CampaignError("qualification_policy_shape_invalid")
    stage_order_value = stages.get("order")
    stage_definitions = stages.get("definitions")
    if stage_order_value != ["Q1_WIFI", "Q2_CELLULAR"] or not isinstance(stage_definitions, dict):
        raise CampaignError("qualification_stage_order_invalid")
    if stage_id not in ("Q1_WIFI", "Q2_CELLULAR"):
        raise CampaignError("qualification_stage_invalid")
    stage_definition = stage_definitions.get(stage_id)
    if not isinstance(stage_definition, dict):
        raise CampaignError("qualification_stage_invalid")
    transport = stage_definition.get("transport")
    runs_per_family = stage_definition.get("runs_per_family")
    if transport not in ("wifi", "cellular") or type(runs_per_family) is not int:
        raise CampaignError("qualification_stage_invalid")
    if runs_per_family != 10 or common.get("runs_per_family") != runs_per_family:
        raise CampaignError("qualification_run_count_invalid")
    if stages.get("transport_pooling") != "forbidden" or stages.get("q2_requires_q1_pass") is not True:
        raise CampaignError("qualification_stage_isolation_invalid")

    token_policy = policy_families.get("token_simulation")
    if not isinstance(token_policy, dict) or not isinstance(token_policy.get("batches"), list):
        raise CampaignError("qualification_token_batches_invalid")
    token_batches: list[tuple[str, int]] = []
    for batch in token_policy["batches"]:
        if (
            not isinstance(batch, dict)
            or batch.get("batch_id") not in ("A", "B")
            or type(batch.get("runs")) is not int
            or batch["runs"] <= 0
        ):
            raise CampaignError("qualification_token_batches_invalid")
        token_batches.append((batch["batch_id"], batch["runs"]))
    if token_batches != [("A", 5), ("B", 5)] or sum(count for _, count in token_batches) != runs_per_family:
        raise CampaignError("qualification_token_batches_invalid")

    catalog_profiles = _published_profile_catalog(catalog)
    bindings: list[QualificationProfileBinding] = []
    for family, policy_family in QUALIFICATION_FAMILIES:
        family_policy = policy_families.get(policy_family)
        if not isinstance(family_policy, dict):
            raise CampaignError("qualification_family_policy_invalid")
        profile_id = family_policy.get("qualification_profile_id")
        profile_version = family_policy.get("qualification_profile_version")
        if not isinstance(profile_id, str) or not isinstance(profile_version, str):
            raise CampaignError("qualification_family_profile_identity_invalid")
        entries = [
            entry
            for entry in catalog_profiles
            if entry.get("profile_id") == profile_id and entry.get("version") == profile_version
        ]
        if len(entries) != 1 or entries[0].get("qualification_policy_ref") != QUALIFICATION_POLICY_ID:
            raise CampaignError("qualification_profile_catalog_entry_invalid")
        entry = entries[0]
        profile_path = _repository_file(root, entry.get("path"), "qualification_profile_path_invalid")
        runtime_path = _repository_file(
            root, entry.get("runtime_plan_path"), "qualification_runtime_path_invalid"
        )
        manifest_path = _repository_file(
            root, entry.get("manifest_path"), "qualification_manifest_path_invalid"
        )
        if profile_path.parent != runtime_path.parent or profile_path.parent != manifest_path.parent:
            raise CampaignError("qualification_bundle_path_mismatch")
        hashes = _manifest_hashes(manifest_path)
        profile = _json_object(profile_path, "qualification_profile_invalid")
        runtime_plan = _json_object(runtime_path, "qualification_runtime_invalid")
        profile_sha256 = _catalog_canonical_sha256(profile)
        runtime_plan_sha256 = _catalog_canonical_sha256(runtime_plan)
        if hashes != {"profile.json": profile_sha256, "runtime_plan.json": runtime_plan_sha256}:
            raise CampaignError("qualification_bundle_hash_mismatch")
        execution_plan = profile.get("execution_plan")
        profile_qualification = profile.get("qualification")
        runtime_qualification = runtime_plan.get("qualification")
        if (
            profile.get("contract_version") != "aneb-profile-v2"
            or profile.get("profile_id") != profile_id
            or profile.get("version") != profile_version
            or profile.get("mode_id") != policy_family
            or profile.get("evidence_tier") != "repeatability_qualification"
            or not isinstance(execution_plan, dict)
            or execution_plan.get("artifact") != "runtime_plan.json"
            or execution_plan.get("artifact_hash") != f"sha256:{runtime_plan_sha256}"
            or execution_plan.get("variant") != "repeatability_qualification"
            or not isinstance(profile_qualification, dict)
            or not isinstance(runtime_qualification, dict)
        ):
            raise CampaignError("qualification_profile_binding_invalid")
        for qualification in (profile_qualification, runtime_qualification):
            if (
                qualification.get("policy_id") != QUALIFICATION_POLICY_ID
                or qualification.get("policy_version") != QUALIFICATION_POLICY_VERSION
                or qualification.get("decision_id") != "D-110"
                or qualification.get("policy_sha256") != policy_sha256
                or qualification.get("stage_order") != ["Q1_WIFI", "Q2_CELLULAR"]
                or qualification.get("transport_pooling") != "forbidden"
                or qualification.get("q2_requires_q1_pass") is not True
                or qualification.get("runs_per_family") != runs_per_family
            ):
                raise CampaignError("qualification_profile_policy_mismatch")
        if runtime_plan.get("variant") != "repeatability_qualification":
            raise CampaignError("qualification_runtime_variant_invalid")
        bindings.append(
            QualificationProfileBinding(
                family=family,
                policy_family=policy_family,
                profile_id=profile_id,
                profile_version=profile_version,
                profile_sha256=profile_sha256,
                runtime_plan_sha256=runtime_plan_sha256,
                execution_variant="repeatability_qualification",
            )
        )

    return QualificationCampaignContract(
        policy_id=QUALIFICATION_POLICY_ID,
        policy_version=QUALIFICATION_POLICY_VERSION,
        policy_sha256=policy_sha256,
        stage_id=stage_id,
        stage_order=tuple(stage_order_value),
        transport=transport,
        runs_per_family=runs_per_family,
        transport_pooling_allowed=False,
        q2_requires_q1_pass=True,
        token_batches=tuple(token_batches),
        profile_bindings=tuple(bindings),
    )


def build_campaign_plan(
    *, contract: QualificationCampaignContract
) -> list[QualificationCampaignRun]:
    if not isinstance(contract, QualificationCampaignContract):
        raise CampaignError("qualification_campaign_contract_invalid")
    token_batch_ids = [
        batch_id
        for batch_id, count in contract.token_batches
        for _ in range(count)
    ]
    if len(token_batch_ids) != contract.runs_per_family:
        raise CampaignError("qualification_token_batches_invalid")
    return [
        QualificationCampaignRun(
            stage_id=contract.stage_id,
            transport=contract.transport,
            policy_id=contract.policy_id,
            policy_version=contract.policy_version,
            policy_sha256=contract.policy_sha256,
            family=binding.family,
            ordinal=ordinal,
            batch_id=token_batch_ids[ordinal - 1] if binding.family == "token" else None,
            profile_id=binding.profile_id,
            profile_version=binding.profile_version,
            profile_sha256=binding.profile_sha256,
            runtime_plan_sha256=binding.runtime_plan_sha256,
            execution_variant=binding.execution_variant,
            q1_pass_required=contract.stage_id == "Q2_CELLULAR" and contract.q2_requires_q1_pass,
        )
        for binding in contract.profile_bindings
        for ordinal in range(1, contract.runs_per_family + 1)
    ]


def _verify_q1_prerequisite_reports(
    *,
    repository_root: Path,
    contract: QualificationCampaignContract,
    paths: tuple[Path, ...],
) -> tuple[QualificationPrerequisiteReport, ...]:
    try:
        policy, policy_sha256, validator = repeatability._load_qualification_policy(
            repository_root
        )
    except repeatability.CohortError as error:
        raise CampaignError("q2_prerequisite_policy_invalid") from error
    if (
        policy_sha256 != contract.policy_sha256
        or policy.get("policy_id") != contract.policy_id
        or policy.get("version") != contract.policy_version
        or policy.get("decision_id") != "D-110"
        or policy.get("claim_scope") != "application_end_to_end_to_probe_node"
    ):
        raise CampaignError("q2_prerequisite_policy_invalid")

    binding_by_policy_family = {
        binding.policy_family: binding for binding in contract.profile_bindings
    }
    verified_by_family: dict[str, QualificationPrerequisiteReport] = {}
    for path in paths:
        try:
            source_path = path.resolve(strict=True)
        except OSError as error:
            raise CampaignError("q2_prerequisite_report_invalid") from error
        raw, report = _strict_json_object_with_bytes(
            source_path, "q2_prerequisite_report_invalid"
        )
        if not validator.is_valid(report):
            raise CampaignError("q2_prerequisite_report_invalid")

        report_policy = report.get("policy")
        cohort = report.get("cohort")
        prerequisite_gate = report.get("prerequisite_gate")
        repeatability_gate = report.get("repeatability_gate")
        radio_integrity = report.get("radio_integrity")
        if (
            report.get("schema_version")
            != repeatability.QUALIFICATION_SCHEMA_VERSION
            or report.get("contract_version")
            != repeatability.QUALIFICATION_SCHEMA_VERSION
            or not isinstance(report_policy, dict)
            or report_policy.get("policy_id") != contract.policy_id
            or report_policy.get("version") != contract.policy_version
            or report_policy.get("decision_id") != "D-110"
            or report_policy.get("canonical_sha256") != contract.policy_sha256
            or report_policy.get("stage_id") != "Q1_WIFI"
            or not isinstance(cohort, dict)
            or not isinstance(cohort.get("identity"), dict)
            or prerequisite_gate
            != {"status": "not_required", "stage_id": None, "report_sha256": None}
            or not isinstance(repeatability_gate, dict)
            or not isinstance(radio_integrity, dict)
        ):
            raise CampaignError("q2_prerequisite_report_invalid")
        if (
            report.get("status") != "repeatability_passed"
            or repeatability_gate.get("status") != "pass"
            or radio_integrity.get("status") != "pass"
        ):
            raise CampaignError("q2_prerequisite_not_passed")

        identity = cohort["identity"]
        network_identity = identity.get("network")
        profile_identity = identity.get("profile")
        claim_identity = identity.get("claim")
        policy_family = identity.get("test_type")
        binding = binding_by_policy_family.get(policy_family)
        expected_profile_fingerprint = (
            None
            if binding is None
            else {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": f"sha256:{binding.profile_sha256}",
            }
        )
        expected_runtime_artifact_hash = (
            None
            if binding is None
            else {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": f"sha256:{binding.runtime_plan_sha256}",
            }
        )
        if (
            binding is None
            or not isinstance(network_identity, dict)
            or not isinstance(profile_identity, dict)
            or not isinstance(claim_identity, dict)
            or profile_identity.get("profile_id") != binding.profile_id
            or profile_identity.get("profile_version") != binding.profile_version
            or profile_identity.get("variant") != binding.execution_variant
            or profile_identity.get("profile_fingerprint")
            != expected_profile_fingerprint
            or profile_identity.get("runtime_artifact_hash")
            != expected_runtime_artifact_hash
            or claim_identity.get("scope") != policy.get("claim_scope")
        ):
            raise CampaignError("q2_prerequisite_report_invalid")
        if network_identity.get("active_transport") != "wifi":
            raise CampaignError("q2_prerequisite_transport_invalid")
        if binding.family in verified_by_family:
            raise CampaignError("q2_prerequisite_family_coverage_invalid")

        canonical_bytes = _catalog_canonical_bytes(report)
        verified_by_family[binding.family] = QualificationPrerequisiteReport(
            family=binding.family,
            policy_family=binding.policy_family,
            source_path=source_path,
            raw_sha256=_sha256_bytes(raw),
            canonical_sha256=_sha256_bytes(canonical_bytes),
            canonical_bytes=canonical_bytes,
        )

    expected_families = {family for family, _ in QUALIFICATION_FAMILIES}
    if set(verified_by_family) != expected_families:
        raise CampaignError("q2_prerequisite_family_coverage_invalid")
    return tuple(verified_by_family[family] for family, _ in QUALIFICATION_FAMILIES)


def prepare_qualification_campaign(
    *,
    repository_root: Path,
    stage_id: str,
    q1_prerequisite_report_paths: Sequence[Path],
) -> PreparedQualificationCampaign:
    contract = load_qualification_campaign_contract(
        repository_root=repository_root,
        stage_id=stage_id,
    )
    prerequisite_paths = tuple(q1_prerequisite_report_paths)
    if not all(isinstance(path, Path) for path in prerequisite_paths):
        raise CampaignError("q1_prerequisite_report_path_invalid")
    if contract.stage_id == "Q1_WIFI":
        if prerequisite_paths:
            raise CampaignError("q1_prerequisite_reports_not_allowed")
        prerequisites: tuple[QualificationPrerequisiteReport, ...] = ()
    else:
        if len(prerequisite_paths) != len(QUALIFICATION_FAMILIES):
            raise CampaignError("q2_prerequisite_reports_required")
        prerequisites = _verify_q1_prerequisite_reports(
            repository_root=repository_root,
            contract=contract,
            paths=prerequisite_paths,
        )
    return PreparedQualificationCampaign(
        contract=contract,
        plan=tuple(build_campaign_plan(contract=contract)),
        q1_prerequisites=prerequisites,
    )


def parse_token_terminal_markers(
    text: str, *, mode: Literal["positive", "negative"]
) -> TokenTerminal:
    if mode != "positive":
        raise CampaignError("repeatability_campaign_positive_only")
    starts = re.findall(r"TOKEN_V2_START run_id=([^\s]+)", text)
    if len(starts) != 1 or UUID7_RE.fullmatch(starts[0]) is None:
        raise CampaignError("token_marker_chain_invalid")
    run_id = starts[0]
    writes = re.findall(
        rf"TOKEN_V2_DB_WRITE run_id={re.escape(run_id)} ok=([^\s]+)", text
    )
    ends = re.findall(
        rf"TOKEN_V2_END run_id={re.escape(run_id)} status=([^\s]+)", text
    )
    all_ends = re.findall(r"TOKEN_V2_END run_id=([^\s]+) status=([^\s]+)", text)
    if writes != ["true"] or ends != ["completed"] or all_ends != [(run_id, "completed")]:
        raise CampaignError("token_marker_chain_invalid")
    return TokenTerminal(run_id=run_id, terminal_status="completed")


def _load_phone_guard(path: Path):
    path = path.resolve(strict=True)
    spec = importlib.util.spec_from_file_location("aneb_external_phone_guard_rev4", path)
    if spec is None or spec.loader is None:
        raise CampaignError("phone_guard_module_invalid")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    if getattr(module, "PHONE_PARSER_CONTRACT_REVISION", None) != 4:
        raise CampaignError("phone_guard_revision_invalid")
    return module


def _run_raw(arguments: Sequence[str], *, timeout: int, stdin: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            list(arguments),
            input=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CampaignError("local_process_failed") from error


def _adb_raw(adb: Path, serial: str, tail: Sequence[str], *, timeout: int = 120, stdin: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    return _run_raw((str(adb), "-s", serial, *tail), timeout=timeout, stdin=stdin)


def _require_success(result: subprocess.CompletedProcess[bytes], code: str) -> bytes:
    if result.returncode != 0 or result.stderr:
        raise CampaignError(f"{code} rc={result.returncode}")
    return result.stdout


def _remote_package_file(adb: Path, serial: str, relative: str) -> bytes:
    if relative not in ROOM_FILES:
        raise CampaignError("private_file_not_allowlisted")
    result = _adb_raw(
        adb,
        serial,
        ("exec-out", "run-as", PACKAGE_NAME, "cat", f"/data/user/0/{PACKAGE_NAME}/{relative}"),
        timeout=120,
    )
    return _require_success(result, "private_file_read_failed")


def _remote_sha(adb: Path, serial: str, path: str, *, run_as: bool) -> str:
    tail = ("shell", "run-as", PACKAGE_NAME, "sha256sum", path) if run_as else ("shell", "sha256sum", path)
    output = _require_success(_adb_raw(adb, serial, tail), "remote_sha_failed").decode("utf-8", "strict").strip()
    match = re.fullmatch(r"([0-9a-f]{64})\s+\S+", output)
    if match is None:
        raise CampaignError("remote_sha_invalid")
    return match.group(1)


def _installed_apk_path(adb: Path, serial: str) -> str:
    output = _require_success(
        _adb_raw(adb, serial, ("shell", "pm", "path", PACKAGE_NAME)),
        "installed_apk_path_failed",
    ).decode("utf-8", "strict").strip()
    match = re.fullmatch(r"package:(/\S+/base\.apk)", output)
    if match is None:
        raise CampaignError("installed_apk_path_invalid")
    return match.group(1)


def _backup_installed_state(adb: Path, serial: str, output: Path) -> dict[str, object]:
    output.mkdir(mode=0o700)
    apk_path = _installed_apk_path(adb, serial)
    before = _remote_sha(adb, serial, apk_path, run_as=False)
    apk = _require_success(
        _adb_raw(adb, serial, ("exec-out", "cat", apk_path), timeout=300),
        "installed_apk_read_failed",
    )
    after = _remote_sha(adb, serial, apk_path, run_as=False)
    if before != after or _sha256_bytes(apk) != before:
        raise CampaignError("installed_apk_changed_during_backup")
    _write_exclusive(output / "base.apk", apk)
    files: dict[str, dict[str, object]] = {}
    for relative in ROOM_FILES:
        absolute = f"/data/user/0/{PACKAGE_NAME}/{relative}"
        before_file = _remote_sha(adb, serial, absolute, run_as=True)
        payload = _remote_package_file(adb, serial, relative)
        after_file = _remote_sha(adb, serial, absolute, run_as=True)
        if before_file != after_file or _sha256_bytes(payload) != before_file:
            raise CampaignError("private_file_changed_during_backup")
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_exclusive(target, payload)
        files[relative] = {"sha256": before_file, "size_bytes": len(payload)}
    manifest = {"apk_sha256": before, "apk_size_bytes": len(apk), "files": files}
    _write_json(output / "backup-manifest.json", manifest)
    return manifest


def _restore_installed_state(adb: Path, serial: str, backup: Path, manifest: dict[str, object]) -> None:
    _require_success(_adb_raw(adb, serial, ("uninstall", PACKAGE_NAME), timeout=180), "candidate_uninstall_failed")
    install = _adb_raw(adb, serial, ("install", "--no-streaming", str(backup / "base.apk")), timeout=300)
    install_text = (install.stdout + install.stderr).decode("utf-8", "strict")
    if install.returncode != 0 or "Success" not in install_text.splitlines():
        raise CampaignError("original_apk_restore_failed")
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise CampaignError("backup_manifest_invalid")
    for relative, metadata in files.items():
        if relative not in ROOM_FILES or not isinstance(metadata, dict):
            raise CampaignError("backup_manifest_invalid")
        payload = (backup / relative).read_bytes()
        expected = metadata.get("sha256")
        if _sha256_bytes(payload) != expected:
            raise CampaignError("backup_payload_changed")
        parent = str(PurePosixPath(relative).parent)
        remote = f"/data/user/0/{PACKAGE_NAME}/{relative}"
        mkdir = f"/data/user/0/{PACKAGE_NAME}/{parent}"
        _require_success(
            _adb_raw(adb, serial, ("exec-in", "run-as", PACKAGE_NAME, "mkdir", "-p", mkdir)),
            "restore_mkdir_failed",
        )
        command = f"cat > {remote}"
        _require_success(
            _adb_raw(
                adb,
                serial,
                ("exec-in", "run-as", PACKAGE_NAME, "sh", "-c", command),
                stdin=payload,
            ),
            "restore_write_failed",
        )
        readback = _remote_package_file(adb, serial, relative)
        if _sha256_bytes(readback) != expected or len(readback) != len(payload):
            raise CampaignError("restore_digest_mismatch")
    restored_apk = _installed_apk_path(adb, serial)
    if _remote_sha(adb, serial, restored_apk, run_as=False) != manifest.get("apk_sha256"):
        raise CampaignError("restored_apk_digest_mismatch")


def _install_candidate(
    adb: Path,
    serial: str,
    apk: Path,
    *,
    permission_receipt_path: Path,
) -> None:
    _require_success(_adb_raw(adb, serial, ("uninstall", PACKAGE_NAME), timeout=180), "original_uninstall_failed")
    install = _adb_raw(adb, serial, ("install", "--no-streaming", str(apk)), timeout=300)
    text = (install.stdout + install.stderr).decode("utf-8", "strict")
    if install.returncode != 0 or "Success" not in text.splitlines():
        raise CampaignError("candidate_install_failed")
    for permission in radio_permissions():
        _require_success(
            _adb_raw(
                adb,
                serial,
                ("shell", "pm", "grant", "--user", "0", PACKAGE_NAME, permission),
            ),
            "radio_permission_grant_failed",
        )
    package_dump = _require_success(
        _adb_raw(adb, serial, ("shell", "dumpsys", "package", PACKAGE_NAME)),
        "radio_permission_dump_failed",
    ).decode("utf-8", "strict")
    record_radio_permission_preflight(package_dump, permission_receipt_path)


def _launch_arguments(
    run_spec: QualificationCampaignRun,
    *,
    serial: str,
    server_base: str,
    adb: Path,
) -> list[str]:
    if not isinstance(run_spec, QualificationCampaignRun):
        raise CampaignError("qualification_run_spec_invalid")
    test_modes = {
        "token": "token_simulation",
        "realtime": "ai_realtime_simulation",
        "network": "network_basic",
    }
    test_mode = test_modes.get(run_spec.family)
    if (
        test_mode is None
        or run_spec.execution_variant != "repeatability_qualification"
        or run_spec.policy_id != QUALIFICATION_POLICY_ID
        or run_spec.policy_version != QUALIFICATION_POLICY_VERSION
        or run_spec.policy_sha256 != QUALIFICATION_POLICY_SHA256
        or run_spec.stage_id not in ("Q1_WIFI", "Q2_CELLULAR")
        or run_spec.transport not in ("wifi", "cellular")
    ):
        raise CampaignError("qualification_run_spec_invalid")
    return [
        str(adb),
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        ACTIVITY_COMPONENT,
        "--es",
        "server",
        server_base,
        "--ez",
        "autorun",
        "true",
        "--es",
        "transport",
        run_spec.transport,
        "--es",
        "test_mode",
        test_mode,
        "--ez",
        "qualification_requested",
        "true",
        "--es",
        "qualification_stage_id",
        run_spec.stage_id,
        "--es",
        "qualification_policy_id",
        run_spec.policy_id,
        "--es",
        "qualification_policy_version",
        run_spec.policy_version,
        "--es",
        "qualification_policy_sha256",
        run_spec.policy_sha256,
        "--es",
        "qualification_profile_id",
        run_spec.profile_id,
        "--es",
        "qualification_profile_version",
        run_spec.profile_version,
        "--es",
        "qualification_profile_sha256",
        run_spec.profile_sha256,
        "--es",
        "qualification_runtime_plan_sha256",
        run_spec.runtime_plan_sha256,
    ]


def _pull_room_snapshot(adb: Path, serial: str, output: Path) -> dict[str, object]:
    output.mkdir(mode=0o700)
    files: dict[str, object] = {}
    for relative in ROOM_FILES[:3]:
        payload = _remote_package_file(adb, serial, relative)
        target = output / Path(relative).name
        _write_exclusive(target, payload)
        files[target.name] = {"sha256": _sha256_bytes(payload), "size_bytes": len(payload)}
    receipt = {"files": files}
    _write_json(output / "room-snapshot.json", receipt)
    return receipt


def _create_evidence_directory(parent: Path, *, stage_id: str) -> Path:
    parent = parent.resolve(strict=True)
    try:
        stage_slug = {
            "Q1_WIFI": "q1-wifi",
            "Q2_CELLULAR": "q2-cellular",
        }[stage_id]
    except KeyError as exc:
        raise CampaignError("qualification_stage_invalid") from exc
    name = (
        f"s3-m2-qualification-{stage_slug}-"
        + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        + "-"
        + uuid.uuid4().hex[:12]
    )
    path = parent / name
    path.mkdir(mode=0o700)
    return path


def _write_prepared_campaign_evidence(
    *, evidence: Path, prepared: PreparedQualificationCampaign
) -> None:
    _write_json(
        evidence / "qualification-contract.json",
        dataclasses.asdict(prepared.contract),
    )
    _write_json(
        evidence / "qualification-plan.json",
        {
            "engineering_validation_only": True,
            "formal_baseline_eligible": False,
            "run_count": len(prepared.plan),
            "runs": [dataclasses.asdict(run_spec) for run_spec in prepared.plan],
        },
    )
    prerequisite_manifest: list[dict[str, object]] = []
    for prerequisite in prepared.q1_prerequisites:
        if (
            _sha256_bytes(prerequisite.canonical_bytes)
            != prerequisite.canonical_sha256
        ):
            raise CampaignError("q1_prerequisite_canonical_sha256_mismatch")
        file_name = f"q1-prerequisite-{prerequisite.family}.json"
        _write_exclusive(evidence / file_name, prerequisite.canonical_bytes)
        prerequisite_manifest.append(
            {
                "canonical_sha256": prerequisite.canonical_sha256,
                "family": prerequisite.family,
                "file_name": file_name,
                "policy_family": prerequisite.policy_family,
                "raw_sha256": prerequisite.raw_sha256,
                "source_file_name": prerequisite.source_path.name,
            }
        )
    _write_json(
        evidence / "q1-prerequisite-manifest.json",
        {
            "reports": prerequisite_manifest,
            "required": prepared.contract.stage_id == "Q2_CELLULAR",
            "stage_id": prepared.contract.stage_id,
        },
    )


def run(args: argparse.Namespace) -> Path:
    prepared = prepare_qualification_campaign(
        repository_root=Path(__file__).resolve().parents[1],
        stage_id=args.qualification_stage,
        q1_prerequisite_report_paths=tuple(args.q1_prerequisite_report),
    )
    evidence = _create_evidence_directory(
        args.evidence_parent,
        stage_id=prepared.contract.stage_id,
    )
    _write_prepared_campaign_evidence(evidence=evidence, prepared=prepared)
    runner = mechanics.SubprocessRunner()
    provenance_report, identity = mechanics.verify_ci_candidate(
        runner=runner,
        python_path=args.python.resolve(strict=True),
        gh_path=args.gh.resolve(strict=True),
        candidate_directory=args.candidate.resolve(strict=True),
        source_commit=args.source_commit,
        report_output=evidence / "candidate-provenance.json",
        timeout_seconds=180,
        contract=network.CONTRACT,
    )
    del provenance_report
    phone_module = _load_phone_guard(args.phone_guard)
    phone_guard_sha = _sha256_bytes(args.phone_guard.resolve(strict=True).read_bytes())
    guard = phone_module.PhoneGuard(str(args.adb.resolve(strict=True)), args.serial, expected_stayon=7)
    lease = guard.acquire()
    _write_exclusive(
        evidence / "phone-preflight.json",
        _coerce_receipt_bytes(lease.preflight.to_canonical_json()),
    )
    ssh = mechanics.SshClient(
        runner=runner,
        executable=args.ssh.resolve(strict=True),
        remote=args.remote,
        ssh_key=args.ssh_key.resolve(strict=True),
        known_hosts=args.known_hosts.resolve(strict=True),
        timeout_seconds=120,
    )
    remote_lock = mechanics.PersistentRemoteLock(ssh=ssh, ttl_seconds=args.lock_ttl_seconds)
    backup: dict[str, object] | None = None
    candidate_installed = False
    restored = False
    remote_before = None
    run_receipts: list[dict[str, object]] = []
    primary_error: BaseException | None = None
    try:
        lock_receipt = remote_lock.acquire()
        _write_json(evidence / "remote-lock-acquired.json", {"receipt": lock_receipt})
        remote_before = mechanics.capture_remote_snapshot(ssh=ssh, lock=remote_lock, stage="campaign_before")
        _write_json(evidence / "remote-before.json", dataclasses.asdict(remote_before))
        serverinfo = mechanics.fetch_serverinfo(
            server_base=args.server_base,
            ca_path=args.server_ca.resolve(strict=True),
            timeout_seconds=30,
            serverinfo_validator=network.validate_network_serverinfo,
        )
        _write_exclusive(evidence / "serverinfo-before.json", serverinfo.body)
        if remote_before.server_binary_sha256 != args.expected_server_binary_sha256:
            raise CampaignError("remote_binary_identity_mismatch")
        lease.claim_package_before_start(PACKAGE_NAME)
        backup = _backup_installed_state(args.adb, args.serial, evidence / "preinstalled-backup")
        _install_candidate(
            args.adb,
            args.serial,
            args.candidate / identity.apk_file_name,
            permission_receipt_path=evidence / "radio-permissions.json",
        )
        candidate_installed = True
        mechanics.verify_or_install_candidate(
            mechanics.AdbClient(
                runner=runner,
                executable=args.adb.resolve(strict=True),
                serial=args.serial,
                timeout_seconds=120,
            ),
            candidate_directory=args.candidate.resolve(strict=True),
            identity=identity,
            evidence_directory=evidence,
            install=False,
            contract=network.CONTRACT,
        )
        for run_spec in prepared.plan:
            family = run_spec.family
            ordinal = run_spec.ordinal
            remote_lock.assert_healthy(f"before_{family}_{ordinal}")
            _require_success(
                _adb_raw(args.adb, args.serial, ("shell", "am", "force-stop", "--user", "0", PACKAGE_NAME)),
                "between_run_stop_failed",
            )
            _require_success(
                _adb_raw(args.adb, args.serial, ("shell", "input", "keyevent", "KEYCODE_HOME")),
                "between_run_home_failed",
            )
            time.sleep(1.0)
            run_dir = evidence / f"{family}-{ordinal:02d}"
            run_dir.mkdir(mode=0o700)
            parser = (
                parse_token_terminal_markers
                if family == "token"
                else mechanics.parse_realtime_terminal_markers
                if family == "realtime"
                else network.parse_network_terminal_markers
            )
            capture = mechanics.LogcatCapture(
                adb=mechanics.AdbClient(
                    runner=runner,
                    executable=args.adb.resolve(strict=True),
                    serial=args.serial,
                    timeout_seconds=120,
                ),
                output_path=run_dir / "logcat.txt",
                stderr_path=run_dir / "logcat.stderr.txt",
                terminal_parser=parser,
                terminal_timeout_code=f"{family}_terminal_timeout",
            )
            try:
                capture.start()
                launch = _run_raw(
                    _launch_arguments(
                        run_spec,
                        serial=args.serial,
                        server_base=args.server_base,
                        adb=args.adb,
                    ),
                    timeout=120,
                )
                launch_text = (launch.stdout + launch.stderr).decode("utf-8", "strict")
                _write_exclusive(run_dir / "launch.txt", launch_text.encode("utf-8"))
                if launch.returncode != 0 or re.search(r"(?m)^Status:\s*ok\s*$", launch_text) is None:
                    raise CampaignError("app_launch_not_ok")
                terminal = capture.wait_terminal(mode="positive", timeout_seconds=args.run_timeout_seconds)
            finally:
                capture.stop(allow_missing=True)
            receipt = {
                "family": family,
                "ordinal": ordinal,
                "qualification_run": dataclasses.asdict(run_spec),
                "terminal": dataclasses.asdict(terminal),
            }
            _write_json(run_dir / "terminal.json", receipt)
            run_receipts.append(receipt)
            print(f"RUN_COMPLETE family={family} ordinal={ordinal} run_id={terminal.run_id}", flush=True)
        _require_success(
            _adb_raw(args.adb, args.serial, ("shell", "am", "force-stop", "--user", "0", PACKAGE_NAME)),
            "final_candidate_stop_failed",
        )
        _pull_room_snapshot(args.adb, args.serial, evidence / "campaign-room")
        _write_json(
            evidence / "campaign-runs.json",
            {
                "engineering_validation_only": True,
                "formal_baseline_eligible": False,
                "phone_guard_revision": 4,
                "phone_guard_sha256": phone_guard_sha,
                "source_commit": args.source_commit,
                "runs": run_receipts,
            },
        )
    except BaseException as error:
        primary_error = error
    finally:
        cleanup_errors: list[str] = []
        if backup is not None:
            try:
                _restore_installed_state(args.adb, args.serial, evidence / "preinstalled-backup", backup)
                candidate_installed = False
                restored = True
            except BaseException as error:
                cleanup_errors.append(f"restore:{type(error).__name__}:{error}")
        elif candidate_installed:
            cleanup_errors.append("restore:backup_unavailable")
        phone_cleanup = None
        try:
            phone_cleanup = lease.cleanup_and_release()
            _write_exclusive(
                evidence / "phone-postflight.json",
                _canonical_bytes(phone_cleanup.to_dict()),
            )
        except BaseException as error:
            cleanup_errors.append(f"phone:{type(error).__name__}:{error}")
        if remote_lock.process is not None and remote_lock.process.poll() is None:
            try:
                remote_after = mechanics.capture_remote_snapshot(ssh=ssh, lock=remote_lock, stage="campaign_after")
                _write_json(evidence / "remote-after.json", dataclasses.asdict(remote_after))
                if remote_before is None:
                    raise CampaignError("remote_before_missing")
                mechanics.assert_remote_snapshot_stable(
                    remote_before,
                    remote_after,
                    expected_binary_sha256=args.expected_server_binary_sha256,
                )
                release = remote_lock.release()
                _write_json(evidence / "remote-lock-released.json", {"receipt": release})
            except BaseException as error:
                cleanup_errors.append(f"remote:{type(error).__name__}:{error}")
                try:
                    remote_lock.emergency_close()
                except BaseException as close_error:
                    cleanup_errors.append(f"remote_emergency:{type(close_error).__name__}:{close_error}")
        _write_json(
            evidence / "final-status.json",
            {
                "campaign_complete": primary_error is None
                and len(run_receipts) == len(prepared.plan),
                "cleanup_errors": cleanup_errors,
                "original_install_restored": restored,
                "primary_error": None if primary_error is None else f"{type(primary_error).__name__}:{primary_error}",
                "run_count": len(run_receipts),
            },
        )
        if cleanup_errors:
            raise CampaignError(";".join(cleanup_errors)) from primary_error
        if primary_error is not None:
            raise primary_error
    return evidence


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-base", required=True)
    parser.add_argument("--remote", required=True)
    parser.add_argument("--ssh-key", type=Path, required=True)
    parser.add_argument("--known-hosts", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--expected-server-binary-sha256", required=True)
    parser.add_argument("--phone-guard", type=Path, required=True)
    parser.add_argument("--evidence-parent", type=Path, required=True)
    parser.add_argument("--server-ca", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--ssh", type=Path, required=True)
    parser.add_argument("--python", type=Path, required=True)
    parser.add_argument("--gh", type=Path, required=True)
    parser.add_argument(
        "--qualification-stage",
        choices=("Q1_WIFI", "Q2_CELLULAR"),
        required=True,
    )
    parser.add_argument(
        "--q1-prerequisite-report",
        action="append",
        default=[],
        type=Path,
    )
    parser.add_argument("--run-timeout-seconds", type=int, default=900)
    parser.add_argument("--lock-ttl-seconds", type=int, default=7200)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        evidence = run(args)
    except BaseException as error:
        print(f"CAMPAIGN_FAILED {type(error).__name__}:{error}", file=sys.stderr, flush=True)
        return 1
    print(f"CAMPAIGN_COMPLETE evidence={evidence}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
