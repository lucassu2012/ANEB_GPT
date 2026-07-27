from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
RUNNERS = (
    (
        "realtime",
        "m0-ec2-realtime-20260727T000000Z-" + "a" * 32,
        "aneb-realtime-quick-ready-publication",
        "aneb-realtime-quick-release-verification",
    ),
    (
        "network",
        "m0-ec3-network-quick-20260727T000000Z-" + "b" * 32,
        "aneb-network-quick-ready-publication",
        "aneb-network-quick-release-verification",
    ),
)


class QuickReadyDirectCliTests(unittest.TestCase):
    def run_cli(self, script: str, argument: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            (sys.executable, str(ROOT / "scripts" / script), str(argument)),
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=30,
        )

    def test_family_wrappers_are_directly_executable_and_keep_their_schemas(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for family, collection, publication_schema, verification_schema in RUNNERS:
                with self.subTest(family=family, command="publish"):
                    published = self.run_cli(
                        f"publish_{family}_quick_ready.py",
                        root / f"{collection}.complete",
                    )
                    self.assertEqual(1, published.returncode)
                    self.assertEqual("", published.stdout)
                    publication = json.loads(published.stderr)
                    self.assertEqual(publication_schema, publication["schema"])
                    self.assertEqual("fail", publication["status"])

                with self.subTest(family=family, command="verify"):
                    verified = self.run_cli(
                        f"verify_{family}_quick_release.py",
                        root / f"{collection}.READY.json",
                    )
                    self.assertEqual(1, verified.returncode)
                    self.assertEqual("", verified.stdout)
                    verification = json.loads(verified.stderr)
                    self.assertEqual(verification_schema, verification["schema"])
                    self.assertEqual("release_ready_invalid", verification["reason_code"])


if __name__ == "__main__":
    unittest.main()
