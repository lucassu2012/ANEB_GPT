#!/usr/bin/env python3
"""Revalidate one published AI Realtime Quick collection without live I/O."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
from typing import Any

SCRIPT_DIRECTORY = str(Path(__file__).resolve().parent)
if SCRIPT_DIRECTORY not in sys.path:
    sys.path.insert(0, SCRIPT_DIRECTORY)

if __package__:
    from scripts import (
        quick_collection_verifier as verifier_core,
        quick_collection_verifier_adapter as verifier_adapter,
        quick_evidence_security as evidence_security,
        verify_realtime_quick_evidence_bundle as cross_verifier,
    )
else:
    import quick_collection_verifier as verifier_core
    import quick_collection_verifier_adapter as verifier_adapter
    import quick_evidence_security as evidence_security
    import verify_realtime_quick_evidence_bundle as cross_verifier


REPORT_SCHEMA = "aneb-realtime-quick-collection-verification"
REPORT_VERSION = "1.0.0"
PROFILE_CONTRACT = "ai_realtime_voice_quick@1.1.1"
EXPECTED_SERVER_VERSION = "aneb-server/0.8.1"
EXPECTED_PACKAGE = "com.aneb.probe.codex"
EXPECTED_VERSION_NAME = "0.5.13-codex"
EXPECTED_VERSION_CODE = 45
NEGATIVE_DEVICE_PORT = 18765
NEGATIVE_REQUIRED_PATHS = verifier_adapter.DEFAULT_NEGATIVE_REQUIRED_PATHS
MAX_JSON_BYTES = verifier_adapter.MAX_JSON_BYTES
MAX_MANIFEST_BYTES = verifier_adapter.MAX_MANIFEST_BYTES
MAX_APK_BYTES = verifier_adapter.MAX_APK_BYTES
MAX_TEXT_BYTES = verifier_adapter.MAX_TEXT_BYTES

COLLECTION_RE = re.compile(
    r"^(?P<collection>m0-ec2-realtime-"
    r"(?P<stamp>[0-9]{8}T[0-9]{6}Z)-[0-9a-f]{32})\.complete$"
)
RUN_ID_RE = verifier_core.RUN_ID_RE
UUID4_RE = verifier_core.UUID4_RE
SHA256_RE = verifier_core.SHA256_RE
COMMIT_RE = verifier_core.COMMIT_RE
RELEVANT_PACKAGES = verifier_adapter.DEFAULT_RELEVANT_PACKAGES
LAUNCHER_COMPONENT = verifier_adapter.DEFAULT_LAUNCHER_COMPONENT
DEVICE_PROPERTY_KEYS = verifier_adapter.DEFAULT_DEVICE_PROPERTY_KEYS
OPTIONAL_DEVICE_PROPERTY_KEYS = (
    verifier_adapter.DEFAULT_OPTIONAL_DEVICE_PROPERTY_KEYS
)
CI_CANDIDATE_NAMES = frozenset(
    {
        "ANEB-Probe-0.5.13-codex-debug.apk",
        "build-manifest.json",
        "checksums.sha256",
        "provenance.sigstore.json",
        "ANEB-安装说明.txt",
    }
)
PLAN_KEYS = verifier_adapter.PLAN_KEYS
STATUS_KEYS = verifier_adapter.STATUS_KEYS
RUN_RECEIPT_KEYS = verifier_adapter.RUN_RECEIPT_KEYS

CollectionVerificationFailure = verifier_core.CollectionVerificationFailure
fail = verifier_core.fail
_assert_directory = verifier_core.assert_directory
_canonical_json = verifier_core.canonical_json
_load_json = verifier_core.load_json
_sha256 = verifier_core.sha256
_exact = verifier_core.exact
_validate_uuid = verifier_core.validate_uuid
_validate_server_base = verifier_core.validate_server_base


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


def _verify_realtime_serverinfo_body(body: object) -> None:
    try:
        cross_verifier.verify_serverinfo(body)
    except cross_verifier.VerificationFailure:
        fail("serverinfo_invalid")


def _verify_realtime_serverinfo_sequence(
    bodies: list[dict[str, Any]],
) -> None:
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
        manifest_schema="aneb-realtime-quick-evidence-manifest",
        complete_marker="ANEB_REALTIME_QUICK_COMPLETE",
        remote_marker_prefix="aneb-realtime-audit",
        candidate=verifier_adapter.CandidateContract(
            apk_name="ANEB-Probe-0.5.13-codex-debug.apk",
            files=CI_CANDIDATE_NAMES,
            package_name=EXPECTED_PACKAGE,
            version_name=EXPECTED_VERSION_NAME,
            version_code=EXPECTED_VERSION_CODE,
        ),
        phone=verifier_adapter.PhoneStateContract(
            receipt_schema="aneb-realtime-phone-live-state-receipt",
            launcher_component=LAUNCHER_COMPONENT,
            relevant_packages=RELEVANT_PACKAGES,
        ),
        device_identity=verifier_adapter.DeviceIdentityContract(
            identity_schema="aneb-realtime-device-identity",
            property_keys=DEVICE_PROPERTY_KEYS,
            optional_property_keys=OPTIONAL_DEVICE_PROPERTY_KEYS,
        ),
        evidence_root_validator=_validate_evidence_root_security,
        serverinfo_body_validator=_verify_realtime_serverinfo_body,
        serverinfo_sequence_validator=_verify_realtime_serverinfo_sequence,
        negative_required_paths=NEGATIVE_REQUIRED_PATHS,
    )


def phone_state_sha256(raw: dict[str, Any]) -> str:
    return verifier_adapter.phone_state_sha256(
        raw,
        contract=_mechanics_adapter().phone,
    )


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
    mechanics = _mechanics_adapter()

    manifest, manifest_sha = mechanics.verify_manifest(bundle)
    root_security_sha = mechanics.verify_evidence_root_security(bundle)
    plan, _ = _load_json(bundle / "collector-plan.json", "collector_plan_invalid")
    _validate_plan(plan, collection)
    mechanics.verify_mode_inventory(manifest, mode=str(plan["evidence_mode"]))
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
    mechanics.verify_complete(
        bundle,
        collection=collection,
        run_id=run_id,
        manifest_sha256=manifest_sha,
    )
    mechanics.verify_candidate(bundle, plan)
    mechanics.verify_device_identity(bundle, plan)
    preflight_hash = mechanics.verify_phone_pair(bundle, "phone-preflight")
    postflight_hash = mechanics.verify_phone_pair(bundle, "phone-postflight")
    if preflight_hash != postflight_hash:
        fail("phone_baseline_not_restored")
    journal_cursor = mechanics.verify_remote(bundle, plan)
    lock_nonce = mechanics.verify_lock(bundle)
    server_version = mechanics.verify_serverinfo(bundle)
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
