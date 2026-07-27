#!/usr/bin/env python3
"""Token Quick adapter for the family-neutral READY transaction."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Callable

if __package__:
    from scripts import quick_evidence_security as evidence_security
    from scripts import quick_ready_transaction as transaction
    from scripts import verify_token_quick_evidence_release as release_verifier
else:
    import quick_evidence_security as evidence_security
    import quick_ready_transaction as transaction
    import verify_token_quick_evidence_release as release_verifier


RootVerifier = Callable[[Path], object]


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
    return transaction.publish_preverified_ready(
        bundle_path,
        report_path,
        contract=release_verifier.TOKEN_READY_CONTRACT,
        adapter=TokenPreverifiedAdapter(root_verifier),
        release_postcheck=release_verifier.verify_release,
    )


__all__ = ("TokenPreverifiedAdapter", "publish_token_ready")
