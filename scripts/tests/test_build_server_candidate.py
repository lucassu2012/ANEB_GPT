from __future__ import annotations

import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import build_server_candidate as candidate_builder


SCRIPT = Path(__file__).resolve().parents[1] / "build_server_candidate.py"
CI_WORKFLOW = SCRIPT.parents[1] / ".github" / "workflows" / "ci.yml"
PUBLIC_CERTIFICATE = SCRIPT.parents[1] / "server" / "certs" / "aneb_local_cert.pem"
SCHEMA = "aneb-server-build-provenance-v1"
CANONICAL_FLAGS = [
    "-trimpath",
    "-buildvcs=true",
    "-mod=readonly",
    "-pgo=off",
]
FIXED_ENVIRONMENT = {
    "CGO_ENABLED": "0",
    "GOAMD64": "v1",
    "GOARCH": "amd64",
    "GOEXPERIMENT": "",
    "GOENV": "off",
    "GOFIPS140": "off",
    "GOFLAGS": "",
    "GOOS": "linux",
    "GOTOOLCHAIN": "local",
    "GOWORK": "off",
}
ARTIFACT_PATHS = {
    "aneb-server.service": "server/aneb-server.service",
    "root-profiles/basic_network.json": "profiles/basic_network.json",
    "root-profiles/s1_chat.json": "profiles/s1_chat.json",
    "root-profiles/s2_coding_agent.json": "profiles/s2_coding_agent.json",
    "root-profiles/s3_multimodal.json": "profiles/s3_multimodal.json",
    "execution-profiles/token_multimodal_quick/profile.json": (
        "profiles/published/token_multimodal_quick/profile.json"
    ),
    "execution-profiles/token_multimodal_quick/runtime_plan.json": (
        "profiles/published/token_multimodal_quick/runtime_plan.json"
    ),
    "execution-profiles/token_multimodal_quick/manifest.sha256": (
        "profiles/published/token_multimodal_quick/manifest.sha256"
    ),
    "execution-profiles/ai_realtime_voice_quick/profile.json": (
        "profiles/published/ai_realtime_voice_quick/profile.json"
    ),
    "execution-profiles/ai_realtime_voice_quick/runtime_plan.json": (
        "profiles/published/ai_realtime_voice_quick/runtime_plan.json"
    ),
    "execution-profiles/ai_realtime_voice_quick/manifest.sha256": (
        "profiles/published/ai_realtime_voice_quick/manifest.sha256"
    ),
    "execution-profiles/network_comprehensive_quick/profile.json": (
        "profiles/published/network_comprehensive_quick/profile.json"
    ),
    "execution-profiles/network_comprehensive_quick/runtime_plan.json": (
        "profiles/published/network_comprehensive_quick/runtime_plan.json"
    ),
    "execution-profiles/network_comprehensive_quick/manifest.sha256": (
        "profiles/published/network_comprehensive_quick/manifest.sha256"
    ),
}


