#!/usr/bin/env python3
"""Retired compatibility entry point for the former shared-status state machine.

The file intentionally remains at its historical path so cached automation fails
closed with a stable diagnostic instead of silently reviving the retired workflow.
It never reads or writes a status file and never operates a device or server.
"""

from __future__ import annotations

import sys
from collections.abc import Sequence


RETIRED_EXIT_CODE = 78
RETIRED_ERROR = (
    "ERROR code=shared_test_status_retired "
    "message=SHARED_TEST_STATUS coordination was retired by Product Owner on 2026-07-19"
)


def main(argv: Sequence[str] | None = None) -> int:
    del argv
    print(RETIRED_ERROR, file=sys.stderr)
    return RETIRED_EXIT_CODE


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
