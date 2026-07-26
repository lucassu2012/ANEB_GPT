#!/usr/bin/env python3
"""Atomically publish an independently revalidated Realtime Quick READY."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
from typing import NoReturn

if __package__:
    from scripts import (
        verify_realtime_evidence_security as evidence_security,
        verify_realtime_quick_collection as collection_verifier,
        verify_realtime_quick_release as release_verifier,
    )
else:
    import verify_realtime_evidence_security as evidence_security
    import verify_realtime_quick_collection as collection_verifier
    import verify_realtime_quick_release as release_verifier


class ReadyPublicationFailure(Exception):
    pass


def fail(reason_code: str) -> NoReturn:
    raise ReadyPublicationFailure(reason_code)


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


def _verify_private_root(bundle: Path) -> None:
    try:
        current = evidence_security.verify_root(bundle.parent)
        stored, _ = collection_verifier._load_json(
            bundle / "evidence-root-security.json",
            "evidence_root_security_invalid",
            maximum=64 * 1024,
            require_canonical=True,
        )
        evidence_security.validate_report(bundle.parent, stored)
    except (
        evidence_security.EvidenceSecurityFailure,
        collection_verifier.CollectionVerificationFailure,
    ):
        fail("release_root_security_invalid")
    if current != stored:
        fail("release_root_security_drift")


def publish_ready(
    bundle_path: str | os.PathLike[str],
) -> dict[str, object]:
    bundle = Path(os.path.abspath(os.fspath(bundle_path)))
    match = collection_verifier.COLLECTION_RE.fullmatch(bundle.name)
    if match is None:
        fail("release_bundle_leaf_invalid")
    collection = match.group("collection")
    root = bundle.parent
    report_path = root / f"{collection}.verification.json"
    report_temp = root / f"{collection}.verification.partial"
    ready_path = root / f"{collection}.READY.json"
    ready_temp = root / f"{collection}.ready.partial"
    siblings = (report_path, report_temp, ready_path, ready_temp)
    if any(path.exists() for path in siblings):
        fail("release_path_collision")
    try:
        collection_verifier._assert_directory(root, "release_root_invalid")
        _verify_private_root(bundle)
        report = collection_verifier.verify_collection(bundle)
    except collection_verifier.CollectionVerificationFailure as error:
        fail(f"collection_verification_failed reason={error.reason_code}")
    report_raw = collection_verifier._canonical_json(report)
    created: list[Path] = []
    try:
        _write_exclusive_fsync(report_temp, report_raw)
        created.append(report_temp)
        _publish_no_replace(report_temp, report_path)
        created.remove(report_temp)
        created.append(report_path)
        marker = {
            "schema": release_verifier.RELEASE_SCHEMA,
            "schema_version": release_verifier.RELEASE_VERSION,
            "status": "ready",
            "reason_code": "ok",
            "collection_id": collection,
            "run_id": report["run_id"],
            "mode": report["mode"],
            "bundle_leaf": bundle.name,
            "manifest_sha256": report["manifest_sha256"],
            "verification_report_leaf": report_path.name,
            "verification_report_sha256": collection_verifier._sha256(report_raw),
            "committed_at_utc": _utc_timestamp(),
        }
        marker_raw = collection_verifier._canonical_json(marker)
        _write_exclusive_fsync(ready_temp, marker_raw)
        created.append(ready_temp)
        _publish_no_replace(ready_temp, ready_path)
        created.remove(ready_temp)
        created.append(ready_path)
        try:
            release_report = release_verifier.verify_release(ready_path)
        except release_verifier.ReleaseVerificationFailure as error:
            fail(f"release_postcheck_failed reason={error.reason_code}")
        if (
            release_report.get("status") != "pass"
            or release_report.get("collection_id") != collection
        ):
            fail("release_postcheck_failed reason=report")
        return {
            "schema": "aneb-realtime-quick-ready-publication",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "collection_id": collection,
            "run_id": report["run_id"],
            "mode": report["mode"],
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


def _emit(value: dict[str, object], *, stream: object) -> None:
    print(
        json.dumps(
            value,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ),
        file=stream,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Publish a Realtime Quick verification report and READY marker",
        allow_abbrev=False,
    )
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args(argv)
    try:
        report = publish_ready(args.bundle)
    except ReadyPublicationFailure as error:
        _emit(
            {
                "schema": "aneb-realtime-quick-ready-publication",
                "schema_version": "1.0.0",
                "status": "fail",
                "reason_code": str(error),
            },
            stream=sys.stderr,
        )
        return 1
    _emit(report, stream=sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
