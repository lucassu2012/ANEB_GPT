#!/usr/bin/env python3
"""Revalidate one published AI Realtime Quick collection.

This verifier is intentionally a separate consumer of collector output.  It
does not contact the phone, E-01, GitHub, or any other network endpoint.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
from typing import Any, Callable, NoReturn
import urllib.parse
import uuid

SCRIPT_DIRECTORY = str(Path(__file__).resolve().parent)
if SCRIPT_DIRECTORY not in sys.path:
    sys.path.insert(0, SCRIPT_DIRECTORY)

if __package__:
    from scripts import (
        verify_realtime_evidence_security as evidence_security,
        verify_realtime_quick_evidence_bundle as cross_verifier,
    )
else:
    import verify_realtime_evidence_security as evidence_security
    import verify_realtime_quick_evidence_bundle as cross_verifier


REPORT_SCHEMA = "aneb-realtime-quick-collection-verification"
REPORT_VERSION = "1.0.0"
PROFILE_CONTRACT = "ai_realtime_voice_quick@1.1.1"
EXPECTED_SERVER_VERSION = "aneb-server/0.8.1"
EXPECTED_PACKAGE = "com.aneb.probe.codex"
EXPECTED_VERSION_NAME = "0.5.13-codex"
EXPECTED_VERSION_CODE = 45
NEGATIVE_DEVICE_PORT = 18765
NEGATIVE_REQUIRED_PATHS = frozenset(
    {
        "negative-proxy/upstream-serverinfo.raw",
        "negative-proxy/filtered-serverinfo.json",
        "negative-proxy/upstream-serverinfo.headers.json",
        "negative-proxy/peer-certificate.sha256",
        "negative-proxy/request-ledger.json",
        "negative-proxy/proxy-receipt.json",
        "negative-proxy.stdout.jsonl",
        "negative-proxy.stderr.txt",
        "negative-proxy-delivery-receipt.json",
        "adb-reverse-preflight.txt",
        "adb-reverse-active.txt",
        "adb-reverse-before-remove.txt",
        "adb-reverse-final.txt",
    }
)
MAX_JSON_BYTES = 32 * 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_APK_BYTES = 256 * 1024 * 1024
MAX_TEXT_BYTES = 16 * 1024 * 1024
REPARSE_ATTRIBUTE = 0x400

COLLECTION_RE = re.compile(
    r"^(?P<collection>m0-ec2-realtime-"
    r"(?P<stamp>[0-9]{8}T[0-9]{6}Z)-[0-9a-f]{32})\.complete$"
)
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
UUID4_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
REMOTE_CURSOR_RE = re.compile(r"^[A-Za-z0-9;:_.=-]{10,1024}$")
COMPONENT_RE = re.compile(
    r"(?P<package>[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"
    r"/(?P<activity>[A-Za-z0-9_.$]+)"
)
TUNNEL_INTERFACE_RE = re.compile(
    r"^(?:tun[0-9]*|tap[0-9]*|wg[0-9A-Za-z_.-]*|"
    r"wireguard[0-9A-Za-z_.-]*)$",
    re.IGNORECASE,
)

RELEVANT_PACKAGES = (
    "com.aneb.probe.codex",
    "com.aneb.probe",
    "com.aneb.experiencelab",
    "com.moonshot.kimichat",
    "com.moonshot.kimiclaw",
    "com.deepseek.chat",
    "com.larus.nova",
    "com.aliyun.tongyi",
    "com.ss.android.ugc.aweme",
    "com.wireguard.android",
    "com.emanuelef.remote_capture",
    "com.pcapdroid.mitm",
)
LAUNCHER_COMPONENT = "com.huawei.android.launcher/.unihome.UniHomeLauncher"
DEVICE_PROPERTY_KEYS = (
    "ro.serialno",
    "ro.boot.serialno",
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.product.device",
    "ro.product.name",
    "ro.build.fingerprint",
    "ro.build.version.security_patch",
    "ro.boot.verifiedbootstate",
    "ro.boot.vbmeta.device_state",
    "ro.boot.flash.locked",
    "ro.boot.veritymode",
)
OPTIONAL_DEVICE_PROPERTY_KEYS = frozenset(
    {
        "ro.serialno",
        "ro.boot.serialno",
        "ro.boot.verifiedbootstate",
        "ro.boot.vbmeta.device_state",
        "ro.boot.flash.locked",
        "ro.boot.veritymode",
    }
)
REMOTE_KEYS = (
    "boot_id",
    "systemd_invocation_id",
    "main_pid",
    "server_binary_sha256",
    "eth0_qdisc_sha256",
    "firewall_full_sha256",
    "firewall_v4_sha256",
    "firewall_v6_sha256",
    "firewall_nft_sha256",
    "docker_sha256",
    "journal_cursor",
)
REMOTE_STABLE_KEYS = tuple(key for key in REMOTE_KEYS if key != "journal_cursor")
CI_CANDIDATE_NAMES = frozenset(
    {
        "ANEB-Probe-0.5.13-codex-debug.apk",
        "build-manifest.json",
        "checksums.sha256",
        "provenance.sigstore.json",
        "ANEB-安装说明.txt",
    }
)
PLAN_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "collection_id",
        "profile_contract",
        "evidence_mode",
        "transport",
        "package_name",
        "version_name",
        "version_code",
        "server_base",
        "client_server_base",
        "negative_upstream_url",
        "remote_host",
        "expected_server_version",
        "expected_server_binary_sha256",
        "expected_apk_sha256",
        "expected_signer_sha256",
        "source_commit",
        "workflow_run_id",
        "server_ca_sha256",
        "device_policy_sha256",
        "adb_serial_sha256",
        "start_barrier_id",
        "end_barrier_id",
        "run_timeout_seconds",
        "lock_ttl_seconds",
        "install_candidate",
    }
)
STATUS_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "collection_id",
        "run_id",
        "mode",
        "cleanup_phone",
        "cleanup_remote",
    }
)
RUN_RECEIPT_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "collection_id",
        "run_id",
        "mode",
        "terminal_status",
        "contract_status",
        "cross_bound_report_sha256",
        "cross_bound",
    }
)


class CollectionVerificationFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> NoReturn:
    raise CollectionVerificationFailure(reason_code)


def _is_reparse(metadata: os.stat_result) -> bool:
    return stat.S_ISLNK(metadata.st_mode) or bool(
        int(getattr(metadata, "st_file_attributes", 0)) & REPARSE_ATTRIBUTE
    )


def _assert_directory(path: Path, reason: str) -> None:
    absolute = Path(os.path.abspath(os.fspath(path)))
    for component in reversed((absolute, *absolute.parents)):
        try:
            metadata = component.lstat()
        except OSError:
            fail(reason)
        if _is_reparse(metadata):
            fail("collection_path_reparse_forbidden")
        if not stat.S_ISDIR(metadata.st_mode):
            fail(reason)


def _read_regular(
    path: Path,
    *,
    maximum: int,
    reason: str,
    allow_empty: bool = False,
) -> bytes:
    try:
        before = path.lstat()
        if _is_reparse(before):
            fail("collection_path_reparse_forbidden")
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size > maximum
            or (before.st_size == 0 and not allow_empty)
        ):
            fail(reason)
        with path.open("rb") as stream:
            opened = os.fstat(stream.fileno())
            if (
                _is_reparse(opened)
                or not stat.S_ISREG(opened.st_mode)
                or (opened.st_dev, opened.st_ino)
                != (before.st_dev, before.st_ino)
            ):
                fail(reason)
            raw = stream.read(maximum + 1)
        after = path.lstat()
    except CollectionVerificationFailure:
        raise
    except OSError:
        fail(reason)
    if (
        len(raw) > maximum
        or len(raw) != before.st_size
        or (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        != (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        or _is_reparse(after)
    ):
        fail(reason)
    return raw


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _load_json(
    path: Path,
    reason: str,
    *,
    maximum: int = MAX_JSON_BYTES,
    require_canonical: bool = True,
) -> tuple[dict[str, Any], bytes]:
    raw = _read_regular(path, maximum=maximum, reason=reason)
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_object,
            parse_constant=lambda item: (_ for _ in ()).throw(ValueError(item)),
        )
    except (UnicodeError, ValueError, json.JSONDecodeError, RecursionError):
        fail(reason)
    if not isinstance(value, dict):
        fail(reason)
    if require_canonical and _canonical_json(value) != raw:
        fail(f"{reason}_noncanonical")
    return value, raw


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _sha256_file(path: Path, *, maximum: int = MAX_APK_BYTES) -> str:
    return _sha256(
        _read_regular(path, maximum=maximum, reason="manifest_file_unreadable")
    )


def _exact(value: object, keys: frozenset[str] | set[str]) -> bool:
    return isinstance(value, dict) and set(value) == set(keys)


def _safe_relative(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        fail("manifest_path_invalid")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        fail("manifest_path_invalid")
    return value


def _verify_manifest(
    bundle: Path,
    *,
    expected_schema: str = "aneb-realtime-quick-evidence-manifest",
) -> tuple[dict[str, Any], str]:
    manifest, raw = _load_json(
        bundle / "evidence-manifest.json",
        "manifest_invalid",
        maximum=MAX_MANIFEST_BYTES,
    )
    if (
        not _exact(manifest, {"schema", "schema_version", "files"})
        or manifest.get("schema") != expected_schema
        or manifest.get("schema_version") != "1.0.0"
        or not isinstance(manifest.get("files"), list)
    ):
        fail("manifest_contract_invalid")

    actual: dict[str, Path] = {}
    try:
        for path in sorted(bundle.rglob("*")):
            metadata = path.lstat()
            if _is_reparse(metadata):
                fail("collection_path_reparse_forbidden")
            if stat.S_ISREG(metadata.st_mode):
                relative = path.relative_to(bundle).as_posix()
                if relative not in {"evidence-manifest.json", "COMPLETE"}:
                    actual[relative] = path
            elif not stat.S_ISDIR(metadata.st_mode):
                fail("collection_entry_type_invalid")
    except CollectionVerificationFailure:
        raise
    except OSError:
        fail("manifest_inventory_unreadable")

    records = manifest["files"]
    observed: list[str] = []
    for record in records:
        if (
            not _exact(record, {"path", "bytes", "sha256"})
            or type(record.get("bytes")) is not int
            or int(record["bytes"]) < 0
            or not isinstance(record.get("sha256"), str)
            or SHA256_RE.fullmatch(str(record["sha256"])) is None
        ):
            fail("manifest_record_invalid")
        relative = _safe_relative(record["path"])
        if relative in observed or relative not in actual:
            fail("manifest_record_invalid")
        path = actual[relative]
        payload = _read_regular(
            path,
            maximum=MAX_APK_BYTES,
            reason="manifest_file_unreadable",
            allow_empty=True,
        )
        if len(payload) != record["bytes"] or _sha256(payload) != record["sha256"]:
            fail("manifest_file_binding_mismatch")
        observed.append(relative)
    if observed != list(actual):
        fail("manifest_coverage_mismatch")
    return manifest, _sha256(raw)


def _validate_uuid(value: object, *, version: int, reason: str) -> str:
    pattern = RUN_ID_RE if version == 7 else UUID4_RE
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        fail(reason)
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        fail(reason)
    if parsed.version != version or parsed.variant != uuid.RFC_4122:
        fail(reason)
    return value


def _validate_server_base(value: object, reason: str) -> urllib.parse.SplitResult:
    if not isinstance(value, str):
        fail(reason)
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        fail(reason)
    try:
        port = parsed.port
    except ValueError:
        fail(reason)
    if port is not None and not 1 <= port <= 65535:
        fail(reason)
    return parsed


def _same_host(left: str, right: str) -> bool:
    try:
        return ipaddress.ip_address(left) == ipaddress.ip_address(right)
    except ValueError:
        return left.casefold() == right.casefold()


def _validate_plan(plan: dict[str, Any], collection: str) -> None:
    if (
        not _exact(plan, PLAN_KEYS)
        or plan.get("schema") != "aneb-realtime-quick-collector-plan"
        or plan.get("schema_version") != "1.0.0"
        or plan.get("collection_id") != collection
        or plan.get("profile_contract") != PROFILE_CONTRACT
        or plan.get("evidence_mode") not in {"positive", "negative"}
        or plan.get("transport") not in {"auto", "wifi", "cellular"}
        or plan.get("package_name") != EXPECTED_PACKAGE
        or plan.get("version_name") != EXPECTED_VERSION_NAME
        or plan.get("version_code") != EXPECTED_VERSION_CODE
        or plan.get("expected_server_version") != EXPECTED_SERVER_VERSION
        or not isinstance(plan.get("expected_server_binary_sha256"), str)
        or SHA256_RE.fullmatch(plan["expected_server_binary_sha256"]) is None
        or not isinstance(plan.get("expected_apk_sha256"), str)
        or SHA256_RE.fullmatch(plan["expected_apk_sha256"]) is None
        or not isinstance(plan.get("expected_signer_sha256"), str)
        or SHA256_RE.fullmatch(plan["expected_signer_sha256"]) is None
        or not isinstance(plan.get("source_commit"), str)
        or COMMIT_RE.fullmatch(plan["source_commit"]) is None
        or type(plan.get("workflow_run_id")) is not int
        or int(plan["workflow_run_id"]) <= 0
        or not isinstance(plan.get("server_ca_sha256"), str)
        or SHA256_RE.fullmatch(plan["server_ca_sha256"]) is None
        or not isinstance(plan.get("device_policy_sha256"), str)
        or SHA256_RE.fullmatch(plan["device_policy_sha256"]) is None
        or not isinstance(plan.get("adb_serial_sha256"), str)
        or SHA256_RE.fullmatch(plan["adb_serial_sha256"]) is None
        or type(plan.get("run_timeout_seconds")) is not int
        or not 60 <= int(plan["run_timeout_seconds"]) <= 1800
        or type(plan.get("lock_ttl_seconds")) is not int
        or not 120 <= int(plan["lock_ttl_seconds"]) <= 3600
        or int(plan["lock_ttl_seconds"]) <= int(plan["run_timeout_seconds"])
        or type(plan.get("install_candidate")) is not bool
    ):
        fail("collector_plan_invalid")
    _validate_uuid(
        plan.get("start_barrier_id"),
        version=4,
        reason="collector_plan_invalid",
    )
    _validate_uuid(
        plan.get("end_barrier_id"),
        version=4,
        reason="collector_plan_invalid",
    )
    server = _validate_server_base(plan.get("server_base"), "collector_plan_invalid")
    remote_host = plan.get("remote_host")
    if (
        not isinstance(remote_host, str)
        or not remote_host
        or not _same_host(str(server.hostname), remote_host)
    ):
        fail("collector_plan_invalid")
    mode = plan["evidence_mode"]
    if mode == "positive":
        if (
            plan.get("client_server_base") != plan["server_base"]
            or plan.get("negative_upstream_url") is not None
        ):
            fail("collector_plan_invalid")
    else:
        expected_client = f"http://127.0.0.1:{NEGATIVE_DEVICE_PORT}"
        expected_upstream = (
            f"https://{server.hostname}:{server.port or 443}/api/v1/serverinfo"
        )
        if (
            plan.get("client_server_base") != expected_client
            or plan.get("negative_upstream_url") != expected_upstream
        ):
            fail("collector_plan_invalid")


def _validate_status_and_run(
    status: dict[str, Any],
    receipt: dict[str, Any],
    *,
    collection: str,
    plan: dict[str, Any],
    cross_raw: bytes,
) -> str:
    if (
        not _exact(status, STATUS_KEYS)
        or status.get("schema") != "aneb-realtime-quick-collector-status"
        or status.get("schema_version") != "1.0.0"
        or status.get("status") != "pass"
        or status.get("reason_code") != "ok"
        or status.get("collection_id") != collection
        or status.get("mode") != plan["evidence_mode"]
        or status.get("cleanup_phone") != "pass"
        or status.get("cleanup_remote") != "pass"
    ):
        fail("collector_status_invalid")
    run_id = _validate_uuid(
        status.get("run_id"),
        version=7,
        reason="collector_status_invalid",
    )
    if (
        not _exact(receipt, RUN_RECEIPT_KEYS)
        or receipt.get("schema") != "aneb-realtime-quick-run-receipt"
        or receipt.get("schema_version") != "1.0.0"
        or receipt.get("status") != "pass"
        or receipt.get("reason_code") != "ok"
        or receipt.get("collection_id") != collection
        or receipt.get("run_id") != run_id
        or receipt.get("mode") != plan["evidence_mode"]
        or receipt.get("cross_bound") is not True
        or receipt.get("cross_bound_report_sha256") != _sha256(cross_raw)
    ):
        fail("run_receipt_invalid")
    if plan["evidence_mode"] == "positive":
        if (
            receipt.get("contract_status") != "authorized"
            or receipt.get("terminal_status") != "completed"
        ):
            fail("run_receipt_invalid")
    elif (
        receipt.get("contract_status") != "rejected"
        or receipt.get("terminal_status") != "contract_rejected"
    ):
        fail("run_receipt_invalid")
    return run_id


def _validate_candidate_report(
    report: dict[str, Any],
    *,
    source_commit: str,
    candidate_apk_name: str = "ANEB-Probe-0.5.13-codex-debug.apk",
    expected_package: str = EXPECTED_PACKAGE,
    expected_version_name: str = EXPECTED_VERSION_NAME,
    expected_version_code: int = EXPECTED_VERSION_CODE,
) -> tuple[str, int, str]:
    root_keys = {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "candidate_provenance_reverified",
        "repository",
        "signer_workflow",
        "predicate_type",
        "source_commit",
        "source_ref",
        "workflow_run_id",
        "workflow_run_url",
        "apk",
        "files",
        "gh",
    }
    if (
        not _exact(report, root_keys)
        or report.get("schema") != "aneb-ci-apk-provenance-report"
        or report.get("schema_version") != "1.0.0"
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("candidate_provenance_reverified") is not True
        or report.get("repository") != "lucassu2012/ANEB_GPT"
        or report.get("signer_workflow")
        != "lucassu2012/ANEB_GPT/.github/workflows/ci.yml"
        or report.get("predicate_type") != "https://slsa.dev/provenance/v1"
        or report.get("source_commit") != source_commit
        or not isinstance(report.get("source_ref"), str)
        or re.fullmatch(r"refs/(?:heads|tags)/[^\r\n]{1,512}", report["source_ref"])
        is None
        or type(report.get("workflow_run_id")) is not int
        or int(report["workflow_run_id"]) <= 0
        or report.get("workflow_run_url")
        != (
            "https://github.com/lucassu2012/ANEB_GPT/actions/runs/"
            f"{report.get('workflow_run_id')}"
        )
    ):
        fail("candidate_report_invalid")
    apk = report["apk"]
    files = report["files"]
    gh = report["gh"]
    if (
        not _exact(
            apk,
            {
                "file_name",
                "sha256",
                "size_bytes",
                "package_name",
                "version_name",
                "version_code",
                "signer_sha256",
            },
        )
        or not _exact(
            files,
            {
                "attestation_bundle_sha256",
                "build_manifest_sha256",
                "checksums_sha256",
            },
        )
        or not _exact(
            gh,
            {
                "version",
                "executable_sha256",
                "certificate_issuer",
                "oidc_issuer",
                "runner_environment",
                "run_invocation_uri",
                "subject_alternative_name",
                "verified_timestamp_count",
            },
        )
    ):
        fail("candidate_report_invalid")
    if (
        apk.get("file_name") != candidate_apk_name
        or apk.get("package_name") != expected_package
        or apk.get("version_name") != expected_version_name
        or apk.get("version_code") != expected_version_code
        or type(apk.get("size_bytes")) is not int
        or not 0 < int(apk["size_bytes"]) <= MAX_APK_BYTES
        or not isinstance(apk.get("sha256"), str)
        or SHA256_RE.fullmatch(apk["sha256"]) is None
        or not isinstance(apk.get("signer_sha256"), str)
        or SHA256_RE.fullmatch(apk["signer_sha256"]) is None
        or any(
            not isinstance(files.get(key), str)
            or SHA256_RE.fullmatch(files[key]) is None
            for key in files
        )
        or not isinstance(gh.get("version"), str)
        or re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", gh["version"]) is None
        or not isinstance(gh.get("executable_sha256"), str)
        or SHA256_RE.fullmatch(gh["executable_sha256"]) is None
        or gh.get("runner_environment") != "github-hosted"
        or type(gh.get("verified_timestamp_count")) is not int
        or int(gh["verified_timestamp_count"]) <= 0
    ):
        fail("candidate_report_invalid")
    return str(apk["sha256"]), int(apk["size_bytes"]), str(apk["signer_sha256"])


def _verify_candidate(
    bundle: Path,
    plan: dict[str, Any],
    *,
    candidate_apk_name: str = "ANEB-Probe-0.5.13-codex-debug.apk",
    candidate_names: frozenset[str] = CI_CANDIDATE_NAMES,
    expected_package: str = EXPECTED_PACKAGE,
    expected_version_name: str = EXPECTED_VERSION_NAME,
    expected_version_code: int = EXPECTED_VERSION_CODE,
) -> None:
    reports = [
        _load_json(bundle / leaf, "candidate_report_invalid")[0]
        for leaf in (
            "ci-source-before.json",
            "ci-candidate-verification.json",
            "ci-source-after.json",
        )
    ]
    if reports[0] != reports[1] or reports[0] != reports[2]:
        fail("candidate_report_drift")
    apk_sha, apk_size, signer_sha = _validate_candidate_report(
        reports[0],
        source_commit=plan["source_commit"],
        candidate_apk_name=candidate_apk_name,
        expected_package=expected_package,
        expected_version_name=expected_version_name,
        expected_version_code=expected_version_code,
    )
    if (
        reports[0]["workflow_run_id"] != plan["workflow_run_id"]
        or apk_sha != plan["expected_apk_sha256"]
        or signer_sha != plan["expected_signer_sha256"]
    ):
        fail("candidate_plan_binding_mismatch")

    candidate = bundle / "ci-candidate"
    _assert_directory(candidate, "candidate_directory_invalid")
    try:
        names = {path.name for path in candidate.iterdir()}
    except OSError:
        fail("candidate_directory_invalid")
    if names != candidate_names:
        fail("candidate_file_set_invalid")
    candidate_apk = candidate / candidate_apk_name
    if (
        candidate_apk.stat().st_size != apk_size
        or _sha256_file(candidate_apk) != apk_sha
    ):
        fail("candidate_apk_binding_mismatch")
    digest_map = {
        "attestation_bundle_sha256": "provenance.sigstore.json",
        "build_manifest_sha256": "build-manifest.json",
        "checksums_sha256": "checksums.sha256",
    }
    report_files = reports[0]["files"]
    for field, leaf in digest_map.items():
        if _sha256_file(candidate / leaf, maximum=MAX_TEXT_BYTES) != report_files[field]:
            fail("candidate_auxiliary_binding_mismatch")

    installed = _read_regular(
        bundle / "installed-base.apk",
        maximum=MAX_APK_BYTES,
        reason="installed_apk_invalid",
    )
    if len(installed) != apk_size or _sha256(installed) != apk_sha:
        fail("installed_apk_binding_mismatch")
    package_dump = _read_regular(
        bundle / "installed-package.txt",
        maximum=MAX_TEXT_BYTES,
        reason="installed_package_invalid",
    )
    try:
        package_text = package_dump.decode("utf-8", errors="strict")
    except UnicodeError:
        fail("installed_package_invalid")
    version_codes = re.findall(r"(?m)^\s*versionCode=([0-9]+)\b", package_text)
    version_names = re.findall(
        r"(?m)^\s*versionName=([^\r\n]+)\r?$",
        package_text,
    )
    if (
        not version_codes
        or any(int(value) != expected_version_code for value in version_codes)
        or not version_names
        or any(value.strip() != expected_version_name for value in version_names)
    ):
        fail("installed_package_invalid")
    install_path = bundle / "adb-install.txt"
    if plan["install_candidate"]:
        install = _read_regular(
            install_path,
            maximum=MAX_TEXT_BYTES,
            reason="install_receipt_invalid",
        )
        try:
            install_text = install.decode("utf-8", errors="strict")
        except UnicodeError:
            fail("install_receipt_invalid")
        if "Success" not in install_text.splitlines():
            fail("install_receipt_invalid")
    elif install_path.exists():
        fail("install_receipt_unexpected")


def _canonical_component(value: str) -> str:
    match = COMPONENT_RE.search(value)
    if match is None:
        fail("phone_state_invalid")
    package = match.group("package")
    activity = match.group("activity")
    if activity.startswith("."):
        activity = package + activity
    return f"{package}/{activity}"


def _active_vpn(connectivity: str) -> bool:
    for line in connectivity.splitlines():
        if "NetworkAgentInfo{" not in line:
            continue
        if re.search(r"(?i)(?:Transports?:\s*VPN|type:\s*VPN)", line) is None:
            continue
        active = re.search(
            r"(?i)(?:state:\s*CONNECTED(?:/CONNECTED)?|"
            r"CONNECTED/CONNECTED|\bVALIDATED\b)",
            line,
        )
        disconnected = re.search(
            r"(?i)(?:state:\s*DISCONNECTED|DISCONNECTED/DISCONNECTED)",
            line,
        )
        if active is not None and disconnected is None:
            return True
    return False


def _is_empty_service_dump(value: str) -> bool:
    normalized = value.strip()
    return normalized in {"", "(nothing)", "No services"} or (
        re.fullmatch(
            r"ACTIVITY MANAGER SERVICES \(dumpsys activity services\)\r?\n"
            r"[ \t]*\(nothing\)",
            normalized,
        )
        is not None
    )


def _phone_state(raw: dict[str, Any]) -> dict[str, object]:
    expected = {
        "device_state",
        "current_user",
        "activity",
        "processes",
        "services",
        "enabled_accessibility",
        "accessibility_dump",
        "interfaces",
        "connectivity",
        "vpn",
        "stayon",
        "wifi_on",
    }
    if set(raw) != expected or raw.get("device_state") != "device" or raw.get(
        "current_user"
    ) != "0":
        fail("phone_state_invalid")
    activity = raw.get("activity")
    if not isinstance(activity, str):
        fail("phone_state_invalid")
    focused_lines = [
        line for line in activity.splitlines() if re.match(r"^\s*mFocusedApp=", line)
    ]
    resumed_lines = [
        line
        for line in activity.splitlines()
        if re.match(
            r"^\s*(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]",
            line,
        )
    ]
    if len(focused_lines) != 1 or not resumed_lines:
        fail("phone_state_invalid")
    focused = _canonical_component(focused_lines[0])
    resumed = tuple(_canonical_component(line) for line in resumed_lines)
    launcher = _canonical_component(LAUNCHER_COMPONENT)
    if focused != launcher or any(item != launcher for item in resumed):
        fail("phone_state_invalid")

    processes_raw = raw.get("processes")
    services_raw = raw.get("services")
    if (
        not isinstance(processes_raw, dict)
        or not isinstance(services_raw, dict)
        or set(processes_raw) != set(RELEVANT_PACKAGES)
        or set(services_raw) != set(RELEVANT_PACKAGES)
    ):
        fail("phone_state_invalid")
    processes: list[list[str]] = []
    services: list[list[str]] = []
    for package in RELEVANT_PACKAGES:
        process = processes_raw[package]
        service = services_raw[package]
        if not isinstance(process, str) or not isinstance(service, str):
            fail("phone_state_invalid")
        process = process.strip()
        if process or not _is_empty_service_dump(service):
            fail("phone_state_invalid")
        processes.append([package, process])
        services.append([package, ""])

    accessibility = raw.get("enabled_accessibility")
    accessibility_dump = raw.get("accessibility_dump")
    if not isinstance(accessibility, str) or not isinstance(accessibility_dump, str):
        fail("phone_state_invalid")
    accessibility = accessibility.strip()
    if any(package in accessibility for package in RELEVANT_PACKAGES):
        fail("phone_state_invalid")
    for line in accessibility_dump.splitlines():
        if (
            re.search(r"(?i)\b(?:bound|enabled)\b.*\bservices?\b", line)
            and any(package in line for package in RELEVANT_PACKAGES)
        ):
            fail("phone_state_invalid")

    interfaces_raw = raw.get("interfaces")
    connectivity = raw.get("connectivity")
    vpn_dump = raw.get("vpn")
    if not all(isinstance(value, str) for value in (interfaces_raw, connectivity, vpn_dump)):
        fail("phone_state_invalid")
    assert isinstance(interfaces_raw, str)
    assert isinstance(connectivity, str)
    assert isinstance(vpn_dump, str)
    interfaces = sorted(
        {line.strip() for line in interfaces_raw.splitlines() if line.strip()}
    )
    if any(TUNNEL_INTERFACE_RE.fullmatch(item) for item in interfaces):
        fail("phone_state_invalid")
    active_vpn = _active_vpn(connectivity)
    vpn_service_active = any(
        "LISTEN" not in line.upper()
        and re.search(
            r"(?i)(?:state\s*[:=]\s*CONNECTED|"
            r"mNetworkInfo.*\bCONNECTED(?:/CONNECTED)?\b)",
            line,
        )
        is not None
        for line in vpn_dump.splitlines()
    )
    if active_vpn or vpn_service_active:
        fail("phone_state_invalid")
    stayon = raw.get("stayon")
    wifi_on = raw.get("wifi_on")
    if (
        not isinstance(stayon, str)
        or re.fullmatch(r"(?:null|[0-9]+)", stayon) is None
        or not isinstance(wifi_on, str)
        or wifi_on not in {"0", "1"}
    ):
        fail("phone_state_invalid")
    return {
        "focused_component": focused,
        "resumed_components": list(resumed),
        "processes": processes,
        "services": services,
        "enabled_accessibility": accessibility,
        "interfaces": interfaces,
        "active_vpn": active_vpn,
        "stayon": stayon,
        "wifi_on": wifi_on,
    }


def phone_state_sha256(raw: dict[str, Any]) -> str:
    return _sha256(_canonical_json(_phone_state(raw)))


def _verify_phone_pair(
    bundle: Path,
    prefix: str,
    *,
    receipt_schema: str = "aneb-realtime-phone-live-state-receipt",
) -> str:
    first, _ = _load_json(bundle / f"{prefix}-t0-raw.json", "phone_state_invalid")
    second, _ = _load_json(bundle / f"{prefix}-t2-raw.json", "phone_state_invalid")
    receipt, _ = _load_json(
        bundle / f"{prefix}-receipt.json",
        "phone_receipt_invalid",
    )
    first_state = _phone_state(first)
    second_state = _phone_state(second)
    first_hash = _sha256(_canonical_json(first_state))
    second_hash = _sha256(_canonical_json(second_state))
    if first_hash != second_hash:
        fail("phone_state_not_stable")
    if (
        not _exact(
            receipt,
            {
                "schema",
                "schema_version",
                "status",
                "reason_code",
                "stable",
                "t0_sha256",
                "t2_sha256",
                "focused_component",
                "stayon",
                "wifi_on",
            },
        )
        or receipt.get("schema") != receipt_schema
        or receipt.get("schema_version") != "1.0.0"
        or receipt.get("status") != "pass"
        or receipt.get("reason_code") != "ok"
        or receipt.get("stable") is not True
        or receipt.get("t0_sha256") != first_hash
        or receipt.get("t2_sha256") != second_hash
        or receipt.get("focused_component") != second_state["focused_component"]
        or receipt.get("stayon") != second_state["stayon"]
        or receipt.get("wifi_on") != second_state["wifi_on"]
    ):
        fail("phone_receipt_invalid")
    return first_hash


def _verify_device_identity(
    bundle: Path,
    plan: dict[str, Any],
    *,
    identity_schema: str = "aneb-realtime-device-identity",
) -> None:
    policy, policy_raw = _load_json(
        bundle / "device-policy.json",
        "device_policy_invalid",
        require_canonical=False,
    )
    identity, _ = _load_json(
        bundle / "device-identity.json",
        "device_identity_invalid",
    )
    if (
        not _exact(
            policy,
            {
                "schema",
                "schema_version",
                "device_alias",
                "adb_serial_sha256",
                "properties",
            },
        )
        or policy.get("schema") != "aneb-device-identity-policy"
        or policy.get("schema_version") != "1.0.0"
        or policy.get("device_alias") != "P40 Pro"
        or policy.get("adb_serial_sha256") != plan["adb_serial_sha256"]
        or _sha256(policy_raw) != plan["device_policy_sha256"]
        or not _exact(policy.get("properties"), set(DEVICE_PROPERTY_KEYS))
    ):
        fail("device_policy_invalid")
    properties = policy["properties"]
    for key in DEVICE_PROPERTY_KEYS:
        value = properties[key]
        if (
            not isinstance(value, str)
            or "\r" in value
            or "\n" in value
            or "\x00" in value
            or len(value.encode("utf-8")) > 2048
            or (key not in OPTIONAL_DEVICE_PROPERTY_KEYS and not value)
        ):
            fail("device_policy_invalid")
    if (
        not _exact(
            identity,
            {
                "schema",
                "schema_version",
                "status",
                "adb_serial_sha256",
                "android_boot_id",
                "properties",
            },
        )
        or identity.get("schema") != identity_schema
        or identity.get("schema_version") != "1.0.0"
        or identity.get("status") != "pass"
        or identity.get("adb_serial_sha256") != policy["adb_serial_sha256"]
        or identity.get("properties") != properties
        or not isinstance(identity.get("android_boot_id"), str)
        or re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            r"[0-9a-f]{4}-[0-9a-f]{12}",
            identity["android_boot_id"],
        )
        is None
    ):
        fail("device_identity_invalid")


def _validate_remote(value: dict[str, Any]) -> dict[str, str]:
    if set(value) != set(REMOTE_KEYS) or any(
        not isinstance(value.get(key), str) for key in REMOTE_KEYS
    ):
        fail("remote_snapshot_invalid")
    result = {key: str(value[key]) for key in REMOTE_KEYS}
    if (
        re.fullmatch(r"[0-9a-f]{32}", result["boot_id"]) is None
        or re.fullmatch(r"[0-9a-f]{32}", result["systemd_invocation_id"]) is None
        or re.fullmatch(r"[1-9][0-9]*", result["main_pid"]) is None
        or any(
            SHA256_RE.fullmatch(result[key]) is None
            for key in REMOTE_KEYS
            if key.endswith("_sha256")
        )
        or REMOTE_CURSOR_RE.fullmatch(result["journal_cursor"]) is None
    ):
        fail("remote_snapshot_invalid")
    return result


def _verify_remote(bundle: Path, plan: dict[str, Any]) -> str:
    snapshots = [
        _validate_remote(
            _load_json(bundle / leaf, "remote_snapshot_invalid")[0]
        )
        for leaf in ("remote-pre.json", "remote-post-window.json", "remote-final.json")
    ]
    if any(
        snapshot["server_binary_sha256"]
        != plan["expected_server_binary_sha256"]
        for snapshot in snapshots
    ):
        fail("remote_binary_identity_mismatch")
    if any(
        snapshots[0][key] != snapshot[key]
        for snapshot in snapshots[1:]
        for key in REMOTE_STABLE_KEYS
    ):
        fail("remote_snapshot_drift")
    return snapshots[0]["journal_cursor"]


def _verify_lock(
    bundle: Path,
    *,
    marker_prefix: str = "aneb-realtime-audit",
) -> str:
    if re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", marker_prefix) is None:
        fail("lock_receipt_invalid")
    acquired = _read_regular(
        bundle / "lock-acquired.txt",
        maximum=4096,
        reason="lock_receipt_invalid",
    )
    released = _read_regular(
        bundle / "lock-released.txt",
        maximum=4096,
        reason="lock_receipt_invalid",
    )
    try:
        acquired_text = acquired.decode("utf-8", errors="strict")
        released_text = released.decode("utf-8", errors="strict")
    except UnicodeError:
        fail("lock_receipt_invalid")
    match = re.fullmatch(
        r"LOCK_ACQUIRED nonce=([0-9a-f]{32}) pid=([1-9][0-9]*) "
        rf"marker=/run/{re.escape(marker_prefix)}-\1\.lock\n",
        acquired_text,
    )
    if match is None:
        fail("lock_receipt_invalid")
    nonce = match.group(1)
    if released_text != f"LOCK_RELEASED nonce={nonce}\n":
        fail("lock_receipt_mismatch")
    return nonce


def _validate_http_headers(value: dict[str, Any]) -> None:
    if set(value) != {"status", "headers"} or value.get("status") != 200:
        fail("serverinfo_http_capture_invalid")
    headers = value.get("headers")
    if not isinstance(headers, list):
        fail("serverinfo_http_capture_invalid")
    content_types: list[str] = []
    for item in headers:
        if (
            not isinstance(item, list)
            or len(item) != 2
            or not all(isinstance(part, str) for part in item)
        ):
            fail("serverinfo_http_capture_invalid")
        if item[0].casefold() == "content-type":
            content_types.append(item[1].split(";", 1)[0].strip().casefold())
    if content_types != ["application/json"]:
        fail("serverinfo_http_capture_invalid")


def _verify_realtime_serverinfo_body(body: object) -> None:
    try:
        cross_verifier.verify_serverinfo(body)
    except cross_verifier.VerificationFailure:
        fail("serverinfo_invalid")


def _verify_realtime_serverinfo_sequence(bodies: list[dict[str, Any]]) -> None:
    stable_keys = (
        "version",
        "anchor_wall_unix_ns",
        "goos",
        "goarch",
        "h3_enabled",
        "tcp_slow_start_after_idle",
        "congestion_control",
        "execution_capabilities",
    )
    if any(
        bodies[0][key] != candidate[key]
        for candidate in bodies[1:]
        for key in stable_keys
    ):
        fail("serverinfo_sequence_invalid")
    if not (
        int(bodies[0]["srv_ts_us"])
        < int(bodies[1]["srv_ts_us"])
        < int(bodies[2]["srv_ts_us"])
        and int(bodies[0]["uptime_s"])
        <= int(bodies[1]["uptime_s"])
        <= int(bodies[2]["uptime_s"])
    ):
        fail("serverinfo_sequence_invalid")


def _verify_serverinfo(
    bundle: Path,
    *,
    body_validator: Callable[[object], None] = _verify_realtime_serverinfo_body,
    sequence_validator: Callable[[list[dict[str, Any]]], None] = (
        _verify_realtime_serverinfo_sequence
    ),
) -> str:
    bodies: list[dict[str, Any]] = []
    for name in ("identity-serverinfo", "start-barrier", "end-barrier"):
        body, _ = _load_json(
            bundle / f"{name}.json",
            "serverinfo_invalid",
            require_canonical=False,
        )
        headers, _ = _load_json(
            bundle / f"{name}.headers.json",
            "serverinfo_http_capture_invalid",
        )
        _validate_http_headers(headers)
        body_validator(body)
        bodies.append(body)
    sequence_validator(bodies)
    return str(bodies[0]["version"])


def revalidate_cross_evidence(
    bundle: Path,
    *,
    plan: dict[str, Any],
    run_id: str,
) -> dict[str, object]:
    mode = str(plan["evidence_mode"])
    serverinfo_path = (
        bundle / "identity-serverinfo.json"
        if mode == "positive"
        else bundle / "negative-proxy" / "upstream-serverinfo.raw"
    )
    arguments: dict[str, Any] = {
        "mode": mode,
        "client_database_path": bundle / "aneb-probe.db",
        "client_report_path": bundle / "client-db-report.json",
        "client_result_path": bundle / "client-result.json",
        "audit_report_path": bundle / "request-entry-audit.json",
        "journal_path": bundle / "journal.raw.log",
        "start_barrier_id": plan["start_barrier_id"],
        "barrier_id": plan["end_barrier_id"],
        "serverinfo_path": serverinfo_path,
    }
    if mode == "negative":
        arguments.update(
            {
                "negative_proxy_bundle": bundle,
                "negative_upstream_url": plan["negative_upstream_url"],
                "negative_ca_file_sha256": plan["server_ca_sha256"],
                "negative_device_port": NEGATIVE_DEVICE_PORT,
            }
        )
    try:
        report = cross_verifier.verify(**arguments)
    except cross_verifier.VerificationFailure:
        fail("cross_evidence_revalidation_failed")
    if report.get("run_id") != run_id or report.get("mode") != mode:
        fail("cross_evidence_revalidation_failed")
    return report


def _verify_mode_inventory(
    manifest: dict[str, Any],
    *,
    mode: str,
) -> None:
    paths = {
        str(record["path"])
        for record in manifest["files"]
        if isinstance(record, dict) and isinstance(record.get("path"), str)
    }
    if mode == "positive" and any(
        path == "negative-proxy"
        or path.startswith("negative-proxy/")
        or path.startswith("negative-proxy.")
        or path.startswith("adb-reverse-")
        for path in paths
    ):
        fail("positive_mode_forbidden_evidence")
    if mode == "negative" and not NEGATIVE_REQUIRED_PATHS.issubset(paths):
        fail("negative_mode_evidence_missing")


def _verify_evidence_root_security(bundle: Path) -> str:
    report, raw = _load_json(
        bundle / "evidence-root-security.json",
        "evidence_root_security_invalid",
        maximum=64 * 1024,
        require_canonical=True,
    )
    try:
        evidence_security.validate_report(bundle.parent, report)
    except evidence_security.EvidenceSecurityFailure:
        fail("evidence_root_security_invalid")
    return _sha256(raw)


def _verify_complete(
    bundle: Path,
    *,
    collection: str,
    run_id: str,
    manifest_sha256: str,
    marker: str = "ANEB_REALTIME_QUICK_COMPLETE",
) -> None:
    complete = _read_regular(
        bundle / "COMPLETE",
        maximum=4096,
        reason="complete_marker_invalid",
    )
    expected = (
        f"{marker} collection_id={collection} "
        f"run_id={run_id} manifest_sha256={manifest_sha256}\n"
    ).encode("ascii")
    if complete != expected:
        fail("complete_marker_mismatch")


def verify_collection(
    bundle_path: str | os.PathLike[str],
    *,
    expected_collection: str | None = None,
    allow_partial: bool = False,
) -> dict[str, object]:
    bundle = Path(os.path.abspath(os.fspath(bundle_path)))
    match = COLLECTION_RE.fullmatch(bundle.name)
    if match is not None:
        collection = match.group("collection")
    elif (
        allow_partial
        and isinstance(expected_collection, str)
        and re.fullmatch(
            r"m0-ec2-realtime-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}",
            expected_collection,
        )
        is not None
        and bundle.name == f"{expected_collection}.partial"
    ):
        collection = expected_collection
    else:
        fail("collection_leaf_invalid")
    _assert_directory(bundle, "collection_directory_invalid")

    manifest, manifest_sha = _verify_manifest(bundle)
    root_security_sha = _verify_evidence_root_security(bundle)
    plan, _ = _load_json(bundle / "collector-plan.json", "collector_plan_invalid")
    _validate_plan(plan, collection)
    _verify_mode_inventory(manifest, mode=str(plan["evidence_mode"]))
    status, _ = _load_json(
        bundle / "collector-status.json",
        "collector_status_invalid",
    )
    run_receipt, _ = _load_json(bundle / "run-receipt.json", "run_receipt_invalid")
    cross_report, cross_raw = _load_json(
        bundle / "cross-bound-report.json",
        "cross_report_invalid",
    )
    run_id = _validate_status_and_run(
        status,
        run_receipt,
        collection=collection,
        plan=plan,
        cross_raw=cross_raw,
    )
    _verify_complete(
        bundle,
        collection=collection,
        run_id=run_id,
        manifest_sha256=manifest_sha,
    )
    _verify_candidate(bundle, plan)
    _verify_device_identity(bundle, plan)
    preflight_hash = _verify_phone_pair(bundle, "phone-preflight")
    postflight_hash = _verify_phone_pair(bundle, "phone-postflight")
    if preflight_hash != postflight_hash:
        fail("phone_baseline_not_restored")
    journal_cursor = _verify_remote(bundle, plan)
    lock_nonce = _verify_lock(bundle)
    server_version = _verify_serverinfo(bundle)
    recomputed_cross = revalidate_cross_evidence(
        bundle,
        plan=plan,
        run_id=run_id,
    )
    if recomputed_cross != cross_report:
        fail("cross_report_revalidation_mismatch")

    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "collection_id": collection,
        "run_id": run_id,
        "mode": plan["evidence_mode"],
        "source_commit": plan["source_commit"],
        "workflow_run_id": plan["workflow_run_id"],
        "server_version": server_version,
        "server_binary_sha256": plan["expected_server_binary_sha256"],
        "apk_sha256": plan["expected_apk_sha256"],
        "manifest_sha256": manifest_sha,
        "manifest_file_count": len(manifest["files"]),
        "journal_cursor": journal_cursor,
        "lock_nonce_sha256": _sha256(lock_nonce.encode("ascii")),
        "phone_state_sha256": postflight_hash,
        "manifest_reverified": True,
        "cross_evidence_recomputed": True,
        "phone_state_reverified": True,
        "remote_state_reverified": True,
        "candidate_provenance_reverified": True,
        "evidence_root_security_sha256": root_security_sha,
        "evidence_root_security_bound": True,
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
        description="Revalidate one published AI Realtime Quick collection",
        allow_abbrev=False,
    )
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args(argv)
    try:
        report = verify_collection(args.bundle)
    except CollectionVerificationFailure as error:
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
