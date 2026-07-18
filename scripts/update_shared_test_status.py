#!/usr/bin/env python3
"""Fail-closed state machine for the Codex/Claude shared test status file.

The command intentionally prints only the transition result. User-supplied task,
resource, note, and evidence text are never echoed to stdout or stderr.

Verifier is a fixed, review-only role. It can independently release or lock a
handoff after pinning the exact raw status-file bytes, but it can never own or
operate shared resources.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import re
import stat
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Sequence, TextIO


IDLE = "空闲"
RUNNING = "进行中"
HANDOFF = "待交接"
LOCKED = "异常锁定"
ALLOWED_STATES = frozenset({IDLE, RUNNING, HANDOFF, LOCKED})
EXECUTORS = {"codex": "Codex", "claude": "Claude", "verifier": "Verifier"}
OPERATORS = frozenset({"Codex", "Claude"})
VERIFIER = "Verifier"
REVIEW_COMMANDS = frozenset({"review-release", "review-lock"})
OUTPUT_STATE = {
    IDLE: "idle",
    RUNNING: "running",
    HANDOFF: "handoff",
    LOCKED: "locked",
}
EMPTY = "无"
REQUIRED_FIELDS = (
    "状态",
    "当前执行者",
    "当前任务",
    "占用资源",
    "开始时间",
    "最近更新",
    "交接说明",
)
CURRENT_HEADER = "## 当前状态"
HISTORY_HEADER = "## 最近记录"
MAX_STATUS_BYTES = 1024 * 1024
SHANGHAI_TZ = timezone(timedelta(hours=8), name="Asia/Shanghai")

_TABLE_ROW = re.compile(r"^\|\s*([^|]+?)\s*\|\s*([^|]*?)\s*\|\s*$")
_SENSITIVE = re.compile(
    r"(?:"
    r"\bgh(?:p|o|u|s|r)_[A-Za-z0-9]{20,}\b|"
    r"\bgithub_pat_[A-Za-z0-9_]{20,}\b|"
    r"\bsk-[A-Za-z0-9_-]{20,}\b|"
    r"\bAIza[A-Za-z0-9_-]{20,}\b|"
    r"\bAKIA[A-Z0-9]{16}\b|"
    r"\b(?:password|passwd|api[_ -]?key|access[_ -]?token|client[_ -]?secret)"
    r"\s*[:=]\s*\S+"
    r")",
    flags=re.IGNORECASE,
)
_LEASE_ID = re.compile(r"^[0-9a-f]{32}$")
_STATUS_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
_LEASE_MARKER = re.compile(r"\[ANEB_LEASE_ID:([0-9a-f]{32})\]")
_LEASE_MARKER_RESERVED = re.compile(r"\[ANEB_LEASE_ID:", flags=re.IGNORECASE)
_RESOURCE_SEPARATOR = re.compile(r"[、,;/]")
_UNSUPPORTED_RESOURCE_SEPARATOR = re.compile(r"[，；|｜]")
_NEGATED_RESOURCE_TOKEN = re.compile(
    r"(?:^(?:no(?:-|\s+|$)|not(?:\s+|$)|without(?:\s+|$)|"
    r"disabled(?:\s+|$)|none$|非|不含)|"
    r"(?:-disabled|\s+disabled)$)",
    flags=re.IGNORECASE,
)


class StatusError(Exception):
    """Expected, non-sensitive state-machine failure."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class Snapshot:
    state: str
    executor: str
    task: str
    resources: str
    started_at: str
    updated_at: str
    note: str


