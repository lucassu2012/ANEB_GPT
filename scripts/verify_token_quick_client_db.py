#!/usr/bin/env python3
"""Fail-closed verifier for one frozen Token Quick Room result."""

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
import uuid

from verify_result_jsonl import validate_jsonl


REPORT_SCHEMA = "aneb-token-quick-client-db-report"
REPORT_VERSION = "1.2.0"
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
PROFILE_ID = "token_multimodal_quick"
PROFILE_VERSION = "1.2.1"
EXPECTED_TASK_IDS = ("task-0006", "task-0010", "task-0016")
EXPECTED_TYPED_METRIC_IDS = frozenset(
    {
        "TOK-B01", "TOK-B02", "TOK-B03", "TOK-B04", "TOK-B05", "TOK-B07",
        "TOK-B09", "TOK-B10", "TOK-B11", "TOK-B14", "TOK-N03", "TOK-N04",
        "TOK-N05", "TOK-N06",
    }
)
EXPECTED_ENVELOPE_METRIC_IDS = frozenset(
    {f"TOK-B{index:02d}" for index in range(1, 16)}
    | {f"TOK-N{index:02d}" for index in range(1, 11)}
    | {"TOK-R01"}
)
EXPECTED_ROOM_VERSION = 19
MAX_DB_BYTES = 512 * 1024 * 1024
# TestEngine.newRunId() records the UUIDv7 millisecond before
# TokenSimulationEngine immediately records startedAtEpochMs. A long pause here
# is not credible evidence for the same fresh launch, so fail closed after 5s.
MAX_RUN_START_DELTA_MS = 5_000
ROOT = Path(__file__).resolve().parents[1]
PROFILE_DIR = ROOT / "profiles" / "published" / PROFILE_ID
PROFILE_ASSET_BASE = f"asset:///published/{PROFILE_ID}"
ROOM_SCHEMA_PATH = (
    ROOT
    / "app"
    / "probe"
    / "schemas"
    / "com.aneb.probe.data.AnebDatabase"
    / "19.json"
)
# SQLite preserves the physical order and DEFAULT clauses added by the explicit
# v15→v18 ALTER TABLE migrations. This is the only legacy CREATE TABLE form
# accepted in addition to Room's v19 fresh-install export.
NETWORK_COMPREHENSIVE_MIGRATED_COLUMN_ORDER = (
    "runId", "startedAtEpochMs", "serverBase", "claimScope", "profileId",
    "profileVersion", "variant", "scorePolicyId", "scoreAnchorPolicyId",
    "conclusionPolicyId", "status", "totalScore", "grade", "verdict",
    "confidence", "downloadMbps", "uploadMbps", "idleRttMs", "loadedRttMs",
    "latencyDeltaMs", "jitterMs", "requestLossRate", "throughputRobustCv",
    "udpNonReturnRate", "postLoadPingMs", "downloadBytes", "uploadBytes",
    "transferErrors", "metricsJson", "groupScoresJson", "conclusionsJson",
    "evidenceJson", "syntheticImpairment", "impairmentProfileId",
    "impairmentProfileVersion", "impairmentDownlinkMbps",
    "impairmentUplinkMbps", "impairmentAddedRttMs", "impairmentJitterMs",
    "impairmentExcludedCsv", "impairmentAcknowledged",
    "impairmentOutageDurationMs", "recoveryTimeMs", "recoveryFailureCount",
    "postRecoverySuccessRatio", "gatewayImpairment", "gatewayExperimentId",
    "gatewayProfileFingerprint", "gatewayManagementBase",
    "gatewayImpairmentLayer", "gatewayAcknowledged",
    "gatewayCleanupAcknowledged", "gatewayBypassObserved",
    "gatewayUplinkDelayMs", "gatewayDownlinkDelayMs",
    "gatewayUplinkLossPct", "gatewayDownlinkLossPct",
)
NETWORK_COMPREHENSIVE_MIGRATED_DEFAULTS = {
    "syntheticImpairment": "0",
    "impairmentExcludedCsv": "''",
    "impairmentAcknowledged": "0",
    "recoveryFailureCount": "0",
    "gatewayImpairment": "0",
    "gatewayAcknowledged": "0",
    "gatewayCleanupAcknowledged": "0",
    "gatewayBypassObserved": "0",
}


class VerificationFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class DuplicateJsonKey(ValueError):
    pass


def fail(reason_code: str) -> None:
    raise VerificationFailure(reason_code)


def uuid7_unix_ms(run_id: str) -> int:
    """Return the 48-bit Unix millisecond prefix of a canonical UUIDv7."""
    if RUN_ID_RE.fullmatch(run_id) is None:
        fail("run_id_not_uuidv7")
    return int(run_id.replace("-", "")[:12], 16)


def epoch_ms(value: object, reason: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        fail(reason)
    return value


def _object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(key)
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise ValueError(value)


def strict_json_text(text: str, reason: str) -> Any:
    try:
        return json.loads(
            text,
            object_pairs_hook=_object_without_duplicates,
            parse_constant=_reject_non_finite,
        )
    except (TypeError, UnicodeError, json.JSONDecodeError, ValueError):
        fail(reason)


def strict_json_file(path: Path, reason: str) -> Any:
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (OSError, UnicodeError):
        fail(reason)
    return strict_json_text(text, reason)


def canonical_sha256(value: object) -> str:
    try:
        payload = json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeError):
        fail("canonical_json_invalid")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def read_manifest(path: Path) -> dict[str, str]:
    try:
        raw = path.read_bytes()
    except OSError:
        fail("manifest_unreadable")
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError:
        fail("manifest_not_ascii")
    text = text.replace("\r\n", "\n")
    if "\r" in text or not text.endswith("\n"):
        fail("manifest_format_invalid")
    entries: dict[str, str] = {}
    for line in text[:-1].split("\n"):
        match = re.fullmatch(r"([0-9a-f]{64})  (profile\.json|runtime_plan\.json)", line)
        if match is None or match.group(2) in entries:
            fail("manifest_format_invalid")
        entries[match.group(2)] = match.group(1)
    if set(entries) != {"profile.json", "runtime_plan.json"}:
        fail("manifest_entries_invalid")
    return entries


