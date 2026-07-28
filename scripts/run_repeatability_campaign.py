#!/usr/bin/env python3
"""Run one protected engineering repeatability campaign on the shared P40/E-01.

This is deliberately not a formal-baseline collector.  It reuses the frozen
M0 mechanics, holds the phone mutex and E-01 deployment flock for the whole
campaign, preserves/restores the pre-existing APK and private files, and emits
raw run IDs/logcat plus a frozen Room snapshot for later independent export.
"""

from __future__ import annotations

import argparse
import dataclasses
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import time
from typing import Literal, Sequence
import uuid

if __package__:
    from scripts import collect_network_quick_evidence as network
    from scripts import collect_realtime_quick_evidence as mechanics
else:
    import collect_network_quick_evidence as network
    import collect_realtime_quick_evidence as mechanics


PACKAGE_NAME = "com.aneb.probe.codex"
ACTIVITY_COMPONENT = f"{PACKAGE_NAME}/com.aneb.probe.ui.MainActivity"
ROOM_FILES = (
    "databases/aneb-probe.db",
    "databases/aneb-probe.db-wal",
    "databases/aneb-probe.db-shm",
    "files/profileInstalled",
    "shared_prefs/probe_settings_v1.xml",
)
UUID7_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


class CampaignError(mechanics.CollectorError):
    pass


@dataclasses.dataclass(frozen=True)
class TokenTerminal:
    run_id: str
    terminal_status: Literal["completed"]


def radio_permissions() -> tuple[str, ...]:
    return (
        "android.permission.READ_PHONE_STATE",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
    )


def parse_radio_permission_status(package_dump: str) -> dict[str, bool]:
    if not isinstance(package_dump, str) or "\x00" in package_dump:
        raise CampaignError("radio_permission_dump_invalid")
    status: dict[str, bool] = {}
    for permission in radio_permissions():
        rows = re.findall(
            rf"(?m)^\s*{re.escape(permission)}: granted=(true|false)(?:,|\s*$)",
            package_dump,
        )
        if len(rows) != 1:
            raise CampaignError(f"radio_permission_row_invalid:{permission}")
        status[permission] = rows[0] == "true"
    return status


def assert_radio_permissions_granted(package_dump: str) -> dict[str, bool]:
    status = parse_radio_permission_status(package_dump)
    denied = [permission for permission in radio_permissions() if not status[permission]]
    if denied:
        raise CampaignError(f"radio_permission_not_granted:{','.join(denied)}")
    return status


def build_radio_permission_receipt(package_dump: str) -> dict[str, object]:
    permissions = parse_radio_permission_status(package_dump)
    denied = [permission for permission in radio_permissions() if not permissions[permission]]
    return {
        "schema_version": "aneb-repeatability-radio-permissions-v1",
        "package_name": PACKAGE_NAME,
        "source": f"dumpsys package {PACKAGE_NAME}",
        "package_dump_sha256": hashlib.sha256(package_dump.encode("utf-8")).hexdigest(),
        "permissions": permissions,
        "denied_permissions": denied,
        "all_granted": not denied,
        "diagnostic_only": True,
        "formal_baseline_eligible": False,
    }


def record_radio_permission_preflight(
    package_dump: str, output: Path
) -> dict[str, object]:
    receipt = build_radio_permission_receipt(package_dump)
    _write_json(output, receipt)
    denied = receipt["denied_permissions"]
    if denied:
        if not isinstance(denied, list) or not all(isinstance(item, str) for item in denied):
            raise CampaignError("radio_permission_receipt_invalid")
        raise CampaignError(f"radio_permission_not_granted:{','.join(denied)}")
    return receipt


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=True, allow_nan=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("ascii")


def _coerce_receipt_bytes(value: str | bytes) -> bytes:
    if isinstance(value, bytes):
        return value
    if isinstance(value, str):
        return value.encode("utf-8")
    raise CampaignError("receipt_type_invalid")


