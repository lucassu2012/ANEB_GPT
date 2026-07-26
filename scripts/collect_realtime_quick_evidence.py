#!/usr/bin/env python3
"""Collect one bounded AI Realtime Quick cross-bound evidence bundle.

The live orchestrator is intentionally built from small, testable contracts.
Importing this module never contacts a phone or server.  External operations
only happen through the CLI/backend that is assembled in :func:`main`.
"""

from __future__ import annotations

from dataclasses import dataclass
import argparse
import base64
import contextlib
import hashlib
import json
import ipaddress
import os
from pathlib import Path
import queue
import re
import shlex
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, BinaryIO, Literal, Mapping, Protocol, Sequence

if __package__:
    from scripts import (
        publish_realtime_quick_ready as ready_publisher,
        verify_realtime_evidence_security as evidence_security,
        verify_realtime_quick_collection as collection_verifier,
        verify_realtime_quick_release as release_verifier,
    )
else:
    import publish_realtime_quick_ready as ready_publisher
    import verify_realtime_evidence_security as evidence_security
    import verify_realtime_quick_collection as collection_verifier
    import verify_realtime_quick_release as release_verifier


PACKAGE_NAME = "com.aneb.probe.codex"
ACTIVITY_COMPONENT = (
    "com.aneb.probe.codex/com.aneb.probe.ui.MainActivity"
)
LAUNCHER_COMPONENT = "com.huawei.android.launcher/.unihome.UniHomeLauncher"
REMOTE_LOCK_PATH = "/run/lock/aneb-deploy.lock"
PROFILE_CONTRACT = "ai_realtime_voice_quick@1.1.1"
NEGATIVE_DEVICE_PORT = 18765
EXPECTED_VERSION_NAME = "0.5.13-codex"
EXPECTED_VERSION_CODE = 45
EXPECTED_SERVER_VERSION = "aneb-server/0.8.1"
REALTIME_PROFILE_SHA256 = (
    "701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7"
)
TOKEN_PROFILE_SHA256 = (
    "caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773"
)
MAX_COMMAND_OUTPUT_BYTES = 16 * 1024 * 1024
MAX_APK_BYTES = 256 * 1024 * 1024
CI_CANDIDATE_NAMES = frozenset(
    {
        "ANEB-Probe-0.5.13-codex-debug.apk",
        "build-manifest.json",
        "checksums.sha256",
        "provenance.sigstore.json",
        "ANEB-安装说明.txt",
    }
)

RELEVANT_PACKAGES = (
    "com.aneb.probe.codex",
    "com.aneb.probe",
    "com.aneb.experiencelab",
    "com.moonshot.kimichat",
    "com.moonshot.kimiclaw",
    "com.deepseek.chat",
    "com.larus.nova",
    "com.aliyun.tongyi",
    "com.ss.android.ugc.aweme",
    "com.wireguard.android",
    "com.emanuelef.remote_capture",
    "com.pcapdroid.mitm",
)

COMPONENT_RE = re.compile(
    r"(?P<package>[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"
    r"/(?P<activity>[A-Za-z0-9_.$]+)"
)
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
FIELD_RE = re.compile(r"(?P<key>[a-z_]+)=(?P<value>[^\s]+)")
TUNNEL_INTERFACE_RE = re.compile(
    r"^(?:tun[0-9]*|tap[0-9]*|wg[0-9A-Za-z_.-]*|wireguard[0-9A-Za-z_.-]*)$",
    re.IGNORECASE,
)
DEVICE_PROPERTY_KEYS = (
    "ro.serialno",
    "ro.boot.serialno",
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.product.device",
    "ro.product.name",
    "ro.build.fingerprint",
    "ro.build.version.security_patch",
    "ro.boot.verifiedbootstate",
    "ro.boot.vbmeta.device_state",
    "ro.boot.flash.locked",
    "ro.boot.veritymode",
)
OPTIONAL_DEVICE_PROPERTY_KEYS = frozenset(
    {
        "ro.serialno",
        "ro.boot.serialno",
        "ro.boot.verifiedbootstate",
        "ro.boot.vbmeta.device_state",
        "ro.boot.flash.locked",
        "ro.boot.veritymode",
    }
)


class CollectorError(RuntimeError):
    """A bounded collector contract rejected the current state."""


@dataclass(frozen=True)
class ProcessResult:
    returncode: int
    stdout: bytes
    stderr: bytes


class CommandRunner(Protocol):
    def run(
        self,
        arguments: Sequence[str],
        *,
        timeout_seconds: float,
        max_output_bytes: int = MAX_COMMAND_OUTPUT_BYTES,
        stdin: bytes | None = None,
    ) -> ProcessResult: ...


class SubprocessRunner:
    """Run an argv-only child with bounded time and captured output."""

    def run(
        self,
        arguments: Sequence[str],
        *,
        timeout_seconds: float,
        max_output_bytes: int = MAX_COMMAND_OUTPUT_BYTES,
        stdin: bytes | None = None,
    ) -> ProcessResult:
        if not arguments or timeout_seconds <= 0 or max_output_bytes <= 0:
            raise CollectorError("command_contract_invalid")
        try:
            process_stdio: dict[str, object]
            if stdin is None:
                process_stdio = {"stdin": subprocess.DEVNULL}
            else:
                process_stdio = {"input": stdin}
            completed = subprocess.run(
                list(arguments),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout_seconds,
                check=False,
                shell=False,
                **process_stdio,
            )
        except subprocess.TimeoutExpired as error:
            raise CollectorError("command_timeout") from error
        except OSError as error:
            raise CollectorError("command_launch_failed") from error
        if (
            not isinstance(completed.stdout, bytes)
            or not isinstance(completed.stderr, bytes)
            or len(completed.stdout) > max_output_bytes
            or len(completed.stderr) > max_output_bytes
        ):
            raise CollectorError("command_output_limit")
        return ProcessResult(
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )


def _decode_utf8(payload: bytes, code: str) -> str:
    try:
        return payload.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise CollectorError(code) from error


def _checked(
    runner: CommandRunner,
    arguments: Sequence[str],
    *,
    timeout_seconds: float,
    code: str,
    max_output_bytes: int = MAX_COMMAND_OUTPUT_BYTES,
) -> ProcessResult:
    result = runner.run(
        arguments,
        timeout_seconds=timeout_seconds,
        max_output_bytes=max_output_bytes,
    )
    if result.returncode != 0:
        raise CollectorError(f"{code} rc={result.returncode}")
    return result


@dataclass(frozen=True)
class PhoneSnapshot:
    focused_component: str
    resumed_components: tuple[str, ...]
    processes: tuple[tuple[str, str], ...]
    services: tuple[tuple[str, str], ...]
    enabled_accessibility: str
    interfaces: tuple[str, ...]
    active_vpn: bool
    stayon: str
    wifi_on: str
    canonical_sha256: str


@dataclass(frozen=True)
class RealtimeTerminalMarkers:
    run_id: str
    contract_status: str
    terminal_status: str
    reason_code: str | None


@dataclass(frozen=True)
class WorkflowResult:
    success: bool
    primary_failure: str | None
    cleanup_failures: tuple[str, ...]
    publish_failure: str | None


@dataclass(frozen=True)
class CollectorConfig:
    adb_serial: str
    server_base: str
    remote: str
    ssh_key: Path
    known_hosts: Path
    device_policy: Path
    candidate_directory: Path
    gh_path: Path
    expected_server_binary_sha256: str
    evidence_mode: Literal["positive", "negative"]
    transport: Literal["auto", "wifi", "cellular"]
    evidence_root: Path
    adb_path: Path
    ssh_path: Path
    python_path: Path
    server_ca_path: Path
    source_commit: str
    run_timeout_seconds: int
    lock_ttl_seconds: int
    command_timeout_seconds: int


@dataclass(frozen=True)
class RemoteSnapshot:
    boot_id: str
    systemd_invocation_id: str
    main_pid: str
    server_binary_sha256: str
    eth0_qdisc_sha256: str
    firewall_full_sha256: str
    firewall_v4_sha256: str
    firewall_v6_sha256: str
    firewall_nft_sha256: str
    docker_sha256: str
    journal_cursor: str


@dataclass(frozen=True)
class CiCandidateIdentity:
    apk_file_name: str
    apk_sha256: str
    apk_size_bytes: int
    signer_sha256: str
    workflow_run_id: int
    workflow_run_url: str


@dataclass(frozen=True)
class HttpCapture:
    status: int
    headers: tuple[tuple[str, str], ...]
    body: bytes
    json_body: dict[str, object]


class WorkflowBackend(Protocol):
    def preflight(self) -> None: ...

    def acquire(self) -> None: ...

    def collect(self) -> None: ...

    def cleanup_phone(self) -> None: ...

    def cleanup_remote(self) -> None: ...

    def publish(self) -> None: ...


def build_audit_headers(
    *,
    run_id: str,
    role: Literal["window_start", "window_end"],
) -> dict[str, str]:
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
        "X-Aneb-Audit-Scope": "realtime_run",
    }


def parse_reverse_inventory(text: str) -> tuple[tuple[str, str, str], ...]:
    entries: list[tuple[str, str, str]] = []
    seen: set[tuple[str, str]] = set()
    for line in text.splitlines():
        if not line:
            continue
        parts = line.split()
        if (
            len(parts) != 3
            or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{3,127}", parts[0])
            is None
            or re.fullmatch(r"tcp:[1-9][0-9]{0,4}", parts[1]) is None
            or re.fullmatch(r"tcp:[1-9][0-9]{0,4}", parts[2]) is None
            or int(parts[1][4:]) > 65535
            or int(parts[2][4:]) > 65535
            or (parts[0], parts[1]) in seen
        ):
            raise CollectorError("adb_reverse_inventory_invalid")
        seen.add((parts[0], parts[1]))
        entries.append((parts[0], parts[1], parts[2]))
    return tuple(entries)


def _owned_reverse(
    inventory: tuple[tuple[str, str, str], ...],
    *,
    transport_label: str,
    device_port: int,
) -> tuple[tuple[str, str, str], ...]:
    local = f"tcp:{device_port}"
    return tuple(
        entry
        for entry in inventory
        if entry[0] == transport_label and entry[1] == local
    )


def assert_reverse_absent(
    inventory: tuple[tuple[str, str, str], ...],
    *,
    device_port: int,
) -> None:
    endpoint = f"tcp:{device_port}"
    if any(entry[1] == endpoint for entry in inventory):
        raise CollectorError("negative_reverse_preexisting")


def assert_owned_reverse(
    inventory: tuple[tuple[str, str, str], ...],
    *,
    transport_label: str,
    device_port: int,
) -> None:
    endpoint = f"tcp:{device_port}"
    if _owned_reverse(
        inventory,
        transport_label=transport_label,
        device_port=device_port,
    ) != ((transport_label, endpoint, endpoint),) or len(inventory) != 1:
        raise CollectorError("negative_reverse_ownership_invalid")


def _exact_keys(
    value: object,
    keys: set[str],
) -> bool:
    return isinstance(value, dict) and set(value) == keys


def validate_ci_provenance_report(
    report: object,
    *,
    source_commit: str,
) -> CiCandidateIdentity:
    try:
        if not _exact_keys(
            report,
            {
                "schema",
                "schema_version",
                "status",
                "reason_code",
                "candidate_provenance_reverified",
                "repository",
                "signer_workflow",
                "predicate_type",
                "source_commit",
                "source_ref",
                "workflow_run_id",
                "workflow_run_url",
                "apk",
                "files",
                "gh",
            },
        ):
            raise ValueError("shape")
        assert isinstance(report, dict)
        run_id = report["workflow_run_id"]
        run_url = report["workflow_run_url"]
        if (
            report["schema"] != "aneb-ci-apk-provenance-report"
            or report["schema_version"] != "1.0.0"
            or report["status"] != "pass"
            or report["reason_code"] != "ok"
            or report["candidate_provenance_reverified"] is not True
            or report["repository"] != "lucassu2012/ANEB_GPT"
            or report["signer_workflow"]
            != "lucassu2012/ANEB_GPT/.github/workflows/ci.yml"
            or report["predicate_type"] != "https://slsa.dev/provenance/v1"
            or report["source_commit"] != source_commit
            or re.fullmatch(r"refs/(?:heads|tags)/[^\r\n]{1,512}", str(report["source_ref"]))
            is None
            or type(run_id) is not int
            or run_id <= 0
            or run_url
            != f"https://github.com/lucassu2012/ANEB_GPT/actions/runs/{run_id}"
        ):
            raise ValueError("identity")
        apk = report["apk"]
        files = report["files"]
        gh = report["gh"]
        if (
            not _exact_keys(
                apk,
                {
                    "file_name",
                    "sha256",
                    "size_bytes",
                    "package_name",
                    "version_name",
                    "version_code",
                    "signer_sha256",
                },
            )
            or not _exact_keys(
                files,
                {
                    "attestation_bundle_sha256",
                    "build_manifest_sha256",
                    "checksums_sha256",
                },
            )
            or not _exact_keys(
                gh,
                {
                    "version",
                    "executable_sha256",
                    "certificate_issuer",
                    "oidc_issuer",
                    "runner_environment",
                    "run_invocation_uri",
                    "subject_alternative_name",
                    "verified_timestamp_count",
                },
            )
        ):
            raise ValueError("nested shape")
        assert isinstance(apk, dict)
        assert isinstance(files, dict)
        assert isinstance(gh, dict)
        if (
            apk["file_name"] != "ANEB-Probe-0.5.13-codex-debug.apk"
            or apk["package_name"] != PACKAGE_NAME
            or apk["version_name"] != EXPECTED_VERSION_NAME
            or type(apk["version_code"]) is not int
            or apk["version_code"] != EXPECTED_VERSION_CODE
            or type(apk["size_bytes"]) is not int
            or not 0 < apk["size_bytes"] <= MAX_APK_BYTES
            or re.fullmatch(r"[0-9a-f]{64}", str(apk["sha256"])) is None
            or re.fullmatch(r"[0-9a-f]{64}", str(apk["signer_sha256"])) is None
            or any(
                re.fullmatch(r"[0-9a-f]{64}", str(files[key])) is None
                for key in files
            )
            or re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", str(gh["version"]))
            is None
            or re.fullmatch(r"[0-9a-f]{64}", str(gh["executable_sha256"]))
            is None
            or gh["runner_environment"] != "github-hosted"
            or type(gh["verified_timestamp_count"]) is not int
            or gh["verified_timestamp_count"] <= 0
        ):
            raise ValueError("candidate")
        return CiCandidateIdentity(
            apk_file_name=str(apk["file_name"]),
            apk_sha256=str(apk["sha256"]),
            apk_size_bytes=int(apk["size_bytes"]),
            signer_sha256=str(apk["signer_sha256"]),
            workflow_run_id=run_id,
            workflow_run_url=str(run_url),
        )
    except (AssertionError, KeyError, TypeError, ValueError) as error:
        raise CollectorError("ci_provenance_report_invalid") from error


def _canonical_component(value: str) -> str:
    match = COMPONENT_RE.search(value)
    if match is None:
        raise CollectorError("phone_live_state_rejected reason=focus_component_invalid")
    package = match.group("package")
    activity = match.group("activity")
    if activity.startswith("."):
        activity = package + activity
    return f"{package}/{activity}"


def _extract_focus(activity: str) -> tuple[str, tuple[str, ...]]:
    focused_lines = [
        line for line in activity.splitlines() if re.match(r"^\s*mFocusedApp=", line)
    ]
    resumed_lines = [
        line
        for line in activity.splitlines()
        if re.match(
            r"^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]",
            line,
        )
    ]
    if len(focused_lines) != 1 or not resumed_lines:
        raise CollectorError("phone_live_state_rejected reason=focus_ambiguous")
    focused = _canonical_component(focused_lines[0])
    resumed = tuple(_canonical_component(line) for line in resumed_lines)
    return focused, resumed


