from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import publish_network_quick_ready as publisher
from scripts import verify_network_quick_release as release_verifier
from scripts.tests.test_verify_network_quick_collection import (
    COLLECTION,
    RUN_ID,
    NetworkCollectionFixture,
)
from scripts.tests.test_verify_realtime_quick_collection import sha256


def collection_report(fixture: NetworkCollectionFixture) -> dict[str, object]:
    manifest = json.loads(
        (fixture.bundle / "evidence-manifest.json").read_text(encoding="utf-8")
    )
    phone_receipt = json.loads(
        (fixture.bundle / "phone-postflight-receipt.json").read_text(
            encoding="utf-8"
        )
    )
    plan = json.loads((fixture.bundle / "collector-plan.json").read_text("utf-8"))
    return {
        "schema": "aneb-network-quick-collection-verification",
        "schema_version": "1.0.0",
        "status": "pass",
        "reason_code": "ok",
        "collection_id": COLLECTION,
        "run_id": RUN_ID,
        "mode": "positive",
        "source_commit": plan["source_commit"],
        "workflow_run_id": plan["workflow_run_id"],
        "server_version": "aneb-server/0.8.2",
        "server_binary_sha256": plan["expected_server_binary_sha256"],
        "apk_sha256": plan["expected_apk_sha256"],
        "manifest_sha256": sha256(fixture.bundle / "evidence-manifest.json"),
        "manifest_file_count": len(manifest["files"]),
        "journal_cursor": "s=0123456789abcdef",
        "lock_nonce_sha256": (
            "d0079e9ecebfcf707ae8dc76d09b22fbafbde3b169d4cae15eb4e7874e549345"
        ),
        "phone_state_sha256": phone_receipt["t2_sha256"],
        "manifest_reverified": True,
        "cross_evidence_recomputed": True,
        "phone_state_reverified": True,
        "remote_state_reverified": True,
        "candidate_provenance_reverified": True,
        "evidence_root_security_sha256": sha256(
            fixture.bundle / "evidence-root-security.json"
        ),
        "evidence_root_security_bound": True,
    }


def root_security_report(fixture: NetworkCollectionFixture) -> dict[str, object]:
    return json.loads(
        (fixture.bundle / "evidence-root-security.json").read_text("utf-8")
    )


class NetworkQuickReadyReleaseTests(unittest.TestCase):
    def publish(
        self, root: Path
    ) -> tuple[NetworkCollectionFixture, Path, dict[str, object]]:
        fixture = NetworkCollectionFixture(root)
        report = collection_report(fixture)
        with (
            mock.patch.object(
                publisher.evidence_security,
                "verify_root",
                return_value=root_security_report(fixture),
            ),
            mock.patch.object(
                publisher.collection_verifier,
                "verify_collection",
                return_value=copy.deepcopy(report),
            ),
        ):
            result = publisher.publish_ready(fixture.bundle)
        return fixture, Path(result["ready_path"]), report

    def test_publishes_network_specific_digest_bound_ready(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture, ready, report = self.publish(Path(temporary))
            marker = json.loads(ready.read_text("utf-8"))

        self.assertEqual("aneb-network-quick-evidence-release", marker["schema"])
        self.assertEqual(COLLECTION, marker["collection_id"])
        self.assertEqual(report["manifest_sha256"], marker["manifest_sha256"])
        self.assertTrue(fixture.bundle.name.endswith(".complete"))

    def test_consumer_recomputes_network_collection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture, ready, report = self.publish(Path(temporary))
            with (
                mock.patch.object(
                    release_verifier.evidence_security,
                    "verify_root",
                    return_value=root_security_report(fixture),
                ),
                mock.patch.object(
                    release_verifier.collection_verifier,
                    "verify_collection",
                    return_value=copy.deepcopy(report),
                ) as collection_check,
            ):
                result = release_verifier.verify_release(ready)

        self.assertEqual("pass", result["status"])
        self.assertEqual(COLLECTION, result["collection_id"])
        collection_check.assert_called_once()

    def test_consumer_rejects_report_tamper(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture, ready, _ = self.publish(Path(temporary))
            report_path = Path(temporary) / f"{COLLECTION}.verification.json"
            value = json.loads(report_path.read_text("utf-8"))
            value["server_version"] = "aneb-server/9.9.9"
            report_path.write_text(
                json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
                newline="\n",
            )
            with mock.patch.object(
                release_verifier.evidence_security,
                "verify_root",
                return_value=root_security_report(fixture),
            ):
                with self.assertRaisesRegex(
                    release_verifier.ReleaseVerificationFailure,
                    "release_report_digest_mismatch",
                ):
                    release_verifier.verify_release(ready)


if __name__ == "__main__":
    unittest.main()
