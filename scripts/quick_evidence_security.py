#!/usr/bin/env python3
"""Family-neutral read-only private-root security verifier for Quick evidence."""

from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
from typing import Any, NoReturn


SCHEMA = "aneb-realtime-private-evidence-root-security"
SCHEMA_VERSION = "1.0.0"
SYSTEM_SID = "S-1-5-18"
ADMINISTRATORS_SID = "S-1-5-32-544"

WRITE_DATA = 0x00000002
APPEND_DATA = 0x00000004
WRITE_EXTENDED_ATTRIBUTES = 0x00000010
DELETE_SUBDIRECTORIES_AND_FILES = 0x00000040
WRITE_ATTRIBUTES = 0x00000100
DELETE = 0x00010000
CHANGE_PERMISSIONS = 0x00040000
TAKE_OWNERSHIP = 0x00080000
WRITE_MASK = (
    WRITE_DATA
    | APPEND_DATA
    | WRITE_EXTENDED_ATTRIBUTES
    | DELETE_SUBDIRECTORIES_AND_FILES
    | WRITE_ATTRIBUTES
    | DELETE
    | CHANGE_PERMISSIONS
    | TAKE_OWNERSHIP
)


class EvidenceSecurityFailure(ValueError):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> NoReturn:
    raise EvidenceSecurityFailure(reason_code)


def _root_binding(path: Path, *, platform: str) -> str:
    absolute = os.path.abspath(os.fspath(path))
    if platform == "windows":
        absolute = os.path.normcase(absolute).replace("/", "\\")
    return hashlib.sha256(absolute.encode("utf-8")).hexdigest()


def _assert_directory_chain(path: Path) -> Path:
    absolute = Path(os.path.abspath(os.fspath(path)))
    current = absolute
    while True:
        try:
            metadata = current.lstat()
        except OSError:
            fail("evidence_root_path_invalid")
        attributes = int(getattr(metadata, "st_file_attributes", 0))
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or stat.S_ISLNK(metadata.st_mode)
            or attributes & 0x400
        ):
            fail("evidence_root_reparse_forbidden")
        parent = current.parent
        if parent == current:
            break
        current = parent
    try:
        return absolute.resolve(strict=True)
    except OSError:
        fail("evidence_root_path_invalid")