@dataclass
class StatusDocument:
    lines: list[str]
    newline: str
    final_newline: bool
    had_bom: bool
    field_rows: dict[str, int]
    history_insert_at: int
    snapshot: Snapshot

    @classmethod
    def parse(cls, payload: bytes) -> "StatusDocument":
        if not payload or len(payload) > MAX_STATUS_BYTES:
            raise StatusError("status_file_size_invalid")
        had_bom = payload.startswith(b"\xef\xbb\xbf")
        try:
            text = payload.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise StatusError("status_file_not_utf8") from exc
        if "\x00" in text:
            raise StatusError("status_file_contains_nul")

        if "\r\n" in text:
            remainder = text.replace("\r\n", "")
            if "\r" in remainder or "\n" in remainder:
                raise StatusError("status_file_mixed_newlines")
            newline = "\r\n"
        elif "\r" in text:
            raise StatusError("status_file_unsupported_newlines")
        else:
            newline = "\n"

        final_newline = text.endswith(("\n", "\r"))
        lines = text.splitlines()
        if lines.count(CURRENT_HEADER) != 1 or lines.count(HISTORY_HEADER) != 1:
            raise StatusError("status_sections_ambiguous")

        current_header = lines.index(CURRENT_HEADER)
        current_end = cls._next_h2(lines, current_header + 1)
        field_rows: dict[str, int] = {}
        field_values: dict[str, str] = {}
        for index in range(current_header + 1, current_end):
            parsed = _parse_table_row(lines[index])
            if parsed is None:
                continue
            key, value = parsed
            if key not in REQUIRED_FIELDS:
                continue
            if key in field_rows:
                raise StatusError("current_status_field_duplicated")
            field_rows[key] = index
            field_values[key] = value
        if set(field_rows) != set(REQUIRED_FIELDS):
            raise StatusError("current_status_fields_incomplete")

        state = _parse_state(field_values["状态"])
        snapshot = Snapshot(
            state=state,
            executor=field_values["当前执行者"].strip(),
            task=field_values["当前任务"].strip(),
            resources=field_values["占用资源"].strip(),
            started_at=field_values["开始时间"].strip(),
            updated_at=field_values["最近更新"].strip(),
            note=field_values["交接说明"].strip(),
        )
        cls._validate_snapshot(snapshot)

        history_header = lines.index(HISTORY_HEADER)
        history_end = cls._next_h2(lines, history_header + 1)
        history_insert_at = cls._history_insert_index(lines, history_header, history_end)
        return cls(
            lines=lines,
            newline=newline,
            final_newline=final_newline,
            had_bom=had_bom,
            field_rows=field_rows,
            history_insert_at=history_insert_at,
            snapshot=snapshot,
        )

    @staticmethod
    def _next_h2(lines: list[str], start: int) -> int:
        for index in range(start, len(lines)):
            if lines[index].startswith("## "):
                return index
        return len(lines)

    @staticmethod
    def _validate_snapshot(snapshot: Snapshot) -> None:
        if snapshot.state == IDLE:
            if any(
                value != EMPTY
                for value in (
                    snapshot.executor,
                    snapshot.task,
                    snapshot.resources,
                    snapshot.started_at,
                )
            ):
                raise StatusError("idle_status_has_active_owner")
        elif any(
            not value or value == EMPTY
            for value in (
                snapshot.executor,
                snapshot.task,
                snapshot.resources,
                snapshot.started_at,
            )
        ):
            raise StatusError("active_status_fields_incomplete")
        else:
            if snapshot.executor != _canonical_executor(snapshot.executor):
                raise StatusError("executor_role_not_canonical")
            _require_operator(snapshot.executor)
            _validated_resource_list(snapshot.resources)
            _snapshot_lease_marker(snapshot, required=True)

    @staticmethod
    def _history_insert_index(lines: list[str], header: int, end: int) -> int:
        table_header = None
        for index in range(header + 1, end):
            cells = _parse_table_cells(lines[index])
            if cells == ["时间", "执行者", "任务", "结果"]:
                table_header = index
                break
        if table_header is None or table_header + 1 >= end:
            raise StatusError("history_table_missing")
        separator = _parse_table_cells(lines[table_header + 1])
        if separator is None or len(separator) != 4 or any(
            re.fullmatch(r":?-{3,}:?", cell) is None for cell in separator
        ):
            raise StatusError("history_table_separator_invalid")
        insert_at = table_header + 2
        while insert_at < end:
            cells = _parse_table_cells(lines[insert_at])
            if cells is None:
                break
            if len(cells) != 4:
                raise StatusError("history_row_invalid")
            insert_at += 1
        return insert_at

    def transition(
        self,
        command: str,
        *,
        actor: str,
        now: datetime,
        task: str | None = None,
        resources: str | None = None,
        note: str | None = None,
        evidence: str | None = None,
        lease_id: str | None = None,
        review_hash_verified: bool = False,
    ) -> tuple[str, str]:
        timestamp = _shanghai_timestamp(now)
        actor = _canonical_executor(actor)
        before = self.snapshot

        if command in {"claim", "handoff", "lock"}:
            _require_operator(actor)
        if actor == VERIFIER and command in REVIEW_COMMANDS and not review_hash_verified:
            raise StatusError("expected_status_sha256_required")

        if command == "claim":
            if before.state != IDLE:
                raise StatusError("claim_requires_idle")
            lease_value = _validated_lease_id(lease_id)
            lease_marker = _lease_marker(lease_value)
            if lease_marker in "\n".join(self.lines):
                raise StatusError("lease_id_already_used")
            task_value = _validated_value(task, "task", 240)
            resources_value = _validated_resource_list(resources)
            note_value = _validated_value(note, "note", 2000)
            note_with_lease = f"{lease_marker} {note_value}"
            fields = {
                "状态": f"**{RUNNING}**",
                "当前执行者": actor,
                "当前任务": task_value,
                "占用资源": resources_value,
                "开始时间": timestamp,
                "最近更新": timestamp,
                "交接说明": note_with_lease,
            }
            history = (actor, task_value, f"自动接手；状态进行中；{note_with_lease}")
            after = RUNNING
        elif command == "handoff":
            if before.state not in {RUNNING, LOCKED}:
                raise StatusError("handoff_requires_running_or_locked")
            if _identity(actor) != _identity(before.executor):
                raise StatusError("handoff_executor_mismatch")
            lease_value = _validated_lease_id(lease_id)
            if _snapshot_lease_marker(before, required=True) != _lease_marker(
                lease_value
            ):
                raise StatusError("lease_id_mismatch")
            note_value = _validated_value(note, "note", 2000)
            lease_marker = _snapshot_lease_marker(before, required=False)
            note_with_lease = (
                f"{lease_marker} {note_value}" if lease_marker else note_value
            )
            fields = {
                "状态": f"**{HANDOFF}**",
                "最近更新": timestamp,
                "交接说明": note_with_lease,
            }
            result_prefix = (
                "异常已处理并清理；待独立复核"
                if before.state == LOCKED
                else "清理完成；待独立复核"
            )
            history = (actor, before.task, f"{result_prefix}；{note_with_lease}")
            after = HANDOFF
        elif command == "lock":
            note_value = _validated_value(note, "note", 2000)
            lease_value = _validated_lease_id(lease_id)
            if before.state != IDLE:
                if actor != before.executor:
                    raise StatusError("lock_executor_mismatch")
                if _snapshot_lease_marker(before, required=True) != _lease_marker(
                    lease_value
                ):
                    raise StatusError("lease_id_mismatch")
                lease_marker = _snapshot_lease_marker(before, required=True)
            else:
                lease_marker = _lease_marker(lease_value)
                if lease_marker in "\n".join(self.lines):
                    raise StatusError("lease_id_already_used")
            owner = before.executor if before.executor != EMPTY else actor
            task_value = before.task if before.task != EMPTY else "共享测试资源异常锁定"
            resources_value = before.resources if before.resources != EMPTY else "状态待核查"
            started_at = before.started_at if before.started_at != EMPTY else timestamp
            fields = {
                "状态": f"**{LOCKED}**",
                "当前执行者": owner,
                "当前任务": task_value,
                "占用资源": resources_value,
                "开始时间": started_at,
                "最近更新": timestamp,
                "交接说明": (
                    f"{lease_marker} {actor} 触发异常锁定：{note_value}"
                    if lease_marker
                    else f"{actor} 触发异常锁定：{note_value}"
                ),
            }
            history = (actor, task_value, f"异常锁定；{note_value}")
            after = LOCKED
        elif command == "review-lock":
            if before.state != HANDOFF:
                raise StatusError("review_lock_requires_handoff")
            if _identity(actor) == _identity(before.executor):
                raise StatusError("reviewer_must_be_independent")
            evidence_value = _validated_value(evidence, "evidence", 2000)
            lease_marker = _snapshot_lease_marker(before, required=True)
            note_prefix = "" if lease_marker in before.task else f"{lease_marker} "
            fields = {
                "状态": f"**{LOCKED}**",
                "最近更新": timestamp,
                "交接说明": (
                    f"{note_prefix}{actor} 独立复核失败并触发异常锁定：{evidence_value}"
                ),
            }
            history = (
                f"{before.executor} → {actor}",
                before.task,
                f"独立复核失败；异常锁定；{evidence_value}",
            )
            after = LOCKED
        elif command == "review-release":
            if before.state != HANDOFF:
                raise StatusError("review_release_requires_handoff")
            if _identity(actor) == _identity(before.executor):
                raise StatusError("reviewer_must_be_independent")
            evidence_value = _validated_value(evidence, "evidence", 2000)
            fields = {
                "状态": f"**{IDLE}**",
                "当前执行者": EMPTY,
                "当前任务": EMPTY,
                "占用资源": EMPTY,
                "开始时间": EMPTY,
                "最近更新": timestamp,
                "交接说明": evidence_value,
            }
            history = (
                f"{before.executor} → {actor}",
                before.task,
                f"独立复核通过；当前空闲；{evidence_value}",
            )
            after = IDLE
        else:
            raise StatusError("unknown_command")

        for key, value in fields.items():
            self.lines[self.field_rows[key]] = f"| {key} | {value} |"
        history_line = "| " + " | ".join(
            _markdown_cell(value) for value in (timestamp, *history)
        ) + " |"
        self.lines.insert(self.history_insert_at, history_line)
        return before.state, after

    def assert_lease(self, *, actor: str, lease_id: str, resource: str) -> None:
        """Verify the current lease without mutating the shared status file."""

        actor = _canonical_executor(actor)
        _require_operator(actor)
        lease_value = _validated_lease_id(lease_id)
        resource_value = _validated_resource_token(resource)
        if self.snapshot.state != RUNNING:
            raise StatusError("lease_requires_running")
        if actor != self.snapshot.executor:
            raise StatusError("lease_executor_mismatch")
        if _snapshot_lease_marker(self.snapshot, required=True) != _lease_marker(
            lease_value
        ):
            raise StatusError("lease_id_mismatch")
        if not _resource_is_present(self.snapshot.resources, resource_value):
            raise StatusError("lease_resource_mismatch")

    def to_bytes(self) -> bytes:
        text = self.newline.join(self.lines)
        if self.final_newline:
            text += self.newline
        payload = text.encode("utf-8")
        if self.had_bom:
            payload = b"\xef\xbb\xbf" + payload
        return payload


