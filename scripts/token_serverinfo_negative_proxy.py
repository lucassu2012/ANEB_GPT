#!/usr/bin/env python3
"""Single-use Debug-only serverinfo filter for ANEB contract-negative evidence."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import socket
import ssl
import stat
import sys
from typing import Any, Callable, NoReturn, Protocol, Sequence, TextIO
from urllib.parse import SplitResult, urlsplit


RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
MAX_BODY_BYTES = 4 * 1024 * 1024
MAX_CA_BYTES = 4 * 1024 * 1024
MAX_HEADER_BYTES = 256 * 1024
MAX_PEER_CERTIFICATE_BYTES = 256 * 1024
MAX_OUTPUT_BYTES = 12 * 1024 * 1024


class NegativeProxyFailure(ValueError):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class _DuplicateJsonKey(ValueError):
    pass


class _NonstandardJsonConstant(ValueError):
    pass


@dataclass(frozen=True)
class UpstreamResponse:
    status: int
    headers: tuple[tuple[str, str], ...]
    body: bytes
    peer_certificate_der: bytes


class UpstreamFetcher(Protocol):
    def fetch(self, headers: dict[str, str]) -> UpstreamResponse: ...


@dataclass(frozen=True)
class ProxyConfig:
    upstream_url: str
    ca_file: Path
    evidence_directory: Path
    delivery_receipt_file: Path | None = None
    max_body_bytes: int = 1024 * 1024
    max_ca_bytes: int = 1024 * 1024
    max_header_bytes: int = 64 * 1024
    max_peer_certificate_bytes: int = 64 * 1024
    max_output_bytes: int = 3 * 1024 * 1024
    listen_port: int = 0
    request_wait_timeout_seconds: float = 30.0
    upstream_timeout_seconds: float = 15.0

    @classmethod
    def for_test(cls, *, evidence_directory: Path) -> "ProxyConfig":
        return cls(
            upstream_url="https://127.0.0.1:8443/api/v1/serverinfo",
            ca_file=evidence_directory.parent / "unused-test-ca.pem",
            evidence_directory=evidence_directory,
        )


@dataclass(frozen=True)
class ProxyServeResult:
    status: str
    reason_code: str
    run_id: str | None
    listen_host: str
    listen_port: int


def _validate_config(config: ProxyConfig) -> None:
    integer_bounds = (
        (config.max_body_bytes, 1, MAX_BODY_BYTES),
        (config.max_ca_bytes, 1, MAX_CA_BYTES),
        (config.max_header_bytes, 1, MAX_HEADER_BYTES),
        (config.max_peer_certificate_bytes, 1, MAX_PEER_CERTIFICATE_BYTES),
        (config.max_output_bytes, 4096, MAX_OUTPUT_BYTES),
        (config.listen_port, 0, 65535),
    )
    if any(
        isinstance(value, bool)
        or not isinstance(value, int)
        or value < minimum
        or value > maximum
        for value, minimum, maximum in integer_bounds
    ):
        raise NegativeProxyFailure("config_invalid")
    time_bounds = (
        (config.request_wait_timeout_seconds, 0.01, 300.0),
        (config.upstream_timeout_seconds, 0.01, 60.0),
    )
    if any(
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < minimum
        or value > maximum
        for value, minimum, maximum in time_bounds
    ):
        raise NegativeProxyFailure("config_invalid")
    if (
        not isinstance(config.upstream_url, str)
        or not config.upstream_url
        or len(config.upstream_url.encode("utf-8")) > 2048
        or not isinstance(config.ca_file, Path)
        or not isinstance(config.evidence_directory, Path)
        or (
            config.delivery_receipt_file is not None
            and (
                not isinstance(config.delivery_receipt_file, Path)
                or config.delivery_receipt_file.name
                != "negative-proxy-delivery-receipt.json"
                or config.delivery_receipt_file.parent
                != config.evidence_directory.parent
            )
        )
    ):
        raise NegativeProxyFailure("config_invalid")


def _validated_upstream_url(config: ProxyConfig) -> SplitResult:
    try:
        parsed = urlsplit(config.upstream_url)
        port = parsed.port
    except (TypeError, ValueError):
        raise NegativeProxyFailure("upstream_url_invalid") from None
    if (
        parsed.scheme != "https"
        or parsed.username is not None
        or parsed.password is not None
        or not parsed.hostname
        or port is None
        or parsed.path != "/api/v1/serverinfo"
        or parsed.query
        or parsed.fragment
    ):
        raise NegativeProxyFailure("upstream_url_invalid")
    try:
        ipaddress.ip_address(parsed.hostname)
    except ValueError:
        raise NegativeProxyFailure("upstream_host_not_ip_literal") from None
    return parsed


def _is_reparse_point(file_status: os.stat_result) -> bool:
    reparse_attribute = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    file_attributes = getattr(file_status, "st_file_attributes", 0)
    return stat.S_ISLNK(file_status.st_mode) or bool(
        file_attributes & reparse_attribute,
    )


def _validate_ca_path_chain(ca_path: Path) -> os.stat_result:
    target_status: os.stat_result | None = None
    for index, candidate in enumerate((ca_path, *ca_path.parents)):
        try:
            candidate_status = os.lstat(candidate)
        except OSError:
            raise NegativeProxyFailure("upstream_ca_invalid") from None
        if _is_reparse_point(candidate_status):
            raise NegativeProxyFailure("upstream_ca_reparse_point")
        if index == 0:
            target_status = candidate_status
        elif not stat.S_ISDIR(candidate_status.st_mode):
            raise NegativeProxyFailure("upstream_ca_invalid")
    if target_status is None or not stat.S_ISREG(target_status.st_mode):
        raise NegativeProxyFailure("upstream_ca_invalid")
    return target_status


def _ca_file_sha256(ca_path: Path, max_ca_bytes: int) -> str:
    path_status = _validate_ca_path_chain(ca_path)
    if path_status.st_size <= 0:
        raise NegativeProxyFailure("upstream_ca_invalid")
    if path_status.st_size > max_ca_bytes:
        raise NegativeProxyFailure("upstream_ca_too_large")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(ca_path, flags)
    except OSError:
        raise NegativeProxyFailure("upstream_ca_invalid") from None
    try:
        opened_before = os.fstat(descriptor)
        if _is_reparse_point(opened_before) or not stat.S_ISREG(opened_before.st_mode):
            raise NegativeProxyFailure("upstream_ca_invalid")
        if opened_before.st_size <= 0:
            raise NegativeProxyFailure("upstream_ca_invalid")
        if opened_before.st_size > max_ca_bytes:
            raise NegativeProxyFailure("upstream_ca_too_large")
        digest = hashlib.sha256()
        total = 0
        while True:
            amount = min(64 * 1024, max_ca_bytes + 1 - total)
            if amount <= 0:
                raise NegativeProxyFailure("upstream_ca_too_large")
            chunk = os.read(descriptor, amount)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
            if total > max_ca_bytes:
                raise NegativeProxyFailure("upstream_ca_too_large")
        opened_after = os.fstat(descriptor)
        before_fingerprint = (
            opened_before.st_dev,
            opened_before.st_ino,
            opened_before.st_size,
            opened_before.st_mtime_ns,
        )
        after_fingerprint = (
            opened_after.st_dev,
            opened_after.st_ino,
            opened_after.st_size,
            opened_after.st_mtime_ns,
        )
        if before_fingerprint != after_fingerprint or total != opened_after.st_size:
            raise NegativeProxyFailure("upstream_ca_changed_during_load")
    finally:
        os.close(descriptor)
    _validate_ca_path_chain(ca_path)
    return digest.hexdigest()


class HttpsUpstreamFetcher:
    """Strict HTTPS transport for the single permitted upstream request."""

    def __init__(self, config: ProxyConfig) -> None:
        _validate_config(config)
        self.config = config
        self._parsed = _validated_upstream_url(config)
        self._ca_path = Path(os.path.abspath(config.ca_file))
        ca_sha256_before = _ca_file_sha256(self._ca_path, config.max_ca_bytes)
        try:
            context = ssl.create_default_context(
                ssl.Purpose.SERVER_AUTH,
                cafile=str(self._ca_path),
            )
        except (OSError, ssl.SSLError):
            raise NegativeProxyFailure("upstream_ca_invalid") from None
        ca_sha256_after = _ca_file_sha256(self._ca_path, config.max_ca_bytes)
        if ca_sha256_before != ca_sha256_after:
            raise NegativeProxyFailure("upstream_ca_changed_during_load")
        self.ca_file_sha256 = ca_sha256_before
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        self._context = context

    def fetch(self, headers: dict[str, str]) -> UpstreamResponse:
        if (
            set(headers) != {"X-Aneb-Run-Id", "X-Aneb-Audit-Role"}
            or headers.get("X-Aneb-Audit-Role") != "capability"
            or RUN_ID_RE.fullmatch(headers.get("X-Aneb-Run-Id", "")) is None
        ):
            raise NegativeProxyFailure("forward_headers_invalid")
        connection: http.client.HTTPSConnection | None = None
        try:
            connection = http.client.HTTPSConnection(
                self._parsed.hostname,
                self._parsed.port,
                timeout=self.config.upstream_timeout_seconds,
                context=self._context,
            )
            connection.putrequest(
                "GET",
                "/api/v1/serverinfo",
                skip_accept_encoding=True,
            )
            connection.putheader("X-Aneb-Run-Id", headers["X-Aneb-Run-Id"])
            connection.putheader("X-Aneb-Audit-Role", headers["X-Aneb-Audit-Role"])
            connection.endheaders()
            if connection.sock is None:
                raise NegativeProxyFailure("upstream_peer_certificate_missing")
            peer_certificate = connection.sock.getpeercert(binary_form=True)
            if (
                not isinstance(peer_certificate, bytes)
                or not peer_certificate
                or len(peer_certificate) > self.config.max_peer_certificate_bytes
            ):
                raise NegativeProxyFailure("upstream_peer_certificate_missing")
            response = connection.getresponse()
            response_headers = tuple(response.getheaders())
            if _upstream_header_size(response_headers) > self.config.max_header_bytes:
                raise NegativeProxyFailure("upstream_headers_too_large")
            chunks: list[bytes] = []
            body_size = 0
            while True:
                amount = min(64 * 1024, self.config.max_body_bytes + 1 - body_size)
                if amount <= 0:
                    raise NegativeProxyFailure("upstream_body_too_large")
                chunk = response.read1(amount)
                if not isinstance(chunk, bytes):
                    raise NegativeProxyFailure("upstream_body_invalid")
                if not chunk:
                    break
                chunks.append(chunk)
                body_size += len(chunk)
                if body_size > self.config.max_body_bytes:
                    raise NegativeProxyFailure("upstream_body_too_large")
            return UpstreamResponse(
                status=response.status,
                headers=response_headers,
                body=b"".join(chunks),
                peer_certificate_der=peer_certificate,
            )
        except NegativeProxyFailure:
            raise
        except (ssl.SSLCertVerificationError, ssl.CertificateError, ssl.SSLError):
            raise NegativeProxyFailure("upstream_tls_verification_failed") from None
        except (socket.timeout, TimeoutError):
            raise NegativeProxyFailure("upstream_timeout") from None
        except http.client.HTTPException:
            raise NegativeProxyFailure("upstream_protocol_invalid") from None
        except OSError:
            raise NegativeProxyFailure("upstream_request_failed") from None
        finally:
            if connection is not None:
                connection.close()


def _atomic_write(path: Path, content: bytes) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _prepare_evidence_directory(path: Path) -> None:
    try:
        path.mkdir(mode=0o700)
        os.chmod(path, 0o700)
    except FileExistsError:
        raise NegativeProxyFailure("evidence_directory_exists") from None
    except OSError:
        raise NegativeProxyFailure("evidence_directory_create_failed") from None


def _write_success_evidence(
    *,
    config: ProxyConfig,
    run_id: str,
    response: UpstreamResponse,
    filtered: bytes,
    ca_file_sha256: str,
) -> None:
    peer_sha256 = _sha256(response.peer_certificate_der)
    header_document = [[name, value] for name, value in response.headers]
    ledger = {
        "schema": "aneb-token-serverinfo-negative-proxy-ledger",
        "schema_version": "1.0.0",
        "counts": {
            "accepted_requests": 1,
            "forbidden_requests": 0,
            "upstream_requests": 1,
        },
        "request": {
            "audit_role": "capability",
            "method": "GET",
            "path": "/api/v1/serverinfo",
            "run_id": run_id,
        },
        "upstream": {
            "body_bytes": len(response.body),
            "header_bytes": _upstream_header_size(response.headers),
            "status": response.status,
            "url": config.upstream_url,
        },
    }
    receipt = {
        "schema": "aneb-token-serverinfo-negative-proxy-receipt",
        "schema_version": "1.0.0",
        "status": "pass",
        "reason_code": "ok",
        "run_id": run_id,
        "upstream_url": config.upstream_url,
        "upstream_body_bytes": len(response.body),
        "upstream_body_sha256": _sha256(response.body),
        "filtered_body_bytes": len(filtered),
        "filtered_body_sha256": _sha256(filtered),
        "peer_certificate_sha256": peer_sha256,
        "ca_file_sha256": ca_file_sha256,
        "evidence_scope": "upstream_fetch_and_filter_only",
        "client_delivery_proven": False,
    }
    payloads = (
        ("upstream-serverinfo.raw", response.body),
        ("filtered-serverinfo.json", filtered),
        ("upstream-serverinfo.headers.json", _canonical_json(header_document)),
        ("peer-certificate.sha256", (peer_sha256 + "\n").encode("ascii")),
        ("request-ledger.json", _canonical_json(ledger)),
        # The final receipt is deliberately written last.
        ("proxy-receipt.json", _canonical_json(receipt)),
    )
    if sum(len(content) for _, content in payloads) > config.max_output_bytes:
        raise NegativeProxyFailure("evidence_output_too_large")
    for name, content in payloads:
        _atomic_write(config.evidence_directory / name, content)


def _write_failure_evidence(
    *,
    config: ProxyConfig,
    reason_code: str,
    run_id: str | None,
    accepted_requests: int,
    forbidden_requests: int,
    upstream_requests: int,
) -> None:
    ledger = {
        "schema": "aneb-token-serverinfo-negative-proxy-ledger",
        "schema_version": "1.0.0",
        "counts": {
            "accepted_requests": accepted_requests,
            "forbidden_requests": forbidden_requests,
            "upstream_requests": upstream_requests,
        },
    }
    receipt = {
        "schema": "aneb-token-serverinfo-negative-proxy-receipt",
        "schema_version": "1.0.0",
        "status": "fail",
        "reason_code": reason_code,
        "run_id": run_id,
        "upstream_url": config.upstream_url,
    }
    _atomic_write(
        config.evidence_directory / "request-ledger.json",
        _canonical_json(ledger),
    )
    _atomic_write(
        config.evidence_directory / "proxy-receipt.json",
        _canonical_json(receipt),
    )


def _write_delivery_receipt(
    *,
    config: ProxyConfig,
    run_id: str,
    body: bytes,
) -> None:
    path = config.delivery_receipt_file
    if path is None:
        return
    try:
        if os.path.lexists(path):
            raise NegativeProxyFailure("delivery_receipt_exists")
        receipt = {
            "schema": "aneb-serverinfo-negative-proxy-delivery-receipt",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "run_id": run_id,
            "response_status": 200,
            "response_body_bytes": len(body),
            "response_body_sha256": _sha256(body),
            "response_write_completed": True,
            "evidence_scope": "proxy_response_write_completed",
        }
        _atomic_write(path, _canonical_json(receipt))
    except NegativeProxyFailure:
        raise
    except OSError:
        raise NegativeProxyFailure("delivery_receipt_write_failed") from None


def _single_header(
    headers: dict[str, list[str]],
    name: str,
    reason_code: str,
) -> str:
    values = [
        value
        for key, candidates in headers.items()
        if key.casefold() == name.casefold()
        for value in candidates
    ]
    if len(values) != 1 or not isinstance(values[0], str):
        raise NegativeProxyFailure(reason_code)
    return values[0]


def _request_header_size(headers: dict[str, list[str]]) -> int:
    total = 0
    for key, values in headers.items():
        if not isinstance(key, str) or not isinstance(values, list):
            raise NegativeProxyFailure("request_headers_invalid")
        for value in values:
            if not isinstance(value, str) or "\r" in value or "\n" in value:
                raise NegativeProxyFailure("request_headers_invalid")
            total += len(key.encode("utf-8")) + len(value.encode("utf-8")) + 4
    return total


def _request_body_present(headers: dict[str, list[str]]) -> bool:
    transfer_encoding = [
        value
        for key, values in headers.items()
        if key.casefold() == "transfer-encoding"
        for value in values
    ]
    if transfer_encoding:
        return True
    content_length = [
        value
        for key, values in headers.items()
        if key.casefold() == "content-length"
        for value in values
    ]
    if not content_length:
        return False
    if len(content_length) != 1 or re.fullmatch(r"[0-9]{1,20}", content_length[0]) is None:
        raise NegativeProxyFailure("request_body_framing_invalid")
    return int(content_length[0]) != 0


def _upstream_header_size(headers: tuple[tuple[str, str], ...]) -> int:
    total = 0
    if not isinstance(headers, tuple):
        raise NegativeProxyFailure("upstream_headers_invalid")
    for item in headers:
        if (
            not isinstance(item, tuple)
            or len(item) != 2
            or not all(isinstance(value, str) for value in item)
            or any("\r" in value or "\n" in value for value in item)
        ):
            raise NegativeProxyFailure("upstream_headers_invalid")
        total += len(item[0].encode("utf-8")) + len(item[1].encode("utf-8")) + 4
    return total


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateJsonKey(key)
        result[key] = value
    return result


def _reject_json_constant(value: str) -> NoReturn:
    raise _NonstandardJsonConstant(value)


def _strict_serverinfo(raw: bytes) -> dict[str, Any]:
    try:
        text = raw.decode("utf-8")
        document = json.loads(
            text,
            object_pairs_hook=_unique_json_object,
            parse_constant=_reject_json_constant,
        )
    except _DuplicateJsonKey:
        raise NegativeProxyFailure("upstream_json_duplicate_key") from None
    except _NonstandardJsonConstant:
        raise NegativeProxyFailure("upstream_json_nonstandard_constant") from None
    except (UnicodeError, json.JSONDecodeError):
        raise NegativeProxyFailure("upstream_json_invalid") from None
    if not isinstance(document, dict):
        raise NegativeProxyFailure("upstream_json_invalid")
    if "execution_capabilities" not in document:
        raise NegativeProxyFailure("execution_capabilities_missing")
    capabilities = document["execution_capabilities"]
    if not isinstance(capabilities, dict) or not capabilities:
        raise NegativeProxyFailure("execution_capabilities_invalid")
    return document


class NegativeProxySession:
    def __init__(self, config: ProxyConfig, *, fetcher: UpstreamFetcher) -> None:
        _validate_config(config)
        self.config = config
        self.fetcher = fetcher
        self._consumed = False
        self._run_id: str | None = None
        self._accepted_requests = 0
        self._forbidden_requests = 0
        self._upstream_requests = 0

    @property
    def run_id(self) -> str | None:
        return self._run_id

    def record_no_request_failure(
        self,
        reason_code: str,
        *,
        forbidden_request: bool,
    ) -> None:
        if self._consumed:
            raise NegativeProxyFailure("request_already_consumed")
        self._consumed = True
        self._forbidden_requests = 1 if forbidden_request else 0
        _prepare_evidence_directory(self.config.evidence_directory)
        _write_failure_evidence(
            config=self.config,
            reason_code=reason_code,
            run_id=None,
            accepted_requests=0,
            forbidden_requests=self._forbidden_requests,
            upstream_requests=0,
        )

    def process(
        self,
        *,
        method: str,
        path: str,
        headers: dict[str, list[str]],
    ) -> bytes:
        if self._consumed:
            raise NegativeProxyFailure("request_already_consumed")
        self._consumed = True
        _prepare_evidence_directory(self.config.evidence_directory)
        try:
            return self._process_once(method=method, path=path, headers=headers)
        except NegativeProxyFailure as error:
            if self._accepted_requests == 0:
                self._forbidden_requests = 1
            _write_failure_evidence(
                config=self.config,
                reason_code=error.reason_code,
                run_id=self._run_id,
                accepted_requests=self._accepted_requests,
                forbidden_requests=self._forbidden_requests,
                upstream_requests=self._upstream_requests,
            )
            raise

    def _process_once(
        self,
        *,
        method: str,
        path: str,
        headers: dict[str, list[str]],
    ) -> bytes:
        if _request_header_size(headers) > self.config.max_header_bytes:
            raise NegativeProxyFailure("request_headers_too_large")
        if method != "GET":
            raise NegativeProxyFailure("request_method_invalid")
        if path != "/api/v1/serverinfo":
            raise NegativeProxyFailure("request_path_invalid")
        if _request_body_present(headers):
            raise NegativeProxyFailure("request_body_forbidden")
        run_id = _single_header(
            headers,
            "X-Aneb-Run-Id",
            "request_run_id_invalid",
        )
        if RUN_ID_RE.fullmatch(run_id) is None:
            raise NegativeProxyFailure("request_run_id_invalid")
        self._run_id = run_id
        role = _single_header(
            headers,
            "X-Aneb-Audit-Role",
            "request_audit_role_invalid",
        )
        if role != "capability":
            raise NegativeProxyFailure("request_audit_role_invalid")
        self._accepted_requests = 1
        forwarded = {
            "X-Aneb-Run-Id": run_id,
            "X-Aneb-Audit-Role": role,
        }
        self._upstream_requests = 1
        try:
            response = self.fetcher.fetch(forwarded)
        except NegativeProxyFailure:
            raise
        except Exception:
            raise NegativeProxyFailure("upstream_request_failed") from None
        if not isinstance(response, UpstreamResponse):
            raise NegativeProxyFailure("upstream_response_invalid")
        if isinstance(response.status, bool) or response.status != 200:
            raise NegativeProxyFailure("upstream_status_invalid")
        if _upstream_header_size(response.headers) > self.config.max_header_bytes:
            raise NegativeProxyFailure("upstream_headers_too_large")
        if (
            not isinstance(response.peer_certificate_der, bytes)
            or not response.peer_certificate_der
            or len(response.peer_certificate_der) > self.config.max_peer_certificate_bytes
        ):
            raise NegativeProxyFailure("upstream_peer_certificate_missing")
        if (
            not isinstance(response.body, bytes)
            or not response.body
            or len(response.body) > self.config.max_body_bytes
        ):
            raise NegativeProxyFailure("upstream_body_too_large")
        document = _strict_serverinfo(response.body)
        del document["execution_capabilities"]
        filtered = _canonical_json(document)
        ca_file_sha256 = getattr(self.fetcher, "ca_file_sha256", None)
        if (
            not isinstance(ca_file_sha256, str)
            or re.fullmatch(r"[0-9a-f]{64}", ca_file_sha256) is None
        ):
            raise NegativeProxyFailure("upstream_ca_sha256_missing")
        _write_success_evidence(
            config=self.config,
            run_id=run_id,
            response=response,
            filtered=filtered,
            ca_file_sha256=ca_file_sha256,
        )
        return filtered


class _SingleUseHttpServer(HTTPServer):
    allow_reuse_address = False

    def __init__(
        self,
        server_address: tuple[str, int],
        session: NegativeProxySession,
    ) -> None:
        self.session = session
        self.result: ProxyServeResult | None = None
        super().__init__(server_address, _SingleUseRequestHandler, bind_and_activate=True)

    def get_request(self) -> tuple[socket.socket, tuple[str, int]]:
        request, client_address = super().get_request()
        request.settimeout(self.session.config.request_wait_timeout_seconds)
        return request, client_address


class _SingleUseRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server: _SingleUseHttpServer

    def log_message(self, format: str, *args: Any) -> None:
        del format, args

    def _request_headers(self) -> dict[str, list[str]]:
        collected: dict[str, list[str]] = {}
        raw_items = getattr(self.headers, "raw_items", None)
        if raw_items is None:
            return collected
        for name, value in raw_items():
            collected.setdefault(name, []).append(value)
        return collected

    def __getattr__(self, name: str) -> Any:
        if name.startswith("do_"):
            return self._handle_single_request
        raise AttributeError(name)

    def send_error(
        self,
        code: int,
        message: str | None = None,
        explain: str | None = None,
    ) -> None:
        del message, explain
        reason_code = {
            414: "request_path_invalid",
            431: "request_headers_too_large",
            501: "request_method_invalid",
        }.get(int(code), "request_malformed")
        host, port = self.server.server_address
        try:
            self.server.session.record_no_request_failure(
                reason_code,
                forbidden_request=True,
            )
        except NegativeProxyFailure as error:
            reason_code = error.reason_code
        self.server.result = ProxyServeResult(
            status="fail",
            reason_code=reason_code,
            run_id=None,
            listen_host=host,
            listen_port=port,
        )
        self._send_json(
            400,
            _canonical_json({"status": "fail", "reason_code": reason_code}),
        )

    def _send_json(self, status: int, body: bytes) -> None:
        self.send_response_only(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()
        self.close_connection = True

    def _handle_single_request(self) -> None:
        host, port = self.server.server_address
        try:
            body = self.server.session.process(
                method=self.command,
                path=self.path,
                headers=self._request_headers(),
            )
        except NegativeProxyFailure as error:
            self.server.result = ProxyServeResult(
                status="fail",
                reason_code=error.reason_code,
                run_id=self.server.session.run_id,
                listen_host=host,
                listen_port=port,
            )
            self._send_json(
                400,
                _canonical_json(
                    {"status": "fail", "reason_code": error.reason_code},
                ),
            )
            return
        try:
            self._send_json(200, body)
            run_id = self.server.session.run_id
            if run_id is None:
                raise NegativeProxyFailure("delivery_run_id_missing")
            _write_delivery_receipt(
                config=self.server.session.config,
                run_id=run_id,
                body=body,
            )
        except (NegativeProxyFailure, OSError) as error:
            reason_code = (
                error.reason_code
                if isinstance(error, NegativeProxyFailure)
                else "response_write_failed"
            )
            self.server.result = ProxyServeResult(
                status="fail",
                reason_code=reason_code,
                run_id=self.server.session.run_id,
                listen_host=host,
                listen_port=port,
            )
            return
        self.server.result = ProxyServeResult(
            status="pass",
            reason_code="ok",
            run_id=run_id,
            listen_host=host,
            listen_port=port,
        )

    do_GET = _handle_single_request
    do_POST = _handle_single_request
    do_PUT = _handle_single_request
    do_PATCH = _handle_single_request
    do_DELETE = _handle_single_request
    do_HEAD = _handle_single_request
    do_OPTIONS = _handle_single_request
    do_CONNECT = _handle_single_request
    do_TRACE = _handle_single_request


def serve_once(
    config: ProxyConfig,
    *,
    fetcher: UpstreamFetcher,
    ready_callback: Callable[[tuple[str, int]], None] | None = None,
) -> ProxyServeResult:
    """Serve exactly one loopback request and then close the listening socket."""

    session = NegativeProxySession(config, fetcher=fetcher)
    try:
        server = _SingleUseHttpServer(("127.0.0.1", config.listen_port), session)
    except OSError:
        raise NegativeProxyFailure("listen_failed") from None
    try:
        server.timeout = config.request_wait_timeout_seconds
        address = (str(server.server_address[0]), int(server.server_address[1]))
        if ready_callback is not None:
            ready_callback(address)
        server.handle_request()
        if server.result is None:
            session.record_no_request_failure(
                "request_wait_timeout",
                forbidden_request=False,
            )
            return ProxyServeResult(
                status="fail",
                reason_code="request_wait_timeout",
                run_id=None,
                listen_host=address[0],
                listen_port=address[1],
            )
        return server.result
    finally:
        server.server_close()


class _CliArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> NoReturn:
        del message
        raise NegativeProxyFailure("cli_arguments_invalid")


def _build_argument_parser() -> argparse.ArgumentParser:
    parser = _CliArgumentParser(
        description="Serve one loopback-only filtered ANEB serverinfo response.",
        allow_abbrev=False,
    )
    parser.add_argument("--upstream-url", required=True)
    parser.add_argument("--ca-file", required=True, type=Path)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--delivery-receipt-file", type=Path)
    parser.add_argument("--listen-port", type=int, default=0)
    parser.add_argument("--request-timeout-seconds", type=float, default=30.0)
    parser.add_argument("--upstream-timeout-seconds", type=float, default=15.0)
    parser.add_argument("--max-body-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--max-ca-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--max-header-bytes", type=int, default=64 * 1024)
    parser.add_argument(
        "--max-peer-certificate-bytes",
        type=int,
        default=64 * 1024,
    )
    parser.add_argument("--max-output-bytes", type=int, default=3 * 1024 * 1024)
    return parser


def _write_machine_line(stream: TextIO, document: dict[str, Any]) -> None:
    stream.write(_canonical_json(document).decode("utf-8") + "\n")
    stream.flush()


def main(
    argv: Sequence[str] | None = None,
    *,
    fetcher: UpstreamFetcher | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
    ready_callback: Callable[[tuple[str, int]], None] | None = None,
) -> int:
    output = stdout if stdout is not None else sys.stdout
    errors = stderr if stderr is not None else sys.stderr
    try:
        arguments = _build_argument_parser().parse_args(argv)
        config = ProxyConfig(
            upstream_url=arguments.upstream_url,
            ca_file=arguments.ca_file,
            evidence_directory=arguments.evidence_dir,
            delivery_receipt_file=arguments.delivery_receipt_file,
            max_body_bytes=arguments.max_body_bytes,
            max_ca_bytes=arguments.max_ca_bytes,
            max_header_bytes=arguments.max_header_bytes,
            max_peer_certificate_bytes=arguments.max_peer_certificate_bytes,
            max_output_bytes=arguments.max_output_bytes,
            listen_port=arguments.listen_port,
            request_wait_timeout_seconds=arguments.request_timeout_seconds,
            upstream_timeout_seconds=arguments.upstream_timeout_seconds,
        )
        _validate_config(config)
        selected_fetcher = fetcher if fetcher is not None else HttpsUpstreamFetcher(config)

        def on_ready(address: tuple[str, int]) -> None:
            _write_machine_line(
                output,
                {
                    "status": "ready",
                    "listen_host": address[0],
                    "listen_port": address[1],
                },
            )
            if ready_callback is not None:
                ready_callback(address)

        result = serve_once(
            config,
            fetcher=selected_fetcher,
            ready_callback=on_ready,
        )
        _write_machine_line(
            output,
            {
                "status": result.status,
                "reason_code": result.reason_code,
                "run_id": result.run_id,
                "listen_host": result.listen_host,
                "listen_port": result.listen_port,
            },
        )
        return 0 if result.status == "pass" else 1
    except NegativeProxyFailure as error:
        _write_machine_line(
            errors,
            {"status": "fail", "reason_code": error.reason_code},
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
