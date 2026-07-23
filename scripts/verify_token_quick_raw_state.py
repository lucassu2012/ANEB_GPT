#!/usr/bin/env python3
"""Fail-closed, local-only verifier for Token Quick raw state evidence.

The module deliberately performs no ADB, SSH, network, or external-tool calls.
``verify_raw_state`` reads the frozen files in one evidence directory, parses the
raw command output, and cross-binds it to identities already authenticated by
the caller (normally ``verify_token_quick_evidence_bundle.py``).
"""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, Mapping, NoReturn


SCHEMA = "aneb-token-quick-raw-state-verification"
SCHEMA_VERSION = "1.1.0"
EXECUTION_MODES = frozenset({"positive", "negative_receipt_missing"})
LAUNCHER_COMPONENT = "com.huawei.android.launcher/.unihome.UniHomeLauncher"
CONFLICT_PACKAGES = frozenset(
    {
        "com.aneb.probe",
        "com.aneb.probe.codex",
        "com.emanuelef.remote_capture",
        "com.pcapdroid.mitm",
        "com.wireguard.android",
    }
)
ANEB_ACCESSIBILITY_PACKAGES = ("com.aneb.probe.codex", "com.aneb.probe")

MAX_TEXT_BYTES = 32 * 1024 * 1024
MAX_JSON_BYTES = 8 * 1024 * 1024
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
NONCE_RE = re.compile(r"^[0-9a-f]{32}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
IDENTITY32_RE = re.compile(r"^[0-9a-f]{32}$")
UINT_RE = re.compile(r"^[1-9][0-9]{0,18}$")
UTC_TIMESTAMP_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:"
    r"[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$"
)
COMPONENT_RE = re.compile(r"[A-Za-z0-9._]+/[A-Za-z0-9._$]+")
ANEB_PACKAGE_RE = re.compile(
    r"(?<![A-Za-z0-9_.])(?:com\.aneb\.probe\.codex|com\.aneb\.probe)"
    r"(?![A-Za-z0-9_])"
)
SERVICE_RECORD_RE = re.compile(r"ServiceRecord\{", re.IGNORECASE)
BOUND_SERVICES_RE = re.compile(r"\bbound services?\b", re.IGNORECASE)
FIELD_HEADER_RE = re.compile(r"^\s*[A-Za-z][A-Za-z0-9 _./()-]{0,80}\s*[:=]")
PACKAGE_HEADER_RE = re.compile(
    r"^\s*Package \[([A-Za-z0-9._]+)\](?:\s|$)", re.MULTILINE
)
VERSION_CODE_RE = re.compile(r"^\s*versionCode=([0-9]+)\b", re.MULTILINE)
VERSION_NAME_RE = re.compile(r"^\s*versionName=([^\r\n]+)$", re.MULTILINE)
LIFECYCLE_EVENT_RE = re.compile(
    r"TOKEN_V2_(START|CONTRACT|DB_WRITE|RESULT|END) run_id="
    r"([0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12})\b"
)
ANY_LIFECYCLE_EVENT_RE = re.compile(
    r"TOKEN_V2_(?:START|CONTRACT|DB_WRITE|RESULT|END)\b"
)
NEGATIVE_LIFECYCLE_EVENT_RE = re.compile(
    r"TOKEN_V2_(START|RADIO|DB_WRITE|CONTRACT|END) run_id="
    r"([0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12})\b"
)
NEGATIVE_ANY_LIFECYCLE_EVENT_RE = re.compile(
    r"TOKEN_V2_(?:START|RADIO|DB_WRITE|CONTRACT|END|RESULT|FAILED|"
    r"TASK_(?:START|END)|PROFILE)\b"
)

ACCESSIBILITY_FINAL_MARKERS = (
    "ANEB_D82_DEVICE_ACCESSIBILITY_FINAL_V1",
    "enabled_accessibility_services_command=settings get secure enabled_accessibility_services",
    "enabled_accessibility_services_output_begin",
    "enabled_accessibility_services_output_end",
    "dumpsys_accessibility_command=dumpsys accessibility",
    "dumpsys_accessibility_output_begin",
    "dumpsys_accessibility_output_end",
)

