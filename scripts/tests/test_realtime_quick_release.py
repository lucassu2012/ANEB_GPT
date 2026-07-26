from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import publish_realtime_quick_ready as publisher
from scripts import verify_realtime_quick_release as release_verifier
from scripts.tests.test_verify_realtime_quick_collection import (
    APK_SHA,
    COLLECTION,
    RUN_ID,
    SERVER_BINARY,
    SOURCE_COMMIT,
    CollectionFixture,
    sha256,
)


def collection_report(fixture: CollectionFixture) -> dict[str, object]:
    manifest = json.loads(
        (fixture.bundle / "evidence-manifest.json").read_text(encoding="utf-8")
    )
    phone_receipt = json.loads(
        (fixture.bundle / "phone-postflight-receipt.json").read_text(
            encoding="utf-8"
        )
    )
    return {
        "schema": "aneb-realtime-quick-collection-verification",
        "schema_version": "1.0.0",
        "status": "pass",
        "reason_code": "ok",
        "collection_id": COLLECTION,
        "run_id": RUN_ID,
        "mode": "positive",
        "source_commit": SOURCE_COMMIT,
        "workflow_run_id": 123456789,
        "server_version": "aneb-server/0.8.1",
        "server_binary_sha256": SERVER_BINARY,
        "apk_sha256": APK_SHA,
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


def root_security_report(fixture: CollectionFixture) -> dict[str, object]:
    return json.loads(
        (fixture.bundle / "evidence-root-security.json").read_text(
            encoding="utf-8"
        )
    )


class RealtimeQuickReadyPublisherTests(unittest.TestCase):
    def test_refuses_ready_when_private_root_is_not_currently_secure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            failure = publisher.evidence_security.EvidenceSecurityFailure(
                "evidence_root_acl_too_permissive"
            )
            with (
                mock.patch.object(
                    publisher.evidence_security,
                    "verify_root",
                    side_effect=failure,
                ),
                mock.patch.object(
                    publisher.collection_verifier,
                    "verify_collection",
                ) as collection_check,
            ):
                with self.assertRaisesRegex(
                    publisher.ReadyPublicationFailure,
                    "release_root_security_invalid",
                ):
                    publisher.publish_ready(fixture.bundle)

            collection_check.assert_not_called()

    def test_publishes_digest_bound_report_then_ready_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
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
                ) as collection_check,
            ):
                result = publisher.publish_ready(fixture.bundle)

            report_path = Path(temporary) / f"{COLLECTION}.verification.json"
            ready_path = Path(temporary) / f"{COLLECTION}.READY.json"
            self.assertTrue(report_path.is_file())
            self.assertTrue(ready_path.is_file())
            self.assertFalse(
                (Path(temporary) / f"{COLLECTION}.verification.partial").exists()
            )
            self.assertFalse(
                (Path(temporary) / f"{COLLECTION}.ready.partial").exists()
            )
            marker = json.loads(ready_path.read_text(encoding="utf-8"))
            self.assertEqual(report["manifest_sha256"], marker["manifest_sha256"])
            self.assertEqual(sha256(report_path), marker["verification_report_sha256"])
            self.assertEqual(str(ready_path), result["ready_path"])
            self.assertEqual(2, collection_check.call_count)

    def test_refuses_any_existing_report_ready_or_temporary_collision(self) -> None:
        collision_leaves = (
            f"{COLLECTION}.verification.json",
            f"{COLLECTION}.verification.partial",
            f"{COLLECTION}.READY.json",
            f"{COLLECTION}.ready.partial",
        )
        for leaf in collision_leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory() as temporary:
                fixture = CollectionFixture(Path(temporary))
                (Path(temporary) / leaf).write_bytes(b"collision")
                with self.assertRaisesRegex(
                    publisher.ReadyPublicationFailure,
                    "release_path_collision",
                ):
                    publisher.publish_ready(fixture.bundle)

    def test_verification_failure_removes_only_new_sibling_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            report = collection_report(fixture)
            with (
                mock.patch.object(
                    publisher.collection_verifier,
                    "verify_collection",
                    return_value=copy.deepcopy(report),
                ),
                mock.patch.object(
                    publisher.evidence_security,
                    "verify_root",
                    return_value=root_security_report(fixture),
                ),
                mock.patch.object(
                    publisher.release_verifier,
                    "verify_release",
                    side_effect=release_verifier.ReleaseVerificationFailure(
                        "release_report_digest_mismatch"
                    ),
                ),
            ):
                with self.assertRaisesRegex(
                    publisher.ReadyPublicationFailure,
                    "release_postcheck_failed",
                ):
                    publisher.publish_ready(fixture.bundle)

            root = Path(temporary)
            self.assertTrue(fixture.bundle.is_dir())
            self.assertFalse((root / f"{COLLECTION}.verification.json").exists())
            self.assertFalse((root / f"{COLLECTION}.READY.json").exists())
            self.assertFalse((root / f"{COLLECTION}.verification.partial").exists())
            self.assertFalse((root / f"{COLLECTION}.ready.partial").exists())