def _write_exclusive(path: Path, value: bytes) -> None:
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0),
        0o600,
    )
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def _write_json(path: Path, value: object) -> None:
    _write_exclusive(path, _canonical_bytes(value))


def build_campaign_plan(*, repetitions: int) -> list[tuple[str, int]]:
    if type(repetitions) is not int or not 1 <= repetitions <= 20:
        raise CampaignError("campaign_repetitions_invalid")
    return [
        (family, ordinal)
        for family in ("token", "realtime", "network")
        for ordinal in range(1, repetitions + 1)
    ]


def parse_token_terminal_markers(
    text: str, *, mode: Literal["positive", "negative"]
) -> TokenTerminal:
    if mode != "positive":
        raise CampaignError("repeatability_campaign_positive_only")
    starts = re.findall(r"TOKEN_V2_START run_id=([^\s]+)", text)
    if len(starts) != 1 or UUID7_RE.fullmatch(starts[0]) is None:
        raise CampaignError("token_marker_chain_invalid")
    run_id = starts[0]
    writes = re.findall(
        rf"TOKEN_V2_DB_WRITE run_id={re.escape(run_id)} ok=([^\s]+)", text
    )
    ends = re.findall(
        rf"TOKEN_V2_END run_id={re.escape(run_id)} status=([^\s]+)", text
    )
    all_ends = re.findall(r"TOKEN_V2_END run_id=([^\s]+) status=([^\s]+)", text)
    if writes != ["true"] or ends != ["completed"] or all_ends != [(run_id, "completed")]:
        raise CampaignError("token_marker_chain_invalid")
    return TokenTerminal(run_id=run_id, terminal_status="completed")


def _load_phone_guard(path: Path):
    path = path.resolve(strict=True)
    spec = importlib.util.spec_from_file_location("aneb_external_phone_guard_rev4", path)
    if spec is None or spec.loader is None:
        raise CampaignError("phone_guard_module_invalid")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    if getattr(module, "PHONE_PARSER_CONTRACT_REVISION", None) != 4:
        raise CampaignError("phone_guard_revision_invalid")
    return module


