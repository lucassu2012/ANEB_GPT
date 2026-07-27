#!/usr/bin/env python3
"""Family-neutral D-87 READY publication and release verification.

Business evidence remains owned by a family adapter.  This module owns only
the mechanical transaction: canonical report publication, digest-bound READY
commit, current-root verification, collection recomputation, and rollback of
new sibling artifacts when post-checking fails.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, Callable, NoReturn, Protocol


READY_BASE_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "collection_id",
        "run_id",
        "bundle_leaf",
        "manifest_sha256",
        "verification_report_leaf",
        "verification_report_sha256",
        "committed_at_utc",
    }
)
# Public compatibility surface for the existing Realtime/Network wrappers.
# Family-specific contracts derive their key set from READY_BASE_KEYS instead.
READY_KEYS = frozenset((*READY_BASE_KEYS, "mode"))
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
UTC_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:"
    r"[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$"
)
REPARSE_ATTRIBUTE = 0x400


@dataclass(frozen=True)
class QuickReadyContract:
    collection_pattern: re.Pattern[str]
    ready_pattern: re.Pattern[str]
    release_schema: str
    release_version: str
    verification_schema: str
    verification_version: str
    publication_schema: str
    collection_report_schema: str
    collection_report_version: str
    mode_field: str = "mode"
    mode_values: frozenset[str] = frozenset({"positive", "negative"})

    def __post_init__(self) -> None:
        if (
            re.fullmatch(r"[a-z][a-z0-9_]{0,63}", self.mode_field) is None
            or self.mode_field in READY_BASE_KEYS
            or not isinstance(self.mode_values, frozenset)
            or not self.mode_values
            or any(
                not isinstance(value, str)
                or not value
                or len(value) > 64
                or re.fullmatch(r"[a-z][a-z0-9_]*", value) is None
                for value in self.mode_values
            )
        ):
            raise ValueError("quick_ready_mode_contract_invalid")


def ready_keys(contract: QuickReadyContract) -> frozenset[str]:
    """Return the exact READY key set for one immutable family contract."""

    return frozenset((*READY_BASE_KEYS, contract.mode_field))


def ready_marker_failure(
    marker: object,
    collection: str,
    *,
    contract: QuickReadyContract,
) -> str | None:
    """Classify family-neutral READY failures without choosing reason codes."""

    if not isinstance(marker, dict) or set(marker) != set(ready_keys(contract)):
        return "keys"
    if (
        marker.get("schema") != contract.release_schema
        or marker.get("schema_version") != contract.release_version
        or marker.get("status") != "ready"
        or marker.get("reason_code") != "ok"
    ):
        return "contract"
    if (
        marker.get("collection_id") != collection
        or contract.collection_pattern.fullmatch(f"{collection}.complete") is None
        or not isinstance(marker.get("run_id"), str)
        or RUN_ID_RE.fullmatch(str(marker["run_id"])) is None
        or marker.get(contract.mode_field) not in contract.mode_values
    ):
        return "identity"
    if (
        marker.get("bundle_leaf") != f"{collection}.complete"
        or marker.get("verification_report_leaf")
        != f"{collection}.verification.json"
        or not isinstance(marker.get("manifest_sha256"), str)
        or SHA256_RE.fullmatch(str(marker["manifest_sha256"])) is None
        or not isinstance(marker.get("verification_report_sha256"), str)
        or SHA256_RE.fullmatch(str(marker["verification_report_sha256"])) is None
    ):
        return "binding"
    timestamp = marker.get("committed_at_utc")
    if not isinstance(timestamp, str) or UTC_RE.fullmatch(timestamp) is None:
        return "timestamp"
    try:
        datetime.strptime(timestamp[:26] + "Z", "%Y-%m-%dT%H:%M:%S.%fZ")
    except ValueError:
        return "timestamp"
    return None


class QuickReadyAdapter(Protocol):
    def verify_private_root(self, bundle: Path) -> None: ...

    def verify_collection(self, bundle: Path) -> dict[str, object]: ...


class CollectionModuleAdapter:
    """Adapter from one family's collection/security modules to the seam."""

    def __init__(self, collection_verifier: object, evidence_security: object) -> None:
        self._collection_verifier = collection_verifier
        self._evidence_security = evidence_security

    def verify_private_root(self, bundle: Path) -> None:
        try:
            current = self._evidence_security.verify_root(bundle.parent)
            stored, _ = self._collection_verifier._load_json(
                bundle / "evidence-root-security.json",
                "evidence_root_security_invalid",
                maximum=64 * 1024,
                require_canonical=True,
            )
            self._evidence_security.validate_report(bundle.parent, stored)
        except Exception:
            fail("release_root_security_invalid")
        if current != stored:
            fail("release_root_security_drift")

    def verify_collection(self, bundle: Path) -> dict[str, object]:
        return self._collection_verifier.verify_collection(bundle)


class QuickReadyFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> NoReturn:
    raise QuickReadyFailure(reason_code)


def _canonical_json(value: object) -> bytes:
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


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _is_reparse(metadata: os.stat_result) -> bool:
    return stat.S_ISLNK(metadata.st_mode) or bool(
        int(getattr(metadata, "st_file_attributes", 0)) & REPARSE_ATTRIBUTE
    )


def _assert_directory(path: Path, reason: str) -> None:
    absolute = Path(os.path.abspath(os.fspath(path)))
    for component in reversed((absolute, *absolute.parents)):
        try:
            metadata = component.lstat()
        except OSError:
            fail(reason)
        if _is_reparse(metadata):
            fail("release_path_reparse_forbidden")
        if not stat.S_ISDIR(metadata.st_mode):
            fail(reason)


def _read_regular(path: Path, *, maximum: int, reason: str) -> bytes:
    try:
        before = path.lstat()
        if _is_reparse(before):
            fail("release_path_reparse_forbidden")
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size <= 0
            or before.st_size > maximum
        ):
            fail(reason)
        with path.open("rb") as stream:
            opened = os.fstat(stream.fileno())
            if (
                _is_reparse(opened)
                or not stat.S_ISREG(opened.st_mode)
                or (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino)
            ):
                fail(reason)
            raw = stream.read(maximum + 1)
        after = path.lstat()
    except QuickReadyFailure:
        raise
    except OSError:
        fail(reason)
    if (
        len(raw) > maximum
        or len(raw) != before.st_size
        or (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        != (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        or _is_reparse(after)
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


def _load_canonical_json(
    path: Path, *, maximum: int, reason: str
) -> tuple[dict[str, object], bytes]:
    raw = _read_regular(path, maximum=maximum, reason=reason)
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_object,
            parse_constant=lambda item: (_ for _ in ()).throw(ValueError(item)),
        )
    except (UnicodeError, ValueError, json.JSONDecodeError, RecursionError):
        fail(reason)
    if not isinstance(value, dict) or _canonical_json(value) != raw:
        fail(f"{reason}_noncanonical")
    return value, raw


def _write_exclusive_fsync(path: Path, raw: bytes) -> None:
    try:
        descriptor = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0),
            0o600,
        )
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError:
        fail("release_path_collision")
    except OSError:
        fail("release_write_failed")


def _publish_no_replace(temporary: Path, final: Path) -> None:
    linked = False
    try:
        if os.name == "nt":
            os.rename(temporary, final)
        else:
            os.link(temporary, final)
            linked = True
            temporary.unlink()
    except FileExistsError:
        fail("release_path_collision")
    except OSError:
        if linked:
            try:
                final.unlink()
            except FileNotFoundError:
                pass
        fail("release_atomic_publish_failed")


def _utc_timestamp() -> str:
    base = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")
    return base + "0Z"


def verify_release(
    ready_path: str | os.PathLike[str],
    *,
    contract: QuickReadyContract,
    adapter: QuickReadyAdapter,
) -> dict[str, object]:
    ready = Path(os.path.abspath(os.fspath(ready_path)))
    leaf_match = contract.ready_pattern.fullmatch(ready.name)
    if leaf_match is None:
        fail("release_ready_leaf_invalid")
    _assert_directory(ready.parent, "release_root_invalid")
    marker, ready_raw = _load_canonical_json(
        ready, maximum=64 * 1024, reason="release_ready_invalid"
    )
    collection = leaf_match.group("collection")
    marker_failure = ready_marker_failure(marker, collection, contract=contract)
    if marker_failure == "timestamp":
        fail("release_ready_timestamp_invalid")
    if marker_failure is not None:
        fail("release_ready_contract_invalid")

    bundle = ready.parent / str(marker["bundle_leaf"])
    report_path = ready.parent / str(marker["verification_report_leaf"])
    adapter.verify_private_root(bundle)
    report, report_raw = _load_canonical_json(
        report_path,
        maximum=4 * 1024 * 1024,
        reason="release_report_invalid",
    )
    if _sha256(report_raw) != marker["verification_report_sha256"]:
        fail("release_report_digest_mismatch")
    if (
        report.get("schema") != contract.collection_report_schema
        or report.get("schema_version") != contract.collection_report_version
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("collection_id") != collection
        or report.get("run_id") != marker["run_id"]
        or report.get(contract.mode_field) != marker[contract.mode_field]
        or report.get("manifest_sha256") != marker["manifest_sha256"]
    ):
        fail("release_report_binding_mismatch")
    try:
        recomputed = adapter.verify_collection(bundle)
    except QuickReadyFailure:
        raise
    except Exception:
        fail("release_collection_revalidation_failed")
    if recomputed != report:
        fail("release_collection_revalidation_mismatch")
    return {
        "schema": contract.verification_schema,
        "schema_version": contract.verification_version,
        "status": "pass",
        "reason_code": "ok",
        "collection_id": collection,
        "run_id": marker["run_id"],
        contract.mode_field: marker[contract.mode_field],
        "bundle_leaf": marker["bundle_leaf"],
        "manifest_sha256": marker["manifest_sha256"],
        "verification_report_leaf": marker["verification_report_leaf"],
        "verification_report_sha256": marker["verification_report_sha256"],
        "ready_sha256": _sha256(ready_raw),
    }