class SidecarLock:
    """Cross-platform fail-fast lock used by all cooperating state writers."""

    def __init__(self, status_file: Path) -> None:
        self.path = status_file.with_name(status_file.name + ".aneb-status.lock")
        self.fd: int | None = None

    def __enter__(self) -> "SidecarLock":
        try:
            self.fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            payload = (
                f"pid={os.getpid()}\n"
                f"created={datetime.now(tz=SHANGHAI_TZ).strftime('%Y-%m-%d %H:%M:%S')}\n"
            ).encode("ascii")
            os.write(self.fd, payload)
            os.fsync(self.fd)
        except FileExistsError as exc:
            raise StatusError("status_file_locked") from exc
        except OSError as exc:
            if self.fd is not None:
                os.close(self.fd)
                self.fd = None
            try:
                self.path.unlink(missing_ok=True)
            except OSError:
                pass
            raise StatusError("lock_creation_failed") from exc
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass
        except OSError as unlink_error:
            if exc is None:
                raise StatusError("lock_cleanup_failed") from unlink_error


def default_status_file() -> Path:
    """Return <project-root>/SHARED_TEST_STATUS.md from this nested worktree."""

    repository = Path(__file__).resolve().parents[1]
    return repository.parent.parent / "SHARED_TEST_STATUS.md"


