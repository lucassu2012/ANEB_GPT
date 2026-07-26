from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
import uuid
from unittest import mock

from scripts import collect_realtime_quick_evidence as collector


RUN_ID = "019fa000-1111-7222-8333-444455556666"
LAUNCHER = "com.huawei.android.launcher/.unihome.UniHomeLauncher"
CANONICAL_LAUNCHER = (
    "com.huawei.android.launcher/"
    "com.huawei.android.launcher.unihome.UniHomeLauncher"
)


def clean_raw_snapshot() -> dict[str, object]:
    activity = "\n".join(
        (
            f"  mFocusedApp=AppWindowToken{{123 u0 {LAUNCHER} t1}}",
            f"  topResumedActivity=ActivityRecord{{456 u0 {LAUNCHER} t1}}",
        )
    )
    processes = {package: "" for package in collector.RELEVANT_PACKAGES}
    services = {package: "(nothing)" for package in collector.RELEVANT_PACKAGES}
    return {
        "device_state": "device",
        "current_user": "0",
        "activity": activity,
        "processes": processes,
        "services": services,
        "enabled_accessibility": "null",
        "accessibility_dump": "Bound services: []\n",
        "interfaces": "dummy0\neth0\nlo\nwlan0\n",
        "connectivity": (
            "NetworkAgentInfo{ network{101} state: CONNECTED/CONNECTED "
            "Capabilities: NOT_VPN&INTERNET&VALIDATED&NOT_SUSPENDED "
            "Transports: WIFI }\n"
            "NetworkRequest [ LISTEN id=12, [ Transports: VPN Capabilities: "
            "NOT_VPN&TRUSTED&NOT_RESTRICTED ] ]\n"
        ),
        "vpn": "VPNs:\n  User 0: none\n",
        "stayon": "7",
        "wifi_on": "1",
    }


class PhoneSnapshotTests(unittest.TestCase):
    def test_clean_snapshot_requires_launcher_and_no_conflicting_runtime(self) -> None:
        snapshot = collector.parse_phone_snapshot(clean_raw_snapshot())

        self.assertEqual(CANONICAL_LAUNCHER, snapshot.focused_component)
        self.assertEqual((CANONICAL_LAUNCHER,), snapshot.resumed_components)
        self.assertEqual("7", snapshot.stayon)
        self.assertEqual("1", snapshot.wifi_on)
        self.assertEqual(collector.clean_snapshot_hash(snapshot), snapshot.canonical_sha256)

    def test_huawei_empty_service_dump_is_not_an_active_service(self) -> None:
        raw = clean_raw_snapshot()
        raw["services"] = {
            package: "ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)"
            for package in collector.RELEVANT_PACKAGES
        }

        snapshot = collector.parse_phone_snapshot(raw)

        self.assertEqual(CANONICAL_LAUNCHER, snapshot.focused_component)
        self.assertTrue(all(not value for _, value in snapshot.processes))
        self.assertTrue(
            all("ServiceRecord{" not in value for _, value in snapshot.services)
        )

    def test_unknown_service_dump_still_fails_closed(self) -> None:
        raw = clean_raw_snapshot()
        raw["services"]["com.aneb.probe.codex"] = (  # type: ignore[index]
            "ACTIVITY MANAGER SERVICES (dumpsys activity services)\nwarning: incomplete"
        )

        with self.assertRaisesRegex(
            collector.CollectorError,
            "phone_live_state_rejected reason=service_active",
        ):
            collector.parse_phone_snapshot(raw)

    def test_foreground_process_service_tunnel_and_vpn_each_fail_closed(self) -> None:
        mutations: dict[str, callable] = {
            "foreground": lambda raw: raw.__setitem__(
                "activity",
                "mFocusedApp=ActivityRecord{1 u0 com.aneb.probe.codex/.MainActivity t2}\n"
                "topResumedActivity=ActivityRecord{2 u0 com.aneb.probe.codex/.MainActivity t2}",
            ),
            "process": lambda raw: raw["processes"].__setitem__(  # type: ignore[union-attr]
                "com.aneb.probe.codex", "1234"
            ),
            "service": lambda raw: raw["services"].__setitem__(  # type: ignore[union-attr]
                "com.aneb.probe.codex",
                "ServiceRecord{abc u0 com.aneb.probe.codex/.ProbeRunService}",
            ),
            "tunnel": lambda raw: raw.__setitem__(
                "interfaces", str(raw["interfaces"]) + "tun0\n"
            ),
            "vpn": lambda raw: raw.__setitem__(
                "connectivity",
                "NetworkAgentInfo{ network{102} state: CONNECTED/CONNECTED "
                "Capabilities: INTERNET&VALIDATED Transports: VPN }\n",
            ),
            "vpn_service": lambda raw: raw.__setitem__(
                "vpn",
                "mNetworkInfo=NetworkInfo: type: VPN state: CONNECTED/CONNECTED\n",
            ),
            "bound_accessibility": lambda raw: raw.__setitem__(
                "accessibility_dump",
                "Bound services: [com.aneb.probe.codex/.ProbeAccessibilityService]\n",
            ),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                raw = clean_raw_snapshot()
                mutate(raw)
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "phone_live_state_rejected",
                ):
                    collector.parse_phone_snapshot(raw)

    def test_listen_only_vpn_request_is_not_misclassified_as_active_vpn(self) -> None:
        snapshot = collector.parse_phone_snapshot(clean_raw_snapshot())
        self.assertFalse(snapshot.active_vpn)

    def test_two_snapshots_must_have_equal_canonical_state(self) -> None:
        first = collector.parse_phone_snapshot(clean_raw_snapshot())
        changed = clean_raw_snapshot()
        changed["stayon"] = "3"
        second = collector.parse_phone_snapshot(changed)

        with self.assertRaisesRegex(
            collector.CollectorError,
            "phone_state_not_stable",
        ):
            collector.assert_stable_phone_snapshots(first, second)


