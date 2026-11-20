#!/usr/bin/env python3
"""Read-only health, contract, and TLS certificate monitor for miw2.

Zero third-party dependencies (Python 3.12+ standard library only).
Validates the six production invariants documented in docs/SERVER_LINEAGE_BOUNDARY.md:
1. tls_cert: OS/CA-verified TLS + IP/DNS SAN + >= min_cert_hours remaining.
2. serverinfo: GET /api/v1/serverinfo -> 200, expected version, h3_enabled, receipt.
3. profiles: GET /api/v1/profiles -> 200, expected version, 4 frozen profiles.
4. echo: POST /api/v1/echo (<=100 B) -> 200, valid timing & observed fields.
5. results_guard: POST /api/v1/results -> strictly HTTP 503 (never prototype 200/400).
6. realtime_sim_ws: RFC 6455 upgrade without Origin -> 101 + Sec-WebSocket-Accept + close.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import ipaddress
import json
import os
import socket
import ssl
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

DEFAULT_BASE_URL = "https://120.77.174.207:8443"
DEFAULT_MIN_CERT_HOURS = 48.0
DEFAULT_EXPECTED_VERSION = "aneb-server/0.8.4"
DEFAULT_EXPECTED_PROFILES = 4
DEFAULT_EXPECTED_VALIDATED_PROFILES = 6
DEFAULT_TIMEOUT_S = 10.0
REQUIRED_LEGACY_PROFILE_IDS = frozenset(
    {"basic_network", "s1_chat", "s2_coding_agent", "s3_multimodal"}
)
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
WS_CLOSE_FRAME_MASKED = b"\x88\x80\x00\x00\x00\x00"
MAX_WS_HEADER_BYTES = 16384


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def build_ssl_context(ca_file: str | Path | None = None) -> ssl.SSLContext:
    cafile_str = str(ca_file) if ca_file is not None else None
    ctx = ssl.create_default_context(cafile=cafile_str)
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    return ctx


def parse_base_url(base_url: str) -> tuple[str, str, int, str]:
    parsed = urlparse(base_url)
    scheme = parsed.scheme.lower()
    if scheme not in ("https", "http"):
        raise ValueError(f"Unsupported URL scheme in base_url: {base_url!r}")
    host = parsed.hostname
    if not host:
        raise ValueError(f"Missing hostname in base_url: {base_url!r}")
    port = parsed.port
    if port is None:
        port = 443 if scheme == "https" else 80
    base_path = parsed.path.rstrip("/")
    return scheme, host, port, base_path


def _san_matches_host(san_entries: tuple[tuple[str, str], ...], host: str) -> bool:
    host_ip: ipaddress.IPv4Address | ipaddress.IPv6Address | None = None
    try:
        host_ip = ipaddress.ip_address(host)
    except ValueError:
        host_ip = None

    host_lower = host.lower()
    for san_type, san_value in san_entries:
        if host_ip is not None and san_type == "IP Address":
            try:
                if ipaddress.ip_address(san_value) == host_ip:
                    return True
            except ValueError:
                continue
        elif host_ip is None and san_type == "DNS":
            if san_value.lower() == host_lower:
                return True
    return False


def inspect_cert(
    cert: dict[str, Any] | None,
    *,
    host: str,
    min_cert_hours: float,
    now: datetime | None = None,
) -> dict[str, Any]:
    if not cert:
        raise ValueError("Peer did not present an X.509 certificate")

    not_before_raw = cert.get("notBefore")
    not_after_raw = cert.get("notAfter")
    if not isinstance(not_before_raw, str) or not isinstance(not_after_raw, str):
        raise ValueError("Certificate missing notBefore or notAfter")

    not_before_ts = ssl.cert_time_to_seconds(not_before_raw)
    not_after_ts = ssl.cert_time_to_seconds(not_after_raw)
    now_dt = now if now is not None else utc_now()
    if now_dt.tzinfo is None:
        now_dt = now_dt.replace(tzinfo=timezone.utc)
    now_ts = now_dt.timestamp()

    if now_ts < not_before_ts:
        raise ValueError(
            f"Certificate not yet valid (notBefore={not_before_raw}, now={now_dt.isoformat()})"
        )
    if now_ts >= not_after_ts:
        raise ValueError(
            f"Certificate expired (notAfter={not_after_raw}, now={now_dt.isoformat()})"
        )

    remaining_hours = (not_after_ts - now_ts) / 3600.0
    san_entries = tuple(cert.get("subjectAltName") or ())
    if not _san_matches_host(san_entries, host):
        raise ValueError(f"Certificate SAN {san_entries!r} does not cover host {host!r}")

    if remaining_hours < min_cert_hours:
        raise ValueError(
            f"Certificate expires in {remaining_hours:.2f}h (< {min_cert_hours:.2f}h threshold)"
        )

    issuer_parts: list[str] = []
    for rdn in cert.get("issuer") or ():
        for k, v in rdn:
            issuer_parts.append(f"{k}={v}")

    return {
        "not_before": datetime.fromtimestamp(not_before_ts, tz=timezone.utc).isoformat(),
        "not_after": datetime.fromtimestamp(not_after_ts, tz=timezone.utc).isoformat(),
        "remaining_hours": round(remaining_hours, 2),
        "min_cert_hours": min_cert_hours,
        "san": [[t, v] for t, v in san_entries],
        "issuer": ", ".join(issuer_parts),
        "subject_present": bool(cert.get("subject")),
    }


def default_tls_connector(
    host: str,
    port: int,
    timeout: float,
    ssl_context: ssl.SSLContext,
) -> ssl.SSLSocket:
    raw_sock = socket.create_connection((host, port), timeout=timeout)
    try:
        tls_sock = ssl_context.wrap_socket(raw_sock, server_hostname=host)
        tls_sock.settimeout(timeout)
        return tls_sock
    except Exception:
        raw_sock.close()
        raise


def default_http_opener(
    scheme: str,
    host: str,
    port: int,
    timeout: float,
    ssl_context: ssl.SSLContext,
) -> http.client.HTTPConnection:
    if scheme == "https":
        return http.client.HTTPSConnection(
            host,
            port=port,
            timeout=timeout,
            context=ssl_context,
        )
    return http.client.HTTPConnection(host, port=port, timeout=timeout)


def _http_request(
    *,
    scheme: str,
    host: str,
    port: int,
    method: str,
    path: str,
    body: bytes | None,
    headers: dict[str, str],
    timeout: float,
    ssl_context: ssl.SSLContext,
    http_opener: Callable[..., http.client.HTTPConnection],
) -> tuple[int, dict[str, str], bytes]:
    conn = http_opener(scheme, host, port, timeout, ssl_context)
    try:
        conn.request(method, path, body=body, headers=headers)
        resp = conn.getresponse()
        raw_body = resp.read()
        resp_headers = {k.lower(): v for k, v in resp.getheaders()}
        return resp.status, resp_headers, raw_body
    finally:
        conn.close()


def _assert_server_header(
    headers: dict[str, str], expected_version: str, endpoint: str
) -> str:
    server_hdr = headers.get("x-aneb-server", "")
    if server_hdr != expected_version:
        raise ValueError(
            f"{endpoint}: X-Aneb-Server={server_hdr!r}, expected {expected_version!r}"
        )
    return server_hdr


def check_tls_cert(
    host: str,
    port: int,
    *,
    timeout: float,
    ssl_context: ssl.SSLContext,
    min_cert_hours: float,
    now_fn: Callable[[], datetime] | None = None,
    tls_connector: Callable[..., Any] | None = None,
) -> dict[str, Any]:
    now_cb = now_fn if now_fn is not None else utc_now
    connector = tls_connector if tls_connector is not None else default_tls_connector
    with connector(host, port, timeout, ssl_context) as sock:
        cert = sock.getpeercert()
        tls_version = sock.version() if hasattr(sock, "version") else None
        cipher = sock.cipher() if hasattr(sock, "cipher") else None
    details = inspect_cert(
        cert,
        host=host,
        min_cert_hours=min_cert_hours,
        now=now_cb(),
    )
    details["tls_version"] = tls_version
    if cipher:
        details["cipher"] = cipher[0]
    return details


def check_serverinfo(
    *,
    scheme: str,
    host: str,
    port: int,
    base_path: str,
    timeout: float,
    ssl_context: ssl.SSLContext,
    http_opener: Callable[..., http.client.HTTPConnection],
    expected_version: str,
    expected_validated_profiles: int,
    require_h3: bool,
) -> dict[str, Any]:
    path = f"{base_path}/api/v1/serverinfo"
    status, headers, raw_body = _http_request(
        scheme=scheme,
        host=host,
        port=port,
        method="GET",
        path=path,
        body=None,
        headers={"Accept": "application/json"},
        timeout=timeout,
        ssl_context=ssl_context,
        http_opener=http_opener,
    )
    if status != 200:
        raise ValueError(f"/api/v1/serverinfo returned HTTP {status}, expected 200")
    server_hdr = _assert_server_header(headers, expected_version, "/api/v1/serverinfo")

    proto_hdr = headers.get("x-aneb-proto", "")
    if "via=tcp" not in proto_hdr:
        raise ValueError(
            f"/api/v1/serverinfo: X-Aneb-Proto={proto_hdr!r} missing 'via=tcp'"
        )

    alt_svc = headers.get("alt-svc", "")
    if require_h3 and not alt_svc:
        raise ValueError("/api/v1/serverinfo: missing Alt-Svc header while require_h3=True")

    payload = json.loads(raw_body.decode("utf-8"))
    if payload.get("version") != expected_version:
        raise ValueError(
            f"/api/v1/serverinfo JSON version={payload.get('version')!r}, expected {expected_version!r}"
        )
    if require_h3 and payload.get("h3_enabled") is not True:
        raise ValueError(
            f"/api/v1/serverinfo h3_enabled={payload.get('h3_enabled')!r}, expected True"
        )

    exec_caps = payload.get("execution_capabilities")
    if not isinstance(exec_caps, dict):
        raise ValueError("/api/v1/serverinfo missing execution_capabilities object")
    if exec_caps.get("contract_id") != "aneb-server-capability-receipt":
        raise ValueError(
            f"/api/v1/serverinfo contract_id={exec_caps.get('contract_id')!r}, "
            "expected 'aneb-server-capability-receipt'"
        )
    validated_profiles = exec_caps.get("validated_profiles")
    if not isinstance(validated_profiles, list):
        raise ValueError("/api/v1/serverinfo execution_capabilities.validated_profiles not a list")
    if len(validated_profiles) != expected_validated_profiles:
        raise ValueError(
            f"/api/v1/serverinfo validated_profiles count={len(validated_profiles)}, "
            f"expected {expected_validated_profiles}"
        )

    return {
        "status": status,
        "server_header": server_hdr,
        "proto_header": proto_hdr,
        "alt_svc": alt_svc,
        "h3_enabled": payload.get("h3_enabled"),
        "validated_profiles_count": len(validated_profiles),
        "congestion_control": payload.get("congestion_control"),
        "tcp_slow_start_after_idle": payload.get("tcp_slow_start_after_idle"),
    }


def check_profiles(
    *,
    scheme: str,
    host: str,
    port: int,
    base_path: str,
    timeout: float,
    ssl_context: ssl.SSLContext,
    http_opener: Callable[..., http.client.HTTPConnection],
    expected_version: str,
    expected_profiles: int,
) -> dict[str, Any]:
    path = f"{base_path}/api/v1/profiles"
    status, headers, raw_body = _http_request(
        scheme=scheme,
        host=host,
        port=port,
        method="GET",
        path=path,
        body=None,
        headers={"Accept": "application/json"},
        timeout=timeout,
        ssl_context=ssl_context,
        http_opener=http_opener,
    )
    if status != 200:
        raise ValueError(f"/api/v1/profiles returned HTTP {status}, expected 200")
    _assert_server_header(headers, expected_version, "/api/v1/profiles")

    payload = json.loads(raw_body.decode("utf-8"))
    if payload.get("server_version") != expected_version:
        raise ValueError(
            f"/api/v1/profiles server_version={payload.get('server_version')!r}, "
            f"expected {expected_version!r}"
        )

    profiles = payload.get("profiles")
    if not isinstance(profiles, list):
        raise ValueError("/api/v1/profiles missing profiles list")
    if len(profiles) != expected_profiles:
        raise ValueError(
            f"/api/v1/profiles count={len(profiles)}, expected {expected_profiles}"
        )

    ids = {
        item.get("profile_id")
        for item in profiles
        if isinstance(item, dict) and isinstance(item.get("profile_id"), str)
    }
    missing = sorted(REQUIRED_LEGACY_PROFILE_IDS - ids)
    if missing:
        raise ValueError(f"/api/v1/profiles missing required IDs: {missing}")

    return {
        "status": status,
        "count": len(profiles),
        "profile_ids": sorted(ids),
    }


def check_echo(
    *,
    scheme: str,
    host: str,
    port: int,
    base_path: str,
    timeout: float,
    ssl_context: ssl.SSLContext,
    http_opener: Callable[..., http.client.HTTPConnection],
    expected_version: str,
) -> dict[str, Any]:
    path = f"{base_path}/api/v1/echo"
    req_body = b'{"probe":"miw2-monitor"}'
    status, headers, raw_body = _http_request(
        scheme=scheme,
        host=host,
        port=port,
        method="POST",
        path=path,
        body=req_body,
        headers={"Content-Type": "application/json"},
        timeout=timeout,
        ssl_context=ssl_context,
        http_opener=http_opener,
    )
    if status != 200:
        raise ValueError(f"/api/v1/echo returned HTTP {status}, expected 200")
    _assert_server_header(headers, expected_version, "/api/v1/echo")

    payload = json.loads(raw_body.decode("utf-8"))
    t1_us = payload.get("t1_us")
    t2_us = payload.get("t2_us")
    anchor = payload.get("anchor_wall_unix_ns")
    observed = payload.get("observed")

    if isinstance(t1_us, bool) or not isinstance(t1_us, int):
        raise ValueError(f"/api/v1/echo invalid t1_us={t1_us!r}")
    if isinstance(t2_us, bool) or not isinstance(t2_us, int):
        raise ValueError(f"/api/v1/echo invalid t2_us={t2_us!r}")
    if t2_us < t1_us:
        raise ValueError(f"/api/v1/echo t2_us ({t2_us}) < t1_us ({t1_us})")
    if isinstance(anchor, bool) or not isinstance(anchor, int) or anchor <= 0:
        raise ValueError(f"/api/v1/echo invalid anchor_wall_unix_ns={anchor!r}")
    if not isinstance(observed, str) or not observed.strip():
        raise ValueError(f"/api/v1/echo invalid observed={observed!r}")

    return {
        "status": status,
        "t1_us": t1_us,
        "t2_us": t2_us,
        "anchor_wall_unix_ns": anchor,
        "observed": observed,
    }


def check_results_disabled(
    *,
    scheme: str,
    host: str,
    port: int,
    base_path: str,
    timeout: float,
    ssl_context: ssl.SSLContext,
    http_opener: Callable[..., http.client.HTTPConnection],
    expected_version: str,
) -> dict[str, Any]:
    path = f"{base_path}/api/v1/results"
    status, headers, raw_body = _http_request(
        scheme=scheme,
        host=host,
        port=port,
        method="POST",
        path=path,
        body=b"{}",
        headers={"Content-Type": "application/json"},
        timeout=timeout,
        ssl_context=ssl_context,
        http_opener=http_opener,
    )
    if status != 503:
        raise ValueError(
            f"/api/v1/results returned HTTP {status}, expected strictly 503 "
            "(production result-ingress guard)"
        )
    _assert_server_header(headers, expected_version, "/api/v1/results")
    return {
        "status": status,
        "body_preview": raw_body.decode("utf-8", errors="replace").strip()[:120],
    }


def _read_http_response_head(sock: Any) -> tuple[str, dict[str, str]]:
    buf = bytearray()
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf.extend(chunk)
        if len(buf) > MAX_WS_HEADER_BYTES:
            raise ValueError("WebSocket handshake response exceeded 16 KiB header limit")

    raw = bytes(buf)
    sep_idx = raw.find(b"\r\n\r\n")
    if sep_idx == -1:
        raise ValueError(f"Incomplete HTTP handshake response: {raw[:200]!r}")

    head_text = raw[:sep_idx].decode("latin-1")
    lines = head_text.split("\r\n")
    status_line = lines[0]
    resp_headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            resp_headers[k.strip().lower()] = v.strip()
    return status_line, resp_headers


def check_realtime_sim_ws(
    *,
    host: str,
    port: int,
    base_path: str,
    timeout: float,
    ssl_context: ssl.SSLContext,
    tls_connector: Callable[..., Any] | None = None,
) -> dict[str, Any]:
    connector = tls_connector if tls_connector is not None else default_tls_connector
    ws_key = base64.b64encode(os.urandom(16)).decode("ascii")
    expected_accept = base64.b64encode(
        hashlib.sha1((ws_key + WS_GUID).encode("ascii")).digest()
    ).decode("ascii")

    host_header = f"[{host}]:{port}" if ":" in host else f"{host}:{port}"
    path = f"{base_path}/api/v1/realtime-sim"
    # Deliberately omit Origin header: server CheckOrigin requires Origin == "".
    req_lines = [
        f"GET {path} HTTP/1.1",
        f"Host: {host_header}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {ws_key}",
        "Sec-WebSocket-Version: 13",
        "",
        "",
    ]
    req_bytes = "\r\n".join(req_lines).encode("ascii")

    with connector(host, port, timeout, ssl_context) as sock:
        sock.sendall(req_bytes)
        status_line, resp_headers = _read_http_response_head(sock)
        if not status_line.startswith("HTTP/1.1 101"):
            raise ValueError(
                f"/api/v1/realtime-sim handshake returned {status_line!r}, expected HTTP/1.1 101"
            )
        upgrade_hdr = resp_headers.get("upgrade", "").lower()
        if upgrade_hdr != "websocket":
            raise ValueError(
                f"/api/v1/realtime-sim Upgrade={resp_headers.get('upgrade')!r}, expected 'websocket'"
            )
        conn_tokens = {
            tok.strip().lower()
            for tok in resp_headers.get("connection", "").split(",")
            if tok.strip()
        }
        if "upgrade" not in conn_tokens:
            raise ValueError(
                f"/api/v1/realtime-sim Connection={resp_headers.get('connection')!r} missing 'Upgrade'"
            )
        actual_accept = resp_headers.get("sec-websocket-accept", "")
        if actual_accept != expected_accept:
            raise ValueError(
                f"/api/v1/realtime-sim Sec-WebSocket-Accept={actual_accept!r}, "
                f"expected {expected_accept!r}"
            )
        sock.sendall(WS_CLOSE_FRAME_MASKED)

    return {
        "status": 101,
        "status_line": status_line,
        "accept_verified": True,
    }


def _run_one(name: str, fn: Callable[[], dict[str, Any]]) -> dict[str, Any]:
    start = time.monotonic()
    try:
        details = fn()
        elapsed_ms = round((time.monotonic() - start) * 1000.0, 2)
        return {"ok": True, "elapsed_ms": elapsed_ms, **details}
    except (
        OSError,
        ssl.SSLError,
        TimeoutError,
        http.client.HTTPException,
        ValueError,
        KeyError,
        TypeError,
        json.JSONDecodeError,
    ) as exc:
        elapsed_ms = round((time.monotonic() - start) * 1000.0, 2)
        return {
            "ok": False,
            "elapsed_ms": elapsed_ms,
            "error_type": type(exc).__name__,
            "error": str(exc),
        }


def run_checks(
    *,
    base_url: str = DEFAULT_BASE_URL,
    min_cert_hours: float = DEFAULT_MIN_CERT_HOURS,
    expected_version: str = DEFAULT_EXPECTED_VERSION,
    expected_profiles: int = DEFAULT_EXPECTED_PROFILES,
    expected_validated_profiles: int = DEFAULT_EXPECTED_VALIDATED_PROFILES,
    require_h3: bool = True,
    timeout: float = DEFAULT_TIMEOUT_S,
    ca_file: str | Path | None = None,
    ssl_context: ssl.SSLContext | None = None,
    now_fn: Callable[[], datetime] | None = None,
    tls_connector: Callable[..., Any] | None = None,
    http_opener: Callable[..., http.client.HTTPConnection] | None = None,
    ws_connector: Callable[..., Any] | None = None,
) -> dict[str, Any]:
    scheme, host, port, base_path = parse_base_url(base_url)
    ctx = ssl_context if ssl_context is not None else build_ssl_context(ca_file=ca_file)
    now_cb = now_fn if now_fn is not None else utc_now
    tls_conn = tls_connector if tls_connector is not None else default_tls_connector
    http_open = http_opener if http_opener is not None else default_http_opener
    ws_conn = ws_connector if ws_connector is not None else tls_conn

    checks = {
        "tls_cert": _run_one(
            "tls_cert",
            lambda: check_tls_cert(
                host,
                port,
                timeout=timeout,
                ssl_context=ctx,
                min_cert_hours=min_cert_hours,
                now_fn=now_cb,
                tls_connector=tls_conn,
            ),
        ),
        "serverinfo": _run_one(
            "serverinfo",
            lambda: check_serverinfo(
                scheme=scheme,
                host=host,
                port=port,
                base_path=base_path,
                timeout=timeout,
                ssl_context=ctx,
                http_opener=http_open,
                expected_version=expected_version,
                expected_validated_profiles=expected_validated_profiles,
                require_h3=require_h3,
            ),
        ),
        "profiles": _run_one(
            "profiles",
            lambda: check_profiles(
                scheme=scheme,
                host=host,
                port=port,
                base_path=base_path,
                timeout=timeout,
                ssl_context=ctx,
                http_opener=http_open,
                expected_version=expected_version,
                expected_profiles=expected_profiles,
            ),
        ),
        "echo": _run_one(
            "echo",
            lambda: check_echo(
                scheme=scheme,
                host=host,
                port=port,
                base_path=base_path,
                timeout=timeout,
                ssl_context=ctx,
                http_opener=http_open,
                expected_version=expected_version,
            ),
        ),
        "results_guard": _run_one(
            "results_guard",
            lambda: check_results_disabled(
                scheme=scheme,
                host=host,
                port=port,
                base_path=base_path,
                timeout=timeout,
                ssl_context=ctx,
                http_opener=http_open,
                expected_version=expected_version,
            ),
        ),
        "realtime_sim_ws": _run_one(
            "realtime_sim_ws",
            lambda: check_realtime_sim_ws(
                host=host,
                port=port,
                base_path=base_path,
                timeout=timeout,
                ssl_context=ctx,
                tls_connector=ws_conn,
            ),
        ),
    }

    passed = sum(1 for item in checks.values() if item["ok"])
    total = len(checks)
    now_dt = now_cb()
    if now_dt.tzinfo is None:
        now_dt = now_dt.replace(tzinfo=timezone.utc)

    return {
        "checked_at_utc": now_dt.isoformat().replace("+00:00", "Z"),
        "base_url": base_url,
        "min_cert_hours": min_cert_hours,
        "expected_version": expected_version,
        "require_h3": require_h3,
        "ok": passed == total,
        "passed": passed,
        "total": total,
        "checks": checks,
    }


def format_text_report(report: dict[str, Any]) -> str:
    lines = [
        f"miw2 monitor: {'PASS' if report['ok'] else 'FAIL'} "
        f"({report['passed']}/{report['total']}) target={report['base_url']} "
        f"at={report['checked_at_utc']}"
    ]
    for name, item in report["checks"].items():
        status = "PASS" if item["ok"] else "FAIL"
        detail = ""
        if name == "tls_cert" and item["ok"]:
            detail = f" remaining={item['remaining_hours']}h"
        elif not item["ok"]:
            detail = f" error={item.get('error_type')}: {item.get('error')}"
        lines.append(f"  - {name}: {status} ({item['elapsed_ms']} ms){detail}")
    return "\n".join(lines)


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Check miw2 TLS certificate expiry and endpoint contract invariants."
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--min-cert-hours", type=float, default=DEFAULT_MIN_CERT_HOURS)
    parser.add_argument("--expected-version", default=DEFAULT_EXPECTED_VERSION)
    parser.add_argument(
        "--expected-profiles", type=int, default=DEFAULT_EXPECTED_PROFILES
    )
    parser.add_argument(
        "--expected-validated-profiles",
        type=int,
        default=DEFAULT_EXPECTED_VALIDATED_PROFILES,
    )
    parser.add_argument(
        "--require-h3",
        action=argparse.BooleanOptionalAction,
        default=True,
    )
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_S)
    parser.add_argument("--ca-file", type=Path, default=None)
    parser.add_argument("--json-out", type=Path, default=None)
    parser.add_argument("--format", choices=("text", "json"), default="text")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    report = run_checks(
        base_url=args.base_url,
        min_cert_hours=args.min_cert_hours,
        expected_version=args.expected_version,
        expected_profiles=args.expected_profiles,
        expected_validated_profiles=args.expected_validated_profiles,
        require_h3=args.require_h3,
        timeout=args.timeout,
        ca_file=args.ca_file,
    )

    if args.json_out is not None:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if args.format == "json":
        print(json.dumps(report, indent=2))
    else:
        print(format_text_report(report))

    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
