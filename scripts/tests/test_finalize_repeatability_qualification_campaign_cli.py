from __future__ import annotations

import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.tests.test_analyze_repeatability_cohort import (
    _network_qualification_run,
    _realtime_qualification_run,
    _token_qualification_run,
)
from scripts.tests.test_export_repeatability_cohort import _database
from scripts.tests.test_finalize_repeatability_qualification_campaign import (
    _write_completed_campaign,
    _write_room_snapshot_receipt,
)
from scripts.tests.test_run_repeatability_campaign import _bind_qualification_hashes


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def _q1_documents(*, token_values: tuple[float, ...] | None = None) -> list[dict[str, object]]:
    values = token_values or tuple(500.0 + (index % 2) for index in range(1, 11))
    if len(values) != 10:
        raise AssertionError("token_fixture_count_invalid")
    return [
        *[
            _bind_qualification_hashes(
                _token_qualification_run(index, value),
                family="token",
            )
            for index, value in enumerate(values, 1)
        ],
        *[
            _bind_qualification_hashes(
                _realtime_qualification_run(
                    index,
                    (
                        0.999 - (index % 3) * 0.001,
                        40.0 + (index % 3),
                        100.0 + (index % 2),
                    ),
                ),
                family="realtime",
            )
            for index in range(11, 21)
        ],
        *[
            _bind_qualification_hashes(
                _network_qualification_run(
                    index,
                    (
                        100.0 + (index % 2),
                        20.0 + (index % 2) * 0.1,
                        50.0 + (index % 2),
                    ),
                ),
                family="network",
            )
            for index in range(21, 31)
        ],
    ]


def _run_real_q1_cli(
    root: Path, documents: list[dict[str, object]]
) -> tuple[subprocess.CompletedProcess[bytes], Path]:
    run_ids = tuple(document["run"]["run_id"] for document in documents)
    evidence = root / "captured"
    evidence.mkdir()
    _write_completed_campaign(evidence, run_ids=run_ids)
    room = evidence / "campaign-room"
    room.mkdir()
    database = room / "aneb-probe.db"
    _database(database, documents)
    connection = sqlite3.connect(database)
    try:
        if connection.execute("PRAGMA journal_mode = WAL").fetchone()[0] != "wal":
            raise AssertionError("wal_mode_not_enabled")
        connection.execute("PRAGMA wal_autocheckpoint = 0")
        connection.execute(
            "CREATE TABLE finalizer_cli_fixture_guard (value INTEGER NOT NULL)"
        )
        connection.execute("INSERT INTO finalizer_cli_fixture_guard VALUES (1)")
        connection.commit()
        _write_room_snapshot_receipt(evidence)
        output = root / "finalized"
        completed = subprocess.run(
            [
                sys.executable,
                "-m",
                "scripts.finalize_repeatability_qualification_campaign",
                str(evidence),
                str(output),
            ],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
        )
    finally:
        connection.close()
    return completed, output


class FinalizeRepeatabilityQualificationCampaignCliTests(unittest.TestCase):
    def test_module_cli_usage_failure_is_canonical_stderr_only(self) -> None:
        completed = subprocess.run(
            [
                sys.executable,
                "-m",
                "scripts.finalize_repeatability_qualification_campaign",
            ],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
        )

        self.assertEqual(2, completed.returncode)
        self.assertEqual(b"", completed.stdout)
        self.assertEqual(
            (
                b'{"reason_code":"finalizer_usage_invalid",'
                b'"schema":"aneb-repeatability-finalizer-cli-error@1.0.0"}\n'
            ),
            completed.stderr,
        )

    def test_module_cli_runs_real_q1_finalization_and_emits_canonical_receipt(self) -> None:
        documents = _q1_documents()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            completed, output = _run_real_q1_cli(root, documents)

            report_sha256_by_family = {
                family: hashlib.sha256(
                    (output / f"{family}-qualification-report.json").read_bytes()
                ).hexdigest()
                for family in ("token", "realtime", "network")
            }
            expected_stdout = (
                json.dumps(
                    {
                        "formal_baseline_eligible": False,
                        "output_directory": str(output.resolve()),
                        "qualification_passed": True,
                        "report_sha256_by_family": report_sha256_by_family,
                        "report_status_by_family": {
                            "network": "repeatability_passed",
                            "realtime": "repeatability_passed",
                            "token": "repeatability_passed",
                        },
                        "schema": (
                            "aneb-repeatability-finalizer-cli-result@1.0.0"
                        ),
                        "stage_id": "Q1_WIFI",
                    },
                    ensure_ascii=False,
                    allow_nan=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            ).encode("utf-8")
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(expected_stdout, completed.stdout)
            self.assertEqual(b"", completed.stderr)
            self.assertTrue((output / "finalization-manifest.json").is_file())
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_module_cli_returns_one_for_completed_failed_qualification(self) -> None:
        token_values = tuple(
            (400.0, 800.0, 1200.0, 1600.0, 2000.0)[(index - 1) % 5]
            for index in range(1, 11)
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            completed, output = _run_real_q1_cli(
                root,
                _q1_documents(token_values=token_values),
            )

            receipt = json.loads(completed.stdout.decode("utf-8"))
            self.assertEqual(1, completed.returncode, completed.stderr)
            self.assertEqual(b"", completed.stderr)
            self.assertFalse(receipt["qualification_passed"])
            self.assertEqual(
                "repeatability_failed",
                receipt["report_status_by_family"]["token"],
            )
            self.assertEqual(
                "repeatability_passed",
                receipt["report_status_by_family"]["realtime"],
            )
            self.assertEqual(
                "repeatability_passed",
                receipt["report_status_by_family"]["network"],
            )
            self.assertFalse(receipt["formal_baseline_eligible"])
            self.assertTrue((output / "finalization-manifest.json").is_file())
            self.assertEqual([], list(root.glob(".finalized.*.partial")))

    def test_module_cli_controlled_failure_is_canonical_stderr_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "scripts.finalize_repeatability_qualification_campaign",
                    str(root / "missing-evidence"),
                    str(root / "unused-output"),
                ],
                cwd=REPOSITORY_ROOT,
                check=False,
                capture_output=True,
            )

        self.assertEqual(2, completed.returncode)
        self.assertEqual(b"", completed.stdout)
        self.assertEqual(
            (
                b'{"reason_code":"campaign_evidence_directory_invalid",'
                b'"schema":"aneb-repeatability-finalizer-cli-error@1.0.0"}\n'
            ),
            completed.stderr,
        )


if __name__ == "__main__":
    unittest.main()
