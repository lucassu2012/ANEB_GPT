"""HOST_ONLY: actual runner/sealing; only the external phone/OS is emulated."""

import hashlib
import base64
import json
from pathlib import Path
import tempfile
import unittest

from scripts.tongyi_ordinary_text_engineering_runner import run_pair


# Structural API31 vendor dialect only. No actual SSID/address/activity contents.
CURRENT_WIFI = b"""Active default network: 367
Current Networks:
  NetworkAgentInfo{network{367} handle{HOST_ONLY} ni{WIFI CONNECTED extra: REDACTED} Score(60) everValidated lastValidated lp{ *** } nc{[ Transports: WIFI Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED]}}
    Requests: REQUEST:1 LISTEN:1 BACKGROUND_REQUEST:0 total:2
      NetworkRequest [ LISTEN id=43, [ Transports: CELLULAR|WIFI Capabilities: NOT_VPN] ]

Status for known UIDs:
  HOST_ONLY_REDACTED
Network Requests:
"""
VPN_LISTENER = b"  activeRequest: null ... LISTEN id=48 ... Transports: VPN ... RequestorPkg: com.huawei.systemserver\n"


class ExternalPhone:
    """The OS/ADB boundary, not a substitute for our runner or its cleanup."""

    def __init__(self):
        self.commands = []
        self.files = {}
        self.unknown_send = False
        self.old_apk = None
        self.installed_debug = None
        self.require_native_abi = False
        self.instrument_alive = False
        self.wifi_on = "1"
        self.foreground_unknown = False
        self.active_vpn = False
        self.partial_pull_failure = False
        self.cancel_during_capture = False
        self.vendor_admission = False
        self.current_network_override = None
        self.preparation_unexpected_send = False

    def run(self, argv, timeout, *, cancel_file=None, on_cancel=None):
        self.commands.append(list(argv))
        if "--print-certs" in argv:
            return 0, ("Signer #1 certificate SHA-256 digest: " + "6" * 64 + "\n").encode(), b""
        if "badging" in argv:
            package = "com.aneb.probe.codex.test" if "android-test" in argv[-1] else "com.aneb.probe.codex"
            return 0, (f"package: name='{package}' versionCode='20' versionName='0.2.0-codex'\n").encode(), b""
        args = list(argv)[3:]
        if args == ["get-state"]:
            return 0, b"device\n", b""
        if args[:3] == ["shell", "dumpsys", "activity"]:
            if self.foreground_unknown:
                return 0, b"mLastPausedActivity: com.huawei.android.launcher/.unihome.UniHomeLauncher\nmResumedActivity: com.example.unknown/.Main\n", b""
            return 0, b"mResumedActivity: com.huawei.android.launcher/.unihome.UniHomeLauncher\n", b""
        if args == ["shell", "dumpsys", "window", "windows"]:
            if self.vendor_admission:
                return 0, b"WINDOW MANAGER WINDOWS\n  Window #0: HOST_ONLY_REDACTED\n", b""
            return 0, b"mCurrentFocus=Window{abc u0 com.huawei.android.launcher/com.huawei.android.launcher.unihome.UniHomeLauncher}\n", b""
        if args == ["shell", "dumpsys", "window"]:
            return 0, b"mCurrentFocus=Window{abc u0 com.huawei.android.launcher/com.huawei.android.launcher.unihome.UniHomeLauncher}\n", b""
        if args == ["shell", "dumpsys", "connectivity"]:
            if self.current_network_override is not None:
                return 0, self.current_network_override, b""
            if self.active_vpn:
                vpn = b"  NetworkAgentInfo{network{900} handle{HOST_ONLY} ni{VPN CONNECTED extra: REDACTED} lp{ *** } nc{[ Transports: VPN Capabilities: INTERNET&VALIDATED]}}\n"
                return 0, CURRENT_WIFI.replace(b"    Requests:", vpn + b"    Requests:", 1), b""
            return 0, CURRENT_WIFI + (VPN_LISTENER if self.vendor_admission else b""), b""
        if args == ["shell", "ps", "-A"]:
            raw = b"USER PID NAME\nu0_a1 99 com.huawei.android.launcher\n"
            if self.instrument_alive:
                raw += b"u0_a44 444 com.aneb.probe.codex\n"
            return 0, raw, b""
        if args == ["shell", "pidof", "com.aneb.probe.codex"]:
            return (0, b"444\n", b"") if self.instrument_alive else (1, b"", b"")
        if args == ["shell", "cat", "/proc/444/stat"]:
            return 0, ("444 (com.aneb) S " + "0 " * 18 + "987654321\n").encode(), b""
        if args == ["shell", "cat", "/proc/444/status"]:
            return 0, b"Uid:\t10044\t10044\t10044\t10044\n", b""
        if args == ["shell", "cat", "/proc/444/cmdline"]:
            return 0, b"com.aneb.probe.codex\x00", b""
        if args == ["shell", "am", "force-stop", "com.aneb.probe.codex"]:
            self.instrument_alive = False
            return 0, b"", b""
        if args[:4] == ["shell", "settings", "get", "global"]:
            value = self.wifi_on if args[-1] == "wifi_on" else "1" if args[-1] == "mobile_data" else "0"
            return 0, (value + "\n").encode(), b""
        if args[:3] == ["shell", "svc", "wifi"]:
            self.wifi_on = "1" if args[-1] == "enable" else "0"
            return 0, b"", b""
        if args[:3] == ["shell", "pm", "path"]:
            if args[-1] == "com.aneb.probe.codex" and self.installed_debug is not None:
                return 0, b"package:/data/app/HOST_ONLY/base.apk\n", b""
            return 1, b"", b""
        if args[0] == "install":
            if "android-test" not in args[-1]:
                self.installed_debug = Path(args[-1]).read_bytes()
            return 0, b"Success\n", b""
        if args[:3] == ["shell", "am", "instrument"]:
            if self.require_native_abi and ("feature_release" not in args or "payload_sha256" not in args or "ui_contract_b64" not in args):
                return 1, b"INSTRUMENTATION_FAILED: NEW_FEATURE_RELEASE_REQUIRED\n", b""
            if self.require_native_abi:
                json.loads(base64.b64decode(args[args.index("ui_contract_b64") + 1]))
            run_id = args[args.index("run_id") + 1]
            network = args[args.index("network_condition") + 1] if "network_condition" in args else None
            terminal = {"state": "OBSERVATION_CLOSED", "run_id": run_id,
                        "network_condition": network, "send_calls": 1,
                        "owned_cleanup": True, "backend_session": "UNKNOWN"}
            preparation = "execution_mode" in args and args[args.index("execution_mode") + 1] == "PREPARATION_ONLY"
            if preparation:
                terminal.update(state="PREPARATION_CLOSED", execution_mode="PREPARATION_ONLY",
                    claim_scope="preparation_inspection_only", send_calls=0, capture="NOT_STARTED", send_selector="UNKNOWN")
                if self.preparation_unexpected_send:
                    terminal["send_calls"] = 1
            if self.unknown_send:
                terminal.update(state="OBSERVATION_UNKNOWN", send_calls="UNKNOWN")
            if self.cancel_during_capture:
                cancel_file.write_bytes(b"HOST_ONLY explicit cancellation\n")
                on_cancel()
                terminal.update(state="OBSERVATION_UNKNOWN", reason="CANCELLED_NO_RETRY")
            root = f"/sdcard/Android/data/com.aneb.probe.codex/files/tongyi-engineering/{run_id}"
            self.files[root] = {
                "TERMINAL.json": json.dumps(terminal).encode(),
                "context.xml": b"<hierarchy package='com.aliyun.tongyi'/>\n",
                "screen.png": b"HOST_ONLY_PNG",
                "video.mp4": b"HOST_ONLY_MP4",
            }
            if preparation:
                del self.files[root]["video.mp4"]
            if self.require_native_abi:
                self.instrument_alive = True
                self.files[root]["OWNED_INSTRUMENTATION.json"] = json.dumps({"package":"com.aneb.probe.codex",
                    "pid":"444", "uid":10044, "start_ticks":"987654321", "run_id":run_id}).encode()
            return (130 if self.cancel_during_capture else 0), ("INSTRUMENTATION_RESULT: aneb_terminal=" + json.dumps(terminal)
                       + "\nINSTRUMENTATION_CODE: -1\n").encode(), b""
        if args[0] == "pull":
            remote, dest = args[1:]
            if remote == "/data/app/HOST_ONLY/base.apk":
                Path(dest).write_bytes(self.installed_debug)
                return 0, b"1 file pulled\n", b""
            Path(dest).mkdir(parents=True)
            for name, raw in self.files[remote].items():
                if self.partial_pull_failure and name == "TERMINAL.json":
                    continue
                (Path(dest) / name).write_bytes(raw)
            if self.partial_pull_failure:
                return 1, b"partial originals pulled\n", b"HOST_ONLY transfer failure\n"
            return 0, b"4 files pulled\n", b""
        if args[0] == "uninstall":
            return 0, b"Success\n", b""
        return 0, b"", b""