def _active_vpn(connectivity: str) -> bool:
    for line in connectivity.splitlines():
        if "NetworkAgentInfo{" not in line:
            continue
        if re.search(r"(?i)(?:Transports?:\s*VPN|type:\s*VPN)", line) is None:
            continue
        active = re.search(
            r"(?i)(?:state:\s*CONNECTED(?:/CONNECTED)?|"
            r"CONNECTED/CONNECTED|\bVALIDATED\b)",
            line,
        )
        disconnected = re.search(
            r"(?i)(?:state:\s*DISCONNECTED|DISCONNECTED/DISCONNECTED)",
            line,
        )
        if active is not None and disconnected is None:
            return True
    return False


def _snapshot_payload(snapshot: PhoneSnapshot) -> dict[str, object]:
    return {
        "focused_component": snapshot.focused_component,
        "resumed_components": list(snapshot.resumed_components),
        "processes": [list(item) for item in snapshot.processes],
        "services": [list(item) for item in snapshot.services],
        "enabled_accessibility": snapshot.enabled_accessibility,
        "interfaces": list(snapshot.interfaces),
        "active_vpn": snapshot.active_vpn,
        "stayon": snapshot.stayon,
        "wifi_on": snapshot.wifi_on,
    }


def clean_snapshot_hash(snapshot: PhoneSnapshot) -> str:
    raw = (
        json.dumps(
            _snapshot_payload(snapshot),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _is_empty_service_dump(value: str) -> bool:
    normalized = value.strip()
    return normalized in {"", "(nothing)", "No services"} or (
        re.fullmatch(
            r"ACTIVITY MANAGER SERVICES \(dumpsys activity services\)\r?\n"
            r"[ \t]*\(nothing\)",
            normalized,
        )
        is not None
    )


def parse_phone_snapshot(raw: dict[str, object]) -> PhoneSnapshot:
    expected = {
        "device_state",
        "current_user",
        "activity",
        "processes",
        "services",
        "enabled_accessibility",
        "accessibility_dump",
        "interfaces",
        "connectivity",
        "vpn",
        "stayon",
        "wifi_on",
    }
    if set(raw) != expected:
        raise CollectorError("phone_live_state_rejected reason=snapshot_shape")
    if raw["device_state"] != "device" or raw["current_user"] != "0":
        raise CollectorError("phone_live_state_rejected reason=device_unavailable")
    if not isinstance(raw["activity"], str):
        raise CollectorError("phone_live_state_rejected reason=activity_invalid")
    focused, resumed = _extract_focus(raw["activity"])
    if focused != _canonical_component(LAUNCHER_COMPONENT) or any(
        item != _canonical_component(LAUNCHER_COMPONENT) for item in resumed
    ):
        raise CollectorError("phone_live_state_rejected reason=not_launcher")

    processes_raw = raw["processes"]
    services_raw = raw["services"]
    if (
        not isinstance(processes_raw, dict)
        or not isinstance(services_raw, dict)
        or set(processes_raw) != set(RELEVANT_PACKAGES)
        or set(services_raw) != set(RELEVANT_PACKAGES)
    ):
        raise CollectorError("phone_live_state_rejected reason=runtime_shape")
    processes: list[tuple[str, str]] = []
    services: list[tuple[str, str]] = []
    for package in RELEVANT_PACKAGES:
        process = processes_raw[package]
        service = services_raw[package]
        if not isinstance(process, str) or not isinstance(service, str):
            raise CollectorError("phone_live_state_rejected reason=runtime_type")
        process = process.strip()
        service = service.strip()
        if process:
            raise CollectorError(
                f"phone_live_state_rejected reason=process_active package={package}"
            )
        if not _is_empty_service_dump(service):
            raise CollectorError(
                f"phone_live_state_rejected reason=service_active package={package}"
            )
        processes.append((package, process))
        services.append((package, ""))

    accessibility = raw["enabled_accessibility"]
    if not isinstance(accessibility, str):
        raise CollectorError("phone_live_state_rejected reason=accessibility_invalid")
    accessibility = accessibility.strip()
    if any(package in accessibility for package in RELEVANT_PACKAGES):
        raise CollectorError(
            "phone_live_state_rejected reason=relevant_accessibility_enabled"
        )
    accessibility_dump = raw["accessibility_dump"]
    if not isinstance(accessibility_dump, str):
        raise CollectorError(
            "phone_live_state_rejected reason=accessibility_dump_invalid"
        )
    for line in accessibility_dump.splitlines():
        if (
            re.search(r"(?i)\b(?:bound|enabled)\b.*\bservices?\b", line)
            and any(package in line for package in RELEVANT_PACKAGES)
        ):
            raise CollectorError(
                "phone_live_state_rejected reason=relevant_accessibility_bound"
            )

    interfaces_raw = raw["interfaces"]
    if not isinstance(interfaces_raw, str):
        raise CollectorError("phone_live_state_rejected reason=interfaces_invalid")
    interfaces = tuple(
        sorted({line.strip() for line in interfaces_raw.splitlines() if line.strip()})
    )
    if any(TUNNEL_INTERFACE_RE.fullmatch(name) for name in interfaces):
        raise CollectorError("phone_live_state_rejected reason=tunnel_active")

    connectivity = raw["connectivity"]
    if not isinstance(connectivity, str):
        raise CollectorError("phone_live_state_rejected reason=connectivity_invalid")
    active_vpn = _active_vpn(connectivity)
    vpn_dump = raw["vpn"]
    if not isinstance(vpn_dump, str):
        raise CollectorError("phone_live_state_rejected reason=vpn_dump_invalid")
    vpn_service_active = any(
        "LISTEN" not in line.upper()
        and re.search(
            r"(?i)(?:state\s*[:=]\s*CONNECTED|"
            r"mNetworkInfo.*\bCONNECTED(?:/CONNECTED)?\b)",
            line,
        )
        is not None
        for line in vpn_dump.splitlines()
    )
    if active_vpn or vpn_service_active:
        raise CollectorError("phone_live_state_rejected reason=active_vpn")

    stayon = raw["stayon"]
    wifi_on = raw["wifi_on"]
    if not isinstance(stayon, str) or not re.fullmatch(r"(?:null|[0-9]+)", stayon):
        raise CollectorError("phone_live_state_rejected reason=stayon_invalid")
    if not isinstance(wifi_on, str) or wifi_on not in {"0", "1"}:
        raise CollectorError("phone_live_state_rejected reason=wifi_state_invalid")

    provisional = PhoneSnapshot(
        focused_component=focused,
        resumed_components=resumed,
        processes=tuple(processes),
        services=tuple(services),
        enabled_accessibility=accessibility,
        interfaces=interfaces,
        active_vpn=active_vpn,
        stayon=stayon,
        wifi_on=wifi_on,
        canonical_sha256="",
    )
    return PhoneSnapshot(
        **{
            **provisional.__dict__,
            "canonical_sha256": clean_snapshot_hash(provisional),
        }
    )


def assert_stable_phone_snapshots(
    first: PhoneSnapshot,
    second: PhoneSnapshot,
) -> None:
    if (
        first.canonical_sha256 != second.canonical_sha256
        or clean_snapshot_hash(first) != first.canonical_sha256
        or clean_snapshot_hash(second) != second.canonical_sha256
    ):
        raise CollectorError("phone_state_not_stable")


def _has_reparse_component(path: Path) -> bool:
    current = path
    while True:
        if current.exists():
            try:
                attributes = current.stat(follow_symlinks=False).st_file_attributes
            except AttributeError:
                attributes = 0
            if current.is_symlink() or attributes & 0x400:
                return True
        if current.parent == current:
            return False
        current = current.parent


def _regular_file(path: Path, code: str) -> Path:
    if (
        not path.is_absolute()
        or not path.is_file()
        or path.is_symlink()
        or _has_reparse_component(path)
        or path.stat().st_size <= 0
    ):
        raise CollectorError(code)
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise CollectorError(code) from error
    if resolved != path:
        raise CollectorError(code)
    return resolved


def _regular_directory(path: Path, code: str) -> Path:
    if (
        not path.is_absolute()
        or not path.is_dir()
        or path.is_symlink()
        or _has_reparse_component(path)
    ):
        raise CollectorError(code)
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise CollectorError(code) from error
    if resolved != path:
        raise CollectorError(code)
    return resolved


def validate_config(config: CollectorConfig) -> None:
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", config.adb_serial) is None:
        raise CollectorError("adb_serial_invalid")
    parsed = urllib.parse.urlsplit(config.server_base)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise CollectorError("server_base_invalid")
    try:
        port = parsed.port
    except ValueError as error:
        raise CollectorError("server_base_invalid") from error
    if port is None:
        port = 443
    if not 1 <= port <= 65535:
        raise CollectorError("server_base_invalid")
    remote_match = re.fullmatch(
        r"(?P<user>[A-Za-z0-9._-]+)@(?P<host>[A-Za-z0-9.-]+)",
        config.remote,
    )
    if remote_match is None:
        raise CollectorError("remote_server_host_binding_invalid")
    remote_host = remote_match.group("host")
    try:
        server_address = ipaddress.ip_address(parsed.hostname)
        remote_address = ipaddress.ip_address(remote_host)
        hosts_match = (
            server_address.version == 4
            and remote_address.version == 4
            and server_address == remote_address
        )
    except ValueError:
        hosts_match = remote_host.casefold() == parsed.hostname.casefold()
    if not hosts_match:
        raise CollectorError("remote_server_host_binding_invalid")
    if re.fullmatch(r"[0-9a-f]{40}", config.source_commit) is None:
        raise CollectorError("source_commit_invalid")
    if (
        re.fullmatch(r"[0-9a-fA-F]{64}", config.expected_server_binary_sha256)
        is None
    ):
        raise CollectorError("expected_server_binary_sha256_invalid")
    if config.evidence_mode not in {"positive", "negative"}:
        raise CollectorError("evidence_mode_invalid")
    if config.transport not in {"auto", "wifi", "cellular"}:
        raise CollectorError("transport_invalid")
    if (
        not 60 <= config.run_timeout_seconds <= 1800
        or not 120 <= config.lock_ttl_seconds <= 3600
        or not 1 <= config.command_timeout_seconds <= 300
        or config.lock_ttl_seconds
        <= (
            config.run_timeout_seconds
            + 5 * config.command_timeout_seconds
            + 180
        )
    ):
        raise CollectorError("timeout_contract_invalid")
    for path, code in (
        (config.ssh_key, "ssh_key_invalid"),
        (config.known_hosts, "known_hosts_invalid"),
        (config.device_policy, "device_policy_invalid"),
        (config.gh_path, "gh_path_invalid"),
        (config.adb_path, "adb_path_invalid"),
        (config.ssh_path, "ssh_path_invalid"),
        (config.python_path, "python_path_invalid"),
        (config.server_ca_path, "server_ca_invalid"),
    ):
        _regular_file(path, code)
    candidate = _regular_directory(
        config.candidate_directory,
        "candidate_directory_invalid",
    )
    if {path.name for path in candidate.iterdir()} != CI_CANDIDATE_NAMES or any(
        not path.is_file() or path.is_symlink() or path.stat().st_size <= 0
        for path in candidate.iterdir()
    ):
        raise CollectorError("candidate_file_set_invalid")
    evidence_parent = config.evidence_root
    while not evidence_parent.exists():
        if evidence_parent.parent == evidence_parent:
            raise CollectorError("evidence_root_parent_invalid")
        evidence_parent = evidence_parent.parent
    _regular_directory(evidence_parent.resolve(), "evidence_root_parent_invalid")


class AdbClient:
    def __init__(
        self,
        *,
        runner: CommandRunner,
        executable: Path,
        serial: str,
        timeout_seconds: int,
    ) -> None:
        self.runner = runner
        self.executable = str(executable)
        self.serial = serial
        self.timeout_seconds = timeout_seconds

    def run(
        self,
        tail: Sequence[str],
        *,
        code: str,
        allowed_returncodes: frozenset[int] | None = frozenset({0}),
        max_output_bytes: int = MAX_COMMAND_OUTPUT_BYTES,
    ) -> ProcessResult:
        result = self.runner.run(
            [self.executable, "-s", self.serial, *tail],
            timeout_seconds=self.timeout_seconds,
            max_output_bytes=max_output_bytes,
        )
        if (
            allowed_returncodes is not None
            and result.returncode not in allowed_returncodes
        ):
            raise CollectorError(f"{code} rc={result.returncode}")
        return result

    def text(
        self,
        tail: Sequence[str],
        *,
        code: str,
        allowed_returncodes: frozenset[int] = frozenset({0}),
    ) -> str:
        result = self.run(
            tail,
            code=code,
            allowed_returncodes=allowed_returncodes,
        )
        return _decode_utf8(result.stdout, f"{code}_utf8").strip()

    def capture_raw_snapshot(self) -> dict[str, object]:
        state = self.text(["get-state"], code="adb_get_state")
        current_user = self.text(
            ["shell", "am", "get-current-user"],
            code="adb_current_user",
        )
        activity = self.text(
            ["shell", "dumpsys", "activity", "activities"],
            code="adb_activity",
        )
        processes: dict[str, str] = {}
        services: dict[str, str] = {}
        for package in RELEVANT_PACKAGES:
            processes[package] = self.text(
                ["shell", "pidof", package],
                code=f"adb_pidof_{package}",
                allowed_returncodes=frozenset({0, 1}),
            )
            services[package] = self.text(
                ["shell", "dumpsys", "activity", "services", package],
                code=f"adb_services_{package}",
            )
        return {
            "device_state": state,
            "current_user": current_user,
            "activity": activity,
            "processes": processes,
            "services": services,
            "enabled_accessibility": self.text(
                [
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "get",
                    "secure",
                    "enabled_accessibility_services",
                ],
                code="adb_accessibility",
            ),
            "accessibility_dump": self.text(
                ["shell", "dumpsys", "accessibility"],
                code="adb_accessibility_dump",
            ),
            "interfaces": self.text(
                ["shell", "ls", "-1", "/sys/class/net"],
                code="adb_interfaces",
            ),
            "connectivity": self.text(
                ["shell", "dumpsys", "connectivity"],
                code="adb_connectivity",
            ),
            "vpn": self.text(
                ["shell", "dumpsys", "vpn"],
                code="adb_vpn",
                allowed_returncodes=frozenset({0, 1}),
            ),
            "stayon": self.text(
                [
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "get",
                    "global",
                    "stay_on_while_plugged_in",
                ],
                code="adb_stayon",
            ),
            "wifi_on": self.text(
                [
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "get",
                    "global",
                    "wifi_on",
                ],
                code="adb_wifi",
            ),
        }

    def stable_clean_snapshot(
        self,
        *,
        interval_seconds: float = 2.0,
    ) -> tuple[PhoneSnapshot, PhoneSnapshot]:
        first = parse_phone_snapshot(self.capture_raw_snapshot())
        time.sleep(interval_seconds)
        second = parse_phone_snapshot(self.capture_raw_snapshot())
        assert_stable_phone_snapshots(first, second)
        return first, second


def capture_stable_phone_evidence(
    adb: AdbClient,
    *,
    directory: Path,
    prefix: str,
    interval_seconds: float = 2.0,
) -> tuple[PhoneSnapshot, PhoneSnapshot]:
    if re.fullmatch(r"[a-z][a-z0-9-]{0,63}", prefix) is None:
        raise CollectorError("phone_evidence_prefix_invalid")
    first_raw = adb.capture_raw_snapshot()
    first = parse_phone_snapshot(first_raw)
    time.sleep(interval_seconds)
    second_raw = adb.capture_raw_snapshot()
    second = parse_phone_snapshot(second_raw)
    assert_stable_phone_snapshots(first, second)
    _write_exclusive_json(directory / f"{prefix}-t0-raw.json", first_raw)
    _write_exclusive_json(directory / f"{prefix}-t2-raw.json", second_raw)
    _write_exclusive_json(
        directory / f"{prefix}-receipt.json",
        {
            "schema": "aneb-realtime-phone-live-state-receipt",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "stable": True,
            "t0_sha256": first.canonical_sha256,
            "t2_sha256": second.canonical_sha256,
            "focused_component": second.focused_component,
            "stayon": second.stayon,
            "wifi_on": second.wifi_on,
        },
    )
    return first, second


def load_device_policy(path: Path, *, adb_serial: str) -> dict[str, object]:
    try:
        raw = _regular_file(path, "device_policy_invalid").read_bytes()
        if len(raw) > 64 * 1024:
            raise ValueError("size")
        policy = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_json_object,
            parse_constant=lambda item: (_ for _ in ()).throw(ValueError(item)),
        )
        if (
            not _exact_keys(
                policy,
                {
                    "schema",
                    "schema_version",
                    "device_alias",
                    "adb_serial_sha256",
                    "properties",
                },
            )
            or policy["schema"] != "aneb-device-identity-policy"
            or policy["schema_version"] != "1.0.0"
            or policy["device_alias"] != "P40 Pro"
            or policy["adb_serial_sha256"]
            != hashlib.sha256(adb_serial.encode("utf-8")).hexdigest()
            or not _exact_keys(policy["properties"], set(DEVICE_PROPERTY_KEYS))
        ):
            raise ValueError("identity")
        properties = policy["properties"]
        assert isinstance(properties, dict)
        for key in DEVICE_PROPERTY_KEYS:
            value = properties[key]
            if (
                not isinstance(value, str)
                or "\r" in value
                or "\n" in value
                or "\x00" in value
                or len(value.encode("utf-8")) > 2048
                or (key not in OPTIONAL_DEVICE_PROPERTY_KEYS and not value)
            ):
                raise ValueError("property")
        return policy
    except (
        AssertionError,
        KeyError,
        OSError,
        TypeError,
        UnicodeError,
        ValueError,
        json.JSONDecodeError,
    ) as error:
        raise CollectorError("device_policy_invalid") from error


def verify_device_policy_live(
    adb: AdbClient,
    *,
    policy: dict[str, object],
    directory: Path,
) -> None:
    serial = adb.text(["get-serialno"], code="adb_get_serialno")
    expected_serial = policy.get("adb_serial_sha256")
    if (
        hashlib.sha256(serial.encode("utf-8")).hexdigest() != expected_serial
        or serial != adb.serial
    ):
        raise CollectorError("device_policy_mismatch field=serial")
    expected_properties = policy.get("properties")
    if not isinstance(expected_properties, dict):
        raise CollectorError("device_policy_mismatch field=properties")
    actual: dict[str, str] = {}
    for key in DEVICE_PROPERTY_KEYS:
        value = adb.text(["shell", "getprop", key], code=f"adb_getprop_{key}")
        if value != expected_properties.get(key):
            raise CollectorError(f"device_policy_mismatch field={key}")
        actual[key] = value
    boot_id = adb.text(
        ["shell", "cat", "/proc/sys/kernel/random/boot_id"],
        code="adb_boot_id",
    )
    if (
        re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            r"[0-9a-f]{4}-[0-9a-f]{12}",
            boot_id,
        )
        is None
    ):
        raise CollectorError("device_boot_id_invalid")
    _write_exclusive_json(
        directory / "device-identity.json",
        {
            "schema": "aneb-realtime-device-identity",
            "schema_version": "1.0.0",
            "status": "pass",
            "adb_serial_sha256": expected_serial,
            "android_boot_id": boot_id,
            "properties": actual,
        },
    )


