from __future__ import annotations

import json
from pathlib import Path
import re
import tempfile
import unittest

from scripts.quick_ready_transaction import (
    QuickReadyContract,
    publish_ready,
)


COLLECTION = "m0-test-quick-20260727T000000Z-" + "a" * 32
RUN_ID = "019fa3aa-c0d5-7586-b736-ae2fe0a35c78"


class FakeQuickAdapter:
    def __init__(self) -> None:
        self.verify_calls = 0

    def verify_private_root(self, bundle: Path) -> None:
        if not bundle.is_dir():
            raise AssertionError("bundle missing")

    def verify_collection(self, bundle: Path) -> dict[str, object]:
        self.verify_calls += 1
        return {
            "schema": "aneb-test-quick-collection-verification",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "collection_id": COLLECTION,
            "run_id": RUN_ID,
            "mode": "positive",
            "manifest_sha256": "b" * 64,
        }


class QuickReadyTransactionTests(unittest.TestCase):
    def contract(self) -> QuickReadyContract:
        return QuickReadyContract(
            collection_pattern=re.compile(
                r"^(?P<collection>m0-test-quick-[0-9]{8}T[0-9]{6}Z-"
                r"[0-9a-f]{32})\.complete$"
            ),
            ready_pattern=re.compile(
                r"^(?P<collection>m0-test-quick-[0-9]{8}T[0-9]{6}Z-"
                r"[0-9a-f]{32})\.READY\.json$"
            ),
            release_schema="aneb-test-quick-evidence-release",
            release_version="1.0.0",
            verification_schema="aneb-test-quick-release-verification",
            verification_version="1.0.0",
            publication_schema="aneb-test-quick-ready-publication",
            collection_report_schema="aneb-test-quick-collection-verification",
            collection_report_version="1.0.0",
        )

    def test_publishes_report_then_digest_bound_ready_and_revalidates(self) -> None:
        contract = self.contract()
        adapter = FakeQuickAdapter()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / f"{COLLECTION}.complete"
            bundle.mkdir()

            result = publish_ready(bundle, contract=contract, adapter=adapter)

            report = root / f"{COLLECTION}.verification.json"
            ready = root / f"{COLLECTION}.READY.json"
            marker = json.loads(ready.read_text(encoding="utf-8"))
            self.assertTrue(report.is_file())

        self.assertEqual("aneb-test-quick-evidence-release", marker["schema"])
        self.assertEqual(COLLECTION, marker["collection_id"])
        self.assertEqual(RUN_ID, marker["run_id"])
        self.assertEqual("positive", marker["mode"])
        self.assertEqual(str(ready), result["ready_path"])
        self.assertRegex(result["ready_sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(2, adapter.verify_calls)

    def test_operator_interrupt_propagates_after_new_artifacts_are_removed(self) -> None:
        adapter = FakeQuickAdapter()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / f"{COLLECTION}.complete"
            bundle.mkdir()

            def interrupt(_: Path) -> dict[str, object]:
                raise KeyboardInterrupt

            with self.assertRaises(KeyboardInterrupt):
                publish_ready(
                    bundle,
                    contract=self.contract(),
                    adapter=adapter,
                    release_postcheck=interrupt,
                )

            self.assertTrue(bundle.is_dir())
            self.assertFalse((root / f"{COLLECTION}.verification.json").exists())
            self.assertFalse((root / f"{COLLECTION}.READY.json").exists())
            self.assertFalse((root / f"{COLLECTION}.verification.partial").exists())
            self.assertFalse((root / f"{COLLECTION}.ready.partial").exists())

    def test_collection_interrupt_is_not_rewritten_as_a_business_failure(self) -> None:
        class InterruptedAdapter(FakeQuickAdapter):
            def verify_collection(self, bundle: Path) -> dict[str, object]:
                raise KeyboardInterrupt

        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / f"{COLLECTION}.complete"
            bundle.mkdir()
            with self.assertRaises(KeyboardInterrupt):
                publish_ready(
                    bundle,
                    contract=self.contract(),
                    adapter=InterruptedAdapter(),
                )


if __name__ == "__main__":
    unittest.main()