def require_dict(value: object, reason: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(reason)
    return value


def require_list(value: object, reason: str) -> list[Any]:
    if not isinstance(value, list):
        fail(reason)
    return value


def verify_published_assets(
    manifest_path: Path,
) -> tuple[
    dict[str, str],
    dict[str, dict[str, object]],
    dict[str, dict[str, object]],
    dict[str, object],
]:
    manifest = read_manifest(manifest_path)
    profile = require_dict(
        strict_json_file(PROFILE_DIR / "profile.json", "profile_artifact_invalid"),
        "profile_artifact_invalid",
    )
    runtime = require_dict(
        strict_json_file(PROFILE_DIR / "runtime_plan.json", "runtime_artifact_invalid"),
        "runtime_artifact_invalid",
    )
    if canonical_sha256(profile) != "sha256:" + manifest["profile.json"]:
        fail("profile_artifact_digest_mismatch")
    if canonical_sha256(runtime) != "sha256:" + manifest["runtime_plan.json"]:
        fail("runtime_artifact_digest_mismatch")
    if (
        profile.get("profile_id") != PROFILE_ID
        or profile.get("version") != PROFILE_VERSION
        or runtime.get("contract_version") != "aneb-token-runtime-plan-v1"
        or runtime.get("task_count") != 3
    ):
        fail("published_asset_identity_mismatch")
    measurements = require_list(profile.get("measurements"), "profile_measurements_invalid")
    measurement_ids = [
        require_dict(value, "profile_measurement_invalid").get("metric_id")
        for value in measurements
    ]
    if (
        any(not isinstance(value, str) for value in measurement_ids)
        or len(set(measurement_ids)) != len(measurement_ids)
        or frozenset(measurement_ids) != EXPECTED_ENVELOPE_METRIC_IDS
    ):
        fail("profile_measurement_set_mismatch")
    measurement_specs = {
        str(value["metric_id"]): value
        for value in (
            require_dict(item, "profile_measurement_invalid") for item in measurements
        )
    }
    business = require_dict(profile.get("business"), "profile_business_invalid")
    evaluation = require_dict(profile.get("evaluation"), "profile_evaluation_invalid")
    behavior_contract = {
        "model_id": business.get("behavior_model_id"),
        "model_version": business.get("behavior_model_version"),
        "model_hash": business.get("behavior_model_hash"),
        "calibration_status": business.get("calibration_status"),
        "source_kind": business.get("model_source_kind"),
        "claim_scope": profile.get("claim_scope"),
        "metric_catalog_id": profile.get("measurement_catalog_id"),
        "target_set_id": evaluation.get("target_set_id"),
        "score_policy_id": evaluation.get("score_policy_id"),
        "score_anchor_policy_id": evaluation.get("score_anchor_policy_id"),
        "conclusion_policy_id": evaluation.get("conclusion_policy_id"),
        "profile_contract_version": profile.get("contract_version"),
    }
    if (
        behavior_contract["model_id"] != runtime.get("model_id")
        or behavior_contract["model_version"] != runtime.get("model_version")
        or behavior_contract["model_hash"] != runtime.get("model_hash")
        or behavior_contract["calibration_status"] != runtime.get("calibration_status")
        or not isinstance(behavior_contract["source_kind"], str)
        or behavior_contract["claim_scope"] != "application_end_to_end_to_probe_node"
        or behavior_contract["metric_catalog_id"] != "token-sim-measurements-v1"
        or behavior_contract["target_set_id"] != "token-sim-targets-v1"
        or behavior_contract["score_policy_id"] != "token-sim-score-v1"
        or behavior_contract["score_anchor_policy_id"] != "compliance-anchors-v1"
        or behavior_contract["conclusion_policy_id"] != "token-sim-conclusions-v2"
        or behavior_contract["profile_contract_version"] != "aneb-profile-v2"
        or not isinstance(behavior_contract["model_hash"], str)
        or re.fullmatch(r"sha256:[0-9a-f]{64}", behavior_contract["model_hash"])
        is None
    ):
        fail("published_execution_contract_mismatch")
    tasks = require_list(runtime.get("tasks"), "runtime_tasks_invalid")
    task_specs: dict[str, dict[str, object]] = {}
    for value in tasks:
        task = require_dict(value, "runtime_task_invalid")
        task_id = task.get("task_id")
        upload = require_dict(task.get("upload"), "runtime_task_upload_invalid")
        if (
            not isinstance(task_id, str)
            or task_id in task_specs
            or not isinstance(upload.get("payload_bytes"), int)
        ):
            fail("runtime_task_identity_invalid")
        token_stream = require_dict(
            task.get("token_stream"), "runtime_task_token_stream_invalid"
        )
        intervals_ms = require_list(
            token_stream.get("intervals_ms"), "runtime_task_intervals_invalid"
        )
        response_artifact_bytes = task.get("response_artifact_bytes")
        if (
            not intervals_ms
            or any(
                not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0
                for value in intervals_ms
            )
            or not isinstance(response_artifact_bytes, int)
            or response_artifact_bytes < 0
        ):
            fail("runtime_task_execution_contract_invalid")
        task_specs[task_id] = {
            "workload_kind": task.get("workload_kind"),
            "upload_bytes": upload["payload_bytes"],
            "response_artifact_bytes": response_artifact_bytes,
            "expected_tokens": len(intervals_ms),
            "request_count": 1 + (1 if response_artifact_bytes > 0 else 0),
        }
    if set(task_specs) != set(EXPECTED_TASK_IDS):
        fail("runtime_task_set_mismatch")
    return manifest, task_specs, measurement_specs, behavior_contract


def source_file_hashes(database: Path) -> dict[str, str]:
    paths = [database, Path(str(database) + "-wal"), Path(str(database) + "-shm")]
    if database.is_symlink() or not database.is_file():
        fail("database_unreadable")
    sidecar_states = [paths[1].is_file(), paths[2].is_file()]
    if sidecar_states[0] != sidecar_states[1]:
        fail("database_wal_shm_state_mismatch")
    result: dict[str, str] = {}
    for path in paths:
        if not path.exists():
            continue
        try:
            stat = path.stat()
            if path.is_symlink() or not path.is_file() or stat.st_size <= 0 or stat.st_size > MAX_DB_BYTES:
                fail("database_file_size_invalid")
            result[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError:
            fail("database_file_unreadable")
    return result


def load_room_schema() -> dict[str, Any]:
    root = require_dict(
        strict_json_file(ROOM_SCHEMA_PATH, "room_schema_export_invalid"),
        "room_schema_export_invalid",
    )
    database = require_dict(root.get("database"), "room_schema_export_invalid")
    if (
        database.get("version") != EXPECTED_ROOM_VERSION
        or not isinstance(database.get("identityHash"), str)
        or re.fullmatch(r"[0-9a-f]{32}", database["identityHash"]) is None
    ):
        fail("room_schema_export_identity_invalid")
    return database


def quote_identifier(value: str) -> str:
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value) is None:
        fail("room_schema_identifier_invalid")
    return '"' + value + '"'


def normalize_create_sql(value: str) -> str:
    normalized = value.strip()
    normalized = re.sub(
        r"^CREATE (TABLE|INDEX) IF NOT EXISTS ",
        r"CREATE \1 ",
        normalized,
        flags=re.IGNORECASE,
    )
    return re.sub(r"\s+", " ", normalized)


def known_migrated_create_sql(
    table_name: str,
    entity: dict[str, Any],
) -> str | None:
    if table_name != "network_comprehensive_result":
        return None
    fields = require_list(entity.get("fields"), "room_schema_export_invalid")
    by_column: dict[str, dict[str, Any]] = {}
    for value in fields:
        field = require_dict(value, "room_schema_export_invalid")
        column = field.get("columnName")
        if (
            not isinstance(column, str)
            or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", column) is None
            or column in by_column
        ):
            fail("room_schema_export_invalid")
        by_column[column] = field
    if set(by_column) != set(NETWORK_COMPREHENSIVE_MIGRATED_COLUMN_ORDER):
        fail("room_schema_export_invalid")

    definitions: list[str] = []
    for column in NETWORK_COMPREHENSIVE_MIGRATED_COLUMN_ORDER:
        field = by_column[column]
        affinity = str(field.get("affinity", "")).upper()
        if affinity not in {"INTEGER", "REAL", "TEXT", "BLOB"}:
            fail("room_schema_export_invalid")
        definition = f"`{column}` {affinity}"
        if field.get("notNull") is True:
            definition += " NOT NULL"
        elif field.get("notNull") is not False:
            fail("room_schema_export_invalid")
        if column in NETWORK_COMPREHENSIVE_MIGRATED_DEFAULTS:
            definition += " DEFAULT " + NETWORK_COMPREHENSIVE_MIGRATED_DEFAULTS[column]
        definitions.append(definition)

    primary = require_dict(entity.get("primaryKey"), "room_schema_export_invalid")
    primary_columns = require_list(
        primary.get("columnNames"), "room_schema_export_invalid"
    )
    if (
        not primary_columns
        or any(
            not isinstance(column, str) or column not in by_column
            for column in primary_columns
        )
    ):
        fail("room_schema_export_invalid")
    primary_sql = ", ".join(f"`{column}`" for column in primary_columns)
    definitions.append(f"PRIMARY KEY({primary_sql})")
    return (
        f"CREATE TABLE IF NOT EXISTS `{table_name}` "
        f"({', '.join(definitions)})"
    )


def verify_room_schema(connection: sqlite3.Connection) -> str:
    exported = load_room_schema()
    user_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
    if user_version != EXPECTED_ROOM_VERSION:
        fail("room_user_version_mismatch")
    entities = require_list(exported.get("entities"), "room_schema_export_invalid")
    expected_by_table: dict[str, dict[str, Any]] = {}
    for value in entities:
        entity = require_dict(value, "room_schema_export_invalid")
        table_name = entity.get("tableName")
        if not isinstance(table_name, str) or table_name in expected_by_table:
            fail("room_schema_export_invalid")
        expected_by_table[table_name] = entity
    actual_tables = {
        str(row[0])
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }
    allowed = set(expected_by_table) | {"room_master_table", "android_metadata"}
    if actual_tables != allowed:
        fail("room_table_set_mismatch")
    if connection.execute("SELECT count(*) FROM sqlite_master WHERE type='view'").fetchone()[0] != 0:
        fail("room_view_set_mismatch")
    if connection.execute("SELECT count(*) FROM sqlite_master WHERE type='trigger'").fetchone()[0] != 0:
        fail("room_trigger_set_mismatch")
    master_columns = connection.execute("PRAGMA table_info(room_master_table)").fetchall()
    if [(row[1], str(row[2]).upper(), row[5]) for row in master_columns] != [
        ("id", "INTEGER", 1),
        ("identity_hash", "TEXT", 0),
    ]:
        fail("room_master_schema_mismatch")
    master_create = connection.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='room_master_table'"
    ).fetchone()
    if (
        master_create is None
        or not isinstance(master_create[0], str)
        or normalize_create_sql(master_create[0])
        != normalize_create_sql(
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
        )
        or connection.execute("PRAGMA index_list(room_master_table)").fetchall() != []
        or connection.execute("PRAGMA foreign_key_list(room_master_table)").fetchall() != []
    ):
        fail("room_master_schema_mismatch")
    master_rows = [
        tuple(row)
        for row in connection.execute(
            "SELECT id, identity_hash FROM room_master_table ORDER BY id"
        ).fetchall()
    ]
    if master_rows != [(42, exported["identityHash"])]:
        fail("room_master_identity_mismatch")

    android_create = connection.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='android_metadata'"
    ).fetchone()
    android_columns = connection.execute("PRAGMA table_info(android_metadata)").fetchall()
    android_rows = connection.execute("SELECT locale FROM android_metadata").fetchall()
    if (
        android_create is None
        or not isinstance(android_create[0], str)
        or normalize_create_sql(android_create[0])
        != normalize_create_sql("CREATE TABLE android_metadata (locale TEXT)")
        or [(row[1], str(row[2]).upper(), row[3], row[5]) for row in android_columns]
        != [("locale", "TEXT", 0, 0)]
        or connection.execute("PRAGMA index_list(android_metadata)").fetchall() != []
        or connection.execute("PRAGMA foreign_key_list(android_metadata)").fetchall() != []
        or len(android_rows) != 1
        or not isinstance(android_rows[0][0], str)
        or not 1 <= len(android_rows[0][0]) <= 128
    ):
        fail("android_metadata_schema_mismatch")

    for table_name, entity in expected_by_table.items():
        create_row = connection.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
            (table_name,),
        ).fetchone()
        expected_create = str(entity.get("createSql", "")).replace(
            "${TABLE_NAME}", table_name
        )
        known_migrated = known_migrated_create_sql(table_name, entity)
        allowed_create_sql = {normalize_create_sql(expected_create)}
        if known_migrated is not None:
            allowed_create_sql.add(normalize_create_sql(known_migrated))
        if (
            create_row is None
            or not isinstance(create_row[0], str)
            or normalize_create_sql(create_row[0]) not in allowed_create_sql
        ):
            fail("room_create_sql_mismatch")
        fields = require_list(entity.get("fields"), "room_schema_export_invalid")
        primary = require_dict(entity.get("primaryKey"), "room_schema_export_invalid")
        primary_columns = require_list(
            primary.get("columnNames"), "room_schema_export_invalid"
        )
        expected_columns: dict[str, tuple[str, int, int]] = {}
        for field in fields:
            field = require_dict(field, "room_schema_export_invalid")
            column = field.get("columnName")
            if not isinstance(column, str) or column in expected_columns:
                fail("room_schema_export_invalid")
            expected_columns[column] = (
                str(field.get("affinity", "")).upper(),
                1 if field.get("notNull") is True else 0,
                primary_columns.index(column) + 1 if column in primary_columns else 0,
            )
        actual_columns = connection.execute(
            f"PRAGMA table_info({quote_identifier(table_name)})"
        ).fetchall()
        projected = {
            str(row[1]): (str(row[2]).upper(), int(row[3]), int(row[5]))
            for row in actual_columns
        }
        if projected != expected_columns:
            fail("room_table_schema_mismatch")
        expected_foreign_keys = require_list(
            entity.get("foreignKeys"), "room_schema_export_invalid"
        )
        actual_foreign_keys = connection.execute(
            f"PRAGMA foreign_key_list({quote_identifier(table_name)})"
        ).fetchall()
        if expected_foreign_keys != [] or actual_foreign_keys != []:
            fail("room_foreign_key_mismatch")

        expected_indexes = {
            str(index["name"]): (
                1 if index.get("unique") is True else 0,
                list(index.get("columnNames", [])),
            )
            for index in require_list(entity.get("indices"), "room_schema_export_invalid")
        }
        index_rows = connection.execute(
            f"PRAGMA index_list({quote_identifier(table_name)})"
        ).fetchall()
        actual_named = {
            str(row[1]): int(row[2]) for row in index_rows if str(row[3]) == "c"
        }
        if actual_named != {name: value[0] for name, value in expected_indexes.items()}:
            fail("room_index_set_mismatch")
        for index_name, (_, expected_columns_for_index) in expected_indexes.items():
            index_sql_row = connection.execute(
                "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
                (index_name,),
            ).fetchone()
            expected_index = next(
                value
                for value in entity["indices"]
                if str(value["name"]) == index_name
            )
            expected_index_sql = str(expected_index["createSql"]).replace(
                "${TABLE_NAME}", table_name
            )
            if (
                index_sql_row is None
                or not isinstance(index_sql_row[0], str)
                or normalize_create_sql(index_sql_row[0])
                != normalize_create_sql(expected_index_sql)
            ):
                fail("room_index_create_sql_mismatch")
            actual_index_columns = [
                str(row[2])
                for row in connection.execute(
                    f"PRAGMA index_info({quote_identifier(index_name)})"
                ).fetchall()
            ]
            if actual_index_columns != expected_columns_for_index:
                fail("room_index_columns_mismatch")
    return str(exported["identityHash"])


