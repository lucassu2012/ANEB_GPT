#!/usr/bin/env python3
"""Fail-closed verifier for a frozen receipt-missing Token Quick Room result."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import tempfile
from typing import Any

import verify_token_quick_client_db as base


REPORT_SCHEMA = "aneb-token-quick-negative-client-db-report"
REPORT_VERSION = "1.0.0"
NEGATIVE_REASON = "receipt_missing"
MAX_INVENTORY_BYTES = 64 * 1024
MAX_DATABASE_FILE_BYTES = 512 * 1024 * 1024


def fail(reason_code: str) -> None:
    raise base.VerificationFailure(reason_code)


def require_exact_keys(value: dict[str, Any], expected: set[str], reason: str) -> None:
    if set(value) != expected:
        fail(reason)


def sha256_file(path: Path, reason: str) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
    except OSError:
        fail(reason)
    return digest.hexdigest()


def verify_inventory(database: Path, inventory_path: Path) -> dict[str, str]:
    try:
        raw = inventory_path.read_bytes()
    except OSError:
        fail("room_inventory_unreadable")
    if not raw or len(raw) > MAX_INVENTORY_BYTES:
        fail("room_inventory_size_invalid")
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        fail("room_inventory_invalid")
    inventory = base.require_dict(
        base.strict_json_text(text, "room_inventory_invalid"),
        "room_inventory_invalid",
    )
    require_exact_keys(
        inventory,
        {
            "schema",
            "schema_version",
            "captured_at_utc",
            "app_process_state",
            "files",
        },
        "room_inventory_fields_invalid",
    )
    if (
        inventory.get("schema") != "aneb-frozen-room-copy"
        or inventory.get("schema_version") != "1.0.0"
        or inventory.get("app_process_state") != "stopped_before_copy"
        or not isinstance(inventory.get("captured_at_utc"), str)
        or not inventory["captured_at_utc"].strip()
    ):
        fail("room_inventory_identity_invalid")

    expected_paths = {
        database.name: database,
        database.name + "-wal": Path(str(database) + "-wal"),
        database.name + "-shm": Path(str(database) + "-shm"),
    }
    entries = base.require_list(inventory.get("files"), "room_inventory_files_invalid")
    if len(entries) != 3:
        fail("room_inventory_files_invalid")
    entry_map: dict[str, dict[str, Any]] = {}
    for raw_entry in entries:
        entry = base.require_dict(raw_entry, "room_inventory_entry_invalid")
        name = entry.get("name")
        state = entry.get("state")
        if (
            not isinstance(name, str)
            or name in entry_map
            or name not in expected_paths
            or state not in {"present", "absent"}
        ):
            fail("room_inventory_entry_invalid")
        if state == "present":
            require_exact_keys(
                entry,
                {"name", "state", "bytes", "sha256"},
                "room_inventory_entry_invalid",
            )
            size = entry.get("bytes")
            digest = entry.get("sha256")
            if (
                not isinstance(size, int)
                or isinstance(size, bool)
                or not 0 < size <= MAX_DATABASE_FILE_BYTES
                or not isinstance(digest, str)
                or re.fullmatch(r"[0-9a-f]{64}", digest) is None
            ):
                fail("room_inventory_entry_invalid")
        else:
            require_exact_keys(
                entry,
                {"name", "state"},
                "room_inventory_entry_invalid",
            )
        entry_map[name] = entry
    if set(entry_map) != set(expected_paths):
        fail("room_inventory_files_invalid")
    if (
        entry_map[database.name]["state"] != "present"
        or entry_map[database.name + "-wal"]["state"]
        != entry_map[database.name + "-shm"]["state"]
    ):
        fail("room_inventory_sidecar_state_invalid")

    hashes: dict[str, str] = {}
    for name, source in expected_paths.items():
        entry = entry_map[name]
        if entry["state"] == "absent":
            if os.path.lexists(source):
                fail("room_frozen_absence_mismatch")
            continue
        try:
            stat = source.stat()
        except OSError:
            fail("room_frozen_file_missing")
        if source.is_symlink() or not source.is_file():
            fail("room_frozen_file_invalid")
        if stat.st_size != entry["bytes"]:
            fail("room_inventory_size_mismatch")
        digest = sha256_file(source, "room_frozen_file_unreadable")
        if digest != entry["sha256"]:
            fail("room_inventory_digest_mismatch")
        hashes[name] = digest
    return hashes


def copy_frozen_room(
    database: Path,
    source_hashes: dict[str, str],
    analysis_root: Path,
) -> Path:
    for name, digest in source_hashes.items():
        source = database.parent / name
        destination = analysis_root / name
        try:
            shutil.copyfile(source, destination)
        except OSError:
            fail("database_analysis_copy_failed")
        if sha256_file(destination, "database_analysis_copy_failed") != digest:
            fail("database_analysis_copy_digest_mismatch")
    return analysis_root / database.name


def select_exact_one(
    connection: sqlite3.Connection,
    query: str,
    run_id: str,
    reason: str,
) -> sqlite3.Row:
    rows = connection.execute(query, (run_id,)).fetchall()
    if len(rows) != 1:
        fail(reason)
    return rows[0]


def read_target_rows(
    database: Path,
    run_id: str,
) -> tuple[sqlite3.Row, sqlite3.Row, str]:
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")
        quick_check = connection.execute("PRAGMA quick_check").fetchall()
        if len(quick_check) != 1 or quick_check[0][0] != "ok":
            fail("database_integrity_failed")
        room_identity = base.verify_room_schema(connection)
        typed = select_exact_one(
            connection,
            """
            SELECT runId, startedAtEpochMs, serverBase, claimScope, profileId,
                   profileVersion, behaviorModelId, behaviorModelVersion,
                   behaviorModelHash, calibrationStatus, variant, scorePolicyId,
                   scoreAnchorPolicyId, conclusionPolicyId, totalScore, grade,
                   verdict, confidence, capReason, metricsJson, conclusionsJson,
                   evidenceJson
              FROM token_simulation_result WHERE runId = ?
            """,
            run_id,
            "typed_result_cardinality_invalid",
        )
        envelope = select_exact_one(
            connection,
            """
            SELECT runId, schemaVersion, testType, startedAtEpochMs,
                   serializedAtEpochMs, canonicalSha256, bodyJson
              FROM result_envelope WHERE runId = ?
            """,
            run_id,
            "result_envelope_cardinality_invalid",
        )
        forbidden_same_run_tables = (
            "test_run",
            "token_event",
            "scenario_result",
            "echo_sample",
            "report_body",
            "continuity_result",
            "ab_result",
            "basic_speed_result",
            "realtime_simulation_result",
            "network_comprehensive_result",
        )
        for table in forbidden_same_run_tables:
            count = connection.execute(
                f'SELECT COUNT(*) FROM "{table}" WHERE runId = ?',
                (run_id,),
            ).fetchone()[0]
            if count != 0:
                fail("unexpected_same_run_rows")
        return typed, envelope, room_identity
    except base.VerificationFailure:
        raise
    except sqlite3.Error:
        fail("database_query_failed")
    finally:
        if connection is not None:
            connection.close()


def verify_radio_context(body: dict[str, Any]) -> int | None:
    context = base.require_dict(body.get("context"), "context_missing")
    network = base.require_dict(context.get("network"), "network_context_missing")
    if network.get("evidence_ref_ids") != []:
        fail("network_evidence_refs_invalid")
    radio = base.require_dict(context.get("radio"), "radio_context_missing")
    samples = base.require_list(radio.get("samples"), "radio_samples_invalid")
    refs = base.require_list(radio.get("evidence_ref_ids"), "radio_evidence_refs_invalid")
    if radio.get("collection_status") == "collected":
        if not samples or radio.get("sample_count") != len(samples) or refs != ["radio-context"]:
            fail("radio_context_invalid")
        return len(samples)
    if samples or radio.get("sample_count") != 0 or refs != []:
        fail("radio_context_invalid")
    return None


def verify_evidence_refs(
    body: dict[str, Any],
    manifest_entries: dict[str, str],
    radio_sample_count: int | None,
) -> None:
    evidence = base.require_dict(body.get("evidence"), "evidence_missing")
    require_exact_keys(
        evidence,
        {"raw_evidence_retained", "invalid_evidence_retained", "refs", "environment_events"},
        "evidence_contract_mismatch",
    )
    if (
        evidence.get("raw_evidence_retained") is not True
        or evidence.get("invalid_evidence_retained") is not True
        or any(
            not isinstance(value, dict)
            for value in base.require_list(
                evidence.get("environment_events"), "environment_events_invalid"
            )
        )
    ):
        fail("evidence_contract_mismatch")
    refs = base.require_list(evidence.get("refs"), "evidence_refs_invalid")
    ref_map: dict[str, dict[str, Any]] = {}
    for value in refs:
        ref = base.require_dict(value, "evidence_ref_invalid")
        ref_id = ref.get("ref_id")
        if not isinstance(ref_id, str) or ref_id in ref_map:
            fail("evidence_ref_identity_invalid")
        ref_map[ref_id] = ref
    expected_ids = {"profile-artifact", "runtime-artifact", "token-raw"}
    if radio_sample_count is not None:
        expected_ids.add("radio-context")
    if set(ref_map) != expected_ids:
        fail("evidence_ref_set_mismatch")
    for ref_id, filename in (
        ("profile-artifact", "profile.json"),
        ("runtime-artifact", "runtime_plan.json"),
    ):
        ref = ref_map[ref_id]
        if (
            ref.get("kind") != "content_addressed_artifact"
            or ref.get("uri") != f"profiles/published/{base.PROFILE_ID}/{filename}"
            or ref.get("media_type") != "application/json"
            or ref.get("record_count") != 1
            or ref.get("redaction") != "none"
            or ref.get("digest")
            != {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": f"sha256:{manifest_entries[filename]}",
            }
        ):
            fail("artifact_evidence_ref_mismatch")
    token_ref = ref_map["token-raw"]
    if (
        token_ref.get("kind") != "inline_json_pointer"
        or token_ref.get("uri") != "#/category_payload/raw_evidence"
        or token_ref.get("media_type") != "application/json"
        or token_ref.get("digest") is not None
        or token_ref.get("record_count") != 0
        or token_ref.get("redaction") != "none"
    ):
        fail("token_evidence_ref_mismatch")
    if radio_sample_count is not None:
        radio_ref = ref_map["radio-context"]
        if (
            radio_ref.get("kind") != "inline_json_pointer"
            or radio_ref.get("uri") != "#/context/radio/samples"
            or radio_ref.get("media_type") != "application/json"
            or radio_ref.get("digest") is not None
            or radio_ref.get("record_count") != radio_sample_count
            or radio_ref.get("redaction") != "location_removed"
        ):
            fail("radio_evidence_ref_mismatch")


def verify_negative_semantics(
    typed: sqlite3.Row,
    envelope: sqlite3.Row,
    *,
    run_id: str,
    manifest_entries: dict[str, str],
    measurement_specs: dict[str, dict[str, object]],
    execution_contract: dict[str, object],
    analysis_root: Path,
    expected_server_base: str | None,
) -> tuple[dict[str, object], str]:
    result_text = envelope["bodyJson"]
    if not isinstance(result_text, str):
        fail("client_json_invalid")
    base.verify_result_schema(result_text, analysis_root)
    body = base.require_dict(
        base.strict_json_text(result_text, "client_json_invalid"),
        "result_body_invalid",
    )
    if (
        envelope["schemaVersion"] != "aneb-result-v2"
        or envelope["testType"] != "token_simulation"
        or body.get("schema_version") != "aneb-result-v2"
        or body.get("test_type") != "token_simulation"
    ):
        fail("result_envelope_identity_mismatch")
    if base.canonical_sha256(body) != envelope["canonicalSha256"]:
        fail("result_envelope_digest_mismatch")

    producer = base.require_dict(body.get("producer"), "producer_missing")
    if (
        producer.get("resolution_status") != "resolved"
        or producer.get("component") != "aneb-probe-android"
        or producer.get("component_version") != "0.5.12-codex"
        or producer.get("exporter_version") != "aneb-result-exporter-v2"
    ):
        fail("producer_identity_mismatch")
    run = base.require_dict(body.get("run"), "run_missing")
    if (
        run.get("run_id") != run_id
        or run.get("status") != "failed"
        or run.get("validity") != "invalid"
        or run.get("invalid_reason_codes") != [NEGATIVE_REASON]
        or run.get("source_record_version") != "room-v19-token-envelope-v1"
    ):
        fail("negative_run_identity_mismatch")
    run_uuid_ms = base.uuid7_unix_ms(run_id)
    started_at_ms = base.epoch_ms(run.get("started_at_epoch_ms"), "run_time_fields_invalid")
    ended_at_ms = base.epoch_ms(run.get("ended_at_epoch_ms"), "run_time_fields_invalid")
    duration_ms = base.epoch_ms(run.get("duration_ms"), "run_time_fields_invalid")
    serialized_at_ms = base.epoch_ms(
        producer.get("serialized_at_epoch_ms"), "run_time_fields_invalid"
    )
    run_start_delta_ms = started_at_ms - run_uuid_ms
    if not 0 <= run_start_delta_ms <= base.MAX_RUN_START_DELTA_MS:
        fail("run_time_identity_mismatch")
    if ended_at_ms < started_at_ms or duration_ms != ended_at_ms - started_at_ms:
        fail("run_duration_mismatch")
    if serialized_at_ms != ended_at_ms:
        fail("run_serialization_time_mismatch")

    profile_digest = f"sha256:{manifest_entries['profile.json']}"
    runtime_digest = f"sha256:{manifest_entries['runtime_plan.json']}"
    profile = base.require_dict(body.get("profile"), "profile_missing")
    profile_fingerprint = base.require_dict(
        profile.get("profile_fingerprint"), "profile_fingerprint_missing"
    )
    runtime_hash = base.require_dict(
        profile.get("runtime_artifact_hash"), "runtime_artifact_hash_missing"
    )
    if (
        profile.get("resolution_status") != "resolved"
        or profile.get("contract_version") != execution_contract["profile_contract_version"]
        or profile.get("profile_id") != base.PROFILE_ID
        or profile.get("profile_version") != base.PROFILE_VERSION
        or profile.get("variant") != "quick"
        or profile.get("runtime_artifact_status") != "resolved"
        or profile.get("source_uri")
        != "profiles/published/token_multimodal_quick/profile.json"
        or profile_fingerprint
        != {
            "algorithm": "sha256",
            "canonicalization": "canonical-json-v1",
            "value": profile_digest,
        }
        or runtime_hash
        != {
            "algorithm": "sha256",
            "canonicalization": "canonical-json-v1",
            "value": runtime_digest,
        }
        or profile.get("profile_evidence_ref_id") != "profile-artifact"
        or profile.get("runtime_artifact_evidence_ref_id") != "runtime-artifact"
    ):
        fail("profile_runtime_identity_mismatch")

    claim = base.require_dict(body.get("claim"), "claim_missing")
    context = base.require_dict(body.get("context"), "context_missing")
    endpoint = base.require_dict(context.get("endpoint"), "endpoint_context_missing")
    device = base.require_dict(context.get("device"), "device_context_missing")
    if endpoint.get("server_version") is not None:
        fail("client_server_version_must_remain_null")
    if (
        expected_server_base is not None
        and endpoint.get("server_base") != expected_server_base
    ):
        fail("client_server_base_mismatch")
    if (
        device.get("availability") != "observed"
        or device.get("app_package") != "com.aneb.probe.codex"
        or device.get("app_version_name") != "0.5.12-codex"
        or device.get("app_version_code") != 44
    ):
        fail("client_app_identity_mismatch")
    radio_sample_count = verify_radio_context(body)

    evaluation = base.require_dict(body.get("evaluation"), "evaluation_missing")
    algorithms = base.require_dict(
        evaluation.get("algorithm_versions"), "algorithm_versions_missing"
    )
    expected_algorithms = {
        "measurement_engine_version": "token-simulation-engine-v2",
        "metric_catalog_id": execution_contract["metric_catalog_id"],
        "target_set_id": execution_contract["target_set_id"],
        "score_policy_id": execution_contract["score_policy_id"],
        "score_anchor_policy_id": execution_contract["score_anchor_policy_id"],
        "conclusion_policy_id": execution_contract["conclusion_policy_id"],
        "calculation_origin": "measurement_engine",
        "finalized_at_epoch_ms": serialized_at_ms,
    }
    if algorithms != expected_algorithms:
        fail("algorithm_contract_mismatch")
    score = base.require_dict(evaluation.get("score"), "score_missing")
    expected_score = {
        "state": "suppressed_invalid",
        "value": None,
        "grade": None,
        "verdict": "invalid",
        "confidence": "invalid",
        "confidence_basis": {
            "method_id": "token-sample-coverage-v1",
            "coverage_ratio": None,
            "minimum_sample_satisfied": False,
        },
        "cap_reason": NEGATIVE_REASON,
        "not_computable_reason": NEGATIVE_REASON,
    }
    if score != expected_score:
        fail("negative_score_contract_mismatch")
    group_scores = base.require_dict(
        evaluation.get("group_scores"), "group_scores_missing"
    )
    if group_scores != {}:
        fail("network_or_group_score_present")
    metrics = base.require_dict(evaluation.get("metrics"), "metrics_missing")
    if set(metrics) != set(measurement_specs):
        fail("envelope_metric_set_mismatch")
    business_kpi_observations = 0
    network_scores = 0
    for metric_id, definition in measurement_specs.items():
        actual = base.require_dict(metrics[metric_id], "metric_invalid")
        base.assert_envelope_metric(
            metric_id,
            actual,
            definition,
            None,
            radio_sample_count=radio_sample_count,
        )
        if actual.get("domain") == "business" and (
            actual.get("state") == "observed" or actual.get("sample_count") != 0
        ):
            business_kpi_observations += 1
        if actual.get("domain") == "network" and actual.get("score") is not None:
            network_scores += 1
    if business_kpi_observations != 0:
        fail("business_kpi_observation_present")
    if network_scores != 0:
        fail("network_score_present")

    conclusions = base.require_list(
        evaluation.get("conclusions"), "conclusions_missing"
    )
    if len(conclusions) != 1:
        fail("negative_conclusion_set_mismatch")
    conclusion = base.require_dict(conclusions[0], "conclusion_invalid")
    if (
        conclusion.get("conclusion_id") != "token-invalid-evidence"
        or conclusion.get("severity") != "failure"
        or conclusion.get("policy_id") != execution_contract["conclusion_policy_id"]
        or conclusion.get("basis")
        != ["evidence:token-raw", f"invalid_reason:{NEGATIVE_REASON}"]
        or not isinstance(conclusion.get("text"), str)
        or not conclusion["text"].strip()
    ):
        fail("negative_conclusion_contract_mismatch")

    category = base.require_dict(body.get("category_payload"), "category_payload_missing")
    model = base.require_dict(category.get("behavior_model"), "behavior_model_missing")
    model_hash = base.require_dict(model.get("model_hash"), "behavior_model_hash_missing")
    raw = base.require_dict(category.get("raw_evidence"), "raw_evidence_missing")
    require_exact_keys(
        raw,
        {"invalid_reason", "rtt_samples_ms", "loaded_rtt_samples_ms", "tasks"},
        "raw_evidence_fields_mismatch",
    )
    if (
        category.get("evidence_contract_version") != "aneb-token-run-evidence-v1"
        or category.get("variant") != "quick"
        or raw.get("invalid_reason") != NEGATIVE_REASON
        or raw.get("rtt_samples_ms") != []
        or raw.get("loaded_rtt_samples_ms") != []
        or raw.get("tasks") != []
    ):
        fail("negative_raw_evidence_mismatch")
    if (
        model.get("resolution_status") != "resolved"
        or model.get("model_id") != execution_contract["model_id"]
        or model.get("model_version") != execution_contract["model_version"]
        or model.get("calibration_status") != execution_contract["calibration_status"]
        or model.get("source_kind") != execution_contract["source_kind"]
        or model_hash
        != {
            "algorithm": "sha256",
            "canonicalization": "canonical-json-v1",
            "value": execution_contract["model_hash"],
        }
        or claim.get("scope") != execution_contract["claim_scope"]
    ):
        fail("behavior_model_contract_mismatch")

    typed_metrics = base.require_dict(
        base.strict_json_text(typed["metricsJson"], "typed_metrics_json_invalid"),
        "typed_metrics_json_invalid",
    )
    typed_conclusions = base.require_list(
        base.strict_json_text(typed["conclusionsJson"], "typed_conclusions_json_invalid"),
        "typed_conclusions_json_invalid",
    )
    typed_evidence = base.require_dict(
        base.strict_json_text(typed["evidenceJson"], "typed_evidence_json_invalid"),
        "typed_evidence_json_invalid",
    )
    if typed_metrics != {}:
        fail("typed_business_metrics_present")
    if typed_conclusions != [conclusion["text"]]:
        fail("typed_conclusions_mismatch")
    if typed_evidence != {
        "contract_version": "aneb-token-run-evidence-v1",
        "variant": "quick",
        **raw,
    }:
        fail("typed_evidence_mismatch")
    if (
        typed["runId"] != run_id
        or envelope["runId"] != run_id
        or typed["startedAtEpochMs"] != started_at_ms
        or envelope["startedAtEpochMs"] != started_at_ms
        or envelope["serializedAtEpochMs"] != serialized_at_ms
        or typed["serverBase"] != endpoint.get("server_base")
        or typed["claimScope"] != claim.get("scope")
        or typed["profileId"] != base.PROFILE_ID
        or typed["profileVersion"] != base.PROFILE_VERSION
        or typed["variant"] != "quick"
        or typed["behaviorModelId"] != model.get("model_id")
        or typed["behaviorModelVersion"] != model.get("model_version")
        or typed["behaviorModelHash"] != model_hash.get("value")
        or typed["calibrationStatus"] != model.get("calibration_status")
        or typed["scorePolicyId"] != algorithms.get("score_policy_id")
        or typed["scoreAnchorPolicyId"] != algorithms.get("score_anchor_policy_id")
        or typed["conclusionPolicyId"] != algorithms.get("conclusion_policy_id")
    ):
        fail("typed_envelope_identity_mismatch")
    if (
        typed["totalScore"] is not None
        or typed["grade"] is not None
        or typed["verdict"] != "INVALID"
        or typed["confidence"] != "INVALID"
        or typed["capReason"] != NEGATIVE_REASON
    ):
        fail("typed_negative_score_mismatch")

    verify_evidence_refs(body, manifest_entries, radio_sample_count)
    return {
        "run_uuid_unix_ms": run_uuid_ms,
        "run_start_delta_ms": run_start_delta_ms,
        "started_at_epoch_ms": started_at_ms,
        "ended_at_epoch_ms": ended_at_ms,
        "serialized_at_epoch_ms": serialized_at_ms,
        "result_body_sha256": hashlib.sha256(result_text.encode("utf-8")).hexdigest(),
        "business_task_count": 0,
        "business_kpi_observation_count": business_kpi_observations,
        "business_artifact_count": 0,
        "network_score_count": network_scores,
        "profile_sha256": profile_digest,
        "runtime_plan_sha256": runtime_digest,
        "endpoint_server_base": endpoint.get("server_base"),
    }, result_text


def verify(
    database: Path,
    *,
    inventory: Path,
    run_id: str,
    manifest: Path,
    expected_server_base: str | None = None,
) -> tuple[dict[str, object], str]:
    if base.RUN_ID_RE.fullmatch(run_id) is None:
        fail("run_id_not_uuidv7")
    (
        manifest_entries,
        _task_specs,
        measurement_specs,
        execution_contract,
    ) = base.verify_published_assets(manifest)
    source_before = verify_inventory(database, inventory)
    pending_error: Exception | None = None
    semantic_report: dict[str, object] | None = None
    result_text: str | None = None
    room_identity: str | None = None
    with tempfile.TemporaryDirectory(prefix="aneb-negative-room-analysis-") as temporary:
        analysis_root = Path(temporary)
        try:
            analysis_database = copy_frozen_room(database, source_before, analysis_root)
            typed, envelope, room_identity = read_target_rows(analysis_database, run_id)
            semantic_report, result_text = verify_negative_semantics(
                typed,
                envelope,
                run_id=run_id,
                manifest_entries=manifest_entries,
                measurement_specs=measurement_specs,
                execution_contract=execution_contract,
                analysis_root=analysis_root,
                expected_server_base=expected_server_base,
            )
        except Exception as error:
            pending_error = error
        source_after = verify_inventory(database, inventory)
        if source_after != source_before:
            fail("frozen_source_modified_during_analysis")
        if pending_error is not None:
            raise pending_error
    if semantic_report is None or room_identity is None or result_text is None:
        fail("database_query_failed")
    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "run_id": run_id,
        "negative_reason_code": NEGATIVE_REASON,
        "room_user_version": base.EXPECTED_ROOM_VERSION,
        "room_identity_hash": room_identity,
        "frozen_source_sha256": source_before,
        "frozen_source_unchanged": True,
        "analysis_copy_used": True,
        "strict_result_schema": "pass",
        **semantic_report,
    }, result_text


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify one frozen Token Quick receipt-missing result without producing "
            "business or network scores"
        )
    )
    parser.add_argument("database", type=Path)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=base.PROFILE_DIR / "manifest.sha256",
    )
    parser.add_argument("--expected-server-base")
    parser.add_argument("--result-output", type=Path)
    args = parser.parse_args()
    try:
        if args.result_output is not None:
            base.assert_safe_result_output(
                args.result_output,
                database=args.database,
                manifest=args.manifest,
            )
        report, result_text = verify(
            args.database,
            inventory=args.inventory,
            run_id=args.run_id,
            manifest=args.manifest,
            expected_server_base=args.expected_server_base,
        )
        if args.result_output is not None:
            base.publish_result_output_atomic(args.result_output, result_text)
        exit_code = 0
    except base.VerificationFailure as error:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": error.reason_code,
            "run_id": args.run_id if base.RUN_ID_RE.fullmatch(args.run_id) else None,
        }
        exit_code = 1
    except Exception:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": "internal_verification_error",
            "run_id": args.run_id if base.RUN_ID_RE.fullmatch(args.run_id) else None,
        }
        exit_code = 1
    output = json.dumps(report, sort_keys=True, separators=(",", ":"), allow_nan=False)
    if len(output.encode("utf-8")) > 8191:
        output = json.dumps(
            {
                "schema": REPORT_SCHEMA,
                "schema_version": REPORT_VERSION,
                "status": "fail",
                "reason_code": "report_size_invalid",
                "run_id": args.run_id if base.RUN_ID_RE.fullmatch(args.run_id) else None,
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        exit_code = 1
    print(output)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
