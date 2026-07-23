#!/usr/bin/env python3
"""Fail-closed consumer for a digest-bound D-87 READY release marker."""

from __future__ import annotations

from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import Any, NoReturn


RELEASE_SCHEMA = "aneb-d82-evidence-release"
RELEASE_VERSION = "1.0.0"
REPORT_SCHEMA = "aneb-d82-evidence-release-verification"
REPORT_VERSION = "1.0.0"
FINAL_MANIFEST_SCHEMA = "aneb-d82-final-evidence-manifest"
FINAL_MANIFEST_VERSION = "1.1.0"
BUNDLE_REPORT_SCHEMA = "aneb-d82-bundle-verification-report"
BUNDLE_REPORT_VERSION = "1.1.0"
PROFILE_CONTRACT = "token_multimodal_quick@1.2.1"
MAX_READY_BYTES = 64 * 1024
MAX_COMPLETE_BYTES = 4 * 1024
MAX_BOUND_JSON_BYTES = 32 * 1024 * 1024
FILE_ATTRIBUTE_REPARSE_POINT = 0x400
COLLECTION_RE = re.compile(
    r"^d82-token-quick-(?P<stamp>[0-9]{8}T[0-9]{6}Z)-[0-9a-f]{32}$"
)
READY_LEAF_RE = re.compile(
    r"^(?P<collection>d82-token-quick-[0-9]{8}T[0-9]{6}Z-"
    r"[0-9a-f]{32})\.READY\.json$"
)
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
UTC_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:"
    r"[0-9]{2}:[0-9]{2}\.[0-9]{7}Z$"
)
EXECUTION_MODES = frozenset({"positive", "negative_receipt_missing"})
EVIDENCE_SCOPES = {
    "positive": "d82_token_quick_cross_bound_acceptance",
    "negative_receipt_missing": "d82_token_quick_contract_rejection_acceptance",
}
READY_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "collection_id",
        "run_id",
        "execution_mode",
        "bundle_leaf",
        "manifest_sha256",
        "verification_report_leaf",
        "verification_report_sha256",
        "committed_at_utc",
    }
)
FINAL_MANIFEST_KEYS = frozenset(
    {
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
)
BUNDLE_REPORT_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "execution_mode",
        "publication",
        "collection_id",
        "run_id",
        "manifest_sha256",
        "source_commit",
        "remote_host",
        "ssh_known_hosts_sha256",
        "server_version",
        "server_binary_sha256",
        "apk_sha256",
        "apk_identity_reverified",
        "accessibility_raw_reverified",
        "raw_state_reverified",
        "raw_files_verified",
        "raw_state_files_verified",
        "device_identity_raw_files_verified",
        "raw_files_verified_total",
        "device_identity",
        "candidate_provenance_reverified",
        "attestation_bundle_sha256",
        "gh_version",
        "gh_executable_sha256",
        "evidence_time_chain_reverified",
        "run_duration_ms",
        "run_start_delta_ms",
        "remote_receipt_clock_delta_ms",
        "run_timeout_seconds",
        "lock_ttl_seconds",
        "verified_apk_identity",
        "android_build_tools_version",
        "journal_derivation_recomputed",
        "request_entry_audit_recomputed",
        "client_room_result_recomputed",
        "negative_proxy_evidence_recomputed",
        "negative_reason_code",
        "client_delivery_proven",
        "negative_proxy_raw_files_verified",
        "business_counts",
        "typed_metrics_verified",
        "envelope_metrics_verified",
        "successful_task_count",
    }
)
TOOLING_PROVENANCE_KEYS = frozenset(
    {"source_commit", "source_dirty", "files", "external_inputs"}
)
EXTERNAL_INPUT_KEYS = frozenset(
    {"ssh_known_hosts_sha256", "device_policy_sha256"}
)
SOURCE_KEYS = frozenset(
    {
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
)
CLIENT_KEYS = frozenset(
    {
        "package_name",
        "version_name",
        "version_code",
        "signer_sha256",
        "apk_sha256",
    }
)
VERIFIED_APK_IDENTITY_KEYS = frozenset(
    {"package_name", "version_name", "version_code", "signer_sha256"}
)
DEVICE_IDENTITY_KEYS = frozenset(
    {
        "schema",
        "schema_version",
        "status",
        "reason_code",
        "device_alias",
        "device_policy_sha256",
        "adb_serial_sha256",
        "android_boot_id",
        "properties_sha256",
        "serial_property_confirmed",
        "verified_boot_observed_complete",
        "verified_boot_secure",
        "raw_files_verified",
    }
)
TRUE_VERIFICATION_FIELDS = frozenset(
    {
        "apk_identity_reverified",
        "accessibility_raw_reverified",
        "raw_state_reverified",
        "candidate_provenance_reverified",
        "evidence_time_chain_reverified",
        "journal_derivation_recomputed",
        "request_entry_audit_recomputed",
        "client_room_result_recomputed",
    }
)


class ReleaseVerificationFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> NoReturn:
    raise ReleaseVerificationFailure(reason_code)


def _is_reparse(metadata: os.stat_result) -> bool:
    attributes = int(getattr(metadata, "st_file_attributes", 0))
    return stat.S_ISLNK(metadata.st_mode) or bool(
        attributes & FILE_ATTRIBUTE_REPARSE_POINT
    )


def _assert_directory_chain(path: Path, *, missing_reason: str) -> None:
    absolute = Path(os.path.abspath(os.fspath(path)))
    chain = list(reversed((absolute, *absolute.parents)))
    for component in chain:
        try:
            metadata = component.lstat()
        except OSError:
            fail(missing_reason)
        if _is_reparse(metadata):
            fail("release_path_reparse_forbidden")
        if not stat.S_ISDIR(metadata.st_mode):
            fail(missing_reason)


def _read_regular(path: Path, *, maximum: int, reason: str) -> bytes:
    try:
        before = path.lstat()
    except OSError:
        fail(reason)
    if _is_reparse(before):
        fail("release_path_reparse_forbidden")
    if not stat.S_ISREG(before.st_mode):
        fail(reason)
    if before.st_size <= 0 or before.st_size > maximum:
        fail(reason)
    try:
        with path.open("rb") as stream:
            opened = os.fstat(stream.fileno())
            if _is_reparse(opened):
                fail("release_path_reparse_forbidden")
            if not stat.S_ISREG(opened.st_mode):
                fail(reason)
            if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
                fail(reason)
            raw = stream.read(maximum + 1)
        after = path.lstat()
    except OSError:
        fail(reason)
    if (
        len(raw) <= 0
        or len(raw) > maximum
        or (after.st_dev, after.st_ino, after.st_size)
        != (before.st_dev, before.st_ino, before.st_size)
    ):
        fail(reason)
    if _is_reparse(after):
        fail("release_path_reparse_forbidden")
    return raw


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _load_strict_json_object(
    raw: bytes,
    *,
    utf8_reason: str,
    json_reason: str,
    contract_reason: str,
) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def reject_constant(_: str) -> NoReturn:
        raise ValueError("non-finite JSON number")

    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        fail(utf8_reason)
    if text.startswith("\ufeff"):
        fail(utf8_reason)
    try:
        value = json.loads(
            text,
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except (json.JSONDecodeError, ValueError, RecursionError):
        fail(json_reason)
    if not isinstance(value, dict):
        fail(contract_reason)
    return value


def _load_ready(raw: bytes) -> dict[str, Any]:
    return _load_strict_json_object(
        raw,
        utf8_reason="release_ready_utf8_invalid",
        json_reason="release_ready_json_invalid",
        contract_reason="release_ready_contract_invalid",
    )


def _validate_ready(marker: dict[str, Any], collection_from_leaf: str) -> None:
    if set(marker) != READY_KEYS:
        fail("release_ready_keys_invalid")
    if (
        marker.get("schema") != RELEASE_SCHEMA
        or marker.get("schema_version") != RELEASE_VERSION
        or marker.get("status") != "ready"
        or marker.get("reason_code") != "ok"
    ):
        fail("release_ready_contract_invalid")
    collection = marker.get("collection_id")
    run_id = marker.get("run_id")
    mode = marker.get("execution_mode")
    collection_match = (
        COLLECTION_RE.fullmatch(collection) if isinstance(collection, str) else None
    )
    if (
        collection_match is None
        or collection != collection_from_leaf
        or not isinstance(run_id, str)
        or RUN_ID_RE.fullmatch(run_id) is None
        or not isinstance(mode, str)
        or mode not in EXECUTION_MODES
    ):
        fail("release_ready_identity_invalid")
    try:
        datetime.strptime(collection_match["stamp"], "%Y%m%dT%H%M%SZ")
    except ValueError:
        fail("release_ready_identity_invalid")
    if (
        marker.get("bundle_leaf") != f"{collection}.complete"
        or marker.get("verification_report_leaf")
        != f"{collection}.verification.json"
        or not isinstance(marker.get("manifest_sha256"), str)
        or SHA256_RE.fullmatch(marker["manifest_sha256"]) is None
        or not isinstance(marker.get("verification_report_sha256"), str)
        or SHA256_RE.fullmatch(marker["verification_report_sha256"]) is None
    ):
        fail("release_ready_binding_invalid")
    timestamp = marker.get("committed_at_utc")
    if not isinstance(timestamp, str) or UTC_RE.fullmatch(timestamp) is None:
        fail("release_ready_timestamp_invalid")
    try:
        datetime.strptime(timestamp[:26] + "Z", "%Y-%m-%dT%H:%M:%S.%fZ")
    except ValueError:
        fail("release_ready_timestamp_invalid")


def _validate_final_manifest(
    manifest: dict[str, Any], marker: dict[str, Any]
) -> None:
    if set(manifest) != FINAL_MANIFEST_KEYS:
        fail("release_manifest_contract_invalid")
    manifest_mode = manifest.get("execution_mode")
    if (
        manifest.get("schema") != FINAL_MANIFEST_SCHEMA
        or manifest.get("schema_version") != FINAL_MANIFEST_VERSION
        or manifest.get("status") != "final"
        or manifest.get("acceptance_eligible") is not True
        or manifest_mode not in EXECUTION_MODES
        or manifest.get("evidence_scope") != EVIDENCE_SCOPES[manifest_mode]
        or manifest.get("profile_contract") != PROFILE_CONTRACT
    ):
        fail("release_manifest_contract_invalid")
    if (
        manifest.get("collection_id") != marker["collection_id"]
        or manifest.get("run_id") != marker["run_id"]
        or manifest_mode != marker["execution_mode"]
    ):
        fail("release_manifest_binding_mismatch")


def _validate_bundle_report(
    report: dict[str, Any],
    marker: dict[str, Any],
    manifest_sha256: str,
) -> None:
    if set(report) != BUNDLE_REPORT_KEYS:
        fail("release_verification_report_contract_invalid")
    if (
        report.get("schema") != BUNDLE_REPORT_SCHEMA
        or report.get("schema_version") != BUNDLE_REPORT_VERSION
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("publication") is not True
        or any(report.get(field) is not True for field in TRUE_VERIFICATION_FIELDS)
    ):
        fail("release_verification_report_contract_invalid")
    if (
        report.get("collection_id") != marker["collection_id"]
        or report.get("run_id") != marker["run_id"]
        or report.get("execution_mode") != marker["execution_mode"]
        or report.get("manifest_sha256") != manifest_sha256
    ):
        fail("release_verification_report_binding_mismatch")
    if marker["execution_mode"] == "positive":
        counts = report.get("business_counts")
        if (
            not isinstance(counts, dict)
            or set(counts) != {"echo", "token_sim", "download"}
            or type(counts.get("echo")) is not int
            or counts.get("echo") != 20
            or type(counts.get("token_sim")) is not int
            or counts.get("token_sim") != 3
            or type(counts.get("download")) is not int
            or counts.get("download") != 1
            or type(report.get("typed_metrics_verified")) is not int
            or report.get("typed_metrics_verified") != 14
            or type(report.get("envelope_metrics_verified")) is not int
            or report.get("envelope_metrics_verified") != 26
            or type(report.get("successful_task_count")) is not int
            or report.get("successful_task_count") != 3
            or report.get("negative_proxy_evidence_recomputed") is not False
            or report.get("negative_reason_code") is not None
            or report.get("client_delivery_proven") is not None
            or type(report.get("negative_proxy_raw_files_verified")) is not int
            or report.get("negative_proxy_raw_files_verified") != 0
        ):
            fail("release_verification_report_contract_invalid")
    else:
        counts = report.get("business_counts")
        if (
            not isinstance(counts, dict)
            or set(counts) != {"echo", "token_sim", "download"}
            or type(counts.get("echo")) is not int
            or counts.get("echo") != 0
            or type(counts.get("token_sim")) is not int
            or counts.get("token_sim") != 0
            or type(counts.get("download")) is not int
            or counts.get("download") != 0
            or type(report.get("typed_metrics_verified")) is not int
            or report.get("typed_metrics_verified") != 0
            or type(report.get("envelope_metrics_verified")) is not int
            or report.get("envelope_metrics_verified") != 0
            or type(report.get("successful_task_count")) is not int
            or report.get("successful_task_count") != 0
            or report.get("negative_proxy_evidence_recomputed") is not True
            or report.get("negative_reason_code") != "receipt_missing"
            or report.get("client_delivery_proven") is not False
            or type(report.get("negative_proxy_raw_files_verified")) is not int
            or report.get("negative_proxy_raw_files_verified") != 12
        ):
            fail("release_verification_report_contract_invalid")


def _validate_identity_closure(
    manifest: dict[str, Any], report: dict[str, Any]
) -> None:
    provenance = manifest.get("tooling_provenance")
    source = manifest.get("source")
    client = manifest.get("client")
    device = manifest.get("device")
    verified_apk = report.get("verified_apk_identity")
    report_device = report.get("device_identity")
    if (
        not isinstance(provenance, dict)
        or set(provenance) != TOOLING_PROVENANCE_KEYS
        or provenance.get("source_dirty") is not False
        or not isinstance(provenance.get("files"), dict)
        or not isinstance(provenance.get("external_inputs"), dict)
        or set(provenance["external_inputs"]) != EXTERNAL_INPUT_KEYS
        or not isinstance(source, dict)
        or set(source) != SOURCE_KEYS
        or not isinstance(client, dict)
        or set(client) != CLIENT_KEYS
        or not isinstance(device, dict)
        or set(device) != DEVICE_IDENTITY_KEYS
        or not isinstance(verified_apk, dict)
        or set(verified_apk) != VERIFIED_APK_IDENTITY_KEYS
        or not isinstance(report_device, dict)
        or set(report_device) != DEVICE_IDENTITY_KEYS
        or not isinstance(manifest.get("profile_contract_definition_sha256"), str)
        or SHA256_RE.fullmatch(
            manifest["profile_contract_definition_sha256"]
        )
        is None
        or not isinstance(provenance.get("source_commit"), str)
        or COMMIT_RE.fullmatch(provenance["source_commit"]) is None
        or not isinstance(
            provenance["external_inputs"].get("ssh_known_hosts_sha256"),
            str,
        )
        or SHA256_RE.fullmatch(
            provenance["external_inputs"]["ssh_known_hosts_sha256"]
        )
        is None
        or not isinstance(
            provenance["external_inputs"].get("device_policy_sha256"), str
        )
        or SHA256_RE.fullmatch(
            provenance["external_inputs"]["device_policy_sha256"]
        )
        is None
        or not isinstance(source.get("server_version"), str)
        or not 0 < len(source["server_version"]) <= 255
        or "\r" in source["server_version"]
        or "\n" in source["server_version"]
        or not isinstance(source.get("server_binary_sha256"), str)
        or SHA256_RE.fullmatch(source["server_binary_sha256"]) is None
        or not isinstance(client.get("apk_sha256"), str)
        or SHA256_RE.fullmatch(client["apk_sha256"]) is None
        or not isinstance(client.get("signer_sha256"), str)
        or SHA256_RE.fullmatch(client["signer_sha256"]) is None
        or not isinstance(client.get("package_name"), str)
        or not 0 < len(client["package_name"]) <= 255
        or "\r" in client["package_name"]
        or "\n" in client["package_name"]
        or not isinstance(client.get("version_name"), str)
        or not 0 < len(client["version_name"]) <= 255
        or "\r" in client["version_name"]
        or "\n" in client["version_name"]
        or type(client.get("version_code")) is not int
        or client["version_code"] <= 0
    ):
        fail("release_identity_contract_invalid")
    expected_apk_identity = {
        "package_name": client["package_name"],
        "version_name": client["version_name"],
        "version_code": client["version_code"],
        "signer_sha256": client["signer_sha256"],
    }
    if (
        report.get("source_commit") != provenance["source_commit"]
        or report.get("ssh_known_hosts_sha256")
        != provenance["external_inputs"]["ssh_known_hosts_sha256"]
        or report.get("server_version") != source["server_version"]
        or report.get("server_binary_sha256")
        != source["server_binary_sha256"]
        or report.get("apk_sha256") != client["apk_sha256"]
        or verified_apk != expected_apk_identity
        or report_device != device
    ):
        fail("release_identity_binding_mismatch")


def verify_release(ready_path: str | os.PathLike[str]) -> dict[str, object]:
    ready = Path(os.path.abspath(os.fspath(ready_path)))
    leaf_match = READY_LEAF_RE.fullmatch(ready.name)
    if leaf_match is None:
        fail("release_ready_leaf_invalid")
    _assert_directory_chain(ready.parent, missing_reason="release_ready_invalid")
    ready_raw = _read_regular(
        ready, maximum=MAX_READY_BYTES, reason="release_ready_invalid"
    )
    marker = _load_ready(ready_raw)
    collection = leaf_match["collection"]
    _validate_ready(marker, collection)

    bundle = ready.parent / f"{collection}.complete"
    report_path = ready.parent / f"{collection}.verification.json"
    manifest_path = bundle / "evidence-manifest.final.json"
    complete_path = bundle / "COMPLETE"
    _assert_directory_chain(bundle, missing_reason="release_bundle_invalid")
    manifest_raw = _read_regular(
        manifest_path,
        maximum=MAX_BOUND_JSON_BYTES,
        reason="release_manifest_invalid",
    )
    report_raw = _read_regular(
        report_path,
        maximum=MAX_BOUND_JSON_BYTES,
        reason="release_verification_report_invalid",
    )
    complete_raw = _read_regular(
        complete_path,
        maximum=MAX_COMPLETE_BYTES,
        reason="release_complete_invalid",
    )
    manifest_sha = _sha256(manifest_raw)
    report_sha = _sha256(report_raw)
    if manifest_sha != marker["manifest_sha256"]:
        fail("release_manifest_digest_mismatch")
    if report_sha != marker["verification_report_sha256"]:
        fail("release_verification_report_digest_mismatch")
    manifest = _load_strict_json_object(
        manifest_raw,
        utf8_reason="release_manifest_utf8_invalid",
        json_reason="release_manifest_json_invalid",
        contract_reason="release_manifest_contract_invalid",
    )
    report = _load_strict_json_object(
        report_raw,
        utf8_reason="release_verification_report_utf8_invalid",
        json_reason="release_verification_report_json_invalid",
        contract_reason="release_verification_report_contract_invalid",
    )
    _validate_final_manifest(manifest, marker)
    _validate_bundle_report(report, marker, manifest_sha)
    _validate_identity_closure(manifest, report)
    complete_re = re.compile(
        rb"^ANEB_D82_COMPLETE collection_id="
        + re.escape(collection.encode("ascii"))
        + rb" run_id="
        + re.escape(marker["run_id"].encode("ascii"))
        + rb" manifest=evidence-manifest\.final\.json manifest_sha256="
        + re.escape(manifest_sha.encode("ascii"))
        + rb"\n$"
    )
    if complete_re.fullmatch(complete_raw) is None:
        fail("release_complete_mismatch")

    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "collection_id": collection,
        "run_id": marker["run_id"],
        "execution_mode": marker["execution_mode"],
        "bundle_leaf": marker["bundle_leaf"],
        "manifest_sha256": manifest_sha,
        "verification_report_leaf": marker["verification_report_leaf"],
        "verification_report_sha256": report_sha,
        "ready_sha256": _sha256(ready_raw),
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


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        _emit(
            {
                "schema": REPORT_SCHEMA,
                "schema_version": REPORT_VERSION,
                "status": "fail",
                "reason_code": "release_ready_argument_invalid",
            },
            stream=sys.stderr,
        )
        return 2
    try:
        report = verify_release(argv[1])
    except ReleaseVerificationFailure as exc:
        _emit(
            {
                "schema": REPORT_SCHEMA,
                "schema_version": REPORT_VERSION,
                "status": "fail",
                "reason_code": exc.reason_code,
            },
            stream=sys.stderr,
        )
        return 1
    _emit(report, stream=sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
