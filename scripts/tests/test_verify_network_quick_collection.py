from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import verify_network_quick_collection as verifier
from scripts.tests.test_verify_realtime_quick_collection import (
    APK_BYTES,
    RUN_ID,
    CollectionFixture,
    canonical,
    sha256,
    write_json,
)


COLLECTION = "m0-ec3-network-quick-20260727T120000Z-" + "a" * 32
NETWORK_APK = "ANEB-Probe-0.5.14-codex-debug.apk"


def network_serverinfo(timestamp: int) -> dict[str, object]:
    return {
        "version": "aneb-server/0.8.2",
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
                {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"},
                {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
                {"primitive_id": "realtime_sim", "wire_contract_id": "aneb-realtime-session-v1"},
                {"primitive_id": "token_sim", "wire_contract_id": "aneb-token-task-v1"},
                {"primitive_id": "udp_echo", "wire_contract_id": "aneb-udp-echo-v2"},
                {"primitive_id": "upload", "wire_contract_id": "aneb-upload-v1"},
            ],
            "validated_profiles": [
                {
                    "profile_id": "ai_realtime_voice_quick",
                    "profile_version": "1.1.1",
                    "profile_sha256": "sha256:701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7",
                },
                {
                    "profile_id": "network_comprehensive_quick",
                    "profile_version": "1.2.0",
                    "profile_sha256": "sha256:15ae5187fac72d86b78ff89ad44d5a51706dc7c4e4cf01432f367acd9ed082cc",
                },
                {
                    "profile_id": "token_multimodal_quick",
                    "profile_version": "1.2.1",
                    "profile_sha256": "sha256:caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773",
                },
            ],
        },
    }


