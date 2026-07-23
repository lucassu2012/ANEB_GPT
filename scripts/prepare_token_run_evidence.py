#!/usr/bin/env python3
"""Prepare source-bound D-82 Token run evidence without contacting a device.

The ``derive`` command validates a raw ``journalctl -o json`` export against a
pre-start process receipt, then derives the byte stream consumed by the D-81
request-entry verifier.  It deliberately stops at the one exact window-end
barrier so later journal traffic cannot silently enter the judged window.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import NoReturn
from urllib.parse import urlsplit


RECEIPT_SCHEMA = "aneb-token-evidence-pre-start-receipt"
RECEIPT_SCHEMA_VERSION = "1.0.0"
DERIVATION_SCHEMA = "aneb-token-evidence-derivation"
DERIVATION_SCHEMA_VERSION = "1.0.0"
MANIFEST_SCHEMA = "aneb-evidence-manifest-draft"
MANIFEST_SCHEMA_VERSION = "1.0.0"
UUID_V7 = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)
UUID_V4 = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)
SYSTEMD_ID = re.compile(r"[0-9a-f]{32}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
JOURNAL_CURSOR_RE = re.compile(r"[A-Za-z0-9;:_.=-]{10,1024}")
UINT64_TEXT_RE = re.compile(r"(?:0|[1-9][0-9]{0,19})")
MAX_UINT64 = (1 << 64) - 1
AUDIT_LINE = re.compile(
    r"ANEB_REQUEST_AUDIT "
    r"instance_id=[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12} "
    r"seq=[1-9][0-9]{0,19} "
    r"class=control method=GET path=/api/v1/serverinfo "
    r"role=(?P<role>window_start|window_end) scope=token_run "
    r"run_id=(?P<run_id>[0-9a-f-]+)"
)
MAX_SOURCE_BYTES = 32 * 1024 * 1024
MAX_RECORDS = 250_000
MAX_MANIFEST_FILE_BYTES = 128 * 1024 * 1024


class EvidenceError(ValueError):
    """A fail-closed evidence preparation error."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _unique_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise EvidenceError(f"duplicate JSON field: {key}")
        value[key] = item
    return value


def _fail(message: str) -> NoReturn:
    print(f"EVIDENCE_PREPARATION_FAILED: {message}", file=sys.stderr)
    raise SystemExit(2)


def _read_only_flags() -> int:
    flags = os.O_RDONLY
    for optional_flag in ("O_BINARY", "O_CLOEXEC", "O_NOFOLLOW"):
        flags |= int(getattr(os, optional_flag, 0))
    return flags


def _fsync_directories(paths: set[Path]) -> None:
    if os.name != "posix":
        return
    flags = os.O_RDONLY | int(getattr(os, "O_DIRECTORY", 0))
    for path in paths:
        descriptor = os.open(path, flags)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def _read_bounded(path: Path) -> bytes:
    try:
        if path.is_symlink():
            raise OSError("symbolic-link inputs are not allowed")
        descriptor = os.open(path, _read_only_flags())
    except OSError as exc:
        raise EvidenceError(f"cannot open {path}: {exc}") from exc
    try:
        with os.fdopen(descriptor, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):
                raise EvidenceError(f"input is not a regular file: {path}")
            if before.st_size > MAX_SOURCE_BYTES:
                raise EvidenceError(
                    f"input exceeds {MAX_SOURCE_BYTES} bytes: {path}"
                )
            payload = bytearray()
            while block := stream.read(1024 * 1024):
                payload.extend(block)
                if len(payload) > MAX_SOURCE_BYTES:
                    raise EvidenceError(
                        f"input exceeds {MAX_SOURCE_BYTES} bytes: {path}"
                    )
            after_stream = os.fstat(stream.fileno())
        after_path = os.stat(path, follow_symlinks=False)
    except EvidenceError:
        raise
    except OSError as exc:
        raise EvidenceError(f"cannot read {path}: {exc}") from exc
    before_identity = (
        before.st_dev,
        before.st_ino,
        before.st_mode,
        before.st_size,
        before.st_mtime_ns,
    )
    after_stream_identity = (
        after_stream.st_dev,
        after_stream.st_ino,
        after_stream.st_mode,
        after_stream.st_size,
        after_stream.st_mtime_ns,
    )
    after_path_identity = (
        after_path.st_dev,
        after_path.st_ino,
        after_path.st_mode,
        after_path.st_size,
        after_path.st_mtime_ns,
    )
    if (
        before_identity != after_stream_identity
        or before_identity != after_path_identity
        or len(payload) != before.st_size
    ):
        raise EvidenceError(f"input changed while reading: {path}")
    return bytes(payload)


