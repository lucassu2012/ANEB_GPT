#!/usr/bin/env python3
"""Immutable category identities for bounded Quick evidence collectors.

The lifecycle engine is shared, but a category's app/server/profile and
evidence schemas must never be inherited implicitly from another category.
Importing this module has no external side effects.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class QuickCollectionContract:
    category: str
    package_name: str
    activity_component: str
    profile_contract: str
    expected_version_name: str
    expected_version_code: int
    expected_server_version: str
    candidate_apk_name: str
    candidate_files: frozenset[str]
    audit_scope: str
    remote_marker_prefix: str
    collection_prefix: str
    plan_schema: str
    status_schema: str
    run_receipt_schema: str
    manifest_schema: str
    device_identity_schema: str
    phone_receipt_schema: str
    complete_marker: str


def network_quick_contract() -> QuickCollectionContract:
    return QuickCollectionContract(
        category="network",
        package_name="com.aneb.probe.codex",
        activity_component=(
            "com.aneb.probe.codex/com.aneb.probe.ui.MainActivity"
        ),
        profile_contract="network_comprehensive_quick@1.2.0",
        expected_version_name="0.5.14-codex",
        expected_version_code=46,
        expected_server_version="aneb-server/0.8.2",
        candidate_apk_name="ANEB-Probe-0.5.14-codex-debug.apk",
        candidate_files=frozenset(
            {
                "ANEB-Probe-0.5.14-codex-debug.apk",
                "build-manifest.json",
                "checksums.sha256",
                "provenance.sigstore.json",
                "ANEB-安装说明.txt",
            }
        ),
        audit_scope="network_run",
        remote_marker_prefix="aneb-network-audit",
        collection_prefix="m0-ec3-network-quick",
        plan_schema="aneb-network-quick-collector-plan",
        status_schema="aneb-network-quick-collector-status",
        run_receipt_schema="aneb-network-quick-run-receipt",
        manifest_schema="aneb-network-quick-evidence-manifest",
        device_identity_schema="aneb-network-device-identity",
        phone_receipt_schema="aneb-network-phone-live-state-receipt",
        complete_marker="ANEB_NETWORK_QUICK_COMPLETE",
    )


def realtime_quick_contract() -> QuickCollectionContract:
    return QuickCollectionContract(
        category="realtime",
        package_name="com.aneb.probe.codex",
        activity_component=(
            "com.aneb.probe.codex/com.aneb.probe.ui.MainActivity"
        ),
        profile_contract="ai_realtime_voice_quick@1.1.1",
        expected_version_name="0.5.13-codex",
        expected_version_code=45,
        expected_server_version="aneb-server/0.8.1",
        candidate_apk_name="ANEB-Probe-0.5.13-codex-debug.apk",
        candidate_files=frozenset(
            {
                "ANEB-Probe-0.5.13-codex-debug.apk",
                "build-manifest.json",
                "checksums.sha256",
                "provenance.sigstore.json",
                "ANEB-安装说明.txt",
            }
        ),
        audit_scope="realtime_run",
        remote_marker_prefix="aneb-realtime-audit",
        collection_prefix="m0-ec2-realtime",
        plan_schema="aneb-realtime-quick-collector-plan",
        status_schema="aneb-realtime-quick-collector-status",
        run_receipt_schema="aneb-realtime-quick-run-receipt",
        manifest_schema="aneb-realtime-quick-evidence-manifest",
        device_identity_schema="aneb-realtime-device-identity",
        phone_receipt_schema="aneb-realtime-phone-live-state-receipt",
        complete_marker="ANEB_REALTIME_QUICK_COMPLETE",
    )


__all__ = (
    "QuickCollectionContract",
    "network_quick_contract",
    "realtime_quick_contract",
)