def select_exact_one(
    connection: sqlite3.Connection, query: str, run_id: str, reason: str
) -> sqlite3.Row:
    rows = connection.execute(query, (run_id,)).fetchall()
    if len(rows) != 1:
        fail(reason)
    return rows[0]


def query_analysis_copy(
    database: Path,
    source_hashes: dict[str, str],
    analysis_root: Path,
    run_id: str,
) -> tuple[sqlite3.Row, sqlite3.Row, str]:
    for source_name in source_hashes:
        source = database.parent / source_name
        destination = analysis_root / source_name
        try:
            shutil.copyfile(source, destination)
        except OSError:
            fail("database_analysis_copy_failed")
        if hashlib.sha256(destination.read_bytes()).hexdigest() != source_hashes[source_name]:
            fail("database_analysis_copy_digest_mismatch")
    analysis_db = analysis_root / database.name
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(analysis_db.resolve().as_uri() + "?mode=ro", uri=True)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")
        quick_check = connection.execute("PRAGMA quick_check").fetchall()
        if len(quick_check) != 1 or quick_check[0][0] != "ok":
            fail("database_integrity_failed")
        room_identity = verify_room_schema(connection)
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
            "test_run", "token_event", "scenario_result", "echo_sample", "report_body",
            "continuity_result", "ab_result", "basic_speed_result",
            "realtime_simulation_result", "network_comprehensive_result",
        )
        for table in forbidden_same_run_tables:
            count = connection.execute(
                f'SELECT COUNT(*) FROM "{table}" WHERE runId = ?', (run_id,)
            ).fetchone()[0]
            if count != 0:
                fail("unexpected_same_run_rows")
        return typed, envelope, room_identity
    except VerificationFailure:
        raise
    except sqlite3.Error:
        fail("database_query_failed")
    finally:
        if connection is not None:
            connection.close()


def verify_result_schema(result_text: str, analysis_root: Path) -> None:
    jsonl = analysis_root / "result.jsonl"
    try:
        jsonl.write_text(result_text + "\n", encoding="utf-8", newline="")
    except OSError:
        fail("result_schema_staging_failed")
    report = validate_jsonl(ROOT, [jsonl])
    if (
        report.get("status") != "pass"
        or report.get("documents") != 1
        or report.get("schema_versions") != {"aneb-result-v2": 1}
        or report.get("errors") != []
    ):
        fail("result_schema_invalid")