def _load_receipt(path: Path) -> tuple[dict[str, object], bytes]:
    raw = _read_bounded(path)
    try:
        receipt = json.loads(
            raw.decode("utf-8"), object_pairs_hook=_unique_json_object
        )
    except EvidenceError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError, RecursionError) as exc:
        raise EvidenceError("pre-start receipt is not one UTF-8 JSON object") from exc
    if not isinstance(receipt, dict):
        raise EvidenceError("pre-start receipt must be a JSON object")
    required = {
        "schema": str,
        "schema_version": str,
        "captured_at_utc": str,
        "journal_cursor": str,
        "journal_monotonic_anchor": int,
        "remote_realtime_anchor_usec": int,
        "boot_id": str,
        "systemd_invocation_id": str,
        "unit": str,
        "main_pid": int,
        "server_base": str,
        "server_version": str,
        "server_binary_sha256": str,
        "serverinfo_body_sha256": str,
        "lock_path": str,
        "lock_nonce": str,
        "lock_remote_pid": int,
        "lock_marker": str,
    }
    if set(receipt) - set(required):
        raise EvidenceError("pre-start receipt fields do not match schema")
    for key, kind in required.items():
        if type(receipt.get(key)) is not kind:
            raise EvidenceError(f"pre-start receipt field {key!r} has the wrong type")
    if receipt["schema"] != RECEIPT_SCHEMA:
        raise EvidenceError("pre-start receipt schema is unsupported")
    if receipt["schema_version"] != RECEIPT_SCHEMA_VERSION:
        raise EvidenceError("pre-start receipt schema_version is unsupported")
    captured_at = str(receipt["captured_at_utc"])
    if not captured_at.endswith("Z"):
        raise EvidenceError("pre-start captured_at_utc must be UTC with a Z suffix")
    try:
        parsed_captured_at = datetime.fromisoformat(captured_at[:-1] + "+00:00")
    except ValueError as exc:
        raise EvidenceError("pre-start captured_at_utc is not an RFC 3339 timestamp") from exc
    if parsed_captured_at.tzinfo != timezone.utc:
        raise EvidenceError("pre-start captured_at_utc is not UTC")
    if JOURNAL_CURSOR_RE.fullmatch(str(receipt["journal_cursor"])) is None:
        raise EvidenceError("pre-start journal_cursor has an invalid format")
    if not 0 < int(receipt["journal_monotonic_anchor"]) <= MAX_UINT64:
        raise EvidenceError(
            "pre-start journal_monotonic_anchor must be a positive uint64"
        )
    if not 0 < int(receipt["remote_realtime_anchor_usec"]) <= MAX_UINT64:
        raise EvidenceError(
            "pre-start remote_realtime_anchor_usec must be a positive uint64"
        )
    if SYSTEMD_ID.fullmatch(str(receipt["boot_id"])) is None:
        raise EvidenceError("pre-start boot_id is not a lowercase systemd ID")
    if SYSTEMD_ID.fullmatch(str(receipt["systemd_invocation_id"])) is None:
        raise EvidenceError("pre-start systemd_invocation_id is not a lowercase systemd ID")
    if receipt["unit"] != "aneb-server.service":
        raise EvidenceError("pre-start unit is not aneb-server.service")
    if int(receipt["main_pid"]) <= 1:
        raise EvidenceError("pre-start main_pid must be greater than one")
    try:
        server_base = urlsplit(str(receipt["server_base"]))
        server_port = server_base.port
    except ValueError as exc:
        raise EvidenceError("pre-start server_base is invalid") from exc
    if (
        server_base.scheme != "https"
        or not server_base.hostname
        or server_port != 8443
        or server_base.username is not None
        or server_base.password is not None
        or server_base.path not in {"", "/"}
        or bool(server_base.query)
        or bool(server_base.fragment)
    ):
        raise EvidenceError(
            "pre-start server_base must be a credential-free HTTPS origin on port 8443"
        )
    if receipt["server_version"] != "aneb-server/0.8.0":
        raise EvidenceError("pre-start server_version is not aneb-server/0.8.0")
    for field in ("server_binary_sha256", "serverinfo_body_sha256"):
        if SHA256_RE.fullmatch(str(receipt[field])) is None:
            raise EvidenceError(f"pre-start {field} is not a lowercase SHA-256")
    if receipt["lock_path"] != "/run/lock/aneb-deploy.lock":
        raise EvidenceError("pre-start lock_path is not the protected deploy lock")
    nonce = str(receipt["lock_nonce"])
    if SYSTEMD_ID.fullmatch(nonce) is None:
        raise EvidenceError("pre-start lock_nonce is not 32 lowercase hex characters")
    if int(receipt["lock_remote_pid"]) <= 1:
        raise EvidenceError("pre-start lock_remote_pid must be greater than one")
    if receipt["lock_marker"] != f"/run/aneb-token-audit-{nonce}.lock":
        raise EvidenceError("pre-start lock_marker does not match lock_nonce")
    return receipt, raw


