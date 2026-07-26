from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import verify_realtime_quick_collection as verifier


COLLECTION = "m0-ec2-realtime-20260725T120000Z-" + "a" * 32
RUN_ID = "01981f4b-1234-7abc-8def-0123456789ab"
SOURCE_COMMIT = "b" * 40
SERVER_BINARY = "c" * 64
APK_BYTES = b"synthetic-apk-for-release-verifier"
APK_SHA = hashlib.sha256(APK_BYTES).hexdigest()
SIGNER_SHA = "d" * 64
SERIAL = "SERIAL-REDACTED-IN-TEST"
SERIAL_SHA = hashlib.sha256(SERIAL.encode("utf-8")).hexdigest()
START_BARRIER = "12345678-1234-4234-8234-123456789abc"
END_BARRIER = "87654321-4321-4321-8321-cba987654321"


def canonical(value: object) -> bytes:
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


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value))


def serverinfo(*, timestamp: int) -> dict[str, object]:
    return {
        "version": "aneb-server/0.8.1",
        "srv_ts_us": timestamp,
        "anchor_wall_unix_ns": 1_721_370_000_000_000_000,
        "uptime_s": timestamp - 1_000,
        "goos": "linux",
        "goarch": "amd64",
        "h3_enabled": True,
        "tcp_slow_start_after_idle": "0",
        "congestion_control": "cubic",
        "execution_capabilities": {
            "contract_id": "aneb-server-capability-receipt",
            "contract_version": "1.0.0",
            "primitives": [
                {
                    "primitive_id": "download",
                    "wire_contract_id": "aneb-download-v1",
                },
                {
                    "primitive_id": "echo",
                    "wire_contract_id": "aneb-echo-v1",
                },
                {
                    "primitive_id": "realtime_sim",
                    "wire_contract_id": "aneb-realtime-session-v1",
                },
                {
                    "primitive_id": "token_sim",
                    "wire_contract_id": "aneb-token-task-v1",
                },
            ],
            "validated_profiles": [
                {
                    "profile_id": "ai_realtime_voice_quick",
                    "profile_version": "1.1.1",
                    "profile_sha256": (
                        "sha256:"
                        "701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7"
                    ),
                },
                {
                    "profile_id": "token_multimodal_quick",
                    "profile_version": "1.2.1",
                    "profile_sha256": (
                        "sha256:"
                        "caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773"
                    ),
                },
            ],
        },
    }


def phone_raw() -> dict[str, object]:
    packages = verifier.RELEVANT_PACKAGES
    return {
        "device_state": "device",
        "current_user": "0",
        "activity": (
            "mFocusedApp=ActivityRecord{1 u0 "
            "com.huawei.android.launcher/.unihome.UniHomeLauncher t1}\n"
            "topResumedActivity=ActivityRecord{2 u0 "
            "com.huawei.android.launcher/.unihome.UniHomeLauncher t1}"
        ),
        "processes": {package: "" for package in packages},
        "services": {package: "(nothing)" for package in packages},
        "enabled_accessibility": "",
        "accessibility_dump": "Enabled services:\n0 services",
        "interfaces": "lo\nwlan0",
        "connectivity": (
            "NetworkAgentInfo{network{101} state: CONNECTED/CONNECTED "
            "Transports: WIFI Capabilities: VALIDATED}"
        ),
        "vpn": "VPNs:\n  User 0: none",
        "stayon": "7",
        "wifi_on": "1",
    }


def remote_snapshot(*, cursor: str) -> dict[str, str]:
    return {
        "boot_id": "1" * 32,
        "systemd_invocation_id": "1" * 32,
        "main_pid": "3388782",
        "server_binary_sha256": SERVER_BINARY,
        "eth0_qdisc_sha256": "1" * 64,
        "firewall_full_sha256": "2" * 64,
        "firewall_v4_sha256": "3" * 64,
        "firewall_v6_sha256": "4" * 64,
        "firewall_nft_sha256": "5" * 64,
        "docker_sha256": "6" * 64,
        "journal_cursor": cursor,
    }


