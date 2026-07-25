#!/usr/bin/env python3
"""Independently revalidate one completed D-82 Token Quick evidence bundle."""

from __future__ import annotations

import argparse
import contextlib
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
from typing import Any, Iterator, NoReturn
import unicodedata
from urllib.parse import urlsplit
import zipfile

import prepare_token_run_evidence as evidence_helper
import verify_ci_apk_provenance as ci_provenance_verifier
import verify_token_quick_client_db as client_verifier
import verify_token_quick_negative_client_db as negative_client_verifier
import verify_token_quick_negative_proxy_evidence as negative_proxy_verifier
import verify_token_quick_device_identity as device_identity_verifier
import verify_token_quick_raw_state as raw_state_verifier
import verify_result_jsonl as result_jsonl_verifier
import verify_token_run_audit as audit_verifier
import verify_token_quick_time_chain as time_chain_verifier


REPORT_SCHEMA = "aneb-d82-bundle-verification-report"
REPORT_VERSION = "1.1.0"
FINAL_SCHEMA = "aneb-d82-final-evidence-manifest"
FINAL_VERSION = "1.1.0"
DRAFT_SCHEMA = "aneb-evidence-manifest-draft"
DRAFT_VERSION = "1.0.0"
PROFILE_CONTRACT = "token_multimodal_quick@1.2.1"
EXECUTION_SCOPES = {
    "positive": "d82_token_quick_cross_bound_acceptance",
    "negative_receipt_missing": "d82_token_quick_contract_rejection_acceptance",
}
MAX_JSON_BYTES = 32 * 1024 * 1024
MAX_PAYLOAD_BYTES = 512 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
MAX_ANDROID_TOOL_OUTPUT_BYTES = 256 * 1024
ANDROID_TOOL_TIMEOUT_SECONDS = 30
ANDROID_BUILD_TOOLS_VERSION = "35.0.0"
PUBLISH_LOCK_NAME = ".aneb-d82-publish.lock"
VERIFICATION_STAGE_SUFFIX = ".verification-stage"
EXPECTED_CLIENT_PACKAGE = "com.aneb.probe.codex"
EXPECTED_CLIENT_VERSION_NAME = "0.5.12-codex"
EXPECTED_CLIENT_VERSION_CODE = 44
ROOT = Path(__file__).resolve().parents[1]
PROFILE_MANIFEST = (
    ROOT
    / "profiles"
    / "published"
    / "token_multimodal_quick"
    / "manifest.sha256"
)
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
UUID_V4_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SHA256_ID_RE = re.compile(r"^sha256:([0-9a-f]{64})$")
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
APK_PACKAGE_RE = re.compile(
    rb"^package: name='([^'\r\n]{1,255})' versionCode='([0-9]{1,10})' "
    rb"versionName='([^'\r\n]{1,255})'(?: [^\r\n]*)?\r?$",
    re.MULTILINE,
)
APK_SIGNER_RE = re.compile(
    rb"^Signer #([0-9]{1,3}) certificate SHA-256 digest: "
    rb"([0-9a-fA-F]{64})\r?$",
    re.MULTILINE,
)
UTC_TIMESTAMP_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:"
    r"[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$"
)
COLLECTION_RE = re.compile(
    r"^d82-token-quick-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}$"
)
COMPLETE_RE = re.compile(
    r"^ANEB_D82_COMPLETE collection_id=(?P<collection>"
    + COLLECTION_RE.pattern[1:-1]
    + r") run_id=(?P<run>[0-9a-f-]{36}) "
    r"manifest=evidence-manifest\.final\.json "
    r"manifest_sha256=(?P<sha>[0-9a-f]{64})\n$"
)
TOOL_PATHS = {
    "collector": "scripts/collect_token_quick_evidence.ps1",
    "derive_helper": "scripts/prepare_token_run_evidence.py",
    "audit_verifier": "scripts/verify_token_run_audit.py",
    "client_db_verifier": "scripts/verify_token_quick_client_db.py",
    "negative_proxy": "scripts/token_serverinfo_negative_proxy.py",
    "negative_proxy_evidence_verifier": (
        "scripts/verify_token_quick_negative_proxy_evidence.py"
    ),
    "negative_client_db_verifier": (
        "scripts/verify_token_quick_negative_client_db.py"
    ),
    "result_jsonl_verifier": "scripts/verify_result_jsonl.py",
    "bundle_verifier": "scripts/verify_token_quick_evidence_bundle.py",
    "time_chain_verifier": "scripts/verify_token_quick_time_chain.py",
    "raw_state_verifier": "scripts/verify_token_quick_raw_state.py",
    "device_identity_verifier": "scripts/verify_token_quick_device_identity.py",
    "ci_provenance_verifier": "scripts/verify_ci_apk_provenance.py",
    "ci_workflow": ".github/workflows/ci.yml",
    "debug_candidate_packager": "scripts/package_debug_candidate.py",
    "spec_catalog": "spec/catalog.json",
    "request_entry_contract": (
        "spec/execution-contracts/"
        "token_multimodal_quick-1.2.1.request-entry.json"
    ),
    "profile_manifest": (
        "profiles/published/token_multimodal_quick/manifest.sha256"
    ),
    "profile_definition": (
        "profiles/published/token_multimodal_quick/profile.json"
    ),
    "runtime_plan": (
        "profiles/published/token_multimodal_quick/runtime_plan.json"
    ),
    "result_schema_core_v1": "spec/schemas/aneb-result-core-v1.schema.json",
    "result_schema_v1": "spec/schemas/aneb-result-v1.schema.json",
    "result_schema_v2": "spec/schemas/aneb-result-v2.schema.json",
    "room_schema_v19": (
        "app/probe/schemas/com.aneb.probe.data.AnebDatabase/19.json"
    ),
    "server_ca": "app/probe/src/main/res/raw/aneb_ip_ca.pem",
}
LAUNCHER_COMPONENT = "com.huawei.android.launcher/.unihome.UniHomeLauncher"
CONFLICT_PACKAGES = {
    "com.aneb.probe",
    "com.aneb.probe.codex",
    "com.emanuelef.remote_capture",
    "com.pcapdroid.mitm",
    "com.wireguard.android",
}
SOURCE_KEYS = {
    "server_base",
    "server_version",
    "server_binary_sha256",
    "boot_id",
    "systemd_invocation_id",
    "main_pid",
    "journal_cursor",
    "journal_monotonic_anchor",
    "remote_realtime_anchor_usec",
    "serverinfo_body_sha256",
    "server_ca_sha256",
    "server_ca_thumbprint",
}
SERVERINFO_KEYS = {
    "anchor_wall_unix_ns",
    "congestion_control",
    "execution_capabilities",
    "goarch",
    "goos",
    "h3_enabled",
    "srv_ts_us",
    "tcp_slow_start_after_idle",
    "uptime_s",
    "version",
}
FINAL_KEYS = {
    "schema",
    "schema_version",
    "status",
    "acceptance_eligible",
    "evidence_scope",
    "execution_mode",
    "finalized_at_utc",
    "collection_id",
    "run_id",
    "start_barrier_id",
    "end_barrier_id",
    "profile_contract",
    "profile_contract_definition_sha256",
    "tooling_provenance",
    "device",
    "client",
    "source",
    "draft_inventory_sha256",
    "client_result_body_sha256",
    "file_count",
    "total_bytes",
    "files",
}
COMMON_REQUIRED_PAYLOADS = {
    "collector-plan.json",
    "collector-status.json",
    "cleanup-report.json",
    "device-preflight.json",
    "device-final-clean.json",
    "device-accessibility-final.txt",
    "identity-serverinfo.headers",
    "identity-serverinfo.json",
    "pre-start-receipt.json",
    "remote-pre-start.txt",
    "remote-end.txt",
    "start-barrier.headers",
    "start-barrier.json",
    "end-barrier.headers",
    "end-barrier.json",
    "journal.raw.jsonl",
    "token-run-audit.log",
    "journal-derivation.json",
    "request-entry-audit.json",
    "app-logcat.txt",
    "aneb-probe.db",
    "room-copy-inventory.json",
    "client-result.json",
    "client-db-report.json",
    "installed-base.apk",
    "lock-acquired.txt",
    "lock-released.txt",
    "lock-release-verified.txt",
    "device-policy.json",
    "ci-candidate/ANEB-Probe-0.5.12-codex-debug.apk",
    "ci-candidate/build-manifest.json",
    "ci-candidate/checksums.sha256",
    "ci-candidate/provenance.sigstore.json",
    "ci-candidate/ANEB-安装说明.txt",
} | set(raw_state_verifier.RAW_FILE_NAMES) | set(
    device_identity_verifier.RAW_FILE_NAMES
)
SUPPLEMENTAL_REQUIRED_PAYLOADS = {
    "app-logcat.stderr.txt",
    "journal-derivation.stdout.txt",
    "installed-apk-signer.txt",
    "device-busy-sentinel-launch.txt",
    "device-busy-sentinel-restore.txt",
    "device-busy-sentinel.json",
    "busy-sentinel-observations.jsonl",
    "device-busy-sentinel-release-guard.json",
    "device-identity-report.json",
    "ci-candidate-verification.json",
}
ROOM_SIDECAR_PAYLOADS = {"aneb-probe.db-wal", "aneb-probe.db-shm"}
NEGATIVE_REQUIRED_PAYLOADS = set(negative_proxy_verifier.RAW_FILE_NAMES)


class BundleFailure(ValueError):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


class DuplicateJsonKey(ValueError):
    pass


def fail(reason_code: str) -> NoReturn:
    raise BundleFailure(reason_code)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(key)
        result[key] = value
    return result


def reject_non_finite(value: str) -> NoReturn:
    raise ValueError(value)


def is_reparse(path: Path) -> bool:
    try:
        if path.is_symlink():
            return True
        is_junction = getattr(path, "is_junction", None)
        return bool(is_junction and is_junction())
    except OSError:
        return True


