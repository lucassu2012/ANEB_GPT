from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "prepare_token_run_evidence.py"
RUN_ID = "018f7a2b-1234-7abc-8def-0123456789ab"
START_ID = "11111111-1111-4111-8111-111111111111"
END_ID = "22222222-2222-4222-8222-222222222222"
INSTANCE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
BOOT_ID = "b" * 32
INVOCATION_ID = "c" * 32
UNIT = "aneb-server.service"
MAIN_PID = 25911
SERVER_BASE = "https://203.0.113.10:8443"
SERVER_VERSION = "aneb-server/0.8.0"
SERVER_BINARY_SHA256 = "d" * 64
SERVERINFO_BODY_SHA256 = "e" * 64
LOCK_PATH = "/run/lock/aneb-deploy.lock"
LOCK_NONCE = "f" * 32
LOCK_REMOTE_PID = 27182
LOCK_MARKER = f"/run/aneb-token-audit-{LOCK_NONCE}.lock"
REMOTE_REALTIME_ANCHOR_USEC = 1_784_433_600_000_000
JOURNAL_MONOTONIC_ANCHOR = 987_654_321_000


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class PrepareTokenRunEvidenceCliTest(unittest.TestCase):
    @staticmethod
    def audit_message(
        seq: int,
        *,
        role: str,
        run_id: str,
    ) -> str:
        return (
            f"ANEB_REQUEST_AUDIT instance_id={INSTANCE_ID} seq={seq} "
            "class=control method=GET path=/api/v1/serverinfo "
            f"role={role} scope=token_run run_id={run_id}"
        )

    @staticmethod
    def receipt() -> dict[str, object]:
        return {
            "schema": "aneb-token-evidence-pre-start-receipt",
            "schema_version": "1.0.0",
            "captured_at_utc": "2026-07-19T04:00:00Z",
            "journal_cursor": "s=pre-start-cursor",
            "journal_monotonic_anchor": JOURNAL_MONOTONIC_ANCHOR,
            "remote_realtime_anchor_usec": REMOTE_REALTIME_ANCHOR_USEC,
            "boot_id": BOOT_ID,
            "systemd_invocation_id": INVOCATION_ID,
            "unit": UNIT,
            "main_pid": MAIN_PID,
            "server_base": SERVER_BASE,
            "server_version": SERVER_VERSION,
            "server_binary_sha256": SERVER_BINARY_SHA256,
            "serverinfo_body_sha256": SERVERINFO_BODY_SHA256,
            "lock_path": LOCK_PATH,
            "lock_nonce": LOCK_NONCE,
            "lock_remote_pid": LOCK_REMOTE_PID,
            "lock_marker": LOCK_MARKER,
        }

    @staticmethod
    def journal_record(index: int, message: object) -> dict[str, object]:
        return {
            "__CURSOR": f"s=cursor-{index}",
            "__REALTIME_TIMESTAMP": str(REMOTE_REALTIME_ANCHOR_USEC + index),
            "__MONOTONIC_TIMESTAMP": str(JOURNAL_MONOTONIC_ANCHOR + index),
            "_BOOT_ID": BOOT_ID,
            "_SYSTEMD_INVOCATION_ID": INVOCATION_ID,
            "_SYSTEMD_UNIT": UNIT,
            "_PID": str(MAIN_PID),
            "MESSAGE": message,
        }

    def write_case(
        self,
        root: Path,
        messages: list[object],
        *,
        receipt: dict[str, object] | None = None,
    ) -> tuple[Path, Path, bytes]:
        receipt_path = root / "pre-start-receipt.json"
        receipt_path.write_text(
            json.dumps(receipt or self.receipt(), sort_keys=True) + "\n",
            encoding="utf-8",
        )
        raw_path = root / "journal.raw.jsonl"
        raw_bytes = b"".join(
            (
                json.dumps(
                    self.journal_record(index, message),
                    ensure_ascii=False,
                    separators=(",", ":"),
                ).encode("utf-8")
                + b"\n"
            )
            for index, message in enumerate(messages, 1)
        )
        raw_path.write_bytes(raw_bytes)
        return raw_path, receipt_path, raw_bytes

    def run_derive(
        self,
        root: Path,
        raw_path: Path,
        receipt_path: Path,
        *extra: str,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "derive",
                "--journal",
                str(raw_path),
                "--pre-start-receipt",
                str(receipt_path),
                "--run-id",
                RUN_ID,
                "--start-barrier-id",
                START_ID,
                "--end-barrier-id",
                END_ID,
                "--message-output",
                str(root / "token-run-audit.log"),
                "--derivation-output",
                str(root / "derivation.json"),
                *extra,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def run_manifest(
        self,
        root: Path,
        output: Path,
        files: list[Path],
    ) -> subprocess.CompletedProcess[str]:
        command = [
            sys.executable,
            str(SCRIPT),
            "manifest",
            "--root",
            str(root),
            "--output",
            str(output),
        ]
        for path in files:
            command.extend(("--file", str(path)))
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_derive_truncates_at_unique_end_barrier_and_hashes_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            messages = [
                "pre-start-cursor successor before the start barrier",
                self.audit_message(100, role="window_start", run_id=START_ID),
                self.audit_message(101, role="capability", run_id=RUN_ID),
                self.audit_message(102, role="window_end", run_id=END_ID),
                "unrelated message after the closed window",
            ]
            raw_path, receipt_path, raw_bytes = self.write_case(root, messages)

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(0, result.returncode, result.stderr)
            expected_log = ("\n".join(messages[:4]) + "\n").encode("utf-8")
            self.assertEqual(expected_log, (root / "token-run-audit.log").read_bytes())
            report = json.loads((root / "derivation.json").read_text(encoding="utf-8"))
            self.assertEqual("pass", report["status"])
            self.assertEqual("aneb-token-evidence-derivation", report["schema"])
            self.assertEqual("1.0.0", report["schema_version"])
            self.assertEqual(
                "journald_source_derivation_only", report["evidence_scope"]
            )
            self.assertEqual(
                {
                    "binary_message": "journalctl_json_byte_array",
                    "record_framing": "message_bytes_plus_lf",
                    "text_message": "utf-8",
                },
                report["derivation_algorithm"],
            )
            self.assertEqual(5, report["source"]["records"])
            self.assertEqual(4, report["derived_message_log"]["records"])
            self.assertEqual(4, report["derived_message_log"]["lines"])
            self.assertEqual(
                JOURNAL_MONOTONIC_ANCHOR,
                report["pre_start"]["journal_monotonic_anchor"],
            )
            self.assertEqual(
                REMOTE_REALTIME_ANCHOR_USEC,
                report["pre_start"]["remote_realtime_anchor_usec"],
            )
            self.assertEqual(1, report["truncation"]["records_after_end_barrier"])
            self.assertEqual(2, report["truncation"]["start_barrier_record"])
            self.assertEqual("s=cursor-2", report["truncation"]["start_cursor"])
            self.assertEqual(4, report["truncation"]["end_barrier_record"])
            self.assertEqual("s=cursor-4", report["truncation"]["end_cursor"])
            self.assertEqual(sha256(raw_bytes), report["source"]["sha256"])
            self.assertEqual(len(raw_bytes), report["source"]["bytes"])
            receipt_bytes = receipt_path.read_bytes()
            self.assertEqual(
                sha256(receipt_bytes), report["pre_start_receipt"]["sha256"]
            )
            self.assertEqual(
                len(receipt_bytes), report["pre_start_receipt"]["bytes"]
            )
            self.assertEqual(
                {
                    "base": SERVER_BASE,
                    "binary_sha256": SERVER_BINARY_SHA256,
                    "serverinfo_body_sha256": SERVERINFO_BODY_SHA256,
                    "version": SERVER_VERSION,
                },
                report["node_identity"],
            )
            self.assertEqual(
                {
                    "marker": LOCK_MARKER,
                    "nonce": LOCK_NONCE,
                    "path": LOCK_PATH,
                    "remote_pid": LOCK_REMOTE_PID,
                },
                report["audit_lock"],
            )
            self.assertEqual(sha256(expected_log), report["derived_message_log"]["sha256"])
            self.assertEqual(len(expected_log), report["derived_message_log"]["bytes"])

    def test_derive_preserves_journalctl_binary_message_arrays(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            start = self.audit_message(100, role="window_start", run_id=START_ID)
            end = self.audit_message(102, role="window_end", run_id=END_ID)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [start, [0, 255, 65], end],
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(0, result.returncode, result.stderr)
            expected = start.encode("ascii") + b"\n\x00\xffA\n" + end.encode("ascii") + b"\n"
            self.assertEqual(expected, (root / "token-run-audit.log").read_bytes())
            report = json.loads((root / "derivation.json").read_text(encoding="utf-8"))
            self.assertEqual(sha256(expected), report["derived_message_log"]["sha256"])

    def test_derive_rejects_multiline_message_that_could_expand_one_record(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    "ordinary prefix\n"
                    + self.audit_message(101, role="capability", run_id=RUN_ID),
                    self.audit_message(102, role="window_end", run_id=END_ID),
                ],
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("MESSAGE contains a record delimiter", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_rejects_export_that_replays_the_pre_start_cursor(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()]
            records[0]["__CURSOR"] = "s=pre-start-cursor"
            raw_path.write_text(
                "".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records),
                encoding="utf-8",
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("pre-start cursor", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_manifest_draft_sorts_relative_paths_and_hashes_exact_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "evidence"
            (root / "nested").mkdir(parents=True)
            alpha = root / "alpha.json"
            zulu = root / "nested" / "zulu.bin"
            alpha_bytes = b'{"alpha":1}\r\n'
            zulu_bytes = b"\x00\xff\x10"
            alpha.write_bytes(alpha_bytes)
            zulu.write_bytes(zulu_bytes)
            output = root / "manifest.draft.json"

            result = self.run_manifest(root, output, [zulu, alpha])

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("acceptance_eligible=false", result.stdout)
            manifest = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("aneb-evidence-manifest-draft", manifest["schema"])
            self.assertEqual("1.0.0", manifest["schema_version"])
            self.assertFalse(manifest["acceptance_eligible"])
            self.assertEqual(
                "inventory_only_not_d82_acceptance", manifest["evidence_scope"]
            )
            self.assertEqual(
                [
                    {
                        "bytes": len(alpha_bytes),
                        "path": "alpha.json",
                        "sha256": sha256(alpha_bytes),
                    },
                    {
                        "bytes": len(zulu_bytes),
                        "path": "nested/zulu.bin",
                        "sha256": sha256(zulu_bytes),
                    },
                ],
                manifest["files"],
            )

    def test_derive_rejects_output_that_would_overwrite_source_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, raw_bytes = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )

            result = self.run_derive(
                root,
                raw_path,
                receipt_path,
                "--message-output",
                str(raw_path),
            )

            self.assertEqual(2, result.returncode)
            self.assertIn("distinct", result.stderr)
            self.assertEqual(raw_bytes, raw_path.read_bytes())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_requires_app_run_uuidv7(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )

            result = self.run_derive(
                root,
                raw_path,
                receipt_path,
                "--run-id",
                "33333333-3333-4333-8333-333333333333",
            )

            self.assertEqual(2, result.returncode)
            self.assertIn("run ID must be a canonical lowercase UUIDv7", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_requires_controller_barrier_uuidv4(self) -> None:
        for option in ("--start-barrier-id", "--end-barrier-id"):
            with self.subTest(option=option), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                raw_path, receipt_path, _ = self.write_case(
                    root,
                    [
                        self.audit_message(100, role="window_start", run_id=START_ID),
                        self.audit_message(101, role="window_end", run_id=END_ID),
                    ],
                )

                result = self.run_derive(
                    root,
                    raw_path,
                    receipt_path,
                    option,
                    "33333333-3333-7333-8333-333333333333",
                )

                self.assertEqual(2, result.returncode)
                self.assertIn(
                    "start/end IDs must be canonical lowercase UUIDv4",
                    result.stderr,
                )
                self.assertFalse((root / "token-run-audit.log").exists())
                self.assertFalse((root / "derivation.json").exists())

    def test_derive_fails_closed_on_missing_or_mismatched_journal_identity(self) -> None:
        cases = {
            "missing_cursor": lambda records: records[0].pop("__CURSOR"),
            "duplicate_cursor": lambda records: records[1].__setitem__(
                "__CURSOR", records[0]["__CURSOR"]
            ),
            "invalid_cursor": lambda records: records[0].__setitem__(
                "__CURSOR", "bad cursor"
            ),
            "boot_mismatch": lambda records: records[0].__setitem__("_BOOT_ID", "d" * 32),
            "invocation_mismatch": lambda records: records[0].__setitem__(
                "_SYSTEMD_INVOCATION_ID", "e" * 32
            ),
            "unit_mismatch": lambda records: records[0].__setitem__(
                "_SYSTEMD_UNIT", "other.service"
            ),
            "pid_mismatch": lambda records: records[0].__setitem__("_PID", "999"),
            "missing_timestamp": lambda records: records[0].pop("__REALTIME_TIMESTAMP"),
            "zero_timestamp": lambda records: records[0].__setitem__(
                "__REALTIME_TIMESTAMP", "0"
            ),
            "oversized_timestamp": lambda records: records[0].__setitem__(
                "__REALTIME_TIMESTAMP", "9" * 5000
            ),
            "missing_monotonic": lambda records: records[0].pop(
                "__MONOTONIC_TIMESTAMP"
            ),
            "zero_monotonic": lambda records: records[0].__setitem__(
                "__MONOTONIC_TIMESTAMP", "0"
            ),
            "oversized_monotonic": lambda records: records[0].__setitem__(
                "__MONOTONIC_TIMESTAMP", "9" * 5000
            ),
            "missing_message": lambda records: records[0].pop("MESSAGE"),
        }
        for label, mutate in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                messages = [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ]
                raw_path, receipt_path, _ = self.write_case(root, messages)
                records = [
                    json.loads(line)
                    for line in raw_path.read_text(encoding="utf-8").splitlines()
                ]
                mutate(records)
                raw_path.write_text(
                    "".join(
                        json.dumps(record, separators=(",", ":")) + "\n"
                        for record in records
                    ),
                    encoding="utf-8",
                )

                result = self.run_derive(root, raw_path, receipt_path)

                self.assertEqual(2, result.returncode, result.stderr)
                self.assertNotIn("Traceback", result.stderr)
                self.assertFalse((root / "token-run-audit.log").exists())
                self.assertFalse((root / "derivation.json").exists())

    def test_derive_requires_every_record_after_monotonic_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            records = [
                json.loads(line)
                for line in raw_path.read_text(encoding="utf-8").splitlines()
            ]
            records[0]["__MONOTONIC_TIMESTAMP"] = str(JOURNAL_MONOTONIC_ANCHOR)
            raw_path.write_text(
                "".join(
                    json.dumps(record, separators=(",", ":")) + "\n"
                    for record in records
                ),
                encoding="utf-8",
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("does not follow monotonic anchor", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_requires_strictly_increasing_monotonic_timestamps(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            records = [
                json.loads(line)
                for line in raw_path.read_text(encoding="utf-8").splitlines()
            ]
            records[1]["__MONOTONIC_TIMESTAMP"] = records[0][
                "__MONOTONIC_TIMESTAMP"
            ]
            raw_path.write_text(
                "".join(
                    json.dumps(record, separators=(",", ":")) + "\n"
                    for record in records
                ),
                encoding="utf-8",
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("monotonic timestamps are not strictly increasing", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_rejects_realtime_before_remote_anchor(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            records = [
                json.loads(line)
                for line in raw_path.read_text(encoding="utf-8").splitlines()
            ]
            records[0]["__REALTIME_TIMESTAMP"] = str(
                REMOTE_REALTIME_ANCHOR_USEC - 1
            )
            raw_path.write_text(
                "".join(
                    json.dumps(record, separators=(",", ":")) + "\n"
                    for record in records
                ),
                encoding="utf-8",
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("precedes remote realtime anchor", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_requires_nondecreasing_realtime_timestamps(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            records = [
                json.loads(line)
                for line in raw_path.read_text(encoding="utf-8").splitlines()
            ]
            records[1]["__REALTIME_TIMESTAMP"] = str(REMOTE_REALTIME_ANCHOR_USEC)
            raw_path.write_text(
                "".join(
                    json.dumps(record, separators=(",", ":")) + "\n"
                    for record in records
                ),
                encoding="utf-8",
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("realtime timestamps are not ordered", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_rejects_duplicate_end_barrier_even_after_first_end(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            end = self.audit_message(101, role="window_end", run_id=END_ID)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    end,
                    end,
                ],
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("one exact end barrier, found 2", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())

    def test_manifest_draft_rejects_files_outside_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root = base / "evidence"
            root.mkdir()
            outside = base / "outside.txt"
            outside.write_text("not evidence", encoding="utf-8")
            output = root / "manifest.draft.json"

            result = self.run_manifest(root, output, [outside])

            self.assertEqual(2, result.returncode)
            self.assertIn("outside root", result.stderr)
            self.assertFalse(output.exists())

    def test_manifest_draft_requires_an_explicit_draft_filename(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "evidence"
            root.mkdir()
            artifact = root / "artifact.txt"
            artifact.write_text("evidence", encoding="utf-8")
            misleading_output = root / "evidence-manifest.json"

            result = self.run_manifest(root, misleading_output, [artifact])

            self.assertEqual(2, result.returncode)
            self.assertIn("output filename must end with .draft.json", result.stderr)
            self.assertFalse(misleading_output.exists())

    def test_manifest_draft_never_replaces_existing_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "evidence"
            root.mkdir()
            artifact = root / "artifact.txt"
            artifact.write_text("evidence", encoding="utf-8")
            output = root / "evidence-inventory.draft.json"
            original = b"pre-existing-manifest\n"
            output.write_bytes(original)

            result = self.run_manifest(root, output, [artifact])

            self.assertEqual(2, result.returncode)
            self.assertIn("manifest output already exists", result.stderr)
            self.assertEqual(original, output.read_bytes())

    def test_manifest_draft_rejects_an_unbounded_input_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "evidence"
            root.mkdir()
            oversized = root / "oversized.bin"
            with oversized.open("wb") as stream:
                stream.truncate(128 * 1024 * 1024 + 1)
            output = root / "evidence-inventory.draft.json"

            result = self.run_manifest(root, output, [oversized])

            self.assertEqual(2, result.returncode)
            self.assertIn("manifest file exceeds", result.stderr)
            self.assertFalse(output.exists())

    def test_derive_rejects_non_timestamped_pre_start_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            receipt = self.receipt()
            receipt["captured_at_utc"] = "not-a-timeZ"
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
                receipt=receipt,
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("captured_at_utc", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())

    def test_derive_fails_closed_for_non_utf8_json_string_message(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    "placeholder",
                    self.audit_message(102, role="window_end", run_id=END_ID),
                ],
            )
            records = [
                json.loads(line)
                for line in raw_path.read_text(encoding="utf-8").splitlines()
            ]
            records[1]["MESSAGE"] = "\udcff"
            raw_path.write_bytes(
                b"".join(
                    json.dumps(record, ensure_ascii=True, separators=(",", ":")).encode(
                        "ascii"
                    )
                    + b"\n"
                    for record in records
                )
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("MESSAGE", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())

    def test_receipt_requires_frozen_node_and_lock_identity(self) -> None:
        required_fields = (
            "journal_monotonic_anchor",
            "remote_realtime_anchor_usec",
            "server_base",
            "server_version",
            "server_binary_sha256",
            "serverinfo_body_sha256",
            "lock_path",
            "lock_nonce",
            "lock_remote_pid",
            "lock_marker",
        )
        for field in required_fields:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                receipt = self.receipt()
                receipt.pop(field)
                raw_path, receipt_path, _ = self.write_case(
                    root,
                    [
                        self.audit_message(100, role="window_start", run_id=START_ID),
                        self.audit_message(101, role="window_end", run_id=END_ID),
                    ],
                    receipt=receipt,
                )

                result = self.run_derive(root, raw_path, receipt_path)

                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn(field, result.stderr)
                self.assertFalse((root / "derivation.json").exists())

    def test_receipt_rejects_unversioned_extra_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            receipt = self.receipt()
            receipt["api_key"] = "must-not-enter-evidence"
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
                receipt=receipt,
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("fields do not match schema", result.stderr)
            self.assertNotIn("must-not-enter-evidence", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_receipt_rejects_invalid_node_and_lock_identity(self) -> None:
        invalid_values: dict[str, object] = {
            "journal_cursor": "s=x",
            "journal_monotonic_anchor": 0,
            "remote_realtime_anchor_usec": 0,
            "server_base": "https://user:secret@example.test:8443/path?query=1",
            "server_version": "aneb-server/0.7.0",
            "server_binary_sha256": "D" * 64,
            "serverinfo_body_sha256": "too-short",
            "lock_path": "/tmp/not-the-deploy-lock",
            "lock_nonce": "g" * 32,
            "lock_remote_pid": 1,
            "lock_marker": "/run/aneb-token-audit-wrong.lock",
        }
        for field, value in invalid_values.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                receipt = self.receipt()
                receipt[field] = value
                raw_path, receipt_path, _ = self.write_case(
                    root,
                    [
                        self.audit_message(100, role="window_start", run_id=START_ID),
                        self.audit_message(101, role="window_end", run_id=END_ID),
                    ],
                    receipt=receipt,
                )

                result = self.run_derive(root, raw_path, receipt_path)

                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn(field, result.stderr)
                self.assertFalse((root / "derivation.json").exists())

    def test_derive_rejects_ambiguous_duplicate_journal_json_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            raw = raw_path.read_bytes()
            raw = raw.replace(
                f'"_PID":"{MAIN_PID}"'.encode("ascii"),
                f'"_PID":"999","_PID":"{MAIN_PID}"'.encode("ascii"),
                1,
            )
            raw_path.write_bytes(raw)

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("duplicate JSON field", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())

    def test_derive_rejects_unversioned_extra_journal_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            records = [
                json.loads(line)
                for line in raw_path.read_text(encoding="utf-8").splitlines()
            ]
            records[0]["SECRET_ENVIRONMENT_FIELD"] = "must-not-enter-evidence"
            raw_path.write_text(
                "".join(
                    json.dumps(record, separators=(",", ":")) + "\n"
                    for record in records
                ),
                encoding="utf-8",
            )

            result = self.run_derive(root, raw_path, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("fields do not match schema", result.stderr)
            self.assertNotIn("must-not-enter-evidence", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_reports_output_io_failure_without_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            blocker = root / "not-a-directory"
            blocker.write_bytes(b"block")

            result = self.run_derive(
                root,
                raw_path,
                receipt_path,
                "--message-output",
                str(blocker / "audit.log"),
            )

            self.assertEqual(2, result.returncode)
            self.assertIn("cannot stage derive outputs", result.stderr)
            self.assertNotIn("Traceback", result.stderr)

    def test_derive_rejects_receipt_that_changes_during_bounded_read(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            with receipt_path.open("ab") as stream:
                stream.write(b" " * (24 * 1024 * 1024))

            stop = threading.Event()
            mutation_count = 0

            def mutate_trailing_json_whitespace() -> None:
                nonlocal mutation_count
                flip = False
                with receipt_path.open("r+b", buffering=0) as stream:
                    while not stop.is_set():
                        stream.seek(-1, os.SEEK_END)
                        stream.write(b"\t" if flip else b" ")
                        flip = not flip
                        mutation_count += 1

            mutator = threading.Thread(target=mutate_trailing_json_whitespace)
            mutator.start()
            try:
                result = self.run_derive(root, raw_path, receipt_path)
            finally:
                stop.set()
                mutator.join(timeout=5)

            self.assertGreater(mutation_count, 0)
            self.assertFalse(mutator.is_alive())
            self.assertEqual(2, result.returncode)
            self.assertIn("changed while reading", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_refuses_symbolic_link_source_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            linked_journal = root / "journal-link.jsonl"
            try:
                linked_journal.symlink_to(raw_path)
            except OSError as exc:
                self.skipTest(f"symbolic links unavailable: {exc}")

            result = self.run_derive(root, linked_journal, receipt_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("symbolic-link inputs are not allowed", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((root / "derivation.json").exists())

    def test_derive_refuses_to_replace_existing_outputs(self) -> None:
        for existing_name in ("token-run-audit.log", "derivation.json"):
            with self.subTest(existing_name=existing_name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                raw_path, receipt_path, _ = self.write_case(
                    root,
                    [
                        self.audit_message(100, role="window_start", run_id=START_ID),
                        self.audit_message(101, role="window_end", run_id=END_ID),
                    ],
                )
                existing = root / existing_name
                original = b"pre-existing-evidence\n"
                existing.write_bytes(original)

                result = self.run_derive(root, raw_path, receipt_path)

                self.assertEqual(2, result.returncode)
                self.assertIn("output already exists", result.stderr)
                self.assertEqual(original, existing.read_bytes())
                other = root / (
                    "derivation.json"
                    if existing_name == "token-run-audit.log"
                    else "token-run-audit.log"
                )
                self.assertFalse(other.exists())

    def test_derive_rolls_back_first_output_when_second_cannot_commit(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            raw_path, receipt_path, _ = self.write_case(
                root,
                [
                    self.audit_message(100, role="window_start", run_id=START_ID),
                    self.audit_message(101, role="window_end", run_id=END_ID),
                ],
            )
            blocker = root / "not-a-directory"
            blocker.write_bytes(b"block")

            result = self.run_derive(
                root,
                raw_path,
                receipt_path,
                "--derivation-output",
                str(blocker / "derivation.json"),
            )

            self.assertEqual(2, result.returncode)
            self.assertIn("cannot stage derive outputs", result.stderr)
            self.assertFalse((root / "token-run-audit.log").exists())
            self.assertFalse((blocker / "derivation.json").exists())


if __name__ == "__main__":
    unittest.main()
