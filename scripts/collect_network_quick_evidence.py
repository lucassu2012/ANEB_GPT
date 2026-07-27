#!/usr/bin/env python3
"""Network Comprehensive Quick live-collector contracts.

This module freezes the Network-specific launch, lifecycle-marker and request
audit identities separately from the AI Realtime collector.  Importing it is
side-effect free; it never contacts a phone or server.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json
import os
import re
from typing import Literal, Mapping
import uuid

try:
    from scripts import collect_realtime_quick_evidence as mechanics
    from scripts.quick_collection_workflow import (
        CollectorError,
        WorkflowBackend,
        WorkflowResult,
        run_workflow,
    )
    from scripts.quick_collection_contract import network_quick_contract
    from scripts.verify_network_quick_client_db import verify_database
    from scripts.verify_network_quick_run_audit import verify_journal
except ModuleNotFoundError:  # Direct script execution from scripts/.
    import collect_realtime_quick_evidence as mechanics
    from quick_collection_contract import network_quick_contract
    from quick_collection_workflow import (
        CollectorError,
        WorkflowBackend,
        WorkflowResult,
        run_workflow,
    )
    from verify_network_quick_client_db import verify_database
    from verify_network_quick_run_audit import verify_journal


CONTRACT = network_quick_contract()
PACKAGE_NAME = CONTRACT.package_name
ACTIVITY_COMPONENT = CONTRACT.activity_component
PROFILE_CONTRACT = CONTRACT.profile_contract
PROFILE_ID = "network_comprehensive_quick"
PROFILE_VERSION = "1.2.0"
EXPECTED_VERSION_NAME = CONTRACT.expected_version_name
EXPECTED_VERSION_CODE = CONTRACT.expected_version_code
EXPECTED_SERVER_VERSION = CONTRACT.expected_server_version
CANDIDATE_APK_NAME = CONTRACT.candidate_apk_name
NEGATIVE_DEVICE_PORT = 18765
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
FIELD_RE = re.compile(r"(?P<key>[a-z_]+)=(?P<value>[^\s]+)")
NETWORK_PROFILE_SHA256 = (
    "15ae5187fac72d86b78ff89ad44d5a51706dc7c4e4cf01432f367acd9ed082cc"
)
REALTIME_PROFILE_SHA256 = (
    "701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7"
)
TOKEN_PROFILE_SHA256 = (
    "caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773"
)


@dataclass(frozen=True)
class NetworkTerminalMarkers:
    run_id: str
    contract_status: Literal["authorized", "rejected"]
    terminal_status: Literal["completed", "contract_rejected"]
    reason_code: str | None


def validate_network_serverinfo(body: object) -> None:
    """Reject any E-01 identity that is not the frozen Network Quick host."""

    try:
        if not isinstance(body, dict) or set(body) != {
            "version",
            "srv_ts_us",
            "anchor_wall_unix_ns",
            "uptime_s",
            "goos",
            "goarch",
            "h3_enabled",
            "tcp_slow_start_after_idle",
            "congestion_control",
            "execution_capabilities",
        }:
            raise ValueError("root")
        if (
            body.get("version") != EXPECTED_SERVER_VERSION
            or body.get("h3_enabled") is not True
            or body.get("goos") != "linux"
            or body.get("goarch") != "amd64"
            or body.get("tcp_slow_start_after_idle") != "0"
            or body.get("congestion_control") != "cubic"
            or type(body.get("srv_ts_us")) is not int
            or int(body["srv_ts_us"]) <= 0
            or type(body.get("anchor_wall_unix_ns")) is not int
            or int(body["anchor_wall_unix_ns"]) <= 0
            or type(body.get("uptime_s")) is not int
            or int(body["uptime_s"]) <= 0
        ):
            raise ValueError("identity")
        receipt = body.get("execution_capabilities")
        if (
            not isinstance(receipt, dict)
            or set(receipt)
            != {
                "contract_id",
                "contract_version",
                "primitives",
                "validated_profiles",
            }
            or receipt.get("contract_id")
            != "aneb-server-capability-receipt"
            or receipt.get("contract_version") != "1.0.0"
        ):
            raise ValueError("receipt")
        primitives = receipt.get("primitives")
        if not isinstance(primitives, list) or len(primitives) != 6:
            raise ValueError("primitives")
        primitive_map: dict[str, str] = {}
        for primitive in primitives:
            if (
                not isinstance(primitive, dict)
                or set(primitive) != {"primitive_id", "wire_contract_id"}
                or not isinstance(primitive.get("primitive_id"), str)
                or not isinstance(primitive.get("wire_contract_id"), str)
                or primitive["primitive_id"] in primitive_map
            ):
                raise ValueError("primitive")
            primitive_map[primitive["primitive_id"]] = primitive[
                "wire_contract_id"
            ]
        if primitive_map != {
            "download": "aneb-download-v1",
            "echo": "aneb-echo-v1",
            "realtime_sim": "aneb-realtime-session-v1",
            "token_sim": "aneb-token-task-v1",
            "udp_echo": "aneb-udp-echo-v2",
            "upload": "aneb-upload-v1",
        }:
            raise ValueError("primitive contract")
        profiles = receipt.get("validated_profiles")
        if not isinstance(profiles, list) or len(profiles) != 3:
            raise ValueError("profiles")
        profile_map: dict[str, tuple[str, str]] = {}
        for profile in profiles:
            if (
                not isinstance(profile, dict)
                or set(profile)
                != {"profile_id", "profile_version", "profile_sha256"}
                or not isinstance(profile.get("profile_id"), str)
                or profile["profile_id"] in profile_map
                or not isinstance(profile.get("profile_version"), str)
                or re.fullmatch(
                    r"sha256:[0-9a-f]{64}",
                    str(profile.get("profile_sha256")),
                )
                is None
            ):
                raise ValueError("profile")
            profile_map[profile["profile_id"]] = (
                profile["profile_version"],
                profile["profile_sha256"],
            )
        if profile_map != {
            "ai_realtime_voice_quick": (
                "1.1.1",
                f"sha256:{REALTIME_PROFILE_SHA256}",
            ),
            "network_comprehensive_quick": (
                "1.2.0",
                f"sha256:{NETWORK_PROFILE_SHA256}",
            ),
            "token_multimodal_quick": (
                "1.2.1",
                f"sha256:{TOKEN_PROFILE_SHA256}",
            ),
        }:
            raise ValueError("profile contract")
    except (KeyError, TypeError, ValueError) as error:
        raise CollectorError("network_serverinfo_contract_invalid") from error


def assert_network_serverinfo_sequence(
    identity: object,
    start: object,
    end: object,
) -> None:
    """Bind three captures to one stable E-01 identity and time window."""

    try:
        for item in (identity, start, end):
            validate_network_serverinfo(item)
        if not all(isinstance(item, dict) for item in (identity, start, end)):
            raise ValueError("shape")
        identity_document = identity
        start_document = start
        end_document = end
        assert isinstance(identity_document, dict)
        assert isinstance(start_document, dict)
        assert isinstance(end_document, dict)
        stable_keys = (
            "version",
            "anchor_wall_unix_ns",
            "goos",
            "goarch",
            "h3_enabled",
            "tcp_slow_start_after_idle",
            "congestion_control",
            "execution_capabilities",
        )
        if any(
            identity_document[key] != candidate[key]
            for candidate in (start_document, end_document)
            for key in stable_keys
        ):
            raise ValueError("stable identity")
        if not (
            int(identity_document["srv_ts_us"])
            < int(start_document["srv_ts_us"])
            < int(end_document["srv_ts_us"])
            and int(identity_document["uptime_s"])
            <= int(start_document["uptime_s"])
            <= int(end_document["uptime_s"])
        ):
            raise ValueError("chronology")
    except (
        AssertionError,
        CollectorError,
        KeyError,
        TypeError,
        ValueError,
    ) as error:
        raise CollectorError("network_serverinfo_sequence_invalid") from error


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
    return compute_network_verifier_reports(
        markers=markers,
        journal_text=journal_text,
        database=database,
        start_barrier_id=start_barrier_id,
        barrier_id=barrier_id,
        mode=mode,
        profile_path=profile_path,
        runtime_path=runtime_path,
        manifest_path=manifest_path,
        expected_server_base=expected_server_base,
    )["binding"]


def compute_network_verifier_reports(
    *,
    markers: NetworkTerminalMarkers,
    journal_text: str,
    database: Path,
    start_barrier_id: str,
    barrier_id: str,
    mode: Literal["positive", "negative"],
    profile_path: Path,
    runtime_path: Path,
    manifest_path: Path,
    expected_server_base: str | None,
) -> dict[str, dict[str, object]]:
    """Return all three reports so an independent consumer can compare each."""

    client_report = verify_database(
        database,
        markers.run_id,
        mode,
        profile_path,
        runtime_path,
        manifest_path,
        expected_server_base,
    )
    audit_report = verify_journal(
        journal_text,
        run_id=markers.run_id,
        start_barrier_id=start_barrier_id,
        barrier_id=barrier_id,
        mode=mode,
        profile_contract=PROFILE_CONTRACT,
    )
    binding = bind_network_verifier_reports(
        markers=markers,
        mode=mode,
        client_report=client_report,
        audit_report=audit_report,
    )
    return {
        "client": dict(client_report),
        "audit": dict(audit_report),
        "binding": binding,
    }


def _write_exclusive_json(path: Path, value: object) -> None:
    payload = (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")
    try:
        descriptor = os.open(
            path,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_BINARY", 0),
            0o600,
        )
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except OSError as error:
        raise CollectorError("network_verifier_report_write_failed") from error


def run_network_verifiers(
    *,
    evidence_directory: Path,
    markers: NetworkTerminalMarkers,
    mode: Literal["positive", "negative"],
    database: Path,
    journal_path: Path,
    profile_path: Path,
    runtime_path: Path,
    manifest_path: Path,
    expected_server_base: str | None,
    start_barrier_id: str,
    end_barrier_id: str,
) -> dict[str, object]:
    """Recompute and persist both independent reports before binding them."""

    try:
        if (
            not evidence_directory.is_dir()
            or evidence_directory.is_symlink()
            or not journal_path.is_file()
            or journal_path.is_symlink()
        ):
            raise ValueError("paths")
        journal_text = journal_path.read_text(encoding="utf-8", errors="strict")
        reports = compute_network_verifier_reports(
            markers=markers,
            journal_text=journal_text,
            database=database,
            start_barrier_id=start_barrier_id,
            barrier_id=end_barrier_id,
            mode=mode,
            profile_path=profile_path,
            runtime_path=runtime_path,
            manifest_path=manifest_path,
            expected_server_base=expected_server_base,
        )
        _write_exclusive_json(
            evidence_directory / "client-db-verification.json",
            reports["client"],
        )
        _write_exclusive_json(
            evidence_directory / "server-audit-verification.json",
            reports["audit"],
        )
        _write_exclusive_json(
            evidence_directory / "cross-bound-report.json",
            reports["binding"],
        )
        return reports["binding"]
    except (OSError, UnicodeError, ValueError) as error:
        raise CollectorError("network_verifier_execution_failed") from error


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
        "X-Aneb-Audit-Scope": CONTRACT.audit_scope,
    }


class NetworkLiveCollectorBackend(mechanics.LiveCollectorBackend):
    """Network semantics over the shared, already-proven live mechanics."""

    def __init__(
        self,
        config: mechanics.CollectorConfig,
        *,
        install_candidate: bool,
        runner: mechanics.CommandRunner | None = None,
    ) -> None:
        super().__init__(
            config,
            install_candidate=install_candidate,
            runner=runner,
            contract=CONTRACT,
        )

    def _validate_serverinfo(self, body: object) -> None:
        validate_network_serverinfo(body)

    def _assert_serverinfo_sequence(
        self,
        identity: object,
        start: object,
        end: object,
    ) -> None:
        assert_network_serverinfo_sequence(identity, start, end)

    def _audit_headers(
        self,
        *,
        run_id: str,
        role: Literal["window_start", "window_end"],
    ) -> dict[str, str]:
        return build_network_audit_headers(run_id=run_id, role=role)

    def _build_launch_arguments(self) -> list[str]:
        return build_network_launch_arguments(
            serial=self.config.adb_serial,
            server_base=self.client_server_base,
            transport=self.config.transport,
            adb_path=self.adb.executable,
        )

    def _parse_terminal_markers(
        self,
        text: str,
        *,
        mode: Literal["positive", "negative"],
    ) -> object:
        return parse_network_terminal_markers(text, mode=mode)

    def _run_category_verifiers(
        self,
        *,
        run_id: str,
        serverinfo_path: Path,
    ) -> dict[str, object]:
        del serverinfo_path
        if self.run_markers is None or self.run_markers.run_id != run_id:
            raise CollectorError("network_verifier_binding_invalid")
        profile_directory = (
            Path(__file__).resolve().parents[1]
            / "profiles"
            / "published"
            / PROFILE_ID
        )
        frozen_profile = self.partial / "network-profile"
        try:
            frozen_profile.mkdir(mode=0o700)
            for leaf in ("profile.json", "runtime_plan.json", "manifest.sha256"):
                source = profile_directory / leaf
                if not source.is_file() or source.is_symlink():
                    raise OSError("profile source")
                mechanics._write_exclusive_bytes(
                    frozen_profile / leaf,
                    source.read_bytes(),
                )
        except OSError as error:
            raise CollectorError("network_profile_freeze_failed") from error
        return run_network_verifiers(
            evidence_directory=self.partial,
            markers=self.run_markers,
            mode=self.config.evidence_mode,
            database=self.partial / "aneb-probe.db",
            journal_path=self.partial / "journal.raw.log",
            profile_path=frozen_profile / "profile.json",
            runtime_path=frozen_profile / "runtime_plan.json",
            manifest_path=frozen_profile / "manifest.sha256",
            expected_server_base=self.client_server_base,
            start_barrier_id=self.start_barrier_id,
            end_barrier_id=self.end_barrier_id,
        )

    def _verify_before_atomic_publish(self) -> dict[str, object]:
        if __package__:
            from scripts import verify_network_quick_collection as verifier
        else:
            import verify_network_quick_collection as verifier
        return verifier.verify_collection(
            self.partial,
            expected_collection=self.collection_id,
            allow_partial=True,
        )

    def _publish_ready(self) -> dict[str, object]:
        if __package__:
            from scripts import publish_network_quick_ready as publisher
        else:
            import publish_network_quick_ready as publisher
        return publisher.publish_ready(self.complete)

    def _verify_release(self, ready_path: Path) -> dict[str, object]:
        if __package__:
            from scripts import verify_network_quick_release as verifier
        else:
            import verify_network_quick_release as verifier
        return verifier.verify_release(ready_path)
