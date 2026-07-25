from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.tests.test_verify_realtime_quick_client_db import (
    PROFILE_SHA,
    RUN_ID,
    negative_body,
    valid_body,
    write_database,
)


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify_realtime_quick_evidence_bundle.py"
CLIENT = ROOT / "scripts" / "verify_realtime_quick_client_db.py"
AUDIT = ROOT / "scripts" / "verify_realtime_quick_run_audit.py"
START = "11111111-1111-4111-8111-111111111111"
END = "22222222-2222-4222-8222-222222222222"
INSTANCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
UPSTREAM_URL = "https://203.0.113.10:8443/api/v1/serverinfo"
CA_SHA256 = "a" * 64
PEER_SHA256 = "b" * 64
DEVICE_PORT = 18765
HOST_PORT = 45678


def audit_line(
    seq: int,
    *,
    class_: str = "control",
    method: str = "GET",
    path: str = "/api/v1/serverinfo",
    role: str = "capability",
    run_id: str = RUN_ID,
) -> str:
    return (
        f"ANEB_REQUEST_AUDIT instance_id={INSTANCE} seq={seq} "
        f"class={class_} method={method} path={path} role={role} "
        f"scope=realtime_run run_id={run_id}"
    )


def positive_journal(echo_count: int = 3) -> str:
    lines = [audit_line(100, role="window_start", run_id=START)]
    lines.append(audit_line(101))
    lines.append(
        audit_line(
            102,
            class_="business",
            path="/api/v1/realtime-sim",
            role="none",
        )
    )
    for index in range(echo_count):
        lines.append(
            audit_line(
                103 + index,
                class_="business",
                method="POST",
                path="/api/v1/echo",
                role="none",
            )
        )
    lines.append(
        f"ANEB_REALTIME_SUMMARY instance_id={INSTANCE} run_id={RUN_ID} "
        "sessions=1 turns=3 uplink_frames=400 downlink_frames=676 "
        "interrupted_turns=1 protocol_ok=true"
    )
    lines.append(audit_line(103 + echo_count, role="window_end", run_id=END))
    return "\n".join(lines) + "\n"


def negative_journal() -> str:
    return "\n".join(
        (
            audit_line(100, role="window_start", run_id=START),
            audit_line(101),
            audit_line(102, role="window_end", run_id=END),
            "",
        )
    )


def serverinfo() -> dict[str, object]:
    return {
        "version": "aneb-server/0.8.1",
        "srv_ts_us": 1721370000000001,
        "anchor_wall_unix_ns": 1721370000000000000,
        "uptime_s": 1000,
        "goos": "linux",
        "goarch": "amd64",
        "h3_enabled": True,
        "tcp_slow_start_after_idle": "0",
        "congestion_control": "cubic",
        "execution_capabilities": {
            "contract_id": "aneb-server-capability-receipt",
            "contract_version": "1.0.0",
            "primitives": [
                {
                    "primitive_id": "download",
                    "wire_contract_id": "aneb-download-v1",
                },
                {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
                {
                    "primitive_id": "realtime_sim",
                    "wire_contract_id": "aneb-realtime-session-v1",
                },
                {
                    "primitive_id": "token_sim",
                    "wire_contract_id": "aneb-token-task-v1",
                },
            ],
            "validated_profiles": [
                {
                    "profile_id": "ai_realtime_voice_quick",
                    "profile_version": "1.1.1",
                    "profile_sha256": f"sha256:{PROFILE_SHA}",
                },
                {
                    "profile_id": "token_multimodal_quick",
                    "profile_version": "1.2.1",
                    "profile_sha256": (
                        "sha256:"
                        "caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773"
                    ),
                },
            ],
        },
    }


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, sort_keys=True, separators=(",", ":")),
        encoding="utf-8",
    )


