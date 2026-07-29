import copy
import importlib.util
import json
import sys
import unittest
from pathlib import Path

import jsonschema


REPO_ROOT = Path(__file__).resolve().parents[3]
VERIFIER_PATH = REPO_ROOT / "scripts" / "verify_spec_catalog.py"
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("verify_spec_catalog", VERIFIER_PATH)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import contract guard
    raise RuntimeError(f"cannot load {VERIFIER_PATH}")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class RepeatabilityQualificationPolicyTest(unittest.TestCase):
    def test_approved_d110_policy_is_cataloged_schema_valid_and_hash_bound(self):
        catalog = json.loads(
            (REPO_ROOT / "spec" / "catalog.json").read_text(encoding="utf-8")
        )
        self.assertEqual("1.11.0", catalog["catalog_version"])
        self.assertEqual(1, len(catalog["repeatability_qualification_policies"]))

        entry = catalog["repeatability_qualification_policies"][0]
        self.assertEqual(
            {
                "policy_id": "aneb-repeatability-qualification-balanced-v1",
                "version": "1.0.0",
                "decision_id": "D-110",
                "path": (
                    "spec/repeatability-policies/"
                    "aneb-repeatability-qualification-balanced-v1.json"
                ),
                "schema_ref": "aneb-repeatability-qualification-policy-v1",
                "hash_strategy_id": "canonical-json-sha256-v1",
                "consumers": ["P1", "P3", "Profile"],
            },
            {key: value for key, value in entry.items() if key != "canonical_sha256"},
        )

        policy = json.loads(
            (REPO_ROOT / entry["path"]).read_text(encoding="utf-8")
        )
        self.assertEqual(
            VERIFY.canonical_json_sha256(policy), entry["canonical_sha256"]
        )

        schema_entry = next(
            item for item in catalog["schemas"]
            if item["schema_id"] == entry["schema_ref"]
        )
        schema = json.loads(
            (REPO_ROOT / schema_entry["path"]).read_text(encoding="utf-8")
        )
        jsonschema.Draft202012Validator.check_schema(schema)
        jsonschema.Draft202012Validator(schema).validate(policy)
        self.assertEqual([], VERIFY.validate_catalog(REPO_ROOT))

    def test_schema_rejects_approved_threshold_substitution(self):
        catalog = json.loads(
            (REPO_ROOT / "spec" / "catalog.json").read_text(encoding="utf-8")
        )
        entry = catalog["repeatability_qualification_policies"][0]
        policy = json.loads(
            (REPO_ROOT / entry["path"]).read_text(encoding="utf-8")
        )
        schema_entry = next(
            item for item in catalog["schemas"]
            if item["schema_id"] == entry["schema_ref"]
        )
        schema = json.loads(
            (REPO_ROOT / schema_entry["path"]).read_text(encoding="utf-8")
        )
        substituted = copy.deepcopy(policy)
        substituted["families"]["ai_realtime_simulation"]["metrics"][1][
            "max_inclusive"
        ] = 0.2

        errors = list(jsonschema.Draft202012Validator(schema).iter_errors(substituted))

        self.assertTrue(errors)
        self.assertTrue(
            any(
                list(error.absolute_path)
                == [
                    "families",
                    "ai_realtime_simulation",
                    "metrics",
                    1,
                ]
                for error in errors
            )
        )


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