def _parse_table_row(line: str) -> tuple[str, str] | None:
    match = _TABLE_ROW.fullmatch(line)
    if match is None:
        return None
    return match.group(1).strip(), match.group(2).strip()


def _parse_table_cells(line: str) -> list[str] | None:
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return None
    return [cell.strip() for cell in stripped[1:-1].split("|")]


def _parse_state(value: str) -> str:
    value = value.strip()
    if value.startswith("**") or value.endswith("**"):
        if not (value.startswith("**") and value.endswith("**") and len(value) > 4):
            raise StatusError("status_markup_invalid")
        value = value[2:-2].strip()
    if value not in ALLOWED_STATES:
        raise StatusError("status_value_unknown")
    return value


def _identity(value: str) -> str:
    return " ".join(value.split()).casefold()


def _canonical_executor(value: str | None) -> str:
    normalized = _validated_value(value, "executor", 80)
    try:
        return EXECUTORS[_identity(normalized)]
    except KeyError as exc:
        raise StatusError("executor_role_invalid") from exc


def _require_operator(actor: str) -> str:
    if actor not in OPERATORS:
        raise StatusError("verifier_operation_forbidden")
    return actor


def _validated_status_sha256(value: str | None) -> str:
    if value is None or _STATUS_SHA256.fullmatch(value) is None:
        raise StatusError("expected_status_sha256_invalid")
    return value.lower()


