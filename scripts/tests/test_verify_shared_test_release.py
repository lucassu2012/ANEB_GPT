from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path

from scripts import verify_shared_test_release as retired_verifier


SCRIPT = Path(__file__).resolve().parents[1] / "verify_shared_test_release.py"


class RetiredSharedReleaseVerifierTest(unittest.TestCase):
    def test_imported_entry_point_fails_closed(self) -> None:
        stderr = StringIO()
        with redirect_stderr(stderr):
            result = retired_verifier.main(["--help"])
        self.assertEqual(78, result)
        self.assertEqual(78, retired_verifier.RETIRED_EXIT_CODE)
        self.assertIn("code=shared_release_verifier_retired", stderr.getvalue())

    def test_legacy_cli_neither_probes_nor_changes_status(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            status = Path(directory) / "SHARED_TEST_STATUS.md"
            original = b"historical handoff evidence\n"
            status.write_bytes(original)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--status-file",
                    str(status),
                    "--adb-serial",
                    "legacy-device",
                    "--ssh-host",
                    "legacy-server",
                ],
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(78, completed.returncode)
            self.assertIn("code=shared_release_verifier_retired", completed.stderr)
            self.assertEqual(original, status.read_bytes())
            self.assertEqual("", completed.stdout)

        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("subprocess", source)
        self.assertNotIn(" adb ", source.casefold())
        self.assertNotIn(" ssh ", source.casefold())


if __name__ == "__main__":
    unittest.main()
