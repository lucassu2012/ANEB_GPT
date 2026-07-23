from __future__ import annotations

import copy
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import verify_token_quick_time_chain as verifier


COLLECTION_ID = (
    "d82-token-quick-20260717T000000Z-0123456789abcdef0123456789abcdef"
)
RUN_ID = "019f6d5f-7fb8-7000-8000-000000000001"
START_ID = "11111111-1111-4111-8111-111111111111"
END_ID = "22222222-2222-4222-8222-222222222222"


def valid_inputs(execution_mode: str = "positive") -> dict[str, object]:
    return {
        "execution_mode": execution_mode,
        "collection_id": COLLECTION_ID,
        "plan": {
            "schema": "aneb-d82-collector-plan",
            "schema_version": "1.1.0",
            "execution_mode": execution_mode,
            "created_at_utc": "2026-07-17T00:00:00.1000000Z",
            "collection_id": COLLECTION_ID,
            "run_timeout_seconds": 900,
            "lock_ttl_seconds": 1200,
            "start_barrier_id": START_ID,
            "end_barrier_id": END_ID,
        },
        "preflight": {
            "captured_at_utc": "2026-07-17T00:00:01.0000000Z",
        },
        "receipt": {
            "captured_at_utc": "2026-07-17T00:00:02.0000000Z",
            "remote_realtime_anchor_usec": 1_784_246_401_500_000,
        },
        "client_report": {
            "schema": "aneb-token-quick-client-db-report",
            "schema_version": "1.2.0",
            "run_id": RUN_ID,
            "run_uuid_unix_ms": 1_784_246_403_000,
            "run_start_delta_ms": 0,
            "started_at_epoch_ms": 1_784_246_403_000,
            "ended_at_epoch_ms": 1_784_246_408_000,
            "serialized_at_epoch_ms": 1_784_246_408_000,
        },
        "room_inventory": {
            "captured_at_utc": "2026-07-17T00:00:09.0000000Z",
        },
        "final_state": {
            "captured_at_utc": "2026-07-17T00:00:10.0000000Z",
        },
        "cleanup": {
            "captured_at_utc": "2026-07-17T00:00:11.0000000Z",
        },
        "status": {
            "completed_at_utc": "2026-07-17T00:00:12.0000000Z",
            "collection_id": COLLECTION_ID,
            "run_id": RUN_ID,
            "start_barrier_id": START_ID,
            "end_barrier_id": END_ID,
        },
        "final": {
            "schema": "aneb-d82-final-evidence-manifest",
            "schema_version": "1.1.0",
            "execution_mode": execution_mode,
            "finalized_at_utc": "2026-07-17T00:00:13.0000000Z",
            "collection_id": COLLECTION_ID,
            "run_id": RUN_ID,
            "start_barrier_id": START_ID,
            "end_barrier_id": END_ID,
        },
    }


def valid_negative_inputs() -> dict[str, object]:
    values = valid_inputs("negative_receipt_missing")
    values["client_report"].update(
        {
            "schema": "aneb-token-quick-negative-client-db-report",
            "schema_version": "1.0.0",
            "negative_reason_code": "receipt_missing",
        }
    )
    return values


