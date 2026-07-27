#!/usr/bin/env python3
"""Token Quick adapter for the family-neutral READY transaction."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import Callable
from typing import NoReturn

if __package__:
    from scripts import quick_evidence_security as evidence_security
    from scripts import quick_ready_transaction as transaction
    from scripts import verify_token_quick_evidence_release as release_verifier
else:
    import quick_evidence_security as evidence_security
    import quick_ready_transaction as transaction
    import verify_token_quick_evidence_release as release_verifier


RootVerifier = Callable[[Path], object]


class ReadyPublicationFailure(Exception):
    pass


def fail(reason_code: str) -> NoReturn:
    raise ReadyPublicationFailure(reason_code)


class TokenPreverifiedAdapter:
    def __init__(self, root_verifier: RootVerifier) -> None:
        self._root_verifier = root_verifier

    def verify_private_root(self, bundle: Path) -> None:
        try:
            self._root_verifier(bundle.parent)
        except transaction.QuickReadyFailure:
            raise
        except Exception:
            transaction.fail("release_root_security_invalid")

    def verify_collection(self, bundle: Path) -> dict[str, object]:
        transaction.fail("preverified_collection_revalidation_forbidden")


def publish_token_ready(
    bundle_path: str | os.PathLike[str],
    report_path: str | os.PathLike[str],
    *,
    root_verifier: RootVerifier = evidence_security.verify_root,
) -> dict[str, object]:
    try:
        return transaction.publish_preverified_ready(
            bundle_path,
            report_path,
            contract=release_verifier.TOKEN_READY_CONTRACT,
            adapter=TokenPreverifiedAdapter(root_verifier),
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
        description="Publish a preverified Token Quick READY marker",
        allow_abbrev=False,
    )
    parser.add_argument("bundle", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args(argv)
    try:
        result = publish_token_ready(args.bundle, args.report)
    except ReadyPublicationFailure as error:
        _emit(
            {
                "schema": release_verifier.TOKEN_READY_CONTRACT.publication_schema,
                "schema_version": "1.0.0",
                "status": "fail",
                "reason_code": str(error),
            },
            stream=sys.stderr,
        )
        return 1
    _emit(result, stream=sys.stdout)
    return 0


__all__ = (
    "ReadyPublicationFailure",
    "TokenPreverifiedAdapter",
    "main",
    "publish_token_ready",
)


if __name__ == "__main__":
    raise SystemExit(main())