class NetworkCollectionFixture:
    def __init__(self, root: Path) -> None:
        realtime = CollectionFixture(root)
        self.bundle = root / f"{COLLECTION}.complete"
        realtime.bundle.rename(self.bundle)
        self.cross_report = {
            "schema": "aneb-network-quick-evidence-binding",
            "schema_version": "1.0.0",
            "status": "pass",
            "mode": "positive",
            "run_id": RUN_ID,
            "profile_contract": "network_comprehensive_quick@1.2.0",
            "contract_status": "authorized",
            "terminal_status": "completed",
            "reason_code": None,
            "server_business_total": 74,
        }
        self._convert()

    def _convert(self) -> None:
        plan = json.loads((self.bundle / "collector-plan.json").read_text("utf-8"))
        plan.update(
            {
                "schema": "aneb-network-quick-collector-plan",
                "collection_id": COLLECTION,
                "profile_contract": "network_comprehensive_quick@1.2.0",
                "version_name": "0.5.14-codex",
                "version_code": 46,
                "expected_server_version": "aneb-server/0.8.2",
                "expected_apk_sha256": hashlib.sha256(APK_BYTES).hexdigest(),
            }
        )
        write_json(self.bundle / "collector-plan.json", plan)

        status = json.loads((self.bundle / "collector-status.json").read_text("utf-8"))
        status.update(
            {
                "schema": "aneb-network-quick-collector-status",
                "collection_id": COLLECTION,
            }
        )
        write_json(self.bundle / "collector-status.json", status)

        write_json(self.bundle / "cross-bound-report.json", self.cross_report)
        receipt = json.loads((self.bundle / "run-receipt.json").read_text("utf-8"))
        receipt.update(
            {
                "schema": "aneb-network-quick-run-receipt",
                "collection_id": COLLECTION,
                "cross_bound_report_sha256": sha256(
                    self.bundle / "cross-bound-report.json"
                ),
            }
        )
        write_json(self.bundle / "run-receipt.json", receipt)

        for leaf in (
            "ci-source-before.json",
            "ci-candidate-verification.json",
            "ci-source-after.json",
        ):
            report = json.loads((self.bundle / leaf).read_text("utf-8"))
            report["apk"].update(
                {
                    "file_name": NETWORK_APK,
                    "version_name": "0.5.14-codex",
                    "version_code": 46,
                }
            )
            write_json(self.bundle / leaf, report)
        candidate = self.bundle / "ci-candidate"
        (candidate / "ANEB-Probe-0.5.13-codex-debug.apk").rename(
            candidate / NETWORK_APK
        )
        (self.bundle / "installed-package.txt").write_bytes(
            b"versionCode=46 minSdk=31\nversionName=0.5.14-codex\n"
        )

        identity = json.loads((self.bundle / "device-identity.json").read_text("utf-8"))
        identity["schema"] = "aneb-network-device-identity"
        write_json(self.bundle / "device-identity.json", identity)
        for prefix in ("phone-preflight", "phone-postflight"):
            path = self.bundle / f"{prefix}-receipt.json"
            value = json.loads(path.read_text("utf-8"))
            value["schema"] = "aneb-network-phone-live-state-receipt"
            write_json(path, value)
        for stage in ("acquired", "before-target", "before-end-barrier"):
            write_json(
                self.bundle / f"busy-sentinel-{stage}.json",
                {
                    "schema": "aneb-network-busy-sentinel",
                    "schema_version": "1.0.0",
                    "stage": stage,
                    "observed_components": [
                        "com.android.settings/com.android.settings.HWSettings"
                    ],
                    "matched": True,
                },
            )

        nonce = "e" * 32
        (self.bundle / "lock-acquired.txt").write_bytes(
            (
                f"LOCK_ACQUIRED nonce={nonce} pid=1234 "
                f"marker=/run/aneb-network-audit-{nonce}.lock\n"
            ).encode("utf-8")
        )
        for name, timestamp in (
            ("identity-serverinfo", 2_000),
            ("start-barrier", 2_100),
            ("end-barrier", 2_200),
        ):
            write_json(self.bundle / f"{name}.json", network_serverinfo(timestamp))

        write_json(
            self.bundle / "client-db-verification.json",
            {
                "schema": "aneb-network-quick-client-db-verification",
                "schema_version": "1.0.0",
                "status": "pass",
                "mode": "positive",
                "run_id": RUN_ID,
                "reason_code": None,
            },
        )
        write_json(
            self.bundle / "server-audit-verification.json",
            {
                "schema": "aneb-network-request-entry-audit-report",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "mode": "positive",
                "run_id": RUN_ID,
                "profile_contract": "network_comprehensive_quick@1.2.0",
                "counts": {"business_total": 74},
            },
        )
        (self.bundle / "app-logcat.txt").write_text(
            "D82_CAPTURE_MARKER nonce=" + "f" * 32 + "\nsynthetic\n",
            encoding="utf-8",
        )
        (self.bundle / "app-logcat.stderr.txt").write_bytes(b"")
        (self.bundle / "journal.raw.log").write_text(
            "synthetic\n", encoding="utf-8"
        )
        (self.bundle / "aneb-probe.db").write_bytes(b"synthetic-db")
        profile = self.bundle / "network-profile"
        profile.mkdir()
        (profile / "profile.json").write_bytes(b"{}\n")
        (profile / "runtime_plan.json").write_bytes(b"{}\n")
        (profile / "manifest.sha256").write_bytes(b"synthetic\n")
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
                "schema": "aneb-network-quick-evidence-manifest",
                "schema_version": "1.0.0",
                "files": records,
            },
        )
        manifest_sha = sha256(self.bundle / "evidence-manifest.json")
        (self.bundle / "COMPLETE").write_bytes(
            (
                f"ANEB_NETWORK_QUICK_COMPLETE collection_id={COLLECTION} "
                f"run_id={RUN_ID} manifest_sha256={manifest_sha}\n"
            ).encode("ascii")
        )


