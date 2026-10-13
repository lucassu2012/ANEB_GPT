"""HOST_ONLY: actual runner/sealing; only the external phone/OS is emulated."""

import hashlib
import base64
import json
from pathlib import Path
import tempfile
import unittest

from scripts.tongyi_ordinary_text_engineering_runner import PAYLOAD, PAYLOAD_SHA256, run_pair


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
        self.install_stdout = b"Success\n"
        self.preparation_unknown_reason = None
        self.companion_installed = False

    def qualification_originals(self, terminal):
        """Synthetic external Native output, not XML/PNG equivalence or device evidence."""
        files = {}
        records = []
        for index, stage in enumerate(("HOME", "DRAWER", "NEW_EMPTY", "EDITOR_EMPTY", "OWN_DRAFT", "OWN_CLEARED")):
            observation = "HOST_ONLY_" + terminal["run_id"] + "_" + stage
            owner = "HOST_ONLY_OWNED_TARGET_SESSION"
            text = PAYLOAD if stage == "OWN_DRAFT" else ""
            native = {"source": "HOST_ONLY_EXTERNAL_NATIVE", "observation_id": observation,
                      "stage": stage, "owner": owner, "display": [1200, 2640, 0],
                      "root_bounds": [0, 0, 1200, 2640],
                      "nodes": [{"id": "HOST_ONLY_EDITOR", "parent": "HOST_ONLY_ROOT",
                                 "package": "com.aliyun.tongyi", "editable": True,
                                 "bounds": [50, 2320, 1150, 2520], "text": text}]}
            prefix = stage.lower() + "-"
            stage_files = {
                prefix + "native-before.json": json.dumps({**native, "epoch": index * 4}, ensure_ascii=False).encode(),
                prefix + "hierarchy.xml": ("<hierarchy source='HOST_ONLY_RAW_XML' observation='" + observation
                    + "'><node package='com.aliyun.tongyi' text='" + text + "'/></hierarchy>\n").encode(),
                prefix + "screen.png": ("HOST_ONLY_NOT_A_REAL_PNG_" + observation).encode(),
                prefix + "native-after.json": json.dumps({**native, "epoch": index * 4 + 3}, ensure_ascii=False).encode(),
            }
            records.append({"stage": stage, "observation_id": observation,
                "owner_before": owner, "owner_after": owner, "display": [1200, 2640, 0],
                "root_bounds": [0, 0, 1200, 2640], "source": "HOST_ONLY_EXTERNAL_OS",
                "sampling_order": ["NATIVE_BEFORE", "RAW_XML", "PNG", "NATIVE_AFTER"],
                "atomic_same_frame": False, "reference_accepted": False,
                "artifacts": [{"path": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
                              for name, raw in stage_files.items()]})
            files.update(stage_files)
        terminal.update(retained_stages=records)
        files["TERMINAL.json"] = json.dumps(terminal, ensure_ascii=False).encode()
        return files

    def source_originals(self, terminal):
        """Two opaque external OS acquisitions, not real Native/XML/PNG/device evidence."""
        files, records = {}, []
        for index in (1, 2):
            label = "source-home-" + str(index)
            observation = "HOST_ONLY_" + terminal["run_id"] + "_" + label
            owner = "HOST_ONLY_OWNED_TARGET_SESSION"
            native = {"source": "HOST_ONLY_EXTERNAL_NATIVE", "observation_id": observation,
                "stage": "HOME", "source_label": label, "owner": owner, "display": [1200, 2640, 0],
                "root_bounds": [0, 0, 1200, 2640],
                "nodes": [{"id": "HOST_ONLY_ROOT", "parent": None, "package": "com.aliyun.tongyi"}]}
            stage_files = {
                label + "-native-before.json": json.dumps({**native, "elapsed_realtime_nanos": str(index * 10)}).encode(),
                label + "-hierarchy.xml": ("<hierarchy source='HOST_ONLY_RAW_XML' observation='" + observation + "'/>\n").encode(),
                label + "-screen.png": ("HOST_ONLY_NOT_A_REAL_PNG_" + observation).encode(),
                label + "-native-after.json": json.dumps({**native, "elapsed_realtime_nanos": str(index * 10 + 3)}).encode(),
            }
            records.append({"stage": "HOME", "source_label": label, "observation_id": observation,
                "owner_before": owner, "owner_after": owner, "display": [1200, 2640, 0], "root_bounds": [0, 0, 1200, 2640],
                "source": "HOST_ONLY_EXTERNAL_OS", "atomic_same_frame": False, "reference_accepted": False,
                "sampling_order": ["NATIVE_BEFORE", "RAW_XML", "PNG", "NATIVE_AFTER"],
                "native_before_nanos": str(index * 10), "xml_nanos": str(index * 10 + 1),
                "png_nanos": str(index * 10 + 2), "native_after_nanos": str(index * 10 + 3),
                "menu_argb_sha256": "f" * 64,
                "artifacts": [{"path": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
                              for name, raw in stage_files.items()]})
            files.update(stage_files)
        terminal.update(retained_sources=records, retained_stages=[], accepted_stages=[])
        files["TERMINAL.json"] = json.dumps(terminal).encode()
        return files

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
            else:
                self.companion_installed = True
            return 0, self.install_stdout, b""
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
            qualification = "execution_mode" in args and args[args.index("execution_mode") + 1] == "UI_REFERENCE_QUALIFICATION"
            source_inspection = "execution_mode" in args and args[args.index("execution_mode") + 1] == "UI_SOURCE_INSPECTION"
            if preparation:
                terminal.update(state="PREPARATION_CLOSED", execution_mode="PREPARATION_ONLY",
                    claim_scope="preparation_inspection_only", send_calls=0, capture="NOT_STARTED", send_selector="UNKNOWN")
                if self.preparation_unexpected_send:
                    terminal["send_calls"] = 1
                if self.preparation_unknown_reason is not None:
                    terminal.update(state="PREPARATION_UNKNOWN", reason=self.preparation_unknown_reason)
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
                if self.preparation_unknown_reason is not None:
                    del self.files[root]["context.xml"]
                    del self.files[root]["screen.png"]
            if qualification:
                terminal.update(state="UI_REFERENCE_QUALIFICATION_CLOSED", execution_mode="UI_REFERENCE_QUALIFICATION",
                    claim_scope="ui_reference_qualification_only", send_calls=0, capture="NOT_STARTED",
                    metrics=None, formal_new_slots=0, reference_accepted=False,
                    feature_release=args[args.index("feature_release") + 1],
                    payload_sha256=args[args.index("payload_sha256") + 1],
                    ui_contract=json.loads(base64.b64decode(args[args.index("ui_contract_b64") + 1])))
                self.files[root] = self.qualification_originals(terminal)
            if source_inspection:
                terminal.update(state="UI_SOURCE_INSPECTION_CLOSED", execution_mode="UI_SOURCE_INSPECTION",
                    claim_scope="ui_source_inspection_only", send_calls=0, capture="NOT_STARTED",
                    metrics=None, formal_new_slots=0, reference_accepted=False,
                    feature_release=args[args.index("feature_release") + 1],
                    payload_sha256=args[args.index("payload_sha256") + 1],
                    ui_contract=json.loads(base64.b64decode(args[args.index("ui_contract_b64") + 1])))
                self.files[root] = self.source_originals(terminal)
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
            if not Path(dest).parent.is_dir():
                return 1, b"", b"HOST_ONLY adb: cannot create destination: No such file or directory\n"
            Path(dest).mkdir()
            for name, raw in self.files[remote].items():
                if self.partial_pull_failure and name == "TERMINAL.json":
                    continue
                (Path(dest) / name).write_bytes(raw)
            if self.partial_pull_failure:
                return 1, b"partial originals pulled\n", b"HOST_ONLY transfer failure\n"
            return 0, b"4 files pulled\n", b""
        if args[0] == "uninstall":
            if args[-1] == "com.aneb.probe.codex.test":
                self.companion_installed = False
            return 0, b"Success\n", b""
        return 0, b"", b""


class WholeEngineeringHostTest(unittest.TestCase):
    def test_public_source_inspection_closes_two_read_only_home_sources_without_accepting_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="UI_SOURCE_INSPECTION", network_transition_authorized=False,
                ui_contract={"binding_scope": "root_current_807_ui_frame", "menu_argb_sha256": "18accc0d7ec5ea76e5add0fec6ce6898809bbf73fb4760604ac30f2ca25da89f",
                             "attachment_resource_ids": [], "new_session_text_only_attachment_contract": True})
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = ExternalPhone()
            phone.require_native_abi = True
            phone.old_apk = phone.installed_debug = old.read_bytes()
            output = root / "actual"

            result = run_pair(release, output, system=phone)

            self.assertEqual("UI_SOURCE_INSPECTION_CLOSED", result["state"], json.dumps(result, sort_keys=True))
            self.assertEqual(result, json.loads((output / "UI_SOURCE_INSPECTION_RESULT.json").read_text(encoding="utf-8")))
            self.assertEqual(0, result["send_calls"])
            self.assertEqual("NOT_STARTED", result["capture"])
            self.assertIsNone(result["metrics"])
            self.assertFalse(result["reference_accepted"])
            self.assertEqual([], result["accepted_stages"])
            self.assertEqual(0, result["formal_new_slots"])
            self.assertEqual(1, len(result["attempts"]))
            attempt = result["attempts"][0]
            originals = output / attempt["run_id"] / "originals"
            terminal = json.loads((originals / "TERMINAL.json").read_text(encoding="utf-8"))
            records = terminal["retained_sources"]
            self.assertEqual(["source-home-1", "source-home-2"], [record["source_label"] for record in records])
            self.assertEqual(2, len({record["observation_id"] for record in records}))
            self.assertEqual(records[0]["menu_argb_sha256"], records[1]["menu_argb_sha256"])
            self.assertNotEqual(release["ui_contract"]["menu_argb_sha256"], records[0]["menu_argb_sha256"])
            for record in records:
                self.assertEqual(4, len(record["artifacts"]))
                for pin in record["artifacts"]:
                    raw = (originals / pin["path"]).read_bytes()
                    self.assertEqual(len(raw), pin["bytes"])
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), pin["sha256"])
            invocation, = [argv for argv in phone.commands if "instrument" in argv]
            self.assertEqual("UI_SOURCE_INSPECTION", invocation[invocation.index("execution_mode") + 1])
            self.assertNotIn("network_condition", invocation)
            self.assertFalse(any("svc" in argv or "screenrecord" in argv or "clear" in argv for argv in phone.commands))
            self.assertFalse(any("uninstall" in argv and "com.aneb.probe.codex" in argv for argv in phone.commands))
            self.assertTrue(result["original_reader_restored"])
            self.assertEqual(phone.old_apk, phone.installed_debug)
            self.assertFalse(phone.instrument_alive or phone.companion_installed)
            self.assertTrue((output / (attempt["run_id"] + "-SOURCE_CONSUMED.json")).is_file())
            executed = len(phone.commands)
            with self.assertRaises(FileExistsError):
                run_pair(release, output, system=phone)
            self.assertEqual(executed, len(phone.commands))

    def test_public_source_missing_original_stops_and_preserves_partial_sources_and_reader_data(self):
        class MissingSourceOriginal(ExternalPhone):
            def source_originals(self, terminal):
                files = super().source_originals(terminal)
                del files["source-home-2-screen.png"]  # External file/transfer failure, not a mocked validator.
                return files

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="UI_SOURCE_INSPECTION", network_transition_authorized=False,
                ui_contract={"binding_scope": "root_current_807_ui_frame", "menu_argb_sha256": "18accc0d7ec5ea76e5add0fec6ce6898809bbf73fb4760604ac30f2ca25da89f",
                             "attachment_resource_ids": [], "new_session_text_only_attachment_contract": True})
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = MissingSourceOriginal()
            phone.require_native_abi = True
            phone.old_apk = phone.installed_debug = old.read_bytes()
            output = root / "actual"

            result = run_pair(release, output, system=phone)

            self.assertEqual("UI_SOURCE_INSPECTION_STOPPED", result["state"])
            self.assertEqual("QUALIFICATION_STAGE_ORIGINAL_MISSING", result["reason"])
            self.assertEqual(0, result["observed_native_send_calls"])
            self.assertEqual("UNKNOWN", result["send_calls"])
            self.assertFalse(result["reference_accepted"])
            self.assertEqual([], result["accepted_stages"])
            self.assertEqual(1, len(result["attempts"]))
            attempt = result["attempts"][0]
            originals = output / attempt["run_id"] / "originals"
            self.assertFalse((originals / "source-home-2-screen.png").exists())
            self.assertTrue((originals / "source-home-1-screen.png").is_file())
            self.assertEqual(7, len([path for path in originals.iterdir() if path.name.startswith("source-home-")]))
            for pin in attempt["artifacts"]:
                raw = (output / pin["path"]).read_bytes()
                self.assertEqual(len(raw), pin["bytes"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), pin["sha256"])
            self.assertEqual(1, len([argv for argv in phone.commands if "instrument" in argv]))
            self.assertFalse(any("svc" in argv or "screenrecord" in argv or "clear" in argv for argv in phone.commands))
            self.assertTrue(result["original_reader_restored"])
            self.assertEqual(phone.old_apk, phone.installed_debug)
            self.assertFalse(phone.instrument_alive or phone.companion_installed)

    def test_public_source_rejection_diagnostic_is_pinned_without_becoming_a_second_source(self):
        class RejectedReadinessPhone(ExternalPhone):
            def source_originals(self, terminal):
                files = super().source_originals(terminal)
                terminal.update(state="UI_SOURCE_INSPECTION_UNKNOWN", reason="UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED",
                    retained_sources=terminal["retained_sources"][:1],
                    source_rejection={"phase": "SECOND_READINESS", "source_label": "source-home-2",
                                      "returned_frame": True, "retention_attempted": True, "diagnostic_retained": True,
                                      "retention_failure": None})
                for name in list(files):
                    if name.startswith("source-home-2-"):
                        del files[name]
                # Synthetic external Native/file output only, not the absent phone readiness Frame or SDK evidence.
                native_name = "source-home-2-rejected-native.json"
                native_raw = json.dumps({"kind": "HOST_ONLY_SYNTHETIC_CACHED_PROJECTION_NOT_NATIVE_SDK",
                    "phase": "SECOND_READINESS", "source_label": "source-home-2", "diagnostic_only": True,
                    "accepted_stage": False, "accepted_source": False, "reference_accepted": False,
                    "nodes": [{"id": "HOST_ONLY_UNKNOWN_CONVERSATION", "text": "PURE_SYNTHETIC_UNKNOWN_CONVERSATION"}]}).encode()
                native_pin = {"path": native_name, "bytes": len(native_raw), "sha256": hashlib.sha256(native_raw).hexdigest()}
                receipt_name = "source-rejected-observation-retention.json"
                receipt_raw = json.dumps({"phase": "SECOND_READINESS", "source_label": "source-home-2",
                    "diagnostic_only": True, "originals_readback_complete": True,
                    "sampling_claim": "HOST_ONLY_ONE_CACHED_NATIVE_NO_XML_NO_PNG_NOT_A_SOURCE_PAIR",
                    "artifacts": [native_pin]}).encode()
                terminal["rejected_observation_retention"] = {"status": "RETAINED", "phase": "SECOND_READINESS",
                    "source_label": "source-home-2", "diagnostic_only": True, "accepted_stage": False,
                    "accepted_source": False, "reference_accepted": False, "artifacts": [native_pin],
                    "receipt": {"path": receipt_name, "bytes": len(receipt_raw), "sha256": hashlib.sha256(receipt_raw).hexdigest()}}
                files.update({native_name: native_raw, receipt_name: receipt_raw,
                              "TERMINAL.json": json.dumps(terminal).encode()})
                return files

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="UI_SOURCE_INSPECTION", network_transition_authorized=False,
                ui_contract={"binding_scope": "root_current_807_ui_frame", "menu_argb_sha256": "18accc0d7ec5ea76e5add0fec6ce6898809bbf73fb4760604ac30f2ca25da89f",
                             "attachment_resource_ids": [], "new_session_text_only_attachment_contract": True})
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = RejectedReadinessPhone()
            phone.require_native_abi = True
            phone.old_apk = phone.installed_debug = old.read_bytes()
            output = root / "actual"

            result = run_pair(release, output, system=phone)

            self.assertEqual("UI_SOURCE_INSPECTION_STOPPED", result["state"])
            self.assertEqual("QUALIFICATION_SIX_ORDERED_ORIGINALS_REQUIRED", result["reason"])
            self.assertEqual("UNKNOWN", result["send_calls"])
            self.assertEqual(0, result["observed_native_send_calls"])
            self.assertFalse(result["reference_accepted"])
            self.assertEqual([], result["accepted_stages"])
            self.assertEqual(1, len(result["attempts"]))
            attempt = result["attempts"][0]
            self.assertEqual("UI_SOURCE_INSPECTION_UNKNOWN", attempt["state"])
            self.assertEqual("UNKNOWN_EXISTING_CONVERSATION_NOT_TOUCHED", attempt["native_reason"])
            self.assertTrue(attempt["native_owned_cleanup"])
            originals = output / attempt["run_id"] / "originals"
            terminal = json.loads((originals / "TERMINAL.json").read_text(encoding="utf-8"))
            self.assertEqual(["source-home-1"], [record["source_label"] for record in terminal["retained_sources"]])
            self.assertEqual([], terminal["retained_stages"])
            for suffix in ("native-before.json", "hierarchy.xml", "screen.png", "native-after.json"):
                self.assertFalse((originals / ("source-home-2-" + suffix)).exists())
            pins = {pin["path"]: pin for pin in attempt["artifacts"]}
            for name in ("source-home-2-rejected-native.json", "source-rejected-observation-retention.json"):
                actual = originals / name
                key = actual.relative_to(output).as_posix()
                raw = actual.read_bytes()
                self.assertEqual(len(raw), pins[key]["bytes"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), pins[key]["sha256"])
            self.assertEqual(1, len([argv for argv in phone.commands if "instrument" in argv]))
            self.assertFalse(any("svc" in argv or "screenrecord" in argv or "clear" in argv for argv in phone.commands))
            self.assertTrue(result["original_reader_restored"])
            self.assertEqual(phone.old_apk, phone.installed_debug)
            self.assertFalse(phone.instrument_alive or phone.companion_installed)

    def test_public_ui_reference_qualification_closes_one_input_only_transaction_and_seals_originals(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="UI_REFERENCE_QUALIFICATION", network_transition_authorized=False,
                ui_contract={"binding_scope": "root_current_807_ui_frame", "menu_argb_sha256": "18accc02a3df6f28b92d60d6a8f345328fbc6feea04dbe3fd1e3e8fc43e8c89f",
                             "attachment_resource_ids": [], "new_session_text_only_attachment_contract": True})
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = ExternalPhone()
            phone.require_native_abi = True
            phone.old_apk = old.read_bytes()
            phone.installed_debug = phone.old_apk
            output = root / "actual"

            result = run_pair(release, output, system=phone)

            self.assertEqual("UI_REFERENCE_QUALIFICATION_CLOSED", result["state"], json.dumps(result, sort_keys=True))
            self.assertEqual(result, json.loads((output / "UI_REFERENCE_QUALIFICATION_RESULT.json").read_text(encoding="utf-8")))
            self.assertFalse((output / "PAIR_RESULT.json").exists())
            self.assertFalse((output / "PREPARATION_RESULT.json").exists())
            self.assertEqual("UI_REFERENCE_QUALIFICATION", result["execution_mode"])
            self.assertEqual(0, result["send_calls"])
            self.assertEqual("NOT_STARTED", result["capture"])
            self.assertIsNone(result["metrics"])
            self.assertEqual(0, result["formal_new_slots"])
            self.assertFalse(result["reference_accepted"])
            self.assertEqual(1, len(result["attempts"]))
            attempt = result["attempts"][0]
            self.assertEqual("UI_REFERENCE_QUALIFICATION_CLOSED", attempt["state"])
            self.assertIsNone(attempt["network_condition"])
            self.assertEqual(0, attempt["send_calls"])
            self.assertTrue(attempt["owned_cleanup"])
            originals = output / attempt["run_id"] / "originals"
            terminal = json.loads((originals / "TERMINAL.json").read_text(encoding="utf-8"))
            self.assertEqual(attempt["run_id"], terminal["run_id"])
            self.assertEqual(result["execution_mode"], terminal["execution_mode"])
            self.assertEqual("tongyi-engineering-807", terminal["feature_release"])
            self.assertEqual(PAYLOAD_SHA256, terminal["payload_sha256"])
            self.assertEqual(release["ui_contract"], terminal["ui_contract"])
            self.assertEqual(0, terminal["send_calls"])
            self.assertEqual("NOT_STARTED", terminal["capture"])
            self.assertTrue(terminal["owned_cleanup"])
            self.assertFalse(terminal["reference_accepted"])
            records = terminal["retained_stages"]
            self.assertEqual(["HOME", "DRAWER", "NEW_EMPTY", "EDITOR_EMPTY", "OWN_DRAFT", "OWN_CLEARED"],
                             [record["stage"] for record in records])
            self.assertEqual(6, len({record["observation_id"] for record in records}))
            retained = {pin["path"]: pin for pin in attempt["artifacts"]}
            self.assertEqual({str(path.relative_to(output)).replace("\\", "/") for path in originals.iterdir()}, set(retained))
            for record in records:
                self.assertEqual("HOST_ONLY_EXTERNAL_OS", record["source"])
                self.assertEqual(record["owner_before"], record["owner_after"])
                self.assertEqual([1200, 2640, 0], record["display"])
                self.assertEqual([0, 0, 1200, 2640], record["root_bounds"])
                self.assertEqual(["NATIVE_BEFORE", "RAW_XML", "PNG", "NATIVE_AFTER"], record["sampling_order"])
                self.assertFalse(record["atomic_same_frame"])
                self.assertFalse(record["reference_accepted"])
                self.assertEqual(4, len(record["artifacts"]))
                for stage_pin in record["artifacts"]:
                    actual_path = originals / stage_pin["path"]
                    raw = actual_path.read_bytes()
                    key = str(actual_path.relative_to(output)).replace("\\", "/")
                    self.assertEqual(len(raw), stage_pin["bytes"])
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), stage_pin["sha256"])
                    self.assertEqual(stage_pin["bytes"], retained[key]["bytes"])
                    self.assertEqual(stage_pin["sha256"], retained[key]["sha256"])
            for artifact_pin in retained.values():
                raw = (output / artifact_pin["path"]).read_bytes()
                self.assertEqual(len(raw), artifact_pin["bytes"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), artifact_pin["sha256"])
            own_draft = json.loads((originals / "own_draft-native-before.json").read_text(encoding="utf-8"))
            own_cleared = json.loads((originals / "own_cleared-native-after.json").read_text(encoding="utf-8"))
            self.assertEqual(239, len(own_draft["nodes"][0]["text"].encode()))
            self.assertEqual(PAYLOAD, own_draft["nodes"][0]["text"])
            self.assertEqual("", own_cleared["nodes"][0]["text"])
            invocations = [argv for argv in phone.commands if "instrument" in argv]
            self.assertEqual(1, len(invocations))
            invocation = invocations[0]
            self.assertEqual("UI_REFERENCE_QUALIFICATION", invocation[invocation.index("execution_mode") + 1])
            self.assertEqual(attempt["run_id"], invocation[invocation.index("run_id") + 1])
            self.assertEqual("tongyi-engineering-807", invocation[invocation.index("feature_release") + 1])
            self.assertEqual(PAYLOAD_SHA256, invocation[invocation.index("payload_sha256") + 1])
            self.assertEqual(release["ui_contract"], json.loads(base64.b64decode(invocation[invocation.index("ui_contract_b64") + 1])))
            self.assertNotIn("network_condition", invocation)
            self.assertFalse(any("svc" in argv or "screenrecord" in argv or "clear" in argv for argv in phone.commands))
            self.assertFalse(any("uninstall" in argv and "com.aneb.probe.codex" in argv for argv in phone.commands))
            self.assertFalse(any(path.suffix == ".mp4" or path.name in ("SEND_INTENT.json", "OWNED_CAPTURE.json")
                                 for path in originals.iterdir()))
            self.assertFalse(phone.instrument_alive)
            self.assertFalse(phone.companion_installed)
            self.assertIn({"package": "com.aneb.probe.codex.test", "absent": True}, result["package_cleanup"])
            self.assertTrue(result["original_reader_restored"])
            self.assertEqual(phone.old_apk, phone.installed_debug)
            self.assertEqual(phone.old_apk, (output / "reader-restored.apk").read_bytes())
            self.assertEqual(release["original_debug_apk_sha256"], hashlib.sha256((output / "reader-restored.apk").read_bytes()).hexdigest())
            executed = len(phone.commands)
            with self.assertRaises(FileExistsError):
                run_pair(release, output, system=phone)
            self.assertEqual(executed, len(phone.commands))

    def test_public_preparation_materializes_native_unknown_without_fabricating_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="PREPARATION_ONLY", ui_contract={})
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = ExternalPhone()
            phone.require_native_abi = True
            phone.old_apk = old.read_bytes()
            phone.installed_debug = phone.old_apk
            phone.install_stdout = b"Performing Streamed Install\r\nSuccess\r\n"
            phone.preparation_unknown_reason = "CURRENT_VIEWPORT_MISMATCH"
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PREPARATION_UNKNOWN", result["attempts"][0]["state"])
            self.assertEqual("PREPARATION_STOPPED", result["state"])
            self.assertEqual(0, result["send_calls"])
            self.assertTrue(result["attempts"][0]["owned_cleanup"])
            self.assertTrue(result["original_reader_restored"])
            self.assertEqual(release["original_debug_apk_sha256"], hashlib.sha256((root / "actual" / "reader-restored.apk").read_bytes()).hexdigest())
            originals = root / "actual" / (release["pair_id"] + "-PREP") / "originals"
            terminal = json.loads((originals / "TERMINAL.json").read_text())
            self.assertEqual("CURRENT_VIEWPORT_MISMATCH", terminal["reason"])
            self.assertEqual(0, terminal["send_calls"])
            self.assertTrue(terminal["owned_cleanup"])
            self.assertTrue(any(pin["path"].endswith("TERMINAL.json") for pin in result["attempts"][0]["artifacts"]))
            self.assertFalse((originals / (release["pair_id"] + "-PREP")).exists())
            self.assertFalse(any("clear" in argv or ("uninstall" in argv and "com.aneb.probe.codex" in argv) for argv in phone.commands))

    def test_public_preparation_rejects_unknown_failure_prelude_even_with_success_suffix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="PREPARATION_ONLY", ui_contract={})
            phone = ExternalPhone()
            phone.install_stdout = b"Failure [HOST_ONLY_UNKNOWN_INSTALL_FAILURE]\r\nSuccess\r\n"
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PREPARATION_STOPPED", result["state"])
            self.assertEqual("INSTALL_FAILED", result["reason"])
            self.assertEqual(0, result["send_calls"])
            self.assertFalse(any("instrument" in argv for argv in phone.commands))

    def test_public_preparation_streamed_install_closes_and_fresh_verifies_original_reader(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = self.release(root)
            release.update(execution_mode="PREPARATION_ONLY", ui_contract={})
            old = root / "old-reader.apk"
            old.write_bytes(b"HOST_ONLY_ORIGINAL_READER_APK")
            release.update(original_debug_apk=str(old), original_debug_apk_sha256=hashlib.sha256(old.read_bytes()).hexdigest())
            phone = ExternalPhone()
            phone.require_native_abi = True
            phone.old_apk = old.read_bytes()
            phone.installed_debug = phone.old_apk
            phone.install_stdout = b"Performing Streamed Install\r\nSuccess\r\n"
            result = run_pair(release, root / "actual", system=phone)
            self.assertEqual("PREPARATION_CLOSED", result["state"])
            self.assertTrue(result["original_reader_restored"])
            restored = root / "actual" / "reader-restored.apk"
            self.assertEqual(release["original_debug_apk_sha256"], hashlib.sha256(restored.read_bytes()).hexdigest())
            self.assertEqual([0], [row["send_calls"] for row in result["attempts"]])
            self.assertFalse(any("clear" in argv or ("uninstall" in argv and "com.aneb.probe.codex" in argv) for argv in phone.commands))
            self.assertEqual(result, json.loads((root / "actual" / "PREPARATION_RESULT.json").read_text()))

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
