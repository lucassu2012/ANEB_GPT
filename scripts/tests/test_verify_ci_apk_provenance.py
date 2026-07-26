from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from copy import deepcopy
from pathlib import Path

from scripts.verify_ci_apk_provenance import (
    ProvenanceVerificationFailure,
    verify_candidate,
)


SOURCE_COMMIT = "a" * 40
EXPECTED_VERSION_NAME = "0.5.12-codex"
EXPECTED_VERSION_CODE = 44
SOURCE_REF = "refs/heads/codex/d82-ci-provenance"
REPOSITORY = "lucassu2012/ANEB_GPT"
WORKFLOW = "lucassu2012/ANEB_GPT/.github/workflows/ci.yml"
WORKFLOW_URI = f"https://github.com/{WORKFLOW}@{SOURCE_REF}"
RUN_URL = f"https://github.com/{REPOSITORY}/actions/runs/123456789"
SCRIPT = Path(__file__).resolve().parents[1] / "verify_ci_apk_provenance.py"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        + "\n",
        encoding="utf-8",
    )


class CandidateFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.candidate = root / "candidate"
        self.candidate.mkdir()
        self.apk_name = "ANEB-Probe-0.5.12-codex-debug.apk"
        self.apk = self.candidate / self.apk_name
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"binary-manifest")
            archive.writestr("classes.dex", b"dex")
        self.apk_sha = sha256(self.apk)
        self.bundle = self.candidate / "provenance.sigstore.json"
        write_json(self.bundle, {"mediaType": "application/vnd.dev.sigstore.bundle.v0.3+json"})
        self.manifest = self.candidate / "build-manifest.json"
        write_json(
            self.manifest,
            {
                "contract_version": "aneb-debug-candidate-v1",
                "artifact_kind": "debug_non_release",
                "public_release": False,
                "source": {
                    "git_sha": SOURCE_COMMIT,
                    "git_ref": SOURCE_REF,
                    "workflow_run_url": RUN_URL,
                },
                "apk": {
                    "file_name": self.apk_name,
                    "sha256": self.apk_sha.upper(),
                    "size_bytes": self.apk.stat().st_size,
                    "package_id": "com.aneb.probe.codex",
                    "version_name": "0.5.12-codex",
                    "version_code": 44,
                    "min_sdk": 29,
                    "target_sdk": 35,
                    "signer_dn": "C=US, O=Android, CN=Android Debug",
                    "signer_sha256": "b" * 64,
                },
                "verification": {
                    "zip_integrity": "pass",
                    "gradle_apk_identity_match": "pass",
                    "apksigner_verification": "pass",
                    "debug_boundary": "pass",
                },
            },
        )
        self.instructions = self.candidate / "ANEB-安装说明.txt"
        self.instructions.write_text("test-only instructions\n", encoding="utf-8")
        self.checksums = self.candidate / "checksums.sha256"
        self.rebuild_checksums()
        self.arguments = root / "gh-arguments.json"
        self.gh = root / "fake_gh.py"
        self.write_gh()

    def rebuild_checksums(self) -> None:
        names = sorted(
            path.name for path in self.candidate.iterdir() if path.name != "checksums.sha256"
        )
        with self.checksums.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(
                "".join(f"{sha256(self.candidate / name)}  {name}\n" for name in names)
            )

    def verification_output(self) -> list[object]:
        return [
            {
                "attestation": {"bundle": "verified"},
                "verificationResult": {
                    "signature": {
                        "certificate": {
                            "certificateIssuer": "CN=sigstore-intermediate,O=sigstore.dev",
                            "issuer": "https://token.actions.githubusercontent.com",
                            "subjectAlternativeName": WORKFLOW_URI,
                            "buildSignerURI": WORKFLOW_URI,
                            "buildSignerDigest": SOURCE_COMMIT,
                            "runnerEnvironment": "github-hosted",
                            "sourceRepositoryURI": f"https://github.com/{REPOSITORY}",
                            "sourceRepositoryDigest": SOURCE_COMMIT,
                            "sourceRepositoryRef": SOURCE_REF,
                            "buildConfigURI": WORKFLOW_URI,
                            "buildConfigDigest": SOURCE_COMMIT,
                            "runInvocationURI": RUN_URL + "/attempts/1",
                        }
                    },
                    "verifiedTimestamps": [
                        {
                            "type": "transparency-log",
                            "uri": "https://rekor.sigstore.dev",
                            "timestamp": "2026-07-19T00:00:00Z",
                        }
                    ],
                    "statement": {
                        "_type": "https://in-toto.io/Statement/v1",
                        "predicateType": "https://slsa.dev/provenance/v1",
                        "subject": [
                            {
                                "name": self.apk_name,
                                "digest": {"sha256": self.apk_sha},
                            },
                            {
                                "name": "build-manifest.json",
                                "digest": {"sha256": sha256(self.manifest)},
                            },
                            {
                                "name": self.instructions.name,
                                "digest": {"sha256": sha256(self.instructions)},
                            },
                        ],
                        "predicate": {},
                    },
                },
            }
        ]

    def write_gh(
        self,
        *,
        output: object | None = None,
        raw_output: str | None = None,
        delay_seconds: int = 0,
        return_code: int = 0,
    ) -> None:
        rendered = (
            raw_output
            if raw_output is not None
            else json.dumps(self.verification_output() if output is None else output)
        )
        source = f"""#!/usr/bin/env python3
import json
import pathlib
import sys
import time

if sys.argv[1:] == [\"--version\"]:
    print(\"gh version 2.96.0 (2026-07-16)\")
    raise SystemExit(0)
pathlib.Path({str(self.arguments)!r}).write_text(json.dumps(sys.argv[1:]), encoding=\"utf-8\")
time.sleep({delay_seconds!r})
sys.stdout.write({rendered!r} + \"\\n\")
raise SystemExit({return_code!r})
"""
        self.gh.write_text(source, encoding="utf-8")

    @property
    def gh_command(self) -> tuple[str, ...]:
        return (str(Path(sys.executable).resolve(strict=True)), str(self.gh))


