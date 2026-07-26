#!/usr/bin/env python3
"""Consume one digest-bound AI Realtime Quick READY release."""

from __future__ import annotations

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sys
from typing import NoReturn

if __package__:
    from scripts import (
        verify_realtime_evidence_security as evidence_security,
        verify_realtime_quick_collection as collection_verifier,
    )
else:
    import verify_realtime_evidence_security as evidence_security
    import verify_realtime_quick_collection as collection_verifier


RELEASE_SCHEMA = "aneb-realtime-quick-evidence-release"
RELEASE_VERSION = "1.0.0"
REPORT_SCHEMA = "aneb-realtime-quick-release-verification"
REPORT_VERSION = "1.0.0"
READY_RE = re.compile(
    r"^(?P<collection>m0-ec2-realtime-[0-9]{8}T[0-9]{6}Z-"
    r"[0-9a-f]{32})\.READY\.json$"
)
UTC_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:"
    r"[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$"
)
READY_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "collection_id",
        "run_id",
        "mode",
        "bundle_leaf",
        "manifest_sha256",
        "verification_report_leaf",
        "verification_report_sha256",
        "committed_at_utc",
    }
)


class ReleaseVerificationFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> NoReturn:
    raise ReleaseVerificationFailure(reason_code)


def _load_json(
    path: Path,
    *,
    maximum: int,
    reason: str,
) -> tuple[dict[str, object], bytes]:
    try:
        return collection_verifier._load_json(
            path,
            reason,
            maximum=maximum,
            require_canonical=True,
        )
    except collection_verifier.CollectionVerificationFailure as error:
        if error.reason_code == "collection_path_reparse_forbidden":
            fail("release_path_reparse_forbidden")
        fail(reason)


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


def verify_release(
    ready_path: str | os.PathLike[str],
) -> dict[str, object]:
    ready = Path(os.path.abspath(os.fspath(ready_path)))
    leaf_match = READY_RE.fullmatch(ready.name)
    if leaf_match is None:
        fail("release_ready_leaf_invalid")
    try:
        collection_verifier._assert_directory(
            ready.parent,
            "release_root_invalid",
        )
    except collection_verifier.CollectionVerificationFailure:
        fail("release_root_invalid")
    marker, ready_raw = _load_json(
        ready,
        maximum=64 * 1024,
        reason="release_ready_invalid",
    )
    collection = leaf_match.group("collection")
    if (
        set(marker) != set(READY_KEYS)
        or marker.get("schema") != RELEASE_SCHEMA
        or marker.get("schema_version") != RELEASE_VERSION
        or marker.get("status") != "ready"
        or marker.get("reason_code") != "ok"
        or marker.get("collection_id") != collection
        or marker.get("mode") not in {"positive", "negative"}
        or not isinstance(marker.get("run_id"), str)
        or collection_verifier.RUN_ID_RE.fullmatch(str(marker["run_id"])) is None
        or marker.get("bundle_leaf") != f"{collection}.complete"
        or marker.get("verification_report_leaf")
        != f"{collection}.verification.json"
        or not isinstance(marker.get("manifest_sha256"), str)
        or collection_verifier.SHA256_RE.fullmatch(
            str(marker["manifest_sha256"])
        )
        is None
        or not isinstance(marker.get("verification_report_sha256"), str)
        or collection_verifier.SHA256_RE.fullmatch(
            str(marker["verification_report_sha256"])
        )
        is None
    ):
        fail("release_ready_contract_invalid")
    timestamp = marker.get("committed_at_utc")
    if not isinstance(timestamp, str) or UTC_RE.fullmatch(timestamp) is None:
        fail("release_ready_timestamp_invalid")
    try:
        datetime.strptime(timestamp[:26] + "Z", "%Y-%m-%dT%H:%M:%S.%fZ")
    except ValueError:
        fail("release_ready_timestamp_invalid")

    bundle = ready.parent / str(marker["bundle_leaf"])
    report_path = ready.parent / str(marker["verification_report_leaf"])
    _verify_private_root(bundle)
    report, report_raw = _load_json(
        report_path,
        maximum=4 * 1024 * 1024,
        reason="release_report_invalid",
    )
    if collection_verifier._sha256(report_raw) != marker["verification_report_sha256"]:
        fail("release_report_digest_mismatch")
    if (
        report.get("schema") != collection_verifier.REPORT_SCHEMA
        or report.get("schema_version") != collection_verifier.REPORT_VERSION
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("collection_id") != collection
        or report.get("run_id") != marker["run_id"]
        or report.get("mode") != marker["mode"]
        or report.get("manifest_sha256") != marker["manifest_sha256"]
    ):
        fail("release_report_binding_mismatch")
    try:
        recomputed = collection_verifier.verify_collection(bundle)
    except collection_verifier.CollectionVerificationFailure:
        fail("release_collection_revalidation_failed")
    if recomputed != report:
        fail("release_collection_revalidation_mismatch")

    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "collection_id": collection,
        "run_id": marker["run_id"],
        "mode": marker["mode"],
        "bundle_leaf": marker["bundle_leaf"],
        "manifest_sha256": marker["manifest_sha256"],
        "verification_report_leaf": marker["verification_report_leaf"],
        "verification_report_sha256": marker["verification_report_sha256"],
        "ready_sha256": collection_verifier._sha256(ready_raw),
    }


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
        description="Verify one AI Realtime Quick READY release",
        allow_abbrev=False,
    )
    parser.add_argument("ready", type=Path)
    args = parser.parse_args(argv)
    try:
        report = verify_release(args.ready)
    except ReleaseVerificationFailure as error:
        _emit(
            {
                "schema": REPORT_SCHEMA,
                "schema_version": REPORT_VERSION,
                "status": "fail",
                "reason_code": error.reason_code,
            },
            stream=sys.stderr,
        )
        return 1
    _emit(report, stream=sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
