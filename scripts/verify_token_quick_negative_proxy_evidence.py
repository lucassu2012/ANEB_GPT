#!/usr/bin/env python3
"""Fail-closed semantic verifier for frozen Token Quick negative-proxy evidence.

The verifier is intentionally local-only: it reads the files already frozen in an
evidence bundle and performs no ADB, network, subprocess, or device operations.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, NoReturn
from urllib.parse import urlsplit


SCHEMA = "aneb-token-quick-negative-proxy-evidence-verification"
SCHEMA_VERSION = "1.0.0"
EXPECTED_DEVICE_PORT = 18765
MAX_SERVERINFO_BYTES = 1024 * 1024
MAX_HEADERS_BYTES = 64 * 1024
MAX_DOCUMENT_BYTES = 256 * 1024
MAX_MACHINE_OUTPUT_BYTES = 64 * 1024
MAX_REVERSE_OUTPUT_BYTES = 256 * 1024

RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
REVERSE_MAPPING_RE = re.compile(
    rb"([A-Za-z0-9._:-]{4,128}) tcp:([1-9][0-9]{0,4}) "
    rb"tcp:([1-9][0-9]{0,4})\n"
)

PROXY_FILE_NAMES = frozenset(
    {
        "upstream-serverinfo.raw",
        "filtered-serverinfo.json",
        "upstream-serverinfo.headers.json",
        "peer-certificate.sha256",
        "request-ledger.json",
        "proxy-receipt.json",
    }
)
ROOT_FILE_NAMES = frozenset(
    {
        "negative-proxy.stdout.jsonl",
        "negative-proxy.stderr.txt",
        "adb-reverse-preflight.txt",
        "adb-reverse-active.txt",
        "adb-reverse-before-remove.txt",
        "adb-reverse-final.txt",
    }
)
RAW_FILE_NAMES = frozenset(
    {f"negative-proxy/{name}" for name in PROXY_FILE_NAMES} | ROOT_FILE_NAMES
)


class NegativeProxyEvidenceFailure(ValueError):
    """Stable machine failure consumed by the enclosing bundle verifier."""

    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class _DuplicateJsonKey(ValueError):
    pass


class _NonstandardJsonConstant(ValueError):
    pass


def fail(reason_code: str) -> NoReturn:
    raise NegativeProxyEvidenceFailure(reason_code)


def _canonical_json(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError):
        fail("negative_proxy_json_invalid")


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateJsonKey(key)
        result[key] = value
    return result


def _reject_constant(value: str) -> NoReturn:
    raise _NonstandardJsonConstant(value)


def _strict_json(raw: bytes, *, reason: str) -> Any:
    try:
        return json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except (
        UnicodeError,
        json.JSONDecodeError,
        _DuplicateJsonKey,
        _NonstandardJsonConstant,
    ):
        fail(reason)


def _require_dict(value: Any, *, reason: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(reason)
    return value


def _require_exact_keys(
    value: dict[str, Any],
    expected: set[str] | frozenset[str],
    *,
    reason: str,
) -> None:
    if set(value) != set(expected):
        fail(reason)


def _is_reparse(path: Path) -> bool:
    try:
        if path.is_symlink():
            return True
        is_junction = getattr(path, "is_junction", None)
        return bool(is_junction and is_junction())
    except OSError:
        return True


def _identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_size,
        metadata.st_mtime_ns,
    )


def _read_regular(
    path: Path,
    *,
    maximum: int,
    reason: str,
    allow_empty: bool = False,
) -> bytes:
    flags = os.O_RDONLY
    for name in ("O_BINARY", "O_CLOEXEC", "O_NOFOLLOW"):
        flags |= int(getattr(os, name, 0))
    try:
        if _is_reparse(path):
            fail(reason)
        descriptor = os.open(path, flags)
    except NegativeProxyEvidenceFailure:
        raise
    except OSError:
        fail(reason)
    try:
        with os.fdopen(descriptor, "rb") as stream:
            before = os.fstat(stream.fileno())
            if (
                not stat.S_ISREG(before.st_mode)
                or before.st_size > maximum
                or (before.st_size == 0 and not allow_empty)
            ):
                fail(reason)
            raw = stream.read(maximum + 1)
            after = os.fstat(stream.fileno())
        path_after = os.stat(path, follow_symlinks=False)
    except NegativeProxyEvidenceFailure:
        raise
    except OSError:
        fail(reason)
    if (
        len(raw) > maximum
        or len(raw) != before.st_size
        or _identity(before) != _identity(after)
        or _identity(before) != _identity(path_after)
    ):
        fail(reason)
    return raw


def _resolve_bundle(bundle: Path) -> Path:
    candidate = Path(bundle)
    try:
        if _is_reparse(candidate):
            fail("negative_proxy_evidence_unavailable")
        root = candidate.resolve(strict=True)
    except NegativeProxyEvidenceFailure:
        raise
    except OSError:
        fail("negative_proxy_evidence_unavailable")
    if not root.is_dir() or _is_reparse(root):
        fail("negative_proxy_evidence_unavailable")
    return root


def _validate_expected_inputs(
    *,
    run_id: str,
    upstream_url: str,
    ca_file_sha256: str,
    device_port: int,
) -> None:
    if not isinstance(run_id, str) or RUN_ID_RE.fullmatch(run_id) is None:
        fail("negative_proxy_run_id_invalid")
    if (
        not isinstance(ca_file_sha256, str)
        or SHA256_RE.fullmatch(ca_file_sha256) is None
    ):
        fail("negative_proxy_ca_sha256_invalid")
    if (
        isinstance(device_port, bool)
        or not isinstance(device_port, int)
        or device_port != EXPECTED_DEVICE_PORT
    ):
        fail("negative_proxy_device_port_invalid")
    if not isinstance(upstream_url, str):
        fail("negative_proxy_upstream_url_invalid")
    try:
        parsed = urlsplit(upstream_url)
        port = parsed.port
    except (TypeError, ValueError):
        fail("negative_proxy_upstream_url_invalid")
    if (
        parsed.scheme != "https"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.hostname is None
        or port is None
        or parsed.path != "/api/v1/serverinfo"
        or parsed.query
        or parsed.fragment
    ):
        fail("negative_proxy_upstream_url_invalid")
    try:
        address = ipaddress.ip_address(parsed.hostname)
    except ValueError:
        fail("negative_proxy_upstream_url_invalid")
    if address.version != 4 or str(address) != parsed.hostname:
        fail("negative_proxy_upstream_url_invalid")


def _read_proxy_files(root: Path) -> dict[str, bytes]:
    directory = root / "negative-proxy"
    try:
        if _is_reparse(directory) or not directory.is_dir():
            fail("negative_proxy_evidence_inventory_invalid")
        names = {entry.name for entry in directory.iterdir()}
    except NegativeProxyEvidenceFailure:
        raise
    except OSError:
        fail("negative_proxy_evidence_inventory_invalid")
    if names != PROXY_FILE_NAMES:
        fail("negative_proxy_evidence_inventory_invalid")
    limits = {
        "upstream-serverinfo.raw": MAX_SERVERINFO_BYTES,
        "filtered-serverinfo.json": MAX_SERVERINFO_BYTES,
        "upstream-serverinfo.headers.json": MAX_HEADERS_BYTES,
        "peer-certificate.sha256": 65,
        "request-ledger.json": MAX_DOCUMENT_BYTES,
        "proxy-receipt.json": MAX_DOCUMENT_BYTES,
    }
    return {
        name: _read_regular(
            directory / name,
            maximum=limits[name],
            reason="negative_proxy_evidence_file_invalid",
        )
        for name in PROXY_FILE_NAMES
    }


def _verify_serverinfo(files: dict[str, bytes]) -> tuple[str, str]:
    upstream_raw = files["upstream-serverinfo.raw"]
    upstream = _require_dict(
        _strict_json(upstream_raw, reason="negative_proxy_upstream_serverinfo_invalid"),
        reason="negative_proxy_upstream_serverinfo_invalid",
    )
    capabilities = upstream.get("execution_capabilities")
    if not isinstance(capabilities, dict) or not capabilities:
        fail("negative_proxy_execution_capabilities_invalid")
    expected_filtered = dict(upstream)
    del expected_filtered["execution_capabilities"]
    expected_filtered_raw = _canonical_json(expected_filtered)
    filtered_raw = files["filtered-serverinfo.json"]
    filtered = _require_dict(
        _strict_json(filtered_raw, reason="negative_proxy_filtered_serverinfo_invalid"),
        reason="negative_proxy_filtered_serverinfo_invalid",
    )
    if "execution_capabilities" in filtered:
        fail("negative_proxy_execution_capabilities_not_removed")
    if filtered_raw != expected_filtered_raw:
        fail("negative_proxy_filtered_serverinfo_mismatch")
    return _sha256(upstream_raw), _sha256(filtered_raw)


def _verify_headers(raw: bytes) -> tuple[int, str]:
    headers = _strict_json(raw, reason="negative_proxy_headers_invalid")
    if not isinstance(headers, list) or raw != _canonical_json(headers):
        fail("negative_proxy_headers_invalid")
    total = 0
    for entry in headers:
        if (
            not isinstance(entry, list)
            or len(entry) != 2
            or not all(isinstance(value, str) for value in entry)
            or any("\r" in value or "\n" in value for value in entry)
        ):
            fail("negative_proxy_headers_invalid")
        total += len(entry[0].encode("utf-8")) + len(entry[1].encode("utf-8")) + 4
    return total, _sha256(raw)


def _verify_ledger(
    raw: bytes,
    *,
    run_id: str,
    upstream_url: str,
    upstream_body_bytes: int,
    header_bytes: int,
) -> str:
    ledger = _require_dict(
        _strict_json(raw, reason="negative_proxy_ledger_invalid"),
        reason="negative_proxy_ledger_invalid",
    )
    if raw != _canonical_json(ledger):
        fail("negative_proxy_ledger_invalid")
    _require_exact_keys(
        ledger,
        {"schema", "schema_version", "counts", "request", "upstream"},
        reason="negative_proxy_ledger_invalid",
    )
    counts = _require_dict(
        ledger.get("counts"), reason="negative_proxy_ledger_invalid"
    )
    request = _require_dict(
        ledger.get("request"), reason="negative_proxy_ledger_invalid"
    )
    upstream = _require_dict(
        ledger.get("upstream"), reason="negative_proxy_ledger_invalid"
    )
    _require_exact_keys(
        counts,
        {"accepted_requests", "forbidden_requests", "upstream_requests"},
        reason="negative_proxy_ledger_invalid",
    )
    _require_exact_keys(
        request,
        {"audit_role", "method", "path", "run_id"},
        reason="negative_proxy_ledger_invalid",
    )
    _require_exact_keys(
        upstream,
        {"body_bytes", "header_bytes", "status", "url"},
        reason="negative_proxy_ledger_invalid",
    )
    if any(type(counts.get(name)) is not int for name in counts) or any(
        type(upstream.get(name)) is not int
        for name in ("body_bytes", "header_bytes", "status")
    ):
        fail("negative_proxy_ledger_binding_mismatch")
    if (
        ledger.get("schema")
        != "aneb-token-serverinfo-negative-proxy-ledger"
        or ledger.get("schema_version") != "1.0.0"
        or counts
        != {
            "accepted_requests": 1,
            "forbidden_requests": 0,
            "upstream_requests": 1,
        }
        or request
        != {
            "audit_role": "capability",
            "method": "GET",
            "path": "/api/v1/serverinfo",
            "run_id": run_id,
        }
        or upstream
        != {
            "body_bytes": upstream_body_bytes,
            "header_bytes": header_bytes,
            "status": 200,
            "url": upstream_url,
        }
    ):
        fail("negative_proxy_ledger_binding_mismatch")
    return _sha256(raw)


def _verify_receipt(
    raw: bytes,
    *,
    run_id: str,
    upstream_url: str,
    ca_file_sha256: str,
    upstream_raw: bytes,
    filtered_raw: bytes,
    peer_sha256: str,
) -> tuple[bool, str]:
    receipt = _require_dict(
        _strict_json(raw, reason="negative_proxy_receipt_invalid"),
        reason="negative_proxy_receipt_invalid",
    )
    if raw != _canonical_json(receipt):
        fail("negative_proxy_receipt_invalid")
    _require_exact_keys(
        receipt,
        {
            "schema",
            "schema_version",
            "status",
            "reason_code",
            "run_id",
            "upstream_url",
            "upstream_body_bytes",
            "upstream_body_sha256",
            "filtered_body_bytes",
            "filtered_body_sha256",
            "peer_certificate_sha256",
            "ca_file_sha256",
            "evidence_scope",
            "client_delivery_proven",
        },
        reason="negative_proxy_receipt_invalid",
    )
    if (
        receipt.get("schema")
        != "aneb-token-serverinfo-negative-proxy-receipt"
        or receipt.get("schema_version") != "1.0.0"
        or receipt.get("status") != "pass"
        or receipt.get("reason_code") != "ok"
        or receipt.get("evidence_scope")
        != "upstream_fetch_and_filter_only"
    ):
        fail("negative_proxy_receipt_identity_invalid")
    if receipt.get("client_delivery_proven") is not False:
        fail("negative_proxy_client_delivery_claim_invalid")
    if receipt.get("run_id") != run_id:
        fail("negative_proxy_run_id_mismatch")
    if receipt.get("upstream_url") != upstream_url:
        fail("negative_proxy_upstream_url_mismatch")
    if (
        isinstance(receipt.get("upstream_body_bytes"), bool)
        or not isinstance(receipt.get("upstream_body_bytes"), int)
        or receipt.get("upstream_body_bytes") != len(upstream_raw)
    ):
        fail("negative_proxy_upstream_body_size_mismatch")
    if receipt.get("upstream_body_sha256") != _sha256(upstream_raw):
        fail("negative_proxy_upstream_body_digest_mismatch")
    if (
        isinstance(receipt.get("filtered_body_bytes"), bool)
        or not isinstance(receipt.get("filtered_body_bytes"), int)
        or receipt.get("filtered_body_bytes") != len(filtered_raw)
    ):
        fail("negative_proxy_filtered_body_size_mismatch")
    if receipt.get("filtered_body_sha256") != _sha256(filtered_raw):
        fail("negative_proxy_filtered_body_digest_mismatch")
    if receipt.get("peer_certificate_sha256") != peer_sha256:
        fail("negative_proxy_peer_certificate_mismatch")
    if receipt.get("ca_file_sha256") != ca_file_sha256:
        fail("negative_proxy_ca_sha256_mismatch")
    return False, _sha256(raw)


def _verify_stdout(raw: bytes, *, run_id: str) -> tuple[int, str]:
    if raw.startswith(b"\xef\xbb\xbf") or b"\x00" in raw:
        fail("negative_proxy_stdout_invalid")
    separator = b"\r\n" if b"\r" in raw else b"\n"
    if not raw.endswith(separator):
        fail("negative_proxy_stdout_invalid")
    lines = raw[: -len(separator)].split(separator)
    if any(b"\r" in line or b"\n" in line for line in lines):
        fail("negative_proxy_stdout_invalid")
    if len(lines) != 2 or any(not line for line in lines):
        fail("negative_proxy_stdout_invalid")
    ready = _require_dict(
        _strict_json(lines[0], reason="negative_proxy_stdout_invalid"),
        reason="negative_proxy_stdout_invalid",
    )
    passed = _require_dict(
        _strict_json(lines[1], reason="negative_proxy_stdout_invalid"),
        reason="negative_proxy_stdout_invalid",
    )
    if lines[0] != _canonical_json(ready) or lines[1] != _canonical_json(passed):
        fail("negative_proxy_stdout_invalid")
    _require_exact_keys(
        ready,
        {"listen_host", "listen_port", "status"},
        reason="negative_proxy_stdout_invalid",
    )
    _require_exact_keys(
        passed,
        {"listen_host", "listen_port", "reason_code", "run_id", "status"},
        reason="negative_proxy_stdout_invalid",
    )
    host_port = ready.get("listen_port")
    if (
        ready.get("listen_host") != "127.0.0.1"
        or ready.get("status") != "ready"
        or isinstance(host_port, bool)
        or not isinstance(host_port, int)
        or not 1 <= host_port <= 65535
        or passed
        != {
            "listen_host": "127.0.0.1",
            "listen_port": host_port,
            "reason_code": "ok",
            "run_id": run_id,
            "status": "pass",
        }
    ):
        fail("negative_proxy_stdout_binding_mismatch")
    return host_port, _sha256(raw)


def _parse_reverse_mapping(
    raw: bytes,
    *,
    device_port: int,
    reason: str,
) -> tuple[str, int]:
    match = REVERSE_MAPPING_RE.fullmatch(raw)
    if match is None:
        fail(reason)
    observed_device_port = int(match.group(2))
    host_port = int(match.group(3))
    if (
        observed_device_port != device_port
        or not 1 <= host_port <= 65535
    ):
        fail(reason)
    return match.group(1).decode("ascii"), host_port


def _verify_reverse(
    root: Path,
    *,
    device_port: int,
    stdout_host_port: int,
) -> tuple[str, str]:
    preflight = _read_regular(
        root / "adb-reverse-preflight.txt",
        maximum=MAX_REVERSE_OUTPUT_BYTES,
        reason="negative_proxy_reverse_preflight_invalid",
    )
    active = _read_regular(
        root / "adb-reverse-active.txt",
        maximum=MAX_REVERSE_OUTPUT_BYTES,
        reason="negative_proxy_reverse_active_invalid",
    )
    before_remove = _read_regular(
        root / "adb-reverse-before-remove.txt",
        maximum=MAX_REVERSE_OUTPUT_BYTES,
        reason="negative_proxy_reverse_before_remove_invalid",
    )
    final = _read_regular(
        root / "adb-reverse-final.txt",
        maximum=MAX_REVERSE_OUTPUT_BYTES,
        reason="negative_proxy_reverse_final_invalid",
    )
    if preflight != b"\n":
        fail("negative_proxy_reverse_preflight_not_empty")
    if final != b"\n":
        fail("negative_proxy_reverse_final_not_empty")
    active_mapping = _parse_reverse_mapping(
        active,
        device_port=device_port,
        reason="negative_proxy_reverse_active_invalid",
    )
    before_mapping = _parse_reverse_mapping(
        before_remove,
        device_port=device_port,
        reason="negative_proxy_reverse_before_remove_invalid",
    )
    if active_mapping != before_mapping or active_mapping[1] != stdout_host_port:
        fail("negative_proxy_reverse_binding_mismatch")
    reverse_digest = _sha256(preflight + active + before_remove + final)
    # The first `adb reverse --list` column is an ADB transport label, not a
    # portable device serial. Huawei's Windows USB transport reports `UsbFfs`.
    transport_label_digest = _sha256(active_mapping[0].encode("ascii"))
    return reverse_digest, transport_label_digest


def verify(
    bundle: Path,
    *,
    run_id: str,
    upstream_url: str,
    ca_file_sha256: str,
    device_port: int = EXPECTED_DEVICE_PORT,
) -> dict[str, object]:
    """Verify one frozen negative-proxy evidence set and return its summary."""

    _validate_expected_inputs(
        run_id=run_id,
        upstream_url=upstream_url,
        ca_file_sha256=ca_file_sha256,
        device_port=device_port,
    )
    root = _resolve_bundle(bundle)
    files = _read_proxy_files(root)
    upstream_sha256, filtered_sha256 = _verify_serverinfo(files)
    header_bytes, headers_sha256 = _verify_headers(
        files["upstream-serverinfo.headers.json"]
    )
    ledger_sha256 = _verify_ledger(
        files["request-ledger.json"],
        run_id=run_id,
        upstream_url=upstream_url,
        upstream_body_bytes=len(files["upstream-serverinfo.raw"]),
        header_bytes=header_bytes,
    )
    peer_raw = files["peer-certificate.sha256"]
    try:
        peer_text = peer_raw.decode("ascii")
    except UnicodeError:
        fail("negative_proxy_peer_certificate_invalid")
    if re.fullmatch(r"[0-9a-f]{64}\n", peer_text) is None:
        fail("negative_proxy_peer_certificate_invalid")
    peer_sha256 = peer_text[:-1]
    client_delivery_proven, receipt_sha256 = _verify_receipt(
        files["proxy-receipt.json"],
        run_id=run_id,
        upstream_url=upstream_url,
        ca_file_sha256=ca_file_sha256,
        upstream_raw=files["upstream-serverinfo.raw"],
        filtered_raw=files["filtered-serverinfo.json"],
        peer_sha256=peer_sha256,
    )
    stdout_raw = _read_regular(
        root / "negative-proxy.stdout.jsonl",
        maximum=MAX_MACHINE_OUTPUT_BYTES,
        reason="negative_proxy_stdout_invalid",
    )
    stderr_raw = _read_regular(
        root / "negative-proxy.stderr.txt",
        maximum=MAX_MACHINE_OUTPUT_BYTES,
        reason="negative_proxy_stderr_invalid",
        allow_empty=True,
    )
    if stderr_raw != b"":
        fail("negative_proxy_stderr_not_empty")
    host_port, stdout_sha256 = _verify_stdout(stdout_raw, run_id=run_id)
    reverse_sha256, adb_transport_label_sha256 = _verify_reverse(
        root,
        device_port=device_port,
        stdout_host_port=host_port,
    )
    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "run_id": run_id,
        "upstream_url": upstream_url,
        "upstream_body_sha256": upstream_sha256,
        "filtered_body_sha256": filtered_sha256,
        "upstream_headers_sha256": headers_sha256,
        "peer_certificate_sha256": peer_sha256,
        "ca_file_sha256": ca_file_sha256,
        "request_ledger_sha256": ledger_sha256,
        "proxy_receipt_sha256": receipt_sha256,
        "proxy_stdout_sha256": stdout_sha256,
        "reverse_evidence_sha256": reverse_sha256,
        "adb_transport_label_sha256": adb_transport_label_sha256,
        "device_port": device_port,
        "host_port": host_port,
        "client_delivery_proven": client_delivery_proven,
        "raw_files_verified": len(RAW_FILE_NAMES),
    }


__all__ = [
    "EXPECTED_DEVICE_PORT",
    "NegativeProxyEvidenceFailure",
    "RAW_FILE_NAMES",
    "SCHEMA",
    "SCHEMA_VERSION",
    "verify",
]
