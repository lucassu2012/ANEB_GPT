from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.verify_result_jsonl import validate_jsonl


ROOT = Path(__file__).resolve().parents[2]


def fixture(version: str, task_vector: str) -> dict:
    result = json.loads(
        (ROOT / "spec/examples/aneb-result-v1/token_simulation.not-computable.valid-schema.json").read_text(
            encoding="utf-8"
        )
    )
    result["schema_version"] = version
    task = json.loads(
        (ROOT / f"spec/examples/aneb-result-cross-version/{task_vector}").read_text(encoding="utf-8")
    )
    result["category_payload"]["raw_evidence"]["tasks"] = [task]
    return result


class ResultJsonlVersionRoutingTest(unittest.TestCase):
    def validate(self, documents: list[dict]) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jsonl"
            path.write_text(
                "".join(json.dumps(document, ensure_ascii=False) + "\n" for document in documents),
                encoding="utf-8",
            )
            return validate_jsonl(ROOT, [path])

    def test_routes_legacy_v1_and_aligned_v2_to_separate_validators(self) -> None:
        v1 = fixture("aneb-result-v1", "token-task-legacy-v1.json")
        v2 = fixture("aneb-result-v2", "token-task-aligned-v2.json")
        v2["run"]["run_id"] = "00000000-0000-7000-8000-000000000002"

        report = self.validate([v1, v2])

        self.assertEqual("pass", report["status"])
        self.assertEqual({"aneb-result-v1": 1, "aneb-result-v2": 1}, report["schema_versions"])

    def test_strict_v2_rejects_legacy_task_shape(self) -> None:
        report = self.validate([fixture("aneb-result-v2", "token-task-legacy-v1.json")])

        self.assertEqual("fail", report["status"])
        self.assertTrue(any("task_id" in error for error in report["errors"]))

    def test_unsupported_version_is_not_reported_as_schema_corruption(self) -> None:
        future = fixture("aneb-result-v2", "token-task-aligned-v2.json")
        future["schema_version"] = "aneb-result-v3"

        report = self.validate([future])

        self.assertEqual("fail", report["status"])
        self.assertIn("unsupported schema_version 'aneb-result-v3'", report["errors"][0])

    def test_duplicate_run_identity_across_versions_fails_closed(self) -> None:
        v1 = fixture("aneb-result-v1", "token-task-legacy-v1.json")
        v2 = fixture("aneb-result-v2", "token-task-aligned-v2.json")

        report = self.validate([v1, v2])

        self.assertEqual("fail", report["status"])
        self.assertTrue(any("duplicate run_id" in error for error in report["errors"]))

    def test_empty_jsonl_is_not_a_successful_audit(self) -> None:
        report = self.validate([])

        self.assertEqual("fail", report["status"])
        self.assertIn("result JSONL contains no documents", report["errors"])


if __name__ == "__main__":
    unittest.main()