def _read_windows_observation(path: Path) -> dict[str, Any]:
    path_value = base64.b64encode(str(path).encode("utf-8")).decode("ascii")
    script = rf"""
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$path = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String('{path_value}')
)
$acl = Get-Acl -LiteralPath $path
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function Resolve-AnEbSid([Security.Principal.IdentityReference]$identity) {{
    return $identity.Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
}}
$ownerIdentity = [Security.Principal.NTAccount]::new([string]$acl.Owner)
try {{
    $ownerSid = [Security.Principal.SecurityIdentifier]::new(
        [string]$acl.Owner
    ).Value
}} catch {{
    $ownerSid = Resolve-AnEbSid $ownerIdentity
}}
$rules = @()
foreach ($rule in $acl.Access) {{
    if ($rule.AccessControlType -ne
        [Security.AccessControl.AccessControlType]::Allow) {{
        continue
    }}
    $rules += [pscustomobject]@{{
        identity = Resolve-AnEbSid $rule.IdentityReference
        rights = [int64]$rule.FileSystemRights
    }}
}}
[pscustomobject]@{{
    platform = 'windows'
    current_identity = $currentSid
    owner_identity = $ownerSid
    allow_rules = @($rules)
}} | ConvertTo-Json -Compress -Depth 5
"""
    encoded = base64.b64encode(script.encode("utf-16le")).decode("ascii")
    executable = (
        os.environ.get("SystemRoot", r"C:\Windows")
        + r"\System32\WindowsPowerShell\v1.0\powershell.exe"
    )
    try:
        result = subprocess.run(
            [
                executable,
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-EncodedCommand",
                encoded,
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        fail("evidence_root_acl_unavailable")
    if result.returncode != 0:
        fail("evidence_root_acl_unavailable")
    try:
        value = json.loads(result.stdout.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("evidence_root_acl_observation_invalid")
    if not isinstance(value, dict):
        fail("evidence_root_acl_observation_invalid")
    return value


def verify_root(root: str | os.PathLike[str]) -> dict[str, object]:
    path = _assert_directory_chain(Path(root))
    if os.name == "nt":
        observation = _read_windows_observation(path)
    else:
        try:
            metadata = path.stat()
            current_uid = os.geteuid()
        except (AttributeError, OSError):
            fail("evidence_root_acl_unavailable")
        observation = {
            "platform": "posix",
            "current_identity": f"uid:{current_uid}",
            "owner_identity": f"uid:{metadata.st_uid}",
            "mode_octal": f"{stat.S_IMODE(metadata.st_mode):04o}",
        }
    return evaluate_observation(path, observation)


def validate_report(
    root: str | os.PathLike[str],
    report: dict[str, Any],
) -> None:
    common_keys = {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "platform",
        "path_sha256",
        "current_identity",
        "owner_identity",
        "allowed_writer_identities",
        "observed_writer_identities",
    }
    if (
        not isinstance(report, dict)
        or report.get("platform") not in {"windows", "posix"}
        or set(report)
        != (
            common_keys
            | ({"mode_octal"} if report.get("platform") == "posix" else set())
        )
        or report.get("schema") != SCHEMA
        or report.get("schema_version") != SCHEMA_VERSION
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or not isinstance(report.get("current_identity"), str)
        or not report["current_identity"]
        or report.get("owner_identity") != report["current_identity"]
        or not isinstance(report.get("allowed_writer_identities"), list)
        or not isinstance(report.get("observed_writer_identities"), list)
    ):
        fail("evidence_root_security_report_invalid")
    platform = str(report["platform"])
    if report.get("path_sha256") != _root_binding(
        Path(root),
        platform=platform,
    ):
        fail("evidence_root_security_report_invalid")
    current = str(report["current_identity"])
    allowed = report["allowed_writer_identities"]
    observed = report["observed_writer_identities"]
    if (
        any(not isinstance(value, str) or not value for value in allowed)
        or any(not isinstance(value, str) or not value for value in observed)
        or allowed != sorted(set(allowed))
        or observed != sorted(set(observed))
        or not set(observed).issubset(set(allowed))
    ):
        fail("evidence_root_security_report_invalid")
    if platform == "windows":
        expected = sorted({current, SYSTEM_SID, ADMINISTRATORS_SID})
        if allowed != expected:
            fail("evidence_root_security_report_invalid")
    elif (
        report.get("mode_octal") != "0700"
        or allowed != [current]
        or observed != [current]
    ):
        fail("evidence_root_security_report_invalid")


def evaluate_observation(
    root: str | os.PathLike[str],
    observation: dict[str, Any],
) -> dict[str, object]:
    path = Path(os.path.abspath(os.fspath(root)))
    if (
        isinstance(observation, dict)
        and observation.get("platform") == "posix"
    ):
        if (
            set(observation)
            != {
                "platform",
                "current_identity",
                "owner_identity",
                "mode_octal",
            }
            or not isinstance(observation.get("current_identity"), str)
            or not observation["current_identity"]
            or not isinstance(observation.get("owner_identity"), str)
            or not isinstance(observation.get("mode_octal"), str)
        ):
            fail("evidence_root_acl_observation_invalid")
        current = str(observation["current_identity"])
        owner = str(observation["owner_identity"])
        if owner != current:
            fail("evidence_root_owner_invalid")
        if observation["mode_octal"] != "0700":
            fail("evidence_root_mode_too_permissive")
        return {
            "schema": SCHEMA,
            "schema_version": SCHEMA_VERSION,
            "status": "pass",
            "reason_code": "ok",
            "platform": "posix",
            "path_sha256": _root_binding(path, platform="posix"),
            "current_identity": current,
            "owner_identity": owner,
            "allowed_writer_identities": [current],
            "observed_writer_identities": [current],
            "mode_octal": "0700",
        }
    if (
        not isinstance(observation, dict)
        or set(observation)
        != {"platform", "current_identity", "owner_identity", "allow_rules"}
        or observation.get("platform") != "windows"
        or not isinstance(observation.get("current_identity"), str)
        or not observation["current_identity"]
        or not isinstance(observation.get("owner_identity"), str)
        or not isinstance(observation.get("allow_rules"), list)
    ):
        fail("evidence_root_acl_observation_invalid")
    current = observation["current_identity"]
    owner = observation["owner_identity"]
    if owner != current:
        fail("evidence_root_owner_invalid")
    allowed = frozenset({current, SYSTEM_SID, ADMINISTRATORS_SID})
    observed_writers: set[str] = set()
    for rule in observation["allow_rules"]:
        if (
            not isinstance(rule, dict)
            or set(rule) != {"identity", "rights"}
            or not isinstance(rule.get("identity"), str)
            or type(rule.get("rights")) is not int
        ):
            fail("evidence_root_acl_observation_invalid")
        identity = str(rule["identity"])
        rights = int(rule["rights"])
        if rights & WRITE_MASK:
            observed_writers.add(identity)
            if identity not in allowed:
                fail("evidence_root_acl_too_permissive")
    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "platform": "windows",
        "path_sha256": _root_binding(path, platform="windows"),
        "current_identity": current,
        "owner_identity": owner,
        "allowed_writer_identities": sorted(allowed),
        "observed_writer_identities": sorted(observed_writers),
    }
