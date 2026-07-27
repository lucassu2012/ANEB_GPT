#!/usr/bin/env python3
"""Verify one bounded Network Quick server request-entry window.

This proves request entry at one ANEB server process. It must be combined with
the frozen client Room result and release/server provenance before READY.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import unicodedata


UUID_PATTERN = (
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)
UUID_RE = re.compile(UUID_PATTERN)
AUDIT_RE = re.compile(
    rf"ANEB_REQUEST_AUDIT instance_id=(?P<instance_id>{UUID_PATTERN}) "
    r"seq=(?P<seq>[1-9][0-9]{0,19}) "
    r"class=(?P<class>control|business) "
    r"method=(?P<method>GET|POST|OTHER|DATAGRAM) path=(?P<path>/[^ ]*) "
    r"role=(?P<role>reachability|capability|window_start|window_end|none|other) "
    r"scope=(?P<scope>token_run|realtime_run|network_run|legacy_unscoped|invalid_header) "
    r"run_id=(?P<run_id>[^ ]+)"
    r"(?: datagram_seq=(?P<datagram_seq>0|[1-9][0-9]{0,9}) "
    r"datagram_bytes=(?P<datagram_bytes>[1-9][0-9]{0,3}))?"
)
DROP_RE = re.compile(
    rf"ANEB_REQUEST_AUDIT_DROP instance_id=(?P<instance_id>{UUID_PATTERN}) "
    r"seq=(?P<seq>[1-9][0-9]{0,19}) count=(?P<count>[1-9][0-9]{0,19}) "
    r"total=(?P<total>[1-9][0-9]{0,19})"
)
PROFILE_CONTRACT = "network_comprehensive_quick@1.2.0"
EXPECTED_CONTRACT_SHA256 = (
    "dd836686b6fea1185cdca9282bc4f79cec64e83972263e197b5bcf2a843fbb52"
)
CONTRACT_PATH = (
    Path(__file__).resolve().parents[1]
    / "spec"
    / "execution-contracts"
    / "network_comprehensive_quick-1.2.0.protocol.json"
)
REPORT_SCHEMA = "aneb-network-request-entry-audit-report"
REPORT_VERSION = "1.0.0"
MAX_JOURNAL_BYTES = 8 * 1024 * 1024
MAX_UINT64 = (1 << 64) - 1


def _duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_contract_key")
        result[key] = value
    return result


def _load_contract() -> tuple[dict[str, int], str]:
    payload = CONTRACT_PATH.read_bytes()
    if not payload or len(payload) > 64 * 1024:
        raise ValueError("contract_size_invalid")
    document = json.loads(payload.decode("utf-8"), object_pairs_hook=_duplicate_keys)
    if not isinstance(document, dict) or set(document) != {
        "schema",
        "contract_id",
        "version",
        "profile",
        "client_engine",
        "runtime",
        "applies_to",
        "required_business_primitives",
        "udp_wire_contract",
    }:
        raise ValueError("contract_shape_invalid")
    if (
        document["schema"] != "aneb-network-protocol-bounded-contract"
        or document["contract_id"] != "aneb-network-quick-protocol-bounds"
        or document["version"] != "1.0.0"
        or document["applies_to"] != ["positive_completed"]
    ):
        raise ValueError("contract_identity_invalid")
    profile = document["profile"]
    if not isinstance(profile, dict) or (
        profile.get("id"), profile.get("version")
    ) != ("network_comprehensive_quick", "1.2.0"):
        raise ValueError("contract_profile_invalid")
    runtime = document["runtime"]
    wire = document["udp_wire_contract"]
    if not isinstance(runtime, dict) or not isinstance(wire, dict):
        raise ValueError("contract_runtime_invalid")
    names = (
        "path_setup_attempts",
        "idle_rtt_samples",
        "post_load_rtt_samples",
        "download_parallel",
        "upload_parallel",
        "udp_packets",
        "udp_packet_bytes",
    )
    values: dict[str, int] = {}
    for name in names:
        value = runtime.get(name)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ValueError("contract_runtime_invalid")
        values[name] = value
    if (
        wire.get("contract_id") != "aneb-udp-echo-v2"
        or wire.get("run_id_format") != "uuid-rfc4122-canonical"
        or wire.get("server_behavior") != "echo_exact_bytes_no_amplification"
    ):
        raise ValueError("contract_udp_invalid")
    expected = {
        "echo_minimum": values["path_setup_attempts"]
        + values["idle_rtt_samples"]
        + values["post_load_rtt_samples"],
        "download_minimum": values["download_parallel"],
        "upload_minimum": values["upload_parallel"],
        "udp_exact": values["udp_packets"],
        "udp_packet_bytes": values["udp_packet_bytes"],
    }
    canonical = json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    digest = hashlib.sha256(canonical).hexdigest()
    if digest != EXPECTED_CONTRACT_SHA256:
        raise ValueError("contract_digest_invalid")
    return expected, digest


def _is_uuid(value: object) -> bool:
    return isinstance(value, str) and UUID_RE.fullmatch(value) is not None


def _safe_uuid(value: object) -> str:
    return value if _is_uuid(value) else "redacted"


def _valid_identity(scope: object, run_id: object) -> bool:
    if scope == "legacy_unscoped":
        return run_id == "none"
    if scope == "invalid_header":
        return run_id == "redacted"
    return scope in {"token_run", "realtime_run", "network_run"} and _is_uuid(run_id)


def _unsafe_control(text: str) -> bool:
    return any(
        character not in {"\n", "\r", "\t"}
        and unicodedata.category(character) in {"Cc", "Cf", "Zl", "Zp"}
        for character in text
    )


def _report(
    *,
    status: str,
    reason_code: str,
    run_id: str,
    start_barrier_id: str,
    barrier_id: str,
    mode: str,
    profile_contract: str | None,
    contract_sha256: str | None = None,
    expected: dict[str, int] | None = None,
    instance_id: str | None = None,
    journal_sha256: str | None = None,
    journal_bytes: int | None = None,
    counts: dict[str, int] | None = None,
    udp_sequences: list[int] | None = None,
    udp_packet_bytes: int | None = None,
) -> dict[str, object]:
    business = counts or {"download": 0, "echo": 0, "udp_echo": 0, "upload": 0}
    return {
        "audit_instance_id": instance_id,
        "barrier_id": barrier_id,
        "counts": {
            "business": dict(business),
            "business_total": sum(business.values()),
        },
        "evidence_scope": "request_entry_only",
        "expected_business_bounds": dict(expected) if expected else None,
        "journal_bytes": journal_bytes,
        "journal_sha256": journal_sha256,
        "mode": mode,
        "profile_contract": profile_contract,
        "profile_contract_definition_sha256": contract_sha256,
        "reason_code": reason_code,
        "run_id": run_id,
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "start_barrier_id": start_barrier_id,
        "status": status,
        "udp": {
            "packet_bytes": udp_packet_bytes,
            "sequences": list(udp_sequences or []),
        },
    }


def verify_journal(
    text: str,
    *,
    run_id: object,
    start_barrier_id: object,
    barrier_id: object,
    mode: object,
    profile_contract: object,
) -> dict[str, object]:
    base: dict[str, object] = {
        "run_id": _safe_uuid(run_id),
        "start_barrier_id": _safe_uuid(start_barrier_id),
        "barrier_id": _safe_uuid(barrier_id),
        "mode": mode if mode in {"positive", "negative"} else "invalid",
        "profile_contract": profile_contract if profile_contract == PROFILE_CONTRACT else None,
    }
    if mode not in {"positive", "negative"}:
        return _report(status="fail", reason_code="mode_invalid", **base)
    if profile_contract != PROFILE_CONTRACT:
        return _report(status="fail", reason_code="profile_contract_invalid", **base)
    try:
        expected, contract_sha256 = _load_contract()
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
        return _report(status="fail", reason_code="profile_contract_definition_invalid", **base)
    base.update(contract_sha256=contract_sha256, expected=expected)
    payload = text.encode("utf-8")
    base.update(
        journal_sha256=hashlib.sha256(payload).hexdigest(),
        journal_bytes=len(payload),
    )
    if not all(_is_uuid(value) for value in (run_id, start_barrier_id, barrier_id)):
        return _report(status="fail", reason_code="audit_id_invalid", **base)
    if len({run_id, start_barrier_id, barrier_id}) != 3:
        return _report(status="fail", reason_code="audit_ids_not_distinct", **base)
    if _unsafe_control(text):
        return _report(status="fail", reason_code="journal_control_character", **base)

    entries: list[dict[str, object]] = []
    records: list[dict[str, object]] = []
    for line_number, line in enumerate(text.replace("\r\n", "\n").split("\n"), 1):
        if not line:
            continue
        match = AUDIT_RE.fullmatch(line)
        if match is not None:
            groups = match.groupdict()
            sequence = int(groups["seq"])
            if sequence > MAX_UINT64:
                return _report(status="fail", reason_code="audit_malformed", **base)
            datagram_seq = groups.pop("datagram_seq")
            datagram_bytes = groups.pop("datagram_bytes")
            record: dict[str, object] = {
                **groups,
                "line": line_number,
                "seq": sequence,
                "datagram_seq": int(datagram_seq) if datagram_seq is not None else None,
                "datagram_bytes": int(datagram_bytes) if datagram_bytes is not None else None,
                "kind": "audit",
            }
            if not _valid_identity(record["scope"], record["run_id"]):
                return _report(status="fail", reason_code="audit_malformed", **base)
            is_datagram = record["method"] == "DATAGRAM"
            if is_datagram != (record["datagram_seq"] is not None):
                return _report(status="fail", reason_code="audit_malformed", **base)
            entries.append(record)
            records.append(record)
            continue
        drop = DROP_RE.fullmatch(line)
        if drop is not None:
            entries.append(
                {
                    "kind": "drop",
                    "line": line_number,
                    "seq": int(drop["seq"]),
                    "instance_id": drop["instance_id"],
                }
            )
            continue
        if "ANEB_REQUEST_AUDIT" in line:
            return _report(status="fail", reason_code="audit_malformed", **base)

    def barrier(record: dict[str, object], role: str, identity: object) -> bool:
        return (
            record["class"] == "control"
            and record["method"] == "GET"
            and record["path"] == "/api/v1/serverinfo"
            and record["role"] == role
            and record["scope"] == "network_run"
            and record["run_id"] == identity
        )

    starts = [record for record in records if barrier(record, "window_start", start_barrier_id)]
    ends = [record for record in records if barrier(record, "window_end", barrier_id)]
    if len(starts) != 1:
        return _report(status="fail", reason_code="start_barrier_invalid", **base)
    if len(ends) != 1:
        return _report(status="fail", reason_code="barrier_invalid", **base)
    start, end = starts[0], ends[0]
    base["instance_id"] = str(start["instance_id"])
    if start["instance_id"] != end["instance_id"]:
        return _report(status="fail", reason_code="audit_instance_changed", **base)
    if int(start["line"]) >= int(end["line"]):
        return _report(status="fail", reason_code="barrier_order_invalid", **base)
    if any(
        record["role"] in {"window_start", "window_end"}
        and record is not start
        and record is not end
        for record in records
    ):
        return _report(status="fail", reason_code="concurrent_window", **base)
    if any(
        record["run_id"] == run_id
        and not int(start["line"]) < int(record["line"]) < int(end["line"])
        for record in records
    ):
        return _report(status="fail", reason_code="target_outside_window", **base)
    window_entries = [
        entry
        for entry in entries
        if int(start["line"]) <= int(entry["line"]) <= int(end["line"])
    ]
    if any(entry["instance_id"] != start["instance_id"] for entry in window_entries):
        return _report(status="fail", reason_code="audit_instance_changed", **base)
    for previous, current in zip(window_entries, window_entries[1:]):
        if int(current["seq"]) != int(previous["seq"]) + 1:
            return _report(status="fail", reason_code="audit_sequence_gap", **base)
    if any(entry["kind"] == "drop" for entry in window_entries):
        return _report(status="fail", reason_code="audit_drop_observed", **base)
    window = [
        record
        for record in records
        if int(start["line"]) < int(record["line"]) < int(end["line"])
    ]
    capability = [
        record
        for record in window
        if record["class"] == "control"
        and record["method"] == "GET"
        and record["role"] == "capability"
        and record["scope"] == "network_run"
        and record["run_id"] == run_id
    ]
    target_control = [
        record
        for record in window
        if record["class"] == "control" and record["run_id"] == run_id
    ]
    if any(
        record["method"] != "GET"
        or record["path"] != "/api/v1/serverinfo"
        or record["role"] not in {"reachability", "capability"}
        or record["scope"] != "network_run"
        for record in target_control
    ):
        return _report(status="fail", reason_code="unexpected_target_control", **base)
    if len(capability) != 1:
        return _report(status="fail", reason_code="target_capability_invalid", **base)
    business = [record for record in window if record["class"] == "business"]
    if business and int(capability[0]["line"]) >= int(business[0]["line"]):
        return _report(status="fail", reason_code="business_before_capability", **base)
    if any(
        record["scope"] != "network_run" or record["run_id"] != run_id
        for record in business
    ):
        return _report(status="fail", reason_code="unattributed_business_observed", **base)

    counts = {"download": 0, "echo": 0, "udp_echo": 0, "upload": 0}
    udp_sequences: list[int] = []
    udp_bytes: list[int] = []
    for record in business:
        signature = (record["method"], record["path"], record["role"])
        if signature == ("POST", "/api/v1/echo", "none"):
            counts["echo"] += 1
        elif signature == ("GET", "/api/v1/download", "none"):
            counts["download"] += 1
        elif signature == ("POST", "/api/v1/upload", "none"):
            counts["upload"] += 1
        elif signature == ("DATAGRAM", "/api/v1/udp-echo", "none"):
            counts["udp_echo"] += 1
            udp_sequences.append(int(record["datagram_seq"]))
            udp_bytes.append(int(record["datagram_bytes"]))
        else:
            return _report(status="fail", reason_code="unexpected_target_business", **base)
    outcome = {
        **base,
        "counts": counts,
        "udp_sequences": sorted(udp_sequences),
        "udp_packet_bytes": udp_bytes[0] if udp_bytes and len(set(udp_bytes)) == 1 else None,
    }
    if mode == "negative":
        return _report(
            status="pass" if not business else "fail",
            reason_code="ok" if not business else "negative_business_observed",
            **outcome,
        )
    if counts["echo"] < expected["echo_minimum"]:
        reason = "positive_echo_count_below_minimum"
    elif counts["download"] < expected["download_minimum"]:
        reason = "positive_download_count_below_minimum"
    elif counts["upload"] < expected["upload_minimum"]:
        reason = "positive_upload_count_below_minimum"
    elif counts["udp_echo"] != expected["udp_exact"]:
        reason = "positive_udp_count_mismatch"
    elif sorted(udp_sequences) != list(range(expected["udp_exact"])):
        reason = "positive_udp_sequence_mismatch"
    elif any(value != expected["udp_packet_bytes"] for value in udp_bytes):
        reason = "positive_udp_packet_bytes_mismatch"
    else:
        reason = "ok"
    return _report(
        status="pass" if reason == "ok" else "fail",
        reason_code=reason,
        **outcome,
    )


def _emit(report: dict[str, object]) -> None:
    print(json.dumps(report, sort_keys=True, separators=(",", ":"), allow_nan=False))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify one Network Quick audit window")
    parser.add_argument("journal", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--start-barrier-id", required=True)
    parser.add_argument("--barrier-id", required=True)
    parser.add_argument("--mode", choices=("positive", "negative"), required=True)
    parser.add_argument("--profile-contract", required=True)
    args = parser.parse_args(argv)
    try:
        payload = args.journal.read_bytes()
        if len(payload) > MAX_JOURNAL_BYTES:
            raise ValueError("journal_too_large")
        text = payload.decode("utf-8")
        report = verify_journal(
            text,
            run_id=args.run_id,
            start_barrier_id=args.start_barrier_id,
            barrier_id=args.barrier_id,
            mode=args.mode,
            profile_contract=args.profile_contract,
        )
    except OSError:
        report = _report(
            status="fail",
            reason_code="journal_read_failed",
            run_id=_safe_uuid(args.run_id),
            start_barrier_id=_safe_uuid(args.start_barrier_id),
            barrier_id=_safe_uuid(args.barrier_id),
            mode=args.mode,
            profile_contract=args.profile_contract,
        )
    except UnicodeError:
        report = _report(
            status="fail",
            reason_code="journal_not_utf8",
            run_id=_safe_uuid(args.run_id),
            start_barrier_id=_safe_uuid(args.start_barrier_id),
            barrier_id=_safe_uuid(args.barrier_id),
            mode=args.mode,
            profile_contract=args.profile_contract,
        )
    except ValueError as error:
        report = _report(
            status="fail",
            reason_code=str(error),
            run_id=_safe_uuid(args.run_id),
            start_barrier_id=_safe_uuid(args.start_barrier_id),
            barrier_id=_safe_uuid(args.barrier_id),
            mode=args.mode,
            profile_contract=args.profile_contract,
        )
    _emit(report)
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
