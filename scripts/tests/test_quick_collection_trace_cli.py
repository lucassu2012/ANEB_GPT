from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.quick_collection_trace_cli import (
    MAX_TRACE_BYTES,
    evaluate_trace_document,
)
from scripts.quick_collection_workflow import CollectorError


class QuickCollectionTraceCliTest(unittest.TestCase):
    @staticmethod
    def _success_document() -> bytes:
        return (
            b'{"events":['
            b'{"outcome":"pass","phase":"preflight"},'
            b'{"outcome":"pass","phase":"acquire"},'
            b'{"outcome":"pass","phase":"collect"},'
            b'{"outcome":"pass","phase":"cleanup_phone"},'
            b'{"outcome":"pass","phase":"cleanup_remote"}'
            b'],"schema":"aneb-quick-workflow-trace@1.0.0"}\n'
        )

    @staticmethod
    def _success_decision() -> bytes:
        return (
            b'{"cleanup_failures":[],"primary_failure":null,'
            b'"publish_eligible":true,"schema":'
            b'"aneb-quick-workflow-decision@1.0.0"}\n'
        )

    def test_success_document_returns_canonical_decision(self) -> None:
        self.assertEqual(
            self._success_decision(),
            evaluate_trace_document(self._success_document()),
        )

    def test_module_cli_emits_only_the_canonical_decision(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_path = Path(directory) / "workflow-trace.json"
            trace_path.write_bytes(self._success_document())
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "scripts.quick_collection_trace_cli",
                    str(trace_path),
                ],
                check=False,
                capture_output=True,
            )

        self.assertEqual(0, completed.returncode)
        self.assertEqual(self._success_decision(), completed.stdout)
        self.assertEqual(b"", completed.stderr)

    def test_unsafe_json_bytes_are_rejected(self) -> None:
        invalid_documents = (
            b'{"schema":"a","schema":"b","events":[]}\n',
            b'{"schema":"aneb-quick-workflow-trace@1.0.0","events":NaN}\n',
            b"\xff",
            b"x" * (MAX_TRACE_BYTES + 1),
        )
        for raw in invalid_documents:
            with self.subTest(raw_prefix=raw[:32]):
                with self.assertRaisesRegex(
                    CollectorError,
                    "^workflow_trace_document_invalid$",
                ):
                    evaluate_trace_document(raw)

    def test_module_cli_failure_is_canonical_stderr_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_path = Path(directory) / "workflow-trace.json"
            trace_path.write_bytes(b'{"schema":"a","schema":"b"}\n')
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "scripts.quick_collection_trace_cli",
                    str(trace_path),
                ],
                check=False,
                capture_output=True,
            )

        self.assertEqual(1, completed.returncode)
        self.assertEqual(b"", completed.stdout)
        self.assertEqual(
            (
                b'{"reason_code":"workflow_trace_document_invalid",'
                b'"schema":"aneb-quick-workflow-cli-error@1.0.0"}\n'
            ),
            completed.stderr,
        )


if __name__ == "__main__":
    unittest.main()