class CiApkProvenanceVerifierTests(unittest.TestCase):
    def assert_reason(self, fixture: CandidateFixture, reason: str) -> None:
        with self.assertRaises(ProvenanceVerificationFailure) as caught:
            verify_candidate(
                fixture.candidate,
                expected_source_commit=SOURCE_COMMIT,
                expected_version_name=EXPECTED_VERSION_NAME,
                expected_version_code=EXPECTED_VERSION_CODE,
                gh_command=fixture.gh_command,
                timeout_seconds=2,
            )
        self.assertEqual(reason, caught.exception.reason_code)

    def test_valid_candidate_is_bound_to_exact_gh_policy_and_source_commit(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))

            report = verify_candidate(
                fixture.candidate,
                expected_source_commit=SOURCE_COMMIT,
                expected_version_name=EXPECTED_VERSION_NAME,
                expected_version_code=EXPECTED_VERSION_CODE,
                gh_command=fixture.gh_command,
                timeout_seconds=5,
            )
            invoked_arguments = json.loads(
                fixture.arguments.read_text(encoding="utf-8")
            )

        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(SOURCE_COMMIT, report["source_commit"])
        self.assertEqual(fixture.apk_sha, report["apk"]["sha256"])
        self.assertEqual("2.96.0", report["gh"]["version"])
        self.assertEqual(
            "CN=sigstore-intermediate,O=sigstore.dev",
            report["gh"]["certificate_issuer"],
        )
        self.assertEqual(
            "https://token.actions.githubusercontent.com",
            report["gh"]["oidc_issuer"],
        )
        self.assertEqual(
            sha256(Path(sys.executable).resolve(strict=True)),
            report["gh"]["executable_sha256"],
        )
        self.assertEqual(
            [
                "attestation",
                "verify",
                str(fixture.apk.resolve()),
                "--repo",
                REPOSITORY,
                "--bundle",
                str(fixture.bundle.resolve()),
                "--signer-workflow",
                WORKFLOW,
                "--source-digest",
                SOURCE_COMMIT,
                "--source-ref",
                SOURCE_REF,
                "--predicate-type",
                "https://slsa.dev/provenance/v1",
                "--deny-self-hosted-runners",
                "--format",
                "json",
            ],
            invoked_arguments,
        )

    def test_expected_version_mismatch_has_a_specific_machine_reason(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            with self.assertRaises(ProvenanceVerificationFailure) as caught:
                verify_candidate(
                    fixture.candidate,
                    expected_source_commit=SOURCE_COMMIT,
                    expected_version_name="0.5.13-codex",
                    expected_version_code=45,
                    gh_command=fixture.gh_command,
                    timeout_seconds=5,
                )

        self.assertEqual("apk_version_mismatch", caught.exception.reason_code)

    def test_expected_version_input_is_strict(self) -> None:
        cases = (
            ("0.5.13", 45),
            (EXPECTED_VERSION_NAME, True),
            (EXPECTED_VERSION_NAME, 0),
        )
        for version_name, version_code in cases:
            with self.subTest(
                version_name=version_name, version_code=version_code
            ), tempfile.TemporaryDirectory() as raw:
                fixture = CandidateFixture(Path(raw))
                with self.assertRaises(ProvenanceVerificationFailure) as caught:
                    verify_candidate(
                        fixture.candidate,
                        expected_source_commit=SOURCE_COMMIT,
                        expected_version_name=version_name,
                        expected_version_code=version_code,
                        gh_command=fixture.gh_command,
                        timeout_seconds=5,
                    )

                self.assertEqual(
                    "expected_apk_identity_invalid", caught.exception.reason_code
                )

    def test_candidate_beneath_an_ancestor_junction_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            target_parent = root / "candidate-parent-target"
            target_parent.mkdir()
            fixture = CandidateFixture(target_parent)
            linked_parent = root / "candidate-parent-link"
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

            with self.assertRaises(ProvenanceVerificationFailure) as caught:
                verify_candidate(
                    linked_parent / "candidate",
                    expected_source_commit=SOURCE_COMMIT,
                    expected_version_name=EXPECTED_VERSION_NAME,
                    expected_version_code=EXPECTED_VERSION_CODE,
                    gh_command=fixture.gh_command,
                    timeout_seconds=5,
                )

        self.assertEqual("candidate_directory_invalid", caught.exception.reason_code)

    def test_direct_candidate_junction_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            fixture = CandidateFixture(root)
            candidate_link = root / "candidate-link"
            if os.name == "nt":
                completed = subprocess.run(
                    [
                        os.environ.get("COMSPEC", "cmd.exe"),
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        str(candidate_link),
                        str(fixture.candidate),
                    ],
                    capture_output=True,
                    check=False,
                    text=True,
                )
                if completed.returncode != 0:
                    self.skipTest(f"junction unavailable: {completed.stderr}")
            else:
                candidate_link.symlink_to(
                    fixture.candidate,
                    target_is_directory=True,
                )

            with self.assertRaises(ProvenanceVerificationFailure) as caught:
                verify_candidate(
                    candidate_link,
                    expected_source_commit=SOURCE_COMMIT,
                    expected_version_name=EXPECTED_VERSION_NAME,
                    expected_version_code=EXPECTED_VERSION_CODE,
                    gh_command=fixture.gh_command,
                    timeout_seconds=5,
                )

        self.assertEqual("candidate_directory_invalid", caught.exception.reason_code)

    def test_gh_executable_beneath_an_ancestor_junction_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            fixture = CandidateFixture(root)
            executable = Path(sys.executable).resolve()
            linked_parent = root / "gh-parent-link"
            if os.name == "nt":
                completed = subprocess.run(
                    [
                        os.environ.get("COMSPEC", "cmd.exe"),
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        str(linked_parent),
                        str(executable.parent),
                    ],
                    capture_output=True,
                    check=False,
                    text=True,
                )
                if completed.returncode != 0:
                    self.skipTest(f"junction unavailable: {completed.stderr}")
            else:
                linked_parent.symlink_to(
                    executable.parent,
                    target_is_directory=True,
                )

            with self.assertRaises(ProvenanceVerificationFailure) as caught:
                verify_candidate(
                    fixture.candidate,
                    expected_source_commit=SOURCE_COMMIT,
                    expected_version_name=EXPECTED_VERSION_NAME,
                    expected_version_code=EXPECTED_VERSION_CODE,
                    gh_command=(
                        str(linked_parent / executable.name),
                        str(fixture.gh),
                    ),
                    timeout_seconds=5,
                )

        self.assertEqual("gh_executable_invalid", caught.exception.reason_code)

    def test_direct_symlinked_gh_executable_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            fixture = CandidateFixture(root)
            gh_link = root / ("gh-link.exe" if os.name == "nt" else "gh-link")
            try:
                gh_link.symlink_to(Path(sys.executable).resolve())
            except OSError as error:
                self.skipTest(f"file symlink unavailable: {error}")

            with self.assertRaises(ProvenanceVerificationFailure) as caught:
                verify_candidate(
                    fixture.candidate,
                    expected_source_commit=SOURCE_COMMIT,
                    expected_version_name=EXPECTED_VERSION_NAME,
                    expected_version_code=EXPECTED_VERSION_CODE,
                    gh_command=(str(gh_link), str(fixture.gh)),
                    timeout_seconds=5,
                )

        self.assertEqual("gh_executable_invalid", caught.exception.reason_code)

    def test_verified_certificate_from_other_repository_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            output = deepcopy(fixture.verification_output())
            certificate = output[0]["verificationResult"]["signature"]["certificate"]
            certificate["sourceRepositoryURI"] = "https://github.com/attacker/repo"
            fixture.write_gh(output=output)

            self.assert_reason(fixture, "attestation_policy_mismatch")

    def test_verified_certificate_from_other_workflow_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            output = deepcopy(fixture.verification_output())
            certificate = output[0]["verificationResult"]["signature"]["certificate"]
            certificate["buildSignerURI"] = (
                f"https://github.com/{REPOSITORY}/.github/workflows/other.yml@{SOURCE_REF}"
            )
            fixture.write_gh(output=output)

            self.assert_reason(fixture, "attestation_policy_mismatch")

    def test_verified_certificate_from_other_oidc_issuer_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            output = deepcopy(fixture.verification_output())
            certificate = output[0]["verificationResult"]["signature"]["certificate"]
            certificate["issuer"] = "https://token.attacker.invalid"
            fixture.write_gh(output=output)

            self.assert_reason(fixture, "attestation_policy_mismatch")

    def test_wrong_commit_ref_or_self_hosted_runner_is_rejected(self) -> None:
        cases = {
            "sourceRepositoryDigest": "c" * 40,
            "sourceRepositoryRef": "refs/heads/main",
            "runnerEnvironment": "self-hosted",
        }
        for field, value in cases.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as raw:
                fixture = CandidateFixture(Path(raw))
                output = deepcopy(fixture.verification_output())
                certificate = output[0]["verificationResult"]["signature"][
                    "certificate"
                ]
                certificate[field] = value
                fixture.write_gh(output=output)

                self.assert_reason(fixture, "attestation_policy_mismatch")

    def test_manifest_cannot_self_report_a_different_source_commit(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            manifest = json.loads(fixture.manifest.read_text(encoding="utf-8"))
            manifest["source"]["git_sha"] = "c" * 40
            write_json(fixture.manifest, manifest)
            fixture.rebuild_checksums()

            self.assert_reason(fixture, "source_commit_mismatch")

    def test_hung_gh_verification_is_killed_at_the_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.write_gh(delay_seconds=30)
            started = time.monotonic()

            with self.assertRaises(ProvenanceVerificationFailure) as caught:
                verify_candidate(
                    fixture.candidate,
                    expected_source_commit=SOURCE_COMMIT,
                    expected_version_name=EXPECTED_VERSION_NAME,
                    expected_version_code=EXPECTED_VERSION_CODE,
                    gh_command=fixture.gh_command,
                    timeout_seconds=1,
                )

            elapsed = time.monotonic() - started
        self.assertEqual("gh_attestation_timeout", caught.exception.reason_code)
        self.assertLess(elapsed, 5.0)

    def test_duplicate_json_key_in_gh_output_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            output = json.dumps(fixture.verification_output())
            needle = f'"predicateType": "https://slsa.dev/provenance/v1"'
            duplicate = needle + ', "predicateType": "https://attacker.invalid/type"'
            self.assertIn(needle, output)
            fixture.write_gh(raw_output=output.replace(needle, duplicate, 1))

            self.assert_reason(fixture, "gh_output_invalid")

    def test_duplicate_json_keys_in_candidate_inputs_are_rejected(self) -> None:
        with self.subTest(input="manifest"), tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            manifest = fixture.manifest.read_text(encoding="utf-8")
            needle = f'"git_sha":"{SOURCE_COMMIT}"'
            fixture.manifest.write_text(
                manifest.replace(needle, needle + ',"git_sha":"' + "c" * 40 + '"', 1),
                encoding="utf-8",
            )
            fixture.rebuild_checksums()
            self.assert_reason(fixture, "build_manifest_invalid")

        with self.subTest(input="bundle"), tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.bundle.write_text(
                '{"mediaType":"one","mediaType":"two"}\n', encoding="utf-8"
            )
            fixture.rebuild_checksums()
            self.assert_reason(fixture, "attestation_bundle_invalid")

    def test_nonstandard_json_constants_in_candidate_inputs_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.bundle.write_text('{"integratedTime":NaN}\n', encoding="utf-8")
            fixture.rebuild_checksums()

            self.assert_reason(fixture, "attestation_bundle_invalid")

    def test_attested_subject_digest_must_equal_the_real_apk(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            output = deepcopy(fixture.verification_output())
            output[0]["verificationResult"]["statement"]["subject"][0]["digest"][
                "sha256"
            ] = "c" * 64
            fixture.write_gh(output=output)

            self.assert_reason(fixture, "attestation_subject_mismatch")

    def test_attested_subject_digests_accept_uppercase_emitted_by_actions_attest(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            output = deepcopy(fixture.verification_output())
            for subject in output[0]["verificationResult"]["statement"]["subject"]:
                subject["digest"]["sha256"] = subject["digest"]["sha256"].upper()
            fixture.write_gh(output=output)

            report = verify_candidate(
                fixture.candidate,
                expected_source_commit=SOURCE_COMMIT,
                expected_version_name=EXPECTED_VERSION_NAME,
                expected_version_code=EXPECTED_VERSION_CODE,
                gh_command=fixture.gh_command,
                timeout_seconds=5,
            )

        self.assertEqual("pass", report["status"])

    def test_attested_subject_names_must_be_the_exact_three_initial_payloads(self) -> None:
        for case in ("missing", "extra", "duplicate"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as raw:
                fixture = CandidateFixture(Path(raw))
                output = deepcopy(fixture.verification_output())
                subjects = output[0]["verificationResult"]["statement"]["subject"]
                if case == "missing":
                    subjects.pop()
                elif case == "extra":
                    subjects.append(
                        {"name": "unexpected.txt", "digest": {"sha256": "c" * 64}}
                    )
                else:
                    subjects[-1] = deepcopy(subjects[0])
                fixture.write_gh(output=output)

                self.assert_reason(fixture, "attestation_subject_mismatch")

    def test_manifest_apk_digest_must_equal_the_real_apk(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            with zipfile.ZipFile(fixture.apk, "a") as archive:
                archive.writestr("assets/tampered.txt", b"tampered")
            fixture.rebuild_checksums()

            self.assert_reason(fixture, "apk_identity_mismatch")

    def test_attestation_must_bind_the_install_instructions_not_only_the_apk(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.instructions.write_text(
                "ATTACKER REPLACED INSTALL INSTRUCTIONS\n",
                encoding="utf-8",
            )
            fixture.rebuild_checksums()

            self.assert_reason(fixture, "attestation_subject_mismatch")

    def test_nonzero_gh_verification_is_rejected_without_trusting_stdout(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.write_gh(return_code=1)

            self.assert_reason(fixture, "gh_attestation_rejected")

    def test_malformed_gh_json_output_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.write_gh(raw_output="not-json")

            self.assert_reason(fixture, "gh_output_invalid")

    def test_oversized_gh_output_is_killed_and_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.write_gh(raw_output="x" * (8 * 1024 * 1024 + 1))

            self.assert_reason(fixture, "gh_attestation_output_too_large")

    def test_checksums_must_cover_and_match_every_candidate_payload(self) -> None:
        with self.subTest(case="missing"), tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            lines = fixture.checksums.read_text(encoding="utf-8").splitlines()
            with fixture.checksums.open("w", encoding="utf-8", newline="\n") as stream:
                stream.write(
                    "\n".join(
                        line for line in lines if not line.endswith("provenance.sigstore.json")
                    )
                    + "\n"
                )
            self.assert_reason(fixture, "checksums_invalid")

        with self.subTest(case="mismatch"), tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            fixture.instructions.write_text("changed\n", encoding="utf-8")
            self.assert_reason(fixture, "checksums_mismatch")

    def test_candidate_rejects_an_extra_checksummed_payload(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            (fixture.candidate / "SECOND-UNATTESTED.apk").write_bytes(b"not-an-apk")
            fixture.rebuild_checksums()

            self.assert_reason(fixture, "candidate_file_set_invalid")

    def test_cli_failure_is_one_stable_machine_json_line(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            fixture = CandidateFixture(Path(raw))
            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    str(fixture.candidate),
                    "--source-commit",
                    "not-a-commit",
                    "--expected-version-name",
                    EXPECTED_VERSION_NAME,
                    "--expected-version-code",
                    str(EXPECTED_VERSION_CODE),
                    "--gh-path",
                    str(Path(sys.executable).resolve(strict=True)),
                ],
                capture_output=True,
                check=False,
                text=True,
                encoding="utf-8",
                timeout=10,
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual("", completed.stderr)
        self.assertEqual(1, len(completed.stdout.splitlines()))
        report = json.loads(completed.stdout)
        self.assertEqual(
            {
                "schema": "aneb-ci-apk-provenance-report",
                "schema_version": "1.0.0",
                "status": "fail",
                "reason_code": "source_commit_invalid",
                "candidate_provenance_reverified": False,
                "source_commit": None,
                "apk": None,
            },
            report,
        )


if __name__ == "__main__":
    unittest.main()
