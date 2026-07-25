#!/usr/bin/env python3
"""Verify the bounded negative serverinfo proxy write evidence for Realtime Quick."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from typing import Any, NoReturn

try:
    import verify_token_quick_negative_proxy_evidence as proxy_verifier
except ModuleNotFoundError:  # imported as scripts.<module> by unit tests
    from scripts import verify_token_quick_negative_proxy_evidence as proxy_verifier


SCHEMA = "aneb-realtime-quick-negative-delivery-evidence"
SCHEMA_VERSION = "0.1.0"
DELIVERY_RECEIPT = "negative-proxy-delivery-receipt.json"
MAX_RECEIPT_BYTES = 64 * 1024
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class NegativeDeliveryEvidenceFailure(ValueError):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class _DuplicateJsonKey(ValueError):
    pass


def fail(reason_code: str) -> NoReturn:
    raise NegativeDeliveryEvidenceFailure(reason_code)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateJsonKey(key)
        result[key] = value
    return result


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
        fail("negative_delivery_receipt_invalid")


def _read_regular(path: Path, *, maximum: int, reason: str) -> bytes:
    flags = os.O_RDONLY
    for name in ("O_BINARY", "O_CLOEXEC", "O_NOFOLLOW"):
        flags |= int(getattr(os, name, 0))
    try:
        if path.is_symlink() or not path.is_file():
            fail(reason)
        before = path.stat()
        if before.st_size <= 0 or before.st_size > maximum:
            fail(reason)
        descriptor = os.open(path, flags)
        try:
            with os.fdopen(descriptor, "rb") as stream:
                payload = stream.read(maximum + 1)
            descriptor = -1
        finally:
            if descriptor >= 0:
                os.close(descriptor)
        after = path.stat()
    except NegativeDeliveryEvidenceFailure:
        raise
    except OSError:
        fail(reason)
    if (
        len(payload) != before.st_size
        or len(payload) > maximum
        or (
            before.st_dev,
            before.st_ino,
            before.st_mode,
            before.st_size,
            before.st_mtime_ns,
        )
        != (
            after.st_dev,
            after.st_ino,
            after.st_mode,
            after.st_size,
            after.st_mtime_ns,
        )
    ):
        fail(reason)
    return payload


def _strict_receipt(raw: bytes) -> dict[str, Any]:
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
        )
    except (UnicodeError, ValueError, json.JSONDecodeError, _DuplicateJsonKey):
        fail("negative_delivery_receipt_invalid")
    if not isinstance(value, dict) or raw != _canonical_json(value):
        fail("negative_delivery_receipt_invalid")
    return value


def verify(
    bundle: Path,
    *,
    run_id: str,
    upstream_url: str,
    ca_file_sha256: str,
    device_port: int = proxy_verifier.EXPECTED_DEVICE_PORT,
) -> dict[str, object]:
    try:
        root = Path(bundle).resolve(strict=True)
    except OSError:
        fail("negative_delivery_bundle_invalid")
    try:
        proxy = proxy_verifier.verify(
            root,
            run_id=run_id,
            upstream_url=upstream_url,
            ca_file_sha256=ca_file_sha256,
            device_port=device_port,
        )
    except proxy_verifier.NegativeProxyEvidenceFailure as error:
        fail(error.reason_code)
    filtered = _read_regular(
        root / "negative-proxy" / "filtered-serverinfo.json",
        maximum=proxy_verifier.MAX_SERVERINFO_BYTES,
        reason="negative_delivery_filtered_body_invalid",
    )
    receipt_raw = _read_regular(
        root / DELIVERY_RECEIPT,
        maximum=MAX_RECEIPT_BYTES,
        reason="negative_delivery_receipt_invalid",
    )
    receipt = _strict_receipt(receipt_raw)
    expected_keys = {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "run_id",
        "response_status",
        "response_body_bytes",
        "response_body_sha256",
        "response_write_completed",
        "evidence_scope",
    }
    if (
        set(receipt) != expected_keys
        or receipt.get("schema")
        != "aneb-serverinfo-negative-proxy-delivery-receipt"
        or receipt.get("schema_version") != "1.0.0"
        or receipt.get("status") != "pass"
        or receipt.get("reason_code") != "ok"
        or receipt.get("run_id") != run_id
        or receipt.get("response_status") != 200
        or receipt.get("response_write_completed") is not True
        or receipt.get("evidence_scope") != "proxy_response_write_completed"
    ):
        fail("negative_delivery_receipt_invalid")
    filtered_sha256 = hashlib.sha256(filtered).hexdigest()
    if (
        isinstance(receipt.get("response_body_bytes"), bool)
        or not isinstance(receipt.get("response_body_bytes"), int)
        or receipt.get("response_body_bytes") != len(filtered)
        or not isinstance(receipt.get("response_body_sha256"), str)
        or SHA256_RE.fullmatch(receipt["response_body_sha256"]) is None
        or receipt["response_body_sha256"] != filtered_sha256
        or proxy.get("filtered_body_sha256") != filtered_sha256
    ):
        fail("negative_delivery_body_binding_mismatch")
    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "run_id": run_id,
        "upstream_url": upstream_url,
        "upstream_body_sha256": proxy["upstream_body_sha256"],
        "filtered_body_sha256": filtered_sha256,
        "delivery_receipt_sha256": hashlib.sha256(receipt_raw).hexdigest(),
        "proxy_response_write_completed": True,
        "evidence_scope": "proxy_response_write_completed",
        "device_port": proxy["device_port"],
        "host_port": proxy["host_port"],
        "raw_files_verified": proxy["raw_files_verified"] + 1,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify Realtime Quick negative proxy response-write evidence"
    )
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--upstream-url", required=True)
    parser.add_argument("--ca-file-sha256", required=True)
    parser.add_argument(
        "--device-port",
        type=int,
        default=proxy_verifier.EXPECTED_DEVICE_PORT,
    )
    args = parser.parse_args()
    try:
        report = verify(
            args.bundle,
            run_id=args.run_id,
            upstream_url=args.upstream_url,
            ca_file_sha256=args.ca_file_sha256,
            device_port=args.device_port,
        )
        exit_code = 0
    except NegativeDeliveryEvidenceFailure as error:
        report = {
            "schema": SCHEMA,
            "schema_version": SCHEMA_VERSION,
            "status": "fail",
            "reason_code": error.reason_code,
            "run_id": args.run_id,
        }
        exit_code = 1
    except Exception:
        report = {
            "schema": SCHEMA,
            "schema_version": SCHEMA_VERSION,
            "status": "fail",
            "reason_code": "internal_verification_error",
            "run_id": args.run_id,
        }
        exit_code = 1
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