def read_regular(path: Path, *, maximum: int, reason: str) -> bytes:
    flags = os.O_RDONLY
    for name in ("O_BINARY", "O_CLOEXEC", "O_NOFOLLOW"):
        flags |= int(getattr(os, name, 0))
    try:
        if is_reparse(path):
            fail(reason)
        descriptor = os.open(path, flags)
    except (OSError, BundleFailure):
        fail(reason)
    try:
        with os.fdopen(descriptor, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or not 0 <= before.st_size <= maximum:
                fail(reason)
            chunks: list[bytes] = []
            total = 0
            while block := stream.read(1024 * 1024):
                chunks.append(block)
                total += len(block)
                if total > maximum:
                    fail(reason)
            after = os.fstat(stream.fileno())
        path_after = os.stat(path, follow_symlinks=False)
    except (OSError, BundleFailure):
        fail(reason)
    identity = lambda value: (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_size,
        value.st_mtime_ns,
    )
    if identity(before) != identity(after) or identity(before) != identity(path_after):
        fail(reason)
    payload = b"".join(chunks)
    if len(payload) != before.st_size:
        fail(reason)
    return payload


def resolve_android_identity_tools(
    build_tools_directory: Path | None,
) -> tuple[Path, Path, Path]:
    if build_tools_directory is None:
        sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get(
            "ANDROID_SDK_ROOT"
        )
        if not sdk_root:
            fail("android_build_tools_unavailable")
        build_tools_directory = (
            Path(sdk_root) / "build-tools" / ANDROID_BUILD_TOOLS_VERSION
        )
    try:
        unresolved = Path(build_tools_directory)
        if is_reparse(unresolved):
            fail("android_build_tools_unavailable")
        tools_root = unresolved.resolve(strict=True)
    except (OSError, BundleFailure):
        fail("android_build_tools_unavailable")
    if (
        not tools_root.is_dir()
        or tools_root.name != ANDROID_BUILD_TOOLS_VERSION
        or len(str(tools_root)) > 4096
    ):
        fail("android_build_tools_unavailable")

    aapt2 = tools_root / ("aapt2.exe" if os.name == "nt" else "aapt2")
    apksigner_jar = tools_root / "lib" / "apksigner.jar"
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    else:
        resolved_java = shutil.which("java")
        if not resolved_java:
            fail("java_runtime_unavailable")
        java = Path(resolved_java)
    resolved: list[Path] = []
    for path, reason in (
        (aapt2, "android_build_tools_unavailable"),
        (apksigner_jar, "android_build_tools_unavailable"),
        (java, "java_runtime_unavailable"),
    ):
        try:
            if is_reparse(path):
                fail(reason)
            exact = path.resolve(strict=True)
            metadata = exact.stat()
        except (OSError, BundleFailure):
            fail(reason)
        if (
            not exact.is_file()
            or not stat.S_ISREG(metadata.st_mode)
            or not 0 < metadata.st_size <= 256 * 1024 * 1024
            or len(str(exact)) > 4096
        ):
            fail(reason)
        resolved.append(exact)
    return resolved[0], resolved[1], resolved[2]


def run_android_tool_once(command: list[str], *, failure_reason: str) -> bytes:
    if (
        not command
        or len(command) > 16
        or any(not isinstance(argument, str) or len(argument) > 4096 for argument in command)
    ):
        fail("android_tool_invocation_invalid")
    environment = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(name, None)
    environment["LANG"] = "C"
    environment["LC_ALL"] = "C"
    creation_flags = int(getattr(subprocess, "CREATE_NO_WINDOW", 0))
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            shell=False,
            env=environment,
            creationflags=creation_flags,
        )
    except OSError:
        fail("android_tool_launch_failed")
    if process.stdout is None:
        process.kill()
        fail("android_tool_launch_failed")

    output = bytearray()
    overflow = threading.Event()

    def consume() -> None:
        try:
            while chunk := process.stdout.read(8192):
                remaining = MAX_ANDROID_TOOL_OUTPUT_BYTES + 1 - len(output)
                if remaining > 0:
                    output.extend(chunk[:remaining])
                if len(output) > MAX_ANDROID_TOOL_OUTPUT_BYTES or len(chunk) > remaining:
                    overflow.set()
                    return
        except OSError:
            overflow.set()

    reader = threading.Thread(target=consume, name="aneb-android-tool-output", daemon=True)
    reader.start()
    deadline = time.monotonic() + ANDROID_TOOL_TIMEOUT_SECONDS
    timed_out = False
    while process.poll() is None:
        if overflow.is_set():
            process.kill()
            break
        if time.monotonic() >= deadline:
            timed_out = True
            process.kill()
            break
        time.sleep(0.01)
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=2)
    reader.join(timeout=2)
    if reader.is_alive():
        process.stdout.close()
        fail("android_tool_output_read_failed")
    process.stdout.close()
    if timed_out:
        fail("android_tool_timeout")
    if overflow.is_set() or len(output) > MAX_ANDROID_TOOL_OUTPUT_BYTES:
        fail("android_tool_output_too_large")
    if process.returncode != 0:
        fail(failure_reason)
    return bytes(output)


def verify_apk_archive(raw: bytes) -> None:
    try:
        with zipfile.ZipFile(io.BytesIO(raw), "r") as archive:
            entries = archive.infolist()
            if not 1 <= len(entries) <= 10000:
                fail("apk_archive_invalid")
            names: set[str] = set()
            total_uncompressed = 0
            for entry in entries:
                name = entry.filename
                pure = PurePosixPath(name)
                if (
                    not name
                    or "\\" in name
                    or "\x00" in name
                    or pure.is_absolute()
                    or any(part in {"", ".", ".."} for part in pure.parts)
                    or name in names
                    or entry.flag_bits & 0x1
                    or entry.file_size < 0
                    or entry.compress_size < 0
                ):
                    fail("apk_archive_invalid")
                names.add(name)
                total_uncompressed += entry.file_size
                if total_uncompressed > 1024 * 1024 * 1024:
                    fail("apk_archive_invalid")
            if "AndroidManifest.xml" not in names or "classes.dex" not in names:
                fail("apk_archive_invalid")
            manifest = archive.getinfo("AndroidManifest.xml")
            if not 0 < manifest.file_size <= 16 * 1024 * 1024:
                fail("apk_archive_invalid")
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile, KeyError):
        fail("apk_archive_invalid")


def verify_apk_identity(
    raw: bytes, build_tools_directory: Path | None
) -> dict[str, Any]:
    verify_apk_archive(raw)
    aapt2, apksigner_jar, java = resolve_android_identity_tools(
        build_tools_directory
    )
    with tempfile.TemporaryDirectory(prefix="aneb-d82-apk-") as temporary:
        apk = Path(temporary) / "installed-base.apk"
        try:
            apk.write_bytes(raw)
        except OSError:
            fail("apk_identity_staging_failed")
        badging = run_android_tool_once(
            [str(aapt2), "dump", "badging", str(apk)],
            failure_reason="apk_badging_verification_failed",
        )
        signer = run_android_tool_once(
            [
                str(java),
                "-jar",
                str(apksigner_jar),
                "verify",
                "--print-certs",
                str(apk),
            ],
            failure_reason="apk_signature_verification_failed",
        )
    packages = APK_PACKAGE_RE.findall(badging)
    signers = APK_SIGNER_RE.findall(signer)
    if len(packages) != 1:
        fail("apk_badging_output_invalid")
    if len(signers) != 1 or signers[0][0] != b"1":
        fail("apk_signer_output_invalid")
    package_raw, version_code_raw, version_name_raw = packages[0]
    try:
        package_name = package_raw.decode("ascii")
        version_name = version_name_raw.decode("ascii")
        version_code = int(version_code_raw)
        signer_sha256 = signers[0][1].decode("ascii").lower()
    except (UnicodeError, ValueError):
        fail("apk_identity_output_invalid")
    return {
        "package_name": package_name,
        "version_name": version_name,
        "version_code": version_code,
        "signer_sha256": signer_sha256,
    }


def strict_json_bytes(raw: bytes, reason: str) -> Any:
    try:
        return json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=reject_non_finite,
        )
    except (UnicodeError, json.JSONDecodeError, ValueError):
        fail(reason)


def strict_json_file(path: Path, reason: str) -> tuple[Any, bytes]:
    raw = read_regular(path, maximum=MAX_JSON_BYTES, reason=reason)
    return strict_json_bytes(raw, reason), raw