class RealtimeMarkerTests(unittest.TestCase):
    def test_positive_marker_chain_is_exact(self) -> None:
        text = "\n".join(
            (
                f"REALTIME_V1_START run_id={RUN_ID} variant=quick server=https://example.invalid",
                f"REALTIME_V1_CONTRACT run_id={RUN_ID} status=authorized receipt_version=1.0.0",
                f"REALTIME_V1_DB_WRITE run_id={RUN_ID} ok=true",
                f"REALTIME_V1_RESULT run_id={RUN_ID} score=92.1 grade=A verdict=excellent confidence=HIGH",
                f"REALTIME_V1_END run_id={RUN_ID} status=completed",
            )
        )

        result = collector.parse_realtime_terminal_markers(text, mode="positive")

        self.assertEqual(RUN_ID, result.run_id)
        self.assertEqual("completed", result.terminal_status)
        self.assertEqual("authorized", result.contract_status)

    def test_negative_marker_chain_is_exact(self) -> None:
        text = "\n".join(
            (
                f"REALTIME_V1_START run_id={RUN_ID} variant=quick server=http://127.0.0.1:18765",
                f"REALTIME_V1_DB_WRITE run_id={RUN_ID} ok=true",
                f"REALTIME_V1_CONTRACT run_id={RUN_ID} status=rejected reason=receipt_missing persisted=true",
                f"REALTIME_V1_END run_id={RUN_ID} status=contract_rejected",
            )
        )

        result = collector.parse_realtime_terminal_markers(text, mode="negative")

        self.assertEqual("receipt_missing", result.reason_code)
        self.assertEqual("contract_rejected", result.terminal_status)

    def test_mixed_run_ids_duplicate_terminal_or_wrong_reason_fail_closed(self) -> None:
        cases = (
            "\n".join(
                (
                    f"REALTIME_V1_START run_id={RUN_ID}",
                    "REALTIME_V1_DB_WRITE run_id=019fa000-1111-7222-8333-444455556667 ok=true",
                    f"REALTIME_V1_CONTRACT run_id={RUN_ID} status=rejected reason=receipt_missing persisted=true",
                    f"REALTIME_V1_END run_id={RUN_ID} status=contract_rejected",
                )
            ),
            "\n".join(
                (
                    f"REALTIME_V1_START run_id={RUN_ID}",
                    f"REALTIME_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"REALTIME_V1_CONTRACT run_id={RUN_ID} status=rejected reason=receipt_missing persisted=true",
                    f"REALTIME_V1_END run_id={RUN_ID} status=contract_rejected",
                    f"REALTIME_V1_END run_id={RUN_ID} status=contract_rejected",
                )
            ),
            "\n".join(
                (
                    f"REALTIME_V1_START run_id={RUN_ID}",
                    f"REALTIME_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"REALTIME_V1_CONTRACT run_id={RUN_ID} status=rejected reason=profile_hash_mismatch persisted=true",
                    f"REALTIME_V1_END run_id={RUN_ID} status=contract_rejected",
                )
            ),
        )
        for text in cases:
            with self.subTest(text=text):
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "realtime_marker_chain_invalid",
                ):
                    collector.parse_realtime_terminal_markers(text, mode="negative")