class CiWorkflowContractTest(unittest.TestCase):
    def test_contract_tests_install_repository_go_toolchain_first(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        contracts = workflow.split("\n  contracts:\n", 1)[1].split(
            "\n  server:\n", 1
        )[0]

        setup_go = contracts.index("- uses: actions/setup-go@v5")
        script_tests = contracts.index(
            "python -m unittest discover -s scripts/tests -v"
        )

        self.assertLess(setup_go, script_tests)
        self.assertIn("go-version-file: server/go.mod", contracts)
        self.assertIn("cache: false", contracts)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class BuildServerCandidateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.repo = self.base / "repo"
        self.server = self.repo / "server"
        self.repo.mkdir()
        self.server.mkdir()
        (self.repo / ".gitignore").write_text(
            "evidence/\nserver/tls/\n",
            encoding="utf-8",
        )
        (self.server / "go.mod").write_text(
            "module example.com/aneb-server\n\ngo 1.25.0\n",
            encoding="utf-8",
        )
        (self.server / "go.sum").write_text("", encoding="utf-8")
        (self.server / "main.go").write_text(
            'package main\n\nimport "fmt"\n\nfunc main() { fmt.Println("aneb") }\n',
            encoding="utf-8",
        )
        for name, relative in ARTIFACT_PATHS.items():
            del name
            path = self.repo / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"fixture:{relative}\n", encoding="utf-8")

        self._run(["git", "init", "--quiet", str(self.repo)])
        self._git("config", "user.email", "aneb-test@example.invalid")
        self._git("config", "user.name", "ANEB Test")
        self._git("add", ".")
        self._git("commit", "--quiet", "-m", "fixture")
        evidence = self.repo / "evidence" / "ignored.txt"
        evidence.parent.mkdir()
        evidence.write_text("allowed ignored evidence\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _run(
        self,
        arguments: list[str],
        *,
        cwd: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            arguments,
            cwd=cwd,
            text=True,
            capture_output=True,
            check=True,
        )

    def _git(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return self._run(["git", "-C", str(self.repo), *arguments])

    def _candidate_command(self, stage: Path) -> list[str]:
        stage.mkdir()
        expected_commit = self._git("rev-parse", "--verify", "HEAD^{commit}").stdout.strip()
        command = [
            sys.executable,
            str(SCRIPT),
            "--repo-root",
            str(self.repo.resolve()),
            "--expected-commit",
            expected_commit,
            "--server-dir",
            str(self.server.resolve()),
            "--output-bin",
            str((stage / "aneb-server-linux").resolve()),
            "--provenance",
            str((stage / "build-provenance.json").resolve()),
            "--buildinfo",
            str((stage / "go-buildinfo.json").resolve()),
            "--artifact-snapshot-root",
            str((stage / "upload-artifacts").resolve()),
        ]
        for name, relative in ARTIFACT_PATHS.items():
            command.extend(
                ["--artifact", f"{name}={(self.repo / relative).resolve()}"]
            )
        return command

    def test_expected_commit_mismatch_publishes_nothing(self) -> None:
        stage = self.base / "commit-mismatch"
        command = self._candidate_command(stage)
        expected_index = command.index("--expected-commit") + 1
        command[expected_index] = "0" * 40

        result = self._invoke_command(command)

        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("ERROR code=repository_head_mismatch\n", result.stderr)
        self.assertEqual([], list(stage.iterdir()))

    def _invoke_candidate(self, stage: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self._candidate_command(stage),
            text=True,
            capture_output=True,
            check=False,
        )

    @staticmethod
    def _invoke_command(command: list[str]) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            command,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_clean_head_build_is_reproducible_and_fully_bound(self) -> None:
        first = self.base / "first"
        second = self.base / "second"

        first_run = self._run(self._candidate_command(first))
        second_run = self._run(self._candidate_command(second))

        self.assertIn("OK schema=aneb-server-build-provenance-v1", first_run.stdout)
        self.assertEqual("", first_run.stderr)
        binary = first / "aneb-server-linux"
        provenance_path = first / "build-provenance.json"
        buildinfo_path = first / "go-buildinfo.json"
        self.assertTrue(binary.is_file())
        self.assertTrue(provenance_path.is_file())
        self.assertTrue(buildinfo_path.is_file())
        artifact_snapshot = first / "upload-artifacts"
        self.assertTrue(artifact_snapshot.is_dir())
        self.assertEqual(sha256(binary), sha256(second / "aneb-server-linux"))
        self.assertEqual(provenance_path.read_bytes(), (second / provenance_path.name).read_bytes())
        self.assertEqual(buildinfo_path.read_bytes(), (second / buildinfo_path.name).read_bytes())

        provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        buildinfo = json.loads(buildinfo_path.read_text(encoding="utf-8"))
        commit = self._git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(SCHEMA, provenance["schema"])
        self.assertEqual(commit, provenance["commit"])
        self.assertEqual(buildinfo["GoVersion"], provenance["GoVersion"])
        self.assertEqual(CANONICAL_FLAGS, provenance["canonical_flags"])
        self.assertEqual(FIXED_ENVIRONMENT, provenance["environment"])
        self.assertEqual(
            {
                "bytes": buildinfo_path.stat().st_size,
                "sha256": sha256(buildinfo_path),
            },
            provenance["go_buildinfo"],
        )
        self.assertEqual(
            {"bytes": binary.stat().st_size, "sha256": sha256(binary)},
            provenance["binary"],
        )
        settings = {item["Key"]: item["Value"] for item in buildinfo["Settings"]}
        self.assertEqual(commit, settings["vcs.revision"])
        self.assertEqual("false", settings["vcs.modified"])
        for key in ("GOOS", "GOARCH", "GOAMD64", "CGO_ENABLED"):
            self.assertEqual(FIXED_ENVIRONMENT[key], settings[key])
        self.assertEqual("off", settings.get("GOFIPS140", "off"))

        module_files = provenance["module_files"]
        for name in ("go.mod", "go.sum"):
            relative = f"server/{name}"
            head_bytes = self._git("show", f"HEAD:{relative}").stdout.encode("utf-8")
            self.assertEqual(
                {
                    "bytes": len(head_bytes),
                    "path": relative,
                    "sha256": hashlib.sha256(head_bytes).hexdigest(),
                },
                module_files[name],
            )
        artifacts = {item["name"]: item for item in provenance["artifacts"]}
        self.assertEqual(set(ARTIFACT_PATHS), set(artifacts))
        for name, relative in ARTIFACT_PATHS.items():
            head_bytes = self._git("show", f"HEAD:{relative}").stdout.encode("utf-8")
            self.assertEqual(relative, artifacts[name]["path"])
            self.assertEqual(len(head_bytes), artifacts[name]["bytes"])
            self.assertEqual(
                hashlib.sha256(head_bytes).hexdigest(),
                artifacts[name]["sha256"],
            )
            self.assertEqual(
                head_bytes,
                artifact_snapshot.joinpath(*name.split("/")).read_bytes(),
            )

    def test_external_gofips_poison_is_overridden_and_recorded(self) -> None:
        stage = self.base / "gofips-poison"
        environment = os.environ.copy()
        environment["GOFIPS140"] = "latest"

        result = subprocess.run(
            self._candidate_command(stage),
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        provenance = json.loads(
            (stage / "build-provenance.json").read_text(encoding="utf-8")
        )
        buildinfo = json.loads(
            (stage / "go-buildinfo.json").read_text(encoding="utf-8")
        )
        settings = {item["Key"]: item["Value"] for item in buildinfo["Settings"]}
        self.assertEqual("off", provenance["environment"]["GOFIPS140"])
        self.assertEqual("off", settings.get("GOFIPS140", "off"))

    def test_module_verification_failure_publishes_nothing(self) -> None:
        stage = self.base / "module-verify-failure"
        command = self._candidate_command(stage)
        real_run = candidate_builder._run

        def fail_module_verify(arguments, **kwargs):
            if len(arguments) >= 3 and arguments[1:3] == ["mod", "verify"]:
                raise candidate_builder.CandidateBuildError("go_module_verify_failed")
            return real_run(arguments, **kwargs)

        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(candidate_builder, "_run", side_effect=fail_module_verify):
            result = candidate_builder.main(command[2:], stdout=stdout, stderr=stderr)

        self.assertEqual(2, result)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual("ERROR code=go_module_verify_failed\n", stderr.getvalue())
        self.assertEqual([], list(stage.iterdir()))

    def test_concurrent_worktree_source_change_still_builds_exact_head(self) -> None:
        baseline_stage = self.base / "source-race-baseline"
        baseline = self._invoke_candidate(baseline_stage)
        self.assertEqual(0, baseline.returncode, baseline.stderr)

        raced_stage = self.base / "source-race-candidate"
        command = self._candidate_command(raced_stage)
        argv = command[2:]
        real_run = subprocess.run
        tracked_source = self.server / "main.go"
        head_source = tracked_source.read_bytes()
        mutation_applied = False

        def run_while_worktree_is_modified(arguments, **kwargs):
            nonlocal mutation_applied
            if (
                not mutation_applied
                and len(arguments) >= 2
                and Path(arguments[0]).name.casefold().startswith("go")
                and arguments[1] == "build"
            ):
                tracked_source.write_text(
                    'package main\n\nimport "fmt"\n\nfunc main() { fmt.Println("raced") }\n',
                    encoding="utf-8",
                )
                mutation_applied = True
                try:
                    return real_run(arguments, **kwargs)
                finally:
                    tracked_source.write_bytes(head_source)
            return real_run(arguments, **kwargs)

        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(
            candidate_builder.subprocess,
            "run",
            side_effect=run_while_worktree_is_modified,
        ):
            result = candidate_builder.main(argv, stdout=stdout, stderr=stderr)

        self.assertTrue(mutation_applied)
        self.assertEqual(0, result, stderr.getvalue())
        self.assertEqual("", stderr.getvalue())
        for name in ("aneb-server-linux", "build-provenance.json", "go-buildinfo.json"):
            self.assertEqual(
                (baseline_stage / name).read_bytes(),
                (raced_stage / name).read_bytes(),
                name,
            )

    def test_concurrent_worktree_artifact_change_still_publishes_exact_head(self) -> None:
        stage = self.base / "artifact-race-candidate"
        command = self._candidate_command(stage)
        argv = command[2:]
        real_run = subprocess.run
        relative = "profiles/s1_chat.json"
        tracked_artifact = self.repo / relative
        worktree_bytes = tracked_artifact.read_bytes()
        mutation_applied = False

        def run_while_artifact_is_modified(arguments, **kwargs):
            nonlocal mutation_applied
            if (
                not mutation_applied
                and len(arguments) >= 2
                and Path(arguments[0]).name.casefold().startswith("go")
                and arguments[1] == "build"
            ):
                tracked_artifact.write_text("not-the-head-artifact\n", encoding="utf-8")
                mutation_applied = True
                try:
                    return real_run(arguments, **kwargs)
                finally:
                    tracked_artifact.write_bytes(worktree_bytes)
            return real_run(arguments, **kwargs)

        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(
            candidate_builder.subprocess,
            "run",
            side_effect=run_while_artifact_is_modified,
        ):
            result = candidate_builder.main(argv, stdout=stdout, stderr=stderr)

        self.assertTrue(mutation_applied)
        self.assertEqual(0, result, stderr.getvalue())
        self.assertEqual("", stderr.getvalue())
        head_bytes = self._git("show", f"HEAD:{relative}").stdout.encode("utf-8")
        self.assertEqual(
            head_bytes,
            (stage / "upload-artifacts" / "root-profiles" / "s1_chat.json").read_bytes(),
        )

    def test_optional_certificate_is_published_from_its_single_validated_snapshot(self) -> None:
        certificate = self.server / "tls" / "ip" / "aneb_ip_cert.pem"
        certificate.parent.mkdir(parents=True)
        validated_bytes = PUBLIC_CERTIFICATE.read_bytes()
        certificate.write_bytes(validated_bytes)
        stage = self.base / "certificate-race"
        command = self._candidate_command(stage)
        command.extend(["--artifact", f"tls/ip-cert.pem={certificate.resolve()}"])
        argv = command[2:]
        real_run = subprocess.run
        mutation_applied = False

        def run_after_certificate_validation(arguments, **kwargs):
            nonlocal mutation_applied
            if not mutation_applied and "clone" in arguments:
                certificate.write_bytes(b"raced-after-public-certificate-validation\n")
                mutation_applied = True
            return real_run(arguments, **kwargs)

        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(
            candidate_builder.subprocess,
            "run",
            side_effect=run_after_certificate_validation,
        ):
            result = candidate_builder.main(argv, stdout=stdout, stderr=stderr)

        self.assertTrue(mutation_applied)
        self.assertEqual(0, result, stderr.getvalue())
        self.assertEqual("", stderr.getvalue())
        published = stage / "upload-artifacts" / "tls" / "ip-cert.pem"
        self.assertEqual(validated_bytes, published.read_bytes())
        provenance = json.loads((stage / "build-provenance.json").read_text(encoding="utf-8"))
        certificate_record = next(
            item for item in provenance["artifacts"] if item["name"] == "tls/ip-cert.pem"
        )
        self.assertEqual(hashlib.sha256(validated_bytes).hexdigest(), certificate_record["sha256"])

    def test_assume_unchanged_index_flag_is_rejected(self) -> None:
        self._git("update-index", "--assume-unchanged", "server/main.go")
        stage = self.base / "assume-unchanged"
        try:
            completed = self._invoke_candidate(stage)
        finally:
            self._git("update-index", "--no-assume-unchanged", "server/main.go")

        self.assertEqual(2, completed.returncode)
        self.assertEqual(
            "ERROR code=repository_index_flags_forbidden\n",
            completed.stderr,
        )
        self.assertEqual([], list(stage.iterdir()))

    def test_skip_worktree_index_flag_is_rejected(self) -> None:
        self._git("update-index", "--skip-worktree", "server/main.go")
        stage = self.base / "skip-worktree"
        try:
            completed = self._invoke_candidate(stage)
        finally:
            self._git("update-index", "--no-skip-worktree", "server/main.go")

        self.assertEqual(2, completed.returncode)
        self.assertEqual(
            "ERROR code=repository_index_flags_forbidden\n",
            completed.stderr,
        )
        self.assertEqual([], list(stage.iterdir()))

    def test_snapshot_build_inputs_must_equal_exact_head_blobs(self) -> None:
        stage = self.base / "snapshot-source-tamper"
        command = self._candidate_command(stage)
        argv = command[2:]
        real_run = subprocess.run
        snapshot_root: Path | None = None
        snapshot_source: Path | None = None
        snapshot_head_bytes: bytes | None = None
        tampered = False

        def run_with_hidden_snapshot_tamper(arguments, **kwargs):
            nonlocal snapshot_root, snapshot_source, snapshot_head_bytes, tampered
            is_go = len(arguments) >= 2 and Path(arguments[0]).name.casefold().startswith("go")
            cwd_value = kwargs.get("cwd")
            cwd = Path(cwd_value).resolve() if cwd_value is not None else None
            if (
                is_go
                and arguments[1] == "list"
                and cwd is not None
                and cwd != self.server.resolve()
                and not tampered
            ):
                completed = real_run(arguments, **kwargs)
                snapshot_root = cwd.parent
                snapshot_source = cwd / "main.go"
                snapshot_head_bytes = snapshot_source.read_bytes()
                snapshot_source.write_text(
                    'package main\n\nimport "fmt"\n\nfunc main() { fmt.Println("hidden") }\n',
                    encoding="utf-8",
                )
                real_run(
                    [
                        "git",
                        "-C",
                        str(snapshot_root),
                        "update-index",
                        "--assume-unchanged",
                        "server/main.go",
                    ],
                    check=True,
                    capture_output=True,
                )
                tampered = True
                return completed
            if is_go and arguments[1] == "build" and tampered:
                try:
                    return real_run(arguments, **kwargs)
                finally:
                    assert snapshot_root is not None
                    assert snapshot_source is not None
                    assert snapshot_head_bytes is not None
                    snapshot_source.write_bytes(snapshot_head_bytes)
                    real_run(
                        [
                            "git",
                            "-C",
                            str(snapshot_root),
                            "update-index",
                            "--no-assume-unchanged",
                            "server/main.go",
                        ],
                        check=True,
                        capture_output=True,
                    )
            return real_run(arguments, **kwargs)

        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(
            candidate_builder.subprocess,
            "run",
            side_effect=run_with_hidden_snapshot_tamper,
        ):
            result = candidate_builder.main(argv, stdout=stdout, stderr=stderr)

        self.assertTrue(tampered)
        self.assertEqual(2, result)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual(
            "ERROR code=repository_snapshot_input_mismatch\n",
            stderr.getvalue(),
        )
        self.assertEqual([], list(stage.iterdir()))

    def test_dirty_tracked_file_fails_without_leaking_its_name(self) -> None:
        sensitive_name = "customer-secret-main.go"
        tracked = self.server / "main.go"
        tracked.write_text(
            tracked.read_text(encoding="utf-8") + f"// {sensitive_name}\n",
            encoding="utf-8",
        )
        stage = self.base / "dirty-tracked"

        completed = self._invoke_candidate(stage)

        self.assertEqual(2, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertEqual(
            "ERROR code=repository_dirty_before_build\n",
            completed.stderr,
        )
        self.assertNotIn(sensitive_name, completed.stderr)
        self.assertEqual([], list(stage.iterdir()))

    def test_untracked_file_fails_without_leaking_its_name(self) -> None:
        sensitive_name = "customer-access-token-backup.txt"
        (self.repo / sensitive_name).write_text("not a real secret\n", encoding="utf-8")
        stage = self.base / "dirty-untracked"

        completed = self._invoke_candidate(stage)

        self.assertEqual(2, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertEqual(
            "ERROR code=repository_dirty_before_build\n",
            completed.stderr,
        )
        self.assertNotIn(sensitive_name, completed.stderr)
        self.assertEqual([], list(stage.iterdir()))

    def test_gitignored_go_source_cannot_influence_the_candidate(self) -> None:
        gitignore = self.repo / ".gitignore"
        gitignore.write_text(
            gitignore.read_text(encoding="utf-8") + "server/ignored.go\n",
            encoding="utf-8",
        )
        self._git("add", ".gitignore")
        self._git("commit", "--quiet", "-m", "ignore generated source")
        ignored_source = self.server / "ignored.go"
        ignored_source.write_text(
            "package main\n\nfunc ignoredBuildInput() {}\n",
            encoding="utf-8",
        )
        stage = self.base / "ignored-source"

        completed = self._invoke_candidate(stage)

        self.assertEqual(2, completed.returncode)
        self.assertEqual("ERROR code=source_not_tracked\n", completed.stderr)
        self.assertNotIn(ignored_source.name, completed.stderr)
        self.assertEqual([], list(stage.iterdir()))

    def test_mandatory_artifact_set_and_paths_are_exact(self) -> None:
        missing_stage = self.base / "missing-artifact"
        missing = self._candidate_command(missing_stage)
        marker = next(
            index
            for index, value in enumerate(missing)
            if value.startswith(
                "execution-profiles/ai_realtime_voice_quick/manifest.sha256="
            )
        )
        del missing[marker - 1:marker + 1]
        missing_result = self._invoke_command(missing)
        self.assertEqual(2, missing_result.returncode)
        self.assertEqual(
            "ERROR code=artifact_set_incomplete\n",
            missing_result.stderr,
        )
        self.assertEqual([], list(missing_stage.iterdir()))

        mismatch_stage = self.base / "mismatched-artifact"
        mismatch = self._candidate_command(mismatch_stage)
        artifact_index = next(
            index
            for index, value in enumerate(mismatch)
            if value.startswith("aneb-server.service=")
        )
        mismatch[artifact_index] = (
            f"aneb-server.service={(self.server / 'go.mod').resolve()}"
        )
        mismatch_result = self._invoke_command(mismatch)
        self.assertEqual(2, mismatch_result.returncode)
        self.assertEqual(
            "ERROR code=artifact_path_invalid\n",
            mismatch_result.stderr,
        )
        self.assertEqual([], list(mismatch_stage.iterdir()))

    def test_private_key_artifacts_are_never_accepted(self) -> None:
        stage = self.base / "private-key-name"
        command = self._candidate_command(stage)
        command.extend(
            [
                "--artifact",
                f"tls/ip-key.pem={(self.server / 'tls' / 'ip' / 'key.pem').resolve()}",
            ]
        )

        result = self._invoke_command(command)

        self.assertEqual(2, result.returncode)
        self.assertEqual("ERROR code=artifact_not_allowed\n", result.stderr)
        self.assertEqual([], list(stage.iterdir()))

    def test_optional_certificate_rejects_private_key_material(self) -> None:
        certificate = self.server / "tls" / "ip" / "aneb_ip_cert.pem"
        certificate.parent.mkdir(parents=True)
        private_key_label = "PRIVATE" + " KEY"
        certificate.write_text(
            f"-----BEGIN {private_key_label}-----\nnot-real\n"
            f"-----END {private_key_label}-----\n",
            encoding="ascii",
        )
        stage = self.base / "private-certificate"
        command = self._candidate_command(stage)
        command.extend(["--artifact", f"tls/ip-cert.pem={certificate.resolve()}"])

        result = self._invoke_command(command)

        self.assertEqual(2, result.returncode)
        self.assertEqual("ERROR code=public_certificate_invalid\n", result.stderr)
        self.assertEqual([], list(stage.iterdir()))

    def test_outputs_inside_the_repository_are_rejected(self) -> None:
        stage = self.repo / "evidence" / "candidate"

        result = self._invoke_candidate(stage)

        self.assertEqual(2, result.returncode)
        self.assertEqual("ERROR code=output_path_invalid\n", result.stderr)
        self.assertEqual([], list(stage.iterdir()))

    def test_repository_dirty_after_build_publishes_nothing_and_leaks_no_name(self) -> None:
        stage = self.base / "dirty-after"
        command = self._candidate_command(stage)
        argv = command[2:]
        real_run = subprocess.run
        sensitive_name = "post-build-customer-token.txt"
        mutated = False

        def run_then_dirty(arguments, **kwargs):
            nonlocal mutated
            completed = real_run(arguments, **kwargs)
            if (
                not mutated
                and len(arguments) >= 2
                and Path(arguments[0]).name.casefold().startswith("go")
                and arguments[1] == "build"
                and completed.returncode == 0
            ):
                (self.repo / sensitive_name).write_text(
                    "not a real secret\n",
                    encoding="utf-8",
                )
                mutated = True
            return completed

        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(
            candidate_builder.subprocess,
            "run",
            side_effect=run_then_dirty,
        ):
            result = candidate_builder.main(argv, stdout=stdout, stderr=stderr)

        self.assertTrue(mutated)
        self.assertEqual(2, result)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual(
            "ERROR code=repository_dirty_after_build\n",
            stderr.getvalue(),
        )
        self.assertNotIn(sensitive_name, stderr.getvalue())
        self.assertEqual([], list(stage.iterdir()))

    def test_head_change_after_build_publishes_nothing(self) -> None:
        stage = self.base / "head-changed"
        command = self._candidate_command(stage)
        argv = command[2:]
        real_run = subprocess.run
        mutated = False

        def run_then_commit(arguments, **kwargs):
            nonlocal mutated
            completed = real_run(arguments, **kwargs)
            if (
                not mutated
                and len(arguments) >= 2
                and Path(arguments[0]).name.casefold().startswith("go")
                and arguments[1] == "build"
                and completed.returncode == 0
            ):
                tracked = self.server / "main.go"
                tracked.write_text(
                    tracked.read_text(encoding="utf-8") + "// after build\n",
                    encoding="utf-8",
                )
                real_run(
                    ["git", "-C", str(self.repo), "add", "server/main.go"],
                    check=True,
                    capture_output=True,
                )
                real_run(
                    [
                        "git",
                        "-C",
                        str(self.repo),
                        "commit",
                        "--quiet",
                        "-m",
                        "concurrent commit",
                    ],
                    check=True,
                    capture_output=True,
                )
                mutated = True
            return completed

        stdout = io.StringIO()
        stderr = io.StringIO()
        with mock.patch.object(
            candidate_builder.subprocess,
            "run",
            side_effect=run_then_commit,
        ):
            result = candidate_builder.main(argv, stdout=stdout, stderr=stderr)

        self.assertTrue(mutated)
        self.assertEqual(2, result)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual(
            "ERROR code=repository_head_changed_after_build\n",
            stderr.getvalue(),
        )
        self.assertEqual([], list(stage.iterdir()))

    def test_buildinfo_contract_mismatch_never_publishes_candidate(self) -> None:
        mismatches = {
            "vcs.revision": "0" * 40,
            "vcs.modified": "true",
            "GOOS": "windows",
            "GOARCH": "arm64",
            "GOAMD64": "v3",
            "CGO_ENABLED": "1",
        }
        for setting_key, bad_value in mismatches.items():
            with self.subTest(setting=setting_key):
                stage = self.base / f"bad-{setting_key.replace('.', '-')}"
                command = self._candidate_command(stage)
                argv = command[2:]
                real_run = subprocess.run

                def run_with_bad_buildinfo(arguments, **kwargs):
                    completed = real_run(arguments, **kwargs)
                    if (
                        len(arguments) >= 4
                        and Path(arguments[0]).name.casefold().startswith("go")
                        and arguments[1:4] == ["version", "-m", "-json"]
                        and completed.returncode == 0
                    ):
                        payload = json.loads(completed.stdout)
                        for item in payload["Settings"]:
                            if item["Key"] == setting_key:
                                item["Value"] = bad_value
                                break
                        else:
                            self.fail(f"missing setting {setting_key}")
                        return subprocess.CompletedProcess(
                            completed.args,
                            completed.returncode,
                            json.dumps(payload).encode("utf-8"),
                            completed.stderr,
                        )
                    return completed

                stdout = io.StringIO()
                stderr = io.StringIO()
                with mock.patch.object(
                    candidate_builder.subprocess,
                    "run",
                    side_effect=run_with_bad_buildinfo,
                ):
                    result = candidate_builder.main(
                        argv,
                        stdout=stdout,
                        stderr=stderr,
                    )

                self.assertEqual(2, result)
                self.assertEqual("", stdout.getvalue())
                self.assertEqual(
                    "ERROR code=go_buildinfo_contract_mismatch\n",
                    stderr.getvalue(),
                )
                self.assertEqual([], list(stage.iterdir()))


if __name__ == "__main__":
    unittest.main()
