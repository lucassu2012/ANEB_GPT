from __future__ import annotations

import json
import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts import verify_token_run_audit as audit_verifier


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/verify_token_run_audit.py"
RUN = "018f7a2b-1234-7abc-8def-0123456789ab"
START = "11111111-1111-4111-8111-111111111111"
END = "22222222-2222-4222-8222-222222222222"
OTHER = "33333333-3333-4333-8333-333333333333"
INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
INSTANCE_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"


class TokenRunAuditCliTest(unittest.TestCase):
    @staticmethod
    def audit(
        seq: int,
        *,
        class_: str = "control",
        method: str = "GET",
        path: str = "/api/v1/serverinfo",
        role: str = "capability",
        scope: str = "token_run",
        run_id: str = RUN,
        instance: str = INSTANCE,
    ) -> str:
        return (
            f"ANEB_REQUEST_AUDIT instance_id={instance} seq={seq} "
            f"class={class_} method={method} path={path} role={role} "
            f"scope={scope} run_id={run_id}"
        )

    @staticmethod
    def drop(
        seq: int,
        *,
        count: int = 1,
        total: int = 1,
        instance: str = INSTANCE,
    ) -> str:
        return (
            f"ANEB_REQUEST_AUDIT_DROP instance_id={instance} seq={seq} "
            f"count={count} total={total}"
        )

    def window(
        self,
        events: list[dict[str, str]] | None = None,
        *,
        start_seq: int = 100,
        instance: str = INSTANCE,
        end_instance: str | None = None,
        prefix: list[str] | None = None,
        suffix: list[str] | None = None,
    ) -> str:
        lines = list(prefix or [])
        lines.append(
            self.audit(
                start_seq,
                role="window_start",
                run_id=START,
                instance=instance,
            )
        )
        for offset, raw_event in enumerate(events or [], 1):
            event = dict(raw_event)
            kind = event.pop("kind", "audit")
            seq = int(event.pop("seq", str(start_seq + offset)))
            event.setdefault("instance", instance)
            if kind == "drop":
                lines.append(self.drop(seq, **event))
            else:
                lines.append(self.audit(seq, **event))
        end_seq = start_seq + len(events or []) + 1
        lines.append(
            self.audit(
                end_seq,
                role="window_end",
                run_id=END,
                instance=end_instance or instance,
            )
        )
        lines.extend(suffix or [])
        return "\n".join(lines) + "\n"

    @staticmethod
    def capability(**overrides: str) -> dict[str, str]:
        event = {"role": "capability", "run_id": RUN}
        event.update(overrides)
        return event

    @staticmethod
    def reachability(**overrides: str) -> dict[str, str]:
        event = {"role": "reachability", "run_id": RUN}
        event.update(overrides)
        return event

    @staticmethod
    def business(path: str, method: str, **overrides: str) -> dict[str, str]:
        event = {
            "class_": "business",
            "method": method,
            "path": path,
            "role": "none",
            "run_id": RUN,
        }
        event.update(overrides)
        return event

    def positive_events(self) -> list[dict[str, str]]:
        return [
            self.reachability(),
            self.capability(),
            self.business("/api/v1/echo", "POST"),
            self.business("/api/v1/token-sim", "POST"),
            self.business("/api/v1/download", "GET"),
        ]

    def run_audit(
        self,
        journal: str | bytes,
        *,
        run_id: str = RUN,
        start_barrier_id: str = START,
        barrier_id: str = END,
        mode: str = "negative",
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "journal.log"
            if isinstance(journal, bytes):
                path.write_bytes(journal)
            else:
                path.write_bytes(journal.encode("utf-8"))
            return subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    str(path),
                    "--run-id",
                    run_id,
                    "--start-barrier-id",
                    start_barrier_id,
                    "--barrier-id",
                    barrier_id,
                    "--mode",
                    mode,
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )

    def assert_reason(self, result: subprocess.CompletedProcess[str], reason: str) -> dict:
        self.assertEqual(1, result.returncode, result.stdout)
        report = json.loads(result.stdout)
        self.assertEqual("fail", report["status"])
        self.assertEqual(reason, report["reason_code"])
        return report

    def test_negative_capability_only_window_passes(self) -> None:
        result = self.run_audit(
            self.window([self.reachability(), self.capability()])
        )
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual("request_entry_coverage_only", report["evidence_scope"])
        self.assertEqual("aneb-token-request-entry-audit-report", report["schema"])
        self.assertEqual("2.0.0", report["schema_version"])
        self.assertEqual(INSTANCE, report["audit_instance_id"])
        self.assertEqual(100, report["start_barrier_seq"])
        self.assertEqual(103, report["barrier_seq"])
        self.assertEqual(2, report["counts"]["control"])
        self.assertEqual(0, report["counts"]["business_total"])
        journal = self.window([self.reachability(), self.capability()])
        self.assertEqual(len(journal.encode("utf-8")), report["journal_bytes"])
        self.assertEqual(
            hashlib.sha256(journal.encode("utf-8")).hexdigest(),
            report["journal_sha256"],
        )

    def test_positive_window_counts_all_required_request_entries(self) -> None:
        result = self.run_audit(self.window(self.positive_events()), mode="positive")
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(1, report["counts"]["business"]["echo"])
        self.assertEqual(1, report["counts"]["business"]["token_sim"])
        self.assertEqual(1, report["counts"]["business"]["download"])
        self.assertEqual(3, report["counts"]["business_total"])

    def test_output_is_one_line_canonical_json(self) -> None:
        result = self.run_audit(self.window([self.capability()]))
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(
            json.dumps(report, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
            + "\n",
            result.stdout,
        )

    def test_all_cli_identities_must_be_canonical_lowercase_uuid(self) -> None:
        cases = (
            ("run_id", {"run_id": "sk_live_secret_like_value"}),
            ("run_id", {"run_id": RUN.upper()}),
            ("start_barrier_id", {"start_barrier_id": "start-123"}),
            ("barrier_id", {"barrier_id": "/not-an-id"}),
        )
        for label, arguments in cases:
            with self.subTest(label=label, arguments=arguments):
                result = self.run_audit("", **arguments)
                report = self.assert_reason(result, f"{label}_invalid")
                self.assertNotIn(next(iter(arguments.values())), result.stdout)
                self.assertEqual("redacted", report[label])

    def test_invalid_identity_is_not_leaked_when_journal_read_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.log"
            secret = "sk_live_secret_must_not_echo"
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    str(missing),
                    "--run-id",
                    secret,
                    "--start-barrier-id",
                    START,
                    "--barrier-id",
                    END,
                    "--mode",
                    "negative",
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
        report = self.assert_reason(result, "journal_read_failed")
        self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertEqual("redacted", report["run_id"])

    def test_run_start_and_end_ids_must_be_distinct(self) -> None:
        result = self.run_audit("", barrier_id=RUN)
        self.assert_reason(result, "audit_ids_not_distinct")

    def test_library_api_rejects_and_redacts_invalid_mode(self) -> None:
        secret_mode = "sk_live_mode_must_not_echo"
        report = audit_verifier.verify_journal(
            "",
            run_id=RUN,
            start_barrier_id=START,
            barrier_id=END,
            mode=secret_mode,
        )
        self.assertEqual("fail", report["status"])
        self.assertEqual("mode_invalid", report["reason_code"])
        self.assertEqual("invalid", report["mode"])
        self.assertNotIn(secret_mode, json.dumps(report, sort_keys=True))

    def test_library_api_rejects_unencodable_text_without_traceback(self) -> None:
        report = audit_verifier.verify_journal(
            "\ud800",
            run_id=RUN,
            start_barrier_id=START,
            barrier_id=END,
            mode="negative",
        )
        self.assertEqual("fail", report["status"])
        self.assertEqual("journal_not_utf8", report["reason_code"])
        self.assertIsNone(report["journal_sha256"])

    def test_missing_start_barrier_fails_closed(self) -> None:
        journal = (
            self.audit(1, role="capability", run_id=RUN)
            + "\n"
            + self.audit(2, role="window_end", run_id=END)
            + "\n"
        )
        self.assert_reason(self.run_audit(journal), "start_barrier_missing")

    def test_duplicate_start_barrier_fails_closed(self) -> None:
        start = self.audit(1, role="window_start", run_id=START)
        journal = start + "\n" + start.replace("seq=1 ", "seq=2 ") + "\n"
        journal += self.audit(3, role="window_end", run_id=END) + "\n"
        self.assert_reason(self.run_audit(journal), "start_barrier_duplicate")

    def test_start_id_reused_on_business_record_fails_closed(self) -> None:
        events = [
            self.capability(),
            self.business("/api/v1/echo", "POST", run_id=START),
        ]
        self.assert_reason(self.run_audit(self.window(events)), "start_barrier_id_reused")

    def test_missing_end_barrier_fails_closed(self) -> None:
        journal = self.audit(1, role="window_start", run_id=START) + "\n"
        journal += self.audit(2, role="capability", run_id=RUN) + "\n"
        self.assert_reason(self.run_audit(journal), "barrier_missing")

    def test_duplicate_end_barrier_fails_closed(self) -> None:
        journal = self.window([self.capability()])
        journal += self.audit(103, role="window_end", run_id=END) + "\n"
        self.assert_reason(self.run_audit(journal), "barrier_duplicate")

    def test_end_id_reused_on_business_record_fails_closed(self) -> None:
        events = [
            self.capability(),
            self.business("/api/v1/echo", "POST", run_id=END),
        ]
        self.assert_reason(self.run_audit(self.window(events)), "barrier_id_reused")

    def test_end_before_start_fails_closed(self) -> None:
        journal = self.audit(1, role="window_end", run_id=END) + "\n"
        journal += self.audit(2, role="window_start", run_id=START) + "\n"
        self.assert_reason(self.run_audit(journal), "barrier_order_invalid")

    def test_historical_target_records_before_start_cannot_pass(self) -> None:
        history = [
            self.audit(1, role="capability", run_id=RUN),
            self.audit(
                2,
                class_="business",
                method="POST",
                path="/api/v1/echo",
                role="none",
                run_id=RUN,
            ),
        ]
        result = self.run_audit(
            self.window([self.capability()], start_seq=10, prefix=history),
            mode="positive",
        )
        self.assert_reason(result, "target_outside_window")

    def test_delayed_target_record_after_end_fails_closed(self) -> None:
        delayed = self.audit(
            103,
            class_="business",
            method="POST",
            path="/api/v1/echo",
            role="none",
            run_id=RUN,
        )
        result = self.run_audit(self.window([self.capability()], suffix=[delayed]))
        self.assert_reason(result, "target_outside_window")

    def test_barriers_from_different_process_instances_fail_closed(self) -> None:
        result = self.run_audit(
            self.window([self.capability()], end_instance=INSTANCE_2)
        )
        self.assert_reason(result, "audit_instance_changed")

    def test_process_restart_inside_window_fails_closed(self) -> None:
        events = [self.capability(instance=INSTANCE_2)]
        result = self.run_audit(self.window(events))
        self.assert_reason(result, "audit_instance_changed")

    def test_sequence_gap_inside_window_fails_closed(self) -> None:
        events = [self.capability(seq="103")]
        result = self.run_audit(self.window(events))
        self.assert_reason(result, "audit_sequence_gap")

    def test_sequence_duplicate_inside_window_fails_closed(self) -> None:
        events = [self.reachability(), self.capability(seq="101")]
        result = self.run_audit(self.window(events))
        self.assert_reason(result, "audit_sequence_gap")

    def test_unbounded_numeric_fields_return_canonical_failure_not_traceback(self) -> None:
        huge = "9" * 5000
        line = (
            f"ANEB_REQUEST_AUDIT instance_id={INSTANCE} seq={huge} "
            "class=control method=GET path=/api/v1/serverinfo role=window_start "
            f"scope=token_run run_id={START}\n"
        )
        result = self.run_audit(line)
        self.assert_reason(result, "audit_malformed")
        self.assertNotIn("Traceback", result.stderr)

        overflow = self.audit(
            (1 << 64),
            role="window_start",
            run_id=START,
        )
        result = self.run_audit(overflow + "\n")
        self.assert_reason(result, "audit_malformed")

    def test_drop_inside_window_fails_closed(self) -> None:
        events = [self.capability(), {"kind": "drop"}]
        result = self.run_audit(self.window(events))
        self.assert_reason(result, "audit_drop_observed")

    def test_drop_after_end_does_not_invalidate_closed_window(self) -> None:
        result = self.run_audit(
            self.window([self.capability()], suffix=[self.drop(103)])
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_nested_or_concurrent_window_fails_closed(self) -> None:
        events = [
            self.capability(),
            {"role": "window_start", "run_id": OTHER},
        ]
        result = self.run_audit(self.window(events))
        self.assert_reason(result, "concurrent_window")

    def test_outer_window_that_encloses_target_window_fails_closed(self) -> None:
        outer_start = self.audit(99, role="window_start", run_id=OTHER)
        outer_end_id = "44444444-4444-4444-8444-444444444444"
        outer_end = self.audit(104, role="window_end", run_id=outer_end_id)
        result = self.run_audit(
            self.window(
                [self.capability()],
                prefix=[outer_start],
                suffix=[outer_end],
            )
        )
        self.assert_reason(result, "concurrent_window")

    def test_reachability_does_not_substitute_for_capability(self) -> None:
        result = self.run_audit(self.window([self.reachability()]))
        self.assert_reason(result, "target_capability_missing")

    def test_duplicate_capability_fails_closed(self) -> None:
        result = self.run_audit(self.window([self.capability(), self.capability()]))
        self.assert_reason(result, "target_capability_duplicate")

    def test_business_before_capability_fails_closed(self) -> None:
        events = [
            self.business("/api/v1/echo", "POST"),
            self.capability(),
            self.business("/api/v1/token-sim", "POST"),
            self.business("/api/v1/download", "GET"),
        ]
        result = self.run_audit(self.window(events), mode="positive")
        self.assert_reason(result, "business_before_capability")

    def test_negative_run_rejects_any_target_business(self) -> None:
        events = [self.capability(), self.business("/api/v1/echo", "POST")]
        result = self.run_audit(self.window(events))
        self.assert_reason(result, "negative_business_observed")

    def test_positive_reports_each_missing_required_primitive(self) -> None:
        all_business = [
            self.business("/api/v1/echo", "POST"),
            self.business("/api/v1/token-sim", "POST"),
            self.business("/api/v1/download", "GET"),
        ]
        cases = (
            (0, "positive_echo_missing"),
            (1, "positive_token_sim_missing"),
            (2, "positive_download_missing"),
        )
        for missing, reason in cases:
            with self.subTest(reason=reason):
                events = [self.capability()] + [
                    event for index, event in enumerate(all_business) if index != missing
                ]
                self.assert_reason(
                    self.run_audit(self.window(events), mode="positive"),
                    reason,
                )

    def test_unknown_api_is_visible_and_fails_both_modes(self) -> None:
        for mode in ("negative", "positive"):
            with self.subTest(mode=mode):
                events = self.positive_events() if mode == "positive" else [self.capability()]
                events.append(self.business("/api/v1/other", "POST"))
                expected = (
                    "negative_business_observed"
                    if mode == "negative"
                    else "unexpected_target_business"
                )
                self.assert_reason(
                    self.run_audit(self.window(events), mode=mode),
                    expected,
                )

    def test_business_with_audit_role_is_unexpected(self) -> None:
        events = self.positive_events()
        events.append(self.business("/api/v1/echo", "POST", role="other"))
        result = self.run_audit(self.window(events), mode="positive")
        self.assert_reason(result, "unexpected_target_business")

    def test_foreign_or_unscoped_business_fails_closed(self) -> None:
        cases = (
            self.business("/api/v1/echo", "POST", run_id=OTHER),
            self.business(
                "/api/v1/echo",
                "POST",
                scope="legacy_unscoped",
                run_id="none",
            ),
            self.business(
                "/api/v1/echo",
                "POST",
                scope="invalid_header",
                run_id="redacted",
            ),
        )
        for event in cases:
            with self.subTest(event=event):
                result = self.run_audit(self.window([self.capability(), event]))
                self.assert_reason(result, "unattributed_business_observed")

    def test_unexpected_target_control_fails_closed(self) -> None:
        events = [self.capability(), {"role": "none", "run_id": RUN}]
        result = self.run_audit(self.window(events))
        self.assert_reason(result, "unexpected_target_control")

    def test_prefixed_or_class_path_mismatched_audit_fails_closed(self) -> None:
        valid = self.window([self.capability()])
        malformed = (
            "INFO "
            + self.audit(90, role="reachability", run_id=OTHER)
            + "\n"
            + valid
        )
        self.assert_reason(self.run_audit(malformed), "audit_malformed")

        mismatch = valid.replace(
            "class=control method=GET path=/api/v1/serverinfo role=capability",
            "class=control method=GET path=/api/v1/echo role=capability",
        )
        self.assert_reason(self.run_audit(mismatch), "audit_malformed")

    def test_scope_and_marker_mismatch_fails_closed(self) -> None:
        malformed = self.window([self.capability(scope="legacy_unscoped")])
        self.assert_reason(self.run_audit(malformed), "audit_malformed")

    def test_secret_like_run_id_must_be_redacted_by_server_grammar(self) -> None:
        leaked = self.window([self.capability(run_id="sk_live_example_secret")])
        self.assert_reason(self.run_audit(leaked), "audit_malformed")

    def test_non_utf8_and_oversized_journals_are_rejected(self) -> None:
        self.assert_reason(self.run_audit(b"\xff\xfe"), "journal_not_utf8")
        oversized = b"x" * (8 * 1024 * 1024 + 1)
        self.assert_reason(self.run_audit(oversized), "journal_too_large")

    def test_unicode_line_separator_or_bare_carriage_return_cannot_inject(self) -> None:
        for separator in ("\u2028", "\r"):
            with self.subTest(separator=repr(separator)):
                journal = self.window([self.capability()]) + separator
                self.assert_reason(
                    self.run_audit(journal),
                    "journal_control_character",
                )


if __name__ == "__main__":
    unittest.main()
