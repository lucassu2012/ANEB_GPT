from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify_token_quick_client_db.py"
STRICT_RESULT_VERIFIER = ROOT / "scripts" / "verify_result_jsonl.py"
RUN_ID = "019f6d5f-7400-7000-8000-000000000001"
PROFILE_SHA = "caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773"
RUNTIME_SHA = "83e5c828784e1df89939f1c42fbdd296e3bb02c362676e82603a34575f17e926"
RUNTIME_PLAN = json.loads(
    (ROOT / "profiles" / "published" / "token_multimodal_quick" / "runtime_plan.json").read_text(
        encoding="utf-8"
    )
)
PROFILE = json.loads(
    (ROOT / "profiles" / "published" / "token_multimodal_quick" / "profile.json").read_text(
        encoding="utf-8"
    )
)
TASK_EXECUTION = {
    task["task_id"]: {
        "expected_tokens": len(task["token_stream"]["intervals_ms"]),
        "request_count": 1 + int(task["response_artifact_bytes"] > 0),
    }
    for task in RUNTIME_PLAN["tasks"]
}
TYPED_METRIC_IDS = {
    "TOK-B01", "TOK-B02", "TOK-B03", "TOK-B04", "TOK-B05", "TOK-B07",
    "TOK-B09", "TOK-B10", "TOK-B11", "TOK-B14", "TOK-N03", "TOK-N04",
    "TOK-N05", "TOK-N06",
}
ENVELOPE_METRIC_IDS = (
    {f"TOK-B{index:02d}" for index in range(1, 16)}
    | {f"TOK-N{index:02d}" for index in range(1, 11)}
    | {"TOK-R01"}
)
SCORER_METRICS = {
    "TOK-B01": (1.0, 1.0, 3, 20, 0.99, 100.0),
    "TOK-B02": (25.0, 1.0, 3, 3, 0.95, 100.0),
    "TOK-B03": (500.0, None, 3, 1, 0.0, None),
    "TOK-B04": (640.0, 1.0, 3, 10, 0.95, 100.0),
    "TOK-B05": (140.0, 1.0, 3, 10, 0.95, 100.0),
    "TOK-B07": (1.0, 1.0, 1220, 100, 0.95, 100.0),
    "TOK-B09": (0.0, 1.0, 1217, 100, 0.98, 100.0),
    "TOK-B10": (0.0, 1.0, 1217, 100, 1.0, 100.0),
    "TOK-B11": (1.0, 1.0, 1220, 100, 0.99, 100.0),
    "TOK-B14": (0.0, 1.0, 1, 1, 0.95, 100.0),
    "TOK-N03": (42.0, 1.0, 20, 20, 0.95, 100.0),
    "TOK-N04": (0.0, 1.0, 20, 20, 0.95, 100.0),
    "TOK-N05": (0.0, 1.0, 24, 20, 0.98, 100.0),
    "TOK-N06": (8.5, 1.0 / 3.0, 3, 5, 0.95, 22.916666666666664),
}
RESULT_FIXTURE = (
    ROOT
    / "spec"
    / "examples"
    / "aneb-result-v1"
    / "token_simulation.not-computable.valid-schema.json"
)
ROOM_SCHEMA = (
    ROOT
    / "app"
    / "probe"
    / "schemas"
    / "com.aneb.probe.data.AnebDatabase"
    / "19.json"
)


def canonical_sha(value: object) -> str:
    payload = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def production_quality_target(target: object) -> object:
    if target is None:
        return None
    assert isinstance(target, dict)
    return {
        "operator": target.get("operator", ""),
        "value": target.get("value"),
        "values": dict(sorted(target.get("values", {}).items())),
        "policy_id": target.get("policy_id"),
        "required_compliance_ratio": target.get("required_compliance_ratio"),
        "provenance": target.get("provenance", ""),
    }


