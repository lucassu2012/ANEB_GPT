from __future__ import annotations

import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import verify_token_quick_raw_state as raw_verifier


RUN_ID = "019f6d5f-7400-7000-8000-000000000001"
OTHER_RUN_ID = "019f6d5f-7401-7000-8000-000000000002"
LOCK_NONCE = "d" * 32
LOCK_PID = 4242
BOOT_ID = "a" * 32
INVOCATION_ID = "b" * 32
SERVER_PID = 3388782
SERVER_BINARY = "c" * 64
PACKAGE_NAME = "com.aneb.probe.codex"
VERSION_NAME = "0.5.12-codex"
VERSION_CODE = 44
LAUNCHER = raw_verifier.LAUNCHER_COMPONENT


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=True, separators=(",", ":")) + "\n").encode()


def negative_log_events() -> list[str]:
    return [
        f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://loopback:38443",
        f"TOKEN_V2_RADIO run_id={RUN_ID} status=unavailable samples=0",
        f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
        f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=rejected reason=receipt_missing",
        f"TOKEN_V2_END run_id={RUN_ID} status=contract_rejected",
    ]


class RawStateFixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.root.mkdir(parents=True)
        self._write_all()

    def write(self, name: str, value: str | bytes) -> None:
        payload = value.encode("utf-8") if isinstance(value, str) else value
        (self.root / name).write_bytes(payload)

    @staticmethod
    def window(component: str = LAUNCHER) -> str:
        return f"  mCurrentFocus=Window{{123 u0 {component}}}\n"

    @staticmethod
    def activity(component: str = LAUNCHER) -> str:
        return (
            f"  mFocusedApp=ActivityRecord{{123 u0 {component} t1}}\n"
            f"  topResumedActivity=ActivityRecord{{123 u0 {component} t1}}\n"
        )

    @staticmethod
    def accessibility_final(
        enabled: str = "null", dumpsys: str = "User state:\n  Bound services: {}"
    ) -> str:
        return (
            "ANEB_D82_DEVICE_ACCESSIBILITY_FINAL_V1\n"
            "enabled_accessibility_services_command=settings get secure enabled_accessibility_services\n"
            "enabled_accessibility_services_output_begin\n"
            f"{enabled}\n"
            "enabled_accessibility_services_output_end\n"
            "dumpsys_accessibility_command=dumpsys accessibility\n"
            "dumpsys_accessibility_output_begin\n"
            f"{dumpsys}\n"
            "dumpsys_accessibility_output_end\n"
        )

    @staticmethod
    def logcat(extra_before: str = "", events: list[str] | None = None) -> str:
        prefix = "1784450000.000  100  101 I AnebProbe: "
        marker = (
            "1784450000.001  100  101 I AnebD82: "
            f"D82_CAPTURE_MARKER nonce={LOCK_NONCE}\n"
        )
        lifecycle = events or [
            f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://47.94.87.184:8443",
            f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=validated_receipt",
            f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
            f"TOKEN_V2_RESULT run_id={RUN_ID} score=88.8 grade=A verdict=PASS confidence=MEDIUM",
            f"TOKEN_V2_END run_id={RUN_ID} status=completed",
        ]
        return extra_before + marker + "".join(prefix + event + "\n" for event in lifecycle)

    def _write_all(self) -> None:
        empty_processes = {package: "" for package in sorted(raw_verifier.CONFLICT_PACKAGES)}
        empty_services = {
            package: "ACTIVITY MANAGER SERVICES\n  (nothing)"
            for package in sorted(raw_verifier.CONFLICT_PACKAGES)
        }
        for stage in ("preflight", "final"):
            self.write(f"device-window-{stage}.txt", self.window())
            self.write(f"device-activity-{stage}.txt", self.activity())
            self.write(f"device-processes-{stage}.json", json_bytes(empty_processes))
            self.write(f"device-services-{stage}.json", json_bytes(empty_services))
            self.write(
                f"device-connectivity-{stage}.txt",
                "NetworkAgentInfo{ networkId=100 state: CONNECTED "
                "Transports: WIFI VALIDATED }\n",
            )
            self.write(f"device-vpn-{stage}.txt", "VPN manager state: DISCONNECTED\n")
            self.write(f"device-tun-{stage}.txt", "absent\n")
            self.write(f"device-stayon-{stage}.txt", "0\n")

        # A disabled shortcut target is intentionally present.  It is neither an
        # enabled nor a bound AccessibilityService and must not cause a reject.
        self.write(
            "device-accessibility-preflight.txt",
            "enabled_accessibility_services=null\n"
            "User state:\n"
            "  Bound services: {}\n"
            "  Shortcut targets: [com.aneb.probe.codex/.AnebAccessibilityService]\n",
        )
        self.write("device-accessibility-final.txt", self.accessibility_final())
        self.write(
            "device-package-preflight.txt",
            "Packages:\n"
            f"  Package [{PACKAGE_NAME}] (deadbeef):\n"
            f"    versionCode={VERSION_CODE} minSdk=26 targetSdk=35\n"
            f"    versionName={VERSION_NAME}\n",
        )

        anchor = json.dumps(
            {
                "__CURSOR": "s=0123456789abcdef",
                "__MONOTONIC_TIMESTAMP": "123456789",
            },
            separators=(",", ":"),
        ).encode()
        anchor_b64 = base64.b64encode(anchor).decode("ascii")
        self.write(
            "remote-pre-start.txt",
            f"boot_id={BOOT_ID}\n"
            f"systemd_invocation_id={INVOCATION_ID}\n"
            f"main_pid={SERVER_PID}\n"
            f"server_binary_sha256={SERVER_BINARY}\n"
            "remote_realtime_anchor_usec=1784450000000000\n"
            f"journal_anchor_json_base64={anchor_b64}\n",
        )
        self.write(
            "remote-end.txt",
            f"boot_id={BOOT_ID}\n"
            f"systemd_invocation_id={INVOCATION_ID}\n"
            f"main_pid={SERVER_PID}\n"
            f"server_binary_sha256={SERVER_BINARY}\n",
        )
        self.write(
            "lock-acquired.txt",
            f"LOCK_ACQUIRED nonce={LOCK_NONCE} pid={LOCK_PID} "
            f"marker=/run/aneb-token-audit-{LOCK_NONCE}.lock\n",
        )
        self.write(
            "lock-released.txt",
            f"LOCK_RELEASED nonce={LOCK_NONCE}\nprocess_exit=0\nstderr=\n",
        )
        self.write(
            "lock-release-verified.txt",
            f"LOCK_RELEASE_VERIFIED nonce={LOCK_NONCE}\n",
        )
        self.write(
            "logcat-capture-marker.json",
            json_bytes(
                {
                    "schema": "aneb-d82-logcat-capture-marker",
                    "schema_version": "1.0.0",
                    "captured_at_utc": "2026-07-19T08:00:00.0000000Z",
                    "marker_nonce": LOCK_NONCE,
                    "marker": f"D82_CAPTURE_MARKER nonce={LOCK_NONCE}",
                }
            ),
        )
        self.write("app-logcat.txt", self.logcat())

        # Deliberately clean derived summaries.  Negative tests mutate only raw
        # files, proving the helper does not trust these summaries.
        self.write(
            "device-preflight.json",
            json_bytes({"launcher": LAUNCHER, "active_vpn": False, "tun0": "absent"}),
        )
        self.write(
            "device-final-clean.json",
            json_bytes({"launcher": LAUNCHER, "active_vpn": False, "tun0": "absent"}),
        )

    def verify(self, *, execution_mode: object = "positive") -> dict[str, object]:
        return raw_verifier.verify_raw_state(
            self.root,
            execution_mode=execution_mode,
            expected_run_id=RUN_ID,
            expected_lock_nonce=LOCK_NONCE,
            expected_package_name=PACKAGE_NAME,
            expected_version_name=VERSION_NAME,
            expected_version_code=VERSION_CODE,
            expected_remote_identity={
                "boot_id": BOOT_ID,
                "systemd_invocation_id": INVOCATION_ID,
                "main_pid": SERVER_PID,
                "server_binary_sha256": SERVER_BINARY,
            },
            expected_lock_remote_pid=LOCK_PID,
            expected_lock_marker=f"/run/aneb-token-audit-{LOCK_NONCE}.lock",
        )