def _remote_sha256(
    adb: AdbClient,
    *,
    package: str | None,
    path: str,
    code: str,
) -> str:
    tail = ["shell"]
    if package is not None:
        tail.extend(["run-as", package])
    tail.extend(["sha256sum", path])
    output = adb.text(tail, code=code)
    match = re.fullmatch(r"([0-9a-f]{64})\s+\*?(.+)", output)
    if match is None or match.group(2) != path:
        raise CollectorError(f"{code}_invalid")
    return match.group(1)


def parse_installed_package_identity(package_dump: str) -> tuple[int, str]:
    if not isinstance(package_dump, str) or "\x00" in package_dump:
        raise CollectorError("installed_package_identity_invalid")
    version_codes = re.findall(
        r"(?m)^\s*versionCode=([0-9]+)\b",
        package_dump,
    )
    version_names = re.findall(
        r"(?m)^\s*versionName=([^\r\n]+)\r?$",
        package_dump,
    )
    if (
        not version_codes
        or len({int(value) for value in version_codes}) != 1
        or not version_names
        or len({value.strip() for value in version_names}) != 1
    ):
        raise CollectorError("installed_package_identity_invalid")
    return int(version_codes[0]), version_names[0].strip()


def verify_or_install_candidate(
    adb: AdbClient,
    *,
    candidate_directory: Path,
    identity: CiCandidateIdentity,
    evidence_directory: Path,
    install: bool,
) -> None:
    candidate_apk = candidate_directory / identity.apk_file_name
    if (
        _sha256_file(candidate_apk) != identity.apk_sha256
        or candidate_apk.stat().st_size != identity.apk_size_bytes
    ):
        raise CollectorError("candidate_apk_changed")
    if install:
        installed = adb.run(
            ["install", "-r", "--no-streaming", str(candidate_apk)],
            code="adb_install_candidate",
            allowed_returncodes=None,
            max_output_bytes=1024 * 1024,
        )
        output = _decode_utf8(
            installed.stdout + installed.stderr,
            "adb_install_candidate_utf8",
        )
        _write_exclusive_bytes(
            evidence_directory / "adb-install.txt",
            (output.rstrip("\r\n") + "\n").encode("utf-8"),
        )
        if installed.returncode != 0:
            if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output:
                raise CollectorError("adb_install_candidate_signature_mismatch")
            raise CollectorError(
                f"adb_install_candidate_failed rc={installed.returncode}"
            )
        if "Success" not in output.splitlines():
            raise CollectorError("adb_install_candidate_receipt_invalid")
    package_dump = adb.text(
        ["shell", "dumpsys", "package", PACKAGE_NAME],
        code="adb_package_identity",
    )
    version_code, version_name = parse_installed_package_identity(package_dump)
    if (
        version_code != EXPECTED_VERSION_CODE
        or version_name != EXPECTED_VERSION_NAME
    ):
        raise CollectorError("installed_package_identity_mismatch")
    package_path = adb.text(
        ["shell", "pm", "path", PACKAGE_NAME],
        code="adb_pm_path",
    )
    match = re.fullmatch(r"package:(/[^\r\n ]+/base\.apk)", package_path)
    if match is None:
        raise CollectorError("installed_apk_path_invalid")
    remote_path = match.group(1)
    before = _remote_sha256(
        adb,
        package=None,
        path=remote_path,
        code="installed_apk_sha_before",
    )
    result = adb.run(
        ["exec-out", "cat", remote_path],
        code="installed_apk_copy",
        max_output_bytes=MAX_APK_BYTES,
    )
    if result.stderr or not result.stdout:
        raise CollectorError("installed_apk_copy_invalid")
    after = _remote_sha256(
        adb,
        package=None,
        path=remote_path,
        code="installed_apk_sha_after",
    )
    local_digest = hashlib.sha256(result.stdout).hexdigest()
    if (
        before != after
        or before != local_digest
        or local_digest != identity.apk_sha256
        or len(result.stdout) != identity.apk_size_bytes
    ):
        raise CollectorError("installed_apk_digest_mismatch")
    _write_exclusive_bytes(
        evidence_directory / "installed-base.apk",
        result.stdout,
    )
    _write_exclusive_bytes(
        evidence_directory / "installed-package.txt",
        (package_dump + "\n").encode("utf-8"),
    )
    run_as = adb.text(
        ["shell", "run-as", PACKAGE_NAME, "id"],
        code="adb_run_as",
    )
    if re.fullmatch(r"uid=[0-9]+\([^)]*\).*", run_as) is None:
        raise CollectorError("run_as_unavailable")


ROOM_FILES = (
    ("aneb-probe.db", "databases/aneb-probe.db"),
    ("aneb-probe.db-wal", "databases/aneb-probe.db-wal"),
    ("aneb-probe.db-shm", "databases/aneb-probe.db-shm"),
)


def run_as_shell_tail(script: str) -> list[str]:
    if (
        not isinstance(script, str)
        or not script
        or "\x00" in script
        or "\r" in script
        or "\n" in script
        or len(script.encode("utf-8")) > 4096
    ):
        raise CollectorError("run_as_shell_script_invalid")
    return [
        "shell",
        "run-as",
        PACKAGE_NAME,
        "sh",
        "-c",
        shlex.quote(script),
    ]


def copy_frozen_room_database(
    adb: AdbClient,
    *,
    evidence_directory: Path,
) -> dict[str, object]:
    states: dict[str, str] = {}
    for name, remote_path in ROOM_FILES:
        script = (
            f'if [ -r "{remote_path}" ]; then printf present; '
            "else printf absent; fi"
        )
        state = adb.text(
            run_as_shell_tail(script),
            code=f"room_state_{name}",
        )
        if state not in {"present", "absent"}:
            raise CollectorError(f"room_file_state_invalid file={name}")
        states[name] = state
    if states["aneb-probe.db"] != "present":
        raise CollectorError("room_main_database_missing")
    if states["aneb-probe.db-wal"] != states["aneb-probe.db-shm"]:
        raise CollectorError("room_wal_shm_state_mismatch")
    files: list[dict[str, object]] = []
    for name, remote_path in ROOM_FILES:
        if states[name] == "absent":
            files.append({"name": name, "state": "absent"})
            continue
        before = _remote_sha256(
            adb,
            package=PACKAGE_NAME,
            path=remote_path,
            code=f"room_digest_before_{name}",
        )
        result = adb.run(
            ["exec-out", "run-as", PACKAGE_NAME, "cat", remote_path],
            code=f"room_copy_{name}",
            max_output_bytes=256 * 1024 * 1024,
        )
        if result.stderr or not result.stdout:
            raise CollectorError(f"room_copy_invalid file={name}")
        after = _remote_sha256(
            adb,
            package=PACKAGE_NAME,
            path=remote_path,
            code=f"room_digest_after_{name}",
        )
        local = hashlib.sha256(result.stdout).hexdigest()
        if before != after or before != local:
            raise CollectorError(f"room_file_digest_mismatch file={name}")
        _write_exclusive_bytes(evidence_directory / name, result.stdout)
        files.append(
            {
                "name": name,
                "state": "present",
                "bytes": len(result.stdout),
                "sha256": local,
            }
        )
    inventory = {
        "schema": "aneb-frozen-room-copy",
        "schema_version": "1.0.0",
        "app_process_state": "stopped_before_copy",
        "files": files,
    }
    _write_exclusive_json(
        evidence_directory / "room-copy-inventory.json",
        inventory,
    )
    return inventory


def post_marker_log(text: str, *, nonce: str) -> str:
    if re.fullmatch(r"[0-9a-f]{32}", nonce) is None:
        raise CollectorError("logcat_marker_nonce_invalid")
    pattern = re.compile(
        r"(?m)^[^\r\n]*D82_CAPTURE_MARKER nonce="
        + re.escape(nonce)
        + r"\s*$"
    )
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise CollectorError(
            f"logcat_marker_count_invalid count={len(matches)}"
        )
    return text[matches[0].end() :]


REMOTE_SNAPSHOT_KEYS = (
    "boot_id",
    "systemd_invocation_id",
    "main_pid",
    "server_binary_sha256",
    "eth0_qdisc_sha256",
    "firewall_full_sha256",
    "firewall_v4_sha256",
    "firewall_v6_sha256",
    "firewall_nft_sha256",
    "docker_sha256",
    "journal_cursor",
)


def parse_remote_snapshot(text: str) -> RemoteSnapshot:
    values: dict[str, str] = {}
    try:
        for line in text.splitlines():
            key, separator, value = line.partition("=")
            if (
                not separator
                or key not in REMOTE_SNAPSHOT_KEYS
                or key in values
                or not value
            ):
                raise ValueError("shape")
            values[key] = value
        if set(values) != set(REMOTE_SNAPSHOT_KEYS):
            raise ValueError("missing")
        if (
            re.fullmatch(r"[0-9a-f]{32}", values["boot_id"]) is None
            or re.fullmatch(
                r"[0-9a-f]{32}", values["systemd_invocation_id"]
            )
            is None
            or re.fullmatch(r"[1-9][0-9]*", values["main_pid"]) is None
            or any(
                re.fullmatch(r"[0-9a-f]{64}", values[key]) is None
                for key in REMOTE_SNAPSHOT_KEYS
                if key.endswith("_sha256")
            )
            or re.fullmatch(
                r"[A-Za-z0-9;:_.=-]{10,1024}",
                values["journal_cursor"],
            )
            is None
        ):
            raise ValueError("value")
        return RemoteSnapshot(**values)
    except (TypeError, ValueError) as error:
        raise CollectorError("remote_snapshot_invalid") from error


def assert_remote_snapshot_stable(
    before: RemoteSnapshot,
    after: RemoteSnapshot,
    *,
    expected_binary_sha256: str,
) -> None:
    if (
        re.fullmatch(r"[0-9a-f]{64}", expected_binary_sha256) is None
        or before.server_binary_sha256 != expected_binary_sha256
        or after.server_binary_sha256 != expected_binary_sha256
    ):
        raise CollectorError("remote_binary_identity_mismatch")
    stable_fields = (
        "boot_id",
        "systemd_invocation_id",
        "main_pid",
        "server_binary_sha256",
        "eth0_qdisc_sha256",
        "firewall_full_sha256",
        "firewall_v4_sha256",
        "firewall_v6_sha256",
        "firewall_nft_sha256",
        "docker_sha256",
    )
    if any(getattr(before, field) != getattr(after, field) for field in stable_fields):
        raise CollectorError("remote_baseline_changed")


def _unique_json_object(
    pairs: list[tuple[str, object]],
) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def validate_http_capture(
    *,
    status: int,
    headers: tuple[tuple[str, str], ...],
    body: bytes,
) -> HttpCapture:
    try:
        if type(status) is not int or status != 200:
            raise ValueError("status")
        if (
            not isinstance(headers, tuple)
            or not headers
            or not isinstance(body, bytes)
            or not 0 < len(body) <= 1024 * 1024
        ):
            raise ValueError("shape")
        content_types: list[str] = []
        header_bytes = 0
        for item in headers:
            if (
                not isinstance(item, tuple)
                or len(item) != 2
                or not all(isinstance(value, str) for value in item)
                or any("\r" in value or "\n" in value for value in item)
            ):
                raise ValueError("header")
            name, value = item
            header_bytes += len(name.encode("utf-8")) + len(value.encode("utf-8")) + 4
            if name.casefold() == "content-type":
                content_types.append(value.casefold().strip())
        if (
            header_bytes > 64 * 1024
            or content_types != ["application/json"]
        ):
            raise ValueError("content type")
        decoded = body.decode("utf-8", errors="strict")
        parsed = json.loads(
            decoded,
            object_pairs_hook=_unique_json_object,
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
        )
        if not isinstance(parsed, dict):
            raise ValueError("body")
        validate_serverinfo(parsed)
        return HttpCapture(
            status=status,
            headers=headers,
            body=body,
            json_body=parsed,
        )
    except (CollectorError, TypeError, UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise CollectorError("http_capture_invalid") from error


def validate_serverinfo(body: object) -> None:
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
        capabilities = body.get("execution_capabilities")
        if (
            not isinstance(capabilities, dict)
            or set(capabilities)
            != {
                "contract_id",
                "contract_version",
                "primitives",
                "validated_profiles",
            }
            or capabilities.get("contract_id")
            != "aneb-server-capability-receipt"
            or capabilities.get("contract_version") != "1.0.0"
        ):
            raise ValueError("receipt")
        primitives = capabilities.get("primitives")
        if not isinstance(primitives, list) or len(primitives) != 4:
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
            primitive_map[primitive["primitive_id"]] = primitive["wire_contract_id"]
        if primitive_map != {
            "download": "aneb-download-v1",
            "echo": "aneb-echo-v1",
            "realtime_sim": "aneb-realtime-session-v1",
            "token_sim": "aneb-token-task-v1",
        }:
            raise ValueError("primitive contract")
        profiles = capabilities.get("validated_profiles")
        if not isinstance(profiles, list) or len(profiles) != 2:
            raise ValueError("profiles")
        profile_map: dict[str, tuple[str, str]] = {}
        for profile in profiles:
            if (
                not isinstance(profile, dict)
                or set(profile)
                != {
                    "profile_id",
                    "profile_version",
                    "profile_sha256",
                }
                or not isinstance(profile.get("profile_id"), str)
                or profile["profile_id"] in profile_map
                or not isinstance(profile.get("profile_version"), str)
                or not isinstance(profile.get("profile_sha256"), str)
                or re.fullmatch(
                    r"sha256:[0-9a-f]{64}",
                    profile["profile_sha256"],
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
            "token_multimodal_quick": (
                "1.2.1",
                f"sha256:{TOKEN_PROFILE_SHA256}",
            ),
        }:
            raise ValueError("token profile")
    except (KeyError, TypeError, ValueError) as error:
        raise CollectorError("serverinfo_contract_invalid") from error


def assert_serverinfo_sequence(
    identity: object,
    start: object,
    end: object,
) -> None:
    try:
        for item in (identity, start, end):
            validate_serverinfo(item)
        if not all(isinstance(item, dict) for item in (identity, start, end)):
            raise ValueError("shape")
        identity_dict = identity
        start_dict = start
        end_dict = end
        assert isinstance(identity_dict, dict)
        assert isinstance(start_dict, dict)
        assert isinstance(end_dict, dict)
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
            identity_dict[key] != candidate[key]
            for candidate in (start_dict, end_dict)
            for key in stable_keys
        ):
            raise ValueError("stable identity")
        if not (
            int(identity_dict["srv_ts_us"])
            < int(start_dict["srv_ts_us"])
            < int(end_dict["srv_ts_us"])
            and int(identity_dict["uptime_s"])
            <= int(start_dict["uptime_s"])
            <= int(end_dict["uptime_s"])
        ):
            raise ValueError("chronology")
    except (AssertionError, CollectorError, KeyError, TypeError, ValueError) as error:
        raise CollectorError("serverinfo_sequence_invalid") from error


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self,
        request: urllib.request.Request,
        fp: BinaryIO,
        code: int,
        message: str,
        headers: Mapping[str, str],
        new_url: str,
    ) -> None:
        del request, fp, code, message, headers, new_url
        return None


