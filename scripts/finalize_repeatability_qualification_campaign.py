#!/usr/bin/env python3
"""Independently finalize one frozen S3/M2 qualification campaign.

The first public boundary loads only a completely cleaned campaign and restores
its three family cohorts from the exact prepared plan.  Export, analysis, and
atomic publication are separate later steps; this module never grants formal
baseline eligibility.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
from typing import Any, Callable, Literal
import uuid

if __package__:
    from scripts import run_repeatability_campaign as campaign
    from scripts import analyze_repeatability_cohort as repeatability
    from scripts import export_repeatability_cohort as exporter
else:
    import run_repeatability_campaign as campaign
    import analyze_repeatability_cohort as repeatability
    import export_repeatability_cohort as exporter


MAX_CONTROL_FILE_BYTES = 4 * 1024 * 1024
FAMILIES = ("token", "realtime", "network")
ROOM_FILES = ("aneb-probe.db", "aneb-probe.db-wal", "aneb-probe.db-shm")
TEST_TYPES = {
    "token": "token_simulation",
    "realtime": "ai_realtime_simulation",
    "network": "network_comprehensive",
}
CLI_ERROR_SCHEMA = "aneb-repeatability-finalizer-cli-error@1.0.0"
CLI_RESULT_SCHEMA = "aneb-repeatability-finalizer-cli-result@1.0.0"


class FinalizationError(RuntimeError):
    """Raised when captured campaign evidence cannot be consumed safely."""


@dataclasses.dataclass(frozen=True)
class CompletedQualificationCampaign:
    evidence_directory: Path
    stage_id: Literal["Q1_WIFI", "Q2_CELLULAR"]
    run_ids_by_family: dict[str, tuple[str, ...]]
    profile_bindings_by_family: dict[str, campaign.QualificationProfileBinding]
    formal_baseline_eligible: Literal[False] = False


@dataclasses.dataclass(frozen=True)
class FinalizedQualificationCampaign:
    output_directory: Path
    stage_id: Literal["Q1_WIFI", "Q2_CELLULAR"]
    report_sha256_by_family: dict[str, str]
    report_status_by_family: dict[
        str, Literal["repeatability_passed", "repeatability_failed"]
    ]
    qualification_passed: bool
    formal_baseline_eligible: Literal[False] = False


def _canonical_bytes(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise FinalizationError("finalization_json_invalid") from exc


def _write_exclusive(path: Path, value: bytes) -> None:
    try:
        descriptor = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0),
            0o600,
        )
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
    except OSError as exc:
        raise FinalizationError("finalization_write_failed") from exc


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _load_object(path: Path, code: str) -> dict[str, object]:
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
        if not path.is_file() or path.is_symlink():
            raise OSError("control_file_invalid")
        raw = path.read_bytes()
        if len(raw) > MAX_CONTROL_FILE_BYTES:
            raise ValueError("control_file_too_large")
        value = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=reject_duplicate_keys,
            parse_constant=reject_non_finite,
        )
    except (OSError, UnicodeDecodeError, ValueError, json.JSONDecodeError) as exc:
        raise FinalizationError(code) from exc
    if not isinstance(value, dict):
        raise FinalizationError(code)
    return value


def _require_completed_final_status(
    document: dict[str, object], *, run_count: int
) -> None:
    if (
        document.get("campaign_complete") is not True
        or document.get("cleanup_errors") != []
        or document.get("original_install_restored") is not True
        or document.get("primary_error") is not None
        or document.get("run_count") != run_count
    ):
        raise FinalizationError("campaign_final_status_invalid")


def _validated_plan(
    contract: dict[str, object], plan_document: dict[str, object]
) -> tuple[
    str,
    list[dict[str, object]],
    dict[str, campaign.QualificationProfileBinding],
]:
    stage_id = contract.get("stage_id")
    transport = contract.get("transport")
    runs_per_family = contract.get("runs_per_family")
    profile_bindings = contract.get("profile_bindings")
    if (
        stage_id not in ("Q1_WIFI", "Q2_CELLULAR")
        or transport not in ("wifi", "cellular")
        or (stage_id == "Q1_WIFI" and transport != "wifi")
        or (stage_id == "Q2_CELLULAR" and transport != "cellular")
        or runs_per_family != 10
        or contract.get("transport_pooling_allowed") is not False
        or not isinstance(profile_bindings, list)
        or len(profile_bindings) != len(FAMILIES)
    ):
        raise FinalizationError("qualification_contract_invalid")
    bindings_by_family = {
        item.get("family"): item
        for item in profile_bindings
        if isinstance(item, dict) and item.get("family") in FAMILIES
    }
    if tuple(bindings_by_family) != FAMILIES:
        raise FinalizationError("qualification_contract_invalid")
    try:
        profile_bindings = {
            family: campaign.QualificationProfileBinding(
                **bindings_by_family[family]
            )
            for family in FAMILIES
        }
    except (KeyError, TypeError) as exc:
        raise FinalizationError("qualification_contract_invalid") from exc

    runs = plan_document.get("runs")
    expected_count = runs_per_family * len(FAMILIES)
    if (
        plan_document.get("engineering_validation_only") is not True
        or plan_document.get("formal_baseline_eligible") is not False
        or plan_document.get("run_count") != expected_count
        or not isinstance(runs, list)
        or len(runs) != expected_count
    ):
        raise FinalizationError("qualification_plan_invalid")

    validated: list[dict[str, object]] = []
    index = 0
    for family in FAMILIES:
        binding = bindings_by_family[family]
        for ordinal in range(1, runs_per_family + 1):
            run = runs[index]
            index += 1
            if (
                not isinstance(run, dict)
                or run.get("stage_id") != stage_id
                or run.get("transport") != transport
                or run.get("policy_id") != contract.get("policy_id")
                or run.get("policy_version") != contract.get("policy_version")
                or run.get("policy_sha256") != contract.get("policy_sha256")
                or run.get("family") != family
                or run.get("ordinal") != ordinal
                or run.get("profile_id") != binding.get("profile_id")
                or run.get("profile_version") != binding.get("profile_version")
                or run.get("profile_sha256") != binding.get("profile_sha256")
                or run.get("runtime_plan_sha256")
                != binding.get("runtime_plan_sha256")
                or run.get("execution_variant") != "repeatability_qualification"
            ):
                raise FinalizationError("qualification_plan_invalid")
            validated.append(run)
    return stage_id, validated, profile_bindings


def load_completed_campaign(evidence_directory: Path) -> CompletedQualificationCampaign:
    """Load exact completed run IDs without exporting, analyzing, or pooling them."""

    try:
        evidence = evidence_directory.resolve(strict=True)
    except OSError as exc:
        raise FinalizationError("campaign_evidence_directory_invalid") from exc
    if not evidence.is_dir() or evidence.is_symlink():
        raise FinalizationError("campaign_evidence_directory_invalid")

    contract = _load_object(
        evidence / "qualification-contract.json",
        "qualification_contract_invalid",
    )
    plan_document = _load_object(
        evidence / "qualification-plan.json",
        "qualification_plan_invalid",
    )
    stage_id, plan, profile_bindings = _validated_plan(contract, plan_document)
    _require_completed_final_status(
        _load_object(
            evidence / "final-status.json",
            "campaign_final_status_invalid",
        ),
        run_count=len(plan),
    )
    campaign_runs = _load_object(
        evidence / "campaign-runs.json",
        "campaign_runs_invalid",
    )
    receipts = campaign_runs.get("runs")
    if (
        campaign_runs.get("engineering_validation_only") is not True
        or campaign_runs.get("formal_baseline_eligible") is not False
        or not isinstance(receipts, list)
        or len(receipts) != len(plan)
    ):
        raise FinalizationError("campaign_runs_invalid")

    run_ids_by_family: dict[str, list[str]] = {family: [] for family in FAMILIES}
    seen_run_ids: set[str] = set()
    for run_spec, receipt in zip(plan, receipts, strict=True):
        if (
            not isinstance(receipt, dict)
            or receipt.get("qualification_run") != run_spec
            or receipt.get("family") != run_spec["family"]
            or receipt.get("ordinal") != run_spec["ordinal"]
        ):
            raise FinalizationError("campaign_run_binding_mismatch")
        terminal = receipt.get("terminal")
        run_id = terminal.get("run_id") if isinstance(terminal, dict) else None
        if (
            not isinstance(run_id, str)
            or campaign.UUID7_RE.fullmatch(run_id) is None
            or run_id in seen_run_ids
            or terminal.get("terminal_status") != "completed"
        ):
            raise FinalizationError("campaign_terminal_invalid")
        seen_run_ids.add(run_id)
        run_ids_by_family[run_spec["family"]].append(run_id)

    return CompletedQualificationCampaign(
        evidence_directory=evidence,
        stage_id=stage_id,
        run_ids_by_family={
            family: tuple(run_ids_by_family[family]) for family in FAMILIES
        },
        profile_bindings_by_family=profile_bindings,
    )


def _validated_room_database(evidence: Path) -> Path:
    room_path = evidence / "campaign-room"
    if not room_path.is_dir() or room_path.is_symlink():
        raise FinalizationError("campaign_room_snapshot_invalid")
    receipt = _load_object(
        room_path / "room-snapshot.json",
        "campaign_room_snapshot_invalid",
    )
    files = receipt.get("files")
    if not isinstance(files, dict) or set(files) != set(ROOM_FILES):
        raise FinalizationError("campaign_room_snapshot_invalid")
    for name in ROOM_FILES:
        metadata = files[name]
        source = room_path / name
        if (
            not isinstance(metadata, dict)
            or set(metadata) != {"sha256", "size_bytes"}
            or not source.is_file()
            or source.is_symlink()
        ):
            raise FinalizationError("campaign_room_snapshot_invalid")
        try:
            payload = source.read_bytes()
        except OSError as exc:
            raise FinalizationError("campaign_room_snapshot_invalid") from exc
        if (
            metadata.get("sha256") != _sha256_bytes(payload)
            or metadata.get("size_bytes") != len(payload)
        ):
            raise FinalizationError("campaign_room_snapshot_mismatch")
    return room_path / ROOM_FILES[0]


def _qualification_prerequisites(
    completed: CompletedQualificationCampaign,
) -> dict[str, dict[str, object] | None]:
    manifest = _load_object(
        completed.evidence_directory / "q1-prerequisite-manifest.json",
        "q1_prerequisite_manifest_invalid",
    )
    reports = manifest.get("reports")
    if (
        set(manifest) != {"reports", "required", "stage_id"}
        or manifest.get("stage_id") != completed.stage_id
        or not isinstance(reports, list)
    ):
        raise FinalizationError("q1_prerequisite_manifest_invalid")
    if completed.stage_id == "Q1_WIFI":
        if manifest.get("required") is not False or reports != []:
            raise FinalizationError("q1_prerequisite_manifest_invalid")
        return {family: None for family in FAMILIES}
    if manifest.get("required") is not True or len(reports) != len(FAMILIES):
        raise FinalizationError("q1_prerequisite_manifest_invalid")

    result: dict[str, dict[str, object] | None] = {}
    for family, item in zip(FAMILIES, reports, strict=True):
        expected_name = f"q1-prerequisite-{family}.json"
        if (
            not isinstance(item, dict)
            or set(item)
            != {
                "canonical_sha256",
                "family",
                "file_name",
                "policy_family",
                "raw_sha256",
                "source_file_name",
            }
            or item.get("family") != family
            or item.get("policy_family") != TEST_TYPES[family]
            or item.get("file_name") != expected_name
        ):
            raise FinalizationError("q1_prerequisite_manifest_invalid")
        report_path = completed.evidence_directory / expected_name
        try:
            report_bytes = report_path.read_bytes()
        except OSError as exc:
            raise FinalizationError("q1_prerequisite_report_invalid") from exc
        if item.get("canonical_sha256") != _sha256_bytes(report_bytes):
            raise FinalizationError("q1_prerequisite_report_invalid")
        result[family] = _load_object(
            report_path,
            "q1_prerequisite_report_invalid",
        )
    return result


def _validated_report(
    report: dict[str, Any],
    *,
    family: str,
    stage_id: str,
    expected_run_ids: tuple[str, ...],
    profile_binding: campaign.QualificationProfileBinding,
) -> bytes:
    policy = report.get("policy")
    cohort = report.get("cohort")
    identity = cohort.get("identity") if isinstance(cohort, dict) else None
    profile = identity.get("profile") if isinstance(identity, dict) else None
    expected_profile_fingerprint = {
        "algorithm": "sha256",
        "canonicalization": "canonical-json-v1",
        "value": f"sha256:{profile_binding.profile_sha256}",
    }
    expected_runtime_artifact_hash = {
        "algorithm": "sha256",
        "canonicalization": "canonical-json-v1",
        "value": f"sha256:{profile_binding.runtime_plan_sha256}",
    }
    if (
        report.get("schema_version")
        != repeatability.QUALIFICATION_SCHEMA_VERSION
        or report.get("status")
        not in {"repeatability_passed", "repeatability_failed"}
        or report.get("formal_baseline_eligible") is not False
        or not isinstance(policy, dict)
        or policy.get("stage_id") != stage_id
        or not isinstance(identity, dict)
        or identity.get("test_type") != TEST_TYPES[family]
        or cohort.get("run_ids") != list(expected_run_ids)
        or not isinstance(profile, dict)
        or profile.get("profile_id") != profile_binding.profile_id
        or profile.get("profile_version") != profile_binding.profile_version
        or profile.get("variant") != profile_binding.execution_variant
        or profile.get("profile_fingerprint") != expected_profile_fingerprint
        or profile.get("runtime_artifact_hash")
        != expected_runtime_artifact_hash
    ):
        raise FinalizationError("qualification_report_binding_invalid")
    return _canonical_bytes(report)


def finalize_completed_campaign(
    evidence_directory: Path,
    output_directory: Path,
    *,
    repository_root: Path,
    _export_cohort: Callable[..., dict[str, Any]] = exporter.export_cohort,
    _analyze_qualification: Callable[..., dict[str, Any]] = (
        repeatability.analyze_qualification
    ),
) -> FinalizedQualificationCampaign:
    """Export and analyze three frozen families, then publish one atomic result."""

    completed = load_completed_campaign(evidence_directory)
    database = _validated_room_database(completed.evidence_directory)
    prerequisites = _qualification_prerequisites(completed)
    try:
        root = repository_root.resolve(strict=True)
        destination_parent = output_directory.parent.resolve(strict=True)
    except OSError as exc:
        raise FinalizationError("finalization_path_invalid") from exc
    destination = destination_parent / output_directory.name
    if (
        not root.is_dir()
        or root.is_symlink()
        or not destination_parent.is_dir()
        or destination_parent.is_symlink()
        or destination.exists()
        or destination.is_symlink()
    ):
        raise FinalizationError("finalization_path_invalid")

    staging = destination_parent / f".{destination.name}.{uuid.uuid4().hex}.partial"
    try:
        staging.mkdir(mode=0o700)
    except OSError as exc:
        raise FinalizationError("finalization_staging_failed") from exc

    reports: dict[str, str] = {}
    report_statuses: dict[
        str, Literal["repeatability_passed", "repeatability_failed"]
    ] = {}
    manifest_families: list[dict[str, object]] = []
    try:
        for family in FAMILIES:
            cohort_name = f"{family}-cohort.jsonl"
            report_name = f"{family}-qualification-report.json"
            cohort_path = staging / cohort_name
            try:
                export_receipt = _export_cohort(
                    database,
                    completed.run_ids_by_family[family],
                    cohort_path,
                    root=root,
                )
                documents = repeatability.load_jsonl((cohort_path,))
                report = _analyze_qualification(
                    documents,
                    root=root,
                    stage_id=completed.stage_id,
                    prerequisite_report=prerequisites[family],
                )
            except (OSError, RuntimeError, ValueError) as exc:
                raise FinalizationError(f"qualification_family_failed:{family}") from exc
            if (
                export_receipt.get("run_ids")
                != list(completed.run_ids_by_family[family])
                or export_receipt.get("run_count")
                != len(completed.run_ids_by_family[family])
                or export_receipt.get("results_reconstructed") is not False
                or export_receipt.get("scores_recomputed") is not False
            ):
                raise FinalizationError("qualification_export_receipt_invalid")
            report_bytes = _validated_report(
                report,
                family=family,
                stage_id=completed.stage_id,
                expected_run_ids=completed.run_ids_by_family[family],
                profile_binding=completed.profile_bindings_by_family[family],
            )
            _write_exclusive(staging / report_name, report_bytes)
            try:
                cohort_bytes = cohort_path.read_bytes()
            except OSError as exc:
                raise FinalizationError(
                    f"qualification_family_failed:{family}"
                ) from exc
            cohort_sha = _sha256_bytes(cohort_bytes)
            report_sha = _sha256_bytes(report_bytes)
            if export_receipt.get("output_sha256") != cohort_sha:
                raise FinalizationError("qualification_export_receipt_invalid")
            reports[family] = report_sha
            report_statuses[family] = report["status"]
            manifest_families.append(
                {
                    "cohort_file": cohort_name,
                    "cohort_sha256": cohort_sha,
                    "family": family,
                    "formal_baseline_eligible": False,
                    "report_file": report_name,
                    "report_sha256": report_sha,
                    "report_status": report["status"],
                    "run_count": len(completed.run_ids_by_family[family]),
                    "run_ids": list(completed.run_ids_by_family[family]),
                }
            )

        _validated_room_database(completed.evidence_directory)
        _write_exclusive(
            staging / "finalization-manifest.json",
            _canonical_bytes(
                {
                    "families": manifest_families,
                    "formal_baseline_eligible": False,
                    "schema_version": (
                        "aneb-repeatability-qualification-campaign-finalization-v1"
                    ),
                    "stage_id": completed.stage_id,
                }
            ),
        )
        try:
            staging.rename(destination)
        except OSError as exc:
            raise FinalizationError("finalization_publication_failed") from exc
    except BaseException:
        if staging.exists() and staging.is_dir() and not staging.is_symlink():
            shutil.rmtree(staging)
        raise

    return FinalizedQualificationCampaign(
        output_directory=destination,
        stage_id=completed.stage_id,
        report_sha256_by_family=reports,
        report_status_by_family=report_statuses,
        qualification_passed=all(
            report_statuses[family] == "repeatability_passed"
            for family in FAMILIES
        ),
    )


def _canonical_line(value: object) -> bytes:
    return _canonical_bytes(value) + b"\n"


def _emit_cli_error(reason_code: str) -> int:
    sys.stderr.buffer.write(
        _canonical_line(
            {
                "reason_code": reason_code,
                "schema": CLI_ERROR_SCHEMA,
            }
        )
    )
    return 2


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    if len(arguments) != 2:
        return _emit_cli_error("finalizer_usage_invalid")
    try:
        result = finalize_completed_campaign(
            Path(arguments[0]),
            Path(arguments[1]),
            repository_root=Path.cwd(),
        )
    except FinalizationError as exc:
        reason_code = str(exc).strip() or "finalization_failed"
        return _emit_cli_error(reason_code)
    sys.stdout.buffer.write(
        _canonical_line(
            {
                "formal_baseline_eligible": result.formal_baseline_eligible,
                "output_directory": str(result.output_directory),
                "report_sha256_by_family": result.report_sha256_by_family,
                "report_status_by_family": result.report_status_by_family,
                "schema": CLI_RESULT_SCHEMA,
                "stage_id": result.stage_id,
                "qualification_passed": result.qualification_passed,
            }
        )
    )
    return 0 if result.qualification_passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
