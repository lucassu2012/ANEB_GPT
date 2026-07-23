from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.verify_token_quick_negative_proxy_evidence import (
    NegativeProxyEvidenceFailure,
    verify,
)


RUN_ID = "019f731f-602a-72b3-abeb-85afa315e0f0"
UPSTREAM_URL = "https://203.0.113.10:8443/api/v1/serverinfo"
CA_SHA256 = "a" * 64
PEER_SHA256 = "b" * 64
DEVICE_PORT = 18765
HOST_PORT = 45678
DEVICE_SERIAL = "ABC123"


def canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


class EvidenceFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.proxy = root / "negative-proxy"
        self.proxy.mkdir(parents=True)
        self.upstream = {
            "version": "aneb-server/0.8.0",
            "h3_enabled": True,
            "nested": {"execution_capabilities": {"preserved": True}},
            "execution_capabilities": {
                "contract_id": "aneb-execution-receipt-v1",
                "primitives": ["echo", "download"],
            },
        }
        self.upstream_raw = (
            b'{"version":"aneb-server/0.8.0","h3_enabled":true,'
            b'"nested":{"execution_capabilities":{"preserved":true}},'
            b'"execution_capabilities":{"contract_id":'
            b'"aneb-execution-receipt-v1","primitives":["echo","download"]}}'
        )
        self.filtered = dict(self.upstream)
        del self.filtered["execution_capabilities"]
        self.filtered_raw = canonical_json(self.filtered)
        self.headers = [
            ["Content-Type", "application/json"],
            ["X-Aneb-Proto", "h2"],
        ]
        self.headers_raw = canonical_json(self.headers)
        self.header_bytes = sum(
            len(name.encode("utf-8")) + len(value.encode("utf-8")) + 4
            for name, value in self.headers
        )
        self.ledger = {
            "schema": "aneb-token-serverinfo-negative-proxy-ledger",
            "schema_version": "1.0.0",
            "counts": {
                "accepted_requests": 1,
                "forbidden_requests": 0,
                "upstream_requests": 1,
            },
            "request": {
                "audit_role": "capability",
                "method": "GET",
                "path": "/api/v1/serverinfo",
                "run_id": RUN_ID,
            },
            "upstream": {
                "body_bytes": len(self.upstream_raw),
                "header_bytes": self.header_bytes,
                "status": 200,
                "url": UPSTREAM_URL,
            },
        }
        self.receipt = {
            "schema": "aneb-token-serverinfo-negative-proxy-receipt",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "run_id": RUN_ID,
            "upstream_url": UPSTREAM_URL,
            "upstream_body_bytes": len(self.upstream_raw),
            "upstream_body_sha256": hashlib.sha256(self.upstream_raw).hexdigest(),
            "filtered_body_bytes": len(self.filtered_raw),
            "filtered_body_sha256": hashlib.sha256(self.filtered_raw).hexdigest(),
            "peer_certificate_sha256": PEER_SHA256,
            "ca_file_sha256": CA_SHA256,
            "evidence_scope": "upstream_fetch_and_filter_only",
            "client_delivery_proven": False,
        }
        self.write()

    def write(self) -> None:
        (self.proxy / "upstream-serverinfo.raw").write_bytes(self.upstream_raw)
        (self.proxy / "filtered-serverinfo.json").write_bytes(self.filtered_raw)
        (self.proxy / "upstream-serverinfo.headers.json").write_bytes(
            self.headers_raw
        )
        (self.proxy / "peer-certificate.sha256").write_bytes(
            (PEER_SHA256 + "\n").encode("ascii")
        )
        (self.proxy / "request-ledger.json").write_bytes(
            canonical_json(self.ledger)
        )
        (self.proxy / "proxy-receipt.json").write_bytes(
            canonical_json(self.receipt)
        )
        ready = {
            "listen_host": "127.0.0.1",
            "listen_port": HOST_PORT,
            "status": "ready",
        }
        passed = {
            "listen_host": "127.0.0.1",
            "listen_port": HOST_PORT,
            "reason_code": "ok",
            "run_id": RUN_ID,
            "status": "pass",
        }
        (self.root / "negative-proxy.stdout.jsonl").write_bytes(
            canonical_json(ready) + b"\n" + canonical_json(passed) + b"\n"
        )
        (self.root / "negative-proxy.stderr.txt").write_bytes(b"")
        mapping = (
            f"{DEVICE_SERIAL} tcp:{DEVICE_PORT} tcp:{HOST_PORT}\n".encode("ascii")
        )
        (self.root / "adb-reverse-preflight.txt").write_bytes(b"\n")
        (self.root / "adb-reverse-active.txt").write_bytes(mapping)
        (self.root / "adb-reverse-before-remove.txt").write_bytes(mapping)
        (self.root / "adb-reverse-final.txt").write_bytes(b"\n")

    def verify(self) -> dict[str, object]:
        return verify(
            self.root,
            run_id=RUN_ID,
            upstream_url=UPSTREAM_URL,
            ca_file_sha256=CA_SHA256,
            device_port=DEVICE_PORT,
        )