SCORER_SPECS: dict[str, tuple[int, float]] = {
    "TOK-B01": (20, 0.99),
    "TOK-B02": (3, 0.95),
    "TOK-B03": (1, 0.0),
    "TOK-B04": (10, 0.95),
    "TOK-B05": (10, 0.95),
    "TOK-B07": (100, 0.95),
    "TOK-B09": (100, 0.98),
    "TOK-B10": (100, 1.0),
    "TOK-B11": (100, 0.99),
    "TOK-B14": (1, 0.95),
    "TOK-N03": (20, 0.95),
    "TOK-N04": (20, 0.95),
    "TOK-N05": (20, 0.98),
    "TOK-N06": (5, 0.95),
}
EXPECTED_GROUP_IDS = frozenset(
    {"task_completion", "interaction", "multimodal_transfer", "network_stability", "efficiency"}
)


def finite_number(value: object) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = min(max(quantile, 0.0), 1.0) * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def compliance_score(ratio: float) -> float:
    value = min(max(ratio, 0.0), 1.0)
    anchors = ((0.0, 0.0), (0.80, 55.0), (0.90, 70.0), (0.95, 85.0), (1.0, 100.0))
    for index, upper in enumerate(anchors):
        if value <= upper[0]:
            if index == 0:
                return upper[1]
            lower = anchors[index - 1]
            fraction = (value - lower[0]) / (upper[0] - lower[0])
            return lower[1] + fraction * (upper[1] - lower[1])
    return anchors[-1][1]


def kotlin_round1(value: float) -> float:
    return math.floor(value * 10.0 + 0.5) / 10.0


def metric_projection(
    metric_id: str,
    value: float | None,
    compliance: float | None,
    sample_count: int,
) -> dict[str, object]:
    minimum, target = SCORER_SPECS[metric_id]
    return {
        "value": value,
        "compliance_ratio": compliance,
        "sample_count": sample_count,
        "minimum_sample_count": minimum,
        "target_compliance_ratio": target,
        "score": None if compliance is None else compliance_score(compliance),
    }


def recompute_quick_score(
    tasks: list[dict[str, Any]], rtt: list[float], loaded_rtt: list[float]
) -> tuple[dict[str, dict[str, object]], dict[str, float], dict[str, object]]:
    expected_tokens = sum(int(task["expected_tokens"]) for task in tasks)
    unique_tokens = sum(int(task["unique_tokens"]) for task in tasks)
    duplicate_tokens = sum(int(task["duplicate_tokens"]) for task in tasks)
    lateness = [float(value) for task in tasks for value in task["token_lateness_ms"]]
    residuals = [float(value) for task in tasks for value in task["itl_residual_ms"]]

    task_success = sum(task["success"] is True for task in tasks) / len(tasks)
    upload_deadline_pass: list[bool] = []
    upload_rate_pass: list[bool] = []
    for task in tasks:
        workload = str(task["workload_kind"])
        upload_bytes = int(task["upload_bytes"])
        mib = upload_bytes / (1024.0 * 1024.0)
        deadline = {
            "text": 1000.0,
            "document": max(2000.0, 1000.0 + mib * 1000.0),
            "image": max(2000.0, mib * 1000.0),
            "video": 60000.0,
        }.get(workload, math.nan)
        target_rate = {"text": 1.0, "document": 10.0, "image": 12.0, "video": 20.0}.get(
            workload, math.inf
        )
        upload_deadline_pass.append(float(task["click_to_node_receive_ms"]) <= deadline)
        upload_rate_pass.append(float(task["upload_goodput_mbps"]) >= target_rate)

    ttft_pass = [float(task["ttft_excess_ms"]) <= 200.0 for task in tasks]
    end_to_end_pass = [
        float(task["ttft_ms"]) <= float(task["server_processing_ms"]) + 200.0 for task in tasks
    ]
    on_time = sum(value <= 200.0 for value in lateness) / len(lateness)
    stall_rate = sum(value > 200.0 for value in residuals) / len(residuals)
    severe_rate = sum(value > 1000.0 for value in residuals) / len(residuals)
    completeness = unique_tokens / expected_tokens
    redundancy = duplicate_tokens / unique_tokens
    rtt_median = percentile(rtt, 0.50)
    rtt_p95 = percentile(rtt, 0.95)
    assert rtt_median is not None and rtt_p95 is not None
    rtt_compliance = sum(value <= 100.0 for value in rtt) / len(rtt)
    variation_compliance = sum(abs(value - rtt_median) <= 30.0 for value in rtt) / len(rtt)
    request_count = len(rtt) + len(loaded_rtt) + sum(int(task["request_count"]) for task in tasks)
    failed_requests = sum(int(task["failed_request_count"]) for task in tasks)
    request_success = (request_count - failed_requests) / request_count

    ratio = lambda values: sum(values) / len(values)
    metrics = {
        "TOK-B01": metric_projection("TOK-B01", task_success, task_success, len(tasks)),
        "TOK-B02": metric_projection(
            "TOK-B02",
            percentile([float(task["click_to_node_receive_ms"]) for task in tasks], 0.95),
            ratio(upload_deadline_pass),
            len(upload_deadline_pass),
        ),
        "TOK-B03": metric_projection(
            "TOK-B03",
            percentile([float(task["server_processing_ms"]) for task in tasks], 0.95),
            None,
            len(tasks),
        ),
        "TOK-B04": metric_projection(
            "TOK-B04",
            percentile([float(task["ttft_ms"]) for task in tasks], 0.95),
            ratio(end_to_end_pass),
            len(end_to_end_pass),
        ),
        "TOK-B05": metric_projection(
            "TOK-B05",
            percentile([float(task["ttft_excess_ms"]) for task in tasks], 0.95),
            ratio(ttft_pass),
            len(ttft_pass),
        ),
        "TOK-B07": metric_projection("TOK-B07", on_time, on_time, len(lateness)),
        "TOK-B09": metric_projection("TOK-B09", stall_rate, 1.0 - stall_rate, len(residuals)),
        "TOK-B10": metric_projection("TOK-B10", severe_rate, 1.0 - severe_rate, len(residuals)),
        "TOK-B11": metric_projection("TOK-B11", completeness, completeness, expected_tokens),
        "TOK-B14": metric_projection("TOK-B14", redundancy, 1.0 - redundancy, 1),
        "TOK-N03": metric_projection("TOK-N03", rtt_p95, rtt_compliance, len(rtt)),
        "TOK-N04": metric_projection(
            "TOK-N04", rtt_p95 - rtt_median, variation_compliance, len(rtt)
        ),
        "TOK-N05": metric_projection(
            "TOK-N05", 1.0 - request_success, request_success, request_count
        ),
        "TOK-N06": metric_projection(
            "TOK-N06",
            percentile([float(task["upload_goodput_mbps"]) for task in tasks], 0.05),
            ratio(upload_rate_pass),
            len(upload_rate_pass),
        ),
    }
    metric_score = lambda metric_id: float(metrics[metric_id]["score"])
    artifact_tasks = [task for task in tasks if int(task["response_artifact_bytes"]) > 0]
    down_compliance = [float(task["download_goodput_mbps"]) >= 25.0 for task in artifact_tasks]
    if down_compliance:
        multimodal = (
            metric_score("TOK-B02") * 0.30
            + metric_score("TOK-N06") * 0.30
            + compliance_score(ratio(down_compliance)) * 0.40
        )
    else:
        multimodal = metric_score("TOK-B02") * 0.50 + metric_score("TOK-N06") * 0.50
    raw_groups = {
        "task_completion": metric_score("TOK-B01") * 0.60 + metric_score("TOK-B11") * 0.40,
        "interaction": (
            metric_score("TOK-B05") * 0.50
            + metric_score("TOK-B07") / 3.0
            + metric_score("TOK-B09") / 6.0
        ),
        "multimodal_transfer": multimodal,
        "network_stability": (
            metric_score("TOK-N03") + metric_score("TOK-N04") + metric_score("TOK-N05")
        ) / 3.0,
        "efficiency": metric_score("TOK-B14"),
    }
    total = (
        raw_groups["task_completion"] * 0.25
        + raw_groups["interaction"] * 0.30
        + raw_groups["multimodal_transfer"] * 0.25
        + raw_groups["network_stability"] * 0.15
        + raw_groups["efficiency"] * 0.05
    )
    cap_reason = None
    if task_success < 0.80:
        cap_reason = "任务成功率低于 80%"
    elif severe_rate > 0.01:
        cap_reason = "严重卡顿率高于 1%"
    if cap_reason is not None:
        total = min(total, 54.0)
    required_ids = set(SCORER_SPECS) - {"TOK-B03", "TOK-B04"}
    coverage = min(
        min(float(metrics[metric_id]["sample_count"]) / SCORER_SPECS[metric_id][0], 1.0)
        for metric_id in required_ids
    )
    minimum_satisfied = all(
        int(metrics[metric_id]["sample_count"]) >= SCORER_SPECS[metric_id][0]
        for metric_id in required_ids
    )
    confidence = "medium" if all(
        float(metrics[metric_id]["sample_count"]) / SCORER_SPECS[metric_id][0] >= 0.50
        for metric_id in required_ids
    ) else "low"
    all_targets_met = all(
        float(metrics[metric_id]["compliance_ratio"]) + 1e-12 >= SCORER_SPECS[metric_id][1]
        for metric_id in required_ids
    )
    verdict = "inconclusive" if confidence == "low" else (
        "fail" if cap_reason is not None or not all_targets_met else "pass"
    )
    rounded_total = kotlin_round1(total)
    grade = "A" if total >= 85.0 else "B" if total >= 70.0 else "C" if total >= 55.0 else "D"
    score = {
        "state": "computed",
        "value": rounded_total,
        "grade": grade,
        "verdict": verdict,
        "confidence": confidence,
        "confidence_basis": {
            "method_id": "token-sample-coverage-v1",
            "coverage_ratio": coverage,
            "minimum_sample_satisfied": minimum_satisfied,
        },
        "cap_reason": cap_reason,
        "not_computable_reason": None,
    }
    return metrics, {key: kotlin_round1(value) for key, value in raw_groups.items()}, score


