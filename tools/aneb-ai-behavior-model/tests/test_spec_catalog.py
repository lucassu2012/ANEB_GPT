import copy
import importlib.util
import json
import sys
import tempfile
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


class SpecCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog_path = REPO_ROOT / "spec" / "catalog.json"
        cls.catalog = json.loads(cls.catalog_path.read_text(encoding="utf-8"))
        schema_path = REPO_ROOT / "tools/aneb-ai-behavior-model/schemas/aneb-profile-v2.schema.json"
        cls.profile_schema = json.loads(schema_path.read_text(encoding="utf-8"))
        jsonschema.Draft202012Validator.check_schema(cls.profile_schema)
        cls.profile_validator = jsonschema.Draft202012Validator(cls.profile_schema)
        cls.published = next(
            family for family in cls.catalog["profile_families"]
            if family["family_id"] == "published-profile-v2"
        )
        cls.published_profiles = {
            entry["profile_id"]: json.loads((REPO_ROOT / entry["path"]).read_text(encoding="utf-8"))
            for entry in cls.published["profiles"]
        }
        cls.quick_profile = cls.published_profiles["token_multimodal_quick"]

    def _validate_requirements(self, profile, *, required=True):
        errors = []
        VERIFY._validate_execution_requirements(profile, required=required, label="mutated-profile", errors=errors)
        return errors

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

    def test_profile_schema_accepts_all_published_profiles_and_locks_migration_scope(self):
        profiles_with_requirements = set()
        for profile_id, profile in self.published_profiles.items():
            schema_errors = list(self.profile_validator.iter_errors(profile))
            self.assertEqual([], schema_errors, (profile_id, schema_errors))
            if "execution_requirements" in profile:
                profiles_with_requirements.add(profile_id)
        policies = {
            entry["profile_id"]
            for entry in self.published["profiles"]
            if entry.get("execution_requirements_policy") == "required"
        }
        self.assertEqual({"token_multimodal_quick"}, profiles_with_requirements)
        self.assertEqual({"token_multimodal_quick"}, policies)
        self.assertEqual(11, len(self.published_profiles) - len(profiles_with_requirements))
        self.assertEqual([], self._validate_requirements(self.quick_profile))

    def test_duplicate_primitive_id_fails_schema_and_python_verifier(self):
        profile = copy.deepcopy(self.quick_profile)
        profile["execution_requirements"]["required_primitives"].append(
            copy.deepcopy(profile["execution_requirements"]["required_primitives"][0])
        )
        self.assertTrue(list(self.profile_validator.iter_errors(profile)))
        errors = self._validate_requirements(profile)
        self.assertTrue(any("duplicate 'echo'" in error for error in errors), errors)

    def test_unknown_primitive_or_wire_contract_fails_closed(self):
        for field, value, expected_error in (
            ("primitive_id", "arbitrary_network_call", "unknown primitive"),
            ("wire_contract_id", "aneb-echo-v2", "unsupported wire contract"),
        ):
            with self.subTest(field=field):
                profile = copy.deepcopy(self.quick_profile)
                profile["execution_requirements"]["required_primitives"][0][field] = value
                self.assertTrue(list(self.profile_validator.iter_errors(profile)))
                errors = self._validate_requirements(profile)
                self.assertTrue(any(expected_error in error for error in errors), errors)

    def test_missing_fields_and_arbitrary_execution_keys_fail_closed(self):
        mutations = (
            (
                lambda requirements: requirements["client_engine"].pop("min_version"),
                "missing keys ['min_version']",
            ),
            (
                lambda requirements: requirements.update({"script": "curl https://example.invalid"}),
                "unverified keys ['script']",
            ),
            (
                lambda requirements: requirements["required_primitives"][0].update(
                    {"url": "https://example.invalid"}
                ),
                "unverified keys ['url']",
            ),
            (
                lambda requirements: requirements["required_primitives"].pop(),
                "must declare exactly the supported primitive set",
            ),
        )
        for mutate, expected_error in mutations:
            with self.subTest(expected_error=expected_error):
                profile = copy.deepcopy(self.quick_profile)
                mutate(profile["execution_requirements"])
                self.assertTrue(list(self.profile_validator.iter_errors(profile)))
                errors = self._validate_requirements(profile)
                self.assertTrue(any(expected_error in error for error in errors), errors)

    def test_incompatible_engine_and_receipt_ranges_fail_python_verifier(self):
        for member in ("client_engine", "server_capability_receipt"):
            with self.subTest(member=member):
                profile = copy.deepcopy(self.quick_profile)
                profile["execution_requirements"][member]["min_version"] = "2.0.0"
                profile["execution_requirements"][member]["max_version_exclusive"] = "3.0.0"
                self.assertEqual([], list(self.profile_validator.iter_errors(profile)))
                errors = self._validate_requirements(profile)
                self.assertTrue(any("is outside [2.0.0, 3.0.0)" in error for error in errors), errors)

    def test_unknown_execution_contract_fails_schema_and_python_verifier(self):
        profile = copy.deepcopy(self.quick_profile)
        profile["execution_requirements"]["contract_id"] = "aneb-execution-requirements-unknown"
        self.assertTrue(list(self.profile_validator.iter_errors(profile)))
        errors = self._validate_requirements(profile)
        self.assertTrue(any("unsupported contract id/version" in error for error in errors), errors)

    def test_catalog_execution_policy_cannot_be_removed_or_moved(self):
        def remove_policy(catalog):
            published = catalog["profile_families"][1]
            quick = next(item for item in published["profiles"] if item["profile_id"] == "token_multimodal_quick")
            quick.pop("execution_requirements_policy")

        errors = self._validate_mutation(remove_policy)
        self.assertTrue(any("not in the migration allowlist" in error for error in errors), errors)
        self.assertTrue(any("catalog policy set is not recognized" in error for error in errors), errors)

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
