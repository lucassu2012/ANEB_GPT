from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify_token_quick_evidence_release.py"
COLLECTION_ID = (
    "d82-token-quick-20260722T010203Z-0123456789abcdef0123456789abcdef"
)
RUN_ID = "019f7a10-1234-7000-8000-000000000001"


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


class ReleaseFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.bundle = root / f"{COLLECTION_ID}.complete"
        self.report = root / f"{COLLECTION_ID}.verification.json"
        self.ready = root / f"{COLLECTION_ID}.READY.json"
        self.bundle.mkdir()
        source_commit = "4" * 40
        known_hosts_sha256 = "5" * 64
        server_binary_sha256 = "6" * 64
        apk_sha256 = "7" * 64
        signer_sha256 = "a" * 64
        device_identity = {
            "schema": "aneb-token-quick-device-identity-verification",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "device_alias": "P40 Pro",
            "device_policy_sha256": "b" * 64,
            "adb_serial_sha256": "c" * 64,
            "android_boot_id": "11111111-2222-4333-8444-555555555555",
            "properties_sha256": "d" * 64,
            "serial_property_confirmed": True,
            "verified_boot_observed_complete": True,
            "verified_boot_secure": True,
            "raw_files_verified": 6,
        }
        self.manifest = {
            "schema": "aneb-d82-final-evidence-manifest",
            "schema_version": "1.1.0",
            "status": "final",
            "acceptance_eligible": True,
            "evidence_scope": "d82_token_quick_cross_bound_acceptance",
            "execution_mode": "positive",
            "finalized_at_utc": "2026-07-22T01:02:03.1234567Z",
            "collection_id": COLLECTION_ID,
            "run_id": RUN_ID,
            "start_barrier_id": "11111111-2222-4333-8444-555555555555",
            "end_barrier_id": "66666666-7777-4888-8999-aaaaaaaaaaaa",
            "profile_contract": "token_multimodal_quick@1.2.1",
            "profile_contract_definition_sha256": "1" * 64,
            "tooling_provenance": {
                "source_commit": source_commit,
                "source_dirty": False,
                "files": {},
                "external_inputs": {
                    "ssh_known_hosts_sha256": known_hosts_sha256,
                    "device_policy_sha256": "b" * 64,
                },
            },
            "device": device_identity,
            "client": {
                "package_name": "com.aneb.probe.codex",
                "version_name": "0.5.12-codex",
                "version_code": 44,
                "signer_sha256": signer_sha256,
                "apk_sha256": apk_sha256,
            },
            "source": {
                "server_base": "https://e01.example:8443",
                "server_version": "aneb-server/0.8.0",
                "server_binary_sha256": server_binary_sha256,
                "boot_id": "22222222-3333-4444-8555-666666666666",
                "systemd_invocation_id": "e" * 32,
                "main_pid": 3388782,
                "journal_cursor": "s=fixture",
                "journal_monotonic_anchor": 100,
                "remote_realtime_anchor_usec": 200,
                "serverinfo_body_sha256": {
                    "identity": "f" * 64,
                    "start_barrier": "0" * 64,
                    "end_barrier": "1" * 64,
                },
                "server_ca_sha256": "2" * 64,
                "server_ca_thumbprint": "3" * 40,
            },
            "draft_inventory_sha256": "2" * 64,
            "client_result_body_sha256": "3" * 64,
            "file_count": 0,
            "total_bytes": 0,
            "files": [],
        }
        self.report_value = {
            "schema": "aneb-d82-bundle-verification-report",
            "schema_version": "1.1.0",
            "status": "pass",
            "reason_code": "ok",
            "execution_mode": "positive",
            "publication": True,
            "collection_id": COLLECTION_ID,
            "run_id": RUN_ID,
            "manifest_sha256": "",
            "source_commit": source_commit,
            "remote_host": "e01.example",
            "ssh_known_hosts_sha256": known_hosts_sha256,
            "server_version": "aneb-server/0.8.0",
            "server_binary_sha256": server_binary_sha256,
            "apk_sha256": apk_sha256,
            "apk_identity_reverified": True,
            "accessibility_raw_reverified": True,
            "raw_state_reverified": True,
            "raw_files_verified": 26,
            "raw_state_files_verified": 26,
            "device_identity_raw_files_verified": 6,
            "raw_files_verified_total": 32,
            "device_identity": dict(device_identity),
            "candidate_provenance_reverified": True,
            "attestation_bundle_sha256": "8" * 64,
            "gh_version": "gh version 2.80.0",
            "gh_executable_sha256": "9" * 64,
            "evidence_time_chain_reverified": True,
            "run_duration_ms": 1000,
            "run_start_delta_ms": 0,
            "remote_receipt_clock_delta_ms": 0,
            "run_timeout_seconds": 420,
            "lock_ttl_seconds": 600,
            "verified_apk_identity": {
                "package_name": "com.aneb.probe.codex",
                "version_name": "0.5.12-codex",
                "version_code": 44,
                "signer_sha256": signer_sha256,
            },
            "android_build_tools_version": "35.0.0",
            "journal_derivation_recomputed": True,
            "request_entry_audit_recomputed": True,
            "client_room_result_recomputed": True,
            "negative_proxy_evidence_recomputed": False,
            "negative_reason_code": None,
            "client_delivery_proven": None,
            "negative_proxy_raw_files_verified": 0,
            "business_counts": {"echo": 20, "token_sim": 3, "download": 1},
            "typed_metrics_verified": 14,
            "envelope_metrics_verified": 26,
            "successful_task_count": 3,
        }
        self.marker = {
            "schema": "aneb-d82-evidence-release",
            "schema_version": "1.0.0",
            "status": "ready",
            "reason_code": "ok",
            "collection_id": COLLECTION_ID,
            "run_id": RUN_ID,
            "execution_mode": "positive",
            "bundle_leaf": self.bundle.name,
            "manifest_sha256": "",
            "verification_report_leaf": self.report.name,
            "verification_report_sha256": "",
            "committed_at_utc": "2026-07-22T01:02:04.1234567Z",
        }
        self.write_bound_files()
        self.write_marker()

    def write_bound_files(self, *, bind_report_manifest: bool = True) -> None:
        self.manifest_raw = (
            json.dumps(self.manifest, separators=(",", ":")).encode("utf-8")
            + b"\n"
        )
        (self.bundle / "evidence-manifest.final.json").write_bytes(
            self.manifest_raw
        )
        manifest_sha = sha256(self.manifest_raw)
        if bind_report_manifest:
            self.report_value["manifest_sha256"] = manifest_sha
        self.report_raw = (
            json.dumps(self.report_value, separators=(",", ":")).encode("utf-8")
            + b"\n"
        )
        self.report.write_bytes(self.report_raw)
        self.marker["manifest_sha256"] = manifest_sha
        self.marker["verification_report_sha256"] = sha256(self.report_raw)
        (self.bundle / "COMPLETE").write_text(
            "ANEB_D82_COMPLETE "
            f"collection_id={self.marker['collection_id']} "
            f"run_id={self.marker['run_id']} "
            "manifest=evidence-manifest.final.json "
            f"manifest_sha256={manifest_sha}\n",
            encoding="ascii",
            newline="",
        )

    def write_raw_manifest(self, raw: bytes) -> None:
        self.manifest_raw = raw
        (self.bundle / "evidence-manifest.final.json").write_bytes(raw)
        manifest_sha = sha256(raw)
        self.report_value["manifest_sha256"] = manifest_sha
        self.report_raw = (
            json.dumps(self.report_value, separators=(",", ":")).encode("utf-8")
            + b"\n"
        )
        self.report.write_bytes(self.report_raw)
        self.marker["manifest_sha256"] = manifest_sha
        self.marker["verification_report_sha256"] = sha256(self.report_raw)
        (self.bundle / "COMPLETE").write_text(
            "ANEB_D82_COMPLETE "
            f"collection_id={self.marker['collection_id']} "
            f"run_id={self.marker['run_id']} "
            "manifest=evidence-manifest.final.json "
            f"manifest_sha256={manifest_sha}\n",
            encoding="ascii",
            newline="",
        )
        self.write_marker()

    def write_raw_report(self, raw: bytes) -> None:
        self.report_raw = raw
        self.report.write_bytes(raw)
        self.marker["verification_report_sha256"] = sha256(raw)
        self.write_marker()

    def configure_negative_mode(self) -> None:
        self.manifest["execution_mode"] = "negative_receipt_missing"
        self.manifest[
            "evidence_scope"
        ] = "d82_token_quick_contract_rejection_acceptance"
        self.report_value["execution_mode"] = "negative_receipt_missing"
        self.report_value["negative_proxy_evidence_recomputed"] = True
        self.report_value["negative_reason_code"] = "receipt_missing"
        self.report_value["client_delivery_proven"] = False
        self.report_value["negative_proxy_raw_files_verified"] = 12
        self.report_value["business_counts"] = {
            "echo": 0,
            "token_sim": 0,
            "download": 0,
        }
        self.report_value["typed_metrics_verified"] = 0
        self.report_value["envelope_metrics_verified"] = 0
        self.report_value["successful_task_count"] = 0
        self.marker["execution_mode"] = "negative_receipt_missing"
        self.write_bound_files()
        self.write_marker()

    def write_marker(self) -> None:
        self.ready.write_text(
            json.dumps(self.marker, separators=(",", ":")) + "\n",
            encoding="utf-8",
            newline="",
        )

    def run(self, ready: Path | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(ready or self.ready)],
            text=True,
            capture_output=True,
            check=False,
            timeout=10,
        )