def equal_number(actual: object, expected: object) -> bool:
    if actual is None or expected is None:
        return actual is expected
    return finite_number(actual) and finite_number(expected) and math.isclose(
        float(actual), float(expected), rel_tol=0.0, abs_tol=1e-9
    )


def assert_metric_values(actual: dict[str, Any], expected: dict[str, object], reason: str) -> None:
    if set(actual) != set(expected):
        fail(reason)
    for key, value in expected.items():
        if key in {"value", "compliance_ratio", "target_compliance_ratio", "score"}:
            if not equal_number(actual.get(key), value):
                fail(reason)
        elif actual.get(key) != value:
            fail(reason)


def expected_quality_target(definition: dict[str, object]) -> object:
    raw = definition.get("quality_target")
    if raw is None:
        return None
    target = require_dict(raw, "profile_quality_target_invalid")
    values = target.get("values", {})
    if not isinstance(values, dict):
        fail("profile_quality_target_invalid")
    return {
        "operator": target.get("operator", ""),
        "value": target.get("value"),
        "values": dict(sorted(values.items())),
        "policy_id": target.get("policy_id"),
        "required_compliance_ratio": target.get("required_compliance_ratio"),
        "provenance": target.get("provenance", ""),
    }


def assert_envelope_metric(
    metric_id: str,
    actual: dict[str, Any],
    definition: dict[str, object],
    scorer_metric: dict[str, object] | None,
    *,
    radio_sample_count: int | None,
) -> None:
    expected_keys = {
        "label", "domain", "unit", "measurement_level", "state", "value",
        "compliance_ratio", "sample_count", "minimum_sample_count", "source_event_ids",
        "direction", "required_for_score", "quality_target", "score", "formula_id",
        "aggregation", "components", "source_evidence_ref_ids", "invalid_reason",
    }
    if set(actual) != expected_keys:
        fail("envelope_metric_fields_mismatch")
    source_event_ids = definition.get("source_event_ids", [])
    if not isinstance(source_event_ids, list):
        fail("profile_measurement_definition_invalid")
    domain = str(definition.get("domain", ""))
    normalized_domain = domain if domain in {"business", "network", "radio"} else "diagnostic"
    static_expected = {
        "label": definition.get("label", ""),
        "domain": normalized_domain,
        "unit": definition.get("unit", ""),
        "measurement_level": definition.get("measurement_level", ""),
        "source_event_ids": source_event_ids,
        "direction": definition.get("direction", ""),
        "required_for_score": definition.get("required_for_score", False),
        "quality_target": expected_quality_target(definition),
        "formula_id": definition.get("formula_id", ""),
        "aggregation": definition.get("aggregation", ""),
        "components": {},
    }
    for key, value in static_expected.items():
        if actual.get(key) != value:
            fail("envelope_metric_definition_mismatch")
    if scorer_metric is not None:
        expected_dynamic = {
            "state": "observed",
            "value": scorer_metric["value"],
            "compliance_ratio": scorer_metric["compliance_ratio"],
            "sample_count": scorer_metric["sample_count"],
            "minimum_sample_count": scorer_metric["minimum_sample_count"],
            "score": scorer_metric["score"],
            "source_evidence_ref_ids": ["token-raw"],
            "invalid_reason": None,
        }
    elif metric_id == "TOK-R01" and radio_sample_count is not None:
        expected_dynamic = {
            "state": "observed",
            "value": None,
            "compliance_ratio": None,
            "sample_count": radio_sample_count,
            "minimum_sample_count": definition.get("minimum_sample_count", 0),
            "score": None,
            "source_evidence_ref_ids": ["radio-context"],
            "invalid_reason": None,
        }
    else:
        expected_dynamic = {
            "state": "missing",
            "value": None,
            "compliance_ratio": None,
            "sample_count": 0,
            "minimum_sample_count": definition.get("minimum_sample_count", 0),
            "score": None,
            "source_evidence_ref_ids": ["token-raw"],
            "invalid_reason": "measurement_not_emitted_by_current_engine",
        }
    for key, value in expected_dynamic.items():
        if key in {"value", "compliance_ratio", "score"}:
            if not equal_number(actual.get(key), value):
                fail("envelope_metric_value_mismatch")
        elif actual.get(key) != value:
            fail("envelope_metric_value_mismatch")