def candidate_report() -> dict[str, object]:
    files = {
        "provenance.sigstore.json": b"sigstore",
        "build-manifest.json": b"manifest",
        "checksums.sha256": b"checksums",
    }
    return {
        "schema": "aneb-ci-apk-provenance-report",
        "schema_version": "1.0.0",
        "status": "pass",
        "reason_code": "ok",
        "candidate_provenance_reverified": True,
        "repository": "lucassu2012/ANEB_GPT",
        "signer_workflow": "lucassu2012/ANEB_GPT/.github/workflows/ci.yml",
        "predicate_type": "https://slsa.dev/provenance/v1",
        "source_commit": SOURCE_COMMIT,
        "source_ref": "refs/heads/codex/m0-ai-realtime-contract",
        "workflow_run_id": 123456789,
        "workflow_run_url": (
            "https://github.com/lucassu2012/ANEB_GPT/actions/runs/123456789"
        ),
        "apk": {
            "file_name": "ANEB-Probe-0.5.13-codex-debug.apk",
            "sha256": APK_SHA,
            "size_bytes": len(APK_BYTES),
            "package_name": "com.aneb.probe.codex",
            "version_name": "0.5.13-codex",
            "version_code": 45,
            "signer_sha256": SIGNER_SHA,
        },
        "files": {
            "attestation_bundle_sha256": hashlib.sha256(
                files["provenance.sigstore.json"]
            ).hexdigest(),
            "build_manifest_sha256": hashlib.sha256(
                files["build-manifest.json"]
            ).hexdigest(),
            "checksums_sha256": hashlib.sha256(
                files["checksums.sha256"]
            ).hexdigest(),
        },
        "gh": {
            "version": "2.96.0",
            "executable_sha256": "7" * 64,
            "certificate_issuer": "https://token.actions.githubusercontent.com",
            "oidc_issuer": "https://token.actions.githubusercontent.com",
            "runner_environment": "github-hosted",
            "run_invocation_uri": (
                "https://github.com/lucassu2012/ANEB_GPT/actions/runs/123456789"
            ),
            "subject_alternative_name": (
                "https://github.com/lucassu2012/ANEB_GPT/"
                ".github/workflows/ci.yml@refs/heads/codex/m0-ai-realtime-contract"
            ),
            "verified_timestamp_count": 1,
        },
    }