def write_negative_proxy_evidence(root: Path, upstream: dict[str, object]) -> None:
    proxy = root / "negative-proxy"
    proxy.mkdir()
    upstream_raw = json.dumps(
        upstream,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    filtered = dict(upstream)
    del filtered["execution_capabilities"]
    filtered_raw = json.dumps(
        filtered,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    headers = [["Content-Type", "application/json"]]
    headers_raw = json.dumps(
        headers,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    header_bytes = sum(
        len(name.encode("utf-8")) + len(value.encode("utf-8")) + 4
        for name, value in headers
    )
    ledger = {
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
            "body_bytes": len(upstream_raw),
            "header_bytes": header_bytes,
            "status": 200,
            "url": UPSTREAM_URL,
        },
    }
    receipt = {
        "schema": "aneb-token-serverinfo-negative-proxy-receipt",
        "schema_version": "1.0.0",
        "status": "pass",
        "reason_code": "ok",
        "run_id": RUN_ID,
        "upstream_url": UPSTREAM_URL,
        "upstream_body_bytes": len(upstream_raw),
        "upstream_body_sha256": hashlib.sha256(upstream_raw).hexdigest(),
        "filtered_body_bytes": len(filtered_raw),
        "filtered_body_sha256": hashlib.sha256(filtered_raw).hexdigest(),
        "peer_certificate_sha256": PEER_SHA256,
        "ca_file_sha256": CA_SHA256,
        "evidence_scope": "upstream_fetch_and_filter_only",
        "client_delivery_proven": False,
    }
    (proxy / "upstream-serverinfo.raw").write_bytes(upstream_raw)
    (proxy / "filtered-serverinfo.json").write_bytes(filtered_raw)
    (proxy / "upstream-serverinfo.headers.json").write_bytes(headers_raw)
    (proxy / "peer-certificate.sha256").write_bytes(
        (PEER_SHA256 + "\n").encode("ascii"),
    )
    (proxy / "request-ledger.json").write_bytes(
        json.dumps(ledger, sort_keys=True, separators=(",", ":")).encode("utf-8"),
    )
    (proxy / "proxy-receipt.json").write_bytes(
        json.dumps(receipt, sort_keys=True, separators=(",", ":")).encode("utf-8"),
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
    (root / "negative-proxy.stdout.jsonl").write_bytes(
        (
            json.dumps(ready, sort_keys=True, separators=(",", ":"))
            + "\n"
            + json.dumps(passed, sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode("utf-8"),
    )
    (root / "negative-proxy.stderr.txt").write_bytes(b"")
    mapping = f"UsbFfs tcp:{DEVICE_PORT} tcp:{HOST_PORT}\n".encode("ascii")
    (root / "adb-reverse-preflight.txt").write_bytes(b"\n")
    (root / "adb-reverse-active.txt").write_bytes(mapping)
    (root / "adb-reverse-before-remove.txt").write_bytes(mapping)
    (root / "adb-reverse-final.txt").write_bytes(b"\n")
    write_json(
        root / "negative-proxy-delivery-receipt.json",
        {
            "schema": "aneb-serverinfo-negative-proxy-delivery-receipt",
            "schema_version": "1.0.0",
            "status": "pass",
            "reason_code": "ok",
            "run_id": RUN_ID,
            "response_status": 200,
            "response_body_bytes": len(filtered_raw),
            "response_body_sha256": hashlib.sha256(filtered_raw).hexdigest(),
            "response_write_completed": True,
            "evidence_scope": "proxy_response_write_completed",
        },
    )


class RealtimeQuickEvidenceBundleVerifierTests(unittest.TestCase):
    def build_positive_inputs(self, root: Path) -> dict[str, Path]:
        database = root / "aneb-probe.db"
        client_result = root / "client-result.json"
        client_report = root / "client-report.json"
        audit_report = root / "audit-report.json"
        journal = root / "journal.log"
        serverinfo_path = root / "serverinfo.json"
        write_database(database, valid_body())
        client_completed = subprocess.run(
            [
                sys.executable,
                str(CLIENT),
                str(database),
                "--run-id",
                RUN_ID,
                "--mode",
                "positive",
                "--result-output",
                str(client_result),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )
        self.assertEqual(
            0,
            client_completed.returncode,
            client_completed.stdout + client_completed.stderr,
        )
        client_report.write_text(client_completed.stdout, encoding="utf-8")
        journal.write_text(positive_journal(), encoding="utf-8")
        audit_completed = subprocess.run(
            [
                sys.executable,
                str(AUDIT),
                str(journal),
                "--run-id",
                RUN_ID,
                "--start-barrier-id",
                START,
                "--barrier-id",
                END,
                "--mode",
                "positive",
                "--profile-contract",
                "ai_realtime_voice_quick@1.1.1",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )
        self.assertEqual(
            0,
            audit_completed.returncode,
            audit_completed.stdout + audit_completed.stderr,
        )
        audit_report.write_text(audit_completed.stdout, encoding="utf-8")
        write_json(serverinfo_path, serverinfo())
        return {
            "client_database": database,
            "client_report": client_report,
            "client_result": client_result,
            "audit_report": audit_report,
            "journal": journal,
            "serverinfo": serverinfo_path,
        }

    def build_negative_inputs(self, root: Path) -> dict[str, Path]:
        database = root / "aneb-probe.db"
        client_result = root / "client-result.json"
        client_report = root / "client-report.json"
        audit_report = root / "audit-report.json"
        journal = root / "journal.log"
        serverinfo_path = root / "serverinfo.json"
        negative = negative_body()
        negative["context"]["endpoint"]["server_base"] = (
            f"http://127.0.0.1:{DEVICE_PORT}"
        )
        write_database(database, negative)
        client_completed = subprocess.run(
            [
                sys.executable,
                str(CLIENT),
                str(database),
                "--run-id",
                RUN_ID,
                "--mode",
                "negative",
                "--result-output",
                str(client_result),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )
        self.assertEqual(
            0,
            client_completed.returncode,
            client_completed.stdout + client_completed.stderr,
        )
        client_report.write_text(client_completed.stdout, encoding="utf-8")
        journal.write_text(negative_journal(), encoding="utf-8")
        audit_completed = subprocess.run(
            [
                sys.executable,
                str(AUDIT),
                str(journal),
                "--run-id",
                RUN_ID,
                "--start-barrier-id",
                START,
                "--barrier-id",
                END,
                "--mode",
                "negative",
                "--profile-contract",
                "ai_realtime_voice_quick@1.1.1",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )
        self.assertEqual(
            0,
            audit_completed.returncode,
            audit_completed.stdout + audit_completed.stderr,
        )
        audit_report.write_text(audit_completed.stdout, encoding="utf-8")
        upstream = serverinfo()
        write_json(serverinfo_path, upstream)
        write_negative_proxy_evidence(root, upstream)
        return {
            "client_database": database,
            "client_report": client_report,
            "client_result": client_result,
            "audit_report": audit_report,
            "journal": journal,
            "serverinfo": serverinfo_path,
            "negative_proxy_bundle": root,
        }

    def run_verifier(
        self,
        inputs: dict[str, Path],
        *,
        mode: str = "positive",
    ) -> subprocess.CompletedProcess[str]:
        command = [
            sys.executable,
            str(SCRIPT),
            "--mode",
            mode,
            "--client-database",
            str(inputs["client_database"]),
            "--client-report",
            str(inputs["client_report"]),
            "--client-result",
            str(inputs["client_result"]),
            "--audit-report",
            str(inputs["audit_report"]),
            "--journal",
            str(inputs["journal"]),
            "--start-barrier-id",
            START,
            "--barrier-id",
            END,
            "--serverinfo",
            str(inputs["serverinfo"]),
        ]
        if mode == "negative":
            command.extend(
                (
                    "--negative-proxy-bundle",
                    str(inputs["negative_proxy_bundle"]),
                    "--negative-upstream-url",
                    UPSTREAM_URL,
                    "--negative-ca-file-sha256",
                    CA_SHA256,
                    "--negative-device-port",
                    str(DEVICE_PORT),
                )
            )
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )

    def test_accepts_cross_bound_positive_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = self.build_positive_inputs(Path(temporary))
            completed = self.run_verifier(inputs)

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual(RUN_ID, report["run_id"])
        self.assertEqual("aneb-server/0.8.1", report["server_version"])
        self.assertEqual(3, report["loaded_rtt_attempts"])
        self.assertEqual(3, report["server_echo_entries"])
        self.assertTrue(report["cross_bound"])
        self.assertRegex(report["client_result_sha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(report["serverinfo_sha256"], r"^[0-9a-f]{64}$")

    def test_accepts_cross_bound_receipt_missing_negative_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = self.build_negative_inputs(Path(temporary))
            completed = self.run_verifier(inputs, mode="negative")

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("negative", report["mode"])
        self.assertTrue(report["cross_bound"])
        self.assertTrue(report["proxy_response_write_completed"])
        self.assertTrue(report["client_receipt_missing_bound"])
        self.assertEqual(0, report["server_echo_entries"])

    def test_rejects_forged_client_report_not_recomputed_from_room(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = self.build_positive_inputs(Path(temporary))
            report = json.loads(inputs["client_report"].read_text(encoding="utf-8"))
            report["frozen_source_sha256"] = {"aneb-probe.db": "f" * 64}
            write_json(inputs["client_report"], report)
            completed = self.run_verifier(inputs)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "client_raw_revalidation_failed",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_forged_audit_report_not_recomputed_from_journal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = self.build_positive_inputs(Path(temporary))
            report = json.loads(inputs["audit_report"].read_text(encoding="utf-8"))
            report["journal_sha256"] = "f" * 64
            write_json(inputs["audit_report"], report)
            completed = self.run_verifier(inputs)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "audit_raw_revalidation_failed",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_client_result_detached_from_room(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = self.build_positive_inputs(Path(temporary))
            result = json.loads(inputs["client_result"].read_text(encoding="utf-8"))
            result["evaluation"]["conclusions"][0]["text"] = "forged"
            write_json(inputs["client_result"], result)
            completed = self.run_verifier(inputs)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "client_raw_revalidation_failed",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_negative_delivery_receipt_digest_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = self.build_negative_inputs(Path(temporary))
            path = Path(temporary) / "negative-proxy-delivery-receipt.json"
            receipt = json.loads(path.read_text(encoding="utf-8"))
            receipt["response_body_sha256"] = "f" * 64
            write_json(path, receipt)
            completed = self.run_verifier(inputs, mode="negative")

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "negative_delivery_body_binding_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_server_primitive_contract_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = self.build_positive_inputs(Path(temporary))
            body = json.loads(inputs["serverinfo"].read_text(encoding="utf-8"))
            body["execution_capabilities"]["primitives"][2][
                "wire_contract_id"
            ] = "aneb-realtime-session-v2"
            write_json(inputs["serverinfo"], body)
            completed = self.run_verifier(inputs)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "serverinfo_primitive_set_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )


if __name__ == "__main__":
    unittest.main()
