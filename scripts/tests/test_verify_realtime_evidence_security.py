from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import verify_realtime_evidence_security as security


class RealtimeEvidenceSecurityTests(unittest.TestCase):
    def test_windows_observation_accepts_only_approved_writers(self) -> None:
        root = Path(r"E:\private\aneb-realtime")
        current = "S-1-5-21-1000"
        observation = {
            "platform": "windows",
            "current_identity": current,
            "owner_identity": current,
            "allow_rules": [
                {"identity": current, "rights": security.WRITE_DATA},
                {"identity": security.SYSTEM_SID, "rights": security.WRITE_DATA},
                {
                    "identity": security.ADMINISTRATORS_SID,
                    "rights": security.TAKE_OWNERSHIP,
                },
                {"identity": "S-1-5-32-545", "rights": 0x00000001},
            ],
        }

        report = security.evaluate_observation(root, observation)

        self.assertEqual("pass", report["status"])
        self.assertEqual(
            sorted(
                {
                    current,
                    security.SYSTEM_SID,
                    security.ADMINISTRATORS_SID,
                }
            ),
            report["observed_writer_identities"],
        )

    def test_windows_observation_rejects_unapproved_writer(self) -> None:
        root = Path(r"E:\private\aneb-realtime")
        observation = {
            "platform": "windows",
            "current_identity": "S-1-5-21-1000",
            "owner_identity": "S-1-5-21-1000",
            "allow_rules": [
                {
                    "identity": "S-1-5-21-2000",
                    "rights": security.WRITE_DATA,
                }
            ],
        }

        with self.assertRaisesRegex(
            security.EvidenceSecurityFailure,
            "evidence_root_acl_too_permissive",
        ):
            security.evaluate_observation(root, observation)

    def test_posix_observation_requires_owner_only_mode(self) -> None:
        root = Path("/private/aneb-realtime")
        observation = {
            "platform": "posix",
            "current_identity": "uid:1000",
            "owner_identity": "uid:1000",
            "mode_octal": "0700",
        }

        report = security.evaluate_observation(root, observation)

        self.assertEqual("pass", report["status"])
        self.assertEqual(["uid:1000"], report["observed_writer_identities"])
        observation["mode_octal"] = "0770"
        with self.assertRaisesRegex(
            security.EvidenceSecurityFailure,
            "evidence_root_mode_too_permissive",
        ):
            security.evaluate_observation(root, observation)

    def test_verify_root_reads_live_platform_security(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            if os.name == "nt":
                current = "S-1-5-21-1000"
                observation = {
                    "platform": "windows",
                    "current_identity": current,
                    "owner_identity": current,
                    "allow_rules": [
                        {"identity": current, "rights": security.WRITE_DATA}
                    ],
                }
                with mock.patch.object(
                    security,
                    "_read_windows_observation",
                    return_value=observation,
                ) as reader:
                    report = security.verify_root(root)
                reader.assert_called_once_with(root.resolve())
            else:
                root.chmod(0o700)
                report = security.verify_root(root)

        self.assertEqual("pass", report["status"])

    def test_stored_report_is_bound_to_exact_root(self) -> None:
        root = Path(r"E:\private\aneb-realtime")
        current = "S-1-5-21-1000"
        report = security.evaluate_observation(
            root,
            {
                "platform": "windows",
                "current_identity": current,
                "owner_identity": current,
                "allow_rules": [
                    {"identity": current, "rights": security.WRITE_DATA}
                ],
            },
        )

        security.validate_report(root, report)
        with self.assertRaisesRegex(
            security.EvidenceSecurityFailure,
            "evidence_root_security_report_invalid",
        ):
            security.validate_report(root.parent, report)

    def test_windows_acl_reader_ignores_progress_stream_on_success(self) -> None:
        current = "S-1-5-21-1000"
        stdout = json.dumps(
            {
                "platform": "windows",
                "current_identity": current,
                "owner_identity": current,
                "allow_rules": [],
            }
        ).encode("utf-8")
        completed = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=stdout,
            stderr=b"#< CLIXML progress-only",
        )
        with mock.patch.object(
            security.subprocess,
            "run",
            return_value=completed,
        ):
            observation = security._read_windows_observation(
                Path(r"E:\private\aneb-realtime")
            )

        self.assertEqual(current, observation["owner_identity"])


if __name__ == "__main__":
    unittest.main()
