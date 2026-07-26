from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.package_debug_candidate import (
    CandidateError,
    build_tool,
    finalize_candidate,
    package_candidate,
    parse_identity,
    sha256,
)


BADGING = """package: name='com.aneb.probe.codex' versionCode='42' versionName='0.5.10-codex' platformBuildVersionName='15'
minSdkVersion:'29'
targetSdkVersion:'35'
"""
SIGNER = """Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: 6644ddcf728b5bc9efaa07361fc828b9f419d977681000f2e4136c24340b89d9
"""
SCRIPT = Path(__file__).resolve().parents[1] / "package_debug_candidate.py"
WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "ci.yml"


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
        self.assertEqual(
            {
                packaged.name,
                "build-manifest.json",
                "ANEB-安装说明.txt",
            },
            {
                line.split("  ", 1)[1]
                for line in checksums.splitlines()
            },
        )
        instructions = (output / "ANEB-安装说明.txt").read_text(encoding="utf-8")
        self.assertIn("这不是正式签名 Release", instructions)
        self.assertIn("如果版本、包名或 SHA-256 任一不匹配，请不要安装", instructions)

    def test_finalizes_candidate_with_exact_attestation_inventory(self) -> None:
        self.package()
        output = self.root / "dist"
        bundle = self.root / "attestation.json"
        bundle.write_text(
            '{"mediaType":"application/vnd.dev.sigstore.bundle.v0.3+json"}\n',
            encoding="utf-8",
        )

        report = finalize_candidate(output, bundle)

        copied_bundle = output / "provenance.sigstore.json"
        self.assertEqual(bundle.read_bytes(), copied_bundle.read_bytes())
        entries = {
            line.split("  ", 1)[1]: line.split("  ", 1)[0]
            for line in (output / "checksums.sha256")
            .read_text(encoding="utf-8")
            .splitlines()
        }
        expected_names = {
            path.name for path in output.iterdir() if path.name != "checksums.sha256"
        }
        self.assertEqual(expected_names, set(entries))
        self.assertEqual(
            {name: sha256(output / name) for name in expected_names}, entries
        )
        self.assertEqual(sha256(copied_bundle), report["bundle_sha256"])
        self.assertEqual(len(expected_names), report["payload_count"])

    def test_finalize_rejects_a_symlinked_attestation_bundle(self) -> None:
        self.package()
        bundle = self.root / "attestation.json"
        bundle.write_text('{"mediaType":"sigstore"}\n', encoding="utf-8")
        link = self.root / "attestation-link.json"
        try:
            link.symlink_to(bundle)
        except OSError as error:
            self.skipTest(f"symlink unavailable: {error}")

        with self.assertRaisesRegex(CandidateError, "attestation_bundle_invalid"):
            finalize_candidate(self.root / "dist", link)

        self.assertFalse(
            (self.root / "dist" / "provenance.sigstore.json").exists()
        )

    def test_finalize_rejects_a_bundle_beneath_an_ancestor_junction(self) -> None:
        self.package()
        target_parent = self.root / "bundle-parent-target"
        target_parent.mkdir()
        bundle = target_parent / "attestation.json"
        bundle.write_text('{"mediaType":"sigstore"}\n', encoding="utf-8")
        linked_parent = self.root / "bundle-parent-link"
        if os.name == "nt":
            completed = subprocess.run(
                [
                    os.environ.get("COMSPEC", "cmd.exe"),
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(linked_parent),
                    str(target_parent),
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            if completed.returncode != 0:
                self.skipTest(f"junction unavailable: {completed.stderr}")
        else:
            linked_parent.symlink_to(target_parent, target_is_directory=True)

        with self.assertRaisesRegex(CandidateError, "attestation_bundle_invalid"):
            finalize_candidate(
                self.root / "dist",
                linked_parent / "attestation.json",
            )

    def test_finalize_rejects_a_reparse_or_symlinked_candidate_directory(self) -> None:
        self.package()
        candidate = self.root / "dist"
        candidate_link = self.root / "dist-link"
        if os.name == "nt":
            completed = subprocess.run(
                [
                    os.environ.get("COMSPEC", "cmd.exe"),
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(candidate_link),
                    str(candidate),
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            if completed.returncode != 0:
                self.skipTest(f"junction unavailable: {completed.stderr}")
        else:
            candidate_link.symlink_to(candidate, target_is_directory=True)
        bundle = self.root / "attestation.json"
        bundle.write_text('{"mediaType":"sigstore"}\n', encoding="utf-8")

        with self.assertRaisesRegex(CandidateError, "candidate_directory_invalid"):
            finalize_candidate(candidate_link, bundle)

    def test_finalize_rejects_a_candidate_beneath_an_ancestor_junction(self) -> None:
        self.package()
        target_parent = self.root / "candidate-target"
        target_parent.mkdir()
        candidate = target_parent / "dist"
        (self.root / "dist").rename(candidate)
        linked_parent = self.root / "candidate-linked-parent"
        if os.name == "nt":
            completed = subprocess.run(
                [
                    os.environ.get("COMSPEC", "cmd.exe"),
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(linked_parent),
                    str(target_parent),
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            if completed.returncode != 0:
                self.skipTest(f"junction unavailable: {completed.stderr}")
        else:
            linked_parent.symlink_to(target_parent, target_is_directory=True)
        bundle = self.root / "attestation.json"
        bundle.write_text('{"mediaType":"sigstore"}\n', encoding="utf-8")

        with self.assertRaisesRegex(CandidateError, "candidate_directory_invalid"):
            finalize_candidate(linked_parent / "dist", bundle)

    def test_packaging_rejects_a_direct_output_junction(self) -> None:
        target = self.root / "output-target"
        target.mkdir()
        output = self.root / "output-link"
        if os.name == "nt":
            completed = subprocess.run(
                [
                    os.environ.get("COMSPEC", "cmd.exe"),
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(output),
                    str(target),
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            if completed.returncode != 0:
                self.skipTest(f"junction unavailable: {completed.stderr}")
        else:
            output.symlink_to(target, target_is_directory=True)

        with self.assertRaisesRegex(CandidateError, "candidate_output_invalid"):
            package_candidate(
                self.apk,
                self.metadata,
                output,
                BADGING,
                SIGNER,
            )

    def test_packaging_rejects_an_output_beneath_an_ancestor_junction(self) -> None:
        target_parent = self.root / "output-parent-target"
        target_parent.mkdir()
        linked_parent = self.root / "output-parent-link"
        if os.name == "nt":
            completed = subprocess.run(
                [
                    os.environ.get("COMSPEC", "cmd.exe"),
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(linked_parent),
                    str(target_parent),
                ],
                capture_output=True,
                check=False,
                text=True,
            )
            if completed.returncode != 0:
                self.skipTest(f"junction unavailable: {completed.stderr}")
        else:
            linked_parent.symlink_to(target_parent, target_is_directory=True)

        with self.assertRaisesRegex(CandidateError, "candidate_output_invalid"):
            package_candidate(
                self.apk,
                self.metadata,
                linked_parent / "dist",
                BADGING,
                SIGNER,
            )

    def test_finalize_refuses_to_replace_an_existing_provenance_target(self) -> None:
        self.package()
        output = self.root / "dist"
        target = output / "provenance.sigstore.json"
        target.write_text('{"owner":"existing"}\n', encoding="utf-8")
        bundle = self.root / "attestation.json"
        bundle.write_text('{"owner":"new"}\n', encoding="utf-8")

        with self.assertRaisesRegex(
            CandidateError, "attestation_bundle_target_exists"
        ):
            finalize_candidate(output, bundle)

        self.assertEqual('{"owner":"existing"}\n', target.read_text(encoding="utf-8"))

    def test_finalize_rejects_duplicate_keys_in_the_sigstore_bundle(self) -> None:
        self.package()
        output = self.root / "dist"
        bundle = self.root / "attestation.json"
        bundle.write_text(
            '{"mediaType":"expected","mediaType":"substituted"}\n',
            encoding="utf-8",
        )

        with self.assertRaisesRegex(CandidateError, "attestation_bundle_invalid"):
            finalize_candidate(output, bundle)

        self.assertFalse((output / "provenance.sigstore.json").exists())

    def test_finalize_rejects_nonstandard_json_constants_in_the_bundle(self) -> None:
        self.package()
        output = self.root / "dist"
        bundle = self.root / "attestation.json"
        bundle.write_text('{"integratedTime":NaN}\n', encoding="utf-8")

        with self.assertRaisesRegex(CandidateError, "attestation_bundle_invalid"):
            finalize_candidate(output, bundle)

        self.assertFalse((output / "provenance.sigstore.json").exists())

    def test_finalize_rejects_an_oversized_bundle_before_copying_it(self) -> None:
        self.package()
        output = self.root / "dist"
        bundle = self.root / "attestation.json"
        with bundle.open("wb") as stream:
            stream.truncate(16 * 1024 * 1024 + 1)

        with self.assertRaisesRegex(CandidateError, "attestation_bundle_invalid"):
            finalize_candidate(output, bundle)

        self.assertFalse((output / "provenance.sigstore.json").exists())

    def test_finalize_rejects_a_bundle_source_inside_the_candidate_boundary(self) -> None:
        self.package()
        output = self.root / "dist"
        bundle = output / "incoming-attestation.json"
        bundle.write_text('{"mediaType":"sigstore"}\n', encoding="utf-8")

        with self.assertRaisesRegex(
            CandidateError, "attestation_bundle_boundary_invalid"
        ):
            finalize_candidate(output, bundle)

        self.assertFalse((output / "provenance.sigstore.json").exists())

    def test_finalize_rejects_an_extra_candidate_payload(self) -> None:
        self.package()
        output = self.root / "dist"
        (output / "SECOND-UNATTESTED.apk").write_bytes(b"not-an-apk")
        bundle = self.root / "attestation.json"
        bundle.write_text('{"mediaType":"sigstore"}\n', encoding="utf-8")

        with self.assertRaisesRegex(CandidateError, "candidate_inventory_invalid"):
            finalize_candidate(output, bundle)

        self.assertFalse((output / "provenance.sigstore.json").exists())

    def test_finalize_cli_attaches_the_bundle_without_android_tools(self) -> None:
        self.package()
        output = self.root / "dist"
        bundle = self.root / "attestation.json"
        bundle.write_text('{"mediaType":"sigstore"}\n', encoding="utf-8")

        completed = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--output",
                str(output),
                "--finalize-bundle",
                str(bundle),
            ],
            capture_output=True,
            check=False,
            text=True,
            encoding="utf-8",
            timeout=10,
        )

        self.assertEqual("", completed.stderr)
        self.assertEqual(0, completed.returncode)
        self.assertIn("ANEB debug candidate finalization: PASS", completed.stdout)
        self.assertTrue((output / "provenance.sigstore.json").is_file())

    def test_ci_non_pr_path_attests_finalizes_and_reverifies_before_upload(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("artifact-metadata: write", workflow)
        self.assertIn("name: Android candidate build", workflow)
        attest = workflow.index("id: attest")
        bundle = workflow.index("${{ steps.attest.outputs.bundle-path }}")
        verifier = workflow.index("python scripts/verify_ci_apk_provenance.py")
        upload = workflow.index("name: aneb-probe-debug-verified-${{ github.sha }}")
        self.assertLess(attest, bundle)
        self.assertLess(bundle, verifier)
        self.assertLess(verifier, upload)
        self.assertIn('--source-commit "${{ github.sha }}"', workflow)
        self.assertIn("--gh-path \"$GH_PATH\"", workflow)
        self.assertIn(
            "subject-checksums: dist/aneb-probe-debug/checksums.sha256",
            workflow,
        )
        self.assertNotIn("subject-path: dist/aneb-probe-debug/*.apk", workflow)

    def test_ci_provenance_verifier_receives_current_android_identity(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        build = (WORKFLOW.parents[2] / "app" / "probe" / "build.gradle.kts").read_text(
            encoding="utf-8"
        )

        version_code = re.search(r"(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$", build)
        version_name = re.search(r'(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$', build)
        suffix = re.search(r'(?m)^\s*versionNameSuffix\s*=\s*"([^"]+)"\s*$', build)
        self.assertIsNotNone(version_code)
        self.assertIsNotNone(version_name)
        self.assertIsNotNone(suffix)
        assert version_code is not None and version_name is not None and suffix is not None

        self.assertIn(
            f'--expected-version-name "{version_name.group(1)}{suffix.group(1)}"',
            workflow,
        )
        self.assertIn(
            f'--expected-version-code "{version_code.group(1)}"',
            workflow,
        )

    def test_ci_pr_path_is_explicitly_review_unattested(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("name: Upload review-unattested Debug candidate", workflow)
        self.assertIn("if: github.event_name == 'pull_request'", workflow)
        self.assertIn(
            "name: aneb-probe-debug-review-unattested-"
            "${{ github.event.pull_request.head.sha }}",
            workflow,
        )
        self.assertGreaterEqual(
            workflow.count("if: github.event_name != 'pull_request'"), 4
        )
        self.assertNotIn("name: Verified Android candidate", workflow)

    def test_ci_attestation_permissions_are_scoped_to_the_android_job(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        workflow_header, jobs = workflow.split("\njobs:\n", 1)
        android_job = jobs.split("\n  contracts:\n", 1)[0]

        self.assertIn("permissions:\n  contents: read", workflow_header)
        self.assertNotIn("id-token: write", workflow_header)
        self.assertNotIn("attestations: write", workflow_header)
        self.assertNotIn("artifact-metadata: write", workflow_header)
        self.assertIn(
            "    permissions:\n"
            "      contents: read\n"
            "      id-token: write\n"
            "      attestations: write\n"
            "      artifact-metadata: write",
            android_job,
        )

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
