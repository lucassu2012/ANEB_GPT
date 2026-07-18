from __future__ import annotations

import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.package_debug_candidate import CandidateError, build_tool, package_candidate, parse_identity, sha256


BADGING = """package: name='com.aneb.probe.codex' versionCode='42' versionName='0.5.10-codex' platformBuildVersionName='15'
minSdkVersion:'29'
targetSdkVersion:'35'
"""
SIGNER = """Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: 6644ddcf728b5bc9efaa07361fc828b9f419d977681000f2e4136c24340b89d9
"""


class DebugCandidatePackagingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.apk = self.root / "probe-debug.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"dex")
        self.metadata = self.root / "output-metadata.json"
        self.metadata.write_text(
            json.dumps(
                {
                    "applicationId": "com.aneb.probe.codex",
                    "variantName": "debug",
                    "elements": [
                        {
                            "type": "SINGLE",
                            "filters": [],
                            "versionCode": 42,
                            "versionName": "0.5.10-codex",
                            "outputFile": "probe-debug.apk",
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def package(self, badging: str = BADGING, signer: str = SIGNER) -> dict[str, object]:
        return package_candidate(
            self.apk,
            self.metadata,
            self.root / "dist",
            badging,
            signer,
            source_sha="a" * 40,
            source_ref="refs/heads/codex/test",
            run_url="https://github.example/actions/runs/1",
        )

    def test_packages_exact_apk_manifest_checksums_and_chinese_instructions(self) -> None:
        manifest = self.package()
        output = self.root / "dist"
        packaged = output / "ANEB-Probe-0.5.10-codex-debug.apk"

        self.assertEqual("debug_non_release", manifest["artifact_kind"])
        self.assertFalse(manifest["public_release"])
        self.assertEqual(sha256(self.apk), manifest["apk"]["sha256"])
        self.assertEqual(self.apk.read_bytes(), packaged.read_bytes())
        checksums = (output / "checksums.sha256").read_text(encoding="utf-8")
        self.assertIn(f"{sha256(packaged)}  {packaged.name}", checksums)
        instructions = (output / "ANEB-安装说明.txt").read_text(encoding="utf-8")
        self.assertIn("这不是正式签名 Release", instructions)
        self.assertIn("如果版本、包名或 SHA-256 任一不匹配，不要安装", instructions)

    def test_parser_requires_complete_aapt_and_signer_identity(self) -> None:
        with self.assertRaisesRegex(CandidateError, "apk_identity_output_incomplete"):
            parse_identity("package: name='x'", SIGNER)

    def test_parser_accepts_sdk_alias_reordered_fields_and_colon_digest(self) -> None:
        badging = """package: versionName='0.5.10-codex' name='com.aneb.probe.codex' versionCode='42'
sdkVersion:'29'
targetSdkVersion:'35'
"""
        digest = "66" * 32
        signer = SIGNER.replace(
            "6644ddcf728b5bc9efaa07361fc828b9f419d977681000f2e4136c24340b89d9",
            ":".join(digest[index : index + 2] for index in range(0, len(digest), 2)),
        )
        identity = parse_identity(badging, signer)
        self.assertEqual("com.aneb.probe.codex", identity.package_id)
        self.assertEqual(42, identity.version_code)
        self.assertEqual(29, identity.min_sdk)
        self.assertEqual(digest.upper(), identity.signer_sha256)

    def test_build_tool_can_pin_exact_version(self) -> None:
        sdk = self.root / "sdk"
        tool_dir = sdk / "build-tools" / "35.0.0"
        tool_dir.mkdir(parents=True)
        suffix = ".exe" if os.name == "nt" else ""
        expected = tool_dir / f"aapt2{suffix}"
        expected.touch()
        newer = sdk / "build-tools" / "99.0.0"
        newer.mkdir()
        (newer / f"aapt2{suffix}").touch()
        with patch.dict(os.environ, {"ANDROID_HOME": str(sdk), "ANDROID_SDK_ROOT": ""}):
            self.assertEqual(expected, build_tool("aapt2", "35.0.0"))

    def test_rejects_apk_and_gradle_identity_mismatch(self) -> None:
        mismatch = BADGING.replace("versionCode='42'", "versionCode='43'")
        with self.assertRaisesRegex(CandidateError, "apk_gradle_identity_mismatch"):
            self.package(badging=mismatch)

    def test_rejects_non_codex_package_even_when_metadata_matches(self) -> None:
        payload = json.loads(self.metadata.read_text(encoding="utf-8"))
        payload["applicationId"] = "com.aneb.probe"
        self.metadata.write_text(json.dumps(payload), encoding="utf-8")
        release_badging = BADGING.replace("com.aneb.probe.codex", "com.aneb.probe")
        with self.assertRaisesRegex(CandidateError, "candidate_package_not_codex_debug"):
            self.package(badging=release_badging)

    def test_rejects_non_debug_signer(self) -> None:
        signer = SIGNER.replace("CN=Android Debug", "CN=Production")
        with self.assertRaisesRegex(CandidateError, "candidate_signer_not_android_debug"):
            self.package(signer=signer)

    def test_refuses_to_overwrite_nonempty_output(self) -> None:
        output = self.root / "dist"
        output.mkdir()
        (output / "keep.txt").write_text("owner data", encoding="utf-8")
        with self.assertRaisesRegex(CandidateError, "candidate_output_not_empty"):
            self.package()
        self.assertEqual("owner data", (output / "keep.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
