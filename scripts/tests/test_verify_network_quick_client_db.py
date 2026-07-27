from __future__ import annotations

import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from scripts import verify_network_quick_client_db as verifier


RUN_ID = "00000000-0000-7000-8000-000000000301"
SERVER_BASE = "https://120.79.148.0:8443"


def body(mode: str) -> dict[str, object]:
    positive = mode == "positive"
    return {
        "schema_version": "aneb-result-v2",
        "test_type": "network_comprehensive",
        "producer": {
            "component": "aneb-probe-android",
            "component_version": "0.5.14-codex",
        },
        "run": {
            "run_id": RUN_ID,
            "status": "completed" if positive else "failed",
            "validity": "valid" if positive else "invalid",
            "invalid_reason_codes": [] if positive else ["receipt_missing"],
            "source_record_version": "room-v19-network-envelope-v1",
        },
        "profile": {
            "profile_id": "network_comprehensive_quick",
            "profile_version": "1.2.0",
            "variant": "quick",
            "runtime_artifact_status": "resolved",
            "profile_fingerprint": {"value": "sha256:" + verifier.PROFILE_SHA256},
            "runtime_artifact_hash": {"value": "sha256:" + verifier.RUNTIME_SHA256},
        },
        "context": {
            "device": {
                "app_package": "com.aneb.probe.codex",
                "app_version_name": "0.5.14-codex",
                "app_version_code": 46,
            },
            "endpoint": {"server_base": SERVER_BASE, "server_version": None},
        },
        "evaluation": {
            "score": {
                "state": "computed" if positive else "suppressed_invalid",
                "value": 92.0 if positive else None,
                "grade": "A" if positive else None,
            },
        },
        "category_payload": {
            "transfer_summary": {
                "download_bytes": 33_554_432 if positive else 0,
                "upload_bytes": 8_388_608 if positive else 0,
            },
            "raw_evidence": {
                "invalid_reason": None if positive else "receipt_missing",
                "app_request_attempts": 55 if positive else 0,
                "app_request_successes": 55 if positive else 0,
                "udp_packets_sent": 50 if positive else 0,
                "udp_received_seqs": list(range(50)) if positive else [],
            },
        },
    }


def create_database(path: Path, mode: str) -> None:
    positive = mode == "positive"
    result = body(mode)
    body_text = json.dumps(result, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);
        INSERT INTO room_master_table VALUES (42, '5eea8eb8e2b9f91767a34e3499c77484');
        CREATE TABLE network_comprehensive_result (
          runId TEXT PRIMARY KEY, profileId TEXT, profileVersion TEXT, variant TEXT,
          status TEXT, totalScore REAL, grade TEXT,
          downloadMbps REAL, uploadMbps REAL, idleRttMs REAL, loadedRttMs REAL,
          latencyDeltaMs REAL, jitterMs REAL, requestLossRate REAL,
          throughputRobustCv REAL, udpNonReturnRate REAL, postLoadPingMs REAL,
          downloadBytes INTEGER NOT NULL, uploadBytes INTEGER NOT NULL
        );
        CREATE TABLE result_envelope (
          runId TEXT PRIMARY KEY, schemaVersion TEXT, testType TEXT,
          canonicalSha256 TEXT, bodyJson TEXT
        );
        """
    )
    metrics = (42.0, 16.0, 30.0, 80.0, 50.0, 4.0, 0.0, 0.1, 0.0, 35.0)
    connection.execute(
        "INSERT INTO network_comprehensive_result VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (
            RUN_ID,
            "network_comprehensive_quick",
            "1.2.0",
            "quick",
            "completed" if positive else "invalid",
            92.0 if positive else None,
            "A" if positive else None,
            *(metrics if positive else (None,) * len(metrics)),
            33_554_432 if positive else 0,
            8_388_608 if positive else 0,
        ),
    )
    connection.execute(
        "INSERT INTO result_envelope VALUES (?,?,?,?,?)",
        (
            RUN_ID,
            "aneb-result-v2",
            "network_comprehensive",
            verifier.canonical_sha256(result),
            body_text,
        ),
    )
    connection.commit()
    connection.close()


class NetworkQuickClientDatabaseVerifierTest(unittest.TestCase):
    def verify(self, database: Path, mode: str) -> dict[str, object]:
        return verifier.verify_database(
            database,
            RUN_ID,
            mode,
            verifier.PROFILE_DIR / "profile.json",
            verifier.PROFILE_DIR / "runtime_plan.json",
            verifier.PROFILE_DIR / "manifest.sha256",
            SERVER_BASE,
        )

    def test_positive_result_binds_runtime_and_nonzero_business(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb.db"
            create_database(database, "positive")
            report = self.verify(database, "positive")
        self.assertEqual("pass", report["status"])
        self.assertEqual(50, report["business"]["udp_unique_returns"])
        self.assertGreater(report["business"]["download_bytes"], 0)

    def test_negative_receipt_missing_requires_zero_business(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb.db"
            create_database(database, "negative")
            report = self.verify(database, "negative")
        self.assertEqual("receipt_missing", report["reason_code"])
        self.assertEqual(0, report["business"]["udp_packets_sent"])

    def test_negative_nonzero_udp_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb.db"
            create_database(database, "negative")
            connection = sqlite3.connect(database)
            body_text = connection.execute(
                "SELECT bodyJson FROM result_envelope WHERE runId=?", (RUN_ID,)
            ).fetchone()[0]
            mutated = json.loads(body_text)
            mutated["category_payload"]["raw_evidence"]["udp_packets_sent"] = 1
            connection.execute(
                "UPDATE result_envelope SET bodyJson=?, canonicalSha256=? WHERE runId=?",
                (
                    json.dumps(mutated, sort_keys=True, separators=(",", ":")),
                    verifier.canonical_sha256(mutated),
                    RUN_ID,
                ),
            )
            connection.commit()
            connection.close()
            with self.assertRaisesRegex(verifier.VerificationError, "negative_zero_business"):
                self.verify(database, "negative")

    def test_envelope_digest_drift_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb.db"
            create_database(database, "positive")
            connection = sqlite3.connect(database)
            connection.execute(
                "UPDATE result_envelope SET canonicalSha256=? WHERE runId=?",
                ("sha256:" + "0" * 64, RUN_ID),
            )
            connection.commit()
            connection.close()
            with self.assertRaisesRegex(verifier.VerificationError, "digest_mismatch"):
                self.verify(database, "positive")


if __name__ == "__main__":
    unittest.main()
