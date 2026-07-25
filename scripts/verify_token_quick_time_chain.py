#!/usr/bin/env python3
"""Cross-bind D-82 collection, device, server, cleanup, and publish times."""

from __future__ import annotations

import calendar
from datetime import datetime, timezone
import re
from typing import Any, Mapping


COLLECTION_RE = re.compile(
    r"^d82-token-quick-(?P<stamp>[0-9]{8}T[0-9]{6}Z)-[0-9a-f]{32}$"
)
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
UTC_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:"
    r"[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$"
)
MAX_COLLECTION_PLAN_DELTA_MS = 5_000
MAX_CROSS_CLOCK_DELTA_MS = 60_000
MAX_RUN_ID_START_DELTA_MS = 5_000
RUN_TIMEOUT_GRACE_MS = 10_000
EXECUTION_MODES = frozenset({"positive", "negative_receipt_missing"})
CLIENT_REPORT_CONTRACTS = {
    "positive": ("aneb-token-quick-client-db-report", "1.2.0"),
    "negative_receipt_missing": (
        "aneb-token-quick-negative-client-db-report",
        "1.0.0",
    ),
}


class TimeChainFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> None:
    raise TimeChainFailure(reason_code)


def _mapping(value: object, reason: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        fail(reason)
    return value


def _integer(value: object, reason: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        fail(reason)
    return value


def _utc_ms(value: object, reason: str) -> int:
    if not isinstance(value, str) or UTC_RE.fullmatch(value) is None:
        fail(reason)
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        fail(reason)
    if parsed.tzinfo != timezone.utc:
        fail(reason)
    return calendar.timegm(parsed.utctimetuple()) * 1000 + parsed.microsecond // 1000


def _collection_ms(collection_id: object) -> int:
    if not isinstance(collection_id, str):
        fail("time_collection_identity_mismatch")
    match = COLLECTION_RE.fullmatch(collection_id)
    if match is None:
        fail("time_collection_identity_mismatch")
    try:
        parsed = datetime.strptime(match["stamp"], "%Y%m%dT%H%M%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError:
        fail("time_collection_identity_mismatch")
    return calendar.timegm(parsed.utctimetuple()) * 1000


def _uuid7_ms(run_id: object) -> int:
    if not isinstance(run_id, str) or RUN_ID_RE.fullmatch(run_id) is None:
        fail("run_time_binding_invalid")
    return int(run_id.replace("-", "")[:12], 16)


def verify_time_chain(
    *,
    execution_mode: str,
    collection_id: str,
    plan: Mapping[str, Any],
    preflight: Mapping[str, Any],
    receipt: Mapping[str, Any],
    client_report: Mapping[str, Any],
    room_inventory: Mapping[str, Any],
    final_state: Mapping[str, Any],
    cleanup: Mapping[str, Any],
    status: Mapping[str, Any],
    final: Mapping[str, Any],
) -> dict[str, object]:
    if not isinstance(execution_mode, str) or execution_mode not in EXECUTION_MODES:
        fail("time_execution_mode_invalid")
    plan = _mapping(plan, "time_plan_invalid")
    preflight = _mapping(preflight, "time_sequence_invalid")
    receipt = _mapping(receipt, "time_sequence_invalid")
    client = _mapping(client_report, "run_time_binding_invalid")
    room = _mapping(room_inventory, "time_sequence_invalid")
    final_state = _mapping(final_state, "time_sequence_invalid")
    cleanup = _mapping(cleanup, "time_sequence_invalid")
    status = _mapping(status, "time_sequence_invalid")
    final = _mapping(final, "time_sequence_invalid")

    if (
        plan.get("schema") != "aneb-d82-collector-plan"
        or plan.get("schema_version") != "1.1.0"
    ):
        fail("time_plan_invalid")
    if (
        final.get("schema") != "aneb-d82-final-evidence-manifest"
        or final.get("schema_version") != "1.1.0"
    ):
        fail("time_identity_mismatch")
    if (
        plan.get("execution_mode") != execution_mode
        or final.get("execution_mode") != execution_mode
    ):
        fail("time_execution_mode_mismatch")

    collection_ms = _collection_ms(collection_id)
    plan_ms = _utc_ms(plan.get("created_at_utc"), "time_plan_invalid")
    run_timeout_seconds = _integer(
        plan.get("run_timeout_seconds"), "time_plan_invalid"
    )
    lock_ttl_seconds = _integer(plan.get("lock_ttl_seconds"), "time_plan_invalid")
    if (
        not 60 <= run_timeout_seconds <= 1800
        or not 120 <= lock_ttl_seconds <= 3600
        or lock_ttl_seconds <= run_timeout_seconds + 60
    ):
        fail("time_plan_invalid")
    if (
        plan.get("collection_id") != collection_id
        or final.get("collection_id") != collection_id
        or status.get("collection_id") != collection_id
    ):
        fail("time_identity_mismatch")
    if not collection_ms <= plan_ms <= collection_ms + MAX_COLLECTION_PLAN_DELTA_MS:
        fail("time_collection_identity_mismatch")

    run_id = client.get("run_id")
    if final.get("run_id") != run_id or status.get("run_id") != run_id:
        fail("time_identity_mismatch")
    if any(
        plan.get(key) != status.get(key) or plan.get(key) != final.get(key)
        for key in ("start_barrier_id", "end_barrier_id")
    ):
        fail("time_identity_mismatch")

    preflight_ms = _utc_ms(
        preflight.get("captured_at_utc"), "time_sequence_invalid"
    )
    receipt_ms = _utc_ms(receipt.get("captured_at_utc"), "time_sequence_invalid")
    room_ms = _utc_ms(room.get("captured_at_utc"), "time_sequence_invalid")
    final_state_ms = _utc_ms(
        final_state.get("captured_at_utc"), "time_sequence_invalid"
    )
    cleanup_ms = _utc_ms(cleanup.get("captured_at_utc"), "time_sequence_invalid")
    status_ms = _utc_ms(status.get("completed_at_utc"), "time_sequence_invalid")
    final_ms = _utc_ms(final.get("finalized_at_utc"), "time_sequence_invalid")
    if not plan_ms <= preflight_ms <= receipt_ms:
        fail("time_sequence_invalid")
    if not room_ms <= final_state_ms <= cleanup_ms <= status_ms <= final_ms:
        fail("time_sequence_invalid")

    remote_anchor_usec = _integer(
        receipt.get("remote_realtime_anchor_usec"), "remote_clock_mismatch"
    )
    if remote_anchor_usec <= 0:
        fail("remote_clock_mismatch")
    remote_anchor_ms = remote_anchor_usec // 1000
    remote_delta_ms = abs(receipt_ms - remote_anchor_ms)
    if remote_delta_ms > MAX_CROSS_CLOCK_DELTA_MS:
        fail("remote_clock_mismatch")

    expected_client_schema, expected_client_version = CLIENT_REPORT_CONTRACTS[
        execution_mode
    ]
    if (
        client.get("schema") != expected_client_schema
        or client.get("schema_version") != expected_client_version
        or (
            execution_mode == "positive"
            and "negative_reason_code" in client
        )
        or (
            execution_mode == "negative_receipt_missing"
            and client.get("negative_reason_code") != "receipt_missing"
        )
    ):
        fail("run_time_binding_invalid")
    uuid_ms = _uuid7_ms(run_id)
    reported_uuid_ms = _integer(
        client.get("run_uuid_unix_ms"), "run_time_binding_invalid"
    )
    started_ms = _integer(
        client.get("started_at_epoch_ms"), "run_time_binding_invalid"
    )
    ended_ms = _integer(client.get("ended_at_epoch_ms"), "run_time_binding_invalid")
    serialized_ms = _integer(
        client.get("serialized_at_epoch_ms"), "run_time_binding_invalid"
    )
    start_delta_ms = _integer(
        client.get("run_start_delta_ms"), "run_time_binding_invalid"
    )
    if (
        reported_uuid_ms != uuid_ms
        or start_delta_ms != started_ms - uuid_ms
        or not 0 <= start_delta_ms <= MAX_RUN_ID_START_DELTA_MS
        or ended_ms < started_ms
        or serialized_ms != ended_ms
    ):
        fail("run_time_binding_invalid")
    if (
        started_ms < receipt_ms - MAX_CROSS_CLOCK_DELTA_MS
        or started_ms
        > receipt_ms + run_timeout_seconds * 1000 + MAX_CROSS_CLOCK_DELTA_MS
        or ended_ms > room_ms + MAX_CROSS_CLOCK_DELTA_MS
    ):
        fail("run_time_binding_invalid")
    run_duration_ms = ended_ms - started_ms
    if run_duration_ms > run_timeout_seconds * 1000 + RUN_TIMEOUT_GRACE_MS:
        fail("run_timeout_exceeded")
    if cleanup_ms - receipt_ms >= lock_ttl_seconds * 1000:
        fail("lock_ttl_exceeded")

    return {
        "status": "pass",
        "reason_code": "ok",
        "execution_mode": execution_mode,
        "collection_id": collection_id,
        "run_id": run_id,
        "run_duration_ms": run_duration_ms,
        "run_start_delta_ms": start_delta_ms,
        "remote_receipt_clock_delta_ms": remote_delta_ms,
        "collection_window_ms": final_ms - plan_ms,
        "run_timeout_seconds": run_timeout_seconds,
        "lock_ttl_seconds": lock_ttl_seconds,
    }
