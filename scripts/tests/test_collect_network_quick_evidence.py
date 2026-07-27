from __future__ import annotations

import json
import unittest
from pathlib import Path
import tempfile
from unittest import mock

from scripts import collect_realtime_quick_evidence as mechanics
from scripts.collect_network_quick_evidence import (
    CollectorError,
    NetworkTerminalMarkers,
    NetworkLiveCollectorBackend,
    PROFILE_CONTRACT,
    assert_network_serverinfo_sequence,
    bind_network_verifier_reports,
    build_network_audit_headers,
    build_network_launch_arguments,
    parse_network_terminal_markers,
    run_network_verifiers,
    validate_network_serverinfo,
    verify_collected_network_evidence,
)


RUN_ID = "019fa111-1111-7111-8111-111111111111"


class NetworkQuickCollectorContractTest(unittest.TestCase):
    @staticmethod
    def network_serverinfo() -> dict[str, object]:
        return {
            "version": "aneb-server/0.8.2",
            "srv_ts_us": 1_000_000,
            "anchor_wall_unix_ns": 2_000_000_000,
            "uptime_s": 100,
            "goos": "linux",
            "goarch": "amd64",
            "h3_enabled": True,
            "tcp_slow_start_after_idle": "0",
            "congestion_control": "cubic",
            "execution_capabilities": {
                "contract_id": "aneb-server-capability-receipt",
                "contract_version": "1.0.0",
                "primitives": [
                    {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"},
                    {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
                    {"primitive_id": "realtime_sim", "wire_contract_id": "aneb-realtime-session-v1"},
                    {"primitive_id": "token_sim", "wire_contract_id": "aneb-token-task-v1"},
                    {"primitive_id": "udp_echo", "wire_contract_id": "aneb-udp-echo-v2"},
                    {"primitive_id": "upload", "wire_contract_id": "aneb-upload-v1"},
                ],
                "validated_profiles": [
                    {
                        "profile_id": "ai_realtime_voice_quick",
                        "profile_version": "1.1.1",
                        "profile_sha256": "sha256:701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7",
                    },
                    {
                        "profile_id": "network_comprehensive_quick",
                        "profile_version": "1.2.0",
                        "profile_sha256": "sha256:15ae5187fac72d86b78ff89ad44d5a51706dc7c4e4cf01432f367acd9ed082cc",
                    },
                    {
                        "profile_id": "token_multimodal_quick",
                        "profile_version": "1.2.1",
                        "profile_sha256": "sha256:caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773",
                    },
                ],
            },
        }

    def test_network_serverinfo_requires_082_six_primitive_receipt(self) -> None:
        validate_network_serverinfo(self.network_serverinfo())

    def test_live_backend_uses_network_identity_and_blocks_realtime_publish(self) -> None:
        placeholder = Path("placeholder")
        config = mechanics.CollectorConfig(
            adb_serial="SERIAL",
            server_base="https://203.0.113.10:8443",
            remote="root@203.0.113.10",
            ssh_key=placeholder,
            known_hosts=placeholder,
            device_policy=placeholder,
            candidate_directory=placeholder,
            gh_path=placeholder,
            expected_server_binary_sha256="a" * 64,
            evidence_mode="positive",
            transport="wifi",
            evidence_root=placeholder,
            adb_path=placeholder,
            ssh_path=placeholder,
            python_path=placeholder,
            server_ca_path=placeholder,
            source_commit="b" * 40,
            run_timeout_seconds=900,
            lock_ttl_seconds=1800,
            command_timeout_seconds=120,
        )
        backend = NetworkLiveCollectorBackend(
            config,
            install_candidate=True,
            runner=mock.Mock(),
        )

        self.assertTrue(backend.collection_id.startswith("m0-ec3-network-quick-"))
        self.assertEqual("aneb-server/0.8.2", backend.contract.expected_server_version)
        self.assertEqual(
            ["--es", "test_mode", "network_basic"],
            backend._build_launch_arguments()[-3:],
        )
        with self.assertRaisesRegex(
            CollectorError,
            "network_collection_verifier_not_implemented",
        ):
            backend._verify_before_atomic_publish()

    def test_network_serverinfo_sequence_is_stable_and_chronological(self) -> None:
        identity = self.network_serverinfo()
        start = json.loads(json.dumps(identity))
        end = json.loads(json.dumps(identity))
        start["srv_ts_us"] = 2_000_000
        start["uptime_s"] = 101
        end["srv_ts_us"] = 3_000_000
        end["uptime_s"] = 102

        assert_network_serverinfo_sequence(identity, start, end)

        end["version"] = "aneb-server/0.8.1"
        with self.assertRaisesRegex(
            CollectorError,
            "network_serverinfo_sequence_invalid",
        ):
            assert_network_serverinfo_sequence(identity, start, end)

    def test_positive_terminal_chain_is_exact(self) -> None:
        markers = parse_network_terminal_markers(
            "\n".join(
                (
                    f"I/AnebProbe: NET_V1_START run_id={RUN_ID} variant=quick transport=wifi server=https://120.79.148.0:8443",
                    f"I/AnebProbe: NET_V1_PROFILE id=network_comprehensive_quick version=1.2.0 source=bundled score=network-comprehensive-score-v1",
                    f"I/AnebProbe: NET_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"I/AnebProbe: NET_V1_RESULT run_id={RUN_ID} status=completed score=98.1 grade=A verdict=PASS confidence=LOW",
                    f"I/AnebProbe: NET_V1_END run_id={RUN_ID} status=completed",
                )
            ),
            mode="positive",
        )
        self.assertEqual(RUN_ID, markers.run_id)
        self.assertEqual("authorized", markers.contract_status)
        self.assertEqual("completed", markers.terminal_status)
        self.assertIsNone(markers.reason_code)

    def test_negative_receipt_missing_chain_is_exact(self) -> None:
        markers = parse_network_terminal_markers(
            "\n".join(
                (
                    f"NET_V1_START run_id={RUN_ID} variant=quick transport=wifi server=http://127.0.0.1:18765",
                    f"NET_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"NET_V1_CONTRACT run_id={RUN_ID} status=rejected reason=receipt_missing detail=missing",
                    f"NET_V1_END run_id={RUN_ID} status=contract_rejected",
                )
            ),
            mode="negative",
        )
        self.assertEqual("rejected", markers.contract_status)
        self.assertEqual("contract_rejected", markers.terminal_status)
        self.assertEqual("receipt_missing", markers.reason_code)

    def test_positive_rejects_contract_rejection_or_duplicate_result(self) -> None:
        text = "\n".join(
            (
                f"NET_V1_START run_id={RUN_ID} variant=quick transport=wifi server=https://120.79.148.0:8443",
                f"NET_V1_PROFILE id=network_comprehensive_quick version=1.2.0 source=bundled score=network-comprehensive-score-v1",
                f"NET_V1_DB_WRITE run_id={RUN_ID} ok=true",
                f"NET_V1_RESULT run_id={RUN_ID} status=completed score=98 grade=A verdict=PASS confidence=LOW",
                f"NET_V1_RESULT run_id={RUN_ID} status=completed score=98 grade=A verdict=PASS confidence=LOW",
                f"NET_V1_END run_id={RUN_ID} status=completed",
            )
        )
        with self.assertRaisesRegex(CollectorError, "network_marker_chain_invalid"):
            parse_network_terminal_markers(text, mode="positive")

    def test_network_launch_is_bounded_to_quick_network_mode(self) -> None:
        command = build_network_launch_arguments(
            serial="8MY0221126002537",
            server_base="https://120.79.148.0:8443",
            transport="wifi",
            adb_path="adb",
        )
        self.assertEqual(["--es", "mode", "quick"], command[-9:-6])
        self.assertEqual(["--es", "transport", "wifi"], command[-6:-3])
        self.assertEqual(["--es", "test_mode", "network_basic"], command[-3:])

    def test_negative_loopback_is_the_only_http_target(self) -> None:
        build_network_launch_arguments(
            serial="8MY0221126002537",
            server_base="http://127.0.0.1:18765",
            transport="auto",
        )
        with self.assertRaisesRegex(CollectorError, "server_base_invalid"):
            build_network_launch_arguments(
                serial="8MY0221126002537",
                server_base="http://example.com:18765",
                transport="auto",
            )

    def test_audit_headers_use_network_scope(self) -> None:
        headers = build_network_audit_headers(
            run_id="11111111-1111-4111-8111-111111111111",
            role="window_start",
        )
        self.assertEqual("network_run", headers["X-Aneb-Audit-Scope"])
        self.assertEqual("window_start", headers["X-Aneb-Audit-Role"])

    def test_positive_verifier_reports_are_cross_bound(self) -> None:
        markers = parse_network_terminal_markers(
            "\n".join(
                (
                    f"NET_V1_START run_id={RUN_ID}",
                    "NET_V1_PROFILE id=network_comprehensive_quick version=1.2.0",
                    f"NET_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"NET_V1_RESULT run_id={RUN_ID} status=completed",
                    f"NET_V1_END run_id={RUN_ID} status=completed",
                )
            ),
            mode="positive",
        )
        report = bind_network_verifier_reports(
            markers=markers,
            mode="positive",
            client_report={
                "schema": "aneb-network-quick-client-db-verification",
                "schema_version": "1.0.0",
                "status": "pass",
                "mode": "positive",
                "run_id": RUN_ID,
                "reason_code": None,
            },
            audit_report={
                "schema": "aneb-network-request-entry-audit-report",
                "schema_version": "1.0.0",
                "status": "pass",
                "mode": "positive",
                "run_id": RUN_ID,
                "profile_contract": PROFILE_CONTRACT,
                "reason_code": "ok",
                "counts": {"business_total": 55},
            },
        )
        self.assertEqual("pass", report["status"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertEqual("authorized", report["contract_status"])

    def test_negative_verifier_reports_require_zero_server_business(self) -> None:
        markers = parse_network_terminal_markers(
            "\n".join(
                (
                    f"NET_V1_START run_id={RUN_ID}",
                    f"NET_V1_CONTRACT run_id={RUN_ID} status=rejected reason=receipt_missing detail=missing",
                    f"NET_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"NET_V1_END run_id={RUN_ID} status=contract_rejected",
                )
            ),
            mode="negative",
        )
        client = {
            "schema": "aneb-network-quick-client-db-verification",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": "negative",
            "run_id": RUN_ID,
            "reason_code": "receipt_missing",
        }
        audit = {
            "schema": "aneb-network-request-entry-audit-report",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": "negative",
            "run_id": RUN_ID,
            "profile_contract": PROFILE_CONTRACT,
            "reason_code": "ok",
            "counts": {"business_total": 0},
        }
        report = bind_network_verifier_reports(
            markers=markers,
            mode="negative",
            client_report=client,
            audit_report=audit,
        )
        self.assertEqual("receipt_missing", report["reason_code"])
        bad_audit = dict(audit)
        bad_audit["counts"] = {"business_total": 1}
        with self.assertRaisesRegex(CollectorError, "network_verifier_binding_invalid"):
            bind_network_verifier_reports(
                markers=markers,
                mode="negative",
                client_report=client,
                audit_report=bad_audit,
            )

    def test_verifier_reports_reject_failed_or_cross_run_evidence(self) -> None:
        markers = parse_network_terminal_markers(
            "\n".join(
                (
                    f"NET_V1_START run_id={RUN_ID}",
                    "NET_V1_PROFILE id=network_comprehensive_quick version=1.2.0",
                    f"NET_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"NET_V1_RESULT run_id={RUN_ID} status=completed",
                    f"NET_V1_END run_id={RUN_ID} status=completed",
                )
            ),
            mode="positive",
        )
        with self.assertRaisesRegex(CollectorError, "network_verifier_binding_invalid"):
            bind_network_verifier_reports(
                markers=markers,
                mode="positive",
                client_report={
                    "schema": "aneb-network-quick-client-db-verification",
                    "schema_version": "1.0.0",
                    "status": "fail",
                    "mode": "positive",
                    "run_id": RUN_ID,
                    "reason_code": None,
                },
                audit_report={
                    "schema": "aneb-network-request-entry-audit-report",
                    "schema_version": "1.0.0",
                    "status": "pass",
                    "mode": "positive",
                    "run_id": "019fa111-1111-7111-8111-111111111112",
                    "profile_contract": PROFILE_CONTRACT,
                    "reason_code": "ok",
                    "counts": {"business_total": 55},
                },
            )

    @mock.patch("scripts.collect_network_quick_evidence.verify_journal")
    @mock.patch("scripts.collect_network_quick_evidence.verify_database")
    def test_network_verifier_runner_persists_each_independent_report(
        self,
        client_verifier: mock.Mock,
        audit_verifier: mock.Mock,
    ) -> None:
        client_verifier.return_value = {
            "schema": "aneb-network-quick-client-db-verification",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": "positive",
            "run_id": RUN_ID,
            "reason_code": None,
        }
        audit_verifier.return_value = {
            "schema": "aneb-network-request-entry-audit-report",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": "positive",
            "run_id": RUN_ID,
            "profile_contract": PROFILE_CONTRACT,
            "reason_code": "ok",
            "counts": {"business_total": 74},
        }
        markers = NetworkTerminalMarkers(
            run_id=RUN_ID,
            contract_status="authorized",
            terminal_status="completed",
            reason_code=None,
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            journal = root / "journal.raw.log"
            journal.write_text("frozen\n", encoding="utf-8")
            report = run_network_verifiers(
                evidence_directory=root,
                markers=markers,
                mode="positive",
                database=root / "aneb-probe.db",
                journal_path=journal,
                profile_path=root / "profile.json",
                runtime_path=root / "runtime.json",
                manifest_path=root / "manifest.sha256",
                expected_server_base="https://120.79.148.0:8443",
                start_barrier_id="11111111-1111-4111-8111-111111111111",
                end_barrier_id="22222222-2222-4222-8222-222222222222",
            )

            self.assertEqual("pass", report["status"])
            for name in (
                "client-db-verification.json",
                "server-audit-verification.json",
                "cross-bound-report.json",
            ):
                self.assertTrue((root / name).is_file(), name)

    @mock.patch("scripts.collect_network_quick_evidence.verify_journal")
    @mock.patch("scripts.collect_network_quick_evidence.verify_database")
    def test_collected_evidence_runs_both_verifiers_then_binds(
        self,
        client_verifier: mock.Mock,
        audit_verifier: mock.Mock,
    ) -> None:
        client_verifier.return_value = {
            "schema": "aneb-network-quick-client-db-verification",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": "positive",
            "run_id": RUN_ID,
            "reason_code": None,
        }
        audit_verifier.return_value = {
            "schema": "aneb-network-request-entry-audit-report",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": "positive",
            "run_id": RUN_ID,
            "profile_contract": PROFILE_CONTRACT,
            "reason_code": "ok",
            "counts": {"business_total": 55},
        }
        report = verify_collected_network_evidence(
            logcat_text="\n".join(
                (
                    f"NET_V1_START run_id={RUN_ID}",
                    "NET_V1_PROFILE id=network_comprehensive_quick version=1.2.0",
                    f"NET_V1_DB_WRITE run_id={RUN_ID} ok=true",
                    f"NET_V1_RESULT run_id={RUN_ID} status=completed",
                    f"NET_V1_END run_id={RUN_ID} status=completed",
                )
            ),
            journal_text="frozen audit journal",
            database=Path("frozen.db"),
            run_id=RUN_ID,
            start_barrier_id="11111111-1111-4111-8111-111111111111",
            barrier_id="22222222-2222-4222-8222-222222222222",
            mode="positive",
            profile_path=Path("profile.json"),
            runtime_path=Path("runtime.json"),
            manifest_path=Path("manifest.sha256"),
            expected_server_base="https://120.79.148.0:8443",
        )
        self.assertEqual("pass", report["status"])
        client_verifier.assert_called_once()
        audit_verifier.assert_called_once_with(
            "frozen audit journal",
            run_id=RUN_ID,
            start_barrier_id="11111111-1111-4111-8111-111111111111",
            barrier_id="22222222-2222-4222-8222-222222222222",
            mode="positive",
            profile_contract=PROFILE_CONTRACT,
        )


if __name__ == "__main__":
    unittest.main()
