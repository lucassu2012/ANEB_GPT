from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import verify_network_quick_run_audit as verifier


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify_network_quick_run_audit.py"
RUN = "019fa000-1234-7abc-8def-0123456789ab"
START = "11111111-1111-4111-8111-111111111111"
END = "22222222-2222-4222-8222-222222222222"
FOREIGN = "33333333-3333-4333-8333-333333333333"
INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
PROFILE_CONTRACT = "network_comprehensive_quick@1.2.0"


class NetworkQuickRunAuditCliTest(unittest.TestCase):
    @staticmethod
    def audit(
        seq: int,
        *,
        class_: str = "control",
        method: str = "GET",
        path: str = "/api/v1/serverinfo",
        role: str = "capability",
        scope: str = "network_run",
        run_id: str = RUN,
        instance: str = INSTANCE,
        suffix: str = "",
    ) -> str:
        return (
            f"ANEB_REQUEST_AUDIT instance_id={instance} seq={seq} "
            f"class={class_} method={method} path={path} role={role} "
            f"scope={scope} run_id={run_id}{suffix}"
        )

    @staticmethod
    def event(method: str, path: str) -> dict[str, str]:
        return {
            "class_": "business",
            "method": method,
            "path": path,
            "role": "none",
        }

    def window(self, events: list[dict[str, str]]) -> str:
        lines = [self.audit(100, role="window_start", run_id=START)]
        lines.extend(
            self.audit(100 + offset, **event)
            for offset, event in enumerate(events, 1)
        )
        lines.append(
            self.audit(101 + len(events), role="window_end", run_id=END)
        )
        return "\n".join(lines) + "\n"

    def positive_journal(
        self,
        *,
        echo_count: int = 18,
        download_count: int = 4,
        upload_count: int = 2,
        udp_sequences: list[int] | None = None,
        udp_bytes: int = 256,
    ) -> str:
        sequences = list(range(50)) if udp_sequences is None else udp_sequences
        events = [
            {"role": "capability"},
            *[self.event("POST", "/api/v1/echo") for _ in range(echo_count)],
            *[self.event("GET", "/api/v1/download") for _ in range(download_count)],
            *[self.event("POST", "/api/v1/upload") for _ in range(upload_count)],
            *[
                {
                    **self.event("DATAGRAM", "/api/v1/udp-echo"),
                    "suffix": f" datagram_seq={sequence} datagram_bytes={udp_bytes}",
                }
                for sequence in sequences
            ],
        ]
        return self.window(events)

    def run_audit(
        self,
        journal: str,
        *,
        mode: str = "positive",
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "journal.log"
            path.write_text(journal, encoding="utf-8", newline="")
            return subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    str(path),
                    "--run-id",
                    RUN,
                    "--start-barrier-id",
                    START,
                    "--barrier-id",
                    END,
                    "--mode",
                    mode,
                    "--profile-contract",
                    PROFILE_CONTRACT,
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
                timeout=20,
            )

    def assert_reason(
        self,
        completed: subprocess.CompletedProcess[str],
        reason: str,
    ) -> dict[str, object]:
        self.assertEqual(1, completed.returncode, completed.stdout)
        report = json.loads(completed.stdout)
        self.assertEqual("fail", report["status"])
        self.assertEqual(reason, report["reason_code"])
        return report

    def test_positive_minimum_http_and_exact_udp_sequence_pass(self) -> None:
        completed = self.run_audit(self.positive_journal())

        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(
            {"download": 4, "echo": 18, "udp_echo": 50, "upload": 2},
            report["counts"]["business"],
        )
        self.assertEqual(list(range(50)), report["udp"]["sequences"])
        self.assertEqual(256, report["udp"]["packet_bytes"])
        self.assertRegex(
            report["profile_contract_definition_sha256"],
            r"^[0-9a-f]{64}$",
        )

    def test_negative_capability_only_window_passes(self) -> None:
        completed = self.run_audit(
            self.window([{"role": "capability"}]),
            mode="negative",
        )

        self.assertEqual(0, completed.returncode, completed.stdout)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual(0, report["counts"]["business_total"])
        self.assertEqual([], report["udp"]["sequences"])
        self.assertIsNone(report["udp"]["packet_bytes"])

    def test_target_business_outside_barriers_is_rejected(self) -> None:
        outside = self.audit(
            99,
            **self.event("POST", "/api/v1/echo"),
        )
        completed = self.run_audit(outside + "\n" + self.positive_journal())

        self.assert_reason(completed, "target_outside_window")

    def test_unexpected_target_control_is_rejected(self) -> None:
        lines = self.positive_journal().splitlines()
        lines.insert(
            2,
            self.audit(102, role="other"),
        )
        for index in range(3, len(lines)):
            lines[index] = re.sub(
                r" seq=([0-9]+) ",
                lambda match: f" seq={int(match.group(1)) + 1} ",
                lines[index],
                count=1,
            )
        completed = self.run_audit("\n".join(lines) + "\n")

        self.assert_reason(completed, "unexpected_target_control")

    def test_concurrent_window_is_rejected(self) -> None:
        lines = self.positive_journal().splitlines()
        lines.insert(
            2,
            self.audit(102, role="window_start", run_id=FOREIGN),
        )
        for index in range(3, len(lines)):
            lines[index] = re.sub(
                r" seq=([0-9]+) ",
                lambda match: f" seq={int(match.group(1)) + 1} ",
                lines[index],
                count=1,
            )
        completed = self.run_audit("\n".join(lines) + "\n")

        self.assert_reason(completed, "concurrent_window")

    def test_malformed_audit_identity_is_rejected_even_when_foreign(self) -> None:
        lines = self.positive_journal().splitlines()
        lines.insert(
            2,
            self.audit(
                102,
                scope="legacy_unscoped",
                run_id="secret-like-value",
            ),
        )
        for index in range(3, len(lines)):
            lines[index] = re.sub(
                r" seq=([0-9]+) ",
                lambda match: f" seq={int(match.group(1)) + 1} ",
                lines[index],
                count=1,
            )
        completed = self.run_audit("\n".join(lines) + "\n")

        self.assert_reason(completed, "audit_malformed")

    def test_profile_http_minima_and_udp_exactness_fail_closed(self) -> None:
        cases = (
            (
                self.positive_journal(echo_count=17),
                "positive_echo_count_below_minimum",
            ),
            (
                self.positive_journal(download_count=3),
                "positive_download_count_below_minimum",
            ),
            (
                self.positive_journal(upload_count=1),
                "positive_upload_count_below_minimum",
            ),
            (
                self.positive_journal(udp_sequences=list(range(49))),
                "positive_udp_count_mismatch",
            ),
            (
                self.positive_journal(udp_sequences=[*range(49), 48]),
                "positive_udp_sequence_mismatch",
            ),
            (
                self.positive_journal(udp_bytes=255),
                "positive_udp_packet_bytes_mismatch",
            ),
        )
        for journal, reason in cases:
            with self.subTest(reason=reason):
                self.assert_reason(self.run_audit(journal), reason)

    def test_contract_nested_shape_drift_is_rejected(self) -> None:
        contract = json.loads(verifier.CONTRACT_PATH.read_text(encoding="utf-8"))
        contract["profile"]["unexpected"] = "must-fail"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.json"
            path.write_text(json.dumps(contract), encoding="utf-8")
            with mock.patch.object(verifier, "CONTRACT_PATH", path):
                report = verifier.verify_journal(
                    self.positive_journal(),
                    run_id=RUN,
                    start_barrier_id=START,
                    barrier_id=END,
                    mode="positive",
                    profile_contract=PROFILE_CONTRACT,
                )

        self.assertEqual("fail", report["status"])
        self.assertEqual(
            "profile_contract_definition_invalid",
            report["reason_code"],
        )

    def test_drop_sequence_order_and_attribution_fail_closed(self) -> None:
        lines = self.positive_journal().splitlines()
        lines.insert(
            2,
            (
                f"ANEB_REQUEST_AUDIT_DROP instance_id={INSTANCE} "
                "seq=102 count=1 total=1"
            ),
        )
        for index in range(3, len(lines)):
            lines[index] = re.sub(
                r" seq=([0-9]+) ",
                lambda match: f" seq={int(match.group(1)) + 1} ",
                lines[index],
                count=1,
            )
        self.assert_reason(
            self.run_audit("\n".join(lines) + "\n"),
            "audit_drop_observed",
        )

        gap = self.positive_journal().replace(" seq=110 ", " seq=111 ", 1)
        self.assert_reason(self.run_audit(gap), "audit_sequence_gap")

        normal_events = [
            *[self.event("POST", "/api/v1/echo") for _ in range(18)],
            {"role": "capability"},
            *[self.event("GET", "/api/v1/download") for _ in range(4)],
            *[self.event("POST", "/api/v1/upload") for _ in range(2)],
            *[
                {
                    **self.event("DATAGRAM", "/api/v1/udp-echo"),
                    "suffix": f" datagram_seq={sequence} datagram_bytes=256",
                }
                for sequence in range(50)
            ],
        ]
        self.assert_reason(
            self.run_audit(self.window(normal_events)),
            "business_before_capability",
        )

        unattributed = self.positive_journal().splitlines()
        unattributed[2] = unattributed[2].replace(
            "scope=network_run",
            "scope=token_run",
        )
        self.assert_reason(
            self.run_audit("\n".join(unattributed) + "\n"),
            "unattributed_business_observed",
        )


if __name__ == "__main__":
    unittest.main()
