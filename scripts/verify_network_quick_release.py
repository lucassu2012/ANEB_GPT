#!/usr/bin/env python3
"""Consume one digest-bound Network Quick READY release."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
from typing import NoReturn

if __package__:
    from scripts import (
        quick_ready_transaction as transaction,
        verify_network_quick_collection as collection_verifier,
        quick_evidence_security as evidence_security,
    )
else:
    import quick_ready_transaction as transaction
    import verify_network_quick_collection as collection_verifier
    import quick_evidence_security as evidence_security


RELEASE_SCHEMA = "aneb-network-quick-evidence-release"
RELEASE_VERSION = "1.0.0"
REPORT_SCHEMA = "aneb-network-quick-release-verification"
REPORT_VERSION = "1.0.0"
READY_RE = re.compile(
    r"^(?P<collection>m0-ec3-network-quick-[0-9]{8}T[0-9]{6}Z-"
    r"[0-9a-f]{32})\.READY\.json$"
)
UTC_RE = transaction.UTC_RE
READY_KEYS = transaction.READY_KEYS
CONTRACT = transaction.QuickReadyContract(
    collection_pattern=collection_verifier.COLLECTION_RE,
    ready_pattern=READY_RE,
    release_schema=RELEASE_SCHEMA,
    release_version=RELEASE_VERSION,
    verification_schema=REPORT_SCHEMA,
    verification_version=REPORT_VERSION,
    publication_schema="aneb-network-quick-ready-publication",
    collection_report_schema=collection_verifier.REPORT_SCHEMA,
    collection_report_version=collection_verifier.REPORT_VERSION,
)


class ReleaseVerificationFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> NoReturn:
    raise ReleaseVerificationFailure(reason_code)


def verify_release(
    ready_path: str | os.PathLike[str],
) -> dict[str, object]:
    adapter = transaction.CollectionModuleAdapter(
        collection_verifier, evidence_security
    )
    try:
        return transaction.verify_release(
            ready_path, contract=CONTRACT, adapter=adapter
        )
    except transaction.QuickReadyFailure as error:
        fail(error.reason_code)


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
        description="Verify one Network Quick READY release", allow_abbrev=False
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
