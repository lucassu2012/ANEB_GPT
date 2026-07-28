from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.analyze_repeatability_cohort import CohortError, load_jsonl, main
from scripts.tests.test_analyze_repeatability_cohort import ROOT, _realtime_run, _token_run


class RepeatabilityCohortCliTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