class CommandContractTests(unittest.TestCase):
    def test_subprocess_runner_uses_input_without_conflicting_stdin_argument(self) -> None:
        completed = subprocess.CompletedProcess(
            args=["tool"],
            returncode=0,
            stdout=b"ok",
            stderr=b"",
        )
        with mock.patch.object(
            collector.subprocess,
            "run",
            return_value=completed,
        ) as run:
            result = collector.SubprocessRunner().run(
                ["tool"],
                timeout_seconds=5,
                stdin=b"payload",
            )

        self.assertEqual(b"ok", result.stdout)
        self.assertEqual(b"payload", run.call_args.kwargs["input"])
        self.assertNotIn("stdin", run.call_args.kwargs)

    def test_launch_command_is_exact_and_has_no_remote_script_surface(self) -> None:
        command = collector.build_realtime_launch_arguments(
            serial="ABC123",
            server_base="https://203.0.113.10:8443",
            transport="wifi",
        )
        self.assertEqual(
            [
                "adb",
                "-s",
                "ABC123",
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "com.aneb.probe.codex/com.aneb.probe.ui.MainActivity",
                "--es",
                "server",
                "https://203.0.113.10:8443",
                "--ez",
                "autorun",
                "true",
                "--es",
                "mode",
                "quick",
                "--es",
                "transport",
                "wifi",
                "--es",
                "test_mode",
                "realtime",
            ],
            command,
        )

    def test_audit_barrier_headers_bind_realtime_scope_and_exact_role(self) -> None:
        barrier_id = "11111111-1111-4111-8111-111111111111"
        self.assertEqual(
            {
                "X-Aneb-Run-Id": barrier_id,
                "X-Aneb-Audit-Role": "window_start",
                "X-Aneb-Audit-Scope": "realtime_run",
            },
            collector.build_audit_headers(
                run_id=barrier_id,
                role="window_start",
            ),
        )
        with self.assertRaisesRegex(
            collector.CollectorError,
            "audit_header_contract_invalid",
        ):
            collector.build_audit_headers(
                run_id=barrier_id,
                role="capability",
            )

    def test_reverse_inventory_binds_transport_label_not_device_serial(self) -> None:
        text = "\n".join(
            (
                "UsbFfs tcp:18765 tcp:18765",
                "OTHER tcp:9000 tcp:9000",
            )
        )
        inventory = collector.parse_reverse_inventory(text)
        self.assertEqual(
            (
                ("UsbFfs", "tcp:18765", "tcp:18765"),
                ("OTHER", "tcp:9000", "tcp:9000"),
            ),
            inventory,
        )
        with self.assertRaisesRegex(
            collector.CollectorError,
            "negative_reverse_ownership_invalid",
        ):
            collector.assert_owned_reverse(
                inventory,
                transport_label="UsbFfs",
                device_port=18765,
            )
        collector.assert_owned_reverse(
            (inventory[0],),
            transport_label="UsbFfs",
            device_port=18765,
        )
        with self.assertRaisesRegex(
            collector.CollectorError,
            "negative_reverse_preexisting",
        ):
            collector.assert_reverse_absent(
                inventory,
                device_port=18765,
            )

    def test_reverse_inventory_rejects_label_verifier_would_reject(self) -> None:
        with self.assertRaisesRegex(
            collector.CollectorError,
            "adb_reverse_inventory_invalid",
        ):
            collector.parse_reverse_inventory("X tcp:18765 tcp:18765")

    def test_ci_provenance_report_is_bound_to_exact_candidate_identity(self) -> None:
        commit = "a" * 40
        report = {
            "schema": "aneb-ci-apk-provenance-report",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "candidate_provenance_reverified": True,
            "repository": "lucassu2012/ANEB_GPT",
            "signer_workflow": "lucassu2012/ANEB_GPT/.github/workflows/ci.yml",
            "predicate_type": "https://slsa.dev/provenance/v1",
            "source_commit": commit,
            "source_ref": "refs/heads/codex/m0-ai-realtime-contract",
            "workflow_run_id": 123,
            "workflow_run_url": "https://github.com/lucassu2012/ANEB_GPT/actions/runs/123",
            "apk": {
                "file_name": "ANEB-Probe-0.5.13-codex-debug.apk",
                "sha256": "b" * 64,
                "size_bytes": 100,
                "package_name": "com.aneb.probe.codex",
                "version_name": "0.5.13-codex",
                "version_code": 45,
                "signer_sha256": "c" * 64,
            },
            "files": {
                "attestation_bundle_sha256": "d" * 64,
                "build_manifest_sha256": "e" * 64,
                "checksums_sha256": "f" * 64,
            },
            "gh": {
                "version": "2.96.0",
                "executable_sha256": "1" * 64,
                "certificate_issuer": "https://token.actions.githubusercontent.com",
                "oidc_issuer": "https://token.actions.githubusercontent.com",
                "runner_environment": "github-hosted",
                "run_invocation_uri": (
                    "https://github.com/lucassu2012/ANEB_GPT/actions/runs/123/"
                    "attempts/1"
                ),
                "subject_alternative_name": (
                    "https://github.com/lucassu2012/ANEB_GPT/"
                    ".github/workflows/ci.yml@refs/heads/codex/m0-ai-realtime-contract"
                ),
                "verified_timestamp_count": 1,
            },
        }

        identity = collector.validate_ci_provenance_report(
            report,
            source_commit=commit,
        )

        self.assertEqual("b" * 64, identity.apk_sha256)
        self.assertEqual("c" * 64, identity.signer_sha256)
        report["apk"]["version_code"] = 44  # type: ignore[index]
        with self.assertRaisesRegex(
            collector.CollectorError,
            "ci_provenance_report_invalid",
        ):
            collector.validate_ci_provenance_report(
                report,
                source_commit=commit,
            )

    def test_remote_lock_script_is_scoped_and_ttl_bounded(self) -> None:
        script = collector.remote_lock_holder_script()
        self.assertIn("flock -n 9", script)
        self.assertIn("/run/lock/aneb-deploy.lock", script)
        self.assertIn("/run/aneb-realtime-audit-$NONCE.lock", script)
        self.assertIn('read -r -t "$TTL_SECONDS"', script)
        self.assertNotIn("eth0", script)
        self.assertNotIn("iptables", script)
        self.assertNotIn("systemctl restart", script)

    def test_remote_snapshot_parser_requires_all_exact_fingerprints(self) -> None:
        values = {
            "boot_id": "1234567812344abc8def1234567890ab",
            "systemd_invocation_id": "abcdef" * 5 + "ab",
            "main_pid": "3388782",
            "server_binary_sha256": "a" * 64,
            "eth0_qdisc_sha256": "b" * 64,
            "firewall_full_sha256": "c" * 64,
            "firewall_v4_sha256": "d" * 64,
            "firewall_v6_sha256": "e" * 64,
            "firewall_nft_sha256": "f" * 64,
            "docker_sha256": "1" * 64,
            "journal_cursor": "s=0123456789abcdef;i=42;b=abcdef;m=123;t=456;x=789",
        }
        text = "".join(f"{key}={value}\n" for key, value in values.items())

        snapshot = collector.parse_remote_snapshot(text)

        self.assertEqual("3388782", snapshot.main_pid)
        self.assertEqual("a" * 64, snapshot.server_binary_sha256)
        self.assertEqual("1" * 64, snapshot.docker_sha256)

        for mutation in (
            text.replace("docker_sha256=", "unknown=", 1),
            text + "main_pid=1\n",
            text.replace("server_binary_sha256=" + "a" * 64, "server_binary_sha256=bad"),
        ):
            with self.subTest(mutation=mutation):
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "remote_snapshot_invalid",
                ):
                    collector.parse_remote_snapshot(mutation)

    def test_remote_snapshot_stability_allows_only_journal_cursor_to_advance(self) -> None:
        before = collector.RemoteSnapshot(
            boot_id="a" * 32,
            systemd_invocation_id="b" * 32,
            main_pid="123",
            server_binary_sha256="c" * 64,
            eth0_qdisc_sha256="d" * 64,
            firewall_full_sha256="e" * 64,
            firewall_v4_sha256="f" * 64,
            firewall_v6_sha256="1" * 64,
            firewall_nft_sha256="2" * 64,
            docker_sha256="3" * 64,
            journal_cursor="s=before-cursor",
        )
        after = collector.RemoteSnapshot(
            **{
                **before.__dict__,
                "journal_cursor": "s=after-cursor",
            }
        )
        collector.assert_remote_snapshot_stable(
            before,
            after,
            expected_binary_sha256="c" * 64,
        )
        changed = collector.RemoteSnapshot(
            **{
                **after.__dict__,
                "eth0_qdisc_sha256": "9" * 64,
            }
        )
        with self.assertRaisesRegex(
            collector.CollectorError,
            "remote_baseline_changed",
        ):
            collector.assert_remote_snapshot_stable(
                before,
                changed,
                expected_binary_sha256="c" * 64,
            )

    def test_remote_snapshot_canonicalizes_only_iptables_wall_clock_wrappers(
        self,
    ) -> None:
        script = collector.remote_snapshot_script()

        self.assertIn("canonicalize_iptables_save()", script)
        self.assertIn("malformed iptables-save Generated header", script)
        self.assertIn("malformed iptables-save Completed footer", script)
        self.assertIn('v4_snapshot="$(iptables-save |', script)
        self.assertIn('v6_snapshot="$(ip6tables-save |', script)
        self.assertNotIn('v4="$(iptables-save | hash_stream)"', script)
        self.assertNotIn('v6="$(ip6tables-save | hash_stream)"', script)

    def test_installed_package_identity_accepts_real_adb_crlf(self) -> None:
        package_dump = (
            "Packages:\r\n"
            "  Package [com.aneb.probe.codex] (d82eb88):\r\n"
            "    versionCode=45 minSdk=29 targetSdk=35\r\n"
            "    versionName=0.5.13-codex\r\n"
            "    splits=[base]\r\n"
        )

        self.assertEqual(
            (45, "0.5.13-codex"),
            collector.parse_installed_package_identity(package_dump),
        )

    def test_installed_package_identity_rejects_conflicting_history(self) -> None:
        package_dump = (
            "    versionCode=45 minSdk=29 targetSdk=35\n"
            "    versionName=0.5.13-codex\n"
            "    versionCode=44 minSdk=29 targetSdk=35\n"
            "    versionName=0.5.12-codex\n"
        )

        with self.assertRaisesRegex(
            collector.CollectorError,
            "installed_package_identity_invalid",
        ):
            collector.parse_installed_package_identity(package_dump)

    def test_run_as_shell_script_is_one_remote_quoted_argument(self) -> None:
        script = (
            'if [ -r "databases/aneb-probe.db" ]; then printf present; '
            "else printf absent; fi"
        )

        self.assertEqual(
            [
                "shell",
                "run-as",
                "com.aneb.probe.codex",
                "sh",
                "-c",
                "'" + script + "'",
            ],
            collector.run_as_shell_tail(script),
        )

    def test_run_as_shell_script_rejects_multiline_or_nul(self) -> None:
        for script in ("", "printf ok\n", "printf ok\r", "printf\x00ok"):
            with self.subTest(script=repr(script)):
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "run_as_shell_script_invalid",
                ):
                    collector.run_as_shell_tail(script)

    def test_strict_json_line_accepts_one_windows_crlf_record(self) -> None:
        self.assertEqual(
            {"reason_code": "ok", "status": "pass"},
            collector._strict_json_line(
                b'{"reason_code":"ok","status":"pass"}\r\n',
                code="verifier_output_invalid",
            ),
        )

    def test_json_verifier_report_is_persisted_as_canonical_lf_json(self) -> None:
        class WindowsRunner:
            def run(
                self,
                arguments: list[str],
                *,
                timeout_seconds: float,
                max_output_bytes: int,
                stdin: bytes | None = None,
            ) -> collector.ProcessResult:
                del arguments, timeout_seconds, max_output_bytes, stdin
                return collector.ProcessResult(
                    returncode=0,
                    stdout=b'{"reason_code":"ok","status":"pass"}\r\n',
                    stderr=b"",
                )

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "report.json"
            report = collector._run_json_verifier(
                runner=WindowsRunner(),
                arguments=["verifier"],
                output=output,
                code="test_verifier",
                timeout_seconds=5,
            )

            self.assertEqual({"reason_code": "ok", "status": "pass"}, report)
            self.assertEqual(
                b'{"reason_code":"ok","status":"pass"}\n',
                output.read_bytes(),
            )

    def test_strict_json_line_rejects_embedded_or_unterminated_records(self) -> None:
        invalid = (
            b'{"status":"pass"}',
            b'{"status":"pass"}\r',
            b'{"status":"pass"}\n\n',
            b'{"status":"pass"}\r\n\r\n',
            b'{"status":\r\n"pass"}\r\n',
            b' {"status":"pass"}\r\n',
        )
        for payload in invalid:
            with self.subTest(payload=payload):
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "verifier_output_invalid",
                ):
                    collector._strict_json_line(
                        payload,
                        code="verifier_output_invalid",
                    )

    def test_negative_proxy_rejects_request_timeout_outside_child_contract(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(
                collector.CollectorError,
                "negative_proxy_request_timeout_invalid",
            ):
                collector.NegativeProxyProcess(
                    python_path=Path("python"),
                    server_base="https://203.0.113.10:8443",
                    ca_path=Path("ca.pem"),
                    evidence_directory=Path(temporary),
                    request_timeout_seconds=900,
                )

    def test_negative_proxy_persists_child_startup_failure_evidence(self) -> None:
        class FailedProcess:
            def __init__(self) -> None:
                self.stdout = io.BytesIO(b"")
                self.stderr = io.BytesIO(
                    b'{"reason_code":"config_invalid","status":"fail"}\r\n'
                )

            @staticmethod
            def poll() -> int:
                return 2

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            process = collector.NegativeProxyProcess(
                python_path=Path("python"),
                server_base="https://203.0.113.10:8443",
                ca_path=Path("ca.pem"),
                evidence_directory=root,
                request_timeout_seconds=120,
            )
            with (
                mock.patch.object(
                    collector.subprocess,
                    "Popen",
                    return_value=FailedProcess(),
                ),
                self.assertRaisesRegex(
                    collector.CollectorError,
                    "negative_proxy_ready_invalid",
                ),
            ):
                process.start()

            self.assertEqual(b"", process.output_path.read_bytes())
            expected_stderr = (
                b'{"reason_code":"config_invalid","status":"fail"}\r\n'
            )
            self.assertEqual(expected_stderr, process.stderr_path.read_bytes())
            report = json.loads(process.startup_failure_path.read_text("utf-8"))
            self.assertEqual(2, report["returncode"])
            self.assertEqual(
                hashlib.sha256(expected_stderr).hexdigest(),
                report["stderr_sha256"],
            )

    def test_serverinfo_requires_081_realtime_receipt_and_exact_profile(self) -> None:
        body = self._valid_serverinfo()
        collector.validate_serverinfo(body)
        body["version"] = "aneb-server/0.8.0"
        with self.assertRaisesRegex(
            collector.CollectorError,
            "serverinfo_contract_invalid",
        ):
            collector.validate_serverinfo(body)

        body["version"] = "aneb-server/0.8.1"
        body["execution_capabilities"]["validated_profiles"][0][  # type: ignore[index]
            "profile_sha256"
        ] = "sha256:" + "f" * 64
        with self.assertRaisesRegex(
            collector.CollectorError,
            "serverinfo_contract_invalid",
        ):
            collector.validate_serverinfo(body)

    @staticmethod
    def _valid_serverinfo() -> dict[str, object]:
        return {
            "version": "aneb-server/0.8.1",
            "srv_ts_us": 123456,
            "anchor_wall_unix_ns": 123456789,
            "uptime_s": 123,
            "goos": "linux",
            "goarch": "amd64",
            "h3_enabled": True,
            "tcp_slow_start_after_idle": "0",
            "congestion_control": "cubic",
            "execution_capabilities": {
                "contract_id": "aneb-server-capability-receipt",
                "contract_version": "1.0.0",
                "primitives": [
                    {
                        "primitive_id": "download",
                        "wire_contract_id": "aneb-download-v1",
                    },
                    {
                        "primitive_id": "echo",
                        "wire_contract_id": "aneb-echo-v1",
                    },
                    {
                        "primitive_id": "realtime_sim",
                        "wire_contract_id": "aneb-realtime-session-v1",
                    },
                    {
                        "primitive_id": "token_sim",
                        "wire_contract_id": "aneb-token-task-v1",
                    },
                ],
                "validated_profiles": [
                    {
                        "profile_id": "ai_realtime_voice_quick",
                        "profile_version": "1.1.1",
                        "profile_sha256": "sha256:"
                        + collector.REALTIME_PROFILE_SHA256,
                    },
                    {
                        "profile_id": "token_multimodal_quick",
                        "profile_version": "1.2.1",
                        "profile_sha256": "sha256:"
                        + collector.TOKEN_PROFILE_SHA256,
                    },
                ],
            },
        }

    def test_http_capture_rejects_duplicate_json_keys_and_wrong_content_type(self) -> None:
        body = self._valid_serverinfo()
        raw = json.dumps(body, separators=(",", ":")).encode("utf-8")
        capture = collector.validate_http_capture(
            status=200,
            headers=(("Content-Type", "application/json"),),
            body=raw,
        )
        self.assertEqual(body, capture.json_body)

        for headers, payload in (
            ((("Content-Type", "text/html"),), raw),
            (
                (("Content-Type", "application/json"),),
                b'{"version":"a","version":"b"}',
            ),
        ):
            with self.subTest(headers=headers, payload=payload):
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "http_capture_invalid",
                ):
                    collector.validate_http_capture(
                        status=200,
                        headers=headers,
                        body=payload,
                    )

    def test_serverinfo_sequence_requires_same_anchor_and_strict_chronology(self) -> None:
        identity = self._valid_serverinfo()
        start = json.loads(json.dumps(identity))
        end = json.loads(json.dumps(identity))
        start["srv_ts_us"] = 123457
        end["srv_ts_us"] = 123458
        start["uptime_s"] = 124
        end["uptime_s"] = 124
        collector.assert_serverinfo_sequence(identity, start, end)

        end["anchor_wall_unix_ns"] = 999
        with self.assertRaisesRegex(
            collector.CollectorError,
            "serverinfo_sequence_invalid",
        ):
            collector.assert_serverinfo_sequence(identity, start, end)