class RealtimeQuickReleaseVerifierTests(unittest.TestCase):
    def published_fixture(
        self,
        root: Path,
    ) -> tuple[CollectionFixture, Path, dict[str, object]]:
        fixture = CollectionFixture(root)
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

    def verify(
        self,
        ready_path: Path,
        report: dict[str, object],
    ) -> dict[str, object]:
        root_report = json.loads(
            (
                ready_path.parent
                / f"{COLLECTION}.complete"
                / "evidence-root-security.json"
            ).read_text(encoding="utf-8")
        )
        with (
            mock.patch.object(
                release_verifier.evidence_security,
                "verify_root",
                return_value=root_report,
            ),
            mock.patch.object(
                release_verifier.collection_verifier,
                "verify_collection",
                return_value=copy.deepcopy(report),
            ) as collection_check,
        ):
            result = release_verifier.verify_release(ready_path)
        collection_check.assert_called_once()
        return result

    def test_accepts_ready_only_after_collection_report_recomputation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture, ready_path, report = self.published_fixture(Path(temporary))
            result = self.verify(ready_path, report)

        self.assertEqual("pass", result["status"])
        self.assertEqual(COLLECTION, result["collection_id"])
        self.assertEqual(RUN_ID, result["run_id"])
        self.assertEqual(
            report["manifest_sha256"],
            result["manifest_sha256"],
        )
        self.assertRegex(result["ready_sha256"], r"^[0-9a-f]{64}$")

    def test_ready_consumer_rechecks_current_private_root_security(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            _, ready_path, _ = self.published_fixture(Path(temporary))
            failure = release_verifier.evidence_security.EvidenceSecurityFailure(
                "evidence_root_acl_too_permissive"
            )
            with mock.patch.object(
                release_verifier.evidence_security,
                "verify_root",
                side_effect=failure,
            ):
                with self.assertRaisesRegex(
                    release_verifier.ReleaseVerificationFailure,
                    "release_root_security_invalid",
                ):
                    release_verifier.verify_release(ready_path)

    def test_rejects_report_tamper_even_when_marker_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            _, ready_path, report = self.published_fixture(Path(temporary))
            report_path = Path(temporary) / f"{COLLECTION}.verification.json"
            value = json.loads(report_path.read_text(encoding="utf-8"))
            value["server_version"] = "aneb-server/9.9.9"
            report_path.write_text(
                json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
                newline="\n",
            )
            with self.assertRaisesRegex(
                release_verifier.ReleaseVerificationFailure,
                "release_report_digest_mismatch",
            ):
                self.verify(ready_path, report)

    def test_rejects_report_that_no_longer_matches_recomputed_collection(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture, ready_path, report = self.published_fixture(Path(temporary))
            recomputed = copy.deepcopy(report)
            recomputed["phone_state_sha256"] = "f" * 64
            with (
                mock.patch.object(
                    release_verifier.evidence_security,
                    "verify_root",
                    return_value=root_security_report(fixture),
                ),
                mock.patch.object(
                    release_verifier.collection_verifier,
                    "verify_collection",
                    return_value=recomputed,
                ),
            ):
                with self.assertRaisesRegex(
                    release_verifier.ReleaseVerificationFailure,
                    "release_collection_revalidation_mismatch",
                ):
                    release_verifier.verify_release(ready_path)

    def test_missing_ready_is_never_inferred_from_complete_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = CollectionFixture(Path(temporary))
            missing = Path(temporary) / f"{COLLECTION}.READY.json"
            with self.assertRaisesRegex(
                release_verifier.ReleaseVerificationFailure,
                "release_ready_invalid",
            ):
                release_verifier.verify_release(missing)
            self.assertTrue(fixture.bundle.is_dir())


if __name__ == "__main__":
    unittest.main()