def publish_ready(
    bundle_path: str | os.PathLike[str],
    *,
    contract: QuickReadyContract,
    adapter: QuickReadyAdapter,
    release_postcheck: Callable[[Path], dict[str, object]] | None = None,
) -> dict[str, object]:
    bundle = Path(os.path.abspath(os.fspath(bundle_path)))
    match = contract.collection_pattern.fullmatch(bundle.name)
    if match is None:
        fail("release_bundle_leaf_invalid")
    collection = match.group("collection")
    root = bundle.parent
    report_path = root / f"{collection}.verification.json"
    report_temp = root / f"{collection}.verification.partial"
    ready_path = root / f"{collection}.READY.json"
    ready_temp = root / f"{collection}.ready.partial"
    if any(path.exists() for path in (report_path, report_temp, ready_path, ready_temp)):
        fail("release_path_collision")
    _assert_directory(root, "release_root_invalid")
    adapter.verify_private_root(bundle)
    try:
        report = adapter.verify_collection(bundle)
    except QuickReadyFailure:
        raise
    except Exception as error:
        reason = getattr(error, "reason_code", str(error))
        fail(f"collection_verification_failed reason={reason}")
    report_raw = _canonical_json(report)
    created: list[Path] = []
    try:
        _write_exclusive_fsync(report_temp, report_raw)
        created.append(report_temp)
        _publish_no_replace(report_temp, report_path)
        created.remove(report_temp)
        created.append(report_path)
        marker = {
            "schema": contract.release_schema,
            "schema_version": contract.release_version,
            "status": "ready",
            "reason_code": "ok",
            "collection_id": collection,
            "run_id": report["run_id"],
            contract.mode_field: report[contract.mode_field],
            "bundle_leaf": bundle.name,
            "manifest_sha256": report["manifest_sha256"],
            "verification_report_leaf": report_path.name,
            "verification_report_sha256": _sha256(report_raw),
            "committed_at_utc": _utc_timestamp(),
        }
        _write_exclusive_fsync(ready_temp, _canonical_json(marker))
        created.append(ready_temp)
        _publish_no_replace(ready_temp, ready_path)
        created.remove(ready_temp)
        created.append(ready_path)
        if release_postcheck is None:
            release_report = verify_release(
                ready_path, contract=contract, adapter=adapter
            )
        else:
            try:
                release_report = release_postcheck(ready_path)
            except Exception as error:
                reason = getattr(error, "reason_code", str(error))
                fail(f"release_postcheck_failed reason={reason}")
        if (
            release_report.get("status") != "pass"
            or release_report.get("collection_id") != collection
        ):
            fail("release_postcheck_failed reason=report")
        return {
            "schema": contract.publication_schema,
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "collection_id": collection,
            "run_id": report["run_id"],
            contract.mode_field: report[contract.mode_field],
            "verification_report_path": str(report_path),
            "verification_report_sha256": marker["verification_report_sha256"],
            "ready_path": str(ready_path),
            "ready_sha256": release_report["ready_sha256"],
        }
    except BaseException:
        for path in reversed(created):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        for path in (report_temp, ready_temp):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        raise


__all__ = (
    "QuickReadyAdapter",
    "QuickReadyContract",
    "QuickReadyFailure",
    "CollectionModuleAdapter",
    "publish_ready",
    "ready_keys",
    "ready_marker_failure",
    "verify_release",
)
