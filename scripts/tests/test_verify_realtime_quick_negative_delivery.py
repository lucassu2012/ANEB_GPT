from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.tests.test_verify_token_quick_negative_proxy_evidence import (
    CA_SHA256,
    DEVICE_PORT,
    EvidenceFixture,
    RUN_ID,
    UPSTREAM_URL,
)
from scripts.verify_realtime_quick_negative_delivery import (
    NegativeDeliveryEvidenceFailure,
    verify,
)


def canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def write_delivery_receipt(fixture: EvidenceFixture) -> Path:
    path = fixture.root / "negative-proxy-delivery-receipt.json"
    path.write_bytes(
        canonical_json(
            {
                "schema": "aneb-serverinfo-negative-proxy-delivery-receipt",
                "schema_version": "1.0.0",
                "status": "pass",
                "reason_code": "ok",
                "run_id": RUN_ID,
                "response_status": 200,
                "response_body_bytes": len(fixture.filtered_raw),
                "response_body_sha256": hashlib.sha256(
                    fixture.filtered_raw
                ).hexdigest(),
                "response_write_completed": True,
                "evidence_scope": "proxy_response_write_completed",
            }
        )
    )
    return path


class RealtimeQuickNegativeDeliveryVerifierTests(unittest.TestCase):
    def test_accepts_proxy_write_receipt_bound_to_filtered_body(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            write_delivery_receipt(fixture)
            report = verify(
                fixture.root,
                run_id=RUN_ID,
                upstream_url=UPSTREAM_URL,
                ca_file_sha256=CA_SHA256,
                device_port=DEVICE_PORT,
            )

        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertTrue(report["proxy_response_write_completed"])
        self.assertEqual(
            hashlib.sha256(fixture.filtered_raw).hexdigest(),
            report["filtered_body_sha256"],
        )
        self.assertEqual(13, report["raw_files_verified"])

    def test_rejects_delivery_receipt_bound_to_a_different_body(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            path = write_delivery_receipt(fixture)
            receipt = json.loads(path.read_bytes())
            receipt["response_body_sha256"] = "f" * 64
            path.write_bytes(canonical_json(receipt))

            with self.assertRaises(NegativeDeliveryEvidenceFailure) as raised:
                verify(
                    fixture.root,
                    run_id=RUN_ID,
                    upstream_url=UPSTREAM_URL,
                    ca_file_sha256=CA_SHA256,
                    device_port=DEVICE_PORT,
                )

        self.assertEqual(
            "negative_delivery_body_binding_mismatch",
            raised.exception.reason_code,
        )


if __name__ == "__main__":
    unittest.main()