RAW_FILE_NAMES = frozenset(
    {
        "device-window-preflight.txt",
        "device-activity-preflight.txt",
        "device-processes-preflight.json",
        "device-services-preflight.json",
        "device-accessibility-preflight.txt",
        "device-connectivity-preflight.txt",
        "device-vpn-preflight.txt",
        "device-tun-preflight.txt",
        "device-stayon-preflight.txt",
        "device-package-preflight.txt",
        "device-window-final.txt",
        "device-activity-final.txt",
        "device-processes-final.json",
        "device-services-final.json",
        "device-accessibility-final.txt",
        "device-connectivity-final.txt",
        "device-vpn-final.txt",
        "device-tun-final.txt",
        "device-stayon-final.txt",
        "remote-pre-start.txt",
        "remote-end.txt",
        "lock-acquired.txt",
        "lock-released.txt",
        "lock-release-verified.txt",
        "logcat-capture-marker.json",
        "app-logcat.txt",
    }
)


class RawStateVerificationFailure(ValueError):
    """Stable fail-closed error consumed by the bundle verifier."""

    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class DuplicateJsonKey(ValueError):
    pass


def fail(reason_code: str) -> NoReturn:
    raise RawStateVerificationFailure(reason_code)


def _is_reparse(path: Path) -> bool:
    try:
        if path.is_symlink():
            return True
        is_junction = getattr(path, "is_junction", None)
        return bool(is_junction and is_junction())
    except OSError:
        return True


def _read_regular(path: Path, *, maximum: int, reason: str) -> bytes:
    flags = os.O_RDONLY
    for name in ("O_BINARY", "O_CLOEXEC", "O_NOFOLLOW"):
        flags |= int(getattr(os, name, 0))
    try:
        if _is_reparse(path):
            fail(reason)
        descriptor = os.open(path, flags)
    except (OSError, RawStateVerificationFailure):
        fail(reason)
    try:
        with os.fdopen(descriptor, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or not 0 <= before.st_size <= maximum:
                fail(reason)
            chunks: list[bytes] = []
            total = 0
            while block := stream.read(1024 * 1024):
                chunks.append(block)
                total += len(block)
                if total > maximum:
                    fail(reason)
            after = os.fstat(stream.fileno())
        path_after = os.stat(path, follow_symlinks=False)
    except (OSError, RawStateVerificationFailure):
        fail(reason)
    identity = lambda value: (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_size,
        value.st_mtime_ns,
    )
    if identity(before) != identity(after) or identity(before) != identity(path_after):
        fail(reason)
    payload = b"".join(chunks)
    if len(payload) != before.st_size:
        fail(reason)
    return payload


def _decode_text(raw: bytes, *, reason: str, allow_blank: bool = False) -> str:
    if not raw or raw.startswith(b"\xef\xbb\xbf"):
        fail(reason)
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        fail(reason)
    if not text.endswith("\n"):
        fail(reason)
    text = text.replace("\r\n", "\n")
    if "\r" in text or "\x00" in text:
        fail(reason)
    if not allow_blank and not text[:-1].strip():
        fail(reason)
    return text


def _read_text(
    root: Path,
    name: str,
    *,
    reason: str,
    allow_blank: bool = False,
) -> str:
    return _decode_text(
        _read_regular(root / name, maximum=MAX_TEXT_BYTES, reason=reason),
        reason=reason,
        allow_blank=allow_blank,
    )


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(key)
        result[key] = value
    return result


def _reject_non_finite(value: str) -> NoReturn:
    raise ValueError(value)


def _strict_json_bytes(raw: bytes, *, reason: str) -> Any:
    if not raw or raw.startswith(b"\xef\xbb\xbf"):
        fail(reason)
    try:
        return json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_non_finite,
        )
    except (UnicodeError, json.JSONDecodeError, DuplicateJsonKey, ValueError):
        fail(reason)


def _read_json_object(root: Path, name: str, *, reason: str) -> dict[str, Any]:
    raw = _read_regular(root / name, maximum=MAX_JSON_BYTES, reason=reason)
    value = _strict_json_bytes(raw, reason=reason)
    if not isinstance(value, dict):
        fail(reason)
    return value


def _extract_component(line: str, *, reason: str) -> str:
    matches = COMPONENT_RE.findall(line)
    if len(matches) != 1:
        fail(reason)
    return matches[0]


