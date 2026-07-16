from __future__ import annotations

import json
import unittest
from copy import deepcopy
from pathlib import Path

from _json_schema_subset import ContractSchemaError, validate
from aneb_behavior_model.generator import build_artifacts
from aneb_behavior_model.model import load_model

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
MODEL = ROOT / "models/token_multimodal_hypothesis_v0.1.json"
SCHEMA = ROOT / "schemas/aneb-profile-v2.schema.json"
GOLDEN = REPO / "testdata/profile-v2/golden/token_multimodal_standard.seed-20260716.json"
SEED = 20260716
PROFILE_HASH = "sha256:de034840850362fb80b829090c2a7a2dc3b8e3c509622b2edd620b1b2528a7af"


class ProfileContractMatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        self.schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        self.golden = json.loads(GOLDEN.read_text(encoding="utf-8"))

    def test_generator_reproduces_shared_golden(self) -> None:
        artifacts = build_artifacts(load_model(MODEL), SEED)

        self.assertEqual(self.golden, artifacts.profile)
        self.assertEqual(PROFILE_HASH, artifacts.manifest["profile.json"])
        self.assertEqual(26, len(self.golden["measurements"]))
        self.assertEqual("hypothesis", self.golden["business"]["calibration_status"])

    def test_shared_golden_matches_profile_v2_schema(self) -> None:
        validate(self.golden, self.schema)

    def test_schema_gate_rejects_contract_breaking_mutations(self) -> None:
        def wrong_contract(profile: dict) -> None:
            profile["contract_version"] = "aneb-profile-v99"

        def wrong_claim(profile: dict) -> None:
            profile["claim_scope"] = "operator_wide_rating"

        def empty_measurements(profile: dict) -> None:
            profile["measurements"] = []

        def invalid_sample_count(profile: dict) -> None:
            profile["measurements"][0]["minimum_sample_count"] = 0

        def missing_evaluation(profile: dict) -> None:
            del profile["evaluation"]

        cases = {
            "wrong contract": wrong_contract,
            "wrong claim": wrong_claim,
            "empty measurements": empty_measurements,
            "invalid sample count": invalid_sample_count,
            "missing evaluation": missing_evaluation,
        }
        for name, mutate in cases.items():
            with self.subTest(name=name):
                candidate = deepcopy(self.golden)
                mutate(candidate)
                with self.assertRaises(ContractSchemaError):
                    validate(candidate, self.schema)

    def test_schema_subset_preflights_unvisited_branches(self) -> None:
        cases = {
            "root": {"oneOf": []},
            "absent optional property": {
                "type": "object",
                "properties": {"optional": {"pattern": "not implemented"}},
            },
            "unreferenced definition": {
                "$defs": {"unused": {"oneOf": []}},
            },
        }
        for name, schema in cases.items():
            with self.subTest(name=name):
                with self.assertRaisesRegex(ContractSchemaError, "unsupported schema keywords"):
                    validate({}, schema)

    def test_schema_subset_rejects_non_finite_numbers(self) -> None:
        schema = {
            "type": "object",
            "properties": {
                "quality_target": {
                    "type": "object",
                    "additionalProperties": True,
                },
            },
        }
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                with self.assertRaises(ContractSchemaError):
                    validate({"quality_target": {"value": value}}, schema)

    def test_schema_subset_uses_json_type_sensitive_equality(self) -> None:
        with self.assertRaises(ContractSchemaError):
            validate(True, {"const": 1})
        with self.assertRaises(ContractSchemaError):
            validate(False, {"enum": [0]})


if __name__ == "__main__":
    unittest.main()