class CollectionFixture:
    def __init__(self, root: Path) -> None:
        self.bundle = root / f"{COLLECTION}.complete"
        self.bundle.mkdir()
        self.cross_report = {
            "schema": "aneb-realtime-quick-cross-bound-evidence-report",
            "schema_version": "0.1.0",
            "status": "pass",
            "reason_code": "ok",
            "run_id": RUN_ID,
            "mode": "positive",
            "cross_bound": True,
            "server_version": "aneb-server/0.8.1",
        }
        self._write()

    def _write(self) -> None:
        plan = {
            "schema": "aneb-realtime-quick-collector-plan",
            "schema_version": "1.0.0",
            "collection_id": COLLECTION,
            "profile_contract": "ai_realtime_voice_quick@1.1.1",
            "evidence_mode": "positive",
            "transport": "wifi",
            "package_name": "com.aneb.probe.codex",
            "version_name": "0.5.13-codex",
            "version_code": 45,
            "server_base": "https://203.0.113.10:8443",
            "client_server_base": "https://203.0.113.10:8443",
            "negative_upstream_url": None,
            "remote_host": "203.0.113.10",
            "expected_server_version": "aneb-server/0.8.1",
            "expected_server_binary_sha256": SERVER_BINARY,
            "expected_apk_sha256": APK_SHA,
            "expected_signer_sha256": SIGNER_SHA,
            "source_commit": SOURCE_COMMIT,
            "workflow_run_id": 123456789,
            "server_ca_sha256": "8" * 64,
            "device_policy_sha256": "",
            "adb_serial_sha256": SERIAL_SHA,
            "start_barrier_id": START_BARRIER,
            "end_barrier_id": END_BARRIER,
            "run_timeout_seconds": 900,
            "lock_ttl_seconds": 1800,
            "install_candidate": True,
        }
        policy_properties = {
            "ro.serialno": SERIAL,
            "ro.boot.serialno": SERIAL,
            "ro.product.manufacturer": "HUAWEI",
            "ro.product.model": "ANA-AN00",
            "ro.product.device": "HWANA",
            "ro.product.name": "ANA-AN00",
            "ro.build.fingerprint": "HUAWEI/ANA-AN00/test:user/release-keys",
            "ro.build.version.security_patch": "2024-01-01",
            "ro.boot.verifiedbootstate": "green",
            "ro.boot.vbmeta.device_state": "locked",
            "ro.boot.flash.locked": "1",
            "ro.boot.veritymode": "enforcing",
        }
        policy = {
            "schema": "aneb-device-identity-policy",
            "schema_version": "1.0.0",
            "device_alias": "P40 Pro",
            "adb_serial_sha256": SERIAL_SHA,
            "properties": policy_properties,
        }
        current_sid = "S-1-5-21-1000"
        write_json(
            self.bundle / "evidence-root-security.json",
            verifier.evidence_security.evaluate_observation(
                self.bundle.parent,
                {
                    "platform": "windows",
                    "current_identity": current_sid,
                    "owner_identity": current_sid,
                    "allow_rules": [
                        {
                            "identity": current_sid,
                            "rights": verifier.evidence_security.WRITE_DATA,
                        }
                    ],
                },
            ),
        )
        write_json(self.bundle / "device-policy.json", policy)
        plan["device_policy_sha256"] = sha256(self.bundle / "device-policy.json")
        write_json(self.bundle / "collector-plan.json", plan)
        write_json(
            self.bundle / "collector-status.json",
            {
                "schema": "aneb-realtime-quick-collector-status",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "collection_id": COLLECTION,
                "run_id": RUN_ID,
                "mode": "positive",
                "cleanup_phone": "pass",
                "cleanup_remote": "pass",
            },
        )
        write_json(
            self.bundle / "run-receipt.json",
            {
                "schema": "aneb-realtime-quick-run-receipt",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "collection_id": COLLECTION,
                "run_id": RUN_ID,
                "mode": "positive",
                "terminal_status": "completed",
                "contract_status": "authorized",
                "cross_bound_report_sha256": "",
                "cross_bound": True,
            },
        )
        write_json(self.bundle / "cross-bound-report.json", self.cross_report)
        receipt = json.loads(
            (self.bundle / "run-receipt.json").read_text(encoding="utf-8")
        )
        receipt["cross_bound_report_sha256"] = sha256(
            self.bundle / "cross-bound-report.json"
        )
        write_json(self.bundle / "run-receipt.json", receipt)

        report = candidate_report()
        for leaf in (
            "ci-source-before.json",
            "ci-candidate-verification.json",
            "ci-source-after.json",
        ):
            write_json(self.bundle / leaf, report)
        candidate = self.bundle / "ci-candidate"
        candidate.mkdir()
        (candidate / "ANEB-Probe-0.5.13-codex-debug.apk").write_bytes(APK_BYTES)
        (candidate / "build-manifest.json").write_bytes(b"manifest")
        (candidate / "checksums.sha256").write_bytes(b"checksums")
        (candidate / "provenance.sigstore.json").write_bytes(b"sigstore")
        (candidate / "ANEB-瀹夎璇存槑.txt").write_bytes(b"instructions")
        (self.bundle / "installed-base.apk").write_bytes(APK_BYTES)
        (self.bundle / "installed-package.txt").write_bytes(
            b"versionCode=45 minSdk=31\nversionName=0.5.13-codex\n",
        )
        (self.bundle / "adb-install.txt").write_bytes(b"Success\n")

        write_json(
            self.bundle / "device-identity.json",
            {
                "schema": "aneb-realtime-device-identity",
                "schema_version": "1.0.0",
                "status": "pass",
                "adb_serial_sha256": SERIAL_SHA,
                "android_boot_id": "22222222-3333-4444-8555-666666666666",
                "properties": policy["properties"],
            },
        )
        raw = phone_raw()
        phone_hash = verifier.phone_state_sha256(raw)
        for prefix in ("phone-preflight", "phone-postflight"):
            write_json(self.bundle / f"{prefix}-t0-raw.json", raw)
            write_json(self.bundle / f"{prefix}-t2-raw.json", raw)
            write_json(
                self.bundle / f"{prefix}-receipt.json",
                {
                    "schema": "aneb-realtime-phone-live-state-receipt",
                    "schema_version": "1.0.0",
                    "status": "pass",
                    "reason_code": "ok",
                    "stable": True,
                    "t0_sha256": phone_hash,
                    "t2_sha256": phone_hash,
                    "focused_component": (
                        "com.huawei.android.launcher/"
                        "com.huawei.android.launcher.unihome.UniHomeLauncher"
                    ),
                    "stayon": "7",
                    "wifi_on": "1",
                },
            )

        write_json(
            self.bundle / "remote-pre.json",
            remote_snapshot(cursor="s=0123456789abcdef"),
        )
        write_json(
            self.bundle / "remote-post-window.json",
            remote_snapshot(cursor="s=1123456789abcdef"),
        )
        write_json(
            self.bundle / "remote-final.json",
            remote_snapshot(cursor="s=2123456789abcdef"),
        )
        nonce = "e" * 32
        (self.bundle / "lock-acquired.txt").write_bytes(
            (
                f"LOCK_ACQUIRED nonce={nonce} pid=1234 "
                f"marker=/run/aneb-realtime-audit-{nonce}.lock\n"
            ).encode("utf-8"),
        )
        (self.bundle / "lock-released.txt").write_bytes(
            f"LOCK_RELEASED nonce={nonce}\n".encode("utf-8"),
        )

        for name, timestamp in (
            ("identity-serverinfo", 2_000),
            ("start-barrier", 2_100),
            ("end-barrier", 2_200),
        ):
            write_json(self.bundle / f"{name}.json", serverinfo(timestamp=timestamp))
            write_json(
                self.bundle / f"{name}.headers.json",
                {
                    "status": 200,
                    "headers": [["Content-Type", "application/json"]],
                },
            )

        self.rebuild_manifest()

    def rebuild_manifest(self) -> None:
        for leaf in ("evidence-manifest.json", "COMPLETE"):
            path = self.bundle / leaf
            if path.exists():
                path.unlink()
        records = []
        for path in sorted(self.bundle.rglob("*")):
            if path.is_file():
                records.append(
                    {
                        "path": path.relative_to(self.bundle).as_posix(),
                        "bytes": path.stat().st_size,
                        "sha256": sha256(path),
                    }
                )
        write_json(
            self.bundle / "evidence-manifest.json",
            {
                "schema": "aneb-realtime-quick-evidence-manifest",
                "schema_version": "1.0.0",
                "files": records,
            },
        )
        manifest_sha = sha256(self.bundle / "evidence-manifest.json")
        (self.bundle / "COMPLETE").write_bytes(
            (
                f"ANEB_REALTIME_QUICK_COMPLETE collection_id={COLLECTION} "
                f"run_id={RUN_ID} manifest_sha256={manifest_sha}\n"
            ).encode("utf-8"),
        )