def fetch_serverinfo(
    *,
    server_base: str,
    ca_path: Path,
    timeout_seconds: float,
    headers: Mapping[str, str] | None = None,
) -> HttpCapture:
    if timeout_seconds <= 0 or timeout_seconds > 120:
        raise CollectorError("serverinfo_http_contract_invalid")
    frozen_ca = _regular_file(ca_path, "server_ca_invalid")
    ca_before = _sha256_file(frozen_ca)
    try:
        context = ssl.create_default_context(
            ssl.Purpose.SERVER_AUTH,
            cafile=str(frozen_ca),
        )
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        opener = urllib.request.build_opener(
            _NoRedirectHandler(),
            urllib.request.HTTPSHandler(context=context),
        )
        request = urllib.request.Request(
            server_base.rstrip("/") + "/api/v1/serverinfo",
            method="GET",
            headers=dict(headers or {}),
        )
        with opener.open(request, timeout=timeout_seconds) as response:
            if response.geturl() != request.full_url:
                raise CollectorError("serverinfo_redirect_forbidden")
            body = response.read(1024 * 1024 + 1)
            raw_items = getattr(response.headers, "raw_items", None)
            response_headers = tuple(
                raw_items() if callable(raw_items) else response.headers.items()
            )
            capture = validate_http_capture(
                status=int(response.status),
                headers=response_headers,
                body=body,
            )
    except CollectorError:
        raise
    except urllib.error.HTTPError as error:
        raise CollectorError(f"serverinfo_http_status status={error.code}") from error
    except (
        OSError,
        ssl.CertificateError,
        ssl.SSLError,
        TimeoutError,
        urllib.error.URLError,
    ) as error:
        raise CollectorError("serverinfo_http_failed") from error
    if _sha256_file(frozen_ca) != ca_before:
        raise CollectorError("server_ca_changed")
    return capture


def _write_exclusive_bytes(path: Path, payload: bytes) -> None:
    if not isinstance(payload, bytes):
        raise CollectorError("evidence_payload_invalid")
    try:
        descriptor = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0),
            0o600,
        )
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError as error:
        raise CollectorError(f"evidence_file_exists path={path.name}") from error
    except OSError as error:
        raise CollectorError(f"evidence_write_failed path={path.name}") from error


def _write_exclusive_json(path: Path, value: object) -> None:
    _write_exclusive_bytes(path, _canonical_json_bytes(value))


def prepare_evidence_staging(evidence_root: Path, partial: Path) -> None:
    try:
        evidence_root.mkdir(mode=0o700, exist_ok=True)
        resolved_root = _regular_directory(
            evidence_root.resolve(strict=True),
            "evidence_root_invalid",
        )
        security_report = evidence_security.verify_root(resolved_root)
    except evidence_security.EvidenceSecurityFailure as error:
        raise CollectorError(error.reason_code) from error
    except OSError as error:
        raise CollectorError("evidence_staging_create_failed") from error
    try:
        partial.mkdir(mode=0o700)
        _write_exclusive_json(
            partial / "evidence-root-security.json",
            security_report,
        )
    except OSError as error:
        raise CollectorError("evidence_staging_create_failed") from error


def write_http_capture(
    directory: Path,
    *,
    name: str,
    capture: HttpCapture,
) -> None:
    if re.fullmatch(r"[a-z][a-z0-9-]{0,63}", name) is None:
        raise CollectorError("http_capture_name_invalid")
    _write_exclusive_bytes(directory / f"{name}.json", capture.body)
    _write_exclusive_json(
        directory / f"{name}.headers.json",
        {
            "status": capture.status,
            "headers": [list(item) for item in capture.headers],
        },
    )


def _strict_json_line(payload: bytes, *, code: str) -> dict[str, object]:
    try:
        text = payload.decode("utf-8", errors="strict")
        if text.endswith("\r\n"):
            body = text[:-2]
        elif text.endswith("\n"):
            body = text[:-1]
        else:
            raise ValueError("line")
        if (
            not body
            or "\r" in body
            or "\n" in body
            or body != body.strip()
        ):
            raise ValueError("line")
        value = json.loads(
            body,
            object_pairs_hook=_unique_json_object,
            parse_constant=lambda item: (_ for _ in ()).throw(ValueError(item)),
        )
        if not isinstance(value, dict):
            raise ValueError("object")
        return value
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise CollectorError(code) from error


def _candidate_snapshot(directory: Path) -> dict[str, tuple[int, str]]:
    if {path.name for path in directory.iterdir()} != CI_CANDIDATE_NAMES:
        raise CollectorError("candidate_file_set_invalid")
    result: dict[str, tuple[int, str]] = {}
    for name in sorted(CI_CANDIDATE_NAMES):
        path = _regular_file(directory / name, "candidate_payload_invalid")
        size = path.stat().st_size
        if size > (MAX_APK_BYTES if name.endswith(".apk") else 16 * 1024 * 1024):
            raise CollectorError("candidate_payload_invalid")
        result[name] = (size, _sha256_file(path))
    return result


def _copy_candidate(
    source: Path,
    destination: Path,
    *,
    expected: dict[str, tuple[int, str]],
) -> None:
    try:
        destination.mkdir(mode=0o700)
    except OSError as error:
        raise CollectorError("candidate_copy_directory_failed") from error
    for name in sorted(CI_CANDIDATE_NAMES):
        source_path = source / name
        target = destination / name
        try:
            with source_path.open("rb") as reader:
                descriptor = os.open(
                    target,
                    os.O_WRONLY
                    | os.O_CREAT
                    | os.O_EXCL
                    | getattr(os, "O_BINARY", 0),
                    0o600,
                )
                with os.fdopen(descriptor, "wb") as writer:
                    shutil.copyfileobj(reader, writer, 1024 * 1024)
                    writer.flush()
                    os.fsync(writer.fileno())
        except OSError as error:
            raise CollectorError("candidate_copy_failed") from error
        if (target.stat().st_size, _sha256_file(target)) != expected[name]:
            raise CollectorError("candidate_copy_mismatch")


def verify_ci_candidate(
    *,
    runner: CommandRunner,
    python_path: Path,
    gh_path: Path,
    candidate_directory: Path,
    source_commit: str,
    report_output: Path,
    timeout_seconds: int,
) -> tuple[dict[str, object], CiCandidateIdentity]:
    verifier = Path(__file__).resolve().with_name("verify_ci_apk_provenance.py")
    result = runner.run(
        [
            str(python_path),
            str(verifier),
            str(candidate_directory),
            "--source-commit",
            source_commit,
            "--expected-version-name",
            EXPECTED_VERSION_NAME,
            "--expected-version-code",
            str(EXPECTED_VERSION_CODE),
            "--gh-path",
            str(gh_path),
            "--timeout-seconds",
            str(min(120, max(1, timeout_seconds - 1))),
        ],
        timeout_seconds=timeout_seconds,
        max_output_bytes=2 * 1024 * 1024,
    )
    if result.returncode != 0 or result.stderr:
        raise CollectorError("ci_provenance_verifier_failed")
    report = _strict_json_line(
        result.stdout,
        code="ci_provenance_verifier_output_invalid",
    )
    identity = validate_ci_provenance_report(
        report,
        source_commit=source_commit,
    )
    _write_exclusive_bytes(report_output, result.stdout)
    return report, identity


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
        fields = {match.group("key"): match.group("value") for match in FIELD_RE.finditer(payload)}
        records.append(fields)
    return records


def parse_realtime_terminal_markers(
    text: str,
    *,
    mode: Literal["positive", "negative"],
) -> RealtimeTerminalMarkers:
    try:
        starts = _marker_records(text, "REALTIME_V1_START")
        contracts = _marker_records(text, "REALTIME_V1_CONTRACT")
        database_writes = _marker_records(text, "REALTIME_V1_DB_WRITE")
        results = _marker_records(text, "REALTIME_V1_RESULT")
        ends = _marker_records(text, "REALTIME_V1_END")
        failures = _marker_records(text, "REALTIME_V1_FAILED")
        if (
            len(starts) != 1
            or len(contracts) != 1
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
            raise ValueError("run binding")
        run_id = next(iter(run_ids))
        assert isinstance(run_id, str)
        validate_run_id(run_id)
        if database_writes[0].get("ok") != "true":
            raise ValueError("database")
        if mode == "positive":
            if (
                len(results) != 1
                or contracts[0].get("status") != "authorized"
                or ends[0].get("status") != "completed"
            ):
                raise ValueError("positive")
            return RealtimeTerminalMarkers(
                run_id=run_id,
                contract_status="authorized",
                terminal_status="completed",
                reason_code=None,
            )
        if (
            results
            or set(contracts[0]) != {"run_id", "status", "reason", "detail"}
            or contracts[0].get("status") != "rejected"
            or contracts[0].get("reason") != "receipt_missing"
            or not contracts[0].get("detail")
            or ends[0].get("status") != "contract_rejected"
        ):
            raise ValueError("negative")
        return RealtimeTerminalMarkers(
            run_id=run_id,
            contract_status="rejected",
            terminal_status="contract_rejected",
            reason_code="receipt_missing",
        )
    except (AssertionError, CollectorError, ValueError) as error:
        raise CollectorError("realtime_marker_chain_invalid") from error


def build_realtime_launch_arguments(
    *,
    serial: str,
    server_base: str,
    transport: Literal["auto", "wifi", "cellular"],
    adb_path: str = "adb",
) -> list[str]:
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
        "realtime",
    ]


def remote_lock_holder_script() -> str:
    return r"""set -Eeuo pipefail
IFS=$'\n\t'
umask 077
NONCE="${1:?nonce required}"
TTL_SECONDS="${2:?TTL required}"
LOCK_PATH="${3:?lock path required}"
[[ "$NONCE" =~ ^[0-9a-f]{32}$ ]] || exit 64
[[ "$TTL_SECONDS" =~ ^[0-9]+$ ]] || exit 64
[[ "$LOCK_PATH" == '/run/lock/aneb-deploy.lock' ]] || exit 64
MARKER="/run/aneb-realtime-audit-$NONCE.lock"
cleanup() { rm -f -- "$MARKER"; }
trap cleanup EXIT HUP INT TERM
exec 9>"$LOCK_PATH"
if ! flock -n 9; then
    printf 'LOCK_BUSY path=%s\n' "$LOCK_PATH" >&2
    exit 75
fi
printf '%s %s\n' "$NONCE" "$$" > "$MARKER"
chmod 0600 "$MARKER"
printf 'LOCK_ACQUIRED nonce=%s pid=%s marker=%s\n' "$NONCE" "$$" "$MARKER"
if ! IFS= read -r -t "$TTL_SECONDS" command; then
    exit 76
fi
if [[ "$command" == "RELEASE $NONCE" ]]; then
    printf 'LOCK_RELEASED nonce=%s\n' "$NONCE"
    exit 0
fi
printf 'LOCK_PROTOCOL_ERROR\n' >&2
exit 64
"""


class SshClient:
    def __init__(
        self,
        *,
        runner: CommandRunner,
        executable: Path,
        remote: str,
        ssh_key: Path,
        known_hosts: Path,
        timeout_seconds: int,
    ) -> None:
        self.runner = runner
        self.executable = str(executable)
        self.remote = remote
        self.ssh_key = str(ssh_key)
        self.known_hosts = str(known_hosts)
        self.timeout_seconds = timeout_seconds

    def arguments(self, remote_command: str) -> list[str]:
        return [
            self.executable,
            "-T",
            "-o",
            "BatchMode=yes",
            "-o",
            "IdentitiesOnly=yes",
            "-o",
            "StrictHostKeyChecking=yes",
            "-o",
            "UpdateHostKeys=no",
            "-o",
            "HashKnownHosts=no",
            "-o",
            "CheckHostIP=yes",
            "-o",
            "CanonicalizeHostname=no",
            "-o",
            "ProxyCommand=none",
            "-o",
            "ProxyJump=none",
            "-o",
            f"UserKnownHostsFile={self.known_hosts}",
            "-o",
            f"GlobalKnownHostsFile={self.known_hosts}",
            "-o",
            "KnownHostsCommand=none",
            "-o",
            "ConnectTimeout=10",
            "-o",
            "ServerAliveInterval=10",
            "-o",
            "ServerAliveCountMax=3",
            "-i",
            self.ssh_key,
            self.remote,
            remote_command,
        ]

    def text(
        self,
        remote_command: str,
        *,
        code: str,
        max_output_bytes: int = MAX_COMMAND_OUTPUT_BYTES,
    ) -> str:
        result = _checked(
            self.runner,
            self.arguments(remote_command),
            timeout_seconds=self.timeout_seconds,
            code=code,
            max_output_bytes=max_output_bytes,
        )
        if result.stderr.strip():
            raise CollectorError(f"{code}_stderr")
        return _decode_utf8(result.stdout, f"{code}_utf8").strip()


def _readline_with_timeout(
    stream: BinaryIO,
    *,
    timeout_seconds: float,
) -> bytes:
    result_queue: queue.Queue[bytes | BaseException] = queue.Queue(maxsize=1)

    def read() -> None:
        try:
            result_queue.put(stream.readline())
        except BaseException as error:
            result_queue.put(error)

    threading.Thread(target=read, daemon=True).start()
    try:
        value = result_queue.get(timeout=timeout_seconds)
    except queue.Empty as error:
        raise CollectorError("lock_receipt_timeout") from error
    if isinstance(value, BaseException):
        raise CollectorError("lock_receipt_read_failed") from value
    return value