def verify_launcher_text(
    window_text: str,
    activity_text: str,
    *,
    expected_launcher: str = LAUNCHER_COMPONENT,
    reason: str = "raw_launcher_invalid",
) -> tuple[str, ...]:
    """Verify the exact focused/resumed launcher evidence from two dumpsys texts."""

    window_lines = [
        line for line in window_text.splitlines() if re.match(r"^\s*mCurrentFocus=", line)
    ]
    focused_lines = [
        line for line in activity_text.splitlines() if re.match(r"^\s*mFocusedApp=", line)
    ]
    resumed_lines = [
        line
        for line in activity_text.splitlines()
        if re.match(
            r"^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]",
            line,
        )
    ]
    if len(window_lines) != 1 or len(focused_lines) != 1 or not resumed_lines:
        fail(reason)
    components = tuple(
        _extract_component(line, reason=reason)
        for line in (window_lines + focused_lines + resumed_lines)
    )
    if any(component != expected_launcher for component in components):
        fail(reason)
    return components


def verify_process_service_objects(
    processes: Mapping[str, Any],
    services: Mapping[str, Any],
    *,
    reason: str = "raw_process_service_state_invalid",
) -> None:
    """Require the five coordinated packages to have no PID or ServiceRecord."""

    if set(processes) != CONFLICT_PACKAGES or set(services) != CONFLICT_PACKAGES:
        fail(reason)
    if any(not isinstance(value, str) or value != "" for value in processes.values()):
        fail(reason)
    if any(
        not isinstance(value, str) or SERVICE_RECORD_RE.search(value) is not None
        for value in services.values()
    ):
        fail(reason)