def _message_bytes(value: object) -> bytes:
    if isinstance(value, str):
        try:
            message = value.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise EvidenceError("journal MESSAGE string is not valid UTF-8") from exc
    elif isinstance(value, list):
        if any(type(item) is not int or not 0 <= item <= 255 for item in value):
            raise EvidenceError("journal MESSAGE byte array contains a non-byte value")
        message = bytes(value)
    else:
        raise EvidenceError("journal MESSAGE must be a string or a byte array")
    if b"\r" in message or b"\n" in message:
        raise EvidenceError("journal MESSAGE contains a record delimiter")
    return message


def _parse_journal(
    raw: bytes,
    receipt: dict[str, object],
) -> list[tuple[dict[str, object], bytes]]:
    if not raw:
        raise EvidenceError("raw journal JSONL is empty")
    records: list[tuple[dict[str, object], bytes]] = []
    cursors: set[str] = set()
    previous_monotonic: int | None = None
    previous_realtime: int | None = None
    for line_number, raw_line in enumerate(raw.splitlines(), 1):
        if not raw_line.strip():
            raise EvidenceError(f"raw journal line {line_number} is empty")
        if len(records) >= MAX_RECORDS:
            raise EvidenceError(f"raw journal exceeds {MAX_RECORDS} records")
        try:
            record = json.loads(
                raw_line.decode("utf-8"), object_pairs_hook=_unique_json_object
            )
        except EvidenceError:
            raise
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError, RecursionError) as exc:
            raise EvidenceError(f"raw journal line {line_number} is not UTF-8 JSON") from exc
        if not isinstance(record, dict):
            raise EvidenceError(f"raw journal line {line_number} is not an object")
        required = (
            "__CURSOR",
            "__REALTIME_TIMESTAMP",
            "__MONOTONIC_TIMESTAMP",
            "_BOOT_ID",
            "_SYSTEMD_INVOCATION_ID",
            "_SYSTEMD_UNIT",
            "_PID",
            "MESSAGE",
        )
        if set(record) - set(required):
            raise EvidenceError(
                f"raw journal line {line_number} fields do not match schema"
            )
        missing = [field for field in required if field not in record]
        if missing:
            raise EvidenceError(
                f"raw journal line {line_number} lacks {','.join(missing)}"
            )
        cursor = record["__CURSOR"]
        if (
            not isinstance(cursor, str)
            or JOURNAL_CURSOR_RE.fullmatch(cursor) is None
        ):
            raise EvidenceError(f"raw journal line {line_number} has invalid __CURSOR")
        if cursor == receipt["journal_cursor"]:
            raise EvidenceError(
                f"raw journal line {line_number} replays the pre-start cursor"
            )
        if cursor in cursors:
            raise EvidenceError(f"raw journal line {line_number} reuses __CURSOR")
        cursors.add(cursor)
        expected = {
            "_BOOT_ID": receipt["boot_id"],
            "_SYSTEMD_INVOCATION_ID": receipt["systemd_invocation_id"],
            "_SYSTEMD_UNIT": receipt["unit"],
            "_PID": str(receipt["main_pid"]),
        }
        for field, value in expected.items():
            if record[field] != value:
                raise EvidenceError(
                    f"raw journal line {line_number} {field} does not match pre-start receipt"
                )
        timestamp = record["__REALTIME_TIMESTAMP"]
        if (
            not isinstance(timestamp, str)
            or UINT64_TEXT_RE.fullmatch(timestamp) is None
        ):
            raise EvidenceError(
                f"raw journal line {line_number} has invalid __REALTIME_TIMESTAMP"
            )
        realtime_value = int(timestamp)
        if realtime_value <= 0 or realtime_value > MAX_UINT64:
            raise EvidenceError(
                f"raw journal line {line_number} has invalid __REALTIME_TIMESTAMP"
            )
        if realtime_value < int(receipt["remote_realtime_anchor_usec"]):
            raise EvidenceError(
                f"raw journal line {line_number} precedes remote realtime anchor"
            )
        if previous_realtime is not None and realtime_value < previous_realtime:
            raise EvidenceError("raw journal realtime timestamps are not ordered")
        previous_realtime = realtime_value
        monotonic_timestamp = record["__MONOTONIC_TIMESTAMP"]
        if (
            not isinstance(monotonic_timestamp, str)
            or UINT64_TEXT_RE.fullmatch(monotonic_timestamp) is None
        ):
            raise EvidenceError(
                f"raw journal line {line_number} does not follow monotonic anchor"
            )
        monotonic_value = int(monotonic_timestamp)
        if (
            monotonic_value > MAX_UINT64
            or monotonic_value <= int(receipt["journal_monotonic_anchor"])
        ):
            raise EvidenceError(
                f"raw journal line {line_number} does not follow monotonic anchor"
            )
        if previous_monotonic is not None and monotonic_value <= previous_monotonic:
            raise EvidenceError(
                "raw journal monotonic timestamps are not strictly increasing"
            )
        previous_monotonic = monotonic_value
        records.append((record, _message_bytes(record["MESSAGE"])))
    return records


