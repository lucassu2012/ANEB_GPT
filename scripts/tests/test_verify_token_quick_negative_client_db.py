from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify_token_quick_negative_client_db.py"
RUN_ID = "019f6d5f-7400-7000-8000-000000000001"
REASON = "receipt_missing"
NEGATIVE_SERVER_BASE = "http://127.0.0.1:18765"

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_verify_token_quick_client_db as positive_fixture  # noqa: E402


def negative_body() -> dict[str, object]:
    body = positive_fixture.valid_body()
    body["context"]["endpoint"]["server_base"] = NEGATIVE_SERVER_BASE
    body["run"].update(
        status="failed",
        validity="invalid",
        invalid_reason_codes=[REASON],
    )
    body["evaluation"]["score"] = {
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
        "cap_reason": REASON,
        "not_computable_reason": REASON,
    }
    body["evaluation"]["group_scores"] = {}
    for metric in body["evaluation"]["metrics"].values():
        metric.update(
            state="missing",
            value=None,
            compliance_ratio=None,
            sample_count=0,
            score=None,
            source_evidence_ref_ids=["token-raw"],
            invalid_reason="measurement_not_emitted_by_current_engine",
        )
    conclusion_text = (
        "测试证据无效：节点没有返回机器可读的能力回执，测试已在发送首个业务请求前停止；"
        "原始数据已保留，评分被抑制。"
    )
    body["evaluation"]["conclusions"] = [
        {
            "conclusion_id": "token-invalid-evidence",
            "severity": "failure",
            "policy_id": "token-sim-conclusions-v2",
            "text": conclusion_text,
            "basis": ["evidence:token-raw", f"invalid_reason:{REASON}"],
        }
    ]
    body["category_payload"]["raw_evidence"] = {
        "invalid_reason": REASON,
        "rtt_samples_ms": [],
        "loaded_rtt_samples_ms": [],
        "tasks": [],
    }
    for evidence_ref in body["evidence"]["refs"]:
        if evidence_ref["ref_id"] == "token-raw":
            evidence_ref["record_count"] = 0
    return body