class PersistentRemoteLock:
    def __init__(
        self,
        *,
        ssh: SshClient,
        ttl_seconds: int,
    ) -> None:
        self.ssh = ssh
        self.ttl_seconds = ttl_seconds
        self.nonce = uuid.uuid4().hex
        self.marker = f"/run/aneb-realtime-audit-{self.nonce}.lock"
        self.remote_pid: int | None = None
        self.process: subprocess.Popen[bytes] | None = None

    def acquire(self) -> str:
        encoded = base64.b64encode(remote_lock_holder_script().encode("utf-8")).decode(
            "ascii"
        )
        command = (
            "bash -c \"$(printf '%s' '"
            + encoded
            + "' | base64 -d)\" -- '"
            + self.nonce
            + "' '"
            + str(self.ttl_seconds)
            + "' '"
            + REMOTE_LOCK_PATH
            + "'"
        )
        try:
            process = subprocess.Popen(
                self.ssh.arguments(command),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                shell=False,
            )
        except OSError as error:
            raise CollectorError("lock_ssh_launch_failed") from error
        self.process = process
        if process.stdout is None:
            self._terminate()
            raise CollectorError("lock_stdout_unavailable")
        try:
            line = _readline_with_timeout(
                process.stdout,
                timeout_seconds=15,
            ).decode("utf-8", errors="strict").rstrip("\r\n")
        except UnicodeError as error:
            self._terminate()
            raise CollectorError("lock_receipt_invalid") from error
        match = re.fullmatch(
            r"LOCK_ACQUIRED nonce="
            + re.escape(self.nonce)
            + r" pid=([1-9][0-9]*) marker=("
            + re.escape(self.marker)
            + r")",
            line,
        )
        if match is None:
            self._terminate()
            raise CollectorError("lock_receipt_invalid")
        self.remote_pid = int(match.group(1))
        self.assert_healthy("acquire")
        return line

    def assert_healthy(self, stage: str) -> None:
        if re.fullmatch(r"[a-z0-9_-]{1,64}", stage) is None:
            raise CollectorError("lock_stage_invalid")
        if (
            self.process is None
            or self.remote_pid is None
            or self.process.poll() is not None
        ):
            raise CollectorError(f"audit_lock_process_lost stage={stage}")
        command = "\n".join(
            (
                "set -Eeuo pipefail",
                f"LOCK_PATH='{REMOTE_LOCK_PATH}'",
                f"MARKER='{self.marker}'",
                f"EXPECTED='{self.nonce} {self.remote_pid}'",
                '[ "$(cat -- "$MARKER")" = "$EXPECTED" ]',
                f"kill -0 '{self.remote_pid}'",
                "set +e",
                'flock -n "$LOCK_PATH" -c true',
                "flock_rc=$?",
                "set -e",
                '[ "$flock_rc" -eq 1 ]',
                f"printf 'LOCK_HEALTHY nonce=%s pid=%s\\n' "
                f"'{self.nonce}' '{self.remote_pid}'",
            )
        )
        actual = self.ssh.text(command, code=f"lock_health_{stage}")
        expected = f"LOCK_HEALTHY nonce={self.nonce} pid={self.remote_pid}"
        if actual != expected:
            raise CollectorError(f"audit_lock_health_invalid stage={stage}")

    def release(self) -> str:
        if (
            self.process is None
            or self.remote_pid is None
            or self.process.poll() is not None
            or self.process.stdin is None
            or self.process.stdout is None
        ):
            raise CollectorError("audit_lock_process_lost_before_release")
        self.assert_healthy("before_release")
        try:
            self.process.stdin.write(f"RELEASE {self.nonce}\n".encode("ascii"))
            self.process.stdin.flush()
        except OSError as error:
            raise CollectorError("audit_lock_release_write_failed") from error
        try:
            release = _readline_with_timeout(
                self.process.stdout,
                timeout_seconds=15,
            ).decode("utf-8", errors="strict").rstrip("\r\n")
        except UnicodeError as error:
            raise CollectorError("audit_lock_release_receipt_invalid") from error
        if release != f"LOCK_RELEASED nonce={self.nonce}":
            raise CollectorError("audit_lock_release_receipt_invalid")
        try:
            returncode = self.process.wait(timeout=15)
        except subprocess.TimeoutExpired as error:
            self._terminate()
            raise CollectorError("audit_lock_holder_exit_timeout") from error
        stderr = b""
        if self.process.stderr is not None:
            stderr = self.process.stderr.read(MAX_COMMAND_OUTPUT_BYTES + 1)
        if returncode != 0 or stderr:
            raise CollectorError("audit_lock_holder_exit_invalid")
        verification = self.ssh.text(
            "\n".join(
                (
                    "set -Eeuo pipefail",
                    f"test ! -e '{self.marker}'",
                    f"flock -n '{REMOTE_LOCK_PATH}' -c true",
                    f"printf 'LOCK_RELEASE_VERIFIED nonce=%s\\n' '{self.nonce}'",
                )
            ),
            code="lock_release_verification",
        )
        if verification != f"LOCK_RELEASE_VERIFIED nonce={self.nonce}":
            raise CollectorError("lock_release_verification_invalid")
        return release

    def _terminate(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)

    def emergency_close(self) -> None:
        self._terminate()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                output = self.ssh.text(
                    "\n".join(
                        (
                            "set -Eeuo pipefail",
                            f"test ! -e '{self.marker}'",
                            f"flock -n '{REMOTE_LOCK_PATH}' -c true",
                            "printf 'LOCK_CLOSED\\n'",
                        )
                    ),
                    code="lock_emergency_close",
                )
                if output == "LOCK_CLOSED":
                    return
            except CollectorError:
                pass
            time.sleep(0.5)
        raise CollectorError("lock_emergency_close_failed")

    def cancel_unconfirmed(self) -> None:
        """Terminate only this SSH child when acquire never returned a receipt."""

        self._terminate()


def _popen_creation_flags() -> int:
    return int(getattr(subprocess, "CREATE_NO_WINDOW", 0))


class LogcatCapture:
    def __init__(
        self,
        *,
        adb: AdbClient,
        output_path: Path,
        stderr_path: Path,
    ) -> None:
        self.adb = adb
        self.output_path = output_path
        self.stderr_path = stderr_path
        self.process: subprocess.Popen[bytes] | None = None
        self._stdout: BinaryIO | None = None
        self._stderr: BinaryIO | None = None
        self.marker_nonce = uuid.uuid4().hex

    def start(self) -> None:
        epoch = self.adb.text(
            ["shell", "date", "+%s"],
            code="logcat_epoch",
        )
        if re.fullmatch(r"[0-9]{9,12}", epoch) is None:
            raise CollectorError("device_epoch_invalid")
        try:
            self._stdout = self.output_path.open("xb")
            self._stderr = self.stderr_path.open("xb")
            self.process = subprocess.Popen(
                [
                    self.adb.executable,
                    "-s",
                    self.adb.serial,
                    "logcat",
                    "-v",
                    "epoch",
                    "-T",
                    f"{epoch}.000",
                    "-s",
                    "AnebProbe:I",
                    "AnebD82:I",
                    "*:S",
                ],
                stdin=subprocess.DEVNULL,
                stdout=self._stdout,
                stderr=self._stderr,
                shell=False,
                creationflags=_popen_creation_flags(),
            )
        except OSError as error:
            self.stop(allow_missing=True)
            raise CollectorError("logcat_launch_failed") from error
        self.adb.text(
            [
                "shell",
                "log",
                "-t",
                "AnebD82",
                f"D82_CAPTURE_MARKER nonce={self.marker_nonce}",
            ],
            code="logcat_marker_write",
        )
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise CollectorError("logcat_exited_before_marker")
            try:
                text = self.output_path.read_text(encoding="utf-8")
                post_marker_log(text, nonce=self.marker_nonce)
                return
            except (OSError, UnicodeError, CollectorError):
                time.sleep(0.1)
        raise CollectorError("logcat_marker_not_visible")

    def wait_terminal(
        self,
        *,
        mode: Literal["positive", "negative"],
        timeout_seconds: int,
    ) -> RealtimeTerminalMarkers:
        if self.process is None:
            raise CollectorError("logcat_not_started")
        deadline = time.monotonic() + timeout_seconds
        last_error: CollectorError | None = None
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise CollectorError("logcat_exited_early")
            try:
                text = self.output_path.read_text(encoding="utf-8")
                current = post_marker_log(text, nonce=self.marker_nonce)
                return parse_realtime_terminal_markers(current, mode=mode)
            except (OSError, UnicodeError):
                last_error = CollectorError("logcat_read_failed")
            except CollectorError as error:
                last_error = error
            time.sleep(0.25)
        if last_error is not None:
            raise CollectorError(
                f"realtime_terminal_timeout last={_error_text(last_error)}"
            )
        raise CollectorError("realtime_terminal_timeout")

    def stop(self, *, allow_missing: bool = False) -> None:
        process = self.process
        if process is None:
            if allow_missing:
                for stream in (self._stdout, self._stderr):
                    if stream is not None:
                        stream.close()
                return
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired as error:
                    raise CollectorError("logcat_stop_timeout") from error
        for stream in (self._stdout, self._stderr):
            if stream is not None:
                stream.close()
        self._stdout = None
        self._stderr = None
        if self.stderr_path.exists() and self.stderr_path.stat().st_size:
            raise CollectorError("logcat_stderr_not_empty")


class NegativeProxyProcess:
    def __init__(
        self,
        *,
        python_path: Path,
        server_base: str,
        ca_path: Path,
        evidence_directory: Path,
        request_timeout_seconds: int,
    ) -> None:
        if (
            isinstance(request_timeout_seconds, bool)
            or not isinstance(request_timeout_seconds, int)
            or not 1 <= request_timeout_seconds <= 300
        ):
            raise CollectorError("negative_proxy_request_timeout_invalid")
        parsed = urllib.parse.urlsplit(server_base)
        port = parsed.port or 443
        self.arguments = [
            str(python_path),
            str(Path(__file__).resolve().with_name("token_serverinfo_negative_proxy.py")),
            "--upstream-url",
            f"https://{parsed.hostname}:{port}/api/v1/serverinfo",
            "--ca-file",
            str(ca_path),
            "--evidence-dir",
            str(evidence_directory / "negative-proxy"),
            "--delivery-receipt-file",
            str(evidence_directory / "negative-proxy-delivery-receipt.json"),
            "--listen-port",
            str(NEGATIVE_DEVICE_PORT),
            "--request-timeout-seconds",
            str(request_timeout_seconds),
            "--upstream-timeout-seconds",
            "20",
        ]
        self.output_path = evidence_directory / "negative-proxy.stdout.jsonl"
        self.stderr_path = evidence_directory / "negative-proxy.stderr.txt"
        self.startup_failure_path = (
            evidence_directory / "negative-proxy-startup-failure.json"
        )
        self.process: subprocess.Popen[bytes] | None = None
        self._first_line = b""

    def _persist_startup_failure(self) -> None:
        process = self.process
        if process is None:
            return
        self.stop()
        stdout_remainder = (
            b""
            if process.stdout is None
            else process.stdout.read(2 * 1024 * 1024 + 1)
        )
        stderr = (
            b""
            if process.stderr is None
            else process.stderr.read(2 * 1024 * 1024 + 1)
        )
        stdout = self._first_line + stdout_remainder
        stdout_truncated = len(stdout) > 2 * 1024 * 1024
        stderr_truncated = len(stderr) > 2 * 1024 * 1024
        _write_exclusive_bytes(self.output_path, stdout[: 2 * 1024 * 1024])
        _write_exclusive_bytes(self.stderr_path, stderr[: 2 * 1024 * 1024])
        _write_exclusive_json(
            self.startup_failure_path,
            {
                "returncode": process.poll(),
                "stderr_sha256": hashlib.sha256(stderr).hexdigest(),
                "stderr_truncated": stderr_truncated,
                "stdout_sha256": hashlib.sha256(stdout).hexdigest(),
                "stdout_truncated": stdout_truncated,
            },
        )

    def start(self) -> None:
        try:
            self.process = subprocess.Popen(
                self.arguments,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                shell=False,
                creationflags=_popen_creation_flags(),
            )
        except OSError as error:
            raise CollectorError("negative_proxy_launch_failed") from error
        if self.process.stdout is None:
            self.stop()
            raise CollectorError("negative_proxy_stdout_unavailable")
        try:
            self._first_line = _readline_with_timeout(
                self.process.stdout,
                timeout_seconds=15,
            )
            ready = _strict_json_line(
                self._first_line,
                code="negative_proxy_ready_invalid",
            )
            if ready != {
                "listen_host": "127.0.0.1",
                "listen_port": NEGATIVE_DEVICE_PORT,
                "status": "ready",
            }:
                raise CollectorError("negative_proxy_ready_invalid")
        except CollectorError:
            self._persist_startup_failure()
            raise

    def wait(self, *, expected_run_id: str, timeout_seconds: int) -> None:
        if (
            self.process is None
            or self.process.stdout is None
            or self.process.stderr is None
        ):
            raise CollectorError("negative_proxy_not_started")
        try:
            returncode = self.process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired as error:
            raise CollectorError("negative_proxy_timeout") from error
        remainder = self.process.stdout.read(2 * 1024 * 1024 + 1)
        stderr = self.process.stderr.read(2 * 1024 * 1024 + 1)
        stdout = self._first_line + remainder
        _write_exclusive_bytes(self.output_path, stdout)
        _write_exclusive_bytes(self.stderr_path, stderr)
        lines = stdout.splitlines(keepends=True)
        if len(lines) != 2:
            raise CollectorError("negative_proxy_terminal_invalid")
        terminal = _strict_json_line(
            lines[1],
            code="negative_proxy_terminal_invalid",
        )
        if (
            returncode != 0
            or stderr
            or terminal
            != {
                "listen_host": "127.0.0.1",
                "listen_port": NEGATIVE_DEVICE_PORT,
                "reason_code": "ok",
                "run_id": expected_run_id,
                "status": "pass",
            }
        ):
            raise CollectorError("negative_proxy_terminal_invalid")

    def stop(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)


def start_busy_sentinel(
    adb: AdbClient,
    *,
    evidence_directory: Path,
    stage: str,
) -> None:
    if re.fullmatch(r"[a-z0-9_-]{1,64}", stage) is None:
        raise CollectorError("busy_sentinel_stage_invalid")
    adb.text(
        [
            "shell",
            "am",
            "start",
            "-W",
            "-a",
            "android.settings.SETTINGS",
        ],
        code=f"busy_sentinel_start_{stage}",
    )
    activity = adb.text(
        ["shell", "dumpsys", "activity", "activities"],
        code=f"busy_sentinel_activity_{stage}",
    )
    focused, resumed = _extract_focus(activity)
    observed = (focused, *resumed)
    if any(not component.startswith("com.android.settings/") for component in observed):
        raise CollectorError(f"busy_sentinel_not_focused stage={stage}")
    _write_exclusive_json(
        evidence_directory / f"busy-sentinel-{stage}.json",
        {
            "schema": "aneb-realtime-busy-sentinel",
            "schema_version": "1.0.0",
            "stage": stage,
            "observed_components": list(observed),
            "matched": True,
        },
    )


def journal_export_command(cursor: str) -> str:
    if (
        re.fullmatch(r"[A-Za-z0-9;:_.=-]{10,1024}", cursor) is None
        or "'" in cursor
    ):
        raise CollectorError("journal_cursor_invalid")
    return (
        "set -Eeuo pipefail; "
        "journalctl --unit aneb-server.service "
        f"--after-cursor '{cursor}' --output=cat --no-pager"
    )


def wait_for_end_barrier(
    *,
    ssh: SshClient,
    lock: PersistentRemoteLock,
    cursor: str,
    barrier_id: str,
    timeout_seconds: int = 20,
) -> None:
    try:
        parsed = uuid.UUID(barrier_id)
    except ValueError as error:
        raise CollectorError("barrier_id_invalid") from error
    if parsed.version != 4 or str(parsed) != barrier_id:
        raise CollectorError("barrier_id_invalid")
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        lock.assert_healthy("wait_end_barrier")
        command = (
            "set -Eeuo pipefail; "
            f"if {journal_export_command(cursor).removeprefix('set -Eeuo pipefail; ')} "
            f"| grep -F -- 'role=window_end scope=realtime_run "
            f"run_id={barrier_id}' >/dev/null; "
            "then printf 'SEEN\\n'; else printf 'WAIT\\n'; fi"
        )
        result = ssh.text(command, code="wait_end_barrier")
        if result == "SEEN":
            return
        if result != "WAIT":
            raise CollectorError("end_barrier_wait_invalid")
        time.sleep(0.25)
    raise CollectorError("end_barrier_not_observed")


