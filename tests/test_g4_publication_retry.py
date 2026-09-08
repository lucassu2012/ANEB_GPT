from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tests.test_g4_all_not_started_publication import validator


class G4PublicationRetryTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        fixture = self.root / "android-evidence"
        fixture.mkdir()
        self.profile = validator.load_json("profile-manifest.json")
        validator.build_e2e_bundle(fixture, self.profile, validator.EXPECTED_PROFILE_HASH)
        meta_text = (fixture / "meta.json").read_text(encoding="utf-8")
        self.campaign_id = json.loads(meta_text)["campaign_id"]
        self.payload = {
            "schema_version": "aneb-prototype-upload-0.1",
            "campaign_id": self.campaign_id,
            "meta_json": meta_text,
            "events_jsonl": (fixture / "events.jsonl").read_text(encoding="utf-8"),
            "runs_csv": (fixture / "runs.csv").read_text(encoding="utf-8"),
            "summary_csv": (fixture / "summary.csv").read_text(encoding="utf-8"),
        }
        self.handoff = self.root / "request.json"
        self.output = self.root / "published"
        self._write_request(self.payload)
        self.first = validator.publish_android_upload(self.handoff, self.output)
        self.campaign = self.output / self.campaign_id
        self.original = self._files()
        self.modified_at = self._times()

    def _write_request(self, payload: dict[str, str], **formatting: object) -> None:
        self.handoff.write_text(json.dumps(payload, **formatting) + "\n", encoding="utf-8", newline="\n")

    def _files(self) -> dict[str, bytes]:
        return {path.name: path.read_bytes() for path in self.campaign.iterdir()}

    def _times(self) -> dict[str, int]:
        return {path.name: path.stat().st_mtime_ns for path in self.campaign.iterdir()}

    def _assert_unchanged(self) -> None:
        self.assertEqual(self.original, self._files())
        self.assertEqual(self.modified_at, self._times())
        validator.verify_e2e_bundle(self.campaign, self.profile, validator.EXPECTED_PROFILE_HASH)

    def _conflicting_payload(self) -> dict[str, str]:
        payload = dict(self.payload)
        # Inner text bytes are immutable; outer request JSON formatting is not.
        payload["meta_json"] = json.dumps(json.loads(payload["meta_json"]), indent=2) + "\n"
        self.assertNotEqual(self.payload["meta_json"], payload["meta_json"])
        return payload

    def test_retry_returns_original_receipt_without_rewriting_published_evidence(self) -> None:
        self.assertEqual(self.first, validator.publish_android_upload(self.handoff, self.output))
        self._assert_unchanged()

    def test_outer_request_formatting_does_not_change_retry_identity(self) -> None:
        self._write_request(self.payload, indent=4, sort_keys=True)
        self.assertEqual(self.first, validator.publish_android_upload(self.handoff, self.output))
        self._assert_unchanged()

    def test_conflict_retains_one_digest_only_diagnostic_without_changing_evidence(self) -> None:
        payload = self._conflicting_payload()
        self._write_request(payload)
        with self.assertRaises(ValueError):
            validator.publish_android_upload(self.handoff, self.output)
        diagnostics = list((self.output / ".publication diagnostics").glob("*.json"))
        self.assertEqual(1, len(diagnostics), "runtime must retain conflict outside the seven-file bundle")
        retained = diagnostics[0].read_bytes()
        self.assertLessEqual(len(retained), 2048)
        diagnostic = json.loads(retained)
        self.assertEqual("immutable_campaign_conflict", diagnostic["reason"])
        self.assertEqual(self.first["manifest_sha256"], diagnostic["published_manifest_sha256"])
        self.assertEqual(hashlib.sha256(self.campaign_id.encode()).hexdigest(), diagnostic["campaign_id_sha256"])
        self.assertEqual(["meta.json"], diagnostic["changed_files"])
        self.assertEqual(
            hashlib.sha256(payload["meta_json"].encode()).hexdigest(),
            diagnostic["incoming_payload_sha256"]["meta.json"],
        )
        self.assertNotIn(self.campaign_id.encode(), retained)
        self.assertNotIn(self.payload["meta_json"].encode(), retained)
        with self.assertRaises(ValueError):
            validator.publish_android_upload(self.handoff, self.output)
        self.assertEqual(diagnostics, list((self.output / ".publication diagnostics").glob("*.json")))
        self.assertEqual(retained, diagnostics[0].read_bytes(), "retain the first conflict, do not grow a retry log")
        self._assert_unchanged()

    def test_damaged_existing_bundle_is_not_repaired_or_accepted_as_retry(self) -> None:
        report = self.campaign / "report.html"
        report.write_bytes(report.read_bytes() + b"tampered")
        damaged = self._files()
        with self.assertRaises(ValueError):
            validator.publish_android_upload(self.handoff, self.output)
        self.assertEqual(damaged, self._files())

    def test_diagnostic_namespace_cannot_modify_another_valid_campaign(self) -> None:
        other_id = ".publication-diagnostics"
        other = {key: value.replace(self.campaign_id, other_id) for key, value in self.payload.items()}
        self._write_request(other)
        receipt = validator.publish_android_upload(self.handoff, self.output)
        self.assertEqual(other_id, receipt["campaign_id"])
        other_root = self.output / other_id
        original = {path.name: path.read_bytes() for path in other_root.iterdir()}
        self._write_request(self._conflicting_payload())
        with self.assertRaises(ValueError):
            validator.publish_android_upload(self.handoff, self.output)
        self.assertEqual(original, {path.name: path.read_bytes() for path in other_root.iterdir()})
        validator.verify_e2e_bundle(other_root, self.profile, validator.EXPECTED_PROFILE_HASH)
        self._assert_unchanged()

    def test_diagnostic_write_failure_still_rejects_conflict(self) -> None:
        diagnostic_root = self.output / ".publication diagnostics"
        diagnostic_root.write_text("blocked", encoding="utf-8")
        self._write_request(self._conflicting_payload())
        with self.assertRaises((ValueError, OSError)):
            validator.publish_android_upload(self.handoff, self.output)
        self.assertEqual("blocked", diagnostic_root.read_text(encoding="utf-8"))
        self._assert_unchanged()


if __name__ == "__main__":
    unittest.main()