class WholeEngineeringHostTest(unittest.TestCase):
    def test_preparation_scope_violation_retains_observed_send_instead_of_relabelling_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="PREPARATION_ONLY", ui_contract={})
            phone = ExternalPhone()
            phone.require_native_abi = True
            phone.preparation_unexpected_send = True
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PREPARATION_STOPPED",result["state"])
            self.assertEqual(1,result["attempts"][0]["send_calls"])
            self.assertEqual(1,result["send_calls"])
            self.assertEqual(1,sum("instrument" in argv for argv in phone.commands))

    def test_public_whole_entry_prepares_once_without_send_selector_or_network_change_and_restores_reader(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="PREPARATION_ONLY", ui_contract={
                "binding_scope": "root_current_807_ui_frame",
                "menu_argb_sha256": "18accc0d7ec5ea76e5add0fec6ce6898809bbf73fb4760604ac30f2ca25da89f",
                "attachment_resource_ids": [], "new_session_text_only_attachment_contract": True})
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = ExternalPhone()
            phone.require_native_abi = True
            phone.old_apk = old.read_bytes()
            phone.installed_debug = phone.old_apk
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PREPARATION_CLOSED", result["state"])
            self.assertEqual([0], [row["send_calls"] for row in result["attempts"]])
            self.assertTrue(result["original_reader_restored"])
            self.assertEqual(phone.old_apk, phone.installed_debug)
            self.assertEqual(1, sum("instrument" in argv for argv in phone.commands))
            self.assertFalse(any("svc" in argv or "clear" in argv for argv in phone.commands))
            self.assertFalse(any("uninstall" in argv and "com.aneb.probe.codex" in argv for argv in phone.commands))
            self.assertFalse(any(pin["path"].endswith("video.mp4") for pin in result["attempts"][0]["artifacts"]))
            self.assertEqual(result, json.loads((root / "actual" / "PREPARATION_RESULT.json").read_text()))

    def test_real_vendor_admission_dialect_collects_and_closes_complete_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = ExternalPhone()
            phone.vendor_admission = True
            phone.require_native_abi = True
            result = run_pair(self.release(root), root / "actual", system=phone)
            self.assertEqual("PAIR_OBSERVATION_CLOSED", result["state"])
            self.assertEqual([1, 1], [row["send_calls"] for row in result["attempts"]])
            self.assertTrue(all(row["owned_cleanup"] for row in result["attempts"]))
            self.assertFalse(phone.instrument_alive)

    def release(self, root):
        app, test = root / "debug.apk", root / "android-test.apk"
        app.write_bytes(b"HOST_ONLY_DEBUG_APK")
        test.write_bytes(b"HOST_ONLY_TEST_APK")
        tools = {}
        for key in ("adb", "java", "apksigner_jar", "aapt"):
            path = root / (key + ".HOST_ONLY")
            path.write_bytes(b"HOST_ONLY_TOOL")
            tools[key] = str(path)
            tools[key + "_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        return {
            **tools, "live_release_authorized": True, "serial": "HOST_ONLY",
            "approved_debug_certificate_sha256": "6" * 64,
            "debug_apk": str(app), "test_apk": str(test),
            "debug_apk_sha256": hashlib.sha256(app.read_bytes()).hexdigest(),
            "test_apk_sha256": hashlib.sha256(test.read_bytes()).hexdigest(),
            "pair_id": "host-first-pair", "ui_contract": {"ordinary_text": "快速"},
        }

    def test_same_public_runner_collects_seals_and_closes_W1_then_C1(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            phone = ExternalPhone()
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PAIR_OBSERVATION_CLOSED", result["state"])
            self.assertEqual(["W1", "C1"], [row["network_condition"] for row in result["attempts"]])
            self.assertEqual([1, 1], [row["send_calls"] for row in result["attempts"]])
            self.assertTrue(all(row["owned_cleanup"] for row in result["attempts"]))
            for row in result["attempts"]:
                for pin in row["artifacts"]:
                    raw = (root / "actual" / pin["path"]).read_bytes()
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), pin["sha256"])
            self.assertEqual(0, result["formal_new_slots"])
            self.assertEqual(result, json.loads((root / "actual" / "PAIR_RESULT.json").read_text()))

    def test_unknown_W1_send_seals_failure_skips_C1_and_refuses_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = ExternalPhone()
            phone.unknown_send = True
            release = self.release(root)
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PAIR_STOPPED", result["state"])
            self.assertEqual("UNKNOWN", result["attempts"][0]["send_calls"])
            self.assertEqual(["OBSERVATION_UNKNOWN", "NOT_RUN"], [row["state"] for row in result["attempts"]])
            self.assertTrue(result["attempts"][0]["artifacts"])
            executed = len(phone.commands)
            with self.assertRaises(FileExistsError):
                run_pair(release, root / "actual", system=phone)
            self.assertEqual(executed, len(phone.commands))

    def test_fixed_existing_reader_is_updated_and_restored_without_uninstall_or_data_clear(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = ExternalPhone()
            phone.old_apk = old.read_bytes()
            phone.installed_debug = phone.old_apk
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PAIR_OBSERVATION_CLOSED", result["state"])
            self.assertEqual(phone.old_apk, phone.installed_debug)
            self.assertFalse(any("uninstall" in argv and "com.aneb.probe.codex" in argv for argv in phone.commands))
            self.assertFalse(any("clear" in argv for argv in phone.commands))
            self.assertTrue(result["original_reader_restored"])

    def test_real_native_entry_binding_and_host_owned_process_stop_before_postflight(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = ExternalPhone()
            phone.require_native_abi = True
            result = run_pair(self.release(root), root / "actual", system=phone)
            self.assertEqual("PAIR_OBSERVATION_CLOSED", result["state"])
            self.assertFalse(phone.instrument_alive)
            invocations = [argv for argv in phone.commands if "instrument" in argv]
            self.assertEqual(2, len(invocations))
            self.assertTrue(all("tongyi-engineering-807" in argv for argv in invocations))
            self.assertEqual(2, sum("force-stop" in argv for argv in phone.commands))

    def test_pair_changes_only_approved_wifi_and_restores_original_setting(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = ExternalPhone()
            release = self.release(root)
            release["network_transition_authorized"] = True
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PAIR_OBSERVATION_CLOSED", result["state"])
            self.assertEqual("1", phone.wifi_on)
            wifi = [argv[-1] for argv in phone.commands if "svc" in argv and "wifi" in argv]
            self.assertEqual(["enable", "disable", "enable"], wifi)
            self.assertTrue(result["network_settings_restored"])

    def test_unknown_foreground_or_active_vpn_refuses_all_install_and_input(self):
        for field in ("foreground_unknown", "active_vpn"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                phone = ExternalPhone()
                setattr(phone, field, True)
                result = run_pair(self.release(root), root / "actual", system=phone)
                self.assertEqual("PAIR_STOPPED", result["state"])
                self.assertFalse(any("install" in argv or "instrument" in argv for argv in phone.commands))

    def test_unknown_current_agents_or_default_refuse_all_install_and_input(self):
        unknown = {
            "empty": b"",
            "no_default": CURRENT_WIFI.replace(b"Active default network: 367\n", b""),
            "no_agents": b"Active default network: 367\nCurrent Networks:\nNetwork Requests:\n" + VPN_LISTENER,
            "unknown_transport": CURRENT_WIFI.replace(b"Transports: WIFI Capabilities:", b"Transports: UNKNOWN Capabilities:"),
            "default_not_current": CURRENT_WIFI.replace(b"Active default network: 367", b"Active default network: 999"),
            "unknown_agent_structure": CURRENT_WIFI.replace(b"\nStatus for known UIDs:", b"\nUnknownCurrentAgent\nStatus for known UIDs:"),
        }
        for name, reply in unknown.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                phone = ExternalPhone()
                phone.vendor_admission = True
                phone.current_network_override = reply
                result = run_pair(self.release(root), root / "actual", system=phone)
                self.assertEqual("PAIR_STOPPED", result["state"])
                self.assertFalse(any("install" in argv or "instrument" in argv for argv in phone.commands))

    def test_failed_partial_original_transfer_is_sealed_and_C1_is_not_run(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = ExternalPhone()
            phone.partial_pull_failure = True
            phone.require_native_abi = True
            result = run_pair(self.release(root), root / "actual", system=phone)
            self.assertEqual("PAIR_STOPPED", result["state"])
            self.assertTrue(result["attempts"][0]["artifacts"])
            self.assertEqual("NOT_RUN", result["attempts"][1]["state"])
            self.assertFalse(phone.instrument_alive)
            self.assertEqual(1, sum("instrument" in argv for argv in phone.commands))
            self.assertFalse(any("uninstall" in argv and "com.aneb.probe.codex" in argv for argv in phone.commands))

    def test_explicit_cancel_requests_native_stop_preserves_raw_and_never_starts_C1(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = ExternalPhone()
            phone.require_native_abi = True
            phone.cancel_during_capture = True
            result = run_pair(self.release(root), root / "actual", system=phone)
            self.assertEqual("PAIR_STOPPED", result["state"])
            self.assertEqual("NOT_RUN", result["attempts"][1]["state"])
            self.assertTrue(result["attempts"][0]["artifacts"])
            self.assertFalse(phone.instrument_alive)
            self.assertTrue(any("touch" in argv and argv[-1].endswith("/CANCEL") for argv in phone.commands))

    def test_no_new_feature_release_has_zero_external_actions(self):
        with tempfile.TemporaryDirectory() as directory:
            phone = ExternalPhone()
            result = run_pair({"live_release_authorized": False}, Path(directory) / "actual", system=phone)
            self.assertEqual("EXPLICIT_NEW_FEATURE_RELEASE_REQUIRED", result["reason"])
            self.assertEqual([], phone.commands)

    def test_unattributed_installed_reader_is_not_overwritten_or_uninstalled(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phone = ExternalPhone()
            phone.installed_debug = b"HOST_ONLY_UNATTRIBUTED_READER"
            result = run_pair(self.release(root), root / "actual", system=phone)
            self.assertEqual("PAIR_STOPPED", result["state"])
            self.assertEqual("UNKNOWN_EXISTING_PACKAGE_NOT_OVERWRITTEN", result["reason"])
            self.assertFalse(any("install" in argv or "uninstall" in argv or "instrument" in argv for argv in phone.commands))


if __name__ == "__main__":
    unittest.main()
