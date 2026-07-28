from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.analyze_repeatability_cohort import CohortError, load_jsonl, main
from scripts.tests.test_analyze_repeatability_cohort import (
    ROOT,
    _as_cellular,
    _realtime_qualification_run,
    _realtime_run,
    _token_run,
)


class RepeatabilityCohortCliTests(unittest.TestCase):
    def test_direct_script_help_uses_the_supported_import_path(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "analyze_repeatability_cohort.py"),
                "--help",
            ],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))

    def test_loader_rejects_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "duplicate.jsonl"
            path.write_text('{"schema_version":"aneb-result-v2","schema_version":"aneb-result-v2"}\n', encoding="utf-8")

            with self.assertRaisesRegex(CohortError, "duplicate_json_key:schema_version"):
                load_jsonl([path])

    def test_loader_rejects_non_finite_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "nan.jsonl"
            path.write_text('{"schema_version":"aneb-result-v2","value":NaN}\n', encoding="utf-8")

            with self.assertRaisesRegex(CohortError, "non_finite_json_number:NaN"):
                load_jsonl([path])

    def test_cli_writes_policy_pending_realtime_diagnostics_with_success_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "realtime.jsonl"
            output_path = Path(temp_dir) / "report.json"
            documents = [_realtime_run(index, (0.98, 42.0 + index, 0.01)) for index in range(1, 4)]
            input_path.write_text(
                "".join(json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n" for item in documents),
                encoding="utf-8",
            )

            exit_code = main(
                [
                    "--root",
                    str(ROOT),
                    "--output",
                    str(output_path),
                    str(input_path),
                ]
            )

            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("policy_pending", report["status"])
            self.assertEqual("diagnostic_only", report["policy"]["mode"])

    def test_cli_writes_q1_qualification_report_with_machine_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "realtime-q1.jsonl"
            output_path = Path(temp_dir) / "qualification.json"
            documents = [
                _realtime_qualification_run(index, (0.999, 40.0, 100.0))
                for index in range(1, 11)
            ]
            input_path.write_text(
                "".join(
                    json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n"
                    for item in documents
                ),
                encoding="utf-8",
            )

            exit_code = main(
                [
                    "--root",
                    str(ROOT),
                    "--qualification-stage",
                    "Q1_WIFI",
                    "--output",
                    str(output_path),
                    str(input_path),
                ]
            )

            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("aneb-repeatability-qualification-v1", report["schema_version"])
            self.assertEqual("repeatability_passed", report["status"])
            self.assertEqual("Q1_WIFI", report["policy"]["stage_id"])

    def test_cli_q2_loads_and_binds_strict_q1_prerequisite_report(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            q1_input = Path(temp_dir) / "realtime-q1.jsonl"
            q1_output = Path(temp_dir) / "q1-report.json"
            q2_input = Path(temp_dir) / "realtime-q2.jsonl"
            q2_output = Path(temp_dir) / "q2-report.json"
            q1_documents = [
                _realtime_qualification_run(index, (0.999, 40.0, 100.0))
                for index in range(1, 11)
            ]
            q2_documents = [
                _as_cellular(
                    _realtime_qualification_run(index, (0.998, 41.0, 101.0))
                )
                for index in range(11, 21)
            ]
            q1_input.write_text(
                "".join(json.dumps(item) + "\n" for item in q1_documents),
                encoding="utf-8",
            )
            q2_input.write_text(
                "".join(json.dumps(item) + "\n" for item in q2_documents),
                encoding="utf-8",
            )
            self.assertEqual(
                0,
                main(
                    [
                        "--root",
                        str(ROOT),
                        "--qualification-stage",
                        "Q1_WIFI",
                        "--output",
                        str(q1_output),
                        str(q1_input),
                    ]
                ),
            )

            exit_code = main(
                [
                    "--root",
                    str(ROOT),
                    "--qualification-stage",
                    "Q2_CELLULAR",
                    "--prerequisite-report",
                    str(q1_output),
                    "--output",
                    str(q2_output),
                    str(q2_input),
                ]
            )

            report = json.loads(q2_output.read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("repeatability_passed", report["status"])
            self.assertEqual("pass", report["prerequisite_gate"]["status"])
            self.assertEqual("Q1_WIFI", report["prerequisite_gate"]["stage_id"])

    def test_cli_returns_one_for_authorized_token_threshold_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "token.jsonl"
            output_path = Path(temp_dir) / "report.json"
            values = (400.0, 800.0, 1200.0, 1600.0, 2000.0)
            documents = [_token_run(index, value) for index, value in enumerate(values, 1)]
            input_path.write_text(
                "".join(json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n" for item in documents),
                encoding="utf-8",
            )

            exit_code = main(
                [
                    "--root",
                    str(ROOT),
                    "--output",
                    str(output_path),
                    str(input_path),
                ]
            )

            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(1, exit_code)
            self.assertEqual("fail", report["status"])
            self.assertEqual("D-58", report["policy"]["authority"])

    def test_cli_returns_two_and_retains_machine_error_for_invalid_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "invalid.jsonl"
            output_path = Path(temp_dir) / "report.json"
            input_path.write_text("{}\n", encoding="utf-8")

            exit_code = main(
                [
                    "--root",
                    str(ROOT),
                    "--output",
                    str(output_path),
                    str(input_path),
                ]
            )

            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(2, exit_code)
            self.assertEqual("invalid", report["status"])
            self.assertIn("strict_v2_required", report["error"])

    def test_cli_qualification_error_retains_the_machine_contract_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "invalid-qualification.jsonl"
            output_path = Path(temp_dir) / "qualification-error.json"
            input_path.write_text("{}\n", encoding="utf-8")

            exit_code = main(
                [
                    "--root",
                    str(ROOT),
                    "--qualification-stage",
                    "Q1_WIFI",
                    "--output",
                    str(output_path),
                    str(input_path),
                ]
            )

            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(2, exit_code)
            self.assertEqual("aneb-repeatability-qualification-v1", report["schema_version"])
            self.assertEqual("aneb-repeatability-qualification-v1", report["contract_version"])
            self.assertEqual("invalid", report["status"])
            self.assertIn("strict_v2_required", report["error"])


if __name__ == "__main__":
    unittest.main()
