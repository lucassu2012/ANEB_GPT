from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.run_repeatability_campaign import (
    CampaignError,
    _coerce_receipt_bytes,
    _install_candidate,
    assert_radio_permissions_granted,
    record_radio_permission_preflight,
    radio_permissions,
    build_campaign_plan,
    parse_token_terminal_markers,
)


RUN_ID = "019fa000-1111-7222-8333-444455556666"


class RepeatabilityCampaignTests(unittest.TestCase):
    def test_phone_receipt_text_is_encoded_exactly_once(self) -> None:
        self.assertEqual(b'{"ok":true}', _coerce_receipt_bytes('{"ok":true}'))
        self.assertEqual(b'{"ok":true}', _coerce_receipt_bytes(b'{"ok":true}'))

    def test_radio_permission_set_is_exact_and_not_open_ended(self) -> None:
        self.assertEqual(
            (
                "android.permission.READ_PHONE_STATE",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
            ),
            radio_permissions(),
        )

    def test_runtime_permission_verifier_requires_each_unique_granted_row(self) -> None:
        granted = "\n".join(
            f"    {permission}: granted=true, flags=[ USER_SET ]"
            for permission in radio_permissions()
        )
        self.assertEqual(
            {permission: True for permission in radio_permissions()},
            assert_radio_permissions_granted(granted),
        )
        with self.assertRaisesRegex(
            CampaignError,
            r"radio_permission_not_granted:android\.permission\.ACCESS_FINE_LOCATION",
        ):
            assert_radio_permissions_granted(
                granted.replace(
                    "android.permission.ACCESS_FINE_LOCATION: granted=true",
                    "android.permission.ACCESS_FINE_LOCATION: granted=false",
                )
            )

    def test_runtime_permission_verifier_accepts_granted_rows_without_flags(self) -> None:
        granted = "\n".join(
            f"    {permission}: granted=true"
            for permission in radio_permissions()
        )
        self.assertEqual(
            {permission: True for permission in radio_permissions()},
            assert_radio_permissions_granted(granted),
        )

    def test_radio_permission_preflight_retains_diagnostic_receipt_before_denial(self) -> None:
        package_dump = "\n".join(
            f"    {permission}: granted={'false' if permission.endswith('FINE_LOCATION') else 'true'}, flags=[ USER_SET ]"
            for permission in radio_permissions()
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "radio-permissions.json"
            with self.assertRaisesRegex(
                CampaignError,
                r"radio_permission_not_granted:android\.permission\.ACCESS_FINE_LOCATION",
            ):
                record_radio_permission_preflight(package_dump, output)
            receipt = json.loads(output.read_text(encoding="ascii"))

        self.assertEqual("aneb-repeatability-radio-permissions-v1", receipt["schema_version"])
        self.assertEqual("com.aneb.probe.codex", receipt["package_name"])
        self.assertEqual(
            {permission: permission != "android.permission.ACCESS_FINE_LOCATION" for permission in radio_permissions()},
            receipt["permissions"],
        )
        self.assertEqual(
            ["android.permission.ACCESS_FINE_LOCATION"],
            receipt["denied_permissions"],
        )
        self.assertFalse(receipt["all_granted"])
        self.assertTrue(receipt["diagnostic_only"])
        self.assertFalse(receipt["formal_baseline_eligible"])

    def test_candidate_install_records_permission_receipt_before_denial(self) -> None:
        package_dump = "\n".join(
            f"    {permission}: granted={'false' if permission.endswith('FINE_LOCATION') else 'true'}, flags=[ USER_SET ]"
            for permission in radio_permissions()
        )
        results = [
            subprocess.CompletedProcess([], 0, b"", b""),
            subprocess.CompletedProcess([], 0, b"Success\n", b""),
            *[subprocess.CompletedProcess([], 0, b"", b"") for _ in radio_permissions()],
            subprocess.CompletedProcess([], 0, package_dump.encode("utf-8"), b""),
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "radio-permissions.json"
            with mock.patch(
                "scripts.run_repeatability_campaign._adb_raw",
                side_effect=results,
            ) as adb_raw:
                with self.assertRaisesRegex(
                    CampaignError,
                    r"radio_permission_not_granted:android\.permission\.ACCESS_FINE_LOCATION",
                ):
                    _install_candidate(
                        Path("adb"),
                        "serial",
                        Path("candidate.apk"),
                        permission_receipt_path=output,
                    )
            self.assertTrue(output.is_file())
            self.assertEqual(6, adb_raw.call_count)

    def test_campaign_plan_is_five_runs_per_family_in_frozen_order(self) -> None:
        self.assertEqual(
            [(family, ordinal) for family in ("token", "realtime", "network") for ordinal in range(1, 6)],
            build_campaign_plan(repetitions=5),
        )

    def test_token_positive_terminal_requires_one_complete_durable_chain(self) -> None:
        text = "\n".join(
            (
                f"I/AnebProbe: TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://120.79.148.0:8443",
                f"I/AnebProbe: TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
                f"I/AnebProbe: TOKEN_V2_END run_id={RUN_ID} status=completed",
            )
        )
        terminal = parse_token_terminal_markers(text, mode="positive")
        self.assertEqual(RUN_ID, terminal.run_id)
        self.assertEqual("completed", terminal.terminal_status)

    def test_token_positive_terminal_rejects_missing_or_foreign_chain(self) -> None:
        with self.assertRaises(CampaignError):
            parse_token_terminal_markers(
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick\n"
                f"TOKEN_V2_END run_id={RUN_ID} status=completed\n",
                mode="positive",
            )
        with self.assertRaises(CampaignError):
            parse_token_terminal_markers(
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick\n"
                f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true\n"
                f"TOKEN_V2_END run_id=019fa000-1111-7222-8333-444455556667 status=completed\n",
                mode="positive",
            )


if __name__ == "__main__":
    unittest.main()
