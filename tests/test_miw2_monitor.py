#!/usr/bin/env python3
"""Offline unit tests for tools/miw2-monitor/check_miw2.py (stdlib only)."""

from __future__ import annotations

import base64
import contextlib
import hashlib
import http.server
import io
import json
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[1]
MONITOR_DIR = REPO_ROOT / "tools" / "miw2-monitor"
CHECK_SCRIPT = MONITOR_DIR / "check_miw2.py"
if str(MONITOR_DIR) not in sys.path:
    sys.path.insert(0, str(MONITOR_DIR))

import check_miw2  # noqa: E402


def _fmt_cert_time(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%b %d %H:%M:%S %Y GMT")


class _FakeTLSSocket:
    def __init__(
        self,
        cert: dict[str, Any],
        *,
        tls_version: str = "TLSv1.3",
        cipher: tuple[str, str, int] = ("TLS_AES_128_GCM_SHA256", "TLSv1.3", 128),
    ) -> None:
        self._cert = cert
        self._tls_version = tls_version
        self._cipher = cipher

    def getpeercert(self) -> dict[str, Any]:
        return self._cert

    def version(self) -> str:
        return self._tls_version

    def cipher(self) -> tuple[str, str, int]:
        return self._cipher

    def __enter__(self) -> _FakeTLSSocket:
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        return None


def _plain_tcp_connector(
    host: str, port: int, timeout: float, _ssl_context: ssl.SSLContext
) -> socket.socket:
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.settimeout(timeout)
    return sock


class _ServerState:
    def __init__(self) -> None:
        self.server_version = "aneb-server/0.8.4"
        self.h3_enabled = True
        self.include_alt_svc = True
        self.proto_header = "HTTP/1.1;via=tcp"
        self.validated_profiles = [
            "ai_realtime_voice_quick",
            "ai_realtime_voice_repeatability_qualification",
            "network_comprehensive_quick",
            "network_comprehensive_repeatability_qualification",
            "token_multimodal_quick",
            "token_multimodal_repeatability_qualification",
        ]
        self.profiles = [
            {"profile_id": "basic_network", "version": "0.1.0"},
            {"profile_id": "s1_chat", "version": "0.2.1"},
            {"profile_id": "s2_coding_agent", "version": "0.2.1"},
            {"profile_id": "s3_multimodal", "version": "0.3.0"},
        ]
        self.echo_t1 = 1000
        self.echo_t2 = 1250
        self.echo_observed = "127.0.0.1:54321"
        self.results_status = 503
        self.ws_mode = "valid"  # valid | 403 | 200 | bad_accept | bad_status_1010 | stall
        self.ws_saw_origin: bool | None = None
        self.ws_received_close_frame: bytes | None = None
        self.ws_event = threading.Event()


def _make_handler(state: _ServerState) -> type[http.server.BaseHTTPRequestHandler]:
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            return None

        def _send_json(
            self,
            status: int,
            payload: dict[str, Any],
            *,
            extra_headers: dict[str, str] | None = None,
        ) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-Aneb-Server", state.server_version)
            self.send_header("X-Aneb-Proto", state.proto_header)
            if extra_headers:
                for k, v in extra_headers.items():
                    self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            if self.path == "/api/v1/serverinfo":
                extra: dict[str, str] = {}
                if state.include_alt_svc:
                    extra["Alt-Svc"] = 'h3=":8443"; ma=86400'
                self._send_json(
                    200,
                    {
                        "version": state.server_version,
                        "h3_enabled": state.h3_enabled,
                        "congestion_control": "cubic",
                        "tcp_slow_start_after_idle": "0",
                        "execution_capabilities": {
                            "contract_id": "aneb-server-capability-receipt",
                            "validated_profiles": list(state.validated_profiles),
                        },
                    },
                    extra_headers=extra,
                )
                return

            if self.path == "/api/v1/profiles":
                self._send_json(
                    200,
                    {
                        "server_version": state.server_version,
                        "profiles": list(state.profiles),
                    },
                )
                return

            if self.path == "/api/v1/realtime-sim":
                state.ws_saw_origin = "origin" in {k.lower() for k in self.headers.keys()}
                if state.ws_mode == "stall":
                    self.close_connection = True
                    threading.Event().wait(0.8)
                    return
                if state.ws_mode == "403":
                    self.close_connection = True
                    body = b"forbidden"
                    self.send_response(403)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                if state.ws_mode == "200":
                    self.close_connection = True
                    body = b"ok"
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return

                ws_key = self.headers.get("Sec-WebSocket-Key", "")
                accept = base64.b64encode(
                    hashlib.sha1((ws_key + check_miw2.WS_GUID).encode("ascii")).digest()
                ).decode("ascii")
                if state.ws_mode == "bad_accept":
                    accept = "invalid-accept-token"

                status_line = (
                    "HTTP/1.1 1010 Bogus"
                    if state.ws_mode == "bad_status_1010"
                    else "HTTP/1.1 101 Switching Protocols"
                )
                self.wfile.write(
                    (
                        f"{status_line}\r\n"
                        "Upgrade: websocket\r\n"
                        "Connection: Upgrade\r\n"
                        f"Sec-WebSocket-Accept: {accept}\r\n"
                        "\r\n"
                    ).encode("ascii")
                )
                self.wfile.flush()
                self.connection.settimeout(2.0)
                try:
                    state.ws_received_close_frame = self.rfile.read(6)
                except OSError:
                    state.ws_received_close_frame = None
                state.ws_event.set()
                self.close_connection = True
                return

            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length", "0"))
            if length > 0:
                _ = self.rfile.read(length)

            if self.path == "/api/v1/echo":
                self._send_json(
                    200,
                    {
                        "t1_us": state.echo_t1,
                        "t2_us": state.echo_t2,
                        "anchor_wall_unix_ns": 1795145069220317426,
                        "observed": state.echo_observed,
                    },
                )
                return

            if self.path == "/api/v1/results":
                if state.results_status == 503:
                    body = b"result upload unavailable\n"
                    self.send_response(503)
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("X-Aneb-Server", state.server_version)
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self._send_json(state.results_status, {"status": state.results_status})
                return

            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    return Handler


class _QuietThreadingHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request: Any, client_address: Any) -> None:
        return None