def production_metric(definition: dict[str, object]) -> dict[str, object]:
    metric_id = str(definition["metric_id"])
    scorer = SCORER_METRICS.get(metric_id)
    if scorer is None:
        value = compliance = score = None
        sample_count = 0
        minimum = int(definition["minimum_sample_count"])
        state = "missing"
        invalid_reason = "measurement_not_emitted_by_current_engine"
    else:
        value, compliance, sample_count, minimum, _, score = scorer
        state = "observed"
        invalid_reason = None
    domain = str(definition["domain"])
    normalized_domain = domain if domain in {"business", "network", "radio"} else "diagnostic"
    return {
        "label": definition.get("label", ""),
        "domain": normalized_domain,
        "unit": definition.get("unit", ""),
        "measurement_level": definition.get("measurement_level", ""),
        "state": state,
        "value": value,
        "compliance_ratio": compliance,
        "sample_count": sample_count,
        "minimum_sample_count": minimum,
        "source_event_ids": definition.get("source_event_ids", []),
        "direction": definition.get("direction", ""),
        "required_for_score": definition.get("required_for_score", False),
        "quality_target": production_quality_target(definition.get("quality_target")),
        "score": score,
        "formula_id": definition.get("formula_id", ""),
        "aggregation": definition.get("aggregation", ""),
        "components": {},
        "source_evidence_ref_ids": ["token-raw"],
        "invalid_reason": invalid_reason,
    }