def _run_raw(arguments: Sequence[str], *, timeout: int, stdin: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            list(arguments),
            input=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise CampaignError("local_process_failed") from error


def _adb_raw(adb: Path, serial: str, tail: Sequence[str], *, timeout: int = 120, stdin: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    return _run_raw((str(adb), "-s", serial, *tail), timeout=timeout, stdin=stdin)


def _require_success(result: subprocess.CompletedProcess[bytes], code: str) -> bytes:
    if result.returncode != 0 or result.stderr:
        raise CampaignError(f"{code} rc={result.returncode}")
    return result.stdout


def _remote_package_file(adb: Path, serial: str, relative: str) -> bytes:
    if relative not in ROOM_FILES:
        raise CampaignError("private_file_not_allowlisted")
    result = _adb_raw(
        adb,
        serial,
        ("exec-out", "run-as", PACKAGE_NAME, "cat", f"/data/user/0/{PACKAGE_NAME}/{relative}"),
        timeout=120,
    )
    return _require_success(result, "private_file_read_failed")


def _remote_sha(adb: Path, serial: str, path: str, *, run_as: bool) -> str:
    tail = ("shell", "run-as", PACKAGE_NAME, "sha256sum", path) if run_as else ("shell", "sha256sum", path)
    output = _require_success(_adb_raw(adb, serial, tail), "remote_sha_failed").decode("utf-8", "strict").strip()
    match = re.fullmatch(r"([0-9a-f]{64})\s+\S+", output)
    if match is None:
        raise CampaignError("remote_sha_invalid")
    return match.group(1)


def _installed_apk_path(adb: Path, serial: str) -> str:
    output = _require_success(
        _adb_raw(adb, serial, ("shell", "pm", "path", PACKAGE_NAME)),
        "installed_apk_path_failed",
    ).decode("utf-8", "strict").strip()
    match = re.fullmatch(r"package:(/\S+/base\.apk)", output)
    if match is None:
        raise CampaignError("installed_apk_path_invalid")
    return match.group(1)


def _backup_installed_state(adb: Path, serial: str, output: Path) -> dict[str, object]:
    output.mkdir(mode=0o700)
    apk_path = _installed_apk_path(adb, serial)
    before = _remote_sha(adb, serial, apk_path, run_as=False)
    apk = _require_success(
        _adb_raw(adb, serial, ("exec-out", "cat", apk_path), timeout=300),
        "installed_apk_read_failed",
    )
    after = _remote_sha(adb, serial, apk_path, run_as=False)
    if before != after or _sha256_bytes(apk) != before:
        raise CampaignError("installed_apk_changed_during_backup")
    _write_exclusive(output / "base.apk", apk)
    files: dict[str, dict[str, object]] = {}
    for relative in ROOM_FILES:
        absolute = f"/data/user/0/{PACKAGE_NAME}/{relative}"
        before_file = _remote_sha(adb, serial, absolute, run_as=True)
        payload = _remote_package_file(adb, serial, relative)
        after_file = _remote_sha(adb, serial, absolute, run_as=True)
        if before_file != after_file or _sha256_bytes(payload) != before_file:
            raise CampaignError("private_file_changed_during_backup")
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_exclusive(target, payload)
        files[relative] = {"sha256": before_file, "size_bytes": len(payload)}
    manifest = {"apk_sha256": before, "apk_size_bytes": len(apk), "files": files}
    _write_json(output / "backup-manifest.json", manifest)
    return manifest


def _restore_installed_state(adb: Path, serial: str, backup: Path, manifest: dict[str, object]) -> None:
    _require_success(_adb_raw(adb, serial, ("uninstall", PACKAGE_NAME), timeout=180), "candidate_uninstall_failed")
    install = _adb_raw(adb, serial, ("install", "--no-streaming", str(backup / "base.apk")), timeout=300)
    install_text = (install.stdout + install.stderr).decode("utf-8", "strict")
    if install.returncode != 0 or "Success" not in install_text.splitlines():
        raise CampaignError("original_apk_restore_failed")
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise CampaignError("backup_manifest_invalid")
    for relative, metadata in files.items():
        if relative not in ROOM_FILES or not isinstance(metadata, dict):
            raise CampaignError("backup_manifest_invalid")
        payload = (backup / relative).read_bytes()
        expected = metadata.get("sha256")
        if _sha256_bytes(payload) != expected:
            raise CampaignError("backup_payload_changed")
        parent = str(PurePosixPath(relative).parent)
        remote = f"/data/user/0/{PACKAGE_NAME}/{relative}"
        mkdir = f"/data/user/0/{PACKAGE_NAME}/{parent}"
        _require_success(
            _adb_raw(adb, serial, ("exec-in", "run-as", PACKAGE_NAME, "mkdir", "-p", mkdir)),
            "restore_mkdir_failed",
        )
        command = f"cat > {remote}"
        _require_success(
            _adb_raw(
                adb,
                serial,
                ("exec-in", "run-as", PACKAGE_NAME, "sh", "-c", command),
                stdin=payload,
            ),
            "restore_write_failed",
        )
        readback = _remote_package_file(adb, serial, relative)
        if _sha256_bytes(readback) != expected or len(readback) != len(payload):
            raise CampaignError("restore_digest_mismatch")
    restored_apk = _installed_apk_path(adb, serial)
    if _remote_sha(adb, serial, restored_apk, run_as=False) != manifest.get("apk_sha256"):
        raise CampaignError("restored_apk_digest_mismatch")


def _install_candidate(
    adb: Path,
    serial: str,
    apk: Path,
    *,
    permission_receipt_path: Path,
) -> None:
    _require_success(_adb_raw(adb, serial, ("uninstall", PACKAGE_NAME), timeout=180), "original_uninstall_failed")
    install = _adb_raw(adb, serial, ("install", "--no-streaming", str(apk)), timeout=300)
    text = (install.stdout + install.stderr).decode("utf-8", "strict")
    if install.returncode != 0 or "Success" not in text.splitlines():
        raise CampaignError("candidate_install_failed")
    for permission in radio_permissions():
        _require_success(
            _adb_raw(
                adb,
                serial,
                ("shell", "pm", "grant", "--user", "0", PACKAGE_NAME, permission),
            ),
            "radio_permission_grant_failed",
        )
    package_dump = _require_success(
        _adb_raw(adb, serial, ("shell", "dumpsys", "package", PACKAGE_NAME)),
        "radio_permission_dump_failed",
    ).decode("utf-8", "strict")
    record_radio_permission_preflight(package_dump, permission_receipt_path)


def _launch_arguments(family: str, *, serial: str, server_base: str, adb: Path) -> list[str]:
    if family == "realtime":
        return mechanics.build_realtime_launch_arguments(
            serial=serial, server_base=server_base, transport="wifi", adb_path=str(adb)
        )
    if family == "network":
        return network.build_network_launch_arguments(
            serial=serial, server_base=server_base, transport="wifi", adb_path=str(adb)
        )
    if family != "token":
        raise CampaignError("campaign_family_invalid")
    return [
        str(adb), "-s", serial, "shell", "am", "start", "-W", "-n", ACTIVITY_COMPONENT,
        "--es", "server", server_base, "--ez", "autorun", "true", "--es", "mode", "quick",
        "--es", "transport", "wifi", "--es", "test_mode", "token",
    ]


def _pull_room_snapshot(adb: Path, serial: str, output: Path) -> dict[str, object]:
    output.mkdir(mode=0o700)
    files: dict[str, object] = {}
    for relative in ROOM_FILES[:3]:
        payload = _remote_package_file(adb, serial, relative)
        target = output / Path(relative).name
        _write_exclusive(target, payload)
        files[target.name] = {"sha256": _sha256_bytes(payload), "size_bytes": len(payload)}
    receipt = {"files": files}
    _write_json(output / "room-snapshot.json", receipt)
    return receipt


def _create_evidence_directory(parent: Path) -> Path:
    parent = parent.resolve(strict=True)
    name = "s3-m1-repeatability-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:12]
    path = parent / name
    path.mkdir(mode=0o700)
    return path


def run(args: argparse.Namespace) -> Path:
    evidence = _create_evidence_directory(args.evidence_parent)
    runner = mechanics.SubprocessRunner()
    provenance_report, identity = mechanics.verify_ci_candidate(
        runner=runner,
        python_path=args.python.resolve(strict=True),
        gh_path=args.gh.resolve(strict=True),
        candidate_directory=args.candidate.resolve(strict=True),
        source_commit=args.source_commit,
        report_output=evidence / "candidate-provenance.json",
        timeout_seconds=180,
        contract=network.CONTRACT,
    )
    del provenance_report
    phone_module = _load_phone_guard(args.phone_guard)
    phone_guard_sha = _sha256_bytes(args.phone_guard.resolve(strict=True).read_bytes())
    guard = phone_module.PhoneGuard(str(args.adb.resolve(strict=True)), args.serial, expected_stayon=7)
    lease = guard.acquire()
    _write_exclusive(
        evidence / "phone-preflight.json",
        _coerce_receipt_bytes(lease.preflight.to_canonical_json()),
    )
    ssh = mechanics.SshClient(
        runner=runner,
        executable=args.ssh.resolve(strict=True),
        remote=args.remote,
        ssh_key=args.ssh_key.resolve(strict=True),
        known_hosts=args.known_hosts.resolve(strict=True),
        timeout_seconds=120,
    )
    remote_lock = mechanics.PersistentRemoteLock(ssh=ssh, ttl_seconds=args.lock_ttl_seconds)
    backup: dict[str, object] | None = None
    candidate_installed = False
    restored = False
    remote_before = None
    run_receipts: list[dict[str, object]] = []
    primary_error: BaseException | None = None
    try:
        lock_receipt = remote_lock.acquire()
        _write_json(evidence / "remote-lock-acquired.json", {"receipt": lock_receipt})
        remote_before = mechanics.capture_remote_snapshot(ssh=ssh, lock=remote_lock, stage="campaign_before")
        _write_json(evidence / "remote-before.json", dataclasses.asdict(remote_before))
        serverinfo = mechanics.fetch_serverinfo(
            server_base=args.server_base,
            ca_path=args.server_ca.resolve(strict=True),
            timeout_seconds=30,
            serverinfo_validator=network.validate_network_serverinfo,
        )
        _write_exclusive(evidence / "serverinfo-before.json", serverinfo.body)
        if remote_before.server_binary_sha256 != args.expected_server_binary_sha256:
            raise CampaignError("remote_binary_identity_mismatch")
        lease.claim_package_before_start(PACKAGE_NAME)
        backup = _backup_installed_state(args.adb, args.serial, evidence / "preinstalled-backup")
        _install_candidate(
            args.adb,
            args.serial,
            args.candidate / identity.apk_file_name,
            permission_receipt_path=evidence / "radio-permissions.json",
        )
        candidate_installed = True
        mechanics.verify_or_install_candidate(
            mechanics.AdbClient(
                runner=runner,
                executable=args.adb.resolve(strict=True),
                serial=args.serial,
                timeout_seconds=120,
            ),
            candidate_directory=args.candidate.resolve(strict=True),
            identity=identity,
            evidence_directory=evidence,
            install=False,
            contract=network.CONTRACT,
        )
        for family, ordinal in build_campaign_plan(repetitions=args.repetitions):
            remote_lock.assert_healthy(f"before_{family}_{ordinal}")
            _require_success(
                _adb_raw(args.adb, args.serial, ("shell", "am", "force-stop", "--user", "0", PACKAGE_NAME)),
                "between_run_stop_failed",
            )
            _require_success(
                _adb_raw(args.adb, args.serial, ("shell", "input", "keyevent", "KEYCODE_HOME")),
                "between_run_home_failed",
            )
            time.sleep(1.0)
            run_dir = evidence / f"{family}-{ordinal:02d}"
            run_dir.mkdir(mode=0o700)
            parser = (
                parse_token_terminal_markers
                if family == "token"
                else mechanics.parse_realtime_terminal_markers
                if family == "realtime"
                else network.parse_network_terminal_markers
            )
            capture = mechanics.LogcatCapture(
                adb=mechanics.AdbClient(
                    runner=runner,
                    executable=args.adb.resolve(strict=True),
                    serial=args.serial,
                    timeout_seconds=120,
                ),
                output_path=run_dir / "logcat.txt",
                stderr_path=run_dir / "logcat.stderr.txt",
                terminal_parser=parser,
                terminal_timeout_code=f"{family}_terminal_timeout",
            )
            try:
                capture.start()
                launch = _run_raw(
                    _launch_arguments(family, serial=args.serial, server_base=args.server_base, adb=args.adb),
                    timeout=120,
                )
                launch_text = (launch.stdout + launch.stderr).decode("utf-8", "strict")
                _write_exclusive(run_dir / "launch.txt", launch_text.encode("utf-8"))
                if launch.returncode != 0 or re.search(r"(?m)^Status:\s*ok\s*$", launch_text) is None:
                    raise CampaignError("app_launch_not_ok")
                terminal = capture.wait_terminal(mode="positive", timeout_seconds=args.run_timeout_seconds)
            finally:
                capture.stop(allow_missing=True)
            receipt = {"family": family, "ordinal": ordinal, "terminal": dataclasses.asdict(terminal)}
            _write_json(run_dir / "terminal.json", receipt)
            run_receipts.append(receipt)
            print(f"RUN_COMPLETE family={family} ordinal={ordinal} run_id={terminal.run_id}", flush=True)
        _require_success(
            _adb_raw(args.adb, args.serial, ("shell", "am", "force-stop", "--user", "0", PACKAGE_NAME)),
            "final_candidate_stop_failed",
        )
        _pull_room_snapshot(args.adb, args.serial, evidence / "campaign-room")
        _write_json(
            evidence / "campaign-runs.json",
            {
                "engineering_validation_only": True,
                "formal_baseline_eligible": False,
                "phone_guard_revision": 4,
                "phone_guard_sha256": phone_guard_sha,
                "source_commit": args.source_commit,
                "runs": run_receipts,
            },
        )
    except BaseException as error:
        primary_error = error
    finally:
        cleanup_errors: list[str] = []
        if backup is not None:
            try:
                _restore_installed_state(args.adb, args.serial, evidence / "preinstalled-backup", backup)
                candidate_installed = False
                restored = True
            except BaseException as error:
                cleanup_errors.append(f"restore:{type(error).__name__}:{error}")
        elif candidate_installed:
            cleanup_errors.append("restore:backup_unavailable")
        phone_cleanup = None
        try:
            phone_cleanup = lease.cleanup_and_release()
            _write_exclusive(
                evidence / "phone-postflight.json",
                _canonical_bytes(phone_cleanup.to_dict()),
            )
        except BaseException as error:
            cleanup_errors.append(f"phone:{type(error).__name__}:{error}")
        if remote_lock.process is not None and remote_lock.process.poll() is None:
            try:
                remote_after = mechanics.capture_remote_snapshot(ssh=ssh, lock=remote_lock, stage="campaign_after")
                _write_json(evidence / "remote-after.json", dataclasses.asdict(remote_after))
                if remote_before is None:
                    raise CampaignError("remote_before_missing")
                mechanics.assert_remote_snapshot_stable(
                    remote_before,
                    remote_after,
                    expected_binary_sha256=args.expected_server_binary_sha256,
                )
                release = remote_lock.release()
                _write_json(evidence / "remote-lock-released.json", {"receipt": release})
            except BaseException as error:
                cleanup_errors.append(f"remote:{type(error).__name__}:{error}")
                try:
                    remote_lock.emergency_close()
                except BaseException as close_error:
                    cleanup_errors.append(f"remote_emergency:{type(close_error).__name__}:{close_error}")
        _write_json(
            evidence / "final-status.json",
            {
                "campaign_complete": primary_error is None and len(run_receipts) == args.repetitions * 3,
                "cleanup_errors": cleanup_errors,
                "original_install_restored": restored,
                "primary_error": None if primary_error is None else f"{type(primary_error).__name__}:{primary_error}",
                "run_count": len(run_receipts),
            },
        )
        if cleanup_errors:
            raise CampaignError(";".join(cleanup_errors)) from primary_error
        if primary_error is not None:
            raise primary_error
    return evidence


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--server-base", required=True)
    parser.add_argument("--remote", required=True)
    parser.add_argument("--ssh-key", type=Path, required=True)
    parser.add_argument("--known-hosts", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--expected-server-binary-sha256", required=True)
    parser.add_argument("--phone-guard", type=Path, required=True)
    parser.add_argument("--evidence-parent", type=Path, required=True)
    parser.add_argument("--server-ca", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--ssh", type=Path, required=True)
    parser.add_argument("--python", type=Path, required=True)
    parser.add_argument("--gh", type=Path, required=True)
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--run-timeout-seconds", type=int, default=900)
    parser.add_argument("--lock-ttl-seconds", type=int, default=7200)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        evidence = run(args)
    except BaseException as error:
        print(f"CAMPAIGN_FAILED {type(error).__name__}:{error}", file=sys.stderr, flush=True)
        return 1
    print(f"CAMPAIGN_COMPLETE evidence={evidence}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
