"""Private Tongyi engineering collection. No device action at import time."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

PAYLOAD = "请给出三条整理书桌的实用建议，分别涉及纸张、文具和桌面清洁。每条单独一行，每行约15个汉字。只写这三行，不加标题、编号或额外解释。请直接回答，不搜索、不调用工具。"
PAYLOAD_SHA256 = "f1e312c9123478fcf64c9ad056a436a493f6bd5dde09af029fc8ad62186efac5"
TARGET = "com.aliyun.tongyi"
DEBUG_PACKAGE = "com.aneb.probe.codex"
TEST_PACKAGE = DEBUG_PACKAGE + ".test"
INSTRUMENTATION_CLASS = "com.aneb.probe.engineering.TongyiWholeEngineeringInstrumentation"
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


class SubprocessSystem:
    """Explicit argv only. Constructed only after an authorized CLI release."""

    def run(self, argv, timeout, *, cancel_file=None, on_cancel=None):
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        deadline = time.monotonic() + timeout
        stopped = False
        try:
            while True:
                if not stopped and cancel_file is not None and cancel_file.exists():
                    stopped = True
                    if on_cancel is not None:
                        on_cancel()
                    deadline = min(deadline, time.monotonic() + 20)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    if not stopped and on_cancel is not None:
                        on_cancel()
                    process.kill() # Only this host argv child. Android identity cleanup is separate.
                    stdout, stderr = process.communicate()
                    self.last_run = {"pid": process.pid, "native_returncode": process.returncode,
                                     "direct_child_exited": True, "stdout_eof": True, "stderr_eof": True}
                    return 124, stdout, stderr
                try:
                    stdout, stderr = process.communicate(timeout=min(0.25, remaining))
                    self.last_run = {"pid": process.pid, "native_returncode": process.returncode,
                                     "direct_child_exited": True, "stdout_eof": True, "stderr_eof": True}
                    return process.returncode, stdout, stderr
                except subprocess.TimeoutExpired:
                    continue
        except KeyboardInterrupt:
            if on_cancel is not None:
                on_cancel()
            process.kill()
            stdout, stderr = process.communicate()
            self.last_run = {"pid": process.pid, "native_returncode": process.returncode,
                             "direct_child_exited": True, "stdout_eof": True, "stderr_eof": True}
            return 130, stdout, stderr


def _json(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, ensure_ascii=False, sort_keys=True, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())


def _pin(path, root):
    raw = path.read_bytes()
    return {"path": path.relative_to(root).as_posix(), "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest()}


def _install_success_closed(raw):
    """Only the known adb install success forms; no unknown prelude or suffix."""
    return re.fullmatch(rb"(?:Performing Streamed Install\r?\n)?Success(?:\r?\n)?", raw) is not None


def _current_agents_non_vpn(raw):
    """Understand current API31 agent/default facts, not listeners or historical text."""
    defaults = re.findall(rb"(?m)^Active default network:([^\r\n]*)", raw)
    sections = re.findall(rb"(?ms)^Current Networks:\r?\n(.*?)(?=^(?:Status for known UIDs:|Network Requests:)\r?$)", raw)
    if len(defaults) != 1 or not re.fullmatch(rb"[1-9][0-9]*", defaults[0].strip()) or len(sections) != 1:
        return False
    headers = [line for line in sections[0].splitlines()
               if line.strip() and line.startswith(b"  ") and not line.startswith(b"    ")]
    if (not headers or sections[0].count(b"NetworkAgentInfo") != len(headers)
            or any(line.strip() and not line.startswith(b"  ") for line in sections[0].splitlines())):
        return False
    agents = {}
    for line in headers:
        identity = re.match(rb"^  NetworkAgentInfo\{network\{([1-9][0-9]*)\}\s+handle\{[^{}]+\}\s+ni\{([^{}]*)\}", line)
        networks = re.findall(rb"\bnc\{\[\s+Transports:\s*([A-Z_]+(?:\|[A-Z_]+)*)\s+Capabilities:\s*([A-Z_]+(?:&[A-Z_]+)*)\b", line)
        if identity is None or len(networks) != 1 or not line.endswith(b"]}}"):
            return False
        net_id, info = identity.groups()
        transports, capabilities = networks[0][0].split(b"|"), set(networks[0][1].split(b"&"))
        # Only these physical transports are understood for this fixed W1/C1 feature.
        # Any current VPN agent, including a non-default one, or unknown transport holds.
        expected = b"WIFI CONNECTED" if transports == [b"WIFI"] else None
        mobile = transports == [b"CELLULAR"] and re.fullmatch(rb"MOBILE(?:\([^()]*\))? CONNECTED", info.split(b" extra:", 1)[0])
        if (expected is None and not mobile) or (expected is not None and info.split(b" extra:", 1)[0] != expected):
            return False
        if b"NOT_VPN" not in capabilities or net_id in agents:
            return False
        agents[net_id] = capabilities
    default_caps = agents.get(defaults[0].strip())
    return default_caps is not None and {b"INTERNET", b"VALIDATED", b"NOT_VPN"} <= default_caps


def run_pair(release: dict, output_root: Path, *, system=None) -> dict:
    """One explicit whole transaction; preparation never enters the W1/C1 loop."""
    output_root.mkdir(parents=True, exist_ok=False)
    preparation = release.get("execution_mode") == "PREPARATION_ONLY"
    stopped_state = "PREPARATION_STOPPED" if preparation else "PAIR_STOPPED"
    result_file = "PREPARATION_RESULT.json" if preparation else "PAIR_RESULT.json"
    networks = ("PREPARATION_ONLY",) if preparation else ("W1", "C1")
    result = {
        "state": stopped_state,
        "claim_scope": "preparation_inspection_only" if preparation else "private_engineering_observation",
        "formal_new_slots": 0,
        "attempts": [],
        "rpi": "not_computed",
    }
    if release.get("live_release_authorized") is not True:
        result["reason"] = "EXPLICIT_NEW_FEATURE_RELEASE_REQUIRED"
        _json(output_root / result_file, result)
        return result
    if release.get("execution_mode", "COLLECTION") not in ("COLLECTION", "PREPARATION_ONLY") or (
            preparation and release.get("network_transition_authorized") is True):
        result["reason"] = "UNKNOWN_MODE_OR_PREPARATION_NETWORK_CHANGE_FORBIDDEN"
        _json(output_root / result_file, result)
        return result
    if preparation:
        result.update(execution_mode="PREPARATION_ONLY", send_calls=0, capture="NOT_STARTED", metrics=None, send_selector="UNKNOWN")
    for key in ("serial", "pair_id"):
        if not isinstance(release.get(key), str) or not SAFE_ID.fullmatch(release[key]):
            raise ValueError("invalid_release_identity")
    for key in ("debug_apk", "test_apk", "adb", "java", "apksigner_jar", "aapt"):
        path = Path(release[key])
        if not path.is_absolute() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != release[key + "_sha256"]:
            raise ValueError("fixed_apk_mismatch")
    if hashlib.sha256(PAYLOAD.encode()).hexdigest() != PAYLOAD_SHA256:
        raise ValueError("frozen_payload_mismatch")
    system = system or SubprocessSystem()
    prefix = [release["adb"], "-s", release["serial"]]
    installed = []
    reader_updated = False
    original_settings = None
    wifi_touched = False
    preserve_debug_originals = False
    sequence = 0

    def command(args, label, timeout=20, external=False, cancel_file=None, on_cancel=None):
        nonlocal sequence
        sequence += 1
        directory = output_root / "receipts" / f"{sequence:03}-{label}"
        directory.mkdir(parents=True)
        started = time.monotonic()
        argv = args if external else prefix + args
        if cancel_file is None:
            rc, stdout, stderr = system.run(argv, timeout)
        else:
            rc, stdout, stderr = system.run(argv, timeout, cancel_file=cancel_file, on_cancel=on_cancel)
        (directory / "stdout.raw").write_bytes(stdout)
        (directory / "stderr.raw").write_bytes(stderr)
        _json(directory / "RESULT.json", {"argv": argv, "returncode": rc,
              "elapsed_seconds": time.monotonic() - started,
              "native_process": getattr(system, "last_run", {"source": "injected_external_OS_HOST_ONLY"}),
              "stdout": _pin(directory / "stdout.raw", output_root),
              "stderr": _pin(directory / "stderr.raw", output_root)})
        return rc, stdout, stderr

    def certificate(path, label):
        rc, raw, _ = command([release["java"], "-jar", release["apksigner_jar"],
                              "verify", "--print-certs", str(path)], label, external=True)
        certs = re.findall(rb"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", raw)
        if rc != 0 or len(certs) != 1:
            raise RuntimeError("APK_CERTIFICATE_NOT_VERIFIED")
        return certs[0].decode().lower()

    def apk_package(path, expected, label):
        rc, raw, _ = command([release["aapt"], "dump", "badging", str(path)], label, external=True)
        names = re.findall(rb"(?m)^package: name='([^']+)'", raw)
        if rc != 0 or names != [expected.encode()]:
            raise RuntimeError("FIXED_APK_PACKAGE_MISMATCH")

    def verify_installed_reader(expected_sha, label):
        rc, raw, _ = command(["shell", "pm", "path", DEBUG_PACKAGE], label + "-path")
        paths = raw.decode().splitlines()
        if rc != 0 or len(paths) != 1 or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=~+-]+\.apk", paths[0]):
            raise RuntimeError("INSTALLED_READER_SPLIT_OR_UNKNOWN")
        copied = output_root / (label + ".apk")
        rc, _, _ = command(["pull", paths[0][8:], str(copied)], label + "-pull")
        if rc != 0 or not copied.is_file() or hashlib.sha256(copied.read_bytes()).hexdigest() != expected_sha:
            raise RuntimeError("FRESH_INSTALLED_READER_SHA_MISMATCH")
        if certificate(copied, label + "-cert") != release["approved_debug_certificate_sha256"].lower():
            raise RuntimeError("FRESH_INSTALLED_READER_CERT_MISMATCH")

    def clean_current(label):
        rc, activities, _ = command(["shell", "dumpsys", "activity", "activities"], label + "-focus")
        rc2, processes, _ = command(["shell", "ps", "-A"], label + "-processes")
        rc3, windows, _ = command(["shell", "dumpsys", "window"], label + "-window")
        rc4, connectivity, _ = command(["shell", "dumpsys", "connectivity"], label + "-vpn")
        resumed = re.findall(rb"(?m)^\s*mResumedActivity:([^\r\n]*)", activities)
        focused = re.findall(rb"(?m)^\s*mCurrentFocus=([^\r\n]*)", windows)
        conflicts = (TARGET, TEST_PACKAGE, DEBUG_PACKAGE, "com.aneb.probe", "com.deepseek.chat", "com.moonshot.kimichat", "screenrecord", "tcpdump") + tuple(release.get("additional_conflicting_packages", []))
        if not all(isinstance(item, str) and re.fullmatch(r"[A-Za-z0-9_.]+", item) for item in conflicts):
            raise RuntimeError("INVALID_NAMED_CONFLICT_LIST")
        return (rc == rc2 == rc3 == rc4 == 0 and len(resumed) == len(focused) == 1
                and b"com.huawei.android.launcher/" in resumed[0] and b"com.huawei.android.launcher/" in focused[0]
                and _current_agents_non_vpn(connectivity)
                and not any(item.encode() in processes for item in conflicts))

    def stop_owned_instrumentation(dest, run_id):
        rc, current, _ = command(["shell", "pidof", DEBUG_PACKAGE], "own-instrument-current")
        if not current.strip() and rc in (0, 1):
            return True
        owner = json.loads((dest / "OWNED_INSTRUMENTATION.json").read_text(encoding="utf-8"))
        pid = owner.get("pid")
        if owner.get("run_id") != run_id or owner.get("package") != DEBUG_PACKAGE or not isinstance(pid, str) or not pid.isdecimal() or current.strip().decode() != pid:
            raise RuntimeError("INSTRUMENTATION_OWNER_UNKNOWN_NOT_STOPPED")
        stat_rc, raw, _ = command(["shell", "cat", f"/proc/{pid}/stat"], "own-instrument-ticks")
        uid_rc, status, _ = command(["shell", "cat", f"/proc/{pid}/status"], "own-instrument-uid")
        cmd_rc, cmdline, _ = command(["shell", "cat", f"/proc/{pid}/cmdline"], "own-instrument-command")
        ticks = raw.decode().rsplit(") ", 1)[-1].split()[19]
        uid = re.search(rb"(?m)^Uid:\s+([0-9]+)\s", status)
        if any(code != 0 for code in (stat_rc, uid_rc, cmd_rc)) or ticks != owner.get("start_ticks") or not uid or int(uid[1]) != owner.get("uid") or cmdline.rstrip(b"\x00\r\n") != DEBUG_PACKAGE.encode():
            raise RuntimeError("INSTRUMENTATION_IDENTITY_DRIFT_NOT_STOPPED")
        stop_rc, _, _ = command(["shell", "am", "force-stop", DEBUG_PACKAGE], "stop-owned-instrumentation")
        check_rc, after, _ = command(["shell", "pidof", DEBUG_PACKAGE], "own-instrument-absent")
        return stop_rc == 0 and check_rc in (0, 1) and not after.strip()

    try:
        approved_cert = release["approved_debug_certificate_sha256"].lower()
        if not re.fullmatch(r"[0-9a-f]{64}", approved_cert):
            raise RuntimeError("FIXED_CERTIFICATE_REQUIRED")
        for key, package in (("debug_apk", DEBUG_PACKAGE), ("test_apk", TEST_PACKAGE)):
            apk_package(Path(release[key]), package, key + "-package")
            if certificate(Path(release[key]), key + "-certificate") != approved_cert:
                raise RuntimeError("FIXED_NEW_APK_CERTIFICATE_MISMATCH")
        rc, state, _ = command(["get-state"], "online")
        if rc != 0 or state.strip() != b"device" or not clean_current("preflight"):
            raise RuntimeError("PHONE_NOT_ADMITTED")
        if release.get("network_transition_authorized") is True:
            original_settings = {}
            for key in ("wifi_on", "mobile_data", "stay_on_while_plugged_in"):
                setting_rc, setting, _ = command(["shell", "settings", "get", "global", key], "before-setting-" + key)
                value = setting.strip().decode()
                if setting_rc != 0 or not value.isdecimal() or (key in ("wifi_on", "mobile_data") and value not in ("0", "1")):
                    raise RuntimeError("ORIGINAL_NETWORK_SETTINGS_UNKNOWN")
                original_settings[key] = value
            if original_settings["mobile_data"] != "1":
                raise RuntimeError("C1_DATA_NOT_ENABLED_NOT_CHANGED")
        for package in (DEBUG_PACKAGE, TEST_PACKAGE):
            rc, raw, error = command(["shell", "pm", "path", package], "package-absent")
            if rc == 1 and not raw and not error:
                continue
            if package != DEBUG_PACKAGE or not release.get("original_debug_apk"):
                raise RuntimeError("UNKNOWN_EXISTING_PACKAGE_NOT_OVERWRITTEN")
            original = Path(release["original_debug_apk"])
            if not original.is_absolute() or original.is_symlink() or hashlib.sha256(original.read_bytes()).hexdigest() != release["original_debug_apk_sha256"]:
                raise RuntimeError("FIXED_ORIGINAL_READER_APK_MISMATCH")
            apk_package(original, DEBUG_PACKAGE, "original-reader-package")
            cert = release["approved_debug_certificate_sha256"].lower()
            if certificate(original, "original-reader-cert") != cert or certificate(Path(release["debug_apk"]), "new-reader-cert") != cert:
                raise RuntimeError("DATA_PRESERVING_CERTIFICATE_MISMATCH")
            verify_installed_reader(release["original_debug_apk_sha256"], "reader-before")
            reader_updated = True
        for package, key in ((DEBUG_PACKAGE, "debug_apk"), (TEST_PACKAGE, "test_apk")):
            flags = ["-r", "-d", "-t"] if package == DEBUG_PACKAGE and reader_updated else ["-t"]
            rc, raw, _ = command(["install", *flags, release[key]], "install")
            if rc != 0 or not _install_success_closed(raw):
                raise RuntimeError("INSTALL_FAILED")
            if not (package == DEBUG_PACKAGE and reader_updated):
                installed.append(package)
            else:
                verify_installed_reader(release["debug_apk_sha256"], "reader-updated")
        for network in networks:
            if (output_root / "CANCEL").exists():
                raise RuntimeError("HOST_CANCELLED_SECOND_NOT_RUN")
            if original_settings is not None:
                wifi_touched = True
                wifi_rc, _, _ = command(["shell", "svc", "wifi", "enable" if network == "W1" else "disable"], "network-" + network)
                if wifi_rc != 0:
                    raise RuntimeError("APPROVED_WIFI_TRANSITION_FAILED")
            run_id = release["pair_id"] + ("-PREP" if preparation else "-" + network)
            attempt = {"run_id": run_id, "network_condition": None if preparation else network,
                       "send_calls": 0 if preparation else "UNKNOWN", "owned_cleanup": False, "artifacts": [],
                       "state": "INVOKED_OUTCOME_UNKNOWN"}
            result["attempts"].append(attempt)
            if preparation:
                attempt["send_calls"] = "UNKNOWN"
                result["send_calls"] = "UNKNOWN"
            preserve_debug_originals = True
            _json(output_root / (run_id + ("-PREPARATION_CONSUMED.json" if preparation else "-OPPORTUNITY_CONSUMED.json")),
                  {"run_id": run_id, "retry_allowed": False, "actual_send_calls": 0 if preparation else "UNKNOWN"})
            remote = "/sdcard/Android/data/" + DEBUG_PACKAGE + "/files/tongyi-engineering/" + run_id
            def cancel_native():
                command(["shell", "touch", remote + "/CANCEL"], "request-native-cancel", timeout=5)
            rc, stdout, stderr = command([
                "shell", "am", "instrument", "-w", "-r", "-e", "class", INSTRUMENTATION_CLASS,
                "-e", "feature_release", "tongyi-engineering-807", "-e", "payload_sha256", PAYLOAD_SHA256,
                "-e", "run_id", run_id,
                *( ["-e", "execution_mode", "PREPARATION_ONLY"] if preparation else ["-e", "network_condition", network] ),
                "-e", "ui_contract_b64", base64.b64encode(json.dumps(release["ui_contract"], ensure_ascii=False).encode("utf-8")).decode("ascii"),
                TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner",
            ], "whole-" + network, timeout=220 if preparation else 310, cancel_file=output_root / "CANCEL", on_cancel=cancel_native)
            dest = output_root / run_id / "originals"
            pull_rc, _, _ = command(["pull", remote, str(dest)], "originals-" + network)
            if dest.is_dir(): # Preserve and pin partial originals even when the transfer fails.
                attempt["artifacts"] = [_pin(path, output_root) for path in sorted(dest.rglob("*")) if path.is_file()]
            host_owned_stop = stop_owned_instrumentation(dest, run_id)
            terminal = json.loads((dest / "TERMINAL.json").read_text(encoding="utf-8"))
            if terminal.get("run_id") != run_id or terminal.get("network_condition") != (None if preparation else network):
                raise RuntimeError("NATIVE_TERMINAL_IDENTITY_MISMATCH")
            attempt["send_calls"] = terminal.get("send_calls", "UNKNOWN")
            if preparation:
                result["send_calls"] = attempt["send_calls"]
            if preparation and (terminal.get("execution_mode") != "PREPARATION_ONLY" or terminal.get("send_calls") != 0 or
                    terminal.get("capture") != "NOT_STARTED" or terminal.get("claim_scope") != "preparation_inspection_only" or
                    any(pin["path"].endswith(("SEND_INTENT.json", "video.mp4", "OWNED_CAPTURE.json")) for pin in attempt["artifacts"])):
                raise RuntimeError("PREPARATION_SEND_CAPTURE_SCOPE_MISMATCH")
            if pull_rc == 0:
                preserve_debug_originals = False
            attempt["owned_cleanup"] = terminal.get("owned_cleanup") is True and host_owned_stop and clean_current("after-" + network)
            attempt["state"] = terminal.get("state", "UNKNOWN")
            if rc != 0 or pull_rc != 0 or attempt["state"] != ("PREPARATION_CLOSED" if preparation else "OBSERVATION_CLOSED") or not attempt["owned_cleanup"] or (output_root / "CANCEL").exists():
                raise RuntimeError("ATTEMPT_NOT_CLOSED_SECOND_NOT_RUN")
        result["state"] = "PREPARATION_CLOSED" if preparation else "PAIR_OBSERVATION_CLOSED"
    except Exception as error:
        result["reason"] = str(error)
        for network in networks:
            if not any(row["network_condition"] == (None if preparation else network) for row in result["attempts"]):
                result["attempts"].append({"run_id": release["pair_id"] + ("-PREP" if preparation else "-" + network),
                    "network_condition": None if preparation else network, "state": "NOT_RUN", "send_calls": 0,
                    "owned_cleanup": False, "artifacts": [], "reason": "PRECEDING_ADMISSION_OR_CLOSURE_FAILED"})
    finally:
        cleanup = []
        if wifi_touched:
            try:
                restore_rc, _, _ = command(["shell", "svc", "wifi", "enable" if original_settings["wifi_on"] == "1" else "disable"], "restore-wifi")
                restored = restore_rc == 0
                for key, expected in original_settings.items():
                    check_rc, value, _ = command(["shell", "settings", "get", "global", key], "restored-setting-" + key)
                    restored = restored and check_rc == 0 and value.strip().decode() == expected
                result["network_settings_restored"] = restored
            except Exception:
                result["network_settings_restored"] = False
            if not result["network_settings_restored"]:
                result["state"] = stopped_state
        package_mutation_safe = clean_current("before-package-cleanup") if installed or reader_updated else True
        if reader_updated and package_mutation_safe:
            try:
                rc, raw, _ = command(["install", "-r", "-d", "-t", release["original_debug_apk"]], "restore-original-reader")
                if rc != 0 or not _install_success_closed(raw):
                    raise RuntimeError("ORIGINAL_READER_RESTORE_FAILED")
                verify_installed_reader(release["original_debug_apk_sha256"], "reader-restored")
                result["original_reader_restored"] = True
            except Exception as failure:
                result["original_reader_restored"] = False
                result["restore_failure"] = str(failure)
                result["state"] = stopped_state
        elif reader_updated:
            result["original_reader_restored"] = False
            result["restore_failure"] = "CURRENT_UNKNOWN_PROCESS_NOT_OVERWRITTEN"
        for package in reversed(installed) if package_mutation_safe else []:
            if package == DEBUG_PACKAGE and preserve_debug_originals:
                cleanup.append({"package": package, "absent": False, "reason": "INCOMPLETE_TRANSFER_REMOTE_ORIGINALS_RETAINED"})
                continue
            try:
                rc, raw, error = command(["uninstall", package], "uninstall")
                cleanup.append({"package": package, "absent": rc == 0 and raw.strip() == b"Success" and not error})
            except Exception:
                cleanup.append({"package": package, "absent": False})
        result["package_cleanup"] = cleanup
        if not package_mutation_safe or not all(row["absent"] for row in cleanup):
            result["state"] = stopped_state
            result["package_cleanup_unknown"] = not package_mutation_safe
        _json(output_root / result_file, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute-new-feature-release", action="store_true")
    parser.add_argument("--preparation-only", action="store_true")
    args = parser.parse_args()
    release = json.loads(args.release.read_text(encoding="utf-8"))
    if args.preparation_only != (release.get("execution_mode") == "PREPARATION_ONLY"):
        parser.error("explicit --preparation-only must match fixed release execution_mode")
    if not args.execute_new_feature_release:
        release["live_release_authorized"] = False
    result = run_pair(release, args.output)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["state"] in ("PAIR_OBSERVATION_CLOSED", "PREPARATION_CLOSED") else 2


if __name__ == "__main__":
    raise SystemExit(main())
