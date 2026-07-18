#!/usr/bin/env python3
"""Fail closed when tracked ANEB source contains high-confidence credentials.

The scanner reports only the rule, file and line number. It never prints the
matched value, which keeps CI logs from becoming another disclosure channel.
The default CLI scope is Git-tracked files, not build outputs or evidence.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable, Sequence


@dataclass(frozen=True)
class SecretRule:
    rule_id: str
    pattern: re.Pattern[str]


@dataclass(frozen=True)
class Finding:
    rule_id: str
    relative_path: str
    line_number: int


RULES: tuple[SecretRule, ...] = (
    SecretRule("github_classic_token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,255}\b")),
    SecretRule("github_fine_grained_token", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{60,255}\b")),
    SecretRule("anthropic_api_key", re.compile(r"\bsk-ant-[A-Za-z0-9_-]{32,255}\b")),
    SecretRule("openai_api_key", re.compile(r"\bsk-(?!ant-)(?:proj-)?[A-Za-z0-9_-]{32,255}\b")),
    SecretRule("aws_access_key", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    SecretRule("alibaba_access_key", re.compile(r"\bLTAI[A-Za-z0-9]{12,30}\b")),
    SecretRule("google_api_key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    SecretRule("slack_token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,255}\b")),
    SecretRule(
        "pem_private_key",
        re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
    ),
)


def scan_text(relative_path: str, text: str) -> list[Finding]:
    findings: list[Finding] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        for rule in RULES:
            if rule.pattern.search(line):
                findings.append(Finding(rule.rule_id, relative_path, line_number))
    return findings


def tracked_paths(root: Path) -> list[Path]:
    completed = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=False,
        capture_output=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"git ls-files failed: {detail or completed.returncode}")
    names = completed.stdout.decode("utf-8", errors="surrogateescape").split("\0")
    return [root / name for name in names if name]


def staged_relative_paths(root: Path) -> list[str]:
    completed = subprocess.run(
        ["git", "-C", str(root), "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"],
        check=False,
        capture_output=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"git diff --cached failed: {detail or completed.returncode}")
    return [
        name
        for name in completed.stdout.decode("utf-8", errors="surrogateescape").split("\0")
        if name
    ]


def scan_bytes(relative_path: str, data: bytes) -> list[Finding]:
    if b"\0" in data:
        return []
    return scan_text(relative_path, data.decode("utf-8", errors="replace"))


def scan_paths(root: Path, paths: Iterable[Path]) -> list[Finding]:
    root = root.resolve()
    findings: list[Finding] = []
    for path in paths:
        candidate = path if path.is_absolute() else root / path
        try:
            relative_path = candidate.relative_to(root).as_posix()
        except ValueError:
            findings.append(Finding("tracked_path_outside_repository", str(candidate), 0))
            continue
        if candidate.is_symlink():
            findings.append(Finding("tracked_symlink_not_scanned", relative_path, 0))
            continue
        if not candidate.is_file():
            findings.append(Finding("tracked_file_missing", relative_path, 0))
            continue
        findings.extend(scan_bytes(relative_path, candidate.read_bytes()))
    return findings


def scan_staged(root: Path, relative_paths: Iterable[str]) -> list[Finding]:
    findings: list[Finding] = []
    for relative_path in relative_paths:
        completed = subprocess.run(
            ["git", "-C", str(root), "show", f":./{relative_path}"],
            check=False,
            capture_output=True,
        )
        if completed.returncode != 0:
            findings.append(Finding("staged_blob_unreadable", relative_path, 0))
            continue
        findings.extend(scan_bytes(relative_path, completed.stdout))
    return findings


def unique_findings(findings: Iterable[Finding]) -> list[Finding]:
    return sorted(
        set(findings),
        key=lambda finding: (finding.relative_path, finding.line_number, finding.rule_id),
    )


def format_finding(finding: Finding) -> str:
    location = finding.relative_path
    if finding.line_number > 0:
        location = f"{location}:{finding.line_number}"
    return f"secret-scan finding rule={finding.rule_id} location={location}"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Repository root; defaults to the parent of scripts/.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    root = args.root.resolve()
    try:
        paths = tracked_paths(root)
        staged_paths = staged_relative_paths(root)
        findings = unique_findings(
            [*scan_paths(root, paths), *scan_staged(root, staged_paths)]
        )
    except (OSError, RuntimeError) as exc:
        print(f"secret-scan error: {exc}", file=sys.stderr)
        return 2
    if findings:
        for finding in findings:
            print(format_finding(finding), file=sys.stderr)
        print(
            f"ANEB repository secret scan: FAIL ({len(findings)} high-confidence finding(s)); "
            "matched values were redacted",
            file=sys.stderr,
        )
        return 1
    print(
        "ANEB repository secret scan: PASS "
        f"({len(paths)} tracked files, {len(staged_paths)} staged paths rechecked)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