def remote_snapshot_script() -> str:
    return r"""set -Eeuo pipefail
IFS=$'\n\t'
unit='aneb-server.service'
systemctl is-active --quiet "$unit"
hash_stream() { sha256sum | awk '{print $1}'; }
canonicalize_iptables_save() {
    local expected_tool="${1:?expected iptables-save tool required}"
    [[ "$expected_tool" == "iptables-save" || "$expected_tool" == "ip6tables-save" ]] || exit 64
    python3 -c '
from datetime import datetime
import re
import sys

expected_tool = sys.argv[1].encode("ascii")
data = sys.stdin.buffer.read()
if not data:
    raise SystemExit("empty iptables-save snapshot")
lines = data.splitlines(keepends=True)
if len(lines) < 2:
    raise SystemExit("incomplete iptables-save snapshot")
bodies = [line.rstrip(b"\r\n") for line in lines]
generated = [index for index, body in enumerate(bodies) if body.startswith(b"# Generated by")]
completed = [index for index, body in enumerate(bodies) if body.startswith(b"# Completed on")]
if not generated or len(generated) != len(completed):
    raise SystemExit("iptables-save wrapper positions invalid")
header_prefix = b"# Generated by " + expected_tool + b" "
completed_prefix = b"# Completed on "
timestamp_pattern = re.compile(
    rb"(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) "
    rb"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) "
    rb"(?: [1-9]|[12][0-9]|3[01]) "
    rb"(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9] [0-9]{4}"
)
def validate_timestamp(value, label):
    if not timestamp_pattern.fullmatch(value):
        raise SystemExit(f"malformed {label} timestamp")
    text = value.decode("ascii")
    parsed = datetime.strptime(text, "%a %b %d %H:%M:%S %Y")
    canonical = "{} {:2d} {}".format(
        parsed.strftime("%a %b"),
        parsed.day,
        parsed.strftime("%H:%M:%S %Y"),
    )
    if text != canonical:
        raise SystemExit(f"non-canonical {label} timestamp")
previous_completed = -1
for block_index, (generated_index, completed_index) in enumerate(zip(generated, completed)):
    expected_generated = 0 if block_index == 0 else previous_completed + 1
    if generated_index != expected_generated or completed_index <= generated_index + 1:
        raise SystemExit("iptables-save wrapper positions invalid")
    if block_index == len(generated) - 1 and completed_index != len(lines) - 1:
        raise SystemExit("iptables-save wrapper positions invalid")
    header = bodies[generated_index]
    if not header.startswith(header_prefix):
        raise SystemExit("iptables-save Generated tool identity mismatch")
    identity, separator, generated_timestamp = header.rpartition(b" on ")
    if (
        not separator
        or not identity.startswith(header_prefix)
        or not identity[len(header_prefix):].strip()
    ):
        raise SystemExit("malformed iptables-save Generated header")
    footer = bodies[completed_index]
    if not footer.startswith(completed_prefix):
        raise SystemExit("malformed iptables-save Completed footer")
    validate_timestamp(generated_timestamp, "Generated")
    validate_timestamp(footer[len(completed_prefix):], "Completed")
    newline = b"\r\n" if lines[generated_index].endswith(b"\r\n") else b"\n" if lines[generated_index].endswith(b"\n") else b""
    if not newline:
        raise SystemExit("Generated header must end with a newline")
    sys.stdout.buffer.write(identity + newline)
    for line in lines[generated_index + 1:completed_index]:
        sys.stdout.buffer.write(line)
    previous_completed = completed_index
' "$expected_tool"
}
v4_snapshot="$(iptables-save | canonicalize_iptables_save iptables-save)"
v6_snapshot="$(ip6tables-save | canonicalize_iptables_save ip6tables-save)"
nft_snapshot="$(nft --stateless list ruleset)"
v4="$(printf '%s\n' "$v4_snapshot" | hash_stream)"
v6="$(printf '%s\n' "$v6_snapshot" | hash_stream)"
nft_sha="$(printf '%s\n' "$nft_snapshot" | hash_stream)"
full="$({
    printf '%s\n' "$v4_snapshot"
    printf '%s\n' "$v6_snapshot"
    printf '%s\n' "$nft_snapshot"
} | hash_stream)"
docker="$({
    printf '%s\n' "$v4_snapshot" | awk '/(^:DOCKER|DOCKER)/ { print }'
    printf '%s\n' "$v6_snapshot" | awk '/(^:DOCKER|DOCKER)/ { print }'
} | hash_stream)"
qdisc="$(tc qdisc show dev eth0 | hash_stream)"
cursor="$(journalctl --unit "$unit" --lines=0 --show-cursor --output=cat --no-pager |
    awk -F': ' '/^-- cursor:/ { print $2 }')"
printf 'boot_id=%s\n' "$(tr -d '-' < /proc/sys/kernel/random/boot_id)"
printf 'systemd_invocation_id=%s\n' \
    "$(systemctl show "$unit" --property=InvocationID --value)"
printf 'main_pid=%s\n' "$(systemctl show "$unit" --property=MainPID --value)"
printf 'server_binary_sha256=%s\n' \
    "$(sha256sum /opt/aneb/bin/aneb-server | awk '{print $1}')"
printf 'eth0_qdisc_sha256=%s\n' "$qdisc"
printf 'firewall_full_sha256=%s\n' "$full"
printf 'firewall_v4_sha256=%s\n' "$v4"
printf 'firewall_v6_sha256=%s\n' "$v6"
printf 'firewall_nft_sha256=%s\n' "$nft_sha"
printf 'docker_sha256=%s\n' "$docker"
printf 'journal_cursor=%s\n' "$cursor"
"""


def capture_remote_snapshot(
    *,
    ssh: SshClient,
    lock: PersistentRemoteLock,
    stage: str,
) -> RemoteSnapshot:
    lock.assert_healthy(stage)
    return parse_remote_snapshot(
        ssh.text(remote_snapshot_script(), code=f"remote_snapshot_{stage}")
    )


def export_locked_journal(
    *,
    ssh: SshClient,
    lock: PersistentRemoteLock,
    cursor: str,
    output: Path,
) -> None:
    lock.assert_healthy("journal_export")
    text = ssh.text(
        journal_export_command(cursor),
        code="journal_export",
        max_output_bytes=16 * 1024 * 1024,
    )
    if not text:
        raise CollectorError("journal_export_empty")
    _write_exclusive_bytes(output, (text + "\n").encode("utf-8"))


def _run_json_verifier(
    *,
    runner: CommandRunner,
    arguments: Sequence[str],
    output: Path,
    code: str,
    timeout_seconds: int,
) -> dict[str, object]:
    result = runner.run(
        arguments,
        timeout_seconds=timeout_seconds,
        max_output_bytes=16 * 1024 * 1024,
    )
    if result.returncode != 0 or result.stderr:
        raise CollectorError(f"{code}_failed rc={result.returncode}")
    report = _strict_json_line(result.stdout, code=f"{code}_output_invalid")
    if report.get("status") != "pass" or report.get("reason_code") != "ok":
        raise CollectorError(f"{code}_report_not_pass")
    _write_exclusive_bytes(output, _canonical_json_bytes(report))
    return report


def run_realtime_verifiers(
    *,
    runner: CommandRunner,
    python_path: Path,
    evidence_directory: Path,
    run_id: str,
    mode: Literal["positive", "negative"],
    expected_server_base: str,
    start_barrier_id: str,
    end_barrier_id: str,
    serverinfo_path: Path,
    ca_file_sha256: str,
    negative_upstream_url: str | None,
    timeout_seconds: int,
) -> dict[str, object]:
    validate_run_id(run_id)
    if (
        mode == "positive"
        and negative_upstream_url is not None
        or mode == "negative"
        and (
            not isinstance(negative_upstream_url, str)
            or re.fullmatch(
                r"https://(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\]):"
                r"[1-9][0-9]{0,4}/api/v1/serverinfo",
                negative_upstream_url,
            )
            is None
        )
    ):
        raise CollectorError("negative_upstream_url_invalid")
    scripts = Path(__file__).resolve().parent
    database = evidence_directory / "aneb-probe.db"
    client_report_path = evidence_directory / "client-db-report.json"
    client_result_path = evidence_directory / "client-result.json"
    audit_report_path = evidence_directory / "request-entry-audit.json"
    journal_path = evidence_directory / "journal.raw.log"
    client_arguments = [
        str(python_path),
        str(scripts / "verify_realtime_quick_client_db.py"),
        str(database),
        "--run-id",
        run_id,
        "--mode",
        mode,
        "--expected-server-base",
        expected_server_base,
        "--result-output",
        str(client_result_path),
    ]
    client = _run_json_verifier(
        runner=runner,
        arguments=client_arguments,
        output=client_report_path,
        code="client_db_verifier",
        timeout_seconds=timeout_seconds,
    )
    if client.get("run_id") != run_id or client.get("mode") != mode:
        raise CollectorError("client_db_verifier_binding_mismatch")
    audit_arguments = [
        str(python_path),
        str(scripts / "verify_realtime_quick_run_audit.py"),
        str(journal_path),
        "--run-id",
        run_id,
        "--start-barrier-id",
        start_barrier_id,
        "--barrier-id",
        end_barrier_id,
        "--mode",
        mode,
        "--profile-contract",
        PROFILE_CONTRACT,
    ]
    audit = _run_json_verifier(
        runner=runner,
        arguments=audit_arguments,
        output=audit_report_path,
        code="run_audit_verifier",
        timeout_seconds=timeout_seconds,
    )
    if audit.get("run_id") != run_id or audit.get("mode") != mode:
        raise CollectorError("run_audit_verifier_binding_mismatch")
    cross_arguments = [
        str(python_path),
        str(scripts / "verify_realtime_quick_evidence_bundle.py"),
        "--mode",
        mode,
        "--client-database",
        str(database),
        "--client-report",
        str(client_report_path),
        "--client-result",
        str(client_result_path),
        "--audit-report",
        str(audit_report_path),
        "--journal",
        str(journal_path),
        "--start-barrier-id",
        start_barrier_id,
        "--barrier-id",
        end_barrier_id,
        "--serverinfo",
        str(serverinfo_path),
    ]
    if mode == "negative":
        cross_arguments.extend(
            [
                "--negative-proxy-bundle",
                str(evidence_directory),
                "--negative-upstream-url",
                str(negative_upstream_url),
                "--negative-ca-file-sha256",
                ca_file_sha256,
                "--negative-device-port",
                str(NEGATIVE_DEVICE_PORT),
            ]
        )
    cross = _run_json_verifier(
        runner=runner,
        arguments=cross_arguments,
        output=evidence_directory / "cross-bound-report.json",
        code="cross_bound_verifier",
        timeout_seconds=timeout_seconds,
    )
    if (
        cross.get("run_id") != run_id
        or cross.get("mode") != mode
        or cross.get("cross_bound") is not True
    ):
        raise CollectorError("cross_bound_verifier_binding_mismatch")
    return cross


def _canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_evidence_manifest(root: Path) -> Path:
    if not root.is_dir() or root.is_symlink():
        raise CollectorError("evidence_root_invalid")
    output = root / "evidence-manifest.json"
    files: list[dict[str, object]] = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise CollectorError("evidence_symlink_forbidden")
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if relative in {"evidence-manifest.json", "COMPLETE"}:
            continue
        files.append(
            {
                "path": relative,
                "bytes": path.stat().st_size,
                "sha256": _sha256_file(path),
            }
        )
    payload = {
        "schema": "aneb-realtime-quick-evidence-manifest",
        "schema_version": "1.0.0",
        "files": files,
    }
    temporary = root / ".evidence-manifest.json.tmp"
    if temporary.exists() or output.exists():
        raise CollectorError("evidence_manifest_exists")
    with temporary.open("xb") as stream:
        stream.write(_canonical_json_bytes(payload))
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, output)
    return output


def verify_evidence_manifest(root: Path) -> None:
    try:
        manifest_path = root / "evidence-manifest.json"
        raw = manifest_path.read_bytes()
        manifest = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_json_object,
            parse_constant=lambda item: (_ for _ in ()).throw(ValueError(item)),
        )
        if (
            not _exact_keys(manifest, {"schema", "schema_version", "files"})
            or manifest["schema"] != "aneb-realtime-quick-evidence-manifest"
            or manifest["schema_version"] != "1.0.0"
            or not isinstance(manifest["files"], list)
        ):
            raise ValueError("shape")
        expected_paths: list[str] = []
        actual_files: dict[str, Path] = {}
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                raise ValueError("symlink")
            if path.is_file():
                relative = path.relative_to(root).as_posix()
                if relative not in {"evidence-manifest.json", "COMPLETE"}:
                    expected_paths.append(relative)
                    actual_files[relative] = path
        records = manifest["files"]
        observed_paths: list[str] = []
        for record in records:
            if (
                not _exact_keys(record, {"path", "bytes", "sha256"})
                or not isinstance(record["path"], str)
                or type(record["bytes"]) is not int
                or record["bytes"] < 0
                or not isinstance(record["sha256"], str)
                or re.fullmatch(r"[0-9a-f]{64}", record["sha256"]) is None
                or record["path"] not in actual_files
            ):
                raise ValueError("record")
            path = actual_files[record["path"]]
            if (
                path.stat().st_size != record["bytes"]
                or _sha256_file(path) != record["sha256"]
            ):
                raise ValueError("digest")
            observed_paths.append(record["path"])
        if observed_paths != expected_paths:
            raise ValueError("coverage")
    except (
        KeyError,
        OSError,
        TypeError,
        UnicodeError,
        ValueError,
        json.JSONDecodeError,
    ) as error:
        raise CollectorError("evidence_manifest_invalid") from error