class TokenQuickTimeChainTests(unittest.TestCase):
    def verify(self, values: dict[str, object]) -> dict[str, object]:
        return verifier.verify_time_chain(**values)

    def assert_reason(self, values: dict[str, object], reason: str) -> None:
        with self.assertRaises(verifier.TimeChainFailure) as raised:
            self.verify(values)
        self.assertEqual(reason, raised.exception.reason_code)

    def test_accepts_one_coherent_cross_clock_collection(self) -> None:
        report = self.verify(valid_inputs())

        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual("positive", report["execution_mode"])
        self.assertEqual(COLLECTION_ID, report["collection_id"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertEqual(5_000, report["run_duration_ms"])
        self.assertEqual(500, report["remote_receipt_clock_delta_ms"])

    def test_accepts_receipt_missing_client_report_contract_in_negative_mode(self) -> None:
        report = self.verify(valid_negative_inputs())

        self.assertEqual("pass", report["status"])
        self.assertEqual("negative_receipt_missing", report["execution_mode"])
        self.assertEqual(RUN_ID, report["run_id"])

    def test_rejects_mode_not_bound_by_both_plan_and_final_manifest(self) -> None:
        for source in ("plan", "final"):
            with self.subTest(source=source):
                values = valid_inputs()
                values[source]["execution_mode"] = "negative_receipt_missing"
                self.assert_reason(values, "time_execution_mode_mismatch")

    def test_rejects_client_report_contract_from_the_other_mode(self) -> None:
        positive = valid_inputs()
        positive["client_report"].update(
            {
                "schema": "aneb-token-quick-negative-client-db-report",
                "schema_version": "1.0.0",
                "negative_reason_code": "receipt_missing",
            }
        )
        self.assert_reason(positive, "run_time_binding_invalid")

        negative = valid_negative_inputs()
        negative["client_report"].update(
            {
                "schema": "aneb-token-quick-client-db-report",
                "schema_version": "1.2.0",
            }
        )
        negative["client_report"].pop("negative_reason_code")
        self.assert_reason(negative, "run_time_binding_invalid")

    def test_negative_mode_requires_receipt_missing_reason(self) -> None:
        values = valid_negative_inputs()
        values["client_report"]["negative_reason_code"] = "receipt_invalid"
        self.assert_reason(values, "run_time_binding_invalid")

    def test_rejects_pre_mode_plan_or_final_manifest_schema_version(self) -> None:
        cases = {
            "plan": ("plan", "time_plan_invalid"),
            "final": ("final", "time_identity_mismatch"),
        }
        for name, (source, reason) in cases.items():
            with self.subTest(name=name):
                values = valid_inputs()
                values[source]["schema_version"] = "1.0.0"
                self.assert_reason(values, reason)

    def test_rejects_unknown_execution_mode(self) -> None:
        values = valid_inputs()
        values["execution_mode"] = "negative"
        self.assert_reason(values, "time_execution_mode_invalid")

    def test_rejects_plan_timestamp_not_created_with_collection_id(self) -> None:
        values = valid_inputs()
        values["plan"]["created_at_utc"] = "2026-07-18T00:00:00.1000000Z"
        self.assert_reason(values, "time_collection_identity_mismatch")

    def test_rejects_self_consistent_run_from_a_different_day(self) -> None:
        values = valid_inputs()
        client = values["client_report"]
        for key in (
            "run_uuid_unix_ms",
            "started_at_epoch_ms",
            "ended_at_epoch_ms",
            "serialized_at_epoch_ms",
        ):
            client[key] += 86_400_000
        self.assert_reason(values, "run_time_binding_invalid")

    def test_rejects_run_duration_beyond_this_collection_timeout(self) -> None:
        values = valid_inputs()
        values["plan"]["run_timeout_seconds"] = 60
        client = values["client_report"]
        client["ended_at_epoch_ms"] = client["started_at_epoch_ms"] + 70_001
        client["serialized_at_epoch_ms"] = client["ended_at_epoch_ms"]
        values["room_inventory"]["captured_at_utc"] = (
            "2026-07-17T00:01:14.0000000Z"
        )
        values["final_state"]["captured_at_utc"] = (
            "2026-07-17T00:01:15.0000000Z"
        )
        values["cleanup"]["captured_at_utc"] = "2026-07-17T00:01:16.0000000Z"
        values["status"]["completed_at_utc"] = "2026-07-17T00:01:17.0000000Z"
        values["final"]["finalized_at_utc"] = "2026-07-17T00:01:18.0000000Z"
        self.assert_reason(values, "run_timeout_exceeded")

    def test_rejects_room_copy_before_run_end_beyond_clock_tolerance(self) -> None:
        values = valid_inputs()
        values["room_inventory"]["captured_at_utc"] = (
            "2026-07-16T23:58:00.0000000Z"
        )
        self.assert_reason(values, "run_time_binding_invalid")

    def test_rejects_cleanup_status_and_final_manifest_out_of_order(self) -> None:
        values = valid_inputs()
        values["final"]["finalized_at_utc"] = "2026-07-17T00:00:10.5000000Z"
        self.assert_reason(values, "time_sequence_invalid")

    def test_rejects_remote_clock_far_from_receipt(self) -> None:
        values = valid_inputs()
        values["receipt"]["remote_realtime_anchor_usec"] -= 3_600_000_000
        self.assert_reason(values, "remote_clock_mismatch")

    def test_rejects_window_that_outlives_the_recorded_remote_lock_ttl(self) -> None:
        values = valid_inputs()
        values["plan"]["run_timeout_seconds"] = 60
        values["plan"]["lock_ttl_seconds"] = 121
        values["cleanup"]["captured_at_utc"] = "2026-07-17T00:02:04.0000000Z"
        values["status"]["completed_at_utc"] = "2026-07-17T00:02:05.0000000Z"
        values["final"]["finalized_at_utc"] = "2026-07-17T00:02:06.0000000Z"
        self.assert_reason(values, "lock_ttl_exceeded")

    def test_rejects_cross_bound_identifiers_even_when_times_are_coherent(self) -> None:
        values = valid_inputs()
        values["status"]["run_id"] = "019f6d5f-7fb8-7000-8000-000000000002"
        self.assert_reason(values, "time_identity_mismatch")

    def test_rejects_boolean_or_fractional_timeout_fields(self) -> None:
        for value in (True, 60.5):
            with self.subTest(value=value):
                values = copy.deepcopy(valid_inputs())
                values["plan"]["run_timeout_seconds"] = value
                self.assert_reason(values, "time_plan_invalid")


if __name__ == "__main__":
    unittest.main()
