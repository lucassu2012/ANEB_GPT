#!/usr/bin/env python3
"""Verify one bounded AI realtime Quick request-entry and protocol window.

The report proves only bounded server request entry and the server-emitted
protocol summary. A release verifier must still bind it to the retained client
database, APK provenance, server identity, and immutable evidence inventory.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import unicodedata


CANONICAL_UUID_PATTERN = (
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)
CANONICAL_UUID_RE = re.compile(CANONICAL_UUID_PATTERN)
AUDIT_RE = re.compile(
    rf"ANEB_REQUEST_AUDIT instance_id=(?P<instance_id>{CANONICAL_UUID_PATTERN}) "
    r"seq=(?P<seq>[1-9][0-9]{0,19}) "
    r"class=(?P<class>control|business) "
    r"method=(?P<method>GET|POST|OTHER) path=(?P<path>/[^ ]*) "
    r"role=(?P<role>reachability|capability|window_start|window_end|none|other) "
    r"scope=(?P<scope>token_run|realtime_run|legacy_unscoped|invalid_header) "
    r"run_id=(?P<run_id>[^ ]+)"
)
AUDIT_DROP_RE = re.compile(
    rf"ANEB_REQUEST_AUDIT_DROP instance_id=(?P<instance_id>{CANONICAL_UUID_PATTERN}) "
    r"seq=(?P<seq>[1-9][0-9]{0,19}) count=(?P<count>[1-9][0-9]{0,19}) "
    r"total=(?P<total>[1-9][0-9]{0,19})"
)
REALTIME_SUMMARY_RE = re.compile(
    rf"ANEB_REALTIME_SUMMARY instance_id=(?P<instance_id>{CANONICAL_UUID_PATTERN}) "
    rf"run_id=(?P<run_id>{CANONICAL_UUID_PATTERN}) "
    r"sessions=(?P<sessions>[0-9]{1,5}) turns=(?P<turns>[0-9]{1,5}) "
    r"uplink_frames=(?P<uplink_frames>[0-9]{1,5}) "
    r"downlink_frames=(?P<downlink_frames>[0-9]{1,5}) "
    r"interrupted_turns=(?P<interrupted_turns>[0-9]{1,5}) "
    r"protocol_ok=(?P<protocol_ok>true|false)"
)
MAX_JOURNAL_BYTES = 8 * 1024 * 1024
MAX_UINT64 = (1 << 64) - 1
MAX_PROTOCOL_COUNT = 10_000
MAX_CONTRACT_INTEGER = 1 << 30
REPORT_SCHEMA = "aneb-realtime-request-entry-audit-report"
REPORT_SCHEMA_VERSION = "1.0.0"
PROFILE_CONTRACT = "ai_realtime_voice_quick@1.1.1"
CONTRACT_PATH = (
    Path(__file__).resolve().parents[1]
    / "spec"
    / "execution-contracts"
    / "ai_realtime_voice_quick-1.1.1.protocol.json"
)
MAX_CONTRACT_BYTES = 32 * 1024
SHA256_ID_RE = re.compile(r"sha256:[0-9a-f]{64}")
CONTROL_PATH = "/api/v1/serverinfo"
BUSINESS_PATHS = frozenset(
    {
        "/api/v1/echo",
        "/api/v1/token-sim",
        "/api/v1/download",
        "/api/v1/realtime-sim",
        "/api/v1/other",
    }
)
CONTROL_ROLES = frozenset(
    {"reachability", "capability", "window_start", "window_end", "none", "other"}
)
BUSINESS_ROLES = frozenset({"none", "other"})


def _reject_duplicate_json_keys(
    pairs: list[tuple[str, object]],
) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_contract_key")
        result[key] = value
    return result


def _require_exact_keys(
    value: object,
    expected: set[str],
) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError("contract_shape_invalid")
    return value


def _positive_int(value: object, reason: str) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not 0 < value <= MAX_CONTRACT_INTEGER
    ):
        raise ValueError(reason)
    return value


def _load_contract() -> tuple[dict[str, int], dict[str, object], str]:
    payload = CONTRACT_PATH.read_bytes()
    if not payload or len(payload) > MAX_CONTRACT_BYTES:
        raise ValueError("contract_size_invalid")
    document = json.loads(
        payload.decode("utf-8"),
        object_pairs_hook=_reject_duplicate_json_keys,
    )
    root = _require_exact_keys(
        document,
        {
            "schema",
            "contract_id",
            "version",
            "profile",
            "client_engine",
            "runtime",
            "applies_to",
            "exact_business_counts",
        },
    )
    if root["schema"] != "aneb-realtime-protocol-exact-contract":
        raise ValueError("contract_schema_invalid")
    if root["contract_id"] != "aneb-realtime-quick-protocol-signature":
        raise ValueError("contract_id_invalid")
    if root["version"] != "1.0.0":
        raise ValueError("contract_version_invalid")
    if root["applies_to"] != ["positive_completed"]:
        raise ValueError("contract_scope_invalid")

    profile = _require_exact_keys(
        root["profile"],
        {"id", "version", "canonical_sha256"},
    )
    if profile["id"] != "ai_realtime_voice_quick" or profile["version"] != "1.1.1":
        raise ValueError("contract_profile_invalid")
    if not isinstance(profile["canonical_sha256"], str) or not SHA256_ID_RE.fullmatch(
        profile["canonical_sha256"]
    ):
        raise ValueError("contract_profile_sha_invalid")

    engine = _require_exact_keys(
        root["client_engine"],
        {"contract_id", "version"},
    )
    if (
        engine["contract_id"] != "aneb-realtime-simulation-engine"
        or engine["version"] != "1.0.0"
    ):
        raise ValueError("contract_client_engine_invalid")

    runtime = _require_exact_keys(
        root["runtime"],
        {
            "canonical_sha256",
            "session_count",
            "turn_count",
            "frame_ms",
            "uplink_frames",
            "uplink_payload_bytes",
            "planned_downlink_frames",
            "planned_downlink_payload_bytes",
            "effective_downlink_frames",
            "effective_downlink_payload_bytes",
            "interrupted_turns",
            "max_stop_within_ms",
        },
    )
    if not isinstance(runtime["canonical_sha256"], str) or not SHA256_ID_RE.fullmatch(
        runtime["canonical_sha256"]
    ):
        raise ValueError("contract_runtime_sha_invalid")
    integer_runtime: dict[str, int] = {}
    for key in runtime:
        if key == "canonical_sha256":
            continue
        integer_runtime[key] = _positive_int(
            runtime[key],
            "contract_runtime_count_invalid",
        )
    if integer_runtime["frame_ms"] != 20:
        raise ValueError("contract_frame_cadence_invalid")
    if integer_runtime["effective_downlink_frames"] > integer_runtime[
        "planned_downlink_frames"
    ]:
        raise ValueError("contract_effective_frames_invalid")

    raw_counts = _require_exact_keys(root["exact_business_counts"], {"realtime_sim"})
    counts = {
        "realtime_sim": _positive_int(
            raw_counts["realtime_sim"],
            "contract_count_invalid",
        )
    }
    if counts["realtime_sim"] != integer_runtime["session_count"]:
        raise ValueError("contract_session_count_mismatch")

    signature = {
        "sessions": integer_runtime["session_count"],
        "turns": integer_runtime["turn_count"],
        "uplink_frames": integer_runtime["uplink_frames"],
        "downlink_frames": integer_runtime["effective_downlink_frames"],
        "interrupted_turns": integer_runtime["interrupted_turns"],
        "protocol_ok": True,
    }
    canonical = json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return counts, signature, hashlib.sha256(canonical).hexdigest()


def _is_canonical_uuid(value: object) -> bool:
    return isinstance(value, str) and CANONICAL_UUID_RE.fullmatch(value) is not None


def _safe_uuid(value: object) -> str:
    return value if _is_canonical_uuid(value) else "redacted"


def _safe_mode(value: object) -> str:
    return value if value in {"positive", "negative"} else "invalid"


def _safe_profile_contract(value: object) -> str | None:
    return value if value == PROFILE_CONTRACT else None


def _profile_enforcement(value: object, mode: object) -> str:
    if value != PROFILE_CONTRACT or mode not in {"positive", "negative"}:
        return "not_evaluated"
    return (
        "positive_exact_protocol_signature"
        if mode == "positive"
        else "negative_zero_business"
    )


def _valid_audit_identity(scope: str, run_id: str) -> bool:
    if scope == "legacy_unscoped":
        return run_id == "none"
    if scope == "invalid_header":
        return run_id == "redacted"
    return scope in {"token_run", "realtime_run"} and _is_canonical_uuid(run_id)


def _valid_audit_record(match: re.Match[str]) -> bool:
    if not _valid_audit_identity(match["scope"], match["run_id"]):
        return False
    if match["class"] == "control":
        return match["path"] == CONTROL_PATH and match["role"] in CONTROL_ROLES
    return match["path"] in BUSINESS_PATHS and match["role"] in BUSINESS_ROLES


def _has_unsafe_control_character(text: str) -> bool:
    for index, character in enumerate(text):
        if character in {"\n", "\t"}:
            continue
        if character == "\r" and index + 1 < len(text) and text[index + 1] == "\n":
            continue
        if unicodedata.category(character) in {"Cc", "Cf", "Zl", "Zp"}:
            return True
    return False


def _report(
    *,
    status: str,
    reason_code: str,
    run_id: str,
    start_barrier_id: str,
    barrier_id: str,
    mode: str,
    profile_contract: str | None,
    profile_contract_enforcement: str,
    expected_business_counts: dict[str, int] | None = None,
    expected_protocol_signature: dict[str, object] | None = None,
    profile_contract_definition_sha256: str | None = None,
    protocol_summary: dict[str, object] | None = None,
    audit_instance_id: str | None = None,
    start_barrier_line: int | None = None,
    start_barrier_seq: int | None = None,
    barrier_line: int | None = None,
    barrier_seq: int | None = None,
    journal_sha256: str | None = None,
    journal_bytes: int | None = None,
    control: int = 0,
    echo: int = 0,
    realtime_sim: int = 0,
    unexpected: int = 0,
    unattributed_business: int = 0,
    unexpected_control: int = 0,
) -> dict[str, object]:
    return {
        "audit_instance_id": audit_instance_id,
        "barrier_id": barrier_id,
        "barrier_line": barrier_line,
        "barrier_seq": barrier_seq,
        "counts": {
            "business": {
                "echo": echo,
                "realtime_sim": realtime_sim,
                "unexpected": unexpected,
            },
            "business_total": echo + realtime_sim + unexpected,
            "control": control,
            "unattributed_business": unattributed_business,
            "unexpected_control": unexpected_control,
        },
        "evidence_scope": "request_entry_and_protocol_summary_only",
        "expected_business_counts": (
            dict(expected_business_counts)
            if expected_business_counts is not None
            else None
        ),
        "expected_protocol_signature": (
            dict(expected_protocol_signature)
            if expected_protocol_signature is not None
            else None
        ),
        "journal_bytes": journal_bytes,
        "journal_sha256": journal_sha256,
        "mode": mode,
        "profile_contract": profile_contract,
        "profile_contract_definition_sha256": profile_contract_definition_sha256,
        "profile_contract_enforcement": profile_contract_enforcement,
        "protocol_summary": (
            dict(protocol_summary) if protocol_summary is not None else None
        ),
        "reason_code": reason_code,
        "run_id": run_id,
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
        "start_barrier_id": start_barrier_id,
        "start_barrier_line": start_barrier_line,
        "start_barrier_seq": start_barrier_seq,
        "status": status,
    }


def _summary_values(match: re.Match[str]) -> dict[str, object]:
    values: dict[str, object] = {
        "sessions": int(match["sessions"]),
        "turns": int(match["turns"]),
        "uplink_frames": int(match["uplink_frames"]),
        "downlink_frames": int(match["downlink_frames"]),
        "interrupted_turns": int(match["interrupted_turns"]),
        "protocol_ok": match["protocol_ok"] == "true",
    }
    if any(
        isinstance(value, int)
        and not isinstance(value, bool)
        and value > MAX_PROTOCOL_COUNT
        for value in values.values()
    ):
        raise ValueError("summary_count_invalid")
    return values


def verify_journal(
    text: str,
    *,
    run_id: object,
    start_barrier_id: object,
    barrier_id: object,
    mode: object,
    profile_contract: object,
) -> dict[str, object]:
    report_arguments: dict[str, object] = {
        "run_id": _safe_uuid(run_id),
        "start_barrier_id": _safe_uuid(start_barrier_id),
        "barrier_id": _safe_uuid(barrier_id),
        "mode": _safe_mode(mode),
        "profile_contract": _safe_profile_contract(profile_contract),
        "profile_contract_enforcement": _profile_enforcement(
            profile_contract,
            mode,
        ),
        "expected_business_counts": None,
        "expected_protocol_signature": None,
        "profile_contract_definition_sha256": None,
    }
    if mode not in {"positive", "negative"}:
        return _report(
            status="fail",
            reason_code="mode_invalid",
            **report_arguments,
        )
    if profile_contract is None:
        return _report(
            status="fail",
            reason_code="profile_contract_missing",
            **report_arguments,
        )
    if not isinstance(profile_contract, str) or profile_contract != PROFILE_CONTRACT:
        return _report(
            status="fail",
            reason_code="profile_contract_invalid",
            **report_arguments,
        )
    try:
        expected_counts, expected_signature, definition_sha256 = _load_contract()
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
        report_arguments["profile_contract_enforcement"] = "not_evaluated"
        return _report(
            status="fail",
            reason_code="profile_contract_definition_invalid",
            **report_arguments,
        )
    report_arguments.update(
        expected_business_counts=expected_counts,
        expected_protocol_signature=expected_signature,
        profile_contract_definition_sha256=definition_sha256,
    )
    try:
        journal_payload = text.encode("utf-8")
    except UnicodeEncodeError:
        return _report(
            status="fail",
            reason_code="journal_not_utf8",
            **report_arguments,
        )
    report_arguments.update(
        journal_sha256=hashlib.sha256(journal_payload).hexdigest(),
        journal_bytes=len(journal_payload),
    )
    for label, value in (
        ("run_id", run_id),
        ("start_barrier_id", start_barrier_id),
        ("barrier_id", barrier_id),
    ):
        if not _is_canonical_uuid(value):
            return _report(
                status="fail",
                reason_code=f"{label}_invalid",
                **report_arguments,
            )
    if len({run_id, start_barrier_id, barrier_id}) != 3:
        return _report(
            status="fail",
            reason_code="audit_ids_not_distinct",
            **report_arguments,
        )
    if _has_unsafe_control_character(text):
        return _report(
            status="fail",
            reason_code="journal_control_character",
            **report_arguments,
        )

    entries: list[dict[str, object]] = []
    records: list[dict[str, object]] = []
    summaries: list[dict[str, object]] = []
    lines = text.replace("\r\n", "\n").split("\n")
    for line_number, line in enumerate(lines, 1):
        audit = AUDIT_RE.fullmatch(line)
        if audit is not None:
            try:
                sequence = int(audit["seq"])
            except ValueError:
                sequence = MAX_UINT64 + 1
            if sequence > MAX_UINT64 or not _valid_audit_record(audit):
                return _report(
                    status="fail",
                    reason_code="audit_malformed",
                    **report_arguments,
                )
            record: dict[str, object] = {
                "kind": "audit",
                "line": line_number,
                **audit.groupdict(),
                "seq": sequence,
            }
            entries.append(record)
            records.append(record)
            continue
        drop = AUDIT_DROP_RE.fullmatch(line)
        if drop is not None:
            try:
                sequence = int(drop["seq"])
                count = int(drop["count"])
                total = int(drop["total"])
            except ValueError:
                sequence = count = total = MAX_UINT64 + 1
            if max(sequence, count, total) > MAX_UINT64 or total < count:
                return _report(
                    status="fail",
                    reason_code="audit_malformed",
                    **report_arguments,
                )
            entries.append(
                {
                    "kind": "drop",
                    "line": line_number,
                    "seq": sequence,
                    "instance_id": drop["instance_id"],
                    "count": count,
                    "total": total,
                }
            )
            continue
        summary = REALTIME_SUMMARY_RE.fullmatch(line)
        if summary is not None:
            try:
                values = _summary_values(summary)
            except ValueError:
                return _report(
                    status="fail",
                    reason_code="realtime_summary_malformed",
                    **report_arguments,
                )
            summaries.append(
                {
                    "line": line_number,
                    "instance_id": summary["instance_id"],
                    "run_id": summary["run_id"],
                    "values": values,
                }
            )
            continue
        if "ANEB_REQUEST_AUDIT" in line:
            return _report(
                status="fail",
                reason_code="audit_malformed",
                **report_arguments,
            )
        if "ANEB_REALTIME_SUMMARY" in line:
            return _report(
                status="fail",
                reason_code="realtime_summary_malformed",
                **report_arguments,
            )

    def exact_barrier(record: dict[str, object], *, role: str) -> bool:
        return (
            record["class"] == "control"
            and record["method"] == "GET"
            and record["path"] == CONTROL_PATH
            and record["role"] == role
            and record["scope"] == "realtime_run"
        )

    start_id_records = [
        record for record in records if record["run_id"] == start_barrier_id
    ]
    start_records = [
        record
        for record in start_id_records
        if exact_barrier(record, role="window_start")
    ]
    if len(start_id_records) != len(start_records):
        return _report(
            status="fail",
            reason_code="start_barrier_id_reused",
            **report_arguments,
        )
    if len(start_records) != 1:
        return _report(
            status="fail",
            reason_code=(
                "start_barrier_missing"
                if not start_records
                else "start_barrier_duplicate"
            ),
            **report_arguments,
        )
    end_id_records = [record for record in records if record["run_id"] == barrier_id]
    end_records = [
        record
        for record in end_id_records
        if exact_barrier(record, role="window_end")
    ]
    if len(end_id_records) != len(end_records):
        return _report(
            status="fail",
            reason_code="barrier_id_reused",
            **report_arguments,
        )
    if len(end_records) != 1:
        return _report(
            status="fail",
            reason_code="barrier_missing" if not end_records else "barrier_duplicate",
            **report_arguments,
        )

    start = start_records[0]
    end = end_records[0]
    window_arguments = {
        **report_arguments,
        "audit_instance_id": str(start["instance_id"]),
        "start_barrier_line": int(start["line"]),
        "start_barrier_seq": int(start["seq"]),
        "barrier_line": int(end["line"]),
        "barrier_seq": int(end["seq"]),
    }
    if start["instance_id"] != end["instance_id"]:
        return _report(
            status="fail",
            reason_code="audit_instance_changed",
            **window_arguments,
        )
    if int(start["line"]) >= int(end["line"]):
        return _report(
            status="fail",
            reason_code="barrier_order_invalid",
            **window_arguments,
        )
    if any(
        record["run_id"] == run_id
        and not int(start["line"]) < int(record["line"]) < int(end["line"])
        for record in records
    ):
        return _report(
            status="fail",
            reason_code="target_outside_window",
            **window_arguments,
        )
    window_entries = [
        entry
        for entry in entries
        if int(start["line"]) <= int(entry["line"]) <= int(end["line"])
    ]
    if any(entry["instance_id"] != start["instance_id"] for entry in window_entries):
        return _report(
            status="fail",
            reason_code="audit_instance_changed",
            **window_arguments,
        )
    for previous, current in zip(window_entries, window_entries[1:]):
        if int(current["seq"]) != int(previous["seq"]) + 1:
            return _report(
                status="fail",
                reason_code="audit_sequence_gap",
                **window_arguments,
            )
    if any(entry["kind"] == "drop" for entry in window_entries):
        return _report(
            status="fail",
            reason_code="audit_drop_observed",
            **window_arguments,
        )
    if any(
        record["role"] in {"window_start", "window_end"}
        and record is not start
        and record is not end
        for record in records
    ):
        return _report(
            status="fail",
            reason_code="concurrent_window",
            **window_arguments,
        )

    window_records = [
        record
        for record in records
        if int(start["line"]) < int(record["line"]) < int(end["line"])
    ]
    window_summaries = [
        summary
        for summary in summaries
        if int(start["line"]) < int(summary["line"]) < int(end["line"])
    ]
    if any(
        summary["run_id"] == run_id
        and not int(start["line"]) < int(summary["line"]) < int(end["line"])
        for summary in summaries
    ):
        return _report(
            status="fail",
            reason_code="target_summary_outside_window",
            **window_arguments,
        )

    control = echo = realtime_sim = unexpected = 0
    unattributed_business = unexpected_control = 0
    capability_records: list[dict[str, object]] = []
    target_business_records: list[dict[str, object]] = []
    for record in window_records:
        if record["class"] == "business" and (
            record["scope"] != "realtime_run" or record["run_id"] != run_id
        ):
            unattributed_business += 1
            continue
        if record["run_id"] != run_id:
            continue
        if record["class"] == "control":
            if (
                record["role"] in {"reachability", "capability"}
                and record["method"] == "GET"
            ):
                control += 1
                if record["role"] == "capability":
                    capability_records.append(record)
            else:
                unexpected_control += 1
            continue
        target_business_records.append(record)
        if record["scope"] != "realtime_run" or record["role"] != "none":
            unexpected += 1
        elif record["method"] == "GET" and record["path"] == "/api/v1/realtime-sim":
            realtime_sim += 1
        elif record["method"] == "POST" and record["path"] == "/api/v1/echo":
            echo += 1
        else:
            unexpected += 1

    protocol_summary: dict[str, object] | None = None
    if len(window_summaries) == 1:
        summary = window_summaries[0]
        if (
            summary["run_id"] == run_id
            and summary["instance_id"] == start["instance_id"]
        ):
            protocol_summary = dict(summary["values"])
    outcome_arguments = {
        **window_arguments,
        "control": control,
        "echo": echo,
        "realtime_sim": realtime_sim,
        "unexpected": unexpected,
        "unattributed_business": unattributed_business,
        "unexpected_control": unexpected_control,
        "protocol_summary": protocol_summary,
    }
    if unexpected_control:
        status, reason = "fail", "unexpected_target_control"
    elif not capability_records:
        status, reason = "fail", "target_capability_missing"
    elif len(capability_records) != 1:
        status, reason = "fail", "target_capability_duplicate"
    elif target_business_records and int(capability_records[0]["line"]) >= int(
        target_business_records[0]["line"]
    ):
        status, reason = "fail", "business_before_capability"
    elif unattributed_business:
        status, reason = "fail", "unattributed_business_observed"
    elif mode == "negative" and target_business_records:
        status, reason = "fail", "negative_business_observed"
    elif mode == "negative" and window_summaries:
        status, reason = "fail", "negative_realtime_summary_observed"
    elif mode == "negative":
        status, reason = "pass", "ok"
    elif unexpected:
        status, reason = "fail", "unexpected_target_business"
    elif realtime_sim != expected_counts["realtime_sim"]:
        status, reason = "fail", "positive_realtime_sim_count_mismatch"
    elif not window_summaries:
        status, reason = "fail", "realtime_summary_missing"
    elif len(window_summaries) != 1:
        status, reason = "fail", "realtime_summary_duplicate"
    elif protocol_summary is None:
        status, reason = "fail", "realtime_summary_identity_mismatch"
    elif protocol_summary != expected_signature:
        status, reason = "fail", "realtime_summary_mismatch"
    else:
        status, reason = "pass", "ok"
    return _report(
        status=status,
        reason_code=reason,
        **outcome_arguments,
    )


def _emit(report: dict[str, object]) -> None:
    print(
        json.dumps(
            report,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify one bounded AI realtime Quick audit window",
    )
    parser.add_argument("journal", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--start-barrier-id", required=True)
    parser.add_argument("--barrier-id", required=True)
    parser.add_argument("--mode", choices=("positive", "negative"), required=True)
    parser.add_argument("--profile-contract", required=True)
    args = parser.parse_args(argv)

    journal_bytes = b""
    try:
        with args.journal.open("rb") as stream:
            journal_bytes = stream.read(MAX_JOURNAL_BYTES + 1)
    except OSError:
        reason = "journal_read_failed"
    else:
        if len(journal_bytes) > MAX_JOURNAL_BYTES:
            reason = "journal_too_large"
        else:
            try:
                text = journal_bytes.decode("utf-8")
            except UnicodeError:
                reason = "journal_not_utf8"
            else:
                report = verify_journal(
                    text,
                    run_id=args.run_id,
                    start_barrier_id=args.start_barrier_id,
                    barrier_id=args.barrier_id,
                    mode=args.mode,
                    profile_contract=args.profile_contract,
                )
                reason = None
    if reason is not None:
        report = _report(
            status="fail",
            reason_code=reason,
            run_id=_safe_uuid(args.run_id),
            start_barrier_id=_safe_uuid(args.start_barrier_id),
            barrier_id=_safe_uuid(args.barrier_id),
            mode=_safe_mode(args.mode),
            profile_contract=_safe_profile_contract(args.profile_contract),
            profile_contract_enforcement=_profile_enforcement(
                args.profile_contract,
                args.mode,
            ),
            journal_sha256=(
                hashlib.sha256(journal_bytes).hexdigest()
                if reason == "journal_not_utf8"
                else None
            ),
            journal_bytes=(
                len(journal_bytes) if reason == "journal_not_utf8" else None
            ),
        )
    _emit(report)
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
