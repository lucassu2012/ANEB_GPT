from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify_realtime_quick_run_audit.py"
RUN = "019fa000-1234-7abc-8def-0123456789ab"
START = "11111111-1111-4111-8111-111111111111"
END = "22222222-2222-4222-8222-222222222222"
FOREIGN = "33333333-3333-4333-8333-333333333333"
INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
INSTANCE_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
PROFILE_CONTRACT = "ai_realtime_voice_quick@1.1.1"


class RealtimeQuickRunAuditCliTest(unittest.TestCase):
    @staticmethod
    def audit(
        seq: int,
        *,
        class_: str = "control",
        method: str = "GET",
        path: str = "/api/v1/serverinfo",
        role: str = "capability",
        scope: str = "realtime_run",
        run_id: str = RUN,
        instance: str = INSTANCE,
    ) -> str:
        return (
            f"ANEB_REQUEST_AUDIT instance_id={instance} seq={seq} "
            f"class={class_} method={method} path={path} role={role} "
            f"scope={scope} run_id={run_id}"
        )

    @staticmethod
    def summary(
        *,
        sessions: int = 1,
        turns: int = 3,
        uplink_frames: int = 400,
        downlink_frames: int = 676,
        interrupted_turns: int = 1,
        protocol_ok: str = "true",
        run_id: str = RUN,
        instance: str = INSTANCE,
    ) -> str:
        return (
            f"ANEB_REALTIME_SUMMARY instance_id={instance} run_id={run_id} "
            f"sessions={sessions} turns={turns} uplink_frames={uplink_frames} "
            f"downlink_frames={downlink_frames} "
            f"interrupted_turns={interrupted_turns} protocol_ok={protocol_ok}"
        )

    def window(
        self,
        events: list[dict[str, str]] | None = None,
        *,
        summaries: list[str] | None = None,
        instance: str = INSTANCE,
    ) -> str:
        lines = [
            self.audit(
                100,
                role="window_start",
                run_id=START,
                instance=instance,
            )
        ]
        for offset, event in enumerate(events or [], 1):
            lines.append(self.audit(100 + offset, instance=instance, **event))
        lines.extend(summaries or [])
        lines.append(
            self.audit(
                101 + len(events or []),
                role="window_end",
                run_id=END,
                instance=instance,
            )
        )
        return "\n".join(lines) + "\n"

    @staticmethod
    def capability(**overrides: str) -> dict[str, str]:
        event = {"role": "capability", "run_id": RUN}
        event.update(overrides)
        return event

    @staticmethod
    def realtime(**overrides: str) -> dict[str, str]:
        event = {
            "class_": "business",
            "method": "GET",
            "path": "/api/v1/realtime-sim",
            "role": "none",
            "run_id": RUN,
        }
        event.update(overrides)
        return event

    @staticmethod
    def echo(**overrides: str) -> dict[str, str]:
        event = {
            "class_": "business",
            "method": "POST",
            "path": "/api/v1/echo",
            "role": "none",
            "run_id": RUN,
        }
        event.update(overrides)
        return event

    def run_audit(
        self,
        journal: str | bytes,
        *,
        mode: str = "positive",
        profile_contract: str | None = PROFILE_CONTRACT,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "journal.log"
            if isinstance(journal, bytes):
                path.write_bytes(journal)
            else:
                path.write_bytes(journal.encode("utf-8"))
            command = [
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
            ]
            if profile_contract is not None:
                command.extend(("--profile-contract", profile_contract))
            return subprocess.run(
                command,
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

    def positive_journal(self, *, echo_count: int = 2) -> str:
        return self.window(
            [
                self.capability(),
                self.realtime(),
                *[self.echo() for _ in range(echo_count)],
            ],
            summaries=[self.summary()],
        )

    def test_positive_exact_protocol_signature_passes(self) -> None:
        completed = self.run_audit(self.positive_journal())

        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(
            "aneb-realtime-request-entry-audit-report",
            report["schema"],
        )
        self.assertEqual("1.0.0", report["schema_version"])
        self.assertEqual(PROFILE_CONTRACT, report["profile_contract"])
        self.assertRegex(
            report["profile_contract_definition_sha256"],
            r"^[0-9a-f]{64}$",
        )
        self.assertEqual(
            {
                "echo": 2,
                "realtime_sim": 1,
                "unexpected": 0,
            },
            report["counts"]["business"],
        )
        self.assertEqual(
            {
                "sessions": 1,
                "turns": 3,
                "uplink_frames": 400,
                "downlink_frames": 676,
                "interrupted_turns": 1,
                "protocol_ok": True,
            },
            report["protocol_summary"],
        )

    def test_negative_capability_only_window_passes(self) -> None:
        completed = self.run_audit(
            self.window([self.capability()]),
            mode="negative",
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual(0, report["counts"]["business_total"])
        self.assertIsNone(report["protocol_summary"])
        self.assertEqual(
            "negative_zero_business",
            report["profile_contract_enforcement"],
        )

    def test_positive_requires_one_realtime_entry_and_one_summary(self) -> None:
        missing_entry = self.window(
            [self.capability(), self.echo()],
            summaries=[self.summary()],
        )
        missing_summary = self.window(
            [self.capability(), self.realtime()],
        )
        duplicate_summary = self.window(
            [self.capability(), self.realtime()],
            summaries=[self.summary(), self.summary()],
        )

        self.assert_reason(
            self.run_audit(missing_entry),
            "positive_realtime_sim_count_mismatch",
        )
        self.assert_reason(
            self.run_audit(missing_summary),
            "realtime_summary_missing",
        )
        self.assert_reason(
            self.run_audit(duplicate_summary),
            "realtime_summary_duplicate",
        )

    def test_protocol_summary_must_match_the_frozen_signature(self) -> None:
        fields = (
            ("sessions", 2),
            ("turns", 4),
            ("uplink_frames", 399),
            ("downlink_frames", 675),
            ("interrupted_turns", 0),
            ("protocol_ok", "false"),
        )
        for name, value in fields:
            with self.subTest(name=name):
                summary = self.summary(**{name: value})
                journal = self.window(
                    [self.capability(), self.realtime()],
                    summaries=[summary],
                )
                self.assert_reason(
                    self.run_audit(journal),
                    "realtime_summary_mismatch",
                )

    def test_summary_is_bound_to_the_same_run_and_process(self) -> None:
        wrong_run = self.window(
            [self.capability(), self.realtime()],
            summaries=[self.summary(run_id=FOREIGN)],
        )
        wrong_instance = self.window(
            [self.capability(), self.realtime()],
            summaries=[self.summary(instance=INSTANCE_2)],
        )

        self.assert_reason(
            self.run_audit(wrong_run),
            "realtime_summary_identity_mismatch",
        )
        self.assert_reason(
            self.run_audit(wrong_instance),
            "realtime_summary_identity_mismatch",
        )

    def test_negative_rejects_business_or_protocol_summary(self) -> None:
        business = self.window([self.capability(), self.realtime()])
        summary = self.window(
            [self.capability()],
            summaries=[self.summary()],
        )

        self.assert_reason(
            self.run_audit(business, mode="negative"),
            "negative_business_observed",
        )
        self.assert_reason(
            self.run_audit(summary, mode="negative"),
            "negative_realtime_summary_observed",
        )

    def test_foreign_or_unscoped_business_fails_closed(self) -> None:
        for overrides in (
            {"scope": "token_run"},
            {"scope": "legacy_unscoped", "run_id": "none"},
            {"run_id": FOREIGN},
        ):
            with self.subTest(overrides=overrides):
                journal = self.window(
                    [
                        self.capability(),
                        self.realtime(),
                        self.echo(**overrides),
                    ],
                    summaries=[self.summary()],
                )
                self.assert_reason(
                    self.run_audit(journal),
                    "unattributed_business_observed",
                )

    def test_business_before_capability_fails_closed(self) -> None:
        journal = self.window(
            [self.realtime(), self.capability()],
            summaries=[self.summary()],
        )
        self.assert_reason(
            self.run_audit(journal),
            "business_before_capability",
        )

    def test_malformed_summary_is_redacted_and_fails_closed(self) -> None:
        journal = self.window(
            [self.capability(), self.realtime()],
            summaries=[
                "ANEB_REALTIME_SUMMARY instance_id=secret "
                "run_id=do-not-echo sessions=1 turns=3 uplink_frames=400 "
                "downlink_frames=676 interrupted_turns=1 protocol_ok=true"
            ],
        )
        completed = self.run_audit(journal)
        report = self.assert_reason(completed, "realtime_summary_malformed")
        self.assertNotIn("secret", completed.stdout)
        self.assertNotIn("do-not-echo", completed.stdout)
        self.assertEqual(RUN, report["run_id"])

    def test_unknown_profile_contract_fails_closed(self) -> None:
        completed = self.run_audit(
            self.positive_journal(),
            profile_contract="ai_realtime_voice_quick@9.9.9",
        )
        report = self.assert_reason(completed, "profile_contract_invalid")
        self.assertIsNone(report["profile_contract"])


if __name__ == "__main__":
    unittest.main()
