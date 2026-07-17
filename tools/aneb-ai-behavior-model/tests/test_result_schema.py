import importlib.util
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
VERIFIER_PATH = REPO_ROOT / "scripts" / "verify_result_schema.py"
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("verify_result_schema", VERIFIER_PATH)
if SPEC is None or SPEC.loader is None:  # pragma: no cover
    raise RuntimeError(f"cannot load {VERIFIER_PATH}")
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class ResultSchemaTest(unittest.TestCase):
    def test_schema_fixtures_and_fail_closed_invariants(self):
        self.assertEqual([], VERIFY.validate_repository(REPO_ROOT))


if __name__ == "__main__":
    unittest.main()