def atomic_publish(partial: Path, complete: Path) -> None:
    status_path = partial / "collector-status.json"
    try:
        status = json.loads(status_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CollectorError("publish_not_ready") from error
    if not isinstance(status, dict) or status.get("status") != "pass":
        raise CollectorError("publish_not_ready")
    if complete.exists():
        raise CollectorError("publish_target_exists")
    if not (partial / "evidence-manifest.json").is_file() or not (
        partial / "COMPLETE"
    ).is_file():
        raise CollectorError("publish_not_ready")
    verify_evidence_manifest(partial)
    os.replace(partial, complete)


def verify_before_atomic_publish(
    partial: Path,
    *,
    collection_id: str,
) -> dict[str, object]:
    try:
        report = collection_verifier.verify_collection(
            partial,
            expected_collection=collection_id,
            allow_partial=True,
        )
    except collection_verifier.CollectionVerificationFailure as error:
        raise CollectorError(
            "prepublish_collection_verification_failed "
            f"reason={error.reason_code}"
        ) from error
    if report.get("status") != "pass" or report.get("collection_id") != collection_id:
        raise CollectorError("prepublish_collection_verification_failed reason=report")
    return report


def _error_text(error: BaseException) -> str:
    text = str(error).strip()
    return text if text else error.__class__.__name__


def run_workflow(backend: WorkflowBackend) -> WorkflowResult:
    primary_failure: str | None = None
    cleanup_failures: list[str] = []
    publish_failure: str | None = None
    collected = False
    preflighted = False
    try:
        backend.preflight()
        preflighted = True
        backend.acquire()
        backend.collect()
        collected = True
    except BaseException as error:
        primary_failure = _error_text(error)
    finally:
        if preflighted:
            for cleanup in (backend.cleanup_phone, backend.cleanup_remote):
                try:
                    cleanup()
                except BaseException as error:
                    cleanup_failures.append(_error_text(error))
    if collected and primary_failure is None and not cleanup_failures:
        try:
            backend.publish()
        except BaseException as error:
            publish_failure = _error_text(error)
    return WorkflowResult(
        success=(
            collected
            and primary_failure is None
            and not cleanup_failures
            and publish_failure is None
        ),
        primary_failure=primary_failure,
        cleanup_failures=tuple(cleanup_failures),
        publish_failure=publish_failure,
    )


def _remote_snapshot_document(snapshot: RemoteSnapshot) -> dict[str, str]:
    return {
        key: getattr(snapshot, key)
        for key in REMOTE_SNAPSHOT_KEYS
    }


def _serverinfo_from_bytes(payload: bytes) -> dict[str, object]:
    try:
        value = json.loads(
            payload.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_json_object,
            parse_constant=lambda item: (_ for _ in ()).throw(ValueError(item)),
        )
        validate_serverinfo(value)
        if not isinstance(value, dict):
            raise ValueError("shape")
        return value
    except (CollectorError, TypeError, UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise CollectorError("serverinfo_contract_invalid") from error


class LiveCollectorBackend:
    """One fail-closed, bounded Realtime Quick live collection lifecycle."""

    def __init__(
        self,
        config: CollectorConfig,
        *,
        install_candidate: bool,
        runner: CommandRunner | None = None,
    ) -> None:
        self.config = config
        self.install_candidate = install_candidate
        self.runner = runner or SubprocessRunner()
        self.adb = AdbClient(
            runner=self.runner,
            executable=config.adb_path,
            serial=config.adb_serial,
            timeout_seconds=config.command_timeout_seconds,
        )
        self.ssh = SshClient(
            runner=self.runner,
            executable=config.ssh_path,
            remote=config.remote,
            ssh_key=config.ssh_key,
            known_hosts=config.known_hosts,
            timeout_seconds=config.command_timeout_seconds,
        )
        self.collection_id = (
            "m0-ec2-realtime-"
            + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
            + "-"
            + uuid.uuid4().hex
        )
        self.partial = config.evidence_root / f"{self.collection_id}.partial"
        self.complete = config.evidence_root / f"{self.collection_id}.complete"
        self.policy: dict[str, object] | None = None
        self.candidate_identity: CiCandidateIdentity | None = None
        self.candidate_report: dict[str, object] | None = None
        self.original_stayon: str | None = None
        self.lock: PersistentRemoteLock | None = None
        self.lock_acquired = False
        self.remote_before: RemoteSnapshot | None = None
        self.identity_capture: HttpCapture | None = None
        self.start_capture: HttpCapture | None = None
        self.end_capture: HttpCapture | None = None
        self.start_barrier_id = str(uuid.uuid4())
        self.end_barrier_id = str(uuid.uuid4())
        self.start_barrier_attempted = False
        self.end_barrier_attempted = False
        self.run_markers: RealtimeTerminalMarkers | None = None
        self.logcat: LogcatCapture | None = None
        self.proxy: NegativeProxyProcess | None = None
        self.reverse_owned = False
        self.reverse_transport_label: str | None = None
        self.app_launch_attempted = False
        self.settings_started = False
        self.stayon_mutation_attempted = False
        self.stayon_changed = False
        self.live_preflight_complete = False
        self.cleanup_phone_complete = False
        self.cleanup_remote_complete = False
        self.ready_path: Path | None = None

    @property
    def client_server_base(self) -> str:
        if self.config.evidence_mode == "negative":
            return f"http://127.0.0.1:{NEGATIVE_DEVICE_PORT}"
        return self.config.server_base.rstrip("/")

    @property
    def negative_upstream_url(self) -> str | None:
        if self.config.evidence_mode != "negative":
            return None
        parsed = urllib.parse.urlsplit(self.config.server_base)
        return (
            f"https://{parsed.hostname}:{parsed.port or 443}"
            "/api/v1/serverinfo"
        )

    def _write_plan(self) -> None:
        if self.candidate_identity is None:
            raise CollectorError("candidate_identity_missing")
        _write_exclusive_json(
            self.partial / "collector-plan.json",
            {
                "schema": "aneb-realtime-quick-collector-plan",
                "schema_version": "1.0.0",
                "collection_id": self.collection_id,
                "profile_contract": PROFILE_CONTRACT,
                "evidence_mode": self.config.evidence_mode,
                "transport": self.config.transport,
                "package_name": PACKAGE_NAME,
                "version_name": EXPECTED_VERSION_NAME,
                "version_code": EXPECTED_VERSION_CODE,
                "server_base": self.config.server_base.rstrip("/"),
                "client_server_base": self.client_server_base,
                "negative_upstream_url": self.negative_upstream_url,
                "remote_host": self.config.remote.split("@", 1)[1],
                "expected_server_version": EXPECTED_SERVER_VERSION,
                "expected_server_binary_sha256": (
                    self.config.expected_server_binary_sha256.lower()
                ),
                "expected_apk_sha256": self.candidate_identity.apk_sha256,
                "expected_signer_sha256": self.candidate_identity.signer_sha256,
                "source_commit": self.config.source_commit,
                "workflow_run_id": self.candidate_identity.workflow_run_id,
                "server_ca_sha256": _sha256_file(self.config.server_ca_path),
                "device_policy_sha256": _sha256_file(self.config.device_policy),
                "adb_serial_sha256": hashlib.sha256(
                    self.config.adb_serial.encode("utf-8")
                ).hexdigest(),
                "start_barrier_id": self.start_barrier_id,
                "end_barrier_id": self.end_barrier_id,
                "run_timeout_seconds": self.config.run_timeout_seconds,
                "lock_ttl_seconds": self.config.lock_ttl_seconds,
                "install_candidate": self.install_candidate,
            },
        )

    def _assert_repository_frozen(self) -> None:
        repository = Path(__file__).resolve().parents[1]
        head = _checked(
            self.runner,
            ["git", "-C", str(repository), "rev-parse", "HEAD"],
            timeout_seconds=self.config.command_timeout_seconds,
            code="git_head",
        )
        status = _checked(
            self.runner,
            [
                "git",
                "-C",
                str(repository),
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
            ],
            timeout_seconds=self.config.command_timeout_seconds,
            code="git_status",
        )
        if (
            _decode_utf8(head.stdout, "git_head_utf8").strip()
            != self.config.source_commit
            or status.stdout
            or status.stderr
            or head.stderr
        ):
            raise CollectorError("repository_not_frozen_at_source_commit")

    def preflight(self) -> None:
        validate_config(self.config)
        self._assert_repository_frozen()
        prepare_evidence_staging(self.config.evidence_root, self.partial)
        self.policy = load_device_policy(
            self.config.device_policy,
            adb_serial=self.config.adb_serial,
        )
        source_before = _candidate_snapshot(self.config.candidate_directory)
        source_report, source_identity = verify_ci_candidate(
            runner=self.runner,
            python_path=self.config.python_path,
            gh_path=self.config.gh_path,
            candidate_directory=self.config.candidate_directory,
            source_commit=self.config.source_commit,
            report_output=self.partial / "ci-source-before.json",
            timeout_seconds=self.config.command_timeout_seconds,
        )
        bundled_directory = self.partial / "ci-candidate"
        _copy_candidate(
            self.config.candidate_directory,
            bundled_directory,
            expected=source_before,
        )
        bundled_report, bundled_identity = verify_ci_candidate(
            runner=self.runner,
            python_path=self.config.python_path,
            gh_path=self.config.gh_path,
            candidate_directory=bundled_directory,
            source_commit=self.config.source_commit,
            report_output=self.partial / "ci-candidate-verification.json",
            timeout_seconds=self.config.command_timeout_seconds,
        )
        source_after_report, source_after_identity = verify_ci_candidate(
            runner=self.runner,
            python_path=self.config.python_path,
            gh_path=self.config.gh_path,
            candidate_directory=self.config.candidate_directory,
            source_commit=self.config.source_commit,
            report_output=self.partial / "ci-source-after.json",
            timeout_seconds=self.config.command_timeout_seconds,
        )
        if (
            source_before != _candidate_snapshot(self.config.candidate_directory)
            or source_report != bundled_report
            or source_report != source_after_report
            or source_identity != bundled_identity
            or source_identity != source_after_identity
        ):
            raise CollectorError("ci_candidate_verification_drift")
        self.candidate_identity = bundled_identity
        self.candidate_report = bundled_report
        _write_exclusive_bytes(
            self.partial / "device-policy.json",
            self.config.device_policy.read_bytes(),
        )
        first, second = capture_stable_phone_evidence(
            self.adb,
            directory=self.partial,
            prefix="phone-preflight",
        )
        self.original_stayon = second.stayon
        assert self.policy is not None
        verify_device_policy_live(
            self.adb,
            policy=self.policy,
            directory=self.partial,
        )
        self.live_preflight_complete = True
        self._write_plan()

    def acquire(self) -> None:
        self.lock = PersistentRemoteLock(
            ssh=self.ssh,
            ttl_seconds=self.config.lock_ttl_seconds,
        )
        receipt = self.lock.acquire()
        self.lock_acquired = True
        _write_exclusive_bytes(
            self.partial / "lock-acquired.txt",
            (receipt + "\n").encode("utf-8"),
        )
        self.remote_before = capture_remote_snapshot(
            ssh=self.ssh,
            lock=self.lock,
            stage="pre",
        )
        if (
            self.remote_before.server_binary_sha256
            != self.config.expected_server_binary_sha256.lower()
        ):
            raise CollectorError("remote_binary_identity_mismatch")
        _write_exclusive_json(
            self.partial / "remote-pre.json",
            _remote_snapshot_document(self.remote_before),
        )
        self.identity_capture = fetch_serverinfo(
            server_base=self.config.server_base,
            ca_path=self.config.server_ca_path,
            timeout_seconds=self.config.command_timeout_seconds,
        )
        write_http_capture(
            self.partial,
            name="identity-serverinfo",
            capture=self.identity_capture,
        )
        if self.candidate_identity is None:
            raise CollectorError("candidate_identity_missing")
        verify_or_install_candidate(
            self.adb,
            candidate_directory=self.partial / "ci-candidate",
            identity=self.candidate_identity,
            evidence_directory=self.partial,
            install=self.install_candidate,
        )
        self.settings_started = True
        start_busy_sentinel(
            self.adb,
            evidence_directory=self.partial,
            stage="acquired",
        )
        if self.original_stayon is None:
            raise CollectorError("original_stayon_unknown")
        if self.original_stayon != "7":
            self.stayon_mutation_attempted = True
            self.adb.text(
                [
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "put",
                    "global",
                    "stay_on_while_plugged_in",
                    "7",
                ],
                code="enable_stayon",
            )
            self.stayon_changed = True
            actual = self.adb.text(
                [
                    "shell",
                    "settings",
                    "--user",
                    "0",
                    "get",
                    "global",
                    "stay_on_while_plugged_in",
                ],
                code="verify_stayon_enabled",
            )
            if actual != "7":
                raise CollectorError("stayon_enable_verification_failed")
        self.logcat = LogcatCapture(
            adb=self.adb,
            output_path=self.partial / "app-logcat.txt",
            stderr_path=self.partial / "app-logcat.stderr.txt",
        )
        self.logcat.start()

    def _barrier(
        self,
        *,
        role: Literal["window_start", "window_end"],
    ) -> HttpCapture:
        if role == "window_start":
            if self.start_barrier_attempted:
                raise CollectorError("barrier_already_attempted role=window_start")
            self.start_barrier_attempted = True
            barrier_id = self.start_barrier_id
            name = "start-barrier"
        else:
            if self.end_barrier_attempted:
                raise CollectorError("barrier_already_attempted role=window_end")
            self.end_barrier_attempted = True
            barrier_id = self.end_barrier_id
            name = "end-barrier"
        capture = fetch_serverinfo(
            server_base=self.config.server_base,
            ca_path=self.config.server_ca_path,
            timeout_seconds=self.config.command_timeout_seconds,
            headers=build_audit_headers(run_id=barrier_id, role=role),
        )
        write_http_capture(self.partial, name=name, capture=capture)
        return capture

    def _start_negative_proxy(self) -> None:
        inventory_text = self.adb.text(
            ["reverse", "--list"],
            code="adb_reverse_pre",
        )
        _write_exclusive_bytes(
            self.partial / "adb-reverse-preflight.txt",
            (inventory_text + "\n").encode("utf-8"),
        )
        inventory = parse_reverse_inventory(inventory_text)
        if inventory:
            raise CollectorError("negative_reverse_inventory_not_empty")
        assert_reverse_absent(
            inventory,
            device_port=NEGATIVE_DEVICE_PORT,
        )
        self.proxy = NegativeProxyProcess(
            python_path=self.config.python_path,
            server_base=self.config.server_base,
            ca_path=self.config.server_ca_path,
            evidence_directory=self.partial,
            request_timeout_seconds=self.config.command_timeout_seconds,
        )
        self.proxy.start()
        self.adb.text(
            [
                "reverse",
                "--no-rebind",
                f"tcp:{NEGATIVE_DEVICE_PORT}",
                f"tcp:{NEGATIVE_DEVICE_PORT}",
            ],
            code="adb_reverse_create",
        )
        self.reverse_owned = True
        current_text = self.adb.text(
            ["reverse", "--list"],
            code="adb_reverse_verify",
        )
        _write_exclusive_bytes(
            self.partial / "adb-reverse-active.txt",
            (current_text + "\n").encode("utf-8"),
        )
        current_inventory = parse_reverse_inventory(current_text)
        endpoint = f"tcp:{NEGATIVE_DEVICE_PORT}"
        if (
            len(current_inventory) != 1
            or current_inventory[0][1:] != (endpoint, endpoint)
        ):
            raise CollectorError("negative_reverse_inventory_polluted")
        self.reverse_transport_label = current_inventory[0][0]
        assert_owned_reverse(
            current_inventory,
            transport_label=self.reverse_transport_label,
            device_port=NEGATIVE_DEVICE_PORT,
        )

    def _remove_reverse(self) -> None:
        if not self.reverse_owned:
            return
        if self.reverse_transport_label is None:
            raise CollectorError("negative_reverse_ownership_lost")
        current = parse_reverse_inventory(
            self.adb.text(["reverse", "--list"], code="adb_reverse_before_remove")
        )
        before_remove_path = self.partial / "adb-reverse-before-remove.txt"
        owned = _owned_reverse(
            current,
            transport_label=self.reverse_transport_label,
            device_port=NEGATIVE_DEVICE_PORT,
        )
        if owned:
            if current != owned:
                raise CollectorError("negative_reverse_inventory_polluted")
            assert_owned_reverse(
                current,
                transport_label=self.reverse_transport_label,
                device_port=NEGATIVE_DEVICE_PORT,
            )
            before_remove_payload = (
                "\n".join(" ".join(item) for item in current) + "\n"
            ).encode("utf-8")
            if before_remove_path.exists():
                if before_remove_path.read_bytes() != before_remove_payload:
                    raise CollectorError(
                        "adb_reverse_before_remove_evidence_drift"
                    )
            else:
                _write_exclusive_bytes(
                    before_remove_path,
                    before_remove_payload,
                )
            self.adb.text(
                ["reverse", "--remove", f"tcp:{NEGATIVE_DEVICE_PORT}"],
                code="adb_reverse_remove",
            )
        elif current:
            raise CollectorError("negative_reverse_inventory_polluted")
        elif not before_remove_path.is_file():
            raise CollectorError("negative_reverse_ownership_lost")
        final_text = self.adb.text(
            ["reverse", "--list"],
            code="adb_reverse_final",
        )
        assert_reverse_absent(
            parse_reverse_inventory(final_text),
            device_port=NEGATIVE_DEVICE_PORT,
        )
        if parse_reverse_inventory(final_text):
            raise CollectorError("negative_reverse_final_inventory_not_empty")
        if not (self.partial / "adb-reverse-final.txt").exists():
            _write_exclusive_bytes(
                self.partial / "adb-reverse-final.txt",
                (final_text + "\n").encode("utf-8"),
            )
        self.reverse_owned = False
        self.reverse_transport_label = None

    def _stop_target(self) -> None:
        if not self.app_launch_attempted:
            return
        self.adb.text(
            ["shell", "am", "force-stop", PACKAGE_NAME],
            code="force_stop_target",
        )
        pid = self.adb.text(
            ["shell", "pidof", PACKAGE_NAME],
            code="target_pid_after_stop",
            allowed_returncodes=frozenset({0, 1}),
        )
        services = self.adb.text(
            ["shell", "dumpsys", "activity", "services", PACKAGE_NAME],
            code="target_services_after_stop",
        )
        if pid or not _is_empty_service_dump(services):
            raise CollectorError("target_app_not_stopped")
        self.app_launch_attempted = False

    def collect(self) -> None:
        if (
            self.lock is None
            or self.remote_before is None
            or self.identity_capture is None
            or self.logcat is None
        ):
            raise CollectorError("collector_not_acquired")
        self.lock.assert_healthy("before_start_barrier")
        self.start_capture = self._barrier(role="window_start")
        if self.config.evidence_mode == "negative":
            self._start_negative_proxy()
        start_busy_sentinel(
            self.adb,
            evidence_directory=self.partial,
            stage="before-target",
        )
        command = build_realtime_launch_arguments(
            serial=self.config.adb_serial,
            server_base=self.client_server_base,
            transport=self.config.transport,
            adb_path=self.adb.executable,
        )
        self.app_launch_attempted = True
        launch = self.adb.run(
            command[3:],
            code="start_realtime_quick",
            max_output_bytes=1024 * 1024,
        )
        if launch.stderr:
            raise CollectorError("start_realtime_quick_stderr")
        _write_exclusive_bytes(
            self.partial / "app-launch.txt",
            launch.stdout,
        )
        self.run_markers = self.logcat.wait_terminal(
            mode=self.config.evidence_mode,
            timeout_seconds=self.config.run_timeout_seconds,
        )
        if self.proxy is not None:
            self.proxy.wait(
                expected_run_id=self.run_markers.run_id,
                timeout_seconds=30,
            )
        self._stop_target()
        self._remove_reverse()
        if self.proxy is not None:
            self.proxy.stop()
        start_busy_sentinel(
            self.adb,
            evidence_directory=self.partial,
            stage="before-end-barrier",
        )
        self.lock.assert_healthy("before_end_barrier")
        self.end_capture = self._barrier(role="window_end")
        wait_for_end_barrier(
            ssh=self.ssh,
            lock=self.lock,
            cursor=self.remote_before.journal_cursor,
            barrier_id=self.end_barrier_id,
        )
        remote_mid = capture_remote_snapshot(
            ssh=self.ssh,
            lock=self.lock,
            stage="post_window",
        )
        assert_remote_snapshot_stable(
            self.remote_before,
            remote_mid,
            expected_binary_sha256=(
                self.config.expected_server_binary_sha256.lower()
            ),
        )
        _write_exclusive_json(
            self.partial / "remote-post-window.json",
            _remote_snapshot_document(remote_mid),
        )
        self.logcat.stop()
        copy_frozen_room_database(
            self.adb,
            evidence_directory=self.partial,
        )
        export_locked_journal(
            ssh=self.ssh,
            lock=self.lock,
            cursor=self.remote_before.journal_cursor,
            output=self.partial / "journal.raw.log",
        )
        assert self.start_capture is not None
        assert self.end_capture is not None
        assert_serverinfo_sequence(
            self.identity_capture.json_body,
            self.start_capture.json_body,
            self.end_capture.json_body,
        )
        if self.config.evidence_mode == "negative":
            serverinfo_path = (
                self.partial
                / "negative-proxy"
                / "upstream-serverinfo.raw"
            )
            _serverinfo_from_bytes(serverinfo_path.read_bytes())
        else:
            serverinfo_path = self.partial / "identity-serverinfo.json"
        cross = run_realtime_verifiers(
            runner=self.runner,
            python_path=self.config.python_path,
            evidence_directory=self.partial,
            run_id=self.run_markers.run_id,
            mode=self.config.evidence_mode,
            expected_server_base=self.client_server_base,
            start_barrier_id=self.start_barrier_id,
            end_barrier_id=self.end_barrier_id,
            serverinfo_path=serverinfo_path,
            ca_file_sha256=_sha256_file(self.config.server_ca_path),
            negative_upstream_url=self.negative_upstream_url,
            timeout_seconds=self.config.command_timeout_seconds,
        )
        _write_exclusive_json(
            self.partial / "run-receipt.json",
            {
                "schema": "aneb-realtime-quick-run-receipt",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "collection_id": self.collection_id,
                "run_id": self.run_markers.run_id,
                "mode": self.config.evidence_mode,
                "terminal_status": self.run_markers.terminal_status,
                "contract_status": self.run_markers.contract_status,
                "cross_bound_report_sha256": _sha256_file(
                    self.partial / "cross-bound-report.json"
                ),
                "cross_bound": cross.get("cross_bound"),
            },
        )

    def cleanup_phone(self) -> None:
        if self.cleanup_phone_complete:
            return
        failures: list[str] = []

        def attempt(action: object) -> None:
            try:
                assert callable(action)
                action()
            except BaseException as error:
                failures.append(_error_text(error))

        attempt(self._stop_target)
        attempt(self._remove_reverse)
        if self.proxy is not None:
            attempt(self.proxy.stop)
        if self.logcat is not None:
            attempt(self.logcat.stop)
        if (
            self.stayon_mutation_attempted
            and self.original_stayon is not None
        ):
            def restore_stayon() -> None:
                self.adb.text(
                    [
                        "shell",
                        "settings",
                        "--user",
                        "0",
                        "put",
                        "global",
                        "stay_on_while_plugged_in",
                        self.original_stayon,
                    ],
                    code="restore_stayon",
                )
                actual = self.adb.text(
                    [
                        "shell",
                        "settings",
                        "--user",
                        "0",
                        "get",
                        "global",
                        "stay_on_while_plugged_in",
                    ],
                    code="verify_restored_stayon",
                )
                if actual != self.original_stayon:
                    raise CollectorError("stayon_restore_verification_failed")

            attempt(restore_stayon)
        if self.settings_started:
            attempt(
                lambda: self.adb.text(
                    ["shell", "am", "force-stop", "com.android.settings"],
                    code="stop_busy_sentinel",
                )
            )
        if self.live_preflight_complete:
            attempt(
                lambda: self.adb.text(
                    ["shell", "input", "keyevent", "KEYCODE_HOME"],
                    code="return_launcher",
                )
            )
            attempt(
                lambda: capture_stable_phone_evidence(
                    self.adb,
                    directory=self.partial,
                    prefix="phone-postflight",
                )
            )
        self.cleanup_phone_complete = not failures
        if failures:
            raise CollectorError("phone_cleanup_failed " + " | ".join(failures))

    def cleanup_remote(self) -> None:
        if self.cleanup_remote_complete:
            return
        if self.lock is None:
            self.cleanup_remote_complete = True
            return
        if not self.lock_acquired:
            self.lock.cancel_unconfirmed()
            self.cleanup_remote_complete = True
            return
        failures: list[str] = []
        if self.remote_before is not None:
            try:
                after = capture_remote_snapshot(
                    ssh=self.ssh,
                    lock=self.lock,
                    stage="final",
                )
                assert_remote_snapshot_stable(
                    self.remote_before,
                    after,
                    expected_binary_sha256=(
                        self.config.expected_server_binary_sha256.lower()
                    ),
                )
                _write_exclusive_json(
                    self.partial / "remote-final.json",
                    _remote_snapshot_document(after),
                )
            except BaseException as error:
                failures.append(_error_text(error))
        try:
            release = self.lock.release()
            self.lock_acquired = False
            _write_exclusive_bytes(
                self.partial / "lock-released.txt",
                (release + "\n").encode("utf-8"),
            )
        except BaseException as error:
            failures.append(_error_text(error))
            try:
                self.lock.emergency_close()
            except BaseException as emergency:
                failures.append(_error_text(emergency))
        self.cleanup_remote_complete = not failures
        if failures:
            raise CollectorError("remote_cleanup_failed " + " | ".join(failures))

    def publish(self) -> None:
        if (
            self.run_markers is None
            or not self.cleanup_phone_complete
            or not self.cleanup_remote_complete
        ):
            raise CollectorError("publish_not_ready")
        release_siblings = (
            self.config.evidence_root
            / f"{self.collection_id}.verification.partial",
            self.config.evidence_root
            / f"{self.collection_id}.verification.json",
            self.config.evidence_root
            / f"{self.collection_id}.ready.partial",
            self.config.evidence_root
            / f"{self.collection_id}.READY.json",
        )
        if any(path.exists() for path in release_siblings):
            raise CollectorError("ready_publication_path_collision")
        _write_exclusive_json(
            self.partial / "collector-status.json",
            {
                "schema": "aneb-realtime-quick-collector-status",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "collection_id": self.collection_id,
                "run_id": self.run_markers.run_id,
                "mode": self.config.evidence_mode,
                "cleanup_phone": "pass",
                "cleanup_remote": "pass",
            },
        )
        manifest = write_evidence_manifest(self.partial)
        _write_exclusive_bytes(
            self.partial / "COMPLETE",
            (
                f"ANEB_REALTIME_QUICK_COMPLETE collection_id={self.collection_id} "
                f"run_id={self.run_markers.run_id} "
                f"manifest_sha256={_sha256_file(manifest)}\n"
            ).encode("utf-8"),
        )
        verify_before_atomic_publish(
            self.partial,
            collection_id=self.collection_id,
        )
        atomic_publish(self.partial, self.complete)
        publication_succeeded = False
        try:
            publication = ready_publisher.publish_ready(self.complete)
            publication_succeeded = True
            ready_path = Path(str(publication.get("ready_path", "")))
            expected_ready = (
                self.config.evidence_root
                / f"{self.collection_id}.READY.json"
            )
            if (
                publication.get("status") != "pass"
                or publication.get("collection_id") != self.collection_id
                or publication.get("run_id") != self.run_markers.run_id
                or publication.get("mode") != self.config.evidence_mode
                or ready_path != expected_ready
            ):
                raise CollectorError("ready_publication_report_invalid")
            release = release_verifier.verify_release(ready_path)
            if (
                release.get("status") != "pass"
                or release.get("collection_id") != self.collection_id
                or release.get("run_id") != self.run_markers.run_id
                or release.get("mode") != self.config.evidence_mode
            ):
                raise CollectorError("ready_release_verification_invalid")
            self.ready_path = ready_path
        except BaseException as error:
            if publication_succeeded:
                for path in release_siblings:
                    try:
                        path.unlink()
                    except FileNotFoundError:
                        pass
            failed = (
                self.config.evidence_root
                / f"{self.collection_id}.verification-failed.partial"
            )
            if failed.exists():
                self.partial = self.complete
                raise CollectorError(
                    "verification_failed_directory_collision"
                ) from error
            try:
                (self.complete / "COMPLETE").unlink()
            except FileNotFoundError:
                pass
            try:
                os.replace(self.complete, failed)
            except OSError as demotion_error:
                raise CollectorError(
                    "ready_publication_failed_and_demotion_failed"
                ) from demotion_error
            self.partial = failed
            raise CollectorError(
                "ready_publication_failed " + _error_text(error)
            ) from error

    def record_failure(self, result: WorkflowResult) -> None:
        if not self.partial.exists():
            return
        for name in ("COMPLETE", "evidence-manifest.json"):
            try:
                (self.partial / name).unlink()
            except FileNotFoundError:
                pass
        status_path = self.partial / "collector-status.json"
        temporary = self.partial / ".collector-status.failure.tmp"
        payload = _canonical_json_bytes(
            {
                "schema": "aneb-realtime-quick-collector-status",
                "schema_version": "1.0.0",
                "status": "fail",
                "reason_code": "collector_or_cleanup_failed",
                "collection_id": self.collection_id,
                "run_id": (
                    self.run_markers.run_id
                    if self.run_markers is not None
                    else None
                ),
                "mode": self.config.evidence_mode,
                "primary_failure": result.primary_failure,
                "cleanup_failures": list(result.cleanup_failures),
                "publish_failure": result.publish_failure,
            }
        )
        _write_exclusive_bytes(temporary, payload)
        os.replace(temporary, status_path)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Collect one bounded AI Realtime Quick evidence bundle",
        allow_abbrev=False,
    )
    parser.add_argument("--adb-serial", required=True)
    parser.add_argument("--server-base", required=True)
    parser.add_argument("--remote", required=True)
    parser.add_argument("--ssh-key", type=Path, required=True)
    parser.add_argument("--known-hosts", type=Path, required=True)
    parser.add_argument("--device-policy", type=Path, required=True)
    parser.add_argument("--candidate-directory", type=Path, required=True)
    parser.add_argument("--gh-path", type=Path, required=True)
    parser.add_argument("--expected-server-binary-sha256", required=True)
    parser.add_argument(
        "--evidence-mode",
        choices=("positive", "negative"),
        default="positive",
    )
    parser.add_argument(
        "--transport",
        choices=("auto", "wifi", "cellular"),
        default="auto",
    )
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--adb-path", type=Path, required=True)
    parser.add_argument("--ssh-path", type=Path, required=True)
    parser.add_argument("--python-path", type=Path, required=True)
    parser.add_argument(
        "--server-ca",
        type=Path,
        default=(
            Path(__file__).resolve().parents[1]
            / "app"
            / "probe"
            / "src"
            / "main"
            / "res"
            / "raw"
            / "aneb_ip_ca.pem"
        ),
    )
    parser.add_argument("--source-commit", required=True)
    parser.add_argument(
        "--run-timeout-seconds",
        type=int,
        default=900,
    )
    parser.add_argument(
        "--lock-ttl-seconds",
        type=int,
        default=1800,
    )
    parser.add_argument(
        "--command-timeout-seconds",
        type=int,
        default=120,
    )
    parser.add_argument("--install-candidate", action="store_true")
    parser.add_argument("--preflight-only", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    config = CollectorConfig(
        adb_serial=args.adb_serial,
        server_base=args.server_base.rstrip("/"),
        remote=args.remote,
        ssh_key=args.ssh_key,
        known_hosts=args.known_hosts,
        device_policy=args.device_policy,
        candidate_directory=args.candidate_directory,
        gh_path=args.gh_path,
        expected_server_binary_sha256=(
            args.expected_server_binary_sha256.lower()
        ),
        evidence_mode=args.evidence_mode,
        transport=args.transport,
        evidence_root=args.evidence_root,
        adb_path=args.adb_path,
        ssh_path=args.ssh_path,
        python_path=args.python_path,
        server_ca_path=args.server_ca,
        source_commit=args.source_commit.lower(),
        run_timeout_seconds=args.run_timeout_seconds,
        lock_ttl_seconds=args.lock_ttl_seconds,
        command_timeout_seconds=args.command_timeout_seconds,
    )
    if args.preflight_only:
        validate_config(config)
        load_device_policy(
            config.device_policy,
            adb_serial=config.adb_serial,
        )
        _candidate_snapshot(config.candidate_directory)
        print(
            "ANEB_REALTIME_QUICK_PREFLIGHT_OK "
            "external_calls=0 expected_server=aneb-server/0.8.1 "
            "expected_app=0.5.13-codex"
        )
        return 0
    backend = LiveCollectorBackend(
        config,
        install_candidate=args.install_candidate,
    )
    result = run_workflow(backend)
    if not result.success:
        try:
            backend.record_failure(result)
        except BaseException as error:
            print(
                _canonical_json_bytes(
                    {
                        "status": "fail",
                        "reason_code": "failure_evidence_publish_failed",
                        "failure": _error_text(error),
                        "workflow": result.__dict__,
                    }
                ).decode("utf-8"),
                end="",
            )
            return 2
    print(
        _canonical_json_bytes(
            {
                "status": "pass" if result.success else "fail",
                "reason_code": (
                    "ok" if result.success else "collector_or_cleanup_failed"
                ),
                "collection_id": backend.collection_id,
                "run_id": (
                    backend.run_markers.run_id
                    if backend.run_markers is not None
                    else None
                ),
                "evidence_directory": str(
                    backend.complete if result.success else backend.partial
                ),
                "ready_path": (
                    str(backend.ready_path)
                    if backend.ready_path is not None
                    else None
                ),
                "workflow": result.__dict__,
            }
        ).decode("utf-8"),
        end="",
    )
    return 0 if result.success else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CollectorError as error:
        print(f"ERROR code={error}", file=os.sys.stderr)
        raise SystemExit(2)