def _exact_barrier(message: bytes, *, role: str, run_id: str) -> bool:
    try:
        text = message.decode("ascii")
    except UnicodeDecodeError:
        return False
    match = AUDIT_LINE.fullmatch(text)
    return bool(match and match["role"] == role and match["run_id"] == run_id)


def _write_new_files(
    outputs: tuple[tuple[Path, bytes], ...], *, label: str
) -> None:
    """Stage every new file before publishing any; never replace prior evidence."""
    staged: list[tuple[Path, Path]] = []
    try:
        for path, data in outputs:
            if os.path.lexists(path):
                raise OSError(f"output already exists: {path}")
            path.parent.mkdir(parents=True, exist_ok=True)
            handle, temporary_name = tempfile.mkstemp(
                prefix=f".{path.name}.", dir=path.parent
            )
            temporary = Path(temporary_name)
            try:
                with os.fdopen(handle, "wb") as stream:
                    stream.write(data)
                    stream.flush()
                    os.fsync(stream.fileno())
            except BaseException:
                try:
                    temporary.unlink()
                except OSError:
                    pass
                raise
            staged.append((temporary, path))
    except OSError as exc:
        for temporary, _ in staged:
            try:
                temporary.unlink()
            except OSError:
                pass
        raise EvidenceError(f"cannot stage {label} outputs: {exc}") from exc

    committed: list[Path] = []
    try:
        for temporary, path in staged:
            # link() is an atomic create-if-absent operation on the supported
            # local filesystems; unlike replace(), it cannot overwrite a file
            # that appeared after preflight.
            os.link(temporary, path)
            committed.append(path)
            temporary.unlink()
        _fsync_directories({path.parent for path in committed})
    except OSError as exc:
        rollback_errors: list[str] = []
        for path in reversed(committed):
            try:
                path.unlink()
            except OSError as rollback_exc:
                rollback_errors.append(str(rollback_exc))
        for temporary, _ in staged:
            try:
                temporary.unlink()
            except OSError:
                pass
        try:
            _fsync_directories({path.parent for path in committed})
        except OSError as rollback_exc:
            rollback_errors.append(str(rollback_exc))
        if rollback_errors:
            raise EvidenceError(
                f"cannot commit {label} outputs and rollback was incomplete"
            ) from exc
        raise EvidenceError(f"cannot commit {label} outputs: {exc}") from exc