def verify(
    database: Path,
    *,
    run_id: str,
    manifest: Path,
    expected_server_base: str | None,
) -> tuple[dict[str, object], str]:
    if RUN_ID_RE.fullmatch(run_id) is None:
        fail("run_id_not_uuidv7")
    (
        manifest_entries,
        task_specs,
        measurement_specs,
        execution_contract,
    ) = verify_published_assets(manifest)
    source_before = source_file_hashes(database)
    pending_error: Exception | None = None
    query_result: tuple[sqlite3.Row, sqlite3.Row, str] | None = None
    with tempfile.TemporaryDirectory(prefix="aneb-room-analysis-") as temporary:
        analysis_root = Path(temporary)
        try:
            query_result = query_analysis_copy(
                database, source_before, analysis_root, run_id
            )
        except Exception as error:
            pending_error = error
        source_after = source_file_hashes(database)
        if source_after != source_before:
            fail("frozen_source_modified_during_analysis")
        if pending_error is not None:
            raise pending_error
        if query_result is None:
            fail("database_query_failed")
        typed, envelope, room_identity = query_result

        result_text = envelope["bodyJson"]
        if not isinstance(result_text, str):
            fail("client_json_invalid")
        verify_result_schema(result_text, analysis_root)
        body = require_dict(strict_json_text(result_text, "client_json_invalid"), "result_body_invalid")

        if envelope["schemaVersion"] != "aneb-result-v2" or envelope["testType"] != "token_simulation":
            fail("result_envelope_identity_mismatch")
        if canonical_sha256(body) != envelope["canonicalSha256"]:
            fail("result_envelope_digest_mismatch")
        if body.get("schema_version") != "aneb-result-v2" or body.get("test_type") != "token_simulation":
            fail("result_body_identity_mismatch")

        producer = require_dict(body.get("producer"), "producer_missing")
        if (
            producer.get("resolution_status") != "resolved"
            or producer.get("component") != "aneb-probe-android"
            or producer.get("component_version") != "0.5.12-codex"
            or producer.get("exporter_version") != "aneb-result-exporter-v2"
        ):
            fail("producer_identity_mismatch")
        run = require_dict(body.get("run"), "run_missing")
        if (
            run.get("run_id") != run_id
            or run.get("status") != "completed"
            or run.get("validity") != "valid"
            or run.get("invalid_reason_codes") != []
            or run.get("source_record_version") != "room-v19-token-envelope-v1"
        ):
            fail("run_not_completed_valid")
        run_uuid_ms = uuid7_unix_ms(run_id)
        started_at_ms = epoch_ms(
            run.get("started_at_epoch_ms"), "run_time_fields_invalid"
        )
        ended_at_ms = epoch_ms(
            run.get("ended_at_epoch_ms"), "run_time_fields_invalid"
        )
        duration_ms = epoch_ms(run.get("duration_ms"), "run_time_fields_invalid")
        serialized_at_ms = epoch_ms(
            producer.get("serialized_at_epoch_ms"), "run_time_fields_invalid"
        )
        run_start_delta_ms = started_at_ms - run_uuid_ms
        if not 0 <= run_start_delta_ms <= MAX_RUN_START_DELTA_MS:
            fail("run_time_identity_mismatch")
        if ended_at_ms < started_at_ms or duration_ms != ended_at_ms - started_at_ms:
            fail("run_duration_mismatch")
        # TokenResultEnvelopeV2 uses endedAtEpochMs as serializedAt verbatim.
        if serialized_at_ms != ended_at_ms:
            fail("run_serialization_time_mismatch")

        profile = require_dict(body.get("profile"), "profile_missing")
        profile_digest = f"sha256:{manifest_entries['profile.json']}"
        runtime_digest = f"sha256:{manifest_entries['runtime_plan.json']}"
        profile_fingerprint = require_dict(
            profile.get("profile_fingerprint"), "profile_fingerprint_missing"
        )
        runtime_hash = require_dict(
            profile.get("runtime_artifact_hash"), "runtime_artifact_hash_missing"
        )
        if (
            profile.get("resolution_status") != "resolved"
            or profile.get("profile_id") != PROFILE_ID
            or profile.get("profile_version") != PROFILE_VERSION
            or profile.get("variant") != "quick"
            or profile.get("runtime_artifact_status") != "resolved"
            or profile.get("contract_version")
            != execution_contract["profile_contract_version"]
            or profile.get("source_uri")
            != f"{PROFILE_ASSET_BASE}/profile.json"
            or profile_fingerprint.get("algorithm") != "sha256"
            or profile_fingerprint.get("canonicalization") != "canonical-json-v1"
            or profile_fingerprint.get("value") != profile_digest
            or runtime_hash.get("algorithm") != "sha256"
            or runtime_hash.get("canonicalization") != "canonical-json-v1"
            or runtime_hash.get("value") != runtime_digest
            or profile.get("profile_evidence_ref_id") != "profile-artifact"
            or profile.get("runtime_artifact_evidence_ref_id") != "runtime-artifact"
        ):
            fail("profile_runtime_identity_mismatch")

        claim = require_dict(body.get("claim"), "claim_missing")
        context = require_dict(body.get("context"), "context_missing")
        endpoint = require_dict(context.get("endpoint"), "endpoint_context_missing")
        device = require_dict(context.get("device"), "device_context_missing")
        network = require_dict(context.get("network"), "network_context_missing")
        if network.get("evidence_ref_ids") != []:
            fail("network_evidence_refs_invalid")
        radio = require_dict(context.get("radio"), "radio_context_missing")
        radio_samples = require_list(radio.get("samples"), "radio_samples_invalid")
        radio_ref_ids = require_list(radio.get("evidence_ref_ids"), "radio_evidence_refs_invalid")
        if radio.get("collection_status") == "collected":
            if (
                not radio_samples
                or radio.get("sample_count") != len(radio_samples)
                or radio_ref_ids != ["radio-context"]
            ):
                fail("radio_context_invalid")
            radio_sample_count: int | None = len(radio_samples)
        else:
            if radio_samples or radio.get("sample_count") != 0 or radio_ref_ids != []:
                fail("radio_context_invalid")
            radio_sample_count = None
        if endpoint.get("server_version") is not None:
            fail("client_server_version_must_remain_null")
        if expected_server_base is not None and endpoint.get("server_base") != expected_server_base:
            fail("client_server_base_mismatch")
        if (
            device.get("availability") != "observed"
            or device.get("app_package") != "com.aneb.probe.codex"
            or device.get("app_version_name") != "0.5.12-codex"
            or device.get("app_version_code") != 44
        ):
            fail("client_app_identity_mismatch")

        evaluation = require_dict(body.get("evaluation"), "evaluation_missing")
        algorithms = require_dict(
            evaluation.get("algorithm_versions"), "algorithm_versions_missing"
        )
        score = require_dict(evaluation.get("score"), "score_missing")
        conclusions = require_list(evaluation.get("conclusions"), "conclusions_missing")
        metrics = require_dict(evaluation.get("metrics"), "metrics_missing")
        group_scores = require_dict(evaluation.get("group_scores"), "group_scores_missing")
        if (
            score.get("state") != "computed"
            or not isinstance(score.get("value"), (int, float))
            or score.get("grade") is None
            or score.get("verdict") in {None, "invalid"}
            or not metrics
            or not group_scores
            or not conclusions
        ):
            fail("evaluation_not_computed")
        if set(metrics) != set(measurement_specs):
            fail("envelope_metric_set_mismatch")
        expected_algorithms = {
            "measurement_engine_version": "token-simulation-engine-v2",
            "metric_catalog_id": execution_contract["metric_catalog_id"],
            "target_set_id": execution_contract["target_set_id"],
            "score_policy_id": execution_contract["score_policy_id"],
            "score_anchor_policy_id": execution_contract["score_anchor_policy_id"],
            "conclusion_policy_id": execution_contract["conclusion_policy_id"],
            "calculation_origin": "measurement_engine",
            "finalized_at_epoch_ms": producer.get("serialized_at_epoch_ms"),
        }
        if algorithms != expected_algorithms:
            fail("algorithm_contract_mismatch")
        if any(
            require_dict(value, "conclusion_invalid").get("policy_id")
            != execution_contract["conclusion_policy_id"]
            for value in conclusions
        ):
            fail("conclusion_policy_mismatch")

        category = require_dict(body.get("category_payload"), "category_payload_missing")
        model = require_dict(category.get("behavior_model"), "behavior_model_missing")
        model_hash = require_dict(model.get("model_hash"), "behavior_model_hash_missing")
        raw = require_dict(category.get("raw_evidence"), "raw_evidence_missing")
        if (
            category.get("evidence_contract_version") != "aneb-token-run-evidence-v1"
            or category.get("variant") != "quick"
            or raw.get("invalid_reason") is not None
        ):
            fail("raw_evidence_invalid")
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

        if (
            typed["runId"] != run_id
            or typed["startedAtEpochMs"] != run.get("started_at_epoch_ms")
            or typed["serverBase"] != endpoint.get("server_base")
            or typed["claimScope"] != claim.get("scope")
            or typed["profileId"] != PROFILE_ID
            or typed["profileVersion"] != PROFILE_VERSION
            or typed["variant"] != "quick"
            or typed["behaviorModelId"] != model.get("model_id")
            or typed["behaviorModelVersion"] != model.get("model_version")
            or typed["behaviorModelHash"] != model_hash.get("value")
            or typed["calibrationStatus"] != model.get("calibration_status")
            or typed["scorePolicyId"] != algorithms.get("score_policy_id")
            or typed["scoreAnchorPolicyId"] != algorithms.get("score_anchor_policy_id")
            or typed["conclusionPolicyId"] != algorithms.get("conclusion_policy_id")
            or envelope["runId"] != run_id
            or envelope["startedAtEpochMs"] != run.get("started_at_epoch_ms")
            or envelope["serializedAtEpochMs"] != producer.get("serialized_at_epoch_ms")
        ):
            fail("typed_envelope_identity_mismatch")
        if (
            typed["totalScore"] != score.get("value")
            or typed["grade"] != score.get("grade")
            or str(typed["verdict"]).casefold() != str(score.get("verdict")).casefold()
            or str(typed["confidence"]).casefold() != str(score.get("confidence")).casefold()
            or typed["capReason"] != score.get("cap_reason")
        ):
            fail("typed_envelope_score_mismatch")

        typed_metrics = require_dict(
            strict_json_text(typed["metricsJson"], "typed_metrics_json_invalid"),
            "typed_metrics_json_invalid",
        )
        if set(typed_metrics) != EXPECTED_TYPED_METRIC_IDS:
            fail("typed_metrics_mismatch")
        typed_conclusions = require_list(
            strict_json_text(typed["conclusionsJson"], "typed_conclusions_json_invalid"),
            "typed_conclusions_json_invalid",
        )
        conclusion_texts = [
            require_dict(value, "conclusion_invalid").get("text") for value in conclusions
        ]
        if typed_conclusions != conclusion_texts:
            fail("typed_conclusions_mismatch")
        typed_evidence = require_dict(
            strict_json_text(typed["evidenceJson"], "typed_evidence_json_invalid"),
            "typed_evidence_json_invalid",
        )
        expected_typed_evidence = {
            "contract_version": "aneb-token-run-evidence-v1",
            "variant": "quick",
            **raw,
        }
        if typed_evidence != expected_typed_evidence:
            fail("typed_evidence_mismatch")

        tasks = require_list(raw.get("tasks"), "tasks_missing")
        if [
            require_dict(value, "task_invalid").get("task_id") for value in tasks
        ] != list(EXPECTED_TASK_IDS):
            fail("task_order_mismatch")
        task_map: dict[str, dict[str, Any]] = {}
        for value in tasks:
            task = require_dict(value, "task_invalid")
            task_id = task.get("task_id")
            if not isinstance(task_id, str) or task_id in task_map:
                fail("task_identity_invalid")
            task_map[task_id] = task
        if set(task_map) != set(EXPECTED_TASK_IDS):
            fail("task_set_mismatch")
        for task_id in EXPECTED_TASK_IDS:
            task = task_map[task_id]
            spec = task_specs[task_id]
            if (
                task.get("workload_kind") != spec["workload_kind"]
                or task.get("upload_bytes") != spec["upload_bytes"]
                or task.get("response_artifact_bytes") != spec["response_artifact_bytes"]
            ):
                fail("task_runtime_contract_mismatch")
            if (
                task.get("success") is not True
                or task.get("network_failure") is not False
                or task.get("error") is not None
                or task.get("failed_request_count") != 0
            ):
                fail("task_not_successful")
            for field in (
                "click_to_node_receive_ms",
                "server_processing_ms",
                "ttft_ms",
                "ttft_excess_ms",
                "upload_goodput_mbps",
            ):
                if not finite_number(task.get(field)) or task[field] < 0:
                    fail("task_required_metric_invalid")
            if task["upload_goodput_mbps"] <= 0:
                fail("task_required_metric_invalid")
            if (
                task.get("request_count") != spec["request_count"]
                or task.get("expected_tokens") != spec["expected_tokens"]
                or task.get("unique_tokens") != task["expected_tokens"]
                or task.get("duplicate_tokens") != 0
            ):
                fail("task_token_evidence_invalid")
            token_lateness = require_list(
                task.get("token_lateness_ms"), "task_token_evidence_invalid"
            )
            itl_residual = require_list(
                task.get("itl_residual_ms"), "task_token_evidence_invalid"
            )
            if (
                len(token_lateness) != spec["expected_tokens"]
                or len(itl_residual) != spec["expected_tokens"] - 1
                or any(
                    not finite_number(value)
                    for value in token_lateness + itl_residual
                )
            ):
                fail("task_token_evidence_invalid")
            for index, residual in enumerate(itl_residual):
                expected_residual = float(token_lateness[index + 1]) - float(token_lateness[index])
                if not math.isclose(
                    float(residual), expected_residual, rel_tol=0.0, abs_tol=1e-6
                ):
                    fail("task_token_timing_identity_mismatch")
            if not math.isclose(
                float(task["ttft_ms"]),
                float(task["server_processing_ms"]) + float(task["ttft_excess_ms"]),
                rel_tol=0.0,
                abs_tol=1e-3,
            ):
                fail("task_ttft_identity_mismatch")
        attachment = task_map["task-0006"]
        if (
            attachment.get("response_artifact_bytes") != 1048576
            or not finite_number(attachment.get("download_goodput_mbps"))
            or attachment["download_goodput_mbps"] <= 0
            or not finite_number(attachment.get("artifact_download_duration_ms"))
            or attachment["artifact_download_duration_ms"] <= 0
        ):
            fail("task_0006_download_evidence_invalid")
        expected_download_mbps = (
            float(attachment["response_artifact_bytes"])
            * 8.0
            / (float(attachment["artifact_download_duration_ms"]) / 1000.0)
            / 1_000_000.0
        )
        if not math.isclose(
            float(attachment["download_goodput_mbps"]),
            expected_download_mbps,
            rel_tol=1e-9,
            abs_tol=1e-9,
        ):
            fail("task_0006_download_identity_mismatch")
        for task_id in ("task-0010", "task-0016"):
            if (
                task_map[task_id].get("download_goodput_mbps") is not None
                or task_map[task_id].get("artifact_download_duration_ms") is not None
            ):
                fail("non_artifact_download_evidence_invalid")

        rtt = require_list(raw.get("rtt_samples_ms"), "rtt_samples_missing")
        loaded_rtt = require_list(
            raw.get("loaded_rtt_samples_ms"), "loaded_rtt_samples_missing"
        )
        if (
            len(rtt) != 20
            or len(loaded_rtt) != 0
            or any(not finite_number(value) or value <= 0 for value in rtt + loaded_rtt)
        ):
            fail("rtt_samples_invalid")

        recomputed_metrics, recomputed_groups, recomputed_score = recompute_quick_score(
            tasks, [float(value) for value in rtt], [float(value) for value in loaded_rtt]
        )
        for metric_id in EXPECTED_TYPED_METRIC_IDS:
            actual_typed = require_dict(typed_metrics[metric_id], "typed_metric_invalid")
            assert_metric_values(
                actual_typed, recomputed_metrics[metric_id], "typed_metric_recomputation_mismatch"
            )
        if set(group_scores) != EXPECTED_GROUP_IDS:
            fail("group_score_set_mismatch")
        for group_id, expected_value in recomputed_groups.items():
            if not equal_number(group_scores.get(group_id), expected_value):
                fail("group_score_recomputation_mismatch")
        confidence_basis = require_dict(
            score.get("confidence_basis"), "score_confidence_basis_invalid"
        )
        expected_score_keys = {
            "state", "value", "grade", "verdict", "confidence", "confidence_basis",
            "cap_reason", "not_computable_reason",
        }
        if set(score) != expected_score_keys:
            fail("score_fields_mismatch")
        if (
            score.get("state") != recomputed_score["state"]
            or not equal_number(score.get("value"), recomputed_score["value"])
            or score.get("grade") != recomputed_score["grade"]
            or score.get("verdict") != recomputed_score["verdict"]
            or score.get("confidence") != recomputed_score["confidence"]
            or confidence_basis != recomputed_score["confidence_basis"]
            or score.get("cap_reason") != recomputed_score["cap_reason"]
            or score.get("not_computable_reason") is not None
        ):
            fail("score_recomputation_mismatch")
        for metric_id, definition in measurement_specs.items():
            assert_envelope_metric(
                metric_id,
                require_dict(metrics[metric_id], "metric_invalid"),
                definition,
                recomputed_metrics.get(metric_id),
                radio_sample_count=radio_sample_count,
            )

        required_order = (
            "TOK-B01", "TOK-B02", "TOK-B05", "TOK-B07", "TOK-B09", "TOK-B10",
            "TOK-B11", "TOK-B14", "TOK-N03", "TOK-N04", "TOK-N05", "TOK-N06",
        )
        bottleneck = min(required_order, key=lambda metric_id: float(recomputed_metrics[metric_id]["score"]))
        target_severity = lambda metric_id: (
            "info"
            if float(recomputed_metrics[metric_id]["compliance_ratio"]) + 1e-12
            >= float(recomputed_metrics[metric_id]["target_compliance_ratio"])
            else "recommendation"
        )
        percent = lambda metric_id: f"{float(recomputed_metrics[metric_id]['compliance_ratio']) * 100.0:.1f}%"
        expected_conclusions = [
            ("token-verdict", "warning" if recomputed_score["verdict"] == "inconclusive" else (
                "info" if recomputed_score["verdict"] == "pass" else "failure"
            ), f"结论：{str(recomputed_score['verdict']).upper()}；证据置信度 {str(recomputed_score['confidence']).upper()}。", ["score:verdict", "score:confidence"]),
            ("token-task-completion", "info", "任务完成 3/3；任务成功率 100.0%。", ["metric:TOK-B01", "evidence:token-raw"]),
            ("token-behavior-profile", "info", "本次模拟的业务行为：上行突发需求、低时延启动需求、流式连续性需求、可选大文件下行需求。", ["profile:business.behavior_feature_ids"]),
            ("token-quick-evidence-limit", "warning", "快测仅覆盖文本、文档、图片各一个任务，不用于 95% 稳定性强结论。", ["profile:evidence_tier", "evidence:token-raw"]),
            ("token-target-rtt", target_severity("TOK-N03"), f"应用 RTT <100ms 达标比例 {percent('TOK-N03')}（目标 ≥95%）。", ["metric:TOK-N03"]),
            ("token-target-uplink", target_severity("TOK-N06"), f"上行速率达到各业务门限比例 {percent('TOK-N06')}（目标 ≥95%）。", ["metric:TOK-N06"]),
            (
                "token-target-stream-timeliness",
                target_severity("TOK-B07"),
                f"仿真 Token 准时到达比例 {percent('TOK-B07')}（目标 ≥95%）。",
                ["metric:TOK-B07"],
            ),
            ("token-primary-bottleneck", "recommendation", f"本次主要瓶颈：{bottleneck}，达标比例 {percent(bottleneck)}；优先改善该指标。", [f"metric:{bottleneck}"]),
        ]
        if recomputed_score["cap_reason"] is not None:
            expected_conclusions.append(
                (
                    "token-score-cap",
                    "failure",
                    f"评分封顶：{recomputed_score['cap_reason']}，总分最高 54。",
                    ["score:cap_reason"],
                )
            )
        if len(conclusions) != len(expected_conclusions):
            fail("conclusion_set_mismatch")
        for actual_value, (expected_id, expected_severity, expected_text, expected_basis) in zip(
            conclusions, expected_conclusions, strict=True
        ):
            conclusion = require_dict(actual_value, "conclusion_invalid")
            if (
                set(conclusion)
                != {"conclusion_id", "severity", "policy_id", "text", "basis"}
                or conclusion.get("conclusion_id") != expected_id
                or conclusion.get("severity") != expected_severity
                or conclusion.get("policy_id") != execution_contract["conclusion_policy_id"]
                or conclusion.get("text") != expected_text
                or conclusion.get("basis") != expected_basis
            ):
                fail("conclusion_contract_mismatch")

        evidence = require_dict(body.get("evidence"), "evidence_missing")
        refs = require_list(evidence.get("refs"), "evidence_refs_invalid")
        environment_events = require_list(
            evidence.get("environment_events"), "environment_events_invalid"
        )
        if (
            set(evidence)
            != {"raw_evidence_retained", "invalid_evidence_retained", "refs", "environment_events"}
            or evidence.get("raw_evidence_retained") is not True
            or evidence.get("invalid_evidence_retained") is not True
            or any(not isinstance(value, dict) for value in environment_events)
        ):
            fail("evidence_contract_mismatch")
        ref_map: dict[str, dict[str, Any]] = {}
        for value in refs:
            ref = require_dict(value, "evidence_ref_invalid")
            ref_id = ref.get("ref_id")
            if not isinstance(ref_id, str) or ref_id in ref_map:
                fail("evidence_ref_identity_invalid")
            if (
                set(ref)
                != {
                    "ref_id", "kind", "uri", "media_type", "digest", "record_count",
                    "redaction", "description",
                }
                or not isinstance(ref.get("description"), str)
                or not ref["description"].strip()
            ):
                fail("evidence_ref_contract_mismatch")
            ref_map[ref_id] = ref
        expected_ref_ids = {"profile-artifact", "runtime-artifact", "token-raw"}
        if radio_sample_count is not None:
            expected_ref_ids.add("radio-context")
        if set(ref_map) != expected_ref_ids:
            fail("evidence_ref_set_mismatch")
        for ref_id, file_name in (
            ("profile-artifact", "profile.json"),
            ("runtime-artifact", "runtime_plan.json"),
        ):
            ref = ref_map[ref_id]
            if (
                ref.get("kind") != "content_addressed_artifact"
                or ref.get("uri") != f"{PROFILE_ASSET_BASE}/{file_name}"
                or ref.get("media_type") != "application/json"
                or ref.get("record_count") != 1
                or ref.get("redaction") != "none"
                or ref.get("digest")
                != {
                    "algorithm": "sha256",
                    "canonicalization": "canonical-json-v1",
                    "value": f"sha256:{manifest_entries[file_name]}",
                }
            ):
                fail("artifact_evidence_ref_mismatch")
        token_ref = ref_map["token-raw"]
        if (
            token_ref.get("kind") != "inline_json_pointer"
            or token_ref.get("uri") != "#/category_payload/raw_evidence"
            or token_ref.get("media_type") != "application/json"
            or token_ref.get("digest") is not None
            or token_ref.get("record_count") != 3
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

        report: dict[str, object] = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "pass",
            "reason_code": "ok",
            "run_id": run_id,
            "run_uuid_unix_ms": run_uuid_ms,
            "run_start_delta_ms": run_start_delta_ms,
            "started_at_epoch_ms": started_at_ms,
            "ended_at_epoch_ms": ended_at_ms,
            "serialized_at_epoch_ms": serialized_at_ms,
            "room_user_version": EXPECTED_ROOM_VERSION,
            "room_identity_hash": room_identity,
            "frozen_source_sha256": source_before,
            "frozen_source_unchanged": True,
            "analysis_copy_used": True,
            "result_body_sha256": hashlib.sha256(result_text.encode("utf-8")).hexdigest(),
            "profile_sha256": profile_digest,
            "runtime_plan_sha256": runtime_digest,
            "successful_task_count": 3,
            "task_0006_response_artifact_bytes": 1048576,
            "score_state": "computed",
            "endpoint_server_version": None,
            "strict_result_schema": "pass",
            "typed_metrics_verified": len(typed_metrics),
            "envelope_metrics_verified": len(metrics),
            "typed_conclusions_verified": len(typed_conclusions),
        }
        return report, result_text


