#!/usr/bin/env python3
"""Network Comprehensive Quick live-collector contracts.

This module freezes the Network-specific launch, lifecycle-marker and request
audit identities separately from the AI Realtime collector.  Importing it is
side-effect free; it never contacts a phone or server.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
from typing import Literal, Mapping
import uuid

try:
    from scripts.verify_network_quick_client_db import verify_database
    from scripts.verify_network_quick_run_audit import verify_journal
except ModuleNotFoundError:  # Direct script execution from scripts/.
    from verify_network_quick_client_db import verify_database
    from verify_network_quick_run_audit import verify_journal


PACKAGE_NAME = "com.aneb.probe.codex"
ACTIVITY_COMPONENT = "com.aneb.probe.codex/com.aneb.probe.ui.MainActivity"
PROFILE_CONTRACT = "network_comprehensive_quick@1.2.0"
PROFILE_ID = "network_comprehensive_quick"
PROFILE_VERSION = "1.2.0"
NEGATIVE_DEVICE_PORT = 18765
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
FIELD_RE = re.compile(r"(?P<key>[a-z_]+)=(?P<value>[^\s]+)")


class CollectorError(RuntimeError):
    """A frozen Network Quick collection contract was rejected."""


@dataclass(frozen=True)
class NetworkTerminalMarkers:
    run_id: str
    contract_status: Literal["authorized", "rejected"]
    terminal_status: Literal["completed", "contract_rejected"]
    reason_code: str | None


def _mapping(value: object) -> Mapping[str, object]:
    if not isinstance(value, Mapping):
        raise CollectorError("network_verifier_binding_invalid")
    return value


def bind_network_verifier_reports(
    *,
    markers: NetworkTerminalMarkers,
    mode: Literal["positive", "negative"],
    client_report: Mapping[str, object],
    audit_report: Mapping[str, object],
) -> dict[str, object]:
    """Cross-bind independent client and server reports to one terminal run.

    This function does not run either verifier.  It rejects stale, cross-run,
    wrong-mode, failed, or non-zero-business negative reports before a caller
    can treat the pair as one Network Quick result.
    """

    try:
        expected_contract_status = "authorized" if mode == "positive" else "rejected"
        expected_terminal = "completed" if mode == "positive" else "contract_rejected"
        expected_reason = None if mode == "positive" else "receipt_missing"
        counts = _mapping(audit_report.get("counts"))
        business_total = counts.get("business_total")
        if (
            mode not in {"positive", "negative"}
            or markers.contract_status != expected_contract_status
            or markers.terminal_status != expected_terminal
            or markers.reason_code != expected_reason
            or client_report.get("schema")
            != "aneb-network-quick-client-db-verification"
            or client_report.get("schema_version") != "1.0.0"
            or client_report.get("status") != "pass"
            or client_report.get("mode") != mode
            or client_report.get("run_id") != markers.run_id
            or client_report.get("reason_code") != expected_reason
            or audit_report.get("schema")
            != "aneb-network-request-entry-audit-report"
            or audit_report.get("schema_version") != "1.0.0"
            or audit_report.get("status") != "pass"
            or audit_report.get("mode") != mode
            or audit_report.get("run_id") != markers.run_id
            or audit_report.get("profile_contract") != PROFILE_CONTRACT
            or audit_report.get("reason_code") != "ok"
            or not isinstance(business_total, int)
            or (mode == "positive" and business_total <= 0)
            or (mode == "negative" and business_total != 0)
        ):
            raise ValueError("binding")
        return {
            "schema": "aneb-network-quick-evidence-binding",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": mode,
            "run_id": markers.run_id,
            "profile_contract": PROFILE_CONTRACT,
            "contract_status": markers.contract_status,
            "terminal_status": markers.terminal_status,
            "reason_code": expected_reason,
            "server_business_total": business_total,
        }
    except (CollectorError, TypeError, ValueError) as error:
        raise CollectorError("network_verifier_binding_invalid") from error


def verify_collected_network_evidence(
    *,
    logcat_text: str,
    journal_text: str,
    database: Path,
    run_id: str,
    start_barrier_id: str,
    barrier_id: str,
    mode: Literal["positive", "negative"],
    profile_path: Path,
    runtime_path: Path,
    manifest_path: Path,
    expected_server_base: str | None,
) -> dict[str, object]:
    """Recompute and bind one already-frozen Network Quick evidence set."""

    markers = parse_network_terminal_markers(logcat_text, mode=mode)
    if markers.run_id != run_id:
        raise CollectorError("network_verifier_binding_invalid")
    client_report = verify_database(
        database,
        run_id,
        mode,
        profile_path,
        runtime_path,
        manifest_path,
        expected_server_base,
    )
    audit_report = verify_journal(
        journal_text,
        run_id=run_id,
        start_barrier_id=start_barrier_id,
        barrier_id=barrier_id,
        mode=mode,
        profile_contract=PROFILE_CONTRACT,
    )
    return bind_network_verifier_reports(
        markers=markers,
        mode=mode,
        client_report=client_report,
        audit_report=audit_report,
    )


def validate_run_id(value: str) -> None:
    if RUN_ID_RE.fullmatch(value) is None:
        raise CollectorError("run_id_invalid")
    try:
        parsed = uuid.UUID(value)
    except ValueError as error:
        raise CollectorError("run_id_invalid") from error
    if parsed.version != 7 or parsed.variant != uuid.RFC_4122:
        raise CollectorError("run_id_invalid")


def _marker_records(text: str, marker: str) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    for line in text.splitlines():
        position = line.find(marker)
        if position < 0:
            continue
        payload = line[position:].strip()
        if not payload.startswith(marker + " "):
            continue
        fields = {
            match.group("key"): match.group("value")
            for match in FIELD_RE.finditer(payload)
        }
        records.append(fields)
    return records


def parse_network_terminal_markers(
    text: str,
    *,
    mode: Literal["positive", "negative"],
) -> NetworkTerminalMarkers:
    """Parse one exact durable Network Quick terminal marker chain."""

    try:
        starts = _marker_records(text, "NET_V1_START")
        profiles = _marker_records(text, "NET_V1_PROFILE")
        contracts = _marker_records(text, "NET_V1_CONTRACT")
        database_writes = _marker_records(text, "NET_V1_DB_WRITE")
        results = _marker_records(text, "NET_V1_RESULT")
        ends = _marker_records(text, "NET_V1_END")
        failures = _marker_records(text, "NET_V1_FAILED")
        if (
            mode not in {"positive", "negative"}
            or len(starts) != 1
            or len(database_writes) != 1
            or len(ends) != 1
            or failures
        ):
            raise ValueError("cardinality")
        run_ids = {
            record.get("run_id")
            for record in (*starts, *contracts, *database_writes, *results, *ends)
        }
        if len(run_ids) != 1 or None in run_ids:
            raise ValueError("run_binding")
        run_id = next(iter(run_ids))
        assert isinstance(run_id, str)
        validate_run_id(run_id)
        if database_writes[0] != {"run_id": run_id, "ok": "true"}:
            raise ValueError("database")
        if mode == "positive":
            if (
                len(profiles) != 1
                or profiles[0].get("id") != PROFILE_ID
                or profiles[0].get("version") != PROFILE_VERSION
                or contracts
                or len(results) != 1
                or results[0].get("run_id") != run_id
                or results[0].get("status") != "completed"
                or ends[0] != {"run_id": run_id, "status": "completed"}
            ):
                raise ValueError("positive")
            return NetworkTerminalMarkers(
                run_id=run_id,
                contract_status="authorized",
                terminal_status="completed",
                reason_code=None,
            )
        if (
            profiles
            or results
            or len(contracts) != 1
            or set(contracts[0]) != {"run_id", "status", "reason", "detail"}
            or contracts[0].get("run_id") != run_id
            or contracts[0].get("status") != "rejected"
            or contracts[0].get("reason") != "receipt_missing"
            or not contracts[0].get("detail")
            or ends[0] != {"run_id": run_id, "status": "contract_rejected"}
        ):
            raise ValueError("negative")
        return NetworkTerminalMarkers(
            run_id=run_id,
            contract_status="rejected",
            terminal_status="contract_rejected",
            reason_code="receipt_missing",
        )
    except (AssertionError, CollectorError, TypeError, ValueError) as error:
        raise CollectorError("network_marker_chain_invalid") from error


def build_network_launch_arguments(
    *,
    serial: str,
    server_base: str,
    transport: Literal["auto", "wifi", "cellular"],
    adb_path: str = "adb",
) -> list[str]:
    """Build the only approved autorun Intent for Network Quick."""

    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", serial) is None:
        raise CollectorError("adb_serial_invalid")
    if transport not in {"auto", "wifi", "cellular"}:
        raise CollectorError("transport_invalid")
    if (
        re.fullmatch(
            r"https://(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|"
            r"(?:[0-9]{1,3}\.){3}[0-9]{1,3})(?::[1-9][0-9]{0,4})?",
            server_base,
        )
        is None
        and server_base != f"http://127.0.0.1:{NEGATIVE_DEVICE_PORT}"
    ):
        raise CollectorError("server_base_invalid")
    return [
        adb_path,
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        ACTIVITY_COMPONENT,
        "--es",
        "server",
        server_base,
        "--ez",
        "autorun",
        "true",
        "--es",
        "mode",
        "quick",
        "--es",
        "transport",
        transport,
        "--es",
        "test_mode",
        "network_basic",
    ]


def build_network_audit_headers(
    *,
    run_id: str,
    role: Literal["window_start", "window_end"],
) -> dict[str, str]:
    """Build the exact E-01 barrier headers for one Network audit window."""

    try:
        parsed = uuid.UUID(run_id)
    except (TypeError, ValueError) as error:
        raise CollectorError("audit_header_contract_invalid") from error
    if (
        role not in {"window_start", "window_end"}
        or str(parsed) != run_id
        or parsed.version != 4
        or parsed.variant != uuid.RFC_4122
    ):
        raise CollectorError("audit_header_contract_invalid")
    return {
        "X-Aneb-Run-Id": run_id,
        "X-Aneb-Audit-Role": role,
        "X-Aneb-Audit-Scope": "network_run",
    }