def valid_body() -> dict[str, object]:
    body = json.loads(RESULT_FIXTURE.read_text(encoding="utf-8"))
    body["schema_version"] = "aneb-result-v2"
    body["producer"].update(
        component="aneb-probe-android",
        component_version="0.5.12-codex",
        exporter_version="aneb-result-exporter-v2",
        build_type="debug",
        serialized_at_epoch_ms=1784246405000,
    )
    body["run"].update(
        run_id=RUN_ID,
        started_at_epoch_ms=1784246400000,
        ended_at_epoch_ms=1784246405000,
        duration_ms=5000,
        status="completed",
        validity="valid",
        invalid_reason_codes=[],
        source_record_version="room-v19-token-envelope-v1",
    )
    body["profile"].update(profile_version="1.2.1", variant="quick")
    body["profile"]["profile_fingerprint"]["canonicalization"] = "canonical-json-v1"
    body["profile"]["profile_fingerprint"]["value"] = f"sha256:{PROFILE_SHA}"
    body["profile"]["runtime_artifact_hash"]["value"] = f"sha256:{RUNTIME_SHA}"
    body["context"]["endpoint"].update(
        server_base="https://aneb.invalid:8443", server_version=None
    )
    body["context"]["device"].update(
        availability="observed",
        manufacturer="HUAWEI",
        model="P40 Pro",
        os_name="Android",
        os_release="12",
        api_level=31,
        app_package="com.aneb.probe.codex",
        app_version_name="0.5.12-codex",
        app_version_code=44,
    )
    algorithm = body["evaluation"]["algorithm_versions"]
    algorithm.update(
        measurement_engine_version="token-simulation-engine-v2",
        score_policy_id="token-sim-score-v1",
        score_anchor_policy_id="compliance-anchors-v1",
        conclusion_policy_id="token-sim-conclusions-v2",
        finalized_at_epoch_ms=1784246405000,
    )
    body["category_payload"]["behavior_model"]["model_hash"]["value"] = (
        "sha256:76d4e93bdcd2951291169a867674004c7a13a28cd2a393f3da3aa0d14bd3cb5b"
    )
    score = body["evaluation"]["score"]
    score.update(
        state="computed",
        value=91.2,
        grade="A",
        verdict="pass",
        confidence="high",
        confidence_basis={
            "method_id": "token-sim-confidence-v1",
            "coverage_ratio": 1.0,
            "minimum_sample_satisfied": True,
        },
        cap_reason=None,
        not_computable_reason=None,
    )
    metric = body["evaluation"]["metrics"]["TOK-N03"]
    metric.update(
        state="observed",
        value=42.0,
        compliance_ratio=1.0,
        sample_count=20,
        score=95.0,
        invalid_reason=None,
    )
    body["evaluation"]["metrics"] = {
        metric_id: json.loads(json.dumps(metric))
        for metric_id in sorted(ENVELOPE_METRIC_IDS)
    }
    body["evaluation"]["group_scores"] = {"network": 95.0}
    body["evaluation"]["conclusions"] = [
        {
            "conclusion_id": "task-completion",
            "severity": "info",
            "policy_id": "token-sim-conclusions-v2",
            "text": "All three Token Quick tasks completed with retained evidence.",
            "basis": ["metric:TOK-N03", "evidence:token-raw"],
        }
    ]

    def task(
        task_id: str,
        workload_kind: str,
        upload_bytes: int,
        response_artifact_bytes: int,
    ) -> dict[str, object]:
        expected_tokens = TASK_EXECUTION[task_id]["expected_tokens"]
        return {
            "task_id": task_id,
            "workload_kind": workload_kind,
            "upload_bytes": upload_bytes,
            "response_artifact_bytes": response_artifact_bytes,
            "success": True,
            "network_failure": False,
            "error": None,
            "click_to_node_receive_ms": 25.0,
            "server_processing_ms": 500.0,
            "ttft_ms": 640.0,
            "ttft_excess_ms": 140.0,
            "upload_goodput_mbps": 8.5,
            "download_goodput_mbps": 8.5 if response_artifact_bytes else None,
            "artifact_download_duration_ms": (
                response_artifact_bytes * 8.0 / 8_500_000.0 * 1000.0
                if response_artifact_bytes
                else None
            ),
            "expected_tokens": expected_tokens,
            "unique_tokens": expected_tokens,
            "duplicate_tokens": 0,
            "token_lateness_ms": [1.0] * expected_tokens,
            "itl_residual_ms": [0.0] * (expected_tokens - 1),
            "request_count": TASK_EXECUTION[task_id]["request_count"],
            "failed_request_count": 0,
        }

    body["category_payload"]["raw_evidence"].update(
        invalid_reason=None,
        rtt_samples_ms=[42.0] * 20,
        loaded_rtt_samples_ms=[],
        tasks=[
            task("task-0006", "text", 16384, 1048576),
            task("task-0010", "document", 5242880, 0),
            task("task-0016", "image", 10485760, 0),
        ],
    )
    body["evaluation"]["score"] = {
        "state": "computed",
        "value": 84.2,
        "grade": "B",
        "verdict": "inconclusive",
        "confidence": "low",
        "confidence_basis": {
            "method_id": "token-sample-coverage-v1",
            "coverage_ratio": 0.15,
            "minimum_sample_satisfied": False,
        },
        "cap_reason": None,
        "not_computable_reason": None,
    }
    body["evaluation"]["group_scores"] = {
        "efficiency": 100.0,
        "interaction": 100.0,
        "multimodal_transfer": 36.9,
        "network_stability": 100.0,
        "task_completion": 100.0,
    }
    body["evaluation"]["metrics"] = {
        str(definition["metric_id"]): production_metric(definition)
        for definition in PROFILE["measurements"]
    }
    conclusion_specs = [
        ("token-verdict", "warning", "结论：INCONCLUSIVE；证据置信度 LOW。", ["score:verdict", "score:confidence"]),
        ("token-task-completion", "info", "任务完成 3/3；任务成功率 100.0%。", ["metric:TOK-B01", "evidence:token-raw"]),
        ("token-behavior-profile", "info", "本次模拟的业务行为：上行突发需求、低时延启动需求、流式连续性需求、可选大文件下行需求。", ["profile:business.behavior_feature_ids"]),
        ("token-quick-evidence-limit", "warning", "快测仅覆盖文本、文档、图片各一个任务，不用于 95% 稳定性强结论。", ["profile:evidence_tier", "evidence:token-raw"]),
        ("token-target-rtt", "info", "应用 RTT <100ms 达标比例 100.0%（目标 ≥95%）。", ["metric:TOK-N03"]),
        ("token-target-uplink", "recommendation", "上行速率达到各业务门限比例 33.3%（目标 ≥95%）。", ["metric:TOK-N06"]),
        ("token-target-stream-timeliness", "info", "仿真 Token 准时到达比例 100.0%（目标 ≥95%）。", ["metric:TOK-B07"]),
        ("token-primary-bottleneck", "recommendation", "本次主要瓶颈：TOK-N06，达标比例 33.3%；优先改善该指标。", ["metric:TOK-N06"]),
    ]
    body["evaluation"]["conclusions"] = [
        {
            "conclusion_id": conclusion_id,
            "severity": severity,
            "policy_id": "token-sim-conclusions-v2",
            "text": conclusion_text,
            "basis": basis,
        }
        for conclusion_id, severity, conclusion_text, basis in conclusion_specs
    ]
    body["evidence"] = {
        "raw_evidence_retained": True,
        "invalid_evidence_retained": True,
        "refs": [
            {
                "ref_id": "profile-artifact",
                "kind": "content_addressed_artifact",
                "uri": "profiles/published/token_multimodal_quick/profile.json",
                "media_type": "application/json",
                "digest": {
                    "algorithm": "sha256",
                    "canonicalization": "canonical-json-v1",
                    "value": f"sha256:{PROFILE_SHA}",
                },
                "record_count": 1,
                "redaction": "none",
                "description": "Profile artifact retained for the run.",
            },
            {
                "ref_id": "runtime-artifact",
                "kind": "content_addressed_artifact",
                "uri": "profiles/published/token_multimodal_quick/runtime_plan.json",
                "media_type": "application/json",
                "digest": {
                    "algorithm": "sha256",
                    "canonicalization": "canonical-json-v1",
                    "value": f"sha256:{RUNTIME_SHA}",
                },
                "record_count": 1,
                "redaction": "none",
                "description": "Runtime artifact retained for the run.",
            },
            {
                "ref_id": "token-raw",
                "kind": "inline_json_pointer",
                "uri": "#/category_payload/raw_evidence",
                "media_type": "application/json",
                "digest": None,
                "record_count": 3,
                "redaction": "none",
                "description": "Inline Token task and RTT evidence.",
            },
        ],
        "environment_events": [],
    }
    return body