def _verify_review_baseline(
    *, command: str, actor: str, expected: str | None, baseline: bytes
) -> bool:
    """Verify an optional review pin against raw bytes while the sidecar is held."""

    actor = _canonical_executor(actor)
    if command not in REVIEW_COMMANDS:
        return False
    if actor == VERIFIER and expected is None:
        raise StatusError("expected_status_sha256_required")
    if expected is None:
        return False
    expected_value = _validated_status_sha256(expected)
    if not hmac.compare_digest(expected_value, _sha256(baseline)):
        raise StatusError("expected_status_sha256_mismatch")
    return True


def _validated_lease_id(value: str | None) -> str:
    try:
        normalized = _validated_value(value, "lease_id", 64).lower()
    except StatusError as exc:
        raise StatusError("lease_id_invalid") from exc
    if _LEASE_ID.fullmatch(normalized) is None:
        raise StatusError("lease_id_invalid")
    return normalized


def _lease_marker(lease_id: str) -> str:
    return f"[ANEB_LEASE_ID:{lease_id}]"


def _snapshot_lease_marker(snapshot: Snapshot, *, required: bool) -> str:
    matches = _LEASE_MARKER.findall(f"{snapshot.task}\n{snapshot.note}")
    unique = set(matches)
    if not matches:
        if required:
            raise StatusError("lease_id_missing")
        return ""
    if len(matches) != 1 or len(unique) != 1:
        raise StatusError("lease_id_ambiguous")
    return _lease_marker(matches[0])


def _resource_is_present(resources: str, expected: str) -> bool:
    tokens = _resource_tokens(resources)
    return _identity(expected) in {_identity(token) for token in tokens}


def _validated_resource_list(value: str | None) -> str:
    normalized = _validated_value(value, "resources", 240)
    _resource_tokens(normalized)
    return normalized


def _validated_resource_token(value: str | None) -> str:
    normalized = _validated_value(value, "resource", 80)
    tokens = _resource_tokens(normalized)
    if len(tokens) != 1 or tokens[0] != normalized:
        raise StatusError("resource_assertion_must_be_single_token")
    return normalized