class RealtimeQuickCollectionVerifierTests(unittest.TestCase):
    def verify(self, fixture: CollectionFixture) -> dict[str, object]:
        with mock.patch.object(
            verifier,
            "revalidate_cross_evidence",
            return_value=copy.deepcopy(fixture.cross_report),
        ) as revalidate:
            report = verifier.verify_collection(fixture.bundle)
        revalidate.assert_called_once()
        return report

    def test_accepts_complete_cross_bound_collection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            report = self.verify(fixture)

        self.assertEqual("pass", report["status"])
        self.assertEqual(COLLECTION, report["collection_id"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertTrue(report["manifest_reverified"])
        self.assertTrue(report["cross_evidence_recomputed"])
        self.assertTrue(report["phone_state_reverified"])
        self.assertTrue(report["remote_state_reverified"])
        self.assertTrue(report["candidate_provenance_reverified"])

    def test_rejects_missing_evidence_root_security_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            (fixture.bundle / "evidence-root-security.json").unlink()
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "evidence_root_security_invalid",
            ):
                self.verify(fixture)

    def test_partial_bundle_requires_explicit_prepublish_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            partial = fixture.bundle.with_name(f"{COLLECTION}.partial")
            fixture.bundle.rename(partial)
            fixture.bundle = partial
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "collection_leaf_invalid",
            ):
                verifier.verify_collection(partial)
            with mock.patch.object(
                verifier,
                "revalidate_cross_evidence",
                return_value=copy.deepcopy(fixture.cross_report),
            ):
                report = verifier.verify_collection(
                    partial,
                    expected_collection=COLLECTION,
                    allow_partial=True,
                )

        self.assertEqual("pass", report["status"])

    def test_accepts_raw_noncanonical_device_policy_bound_by_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            policy_path = fixture.bundle / "device-policy.json"
            policy = json.loads(policy_path.read_text(encoding="utf-8"))
            policy_path.write_bytes(
                (json.dumps(policy, ensure_ascii=False, indent=2) + "\n").encode(
                    "utf-8"
                )
            )
            plan_path = fixture.bundle / "collector-plan.json"
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            plan["device_policy_sha256"] = sha256(policy_path)
            write_json(plan_path, plan)
            fixture.rebuild_manifest()

            report = self.verify(fixture)

        self.assertEqual("pass", report["status"])

    def test_positive_collection_rejects_negative_only_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            negative_directory = fixture.bundle / "negative-proxy"
            negative_directory.mkdir()
            (negative_directory / "proxy-receipt.json").write_bytes(b"{}\n")
            fixture.rebuild_manifest()

            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "positive_mode_forbidden_evidence",
            ):
                self.verify(fixture)

    def test_negative_collection_requires_complete_negative_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            plan_path = fixture.bundle / "collector-plan.json"
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            plan["evidence_mode"] = "negative"
            plan["client_server_base"] = "http://127.0.0.1:18765"
            plan["negative_upstream_url"] = (
                "https://203.0.113.10:8443/api/v1/serverinfo"
            )
            write_json(plan_path, plan)
            fixture.rebuild_manifest()

            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "negative_mode_evidence_missing",
            ):
                self.verify(fixture)

    def test_rejects_manifest_tamper_before_cross_revalidation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            (fixture.bundle / "installed-package.txt").write_text(
                "tampered", encoding="utf-8"
            )
            with mock.patch.object(
                verifier, "revalidate_cross_evidence"
            ) as revalidate:
                with self.assertRaisesRegex(
                    verifier.CollectionVerificationFailure,
                    "manifest_file_binding_mismatch",
                ):
                    verifier.verify_collection(fixture.bundle)
            revalidate.assert_not_called()

    def test_rejects_candidate_report_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            report_path = fixture.bundle / "ci-source-after.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            report["workflow_run_id"] += 1
            write_json(report_path, report)
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "candidate_report_drift",
            ):
                self.verify(fixture)

    def test_rejects_dirty_phone_postflight(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            raw_path = fixture.bundle / "phone-postflight-t2-raw.json"
            raw = json.loads(raw_path.read_text(encoding="utf-8"))
            raw["processes"]["com.aneb.probe.codex"] = "2819"
            write_json(raw_path, raw)
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "phone_state_invalid",
            ):
                self.verify(fixture)

    def test_rejects_remote_fingerprint_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            path = fixture.bundle / "remote-final.json"
            snapshot = json.loads(path.read_text(encoding="utf-8"))
            snapshot["firewall_full_sha256"] = "f" * 64
            write_json(path, snapshot)
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "remote_snapshot_drift",
            ):
                self.verify(fixture)

    def test_rejects_cross_report_not_reproduced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            recomputed = copy.deepcopy(fixture.cross_report)
            recomputed["server_version"] = "aneb-server/9.9.9"
            with mock.patch.object(
                verifier,
                "revalidate_cross_evidence",
                return_value=recomputed,
            ):
                with self.assertRaisesRegex(
                    verifier.CollectionVerificationFailure,
                    "cross_report_revalidation_mismatch",
                ):
                    verifier.verify_collection(fixture.bundle)

    def test_rejects_lock_nonce_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            (fixture.bundle / "lock-released.txt").write_text(
                f"LOCK_RELEASED nonce={'f' * 32}\n",
                encoding="utf-8",
            )
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "lock_receipt_mismatch",
            ):
                self.verify(fixture)

    def test_rejects_serverinfo_sequence_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            path = fixture.bundle / "end-barrier.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["srv_ts_us"] = 2_050
            write_json(path, value)
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "serverinfo_sequence_invalid",
            ):
                self.verify(fixture)

    def test_rejects_installed_apk_digest_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            (fixture.bundle / "installed-base.apk").write_bytes(b"not-the-candidate")
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "installed_apk_binding_mismatch",
            ):
                self.verify(fixture)


if __name__ == "__main__":
    unittest.main()
