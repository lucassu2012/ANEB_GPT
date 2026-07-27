from __future__ import annotations

import inspect
from pathlib import Path
import tempfile
import unittest

from scripts import quick_evidence_security as neutral
from scripts import verify_realtime_evidence_security as legacy


class QuickEvidenceSecurityCompatibilityTests(unittest.TestCase):
    def test_neutral_module_has_no_family_dependency(self) -> None:
        source = inspect.getsource(neutral).casefold()
        for family in ("realtime", "network", "token"):
            self.assertNotIn(f"verify_{family}", source)
            self.assertNotIn(f"collect_{family}", source)

    def test_legacy_entrypoint_reexports_the_same_contract_identity(self) -> None:
        self.assertIs(legacy.EvidenceSecurityFailure, neutral.EvidenceSecurityFailure)
        self.assertIs(legacy.evaluate_observation, neutral.evaluate_observation)
        self.assertIs(legacy.validate_report, neutral.validate_report)
        self.assertIs(legacy.verify_root, neutral.verify_root)

    def test_neutral_report_round_trip_is_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = neutral.evaluate_observation(
                root,
                {
                    "platform": "posix",
                    "current_identity": "uid:1000",
                    "owner_identity": "uid:1000",
                    "mode_octal": "0700",
                },
            )
            neutral.validate_report(root, report)
            with self.assertRaises(neutral.EvidenceSecurityFailure):
                neutral.validate_report(root.parent, report)


if __name__ == "__main__":
    unittest.main()
