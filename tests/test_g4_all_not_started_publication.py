from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = REPO_ROOT / "contracts" / "prototype-0.1" / "validate_contracts.py"
SPEC = importlib.util.spec_from_file_location("prototype_contract_validator", VALIDATOR_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("could not load Prototype 0.1 contract validator")
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class G4AllNotStartedPublicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.profile = validator.load_json("profile-manifest.json")
        cls.profile_hash = validator.EXPECTED_PROFILE_HASH

    def _build_bundle(self, root: Path, campaign_mode: str, all_not_started: bool) -> Path:
        run_count = 3 if campaign_mode == "quick" else 9
        statuses = {index: "not_started" for index in range(1, run_count + 1)} if all_not_started else None
        bundle = root / f"{campaign_mode}-bundle"
        bundle.mkdir()
        validator.build_e2e_bundle(
            bundle,
            self.profile,
            self.profile_hash,
            campaign_mode,
            statuses,
        )
        return bundle

    @staticmethod
    def _handoff_payload(bundle: Path, events_jsonl: str | None = None) -> dict[str, str]:
        meta_json = (bundle / "meta.json").read_text(encoding="utf-8")
        meta = json.loads(meta_json)
        return {
            "schema_version": "aneb-prototype-upload-0.1",
            "campaign_id": meta["campaign_id"],
            "meta_json": meta_json,
            "events_jsonl": (
                (bundle / "events.jsonl").read_text(encoding="utf-8")
                if events_jsonl is None
                else events_jsonl
            ),
            "runs_csv": (bundle / "runs.csv").read_text(encoding="utf-8"),
            "summary_csv": (bundle / "summary.csv").read_text(encoding="utf-8"),
        }

    @staticmethod
    def _write_handoff(root: Path, payload: dict[str, str]) -> Path:
        path = root / "android-upload.json"
        path.write_text(
            json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        return path

    def test_all_not_started_quick_and_acceptance_verify_and_publish_empty_events(self) -> None:
        for campaign_mode, expected_runs in [("quick", 3), ("acceptance", 9)]:
            with self.subTest(campaign_mode=campaign_mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                bundle = self._build_bundle(root, campaign_mode, all_not_started=True)
                self.assertEqual(b"", (bundle / "events.jsonl").read_bytes())
                runs = [
                    validator.csv_row_to_run(row)
                    for row in validator.read_csv(bundle / "runs.csv", validator.RUN_COLUMNS)
                ]
                self.assertEqual(expected_runs, len(runs))
                self.assertEqual({"not_started"}, {run["run_status"] for run in runs})

                validator.verify_e2e_bundle(bundle, self.profile, self.profile_hash)

                handoff = self._write_handoff(root, self._handoff_payload(bundle))
                receipt = validator.publish_android_upload(handoff, root / "published")
                published = root / "published" / receipt["campaign_id"]
                self.assertEqual("verified", receipt["publication_status"])
                self.assertEqual(b"", (published / "events.jsonl").read_bytes())
                validator.verify_e2e_bundle(published, self.profile, self.profile_hash)

    def test_empty_events_reject_an_attempted_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = self._build_bundle(root, "quick", all_not_started=False)
            handoff = self._write_handoff(root, self._handoff_payload(bundle, events_jsonl=""))
            with self.assertRaisesRegex(ValueError, "attempted.*events"):
                validator.publish_android_upload(handoff, root / "published")

    def test_empty_events_must_be_exact_zero_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = self._build_bundle(root, "quick", all_not_started=True)
            handoff = self._write_handoff(root, self._handoff_payload(bundle, events_jsonl="\n"))
            with self.assertRaises(ValueError):
                validator.publish_android_upload(handoff, root / "handoff-published")

            (bundle / "events.jsonl").write_text("\n", encoding="utf-8", newline="\n")
            validator.refresh_bundle_manifest(bundle)
            with self.assertRaisesRegex(ValueError, "exact zero-byte"):
                validator.verify_e2e_bundle(bundle, self.profile, self.profile_hash)

    def test_nonempty_events_still_require_terminal_lf(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = self._build_bundle(root, "quick", all_not_started=False)
            events = (bundle / "events.jsonl").read_text(encoding="utf-8")
            self.assertTrue(events.endswith("\n"))
            handoff = self._write_handoff(root, self._handoff_payload(bundle, events_jsonl=events[:-1]))
            with self.assertRaisesRegex(ValueError, "events_jsonl is not canonical UTF-8/LF text"):
                validator.publish_android_upload(handoff, root / "published")

    def test_empty_events_cross_check_meta_runs_and_summary_campaign_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = self._build_bundle(root, "quick", all_not_started=True)

            runs_mismatch = self._handoff_payload(bundle)
            runs_mismatch["runs_csv"] = runs_mismatch["runs_csv"].replace(
                "campaign-0001", "campaign-forged"
            )
            handoff = self._write_handoff(root, runs_mismatch)
            with self.assertRaisesRegex(ValueError, "campaign identity"):
                validator.publish_android_upload(handoff, root / "runs-mismatch")

            summary_mismatch = self._handoff_payload(bundle)
            summary_mismatch["summary_csv"] = summary_mismatch["summary_csv"].replace(
                "campaign-0001", "campaign-forged"
            )
            handoff = self._write_handoff(root, summary_mismatch)
            with self.assertRaisesRegex(ValueError, "summary.csv"):
                validator.publish_android_upload(handoff, root / "summary-mismatch")


if __name__ == "__main__":
    unittest.main()
