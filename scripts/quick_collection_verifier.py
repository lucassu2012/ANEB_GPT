#!/usr/bin/env python3
"""Family-neutral, side-effect-free Quick collection verifier primitives.

This module deliberately knows nothing about Token, Realtime, or Network
business semantics.  It owns only deterministic file/JSON reads and common
identifier syntax used by independent collection verifiers.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
from typing import Any, NoReturn
import urllib.parse
import uuid


MAX_JSON_BYTES = 32 * 1024 * 1024
MAX_APK_BYTES = 256 * 1024 * 1024
REPARSE_ATTRIBUTE = 0x400
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
UUID4_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
COMPONENT_RE = re.compile(
    r"(?P<package>[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"
    r"/(?P<activity>[A-Za-z0-9_.$]+)"
)


class CollectionVerificationFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> NoReturn:
    raise CollectionVerificationFailure(reason_code)


def is_reparse(metadata: os.stat_result) -> bool:
    return stat.S_ISLNK(metadata.st_mode) or bool(
        int(getattr(metadata, "st_file_attributes", 0)) & REPARSE_ATTRIBUTE
    )


def assert_directory(path: Path, reason: str) -> None:
    absolute = Path(os.path.abspath(os.fspath(path)))
    for component in reversed((absolute, *absolute.parents)):
        try:
            metadata = component.lstat()
        except OSError:
            fail(reason)
        if is_reparse(metadata):
            fail("collection_path_reparse_forbidden")
        if not stat.S_ISDIR(metadata.st_mode):
            fail(reason)


def read_regular(
    path: Path,
    *,
    maximum: int,
    reason: str,
    allow_empty: bool = False,
) -> bytes:
    try:
        before = path.lstat()
        if is_reparse(before):
            fail("collection_path_reparse_forbidden")
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size > maximum
            or (before.st_size == 0 and not allow_empty)
        ):
            fail(reason)
        with path.open("rb") as stream:
            opened = os.fstat(stream.fileno())
            if (
                is_reparse(opened)
                or not stat.S_ISREG(opened.st_mode)
                or (opened.st_dev, opened.st_ino)
                != (before.st_dev, before.st_ino)
            ):
                fail(reason)
            raw = stream.read(maximum + 1)
        after = path.lstat()
    except CollectionVerificationFailure:
        raise
    except OSError:
        fail(reason)
    if (
        len(raw) > maximum
        or len(raw) != before.st_size
        or (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        != (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        or is_reparse(after)
    ):
        fail(reason)
    return raw


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def canonical_json(value: object) -> bytes:
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


def load_json(
    path: Path,
    reason: str,
    *,
    maximum: int = MAX_JSON_BYTES,
    require_canonical: bool = True,
) -> tuple[dict[str, Any], bytes]:
    raw = read_regular(path, maximum=maximum, reason=reason)
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_object,
            parse_constant=lambda item: (_ for _ in ()).throw(ValueError(item)),
        )
    except (UnicodeError, ValueError, json.JSONDecodeError, RecursionError):
        fail(reason)
    if not isinstance(value, dict):
        fail(reason)
    if require_canonical and canonical_json(value) != raw:
        fail(f"{reason}_noncanonical")
    return value, raw


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_file(path: Path, *, maximum: int = MAX_APK_BYTES) -> str:
    return sha256(
        read_regular(path, maximum=maximum, reason="manifest_file_unreadable")
    )


def exact(value: object, keys: frozenset[str] | set[str]) -> bool:
    return isinstance(value, dict) and set(value) == set(keys)


def safe_relative(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        fail("manifest_path_invalid")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        fail("manifest_path_invalid")
    return value


def validate_uuid(value: object, *, version: int, reason: str) -> str:
    pattern = RUN_ID_RE if version == 7 else UUID4_RE
    if version not in {4, 7} or not isinstance(value, str):
        fail(reason)
    if pattern.fullmatch(value) is None:
        fail(reason)
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        fail(reason)
    if parsed.version != version or parsed.variant != uuid.RFC_4122:
        fail(reason)
    return value


def validate_server_base(value: object, reason: str) -> urllib.parse.SplitResult:
    if not isinstance(value, str):
        fail(reason)
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        fail(reason)
    try:
        port = parsed.port
    except ValueError:
        fail(reason)
    if port is not None and not 1 <= port <= 65535:
        fail(reason)
    return parsed


def canonical_component(value: str, *, reason: str) -> str:
    match = COMPONENT_RE.search(value)
    if match is None:
        fail(reason)
    package = match.group("package")
    activity = match.group("activity")
    if activity.startswith("."):
        activity = package + activity
    return f"{package}/{activity}"


__all__ = (
    "COMMIT_RE",
    "CollectionVerificationFailure",
    "MAX_APK_BYTES",
    "MAX_JSON_BYTES",
    "RUN_ID_RE",
    "SHA256_RE",
    "UUID4_RE",
    "assert_directory",
    "canonical_component",
    "canonical_json",
    "exact",
    "fail",
    "is_reparse",
    "load_json",
    "read_regular",
    "safe_relative",
    "sha256",
    "sha256_file",
    "validate_server_base",
    "validate_uuid",
)
