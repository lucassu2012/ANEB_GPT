from __future__ import annotations

import json
import hashlib
import http.client
import io
import os
import queue
import socket
import ssl
import stat
import tempfile
import threading
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from scripts.token_serverinfo_negative_proxy import (
    HttpsUpstreamFetcher,
    NegativeProxyFailure,
    NegativeProxySession,
    ProxyConfig,
    UpstreamResponse,
    main,
    serve_once,
)


RUN_ID = "019f731f-602a-72b3-abeb-85afa315e0f0"


class RecordingFetcher:
    def __init__(
        self,
        response: UpstreamResponse,
        *,
        ca_file_sha256: str = "a" * 64,
    ) -> None:
        self.response = response
        self.ca_file_sha256 = ca_file_sha256
        self.calls: list[dict[str, str]] = []

    def fetch(self, headers: dict[str, str]) -> UpstreamResponse:
        self.calls.append(dict(headers))
        return self.response


class TokenServerInfoNegativeProxyTest(unittest.TestCase):
    def test_valid_request_filters_only_execution_capabilities_and_preserves_raw_body(self) -> None:
        raw = (
            b'{"version":"aneb-server/0.8.0","h3_enabled":true,'
            b'"execution_capabilities":{"contract_id":"receipt","primitives":[1]}}'
        )
        fetcher = RecordingFetcher(
            UpstreamResponse(
                status=200,
                headers=(("Content-Type", "application/json"),),
                body=raw,
                peer_certificate_der=b"peer-certificate",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "negative-proxy"
            session = NegativeProxySession(
                ProxyConfig.for_test(evidence_directory=evidence),
                fetcher=fetcher,
            )

            served = session.process(
                method="GET",
                path="/api/v1/serverinfo",
                headers={
                    "X-Aneb-Run-Id": [RUN_ID],
                    "X-Aneb-Audit-Role": ["capability"],
                },
            )

            self.assertEqual(
                {
                    "X-Aneb-Run-Id": RUN_ID,
                    "X-Aneb-Audit-Role": "capability",
                },
                fetcher.calls[0],
            )
            self.assertEqual(
                {"h3_enabled": True, "version": "aneb-server/0.8.0"},
                json.loads(served),
            )
            self.assertEqual(raw, (evidence / "upstream-serverinfo.raw").read_bytes())
            self.assertEqual(served, (evidence / "filtered-serverinfo.json").read_bytes())

    def test_wrong_method_fails_before_upstream(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(200, (), b'{"execution_capabilities":{"ok":true}}', b"peer"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            session = NegativeProxySession(
                ProxyConfig.for_test(evidence_directory=Path(temporary) / "evidence"),
                fetcher=fetcher,
            )

            with self.assertRaises(NegativeProxyFailure) as raised:
                session.process(
                    method="POST",
                    path="/api/v1/serverinfo",
                    headers={
                        "X-Aneb-Run-Id": [RUN_ID],
                        "X-Aneb-Audit-Role": ["capability"],
                    },
                )

            self.assertEqual("request_method_invalid", raised.exception.reason_code)
            self.assertEqual([], fetcher.calls)

    def test_invalid_path_or_audit_headers_fail_before_upstream(self) -> None:
        cases = (
            (
                "/api/v1/echo",
                {"X-Aneb-Run-Id": [RUN_ID], "X-Aneb-Audit-Role": ["capability"]},
                "request_path_invalid",
            ),
            (
                "/api/v1/serverinfo?x=1",
                {"X-Aneb-Run-Id": [RUN_ID], "X-Aneb-Audit-Role": ["capability"]},
                "request_path_invalid",
            ),
            (
                "/api/v1/serverinfo",
                {"X-Aneb-Run-Id": [RUN_ID, RUN_ID], "X-Aneb-Audit-Role": ["capability"]},
                "request_run_id_invalid",
            ),
            (
                "/api/v1/serverinfo",
                {"X-Aneb-Run-Id": [RUN_ID.upper()], "X-Aneb-Audit-Role": ["capability"]},
                "request_run_id_invalid",
            ),
            (
                "/api/v1/serverinfo",
                {"X-Aneb-Run-Id": [RUN_ID], "X-Aneb-Audit-Role": ["reachability"]},
                "request_audit_role_invalid",
            ),
        )
        for path, headers, expected_reason in cases:
            with self.subTest(path=path, expected_reason=expected_reason):
                fetcher = RecordingFetcher(
                    UpstreamResponse(200, (), b'{"execution_capabilities":{"ok":true}}', b"peer"),
                )
                with tempfile.TemporaryDirectory() as temporary:
                    session = NegativeProxySession(
                        ProxyConfig.for_test(evidence_directory=Path(temporary) / "evidence"),
                        fetcher=fetcher,
                    )
                    with self.assertRaises(NegativeProxyFailure) as raised:
                        session.process(method="GET", path=path, headers=headers)
                    self.assertEqual(expected_reason, raised.exception.reason_code)
                    self.assertEqual([], fetcher.calls)

    def test_second_request_is_rejected_without_a_second_upstream_call(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(200, (), b'{"execution_capabilities":{"ok":true}}', b"peer"),
        )
        headers = {
            "X-Aneb-Run-Id": [RUN_ID],
            "X-Aneb-Audit-Role": ["capability"],
        }
        with tempfile.TemporaryDirectory() as temporary:
            session = NegativeProxySession(
                ProxyConfig.for_test(evidence_directory=Path(temporary) / "evidence"),
                fetcher=fetcher,
            )
            session.process(method="GET", path="/api/v1/serverinfo", headers=headers)

            with self.assertRaises(NegativeProxyFailure) as raised:
                session.process(method="GET", path="/api/v1/serverinfo", headers=headers)

            self.assertEqual("request_already_consumed", raised.exception.reason_code)
            self.assertEqual(1, len(fetcher.calls))

    def test_invalid_upstream_response_fails_with_stable_reason(self) -> None:
        cases = (
            (
                UpstreamResponse(302, (), b'{"execution_capabilities":{"ok":true}}', b"peer"),
                "upstream_status_invalid",
                None,
            ),
            (
                UpstreamResponse(200, (), b'{"execution_capabilities":{"ok":true}}', b"peer"),
                "upstream_body_too_large",
                16,
            ),
            (UpstreamResponse(200, (), b'{"version":"0.8.0"}', b"peer"), "execution_capabilities_missing", None),
            (UpstreamResponse(200, (), b'{"execution_capabilities":{}}', b"peer"), "execution_capabilities_invalid", None),
            (
                UpstreamResponse(
                    200,
                    (),
                    b'{"execution_capabilities":{"a":1},"execution_capabilities":{"b":2}}',
                    b"peer",
                ),
                "upstream_json_duplicate_key",
                None,
            ),
            (
                UpstreamResponse(200, (), b'{"execution_capabilities":{"value":NaN}}', b"peer"),
                "upstream_json_nonstandard_constant",
                None,
            ),
        )
        for response, expected_reason, body_limit in cases:
            with self.subTest(expected_reason=expected_reason):
                fetcher = RecordingFetcher(response)
                with tempfile.TemporaryDirectory() as temporary:
                    config = ProxyConfig.for_test(
                        evidence_directory=Path(temporary) / "evidence",
                    )
                    if body_limit is not None:
                        config = replace(config, max_body_bytes=body_limit)
                    session = NegativeProxySession(config, fetcher=fetcher)
                    with self.assertRaises(NegativeProxyFailure) as raised:
                        session.process(
                            method="GET",
                            path="/api/v1/serverinfo",
                            headers={
                                "X-Aneb-Run-Id": [RUN_ID],
                                "X-Aneb-Audit-Role": ["capability"],
                            },
                        )
                    self.assertEqual(expected_reason, raised.exception.reason_code)
                    self.assertEqual(1, len(fetcher.calls))

    def test_request_response_headers_and_peer_certificate_are_bounded(self) -> None:
        valid_body = b'{"execution_capabilities":{"ok":true}}'
        cases = (
            (
                {"X-Pad": ["x" * 100]},
                UpstreamResponse(200, (), valid_body, b"peer"),
                "request_headers_too_large",
                0,
            ),
            (
                {},
                UpstreamResponse(200, (("X-Pad", "x" * 100),), valid_body, b"peer"),
                "upstream_headers_too_large",
                1,
            ),
            (
                {},
                UpstreamResponse(200, (), valid_body, b""),
                "upstream_peer_certificate_missing",
                1,
            ),
        )
        for extra_headers, response, expected_reason, expected_calls in cases:
            with self.subTest(expected_reason=expected_reason):
                fetcher = RecordingFetcher(response)
                with tempfile.TemporaryDirectory() as temporary:
                    config = replace(
                        ProxyConfig.for_test(evidence_directory=Path(temporary) / "evidence"),
                        max_header_bytes=96,
                    )
                    headers = {
                        "X-Aneb-Run-Id": [RUN_ID],
                        "X-Aneb-Audit-Role": ["capability"],
                        **extra_headers,
                    }
                    with self.assertRaises(NegativeProxyFailure) as raised:
                        NegativeProxySession(config, fetcher=fetcher).process(
                            method="GET",
                            path="/api/v1/serverinfo",
                            headers=headers,
                        )
                    self.assertEqual(expected_reason, raised.exception.reason_code)
                    self.assertEqual(expected_calls, len(fetcher.calls))

    def test_success_writes_private_complete_hash_bound_evidence(self) -> None:
        raw = b'{"version":"aneb-server/0.8.0","execution_capabilities":{"ok":true}}'
        peer = b"peer-certificate-der"
        response_headers = (("Content-Type", "application/json"), ("X-Aneb-Proto", "h2"))
        fetcher = RecordingFetcher(UpstreamResponse(200, response_headers, raw, peer))
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            served = NegativeProxySession(
                ProxyConfig.for_test(evidence_directory=evidence),
                fetcher=fetcher,
            ).process(
                method="GET",
                path="/api/v1/serverinfo",
                headers={
                    "X-Aneb-Run-Id": [RUN_ID],
                    "X-Aneb-Audit-Role": ["capability"],
                },
            )

            self.assertEqual(
                {
                    "filtered-serverinfo.json",
                    "peer-certificate.sha256",
                    "proxy-receipt.json",
                    "request-ledger.json",
                    "upstream-serverinfo.headers.json",
                    "upstream-serverinfo.raw",
                },
                {path.name for path in evidence.iterdir()},
            )
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            ledger = json.loads((evidence / "request-ledger.json").read_bytes())
            self.assertEqual("aneb-token-serverinfo-negative-proxy-receipt", receipt["schema"])
            self.assertEqual("1.0.0", receipt["schema_version"])
            self.assertEqual("pass", receipt["status"])
            self.assertEqual("ok", receipt["reason_code"])
            self.assertEqual(RUN_ID, receipt["run_id"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt["upstream_body_sha256"])
            self.assertEqual(hashlib.sha256(served).hexdigest(), receipt["filtered_body_sha256"])
            self.assertEqual(hashlib.sha256(peer).hexdigest(), receipt["peer_certificate_sha256"])
            self.assertEqual("a" * 64, receipt["ca_file_sha256"])
            self.assertEqual(
                "upstream_fetch_and_filter_only",
                receipt["evidence_scope"],
            )
            self.assertFalse(receipt["client_delivery_proven"])
            self.assertEqual(
                {"accepted_requests": 1, "forbidden_requests": 0, "upstream_requests": 1},
                ledger["counts"],
            )
            self.assertEqual(
                [[name, value] for name, value in response_headers],
                json.loads((evidence / "upstream-serverinfo.headers.json").read_bytes()),
            )
            self.assertEqual(hashlib.sha256(peer).hexdigest() + "\n", (evidence / "peer-certificate.sha256").read_text(encoding="ascii"))
            self.assertFalse(any(path.name.endswith(".tmp") for path in evidence.iterdir()))
            if os.name != "nt":
                self.assertEqual(0o700, stat.S_IMODE(evidence.stat().st_mode))
                for path in evidence.iterdir():
                    self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))

    def test_failed_request_writes_failure_receipt_without_upstream_evidence(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(200, (), b'{"execution_capabilities":{"ok":true}}', b"peer"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            session = NegativeProxySession(
                ProxyConfig.for_test(evidence_directory=evidence),
                fetcher=fetcher,
            )
            with self.assertRaises(NegativeProxyFailure) as raised:
                session.process(
                    method="GET",
                    path="/api/v1/echo",
                    headers={
                        "X-Aneb-Run-Id": [RUN_ID],
                        "X-Aneb-Audit-Role": ["capability"],
                    },
                )

            self.assertEqual("request_path_invalid", raised.exception.reason_code)
            self.assertEqual([], fetcher.calls)
            self.assertEqual(
                {"proxy-receipt.json", "request-ledger.json"},
                {path.name for path in evidence.iterdir()},
            )
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            ledger = json.loads((evidence / "request-ledger.json").read_bytes())
            self.assertEqual("fail", receipt["status"])
            self.assertEqual("request_path_invalid", receipt["reason_code"])
            self.assertIsNone(receipt["run_id"])
            self.assertEqual(
                {"accepted_requests": 0, "forbidden_requests": 1, "upstream_requests": 0},
                ledger["counts"],
            )

    def test_existing_evidence_directory_is_rejected_before_upstream(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(200, (), b'{"execution_capabilities":{"ok":true}}', b"peer"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            evidence.mkdir()
            session = NegativeProxySession(
                ProxyConfig.for_test(evidence_directory=evidence),
                fetcher=fetcher,
            )

            with self.assertRaises(NegativeProxyFailure) as raised:
                session.process(
                    method="GET",
                    path="/api/v1/serverinfo",
                    headers={
                        "X-Aneb-Run-Id": [RUN_ID],
                        "X-Aneb-Audit-Role": ["capability"],
                    },
                )

            self.assertEqual("evidence_directory_exists", raised.exception.reason_code)
            self.assertEqual([], fetcher.calls)

    def test_http_server_binds_loopback_serves_one_request_and_shuts_down(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(
                200,
                (("Content-Type", "application/json"),),
                b'{"version":"0.8.0","execution_capabilities":{"ok":true}}',
                b"peer",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            config = ProxyConfig.for_test(evidence_directory=Path(temporary) / "evidence")
            ready: queue.Queue[tuple[str, int]] = queue.Queue()
            completed: queue.Queue[object] = queue.Queue()

            def run() -> None:
                try:
                    completed.put(
                        serve_once(config, fetcher=fetcher, ready_callback=ready.put),
                    )
                except BaseException as error:  # surfaced in the main test thread
                    completed.put(error)

            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            host, port = ready.get(timeout=2)
            self.assertEqual("127.0.0.1", host)
            connection = http.client.HTTPConnection(host, port, timeout=2)
            connection.request(
                "GET",
                "/api/v1/serverinfo",
                headers={
                    "X-Aneb-Run-Id": RUN_ID,
                    "X-Aneb-Audit-Role": "capability",
                },
            )
            response = connection.getresponse()
            body = response.read()
            connection.close()
            thread.join(timeout=2)

            self.assertFalse(thread.is_alive())
            self.assertEqual(200, response.status)
            self.assertEqual({"version": "0.8.0"}, json.loads(body))
            result = completed.get_nowait()
            if isinstance(result, BaseException):
                raise result
            self.assertEqual("pass", result.status)
            self.assertEqual(RUN_ID, result.run_id)

    def test_http_get_body_is_rejected_without_upstream_forwarding(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(
                200,
                (),
                b'{"execution_capabilities":{"ok":true}}',
                b"peer",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            config = ProxyConfig.for_test(evidence_directory=evidence)
            ready: queue.Queue[tuple[str, int]] = queue.Queue()
            completed: queue.Queue[object] = queue.Queue()

            def run() -> None:
                try:
                    completed.put(
                        serve_once(config, fetcher=fetcher, ready_callback=ready.put),
                    )
                except BaseException as error:
                    completed.put(error)

            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            host, port = ready.get(timeout=2)
            connection = http.client.HTTPConnection(host, port, timeout=2)
            connection.request(
                "GET",
                "/api/v1/serverinfo",
                body=b"x",
                headers={
                    "X-Aneb-Run-Id": RUN_ID,
                    "X-Aneb-Audit-Role": "capability",
                },
            )
            response = connection.getresponse()
            body = json.loads(response.read())
            connection.close()
            thread.join(timeout=2)

            self.assertEqual(400, response.status)
            self.assertEqual("request_body_forbidden", body["reason_code"])
            self.assertEqual([], fetcher.calls)
            result = completed.get_nowait()
            if isinstance(result, BaseException):
                raise result
            self.assertEqual("fail", result.status)
            self.assertEqual("request_body_forbidden", result.reason_code)
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            self.assertEqual("request_body_forbidden", receipt["reason_code"])

    def test_http_wait_timeout_is_bounded_and_writes_failure_receipt(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(
                200,
                (),
                b'{"execution_capabilities":{"ok":true}}',
                b"peer",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            config = replace(
                ProxyConfig.for_test(evidence_directory=evidence),
                request_wait_timeout_seconds=0.05,
            )
            ready: queue.Queue[tuple[str, int]] = queue.Queue()

            result = serve_once(config, fetcher=fetcher, ready_callback=ready.put)

            host, port = ready.get_nowait()
            self.assertEqual("127.0.0.1", host)
            self.assertGreater(port, 0)
            self.assertEqual("fail", result.status)
            self.assertEqual("request_wait_timeout", result.reason_code)
            self.assertEqual([], fetcher.calls)
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            ledger = json.loads((evidence / "request-ledger.json").read_bytes())
            self.assertEqual("request_wait_timeout", receipt["reason_code"])
            self.assertEqual(
                {"accepted_requests": 0, "forbidden_requests": 0, "upstream_requests": 0},
                ledger["counts"],
            )

    def test_https_fetcher_verifies_ca_and_hostname_and_forwards_only_audit_headers(self) -> None:
        class FakeContext:
            check_hostname = False
            verify_mode = ssl.CERT_NONE
            minimum_version = ssl.TLSVersion.MINIMUM_SUPPORTED

        class FakeSocket:
            def getpeercert(self, *, binary_form: bool) -> bytes:
                self.binary_form = binary_form
                return b"verified-peer-der"

            def settimeout(self, timeout: float) -> None:
                self.timeout = timeout

        class FakeResponse:
            status = 200

            def __init__(self) -> None:
                self._chunks = [
                    b'{"execution_capabilities":{"ok":true}}',
                    b"",
                ]

            def getheaders(self) -> list[tuple[str, str]]:
                return [("Content-Type", "application/json")]

            def read1(self, amount: int) -> bytes:
                self.amount = amount
                return self._chunks.pop(0)

        class FakeConnection:
            instance: "FakeConnection | None" = None

            def __init__(
                self,
                host: str,
                port: int,
                *,
                timeout: float,
                context: object,
            ) -> None:
                type(self).instance = self
                self.host = host
                self.port = port
                self.timeout = timeout
                self.context = context
                self.sock = FakeSocket()
                self.request_headers: list[tuple[str, str]] = []
                self.closed = False

            def putrequest(
                self,
                method: str,
                path: str,
                *,
                skip_accept_encoding: bool,
            ) -> None:
                self.request = (method, path, skip_accept_encoding)

            def putheader(self, name: str, value: str) -> None:
                self.request_headers.append((name, value))

            def endheaders(self) -> None:
                self.ended = True

            def getresponse(self) -> FakeResponse:
                return FakeResponse()

            def close(self) -> None:
                self.closed = True

        context = FakeContext()
        with tempfile.TemporaryDirectory() as temporary:
            ca_file = Path(temporary) / "ca.pem"
            ca_file.write_text("test-only-ca", encoding="ascii")
            config = replace(
                ProxyConfig.for_test(evidence_directory=Path(temporary) / "evidence"),
                ca_file=ca_file,
                upstream_timeout_seconds=2.0,
            )
            with (
                patch(
                    "scripts.token_serverinfo_negative_proxy.ssl.create_default_context",
                    return_value=context,
                ) as create_context,
                patch(
                    "scripts.token_serverinfo_negative_proxy.http.client.HTTPSConnection",
                    FakeConnection,
                ),
            ):
                response = HttpsUpstreamFetcher(config).fetch(
                    {
                        "X-Aneb-Run-Id": RUN_ID,
                        "X-Aneb-Audit-Role": "capability",
                    },
                )

        create_context.assert_called_once_with(
            ssl.Purpose.SERVER_AUTH,
            cafile=str(ca_file),
        )
        self.assertTrue(context.check_hostname)
        self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
        self.assertEqual(ssl.TLSVersion.TLSv1_2, context.minimum_version)
        connection = FakeConnection.instance
        self.assertIsNotNone(connection)
        assert connection is not None
        self.assertEqual("127.0.0.1", connection.host)
        self.assertEqual(8443, connection.port)
        self.assertEqual(("GET", "/api/v1/serverinfo", True), connection.request)
        self.assertEqual(
            [
                ("X-Aneb-Run-Id", RUN_ID),
                ("X-Aneb-Audit-Role", "capability"),
            ],
            connection.request_headers,
        )
        self.assertTrue(connection.ended)
        self.assertTrue(connection.closed)
        self.assertEqual(200, response.status)
        self.assertEqual(b'{"execution_capabilities":{"ok":true}}', response.body)
        self.assertEqual(b"verified-peer-der", response.peer_certificate_der)

    def test_https_fetcher_maps_transport_failures_to_stable_reason_codes(self) -> None:
        cases = (
            (ssl.SSLCertVerificationError(1, "bad certificate"), "upstream_tls_verification_failed"),
            (socket.timeout("timed out"), "upstream_timeout"),
            (http.client.HTTPException("bad response"), "upstream_protocol_invalid"),
            (OSError("connection failed"), "upstream_request_failed"),
        )

        class FakeContext:
            check_hostname = False
            verify_mode = ssl.CERT_NONE
            minimum_version = ssl.TLSVersion.MINIMUM_SUPPORTED

        for transport_error, expected_reason in cases:
            with self.subTest(expected_reason=expected_reason):
                class FailingConnection:
                    instance: "FailingConnection | None" = None

                    def __init__(self, *args: object, **kwargs: object) -> None:
                        del args, kwargs
                        type(self).instance = self
                        self.closed = False
                        self.sock = self

                    def getpeercert(self, *, binary_form: bool) -> bytes:
                        self.binary_form = binary_form
                        return b"verified-peer-der"

                    def putrequest(self, *args: object, **kwargs: object) -> None:
                        del args, kwargs

                    def putheader(self, *args: object, **kwargs: object) -> None:
                        del args, kwargs

                    def endheaders(self) -> None:
                        pass

                    def getresponse(self) -> object:
                        raise transport_error

                    def close(self) -> None:
                        self.closed = True

                with tempfile.TemporaryDirectory() as temporary:
                    ca_file = Path(temporary) / "ca.pem"
                    ca_file.write_text("test-only-ca", encoding="ascii")
                    config = replace(
                        ProxyConfig.for_test(evidence_directory=Path(temporary) / "evidence"),
                        ca_file=ca_file,
                    )
                    with (
                        patch(
                            "scripts.token_serverinfo_negative_proxy.ssl.create_default_context",
                            return_value=FakeContext(),
                        ),
                        patch(
                            "scripts.token_serverinfo_negative_proxy.http.client.HTTPSConnection",
                            FailingConnection,
                        ),
                    ):
                        with self.assertRaises(NegativeProxyFailure) as raised:
                            HttpsUpstreamFetcher(config).fetch(
                                {
                                    "X-Aneb-Run-Id": RUN_ID,
                                    "X-Aneb-Audit-Role": "capability",
                                },
                            )
                self.assertEqual(expected_reason, raised.exception.reason_code)
                self.assertIsNotNone(FailingConnection.instance)
                assert FailingConnection.instance is not None
                self.assertTrue(FailingConnection.instance.closed)

    def test_https_fetcher_rejects_unsafe_upstream_or_ca_before_connection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ca_file = root / "ca.pem"
            ca_file.write_text("test-only-ca", encoding="ascii")
            base = replace(
                ProxyConfig.for_test(evidence_directory=root / "evidence"),
                ca_file=ca_file,
            )
            cases = (
                (replace(base, upstream_url="http://127.0.0.1:8443/api/v1/serverinfo"), "upstream_url_invalid"),
                (replace(base, upstream_url="https://127.0.0.1:8443/api/v1/serverinfo?x=1"), "upstream_url_invalid"),
                (replace(base, upstream_url="https://example.test:8443/api/v1/serverinfo"), "upstream_host_not_ip_literal"),
                (replace(base, ca_file=root / "missing.pem"), "upstream_ca_invalid"),
            )
            for config, expected_reason in cases:
                with self.subTest(expected_reason=expected_reason):
                    with self.assertRaises(NegativeProxyFailure) as raised:
                        HttpsUpstreamFetcher(config)
                    self.assertEqual(expected_reason, raised.exception.reason_code)

    def test_ca_file_is_size_bounded_and_successfully_hash_bound(self) -> None:
        class FakeContext:
            check_hostname = False
            verify_mode = ssl.CERT_NONE
            minimum_version = ssl.TLSVersion.MINIMUM_SUPPORTED

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ca_file = root / "ca.pem"
            ca_bytes = b"test-only-ca-bundle"
            ca_file.write_bytes(ca_bytes)
            config = replace(
                ProxyConfig.for_test(evidence_directory=root / "evidence"),
                ca_file=ca_file,
                max_ca_bytes=len(ca_bytes),
            )
            with patch(
                "scripts.token_serverinfo_negative_proxy.ssl.create_default_context",
                return_value=FakeContext(),
            ) as create_context:
                fetcher = HttpsUpstreamFetcher(config)

            self.assertEqual(hashlib.sha256(ca_bytes).hexdigest(), fetcher.ca_file_sha256)
            create_context.assert_called_once_with(
                ssl.Purpose.SERVER_AUTH,
                cafile=str(ca_file),
            )

            oversized = replace(config, max_ca_bytes=len(ca_bytes) - 1)
            with patch(
                "scripts.token_serverinfo_negative_proxy.ssl.create_default_context",
                return_value=FakeContext(),
            ) as forbidden_loader:
                with self.assertRaises(NegativeProxyFailure) as raised:
                    HttpsUpstreamFetcher(oversized)
            self.assertEqual("upstream_ca_too_large", raised.exception.reason_code)
            forbidden_loader.assert_not_called()

    def test_ca_ancestor_reparse_point_is_rejected_before_loading(self) -> None:
        class FakeContext:
            check_hostname = False
            verify_mode = ssl.CERT_NONE
            minimum_version = ssl.TLSVersion.MINIMUM_SUPPORTED

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            real_directory = root / "real"
            real_directory.mkdir()
            (real_directory / "ca.pem").write_bytes(b"test-only-ca")
            alias = root / "alias"
            try:
                alias.symlink_to(real_directory, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlink unavailable: {error}")
            config = replace(
                ProxyConfig.for_test(evidence_directory=root / "evidence"),
                ca_file=alias / "ca.pem",
            )

            with patch(
                "scripts.token_serverinfo_negative_proxy.ssl.create_default_context",
                return_value=FakeContext(),
            ) as forbidden_loader:
                with self.assertRaises(NegativeProxyFailure) as raised:
                    HttpsUpstreamFetcher(config)

            self.assertEqual("upstream_ca_reparse_point", raised.exception.reason_code)
            forbidden_loader.assert_not_called()

    def test_ca_content_drift_during_tls_context_load_is_rejected(self) -> None:
        class FakeContext:
            check_hostname = False
            verify_mode = ssl.CERT_NONE
            minimum_version = ssl.TLSVersion.MINIMUM_SUPPORTED

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ca_file = root / "ca.pem"
            ca_file.write_bytes(b"before-load")
            config = replace(
                ProxyConfig.for_test(evidence_directory=root / "evidence"),
                ca_file=ca_file,
            )

            def mutate_ca(*args: object, **kwargs: object) -> FakeContext:
                del args, kwargs
                ca_file.write_bytes(b"after-load")
                return FakeContext()

            with patch(
                "scripts.token_serverinfo_negative_proxy.ssl.create_default_context",
                side_effect=mutate_ca,
            ):
                with self.assertRaises(NegativeProxyFailure) as raised:
                    HttpsUpstreamFetcher(config)

            self.assertEqual(
                "upstream_ca_changed_during_load",
                raised.exception.reason_code,
            )

    def test_total_success_evidence_output_is_bounded_before_any_success_file(self) -> None:
        raw = json.dumps(
            {
                "execution_capabilities": {"ok": True},
                "padding": "x" * 3000,
            },
            separators=(",", ":"),
        ).encode("ascii")
        fetcher = RecordingFetcher(UpstreamResponse(200, (), raw, b"peer"))
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            config = replace(
                ProxyConfig.for_test(evidence_directory=evidence),
                max_output_bytes=4096,
            )

            with self.assertRaises(NegativeProxyFailure) as raised:
                NegativeProxySession(config, fetcher=fetcher).process(
                    method="GET",
                    path="/api/v1/serverinfo",
                    headers={
                        "X-Aneb-Run-Id": [RUN_ID],
                        "X-Aneb-Audit-Role": ["capability"],
                    },
                )

            self.assertEqual("evidence_output_too_large", raised.exception.reason_code)
            self.assertEqual(
                {"proxy-receipt.json", "request-ledger.json"},
                {path.name for path in evidence.iterdir()},
            )
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            self.assertEqual("evidence_output_too_large", receipt["reason_code"])

    def test_numeric_configuration_is_hard_bounded_before_evidence_or_upstream(self) -> None:
        cases = (
            {"max_body_bytes": 0},
            {"max_body_bytes": 4 * 1024 * 1024 + 1},
            {"max_header_bytes": 0},
            {"max_peer_certificate_bytes": 0},
            {"max_output_bytes": 0},
            {"max_output_bytes": 12 * 1024 * 1024 + 1},
            {"listen_port": -1},
            {"listen_port": 65536},
            {"request_wait_timeout_seconds": 0},
            {"request_wait_timeout_seconds": 301},
            {"upstream_timeout_seconds": 0},
            {"upstream_timeout_seconds": 61},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides):
                fetcher = RecordingFetcher(
                    UpstreamResponse(
                        200,
                        (),
                        b'{"execution_capabilities":{"ok":true}}',
                        b"peer",
                    ),
                )
                with tempfile.TemporaryDirectory() as temporary:
                    evidence = Path(temporary) / "evidence"
                    config = replace(
                        ProxyConfig.for_test(evidence_directory=evidence),
                        **overrides,
                    )
                    with self.assertRaises(NegativeProxyFailure) as raised:
                        NegativeProxySession(config, fetcher=fetcher)
                    self.assertEqual("config_invalid", raised.exception.reason_code)
                    self.assertFalse(evidence.exists())
                    self.assertEqual([], fetcher.calls)

    def test_cli_success_emits_bounded_machine_json_and_has_no_bind_host_option(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(
                200,
                (),
                b'{"version":"0.8.0","execution_capabilities":{"ok":true}}',
                b"peer",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ca_file = root / "ca.pem"
            ca_file.write_text("unused-with-injected-fetcher", encoding="ascii")
            evidence = root / "evidence"
            output = io.StringIO()
            errors = io.StringIO()
            ready: queue.Queue[tuple[str, int]] = queue.Queue()
            completed: queue.Queue[object] = queue.Queue()
            arguments = [
                "--upstream-url",
                "https://127.0.0.1:8443/api/v1/serverinfo",
                "--ca-file",
                str(ca_file),
                "--evidence-dir",
                str(evidence),
                "--request-timeout-seconds",
                "2",
            ]

            def run() -> None:
                try:
                    completed.put(
                        main(
                            arguments,
                            fetcher=fetcher,
                            stdout=output,
                            stderr=errors,
                            ready_callback=ready.put,
                        ),
                    )
                except BaseException as error:
                    completed.put(error)

            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            host, port = ready.get(timeout=2)
            connection = http.client.HTTPConnection(host, port, timeout=2)
            connection.request(
                "GET",
                "/api/v1/serverinfo",
                headers={
                    "X-Aneb-Run-Id": RUN_ID,
                    "X-Aneb-Audit-Role": "capability",
                },
            )
            response = connection.getresponse()
            response.read()
            connection.close()
            thread.join(timeout=2)

            result = completed.get_nowait()
            if isinstance(result, BaseException):
                raise result
            self.assertEqual(0, result)
            self.assertEqual("", errors.getvalue())
            lines = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual(2, len(lines))
            self.assertEqual("ready", lines[0]["status"])
            self.assertEqual("127.0.0.1", lines[0]["listen_host"])
            self.assertEqual("pass", lines[1]["status"])
            self.assertEqual(RUN_ID, lines[1]["run_id"])
            self.assertLess(len(output.getvalue().encode("utf-8")), 1024)

            invalid_output = io.StringIO()
            invalid_errors = io.StringIO()
            invalid_result = main(
                [*arguments, "--bind-host", "0.0.0.0"],
                fetcher=fetcher,
                stdout=invalid_output,
                stderr=invalid_errors,
            )
            self.assertEqual(2, invalid_result)
            self.assertEqual("", invalid_output.getvalue())
            self.assertEqual(
                "cli_arguments_invalid",
                json.loads(invalid_errors.getvalue())["reason_code"],
            )

    def test_cli_rejects_out_of_bounds_configuration_with_stable_reason(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ca_file = root / "ca.pem"
            ca_file.write_text("unused-with-injected-fetcher", encoding="ascii")
            output = io.StringIO()
            errors = io.StringIO()
            result = main(
                [
                    "--upstream-url",
                    "https://127.0.0.1:8443/api/v1/serverinfo",
                    "--ca-file",
                    str(ca_file),
                    "--evidence-dir",
                    str(root / "evidence"),
                    "--listen-port",
                    "65536",
                ],
                fetcher=RecordingFetcher(
                    UpstreamResponse(
                        200,
                        (),
                        b'{"execution_capabilities":{"ok":true}}',
                        b"peer",
                    ),
                ),
                stdout=output,
                stderr=errors,
            )

            self.assertEqual(2, result)
            self.assertEqual("", output.getvalue())
            self.assertEqual("config_invalid", json.loads(errors.getvalue())["reason_code"])
            self.assertFalse((root / "evidence").exists())

    def test_arbitrary_http_method_is_stably_rejected_and_server_exits(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(
                200,
                (),
                b'{"execution_capabilities":{"ok":true}}',
                b"peer",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            config = ProxyConfig.for_test(evidence_directory=evidence)
            ready: queue.Queue[tuple[str, int]] = queue.Queue()
            completed: queue.Queue[object] = queue.Queue()

            def run() -> None:
                try:
                    completed.put(
                        serve_once(config, fetcher=fetcher, ready_callback=ready.put),
                    )
                except BaseException as error:
                    completed.put(error)

            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            host, port = ready.get(timeout=2)
            connection = http.client.HTTPConnection(host, port, timeout=2)
            connection.request(
                "BREW",
                "/api/v1/serverinfo",
                headers={
                    "X-Aneb-Run-Id": RUN_ID,
                    "X-Aneb-Audit-Role": "capability",
                },
            )
            response = connection.getresponse()
            body = json.loads(response.read())
            connection.close()
            thread.join(timeout=2)

            self.assertFalse(thread.is_alive())
            self.assertEqual(400, response.status)
            self.assertEqual("request_method_invalid", body["reason_code"])
            self.assertEqual([], fetcher.calls)
            result = completed.get_nowait()
            if isinstance(result, BaseException):
                raise result
            self.assertEqual("request_method_invalid", result.reason_code)
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            self.assertEqual("request_method_invalid", receipt["reason_code"])

    def test_connected_slow_request_cannot_hold_single_use_server_open(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(
                200,
                (),
                b'{"execution_capabilities":{"ok":true}}',
                b"peer",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            config = replace(
                ProxyConfig.for_test(evidence_directory=evidence),
                request_wait_timeout_seconds=0.05,
            )
            ready: queue.Queue[tuple[str, int]] = queue.Queue()
            completed: queue.Queue[object] = queue.Queue()

            def run() -> None:
                try:
                    completed.put(
                        serve_once(config, fetcher=fetcher, ready_callback=ready.put),
                    )
                except BaseException as error:
                    completed.put(error)

            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            address = ready.get(timeout=2)
            client = socket.create_connection(address, timeout=2)
            client.sendall(b"GET /api/v1/serverinfo")
            thread.join(timeout=0.3)
            exited_within_bound = not thread.is_alive()
            client.close()
            thread.join(timeout=2)

            self.assertTrue(exited_within_bound)
            self.assertEqual([], fetcher.calls)
            result = completed.get_nowait()
            if isinstance(result, BaseException):
                raise result
            self.assertEqual("fail", result.status)
            self.assertEqual("request_wait_timeout", result.reason_code)
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            self.assertEqual("request_wait_timeout", receipt["reason_code"])

    def test_unexpected_fetcher_failure_is_fail_closed_with_receipt(self) -> None:
        class ExplodingFetcher:
            def fetch(self, headers: dict[str, str]) -> UpstreamResponse:
                del headers
                raise RuntimeError("test-only unexpected fetcher failure")

        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            session = NegativeProxySession(
                ProxyConfig.for_test(evidence_directory=evidence),
                fetcher=ExplodingFetcher(),
            )

            with self.assertRaises(NegativeProxyFailure) as raised:
                session.process(
                    method="GET",
                    path="/api/v1/serverinfo",
                    headers={
                        "X-Aneb-Run-Id": [RUN_ID],
                        "X-Aneb-Audit-Role": ["capability"],
                    },
                )

            self.assertEqual("upstream_request_failed", raised.exception.reason_code)
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            ledger = json.loads((evidence / "request-ledger.json").read_bytes())
            self.assertEqual("upstream_request_failed", receipt["reason_code"])
            self.assertEqual(1, ledger["counts"]["upstream_requests"])

    def test_http_parser_header_overflow_is_bounded_and_audited(self) -> None:
        fetcher = RecordingFetcher(
            UpstreamResponse(
                200,
                (),
                b'{"execution_capabilities":{"ok":true}}',
                b"peer",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary) / "evidence"
            config = ProxyConfig.for_test(evidence_directory=evidence)
            ready: queue.Queue[tuple[str, int]] = queue.Queue()
            completed: queue.Queue[object] = queue.Queue()

            def run() -> None:
                try:
                    completed.put(
                        serve_once(config, fetcher=fetcher, ready_callback=ready.put),
                    )
                except BaseException as error:
                    completed.put(error)

            thread = threading.Thread(target=run, daemon=True)
            thread.start()
            client = socket.create_connection(ready.get(timeout=2), timeout=2)
            client.sendall(
                b"GET /api/v1/serverinfo HTTP/1.1\r\n"
                + b"X-Pad: "
                + b"x" * 70_000
                + b"\r\n\r\n",
            )
            response = bytearray()
            while True:
                chunk = client.recv(4096)
                if not chunk:
                    break
                response.extend(chunk)
            client.close()
            thread.join(timeout=2)

            self.assertFalse(thread.is_alive())
            response_body = bytes(response).partition(b"\r\n\r\n")[2]
            self.assertEqual("request_headers_too_large", json.loads(response_body)["reason_code"])
            self.assertEqual([], fetcher.calls)
            result = completed.get_nowait()
            if isinstance(result, BaseException):
                raise result
            self.assertEqual("request_headers_too_large", result.reason_code)
            receipt = json.loads((evidence / "proxy-receipt.json").read_bytes())
            self.assertEqual("request_headers_too_large", receipt["reason_code"])


if __name__ == "__main__":
    unittest.main()
