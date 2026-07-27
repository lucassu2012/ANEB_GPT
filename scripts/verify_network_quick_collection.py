#!/usr/bin/env python3
"""Independently revalidate one Network Quick collection without live I/O."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
from typing import Any, NoReturn
import urllib.parse

if __package__:
    from scripts import quick_collection_verifier as verifier_core
    from scripts import quick_collection_verifier_adapter as verifier_adapter
    from scripts import quick_evidence_security as evidence_security
    from scripts.collect_network_quick_evidence import (
        CollectorError,
        assert_network_serverinfo_sequence,
        compute_network_verifier_reports,
        parse_network_terminal_markers,
        validate_network_serverinfo,
    )
    from scripts.quick_collection_contract import network_quick_contract
else:
    import quick_collection_verifier as verifier_core
    import quick_collection_verifier_adapter as verifier_adapter
    import quick_evidence_security as evidence_security
    from collect_network_quick_evidence import (
        CollectorError,
        assert_network_serverinfo_sequence,
        compute_network_verifier_reports,
        parse_network_terminal_markers,
        validate_network_serverinfo,
    )
    from quick_collection_contract import network_quick_contract


CONTRACT = network_quick_contract()
REPORT_SCHEMA = "aneb-network-quick-collection-verification"
REPORT_VERSION = "1.0.0"
NEGATIVE_DEVICE_PORT = 18765
MAX_TEXT_BYTES = verifier_adapter.MAX_TEXT_BYTES
MAX_JSON_BYTES = verifier_adapter.MAX_JSON_BYTES
COLLECTION_RE = re.compile(
    r"^(?P<collection>m0-ec3-network-quick-"
    r"(?P<stamp>[0-9]{8}T[0-9]{6}Z)-[0-9a-f]{32})\.complete$"
)
COLLECTION_ID_RE = re.compile(
    r"^m0-ec3-network-quick-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}$"
)
RUN_ID_RE = verifier_core.RUN_ID_RE
SHA256_RE = verifier_core.SHA256_RE
COMMIT_RE = verifier_core.COMMIT_RE
PLAN_KEYS = verifier_adapter.PLAN_KEYS
STATUS_KEYS = verifier_adapter.STATUS_KEYS
RUN_RECEIPT_KEYS = verifier_adapter.RUN_RECEIPT_KEYS
CollectionVerificationFailure = verifier_core.CollectionVerificationFailure

_assert_directory = verifier_core.assert_directory
_canonical_json = verifier_core.canonical_json
_exact = verifier_core.exact
_load_json = verifier_core.load_json
_read_regular = verifier_core.read_regular
_sha256 = verifier_core.sha256
_validate_server_base = verifier_core.validate_server_base
_validate_uuid = verifier_core.validate_uuid


def fail(reason_code: str) -> NoReturn:
    raise CollectionVerificationFailure(reason_code)


def _same_host(left: str, right: str) -> bool:
    try:
        return ipaddress.ip_address(left) == ipaddress.ip_address(right)
    except ValueError:
        return left.casefold() == right.casefold()


def _validate_plan(plan: dict[str, Any], collection: str) -> None:
    if (
        not _exact(plan, PLAN_KEYS)
        or plan.get("schema") != CONTRACT.plan_schema
        or plan.get("schema_version") != "1.0.0"
        or plan.get("collection_id") != collection
        or plan.get("profile_contract") != CONTRACT.profile_contract
        or plan.get("evidence_mode") not in {"positive", "negative"}
        or plan.get("transport") not in {"auto", "wifi", "cellular"}
        or plan.get("package_name") != CONTRACT.package_name
        or plan.get("version_name") != CONTRACT.expected_version_name
        or plan.get("version_code") != CONTRACT.expected_version_code
        or plan.get("expected_server_version") != CONTRACT.expected_server_version
        or not isinstance(plan.get("expected_server_binary_sha256"), str)
        or SHA256_RE.fullmatch(str(plan["expected_server_binary_sha256"])) is None
        or not isinstance(plan.get("expected_apk_sha256"), str)
        or SHA256_RE.fullmatch(str(plan["expected_apk_sha256"])) is None
        or not isinstance(plan.get("expected_signer_sha256"), str)
        or SHA256_RE.fullmatch(str(plan["expected_signer_sha256"])) is None
        or not isinstance(plan.get("source_commit"), str)
        or COMMIT_RE.fullmatch(str(plan["source_commit"])) is None
        or type(plan.get("workflow_run_id")) is not int
        or int(plan["workflow_run_id"]) <= 0
        or not isinstance(plan.get("server_ca_sha256"), str)
        or SHA256_RE.fullmatch(str(plan["server_ca_sha256"])) is None
        or not isinstance(plan.get("device_policy_sha256"), str)
        or SHA256_RE.fullmatch(str(plan["device_policy_sha256"])) is None
        or not isinstance(plan.get("adb_serial_sha256"), str)
        or SHA256_RE.fullmatch(str(plan["adb_serial_sha256"])) is None
        or type(plan.get("run_timeout_seconds")) is not int
        or not 60 <= int(plan["run_timeout_seconds"]) <= 1800
        or type(plan.get("lock_ttl_seconds")) is not int
        or not 120 <= int(plan["lock_ttl_seconds"]) <= 3600
        or int(plan["lock_ttl_seconds"]) <= int(plan["run_timeout_seconds"])
        or type(plan.get("install_candidate")) is not bool
    ):
        fail("collector_plan_invalid")
    _validate_uuid(
        plan.get("start_barrier_id"), version=4, reason="collector_plan_invalid"
    )
    _validate_uuid(
        plan.get("end_barrier_id"), version=4, reason="collector_plan_invalid"
    )
    server = _validate_server_base(
        plan.get("server_base"), "collector_plan_invalid"
    )
    remote_host = plan.get("remote_host")
    if (
        not isinstance(remote_host, str)
        or not remote_host
        or not _same_host(str(server.hostname), remote_host)
    ):
        fail("collector_plan_invalid")
    if plan["evidence_mode"] == "positive":
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
        or status.get("schema") != CONTRACT.status_schema
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
        status.get("run_id"), version=7, reason="collector_status_invalid"
    )
    if (
        not _exact(receipt, RUN_RECEIPT_KEYS)
        or receipt.get("schema") != CONTRACT.run_receipt_schema
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
    expected = (
        ("authorized", "completed")
        if plan["evidence_mode"] == "positive"
        else ("rejected", "contract_rejected")
    )
    if (
        receipt.get("contract_status") != expected[0]
        or receipt.get("terminal_status") != expected[1]
    ):
        fail("run_receipt_invalid")
    return run_id


def _network_serverinfo_body(body: object) -> None:
    try:
        validate_network_serverinfo(body)
    except CollectorError:
        fail("serverinfo_invalid")


def _network_serverinfo_sequence(bodies: list[dict[str, Any]]) -> None:
    try:
        assert_network_serverinfo_sequence(bodies[0], bodies[1], bodies[2])
    except (CollectorError, IndexError):
        fail("serverinfo_sequence_invalid")


def _validate_evidence_root_security(
    root: Path,
    report: dict[str, Any],
) -> None:
    try:
        evidence_security.validate_report(root, report)
    except evidence_security.EvidenceSecurityFailure:
        fail("evidence_root_security_invalid")


def _mechanics_adapter() -> verifier_adapter.QuickCollectionVerifierAdapter:
    return verifier_adapter.QuickCollectionVerifierAdapter(
        manifest_schema=CONTRACT.manifest_schema,
        complete_marker=CONTRACT.complete_marker,
        remote_marker_prefix=CONTRACT.remote_marker_prefix,
        candidate=verifier_adapter.CandidateContract(
            apk_name=CONTRACT.candidate_apk_name,
            files=CONTRACT.candidate_files,
            package_name=CONTRACT.package_name,
            version_name=CONTRACT.expected_version_name,
            version_code=CONTRACT.expected_version_code,
        ),
        phone=verifier_adapter.PhoneStateContract(
            receipt_schema=CONTRACT.phone_receipt_schema,
            launcher_component=verifier_adapter.DEFAULT_LAUNCHER_COMPONENT,
            relevant_packages=verifier_adapter.DEFAULT_RELEVANT_PACKAGES,
        ),
        device_identity=verifier_adapter.DeviceIdentityContract(
            identity_schema=CONTRACT.device_identity_schema,
            property_keys=verifier_adapter.DEFAULT_DEVICE_PROPERTY_KEYS,
            optional_property_keys=(
                verifier_adapter.DEFAULT_OPTIONAL_DEVICE_PROPERTY_KEYS
            ),
        ),
        evidence_root_validator=_validate_evidence_root_security,
        serverinfo_body_validator=_network_serverinfo_body,
        serverinfo_sequence_validator=_network_serverinfo_sequence,
        negative_required_paths=(
            verifier_adapter.DEFAULT_NEGATIVE_REQUIRED_PATHS
        ),
    )


def _read_utf8(path: Path, reason: str) -> str:
    raw = _read_regular(path, maximum=MAX_TEXT_BYTES, reason=reason)
    try:
        return raw.decode("utf-8", errors="strict")
    except UnicodeError:
        fail(reason)


def _post_capture_marker_log(text: str) -> str:
    pattern = re.compile(
        r"(?m)^[^\r\n]*D82_CAPTURE_MARKER nonce=([0-9a-f]{32})\s*$"
    )
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        fail("network_logcat_capture_marker_invalid")
    return text[matches[0].end() :]


def _verify_busy_sentinels(bundle: Path) -> None:
    expected_keys = {
        "schema",
        "schema_version",
        "stage",
        "observed_components",
        "matched",
    }
    for stage in ("acquired", "before-target", "before-end-barrier"):
        value, _ = _load_json(
            bundle / f"busy-sentinel-{stage}.json",
            "busy_sentinel_invalid",
        )
        components = value.get("observed_components")
        if (
            set(value) != expected_keys
            or value.get("schema") != CONTRACT.busy_sentinel_schema
            or value.get("schema_version") != "1.0.0"
            or value.get("stage") != stage
            or value.get("matched") is not True
            or not isinstance(components, list)
            or not components
            or not all(isinstance(item, str) for item in components)
        ):
            fail("busy_sentinel_invalid")
        try:
            canonical = [
                verifier_core.canonical_component(
                    item, reason="phone_state_invalid"
                )
                for item in components
            ]
        except CollectionVerificationFailure:
            fail("busy_sentinel_invalid")
        if any(
            not item.startswith("com.android.settings/")
            for item in canonical
        ):
            fail("busy_sentinel_invalid")


def _verify_logcat_stderr(bundle: Path) -> None:
    raw = _read_regular(
        bundle / "app-logcat.stderr.txt",
        maximum=MAX_TEXT_BYTES,
        reason="network_logcat_stderr_invalid",
        allow_empty=True,
    )
    if raw:
        fail("network_logcat_stderr_not_empty")


def revalidate_cross_evidence(
    bundle: Path,
    *,
    plan: dict[str, Any],
    run_id: str,
) -> dict[str, object]:
    mode = str(plan["evidence_mode"])
    try:
        markers = parse_network_terminal_markers(
            _post_capture_marker_log(
                _read_utf8(
                    bundle / "app-logcat.txt", "network_logcat_invalid"
                )
            ),
            mode=mode,
        )
        if markers.run_id != run_id:
            fail("cross_evidence_revalidation_failed")
        reports = compute_network_verifier_reports(
            markers=markers,
            journal_text=_read_utf8(
                bundle / "journal.raw.log", "network_journal_invalid"
            ),
            database=bundle / "aneb-probe.db",
            start_barrier_id=str(plan["start_barrier_id"]),
            barrier_id=str(plan["end_barrier_id"]),
            mode=mode,
            profile_path=bundle / "network-profile" / "profile.json",
            runtime_path=bundle / "network-profile" / "runtime_plan.json",
            manifest_path=bundle / "network-profile" / "manifest.sha256",
            expected_server_base=str(plan["client_server_base"]),
        )
    except (CollectorError, OSError, UnicodeError, ValueError):
        fail("cross_evidence_revalidation_failed")
    stored = {
        "client": _load_json(
            bundle / "client-db-verification.json", "client_report_invalid"
        )[0],
        "audit": _load_json(
            bundle / "server-audit-verification.json", "audit_report_invalid"
        )[0],
        "binding": _load_json(
            bundle / "cross-bound-report.json", "cross_report_invalid"
        )[0],
    }
    if reports != stored:
        fail("cross_report_revalidation_mismatch")
    return reports["binding"]


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
        and COLLECTION_ID_RE.fullmatch(expected_collection) is not None
        and bundle.name == f"{expected_collection}.partial"
    ):
        collection = expected_collection
    else:
        fail("collection_leaf_invalid")
    _assert_directory(bundle, "collection_directory_invalid")
    mechanics = _mechanics_adapter()

    manifest, manifest_sha = mechanics.verify_manifest(bundle)
    root_security_sha = mechanics.verify_evidence_root_security(bundle)
    plan, _ = _load_json(bundle / "collector-plan.json", "collector_plan_invalid")
    _validate_plan(plan, collection)
    mechanics.verify_mode_inventory(manifest, mode=str(plan["evidence_mode"]))
    status, _ = _load_json(bundle / "collector-status.json", "collector_status_invalid")
    run_receipt, _ = _load_json(bundle / "run-receipt.json", "run_receipt_invalid")
    cross_report, cross_raw = _load_json(
        bundle / "cross-bound-report.json", "cross_report_invalid"
    )
    run_id = _validate_status_and_run(
        status,
        run_receipt,
        collection=collection,
        plan=plan,
        cross_raw=cross_raw,
    )
    mechanics.verify_complete(
        bundle,
        collection=collection,
        run_id=run_id,
        manifest_sha256=manifest_sha,
    )
    mechanics.verify_candidate(bundle, plan)
    mechanics.verify_device_identity(bundle, plan)
    _verify_busy_sentinels(bundle)
    _verify_logcat_stderr(bundle)
    preflight_hash = mechanics.verify_phone_pair(bundle, "phone-preflight")
    postflight_hash = mechanics.verify_phone_pair(bundle, "phone-postflight")
    if preflight_hash != postflight_hash:
        fail("phone_baseline_not_restored")
    journal_cursor = mechanics.verify_remote(bundle, plan)
    lock_nonce = mechanics.verify_lock(bundle)
    server_version = mechanics.verify_serverinfo(bundle)
    recomputed_cross = revalidate_cross_evidence(bundle, plan=plan, run_id=run_id)
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
        "lock_nonce_sha256": hashlib.sha256(lock_nonce.encode("ascii")).hexdigest(),
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
        description="Verify one Network Quick collection", allow_abbrev=False
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