def canonical_path(path: Path) -> str:
    return os.path.normcase(os.path.abspath(os.fspath(path.resolve(strict=False))))


def assert_safe_result_output(
    output: Path,
    *,
    database: Path,
    manifest: Path,
) -> None:
    forbidden = {
        canonical_path(database),
        canonical_path(Path(str(database) + "-wal")),
        canonical_path(Path(str(database) + "-shm")),
        canonical_path(manifest),
        canonical_path(PROFILE_DIR / "profile.json"),
        canonical_path(PROFILE_DIR / "runtime_plan.json"),
        canonical_path(ROOM_SCHEMA_PATH),
    }
    if canonical_path(output) in forbidden:
        fail("result_output_alias_forbidden")
    if os.path.lexists(output):
        fail("result_output_exists")
    parent = output.parent
    try:
        if not parent.is_dir():
            fail("result_output_parent_invalid")
        current = parent
        while True:
            if current.is_symlink():
                fail("result_output_symlink_parent_forbidden")
            if current.parent == current:
                break
            current = current.parent
    except OSError:
        fail("result_output_parent_invalid")


def publish_result_output_atomic(output: Path, result_text: str) -> None:
    payload = result_text.encode("utf-8")
    temporary = output.parent / f".{output.name}.{uuid.uuid4().hex}.tmp"
    descriptor: int | None = None
    try:
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        with os.fdopen(descriptor, "wb", closefd=True) as stream:
            descriptor = None
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, output)
    except FileExistsError:
        fail("result_output_exists")
    except OSError:
        fail("result_output_publish_failed")
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            fail("result_output_temporary_cleanup_failed")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify one frozen Token Quick result through an isolated Room analysis copy"
    )
    parser.add_argument("database", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--manifest", type=Path, default=PROFILE_DIR / "manifest.sha256")
    parser.add_argument("--expected-server-base")
    parser.add_argument("--result-output", type=Path)
    args = parser.parse_args()

    try:
        if args.result_output is not None:
            assert_safe_result_output(
                args.result_output,
                database=args.database,
                manifest=args.manifest,
            )
        report, result_text = verify(
            args.database,
            run_id=args.run_id,
            manifest=args.manifest,
            expected_server_base=args.expected_server_base,
        )
        if args.result_output is not None:
            publish_result_output_atomic(args.result_output, result_text)
        exit_code = 0
    except VerificationFailure as error:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": error.reason_code,
            "run_id": args.run_id if RUN_ID_RE.fullmatch(args.run_id) else None,
        }
        exit_code = 1
    except Exception:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": "internal_verification_error",
            "run_id": args.run_id if RUN_ID_RE.fullmatch(args.run_id) else None,
        }
        exit_code = 1
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
