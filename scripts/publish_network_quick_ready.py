#!/usr/bin/env python3
"""Atomically publish an independently revalidated Network Quick READY."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import NoReturn

if __package__:
    from scripts import (
        quick_ready_transaction as transaction,
        verify_network_quick_collection as collection_verifier,
        verify_network_quick_release as release_verifier,
        quick_evidence_security as evidence_security,
    )
else:
    import quick_ready_transaction as transaction
    import verify_network_quick_collection as collection_verifier
    import verify_network_quick_release as release_verifier
    import quick_evidence_security as evidence_security


class ReadyPublicationFailure(Exception):
    pass


def fail(reason_code: str) -> NoReturn:
    raise ReadyPublicationFailure(reason_code)


def publish_ready(bundle_path: str | os.PathLike[str]) -> dict[str, object]:
    adapter = transaction.CollectionModuleAdapter(
        collection_verifier, evidence_security
    )
    try:
        return transaction.publish_ready(
            bundle_path,
            contract=release_verifier.CONTRACT,
            adapter=adapter,
            release_postcheck=release_verifier.verify_release,
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
        description="Publish a Network Quick verification report and READY marker",
        allow_abbrev=False,
    )
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args(argv)
    try:
        report = publish_ready(args.bundle)
    except ReadyPublicationFailure as error:
        _emit(
            {
                "schema": release_verifier.CONTRACT.publication_schema,
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
