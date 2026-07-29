from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from aneb_behavior_model.cli import main as cli_main


TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
POLICY_PATH = (
    REPO_ROOT
    / "spec/repeatability-policies/aneb-repeatability-qualification-balanced-v1.json"
)
VERIFIER_PATH = REPO_ROOT / "scripts/verify_spec_catalog.py"
VERIFIER_SPEC = importlib.util.spec_from_file_location(
    "verify_spec_catalog_for_qualification_publication",
    VERIFIER_PATH,
)
if VERIFIER_SPEC is None or VERIFIER_SPEC.loader is None:  # pragma: no cover
    raise RuntimeError(f"cannot load {VERIFIER_PATH}")
VERIFY = importlib.util.module_from_spec(VERIFIER_SPEC)
VERIFIER_SPEC.loader.exec_module(VERIFY)


def _canonical_sha256(value: object) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


class QualificationPublicationTest(unittest.TestCase):
    def test_cli_publishes_all_three_repeatability_qualification_bundles(self) -> None:
        cases = (
            (
                "token_multimodal",
                TOOL_ROOT / "models/token_multimodal_hypothesis_v0.1.json",
                "token_multimodal_repeatability_qualification",
                20260716,
            ),
            (
                "ai_realtime_voice",
                TOOL_ROOT / "models/ai_realtime_voice_hypothesis_v0.2.json",
                "ai_realtime_voice_repeatability_qualification",
                20260716,
            ),
            (
                "network_comprehensive",
                REPO_ROOT / "profiles/published/network_comprehensive_standard/profile.json",
                "network_comprehensive_repeatability_qualification",
                20260727,
            ),
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for family, source, profile_id, seed in cases:
                with self.subTest(family=family):
                    output = root / profile_id
                    self.assertEqual(
                        0,
                        cli_main(
                            [
                                "publish-qualification-runtime",
                                "--family",
                                family,
                                "--source",
                                str(source),
                                "--qualification-policy",
                                str(POLICY_PATH),
                                "--seed",
                                str(seed),
                                "--out",
                                str(output),
                            ]
                        ),
                    )
                    self.assertEqual(
                        {"manifest.sha256", "profile.json", "runtime_plan.json"},
                        {path.name for path in output.iterdir()},
                    )
                    profile = json.loads((output / "profile.json").read_text(encoding="utf-8"))
                    runtime = json.loads(
                        (output / "runtime_plan.json").read_text(encoding="utf-8")
                    )
                    self.assertEqual(profile_id, profile["profile_id"])
                    self.assertEqual("repeatability_qualification", runtime["variant"])
                    self.assertEqual(profile["qualification"], runtime["qualification"])
                    self.assertFalse(profile["qualification"]["formal_baseline_eligible"])
                    self.assertEqual(
                        {
                            "profile.json": _canonical_sha256(profile),
                            "runtime_plan.json": _canonical_sha256(runtime),
                        },
                        {
                            line.split("  ", 1)[1]: line.split("  ", 1)[0]
                            for line in (output / "manifest.sha256")
                            .read_text(encoding="utf-8")
                            .splitlines()
                        },
                    )
                    published = REPO_ROOT / "profiles/published" / profile_id
                    for name in ("profile.json", "runtime_plan.json", "manifest.sha256"):
                        self.assertEqual(
                            (published / name).read_bytes(),
                            (output / name).read_bytes(),
                            f"{profile_id}/{name} is not reproducible from the frozen CLI inputs",
                        )

    def test_cli_rejects_structurally_similar_but_unapproved_d110_policy(self) -> None:
        policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
        policy["unapproved_extension"] = "must-change-the-canonical-sha"
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            tampered_policy_path = root / "tampered-policy.json"
            tampered_policy_path.write_text(
                json.dumps(policy, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            output = root / "must-not-exist"
            with self.assertRaisesRegex(
                ValueError,
                "approved D-110",
            ):
                cli_main(
                    [
                        "publish-qualification-runtime",
                        "--family",
                        "token_multimodal",
                        "--source",
                        str(TOOL_ROOT / "models/token_multimodal_hypothesis_v0.1.json"),
                        "--qualification-policy",
                        str(tampered_policy_path),
                        "--seed",
                        "20260716",
                        "--out",
                        str(output),
                    ]
                )
            self.assertFalse(output.exists())

    def test_catalog_verifier_rejects_qualification_policy_binding_tamper(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "token-qualification"
            self.assertEqual(
                0,
                cli_main(
                    [
                        "publish-qualification-runtime",
                        "--family",
                        "token_multimodal",
                        "--source",
                        str(TOOL_ROOT / "models/token_multimodal_hypothesis_v0.1.json"),
                        "--qualification-policy",
                        str(POLICY_PATH),
                        "--seed",
                        "20260716",
                        "--out",
                        str(output),
                    ]
                ),
            )
            profile = json.loads((output / "profile.json").read_text(encoding="utf-8"))
            runtime = json.loads((output / "runtime_plan.json").read_text(encoding="utf-8"))
            policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
            errors: list[str] = []
            VERIFY._validate_repeatability_qualification_binding(
                profile,
                runtime,
                policy,
                required=True,
                label="token-qualification",
                errors=errors,
            )
            self.assertEqual([], errors)

            tampered_profile = copy.deepcopy(profile)
            tampered_profile["qualification"]["policy_sha256"] = "0" * 64
            errors = []
            VERIFY._validate_repeatability_qualification_binding(
                tampered_profile,
                runtime,
                policy,
                required=True,
                label="tampered-token-qualification",
                errors=errors,
            )
            self.assertTrue(
                any("profile/runtime qualification mismatch" in error for error in errors),
                errors,
            )
            self.assertTrue(
                any("qualification binding does not match approved policy" in error for error in errors),
                errors,
            )

            tampered_policy = copy.deepcopy(policy)
            tampered_policy["common"]["runs_per_family"] = 11
            errors = []
            VERIFY._validate_repeatability_qualification_binding(
                profile,
                runtime,
                tampered_policy,
                required=True,
                label="tampered-d110-policy",
                errors=errors,
            )
            self.assertTrue(
                any("qualification binding does not match approved policy" in error for error in errors),
                errors,
            )


if __name__ == "__main__":
    unittest.main()
