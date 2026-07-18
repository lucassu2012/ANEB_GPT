from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path

from scripts import update_shared_test_status as retired_status


SCRIPT = Path(__file__).resolve().parents[1] / "update_shared_test_status.py"


class RetiredSharedStatusToolTest(unittest.TestCase):
    def test_imported_entry_point_fails_closed(self) -> None:
        stderr = StringIO()
        with redirect_stderr(stderr):
            result = retired_status.main(["claim"])
        self.assertEqual(78, result)
        self.assertEqual(78, retired_status.RETIRED_EXIT_CODE)
        self.assertIn("code=shared_test_status_retired", stderr.getvalue())

    def test_legacy_cli_does_not_modify_status_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            status = Path(directory) / "SHARED_TEST_STATUS.md"
            original = b"historical status evidence\n"
            status.write_bytes(original)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--status-file",
                    str(status),
                    "claim",
                    "--executor",
                    "Codex",
                    "--task",
                    "legacy",
                    "--resources",
                    "P40 Pro",
                ],
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(78, completed.returncode)
            self.assertIn("code=shared_test_status_retired", completed.stderr)
            self.assertEqual(original, status.read_bytes())
            self.assertEqual("", completed.stdout)


if __name__ == "__main__":
    unittest.main()
