#!/usr/bin/env python3
"""Hermetic runtime entry point for ANEB Prototype 0.1 evidence verification."""

from __future__ import annotations

import json
import re
import sys

from validate_contracts import run_cli


_BUILD_SOURCE_COMMIT = "__ANEB_BUILD_SOURCE_COMMIT__"


def _run_build_info(argv: list[str]) -> int | None:
    if argv != ["build-info", "--json"]:
        return None
    print(_build_info_json())
    return 0


def _build_info_json() -> str:
    if re.fullmatch(r"[0-9a-f]{40}", _BUILD_SOURCE_COMMIT) is None:
        raise RuntimeError("evidence runtime source commit is not embedded")
    return json.dumps(
        {
            "schema_version": "aneb-prototype-evidence-build-0.1",
            "source_commit": _BUILD_SOURCE_COMMIT,
        },
        separators=(",", ":"),
        sort_keys=True,
    )


if __name__ == "__main__":
    try:
        args = sys.argv[1:]
        build_info_exit = _run_build_info(args)
        if build_info_exit is not None:
            raise SystemExit(build_info_exit)
        exit_code = run_cli(args, include_fixture_commands=False)
        if exit_code == 0 and len(args) == 3 and args[0] == "verify-bundle" and args[1] == "--bundle":
            print(f"ANEB_EVIDENCE_BUILD_INFO {_build_info_json()}")
        raise SystemExit(exit_code)
    except (AssertionError, RuntimeError, ValueError, KeyError, TypeError, OSError, json.JSONDecodeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