def typed_metrics(body: dict[str, object]) -> dict[str, object]:
    result: dict[str, object] = {}
    for metric_id, metric in body["evaluation"]["metrics"].items():
        if metric_id not in TYPED_METRIC_IDS:
            continue
        scorer = SCORER_METRICS[metric_id]
        result[metric_id] = {
            "value": metric["value"],
            "compliance_ratio": metric["compliance_ratio"],
            "sample_count": metric["sample_count"],
            "minimum_sample_count": metric["minimum_sample_count"],
            "target_compliance_ratio": scorer[4],
            "score": metric["score"],
        }
    return result


def typed_evidence(body: dict[str, object]) -> dict[str, object]:
    return {
        "contract_version": "aneb-token-run-evidence-v1",
        "variant": "quick",
        **body["category_payload"]["raw_evidence"],
    }


def create_room_schema(connection: sqlite3.Connection) -> None:
    exported = json.loads(ROOM_SCHEMA.read_text(encoding="utf-8"))["database"]
    for entity in exported["entities"]:
        connection.execute(
            entity["createSql"].replace("${TABLE_NAME}", entity["tableName"])
        )
        for index in entity["indices"]:
            connection.execute(
                index["createSql"].replace("${TABLE_NAME}", entity["tableName"])
            )
    connection.execute(
        "CREATE TABLE IF NOT EXISTS room_master_table "
        "(id INTEGER PRIMARY KEY,identity_hash TEXT)"
    )
    connection.execute(
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
        (exported["identityHash"],),
    )
    connection.execute("CREATE TABLE android_metadata (locale TEXT)")
    connection.execute("INSERT INTO android_metadata(locale) VALUES('en_US')")
    connection.execute(f"PRAGMA user_version = {exported['version']}")


def write_database(
    path: Path,
    body: dict[str, object],
    *,
    full_room_schema: bool = True,
    keep_wal_open: bool = False,
) -> sqlite3.Connection | None:
    serialized = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    connection = sqlite3.connect(path)
    should_close = True
    try:
        if keep_wal_open:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA wal_autocheckpoint = 0")
        if not full_room_schema:
            connection.execute(
                "CREATE TABLE token_simulation_result ("
                "runId TEXT PRIMARY KEY, profileId TEXT NOT NULL, "
                "profileVersion TEXT NOT NULL, variant TEXT NOT NULL, "
                "totalScore REAL, grade TEXT, verdict TEXT NOT NULL, "
                "evidenceJson TEXT NOT NULL)"
            )
            connection.execute(
                "CREATE TABLE result_envelope ("
                "runId TEXT PRIMARY KEY, schemaVersion TEXT NOT NULL, "
                "testType TEXT NOT NULL, canonicalSha256 TEXT NOT NULL, "
                "bodyJson TEXT NOT NULL)"
            )
            connection.execute(
                "INSERT INTO token_simulation_result VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    RUN_ID,
                    "token_multimodal_quick",
                    "1.2.1",
                    "quick",
                    91.2,
                    "A",
                    "PASS",
                    json.dumps(typed_evidence(body), separators=(",", ":")),
                ),
            )
            connection.execute(
                "INSERT INTO result_envelope VALUES (?, ?, ?, ?, ?)",
                (
                    RUN_ID,
                    "aneb-result-v2",
                    "token_simulation",
                    canonical_sha(body),
                    serialized,
                ),
            )
            connection.commit()
            return None

        create_room_schema(connection)
        score = body["evaluation"]["score"]
        algorithm = body["evaluation"]["algorithm_versions"]
        model = body["category_payload"]["behavior_model"]
        connection.execute(
            """
            INSERT INTO token_simulation_result (
                runId, startedAtEpochMs, serverBase, claimScope, profileId,
                profileVersion, behaviorModelId, behaviorModelVersion,
                behaviorModelHash, calibrationStatus, variant, scorePolicyId,
                scoreAnchorPolicyId, conclusionPolicyId, totalScore, grade,
                verdict, confidence, capReason, metricsJson, conclusionsJson,
                evidenceJson
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                RUN_ID,
                body["run"]["started_at_epoch_ms"],
                body["context"]["endpoint"]["server_base"],
                body["claim"]["scope"],
                "token_multimodal_quick",
                "1.2.1",
                model["model_id"],
                model["model_version"],
                model["model_hash"]["value"],
                model["calibration_status"],
                "quick",
                algorithm["score_policy_id"],
                algorithm["score_anchor_policy_id"],
                algorithm["conclusion_policy_id"],
                score["value"],
                score["grade"],
                score["verdict"].upper(),
                score["confidence"].upper(),
                score["cap_reason"],
                json.dumps(typed_metrics(body), separators=(",", ":")),
                json.dumps(
                    [item["text"] for item in body["evaluation"]["conclusions"]],
                    separators=(",", ":"),
                ),
                json.dumps(typed_evidence(body), separators=(",", ":")),
            ),
        )
        connection.execute(
            """
            INSERT INTO result_envelope (
                runId, schemaVersion, testType, startedAtEpochMs,
                serializedAtEpochMs, canonicalSha256, bodyJson
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                RUN_ID,
                "aneb-result-v2",
                "token_simulation",
                body["run"]["started_at_epoch_ms"],
                body["producer"]["serialized_at_epoch_ms"],
                canonical_sha(body),
                serialized,
            ),
        )
        connection.commit()
        if keep_wal_open:
            should_close = False
            return connection
    finally:
        if should_close:
            connection.close()
    return None