class NegativeProxyEvidenceVerifierTests(unittest.TestCase):
    def test_accepts_exact_cross_bound_negative_proxy_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            report = EvidenceFixture(Path(temporary)).verify()

        self.assertEqual(
            {
                "schema",
                "schema_version",
                "status",
                "reason_code",
                "run_id",
                "upstream_url",
                "upstream_body_sha256",
                "filtered_body_sha256",
                "upstream_headers_sha256",
                "peer_certificate_sha256",
                "ca_file_sha256",
                "request_ledger_sha256",
                "proxy_receipt_sha256",
                "proxy_stdout_sha256",
                "reverse_evidence_sha256",
                "adb_serial_sha256",
                "device_port",
                "host_port",
                "client_delivery_proven",
                "raw_files_verified",
            },
            set(report),
        )
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertEqual(UPSTREAM_URL, report["upstream_url"])
        self.assertEqual(CA_SHA256, report["ca_file_sha256"])
        self.assertEqual(PEER_SHA256, report["peer_certificate_sha256"])
        self.assertEqual(DEVICE_PORT, report["device_port"])
        self.assertEqual(HOST_PORT, report["host_port"])
        self.assertIs(False, report["client_delivery_proven"])
        self.assertEqual(12, report["raw_files_verified"])

    def test_accepts_windows_crlf_from_redirected_python_stdout(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            stdout_path = fixture.root / "negative-proxy.stdout.jsonl"
            stdout_path.write_bytes(stdout_path.read_bytes().replace(b"\n", b"\r\n"))

            report = fixture.verify()

        self.assertEqual(HOST_PORT, report["host_port"])

    def test_rejects_upstream_serverinfo_without_execution_capabilities(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            upstream = dict(fixture.filtered)
            fixture.upstream_raw = canonical_json(upstream)
            fixture.filtered_raw = canonical_json(upstream)
            fixture.ledger["upstream"]["body_bytes"] = len(fixture.upstream_raw)
            fixture.receipt["upstream_body_bytes"] = len(fixture.upstream_raw)
            fixture.receipt["upstream_body_sha256"] = hashlib.sha256(
                fixture.upstream_raw
            ).hexdigest()
            fixture.receipt["filtered_body_bytes"] = len(fixture.filtered_raw)
            fixture.receipt["filtered_body_sha256"] = hashlib.sha256(
                fixture.filtered_raw
            ).hexdigest()
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_execution_capabilities_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_filtered_serverinfo_that_removes_one_extra_top_level_key(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            filtered = dict(fixture.filtered)
            del filtered["h3_enabled"]
            fixture.filtered_raw = canonical_json(filtered)
            fixture.receipt["filtered_body_bytes"] = len(fixture.filtered_raw)
            fixture.receipt["filtered_body_sha256"] = hashlib.sha256(
                fixture.filtered_raw
            ).hexdigest()
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_filtered_serverinfo_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_filtered_serverinfo_that_keeps_execution_capabilities(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.filtered_raw = canonical_json(fixture.upstream)
            fixture.receipt["filtered_body_bytes"] = len(fixture.filtered_raw)
            fixture.receipt["filtered_body_sha256"] = hashlib.sha256(
                fixture.filtered_raw
            ).hexdigest()
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_execution_capabilities_not_removed",
            raised.exception.reason_code,
        )

    def test_rejects_tampered_upstream_body_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.receipt["upstream_body_sha256"] = "0" * 64
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_upstream_body_digest_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_receipt_with_a_different_ca_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.receipt["ca_file_sha256"] = "c" * 64
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_ca_sha256_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_receipt_bound_to_a_different_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.receipt["run_id"] = "019f731f-602a-72b3-abeb-85afa315e0f1"
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_run_id_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_receipt_bound_to_a_different_upstream_url(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.receipt["upstream_url"] = (
                "https://203.0.113.11:8443/api/v1/serverinfo"
            )
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_upstream_url_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_ledger_boolean_substituted_for_request_count(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.ledger["counts"]["accepted_requests"] = True
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_ledger_binding_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_receipt_that_claims_client_delivery(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.receipt["client_delivery_proven"] = True
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_client_delivery_claim_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_any_third_stdout_line(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            with (fixture.root / "negative-proxy.stdout.jsonl").open("ab") as stream:
                stream.write(b"{}\n")

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_stdout_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_stdout_with_a_different_pass_port(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            ready = {
                "listen_host": "127.0.0.1",
                "listen_port": HOST_PORT,
                "status": "ready",
            }
            passed = {
                "listen_host": "127.0.0.1",
                "listen_port": HOST_PORT + 1,
                "reason_code": "ok",
                "run_id": RUN_ID,
                "status": "pass",
            }
            (fixture.root / "negative-proxy.stdout.jsonl").write_bytes(
                canonical_json(ready) + b"\n" + canonical_json(passed) + b"\n"
            )

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_stdout_binding_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_reverse_evidence_with_an_extra_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            active = fixture.root / "adb-reverse-active.txt"
            active.write_bytes(
                active.read_bytes() + b"ABC123 tcp:18766 tcp:45679\n"
            )

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_reverse_active_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_reverse_host_port_not_bound_to_proxy_stdout(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            changed = (
                f"{DEVICE_SERIAL} tcp:{DEVICE_PORT} tcp:{HOST_PORT + 1}\n".encode(
                    "ascii"
                )
            )
            (fixture.root / "adb-reverse-active.txt").write_bytes(changed)
            (fixture.root / "adb-reverse-before-remove.txt").write_bytes(changed)

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_reverse_binding_mismatch",
            raised.exception.reason_code,
        )

    def test_requires_reverse_preflight_and_final_to_be_exactly_empty(self) -> None:
        cases = (
            (
                "adb-reverse-preflight.txt",
                "negative_proxy_reverse_preflight_not_empty",
            ),
            ("adb-reverse-final.txt", "negative_proxy_reverse_final_not_empty"),
        )
        for name, reason in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                fixture = EvidenceFixture(Path(temporary))
                (fixture.root / name).write_bytes(
                    f"{DEVICE_SERIAL} tcp:{DEVICE_PORT} tcp:{HOST_PORT}\n".encode(
                        "ascii"
                    )
                )

                with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                    fixture.verify()

                self.assertEqual(reason, raised.exception.reason_code)

    def test_rejects_nonempty_proxy_stderr(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            (fixture.root / "negative-proxy.stderr.txt").write_bytes(b"warning\n")

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_stderr_not_empty",
            raised.exception.reason_code,
        )

    def test_rejects_any_extra_receipt_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.receipt["unbound_claim"] = True
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_receipt_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_any_extra_ledger_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            fixture.ledger["unbound_counter"] = 0
            fixture.write()

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_ledger_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_any_extra_stdout_document_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            ready = {
                "listen_host": "127.0.0.1",
                "listen_port": HOST_PORT,
                "status": "ready",
                "extra": None,
            }
            passed = {
                "listen_host": "127.0.0.1",
                "listen_port": HOST_PORT,
                "reason_code": "ok",
                "run_id": RUN_ID,
                "status": "pass",
            }
            (fixture.root / "negative-proxy.stdout.jsonl").write_bytes(
                canonical_json(ready) + b"\n" + canonical_json(passed) + b"\n"
            )

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_stdout_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_header_bytes_not_bound_to_the_ledger(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            changed_headers = fixture.headers + [["X-Added", "tampered"]]
            (fixture.proxy / "upstream-serverinfo.headers.json").write_bytes(
                canonical_json(changed_headers)
            )

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_ledger_binding_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_peer_certificate_digest_not_bound_to_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            (fixture.proxy / "peer-certificate.sha256").write_bytes(
                ("c" * 64 + "\n").encode("ascii")
            )

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_peer_certificate_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_non_loopback_proxy_stdout(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            ready = {
                "listen_host": "0.0.0.0",
                "listen_port": HOST_PORT,
                "status": "ready",
            }
            passed = {
                "listen_host": "0.0.0.0",
                "listen_port": HOST_PORT,
                "reason_code": "ok",
                "run_id": RUN_ID,
                "status": "pass",
            }
            (fixture.root / "negative-proxy.stdout.jsonl").write_bytes(
                canonical_json(ready) + b"\n" + canonical_json(passed) + b"\n"
            )

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_stdout_binding_mismatch",
            raised.exception.reason_code,
        )

    def test_rejects_callers_that_replace_the_fixed_device_port(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                verify(
                    fixture.root,
                    run_id=RUN_ID,
                    upstream_url=UPSTREAM_URL,
                    ca_file_sha256=CA_SHA256,
                    device_port=DEVICE_PORT + 1,
                )

        self.assertEqual(
            "negative_proxy_device_port_invalid",
            raised.exception.reason_code,
        )

    def test_rejects_reverse_mapping_changed_before_remove(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = EvidenceFixture(Path(temporary))
            (fixture.root / "adb-reverse-before-remove.txt").write_bytes(
                f"{DEVICE_SERIAL} tcp:{DEVICE_PORT} tcp:{HOST_PORT + 1}\n".encode(
                    "ascii"
                )
            )

            with self.assertRaises(NegativeProxyEvidenceFailure) as raised:
                fixture.verify()

        self.assertEqual(
            "negative_proxy_reverse_binding_mismatch",
            raised.exception.reason_code,
        )


if __name__ == "__main__":
    unittest.main()