class TokenQuickEvidenceReleaseVerifierTests(unittest.TestCase):
    def assert_failed(
        self,
        completed: subprocess.CompletedProcess[str],
        reason: str | None = None,
    ) -> dict[str, object]:
        self.assertNotEqual(0, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertEqual(1, len(completed.stderr.splitlines()))
        failure = json.loads(completed.stderr)
        self.assertEqual("fail", failure["status"])
        if reason is not None:
            self.assertEqual(reason, failure["reason_code"])
        return failure

    def test_accepts_digest_bound_ready_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            completed = fixture.run()

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("", completed.stderr)
        self.assertEqual(1, len(completed.stdout.splitlines()))
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(COLLECTION_ID, report["collection_id"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertEqual("positive", report["execution_mode"])
        self.assertEqual(sha256(fixture.manifest_raw), report["manifest_sha256"])
        self.assertEqual(
            sha256(fixture.report_raw), report["verification_report_sha256"]
        )

    def test_accepts_complete_negative_receipt_missing_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            fixture.configure_negative_mode()

            completed = fixture.run()

        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("negative_receipt_missing", report["execution_mode"])

    def test_rejects_cross_mode_ready_manifest_and_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            fixture.marker["execution_mode"] = "negative_receipt_missing"
            fixture.write_marker()

            completed = fixture.run()

        self.assert_failed(completed, "release_manifest_binding_mismatch")

    def test_rejects_positive_report_count_semantics_drift(self) -> None:
        mutations = {
            "echo": lambda report: report["business_counts"].update(
                {"echo": 19}
            ),
            "token_sim": lambda report: report["business_counts"].update(
                {"token_sim": 2}
            ),
            "download": lambda report: report["business_counts"].update(
                {"download": 0}
            ),
            "business_extra": lambda report: report[
                "business_counts"
            ].update({"extra": 0}),
            "typed": lambda report: report.update(
                {"typed_metrics_verified": 13}
            ),
            "typed_bool": lambda report: report.update(
                {"typed_metrics_verified": True}
            ),
            "envelope": lambda report: report.update(
                {"envelope_metrics_verified": 25}
            ),
            "tasks": lambda report: report.update(
                {"successful_task_count": 2}
            ),
            "negative_recomputed": lambda report: report.update(
                {"negative_proxy_evidence_recomputed": True}
            ),
            "negative_reason": lambda report: report.update(
                {"negative_reason_code": "receipt_missing"}
            ),
            "delivery": lambda report: report.update(
                {"client_delivery_proven": False}
            ),
            "negative_raw": lambda report: report.update(
                {"negative_proxy_raw_files_verified": 12}
            ),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                mutate(fixture.report_value)
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed,
                    "release_verification_report_contract_invalid",
                )

    def test_rejects_negative_report_count_semantics_drift(self) -> None:
        mutations = {
            "echo": lambda report: report["business_counts"].update(
                {"echo": 1}
            ),
            "token_sim": lambda report: report["business_counts"].update(
                {"token_sim": 1}
            ),
            "download": lambda report: report["business_counts"].update(
                {"download": 1}
            ),
            "business_extra": lambda report: report[
                "business_counts"
            ].update({"extra": 0}),
            "typed": lambda report: report.update(
                {"typed_metrics_verified": 1}
            ),
            "typed_bool": lambda report: report.update(
                {"typed_metrics_verified": False}
            ),
            "envelope": lambda report: report.update(
                {"envelope_metrics_verified": 1}
            ),
            "tasks": lambda report: report.update(
                {"successful_task_count": 1}
            ),
            "negative_recomputed": lambda report: report.update(
                {"negative_proxy_evidence_recomputed": False}
            ),
            "negative_reason": lambda report: report.update(
                {"negative_reason_code": None}
            ),
            "delivery": lambda report: report.update(
                {"client_delivery_proven": True}
            ),
            "negative_raw": lambda report: report.update(
                {"negative_proxy_raw_files_verified": 0}
            ),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.configure_negative_mode()
                mutate(fixture.report_value)
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed,
                    "release_verification_report_contract_invalid",
                )

    def test_rejects_noncanonical_manifest_json(self) -> None:
        variants = {
            "unknown_key": "release_manifest_contract_invalid",
            "missing_key": "release_manifest_contract_invalid",
            "duplicate_key": "release_manifest_json_invalid",
            "nan": "release_manifest_json_invalid",
            "bom": "release_manifest_utf8_invalid",
            "invalid_utf8": "release_manifest_utf8_invalid",
        }
        for variant, expected in variants.items():
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                if variant == "unknown_key":
                    fixture.manifest["unexpected"] = "forbidden"
                    fixture.write_bound_files()
                    fixture.write_marker()
                elif variant == "missing_key":
                    fixture.manifest.pop("source")
                    fixture.write_bound_files()
                    fixture.write_marker()
                elif variant == "nan":
                    fixture.manifest["total_bytes"] = float("nan")
                    fixture.write_bound_files()
                    fixture.write_marker()
                elif variant == "duplicate_key":
                    fixture.write_raw_manifest(
                        fixture.manifest_raw[:-2]
                        + b',"schema":"duplicate"}\n'
                    )
                elif variant == "bom":
                    fixture.write_raw_manifest(
                        b"\xef\xbb\xbf" + fixture.manifest_raw
                    )
                else:
                    fixture.write_raw_manifest(b"\xff" + fixture.manifest_raw)

                completed = fixture.run()

                self.assert_failed(completed, expected)

    def test_rejects_noncanonical_verification_report_json(self) -> None:
        variants = {
            "unknown_key": "release_verification_report_contract_invalid",
            "missing_key": "release_verification_report_contract_invalid",
            "duplicate_key": "release_verification_report_json_invalid",
            "nan": "release_verification_report_json_invalid",
            "bom": "release_verification_report_utf8_invalid",
            "invalid_utf8": "release_verification_report_utf8_invalid",
        }
        for variant, expected in variants.items():
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                if variant == "unknown_key":
                    fixture.report_value["unexpected"] = "forbidden"
                    fixture.write_bound_files()
                    fixture.write_marker()
                elif variant == "missing_key":
                    fixture.report_value.pop("remote_host")
                    fixture.write_bound_files()
                    fixture.write_marker()
                elif variant == "nan":
                    fixture.report_value["run_duration_ms"] = float("nan")
                    fixture.write_bound_files()
                    fixture.write_marker()
                elif variant == "duplicate_key":
                    fixture.write_raw_report(
                        fixture.report_raw[:-2]
                        + b',"schema":"duplicate"}\n'
                    )
                elif variant == "bom":
                    fixture.write_raw_report(
                        b"\xef\xbb\xbf" + fixture.report_raw
                    )
                else:
                    fixture.write_raw_report(b"\xff" + fixture.report_raw)

                completed = fixture.run()

                self.assert_failed(completed, expected)

    def test_rejects_nonfinal_or_ineligible_manifest(self) -> None:
        variants = {
            "schema": ("schema", "other"),
            "version": ("schema_version", "1.2.0"),
            "status": ("status", "draft"),
            "eligible": ("acceptance_eligible", False),
            "scope": ("evidence_scope", "other"),
            "profile": ("profile_contract", "token_multimodal_quick@1.2.0"),
        }
        for name, (key, value) in variants.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.manifest[key] = value
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed, "release_manifest_contract_invalid"
                )

    def test_rejects_cross_bound_manifest_identity(self) -> None:
        mutations = {
            "collection": (
                "collection_id",
                "d82-token-quick-20260722T010203Z-" + "f" * 32,
            ),
            "run": ("run_id", "019f7a10-1234-7000-8000-000000000002"),
        }
        for name, (key, value) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.manifest[key] = value
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed, "release_manifest_binding_mismatch"
                )

    def test_rejects_cross_bound_verification_report_identity(self) -> None:
        variants = ("collection", "run", "mode", "manifest_sha256")
        for variant in variants:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                bind_manifest = True
                if variant == "collection":
                    fixture.report_value["collection_id"] = (
                        "d82-token-quick-20260722T010203Z-" + "f" * 32
                    )
                elif variant == "run":
                    fixture.report_value[
                        "run_id"
                    ] = "019f7a10-1234-7000-8000-000000000002"
                elif variant == "mode":
                    fixture.configure_negative_mode()
                    fixture.report_value["execution_mode"] = "positive"
                else:
                    fixture.report_value["manifest_sha256"] = "0" * 64
                    bind_manifest = False
                fixture.write_bound_files(
                    bind_report_manifest=bind_manifest
                )
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed,
                    "release_verification_report_binding_mismatch",
                )

    def test_rejects_source_or_apk_identity_drift(self) -> None:
        mutations = {
            "manifest_server_version": lambda fixture: fixture.manifest[
                "source"
            ].update({"server_version": "aneb-server/0.8.1"}),
            "manifest_server_binary": lambda fixture: fixture.manifest[
                "source"
            ].update({"server_binary_sha256": "0" * 64}),
            "report_server_version": lambda fixture: fixture.report_value.update(
                {"server_version": "aneb-server/0.8.1"}
            ),
            "report_server_binary": lambda fixture: fixture.report_value.update(
                {"server_binary_sha256": "0" * 64}
            ),
            "manifest_apk_sha": lambda fixture: fixture.manifest[
                "client"
            ].update({"apk_sha256": "0" * 64}),
            "report_apk_sha": lambda fixture: fixture.report_value.update(
                {"apk_sha256": "0" * 64}
            ),
            "verified_apk_package": lambda fixture: fixture.report_value[
                "verified_apk_identity"
            ].update({"package_name": "com.aneb.probe"}),
            "verified_apk_version": lambda fixture: fixture.report_value[
                "verified_apk_identity"
            ].update({"version_name": "0.5.13-codex"}),
            "verified_apk_code": lambda fixture: fixture.report_value[
                "verified_apk_identity"
            ].update({"version_code": 45}),
            "verified_apk_signer": lambda fixture: fixture.report_value[
                "verified_apk_identity"
            ].update({"signer_sha256": "0" * 64}),
            "source_commit": lambda fixture: fixture.report_value.update(
                {"source_commit": "0" * 40}
            ),
            "known_hosts": lambda fixture: fixture.report_value.update(
                {"ssh_known_hosts_sha256": "0" * 64}
            ),
            "device_identity": lambda fixture: fixture.report_value[
                "device_identity"
            ].update({"properties_sha256": "0" * 64}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                mutate(fixture)
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed, "release_identity_binding_mismatch"
                )

    def test_rejects_malformed_manifest_or_report_identity_contract(self) -> None:
        mutations = {
            "profile_definition_sha": lambda fixture: fixture.manifest.update(
                {"profile_contract_definition_sha256": "invalid"}
            ),
            "source_commit": lambda fixture: fixture.manifest[
                "tooling_provenance"
            ].update({"source_commit": "invalid"}),
            "known_hosts": lambda fixture: fixture.manifest[
                "tooling_provenance"
            ]["external_inputs"].update(
                {"ssh_known_hosts_sha256": "invalid"}
            ),
            "client_signer": lambda fixture: fixture.manifest["client"].update(
                {"signer_sha256": "invalid"}
            ),
            "client_version_code_bool": lambda fixture: fixture.manifest[
                "client"
            ].update({"version_code": True}),
            "verified_apk_key_missing": lambda fixture: fixture.report_value[
                "verified_apk_identity"
            ].pop("signer_sha256"),
            "device_identity_key_missing": lambda fixture: fixture.report_value[
                "device_identity"
            ].pop("properties_sha256"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                mutate(fixture)
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed, "release_identity_contract_invalid"
                )

    def test_remote_host_is_not_cross_bound_to_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            fixture.report_value["remote_host"] = "published-host.example"
            fixture.write_bound_files()
            fixture.write_marker()

            completed = fixture.run()

        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_rejects_false_reverified_or_recomputed_flag(self) -> None:
        flags = (
            "apk_identity_reverified",
            "accessibility_raw_reverified",
            "raw_state_reverified",
            "candidate_provenance_reverified",
            "evidence_time_chain_reverified",
            "journal_derivation_recomputed",
            "request_entry_audit_recomputed",
            "client_room_result_recomputed",
        )
        for flag in flags:
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.report_value[flag] = False
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed,
                    "release_verification_report_contract_invalid",
                )

    def test_rejects_failed_or_nonpublication_verification_report(self) -> None:
        variants = {
            "schema": ("schema", "other"),
            "version": ("schema_version", "1.2.0"),
            "failed": ("status", "fail"),
            "reason": ("reason_code", "bundle_invalid"),
            "nonpublication": ("publication", False),
        }
        for name, (key, value) in variants.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.report_value[key] = value
                fixture.write_bound_files()
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(
                    completed,
                    "release_verification_report_contract_invalid",
                )

    def test_rejects_duplicate_ready_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            original = fixture.ready.read_text(encoding="utf-8")
            fixture.ready.write_text(
                '{"schema":"aneb-d82-evidence-release",' + original[1:],
                encoding="utf-8",
                newline="",
            )

            completed = fixture.run()

        failure = self.assert_failed(completed)
        self.assertEqual("release_ready_json_invalid", failure["reason_code"])

    def test_rejects_bundle_directory_reparse_point(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            real_bundle = fixture.root / "real-bundle"
            fixture.bundle.rename(real_bundle)
            try:
                fixture.bundle.symlink_to(real_bundle, target_is_directory=True)
            except OSError:
                if os.name != "nt":
                    self.skipTest("directory symlinks unavailable")
                junction = subprocess.run(
                    [
                        "cmd.exe",
                        "/d",
                        "/c",
                        "mklink",
                        "/J",
                        str(fixture.bundle),
                        str(real_bundle),
                    ],
                    text=True,
                    capture_output=True,
                    check=False,
                )
                if junction.returncode != 0:
                    self.skipTest("directory junctions unavailable")

            completed = fixture.run()
            if fixture.bundle.exists() or fixture.bundle.is_symlink():
                if fixture.bundle.is_symlink():
                    fixture.bundle.unlink()
                elif os.name == "nt":
                    os.rmdir(fixture.bundle)
                else:
                    shutil.rmtree(fixture.bundle)

        self.assert_failed(completed, "release_path_reparse_forbidden")

    def test_rejects_ready_and_bound_file_symlinks(self) -> None:
        for target_name in ("ready", "manifest", "report", "complete"):
            with self.subTest(target=target_name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                targets = {
                    "ready": fixture.ready,
                    "manifest": fixture.bundle / "evidence-manifest.final.json",
                    "report": fixture.report,
                    "complete": fixture.bundle / "COMPLETE",
                }
                link = targets[target_name]
                real = link.with_name(link.name + ".real")
                link.rename(real)
                try:
                    link.symlink_to(real)
                except OSError:
                    self.skipTest("file symlinks unavailable")

                completed = fixture.run()
                link.unlink(missing_ok=True)

                self.assert_failed(completed, "release_path_reparse_forbidden")

    def test_missing_ready_never_promotes_complete_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            fixture.ready.unlink()

            completed = fixture.run()

        self.assert_failed(completed, "release_ready_invalid")

    def test_rejects_unknown_key_or_nonready_contract_state(self) -> None:
        mutations = {
            "unknown_key": lambda marker: marker.update({"extra": "forbidden"}),
            "schema": lambda marker: marker.update({"schema": "other"}),
            "version": lambda marker: marker.update({"schema_version": "1.1.0"}),
            "status": lambda marker: marker.update({"status": "pass"}),
            "reason": lambda marker: marker.update({"reason_code": "pending"}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                mutate(fixture.marker)
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(completed)

    def test_rejects_malformed_or_cross_bound_identity(self) -> None:
        mutations = {
            "collection": lambda marker: marker.update(
                {
                    "collection_id": (
                        "d82-token-quick-20260722T010203Z-" + "f" * 32
                    )
                }
            ),
            "collection_date": lambda marker: marker.update(
                {
                    "collection_id": (
                        "d82-token-quick-20261340T256199Z-"
                        "0123456789abcdef0123456789abcdef"
                    )
                }
            ),
            "run": lambda marker: marker.update(
                {"run_id": "019f7a10-1234-4000-8000-000000000001"}
            ),
            "mode": lambda marker: marker.update({"execution_mode": "negative"}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                mutate(fixture.marker)
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(completed, "release_ready_identity_invalid")

    def test_rejects_impossible_timestamp_in_self_consistent_collection_id(self) -> None:
        invalid_collection = (
            "d82-token-quick-20261340T256199Z-"
            "0123456789abcdef0123456789abcdef"
        )
        with tempfile.TemporaryDirectory() as temporary:
            fixture = ReleaseFixture(Path(temporary))
            invalid_bundle = fixture.root / f"{invalid_collection}.complete"
            invalid_report = (
                fixture.root / f"{invalid_collection}.verification.json"
            )
            invalid_ready = fixture.root / f"{invalid_collection}.READY.json"
            fixture.bundle.rename(invalid_bundle)
            fixture.report.rename(invalid_report)
            fixture.marker.update(
                {
                    "collection_id": invalid_collection,
                    "bundle_leaf": invalid_bundle.name,
                    "verification_report_leaf": invalid_report.name,
                }
            )
            complete = invalid_bundle / "COMPLETE"
            complete.write_text(
                complete.read_text(encoding="ascii").replace(
                    COLLECTION_ID, invalid_collection
                ),
                encoding="ascii",
                newline="",
            )
            fixture.ready = invalid_ready
            fixture.write_marker()

            completed = fixture.run(invalid_ready)

        self.assert_failed(completed, "release_ready_identity_invalid")

    def test_rejects_path_escape_or_nonexact_sibling_leaf(self) -> None:
        mutations = {
            "bundle_escape": ("bundle_leaf", "../outside.complete"),
            "bundle_other": ("bundle_leaf", "other.complete"),
            "report_escape": ("verification_report_leaf", "../outside.json"),
            "report_other": ("verification_report_leaf", "other.json"),
        }
        for name, (key, value) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.marker[key] = value
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(completed, "release_ready_binding_invalid")

    def test_rejects_manifest_or_report_changed_after_ready_commit(self) -> None:
        for target in ("manifest", "report"):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                if target == "manifest":
                    (fixture.bundle / "evidence-manifest.final.json").write_bytes(
                        fixture.manifest_raw + b" "
                    )
                    expected = "release_manifest_digest_mismatch"
                else:
                    fixture.report.write_bytes(fixture.report_raw + b" ")
                    expected = "release_verification_report_digest_mismatch"

                completed = fixture.run()

                self.assert_failed(completed, expected)

    def test_rejects_missing_or_cross_bound_complete_marker(self) -> None:
        mutations = {
            "missing": lambda path: path.unlink(),
            "collection": lambda path: path.write_text(
                path.read_text(encoding="ascii").replace(COLLECTION_ID, "x"),
                encoding="ascii",
                newline="",
            ),
            "run": lambda path: path.write_text(
                path.read_text(encoding="ascii").replace(RUN_ID, "y" * 36),
                encoding="ascii",
                newline="",
            ),
            "manifest": lambda path: path.write_text(
                re.sub(
                    r"manifest_sha256=[0-9a-f]{64}",
                    "manifest_sha256=" + "0" * 64,
                    path.read_text(encoding="ascii"),
                ),
                encoding="ascii",
                newline="",
            ),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                mutate(fixture.bundle / "COMPLETE")

                completed = fixture.run()

                self.assert_failed(completed)

    def test_rejects_invalid_utf8_bom_and_oversized_ready(self) -> None:
        variants = {
            "invalid_utf8": b"\xff\xfe",
            "bom": b"\xef\xbb\xbf{}\n",
            "oversized": b" " * (64 * 1024 + 1),
        }
        for name, raw in variants.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.ready.write_bytes(raw)

                completed = fixture.run()

                self.assert_failed(completed)

    def test_rejects_invalid_commit_timestamp(self) -> None:
        for value in (
            "2026-07-22T01:02:04Z",
            "2026-02-30T01:02:04.1234567Z",
        ):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as temporary:
                fixture = ReleaseFixture(Path(temporary))
                fixture.marker["committed_at_utc"] = value
                fixture.write_marker()

                completed = fixture.run()

                self.assert_failed(completed, "release_ready_timestamp_invalid")


if __name__ == "__main__":
    unittest.main()