def require_dict(value: Any, reason: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(reason)
    return value


def require_list(value: Any, reason: str) -> list[Any]:
    if not isinstance(value, list):
        fail(reason)
    return value


def canonical_path(value: Any) -> str:
    if not isinstance(value, str) or not value or len(value) > 512:
        fail("manifest_path_invalid")
    if value.startswith("/") or "\\" in value:
        fail("manifest_path_invalid")
    components = value.split("/")
    if any(component in {"", ".", ".."} for component in components):
        fail("manifest_path_invalid")
    if unicodedata.normalize("NFC", value) != value or any(
        ord(character) < 32
        or ord(character) == 127
        or character in '<>:"|?*'
        for character in value
    ):
        fail("manifest_path_invalid")
    return value


def valid_utc_timestamp(value: Any) -> bool:
    if not isinstance(value, str) or UTC_TIMESTAMP_RE.fullmatch(value) is None:
        return False
    try:
        datetime.strptime(value[:19], "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return False
    return True


def uuid_v7_epoch_ms(value: str, reason: str) -> int:
    if RUN_ID_RE.fullmatch(value) is None:
        fail(reason)
    try:
        return int(value.replace("-", "")[:12], 16)
    except ValueError:
        fail(reason)


def verify_inventory(
    bundle: Path, manifest: dict[str, Any]
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    entries = require_list(manifest.get("files"), "manifest_files_invalid")
    if type(manifest.get("file_count")) is not int or manifest["file_count"] != len(entries):
        fail("manifest_file_count_mismatch")
    if type(manifest.get("total_bytes")) is not int or not 0 <= manifest["total_bytes"] <= MAX_TOTAL_BYTES:
        fail("manifest_total_bytes_invalid")
    previous: str | None = None
    seen: set[str] = set()
    digests: dict[str, str] = {}
    total = 0
    normalized_entries: list[dict[str, Any]] = []
    for raw_entry in entries:
        entry = require_dict(raw_entry, "manifest_entry_invalid")
        if set(entry) != {"bytes", "path", "sha256"}:
            fail("manifest_entry_invalid")
        relative = canonical_path(entry["path"])
        key = relative.casefold()
        if key in seen or (previous is not None and relative <= previous):
            fail("manifest_paths_invalid")
        seen.add(key)
        previous = relative
        if type(entry["bytes"]) is not int or not 0 <= entry["bytes"] <= MAX_PAYLOAD_BYTES:
            fail("manifest_entry_size_invalid")
        if not isinstance(entry["sha256"], str) or SHA256_RE.fullmatch(entry["sha256"]) is None:
            fail("manifest_entry_digest_invalid")
        path = bundle.joinpath(*relative.split("/"))
        raw = read_regular(path, maximum=MAX_PAYLOAD_BYTES, reason="payload_unreadable")
        if len(raw) != entry["bytes"] or sha256_bytes(raw) != entry["sha256"]:
            fail("payload_digest_mismatch")
        total += len(raw)
        if total > MAX_TOTAL_BYTES:
            fail("manifest_total_bytes_invalid")
        digests[relative] = entry["sha256"]
        normalized_entries.append(
            {"bytes": entry["bytes"], "path": relative, "sha256": entry["sha256"]}
        )
    if total != manifest["total_bytes"]:
        fail("manifest_total_bytes_mismatch")
    mode = manifest.get("execution_mode")
    required = COMMON_REQUIRED_PAYLOADS | SUPPLEMENTAL_REQUIRED_PAYLOADS
    if mode == "negative_receipt_missing":
        required |= NEGATIVE_REQUIRED_PAYLOADS
    elif mode != "positive":
        fail("execution_mode_invalid")
    if not required.issubset(digests):
        fail("required_payload_missing")
    allowed = required | ROOM_SIDECAR_PAYLOADS
    if not set(digests).issubset(allowed):
        fail("unexpected_payload_present")

    actual: set[str] = set()
    try:
        for path in bundle.rglob("*"):
            if is_reparse(path):
                fail("bundle_reparse_point_forbidden")
            if path.is_dir():
                continue
            if not path.is_file():
                fail("bundle_non_regular_entry")
            actual.add(path.relative_to(bundle).as_posix())
    except OSError:
        fail("bundle_inventory_failed")
    expected = set(digests) | {
        "evidence-inventory.draft.json",
        "evidence-manifest.final.json",
        "COMPLETE",
    }
    if actual != expected:
        fail("bundle_inventory_set_mismatch")
    return normalized_entries, digests


def verify_draft(bundle: Path, final: dict[str, Any], entries: list[dict[str, Any]]) -> None:
    draft, raw = strict_json_file(
        bundle / "evidence-inventory.draft.json", "draft_manifest_invalid"
    )
    draft = require_dict(draft, "draft_manifest_invalid")
    if sha256_bytes(raw) != final.get("draft_inventory_sha256"):
        fail("draft_manifest_digest_mismatch")
    if set(draft) != {
        "acceptance_eligible",
        "evidence_scope",
        "file_count",
        "files",
        "schema",
        "schema_version",
        "status",
        "total_bytes",
    }:
        fail("draft_manifest_contract_invalid")
    if (
        draft["schema"] != DRAFT_SCHEMA
        or draft["schema_version"] != DRAFT_VERSION
        or draft["status"] != "draft"
        or draft["acceptance_eligible"] is not False
        or draft["evidence_scope"] != "inventory_only_not_d82_acceptance"
        or draft["file_count"] != final["file_count"]
        or draft["total_bytes"] != final["total_bytes"]
        or draft["files"] != entries
    ):
        fail("draft_manifest_contract_invalid")


def verify_complete_marker(
    bundle: Path, manifest_raw: bytes, final: dict[str, Any]
) -> str:
    raw = read_regular(bundle / "COMPLETE", maximum=4096, reason="complete_marker_invalid")
    try:
        text = raw.decode("ascii")
    except UnicodeError:
        fail("complete_marker_invalid")
    match = COMPLETE_RE.fullmatch(text)
    if match is None:
        fail("complete_marker_invalid")
    manifest_sha = sha256_bytes(manifest_raw)
    if (
        match["collection"] != final["collection_id"]
        or match["run"] != final["run_id"]
        or match["sha"] != manifest_sha
    ):
        fail("complete_marker_mismatch")
    return manifest_sha


def verify_bundle_unchanged(
    bundle: Path,
    original_final: dict[str, Any],
    original_final_raw: bytes,
    original_entries: list[dict[str, Any]],
) -> None:
    """Rehash the complete directory after semantics to close the verify/use window."""
    try:
        current_value, current_raw = strict_json_file(
            bundle / "evidence-manifest.final.json",
            "bundle_changed_during_verification",
        )
        current_final = require_dict(
            current_value, "bundle_changed_during_verification"
        )
        if current_raw != original_final_raw or current_final != original_final:
            fail("bundle_changed_during_verification")
        verify_complete_marker(bundle, current_raw, current_final)
        current_entries, _ = verify_inventory(bundle, current_final)
        verify_draft(bundle, current_final, current_entries)
        if current_entries != original_entries:
            fail("bundle_changed_during_verification")
    except BundleFailure as error:
        if error.reason_code == "bundle_changed_during_verification":
            raise
        fail("bundle_changed_during_verification")


def absolute_lexical(path: Path) -> Path:
    try:
        return Path(os.path.abspath(os.fspath(path)))
    except (OSError, TypeError, ValueError):
        fail("publish_boundary_invalid")


def ensure_non_reparse_chain(path: Path) -> None:
    current = path
    while True:
        try:
            if os.path.lexists(current) and is_reparse(current):
                fail("publish_boundary_invalid")
        except OSError:
            fail("publish_boundary_invalid")
        parent = current.parent
        if parent == current:
            return
        current = parent


def publication_boundary(
    source_lexical: Path,
    source_resolved: Path,
    target_value: Path,
    collection_id: str,
) -> tuple[Path, Path]:
    target = absolute_lexical(target_value)
    expected_leaf = collection_id + ".complete"
    expected_stage = collection_id + VERIFICATION_STAGE_SUFFIX
    if (
        source_lexical.name != expected_leaf
        or source_lexical.parent.name != expected_stage
        or target.name != expected_leaf
        or len(str(source_lexical)) > 4096
        or len(str(target)) > 4096
    ):
        fail("publish_boundary_invalid")
    ensure_non_reparse_chain(source_lexical)
    ensure_non_reparse_chain(target.parent)
    try:
        source_parent = source_lexical.parent.resolve(strict=True)
        evidence_root = target.parent.resolve(strict=True)
        source_exact = source_lexical.resolve(strict=True)
    except OSError:
        fail("publish_boundary_invalid")
    if (
        source_exact != source_resolved
        or not source_exact.is_dir()
        or not source_parent.is_dir()
        or not evidence_root.is_dir()
        or source_parent.parent != evidence_root
    ):
        fail("publish_boundary_invalid")
    if os.path.lexists(target):
        if is_reparse(target):
            fail("publish_boundary_invalid")
        fail("publish_target_exists")
    return evidence_root, target


@contextlib.contextmanager
def exclusive_publish_lock(evidence_root: Path) -> Iterator[None]:
    lock_path = evidence_root / PUBLISH_LOCK_NAME
    if os.path.lexists(lock_path) and is_reparse(lock_path):
        fail("publish_boundary_invalid")
    flags = os.O_RDWR | os.O_CREAT
    for name in ("O_BINARY", "O_CLOEXEC", "O_NOFOLLOW"):
        flags |= int(getattr(os, name, 0))
    try:
        descriptor = os.open(lock_path, flags, 0o600)
    except OSError:
        fail("publish_lock_unavailable")
    locked = False
    try:
        before = os.fstat(descriptor)
        after = os.stat(lock_path, follow_symlinks=False)
        if (
            not stat.S_ISREG(before.st_mode)
            or (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino)
        ):
            fail("publish_boundary_invalid")
        if before.st_size == 0:
            os.write(descriptor, b"\0")
            os.fsync(descriptor)
        os.lseek(descriptor, 0, os.SEEK_SET)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, BlockingIOError):
            fail("publish_lock_unavailable")
        locked = True
        yield
    finally:
        if locked:
            try:
                os.lseek(descriptor, 0, os.SEEK_SET)
                if os.name == "nt":
                    import msvcrt

                    msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl

                    fcntl.flock(descriptor, fcntl.LOCK_UN)
            except OSError:
                pass
        os.close(descriptor)


def atomic_rename_no_replace(source: Path, target: Path) -> None:
    try:
        if os.name == "nt":
            os.rename(source, target)
            return
        if not sys.platform.startswith("linux"):
            fail("publish_failed")
        import ctypes

        libc = ctypes.CDLL(None, use_errno=True)
        renameat2 = libc.renameat2
        renameat2.argtypes = (
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        )
        renameat2.restype = ctypes.c_int
        result = renameat2(
            -100,
            os.fsencode(source),
            -100,
            os.fsencode(target),
            1,
        )
        if result != 0:
            error_number = ctypes.get_errno()
            if error_number in {17, 39}:
                fail("publish_target_exists")
            fail("publish_failed")
    except BundleFailure:
        raise
    except FileExistsError:
        fail("publish_target_exists")
    except (OSError, AttributeError):
        fail("publish_failed")


def publish_verified_bundle(
    source_lexical: Path,
    source_resolved: Path,
    target_value: Path,
    final: dict[str, Any],
    final_raw: bytes,
    entries: list[dict[str, Any]],
) -> None:
    evidence_root, _ = publication_boundary(
        source_lexical, source_resolved, target_value, final["collection_id"]
    )
    with exclusive_publish_lock(evidence_root):
        _, target = publication_boundary(
            source_lexical,
            source_resolved,
            target_value,
            final["collection_id"],
        )
        try:
            verify_bundle_unchanged(source_resolved, final, final_raw, entries)
        except BundleFailure:
            fail("bundle_changed_before_publish")
        _, target = publication_boundary(
            source_lexical,
            source_resolved,
            target,
            final["collection_id"],
        )
        try:
            atomic_rename_no_replace(source_resolved, target)
        except BundleFailure:
            raise
        except (OSError, AttributeError):
            fail("publish_failed")
        try:
            if os.path.lexists(source_lexical) or is_reparse(target) or not target.is_dir():
                fail("publish_failed")
        except OSError:
            fail("publish_failed")


def git_run(repository: Path, arguments: list[str]) -> subprocess.CompletedProcess[bytes]:
    try:
        completed = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            capture_output=True,
            check=False,
            timeout=20,
        )
    except (OSError, subprocess.SubprocessError):
        fail("tooling_provenance_mismatch")
    if len(completed.stdout) > MAX_PAYLOAD_BYTES or len(completed.stderr) > MAX_JSON_BYTES:
        fail("tooling_provenance_mismatch")
    return completed


def verify_tooling(
    bundle: Path,
    final: dict[str, Any],
    repository: Path,
    *,
    expected_ssh_known_hosts_sha256: str,
    device_policy_path: Path,
) -> dict[str, str]:
    provenance = require_dict(
        final.get("tooling_provenance"), "tooling_provenance_mismatch"
    )
    if set(provenance) != {
        "source_commit",
        "source_dirty",
        "files",
        "external_inputs",
    }:
        fail("tooling_provenance_mismatch")
    external_inputs = require_dict(
        provenance["external_inputs"], "tooling_provenance_mismatch"
    )
    if set(external_inputs) != {
        "ssh_known_hosts_sha256",
        "device_policy_sha256",
    }:
        fail("tooling_provenance_mismatch")
    known_hosts_sha256 = external_inputs.get("ssh_known_hosts_sha256")
    policy_sha256 = external_inputs.get("device_policy_sha256")
    if (
        not isinstance(expected_ssh_known_hosts_sha256, str)
        or SHA256_RE.fullmatch(expected_ssh_known_hosts_sha256) is None
        or not isinstance(known_hosts_sha256, str)
        or SHA256_RE.fullmatch(known_hosts_sha256) is None
        or expected_ssh_known_hosts_sha256 != known_hosts_sha256
    ):
        fail("ssh_known_hosts_mismatch")
    if not isinstance(policy_sha256, str) or SHA256_RE.fullmatch(policy_sha256) is None:
        fail("tooling_provenance_mismatch")
    policy_path = Path(device_policy_path)
    external_policy = read_regular(
        policy_path,
        maximum=MAX_JSON_BYTES,
        reason="device_policy_unavailable",
    )
    bundled_policy = read_regular(
        bundle / "device-policy.json",
        maximum=MAX_JSON_BYTES,
        reason="device_policy_unavailable",
    )
    try:
        if policy_path.resolve(strict=True) == (bundle / "device-policy.json").resolve(
            strict=True
        ):
            fail("device_policy_mismatch")
    except OSError:
        fail("device_policy_unavailable")
    if (
        sha256_bytes(external_policy) != policy_sha256
        or sha256_bytes(bundled_policy) != policy_sha256
        or external_policy != bundled_policy
    ):
        fail("device_policy_mismatch")
    commit = provenance["source_commit"]
    if not isinstance(commit, str) or COMMIT_RE.fullmatch(commit) is None:
        fail("tooling_provenance_mismatch")
    if provenance["source_dirty"] is not False:
        fail("tooling_provenance_mismatch")
    files = require_dict(provenance["files"], "tooling_provenance_mismatch")
    if set(files) != set(TOOL_PATHS):
        fail("tooling_provenance_mismatch")
    try:
        repository = repository.resolve(strict=True)
        execution_root = ROOT.resolve(strict=True)
    except OSError:
        fail("tooling_provenance_mismatch")
    if (
        not repository.is_dir()
        or is_reparse(repository)
        or repository != execution_root
    ):
        fail("tooling_provenance_mismatch")

    executed_modules = {
        "derive_helper": Path(evidence_helper.__file__).resolve(),
        "audit_verifier": Path(audit_verifier.__file__).resolve(),
        "client_db_verifier": Path(client_verifier.__file__).resolve(),
        "result_jsonl_verifier": Path(result_jsonl_verifier.__file__).resolve(),
        "bundle_verifier": Path(__file__).resolve(),
        "device_identity_verifier": Path(device_identity_verifier.__file__).resolve(),
        "ci_provenance_verifier": Path(ci_provenance_verifier.__file__).resolve(),
    }
    for label, actual in executed_modules.items():
        expected = repository.joinpath(*TOOL_PATHS[label].split("/")).resolve()
        if actual != expected:
            fail("tooling_provenance_mismatch")

    head = git_run(repository, ["rev-parse", "--verify", "HEAD"])
    status = git_run(
        repository,
        ["status", "--porcelain=v1", "--untracked-files=all"],
    )
    try:
        head_text = head.stdout.decode("ascii").strip().lower()
    except UnicodeError:
        fail("tooling_provenance_mismatch")
    if (
        head.returncode != 0
        or head_text != commit
        or status.returncode != 0
        or status.stdout
    ):
        fail("tooling_provenance_mismatch")

    for label, relative in TOOL_PATHS.items():
        digest = files[label]
        if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
            fail("tooling_provenance_mismatch")
        current = read_regular(
            repository.joinpath(*relative.split("/")),
            maximum=MAX_PAYLOAD_BYTES,
            reason="tooling_provenance_mismatch",
        )
        # Git may normalize line endings in the committed blob while leaving a
        # clean Windows checkout with CRLF bytes.  Bind the exact bytes that ran
        # to the recorded SHA-256, and independently require Git to consider
        # that path unchanged from the frozen commit.
        tracked = git_run(repository, ["cat-file", "-e", f"{commit}:{relative}"])
        unchanged = git_run(
            repository,
            ["diff", "--quiet", "--no-ext-diff", commit, "--", relative],
        )
        if (
            sha256_bytes(current) != digest
            or tracked.returncode != 0
            or unchanged.returncode != 0
        ):
            fail("tooling_provenance_mismatch")

    catalog, _ = strict_json_file(
        repository / TOOL_PATHS["spec_catalog"], "tooling_provenance_mismatch"
    )
    catalog = require_dict(catalog, "tooling_provenance_mismatch")
    contracts = require_list(
        catalog.get("execution_evidence_contracts"), "tooling_provenance_mismatch"
    )
    matching = [
        value
        for value in contracts
        if isinstance(value, dict)
        and value.get("contract_id") == "aneb-token-quick-request-entry-counts"
        and value.get("version") == "1.0.0"
    ]
    if len(matching) != 1 or matching[0].get("canonical_sha256") != final.get(
        "profile_contract_definition_sha256"
    ):
        fail("tooling_provenance_mismatch")

    ca_path = repository / TOOL_PATHS["server_ca"]
    ca_raw = read_regular(
        ca_path, maximum=MAX_JSON_BYTES, reason="tooling_provenance_mismatch"
    )
    try:
        der = ssl.PEM_cert_to_DER_cert(ca_raw.decode("ascii"))
    except (UnicodeError, ValueError):
        fail("tooling_provenance_mismatch")
    source = require_dict(final.get("source"), "source_identity_mismatch")
    if (
        source.get("server_ca_sha256") != sha256_bytes(ca_raw)
        or source.get("server_ca_thumbprint")
        != hashlib.sha1(der).hexdigest()
    ):
        fail("tooling_provenance_mismatch")
    return {
        "ssh_known_hosts_sha256": known_hosts_sha256,
        "device_policy_sha256": policy_sha256,
    }


def server_base_host(value: Any) -> str:
    if not isinstance(value, str) or len(value) > 2048:
        fail("remote_host_mismatch")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        fail("remote_host_mismatch")
    if (
        parsed.scheme != "https"
        or parsed.hostname is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
        or port is None
    ):
        fail("remote_host_mismatch")
    return parsed.hostname


def verify_remote_host_binding(
    bundle: Path,
    final: dict[str, Any],
    receipt: dict[str, Any],
    *,
    expected_remote_host: str,
    expected_ssh_known_hosts_sha256: str,
    device_policy_sha256: str,
) -> str:
    if (
        not isinstance(expected_remote_host, str)
        or not 1 <= len(expected_remote_host) <= 253
        or expected_remote_host.strip() != expected_remote_host
        or any(character in expected_remote_host for character in "/@?#[]")
    ):
        fail("remote_host_mismatch")
    plan_value, _ = strict_json_file(
        bundle / "collector-plan.json", "remote_host_mismatch"
    )
    plan = require_dict(plan_value, "remote_host_mismatch")
    source = require_dict(final.get("source"), "remote_host_mismatch")
    execution_mode = final.get("execution_mode")
    server_bases = (
        plan.get("server_base"),
        receipt.get("server_base"),
        source.get("server_base"),
    )
    if (
        any(not isinstance(value, str) for value in server_bases)
        or any(value != server_bases[0] for value in server_bases[1:])
        or any(server_base_host(value) != expected_remote_host for value in server_bases)
        or plan.get("remote_host") != expected_remote_host
    ):
        fail("remote_host_mismatch")
    if plan.get("ssh_known_hosts_sha256") != expected_ssh_known_hosts_sha256:
        fail("ssh_known_hosts_mismatch")
    if plan.get("device_policy_sha256") != device_policy_sha256:
        fail("device_policy_mismatch")
    if (
        plan.get("schema") != "aneb-d82-collector-plan"
        or plan.get("schema_version") != "1.1.0"
        or plan.get("execution_mode") != execution_mode
    ):
        fail("execution_mode_mismatch")
    if execution_mode == "positive":
        if (
            plan.get("client_server_base") != server_bases[0]
            or plan.get("negative_proxy_upstream_url") is not None
            or plan.get("negative_proxy_device_port") is not None
        ):
            fail("execution_mode_binding_invalid")
    elif execution_mode == "negative_receipt_missing":
        if (
            plan.get("client_server_base") != "http://127.0.0.1:18765"
            or plan.get("negative_proxy_upstream_url")
            != server_bases[0].rstrip("/") + "/api/v1/serverinfo"
            or type(plan.get("negative_proxy_device_port")) is not int
            or plan.get("negative_proxy_device_port") != 18765
        ):
            fail("execution_mode_binding_invalid")
    else:
        fail("execution_mode_invalid")
    return expected_remote_host


def verify_device_identity_binding(
    bundle: Path,
    final: dict[str, Any],
    *,
    device_policy_path: Path,
) -> dict[str, object]:
    preflight_value, _ = strict_json_file(
        bundle / "device-preflight.json", "device_identity_mismatch"
    )
    preflight = require_dict(preflight_value, "device_identity_mismatch")
    try:
        report = device_identity_verifier.verify_device_identity(
            bundle,
            policy_path=Path(device_policy_path),
            expected_input_serial=preflight["adb_serial"],
        )
    except (KeyError, TypeError):
        fail("device_identity_mismatch")
    except device_identity_verifier.DeviceIdentityFailure as error:
        fail(error.reason_code)
    expected = require_dict(final.get("device"), "device_identity_mismatch")
    if expected != report or set(expected) != set(report):
        fail("device_identity_mismatch")
    plan_value, _ = strict_json_file(
        bundle / "collector-plan.json", "device_identity_mismatch"
    )
    plan = require_dict(plan_value, "device_identity_mismatch")
    if (
        plan.get("adb_serial_sha256") != report.get("adb_serial_sha256")
        or plan.get("device_policy_sha256")
        != report.get("device_policy_sha256")
    ):
        fail("device_identity_mismatch")
    return report


def verify_candidate_binding(
    bundle: Path,
    final: dict[str, Any],
    apk_identity: dict[str, Any],
    *,
    gh_path: Path,
) -> dict[str, Any]:
    source_commit = final["tooling_provenance"]["source_commit"]
    try:
        report = ci_provenance_verifier.verify_candidate(
            bundle / "ci-candidate",
            expected_source_commit=source_commit,
            gh_command=(str(gh_path),),
            timeout_seconds=30,
        )
    except ci_provenance_verifier.ProvenanceVerificationFailure as error:
        fail(error.reason_code)
    candidate_apk = require_dict(
        report.get("apk"), "candidate_apk_identity_mismatch"
    )
    client = require_dict(final.get("client"), "candidate_apk_identity_mismatch")
    expected = {
        "package_name": client.get("package_name"),
        "version_name": client.get("version_name"),
        "version_code": client.get("version_code"),
        "signer_sha256": client.get("signer_sha256"),
    }
    if (
        report.get("source_commit") != source_commit
        or candidate_apk.get("file_name")
        != "ANEB-Probe-0.5.12-codex-debug.apk"
        or candidate_apk.get("sha256") != client.get("apk_sha256")
        or any(candidate_apk.get(key) != value for key, value in expected.items())
        or any(apk_identity.get(key) != value for key, value in expected.items())
    ):
        fail("candidate_apk_identity_mismatch")
    return report


def verify_serverinfo(
    bundle: Path, final: dict[str, Any], receipt: dict[str, Any]
) -> str:
    source = require_dict(final.get("source"), "source_identity_mismatch")
    if set(source) != SOURCE_KEYS:
        fail("source_identity_mismatch")
    expected_source = {
        "server_base": receipt["server_base"],
        "server_version": receipt["server_version"],
        "server_binary_sha256": receipt["server_binary_sha256"],
        "boot_id": receipt["boot_id"],
        "systemd_invocation_id": receipt["systemd_invocation_id"],
        "main_pid": receipt["main_pid"],
        "journal_cursor": receipt["journal_cursor"],
        "journal_monotonic_anchor": receipt["journal_monotonic_anchor"],
        "remote_realtime_anchor_usec": receipt["remote_realtime_anchor_usec"],
    }
    for key, value in expected_source.items():
        if source.get(key) != value:
            fail("source_identity_mismatch")
    body_digests = require_dict(
        source.get("serverinfo_body_sha256"), "source_identity_mismatch"
    )
    if set(body_digests) != {"identity", "start_barrier", "end_barrier"}:
        fail("source_identity_mismatch")
    snapshots: list[dict[str, Any]] = []
    for label, filename in {
        "identity": "identity-serverinfo.json",
        "start_barrier": "start-barrier.json",
        "end_barrier": "end-barrier.json",
    }.items():
        body, raw = strict_json_file(bundle / filename, "serverinfo_response_invalid")
        body = require_dict(body, "serverinfo_response_invalid")
        if set(body) != SERVERINFO_KEYS:
            fail("serverinfo_response_invalid")
        capabilities = require_dict(
            body.get("execution_capabilities"), "serverinfo_response_invalid"
        )
        if set(capabilities) != {
            "contract_id",
            "contract_version",
            "primitives",
            "validated_profiles",
        }:
            fail("serverinfo_response_invalid")
        primitives = require_list(
            capabilities.get("primitives"), "serverinfo_response_invalid"
        )
        primitive_map: dict[str, str] = {}
        for primitive_value in primitives:
            primitive = require_dict(primitive_value, "serverinfo_response_invalid")
            if set(primitive) != {"primitive_id", "wire_contract_id"}:
                fail("serverinfo_response_invalid")
            primitive_id = primitive.get("primitive_id")
            wire_contract_id = primitive.get("wire_contract_id")
            if (
                not isinstance(primitive_id, str)
                or not isinstance(wire_contract_id, str)
                or primitive_id in primitive_map
            ):
                fail("serverinfo_response_invalid")
            primitive_map[primitive_id] = wire_contract_id
        if primitive_map != {
            "download": "aneb-download-v1",
            "echo": "aneb-echo-v1",
            "token_sim": "aneb-token-task-v1",
        }:
            fail("serverinfo_response_invalid")
        profiles = require_list(
            capabilities.get("validated_profiles"), "serverinfo_response_invalid"
        )
        if len(profiles) != 1:
            fail("serverinfo_response_invalid")
        profile = require_dict(profiles[0], "serverinfo_response_invalid")
        if (
            set(profile) != {"profile_id", "profile_sha256", "profile_version"}
            or profile.get("profile_id") != "token_multimodal_quick"
            or profile.get("profile_version") != "1.2.1"
            or not isinstance(profile.get("profile_sha256"), str)
            or SHA256_ID_RE.fullmatch(profile["profile_sha256"]) is None
        ):
            fail("serverinfo_response_invalid")
        if (
            body.get("version") != source.get("server_version")
            or body.get("goos") != "linux"
            or body.get("goarch") != "amd64"
            or body.get("h3_enabled") is not True
            or body.get("tcp_slow_start_after_idle") != "0"
            or body.get("congestion_control") != "cubic"
            or type(body.get("srv_ts_us")) is not int
            or body["srv_ts_us"] <= 0
            or type(body.get("anchor_wall_unix_ns")) is not int
            or body["anchor_wall_unix_ns"] <= 0
            or type(body.get("uptime_s")) is not int
            or body["uptime_s"] <= 0
            or capabilities.get("contract_id")
            != "aneb-server-capability-receipt"
            or capabilities.get("contract_version") != "1.0.0"
            or body_digests.get(label) != sha256_bytes(raw)
        ):
            fail("serverinfo_response_invalid")
        header_name = filename.replace(".json", ".headers")
        header_raw = read_regular(
            bundle / header_name, maximum=1024 * 1024, reason="serverinfo_headers_invalid"
        )
        try:
            header_text = header_raw.decode("utf-8")
        except UnicodeError:
            fail("serverinfo_headers_invalid")
        statuses = re.findall(r"(?m)^HTTP/[0-9.]+\s+([0-9]{3})\b", header_text)
        if not statuses or statuses[-1] != "200":
            fail("serverinfo_headers_invalid")
        snapshots.append(body)
    if receipt["serverinfo_body_sha256"] != body_digests["identity"]:
        fail("source_identity_mismatch")
    if (
        len({body["anchor_wall_unix_ns"] for body in snapshots}) != 1
        or not (
            snapshots[0]["srv_ts_us"]
            < snapshots[1]["srv_ts_us"]
            < snapshots[2]["srv_ts_us"]
        )
        or not (
            snapshots[0]["uptime_s"]
            <= snapshots[1]["uptime_s"]
            <= snapshots[2]["uptime_s"]
        )
        or len(
            {
                body["execution_capabilities"]["validated_profiles"][0][
                    "profile_sha256"
                ]
                for body in snapshots
            }
        )
        != 1
    ):
        fail("serverinfo_sequence_invalid")
    if final["execution_mode"] == "negative_receipt_missing":
        upstream_value, _ = strict_json_file(
            bundle / "negative-proxy" / "upstream-serverinfo.raw",
            "negative_proxy_serverinfo_sequence_invalid",
        )
        upstream = require_dict(
            upstream_value, "negative_proxy_serverinfo_sequence_invalid"
        )
        if set(upstream) != SERVERINFO_KEYS:
            fail("negative_proxy_serverinfo_sequence_invalid")
        stable_keys = SERVERINFO_KEYS - {"srv_ts_us", "uptime_s"}
        if (
            any(upstream.get(key) != snapshots[1].get(key) for key in stable_keys)
            or type(upstream.get("srv_ts_us")) is not int
            or type(upstream.get("uptime_s")) is not int
            or not (
                snapshots[1]["srv_ts_us"]
                < upstream["srv_ts_us"]
                < snapshots[2]["srv_ts_us"]
            )
            or not (
                snapshots[1]["uptime_s"]
                <= upstream["uptime_s"]
                <= snapshots[2]["uptime_s"]
            )
        ):
            fail("negative_proxy_serverinfo_sequence_invalid")
    return snapshots[0]["execution_capabilities"]["validated_profiles"][0][
        "profile_sha256"
    ].removeprefix("sha256:")


def verify_negative_proxy_evidence(
    bundle: Path, final: dict[str, Any]
) -> dict[str, Any] | None:
    if final["execution_mode"] == "positive":
        return None
    plan_value, _ = strict_json_file(
        bundle / "collector-plan.json", "negative_proxy_evidence_invalid"
    )
    plan = require_dict(plan_value, "negative_proxy_evidence_invalid")
    source = require_dict(final.get("source"), "negative_proxy_evidence_invalid")
    try:
        report = negative_proxy_verifier.verify(
            bundle,
            run_id=final["run_id"],
            upstream_url=plan["negative_proxy_upstream_url"],
            ca_file_sha256=source["server_ca_sha256"],
            device_port=plan["negative_proxy_device_port"],
        )
    except (KeyError, TypeError):
        fail("negative_proxy_evidence_invalid")
    except negative_proxy_verifier.NegativeProxyEvidenceFailure as error:
        fail(error.reason_code)
    if (
        report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("run_id") != final["run_id"]
        or report.get("client_delivery_proven") is not False
        or not isinstance(report.get("adb_transport_label_sha256"), str)
        or SHA256_RE.fullmatch(report["adb_transport_label_sha256"]) is None
        or report.get("raw_files_verified")
        != len(negative_proxy_verifier.RAW_FILE_NAMES)
    ):
        fail("negative_proxy_evidence_invalid")
    return report


def verify_status_and_client_identity(
    bundle: Path,
    final: dict[str, Any],
    build_tools_directory: Path | None,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    status, _ = strict_json_file(bundle / "collector-status.json", "collector_status_invalid")
    cleanup, _ = strict_json_file(bundle / "cleanup-report.json", "cleanup_status_invalid")
    status = require_dict(status, "collector_status_invalid")
    cleanup = require_dict(cleanup, "cleanup_status_invalid")
    if (
        set(status)
        != {
            "schema",
            "schema_version",
            "completed_at_utc",
            "status",
            "reason_code",
            "failure",
            "workflow_succeeded",
            "cleanup_succeeded",
            "collection_id",
            "run_id",
            "start_barrier_id",
            "end_barrier_id",
            "partial_directory",
            "complete_directory",
        }
        or status.get("schema") != "aneb-d82-collector-status"
        or status.get("schema_version") != "1.0.0"
        or not valid_utc_timestamp(status.get("completed_at_utc"))
        or status.get("status") != "pass"
        or status.get("reason_code") != "ok"
        or status.get("failure") is not None
        or status.get("workflow_succeeded") is not True
        or status.get("cleanup_succeeded") is not True
        or status.get("collection_id") != final["collection_id"]
        or status.get("run_id") != final["run_id"]
        or status.get("start_barrier_id") != final["start_barrier_id"]
        or status.get("end_barrier_id") != final["end_barrier_id"]
    ):
        fail("collector_status_invalid")
    if (
        set(cleanup)
        != {
            "schema",
            "schema_version",
            "captured_at_utc",
            "status",
            "errors",
            "target_stop_attempted",
            "negative_reverse_preflight_captured",
            "negative_reverse_mutation_attempted",
            "negative_reverse_remove_attempted",
            "negative_reverse_final_captured",
            "negative_proxy_completed",
            "negative_proxy_stop_attempted",
            "negative_proxy_stop_succeeded",
            "busy_sentinel_start_attempted",
            "busy_sentinel_started",
            "busy_sentinel_verified",
            "busy_sentinel_restored_after_target",
            "busy_sentinel_lost",
            "busy_sentinel_component",
            "busy_sentinel_release_attempted",
            "busy_sentinel_home_succeeded",
            "stayon_restored",
            "lock_release_attempted",
        }
        or cleanup.get("schema") != "aneb-d82-collector-cleanup"
        or cleanup.get("schema_version") != "1.0.0"
        or not valid_utc_timestamp(cleanup.get("captured_at_utc"))
        or cleanup.get("status") != "pass"
        or cleanup.get("errors") != []
        or cleanup.get("target_stop_attempted") is not True
        or cleanup.get("busy_sentinel_start_attempted") is not True
        or cleanup.get("busy_sentinel_started") is not True
        or cleanup.get("busy_sentinel_verified") is not True
        or cleanup.get("busy_sentinel_restored_after_target") is not True
        or cleanup.get("busy_sentinel_lost") is not False
        or not isinstance(cleanup.get("busy_sentinel_component"), str)
        or re.fullmatch(
            r"com\.android\.settings/[A-Za-z0-9._$]+",
            cleanup["busy_sentinel_component"],
        )
        is None
        or cleanup.get("busy_sentinel_release_attempted") is not True
        or cleanup.get("busy_sentinel_home_succeeded") is not True
        or cleanup.get("stayon_restored") is not True
        or cleanup.get("lock_release_attempted") is not True
    ):
        fail("cleanup_status_invalid")
    negative_cleanup_keys = (
        "negative_reverse_preflight_captured",
        "negative_reverse_mutation_attempted",
        "negative_reverse_remove_attempted",
        "negative_reverse_final_captured",
        "negative_proxy_completed",
        "negative_proxy_stop_attempted",
        "negative_proxy_stop_succeeded",
    )
    expected_negative_cleanup = final.get("execution_mode") == "negative_receipt_missing"
    if any(cleanup.get(key) is not expected_negative_cleanup for key in negative_cleanup_keys):
        fail("cleanup_status_invalid")

    client = require_dict(final.get("client"), "client_identity_mismatch")
    if set(client) != {
        "package_name",
        "version_name",
        "version_code",
        "signer_sha256",
        "apk_sha256",
    }:
        fail("client_identity_mismatch")
    if (
        client["package_name"] != EXPECTED_CLIENT_PACKAGE
        or client["version_name"] != EXPECTED_CLIENT_VERSION_NAME
        or client["version_code"] != EXPECTED_CLIENT_VERSION_CODE
        or not isinstance(client["signer_sha256"], str)
        or SHA256_RE.fullmatch(client["signer_sha256"]) is None
        or not isinstance(client["apk_sha256"], str)
        or SHA256_RE.fullmatch(client["apk_sha256"]) is None
    ):
        fail("client_identity_mismatch")
    apk_raw = read_regular(
        bundle / "installed-base.apk",
        maximum=MAX_PAYLOAD_BYTES,
        reason="client_identity_mismatch",
    )
    if sha256_bytes(apk_raw) != client["apk_sha256"]:
        fail("client_identity_mismatch")
    apk_identity = verify_apk_identity(apk_raw, build_tools_directory)
    for key in ("package_name", "version_name", "version_code", "signer_sha256"):
        if apk_identity.get(key) != client[key]:
            fail("apk_identity_mismatch")
    preflight, _ = strict_json_file(
        bundle / "device-preflight.json", "client_identity_mismatch"
    )
    preflight = require_dict(preflight, "client_identity_mismatch")
    if (
        set(preflight)
        != {
            "schema",
            "schema_version",
            "captured_at_utc",
            "adb_serial",
            "launcher",
            "stay_on_while_plugged_in",
            "package_name",
            "version_name",
            "version_code",
            "signer_sha256",
            "apk_sha256",
            "run_as",
            "tun0",
            "active_vpn",
        }
        or preflight.get("schema") != "aneb-p40-live-preflight"
        or preflight.get("schema_version") != "1.0.0"
        or not valid_utc_timestamp(preflight.get("captured_at_utc"))
        or not isinstance(preflight.get("adb_serial"), str)
        or re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", preflight["adb_serial"]) is None
        or preflight.get("launcher") != LAUNCHER_COMPONENT
        or not isinstance(preflight.get("stay_on_while_plugged_in"), str)
        or re.fullmatch(r"(?:null|[0-9]+)", preflight["stay_on_while_plugged_in"])
        is None
        or preflight.get("run_as") != "available"
        or preflight.get("tun0") != "absent"
        or preflight.get("active_vpn") is not False
    ):
        fail("client_identity_mismatch")
    for key in (
        "package_name",
        "version_name",
        "version_code",
        "signer_sha256",
        "apk_sha256",
    ):
        if preflight.get(key) != client[key]:
            fail("client_identity_mismatch")

    final_state, _ = strict_json_file(
        bundle / "device-final-clean.json", "device_cleanup_evidence_invalid"
    )
    final_state = require_dict(final_state, "device_cleanup_evidence_invalid")
    if set(final_state) != {
        "schema",
        "schema_version",
        "captured_at_utc",
        "launcher",
        "processes",
        "services",
        "tun0",
        "active_vpn",
        "stay_on_while_plugged_in",
    }:
        fail("device_cleanup_evidence_invalid")
    processes = require_dict(
        final_state.get("processes"), "device_cleanup_evidence_invalid"
    )
    services = require_dict(
        final_state.get("services"), "device_cleanup_evidence_invalid"
    )
    if (
        final_state.get("schema") != "aneb-p40-live-clean-after"
        or final_state.get("schema_version") != "1.0.0"
        or not valid_utc_timestamp(final_state.get("captured_at_utc"))
        or final_state.get("launcher") != LAUNCHER_COMPONENT
        or set(processes) != CONFLICT_PACKAGES
        or any(value != "" for value in processes.values())
        or set(services) != CONFLICT_PACKAGES
        or any(
            not isinstance(value, str) or "ServiceRecord{" in value
            for value in services.values()
        )
        or final_state.get("tun0") != "absent"
        or final_state.get("active_vpn") is not False
        or final_state.get("stay_on_while_plugged_in")
        != preflight["stay_on_while_plugged_in"]
    ):
        fail("device_cleanup_evidence_invalid")
    return status, cleanup, apk_identity


def recompute_derivation(bundle: Path, final: dict[str, Any]) -> None:
    with tempfile.TemporaryDirectory(prefix="aneb-d82-reverify-") as temporary:
        temporary_root = Path(temporary)
        args = SimpleNamespace(
            journal=bundle / "journal.raw.jsonl",
            pre_start_receipt=bundle / "pre-start-receipt.json",
            run_id=final["run_id"],
            start_barrier_id=final["start_barrier_id"],
            end_barrier_id=final["end_barrier_id"],
            message_output=temporary_root / "token-run-audit.log",
            derivation_output=temporary_root / "journal-derivation.json",
        )
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                evidence_helper._derive(args)
        except (Exception, SystemExit):
            fail("journal_derivation_revalidation_failed")
        if (
            args.message_output.read_bytes()
            != read_regular(
                bundle / "token-run-audit.log",
                maximum=MAX_JSON_BYTES,
                reason="journal_derivation_revalidation_failed",
            )
            or args.derivation_output.read_bytes()
            != read_regular(
                bundle / "journal-derivation.json",
                maximum=MAX_JSON_BYTES,
                reason="journal_derivation_revalidation_failed",
            )
        ):
            fail("journal_derivation_revalidation_failed")


def recompute_audit(bundle: Path, final: dict[str, Any]) -> dict[str, Any]:
    raw = read_regular(
        bundle / "token-run-audit.log",
        maximum=MAX_JSON_BYTES,
        reason="request_entry_audit_revalidation_failed",
    )
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        fail("request_entry_audit_revalidation_failed")
    execution_mode = final["execution_mode"]
    audit_mode = (
        "negative"
        if execution_mode == "negative_receipt_missing"
        else "positive"
    )
    report = audit_verifier.verify_journal(
        text,
        run_id=final["run_id"],
        start_barrier_id=final["start_barrier_id"],
        barrier_id=final["end_barrier_id"],
        mode=audit_mode,
        profile_contract=PROFILE_CONTRACT,
    )
    stored, _ = strict_json_file(
        bundle / "request-entry-audit.json",
        "request_entry_audit_revalidation_failed",
    )
    if report != stored or report.get("status") != "pass" or report.get("reason_code") != "ok":
        fail("request_entry_audit_revalidation_failed")
    counts = require_dict(report.get("counts"), "request_entry_audit_revalidation_failed")
    business = require_dict(
        counts.get("business"), "request_entry_audit_revalidation_failed"
    )
    expected_business = (
        {"echo": 0, "token_sim": 0, "download": 0}
        if audit_mode == "negative"
        else {"echo": 20, "token_sim": 3, "download": 1}
    )
    if any(business.get(key) != value for key, value in expected_business.items()):
        fail("request_entry_audit_revalidation_failed")
    if audit_mode == "negative":
        if (
            counts.get("control") != 1
            or business.get("unexpected") != 0
            or counts.get("unexpected_control") != 0
            or counts.get("unattributed_business") != 0
        ):
            fail("request_entry_audit_revalidation_failed")
    return report


def verify_room_copy_inventory(bundle: Path) -> None:
    value, _ = strict_json_file(
        bundle / "room-copy-inventory.json", "room_copy_inventory_invalid"
    )
    inventory = require_dict(value, "room_copy_inventory_invalid")
    if (
        set(inventory)
        != {
            "schema",
            "schema_version",
            "captured_at_utc",
            "app_process_state",
            "files",
        }
        or inventory.get("schema") != "aneb-frozen-room-copy"
        or inventory.get("schema_version") != "1.0.0"
        or not valid_utc_timestamp(inventory.get("captured_at_utc"))
        or inventory.get("app_process_state") != "stopped_before_copy"
    ):
        fail("room_copy_inventory_invalid")
    files = require_list(inventory.get("files"), "room_copy_inventory_invalid")
    expected_names = ("aneb-probe.db", "aneb-probe.db-wal", "aneb-probe.db-shm")
    if len(files) != len(expected_names):
        fail("room_copy_inventory_invalid")
    states: dict[str, str] = {}
    for expected_name, raw_entry in zip(expected_names, files, strict=True):
        entry = require_dict(raw_entry, "room_copy_inventory_invalid")
        if entry.get("name") != expected_name or entry.get("state") not in {
            "present",
            "absent",
        }:
            fail("room_copy_inventory_invalid")
        path = bundle / expected_name
        state = entry["state"]
        states[expected_name] = state
        if state == "absent":
            if set(entry) != {"name", "state"} or os.path.lexists(path):
                fail("room_copy_inventory_invalid")
            continue
        if set(entry) != {"name", "state", "bytes", "sha256"}:
            fail("room_copy_inventory_invalid")
        if type(entry.get("bytes")) is not int or not 0 < entry["bytes"] <= MAX_PAYLOAD_BYTES:
            fail("room_copy_inventory_invalid")
        if (
            not isinstance(entry.get("sha256"), str)
            or SHA256_RE.fullmatch(entry["sha256"]) is None
        ):
            fail("room_copy_inventory_invalid")
        raw = read_regular(
            path,
            maximum=MAX_PAYLOAD_BYTES,
            reason="room_copy_inventory_invalid",
        )
        if len(raw) != entry["bytes"] or sha256_bytes(raw) != entry["sha256"]:
            fail("room_copy_inventory_invalid")
    if (
        states["aneb-probe.db"] != "present"
        or states["aneb-probe.db-wal"] != states["aneb-probe.db-shm"]
    ):
        fail("room_copy_inventory_invalid")


def recompute_client(bundle: Path, final: dict[str, Any]) -> dict[str, Any]:
    try:
        if final["execution_mode"] == "negative_receipt_missing":
            report, result_text = negative_client_verifier.verify(
                bundle / "aneb-probe.db",
                inventory=bundle / "room-copy-inventory.json",
                run_id=final["run_id"],
                manifest=PROFILE_MANIFEST,
                expected_server_base="http://127.0.0.1:18765",
            )
        else:
            report, result_text = client_verifier.verify(
                bundle / "aneb-probe.db",
                run_id=final["run_id"],
                manifest=PROFILE_MANIFEST,
                expected_server_base=require_dict(
                    final.get("source"), "source_identity_mismatch"
                )["server_base"],
            )
    except Exception:
        fail("client_room_revalidation_failed")
    stored, _ = strict_json_file(
        bundle / "client-db-report.json", "client_room_revalidation_failed"
    )
    result_raw = read_regular(
        bundle / "client-result.json",
        maximum=MAX_JSON_BYTES,
        reason="client_room_revalidation_failed",
    )
    common_invalid = (
        report != stored
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or result_raw != result_text.encode("utf-8")
        or report.get("result_body_sha256")
        != final.get("client_result_body_sha256")
    )
    if final["execution_mode"] == "negative_receipt_missing":
        mode_invalid = (
            report.get("schema")
            != "aneb-token-quick-negative-client-db-report"
            or report.get("schema_version") != "1.0.0"
            or report.get("negative_reason_code") != "receipt_missing"
            or report.get("endpoint_server_base") != "http://127.0.0.1:18765"
            or any(
                report.get(key) != 0
                for key in (
                    "business_task_count",
                    "business_kpi_observation_count",
                    "business_artifact_count",
                    "network_score_count",
                )
            )
        )
    else:
        mode_invalid = (
            report.get("schema") != "aneb-token-quick-client-db-report"
            or report.get("schema_version") != "1.2.0"
            or report.get("typed_metrics_verified") != 14
            or report.get("envelope_metrics_verified") != 26
        )
    if common_invalid or mode_invalid:
        fail("client_room_revalidation_failed")
    timing_keys = (
        "run_uuid_unix_ms",
        "run_start_delta_ms",
        "started_at_epoch_ms",
        "ended_at_epoch_ms",
        "serialized_at_epoch_ms",
    )
    if any(type(report.get(key)) is not int for key in timing_keys):
        fail("client_room_revalidation_failed")
    if (
        report["run_uuid_unix_ms"] != uuid_v7_epoch_ms(final["run_id"], "client_room_revalidation_failed")
        or report["run_start_delta_ms"]
        != report["started_at_epoch_ms"] - report["run_uuid_unix_ms"]
        or not 0 <= report["run_start_delta_ms"] <= 5_000
        or report["ended_at_epoch_ms"] < report["started_at_epoch_ms"]
        or report["serialized_at_epoch_ms"] != report["ended_at_epoch_ms"]
    ):
        fail("client_room_revalidation_failed")
    return report


def verify_evidence_time_chain(
    bundle: Path,
    final: dict[str, Any],
    receipt: dict[str, Any],
    client: dict[str, Any],
    status: dict[str, Any],
    cleanup: dict[str, Any],
) -> dict[str, object]:
    reason = "time_chain_input_invalid"
    plan_value, _ = strict_json_file(bundle / "collector-plan.json", reason)
    preflight_value, _ = strict_json_file(bundle / "device-preflight.json", reason)
    room_value, _ = strict_json_file(bundle / "room-copy-inventory.json", reason)
    final_state_value, _ = strict_json_file(
        bundle / "device-final-clean.json", reason
    )
    plan = require_dict(plan_value, reason)
    preflight = require_dict(preflight_value, reason)
    room = require_dict(room_value, reason)
    final_state = require_dict(final_state_value, reason)
    if (
        plan.get("schema") != "aneb-d82-collector-plan"
        or plan.get("schema_version") != "1.1.0"
        or plan.get("execution_mode") != final.get("execution_mode")
    ):
        fail("time_plan_invalid")
    try:
        return time_chain_verifier.verify_time_chain(
            execution_mode=final["execution_mode"],
            collection_id=final["collection_id"],
            plan=plan,
            preflight=preflight,
            receipt=receipt,
            client_report=client,
            room_inventory=room,
            final_state=final_state,
            cleanup=cleanup,
            status=status,
            final=final,
        )
    except time_chain_verifier.TimeChainFailure as error:
        fail(error.reason_code)


def verify_raw_state(
    bundle: Path, final: dict[str, Any], receipt: dict[str, Any]
) -> dict[str, Any]:
    client = require_dict(final.get("client"), "raw_expected_client_identity_invalid")
    source = require_dict(final.get("source"), "raw_remote_identity_mismatch")
    try:
        return raw_state_verifier.verify_raw_state(
            bundle,
            execution_mode=final["execution_mode"],
            expected_run_id=final["run_id"],
            expected_lock_nonce=receipt["lock_nonce"],
            expected_package_name=client["package_name"],
            expected_version_name=client["version_name"],
            expected_version_code=client["version_code"],
            expected_remote_identity={
                "boot_id": source["boot_id"],
                "systemd_invocation_id": source["systemd_invocation_id"],
                "main_pid": source["main_pid"],
                "server_binary_sha256": source["server_binary_sha256"],
            },
            expected_lock_remote_pid=receipt["lock_remote_pid"],
            expected_lock_marker=receipt["lock_marker"],
        )
    except (KeyError, TypeError):
        fail("raw_expected_identity_invalid")
    except raw_state_verifier.RawStateVerificationFailure as error:
        fail(error.reason_code)


def verify_bundle(
    bundle: Path,
    repository: Path,
    android_build_tools_directory: Path | None = None,
    *,
    expected_remote_host: str,
    expected_ssh_known_hosts_sha256: str,
    device_policy_path: Path,
    gh_path: Path,
    expected_execution_mode: str = "positive",
    publish_target: Path | None = None,
) -> dict[str, Any]:
    source_lexical = (
        absolute_lexical(bundle) if publish_target is not None else Path(bundle)
    )
    try:
        bundle = bundle.resolve(strict=True)
    except OSError:
        fail("bundle_unavailable")
    if not bundle.is_dir() or is_reparse(bundle) or not bundle.name.endswith(".complete"):
        fail("bundle_unavailable")

    final_value, final_raw = strict_json_file(
        bundle / "evidence-manifest.final.json", "final_manifest_invalid"
    )
    final = require_dict(final_value, "final_manifest_invalid")
    if set(final) != FINAL_KEYS:
        fail("final_manifest_invalid")
    if expected_execution_mode not in EXECUTION_SCOPES:
        fail("execution_mode_invalid")
    if final.get("execution_mode") != expected_execution_mode:
        fail("execution_mode_mismatch")
    if (
        final["schema"] != FINAL_SCHEMA
        or final["schema_version"] != FINAL_VERSION
        or final["status"] != "final"
        or final["acceptance_eligible"] is not True
        or final["evidence_scope"] != EXECUTION_SCOPES[expected_execution_mode]
        or final["profile_contract"] != PROFILE_CONTRACT
        or not valid_utc_timestamp(final.get("finalized_at_utc"))
        or not isinstance(final["profile_contract_definition_sha256"], str)
        or SHA256_RE.fullmatch(final["profile_contract_definition_sha256"]) is None
        or not isinstance(final["collection_id"], str)
        or COLLECTION_RE.fullmatch(final["collection_id"]) is None
        or bundle.name != final["collection_id"] + ".complete"
        or not isinstance(final["run_id"], str)
        or RUN_ID_RE.fullmatch(final["run_id"]) is None
        or not isinstance(final["start_barrier_id"], str)
        or UUID_V4_RE.fullmatch(final["start_barrier_id"]) is None
        or not isinstance(final["end_barrier_id"], str)
        or UUID_V4_RE.fullmatch(final["end_barrier_id"]) is None
        or len(
            {
                final["run_id"],
                final["start_barrier_id"],
                final["end_barrier_id"],
            }
        )
        != 3
    ):
        fail("final_manifest_invalid")
    manifest_sha = verify_complete_marker(bundle, final_raw, final)
    entries, _ = verify_inventory(bundle, final)
    verify_draft(bundle, final, entries)
    external_inputs = verify_tooling(
        bundle,
        final,
        repository,
        expected_ssh_known_hosts_sha256=expected_ssh_known_hosts_sha256,
        device_policy_path=device_policy_path,
    )
    try:
        receipt, _ = evidence_helper._load_receipt(
            bundle / "pre-start-receipt.json"
        )
    except Exception:
        fail("source_identity_mismatch")
    remote_host = verify_remote_host_binding(
        bundle,
        final,
        receipt,
        expected_remote_host=expected_remote_host,
        expected_ssh_known_hosts_sha256=expected_ssh_known_hosts_sha256,
        device_policy_sha256=external_inputs["device_policy_sha256"],
    )
    status, cleanup, apk_identity = verify_status_and_client_identity(
        bundle, final, android_build_tools_directory
    )
    device_identity = verify_device_identity_binding(
        bundle, final, device_policy_path=device_policy_path
    )
    candidate = verify_candidate_binding(
        bundle, final, apk_identity, gh_path=gh_path
    )
    raw_state = verify_raw_state(bundle, final, receipt)
    server_profile_sha256 = verify_serverinfo(bundle, final, receipt)
    negative_proxy = verify_negative_proxy_evidence(bundle, final)
    recompute_derivation(bundle, final)
    audit = recompute_audit(bundle, final)
    verify_room_copy_inventory(bundle)
    client = recompute_client(bundle, final)
    if client.get("profile_sha256") != "sha256:" + server_profile_sha256:
        fail("serverinfo_client_profile_mismatch")
    timing = verify_evidence_time_chain(
        bundle, final, receipt, client, status, cleanup
    )
    if publish_target is None:
        verify_bundle_unchanged(bundle, final, final_raw, entries)
    else:
        publish_verified_bundle(
            source_lexical,
            bundle,
            publish_target,
            final,
            final_raw,
            entries,
        )

    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "execution_mode": expected_execution_mode,
        "publication": publish_target is not None,
        "collection_id": final["collection_id"],
        "run_id": final["run_id"],
        "manifest_sha256": manifest_sha,
        "source_commit": final["tooling_provenance"]["source_commit"],
        "remote_host": remote_host,
        "ssh_known_hosts_sha256": external_inputs["ssh_known_hosts_sha256"],
        "server_version": final["source"]["server_version"],
        "server_binary_sha256": final["source"]["server_binary_sha256"],
        "apk_sha256": final["client"]["apk_sha256"],
        "apk_identity_reverified": True,
        "accessibility_raw_reverified": True,
        "raw_state_reverified": True,
        "raw_files_verified": raw_state["raw_files_verified"],
        "raw_state_files_verified": raw_state["raw_files_verified"],
        "device_identity_raw_files_verified": device_identity[
            "raw_files_verified"
        ],
        "raw_files_verified_total": raw_state["raw_files_verified"]
        + device_identity["raw_files_verified"],
        "device_identity": device_identity,
        "candidate_provenance_reverified": candidate[
            "candidate_provenance_reverified"
        ],
        "attestation_bundle_sha256": candidate["files"][
            "attestation_bundle_sha256"
        ],
        "gh_version": candidate["gh"]["version"],
        "gh_executable_sha256": candidate["gh"]["executable_sha256"],
        "evidence_time_chain_reverified": True,
        "run_duration_ms": timing["run_duration_ms"],
        "run_start_delta_ms": timing["run_start_delta_ms"],
        "remote_receipt_clock_delta_ms": timing[
            "remote_receipt_clock_delta_ms"
        ],
        "run_timeout_seconds": timing["run_timeout_seconds"],
        "lock_ttl_seconds": timing["lock_ttl_seconds"],
        "verified_apk_identity": apk_identity,
        "android_build_tools_version": ANDROID_BUILD_TOOLS_VERSION,
        "journal_derivation_recomputed": True,
        "request_entry_audit_recomputed": True,
        "client_room_result_recomputed": True,
        "negative_proxy_evidence_recomputed": negative_proxy is not None,
        "negative_reason_code": (
            "receipt_missing" if negative_proxy is not None else None
        ),
        "client_delivery_proven": (
            negative_proxy["client_delivery_proven"]
            if negative_proxy is not None
            else None
        ),
        "negative_proxy_raw_files_verified": (
            negative_proxy["raw_files_verified"]
            if negative_proxy is not None
            else 0
        ),
        "business_counts": {
            "echo": audit["counts"]["business"]["echo"],
            "token_sim": audit["counts"]["business"]["token_sim"],
            "download": audit["counts"]["business"]["download"],
        },
        "typed_metrics_verified": client.get("typed_metrics_verified", 0),
        "envelope_metrics_verified": client.get("envelope_metrics_verified", 0),
        "successful_task_count": client.get("successful_task_count", 0),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--repository-root", type=Path, default=ROOT)
    parser.add_argument("--android-build-tools-dir", type=Path)
    parser.add_argument("--expected-remote-host", required=True)
    parser.add_argument("--expected-ssh-known-hosts-sha256", required=True)
    parser.add_argument("--device-policy-path", type=Path, required=True)
    parser.add_argument("--gh-path", type=Path, required=True)
    parser.add_argument(
        "--expected-execution-mode",
        choices=tuple(EXECUTION_SCOPES),
        default="positive",
    )
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--publish-target", type=Path)
    args = parser.parse_args()
    try:
        if args.publish is not (args.publish_target is not None):
            fail("publish_boundary_invalid")
        report = verify_bundle(
            args.bundle,
            args.repository_root,
            args.android_build_tools_dir,
            expected_remote_host=args.expected_remote_host,
            expected_ssh_known_hosts_sha256=(
                args.expected_ssh_known_hosts_sha256
            ),
            device_policy_path=args.device_policy_path,
            gh_path=args.gh_path,
            expected_execution_mode=args.expected_execution_mode,
            publish_target=args.publish_target if args.publish else None,
        )
        return_code = 0
    except BundleFailure as error:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": error.reason_code,
            "collection_id": None,
            "run_id": None,
        }
        return_code = 1
    except Exception:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": "internal_verification_error",
            "collection_id": None,
            "run_id": None,
        }
        return_code = 1
    print(json.dumps(report, ensure_ascii=True, sort_keys=True, separators=(",", ":")))
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