def _derive(args: argparse.Namespace) -> None:
    paths = (
        args.journal.resolve(strict=False),
        args.pre_start_receipt.resolve(strict=False),
        args.message_output.resolve(strict=False),
        args.derivation_output.resolve(strict=False),
    )
    normalized_paths = {os.path.normcase(str(path)) for path in paths}
    if len(normalized_paths) != len(paths):
        raise EvidenceError("journal, receipt, and output paths must be distinct")
    for output in (args.message_output, args.derivation_output):
        if os.path.lexists(output):
            raise EvidenceError("derive output already exists")

    identifiers = (args.run_id, args.start_barrier_id, args.end_barrier_id)
    if UUID_V7.fullmatch(args.run_id) is None:
        raise EvidenceError("run ID must be a canonical lowercase UUIDv7")
    if any(
        UUID_V4.fullmatch(value) is None
        for value in (args.start_barrier_id, args.end_barrier_id)
    ):
        raise EvidenceError("start/end IDs must be canonical lowercase UUIDv4")
    if len(set(identifiers)) != 3:
        raise EvidenceError("run/start/end IDs must be pairwise distinct")

    receipt, receipt_raw = _load_receipt(args.pre_start_receipt)
    raw = _read_bounded(args.journal)
    records = _parse_journal(raw, receipt)
    starts = [
        index
        for index, (_, message) in enumerate(records)
        if _exact_barrier(message, role="window_start", run_id=args.start_barrier_id)
    ]
    ends = [
        index
        for index, (_, message) in enumerate(records)
        if _exact_barrier(message, role="window_end", run_id=args.end_barrier_id)
    ]
    if len(starts) != 1:
        raise EvidenceError(f"expected one exact start barrier, found {len(starts)}")
    if len(ends) != 1:
        raise EvidenceError(f"expected one exact end barrier, found {len(ends)}")
    if starts[0] >= ends[0]:
        raise EvidenceError("exact start barrier does not precede exact end barrier")

    retained = records[: ends[0] + 1]
    message_log = b"".join(message + b"\n" for _, message in retained)
    derived_lines = message_log.count(b"\n")
    if derived_lines != len(retained):
        raise EvidenceError("derived MESSAGE line count does not match source records")
    report = {
        "audit_lock": {
            "marker": receipt["lock_marker"],
            "nonce": receipt["lock_nonce"],
            "path": receipt["lock_path"],
            "remote_pid": receipt["lock_remote_pid"],
        },
        "derived_message_log": {
            "bytes": len(message_log),
            "lines": derived_lines,
            "records": len(retained),
            "sha256": _sha256(message_log),
        },
        "derivation_algorithm": {
            "binary_message": "journalctl_json_byte_array",
            "record_framing": "message_bytes_plus_lf",
            "text_message": "utf-8",
        },
        "evidence_scope": "journald_source_derivation_only",
        "identity": {
            "boot_id": receipt["boot_id"],
            "end_barrier_id": args.end_barrier_id,
            "main_pid": receipt["main_pid"],
            "run_id": args.run_id,
            "start_barrier_id": args.start_barrier_id,
            "systemd_invocation_id": receipt["systemd_invocation_id"],
            "unit": receipt["unit"],
        },
        "node_identity": {
            "base": receipt["server_base"],
            "binary_sha256": receipt["server_binary_sha256"],
            "serverinfo_body_sha256": receipt["serverinfo_body_sha256"],
            "version": receipt["server_version"],
        },
        "pre_start": {
            "captured_at_utc": receipt["captured_at_utc"],
            "journal_cursor": receipt["journal_cursor"],
            "journal_monotonic_anchor": receipt["journal_monotonic_anchor"],
            "remote_realtime_anchor_usec": receipt["remote_realtime_anchor_usec"],
        },
        "pre_start_receipt": {
            "bytes": len(receipt_raw),
            "sha256": _sha256(receipt_raw),
        },
        "schema": DERIVATION_SCHEMA,
        "schema_version": DERIVATION_SCHEMA_VERSION,
        "source": {
            "bytes": len(raw),
            "records": len(records),
            "sha256": _sha256(raw),
        },
        "status": "pass",
        "truncation": {
            "end_barrier_record": ends[0] + 1,
            "end_cursor": retained[-1][0]["__CURSOR"],
            "end_realtime_timestamp": retained[-1][0]["__REALTIME_TIMESTAMP"],
            "records_after_end_barrier": len(records) - len(retained),
            "retained_records": len(retained),
            "start_barrier_record": starts[0] + 1,
            "start_cursor": records[starts[0]][0]["__CURSOR"],
            "start_realtime_timestamp": records[starts[0]][0][
                "__REALTIME_TIMESTAMP"
            ],
        },
    }
    report_bytes = (
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    _write_new_files(
        (
            (args.message_output, message_log),
            (args.derivation_output, report_bytes),
        ),
        label="derive",
    )
    print(
        "EVIDENCE_DERIVATION_OK "
        f"records={len(retained)} sha256={_sha256(message_log)}"
    )


def _manifest_file(root: Path, path: Path) -> tuple[str, int, str]:
    try:
        resolved = path.resolve(strict=True)
        relative = resolved.relative_to(root)
    except (OSError, ValueError) as exc:
        raise EvidenceError(f"manifest file is missing or outside root: {path}") from exc
    if path.is_symlink() or not resolved.is_file():
        raise EvidenceError(f"manifest input is not a regular non-symlink file: {path}")
    relative_text = relative.as_posix()
    if not relative_text or relative_text == ".":
        raise EvidenceError(f"manifest file has no relative path: {path}")
    try:
        digest = hashlib.sha256()
        byte_count = 0
        descriptor = os.open(resolved, _read_only_flags())
        with os.fdopen(descriptor, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):
                raise EvidenceError(
                    f"manifest input is not a regular non-symlink file: {path}"
                )
            if before.st_size > MAX_MANIFEST_FILE_BYTES:
                raise EvidenceError(
                    f"manifest file exceeds {MAX_MANIFEST_FILE_BYTES} bytes: {path}"
                )
            while block := stream.read(1024 * 1024):
                digest.update(block)
                byte_count += len(block)
                if byte_count > MAX_MANIFEST_FILE_BYTES:
                    raise EvidenceError(
                        f"manifest file exceeds {MAX_MANIFEST_FILE_BYTES} bytes: {path}"
                    )
            after_stream = os.fstat(stream.fileno())
        after_path = os.stat(resolved, follow_symlinks=False)
    except EvidenceError:
        raise
    except OSError as exc:
        raise EvidenceError(f"cannot hash manifest file {path}: {exc}") from exc
    before_identity = (
        before.st_dev,
        before.st_ino,
        before.st_mode,
        before.st_size,
        before.st_mtime_ns,
    )
    after_stream_identity = (
        after_stream.st_dev,
        after_stream.st_ino,
        after_stream.st_mode,
        after_stream.st_size,
        after_stream.st_mtime_ns,
    )
    after_path_identity = (
        after_path.st_dev,
        after_path.st_ino,
        after_path.st_mode,
        after_path.st_size,
        after_path.st_mtime_ns,
    )
    if (
        before_identity != after_stream_identity
        or before_identity != after_path_identity
        or byte_count != before.st_size
    ):
        raise EvidenceError(f"manifest file changed while hashing: {path}")
    return relative_text, byte_count, digest.hexdigest()


def _manifest(args: argparse.Namespace) -> None:
    if not args.output.name.endswith(".draft.json"):
        raise EvidenceError("manifest output filename must end with .draft.json")
    if os.path.lexists(args.output):
        raise EvidenceError("manifest output already exists")
    try:
        root = args.root.resolve(strict=True)
    except OSError as exc:
        raise EvidenceError(f"manifest root is unavailable: {args.root}") from exc
    if not root.is_dir():
        raise EvidenceError("manifest root must be a directory")
    output = args.output.resolve(strict=False)
    try:
        output.relative_to(root)
    except ValueError as exc:
        raise EvidenceError("manifest output must be inside manifest root") from exc

    entries: list[dict[str, object]] = []
    seen: set[str] = set()
    total_bytes = 0
    for path in args.files:
        relative, byte_count, digest = _manifest_file(root, path)
        relative_key = os.path.normcase(relative)
        if relative_key in seen:
            raise EvidenceError(f"duplicate manifest path: {relative}")
        if path.resolve(strict=True) == output:
            raise EvidenceError("manifest draft cannot hash itself")
        seen.add(relative_key)
        total_bytes += byte_count
        entries.append(
            {"bytes": byte_count, "path": relative, "sha256": digest}
        )
    entries.sort(key=lambda entry: str(entry["path"]))
    manifest = {
        "acceptance_eligible": False,
        "evidence_scope": "inventory_only_not_d82_acceptance",
        "file_count": len(entries),
        "files": entries,
        "schema": MANIFEST_SCHEMA,
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "status": "draft",
        "total_bytes": total_bytes,
    }
    manifest_bytes = (
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    _write_new_files(((output, manifest_bytes),), label="manifest")
    print(
        "EVIDENCE_MANIFEST_DRAFT_OK "
        f"files={len(entries)} bytes={total_bytes} acceptance_eligible=false"
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    derive = commands.add_parser("derive", help="derive a bounded MESSAGE log")
    derive.add_argument("--journal", type=Path, required=True)
    derive.add_argument("--pre-start-receipt", type=Path, required=True)
    derive.add_argument("--run-id", required=True)
    derive.add_argument("--start-barrier-id", required=True)
    derive.add_argument("--end-barrier-id", required=True)
    derive.add_argument("--message-output", type=Path, required=True)
    derive.add_argument("--derivation-output", type=Path, required=True)
    derive.set_defaults(handler=_derive)
    manifest = commands.add_parser(
        "manifest", help="create a deterministic relative-path evidence manifest draft"
    )
    manifest.add_argument("--root", type=Path, required=True)
    manifest.add_argument("--output", type=Path, required=True)
    manifest.add_argument("--file", dest="files", type=Path, action="append", required=True)
    manifest.set_defaults(handler=_manifest)
    return parser


def main() -> None:
    args = _parser().parse_args()
    try:
        args.handler(args)
    except EvidenceError as exc:
        _fail(str(exc))


if __name__ == "__main__":
    main()