class TokenQuickRawStateVerifierTests(unittest.TestCase):
    def fixture(self, temporary: str) -> RawStateFixture:
        return RawStateFixture(Path(temporary) / "bundle.complete")

    def assert_reason(self, fixture: RawStateFixture, expected: str) -> None:
        with self.assertRaises(raw_verifier.RawStateVerificationFailure) as captured:
            fixture.verify()
        self.assertEqual(expected, captured.exception.reason_code)

    def assert_negative_log_rejected(self, events: list[str]) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write("app-logcat.txt", fixture.logcat(events=events))
            with self.assertRaises(raw_verifier.RawStateVerificationFailure) as captured:
                fixture.verify(execution_mode="negative_receipt_missing")
        self.assertEqual("raw_logcat_lifecycle_invalid", captured.exception.reason_code)

    def test_complete_raw_state_passes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            report = self.fixture(temporary).verify()
        self.assertEqual("aneb-token-quick-raw-state-verification", report["schema"])
        self.assertEqual("1.1.0", report["schema_version"])
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual("positive", report["execution_mode"])
        self.assertEqual(26, report["raw_files_verified"])
        self.assertEqual(5, report["logcat_event_count"])

    def test_receipt_missing_raw_lifecycle_passes_in_negative_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "app-logcat.txt",
                fixture.logcat(events=negative_log_events()),
            )
            report = fixture.verify(execution_mode="negative_receipt_missing")

        self.assertEqual("negative_receipt_missing", report["execution_mode"])
        self.assertEqual(5, report["logcat_event_count"])

    def test_unknown_or_non_string_execution_mode_is_rejected(self) -> None:
        for execution_mode in ("negative", None, ["positive"]):
            with self.subTest(execution_mode=execution_mode):
                with tempfile.TemporaryDirectory() as temporary:
                    fixture = self.fixture(temporary)
                    with self.assertRaises(
                        raw_verifier.RawStateVerificationFailure
                    ) as captured:
                        fixture.verify(execution_mode=execution_mode)
                self.assertEqual(
                    "raw_execution_mode_invalid", captured.exception.reason_code
                )

    def test_negative_mode_rejects_forbidden_lifecycle_families(self) -> None:
        forbidden_cases = {
            "result": f"TOKEN_V2_RESULT run_id={RUN_ID} score=null grade=null verdict=INVALID confidence=INVALID",
            "failed": f"TOKEN_V2_FAILED run_id={RUN_ID} error=receipt_missing",
            "task_start": f"TOKEN_V2_TASK_START run_id={RUN_ID} task=0001 kind=echo bytes=1",
            "task_end": f"TOKEN_V2_TASK_END run_id={RUN_ID} task=0001 success=false",
            "profile": "TOKEN_V2_PROFILE id=token_multimodal_quick version=1.2.1",
        }
        for name, forbidden in forbidden_cases.items():
            with self.subTest(name=name):
                events = negative_log_events()
                events.insert(-1, forbidden)
                self.assert_negative_log_rejected(events)

    def test_negative_mode_rejects_validated_receipt_contract(self) -> None:
        events = negative_log_events()
        events[3] = (
            f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=validated_receipt"
        )
        self.assert_negative_log_rejected(events)

    def test_negative_mode_rejects_every_duplicate_lifecycle_stage(self) -> None:
        for duplicate_index in range(len(negative_log_events())):
            with self.subTest(duplicate_index=duplicate_index):
                events = negative_log_events()
                events.insert(duplicate_index + 1, events[duplicate_index])
                self.assert_negative_log_rejected(events)

    def test_negative_mode_rejects_every_adjacent_order_swap(self) -> None:
        for left_index in range(len(negative_log_events()) - 1):
            with self.subTest(left_index=left_index):
                events = negative_log_events()
                events[left_index], events[left_index + 1] = (
                    events[left_index + 1],
                    events[left_index],
                )
                self.assert_negative_log_rejected(events)

    def test_negative_mode_requires_db_contract_and_end_semantics(self) -> None:
        replacements = {
            "db_write_not_ok": (
                2,
                f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=false",
            ),
            "wrong_rejection_reason": (
                3,
                f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=rejected reason=receipt_invalid",
            ),
            "wrong_terminal_status": (
                4,
                f"TOKEN_V2_END run_id={RUN_ID} status=completed",
            ),
        }
        for name, (index, replacement) in replacements.items():
            with self.subTest(name=name):
                events = negative_log_events()
                events[index] = replacement
                self.assert_negative_log_rejected(events)

    def test_negative_mode_rejects_contradictory_trailing_fields(self) -> None:
        suffixes = {
            "start": (0, " variant=stress"),
            "radio": (1, " samples=1"),
            "db_write": (2, " ok=false"),
            "contract": (3, " status=validated_receipt"),
            "end": (4, " status=completed"),
        }
        for name, (index, suffix) in suffixes.items():
            with self.subTest(name=name):
                events = negative_log_events()
                events[index] += suffix
                self.assert_negative_log_rejected(events)

    def test_clean_derived_json_cannot_hide_preflight_foreground_app(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-window-preflight.txt",
                fixture.window("com.aneb.probe.codex/com.aneb.probe.ui.MainActivity"),
            )
            self.assert_reason(fixture, "raw_launcher_preflight_invalid")

    def test_clean_derived_json_cannot_hide_final_foreground_app(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-activity-final.txt",
                fixture.activity("com.aneb.probe.codex/com.aneb.probe.ui.MainActivity"),
            )
            self.assert_reason(fixture, "raw_launcher_final_invalid")

    def test_clean_derived_json_cannot_hide_preflight_process(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            processes = {package: "" for package in raw_verifier.CONFLICT_PACKAGES}
            processes[PACKAGE_NAME] = "2819"
            fixture.write("device-processes-preflight.json", json_bytes(processes))
            self.assert_reason(fixture, "raw_process_service_preflight_not_clean")

    def test_clean_derived_json_cannot_hide_final_service(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            services = {
                package: "ACTIVITY MANAGER SERVICES\n  (nothing)"
                for package in raw_verifier.CONFLICT_PACKAGES
            }
            services[PACKAGE_NAME] = "ServiceRecord{abcd com.aneb.probe.codex/.CaptureService}"
            fixture.write("device-services-final.json", json_bytes(services))
            self.assert_reason(fixture, "raw_process_service_final_not_clean")

    def test_duplicate_process_json_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-processes-preflight.json",
                '{"com.aneb.probe":"","com.aneb.probe":""}\n',
            )
            self.assert_reason(fixture, "raw_processes_preflight_invalid")

    def test_shortcut_target_alone_is_not_treated_as_enabled(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            report = fixture.verify()
        self.assertTrue(report["accessibility_snapshots_verified"])

    def test_clean_derived_json_cannot_hide_enabled_accessibility(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-accessibility-preflight.txt",
                "enabled_accessibility_services="
                "com.aneb.probe.codex/.AnebAccessibilityService\n"
                "User state:\n  Bound services: {}\n",
            )
            self.assert_reason(fixture, "raw_accessibility_preflight_invalid")

    def test_clean_derived_json_cannot_hide_bound_accessibility(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-accessibility-final.txt",
                fixture.accessibility_final(
                    dumpsys=(
                        "User state:\n"
                        "  Bound services:\n"
                        "    com.aneb.probe/.AnebAccessibilityService\n"
                        "  Shortcut targets: []"
                    )
                ),
            )
            self.assert_reason(fixture, "raw_accessibility_final_invalid")

    def test_clean_derived_json_cannot_hide_multiline_vpn_agent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-connectivity-preflight.txt",
                "NetworkAgentInfo{ networkId=200\n"
                "  state: CONNECTED\n"
                "  NetworkCapabilities: Transports: VPN VALIDATED\n"
                "}\n",
            )
            self.assert_reason(fixture, "raw_active_vpn_preflight")

    def test_clean_derived_json_cannot_hide_vpn_service(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write("device-vpn-final.txt", "mNetworkInfo state=CONNECTED\n")
            self.assert_reason(fixture, "raw_active_vpn_final")

    def test_listen_request_is_not_an_active_vpn(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-vpn-preflight.txt",
                "LISTEN request Transports: VPN state=CONNECTED\n",
            )
            report = fixture.verify()
        self.assertEqual("pass", report["status"])

    def test_vpn_listen_request_after_closed_wifi_agent_is_not_active(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "device-connectivity-preflight.txt",
                "NetworkAgentInfo{ networkId=100\n"
                "  state: CONNECTED\n"
                "  NetworkCapabilities: Transports: WIFI VALIDATED\n"
                "}\n"
                "NetworkRequest [ LISTEN id=42, "
                "NetworkCapabilities: Transports: VPN ]\n",
            )
            report = fixture.verify()
        self.assertEqual("pass", report["status"])

    def test_clean_derived_json_cannot_hide_tun_interface(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write("device-tun-final.txt", "tun0\n")
            self.assert_reason(fixture, "raw_tun_final_not_absent")

    def test_clean_derived_json_cannot_hide_stayon_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write("device-stayon-final.txt", "7\n")
            self.assert_reason(fixture, "raw_stayon_mismatch")

    def test_package_version_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            package = (fixture.root / "device-package-preflight.txt").read_text()
            fixture.write("device-package-preflight.txt", package.replace("versionCode=44", "versionCode=45"))
            self.assert_reason(fixture, "raw_package_identity_mismatch")

    def test_remote_end_identity_change_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            remote_end = (fixture.root / "remote-end.txt").read_text()
            fixture.write("remote-end.txt", remote_end.replace(f"main_pid={SERVER_PID}", "main_pid=999"))
            self.assert_reason(fixture, "raw_remote_identity_mismatch")

    def test_remote_identity_must_match_authenticated_expected_value(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            with self.assertRaises(raw_verifier.RawStateVerificationFailure) as captured:
                raw_verifier.verify_raw_state(
                    fixture.root,
                    execution_mode="positive",
                    expected_run_id=RUN_ID,
                    expected_lock_nonce=LOCK_NONCE,
                    expected_package_name=PACKAGE_NAME,
                    expected_version_name=VERSION_NAME,
                    expected_version_code=VERSION_CODE,
                    expected_remote_identity={
                        "boot_id": BOOT_ID,
                        "systemd_invocation_id": INVOCATION_ID,
                        "main_pid": SERVER_PID,
                        "server_binary_sha256": "e" * 64,
                    },
                )
            self.assertEqual("raw_remote_identity_mismatch", captured.exception.reason_code)

    def test_boolean_expected_remote_pid_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            with self.assertRaises(raw_verifier.RawStateVerificationFailure) as captured:
                raw_verifier.verify_raw_state(
                    fixture.root,
                    execution_mode="positive",
                    expected_run_id=RUN_ID,
                    expected_lock_nonce=LOCK_NONCE,
                    expected_package_name=PACKAGE_NAME,
                    expected_version_name=VERSION_NAME,
                    expected_version_code=VERSION_CODE,
                    expected_remote_identity={
                        "boot_id": BOOT_ID,
                        "systemd_invocation_id": INVOCATION_ID,
                        "main_pid": True,
                        "server_binary_sha256": SERVER_BINARY,
                    },
                )
            self.assertEqual("raw_remote_identity_mismatch", captured.exception.reason_code)

    def test_remote_journal_anchor_allows_trusted_metadata_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            anchor = json.dumps(
                {
                    "__CURSOR": "s=0123456789abcdef",
                    "__MONOTONIC_TIMESTAMP": "123456789",
                    "_BOOT_ID": BOOT_ID,
                    "SYSLOG_IDENTIFIER": "aneb-server",
                },
                separators=(",", ":"),
            ).encode()
            anchor_b64 = base64.b64encode(anchor).decode("ascii")
            remote_pre = (fixture.root / "remote-pre-start.txt").read_text()
            remote_pre = "\n".join(
                (
                    f"journal_anchor_json_base64={anchor_b64}"
                    if line.startswith("journal_anchor_json_base64=")
                    else line
                )
                for line in remote_pre.splitlines()
            ) + "\n"
            fixture.write("remote-pre-start.txt", remote_pre)
            report = fixture.verify()
        self.assertEqual("pass", report["status"])

    def test_lock_nonce_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "lock-released.txt",
                f"LOCK_RELEASED nonce={'e' * 32}\nprocess_exit=0\nstderr=\n",
            )
            self.assert_reason(fixture, "raw_lock_lifecycle_mismatch")

    def test_lock_release_nonempty_stderr_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            fixture.write(
                "lock-released.txt",
                f"LOCK_RELEASED nonce={LOCK_NONCE}\n"
                "process_exit=0\nstderr=unexpected warning\n",
            )
            self.assert_reason(fixture, "raw_lock_lifecycle_mismatch")

    def test_logcat_marker_missing_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            logcat = (fixture.root / "app-logcat.txt").read_text()
            fixture.write("app-logcat.txt", logcat.replace("D82_CAPTURE_MARKER", "D82_CAPTURE_MISSING"))
            self.assert_reason(fixture, "raw_logcat_lifecycle_invalid")

    def test_logcat_marker_before_replay_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            replay = (
                "1784449999.000 1 1 I AnebProbe: "
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://old:8443\n"
            )
            fixture.write("app-logcat.txt", fixture.logcat(extra_before=replay))
            self.assert_reason(fixture, "raw_logcat_replay_before_marker")

    def test_malformed_logcat_lifecycle_before_marker_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            replay = (
                "1784449999.000 1 1 I AnebProbe: "
                "TOKEN_V2_START run_id=not-a-current-run variant=quick\n"
            )
            fixture.write("app-logcat.txt", fixture.logcat(extra_before=replay))
            self.assert_reason(fixture, "raw_logcat_replay_before_marker")

    def test_duplicate_logcat_start_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            events = [
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://new:8443",
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://new:8443",
                f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=validated_receipt",
                f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
                f"TOKEN_V2_RESULT run_id={RUN_ID} score=88.8 grade=A verdict=PASS confidence=MEDIUM",
                f"TOKEN_V2_END run_id={RUN_ID} status=completed",
            ]
            fixture.write("app-logcat.txt", fixture.logcat(events=events))
            self.assert_reason(fixture, "raw_logcat_lifecycle_invalid")

    def test_two_logcat_lifecycle_tokens_on_one_line_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            events = [
                (
                    f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://new:8443 "
                    f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://new:8443"
                ),
                f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=validated_receipt",
                f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
                f"TOKEN_V2_RESULT run_id={RUN_ID} score=88.8 grade=A verdict=PASS confidence=MEDIUM",
                f"TOKEN_V2_END run_id={RUN_ID} status=completed",
            ]
            fixture.write("app-logcat.txt", fixture.logcat(events=events))
            self.assert_reason(fixture, "raw_logcat_lifecycle_invalid")

    def test_missing_logcat_db_write_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            events = [
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://new:8443",
                f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=validated_receipt",
                f"TOKEN_V2_RESULT run_id={RUN_ID} score=88.8 grade=A verdict=PASS confidence=MEDIUM",
                f"TOKEN_V2_END run_id={RUN_ID} status=completed",
            ]
            fixture.write("app-logcat.txt", fixture.logcat(events=events))
            self.assert_reason(fixture, "raw_logcat_lifecycle_invalid")

    def test_out_of_order_logcat_events_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            events = [
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://new:8443",
                f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
                f"TOKEN_V2_CONTRACT run_id={RUN_ID} status=validated_receipt",
                f"TOKEN_V2_RESULT run_id={RUN_ID} score=88.8 grade=A verdict=PASS confidence=MEDIUM",
                f"TOKEN_V2_END run_id={RUN_ID} status=completed",
            ]
            fixture.write("app-logcat.txt", fixture.logcat(events=events))
            self.assert_reason(fixture, "raw_logcat_lifecycle_invalid")

    def test_mixed_run_logcat_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.fixture(temporary)
            events = [
                f"TOKEN_V2_START run_id={RUN_ID} variant=quick server=https://new:8443",
                f"TOKEN_V2_CONTRACT run_id={OTHER_RUN_ID} status=validated_receipt",
                f"TOKEN_V2_DB_WRITE run_id={RUN_ID} ok=true",
                f"TOKEN_V2_RESULT run_id={RUN_ID} score=88.8 grade=A verdict=PASS confidence=MEDIUM",
                f"TOKEN_V2_END run_id={RUN_ID} status=completed",
            ]
            fixture.write("app-logcat.txt", fixture.logcat(events=events))
            self.assert_reason(fixture, "raw_logcat_lifecycle_invalid")


if __name__ == "__main__":
    unittest.main()