def create_negative_database(path: Path) -> sqlite3.Connection:
    body = negative_body()
    connection = positive_fixture.write_database(
        path,
        body,
        keep_wal_open=True,
    )
    assert connection is not None
    connection.execute(
        "UPDATE token_simulation_result SET metricsJson = ? WHERE runId = ?",
        ("{}", RUN_ID),
    )
    connection.commit()
    return connection


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_inventory(path: Path, database: Path) -> None:
    files = []
    for source in (
        database,
        Path(str(database) + "-wal"),
        Path(str(database) + "-shm"),
    ):
        if source.exists():
            files.append(
                {
                    "name": source.name,
                    "state": "present",
                    "bytes": source.stat().st_size,
                    "sha256": sha256(source),
                }
            )
        else:
            files.append(
                {
                    "name": source.name,
                    "state": "absent",
                }
            )
    path.write_text(
        json.dumps(
            {
                "schema": "aneb-frozen-room-copy",
                "schema_version": "1.0.0",
                "captured_at_utc": "2026-07-19T00:00:00.0000000Z",
                "app_process_state": "stopped_before_copy",
                "files": files,
            },
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )


class NegativeClientDbVerifierTests(unittest.TestCase):
    def run_verifier(
        self,
        database: Path,
        inventory: Path,
        *,
        run_id: str = RUN_ID,
        expected_server_base: str | None = None,
        result_output: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = [
                sys.executable,
                str(SCRIPT),
                str(database),
                "--inventory",
                str(inventory),
                "--run-id",
                run_id,
        ]
        if expected_server_base is not None:
            command.extend(["--expected-server-base", expected_server_base])
        if result_output is not None:
            command.extend(["--result-output", str(result_output)])
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )

    def assert_rejected(
        self,
        completed: subprocess.CompletedProcess[str],
        expected_reason: str,
    ) -> None:
        self.assertEqual(1, completed.returncode, completed.stdout + completed.stderr)
        self.assertEqual("", completed.stderr)
        self.assertEqual(1, len(completed.stdout.splitlines()))
        report = json.loads(completed.stdout)
        self.assertEqual("fail", report["status"])
        self.assertEqual(expected_reason, report["reason_code"])

    def test_accepts_exact_receipt_missing_negative_result(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertEqual("", completed.stderr)
        self.assertEqual(1, len(completed.stdout.splitlines()))
        self.assertLessEqual(len(completed.stdout.encode("utf-8")), 8192)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertEqual(REASON, report["negative_reason_code"])
        self.assertEqual(19, report["room_user_version"])
        self.assertEqual(0, report["business_task_count"])
        self.assertEqual(0, report["business_kpi_observation_count"])
        self.assertEqual(0, report["business_artifact_count"])
        self.assertEqual(0, report["network_score_count"])
        self.assertTrue(report["frozen_source_unchanged"])
        self.assertTrue(report["analysis_copy_used"])
        self.assertNotIn("adb_serial", report)
        self.assertNotIn("device_serial", report)
        self.assertNotIn("serial_number", report)

    def test_accepts_checkpointed_database_with_both_room_sidecars_absent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            connection.close()
            self.assertFalse(Path(str(database) + "-wal").exists())
            self.assertFalse(Path(str(database) + "-shm").exists())
            write_inventory(inventory, database)

            completed = self.run_verifier(database, inventory)

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertTrue(report["frozen_source_unchanged"])

    def test_rejects_only_one_room_sidecar_present(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            connection.close()
            Path(str(database) + "-shm").write_bytes(b"not-a-paired-room-snapshot")
            write_inventory(inventory, database)

            completed = self.run_verifier(database, inventory)

        self.assert_rejected(completed, "room_inventory_sidecar_state_invalid")

    def test_rejects_forged_absent_inventory_when_sidecars_exist(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                write_inventory(inventory, database)
                body = json.loads(inventory.read_text(encoding="utf-8"))
                for entry in body["files"][1:]:
                    name = entry["name"]
                    entry.clear()
                    entry.update(name=name, state="absent")
                inventory.write_text(
                    json.dumps(body, separators=(",", ":")), encoding="utf-8"
                )

                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "room_frozen_absence_mismatch")

    def test_rejects_noncanonical_absent_entry_with_zero_and_null_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            connection.close()
            write_inventory(inventory, database)
            body = json.loads(inventory.read_text(encoding="utf-8"))
            body["files"][1].update(bytes=0, sha256=None)
            inventory.write_text(
                json.dumps(body, separators=(",", ":")), encoding="utf-8"
            )

            completed = self.run_verifier(database, inventory)

        self.assert_rejected(completed, "room_inventory_entry_invalid")

    def test_publishes_one_bound_result_and_reports_profile_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            result_output = root / "client-result.json"
            connection = create_negative_database(database)
            try:
                write_inventory(inventory, database)
                completed = self.run_verifier(
                    database,
                    inventory,
                    expected_server_base=NEGATIVE_SERVER_BASE,
                    result_output=result_output,
                )
            finally:
                connection.close()

            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            report = json.loads(completed.stdout)
            self.assertTrue(result_output.is_file())
            self.assertEqual(sha256(result_output), report["result_body_sha256"])
            self.assertRegex(report["profile_sha256"], r"^sha256:[0-9a-f]{64}$")
            self.assertEqual(NEGATIVE_SERVER_BASE, report["endpoint_server_base"])

    def test_rejects_a_different_machine_reason_even_when_all_copies_match(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                body = negative_body()
                other = "receipt_digest_mismatch"
                body["run"]["invalid_reason_codes"] = [other]
                body["evaluation"]["score"]["cap_reason"] = other
                body["evaluation"]["score"]["not_computable_reason"] = other
                body["evaluation"]["conclusions"][0]["basis"] = [
                    "evidence:token-raw",
                    f"invalid_reason:{other}",
                ]
                body["category_payload"]["raw_evidence"]["invalid_reason"] = other
                positive_fixture.update_envelope(connection, body)
                connection.execute(
                    "UPDATE token_simulation_result SET capReason = ?, evidenceJson = ? WHERE runId = ?",
                    (
                        other,
                        json.dumps(
                            {
                                "contract_version": "aneb-token-run-evidence-v1",
                                "variant": "quick",
                                "invalid_reason": other,
                                "rtt_samples_ms": [],
                                "loaded_rtt_samples_ms": [],
                                "tasks": [],
                            },
                            separators=(",", ":"),
                        ),
                        RUN_ID,
                    ),
                )
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "negative_run_identity_mismatch")

    def test_rejects_room_and_strict_v2_reason_code_disagreement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                connection.execute(
                    "UPDATE token_simulation_result SET capReason = ? WHERE runId = ?",
                    ("receipt_digest_mismatch", RUN_ID),
                )
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "typed_negative_score_mismatch")

    def test_rejects_a_missing_wal_or_shm_from_the_frozen_triad(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            live = root / "live" / "aneb-probe.db"
            live.parent.mkdir()
            database = root / "frozen" / "aneb-probe.db"
            database.parent.mkdir()
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(live)
            for source, destination in (
                (live, database),
                (Path(str(live) + "-wal"), Path(str(database) + "-wal")),
                (Path(str(live) + "-shm"), Path(str(database) + "-shm")),
            ):
                shutil.copyfile(source, destination)
            write_inventory(inventory, database)
            connection.close()
            wal = Path(str(database) + "-wal")
            wal.unlink()
            completed = self.run_verifier(database, inventory)

        self.assert_rejected(completed, "room_frozen_file_missing")

    def test_rejects_room_schema_version_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                connection.execute("PRAGMA user_version = 18")
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "room_user_version_mismatch")

    def test_rejects_duplicate_keys_in_the_strict_v2_result(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                serialized = json.dumps(
                    negative_body(), ensure_ascii=False, separators=(",", ":")
                )
                duplicated = serialized.replace(
                    '"schema_version":"aneb-result-v2"',
                    '"schema_version":"aneb-result-v2","schema_version":"aneb-result-v2"',
                    1,
                )
                connection.execute(
                    "UPDATE result_envelope SET bodyJson = ? WHERE runId = ?",
                    (duplicated, RUN_ID),
                )
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "result_schema_invalid")

    def test_rejects_nonstandard_json_constants_in_the_strict_v2_result(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                serialized = json.dumps(
                    negative_body(), ensure_ascii=False, separators=(",", ":")
                )
                nonstandard = serialized.replace('"value":null', '"value":NaN', 1)
                connection.execute(
                    "UPDATE result_envelope SET bodyJson = ? WHERE runId = ?",
                    (nonstandard, RUN_ID),
                )
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "result_schema_invalid")

    def test_rejects_unknown_result_enums(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                body = negative_body()
                body["evaluation"]["score"]["verdict"] = "mystery"
                positive_fixture.update_envelope(connection, body)
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "result_schema_invalid")

    def test_rejects_any_business_task_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                body = negative_body()
                positive = positive_fixture.valid_body()
                body["category_payload"]["raw_evidence"]["tasks"] = [
                    positive["category_payload"]["raw_evidence"]["tasks"][0]
                ]
                for evidence_ref in body["evidence"]["refs"]:
                    if evidence_ref["ref_id"] == "token-raw":
                        evidence_ref["record_count"] = 1
                positive_fixture.update_envelope(connection, body)
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "negative_raw_evidence_mismatch")

    def test_rejects_any_observed_business_kpi(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                body = negative_body()
                positive = positive_fixture.valid_body()
                business_metric_id = next(
                    metric_id
                    for metric_id, metric in positive["evaluation"]["metrics"].items()
                    if metric["domain"] == "business" and metric["state"] == "observed"
                )
                body["evaluation"]["metrics"][business_metric_id] = positive[
                    "evaluation"
                ]["metrics"][business_metric_id]
                positive_fixture.update_envelope(connection, body)
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "envelope_metric_value_mismatch")

    def test_rejects_any_group_or_network_score(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                body = negative_body()
                body["evaluation"]["group_scores"] = positive_fixture.valid_body()[
                    "evaluation"
                ]["group_scores"]
                positive_fixture.update_envelope(connection, body)
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "network_or_group_score_present")

    def test_rejects_substituting_another_run_for_the_requested_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                write_inventory(inventory, database)
                completed = self.run_verifier(
                    database,
                    inventory,
                    run_id="019f6d5f-7400-7000-8000-000000000002",
                )
            finally:
                connection.close()

        self.assert_rejected(completed, "typed_result_cardinality_invalid")

    def test_rejects_any_same_run_business_table_row(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            inventory = root / "room-copy-inventory.json"
            connection = create_negative_database(database)
            try:
                connection.execute(
                    """
                    INSERT INTO echo_sample (
                        runId, scenarioKey, phaseIndex, idx, warmup, t0Us
                    ) VALUES (?, 'negative-forbidden', 0, 0, 0, 1)
                    """,
                    (RUN_ID,),
                )
                connection.commit()
                write_inventory(inventory, database)
                completed = self.run_verifier(database, inventory)
            finally:
                connection.close()

        self.assert_rejected(completed, "unexpected_same_run_rows")


if __name__ == "__main__":
    unittest.main()
