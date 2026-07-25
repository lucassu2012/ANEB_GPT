#!/usr/bin/env python3
"""Fail-closed verifier for the P40 identity snapshots in a D-82 bundle.

This module is deliberately local-only.  It does not call ADB or any external
tool.  The collector supplies two raw snapshots and a separately approved,
private device policy; this verifier checks that the same physical Android boot
and the policy-selected device were observed before and after the run.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, NoReturn


SCHEMA = "aneb-token-quick-device-identity-verification"
SCHEMA_VERSION = "1.0.0"
POLICY_SCHEMA = "aneb-device-identity-policy"
POLICY_VERSION = "1.0.0"
DEVICE_ALIAS = "P40 Pro"
MAX_POLICY_BYTES = 64 * 1024
MAX_RAW_BYTES = 64 * 1024
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SERIAL_RE = re.compile(r"^[A-Za-z0-9._:-]{4,128}$")
BOOT_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)

SERIAL_PROPERTY_KEYS = (
    "ro.serialno",
    "ro.boot.serialno",
)
CORE_PROPERTY_KEYS = (
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.product.device",
    "ro.product.name",
    "ro.build.fingerprint",
    "ro.build.version.security_patch",
)
VERIFIED_BOOT_KEYS = (
    "ro.boot.verifiedbootstate",
    "ro.boot.vbmeta.device_state",
    "ro.boot.flash.locked",
    "ro.boot.veritymode",
)
PROPERTY_KEYS = SERIAL_PROPERTY_KEYS + CORE_PROPERTY_KEYS + VERIFIED_BOOT_KEYS
RAW_FILE_NAMES = frozenset(
    {
        f"device-{kind}-{stage}.txt"
        for kind in ("adb-serial", "getprop", "boot-id")
        for stage in ("preflight", "final")
    }
)


class DeviceIdentityFailure(ValueError):
    """Stable failure consumed by the enclosing evidence verifier."""

    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class DuplicateJsonKey(ValueError):
    pass


def fail(reason_code: str) -> NoReturn:
    raise DeviceIdentityFailure(reason_code)


def _is_reparse(path: Path) -> bool:
    try:
        if path.is_symlink():
            return True
        is_junction = getattr(path, "is_junction", None)
        return bool(is_junction and is_junction())
    except OSError:
        return True


def _read_regular(path: Path, *, maximum: int, reason: str) -> bytes:
    try:
        if _is_reparse(path):
            fail(reason)
        metadata = os.lstat(path)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size <= 0:
            fail(reason)
        if metadata.st_size > maximum:
            fail(reason)
        with path.open("rb") as stream:
            raw = stream.read(maximum + 1)
        after = os.lstat(path)
    except DeviceIdentityFailure:
        raise
    except OSError:
        fail(reason)
    if len(raw) > maximum or (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
    ) != (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
    ):
        fail(reason)
    return raw


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(key)
        result[key] = value
    return result


def _load_policy(path: Path) -> tuple[dict[str, Any], str]:
    raw = _read_regular(path, maximum=MAX_POLICY_BYTES, reason="device_policy_unavailable")
    try:
        policy = json.loads(raw.decode("utf-8"), object_pairs_hook=_object_pairs)
    except (UnicodeError, json.JSONDecodeError, DuplicateJsonKey):
        fail("device_policy_invalid")
    if not isinstance(policy, dict) or set(policy) != {
        "schema",
        "schema_version",
        "device_alias",
        "adb_serial_sha256",
        "properties",
    }:
        fail("device_policy_invalid")
    if (
        policy["schema"] != POLICY_SCHEMA
        or policy["schema_version"] != POLICY_VERSION
        or policy["device_alias"] != DEVICE_ALIAS
        or not isinstance(policy["adb_serial_sha256"], str)
        or SHA256_RE.fullmatch(policy["adb_serial_sha256"]) is None
        or not isinstance(policy["properties"], dict)
        or set(policy["properties"]) != set(PROPERTY_KEYS)
    ):
        fail("device_policy_invalid")
    _validate_properties(policy["properties"], reason="device_policy_invalid")
    return policy, hashlib.sha256(raw).hexdigest()


def _read_text(path: Path, *, reason: str) -> str:
    raw = _read_regular(path, maximum=MAX_RAW_BYTES, reason=reason)
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        fail(reason)
    if "\r" in text or "\x00" in text or not text.endswith("\n"):
        fail(reason)
    return text


def _single_line(path: Path, *, pattern: re.Pattern[str], reason: str) -> str:
    text = _read_text(path, reason=reason)
    lines = text[:-1].split("\n")
    if len(lines) != 1 or pattern.fullmatch(lines[0]) is None:
        fail(reason)
    return lines[0]


def _validate_property_value(value: Any, *, allow_empty: bool, reason: str) -> None:
    if (
        not isinstance(value, str)
        or len(value.encode("utf-8")) > 2048
        or "\r" in value
        or "\n" in value
        or "\x00" in value
        or (not allow_empty and not value)
    ):
        fail(reason)


def _validate_properties(properties: dict[str, Any], *, reason: str) -> None:
    if set(properties) != set(PROPERTY_KEYS):
        fail(reason)
    for key in PROPERTY_KEYS:
        _validate_property_value(
            properties[key],
            allow_empty=key in SERIAL_PROPERTY_KEYS or key in VERIFIED_BOOT_KEYS,
            reason=reason,
        )


def _parse_properties(path: Path) -> dict[str, str]:
    reason = "device_properties_invalid"
    text = _read_text(path, reason=reason)
    values: dict[str, str] = {}
    for line in text[:-1].split("\n"):
        if "=" not in line:
            fail(reason)
        key, value = line.split("=", 1)
        if key not in PROPERTY_KEYS or key in values:
            fail(reason)
        values[key] = value
    _validate_properties(values, reason=reason)
    return values


def _secure_verified_boot(properties: dict[str, str]) -> tuple[bool, bool]:
    observed = all(properties[key] for key in VERIFIED_BOOT_KEYS)
    secure = observed and (
        properties["ro.boot.verifiedbootstate"].lower() == "green"
        and properties["ro.boot.vbmeta.device_state"].lower() == "locked"
        and properties["ro.boot.flash.locked"] == "1"
        and properties["ro.boot.veritymode"].lower() in {"enforcing", "enabled"}
    )
    return observed, secure


def verify_device_identity(
    bundle: Path,
    *,
    policy_path: Path,
    expected_input_serial: str,
) -> dict[str, object]:
    """Verify pre/final P40 identity without disclosing its serial in the report."""

    if not isinstance(expected_input_serial, str) or SERIAL_RE.fullmatch(
        expected_input_serial
    ) is None:
        fail("device_serial_mismatch")
    try:
        root = Path(bundle).resolve(strict=True)
    except OSError:
        fail("device_identity_raw_unavailable")
    if not root.is_dir() or _is_reparse(root):
        fail("device_identity_raw_unavailable")

    policy, policy_sha256 = _load_policy(Path(policy_path))
    serials: dict[str, str] = {}
    boot_ids: dict[str, str] = {}
    properties: dict[str, dict[str, str]] = {}
    for stage in ("preflight", "final"):
        serials[stage] = _single_line(
            root / f"device-adb-serial-{stage}.txt",
            pattern=SERIAL_RE,
            reason="device_identity_raw_unavailable",
        )
        boot_ids[stage] = _single_line(
            root / f"device-boot-id-{stage}.txt",
            pattern=BOOT_ID_RE,
            reason="device_identity_raw_unavailable",
        )
        properties[stage] = _parse_properties(
            root / f"device-getprop-{stage}.txt"
        )

    if (
        serials["preflight"] != serials["final"]
        or serials["preflight"] != expected_input_serial
    ):
        fail("device_serial_mismatch")
    digest = hashlib.sha256(serials["preflight"].encode("utf-8")).hexdigest()
    if digest != policy["adb_serial_sha256"]:
        fail("device_policy_mismatch")
    if boot_ids["preflight"] != boot_ids["final"]:
        fail("device_boot_id_mismatch")
    if properties["preflight"] != properties["final"]:
        fail("device_properties_mismatch")
    if properties["preflight"] != policy["properties"]:
        fail("device_policy_mismatch")

    serial_values = [
        properties["preflight"][key]
        for key in SERIAL_PROPERTY_KEYS
        if properties["preflight"][key]
    ]
    if any(value != expected_input_serial for value in serial_values):
        fail("device_serial_mismatch")
    observed, secure = _secure_verified_boot(properties["preflight"])
    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "device_alias": policy["device_alias"],
        "device_policy_sha256": policy_sha256,
        "adb_serial_sha256": digest,
        "android_boot_id": boot_ids["preflight"],
        "properties_sha256": hashlib.sha256(
            json.dumps(
                properties["preflight"],
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest(),
        "serial_property_confirmed": bool(serial_values),
        "verified_boot_observed_complete": observed,
        "verified_boot_secure": secure,
        "raw_files_verified": len(RAW_FILE_NAMES),
    }


__all__ = [
    "CORE_PROPERTY_KEYS",
    "DEVICE_ALIAS",
    "DeviceIdentityFailure",
    "POLICY_SCHEMA",
    "POLICY_VERSION",
    "PROPERTY_KEYS",
    "RAW_FILE_NAMES",
    "SERIAL_PROPERTY_KEYS",
    "VERIFIED_BOOT_KEYS",
    "verify_device_identity",
]