class NetworkQuickCollectionVerifierTests(unittest.TestCase):
    def verify(self, fixture: NetworkCollectionFixture) -> dict[str, object]:
        with mock.patch.object(
            verifier,
            "revalidate_cross_evidence",
            return_value=copy.deepcopy(fixture.cross_report),
        ) as revalidate:
            report = verifier.verify_collection(fixture.bundle)
        revalidate.assert_called_once()
        return report

    def test_accepts_network_identity_and_recomputes_independent_reports(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NetworkCollectionFixture(Path(temporary))
            report = self.verify(fixture)

        self.assertEqual("pass", report["status"])
        self.assertEqual(COLLECTION, report["collection_id"])
        self.assertEqual("aneb-server/0.8.2", report["server_version"])
        self.assertTrue(report["cross_evidence_recomputed"])

    def test_rejects_realtime_phone_receipt_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NetworkCollectionFixture(Path(temporary))
            path = fixture.bundle / "phone-postflight-receipt.json"
            value = json.loads(path.read_text("utf-8"))
            value["schema"] = "aneb-realtime-phone-live-state-receipt"
            write_json(path, value)
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "phone_receipt_invalid",
            ):
                self.verify(fixture)

    def test_rejects_realtime_busy_sentinel_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NetworkCollectionFixture(Path(temporary))
            path = fixture.bundle / "busy-sentinel-before-target.json"
            value = json.loads(path.read_text("utf-8"))
            value["schema"] = "aneb-realtime-busy-sentinel"
            write_json(path, value)
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "busy_sentinel_invalid",
            ):
                self.verify(fixture)

    def test_rejects_nonempty_logcat_stderr(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NetworkCollectionFixture(Path(temporary))
            (fixture.bundle / "app-logcat.stderr.txt").write_text(
                "adb transport error\n",
                encoding="utf-8",
            )
            fixture.rebuild_manifest()
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "network_logcat_stderr_not_empty",
            ):
                self.verify(fixture)

    def test_cross_revalidation_compares_all_three_persisted_reports(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NetworkCollectionFixture(Path(temporary))
            plan = json.loads(
                (fixture.bundle / "collector-plan.json").read_text("utf-8")
            )
            reports = {
                "client": json.loads(
                    (fixture.bundle / "client-db-verification.json").read_text(
                        "utf-8"
                    )
                ),
                "audit": json.loads(
                    (fixture.bundle / "server-audit-verification.json").read_text(
                        "utf-8"
                    )
                ),
                "binding": copy.deepcopy(fixture.cross_report),
            }
            markers = mock.Mock(run_id=RUN_ID)
            with (
                mock.patch.object(
                    verifier,
                    "parse_network_terminal_markers",
                    return_value=markers,
                ),
                mock.patch.object(
                    verifier,
                    "compute_network_verifier_reports",
                    return_value=copy.deepcopy(reports),
                ),
            ):
                self.assertEqual(
                    fixture.cross_report,
                    verifier.revalidate_cross_evidence(
                        fixture.bundle,
                        plan=plan,
                        run_id=RUN_ID,
                    ),
                )

                reports["client"]["status"] = "fail"
                with mock.patch.object(
                    verifier,
                    "compute_network_verifier_reports",
                    return_value=reports,
                ):
                    with self.assertRaisesRegex(
                        verifier.CollectionVerificationFailure,
                        "cross_report_revalidation_mismatch",
                    ):
                        verifier.revalidate_cross_evidence(
                            fixture.bundle,
                            plan=plan,
                            run_id=RUN_ID,
                        )

    def test_cross_revalidation_ignores_all_pre_capture_logcat(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = NetworkCollectionFixture(Path(temporary))
            nonce = "f" * 32
            (fixture.bundle / "app-logcat.txt").write_text(
                "STALE_NET_V1_END\n"
                f"01-01 D82_CAPTURE_MARKER nonce={nonce}\n"
                "CURRENT_NET_V1_START\n",
                encoding="utf-8",
            )
            plan = json.loads(
                (fixture.bundle / "collector-plan.json").read_text("utf-8")
            )
            reports = {
                "client": json.loads(
                    (fixture.bundle / "client-db-verification.json").read_text(
                        "utf-8"
                    )
                ),
                "audit": json.loads(
                    (fixture.bundle / "server-audit-verification.json").read_text(
                        "utf-8"
                    )
                ),
                "binding": copy.deepcopy(fixture.cross_report),
            }
            markers = mock.Mock(run_id=RUN_ID)
            with (
                mock.patch.object(
                    verifier,
                    "parse_network_terminal_markers",
                    return_value=markers,
                ) as parse,
                mock.patch.object(
                    verifier,
                    "compute_network_verifier_reports",
                    return_value=reports,
                ),
            ):
                verifier.revalidate_cross_evidence(
                    fixture.bundle,
                    plan=plan,
                    run_id=RUN_ID,
                )

        self.assertEqual(
            "\nCURRENT_NET_V1_START\n",
            parse.call_args.args[0].replace("\r\n", "\n"),
        )


if __name__ == "__main__":
    unittest.main()