def _resource_tokens(value: str) -> tuple[str, ...]:
    if _UNSUPPORTED_RESOURCE_SEPARATOR.search(value):
        raise StatusError("resource_separator_invalid")
    raw_tokens = _RESOURCE_SEPARATOR.split(value)
    tokens = tuple(token.strip() for token in raw_tokens)
    if not tokens or any(not token for token in tokens):
        raise StatusError("resource_token_empty")
    if any(_NEGATED_RESOURCE_TOKEN.search(token) for token in tokens):
        raise StatusError("resource_token_negated")
    identities = tuple(_identity(token) for token in tokens)
    if len(set(identities)) != len(identities):
        raise StatusError("resource_token_duplicated")
    return tokens


def _validated_value(value: str | None, field: str, limit: int) -> str:
    del field  # Error output deliberately never includes caller-controlled field data.
    if value is None:
        raise StatusError("required_value_missing")
    normalized = " ".join(value.split())
    if not normalized or normalized == EMPTY:
        raise StatusError("required_value_empty")
    if len(normalized) > limit:
        raise StatusError("value_too_long")
    if any(ord(character) < 32 for character in normalized):
        raise StatusError("value_contains_control_character")
    if _SENSITIVE.search(normalized):
        raise StatusError("sensitive_input_rejected")
    if _LEASE_MARKER_RESERVED.search(normalized):
        raise StatusError("lease_marker_reserved")
    return _markdown_cell(normalized)


def _markdown_cell(value: str) -> str:
    return " ".join(value.split()).replace("|", "｜")


def _shanghai_timestamp(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=SHANGHAI_TZ)
    return value.astimezone(SHANGHAI_TZ).strftime("%Y-%m-%d %H:%M")


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def atomic_replace_if_unchanged(path: Path, baseline: bytes, replacement: bytes) -> None:
    """Atomically replace path only if it still matches the locked baseline."""

    try:
        current = path.read_bytes()
        metadata = path.stat()
    except OSError as exc:
        raise StatusError("status_file_read_failed") from exc
    if _sha256(current) != _sha256(baseline) or current != baseline:
        raise StatusError("concurrent_status_change")
    if path.is_symlink() or not stat.S_ISREG(metadata.st_mode):
        raise StatusError("status_file_not_regular")

    temporary: Path | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
        )
        temporary = Path(temporary_name)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(replacement)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, stat.S_IMODE(metadata.st_mode))

        # Detect manual or non-cooperating writes that happened while the temp
        # file was being prepared. os.replace is the single commit point.
        latest = path.read_bytes()
        if _sha256(latest) != _sha256(baseline) or latest != baseline:
            raise StatusError("concurrent_status_change")
        os.replace(temporary, path)
        temporary = None
        if os.name != "nt":
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    except StatusError:
        raise
    except OSError as exc:
        raise StatusError("atomic_write_failed") from exc
    finally:
        if temporary is not None:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "原子更新 Codex / Claude 共享测试状态；Verifier 仅可独立复核"
            "（失败时不改文件）。"
        ),
        allow_abbrev=False,
    )
    _add_common_arguments(parser)
    commands = parser.add_subparsers(dest="command", required=True)

    claim = commands.add_parser("claim", help="仅执行 空闲 → 进行中。")
    _add_common_arguments(claim, suppress_defaults=True)
    claim.add_argument("--executor", required=True)
    claim.add_argument("--task", required=True)
    claim.add_argument("--resources", required=True)
    claim.add_argument("--note", required=True)
    claim.add_argument(
        "--lease-id",
        required=True,
        help="128-bit lowercase/uppercase hexadecimal lease id generated for this claim.",
    )

    handoff = commands.add_parser(
        "handoff", help="仅由当前执行者执行 进行中/异常锁定 → 待交接。"
    )
    _add_common_arguments(handoff, suppress_defaults=True)
    handoff.add_argument("--executor", required=True)
    handoff.add_argument("--note", required=True)
    handoff.add_argument("--lease-id", required=True)

    lock = commands.add_parser("lock", help="状态不明或清理失败时进入异常锁定。")
    _add_common_arguments(lock, suppress_defaults=True)
    lock.add_argument("--executor", required=True)
    lock.add_argument("--note", required=True)
    lock.add_argument(
        "--lease-id",
        required=True,
        help="Current active lease, or a fresh lease for an idle emergency lock.",
    )

    review = commands.add_parser(
        "review-release",
        help="仅由另一 Codex/Claude 或 Verifier 独立复核后执行 待交接 → 空闲。",
    )
    _add_common_arguments(review, suppress_defaults=True)
    review.add_argument("--executor", required=True, help="独立复核执行者。")
    review.add_argument("--evidence", required=True)
    review.add_argument(
        "--expected-status-sha256",
        help="Verifier 必填：进入 sidecar 锁后核对的原始状态文件 SHA-256。",
    )

    review_lock = commands.add_parser(
        "review-lock", help="独立复核失败时仅执行 待交接 → 异常锁定。"
    )
    _add_common_arguments(review_lock, suppress_defaults=True)
    review_lock.add_argument("--executor", required=True, help="独立复核执行者。")
    review_lock.add_argument("--evidence", required=True)
    review_lock.add_argument(
        "--expected-status-sha256",
        help="Verifier 必填：进入 sidecar 锁后核对的原始状态文件 SHA-256。",
    )

    assertion = commands.add_parser(
        "assert-lease", help="只读验证当前执行者、本次 lease-id 和占用资源。"
    )
    _add_common_arguments(assertion, suppress_defaults=True)
    assertion.add_argument("--executor", required=True)
    assertion.add_argument("--lease-id", required=True)
    assertion.add_argument("--resource", required=True)
    return parser