class EvidencePublicationTests(unittest.TestCase):
    def test_staging_refuses_insecure_evidence_root_before_partial_creation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            partial = root / "run.partial"
            failure = collector.evidence_security.EvidenceSecurityFailure(
                "evidence_root_acl_too_permissive"
            )
            with mock.patch.object(
                collector.evidence_security,
                "verify_root",
                side_effect=failure,
            ):
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "evidence_root_acl_too_permissive",
                ):
                    collector.prepare_evidence_staging(root, partial)

            self.assertFalse(partial.exists())

    def test_staging_binds_private_root_report_into_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            partial = root / "run.partial"
            report = {
                "schema": "aneb-realtime-private-evidence-root-security",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
            }
            with mock.patch.object(
                collector.evidence_security,
                "verify_root",
                return_value=report,
            ) as verify:
                collector.prepare_evidence_staging(root, partial)

            verify.assert_called_once_with(root)
            self.assertEqual(
                report,
                json.loads(
                    (partial / "evidence-root-security.json").read_text(
                        encoding="utf-8"
                    )
                ),
            )

    def backend_for_publication(
        self,
        root: Path,
    ) -> collector.LiveCollectorBackend:
        placeholder = root / "placeholder"
        config = collector.CollectorConfig(
            adb_serial="SERIAL",
            server_base="https://203.0.113.10:8443",
            remote="root@203.0.113.10",
            ssh_key=placeholder,
            known_hosts=placeholder,
            device_policy=placeholder,
            candidate_directory=root,
            gh_path=placeholder,
            expected_server_binary_sha256="a" * 64,
            evidence_mode="positive",
            transport="wifi",
            evidence_root=root,
            adb_path=placeholder,
            ssh_path=placeholder,
            python_path=placeholder,
            server_ca_path=placeholder,
            source_commit="b" * 40,
            run_timeout_seconds=900,
            lock_ttl_seconds=1800,
            command_timeout_seconds=120,
        )
        backend = collector.LiveCollectorBackend(
            config,
            install_candidate=True,
        )
        backend.partial.mkdir()
        (backend.partial / "payload.txt").write_bytes(b"evidence\n")
        backend.run_markers = collector.RealtimeTerminalMarkers(
            run_id=RUN_ID,
            contract_status="authorized",
            terminal_status="completed",
            reason_code=None,
        )
        backend.cleanup_phone_complete = True
        backend.cleanup_remote_complete = True
        return backend

    def test_install_signature_mismatch_is_persisted_and_machine_readable(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"candidate-apk\n"
            apk = root / "ANEB-Probe-0.5.13-codex-debug.apk"
            apk.write_bytes(payload)

            class FailedInstallRunner:
                def run(
                    self,
                    arguments: list[str],
                    *,
                    timeout_seconds: float,
                    max_output_bytes: int = collector.MAX_COMMAND_OUTPUT_BYTES,
                    stdin: bytes | None = None,
                ) -> collector.ProcessResult:
                    del timeout_seconds, max_output_bytes, stdin
                    self.arguments = arguments
                    return collector.ProcessResult(
                        returncode=1,
                        stdout=(
                            b"Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: "
                            b"Package com.aneb.probe.codex signatures do not match]\r\n"
                        ),
                        stderr=b"",
                    )

            runner = FailedInstallRunner()
            adb = collector.AdbClient(
                runner=runner,
                executable=Path("adb"),
                serial="SERIAL",
                timeout_seconds=30,
            )
            identity = collector.CiCandidateIdentity(
                apk_file_name=apk.name,
                apk_sha256=hashlib.sha256(payload).hexdigest(),
                apk_size_bytes=len(payload),
                signer_sha256="a" * 64,
                workflow_run_id=1,
                workflow_run_url=(
                    "https://github.com/lucassu2012/ANEB_GPT/actions/runs/1"
                ),
            )

            with self.assertRaisesRegex(
                collector.CollectorError,
                "adb_install_candidate_signature_mismatch",
            ):
                collector.verify_or_install_candidate(
                    adb,
                    candidate_directory=root,
                    identity=identity,
                    evidence_directory=root,
                    install=True,
                )

            self.assertEqual(
                (
                    b"Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: "
                    b"Package com.aneb.probe.codex signatures do not match]\n"
                ),
                (root / "adb-install.txt").read_bytes(),
            )

    def test_stop_target_accepts_huawei_empty_service_dump(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self.backend_for_publication(Path(temporary))
            backend.app_launch_attempted = True

            class FakeAdb:
                def text(
                    self,
                    tail: list[str],
                    *,
                    code: str,
                    allowed_returncodes: frozenset[int] = frozenset({0}),
                ) -> str:
                    del code, allowed_returncodes
                    if tail == ["shell", "am", "force-stop", collector.PACKAGE_NAME]:
                        return ""
                    if tail == ["shell", "pidof", collector.PACKAGE_NAME]:
                        return ""
                    if tail == [
                        "shell",
                        "dumpsys",
                        "activity",
                        "services",
                        collector.PACKAGE_NAME,
                    ]:
                        return (
                            "ACTIVITY MANAGER SERVICES "
                            "(dumpsys activity services)\n  (nothing)"
                        )
                    raise AssertionError(tail)

            backend.adb = FakeAdb()
            backend._stop_target()

            self.assertFalse(backend.app_launch_attempted)

    def test_stop_target_rejects_unknown_service_dump(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self.backend_for_publication(Path(temporary))
            backend.app_launch_attempted = True

            class FakeAdb:
                def text(
                    self,
                    tail: list[str],
                    *,
                    code: str,
                    allowed_returncodes: frozenset[int] = frozenset({0}),
                ) -> str:
                    del code, allowed_returncodes
                    if tail == ["shell", "am", "force-stop", collector.PACKAGE_NAME]:
                        return ""
                    if tail == ["shell", "pidof", collector.PACKAGE_NAME]:
                        return ""
                    if tail == [
                        "shell",
                        "dumpsys",
                        "activity",
                        "services",
                        collector.PACKAGE_NAME,
                    ]:
                        return (
                            "ACTIVITY MANAGER SERVICES "
                            "(dumpsys activity services)\nwarning: incomplete"
                        )
                    raise AssertionError(tail)

            backend.adb = FakeAdb()
            with self.assertRaisesRegex(
                collector.CollectorError,
                "target_app_not_stopped",
            ):
                backend._stop_target()

            self.assertTrue(backend.app_launch_attempted)

    def test_manifest_is_canonical_and_excludes_itself_and_complete_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.txt").write_bytes(b"a\n")
            (root / "nested").mkdir()
            (root / "nested" / "b.bin").write_bytes(b"\x00\x01")

            manifest = collector.write_evidence_manifest(root)
            parsed = json.loads(manifest.read_text(encoding="utf-8"))

        self.assertEqual("aneb-realtime-quick-evidence-manifest", parsed["schema"])
        self.assertEqual(["a.txt", "nested/b.bin"], [item["path"] for item in parsed["files"]])
        self.assertEqual(
            hashlib.sha256(b"a\n").hexdigest(),
            parsed["files"][0]["sha256"],
        )
        self.assertNotIn("evidence-manifest.json", [item["path"] for item in parsed["files"]])

    def test_atomic_publish_refuses_existing_target_or_failed_status(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            partial = root / "run.partial"
            complete = root / "run.complete"
            partial.mkdir()
            (partial / "collector-status.json").write_text(
                json.dumps({"status": "fail"}) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(collector.CollectorError, "publish_not_ready"):
                collector.atomic_publish(partial, complete)

            (partial / "collector-status.json").write_text(
                json.dumps({"status": "pass"}) + "\n",
                encoding="utf-8",
            )
            complete.mkdir()
            with self.assertRaisesRegex(collector.CollectorError, "publish_target_exists"):
                collector.atomic_publish(partial, complete)

    def test_atomic_publish_moves_only_a_complete_pass_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            partial = root / "run.partial"
            complete = root / "run.complete"
            partial.mkdir()
            (partial / "collector-status.json").write_bytes(
                collector._canonical_json_bytes({"status": "pass"})
            )
            (partial / "payload.txt").write_bytes(b"evidence\n")
            collector.write_evidence_manifest(partial)
            (partial / "COMPLETE").write_text("complete\n", encoding="utf-8")

            collector.atomic_publish(partial, complete)

            self.assertFalse(partial.exists())
            self.assertTrue((complete / "COMPLETE").is_file())
            self.assertTrue((complete / "evidence-manifest.json").is_file())

    def test_atomic_publish_revalidates_manifest_before_move(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            partial = root / "run.partial"
            complete = root / "run.complete"
            partial.mkdir()
            (partial / "collector-status.json").write_bytes(
                collector._canonical_json_bytes({"status": "pass"})
            )
            payload = partial / "payload.txt"
            payload.write_bytes(b"before\n")
            collector.write_evidence_manifest(partial)
            (partial / "COMPLETE").write_text("complete\n", encoding="utf-8")
            payload.write_bytes(b"after\n")

            with self.assertRaisesRegex(
                collector.CollectorError,
                "evidence_manifest_invalid",
            ):
                collector.atomic_publish(partial, complete)

            self.assertTrue(partial.exists())
            self.assertFalse(complete.exists())

    def test_prepublish_gate_requires_independent_collection_pass(self) -> None:
        partial = Path("m0-ec2-realtime-20260725T120000Z-" + "a" * 32 + ".partial")
        collection_id = partial.name.removesuffix(".partial")
        with mock.patch.object(
            collector.collection_verifier,
            "verify_collection",
            return_value={
                "status": "pass",
                "collection_id": collection_id,
            },
        ) as verify:
            report = collector.verify_before_atomic_publish(
                partial,
                collection_id=collection_id,
            )

        self.assertEqual("pass", report["status"])
        verify.assert_called_once_with(
            partial,
            expected_collection=collection_id,
            allow_partial=True,
        )

        failure = collector.collection_verifier.CollectionVerificationFailure(
            "remote_snapshot_drift"
        )
        with mock.patch.object(
            collector.collection_verifier,
            "verify_collection",
            side_effect=failure,
        ):
            with self.assertRaisesRegex(
                collector.CollectorError,
                "prepublish_collection_verification_failed "
                "reason=remote_snapshot_drift",
            ):
                collector.verify_before_atomic_publish(
                    partial,
                    collection_id=collection_id,
                )

    def test_live_publish_requires_ready_and_final_consumer_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = self.backend_for_publication(root)
            expected_ready = root / f"{backend.collection_id}.READY.json"

            def publish_ready(_: Path) -> dict[str, object]:
                (root / f"{backend.collection_id}.verification.json").write_bytes(
                    b"verified\n"
                )
                expected_ready.write_bytes(b"ready\n")
                return {
                    "status": "pass",
                    "collection_id": backend.collection_id,
                    "run_id": RUN_ID,
                    "mode": "positive",
                    "ready_path": str(expected_ready),
                }

            with (
                mock.patch.object(
                    collector,
                    "verify_before_atomic_publish",
                    return_value={"status": "pass"},
                ),
                mock.patch.object(
                    collector.ready_publisher,
                    "publish_ready",
                    side_effect=publish_ready,
                ),
                mock.patch.object(
                    collector.release_verifier,
                    "verify_release",
                    return_value={
                        "status": "pass",
                        "collection_id": backend.collection_id,
                        "run_id": RUN_ID,
                        "mode": "positive",
                    },
                ),
            ):
                backend.publish()

            self.assertTrue(backend.complete.is_dir())
            self.assertEqual(expected_ready, backend.ready_path)
            self.assertTrue(expected_ready.is_file())

    def test_ready_failure_demotes_complete_and_removes_complete_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = self.backend_for_publication(root)
            with (
                mock.patch.object(
                    collector,
                    "verify_before_atomic_publish",
                    return_value={"status": "pass"},
                ),
                mock.patch.object(
                    collector.ready_publisher,
                    "publish_ready",
                    side_effect=collector.ready_publisher.ReadyPublicationFailure(
                        "injected_failure"
                    ),
                ),
            ):
                with self.assertRaisesRegex(
                    collector.CollectorError,
                    "ready_publication_failed injected_failure",
                ):
                    backend.publish()

            self.assertFalse(backend.complete.exists())
            self.assertEqual(
                root
                / f"{backend.collection_id}.verification-failed.partial",
                backend.partial,
            )
            self.assertTrue(backend.partial.is_dir())
            self.assertFalse((backend.partial / "COMPLETE").exists())

    def test_ready_collision_is_never_deleted_or_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = self.backend_for_publication(root)
            collision = root / f"{backend.collection_id}.READY.json"
            collision.write_bytes(b"unknown-owner\n")

            with self.assertRaisesRegex(
                collector.CollectorError,
                "ready_publication_path_collision",
            ):
                backend.publish()

            self.assertEqual(b"unknown-owner\n", collision.read_bytes())
            self.assertTrue(backend.partial.is_dir())
            self.assertFalse(backend.complete.exists())

    def test_negative_reverse_evidence_matches_cross_verifier_file_contract(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self.backend_for_publication(Path(temporary))
            mapping = "UsbFfs tcp:18765 tcp:18765"

            class FakeAdb:
                def __init__(self) -> None:
                    self.inventories = ["", mapping, mapping, ""]

                def text(self, tail: list[str], *, code: str) -> str:
                    del code
                    if tail == ["reverse", "--list"]:
                        return self.inventories.pop(0)
                    if tail in (
                        [
                            "reverse",
                            "--no-rebind",
                            "tcp:18765",
                            "tcp:18765",
                        ],
                        ["reverse", "--remove", "tcp:18765"],
                    ):
                        return ""
                    raise AssertionError(tail)

            backend.adb = FakeAdb()
            proxy = SimpleNamespace(start=lambda: None)
            with mock.patch.object(
                collector,
                "NegativeProxyProcess",
                return_value=proxy,
            ) as proxy_factory:
                backend._start_negative_proxy()
            self.assertEqual(
                backend.config.command_timeout_seconds,
                proxy_factory.call_args.kwargs["request_timeout_seconds"],
            )
            backend._remove_reverse()

            expected = {
                "adb-reverse-preflight.txt": b"\n",
                "adb-reverse-active.txt": (mapping + "\n").encode("ascii"),
                "adb-reverse-before-remove.txt": (mapping + "\n").encode(
                    "ascii"
                ),
                "adb-reverse-final.txt": b"\n",
            }
            self.assertEqual(
                expected,
                {
                    leaf: (backend.partial / leaf).read_bytes()
                    for leaf in expected
                },
            )
            self.assertFalse(backend.reverse_owned)

    def test_negative_reverse_refuses_transport_label_drift_before_remove(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self.backend_for_publication(Path(temporary))
            active = "UsbFfs tcp:18765 tcp:18765"
            drifted = "UsbFfs2 tcp:18765 tcp:18765"

            class FakeAdb:
                def __init__(self) -> None:
                    self.inventories = ["", active, drifted]

                def text(self, tail: list[str], *, code: str) -> str:
                    del code
                    if tail == ["reverse", "--list"]:
                        return self.inventories.pop(0)
                    if tail == [
                        "reverse",
                        "--no-rebind",
                        "tcp:18765",
                        "tcp:18765",
                    ]:
                        return ""
                    if tail == ["reverse", "--remove", "tcp:18765"]:
                        raise AssertionError("drifted mapping must not be removed")
                    raise AssertionError(tail)

            backend.adb = FakeAdb()
            with mock.patch.object(
                collector,
                "NegativeProxyProcess",
                return_value=SimpleNamespace(start=lambda: None),
            ):
                backend._start_negative_proxy()
            with self.assertRaisesRegex(
                collector.CollectorError,
                "negative_reverse_inventory_polluted",
            ):
                backend._remove_reverse()

    def test_negative_proxy_refuses_any_preexisting_reverse_session(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self.backend_for_publication(Path(temporary))

            class FakeAdb:
                def text(self, tail: list[str], *, code: str) -> str:
                    del code
                    if tail == ["reverse", "--list"]:
                        return "SERIAL tcp:12345 tcp:54321"
                    raise AssertionError(tail)

            backend.adb = FakeAdb()
            with (
                mock.patch.object(
                    collector,
                    "NegativeProxyProcess",
                ) as proxy,
                self.assertRaisesRegex(
                    collector.CollectorError,
                    "negative_reverse_inventory_not_empty",
                ),
            ):
                backend._start_negative_proxy()
            proxy.assert_not_called()


class WorkflowTests(unittest.TestCase):
    def test_partial_acquire_failure_still_runs_both_idempotent_cleanups(self) -> None:
        events: list[str] = []

        class Backend:
            def preflight(self) -> None:
                events.append("preflight")

            def acquire(self) -> None:
                events.append("acquire")
                raise collector.CollectorError("install_failed_after_lock")

            def collect(self) -> None:
                events.append("collect")

            def cleanup_phone(self) -> None:
                events.append("cleanup_phone")

            def cleanup_remote(self) -> None:
                events.append("cleanup_remote")

            def publish(self) -> None:
                events.append("publish")

        result = collector.run_workflow(Backend())

        self.assertFalse(result.success)
        self.assertEqual(
            ["preflight", "acquire", "cleanup_phone", "cleanup_remote"],
            events,
        )

    def test_primary_failure_still_runs_all_cleanup_and_never_publishes(self) -> None:
        events: list[str] = []

        class Backend:
            def preflight(self) -> None:
                events.append("preflight")

            def acquire(self) -> None:
                events.append("acquire")

            def collect(self) -> None:
                events.append("collect")
                raise collector.CollectorError("business_failed")

            def cleanup_phone(self) -> None:
                events.append("cleanup_phone")

            def cleanup_remote(self) -> None:
                events.append("cleanup_remote")

            def publish(self) -> None:
                events.append("publish")

        result = collector.run_workflow(Backend())

        self.assertFalse(result.success)
        self.assertEqual(
            [
                "preflight",
                "acquire",
                "collect",
                "cleanup_phone",
                "cleanup_remote",
            ],
            events,
        )
        self.assertEqual("business_failed", result.primary_failure)

    def test_cleanup_failure_suppresses_publish_even_after_collection_success(self) -> None:
        events: list[str] = []

        class Backend:
            def preflight(self) -> None:
                events.append("preflight")

            def acquire(self) -> None:
                events.append("acquire")

            def collect(self) -> None:
                events.append("collect")

            def cleanup_phone(self) -> None:
                events.append("cleanup_phone")
                raise collector.CollectorError("phone_cleanup_failed")

            def cleanup_remote(self) -> None:
                events.append("cleanup_remote")

            def publish(self) -> None:
                events.append("publish")

        result = collector.run_workflow(Backend())

        self.assertFalse(result.success)
        self.assertEqual(("phone_cleanup_failed",), result.cleanup_failures)
        self.assertNotIn("publish", events)

    def test_success_publishes_only_after_both_cleanups(self) -> None:
        events: list[str] = []

        class Backend:
            def preflight(self) -> None:
                events.append("preflight")

            def acquire(self) -> None:
                events.append("acquire")

            def collect(self) -> None:
                events.append("collect")

            def cleanup_phone(self) -> None:
                events.append("cleanup_phone")

            def cleanup_remote(self) -> None:
                events.append("cleanup_remote")

            def publish(self) -> None:
                events.append("publish")

        result = collector.run_workflow(Backend())

        self.assertTrue(result.success)
        self.assertEqual(
            [
                "preflight",
                "acquire",
                "collect",
                "cleanup_phone",
                "cleanup_remote",
                "publish",
            ],
            events,
        )


class IdentifierTests(unittest.TestCase):
    def test_uuid7_is_required_for_business_run(self) -> None:
        collector.validate_run_id(RUN_ID)
        with self.assertRaisesRegex(collector.CollectorError, "run_id_invalid"):
            collector.validate_run_id(str(uuid.uuid4()))


if __name__ == "__main__":
    unittest.main()