def _leading_spaces(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _bound_section_contains_aneb(lines: list[str]) -> bool:
    for index, line in enumerate(lines):
        match = BOUND_SERVICES_RE.search(line)
        if match is None:
            continue
        if ANEB_PACKAGE_RE.search(line) is not None:
            return True
        suffix = line[match.end() :].lstrip(" :=")
        if suffix.casefold() in {"{}", "[]", "null", "none", "(none)"}:
            continue
        header_indent = _leading_spaces(line)
        for following in lines[index + 1 :]:
            if not following.strip():
                continue
            if (
                _leading_spaces(following) <= header_indent
                and FIELD_HEADER_RE.match(following) is not None
            ):
                break
            if ANEB_PACKAGE_RE.search(following) is not None:
                return True
    return False


def verify_accessibility_text(
    enabled_output: str,
    dumpsys_output: str,
    *,
    reason: str = "raw_accessibility_state_invalid",
) -> None:
    """Allow shortcuts, but reject either ANEB package when enabled or bound."""

    if not enabled_output or "\n" in enabled_output or "\r" in enabled_output:
        fail(reason)
    if ANEB_PACKAGE_RE.search(enabled_output) is not None:
        fail(reason)
    lines = dumpsys_output.splitlines()
    if not lines or _bound_section_contains_aneb(lines):
        fail(reason)


def parse_accessibility_preflight(text: str) -> tuple[str, str]:
    reason = "raw_accessibility_preflight_invalid"
    lines = text[:-1].split("\n")
    if len(lines) < 2 or not lines[0].startswith("enabled_accessibility_services="):
        fail(reason)
    if sum(line.startswith("enabled_accessibility_services=") for line in lines) != 1:
        fail(reason)
    enabled = lines[0].split("=", 1)[1]
    dumpsys = "\n".join(lines[1:])
    verify_accessibility_text(enabled, dumpsys, reason=reason)
    return enabled, dumpsys


def parse_accessibility_final(text: str) -> tuple[str, str]:
    reason = "raw_accessibility_final_invalid"
    lines = text[:-1].split("\n")
    markers = ACCESSIBILITY_FINAL_MARKERS
    if len(lines) < 8 or lines[:3] != list(markers[:3]) or lines[-1] != markers[6]:
        fail(reason)
    if any(lines.count(marker) != 1 for marker in markers):
        fail(reason)
    try:
        enabled_end = lines.index(markers[3], 3)
    except ValueError:
        fail(reason)
    if lines[enabled_end + 1 : enabled_end + 3] != list(markers[4:6]):
        fail(reason)
    enabled_lines = lines[3:enabled_end]
    dumpsys_lines = lines[enabled_end + 3 : -1]
    if len(enabled_lines) != 1 or not dumpsys_lines:
        fail(reason)
    enabled = enabled_lines[0]
    dumpsys = "\n".join(dumpsys_lines)
    verify_accessibility_text(enabled, dumpsys, reason=reason)
    return enabled, dumpsys


def verify_no_active_vpn(
    connectivity_text: str,
    vpn_text: str,
    *,
    reason: str = "raw_active_vpn",
) -> None:
    """Port the live-state rule while parsing multi-line NetworkAgent blocks."""

    connectivity_lines = connectivity_text.splitlines()
    blocks: list[str] = []
    index = 0
    while index < len(connectivity_lines):
        line = connectivity_lines[index]
        if "NetworkAgentInfo{" not in line:
            index += 1
            continue
        block_lines = [line]
        balance = line.count("{") - line.count("}")
        index += 1
        while balance > 0 and index < len(connectivity_lines):
            following = connectivity_lines[index]
            # A second agent before the first one closed is malformed raw output,
            # not permission to merge unrelated NetworkRequest/LISTEN sections.
            if "NetworkAgentInfo{" in following:
                fail(reason)
            block_lines.append(following)
            balance += following.count("{") - following.count("}")
            index += 1
        if balance != 0:
            fail(reason)
        blocks.append("\n".join(block_lines))
    for block in blocks:
        is_vpn = re.search(r"(?:Transports?:\s*VPN|type:\s*VPN)", block, re.I)
        is_active = re.search(
            r"(?:state:\s*CONNECTED(?:/CONNECTED)?|CONNECTED/CONNECTED|\bVALIDATED\b)",
            block,
            re.I,
        )
        is_disconnected = re.search(
            r"(?:state:\s*DISCONNECTED|DISCONNECTED/DISCONNECTED)", block, re.I
        )
        if is_vpn and is_active and not is_disconnected:
            fail(reason)
    for line in vpn_text.splitlines():
        if re.search(r"\bLISTEN\b", line, re.I):
            continue
        if re.search(r"state\s*[:=]\s*CONNECTED\b", line, re.I) or re.search(
            r"mNetworkInfo.*\bCONNECTED\b", line, re.I
        ):
            fail(reason)


def _single_line(text: str, *, reason: str) -> str:
    lines = text[:-1].split("\n")
    if len(lines) != 1 or not lines[0]:
        fail(reason)
    return lines[0]


def verify_package_text(
    text: str,
    *,
    expected_package_name: str,
    expected_version_name: str,
    expected_version_code: int,
) -> None:
    reason = "raw_package_identity_mismatch"
    packages = PACKAGE_HEADER_RE.findall(text)
    version_codes = VERSION_CODE_RE.findall(text)
    version_names = [value.strip() for value in VERSION_NAME_RE.findall(text)]
    if (
        packages != [expected_package_name]
        or version_codes != [str(expected_version_code)]
        or version_names != [expected_version_name]
    ):
        fail(reason)


def _parse_key_values(
    text: str,
    *,
    expected_keys: set[str],
    reason: str,
) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in text[:-1].split("\n"):
        match = re.fullmatch(r"([a-z0-9_]+)=(.+)", line)
        if match is None or match.group(1) in values:
            fail(reason)
        values[match.group(1)] = match.group(2)
    if set(values) != expected_keys:
        fail(reason)
    return values


def parse_remote_pre_start(text: str) -> dict[str, Any]:
    reason = "raw_remote_pre_start_invalid"
    values = _parse_key_values(
        text,
        expected_keys={
            "boot_id",
            "systemd_invocation_id",
            "main_pid",
            "server_binary_sha256",
            "remote_realtime_anchor_usec",
            "journal_anchor_json_base64",
        },
        reason=reason,
    )
    if (
        IDENTITY32_RE.fullmatch(values["boot_id"]) is None
        or IDENTITY32_RE.fullmatch(values["systemd_invocation_id"]) is None
        or UINT_RE.fullmatch(values["main_pid"]) is None
        or SHA256_RE.fullmatch(values["server_binary_sha256"]) is None
        or UINT_RE.fullmatch(values["remote_realtime_anchor_usec"]) is None
    ):
        fail(reason)
    try:
        anchor_raw = base64.b64decode(
            values["journal_anchor_json_base64"], validate=True
        )
        anchor = _strict_json_bytes(anchor_raw, reason=reason)
    except (ValueError, RawStateVerificationFailure):
        fail(reason)
    if (
        not isinstance(anchor, dict)
        or not {"__CURSOR", "__MONOTONIC_TIMESTAMP"}.issubset(anchor)
        or not isinstance(anchor["__CURSOR"], str)
        or not 10 <= len(anchor["__CURSOR"]) <= 1024
        or not isinstance(anchor["__MONOTONIC_TIMESTAMP"], str)
        or UINT_RE.fullmatch(anchor["__MONOTONIC_TIMESTAMP"]) is None
    ):
        fail(reason)
    return {
        **values,
        "main_pid": int(values["main_pid"]),
        "remote_realtime_anchor_usec": int(values["remote_realtime_anchor_usec"]),
        "journal_anchor": anchor,
    }


def parse_remote_end(text: str) -> dict[str, Any]:
    reason = "raw_remote_end_invalid"
    values = _parse_key_values(
        text,
        expected_keys={
            "boot_id",
            "systemd_invocation_id",
            "main_pid",
            "server_binary_sha256",
        },
        reason=reason,
    )
    if (
        IDENTITY32_RE.fullmatch(values["boot_id"]) is None
        or IDENTITY32_RE.fullmatch(values["systemd_invocation_id"]) is None
        or UINT_RE.fullmatch(values["main_pid"]) is None
        or SHA256_RE.fullmatch(values["server_binary_sha256"]) is None
    ):
        fail(reason)
    return {**values, "main_pid": int(values["main_pid"])}


def verify_remote_identity(
    pre: Mapping[str, Any],
    end: Mapping[str, Any],
    *,
    expected_remote_identity: Mapping[str, Any],
) -> None:
    reason = "raw_remote_identity_mismatch"
    keys = (
        "boot_id",
        "systemd_invocation_id",
        "main_pid",
        "server_binary_sha256",
    )
    if not isinstance(expected_remote_identity, Mapping) or any(
        key not in expected_remote_identity for key in keys
    ):
        fail(reason)
    if (
        not isinstance(expected_remote_identity["boot_id"], str)
        or IDENTITY32_RE.fullmatch(expected_remote_identity["boot_id"]) is None
        or not isinstance(expected_remote_identity["systemd_invocation_id"], str)
        or IDENTITY32_RE.fullmatch(
            expected_remote_identity["systemd_invocation_id"]
        )
        is None
        or not isinstance(expected_remote_identity["main_pid"], int)
        or isinstance(expected_remote_identity["main_pid"], bool)
        or expected_remote_identity["main_pid"] <= 0
        or not isinstance(expected_remote_identity["server_binary_sha256"], str)
        or SHA256_RE.fullmatch(
            expected_remote_identity["server_binary_sha256"]
        )
        is None
    ):
        fail(reason)
    for key in keys:
        if pre.get(key) != end.get(key) or pre.get(key) != expected_remote_identity.get(key):
            fail(reason)


def verify_lock_lifecycle(
    acquired_text: str,
    released_text: str,
    verified_text: str,
    *,
    expected_nonce: str,
    expected_remote_pid: int | None = None,
    expected_marker: str | None = None,
) -> dict[str, Any]:
    reason = "raw_lock_lifecycle_mismatch"
    if (
        not isinstance(expected_nonce, str)
        or NONCE_RE.fullmatch(expected_nonce) is None
        or (
            expected_remote_pid is not None
            and (
                not isinstance(expected_remote_pid, int)
                or isinstance(expected_remote_pid, bool)
                or expected_remote_pid <= 0
            )
        )
    ):
        fail(reason)
    acquired = re.fullmatch(
        r"LOCK_ACQUIRED nonce=([0-9a-f]{32}) pid=([1-9][0-9]{0,18}) "
        r"marker=(/run/aneb-token-audit-([0-9a-f]{32})\.lock)\n",
        acquired_text,
    )
    released = re.fullmatch(
        r"LOCK_RELEASED nonce=([0-9a-f]{32})\n"
        r"process_exit=0\n"
        r"stderr=\n",
        released_text,
    )
    verified = re.fullmatch(
        r"LOCK_RELEASE_VERIFIED nonce=([0-9a-f]{32})\n", verified_text
    )
    if acquired is None or released is None or verified is None:
        fail(reason)
    nonce = acquired.group(1)
    remote_pid = int(acquired.group(2))
    marker = acquired.group(3)
    if (
        nonce != expected_nonce
        or acquired.group(4) != nonce
        or released.group(1) != nonce
        or verified.group(1) != nonce
        or (expected_remote_pid is not None and remote_pid != expected_remote_pid)
        or (expected_marker is not None and marker != expected_marker)
    ):
        fail(reason)
    return {"nonce": nonce, "remote_pid": remote_pid, "marker": marker}


def verify_logcat_lifecycle(
    marker: Mapping[str, Any],
    logcat_text: str,
    *,
    execution_mode: str,
    expected_run_id: str,
) -> dict[str, Any]:
    reason = "raw_logcat_lifecycle_invalid"
    if (
        not isinstance(execution_mode, str)
        or execution_mode not in EXECUTION_MODES
        or not isinstance(expected_run_id, str)
        or RUN_ID_RE.fullmatch(expected_run_id) is None
    ):
        fail(reason)
    if set(marker) != {
        "schema",
        "schema_version",
        "captured_at_utc",
        "marker_nonce",
        "marker",
    }:
        fail(reason)
    nonce = marker.get("marker_nonce")
    marker_text = marker.get("marker")
    if (
        marker.get("schema") != "aneb-d82-logcat-capture-marker"
        or marker.get("schema_version") != "1.0.0"
        or not isinstance(marker.get("captured_at_utc"), str)
        or UTC_TIMESTAMP_RE.fullmatch(marker["captured_at_utc"]) is None
        or not isinstance(nonce, str)
        or NONCE_RE.fullmatch(nonce) is None
        or marker_text != f"D82_CAPTURE_MARKER nonce={nonce}"
    ):
        fail(reason)

    lines = logcat_text.splitlines()
    any_marker_lines = [
        index
        for index, line in enumerate(lines)
        if "D82_CAPTURE_MARKER" in line
    ]
    expected_marker_lines = [
        index
        for index, line in enumerate(lines)
        if line.rstrip().endswith(marker_text)
    ]
    if (
        len(any_marker_lines) != 1
        or sum(line.count("D82_CAPTURE_MARKER") for line in lines) != 1
        or expected_marker_lines != any_marker_lines
    ):
        fail(reason)
    marker_index = expected_marker_lines[0]
    event_re = (
        NEGATIVE_LIFECYCLE_EVENT_RE
        if execution_mode == "negative_receipt_missing"
        else LIFECYCLE_EVENT_RE
    )
    any_event_re = (
        NEGATIVE_ANY_LIFECYCLE_EVENT_RE
        if execution_mode == "negative_receipt_missing"
        else ANY_LIFECYCLE_EVENT_RE
    )
    if any(any_event_re.search(line) for line in lines[:marker_index]):
        fail("raw_logcat_replay_before_marker")

    events: list[tuple[str, str, str]] = []
    for line in lines[marker_index + 1 :]:
        tokens = list(any_event_re.finditer(line))
        if not tokens:
            continue
        matches = list(event_re.finditer(line))
        if (
            len(tokens) != 1
            or len(matches) != 1
            or tokens[0].start() != matches[0].start()
        ):
            fail(reason)
        match = matches[0]
        events.append((match.group(1), match.group(2), line))
    expected_stages = (
        ["START", "RADIO", "DB_WRITE", "CONTRACT", "END"]
        if execution_mode == "negative_receipt_missing"
        else ["START", "CONTRACT", "DB_WRITE", "RESULT", "END"]
    )
    if [stage for stage, _, _ in events] != expected_stages:
        fail(reason)
    if any(run_id != expected_run_id for _, run_id, _ in events):
        fail(reason)
    escaped = re.escape(expected_run_id)
    positive_start_pattern = re.compile(
        rf"TOKEN_V2_START run_id={escaped}\b.*\bvariant=quick\b.*\bserver=\S+"
    )
    if execution_mode == "negative_receipt_missing":
        patterns = (
            re.compile(
                rf"TOKEN_V2_START run_id={escaped}\b "
                rf"variant=quick server=\S+\s*$"
            ),
            re.compile(
                rf"TOKEN_V2_RADIO run_id={escaped}\b "
                rf"status=\S+ samples=[0-9]+\s*$"
            ),
            re.compile(
                rf"TOKEN_V2_DB_WRITE run_id={escaped}\b ok=true\s*$"
            ),
            re.compile(
                rf"TOKEN_V2_CONTRACT run_id={escaped}\b "
                rf"status=rejected reason=receipt_missing\s*$"
            ),
            re.compile(
                rf"TOKEN_V2_END run_id={escaped}\b "
                rf"status=contract_rejected\s*$"
            ),
        )
    else:
        patterns = (
            positive_start_pattern,
            re.compile(
                rf"TOKEN_V2_CONTRACT run_id={escaped}\b status=validated_receipt\b"
            ),
            re.compile(rf"TOKEN_V2_DB_WRITE run_id={escaped}\b ok=true\b"),
            re.compile(
                rf"TOKEN_V2_RESULT run_id={escaped}\b score=(?!null\b)\S+ "
                rf"grade=(?!null\b)\S+ verdict=(?!INVALID\b)\S+ confidence=\S+"
            ),
            re.compile(rf"TOKEN_V2_END run_id={escaped}\b status=completed\b"),
        )
    if any(pattern.search(events[index][2]) is None for index, pattern in enumerate(patterns)):
        fail(reason)
    return {"marker_nonce": nonce, "event_count": len(events)}


def verify_raw_state(
    bundle: Path,
    *,
    execution_mode: str,
    expected_run_id: str,
    expected_lock_nonce: str,
    expected_package_name: str,
    expected_version_name: str,
    expected_version_code: int,
    expected_remote_identity: Mapping[str, Any],
    expected_lock_remote_pid: int | None = None,
    expected_lock_marker: str | None = None,
    expected_launcher: str = LAUNCHER_COMPONENT,
) -> dict[str, Any]:
    """Verify every minimum raw-state file and return a compact pass report.

    All expected values must originate from an independently authenticated
    manifest/receipt.  A mismatch raises ``RawStateVerificationFailure``.
    """

    if not isinstance(execution_mode, str) or execution_mode not in EXECUTION_MODES:
        fail("raw_execution_mode_invalid")

    bundle_path = Path(bundle)
    try:
        if _is_reparse(bundle_path):
            fail("raw_bundle_unavailable")
        root = bundle_path.resolve(strict=True)
    except OSError:
        fail("raw_bundle_unavailable")
    if not root.is_dir() or _is_reparse(root):
        fail("raw_bundle_unavailable")
    if (
        not isinstance(expected_package_name, str)
        or not expected_package_name
        or not isinstance(expected_version_name, str)
        or not expected_version_name
        or not isinstance(expected_version_code, int)
        or isinstance(expected_version_code, bool)
        or expected_version_code <= 0
    ):
        fail("raw_expected_client_identity_invalid")

    launcher_counts: dict[str, int] = {}
    for stage in ("preflight", "final"):
        reason = f"raw_launcher_{stage}_invalid"
        window = _read_text(root, f"device-window-{stage}.txt", reason=reason)
        activity = _read_text(root, f"device-activity-{stage}.txt", reason=reason)
        components = verify_launcher_text(
            window,
            activity,
            expected_launcher=expected_launcher,
            reason=reason,
        )
        launcher_counts[stage] = len(components)

        processes = _read_json_object(
            root,
            f"device-processes-{stage}.json",
            reason=f"raw_processes_{stage}_invalid",
        )
        services = _read_json_object(
            root,
            f"device-services-{stage}.json",
            reason=f"raw_services_{stage}_invalid",
        )
        verify_process_service_objects(
            processes,
            services,
            reason=f"raw_process_service_{stage}_not_clean",
        )

    pre_accessibility = _read_text(
        root,
        "device-accessibility-preflight.txt",
        reason="raw_accessibility_preflight_invalid",
    )
    parse_accessibility_preflight(pre_accessibility)
    final_accessibility = _read_text(
        root,
        "device-accessibility-final.txt",
        reason="raw_accessibility_final_invalid",
    )
    parse_accessibility_final(final_accessibility)

    for stage in ("preflight", "final"):
        connectivity = _read_text(
            root,
            f"device-connectivity-{stage}.txt",
            reason=f"raw_connectivity_{stage}_invalid",
        )
        vpn = _read_text(
            root,
            f"device-vpn-{stage}.txt",
            reason=f"raw_vpn_{stage}_invalid",
            allow_blank=True,
        )
        verify_no_active_vpn(
            connectivity,
            vpn,
            reason=f"raw_active_vpn_{stage}",
        )
        tun = _single_line(
            _read_text(
                root,
                f"device-tun-{stage}.txt",
                reason=f"raw_tun_{stage}_invalid",
            ),
            reason=f"raw_tun_{stage}_invalid",
        )
        if tun != "absent":
            fail(f"raw_tun_{stage}_not_absent")

    pre_stayon = _single_line(
        _read_text(
            root,
            "device-stayon-preflight.txt",
            reason="raw_stayon_preflight_invalid",
        ),
        reason="raw_stayon_preflight_invalid",
    )
    final_stayon = _single_line(
        _read_text(
            root,
            "device-stayon-final.txt",
            reason="raw_stayon_final_invalid",
        ),
        reason="raw_stayon_final_invalid",
    )
    if re.fullmatch(r"(?:null|[0-9]+)", pre_stayon) is None:
        fail("raw_stayon_preflight_invalid")
    if final_stayon != pre_stayon:
        fail("raw_stayon_mismatch")

    package_text = _read_text(
        root,
        "device-package-preflight.txt",
        reason="raw_package_identity_mismatch",
    )
    verify_package_text(
        package_text,
        expected_package_name=expected_package_name,
        expected_version_name=expected_version_name,
        expected_version_code=expected_version_code,
    )

    remote_pre = parse_remote_pre_start(
        _read_text(
            root,
            "remote-pre-start.txt",
            reason="raw_remote_pre_start_invalid",
        )
    )
    remote_end = parse_remote_end(
        _read_text(root, "remote-end.txt", reason="raw_remote_end_invalid")
    )
    verify_remote_identity(
        remote_pre,
        remote_end,
        expected_remote_identity=expected_remote_identity,
    )

    lock = verify_lock_lifecycle(
        _read_text(root, "lock-acquired.txt", reason="raw_lock_lifecycle_mismatch"),
        _read_text(root, "lock-released.txt", reason="raw_lock_lifecycle_mismatch"),
        _read_text(
            root,
            "lock-release-verified.txt",
            reason="raw_lock_lifecycle_mismatch",
        ),
        expected_nonce=expected_lock_nonce,
        expected_remote_pid=expected_lock_remote_pid,
        expected_marker=expected_lock_marker,
    )

    marker = _read_json_object(
        root,
        "logcat-capture-marker.json",
        reason="raw_logcat_lifecycle_invalid",
    )
    logcat = _read_text(
        root,
        "app-logcat.txt",
        reason="raw_logcat_lifecycle_invalid",
    )
    logcat_report = verify_logcat_lifecycle(
        marker,
        logcat,
        execution_mode=execution_mode,
        expected_run_id=expected_run_id,
    )

    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "execution_mode": execution_mode,
        "run_id": expected_run_id,
        "launcher_snapshots_verified": 2,
        "launcher_components_verified": sum(launcher_counts.values()),
        "conflict_packages_verified_per_snapshot": len(CONFLICT_PACKAGES),
        "accessibility_snapshots_verified": 2,
        "vpn_tun_snapshots_verified": 2,
        "stayon_restored": True,
        "package_identity_verified": True,
        "remote_identity_stable": True,
        "lock_lifecycle_verified": True,
        "lock_remote_pid": lock["remote_pid"],
        "logcat_lifecycle_verified": True,
        "logcat_event_count": logcat_report["event_count"],
        "raw_files_verified": len(RAW_FILE_NAMES),
    }


__all__ = [
    "ACCESSIBILITY_FINAL_MARKERS",
    "CONFLICT_PACKAGES",
    "EXECUTION_MODES",
    "LAUNCHER_COMPONENT",
    "RAW_FILE_NAMES",
    "RawStateVerificationFailure",
    "parse_accessibility_final",
    "parse_accessibility_preflight",
    "parse_remote_end",
    "parse_remote_pre_start",
    "verify_accessibility_text",
    "verify_launcher_text",
    "verify_lock_lifecycle",
    "verify_logcat_lifecycle",
    "verify_no_active_vpn",
    "verify_package_text",
    "verify_process_service_objects",
    "verify_raw_state",
    "verify_remote_identity",
]