def _add_common_arguments(
    parser: argparse.ArgumentParser, *, suppress_defaults: bool = False
) -> None:
    default_path: Path | str = (
        argparse.SUPPRESS if suppress_defaults else default_status_file()
    )
    default_dry_run: bool | str = argparse.SUPPRESS if suppress_defaults else False
    parser.add_argument(
        "--status-file",
        type=Path,
        default=default_path,
        help="共享状态文件；默认定位到当前工作树外两级的项目根目录。",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=default_dry_run,
        help="验证转换但不写入状态文件。",
    )


def execute(args: argparse.Namespace, *, now: datetime | None = None) -> tuple[str, str, str]:
    path = args.status_file
    if path.is_symlink():
        raise StatusError("status_file_not_regular")
    try:
        path = path.resolve(strict=True)
    except OSError as exc:
        raise StatusError("status_file_missing") from exc
    if not path.is_file():
        raise StatusError("status_file_not_regular")

    with SidecarLock(path):
        try:
            baseline = path.read_bytes()
        except OSError as exc:
            raise StatusError("status_file_read_failed") from exc
        review_hash_verified = _verify_review_baseline(
            command=args.command,
            actor=args.executor,
            expected=getattr(args, "expected_status_sha256", None),
            baseline=baseline,
        )
        document = StatusDocument.parse(baseline)
        if args.command == "assert-lease":
            document.assert_lease(
                actor=args.executor,
                lease_id=args.lease_id,
                resource=args.resource,
            )
            return document.snapshot.state, document.snapshot.state, "verified"
        before, after = document.transition(
            args.command,
            actor=args.executor,
            now=now or datetime.now(tz=SHANGHAI_TZ),
            task=getattr(args, "task", None),
            resources=getattr(args, "resources", None),
            note=getattr(args, "note", None),
            evidence=getattr(args, "evidence", None),
            lease_id=getattr(args, "lease_id", None),
            review_hash_verified=review_hash_verified,
        )
        if not args.dry_run:
            atomic_replace_if_unchanged(path, baseline, document.to_bytes())
        return before, after, "dry-run" if args.dry_run else "written"


def main(
    argv: Sequence[str] | None = None,
    *,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
) -> int:
    stdout = stdout or sys.stdout
    stderr = stderr or sys.stderr
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
        before, after, mode = execute(args)
    except StatusError as exc:
        print(f"ERROR code={exc.code}", file=stderr)
        return 2
    print(
        f"OK command={args.command} "
        f"transition={OUTPUT_STATE[before]}->{OUTPUT_STATE[after]} mode={mode}",
        file=stdout,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
