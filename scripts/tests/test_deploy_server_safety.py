from __future__ import annotations

import re
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

    @classmethod
    def canonicalize_iptables_save(cls, snapshot: str) -> str:
        function = cls.remote.split("canonicalize_iptables_save() {", 1)[1].split(
            "\n}", 1
        )[0]
        program = function.split("python3 -c '", 1)[1].split("\n'", 1)[0]
        completed = subprocess.run(
            [sys.executable, "-c", program],
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
            + cls.canonicalize_iptables_save(iptables)
            + "--- ip6tables-save ---\n"
            + cls.canonicalize_iptables_save(ip6tables)
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

    def test_local_gates_precede_the_first_remote_connection(self) -> None:
        main = self.source.split("# No ssh/scp call is made before this function returns successfully.", 1)[1]
        gate = main.index("Invoke-LocalSafetyGates")
        first_connection = main.index("& ssh @SshOpts")
        self.assertLess(gate, first_connection)
        self.assertIn("verify_spec_catalog.py", self.source)
        self.assertIn("test -count=1 ./...", self.source)
        for artifact in ("profile.json", "runtime_plan.json", "manifest.sha256"):
            self.assertIn(artifact, self.source)

    def test_runtime_plan_is_uploaded_with_the_manifest_bound_bundle(self) -> None:
        self.assertRegex(
            self.source,
            r"Copy-ToRemote -LocalPath \$TokenQuickRuntimePlan .*runtime_plan\.json",
        )
        self.assertNotIn("sha256sum", self.remote)
        self.assertIn("{'profile.json', 'runtime_plan.json'}", self.remote)

    def test_candidate_receipt_is_verified_before_live_mutation(self) -> None:
        staged_receipt = self.remote.index("validate_receipt \\")
        stage_ok = self.remote.index('echo "STAGING_OK')
        live_boundary = self.remote.index("LIVE_TOUCHED=1")
        self.assertLess(staged_receipt, stage_ok)
        self.assertLess(stage_ok, live_boundary)
        self.assertIn('-addr "127.0.0.1:$STAGE_PORT"', self.remote)
        self.assertIn("-udp-echo-addr ''", self.remote)
        self.assertIn("setsid runuser -u aneb", self.remote)
        self.assertIn('kill -TERM -- "-$STAGE_PID"', self.remote)

    def test_pre_switch_requires_exact_070_identity_and_freezes_shared_host(self) -> None:
        freeze_call = self.remote.index("freeze_live_baseline\n")
        live_boundary = self.remote.index("LIVE_TOUCHED=1", freeze_call)
        self.assertLess(freeze_call, live_boundary)
        freeze_function = self.remote.index("freeze_live_baseline()")
        self.assertLess(freeze_function, freeze_call)
        self.assertIn("validate_server_identity 'aneb-server/0.7.0' pre-switch", self.remote)
        freeze_body = self.remote.split("freeze_live_baseline() {", 1)[1].split("\n}", 1)[0]
        identity = freeze_body.index("validate_server_identity 'aneb-server/0.7.0' pre-switch")
        legacy = freeze_body.index("validate_legacy_surface pre-switch-0.7")
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
        second = first.replace("18:00:00", "18:03:17")

        canonical_first = self.canonicalize_iptables_save(first)
        canonical_second = self.canonicalize_iptables_save(second)

        self.assertEqual(canonical_first, canonical_second)
        self.assertIn(
            "# Generated by iptables-save v1.8.7 (nf_tables)\n",
            canonical_first,
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
        self.assertIn("iptables-save | canonicalize_iptables_save", snapshot)
        self.assertIn("ip6tables-save | canonicalize_iptables_save", snapshot)
        self.assertIn("iptables-save snapshot failed", snapshot)
        self.assertIn("ip6tables-save snapshot failed", snapshot)
        self.assertNotIn("iptables-save -c", snapshot)
        self.assertNotIn("ip6tables-save -c", snapshot)
        self.assertNotIn("--counters", snapshot)
        self.assertIn("nft --stateless list ruleset", snapshot)
        self.assertIn("nft ruleset snapshot failed", snapshot)

    def test_docker_firewall_filter_does_not_mask_capture_failure(self) -> None:
        snapshot = self.remote.split("docker_firewall_snapshot() {", 1)[1].split(
            "\n}", 1
        )[0]
        self.assertIn("iptables-save | awk '/(^:DOCKER|DOCKER)/ { print }'", snapshot)
        self.assertIn("ip6tables-save | awk '/(^:DOCKER|DOCKER)/ { print }'", snapshot)
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
            "service-unit": "/etc/systemd/system/aneb-server.service",
        }
        for label, path in expected.items():
            self.assertIn(f"snapshot_item {label} {path}", self.remote)
            self.assertIn(f"restore_item {label} {path}", self.remote)
        self.assertIn("systemctl restart aneb-server", self.remote)
        self.assertIn("ROLLBACK_OK", self.remote)
        self.assertIn("restore_item live-binary /opt/aneb/bin/aneb-server || rollback_rc=1", self.remote)
        self.assertIn("validate_server_identity 'aneb-server/0.7.0' rollback", self.remote)
        self.assertIn("validate_legacy_surface rollback-0.7", self.remote)
        self.assertIn("assert_restored_aneb_baseline", self.remote)
        self.assertIn("assert_shared_host_baseline rollback", self.remote)
        self.assertIn("ROLLBACK_FAILED verification=identity+legacy_surface+fingerprints exit=97", self.remote)
        self.assertIn("exit 97", self.remote)

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
        self.assertIn("Get-FileHash -LiteralPath $IpCert -Algorithm SHA256", self.source)
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

    def test_real_deployment_requires_codex_e01_shared_lease(self) -> None:
        local_only = self.source.index("if ($LocalValidationOnly)")
        lease = self.source.index("Assert-SharedDeploymentLease", local_only)
        first_connection = self.source.index("& ssh @SshOpts", lease)
        self.assertLess(local_only, lease)
        self.assertLess(lease, first_connection)
        self.assertIn("E-01", self.source)
        self.assertRegex(self.source, r"\[Parameter\(Mandatory = \$true\)\]\s+\[ValidatePattern\('\^\[0-9a-fA-F\]\{32\}\$'\)\]\s+\[string\]\$LeaseId")
        self.assertIn("update_shared_test_status.py", self.source)
        self.assertIn("assert-lease", self.source)
        self.assertIn("--executor Codex", self.source)
        self.assertIn("--lease-id $LeaseId.ToLowerInvariant()", self.source)
        self.assertIn("--resource E-01", self.source)
        lease_function = self.source.split("function Assert-SharedDeploymentLease", 1)[1].split("function Copy-ToRemote", 1)[0]
        self.assertNotIn("claim", lease_function)
        self.assertNotIn("handoff", lease_function)
        self.assertNotIn("lock", lease_function)

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
        self.assertIn("validate_legacy_surface live-0.8", self.remote)
        self.assertIn("download?bytes=1048576", self.remote)

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
        cleanup = self.remote.split("cleanup() {", 1)[1].split("trap cleanup EXIT", 1)[0]
        self.assertNotIn("prune_backups || ROLLBACK_FAILED=1", cleanup)

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
