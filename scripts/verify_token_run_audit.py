#!/usr/bin/env python3
"""Verify bounded Token request-entry coverage from saved journal text.

The audit is deliberately narrower than an execution result: it proves which
requests entered the server audit boundary inside one complete log window.  A
caller must combine a passing report with the retained client result before it
can make an end-to-end execution claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path


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
    r"scope=(?P<scope>token_run|legacy_unscoped|invalid_header) "
    r"run_id=(?P<run_id>[^ ]+)"
)
AUDIT_DROP_RE = re.compile(
    rf"ANEB_REQUEST_AUDIT_DROP instance_id=(?P<instance_id>{CANONICAL_UUID_PATTERN}) "
    r"seq=(?P<seq>[1-9][0-9]{0,19}) count=(?P<count>[1-9][0-9]{0,19}) "
    r"total=(?P<total>[1-9][0-9]{0,19})"
)
MAX_JOURNAL_BYTES = 8 * 1024 * 1024
MAX_UINT64 = (1 << 64) - 1
REPORT_SCHEMA = "aneb-token-request-entry-audit-report"
REPORT_SCHEMA_VERSION = "2.1.0"
TOKEN_QUICK_PROFILE_CONTRACT = "token_multimodal_quick@1.2.1"
TOKEN_QUICK_CONTRACT_PATH = (
    Path(__file__).resolve().parents[1]
    / "spec/execution-contracts/token_multimodal_quick-1.2.1.request-entry.json"
)
MAX_CONTRACT_BYTES = 16 * 1024
SHA256_ID_RE = re.compile(r"sha256:[0-9a-f]{64}")
CONTROL_PATH = "/api/v1/serverinfo"
BUSINESS_PATHS = frozenset(
    {
        "/api/v1/echo",
        "/api/v1/token-sim",
        "/api/v1/download",
        "/api/v1/other",
    }
)
CONTROL_ROLES = frozenset(
    {"reachability", "capability", "window_start", "window_end", "none", "other"}
)
BUSINESS_ROLES = frozenset({"none", "other"})


def _is_canonical_uuid(value: str) -> bool:
    return CANONICAL_UUID_RE.fullmatch(value) is not None


def _safe_report_uuid(value: str) -> str:
    """Never echo malformed caller input into machine evidence."""
    return value if _is_canonical_uuid(value) else "redacted"


def _safe_report_mode(value: str) -> str:
    return value if value in {"positive", "negative"} else "invalid"


def _safe_report_profile_contract(value: object) -> str | None:
    return (
        value
        if isinstance(value, str) and value == TOKEN_QUICK_PROFILE_CONTRACT
        else None
    )


def _profile_contract_enforcement(value: object, mode: str) -> str:
    if value is None:
        return "none"
    if not isinstance(value, str) or value != TOKEN_QUICK_PROFILE_CONTRACT:
        return "invalid"
    if mode == "positive":
        return "positive_exact_business_counts"
    if mode == "negative":
        return "negative_zero_business"
    return "not_evaluated"


def _reject_duplicate_json_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
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


def _load_token_quick_contract() -> tuple[dict[str, int], str]:
    payload = TOKEN_QUICK_CONTRACT_PATH.read_bytes()
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
    if root["schema"] != "aneb-request-entry-exact-count-contract":
        raise ValueError("contract_schema_invalid")
    if root["contract_id"] != "aneb-token-quick-request-entry-counts":
        raise ValueError("contract_id_invalid")
    if root["version"] != "1.0.0":
        raise ValueError("contract_version_invalid")
    if root["applies_to"] != ["positive_completed"]:
        raise ValueError("contract_scope_invalid")

    profile = _require_exact_keys(
        root["profile"], {"id", "version", "canonical_sha256"}
    )
    if profile["id"] != "token_multimodal_quick" or profile["version"] != "1.2.1":
        raise ValueError("contract_profile_invalid")
    if not isinstance(profile["canonical_sha256"], str) or not SHA256_ID_RE.fullmatch(
        profile["canonical_sha256"]
    ):
        raise ValueError("contract_profile_sha_invalid")

    client_engine = _require_exact_keys(
        root["client_engine"], {"contract_id", "version"}
    )
    if (
        client_engine["contract_id"] != "aneb-token-simulation-engine"
        or client_engine["version"] != "1.0.0"
    ):
        raise ValueError("contract_client_engine_invalid")

    runtime = _require_exact_keys(
        root["runtime"],
        {"canonical_sha256", "task_count", "positive_response_artifact_task_count"},
    )
    if not isinstance(runtime["canonical_sha256"], str) or not SHA256_ID_RE.fullmatch(
        runtime["canonical_sha256"]
    ):
        raise ValueError("contract_runtime_sha_invalid")
    for key in ("task_count", "positive_response_artifact_task_count"):
        value = runtime[key]
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ValueError("contract_runtime_count_invalid")

    raw_counts = _require_exact_keys(
        root["exact_business_counts"], {"echo", "token_sim", "download"}
    )
    counts: dict[str, int] = {}
    for key in ("echo", "token_sim", "download"):
        count = raw_counts[key]
        if isinstance(count, bool) or not isinstance(count, int) or not 0 < count <= 10_000:
            raise ValueError("contract_count_invalid")
        counts[key] = count
    if runtime["task_count"] != counts["token_sim"]:
        raise ValueError("contract_task_count_mismatch")
    if runtime["positive_response_artifact_task_count"] != counts["download"]:
        raise ValueError("contract_artifact_count_mismatch")
    canonical_payload = json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return counts, hashlib.sha256(canonical_payload).hexdigest()


def _valid_audit_identity(scope: str, run_id: str) -> bool:
    if scope == "legacy_unscoped":
        return run_id == "none"
    if scope == "invalid_header":
        return run_id == "redacted"
    return scope == "token_run" and _is_canonical_uuid(run_id)


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
    profile_contract_definition_sha256: str | None = None,
    audit_instance_id: str | None = None,
    start_barrier_line: int | None = None,
    start_barrier_seq: int | None = None,
    barrier_line: int | None = None,
    barrier_seq: int | None = None,
    journal_sha256: str | None = None,
    journal_bytes: int | None = None,
    control: int = 0,
    echo: int = 0,
    token_sim: int = 0,
    download: int = 0,
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
                "download": download,
                "echo": echo,
                "token_sim": token_sim,
                "unexpected": unexpected,
            },
            "business_total": echo + token_sim + download + unexpected,
            "control": control,
            "unattributed_business": unattributed_business,
            "unexpected_control": unexpected_control,
        },
        "evidence_scope": "request_entry_coverage_only",
        "journal_bytes": journal_bytes,
        "journal_sha256": journal_sha256,
        "mode": mode,
        "expected_business_counts": (
            dict(expected_business_counts)
            if expected_business_counts is not None
            else None
        ),
        "profile_contract": profile_contract,
        "profile_contract_definition_sha256": profile_contract_definition_sha256,
        "profile_contract_enforcement": profile_contract_enforcement,
        "reason_code": reason_code,
        "run_id": run_id,
        "start_barrier_id": start_barrier_id,
        "start_barrier_line": start_barrier_line,
        "start_barrier_seq": start_barrier_seq,
        "status": status,
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
    }


def verify_journal(
    text: str,
    *,
    run_id: str,
    start_barrier_id: str,
    barrier_id: str,
    mode: str,
    profile_contract: object = None,
) -> dict[str, object]:
    report_arguments = {
        "run_id": _safe_report_uuid(run_id),
        "start_barrier_id": _safe_report_uuid(start_barrier_id),
        "barrier_id": _safe_report_uuid(barrier_id),
        "mode": _safe_report_mode(mode),
        "profile_contract": _safe_report_profile_contract(profile_contract),
        "profile_contract_enforcement": _profile_contract_enforcement(
            profile_contract, mode
        ),
        "expected_business_counts": None,
        "profile_contract_definition_sha256": None,
    }

    if mode not in {"positive", "negative"}:
        return _report(
            status="fail",
            reason_code="mode_invalid",
            **report_arguments,
        )
    if profile_contract is not None and (
        not isinstance(profile_contract, str)
        or profile_contract != TOKEN_QUICK_PROFILE_CONTRACT
    ):
        return _report(
            status="fail",
            reason_code="profile_contract_invalid",
            **report_arguments,
        )
    expected_business_counts: dict[str, int] | None = None
    if profile_contract == TOKEN_QUICK_PROFILE_CONTRACT:
        try:
            expected_business_counts, definition_sha256 = _load_token_quick_contract()
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
            report_arguments["profile_contract_enforcement"] = "not_evaluated"
            return _report(
                status="fail",
                reason_code="profile_contract_definition_invalid",
                **report_arguments,
            )
        report_arguments.update(
            expected_business_counts=expected_business_counts,
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
    lines = text.replace("\r\n", "\n").split("\n")
    for line_number, line in enumerate(lines, 1):
        audit = AUDIT_RE.fullmatch(line)
        if audit is not None:
            try:
                audit_seq = int(audit["seq"])
            except ValueError:
                audit_seq = MAX_UINT64 + 1
            if audit_seq > MAX_UINT64 or not _valid_audit_record(audit):
                return _report(
                    status="fail",
                    reason_code="audit_malformed",
                    **report_arguments,
                )
            record: dict[str, object] = {
                "kind": "audit",
                "line": line_number,
                **audit.groupdict(),
                "seq": audit_seq,
            }
            entries.append(record)
            records.append(record)
            continue

        drop = AUDIT_DROP_RE.fullmatch(line)
        if drop is not None:
            try:
                drop_seq = int(drop["seq"])
                drop_count = int(drop["count"])
                drop_total = int(drop["total"])
            except ValueError:
                drop_seq = drop_count = drop_total = MAX_UINT64 + 1
            if (
                max(drop_seq, drop_count, drop_total) > MAX_UINT64
                or drop_total < drop_count
            ):
                return _report(
                    status="fail",
                    reason_code="audit_malformed",
                    **report_arguments,
                )
            entries.append(
                {
                    "kind": "drop",
                    "line": line_number,
                    "seq": drop_seq,
                    "instance_id": drop["instance_id"],
                    "count": drop_count,
                    "total": drop_total,
                }
            )
            continue

        if "ANEB_REQUEST_AUDIT" in line:
            return _report(
                status="fail",
                reason_code="audit_malformed",
                **report_arguments,
            )

    def exact_barrier(record: dict[str, object], *, role: str) -> bool:
        return (
            record["class"] == "control"
            and record["method"] == "GET"
            and record["path"] == CONTROL_PATH
            and record["role"] == role
            and record["scope"] == "token_run"
        )

    start_id_records = [record for record in records if record["run_id"] == start_barrier_id]
    start_records = [
        record for record in start_id_records if exact_barrier(record, role="window_start")
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
            reason_code=("start_barrier_missing" if not start_records else "start_barrier_duplicate"),
            **report_arguments,
        )

    end_id_records = [record for record in records if record["run_id"] == barrier_id]
    end_records = [record for record in end_id_records if exact_barrier(record, role="window_end")]
    if len(end_id_records) != len(end_records):
        return _report(
            status="fail",
            reason_code="barrier_id_reused",
            **report_arguments,
        )
    if len(end_records) != 1:
        return _report(
            status="fail",
            reason_code=("barrier_missing" if not end_records else "barrier_duplicate"),
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
        and not (int(start["line"]) < int(record["line"]) < int(end["line"]))
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

    window_records = [
        record
        for record in records
        if int(start["line"]) < int(record["line"]) < int(end["line"])
    ]
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

    control = echo = token_sim = download = unexpected = 0
    unattributed_business = unexpected_control = 0
    capability_records: list[dict[str, object]] = []
    target_business_records: list[dict[str, object]] = []
    for record in window_records:
        if record["class"] == "business" and (
            record["scope"] != "token_run" or record["run_id"] != run_id
        ):
            unattributed_business += 1
            continue
        if record["run_id"] != run_id:
            continue
        if record["class"] == "control":
            if record["role"] in {"reachability", "capability"} and record["method"] == "GET":
                control += 1
                if record["role"] == "capability":
                    capability_records.append(record)
            else:
                unexpected_control += 1
            continue

        target_business_records.append(record)
        if record["scope"] != "token_run" or record["role"] != "none":
            unexpected += 1
        elif record["method"] == "POST" and record["path"] == "/api/v1/echo":
            echo += 1
        elif record["method"] == "POST" and record["path"] == "/api/v1/token-sim":
            token_sim += 1
        elif record["method"] == "GET" and record["path"] == "/api/v1/download":
            download += 1
        else:
            unexpected += 1

    outcome_arguments = {
        **window_arguments,
        "control": control,
        "echo": echo,
        "token_sim": token_sim,
        "download": download,
        "unexpected": unexpected,
        "unattributed_business": unattributed_business,
        "unexpected_control": unexpected_control,
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
    elif mode == "negative":
        status, reason = "pass", "ok"
    elif unexpected:
        status, reason = "fail", "unexpected_target_business"
    elif (
        profile_contract == TOKEN_QUICK_PROFILE_CONTRACT
        and echo != expected_business_counts["echo"]
    ):
        status, reason = "fail", "positive_echo_count_mismatch"
    elif (
        profile_contract == TOKEN_QUICK_PROFILE_CONTRACT
        and token_sim != expected_business_counts["token_sim"]
    ):
        status, reason = "fail", "positive_token_sim_count_mismatch"
    elif (
        profile_contract == TOKEN_QUICK_PROFILE_CONTRACT
        and download != expected_business_counts["download"]
    ):
        status, reason = "fail", "positive_download_count_mismatch"
    elif echo == 0:
        status, reason = "fail", "positive_echo_missing"
    elif token_sim == 0:
        status, reason = "fail", "positive_token_sim_missing"
    elif download == 0:
        status, reason = "fail", "positive_download_missing"
    else:
        status, reason = "pass", "ok"
    return _report(status=status, reason_code=reason, **outcome_arguments)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify one bounded Token request-entry audit window",
    )
    parser.add_argument("journal", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--start-barrier-id", required=True)
    parser.add_argument("--barrier-id", required=True, help="end-barrier UUID")
    parser.add_argument("--mode", choices=("positive", "negative"), required=True)
    parser.add_argument(
        "--profile-contract",
        help=(
            "optional exact business-count contract; currently supports "
            f"{TOKEN_QUICK_PROFILE_CONTRACT}"
        ),
    )
    args = parser.parse_args(argv)

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
        journal_sha256 = None
        journal_size = None
        if reason == "journal_not_utf8":
            journal_sha256 = hashlib.sha256(journal_bytes).hexdigest()
            journal_size = len(journal_bytes)
        report = _report(
            status="fail",
            reason_code=reason,
            run_id=_safe_report_uuid(args.run_id),
            start_barrier_id=_safe_report_uuid(args.start_barrier_id),
            barrier_id=_safe_report_uuid(args.barrier_id),
            mode=_safe_report_mode(args.mode),
            profile_contract=_safe_report_profile_contract(args.profile_contract),
            profile_contract_enforcement=_profile_contract_enforcement(
                args.profile_contract, args.mode
            ),
            journal_sha256=journal_sha256,
            journal_bytes=journal_size,
        )

    print(json.dumps(report, ensure_ascii=True, sort_keys=True, separators=(",", ":")))
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
