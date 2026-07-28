from __future__ import annotations

import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from scripts.export_repeatability_cohort import export_cohort
from scripts.tests.test_analyze_repeatability_cohort import _realtime_run


ROOT = Path(__file__).resolve().parents[2]


def _canonical(value: object) -> str:
    import hashlib

    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def _database(path: Path, documents: list[dict[str, object]]) -> list[str]:
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT NOT NULL);
        INSERT INTO room_master_table VALUES (42, '5eea8eb8e2b9f91767a34e3499c77484');
        CREATE TABLE result_envelope (
          runId TEXT PRIMARY KEY,
          schemaVersion TEXT NOT NULL,
          testType TEXT NOT NULL,
          startedAtEpochMs INTEGER NOT NULL,
          serializedAtEpochMs INTEGER NOT NULL,
          canonicalSha256 TEXT NOT NULL,
          bodyJson TEXT NOT NULL
        );
        CREATE TABLE radio_sample (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          runId TEXT,
          tsNanos INTEGER NOT NULL,
          cellTsNanos INTEGER,
          stale INTEGER NOT NULL,
          subId INTEGER NOT NULL,
          subSwitched INTEGER NOT NULL,
          networkType TEXT NOT NULL,
          overrideType TEXT,
          nrState TEXT NOT NULL,
          rat TEXT,
          pci INTEGER,
          tac INTEGER,
          arfcn INTEGER,
          rsrp INTEGER,
          rsrq INTEGER,
          sinr INTEGER,
          operatorName TEXT,
          lat REAL,
          lon REAL,
          accuracyM REAL
        );
        """
    )
    bodies: list[str] = []
    for document in documents:
        body = json.dumps(document, ensure_ascii=False, separators=(",", ":"))
        bodies.append(body)
        connection.execute(
            "INSERT INTO result_envelope VALUES (?,?,?,?,?,?,?)",
            (
                document["run"]["run_id"],
                document["schema_version"],
                document["test_type"],
                document["run"]["started_at_epoch_ms"],
                document["producer"]["serialized_at_epoch_ms"],
                _canonical(document),
                body,
            ),
        )
        for sample in document["context"]["radio"]["samples"]:
            connection.execute(
                """
                INSERT INTO radio_sample (
                  runId, tsNanos, cellTsNanos, stale, subId, subSwitched,
                  networkType, overrideType, nrState, rat, pci, tac, arfcn,
                  rsrp, rsrq, sinr, operatorName, lat, lon, accuracyM
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    document["run"]["run_id"],
                    sample["elapsed_realtime_nanos"],
                    sample["cell_elapsed_realtime_nanos"],
                    int(sample["stale"]),
                    sample["sub_id"],
                    int(sample["sub_switched"]),
                    sample["network_type"],
                    sample["override_type"],
                    sample["nr_state"],
                    sample["rat"],
                    sample["pci"],
                    sample["tac"],
                    sample["arfcn"],
                    sample["rsrp_dbm"],
                    sample["rsrq_db"],
                    sample["sinr_db"],
                    sample["operator_name"],
                    None,
                    None,
                    None,
                ),
            )
    connection.commit()
    connection.close()
    return bodies


class ExportRepeatabilityCohortTests(unittest.TestCase):
    def test_direct_script_help_uses_the_supported_import_path(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "export_repeatability_cohort.py"),
                "--help",
            ],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))

    def test_exports_frozen_result_envelope_bytes_in_requested_order(self) -> None:
        documents = [_realtime_run(index, (0.98, 42.0 + index, 0.01)) for index in (1, 2)]
        run_ids = [document["run"]["run_id"] for document in reversed(documents)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            bodies = _database(database, documents)
            output = root / "cohort.jsonl"

            receipt = export_cohort(database, run_ids, output, root=ROOT)

            self.assertEqual(
                [bodies[1], bodies[0]],
                output.read_text(encoding="utf-8").splitlines(),
            )
            self.assertEqual("aneb-repeatability-export-v1", receipt["schema_version"])
            self.assertEqual(run_ids, receipt["run_ids"])
            self.assertEqual(2, receipt["run_count"])

    def test_failure_path_preserves_the_source_database_wal_and_shm_bytes(self) -> None:
        document = _realtime_run(1, (0.98, 43.0, 0.01))
        run_id = document["run"]["run_id"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            _database(database, [document])
            connection = sqlite3.connect(database)
            self.assertEqual(
                "wal",
                connection.execute("PRAGMA journal_mode = WAL").fetchone()[0],
            )
            connection.execute("PRAGMA wal_autocheckpoint = 0")
            connection.execute(
                "UPDATE radio_sample SET tsNanos = tsNanos + 1 WHERE runId = ? AND id = 1",
                (run_id,),
            )
            connection.commit()

            source_paths = [
                database,
                Path(str(database) + "-wal"),
                Path(str(database) + "-shm"),
            ]
            self.assertTrue(all(path.is_file() for path in source_paths))
            before = {path.name: path.read_bytes() for path in source_paths}
            output = root / "cohort.jsonl"
            try:
                with self.assertRaisesRegex(
                    RuntimeError,
                    "radio_envelope_binding_mismatch",
                ):
                    export_cohort(database, [run_id], output, root=ROOT)

                self.assertEqual(
                    before,
                    {path.name: path.read_bytes() for path in source_paths},
                )
                self.assertFalse(output.exists())
            finally:
                connection.close()
    def test_rejects_room_radio_rows_that_do_not_match_the_frozen_envelope(self) -> None:
        document = _realtime_run(1, (0.98, 43.0, 0.01))
        run_id = document["run"]["run_id"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            _database(database, [document])
            connection = sqlite3.connect(database)
            connection.execute(
                "UPDATE radio_sample SET tsNanos = tsNanos + 1 WHERE runId = ? AND id = 1",
                (run_id,),
            )
            connection.commit()
            connection.close()

            with self.assertRaisesRegex(RuntimeError, "radio_envelope_binding_mismatch"):
                export_cohort(database, [run_id], root / "cohort.jsonl", root=ROOT)


if __name__ == "__main__":
    unittest.main()
