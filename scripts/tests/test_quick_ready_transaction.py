from __future__ import annotations

import json
from pathlib import Path
import re
import tempfile
import unittest

from scripts import quick_ready_transaction as transaction
from scripts.quick_ready_transaction import (
    QuickReadyFailure,
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


class FakeTokenModeAdapter(FakeQuickAdapter):
    def verify_collection(self, bundle: Path) -> dict[str, object]:
        self.verify_calls += 1
        return {
            "schema": "aneb-test-quick-collection-verification",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "collection_id": COLLECTION,
            "run_id": RUN_ID,
            "execution_mode": "negative_receipt_missing",
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

    def test_publishes_ready_from_a_preverified_report_without_rewriting_it(self) -> None:
        contract = self.contract()
        adapter = FakeQuickAdapter()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / f"{COLLECTION}.complete"
            bundle.mkdir()
            report_path = root / f"{COLLECTION}.verification.json"
            report_raw = (
                json.dumps(
                    {
                        "schema": "aneb-test-quick-collection-verification",
                        "schema_version": "1.0.0",
                        "status": "pass",
                        "reason_code": "ok",
                        "collection_id": COLLECTION,
                        "run_id": RUN_ID,
                        "mode": "positive",
                        "manifest_sha256": "b" * 64,
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            ).encode("utf-8")
            report_path.write_bytes(report_raw)

            def postcheck(ready: Path) -> dict[str, object]:
                marker = json.loads(ready.read_text(encoding="utf-8"))
                return {
                    "status": "pass",
                    "collection_id": marker["collection_id"],
                    "ready_sha256": "c" * 64,
                }

            result = transaction.publish_preverified_ready(
                bundle,
                report_path,
                contract=contract,
                adapter=adapter,
                release_postcheck=postcheck,
            )

            self.assertEqual(report_raw, report_path.read_bytes())
            self.assertTrue((root / f"{COLLECTION}.READY.json").is_file())

        self.assertEqual(0, adapter.verify_calls)
        self.assertEqual("c" * 64, result["ready_sha256"])

    def test_preverified_postcheck_failure_rolls_back_only_ready(self) -> None:
        contract = self.contract()
        adapter = FakeQuickAdapter()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / f"{COLLECTION}.complete"
            bundle.mkdir()
            report_path = root / f"{COLLECTION}.verification.json"
            report_raw = (
                json.dumps(
                    {
                        "schema": "aneb-test-quick-collection-verification",
                        "schema_version": "1.0.0",
                        "status": "pass",
                        "reason_code": "ok",
                        "collection_id": COLLECTION,
                        "run_id": RUN_ID,
                        "mode": "positive",
                        "manifest_sha256": "b" * 64,
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n"
            ).encode("utf-8")
            report_path.write_bytes(report_raw)

            def interrupt(_: Path) -> dict[str, object]:
                raise KeyboardInterrupt

            with self.assertRaises(KeyboardInterrupt):
                transaction.publish_preverified_ready(
                    bundle,
                    report_path,
                    contract=contract,
                    adapter=adapter,
                    release_postcheck=interrupt,
                )

            self.assertEqual(report_raw, report_path.read_bytes())
            self.assertFalse((root / f"{COLLECTION}.READY.json").exists())
            self.assertFalse((root / f"{COLLECTION}.ready.partial").exists())

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

    def test_supports_family_specific_mode_field_and_values(self) -> None:
        base = self.contract()
        contract = QuickReadyContract(
            collection_pattern=base.collection_pattern,
            ready_pattern=base.ready_pattern,
            release_schema=base.release_schema,
            release_version=base.release_version,
            verification_schema=base.verification_schema,
            verification_version=base.verification_version,
            publication_schema=base.publication_schema,
            collection_report_schema=base.collection_report_schema,
            collection_report_version=base.collection_report_version,
            mode_field="execution_mode",
            mode_values=frozenset({"positive", "negative_receipt_missing"}),
        )
        adapter = FakeTokenModeAdapter()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / f"{COLLECTION}.complete"
            bundle.mkdir()

            result = publish_ready(bundle, contract=contract, adapter=adapter)

            marker = json.loads(
                (root / f"{COLLECTION}.READY.json").read_text(encoding="utf-8")
            )

        self.assertNotIn("mode", marker)
        self.assertEqual(
            "negative_receipt_missing",
            marker["execution_mode"],
        )
        self.assertNotIn("mode", result)
        self.assertEqual(
            "negative_receipt_missing",
            result["execution_mode"],
        )

    def test_rejects_a_mode_field_that_collides_with_ready_identity(self) -> None:
        base = self.contract()
        with self.assertRaisesRegex(
            ValueError,
            "quick_ready_mode_contract_invalid",
        ):
            QuickReadyContract(
                collection_pattern=base.collection_pattern,
                ready_pattern=base.ready_pattern,
                release_schema=base.release_schema,
                release_version=base.release_version,
                verification_schema=base.verification_schema,
                verification_version=base.verification_version,
                publication_schema=base.publication_schema,
                collection_report_schema=base.collection_report_schema,
                collection_report_version=base.collection_report_version,
                mode_field="schema",
            )

    def test_rejects_an_unapproved_family_mode_and_rolls_back(self) -> None:
        base = self.contract()
        contract = QuickReadyContract(
            collection_pattern=base.collection_pattern,
            ready_pattern=base.ready_pattern,
            release_schema=base.release_schema,
            release_version=base.release_version,
            verification_schema=base.verification_schema,
            verification_version=base.verification_version,
            publication_schema=base.publication_schema,
            collection_report_schema=base.collection_report_schema,
            collection_report_version=base.collection_report_version,
            mode_field="execution_mode",
            mode_values=frozenset({"positive", "negative_receipt_missing"}),
        )

        class InvalidModeAdapter(FakeTokenModeAdapter):
            def verify_collection(self, bundle: Path) -> dict[str, object]:
                report = super().verify_collection(bundle)
                report["execution_mode"] = "negative_unknown"
                return report

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / f"{COLLECTION}.complete"
            bundle.mkdir()
            with self.assertRaisesRegex(
                QuickReadyFailure,
                "release_ready_contract_invalid",
            ):
                publish_ready(
                    bundle,
                    contract=contract,
                    adapter=InvalidModeAdapter(),
                )

            self.assertTrue(bundle.is_dir())
            self.assertFalse((root / f"{COLLECTION}.verification.json").exists())
            self.assertFalse((root / f"{COLLECTION}.READY.json").exists())


if __name__ == "__main__":
    unittest.main()
