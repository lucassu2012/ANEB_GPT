import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
VERIFIER_PATH = REPO_ROOT / "scripts" / "verify_spec_catalog.py"
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("verify_spec_catalog", VERIFIER_PATH)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import contract guard
    raise RuntimeError(f"cannot load {VERIFIER_PATH}")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class SpecCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog_path = REPO_ROOT / "spec" / "catalog.json"
        cls.catalog = json.loads(cls.catalog_path.read_text(encoding="utf-8"))

    def _validate_mutation(self, mutate):
        catalog = copy.deepcopy(self.catalog)
        mutate(catalog)
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "catalog.json"
            path.write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")
            return VERIFY.validate_catalog(REPO_ROOT, path)

    def test_repository_catalog_and_all_references_validate(self):
        self.assertEqual([], VERIFY.validate_catalog(REPO_ROOT))

    def test_runtime_bound_and_embedded_network_profiles_use_distinct_policies(self):
        published = next(
            family for family in self.catalog["profile_families"]
            if family["family_id"] == "published-profile-v2"
        )
        groups = {group["group_id"]: group for group in published["validation_groups"]}
        runtime_bound = [
            profile for profile in published["profiles"]
            if profile["validation_group_id"] == "behavior_runtime_bound"
        ]
        embedded_network = [
            profile for profile in published["profiles"]
            if profile["validation_group_id"] == "network_embedded_phases"
        ]
        self.assertEqual("required", groups["behavior_runtime_bound"]["runtime_manifest_policy"])
        self.assertEqual("canonical-json-sha256-v1", groups["behavior_runtime_bound"]["hash_strategy_id"])
        self.assertEqual("forbidden", groups["network_embedded_phases"]["runtime_manifest_policy"])
        self.assertIsNone(groups["network_embedded_phases"]["hash_strategy_id"])
        self.assertEqual(6, len(runtime_bound))
        self.assertEqual(6, len(embedded_network))
        self.assertTrue(all("runtime_plan_path" in profile and "manifest_path" in profile for profile in runtime_bound))
        self.assertTrue(all("runtime_plan_path" not in profile and "manifest_path" not in profile for profile in embedded_network))

    def test_catalog_profile_id_mismatch_and_duplicate_fail_closed(self):
        def mutate(catalog):
            legacy = catalog["profile_families"][0]["profiles"]
            legacy[1]["profile_id"] = legacy[0]["profile_id"]

        errors = self._validate_mutation(mutate)
        self.assertTrue(any("duplicate across catalog" in error for error in errors), errors)
        self.assertTrue(any("profile_id does not match catalog" in error for error in errors), errors)

    def test_missing_reference_fails_closed(self):
        def mutate(catalog):
            catalog["schemas"][0]["path"] = "tools/aneb-ai-behavior-model/schemas/missing.json"

        errors = self._validate_mutation(mutate)
        self.assertTrue(any("referenced file does not exist" in error for error in errors), errors)
        self.assertTrue(any("unindexed assets" in error for error in errors), errors)

    def test_network_profile_cannot_declare_runtime_manifest(self):
        def mutate(catalog):
            published = catalog["profile_families"][1]
            network = next(
                profile for profile in published["profiles"]
                if profile["validation_group_id"] == "network_embedded_phases"
            )
            network["runtime_plan_path"] = "profiles/published/token_multimodal_quick/runtime_plan.json"
            network["manifest_path"] = "profiles/published/token_multimodal_quick/manifest.sha256"

        errors = self._validate_mutation(mutate)
        self.assertTrue(any("runtime references are forbidden" in error for error in errors), errors)

    def test_all_runtime_manifests_use_canonical_json_hashes(self):
        published = next(
            family for family in self.catalog["profile_families"]
            if family["family_id"] == "published-profile-v2"
        )
        for entry in published["profiles"]:
            if entry["validation_group_id"] != "behavior_runtime_bound":
                continue
            profile = json.loads((REPO_ROOT / entry["path"]).read_text(encoding="utf-8"))
            runtime = json.loads((REPO_ROOT / entry["runtime_plan_path"]).read_text(encoding="utf-8"))
            errors = []
            manifest = VERIFY._parse_manifest(REPO_ROOT / entry["manifest_path"], entry["manifest_path"], errors)
            self.assertEqual([], errors)
            self.assertEqual(
                {
                    "profile.json": VERIFY.canonical_json_sha256(profile),
                    "runtime_plan.json": VERIFY.canonical_json_sha256(runtime),
                },
                manifest,
            )


if __name__ == "__main__":
    unittest.main()