class Miw2MonitorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.state = _ServerState()
        self.server = _QuietThreadingHTTPServer(
            ("127.0.0.1", 0), _make_handler(self.state)
        )
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.now = datetime(2026, 11, 20, 22, 0, 0, tzinfo=timezone.utc)

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2.0)

    def _cert(
        self,
        *,
        hours_remaining: float = 120.0,
        hours_since_start: float = 24.0,
        ip_san: str = "127.0.0.1",
        subject: tuple[Any, ...] = (),
    ) -> dict[str, Any]:
        return {
            "subject": subject,
            "issuer": (
                (("countryName", "US"),),
                (("organizationName", "Let's Encrypt"),),
                (("commonName", "YE1"),),
            ),
            "notBefore": _fmt_cert_time(self.now - timedelta(hours=hours_since_start)),
            "notAfter": _fmt_cert_time(self.now + timedelta(hours=hours_remaining)),
            "subjectAltName": (("IP Address", ip_san),),
        }

    def _run_against_loopback(
        self,
        *,
        hours_remaining: float = 120.0,
        min_cert_hours: float = 48.0,
        require_h3: bool = True,
    ) -> dict[str, Any]:
        cert = self._cert(hours_remaining=hours_remaining, ip_san="127.0.0.1")
        return check_miw2.run_checks(
            base_url=f"http://127.0.0.1:{self.port}",
            min_cert_hours=min_cert_hours,
            require_h3=require_h3,
            timeout=1.0,
            now_fn=lambda: self.now,
            tls_connector=lambda *args: _FakeTLSSocket(cert),
            ws_connector=_plain_tcp_connector,
        )

    def test_build_ssl_context_enforces_secure_defaults(self) -> None:
        ctx = check_miw2.build_ssl_context()
        self.assertEqual(ctx.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(ctx.check_hostname)
        self.assertGreaterEqual(ctx.minimum_version, ssl.TLSVersion.TLSv1_2)

    def test_inspect_cert_boundaries_and_empty_subject(self) -> None:
        boundary_cert = self._cert(
            hours_remaining=48.0, ip_san="120.77.174.207", subject=()
        )
        res = check_miw2.inspect_cert(
            boundary_cert,
            host="120.77.174.207",
            min_cert_hours=48.0,
            now=self.now,
        )
        self.assertFalse(res["subject_present"])
        self.assertEqual(res["remaining_hours"], 48.0)

        below_threshold = self._cert(hours_remaining=47.9, ip_san="120.77.174.207")
        with self.assertRaisesRegex(ValueError, "47.90h"):
            check_miw2.inspect_cert(
                below_threshold,
                host="120.77.174.207",
                min_cert_hours=48.0,
                now=self.now,
            )

        expired = self._cert(hours_remaining=-1.0, ip_san="120.77.174.207")
        with self.assertRaisesRegex(ValueError, "expired"):
            check_miw2.inspect_cert(
                expired,
                host="120.77.174.207",
                min_cert_hours=48.0,
                now=self.now,
            )

        not_yet_valid = self._cert(
            hours_remaining=100.0, hours_since_start=-1.0, ip_san="120.77.174.207"
        )
        with self.assertRaisesRegex(ValueError, "not yet valid"):
            check_miw2.inspect_cert(
                not_yet_valid,
                host="120.77.174.207",
                min_cert_hours=48.0,
                now=self.now,
            )

        wrong_san = self._cert(hours_remaining=100.0, ip_san="127.0.0.1")
        with self.assertRaisesRegex(ValueError, "does not cover host"):
            check_miw2.inspect_cert(
                wrong_san,
                host="120.77.174.207",
                min_cert_hours=48.0,
                now=self.now,
            )

    def test_healthy_all_six_checks_pass(self) -> None:
        report = self._run_against_loopback()
        self.assertTrue(report["ok"], report)
        self.assertEqual(report["passed"], 6)
        self.assertEqual(report["total"], 6)
        self.assertTrue(self.state.ws_event.wait(timeout=2.0))
        self.assertFalse(self.state.ws_saw_origin)
        self.assertEqual(
            self.state.ws_received_close_frame, check_miw2.WS_CLOSE_FRAME_MASKED
        )

    def test_cert_below_48h_fails_run_checks(self) -> None:
        report = self._run_against_loopback(hours_remaining=47.9)
        self.assertFalse(report["ok"])
        self.assertEqual(report["passed"], 5)
        self.assertFalse(report["checks"]["tls_cert"]["ok"])
        self.assertIn("47.90h", report["checks"]["tls_cert"]["error"])

    def test_results_guard_fails_on_200_and_400(self) -> None:
        for bad_status in (200, 400):
            with self.subTest(status=bad_status):
                self.state.results_status = bad_status
                report = self._run_against_loopback()
                self.assertFalse(report["ok"])
                self.assertFalse(report["checks"]["results_guard"]["ok"])
                self.assertIn(
                    f"HTTP {bad_status}", report["checks"]["results_guard"]["error"]
                )

    def test_serverinfo_and_profiles_failures(self) -> None:
        self.state.server_version = "aneb-server/0.1.0"
        report = self._run_against_loopback()
        self.assertFalse(report["checks"]["serverinfo"]["ok"])
        self.assertFalse(report["checks"]["profiles"]["ok"])
        self.assertFalse(report["checks"]["echo"]["ok"])
        self.assertFalse(report["checks"]["results_guard"]["ok"])

        self.state.server_version = "aneb-server/0.8.4"
        self.state.h3_enabled = False
        report = self._run_against_loopback(require_h3=True)
        self.assertFalse(report["checks"]["serverinfo"]["ok"])

        self.state.h3_enabled = True
        self.state.validated_profiles = ["only_one"]
        self.state.profiles = [{"profile_id": "basic_network", "version": "0.1.0"}]
        report = self._run_against_loopback()
        self.assertFalse(report["checks"]["serverinfo"]["ok"])
        self.assertFalse(report["checks"]["profiles"]["ok"])

    def test_echo_failures(self) -> None:
        self.state.echo_t1 = 2000
        self.state.echo_t2 = 1000
        report = self._run_against_loopback()
        self.assertFalse(report["checks"]["echo"]["ok"])

    def test_realtime_sim_ws_failures(self) -> None:
        for mode in ("403", "200", "bad_accept", "bad_status_1010"):
            with self.subTest(ws_mode=mode):
                self.state.ws_mode = mode
                report = self._run_against_loopback()
                self.assertFalse(report["ok"])
                self.assertFalse(report["checks"]["realtime_sim_ws"]["ok"])

    def test_realtime_sim_ws_rejects_status_1010_prefix(self) -> None:
        self.state.ws_mode = "bad_status_1010"
        with self.assertRaisesRegex(ValueError, "1010"):
            check_miw2.check_realtime_sim_ws(
                host="127.0.0.1",
                port=self.port,
                base_path="",
                timeout=1.0,
                ssl_context=check_miw2.build_ssl_context(),
                tls_connector=_plain_tcp_connector,
            )

    def test_min_cert_hours_rejects_non_finite_and_negative(self) -> None:
        cert = self._cert(hours_remaining=120.0, ip_san="127.0.0.1")
        for bad_threshold in (float("nan"), float("inf"), float("-inf"), -1.0):
            with self.subTest(min_cert_hours=bad_threshold):
                with self.assertRaisesRegex(ValueError, "min_cert_hours"):
                    check_miw2.inspect_cert(
                        cert,
                        host="127.0.0.1",
                        min_cert_hours=bad_threshold,
                        now=self.now,
                    )
                with self.assertRaisesRegex(ValueError, "min_cert_hours"):
                    self._run_against_loopback(min_cert_hours=bad_threshold)

    def test_checked_at_utc_normalizes_non_utc_offset(self) -> None:
        cert = self._cert(hours_remaining=120.0, ip_san="127.0.0.1")
        cst_now = self.now.astimezone(timezone(timedelta(hours=8)))
        report = check_miw2.run_checks(
            base_url=f"http://127.0.0.1:{self.port}",
            timeout=1.0,
            now_fn=lambda: cst_now,
            tls_connector=lambda *args: _FakeTLSSocket(cert),
            ws_connector=_plain_tcp_connector,
        )
        self.assertTrue(report["ok"], report)
        self.assertEqual(report["checked_at_utc"], "2026-11-20T22:00:00Z")

    def test_connection_refused_and_timeout_handling(self) -> None:
        probe_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe_sock.bind(("127.0.0.1", 0))
        closed_port = probe_sock.getsockname()[1]
        probe_sock.close()

        report = check_miw2.run_checks(
            base_url=f"http://127.0.0.1:{closed_port}",
            timeout=0.5,
            ws_connector=_plain_tcp_connector,
        )
        self.assertFalse(report["ok"])
        self.assertEqual(report["passed"], 0)
        for check in report["checks"].values():
            self.assertFalse(check["ok"])
            self.assertIn("error", check)

        self.state.ws_mode = "stall"
        cert = self._cert(hours_remaining=120.0, ip_san="127.0.0.1")
        timeout_report = check_miw2.run_checks(
            base_url=f"http://127.0.0.1:{self.port}",
            timeout=0.2,
            now_fn=lambda: self.now,
            tls_connector=lambda *args: _FakeTLSSocket(cert),
            ws_connector=_plain_tcp_connector,
        )
        self.assertFalse(timeout_report["ok"])
        self.assertFalse(timeout_report["checks"]["realtime_sim_ws"]["ok"])

    def test_cli_format_json_and_json_out(self) -> None:
        cert = self._cert(hours_remaining=120.0, ip_san="127.0.0.1")
        with tempfile.TemporaryDirectory() as tmpdir:
            out_path = Path(tmpdir) / "nested" / "report.json"
            stdout_buf = io.StringIO()
            with (
                mock.patch.object(
                    check_miw2,
                    "default_tls_connector",
                    side_effect=lambda *args: _FakeTLSSocket(cert),
                ),
                mock.patch.object(
                    check_miw2,
                    "check_realtime_sim_ws",
                    return_value={"status": 101, "accept_verified": True},
                ),
                mock.patch.object(check_miw2, "utc_now", return_value=self.now),
                contextlib.redirect_stdout(stdout_buf),
            ):
                rc = check_miw2.main(
                    [
                        "--base-url",
                        f"http://127.0.0.1:{self.port}",
                        "--format",
                        "json",
                        "--json-out",
                        str(out_path),
                    ]
                )
            self.assertEqual(rc, 0)
            stdout_json = json.loads(stdout_buf.getvalue())
            file_json = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertTrue(stdout_json["ok"])
            self.assertEqual(stdout_json, file_json)

            fail_out = Path(tmpdir) / "fail-report.json"
            probe_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            probe_sock.bind(("127.0.0.1", 0))
            closed_port = probe_sock.getsockname()[1]
            probe_sock.close()
            proc = subprocess.run(
                [
                    sys.executable,
                    "-B",
                    str(CHECK_SCRIPT),
                    "--base-url",
                    f"http://127.0.0.1:{closed_port}",
                    "--timeout",
                    "0.5",
                    "--format",
                    "json",
                    "--json-out",
                    str(fail_out),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(proc.returncode, 1)
            fail_stdout = json.loads(proc.stdout)
            fail_file = json.loads(fail_out.read_text(encoding="utf-8"))
            self.assertFalse(fail_stdout["ok"])
            self.assertEqual(fail_stdout, fail_file)


if __name__ == "__main__":
    unittest.main()
