from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from scripts import quick_collection_verifier as verifier


class QuickCollectionVerifierTests(unittest.TestCase):
    def test_canonical_json_round_trip_and_duplicate_key_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            valid = root / "valid.json"
            valid.write_bytes(verifier.canonical_json({"z": 2, "a": 1}))
            value, raw = verifier.load_json(
                valid,
                "fixture_invalid",
                maximum=1024,
                require_canonical=True,
            )
            self.assertEqual({"a": 1, "z": 2}, value)
            self.assertEqual(b'{"a":1,"z":2}\n', raw)

            duplicate = root / "duplicate.json"
            duplicate.write_bytes(b'{"a":1,"a":2}\n')
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "^fixture_invalid$",
            ):
                verifier.load_json(
                    duplicate,
                    "fixture_invalid",
                    maximum=1024,
                    require_canonical=True,
                )

    def test_noncanonical_and_nan_are_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pretty = root / "pretty.json"
            pretty.write_text(json.dumps({"a": 1}, indent=2), encoding="utf-8")
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "^fixture_invalid_noncanonical$",
            ):
                verifier.load_json(
                    pretty,
                    "fixture_invalid",
                    maximum=1024,
                    require_canonical=True,
                )

            nan = root / "nan.json"
            nan.write_bytes(b'{"a":NaN}\n')
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "^fixture_invalid$",
            ):
                verifier.load_json(
                    nan,
                    "fixture_invalid",
                    maximum=1024,
                    require_canonical=False,
                )

    def test_family_neutral_identity_validators(self) -> None:
        run_id = "019fa3d7-8ab2-76eb-90bd-182a482b3c7f"
        barrier_id = "123e4567-e89b-42d3-a456-426614174000"
        self.assertEqual(run_id, verifier.validate_uuid(run_id, version=7, reason="bad"))
        self.assertEqual(
            barrier_id,
            verifier.validate_uuid(barrier_id, version=4, reason="bad"),
        )
        parsed = verifier.validate_server_base("https://127.0.0.1:8443", "bad")
        self.assertEqual("127.0.0.1", parsed.hostname)
        self.assertEqual(8443, parsed.port)
        self.assertEqual(
            "com.aneb.probe.codex/com.aneb.probe.codex.ui.MainActivity",
            verifier.canonical_component(
                "mCurrentFocus=Window{42 com.aneb.probe.codex/.ui.MainActivity}",
                reason="bad",
            ),
        )

        for invalid in (
            "http://127.0.0.1:8443",
            "https://user@127.0.0.1:8443",
            "https://127.0.0.1:8443/path",
        ):
            with self.assertRaisesRegex(
                verifier.CollectionVerificationFailure,
                "^bad$",
            ):
                verifier.validate_server_base(invalid, "bad")


if __name__ == "__main__":
    unittest.main()
