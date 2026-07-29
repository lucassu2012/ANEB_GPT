from __future__ import annotations

import os
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "deploy_server.ps1"


class DeployServerSafetyContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SCRIPT.read_text(encoding="utf-8")
        cls.remote = cls.source.split("$remoteScript = @'", 1)[1].split("\n'@", 1)[0]

    @staticmethod
    def run_bash_fixture(
        bash: str,
        script: str,
        *arguments: str,
    ) -> subprocess.CompletedProcess[str]:
        # Git Bash on Windows truncates sufficiently long `bash -c` payloads.
        # Execute long extracted functions from a private temporary script so
        # the production cleanup body is tested byte-for-byte without relying
        # on the host command-line length limit.
        with tempfile.TemporaryDirectory() as temporary:
            script_path = Path(temporary) / "fixture.sh"
            script_path.write_text(script, encoding="utf-8", newline="\n")
            return subprocess.run(
                [bash, str(script_path), *arguments],
                text=True,
                capture_output=True,
                check=False,
            )

    @classmethod
    def canonicalize_iptables_save(cls, snapshot: str, expected_tool: str) -> str:
        function = cls.remote.split("canonicalize_iptables_save() {", 1)[1].split(
            "\n}", 1
        )[0]
        program = function.split("python3 -c '", 1)[1].split("\n'", 1)[0]
        completed = subprocess.run(
            [sys.executable, "-c", program, expected_tool],
            input=snapshot,
            text=True,
            capture_output=True,
            check=True,
        )
        return completed.stdout

    @classmethod
    def firewall_fixture_fingerprints(
        cls, iptables: str, ip6tables: str, nft_ruleset: str
    ) -> dict[str, str]:
        snapshot = (
            "--- iptables-save ---\n"
            + cls.canonicalize_iptables_save(iptables, "iptables-save")
            + "--- ip6tables-save ---\n"
            + cls.canonicalize_iptables_save(ip6tables, "ip6tables-save")
            + "--- nft-list-ruleset ---\n"
            + nft_ruleset
        )
        function = cls.remote.split("firewall_snapshot_fingerprints() {", 1)[1].split(
            "\n}", 1
        )[0]
        program = function.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "firewall.snapshot"
            path.write_text(snapshot, encoding="utf-8", newline="")
            completed = subprocess.run(
                [sys.executable, "-c", program, str(path)],
                text=True,
                capture_output=True,
                check=True,
            )
        fields = completed.stdout.split()
        if len(fields) != 5 or any(
            not re.fullmatch(r"[0-9a-f]{64}", field) for field in fields
        ):
            raise AssertionError(f"invalid firewall fingerprints: {fields!r}")
        return dict(zip(("full", "v4", "v6", "nft", "docker"), fields, strict=True))

    @classmethod
    def run_receipt_validator(
        cls,
        body: dict[str, object],
        *,
        expected_h3: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        function = cls.remote.split("validate_receipt() {", 1)[1].split("\n}\n\n", 1)[0]
        program = function.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            body_path = root / "serverinfo.json"
            token_manifest_path = root / "token-manifest.sha256"
            realtime_manifest_path = root / "realtime-manifest.sha256"
            network_manifest_path = root / "network-manifest.sha256"
            token_qualification_manifest_path = root / "token-qualification-manifest.sha256"
            realtime_qualification_manifest_path = root / "realtime-qualification-manifest.sha256"
            network_qualification_manifest_path = root / "network-qualification-manifest.sha256"
            receipt_sha_path = root / "receipt.sha256"
            body_path.write_text(json.dumps(body), encoding="utf-8")
            token_manifest_path.write_text(
                "1" * 64 + "  profile.json\n" + "2" * 64 + "  runtime_plan.json\n",
                encoding="utf-8",
            )
            realtime_manifest_path.write_text(
                "3" * 64 + "  profile.json\n" + "4" * 64 + "  runtime_plan.json\n",
                encoding="utf-8",
            )
            network_manifest_path.write_text(
                "5" * 64 + "  profile.json\n" + "6" * 64 + "  runtime_plan.json\n",
                encoding="utf-8",
            )
            token_qualification_manifest_path.write_text(
                "7" * 64 + "  profile.json\n" + "8" * 64 + "  runtime_plan.json\n",
                encoding="utf-8",
            )
            realtime_qualification_manifest_path.write_text(
                "9" * 64 + "  profile.json\n" + "a" * 64 + "  runtime_plan.json\n",
                encoding="utf-8",
            )
            network_qualification_manifest_path.write_text(
                "b" * 64 + "  profile.json\n" + "c" * 64 + "  runtime_plan.json\n",
                encoding="utf-8",
            )
            return subprocess.run(
                [
                    sys.executable,
                    "-c",
                    program,
                    str(body_path),
                    str(token_manifest_path),
                    str(realtime_manifest_path),
                    str(network_manifest_path),
                    str(token_qualification_manifest_path),
                    str(realtime_qualification_manifest_path),
                    str(network_qualification_manifest_path),
                    "true" if expected_h3 else "false",
                    str(receipt_sha_path),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

    @staticmethod
    def valid_receipt_body(*, h3_enabled: bool = False) -> dict[str, object]:
        return {
            "version": "aneb-server/0.8.3",
            "h3_enabled": h3_enabled,
            "execution_capabilities": {
                "contract_id": "aneb-server-capability-receipt",
                "contract_version": "1.0.0",
                "primitives": [
                    {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"},
                    {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
                    {
                        "primitive_id": "realtime_sim",
                        "wire_contract_id": "aneb-realtime-session-v1",
                    },
                    {"primitive_id": "token_sim", "wire_contract_id": "aneb-token-task-v1"},
                    {"primitive_id": "udp_echo", "wire_contract_id": "aneb-udp-echo-v2"},
                    {"primitive_id": "upload", "wire_contract_id": "aneb-upload-v1"},
                ],
                "validated_profiles": [
                    {
                        "profile_id": "ai_realtime_voice_quick",
                        "profile_version": "1.1.1",
                        "profile_sha256": "sha256:" + "3" * 64,
                    },
                    {
                        "profile_id": "ai_realtime_voice_repeatability_qualification",
                        "profile_version": "1.0.0",
                        "profile_sha256": "sha256:" + "9" * 64,
                    },
                    {
                        "profile_id": "network_comprehensive_quick",
                        "profile_version": "1.2.0",
                        "profile_sha256": "sha256:" + "5" * 64,
                    },
                    {
                        "profile_id": "network_comprehensive_repeatability_qualification",
                        "profile_version": "1.0.0",
                        "profile_sha256": "sha256:" + "b" * 64,
                    },
                    {
                        "profile_id": "token_multimodal_quick",
                        "profile_version": "1.2.1",
                        "profile_sha256": "sha256:" + "1" * 64,
                    },
                    {
                        "profile_id": "token_multimodal_repeatability_qualification",
                        "profile_version": "1.0.0",
                        "profile_sha256": "sha256:" + "7" * 64,
                    },
                ],
            },
        }

    def test_candidate_contains_all_runtime_execution_profile_bundles(self) -> None:
        for relative in (
            "execution-profiles/token_multimodal_quick/profile.json",
            "execution-profiles/token_multimodal_quick/runtime_plan.json",
            "execution-profiles/token_multimodal_quick/manifest.sha256",
            "execution-profiles/ai_realtime_voice_quick/profile.json",
            "execution-profiles/ai_realtime_voice_quick/runtime_plan.json",
            "execution-profiles/ai_realtime_voice_quick/manifest.sha256",
            "execution-profiles/network_comprehensive_quick/profile.json",
            "execution-profiles/network_comprehensive_quick/runtime_plan.json",
            "execution-profiles/network_comprehensive_quick/manifest.sha256",
            "execution-profiles/token_multimodal_repeatability_qualification/profile.json",
            "execution-profiles/token_multimodal_repeatability_qualification/runtime_plan.json",
            "execution-profiles/token_multimodal_repeatability_qualification/manifest.sha256",
            "execution-profiles/ai_realtime_voice_repeatability_qualification/profile.json",
            "execution-profiles/ai_realtime_voice_repeatability_qualification/runtime_plan.json",
            "execution-profiles/ai_realtime_voice_repeatability_qualification/manifest.sha256",
            "execution-profiles/network_comprehensive_repeatability_qualification/profile.json",
            "execution-profiles/network_comprehensive_repeatability_qualification/runtime_plan.json",
            "execution-profiles/network_comprehensive_repeatability_qualification/manifest.sha256",
        ):
            self.assertIn(relative, self.source)

    def test_upgrade_and_rollback_contract_is_exactly_082_to_083(self) -> None:
        self.assertIn(
            "validate_server_identity 'aneb-server/0.8.2' pre-switch",
            self.remote,
        )
        self.assertIn(
            "validate_server_identity 'aneb-server/0.8.3' live",
            self.remote,
        )
        self.assertIn(
            "validate_server_identity 'aneb-server/0.8.2' rollback",
            self.remote,
        )

    def test_local_gates_precede_the_first_remote_connection(self) -> None:
        main = self.source.split("# No ssh/scp call is made before this function returns successfully.", 1)[1]
        gate = main.index("Invoke-LocalSafetyGates")
        first_connection = main.index("& ssh @SshOpts")
        self.assertLess(gate, first_connection)
        self.assertIn("verify_spec_catalog.py", self.source)
        self.assertIn("test -count=1 ./...", self.source)
        for artifact in ("profile.json", "runtime_plan.json", "manifest.sha256"):
            self.assertIn(artifact, self.source)

    def test_local_gates_are_environment_clean_and_commit_bound(self) -> None:
        gate = self.source.split("function Invoke-LocalSafetyGates", 1)[1].split(
            "function Copy-ToRemote", 1
        )[0]
        self.assertIn("$script:ExpectedSourceCommit = Get-RepositoryHead", gate)
        self.assertIn("before local safety gates", gate)
        self.assertIn("after local safety gates", gate)
        self.assertIn("$env:GOENV = 'off'", gate)
        self.assertIn("$env:GOFLAGS = ''", gate)
        self.assertIn("$env:GOWORK = 'off'", gate)
        self.assertIn("$env:GOTOOLCHAIN = 'local'", gate)
        self.assertIn("$env:GOEXPERIMENT = ''", gate)
        self.assertIn("$env:GOFIPS140 = 'off'", gate)
        self.assertIn("'GOFIPS140': 'off'", self.remote)
        self.assertIn("settings.get('GOFIPS140', 'off') != 'off'", self.remote)
        self.assertIn("$previousGoEnvironment", gate)
        self.assertIn("--expected-commit', $script:ExpectedSourceCommit", self.source)
        post_gate = self.source.index("after local safety gates")
        helper = self.source.index("& $pythonCommand.Source @candidateArguments")
        self.assertLess(post_gate, helper)

    def test_helper_snapshot_is_the_only_uploaded_artifact_source(self) -> None:
        self.assertIn("--artifact-snapshot-root", self.source)
        helper = self.source.index("& $pythonCommand.Source @candidateArguments")
        local_success = self.source.index("LOCAL_VALIDATION_ONLY_OK")
        first_connection = self.source.index("& ssh @SshOpts", helper)
        self.assertLess(helper, local_success)
        self.assertLess(local_success, first_connection)
        upload = self.source.split("Write-Host '== [4/5] upload candidate into staging =='", 1)[1]
        upload = upload.split("$remoteScript = @'", 1)[0]
        self.assertIn("$CandidateUploadArtifacts", upload)
        for original in (
            "$RootProfileFiles",
            "$TokenQuickProfile",
            "$TokenQuickRuntimePlan",
            "$TokenQuickManifest",
            "$Unit",
            "$IpCert",
        ):
            self.assertNotIn(f"-LocalPath {original}", upload)
        self.assertIn("$CandidateUploadArtifacts.GetEnumerator()", self.source)

    def test_runtime_plan_is_uploaded_with_the_manifest_bound_bundle(self) -> None:
        self.assertIn(
            "'execution-profiles/token_multimodal_quick/runtime_plan.json' = $TokenQuickRuntimePlan",
            self.source,
        )
        self.assertIn("$CandidateUploadArtifacts.GetEnumerator() | Sort-Object Key", self.source)
        self.assertIn('RemotePath "$RemoteStage/$logicalPath"', self.source)
        self.assertNotIn("sha256sum", self.remote)
        self.assertIn("{'profile.json', 'runtime_plan.json'}", self.remote)

    def test_candidate_receipt_is_verified_before_live_mutation(self) -> None:
        staged_receipt = self.remote.index("validate_receipt \\")
        stage_ok = self.remote.index('echo "STAGING_OK')
        live_boundary = self.remote.index("LIVE_TOUCHED=1")
        self.assertLess(staged_receipt, stage_ok)
        self.assertLess(stage_ok, live_boundary)
        self.assertIn('-addr "127.0.0.1:$STAGE_PORT"', self.remote)
        self.assertIn('-udp-echo-addr "127.0.0.1:$STAGE_PORT"', self.remote)
        self.assertIn("socket.SOCK_STREAM", self.remote)
        self.assertIn("socket.SOCK_DGRAM", self.remote)
        self.assertIn("matching loopback TCP/UDP port", self.remote)
        self.assertIn("setsid runuser -u aneb", self.remote)
        self.assertIn('local stage_pid="$STAGE_PID"', self.remote)
        self.assertIn('kill -TERM -- "-$stage_pid"', self.remote)
        self.assertIn("STAGED_SERVER_STOP_FAILED", self.remote)

    def test_pre_switch_requires_exact_082_identity_and_freezes_shared_host(self) -> None:
        freeze_call = self.remote.index("freeze_live_baseline\n")
        live_boundary = self.remote.index("LIVE_TOUCHED=1", freeze_call)
        self.assertLess(freeze_call, live_boundary)
        freeze_function = self.remote.index("freeze_live_baseline()")
        self.assertLess(freeze_function, freeze_call)
        self.assertIn("validate_server_identity 'aneb-server/0.8.2' pre-switch", self.remote)
        freeze_body = self.remote.split("freeze_live_baseline() {", 1)[1].split("\n}", 1)[0]
        identity = freeze_body.index("validate_server_identity 'aneb-server/0.8.2' pre-switch")
        legacy = freeze_body.index("validate_legacy_surface pre-switch-0.8 aneb1")
        binary = freeze_body.index("BASE_BINARY_SHA=")
        self.assertLess(identity, legacy)
        self.assertLess(legacy, binary)
        self.assertIn("body.get('version') != expected", self.remote)
        self.assertIn("body.get('h3_enabled') is not True", self.remote)
        self.assertIn("x-aneb-server", self.remote)
        self.assertIn("BASE_BINARY_SHA=", self.remote)
        self.assertIn("firewall_snapshot_fingerprints", self.remote)
        self.assertIn("eth0_qdisc_fingerprint", self.remote)
        self.assertIn("firewall_fingerprint", self.remote)
        self.assertIn("iptables-save", self.remote)
        self.assertIn("ip6tables-save", self.remote)
        self.assertIn("nft --stateless list ruleset", self.remote)
        self.assertIn("tc qdisc show dev eth0", self.remote)
        self.assertIn('firewall_snapshot > "$sample1"', freeze_body)
        self.assertIn('firewall_snapshot > "$sample2"', freeze_body)
        self.assertIn('cmp -s "$sample1" "$sample2"', freeze_body)
        self.assertIn("PRE_SWITCH_BASELINE_UNSTABLE surface=firewall samples=2", freeze_body)
        self.assertIn("pre_switch_firewall_stability=verified samples=2", freeze_body)
        self.assertIn('BASE_FIREWALL_SHA="$sample1_full"', freeze_body)
        self.assertIn('BASE_IPTABLES_V4_FIREWALL_SHA="$sample1_v4"', freeze_body)
        self.assertIn('BASE_IPTABLES_V6_FIREWALL_SHA="$sample1_v6"', freeze_body)
        self.assertIn('BASE_NFT_FIREWALL_SHA="$sample1_nft"', freeze_body)
        self.assertIn('BASE_DOCKER_FIREWALL_SHA="$sample1_docker"', freeze_body)
        self.assertIn('verify_docker_sha="$(docker_firewall_fingerprint)"', freeze_body)
        baseline_assertion = self.remote.split("assert_shared_host_baseline() {", 1)[1].split(
            "\n}", 1
        )[0]
        self.assertEqual(1, baseline_assertion.count('firewall_snapshot > "$current_snapshot"'))
        self.assertEqual(
            1,
            baseline_assertion.count(
                'firewall_snapshot_fingerprints "$current_snapshot"'
            ),
        )
        for surface in ("iptables_v4", "iptables_v6", "nft_ruleset"):
            self.assertIn(f"surface={surface} expected=", baseline_assertion)

    def test_firewall_fingerprint_ignores_only_iptables_save_run_timestamps(self) -> None:
        first = """# Generated by iptables-save v1.8.7 (nf_tables) on Sat Jul 18 18:00:00 2026
# Warning: iptables-legacy tables present, use iptables-legacy-save to see them
*filter
:INPUT ACCEPT [0:0]
-A INPUT -p tcp --dport 8443 -m comment --comment \"ANEB ingress\" -j ACCEPT
COMMIT
# Completed on Sat Jul 18 18:00:00 2026
"""
        nat_first = first.replace("*filter", "*nat").replace(
            "--dport 8443", "--dport 9443"
        )
        first += nat_first
        second = first.replace("18:00:00", "18:03:17")

        canonical_first = self.canonicalize_iptables_save(first, "iptables-save")
        canonical_second = self.canonicalize_iptables_save(second, "iptables-save")

        self.assertEqual(canonical_first, canonical_second)
        self.assertEqual(
            2,
            canonical_first.count(
                "# Generated by iptables-save v1.8.7 (nf_tables)\n"
            ),
        )
        self.assertNotIn(" on Sat Jul 18", canonical_first)
        self.assertNotIn("# Completed on", canonical_first)
        self.assertIn("# Warning: iptables-legacy tables present", canonical_first)
        self.assertIn('--comment "ANEB ingress"', canonical_first)
        first_v6 = first.replace(
            "Generated by iptables-save", "Generated by ip6tables-save"
        )
        second_v6 = second.replace(
            "Generated by iptables-save", "Generated by ip6tables-save"
        )
        self.assertEqual(
            self.firewall_fixture_fingerprints(first, first_v6, "table inet guard {}\n"),
            self.firewall_fixture_fingerprints(
                second, second_v6, "table inet guard {}\n"
            ),
        )

    def test_iptables_canonicalizer_rejects_malformed_or_ambiguous_streams(self) -> None:
        timestamp = "Sat Jul 18 18:00:00 2026"
        valid = (
            f"# Generated by iptables-save v1.8.7 (nf_tables) on {timestamp}\n"
            "*filter\n"
            ":INPUT ACCEPT [0:0]\n"
            "COMMIT\n"
            f"# Completed on {timestamp}\n"
        )
        valid_multi = valid + valid.replace("*filter", "*nat")
        self.assertEqual(
            2,
            self.canonicalize_iptables_save(
                valid_multi,
                "iptables-save",
            ).count("# Generated by iptables-save v1.8.7 (nf_tables)\n"),
        )
        invalid = {
            "empty": "",
            "blank": "\n",
            "missing-generated": "*filter\nCOMMIT\n" + f"# Completed on {timestamp}\n",
            "missing-completed": valid.rsplit("# Completed on", 1)[0],
            "wrong-family": valid.replace(
                "Generated by iptables-save", "Generated by ip6tables-save", 1
            ),
            "malformed-generated-time": valid.replace(timestamp, "NOT-A-TIME", 1),
            "malformed-completed-time": valid.replace(
                f"# Completed on {timestamp}", "# Completed on NOT-A-TIME"
            ),
            "impossible-date": valid.replace("Sat Jul 18", "Sat Feb 31"),
            "wrong-weekday": valid.replace("Sat Jul 18", "Mon Jul 18"),
            "extra-generated": valid.replace(
                "*filter\n",
                f"# Generated by iptables-save v1.8.7 (nf_tables) on {timestamp}\n*filter\n",
            ),
            "extra-malformed-generated": valid.replace(
                "*filter\n", "# Generated byBROKEN\n*filter\n"
            ),
            "extra-completed": valid.replace(
                "COMMIT\n", f"COMMIT\n# Completed on {timestamp}\n"
            ),
            "extra-malformed-completed": valid.replace(
                "COMMIT\n", "COMMIT\n# Completed onBROKEN\n"
            ),
            "completed-not-tail": valid + "# Warning: trailing diagnostic\n",
            "gap-between-blocks": (
                valid
                + "# unexpected gap\n"
                + valid.replace("*filter", "*nat")
            ),
        }
        for label, snapshot in invalid.items():
            with self.subTest(label=label):
                with self.assertRaises(subprocess.CalledProcessError):
                    self.canonicalize_iptables_save(snapshot, "iptables-save")

        with self.assertRaises(subprocess.CalledProcessError):
            self.canonicalize_iptables_save(valid, "ip6tables-save")
        with self.assertRaises(subprocess.CalledProcessError):
            self.canonicalize_iptables_save(valid, "unsupported-save")

    @staticmethod
    def find_gnu_bash() -> str | None:
        candidates: list[str] = []
        if os.name == "nt":
            for variable in ("ProgramFiles", "ProgramFiles(x86)"):
                root = os.environ.get(variable)
                if root:
                    candidates.append(str(Path(root) / "Git" / "bin" / "bash.exe"))
        discovered = shutil.which("bash")
        if discovered:
            candidates.append(discovered)
        for candidate in dict.fromkeys(candidates):
            if not Path(candidate).is_file():
                continue
            try:
                completed = subprocess.run(
                    [candidate, "--version"],
                    text=True,
                    capture_output=True,
                    timeout=5,
                    check=False,
                )
            except (OSError, subprocess.TimeoutExpired):
                continue
            if completed.returncode == 0 and "GNU bash" in completed.stdout:
                return candidate
        return None

    def test_firewall_snapshot_propagates_upstream_pipeline_failure(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        canonicalizer = "canonicalize_iptables_save() {" + self.remote.split(
            "canonicalize_iptables_save() {", 1
        )[1].split("\n}", 1)[0] + "\n}"
        clean_capture = "capture_clean_firewall_command() {" + self.remote.split(
            "capture_clean_firewall_command() {", 1
        )[1].split("\n}", 1)[0] + "\n}"
        snapshot = "firewall_snapshot() {" + self.remote.split(
            "firewall_snapshot() {", 1
        )[1].split("\n}", 1)[0] + "\n}"
        script = f"""set -Eeuo pipefail
export LC_ALL=C
{canonicalizer}
{clean_capture}
{snapshot}
STAGE="$(mktemp -d)"
iptables-save() {{
    printf '%s\\n' \\
        '# Generated by iptables-save v1.8.7 (nf_tables) on Sat Jul 18 18:00:00 2026' \\
        '*filter' ':INPUT ACCEPT [0:0]' 'COMMIT' \\
        '# Completed on Sat Jul 18 18:00:00 2026'
    return 23
}}
ip6tables-save() {{ return 0; }}
nft() {{ return 0; }}
if output="$(firewall_snapshot 2>&1)"; then
    echo 'firewall_snapshot unexpectedly accepted upstream failure' >&2
    exit 90
fi
[[ "$output" == *'iptables-save snapshot failed'* ]]
"""
        completed = subprocess.run(
            [bash, "-c", script],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(
            0,
            completed.returncode,
            msg=f"stdout={completed.stdout!r}\nstderr={completed.stderr!r}",
        )

    def test_firewall_snapshot_rejects_successful_command_stderr(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        canonicalizer = "canonicalize_iptables_save() {" + self.remote.split(
            "canonicalize_iptables_save() {", 1
        )[1].split("\n}", 1)[0] + "\n}"
        clean_capture = "capture_clean_firewall_command() {" + self.remote.split(
            "capture_clean_firewall_command() {", 1
        )[1].split("\n}", 1)[0] + "\n}"
        snapshot = "firewall_snapshot() {" + self.remote.split(
            "firewall_snapshot() {", 1
        )[1].split("\n}", 1)[0] + "\n}"
        script = f"""set -Eeuo pipefail
export LC_ALL=C
{canonicalizer}
{clean_capture}
{snapshot}
STAGE="$(mktemp -d)"
iptables-save() {{
    printf '%s\\n' \\
        '# Generated by iptables-save v1.8.7 (nf_tables) on Sat Jul 18 18:00:00 2026' \\
        '*filter' ':INPUT ACCEPT [0:0]' 'COMMIT' \\
        '# Completed on Sat Jul 18 18:00:00 2026'
    printf '%s\\n' 'warning despite rc=0' >&2
    return 0
}}
ip6tables-save() {{ return 0; }}
nft() {{ return 0; }}
if output="$(firewall_snapshot 2>&1)"; then
    echo 'firewall_snapshot unexpectedly accepted stderr' >&2
    exit 90
fi
[[ "$output" == *'iptables-save snapshot emitted stderr'* ]]
[[ "$output" != *'warning despite rc=0'* ]]
"""
        completed = subprocess.run(
            [bash, "-c", script],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(
            0,
            completed.returncode,
            msg=f"stdout={completed.stdout!r}\nstderr={completed.stderr!r}",
        )

    def test_firewall_fingerprint_still_detects_rule_semantic_changes(self) -> None:
        iptables = """# Generated by iptables-save v1.8.7 (nf_tables) on Sat Jul 18 18:00:00 2026
*filter
:INPUT ACCEPT [0:0]
-N ANEB-IN
-N DOCKER
-A ANEB-IN -p tcp --dport 8443 -m comment --comment "ANEB ingress" -j ACCEPT
-A DOCKER -p tcp --dport 9000 -j ACCEPT
-A INPUT -j ANEB-IN
COMMIT
# Completed on Sat Jul 18 18:00:00 2026
"""
        ip6tables = """# Generated by ip6tables-save v1.8.7 (nf_tables) on Sat Jul 18 18:00:00 2026
*filter
:INPUT ACCEPT [0:0]
-A INPUT -p tcp --dport 8443 -j ACCEPT
COMMIT
# Completed on Sat Jul 18 18:00:00 2026
"""
        nft_ruleset = """table inet guard {
    chain ingress { type filter hook input priority filter; policy accept; }
}
"""
        baseline = self.firewall_fixture_fingerprints(
            iptables, ip6tables, nft_ruleset
        )
        mutations = {
            "rule": (
                iptables.replace("--dport 8443", "--dport 8444"),
                ip6tables,
                nft_ruleset,
                "v4",
            ),
            "policy": (
                iptables.replace(":INPUT ACCEPT", ":INPUT DROP"),
                ip6tables,
                nft_ruleset,
                "v4",
            ),
            "chain": (
                iptables.replace("ANEB-IN", "ANEB-CHANGED"),
                ip6tables,
                nft_ruleset,
                "v4",
            ),
            "rule-comment": (
                iptables.replace("ANEB ingress", "ANEB changed"),
                ip6tables,
                nft_ruleset,
                "v4",
            ),
            "tool-version": (
                iptables.replace("v1.8.7", "v1.8.8"),
                ip6tables,
                nft_ruleset,
                "v4",
            ),
            "tool-backend": (
                iptables.replace("(nf_tables)", "(legacy)"),
                ip6tables,
                nft_ruleset,
                "v4",
            ),
            "ipv6-rule": (
                iptables,
                ip6tables.replace("--dport 8443", "--dport 8444"),
                nft_ruleset,
                "v6",
            ),
            "docker-rule": (
                iptables.replace("--dport 9000", "--dport 9001"),
                ip6tables,
                nft_ruleset,
                "docker",
            ),
            "nft": (
                iptables,
                ip6tables,
                nft_ruleset.replace("policy accept", "policy drop"),
                "nft",
            ),
        }
        for surface, (changed_v4, changed_v6, changed_nft, component) in mutations.items():
            with self.subTest(surface=surface):
                changed = self.firewall_fixture_fingerprints(
                    changed_v4, changed_v6, changed_nft
                )
                self.assertNotEqual(
                    baseline["full"],
                    changed["full"],
                )
                self.assertNotEqual(baseline[component], changed[component])

        snapshot = self.remote.split("firewall_snapshot() {", 1)[1].split("\n}", 1)[0]
        compact_snapshot = " ".join(snapshot.replace("\\", "").split())
        self.assertIn(
            "capture_clean_firewall_command iptables-save iptables-save | "
            "canonicalize_iptables_save iptables-save",
            compact_snapshot,
        )
        self.assertIn(
            "capture_clean_firewall_command ip6tables-save ip6tables-save | "
            "canonicalize_iptables_save ip6tables-save",
            compact_snapshot,
        )
        self.assertIn("iptables-save snapshot failed", snapshot)
        self.assertIn("ip6tables-save snapshot failed", snapshot)
        self.assertNotIn("iptables-save -c", snapshot)
        self.assertNotIn("ip6tables-save -c", snapshot)
        self.assertNotIn("--counters", snapshot)
        self.assertIn(
            "capture_clean_firewall_command nft-list-ruleset nft --stateless list ruleset",
            compact_snapshot,
        )
        self.assertIn("nft ruleset snapshot failed", snapshot)

    def test_docker_firewall_filter_does_not_mask_capture_failure(self) -> None:
        snapshot = self.remote.split("docker_firewall_snapshot() {", 1)[1].split(
            "\n}", 1
        )[0]
        compact_snapshot = " ".join(snapshot.replace("\\", "").split())
        self.assertIn(
            "capture_clean_firewall_command docker-iptables-save iptables-save | "
            "awk '/(^:DOCKER|DOCKER)/ { print }'",
            compact_snapshot,
        )
        self.assertIn(
            "capture_clean_firewall_command docker-ip6tables-save ip6tables-save | "
            "awk '/(^:DOCKER|DOCKER)/ { print }'",
            compact_snapshot,
        )
        self.assertNotIn("grep", snapshot)
        self.assertNotIn("|| true", snapshot)
        self.assertIn("iptables-save Docker snapshot failed", snapshot)
        self.assertIn("ip6tables-save Docker snapshot failed", snapshot)
        self.assertIn("command -v awk >/dev/null", self.remote)
        self.assertIn("command -v cmp >/dev/null", self.remote)

    def test_restart_or_smoke_failure_has_complete_rollback_surface(self) -> None:
        self.assertIn("if [[ $rc -ne 0 && $LIVE_TOUCHED -eq 1 ]]", self.remote)
        expected = {
            "live-binary": "/opt/aneb/bin/aneb-server",
            "root-profiles": "/opt/aneb/profiles",
            "quick-bundle": "/opt/aneb/execution-profiles/token_multimodal_quick",
            "realtime-quick-bundle": "/opt/aneb/execution-profiles/ai_realtime_voice_quick",
            "network-quick-bundle": "/opt/aneb/execution-profiles/network_comprehensive_quick",
            "token-qualification-bundle": "/opt/aneb/execution-profiles/token_multimodal_repeatability_qualification",
            "realtime-qualification-bundle": "/opt/aneb/execution-profiles/ai_realtime_voice_repeatability_qualification",
            "network-qualification-bundle": "/opt/aneb/execution-profiles/network_comprehensive_repeatability_qualification",
            "service-unit": "/etc/systemd/system/aneb-server.service",
        }
        for label, path in expected.items():
            self.assertIn(f"snapshot_item {label} {path}", self.remote)
            self.assertIn(f"restore_item {label} {path}", self.remote)
        for label, path in (
            ("token-qualification-bundle", "/opt/aneb/execution-profiles/token_multimodal_repeatability_qualification"),
            ("realtime-qualification-bundle", "/opt/aneb/execution-profiles/ai_realtime_voice_repeatability_qualification"),
            ("network-qualification-bundle", "/opt/aneb/execution-profiles/network_comprehensive_repeatability_qualification"),
        ):
            variable = "BASE_" + label.replace("-", "_").upper() + "_SHA"
            self.assertIn(f'{variable}="$(path_fingerprint {path})"', self.remote)
            self.assertIn(f'"$(path_fingerprint {path})" == "${variable}"', self.remote)
        self.assertIn("systemctl restart aneb-server", self.remote)
        self.assertIn("ROLLBACK_OK", self.remote)
        self.assertIn("restore_item live-binary /opt/aneb/bin/aneb-server || rollback_rc=1", self.remote)
        self.assertIn("validate_server_identity 'aneb-server/0.8.2' rollback", self.remote)
        self.assertIn("validate_legacy_surface rollback-0.8 aneb1", self.remote)
        self.assertIn("assert_restored_aneb_baseline", self.remote)
        self.assertIn("assert_shared_host_baseline rollback", self.remote)
        self.assertIn("ROLLBACK_FAILED verification=identity+legacy_surface+fingerprints exit=97", self.remote)
        self.assertIn("final_rc=97", self.remote)
        self.assertIn("result_status='rollback_failed'", self.remote)

    def test_partial_ip_certificate_pair_is_rejected_locally(self) -> None:
        self.assertIn("$ipCertPresent -xor $ipKeyPresent", self.source)
        local_gate = self.source.index("Invoke-LocalSafetyGates")
        first_connection = self.source.index("& ssh @SshOpts", local_gate)
        self.assertLess(local_gate, first_connection)

    def test_ip_certificate_replacement_is_explicit_and_sha_pinned(self) -> None:
        self.assertIn("EnableIpCertificateReplacement", self.source)
        self.assertIn("ExpectedIpCertificateSha256", self.source)
        self.assertIn("ExpectedIpPrivateKeySha256", self.source)
        self.assertIn("replacement was not explicitly enabled", self.source)
        self.assertNotIn("Get-FileHash -LiteralPath $IpCert -Algorithm SHA256", self.source)
        self.assertIn(
            "Get-FileHash -LiteralPath $CandidateUploadArtifacts['tls/ip-cert.pem'] -Algorithm SHA256",
            self.source,
        )
        self.assertIn("Get-FileHash -LiteralPath $IpKey -Algorithm SHA256", self.source)
        self.assertIn('file_sha256 "$STAGE/tls/ip-cert.pem"', self.remote)
        self.assertIn('file_sha256 "$STAGE/tls/ip-key.pem"', self.remote)
        self.assertIn("validate_ip_certificate_bundle", self.remote)
        self.assertIn("openssl x509 -in \"$cert\" -noout -checkip 120.79.148.0", self.remote)
        self.assertIn("not values['notBefore'] <= now <= values['notAfter']", self.remote)
        self.assertIn("openssl x509 -in \"$cert\" -pubkey -noout", self.remote)
        self.assertIn("openssl pkey -in \"$key\" -passin pass: -pubout -outform DER", self.remote)
        self.assertIn('"$cert_public_sha" == "$key_public_sha"', self.remote)
        certificate_validation = self.remote.index("validate_ip_certificate_bundle\n")
        live_boundary = self.remote.index("LIVE_TOUCHED=1", certificate_validation)
        self.assertLess(certificate_validation, live_boundary)

    def test_real_deployment_has_no_retired_shared_status_dependency(self) -> None:
        self.assertNotIn("LeaseId", self.source)
        self.assertNotIn("SHARED_TEST_STATUS", self.source)
        self.assertNotIn("update_shared_test_status.py", self.source)
        self.assertNotIn("Assert-SharedDeploymentLease", self.source)
        self.assertNotIn("assert-lease", self.source)

        acquire = self.remote.index("acquire_deploy_lock\n")
        live_boundary = self.remote.index("LIVE_TOUCHED=1")
        self.assertLess(acquire, live_boundary)
        self.assertIn('DEPLOY_LOCK_PATH="/run/lock/aneb-deploy.lock"', self.remote)
        self.assertIn("flock -n 9", self.remote)

    def test_live_smoke_preserves_weak_network_route_isolation(self) -> None:
        self.assertIn("/api/v1/impairments", self.remote)
        self.assertIn("weak-capacity-latency-v1", self.remote)
        self.assertIn("weak-recovery-v1", self.remote)
        self.assertIn("recovery_code\" -eq 503", self.remote)
        self.assertIn("other_code\" -eq 200", self.remote)
        self.assertIn("normal_code\" -eq 200", self.remote)
        self.assertIn("recovered_code\" -eq 200", self.remote)
        live_receipt = self.remote.rindex("validate_receipt \\")
        weak_smoke = self.remote.index("weak_network_smoke=")
        live_complete = self.remote.rindex("LIVE_TOUCHED=0")
        self.assertLess(live_receipt, weak_smoke)
        self.assertLess(weak_smoke, live_complete)
        fingerprint_check = self.remote.rindex("assert_shared_host_baseline live")
        self.assertLess(weak_smoke, fingerprint_check)
        self.assertLess(fingerprint_check, live_complete)

    def test_live_smoke_preserves_s3_multimodal_download_contract(self) -> None:
        self.assertIn("expected_phase_types", self.remote)
        self.assertIn("for index in (4, 8)", self.remote)
        self.assertIn("phase.get('bytes') != 12582912", self.remote)
        self.assertIn("phase.get('chunk_kb') != 256", self.remote)
        self.assertIn("validate_legacy_surface live-0.8 aneb2", self.remote)
        self.assertIn("download?bytes=1048576", self.remote)

    def test_udp_smoke_is_explicitly_versioned_across_upgrade_boundary(self) -> None:
        function = self.remote.split("validate_legacy_surface() {", 1)[1].split(
            "\n}\n\nrestore_item()", 1
        )[0]
        self.assertIn('local udp_wire="$2"', function)
        self.assertIn('[[ "$udp_wire" == "aneb1" || "$udp_wire" == "aneb2" ]]', function)
        self.assertIn("python3 - \"$udp_wire\" <<'PY'", function)
        self.assertIn("if wire == 'aneb1':", function)
        self.assertIn("packet = b'ANEB1' + struct.pack('>Iq', 7, time.monotonic_ns())", function)
        self.assertIn("elif wire == 'aneb2':", function)
        self.assertIn("packet = b'ANEB2' + uuid.UUID", function)
        self.assertIn("validate_legacy_surface pre-switch-0.8 aneb1", self.remote)
        self.assertIn("validate_legacy_surface rollback-0.8 aneb1", self.remote)
        self.assertIn("validate_legacy_surface live-0.8 aneb2", self.remote)

    def test_staged_uploads_are_digest_bound_and_build_identity_is_reverified(self) -> None:
        for name in (
            "build-provenance.json",
            "go-buildinfo.json",
            "artifact-manifest.sha256",
        ):
            self.assertIn(name, self.source)
            self.assertIn(name, self.remote)
        self.assertIn("validate_uploaded_artifacts", self.remote)
        self.assertIn("validate_build_evidence", self.remote)
        validation = self.remote.index("validate_uploaded_artifacts\n")
        identity = self.remote.index("validate_build_evidence\n", validation)
        candidate = self.remote.index("setsid runuser -u aneb", identity)
        self.assertLess(validation, identity)
        self.assertLess(identity, candidate)
        self.assertIn("linux", self.remote)
        self.assertIn("amd64", self.remote)
        self.assertNotRegex(
            self.source,
            r"(?m)^\s*Add-ArtifactDigestEntry .*IpKey",
        )
        self.assertIn('file_sha256 "$STAGE/tls/ip-key.pem"', self.remote)

    def test_remote_build_validation_uses_uploaded_evidence_without_go_tooling(self) -> None:
        validation = self.remote.split("validate_build_evidence() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        self.assertIn('EXPECTED_SOURCE_COMMIT="${6:?source commit required}"', self.remote)
        self.assertIn('"$EXPECTED_SOURCE_COMMIT"', validation)
        self.assertIn("commit != expected_commit", validation)
        self.assertIn(
            "provenance commit does not match expected source commit",
            validation,
        )
        self.assertIn("'$($script:ExpectedSourceCommit)'", self.source)
        self.assertIn('"$STAGE/build-provenance.json"', validation)
        self.assertIn('"$STAGE/go-buildinfo.json"', validation)
        self.assertIn('"$STAGE/artifact-manifest.sha256"', validation)
        self.assertIn("manifest/provenance binary mismatch", validation)
        self.assertIn("provenance Go build info record mismatch", validation)
        self.assertIn("manifest/provenance Go build info mismatch", validation)
        self.assertIn(
            'STAGED_BINARY_SHA="$(file_sha256 "$STAGE/aneb-server-linux")"',
            self.remote,
        )
        self.assertIn("validate_build_evidence\n", self.remote)
        self.assertIn("validate_receipt \\", self.remote)
        self.assertIn(
            'STAGED_RECEIPT_SHA="$(cat "$STAGE/staged-receipt.sha256")"',
            self.remote,
        )
        self.assertNotIn("go version -m", validation)

    def test_uploaded_build_evidence_validator_rejects_each_binding_mutation(self) -> None:
        validation = self.remote.split("validate_build_evidence() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        program = validation.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        commit = "a" * 40
        artifact_sources = {
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
            "execution-profiles/token_multimodal_repeatability_qualification/profile.json": (
                "profiles/published/token_multimodal_repeatability_qualification/profile.json"
            ),
            "execution-profiles/token_multimodal_repeatability_qualification/runtime_plan.json": (
                "profiles/published/token_multimodal_repeatability_qualification/runtime_plan.json"
            ),
            "execution-profiles/token_multimodal_repeatability_qualification/manifest.sha256": (
                "profiles/published/token_multimodal_repeatability_qualification/manifest.sha256"
            ),
            "execution-profiles/ai_realtime_voice_repeatability_qualification/profile.json": (
                "profiles/published/ai_realtime_voice_repeatability_qualification/profile.json"
            ),
            "execution-profiles/ai_realtime_voice_repeatability_qualification/runtime_plan.json": (
                "profiles/published/ai_realtime_voice_repeatability_qualification/runtime_plan.json"
            ),
            "execution-profiles/ai_realtime_voice_repeatability_qualification/manifest.sha256": (
                "profiles/published/ai_realtime_voice_repeatability_qualification/manifest.sha256"
            ),
            "execution-profiles/network_comprehensive_repeatability_qualification/profile.json": (
                "profiles/published/network_comprehensive_repeatability_qualification/profile.json"
            ),
            "execution-profiles/network_comprehensive_repeatability_qualification/runtime_plan.json": (
                "profiles/published/network_comprehensive_repeatability_qualification/runtime_plan.json"
            ),
            "execution-profiles/network_comprehensive_repeatability_qualification/manifest.sha256": (
                "profiles/published/network_comprehensive_repeatability_qualification/manifest.sha256"
            ),
        }

        def canonical_json(path: Path, value: object) -> None:
            path.write_text(
                json.dumps(
                    value,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                    allow_nan=False,
                )
                + "\n",
                encoding="utf-8",
                newline="\n",
            )

        def record(path: Path) -> dict[str, object]:
            payload = path.read_bytes()
            return {"bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}

        def write_manifest(path: Path, entries: dict[str, str]) -> None:
            path.write_text(
                "".join(f"{entries[name]}  {name}\n" for name in sorted(entries)),
                encoding="ascii",
                newline="\n",
            )

        def create_fixture(stage: Path) -> dict[str, object]:
            binary_path = stage / "aneb-server-linux"
            binary_path.write_bytes(b"\x7fELF-aneb-candidate\n")
            for index, name in enumerate(sorted(artifact_sources)):
                path = stage.joinpath(*name.split("/"))
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(f"artifact-{index}:{name}\n".encode())

            buildinfo_path = stage / "go-buildinfo.json"
            buildinfo: dict[str, object] = {
                "GoVersion": "go1.25.0",
                "Settings": [
                    {"Key": "vcs", "Value": "git"},
                    {"Key": "vcs.revision", "Value": commit},
                    {"Key": "vcs.modified", "Value": "false"},
                    {"Key": "GOOS", "Value": "linux"},
                    {"Key": "GOARCH", "Value": "amd64"},
                    {"Key": "GOAMD64", "Value": "v1"},
                    {"Key": "CGO_ENABLED", "Value": "0"},
                    {"Key": "-trimpath", "Value": "true"},
                    {"Key": "GOFIPS140", "Value": "off"},
                ],
            }
            canonical_json(buildinfo_path, buildinfo)
            provenance_path = stage / "build-provenance.json"
            provenance: dict[str, object] = {
                "schema": "aneb-server-build-provenance-v1",
                "commit": commit,
                "GoVersion": "go1.25.0",
                "canonical_flags": [
                    "-trimpath",
                    "-buildvcs=true",
                    "-mod=readonly",
                    "-pgo=off",
                ],
                "environment": {
                    "GOOS": "linux",
                    "GOARCH": "amd64",
                    "GOAMD64": "v1",
                    "CGO_ENABLED": "0",
                    "GOENV": "off",
                    "GOFLAGS": "",
                    "GOEXPERIMENT": "",
                    "GOFIPS140": "off",
                    "GOWORK": "off",
                    "GOTOOLCHAIN": "local",
                },
                "binary": record(binary_path),
                "module_files": {
                    "go.mod": {"path": "server/go.mod", "bytes": 1, "sha256": "1" * 64},
                    "go.sum": {"path": "server/go.sum", "bytes": 1, "sha256": "2" * 64},
                },
                "artifacts": [
                    {
                        "name": name,
                        "path": artifact_sources[name],
                        **record(stage.joinpath(*name.split("/"))),
                    }
                    for name in sorted(artifact_sources)
                ],
                "go_buildinfo": record(buildinfo_path),
            }
            canonical_json(provenance_path, provenance)
            manifest_entries = {
                "aneb-server-linux": str(record(binary_path)["sha256"]),
                "build-provenance.json": str(record(provenance_path)["sha256"]),
                "go-buildinfo.json": str(record(buildinfo_path)["sha256"]),
                **{
                    name: str(record(stage.joinpath(*name.split("/")))["sha256"])
                    for name in artifact_sources
                },
            }
            manifest_path = stage / "artifact-manifest.sha256"
            write_manifest(manifest_path, manifest_entries)
            return {
                "binary_path": binary_path,
                "buildinfo": buildinfo,
                "buildinfo_path": buildinfo_path,
                "provenance": provenance,
                "provenance_path": provenance_path,
                "manifest_entries": manifest_entries,
                "manifest_path": manifest_path,
            }

        def run_validator(
            stage: Path, fixture: dict[str, object], expected_commit: str
        ) -> subprocess.CompletedProcess[str]:
            return subprocess.run(
                [
                    sys.executable,
                    "-c",
                    program,
                    str(stage),
                    "0",
                    expected_commit,
                    str(fixture["provenance_path"]),
                    str(fixture["buildinfo_path"]),
                    str(fixture["manifest_path"]),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

        mutations = (
            "binary_byte",
            "buildinfo",
            "provenance_binary_record",
            "provenance_buildinfo_record",
            "expected_commit",
            "manifest_binary_entry",
            "manifest_buildinfo_entry",
        )
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            valid_stage = base / "valid"
            valid_stage.mkdir()
            valid_fixture = create_fixture(valid_stage)
            accepted = run_validator(valid_stage, valid_fixture, commit)
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            self.assertIn("build_evidence=verified", accepted.stdout)

            for mutation in mutations:
                with self.subTest(mutation=mutation):
                    stage = base / mutation
                    stage.mkdir()
                    fixture = create_fixture(stage)
                    expected_commit = commit
                    if mutation == "binary_byte":
                        binary_path = fixture["binary_path"]
                        assert isinstance(binary_path, Path)
                        binary_path.write_bytes(binary_path.read_bytes() + b"!")
                    elif mutation == "buildinfo":
                        buildinfo = fixture["buildinfo"]
                        buildinfo_path = fixture["buildinfo_path"]
                        assert isinstance(buildinfo, dict) and isinstance(buildinfo_path, Path)
                        buildinfo["GoVersion"] = "go1.25.1"
                        canonical_json(buildinfo_path, buildinfo)
                    elif mutation in {
                        "provenance_binary_record",
                        "provenance_buildinfo_record",
                    }:
                        provenance = fixture["provenance"]
                        provenance_path = fixture["provenance_path"]
                        assert isinstance(provenance, dict) and isinstance(provenance_path, Path)
                        key = (
                            "binary"
                            if mutation == "provenance_binary_record"
                            else "go_buildinfo"
                        )
                        value = provenance[key]
                        assert isinstance(value, dict)
                        value["sha256"] = "f" * 64
                        canonical_json(provenance_path, provenance)
                    elif mutation == "expected_commit":
                        expected_commit = "b" * 40
                    else:
                        entries = fixture["manifest_entries"]
                        manifest_path = fixture["manifest_path"]
                        assert isinstance(entries, dict) and isinstance(manifest_path, Path)
                        key = (
                            "aneb-server-linux"
                            if mutation == "manifest_binary_entry"
                            else "go-buildinfo.json"
                        )
                        entries[key] = "f" * 64
                        write_manifest(manifest_path, entries)

                    rejected = run_validator(stage, fixture, expected_commit)
                    self.assertNotEqual(
                        0,
                        rejected.returncode,
                        msg=f"mutation unexpectedly accepted: {mutation}",
                    )

    def test_remote_preflight_does_not_require_go(self) -> None:
        preflight = self.remote.split('test -d "$STAGE"', 1)[1].split(
            "validate_uploaded_artifacts", 1
        )[0]
        for runtime_dependency in (
            "/usr/bin/python3",
            "/usr/bin/curl",
            "runuser",
            "setsid",
            "awk",
            "cmp",
        ):
            self.assertIn(runtime_dependency, preflight)
        self.assertNotRegex(preflight, r"(?m)^\s*command -v go(?:\s|$)")

    def test_receipt_rejects_duplicate_primitives_extra_structure_and_wrong_h3(self) -> None:
        valid = self.valid_receipt_body()
        accepted = self.run_receipt_validator(valid)
        self.assertEqual(
            0,
            accepted.returncode,
            msg=f"stdout={accepted.stdout!r}\nstderr={accepted.stderr!r}",
        )

        mutations: dict[str, dict[str, object]] = {}
        duplicate = json.loads(json.dumps(valid))
        duplicate["execution_capabilities"]["primitives"].append(  # type: ignore[index]
            dict(duplicate["execution_capabilities"]["primitives"][0])  # type: ignore[index]
        )
        mutations["duplicate primitive"] = duplicate

        extra_receipt = json.loads(json.dumps(valid))
        extra_receipt["execution_capabilities"]["unexpected"] = {}  # type: ignore[index]
        mutations["extra receipt member"] = extra_receipt

        extra_primitive = json.loads(json.dumps(valid))
        extra_primitive["execution_capabilities"]["primitives"][0]["unexpected"] = 1  # type: ignore[index]
        mutations["extra primitive member"] = extra_primitive

        extra_profile = json.loads(json.dumps(valid))
        extra_profile["execution_capabilities"]["validated_profiles"][0]["unexpected"] = 1  # type: ignore[index]
        mutations["extra profile member"] = extra_profile

        missing_qualification = json.loads(json.dumps(valid))
        missing_qualification["execution_capabilities"]["validated_profiles"].pop(1)  # type: ignore[index]
        mutations["missing qualification profile"] = missing_qualification

        old_candidate_version = json.loads(json.dumps(valid))
        old_candidate_version["version"] = "aneb-server/0.8.2"
        mutations["old candidate version"] = old_candidate_version

        wrong_h3 = self.valid_receipt_body(h3_enabled=True)
        mutations["staged h3 enabled"] = wrong_h3

        for label, body in mutations.items():
            with self.subTest(label=label):
                rejected = self.run_receipt_validator(body)
                self.assertNotEqual(
                    0,
                    rejected.returncode,
                    msg=f"mutation accepted: {label}",
                )

    def test_receipt_canonical_sha_is_stable_and_live_must_match_stage(self) -> None:
        body = self.valid_receipt_body()
        receipt = body["execution_capabilities"]
        expected = hashlib.sha256(
            json.dumps(
                receipt,
                sort_keys=True,
                separators=(",", ":"),
                ensure_ascii=True,
            ).encode("ascii")
        ).hexdigest()
        function = self.remote.split("validate_receipt() {", 1)[1].split("\n}\n\n", 1)[0]
        self.assertIn("sort_keys=True", function)
        self.assertIn("separators=(',', ':')", function)
        self.assertIn("receipt_sha_path", function)
        self.assertRegex(expected, r"^[0-9a-f]{64}$")
        self.assertIn('STAGED_RECEIPT_SHA="$(cat "$STAGE/staged-receipt.sha256")"', self.remote)
        self.assertIn('LIVE_RECEIPT_SHA="$(cat "$STAGE/live-receipt.sha256")"', self.remote)
        self.assertIn('[[ "$LIVE_RECEIPT_SHA" == "$STAGED_RECEIPT_SHA" ]]', self.remote)

    def test_evidence_is_atomic_root_only_bounded_and_precedes_live_mutation(self) -> None:
        self.assertIn("/var/lib/aneb-deploy-evidence", self.remote)
        self.assertIn("persist_stage_evidence()", self.remote)
        function = self.remote.split("persist_stage_evidence() {", 1)[1].split("\n}\n\n", 1)[0]
        for evidence in (
            "build-provenance.json",
            "go-buildinfo.json",
            "staged-serverinfo.json",
            "staged-serverinfo.headers",
            "candidate.log",
            "summary.json",
            "artifact-manifest.sha256",
            "COMPLETE",
        ):
            self.assertIn(evidence, function)
        self.assertIn("root.mkdir(mode=0o700", function)
        self.assertIn("os.chmod(root, 0o700)", function)
        self.assertIn("os.chmod(path, 0o600)", function)
        self.assertIn("os.replace", function)
        self.assertIn("items[10:]", function)
        self.assertNotIn("def atomic_update", function)
        self.assertIn("record_pattern", function)
        self.assertIn("orphan_pattern", function)
        complete = function.index("COMPLETE")
        publish = function.rindex("os.replace(temporary, evidence)")
        self.assertLess(complete, publish)
        stage_validation = self.remote.index("persist_stage_evidence staged_validated")
        live_boundary = self.remote.index("LIVE_TOUCHED=1", stage_validation)
        self.assertLess(stage_validation, live_boundary)
        self.assertNotIn("ip-key.pem", function)
        self.assertNotIn("PRIVATE KEY", function)

    def test_terminal_evidence_is_a_success_hard_gate_with_raw_identity_evidence(self) -> None:
        function = self.remote.split("persist_stage_evidence() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        for evidence in (
            "pre-switch-serverinfo.json",
            "pre-switch-serverinfo.headers",
            "staged-serverinfo.json",
            "staged-serverinfo.headers",
            "live-serverinfo.json",
            "live-serverinfo.headers",
            "pre-switch-fingerprints.txt",
            "live-fingerprints.txt",
            "live-artifact-manifest.sha256",
        ):
            self.assertIn(evidence, function)

        self.assertIn("commit_terminal_evidence() {", self.remote)
        success = self.remote.rindex("DEPLOY_RESULT='success'")
        commit = self.remote.index("commit_terminal_evidence success", success)
        disarm = self.remote.index("LIVE_TOUCHED=0", commit)
        deploy_ok = self.remote.index('DEPLOY_SUCCESS_MESSAGE="DEPLOY_OK', disarm)
        self.assertLess(commit, disarm)
        self.assertLess(disarm, deploy_ok)
        failure = self.remote[commit:disarm]
        self.assertIn("FINAL_EVIDENCE_COMMIT_FAILED", failure)
        self.assertIn("exit 98", failure)

        cleanup = self.remote.split("\ncleanup() {", 1)[1].split(
            "trap cleanup EXIT", 1
        )[0]
        self.assertIn("FINAL_EVIDENCE_COMMITTED", cleanup)
        self.assertNotIn("WARNING deploy_evidence_persist_failed", cleanup)
        self.assertIn('echo "$DEPLOY_SUCCESS_MESSAGE"', cleanup)
        self.assertNotIn('echo "DEPLOY_OK', self.remote)

    def test_terminal_evidence_failure_keeps_rollback_armed_and_suppresses_ok(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        function = "commit_terminal_evidence() {" + self.remote.split(
            "commit_terminal_evidence() {", 1
        )[1].split("\n}\n\n", 1)[0] + "\n}"
        script = f"""set -Eeuo pipefail
{function}
FINAL_EVIDENCE_COMMITTED=0
LIVE_TOUCHED=1
persist_stage_evidence() {{ return 73; }}
verify_terminal_evidence() {{ return 0; }}
DEPLOY_RESULT=success
if ! commit_terminal_evidence success; then
    printf 'FINAL_EVIDENCE_COMMIT_FAILED rollback_armed=%s\n' "$LIVE_TOUCHED" >&2
    exit 98
fi
LIVE_TOUCHED=0
printf 'DEPLOY_OK\n'
"""
        completed = subprocess.run(
            [bash, "-c", script],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(98, completed.returncode)
        self.assertIn("rollback_armed=1", completed.stderr)
        self.assertNotIn("DEPLOY_OK", completed.stdout)

    @unittest.skipIf(os.name == "nt", "directory fsync evidence test requires Linux")
    def test_evidence_retention_removes_only_stale_owned_orphans(self) -> None:
        function = self.remote.split("persist_stage_evidence() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        program = function.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        program = program.replace(
            "expected_stage = Path('/tmp') / f'aneb-deploy-{deploy_id}'",
            "expected_stage = Path(stage_arg)",
        ).replace(
            "expected_root = Path('/var/lib/aneb-deploy-evidence')",
            "expected_root = Path(root_arg)",
        )
        deploy_id = "20260718123456-" + "a" * 32
        other_id = "20260718123457-" + "b" * 32
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            stage = base / f"aneb-deploy-{deploy_id}"
            root = base / "evidence"
            evidence = root / f"aneb-deploy-{deploy_id}"
            stage.mkdir()
            root.mkdir()
            for name, value in {
                "build-provenance.json": "{}\n",
                "go-buildinfo.json": "{}\n",
                "staged-serverinfo.json": '{"version":"aneb-server/0.8.3"}\n',
                "staged-serverinfo.headers": "HTTP/1.1 200 OK\r\n\r\n",
                "candidate.log": "candidate ready\n",
                "artifact-manifest.sha256": "0" * 64 + "  aneb-server-linux\n",
            }.items():
                (stage / name).write_text(value, encoding="utf-8")

            common = [
                str(stage),
                str(root),
                str(evidence),
                deploy_id,
            ]
            staged = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    program,
                    *common,
                    "staged_validated",
                    "1" * 64,
                    "2" * 64,
                    "",
                    "",
                    "0",
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, staged.returncode, staged.stderr)

            stale = root / f".aneb-deploy-{other_id}.tmp-stale"
            fresh = root / f".aneb-deploy-{other_id}.tmp-fresh"
            unrelated = root / ".unowned.tmp-stale"
            stale.mkdir()
            fresh.mkdir()
            unrelated.mkdir()
            records = evidence / "records"
            records.mkdir()
            stale_record = records / ".record-000001-failed.tmp-stale"
            stale_record.mkdir()
            old = 0
            os.utime(stale, (old, old))
            os.utime(stale_record, (old, old))
            symlink = root / f".aneb-deploy-{other_id}.tmp-link"
            symlink.symlink_to(unrelated, target_is_directory=True)

            terminal = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    program,
                    *common,
                    "failed",
                    "1" * 64,
                    "2" * 64,
                    "",
                    "",
                    "0",
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, terminal.returncode, terminal.stderr)
            self.assertFalse(stale.exists())
            self.assertFalse(stale_record.exists())
            self.assertTrue(fresh.exists())
            self.assertTrue(unrelated.exists())
            self.assertTrue(symlink.is_symlink())

    def test_forward_and_rollback_replacement_never_delete_live_before_candidate(self) -> None:
        self.assertIn("atomic_replace_candidate() {", self.remote)
        atomic = self.remote.split("atomic_replace_candidate() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        self.assertIn("RENAME_EXCHANGE", atomic)
        self.assertIn("os.replace(candidate, target)", atomic)

        restore = self.remote.split("restore_item() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        self.assertNotIn('rm -rf -- "$target"', restore)
        copy = restore.index('cp -a -- "$BACKUP/$label" "$candidate"')
        replace = restore.index('atomic_replace_candidate "$candidate" "$target"')
        self.assertLess(copy, replace)

        live = self.remote[self.remote.index("LIVE_TOUCHED=1") :]
        self.assertNotIn("rm -rf -- /opt/aneb/profiles", live)
        self.assertNotIn(
            "rm -rf -- /opt/aneb/execution-profiles/token_multimodal_quick",
            live,
        )
        self.assertNotIn(
            "rm -rf -- /opt/aneb/execution-profiles/ai_realtime_voice_quick",
            live,
        )
        self.assertNotIn(
            "rm -rf -- /opt/aneb/execution-profiles/token_multimodal_repeatability_qualification",
            live,
        )
        self.assertNotIn(
            "rm -rf -- /opt/aneb/execution-profiles/ai_realtime_voice_repeatability_qualification",
            live,
        )
        self.assertNotIn(
            "rm -rf -- /opt/aneb/execution-profiles/network_comprehensive_repeatability_qualification",
            live,
        )

    def test_atomic_exchange_failure_keeps_existing_target_unchanged(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        platform = subprocess.run(
            [bash, "-lc", "uname -s"], text=True, capture_output=True, check=False
        )
        if platform.returncode != 0 or platform.stdout.strip() != "Linux":
            self.skipTest("renameat2 executable counterexample requires Linux")
        function = "atomic_replace_candidate() {" + self.remote.split(
            "atomic_replace_candidate() {", 1
        )[1].split("\n}\n\n", 1)[0] + "\n}"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target"
            target.write_bytes(b"known-live")
            missing = root / "missing-candidate"
            completed = subprocess.run(
                [
                    bash,
                    "-c",
                    function
                    + '\nif atomic_replace_candidate "$1" "$2"; then exit 90; fi',
                    "--",
                    str(missing),
                    str(target),
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(b"known-live", target.read_bytes())

    def test_restore_copy_failure_never_deletes_or_exchanges_live_target(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        function = "restore_item() {" + self.remote.split(
            "restore_item() {", 1
        )[1].split("\n}\n\n", 1)[0] + "\n}"
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            backup = base / "backup"
            backup.mkdir()
            (backup / "live-binary.present").write_text("", encoding="ascii")
            (backup / "live-binary").write_text("rollback bytes", encoding="ascii")
            trace = base / "trace.txt"
            script = f"""set -Eeuo pipefail
{function}
BACKUP="$1"
TRACE="$2"
DEPLOY_ID=20260718123456-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
rm() {{ printf 'rm %s\n' "$*" >> "$TRACE"; return 0; }}
mkdir() {{ return 0; }}
cp() {{ printf 'copy_failed\n' >> "$TRACE"; return 74; }}
normalized_path_fingerprint() {{ printf '%064d\n' 0; }}
atomic_replace_candidate() {{ printf 'atomic_called\n' >> "$TRACE"; return 0; }}
if restore_item live-binary /opt/aneb/bin/aneb-server; then
    exit 90
fi
"""
            completed = subprocess.run(
                [bash, "-c", script, "--", str(backup), str(trace)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            events = trace.read_text(encoding="utf-8")
            self.assertIn("copy_failed", events)
            self.assertNotIn("atomic_called", events)
            self.assertNotIn("rm -rf -- /opt/aneb/bin/aneb-server\n", events)

    def test_evidence_failure_removes_staged_key_without_touching_live(self) -> None:
        persist = self.remote.index("if persist_stage_evidence staged_validated")
        live_boundary = self.remote.index("LIVE_TOUCHED=1", persist)
        failure_block = self.remote[persist:live_boundary]
        self.assertIn("STAGED_EVIDENCE_COMMITTED=1", failure_block)
        self.assertIn("record_primary_failure staged_evidence_persist_failed", failure_block)
        self.assertIn('rm -f -- "$STAGE/tls/ip-key.pem"', failure_block)
        self.assertIn("exit 96", failure_block)
        cleanup = self.remote.split("\ncleanup() {", 1)[1].split("trap cleanup EXIT", 1)[0]
        remove_key = cleanup.index('rm -f -- "$STAGE/tls/ip-key.pem"')
        staged_gate = cleanup.index("STAGED_EVIDENCE_COMMITTED -eq 1", remove_key)
        failure_evidence = cleanup.index("commit_terminal_evidence", staged_gate)
        remove_stage = cleanup.index('rm -rf -- "$STAGE"', failure_evidence)
        self.assertLess(remove_key, staged_gate)
        self.assertLess(staged_gate, failure_evidence)
        self.assertLess(failure_evidence, remove_stage)

    def test_pre_staging_cleanup_skips_terminal_evidence_and_preserves_primary_rc(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        cleanup = "cleanup() {" + self.remote.split("\ncleanup() {", 1)[1].split(
            "\n}\ntrap cleanup EXIT", 1
        )[0] + "\n}"
        script = f"""set -Eeuo pipefail
{cleanup}
TRACE="$1"
STAGE="$2"
EVIDENCE="$3"
DEPLOY_ID=20260718123456-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
BACKUP_ROOT="$2/backups-do-not-exist"
LIVE_TOUCHED=0
ROLLBACK_FAILED=0
DEPLOY_RESULT=failed
FINAL_EVIDENCE_COMMITTED=0
STAGED_EVIDENCE_COMMITTED=0
DEPLOY_SUCCESS_MESSAGE=''
stop_staged_server() {{ :; }}
rollback_live() {{ printf 'rollback_attempted\n' >> "$TRACE"; return 0; }}
commit_terminal_evidence() {{
    printf 'terminal_attempted\n' >> "$TRACE"
    return 73
}}
cancel_cleanup_watchdog() {{ printf 'watchdog_cancel\n' >> "$TRACE"; }}
prune_backups() {{ return 0; }}
rm() {{ return 0; }}
trap cleanup EXIT
exit 42
"""
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            trace = base / "trace.txt"
            stage = base / "stage"
            stage.mkdir()
            evidence = base / "evidence-does-not-exist"
            completed = self.run_bash_fixture(
                bash,
                script,
                str(trace),
                str(stage),
                str(evidence),
            )
            self.assertEqual(42, completed.returncode, completed.stderr)
            events = trace.read_text(encoding="utf-8")
            self.assertNotIn("terminal_attempted", events)
            self.assertNotIn("rollback_attempted", events)
            self.assertIn("terminal_evidence=skipped", completed.stderr)
            self.assertIn("primary_rc=42", completed.stderr)
            self.assertNotIn(
                "DEPLOY_EVIDENCE_PERSIST_FAILED terminal=1",
                completed.stderr,
            )

    def test_live_binary_and_receipt_must_match_staged_candidate(self) -> None:
        self.assertIn('STAGED_BINARY_SHA="$(file_sha256 "$STAGE/aneb-server-linux")"', self.remote)
        live_boundary = self.remote.index("LIVE_TOUCHED=1")
        live_binary = self.remote.index('file_sha256 /opt/aneb/bin/aneb-server', live_boundary)
        live_receipt = self.remote.index('LIVE_RECEIPT_SHA="$(cat', live_binary)
        self.assertIn('[[ "$LIVE_BINARY_SHA" == "$STAGED_BINARY_SHA" ]]', self.remote[live_binary:])
        self.assertIn('[[ "$LIVE_RECEIPT_SHA" == "$STAGED_RECEIPT_SHA" ]]', self.remote[live_receipt:])

    def test_every_installed_artifact_is_digest_bound_to_the_staged_manifest(self) -> None:
        self.assertIn("validate_live_artifacts() {", self.remote)
        function = self.remote.split("validate_live_artifacts() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        for logical in (
            "aneb-server-linux",
            "aneb-server.service",
            "root-profiles/basic_network.json",
            "root-profiles/s1_chat.json",
            "root-profiles/s2_coding_agent.json",
            "root-profiles/s3_multimodal.json",
            "execution-profiles/token_multimodal_quick/profile.json",
            "execution-profiles/token_multimodal_quick/runtime_plan.json",
            "execution-profiles/token_multimodal_quick/manifest.sha256",
            "execution-profiles/ai_realtime_voice_quick/profile.json",
            "execution-profiles/ai_realtime_voice_quick/runtime_plan.json",
            "execution-profiles/ai_realtime_voice_quick/manifest.sha256",
            "execution-profiles/network_comprehensive_quick/profile.json",
            "execution-profiles/network_comprehensive_quick/runtime_plan.json",
            "execution-profiles/network_comprehensive_quick/manifest.sha256",
            "execution-profiles/token_multimodal_repeatability_qualification/profile.json",
            "execution-profiles/token_multimodal_repeatability_qualification/runtime_plan.json",
            "execution-profiles/token_multimodal_repeatability_qualification/manifest.sha256",
            "execution-profiles/ai_realtime_voice_repeatability_qualification/profile.json",
            "execution-profiles/ai_realtime_voice_repeatability_qualification/runtime_plan.json",
            "execution-profiles/ai_realtime_voice_repeatability_qualification/manifest.sha256",
            "execution-profiles/network_comprehensive_repeatability_qualification/profile.json",
            "execution-profiles/network_comprehensive_repeatability_qualification/runtime_plan.json",
            "execution-profiles/network_comprehensive_repeatability_qualification/manifest.sha256",
            "tls/ip-cert.pem",
            "tls/ip-key.pem",
        ):
            self.assertIn(logical, function)
        self.assertIn("artifact digest mismatch after install", function)
        self.assertIn("live-artifact-manifest.sha256", function)
        call = self.remote.index("validate_live_artifacts\n")
        restart = self.remote.index("systemctl restart aneb-server", call)
        self.assertLess(call, restart)

    def test_live_artifact_comparison_rejects_one_byte_unit_mutation(self) -> None:
        function = self.remote.split("validate_live_artifacts() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        program = function.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        logical_to_live = {
            "aneb-server-linux": "/opt/aneb/bin/aneb-server",
            "aneb-server.service": "/etc/systemd/system/aneb-server.service",
            "root-profiles/basic_network.json": "/opt/aneb/profiles/basic_network.json",
            "root-profiles/s1_chat.json": "/opt/aneb/profiles/s1_chat.json",
            "root-profiles/s2_coding_agent.json": "/opt/aneb/profiles/s2_coding_agent.json",
            "root-profiles/s3_multimodal.json": "/opt/aneb/profiles/s3_multimodal.json",
            "execution-profiles/token_multimodal_quick/profile.json": (
                "/opt/aneb/execution-profiles/token_multimodal_quick/profile.json"
            ),
            "execution-profiles/token_multimodal_quick/runtime_plan.json": (
                "/opt/aneb/execution-profiles/token_multimodal_quick/runtime_plan.json"
            ),
            "execution-profiles/token_multimodal_quick/manifest.sha256": (
                "/opt/aneb/execution-profiles/token_multimodal_quick/manifest.sha256"
            ),
            "execution-profiles/ai_realtime_voice_quick/profile.json": (
                "/opt/aneb/execution-profiles/ai_realtime_voice_quick/profile.json"
            ),
            "execution-profiles/ai_realtime_voice_quick/runtime_plan.json": (
                "/opt/aneb/execution-profiles/ai_realtime_voice_quick/runtime_plan.json"
            ),
            "execution-profiles/ai_realtime_voice_quick/manifest.sha256": (
                "/opt/aneb/execution-profiles/ai_realtime_voice_quick/manifest.sha256"
            ),
            "execution-profiles/network_comprehensive_quick/profile.json": (
                "/opt/aneb/execution-profiles/network_comprehensive_quick/profile.json"
            ),
            "execution-profiles/network_comprehensive_quick/runtime_plan.json": (
                "/opt/aneb/execution-profiles/network_comprehensive_quick/runtime_plan.json"
            ),
            "execution-profiles/network_comprehensive_quick/manifest.sha256": (
                "/opt/aneb/execution-profiles/network_comprehensive_quick/manifest.sha256"
            ),
            "execution-profiles/token_multimodal_repeatability_qualification/profile.json": (
                "/opt/aneb/execution-profiles/token_multimodal_repeatability_qualification/profile.json"
            ),
            "execution-profiles/token_multimodal_repeatability_qualification/runtime_plan.json": (
                "/opt/aneb/execution-profiles/token_multimodal_repeatability_qualification/runtime_plan.json"
            ),
            "execution-profiles/token_multimodal_repeatability_qualification/manifest.sha256": (
                "/opt/aneb/execution-profiles/token_multimodal_repeatability_qualification/manifest.sha256"
            ),
            "execution-profiles/ai_realtime_voice_repeatability_qualification/profile.json": (
                "/opt/aneb/execution-profiles/ai_realtime_voice_repeatability_qualification/profile.json"
            ),
            "execution-profiles/ai_realtime_voice_repeatability_qualification/runtime_plan.json": (
                "/opt/aneb/execution-profiles/ai_realtime_voice_repeatability_qualification/runtime_plan.json"
            ),
            "execution-profiles/ai_realtime_voice_repeatability_qualification/manifest.sha256": (
                "/opt/aneb/execution-profiles/ai_realtime_voice_repeatability_qualification/manifest.sha256"
            ),
            "execution-profiles/network_comprehensive_repeatability_qualification/profile.json": (
                "/opt/aneb/execution-profiles/network_comprehensive_repeatability_qualification/profile.json"
            ),
            "execution-profiles/network_comprehensive_repeatability_qualification/runtime_plan.json": (
                "/opt/aneb/execution-profiles/network_comprehensive_repeatability_qualification/runtime_plan.json"
            ),
            "execution-profiles/network_comprehensive_repeatability_qualification/manifest.sha256": (
                "/opt/aneb/execution-profiles/network_comprehensive_repeatability_qualification/manifest.sha256"
            ),
        }
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            stage = base / "stage"
            live = base / "live"
            stage.mkdir()
            live.mkdir()
            entries: dict[str, str] = {}
            for index, logical in enumerate(
                [
                    *logical_to_live,
                    "build-provenance.json",
                    "go-buildinfo.json",
                ]
            ):
                data = f"artifact-{index}-{logical}\n".encode()
                staged_path = stage.joinpath(*logical.split("/"))
                staged_path.parent.mkdir(parents=True, exist_ok=True)
                staged_path.write_bytes(data)
                entries[logical] = hashlib.sha256(data).hexdigest()
                if logical in logical_to_live:
                    live_path = live.joinpath(*logical.split("/"))
                    live_path.parent.mkdir(parents=True, exist_ok=True)
                    live_path.write_bytes(data)
                    program = program.replace(
                        repr(logical_to_live[logical]), repr(str(live_path))
                    )
            (stage / "artifact-manifest.sha256").write_text(
                "".join(f"{entries[name]}  {name}\n" for name in sorted(entries)),
                encoding="ascii",
            )
            output = stage / "live-artifact-manifest.sha256"

            accepted = subprocess.run(
                [sys.executable, "-c", program, str(stage), "0", "none", str(output)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            unit = live / "aneb-server.service"
            unit.write_bytes(unit.read_bytes() + b"x")
            rejected = subprocess.run(
                [sys.executable, "-c", program, str(stage), "0", "none", str(output)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("artifact digest mismatch after install", rejected.stderr)

    def test_remote_deploy_lock_is_nonblocking_and_precedes_shared_mutation(self) -> None:
        self.assertIn('DEPLOY_LOCK_PATH="/run/lock/aneb-deploy.lock"', self.remote)
        self.assertIn("acquire_deploy_lock() {", self.remote)
        function = "acquire_deploy_lock() {" + self.remote.split(
            "acquire_deploy_lock() {", 1
        )[1].split("\n}", 1)[0] + "\n}"
        self.assertIn('exec 9>"$DEPLOY_LOCK_PATH"', function)
        self.assertIn("flock -n 9", function)
        self.assertIn("DEPLOY_LOCK_BUSY", function)

        acquire = self.remote.index("acquire_deploy_lock\n")
        rejected = self.remote[acquire : self.remote.index("early_cleanup() {", acquire)]
        self.assertIn("DEPLOY_LOCK_RC", rejected)
        self.assertIn('rm -f -- "$STAGE/tls/ip-key.pem"', rejected)
        self.assertIn('rm -rf -- "$STAGE"', rejected)
        self.assertIn('systemctl stop "$WATCHDOG_TIMER"', rejected)
        for mutation in (
            "persist_stage_evidence staged_validated",
            "freeze_live_baseline\n",
            'install -d -m 0700 "$BACKUP_ROOT"',
            "LIVE_TOUCHED=1",
        ):
            with self.subTest(mutation=mutation):
                self.assertLess(acquire, self.remote.index(mutation, acquire))

        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU Bash is unavailable; live flock contention was not run")
        has_flock = subprocess.run(
            [bash, "-lc", "command -v flock >/dev/null 2>&1"],
            capture_output=True,
            check=False,
        )
        if has_flock.returncode != 0:
            self.skipTest("flock is unavailable; live lock contention was not run")
        with tempfile.TemporaryDirectory() as temporary:
            lock_path = Path(temporary) / "deploy.lock"
            first = subprocess.Popen(
                [
                    bash,
                    "-c",
                    function
                    + '\nDEPLOY_LOCK_PATH="$1"\nacquire_deploy_lock\nprintf held\nsleep 5',
                    "--",
                    str(lock_path),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            try:
                self.assertEqual(
                    f"DEPLOY_LOCK_ACQUIRED path={lock_path}\n",
                    first.stdout.readline(),
                )
                self.assertEqual("held", first.stdout.read(4))
                second = subprocess.run(
                    [
                        bash,
                        "-c",
                        function
                        + '\nDEPLOY_LOCK_PATH="$1"\n'
                        + 'acquire_deploy_lock || exit $?\nprintf mutated',
                        "--",
                        str(lock_path),
                    ],
                    text=True,
                    capture_output=True,
                    check=False,
                    timeout=5,
                )
                self.assertEqual(75, second.returncode)
                self.assertNotIn("mutated", second.stdout)
                self.assertIn("DEPLOY_LOCK_BUSY", second.stderr)
            finally:
                first.terminate()
                first.communicate(timeout=5)

    def test_remote_cleanup_watchdog_owns_stage_before_private_key_lands(self) -> None:
        self.assertNotIn("$RemoteExecutionStarted", self.source)
        self.assertIn("$RemoteCleanupWatchdogArmed = $false", self.source)
        self.assertIn("systemd-run", self.source)
        self.assertIn("--on-active=30m", self.source)
        self.assertIn(".cleanup-watchdog-armed", self.source)
        self.assertIn(".key-transfer-authorized", self.source)

        watchdog = self.source.index("systemd-run")
        handshake = self.source.index(".key-transfer-authorized", watchdog)
        private_key = self.source.index("-Label 'IP-SAN private key'", handshake)
        invoke = self.source.index("& ssh @SshOpts $Remote", private_key)
        self.assertLess(watchdog, handshake)
        self.assertLess(handshake, private_key)
        self.assertLess(private_key, invoke)

        finally_body = self.source.rsplit("finally {", 1)[1]
        self.assertIn(
            "$RemoteStageCreated -and -not $RemoteCleanupWatchdogArmed",
            finally_body,
        )
        self.assertIn("early_cleanup() {", self.remote)
        early_cleanup = self.remote.split("early_cleanup() {", 1)[1].split(
            "\n}\n", 1
        )[0]
        self.assertIn("trap '' HUP INT TERM", early_cleanup)
        early_trap = self.remote.index("trap early_cleanup EXIT INT TERM HUP")
        validation = self.remote.index("validate_uploaded_artifacts", early_trap)
        self.assertLess(early_trap, validation)
        self.assertIn('rm -f -- "$STAGE/tls/ip-key.pem"', self.remote)
        self.assertIn('rm -rf -- "$STAGE"', self.remote)

    def test_remote_script_and_embedded_python_parse(self) -> None:
        bash = self.find_gnu_bash()
        if bash is not None:
            completed = subprocess.run(
                [bash, "-n", "-s"],
                input=self.remote,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(
                0,
                completed.returncode,
                msg=f"stdout={completed.stdout!r}\nstderr={completed.stderr!r}",
            )
        blocks = re.findall(r"<<'PY'\n(.*?)\nPY(?:\n|$)", self.remote, flags=re.DOTALL)
        self.assertGreaterEqual(len(blocks), 10)
        for index, block in enumerate(blocks):
            with self.subTest(index=index):
                compile(block, f"deploy_server.remote.heredoc.{index}.py", "exec")

    def test_cleanup_is_strict_and_backups_are_bounded(self) -> None:
        self.assertIn("set -Eeuo pipefail", self.remote)
        self.assertIn("trap cleanup EXIT", self.remote)
        self.assertIn("trap 'exit 129' HUP", self.remote)
        self.assertIn("items[3:]", self.remote)
        self.assertIn('rm -rf -- "$STAGE"', self.remote)
        self.assertIn("retained_backups=3", self.remote)
        self.assertIn("WARNING backup_prune_failed maintenance_required=1", self.remote)
        success_tail = self.remote[self.remote.rindex("assert_shared_host_baseline live") :]
        self.assertLess(success_tail.index("LIVE_TOUCHED=0"), success_tail.index("if ! prune_backups"))
        self.assertIn("backup_prune=$PRUNE_RESULT", success_tail)
        cleanup = self.remote.split("\ncleanup() {", 1)[1].split("trap cleanup EXIT", 1)[0]
        self.assertNotIn("prune_backups || ROLLBACK_FAILED=1", cleanup)
        self.assertIn("trap '' HUP INT TERM", cleanup)
        self.assertIn("if ( rollback_live ); then", cleanup)
        self.assertIn("ROLLBACK_FAILED=1", cleanup)
        self.assertIn("rollback_status='failed'", cleanup)
        self.assertGreaterEqual(cleanup.count("set +e"), 2)
        rollback = self.remote.split("rollback_live() {", 1)[1].split(
            "\n}\n\n", 1
        )[0]
        self.assertNotIn("set -e", rollback)

    def test_cleanup_result_reports_independent_machine_readable_surfaces(self) -> None:
        cleanup = self.remote.split("\ncleanup() {", 1)[1].split(
            "\n}\ntrap cleanup EXIT", 1
        )[0]
        result_line = next(
            line for line in cleanup.splitlines() if "ANEB_DEPLOY_RESULT schema=" in line
        )
        for field in (
            "staged_process=",
            "watchdog=",
            "backup_prune=",
            "owned_path_cleanup=",
        ):
            self.assertIn(field, result_line)
        self.assertNotRegex(result_line, r"(?:^|\s)cleanup=")
        self.assertIn(
            "owned_path_cleanup=$owned_path_cleanup_status",
            result_line,
        )
        self.assertNotIn(
            "owned_path_cleanup=$([[ $cleanup_failed",
            result_line,
        )

        stop_failure = cleanup.split("if ! stop_staged_server; then", 1)[1].split(
            "fi", 1
        )[0]
        self.assertIn("staged_process_status='failed'", stop_failure)
        self.assertIn("cleanup_failed=1", stop_failure)

        watchdog_failure = cleanup.split("if cancel_cleanup_watchdog; then", 1)[1].split(
            "fi", 1
        )[0]
        self.assertIn("watchdog_status='failed'", watchdog_failure)
        self.assertIn("cleanup_failed=1", watchdog_failure)

    def test_watchdog_cleanup_accepts_collected_transient_units(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        helper = "watchdog_unit_is_cleared() {" + self.remote.split(
            "watchdog_unit_is_cleared() {", 1
        )[1].split("\n}\n", 1)[0] + "\n}"
        function = "cancel_cleanup_watchdog() {" + self.remote.split(
            "cancel_cleanup_watchdog() {", 1
        )[1].split("\n}\n", 1)[0] + "\n}"
        script = f"""set -Eeuo pipefail
{helper}
{function}
WATCHDOG_TIMER=aneb-deploy-expire-test.timer
WATCHDOG_SERVICE=aneb-deploy-expire-test.service
systemctl() {{
    case "$1" in
        stop) return 5 ;;
        reset-failed) return 5 ;;
        show)
            printf '%s\n' \
                'LoadState=not-found' \
                'ActiveState=inactive' \
                'Job='
            return 0
            ;;
        *) return 64 ;;
    esac
}}
cancel_cleanup_watchdog
"""
        completed = subprocess.run(
            [bash, "-c", script],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_watchdog_cleanup_accepts_loaded_inactive_units(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        helper = "watchdog_unit_is_cleared() {" + self.remote.split(
            "watchdog_unit_is_cleared() {", 1
        )[1].split("\n}\n", 1)[0] + "\n}"
        function = "cancel_cleanup_watchdog() {" + self.remote.split(
            "cancel_cleanup_watchdog() {", 1
        )[1].split("\n}\n", 1)[0] + "\n}"
        script = f"""set -Eeuo pipefail
{helper}
{function}
WATCHDOG_TIMER=aneb-deploy-expire-test.timer
WATCHDOG_SERVICE=aneb-deploy-expire-test.service
systemctl() {{
    case "$1" in
        stop|reset-failed) return 0 ;;
        show)
            printf '%s\n' \
                'LoadState=loaded' \
                'ActiveState=inactive' \
                'Job='
            return 0
            ;;
        *) return 64 ;;
    esac
}}
cancel_cleanup_watchdog
"""
        completed = subprocess.run(
            [bash, "-c", script],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_watchdog_cleanup_fails_closed_without_a_cleared_final_state(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        helper = "watchdog_unit_is_cleared() {" + self.remote.split(
            "watchdog_unit_is_cleared() {", 1
        )[1].split("\n}\n", 1)[0] + "\n}"
        function = "cancel_cleanup_watchdog() {" + self.remote.split(
            "cancel_cleanup_watchdog() {", 1
        )[1].split("\n}\n", 1)[0] + "\n}"
        cases = {
            "query-failed": "return 69",
            "active": (
                "printf '%s\\n' 'LoadState=loaded' "
                "'ActiveState=active' 'Job='; return 0"
            ),
            "pending-job": (
                "printf '%s\\n' 'LoadState=loaded' "
                "'ActiveState=inactive' 'Job=42'; return 0"
            ),
        }
        for label, show_behavior in cases.items():
            with self.subTest(label=label):
                script = f"""set -Eeuo pipefail
{helper}
{function}
WATCHDOG_TIMER=aneb-deploy-expire-test.timer
WATCHDOG_SERVICE=aneb-deploy-expire-test.service
systemctl() {{
    case "$1" in
        stop|reset-failed) return 0 ;;
        show) {show_behavior} ;;
        *) return 64 ;;
    esac
}}
cancel_cleanup_watchdog
"""
                completed = subprocess.run(
                    [bash, "-c", script],
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertNotEqual(0, completed.returncode)
                self.assertIn("WATCHDOG_STATE_", completed.stderr)

    def test_cleanup_ignores_second_hup_and_reaches_terminal_cleanup(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        cleanup = "cleanup() {" + self.remote.split("\ncleanup() {", 1)[1].split(
            "\n}\ntrap cleanup EXIT", 1
        )[0] + "\n}"
        script = f"""set -Eeuo pipefail
{cleanup}
TRACE="$1"
STAGE=/tmp/aneb-cleanup-test
DEPLOY_ID=20260718123456-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
BACKUP_ROOT=/path/that/does/not/exist
LIVE_TOUCHED=0
ROLLBACK_FAILED=0
DEPLOY_RESULT=failed
FINAL_EVIDENCE_COMMITTED=0
STAGED_EVIDENCE_COMMITTED=1
DEPLOY_SUCCESS_MESSAGE=''
stop_staged_server() {{
    kill -HUP "$BASHPID"
    printf 'after_hup\n' >> "$TRACE"
}}
rollback_live() {{ return 0; }}
commit_terminal_evidence() {{
    printf 'terminal_evidence\n' >> "$TRACE"
    FINAL_EVIDENCE_COMMITTED=1
}}
cancel_cleanup_watchdog() {{ printf 'watchdog_cancel\n' >> "$TRACE"; }}
prune_backups() {{ return 0; }}
rm() {{ printf 'rm %s\n' "$*" >> "$TRACE"; return 0; }}
trap cleanup EXIT
trap 'exit 130' INT TERM
trap 'exit 129' HUP
false
"""
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace.txt"
            completed = self.run_bash_fixture(bash, script, str(trace))
            self.assertEqual(1, completed.returncode, completed.stderr)
            events = trace.read_text(encoding="utf-8")
            self.assertIn("after_hup", events)
            self.assertIn("terminal_evidence", events)
            self.assertIn("watchdog_cancel", events)

    def test_cleanup_keeps_watchdog_armed_if_stage_removal_fails(self) -> None:
        bash = self.find_gnu_bash()
        if bash is None:
            self.skipTest("GNU bash is unavailable")
        cleanup = "cleanup() {" + self.remote.split("\ncleanup() {", 1)[1].split(
            "\n}\ntrap cleanup EXIT", 1
        )[0] + "\n}"
        script = f"""set -Eeuo pipefail
{cleanup}
TRACE="$1"
STAGE=/tmp/aneb-cleanup-test
DEPLOY_ID=20260718123456-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
BACKUP_ROOT=/path/that/does/not/exist
LIVE_TOUCHED=0
ROLLBACK_FAILED=0
DEPLOY_RESULT=failed
FINAL_EVIDENCE_COMMITTED=0
DEPLOY_SUCCESS_MESSAGE=''
stop_staged_server() {{ :; }}
rollback_live() {{ return 0; }}
commit_terminal_evidence() {{ FINAL_EVIDENCE_COMMITTED=1; }}
cancel_cleanup_watchdog() {{ printf 'watchdog_cancel\n' >> "$TRACE"; }}
prune_backups() {{ return 0; }}
rm() {{
    if [[ "$*" == "-rf -- $STAGE" ]]; then
        printf 'stage_remove_failed\n' >> "$TRACE"
        return 73
    fi
    return 0
}}
trap cleanup EXIT
false
"""
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace.txt"
            completed = self.run_bash_fixture(bash, script, str(trace))
            self.assertEqual(1, completed.returncode, completed.stderr)
            events = trace.read_text(encoding="utf-8")
            self.assertIn("stage_remove_failed", events)
            self.assertNotIn("watchdog_cancel", events)

    def test_script_has_no_host_network_or_co_tenant_mutation_commands(self) -> None:
        command_patterns = (
            r"(?m)^\s*(?:sudo\s+)?iptables(?:\s|$)",
            r"\bnft\s+(?:add|delete|insert|flush|replace|reset)\b",
            r"\bufw\b",
            r"\btc\s+qdisc\s+(?:add|change|replace|del)\b",
            r"\bdocker\s+(?:run|stop|restart|rm|network|compose)\b",
            r"/etc/sysctl",
        )
        for pattern in command_patterns:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.remote, flags=re.IGNORECASE))


if __name__ == "__main__":
    unittest.main()
