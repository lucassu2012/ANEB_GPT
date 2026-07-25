#!/usr/bin/env python3
"""Retired compatibility entry point for the shared-release Verifier.

The former Verifier was coupled to SHARED_TEST_STATUS transitions.  Keeping this
fail-closed shell prevents stale commands from probing P40/E-01 or changing state.
"""

from __future__ import annotations

import sys
from collections.abc import Sequence


RETIRED_EXIT_CODE = 78
RETIRED_ERROR = (
    "ERROR code=shared_release_verifier_retired "
    "message=state-coupled release verification was retired by Product Owner on 2026-07-19"
)


def main(argv: Sequence[str] | None = None) -> int:
    del argv
    print(RETIRED_ERROR, file=sys.stderr)
    return RETIRED_EXIT_CODE


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
