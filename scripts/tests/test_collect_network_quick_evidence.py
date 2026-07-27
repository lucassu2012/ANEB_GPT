from __future__ import annotations

import unittest
from pathlib import Path
from unittest import mock

from scripts.collect_network_quick_evidence import (
    CollectorError,
    PROFILE_CONTRACT,
    bind_network_verifier_reports,
    build_network_audit_headers,
    build_network_launch_arguments,
    parse_network_terminal_markers,
    verify_collected_network_evidence,
)


RUN_ID = "019fa111-1111-7111-8111-111111111111"


class NetworkQuickCollectorContractTest(unittest.TestCase):
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