def update_envelope(connection: sqlite3.Connection, body: dict[str, object]) -> None:
    serialized = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    connection.execute(
        "UPDATE result_envelope SET canonicalSha256 = ?, bodyJson = ? WHERE runId = ?",
        (canonical_sha(body), serialized, RUN_ID),
    )
    connection.commit()


def frozen_hashes(database: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in (database, Path(str(database) + "-wal"), Path(str(database) + "-shm")):
        if path.exists():
            result[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


class TokenQuickClientDbVerifierTests(unittest.TestCase):
    def run_verifier(
        self, database: Path, *, result_output: Path | None = None
    ) -> subprocess.CompletedProcess[str]:
        command = [sys.executable, str(SCRIPT), str(database), "--run-id", RUN_ID]
        if result_output is not None:
            command.extend(("--result-output", str(result_output)))
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )

    def test_positive_fixture_is_valid_under_the_strict_jsonl_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            jsonl = Path(temporary) / "result.jsonl"
            jsonl.write_text(
                json.dumps(valid_body(), ensure_ascii=False, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    sys.executable,
                    str(STRICT_RESULT_VERIFIER),
                    str(jsonl),
                    "--root",
                    str(ROOT),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
                timeout=30,
            )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_accepts_exact_completed_quick_result_and_reports_bound_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            completed = self.run_verifier(database)

        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertEqual(19, report["room_user_version"])
        self.assertTrue(report["frozen_source_unchanged"])
        self.assertTrue(report["analysis_copy_used"])
        self.assertEqual(3, report["successful_task_count"])
        self.assertEqual(1048576, report["task_0006_response_artifact_bytes"])
        self.assertEqual(14, report["typed_metrics_verified"])
        self.assertEqual(26, report["envelope_metrics_verified"])
        self.assertEqual(f"sha256:{PROFILE_SHA}", report["profile_sha256"])
        self.assertEqual(f"sha256:{RUNTIME_SHA}", report["runtime_plan_sha256"])
        self.assertEqual(1784246400000, report["run_uuid_unix_ms"])
        self.assertEqual(0, report["run_start_delta_ms"])
        self.assertEqual(1784246400000, report["started_at_epoch_ms"])
        self.assertEqual(1784246405000, report["ended_at_epoch_ms"])
        self.assertEqual(1784246405000, report["serialized_at_epoch_ms"])

    def test_rejects_self_consistent_result_whose_uuidv7_time_does_not_match_run_time(self) -> None:
        body = valid_body()
        shifted_start = body["run"]["started_at_epoch_ms"] + 86_400_000
        shifted_end = body["run"]["ended_at_epoch_ms"] + 86_400_000
        body["run"].update(
            started_at_epoch_ms=shifted_start,
            ended_at_epoch_ms=shifted_end,
        )
        body["producer"]["serialized_at_epoch_ms"] = shifted_end
        body["evaluation"]["algorithm_versions"]["finalized_at_epoch_ms"] = shifted_end
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "run_time_identity_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_duration_not_derived_from_started_and_ended_times(self) -> None:
        body = valid_body()
        body["run"]["duration_ms"] = 4999
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "run_duration_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_serialization_time_not_equal_to_production_end_time(self) -> None:
        body = valid_body()
        body["producer"]["serialized_at_epoch_ms"] += 1
        body["evaluation"]["algorithm_versions"]["finalized_at_epoch_ms"] += 1
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "run_serialization_time_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_result_output_is_exclusively_and_atomically_created(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            result_output = root / "client-result.json"
            body = valid_body()
            write_database(database, body)
            completed = self.run_verifier(database, result_output=result_output)
            temporary_artifacts = list(root.glob(".client-result.json.*.tmp"))

            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            self.assertEqual(body, json.loads(result_output.read_text(encoding="utf-8")))
            self.assertEqual([], temporary_artifacts)

    def test_result_output_refuses_existing_file_without_modifying_it(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            result_output = root / "client-result.json"
            write_database(database, valid_body())
            result_output.write_bytes(b"sentinel-do-not-overwrite")
            completed = self.run_verifier(database, result_output=result_output)

            self.assertEqual(b"sentinel-do-not-overwrite", result_output.read_bytes())
        self.assertEqual(1, completed.returncode)
        self.assertEqual("result_output_exists", json.loads(completed.stdout)["reason_code"])

    def test_result_output_refuses_database_alias_without_modifying_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            before = frozen_hashes(database)
            completed = self.run_verifier(database, result_output=database)
            after = frozen_hashes(database)

        self.assertEqual(before, after)
        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "result_output_alias_forbidden",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_user_version_zero_with_only_two_trimmed_tables(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body(), full_room_schema=False)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        report = json.loads(completed.stdout)
        self.assertEqual("fail", report["status"])
        self.assertIn("room_", report["reason_code"])

    def test_rejects_room_create_sql_constraint_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            exported = json.loads(ROOM_SCHEMA.read_text(encoding="utf-8"))["database"]
            entity = next(
                value for value in exported["entities"] if value["tableName"] == "echo_sample"
            )
            sql = entity["createSql"].replace("${TABLE_NAME}", entity["tableName"])
            drifted = sql[:-1] + ", CHECK(1 = 1))"
            connection = sqlite3.connect(database)
            try:
                connection.execute("DROP TABLE echo_sample")
                connection.execute(drifted)
                for index in entity["indices"]:
                    connection.execute(
                        index["createSql"].replace("${TABLE_NAME}", entity["tableName"])
                    )
                connection.commit()
            finally:
                connection.close()
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "room_create_sql_mismatch", json.loads(completed.stdout)["reason_code"]
        )

    def test_rejects_malformed_android_metadata_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            connection = sqlite3.connect(database)
            try:
                connection.execute("DROP TABLE android_metadata")
                connection.execute(
                    "CREATE TABLE android_metadata (locale TEXT, injected TEXT)"
                )
                connection.execute(
                    "INSERT INTO android_metadata(locale, injected) VALUES('en_US', 'x')"
                )
                connection.commit()
            finally:
                connection.close()
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "android_metadata_schema_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_extra_room_master_index(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            connection = sqlite3.connect(database)
            try:
                connection.execute(
                    "CREATE INDEX injected_room_master_idx "
                    "ON room_master_table(identity_hash)"
                )
                connection.commit()
            finally:
                connection.close()
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "room_master_schema_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_typed_row_and_frozen_envelope_score_disagreement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            connection = sqlite3.connect(database)
            try:
                connection.execute(
                    "UPDATE token_simulation_result SET totalScore = 88.8 WHERE runId = ?",
                    (RUN_ID,),
                )
                connection.commit()
            finally:
                connection.close()
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        report = json.loads(completed.stdout)
        self.assertEqual("typed_envelope_score_mismatch", report["reason_code"])

    def test_rejects_all_unsuccessful_tasks_even_with_recomputed_digest(self) -> None:
        body = valid_body()
        for task in body["category_payload"]["raw_evidence"]["tasks"]:
            task.update(
                success=False,
                network_failure=True,
                error="injected_failure",
                failed_request_count=task["request_count"],
            )
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        report = json.loads(completed.stdout)
        self.assertEqual("task_not_successful", report["reason_code"])

    def test_rejects_zeroed_attachment_evidence_against_runtime_contract(self) -> None:
        body = valid_body()
        body["category_payload"]["raw_evidence"]["tasks"][0].update(
            response_artifact_bytes=0,
            download_goodput_mbps=None,
            artifact_download_duration_ms=None,
        )
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        report = json.loads(completed.stdout)
        self.assertEqual("task_runtime_contract_mismatch", report["reason_code"])

    def test_rejects_rewritten_token_and_request_counts_against_runtime_plan(self) -> None:
        body = valid_body()
        task = body["category_payload"]["raw_evidence"]["tasks"][0]
        task.update(
            expected_tokens=4,
            unique_tokens=4,
            token_lateness_ms=[1.0] * 4,
            itl_residual_ms=[0.5] * 3,
            request_count=1,
        )
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "task_token_evidence_invalid",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_quick_run_with_nonempty_loaded_rtt_samples(self) -> None:
        body = valid_body()
        body["category_payload"]["raw_evidence"]["loaded_rtt_samples_ms"] = [55.0]
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual("rtt_samples_invalid", json.loads(completed.stdout)["reason_code"])

    def test_rejects_schema_forbidden_api_key_with_recomputed_digest(self) -> None:
        body = valid_body()
        body["producer"]["api_key"] = "must-never-be-accepted"
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        report = json.loads(completed.stdout)
        self.assertEqual("result_schema_invalid", report["reason_code"])

    def test_rejects_typed_metrics_and_conclusions_disagreement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            connection = sqlite3.connect(database)
            try:
                connection.execute(
                    "UPDATE token_simulation_result "
                    "SET metricsJson = ?, conclusionsJson = ? WHERE runId = ?",
                    ('{}', '["fabricated"]', RUN_ID),
                )
                connection.commit()
            finally:
                connection.close()
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        report = json.loads(completed.stdout)
        self.assertIn(
            report["reason_code"],
            {"typed_metrics_mismatch", "typed_conclusions_mismatch"},
        )

    def test_rejects_envelope_metric_omitted_from_typed_room_projection(self) -> None:
        body = valid_body()
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            connection = sqlite3.connect(database)
            try:
                incomplete = typed_metrics(body)
                del incomplete["TOK-B01"]
                connection.execute(
                    "UPDATE token_simulation_result SET metricsJson = ? WHERE runId = ?",
                    (json.dumps(incomplete, separators=(",", ":")), RUN_ID),
                )
                connection.commit()
            finally:
                connection.close()
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "typed_metrics_mismatch", json.loads(completed.stdout)["reason_code"]
        )

    def test_rejects_unknown_metric_added_to_envelope(self) -> None:
        body = valid_body()
        body["evaluation"]["metrics"]["TOK-X99"] = json.loads(
            json.dumps(body["evaluation"]["metrics"]["TOK-N03"])
        )
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "envelope_metric_set_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_synchronized_forged_behavior_model_identity(self) -> None:
        body = valid_body()
        model = body["category_payload"]["behavior_model"]
        model.update(
            model_id="forged-model",
            model_version="999.0.0",
        )
        model["model_hash"]["value"] = "sha256:" + "0" * 64
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "behavior_model_contract_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_forged_algorithm_policy_set(self) -> None:
        body = valid_body()
        body["evaluation"]["algorithm_versions"]["conclusion_policy_id"] = (
            "token-sim-conclusions-v1"
        )
        for conclusion in body["evaluation"]["conclusions"]:
            conclusion["policy_id"] = "token-sim-conclusions-v1"
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "algorithm_contract_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_synchronized_forged_total_score_grade_and_confidence(self) -> None:
        body = valid_body()
        body["evaluation"]["score"].update(
            value=99.9, grade="A", verdict="pass", confidence="high"
        )
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertNotEqual(0, completed.returncode)
        self.assertEqual("score_recomputation_mismatch", json.loads(completed.stdout)["reason_code"])

    def test_rejects_synchronized_forged_group_scores(self) -> None:
        body = valid_body()
        body["evaluation"]["group_scores"]["multimodal_transfer"] = 100.0
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertNotEqual(0, completed.returncode)
        self.assertEqual(
            "group_score_recomputation_mismatch", json.loads(completed.stdout)["reason_code"]
        )

    def test_rejects_profile_metric_definition_substitution(self) -> None:
        body = valid_body()
        body["evaluation"]["metrics"]["TOK-N03"]["label"] = "forged rtt"
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertNotEqual(0, completed.returncode)
        self.assertEqual(
            "envelope_metric_definition_mismatch", json.loads(completed.stdout)["reason_code"]
        )

    def test_rejects_scorer_target_ratio_replaced_by_profile_target(self) -> None:
        body = valid_body()
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            connection = sqlite3.connect(database)
            metrics = json.loads(
                connection.execute(
                    "SELECT metricsJson FROM token_simulation_result WHERE runId = ?", (RUN_ID,)
                ).fetchone()[0]
            )
            metrics["TOK-B01"]["target_compliance_ratio"] = 0.95
            connection.execute(
                "UPDATE token_simulation_result SET metricsJson = ? WHERE runId = ?",
                (json.dumps(metrics, separators=(",", ":")), RUN_ID),
            )
            connection.commit()
            connection.close()
            completed = self.run_verifier(database)

        self.assertNotEqual(0, completed.returncode)
        self.assertEqual(
            "typed_metric_recomputation_mismatch", json.loads(completed.stdout)["reason_code"]
        )

    def test_rejects_forged_token_evidence_reference_count(self) -> None:
        body = valid_body()
        next(
            ref for ref in body["evidence"]["refs"] if ref["ref_id"] == "token-raw"
        )["record_count"] = 999
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertNotEqual(0, completed.returncode)
        self.assertEqual("token_evidence_ref_mismatch", json.loads(completed.stdout)["reason_code"])

    def test_rejects_synchronized_misleading_conclusion_text(self) -> None:
        body = valid_body()
        for conclusion in body["evaluation"]["conclusions"]:
            conclusion["text"] = "Everything is excellent despite the retained evidence."
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertNotEqual(0, completed.returncode)
        self.assertEqual("conclusion_contract_mismatch", json.loads(completed.stdout)["reason_code"])

    def test_rejects_ghost_profile_and_network_evidence_refs(self) -> None:
        for mutator, reason in (
            (
                lambda body: body["profile"].update(profile_evidence_ref_id="ghost"),
                "profile_runtime_identity_mismatch",
            ),
            (
                lambda body: body["context"]["network"].update(evidence_ref_ids=["ghost"]),
                "network_evidence_refs_invalid",
            ),
        ):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as temporary:
                body = valid_body()
                mutator(body)
                database = Path(temporary) / "aneb-probe.db"
                write_database(database, body)
                completed = self.run_verifier(database)
                self.assertNotEqual(0, completed.returncode)
                self.assertEqual(reason, json.loads(completed.stdout)["reason_code"])

    def test_rejects_task_order_and_timing_identity_substitution(self) -> None:
        cases = (
            (
                lambda body: body["category_payload"]["raw_evidence"]["tasks"].reverse(),
                "task_order_mismatch",
            ),
            (
                lambda body: body["category_payload"]["raw_evidence"]["tasks"][0].update(
                    ttft_ms=999.0
                ),
                "task_ttft_identity_mismatch",
            ),
            (
                lambda body: body["category_payload"]["raw_evidence"]["tasks"][0][
                    "itl_residual_ms"
                ].__setitem__(0, 0.5),
                "task_token_timing_identity_mismatch",
            ),
        )
        for mutator, reason in cases:
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as temporary:
                body = valid_body()
                mutator(body)
                database = Path(temporary) / "aneb-probe.db"
                write_database(database, body)
                completed = self.run_verifier(database)
                self.assertNotEqual(0, completed.returncode)
                self.assertEqual(reason, json.loads(completed.stdout)["reason_code"])

    def test_rejects_extra_same_run_token_event_row(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            connection = sqlite3.connect(database)
            connection.execute(
                """
                INSERT INTO token_event (
                    runId, scenarioKey, streamIndex, seq, schedUs, preFlushUs,
                    arrivalNanos, payloadBytes, sameReadBatch
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (RUN_ID, "forged", 0, 0, 1, 1, 1000, 1, 0),
            )
            connection.commit()
            connection.close()
            completed = self.run_verifier(database)

        self.assertNotEqual(0, completed.returncode)
        self.assertEqual("unexpected_same_run_rows", json.loads(completed.stdout)["reason_code"])

    def test_wal_and_shm_source_bytes_are_unchanged_by_analysis(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            writer = write_database(database, valid_body(), keep_wal_open=True)
            self.assertIsNotNone(writer)
            try:
                before = frozen_hashes(database)
                self.assertIn("aneb-probe.db-wal", before)
                self.assertIn("aneb-probe.db-shm", before)
                completed = self.run_verifier(database)
                after = frozen_hashes(database)
            finally:
                writer.close()

        self.assertEqual(before, after)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertTrue(report["frozen_source_unchanged"])
        self.assertTrue(report["analysis_copy_used"])


if __name__ == "__main__":
    unittest.main()
