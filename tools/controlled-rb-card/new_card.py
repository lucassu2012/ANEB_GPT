#!/usr/bin/env python3
"""
Parameterized immutable Controlled R/B Card Generator for ANEB.

Generates a self-contained, SHA-256-locked card directory from
tools/controlled-rb-card/templates/*.tmpl in strict DAG order:
  1. Stage artifacts & admission/UI-binding files
  2. Write CANDIDATE_ARTIFACTS.json
  3. Render common.ps1
  4. Render execute-once.ps1 and verify-cleanup-readonly.ps1
  5. Render run-once.ps1
  6. Write CARD_SPEC.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


DEFAULT_CASE_ROOT = (
    "E:/G Project/ANEB/Archives/ExternalRoots/ANEB-Research/Business-Profile-Baseline-20261024"
)
DEFAULT_REFERENCE_CARD_NAME = "douyin-controlled-rb-1500kbps-410101-20261120-r3"
DEFAULT_ADB_EXE = "E:/tools/android-sdk/platform-tools/adb.exe"
DEFAULT_ADB_SERIAL = "8MY0221126002537"

CARD_ID_RE = re.compile(r"^[A-Za-z0-9._-]+$")
RUN_ID_PREFIX_RE = re.compile(r"^[A-Za-z0-9_-]{1,56}$")
HEX_40_RE = re.compile(r"^[0-9a-f]{40}$")
ALLOWED_QUEUE_POLICIES = ("", "invalidate", "tail_drop")
MIN_QUEUE_LIMIT_BYTES = 16384
MAX_QUEUE_LIMIT_BYTES = 4194304

APP_PRESETS: dict[str, dict[str, Any]] = {
    "douyin": {
        "business_package": "com.ss.android.ugc.aweme",
        "default_version": 410101,
        "default_version_name": "41.1.0",
        "profile_relpath": "app/probe/src/debug/assets/business-profiles/douyin-feed-single-switch-v1.json",
        "profile_asset": "assets/business-profiles/douyin-feed-single-switch-v1.json",
        "ui_binding_relpath": None,
        "ui_binding_filename": "ui-binding-douyin-410101-v2.json",
        "ui_observation_method": "targeted-public-selectors-2",
        "ui_validator_fn": "Assert-AnebDouyinUiBindingV2",
        "probe_candidate_hosts_ps": "'www.douyin.com'",
        "instrumentation_class": "com.aneb.probe.research.business.NativeVideoScenarioInstrumentation",
        "artifacts_format": "douyin-controlled-rb-artifacts-20261119",
        "card_format": "douyin-controlled-rb-card-1",
        "outcome_format": "douyin-controlled-rb-outcome-1",
        "outer_format": "douyin-controlled-rb-outer-1",
        "card_po": "douyin-controlled-rb-po-1",
        "card_banner": "[DOUYIN CONTROLLED R/B] Same-topology WinRT AP + phone-condition",
        "card_title_prefix": "Douyin (com.ss.android.ugc.aweme)",
    },
    "doubao": {
        "business_package": "com.larus.nova",
        "default_version": 15080040,
        "default_version_name": "15.8.0",
        "profile_relpath": "app/probe/src/debug/assets/business-profiles/doubao-chat-single-send-v1.json",
        "profile_asset": "assets/business-profiles/doubao-chat-single-send-v1.json",
        "ui_binding_relpath": "tools/controlled-rb-card/bindings/ui-binding-doubao-15080040-v1.json",
        "ui_binding_filename": "ui-binding-doubao-15080040-v1.json",
        "ui_observation_method": "doubao-public-selectors-1",
        "ui_validator_fn": "Assert-AnebDoubaoUiBinding",
        "probe_candidate_hosts_ps": "'www.doubao.com', 'www.douyin.com'",
        "instrumentation_class": "com.aneb.probe.research.business.DoubaoControlledScenarioInstrumentation",
        "artifacts_format": "doubao-controlled-rb-artifacts-20261120",
        "card_format": "doubao-controlled-rb-card-1",
        "outcome_format": "doubao-controlled-rb-outcome-1",
        "outer_format": "doubao-controlled-rb-outer-1",
        "card_po": "doubao-controlled-rb-po-1",
        "card_banner": "[DOUBAO CONTROLLED R/B] 30fps MediaProjection + same-topology WinRT AP + phone-condition",
        "card_title_prefix": "Doubao AI Assistant (com.larus.nova)",
    },
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_ascii_ps_safe(name: str, value: str, allow_single_quote: bool = False) -> str:
    try:
        value.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError(f"{name} must be pure ASCII, got {value!r}") from exc
    if "\r" in value or "\n" in value:
        raise ValueError(f"{name} must not contain CR or LF: {value!r}")
    if not allow_single_quote and "'" in value:
        raise ValueError(f"{name} must not contain single quotes: {value!r}")
    return value


def posix_path(path: Path | str) -> str:
    return str(path).replace("\\", "/")


def derive_run_id_prefix(card_id: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_-]", "_", card_id)
    cleaned = cleaned[:55]
    if not cleaned.endswith("_"):
        cleaned = f"{cleaned}_"
    if not RUN_ID_PREFIX_RE.match(cleaned):
        raise ValueError(f"Derived run_id_prefix is invalid: {cleaned!r}")
    return cleaned


def detect_git_value(repo_root: Path, args: list[str]) -> str | None:
    try:
        res = subprocess.run(
            ["git", *args],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            check=True,
        )
        return res.stdout.strip()
    except Exception:
        return None


def read_apk_metadata(apk_path: Path, defaults: dict[str, Any]) -> dict[str, Any]:
    meta_path = apk_path.parent / "output-metadata.json"
    if not meta_path.is_file():
        return dict(defaults)
    data = json.loads(meta_path.read_text(encoding="utf-8"))
    out = dict(defaults)
    if isinstance(data.get("applicationId"), str) and data["applicationId"]:
        out["package"] = data["applicationId"]
    elements = data.get("elements")
    if isinstance(elements, list) and elements:
        el = elements[0]
        if isinstance(el, dict):
            if "versionCode" in el and el["versionCode"] is not None:
                out["version_code"] = int(el["versionCode"])
            if isinstance(el.get("versionName"), str) and el["versionName"]:
                out["version_name"] = el["versionName"]
    return out


def render_template(template_path: Path, tokens: dict[str, str]) -> bytes:
    raw_text = template_path.read_text(encoding="ascii")
    rendered = raw_text
    for key, val in tokens.items():
        rendered = rendered.replace(key, val)
    leftover = re.findall(r"\{\{[A-Z0-9_]+\}\}", rendered)
    if leftover:
        raise ValueError(f"Unsubstituted template token(s) in {template_path.name}: {sorted(set(leftover))}")
    rendered = rendered.replace("\r\n", "\n").replace("\r", "\n")
    return rendered.encode("ascii")


def write_lf_bytes(path: Path, data: bytes) -> str:
    with path.open("wb") as f:
        f.write(data)
    return sha256_bytes(data)


def validate_condition_args(args: argparse.Namespace) -> None:
    if args.downlink_bps is None:
        raise ValueError("--downlink-bps (or --rate) is required")
    if not (1 <= args.downlink_bps <= 1_000_000_000):
        raise ValueError(f"downlink_bps must be in 1..1000000000, got {args.downlink_bps}")
    if not (0 <= args.delay_ms <= 2000):
        raise ValueError(f"delay_ms must be in 0..2000, got {args.delay_ms}")
    if not (0 <= args.loss_bps <= 5000):
        raise ValueError(f"loss_bps must be in 0..5000, got {args.loss_bps}")
    if args.loss_bps > 0 and args.seed <= 0:
        raise ValueError("seed must be > 0 when loss_bps > 0")
    if args.seed < 0:
        raise ValueError(f"seed must be >= 0, got {args.seed}")
    if args.queue_overflow_policy not in ALLOWED_QUEUE_POLICIES:
        raise ValueError(
            f"queue_overflow_policy must be one of {ALLOWED_QUEUE_POLICIES}, got {args.queue_overflow_policy!r}"
        )
    if args.queue_limit_bytes != 0 and not (MIN_QUEUE_LIMIT_BYTES <= args.queue_limit_bytes <= MAX_QUEUE_LIMIT_BYTES):
        raise ValueError(
            f"queue_limit_bytes must be 0 or in {MIN_QUEUE_LIMIT_BYTES}..{MAX_QUEUE_LIMIT_BYTES}, "
            f"got {args.queue_limit_bytes}"
        )
    if not (1 <= args.ttl <= 115):
        raise ValueError(f"ttl must be in 1..115, got {args.ttl}")
    if args.order not in ("R->B", "B->R"):
        raise ValueError(f"order must be 'R->B' or 'B->R', got {args.order!r}")
    if args.version <= 0:
        raise ValueError(f"version must be > 0, got {args.version}")

    # Validate optional uplink metadata (recorded in CARD_SPEC.json only; not emitted into M2.1 request.json)
    if args.uplink_bps is not None and not (1 <= args.uplink_bps <= 1_000_000_000):
        raise ValueError(f"uplink_bps must be in 1..1000000000, got {args.uplink_bps}")
    if args.uplink_delay_ms is not None and not (0 <= args.uplink_delay_ms <= 2000):
        raise ValueError(f"uplink_delay_ms must be in 0..2000, got {args.uplink_delay_ms}")
    if args.uplink_loss_bps is not None:
        if not (0 <= args.uplink_loss_bps <= 5000):
            raise ValueError(f"uplink_loss_bps must be in 0..5000, got {args.uplink_loss_bps}")
        if args.uplink_loss_bps > 0 and args.seed <= 0:
            raise ValueError("seed must be > 0 when uplink_loss_bps > 0")
    if args.uplink_queue_overflow_policy is not None and args.uplink_queue_overflow_policy not in ALLOWED_QUEUE_POLICIES:
        raise ValueError(
            f"uplink_queue_overflow_policy must be one of {ALLOWED_QUEUE_POLICIES}, "
            f"got {args.uplink_queue_overflow_policy!r}"
        )
    if args.uplink_queue_limit_bytes is not None and args.uplink_queue_limit_bytes != 0 and not (
        MIN_QUEUE_LIMIT_BYTES <= args.uplink_queue_limit_bytes <= MAX_QUEUE_LIMIT_BYTES
    ):
        raise ValueError(
            f"uplink_queue_limit_bytes must be 0 or in {MIN_QUEUE_LIMIT_BYTES}..{MAX_QUEUE_LIMIT_BYTES}, "
            f"got {args.uplink_queue_limit_bytes}"
        )


def resolve_input_file(explicit: str | None, fallbacks: list[Path | None], label: str) -> Path:
    if explicit:
        p = Path(os.path.abspath(explicit))
        if not p.is_file():
            raise FileNotFoundError(f"Missing {label}: {p}")
        return p
    for cand in fallbacks:
        if cand is not None and cand.is_file():
            return Path(os.path.abspath(cand))
    tried = [str(c) for c in fallbacks if c is not None]
    raise FileNotFoundError(f"Could not resolve {label}; tried {tried}")


def build_card(args: argparse.Namespace) -> dict[str, Any]:
    preset = APP_PRESETS[args.app]
    if args.version is None:
        args.version = int(preset["default_version"])
    if args.version_name is None:
        args.version_name = str(preset["default_version_name"])
    if args.seed is None:
        args.seed = 20261120 if (args.loss_bps > 0 or (args.uplink_loss_bps or 0) > 0) else 0

    if args.card_id in {".", ".."} or not CARD_ID_RE.match(args.card_id):
        raise ValueError(f"card_id must match [A-Za-z0-9._-]+ and not be '.' or '..', got {args.card_id!r}")
    ensure_ascii_ps_safe("card_id", args.card_id)
    ensure_ascii_ps_safe("version_name", args.version_name)
    ensure_ascii_ps_safe("adb_exe", args.adb_exe)
    ensure_ascii_ps_safe("adb_serial", args.adb_serial)

    validate_condition_args(args)

    repo_root = Path(os.path.abspath(args.repo_root))
    template_dir = Path(__file__).resolve().parent / "templates"
    for tmpl_name in (
        "common.ps1.tmpl",
        "execute-once.ps1.tmpl",
        "run-once.ps1.tmpl",
        "verify-cleanup-readonly.ps1.tmpl",
    ):
        if not (template_dir / tmpl_name).is_file():
            raise FileNotFoundError(f"Missing template: {template_dir / tmpl_name}")

    case_root = Path(os.path.abspath(args.case_root))
    out_dir = Path(os.path.abspath(args.out_dir)) if args.out_dir else Path(os.path.abspath(case_root / args.card_id))
    if out_dir.exists():
        raise FileExistsError(f"Destination card directory already exists (immutable): {out_dir}")

    ensure_ascii_ps_safe("out_dir", posix_path(out_dir))
    ensure_ascii_ps_safe("case_root", posix_path(case_root))

    run_id_prefix = args.run_id_prefix if args.run_id_prefix is not None else derive_run_id_prefix(args.card_id)
    if not RUN_ID_PREFIX_RE.match(run_id_prefix):
        raise ValueError(f"run_id_prefix must match ^[A-Za-z0-9_-]{{1,56}}$, got {run_id_prefix!r}")
    ensure_ascii_ps_safe("run_id_prefix", run_id_prefix)

    source_commit = args.source_commit or detect_git_value(repo_root, ["rev-parse", "HEAD"])
    if not source_commit or not HEX_40_RE.match(source_commit):
        raise ValueError(f"source_commit must be a 40-hex commit SHA, got {source_commit!r}")
    source_branch = args.source_branch or detect_git_value(repo_root, ["rev-parse", "--abbrev-ref", "HEAD"]) or "HEAD"
    ensure_ascii_ps_safe("source_branch", source_branch)

    ref_dir = (
        Path(os.path.abspath(args.reference_card_dir))
        if args.reference_card_dir
        else Path(os.path.abspath(case_root / DEFAULT_REFERENCE_CARD_NAME))
    )
    if not ref_dir.is_dir():
        ref_dir = None

    target_apk_src = resolve_input_file(
        args.target_apk,
        [
            repo_root / "app/probe/build/outputs/apk/debug/probe-debug.apk",
            (ref_dir / "artifacts/probe-debug.apk") if ref_dir else None,
        ],
        "probe-debug.apk",
    )
    test_apk_src = resolve_input_file(
        args.test_apk,
        [
            repo_root / "app/probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk",
            (ref_dir / "artifacts/probe-debug-androidTest.apk") if ref_dir else None,
        ],
        "probe-debug-androidTest.apk",
    )
    helper_exe_src = resolve_input_file(
        args.helper_exe,
        [
            (ref_dir / "artifacts/phone-condition.exe") if ref_dir else None,
        ],
        "phone-condition.exe",
    )
    profile_json_src = resolve_input_file(
        args.profile_json,
        [repo_root / preset["profile_relpath"]],
        "profile JSON",
    )
    ui_binding_rel = preset["ui_binding_relpath"]
    ui_binding_src = resolve_input_file(
        args.ui_binding,
        [
            (repo_root / ui_binding_rel) if ui_binding_rel else None,
            (ref_dir / preset["ui_binding_filename"]) if ref_dir else None,
        ],
        "UI binding JSON",
    )
    conn_adm_src = resolve_input_file(
        args.connection_admission,
        [(ref_dir / "connection-admission.ps1") if ref_dir else None],
        "connection-admission.ps1",
    )
    topo_adm_src = resolve_input_file(
        args.topology_admission,
        [(ref_dir / "topology-admission.ps1") if ref_dir else None],
        "topology-admission.ps1",
    )

    ui_binding_obj = json.loads(ui_binding_src.read_text(encoding="utf-8"))
    if "app_version_code" in ui_binding_obj and int(ui_binding_obj["app_version_code"]) != args.version:
        raise ValueError(
            f"UI binding app_version_code ({ui_binding_obj['app_version_code']}) does not match --version ({args.version})"
        )
    ui_binding_filename = preset["ui_binding_filename"]
    ensure_ascii_ps_safe("ui_binding_filename", ui_binding_filename)

    profile_sha = sha256_file(profile_json_src)
    ui_binding_sha = sha256_file(ui_binding_src)
    ui_binding_bytes = ui_binding_src.stat().st_size

    target_meta = read_apk_metadata(
        target_apk_src,
        {"package": "com.aneb.probe.codex", "version_code": 20, "version_name": "0.2.0-codex"},
    )
    test_meta = read_apk_metadata(
        test_apk_src,
        {"package": f"{target_meta['package']}.test"},
    )
    ensure_ascii_ps_safe("target_package", target_meta["package"])
    ensure_ascii_ps_safe("test_package", test_meta["package"])

    # Create immutable card directory and stage binaries & admission scripts
    artifacts_dir = out_dir / "artifacts"
    artifacts_dir.mkdir(parents=True, exist_ok=False)

    staged_target_apk = artifacts_dir / "probe-debug.apk"
    staged_test_apk = artifacts_dir / "probe-debug-androidTest.apk"
    staged_helper_exe = artifacts_dir / "phone-condition.exe"
    staged_conn_adm = out_dir / "connection-admission.ps1"
    staged_topo_adm = out_dir / "topology-admission.ps1"
    staged_ui_binding = out_dir / ui_binding_filename

    shutil.copyfile(target_apk_src, staged_target_apk)
    shutil.copyfile(test_apk_src, staged_test_apk)
    shutil.copyfile(helper_exe_src, staged_helper_exe)
    shutil.copyfile(conn_adm_src, staged_conn_adm)
    shutil.copyfile(topo_adm_src, staged_topo_adm)
    shutil.copyfile(ui_binding_src, staged_ui_binding)

    target_apk_sha = sha256_file(staged_target_apk)
    test_apk_sha = sha256_file(staged_test_apk)
    helper_exe_sha = sha256_file(staged_helper_exe)
    conn_adm_sha = sha256_file(staged_conn_adm)
    topo_adm_sha = sha256_file(staged_topo_adm)

    # DAG Step 1: CANDIDATE_ARTIFACTS.json
    candidate_artifacts = {
        "format": preset["artifacts_format"],
        "card_id": args.card_id,
        "source_repo": posix_path(repo_root),
        "source_branch": source_branch,
        "source_commit": source_commit,
        "target_business_package": preset["business_package"],
        "target_business_version_code": args.version,
        "target_business_version_name": args.version_name,
        "profile_asset": preset["profile_asset"],
        "profile_sha256": profile_sha,
        "ui_binding_file": ui_binding_filename,
        "ui_binding_bytes": ui_binding_bytes,
        "ui_binding_sha256": ui_binding_sha,
        "ui_observation_method": preset["ui_observation_method"],
        "visible_projection_consent_confirm": True,
        "artifacts": {
            "probe_debug_apk": {
                "path": "artifacts/probe-debug.apk",
                "package": target_meta["package"],
                "version_code": target_meta["version_code"],
                "version_name": target_meta["version_name"],
                "bytes": staged_target_apk.stat().st_size,
                "sha256": target_apk_sha,
            },
            "probe_debug_android_test_apk": {
                "path": "artifacts/probe-debug-androidTest.apk",
                "package": test_meta["package"],
                "runner": f"{test_meta['package']}/androidx.test.runner.AndroidJUnitRunner",
                "entry_point": f"{preset['instrumentation_class']}#runOneProfile",
                "bytes": staged_test_apk.stat().st_size,
                "sha256": test_apk_sha,
            },
            "phone_condition_exe": {
                "path": "artifacts/phone-condition.exe",
                "bytes": staged_helper_exe.stat().st_size,
                "sha256": helper_exe_sha,
            },
        },
    }
    candidate_artifacts_bytes = (json.dumps(candidate_artifacts, ensure_ascii=False, indent=4) + "\n").encode("utf-8")
    candidate_artifacts_sha = write_lf_bytes(out_dir / "CANDIDATE_ARTIFACTS.json", candidate_artifacts_bytes)

    phase_order_ps = "'R', 'B'" if args.order == "R->B" else "'B', 'R'"
    rate_mbps = (args.downlink_bps * 8) / 1_000_000.0
    card_title = (
        f"{preset['card_title_prefix']} Controlled R/B Card {args.card_id}: "
        f"R=inspect | B={rate_mbps:.3f}Mbps delay={args.delay_ms}ms loss={args.loss_bps}bps "
        f"order={args.order}"
    )
    ensure_ascii_ps_safe("card_title", card_title)

    common_tokens = {
        "{{CARD_TITLE}}": card_title,
        "{{CARD_ROOT}}": posix_path(out_dir),
        "{{CASE_ROOT}}": posix_path(case_root),
        "{{ADB_EXE}}": posix_path(args.adb_exe),
        "{{ADB_SERIAL}}": args.adb_serial,
        "{{NATIVE_TTL_SECONDS}}": str(args.ttl),
        "{{SOURCE_COMMIT}}": source_commit,
        "{{PROFILE_SHA256}}": profile_sha,
        "{{BUSINESS_PACKAGE}}": preset["business_package"],
        "{{BUSINESS_VERSION_CODE}}": str(args.version),
        "{{TARGET_PACKAGE}}": target_meta["package"],
        "{{TEST_PACKAGE}}": test_meta["package"],
        "{{RUN_ID_PREFIX}}": run_id_prefix,
        "{{UI_OBSERVATION_METHOD}}": preset["ui_observation_method"],
        "{{B_DOWNLINK_IP_BYTES_PER_SECOND}}": str(args.downlink_bps),
        "{{B_DOWNLINK_DELAY_MS}}": str(args.delay_ms),
        "{{B_DOWNLINK_LOSS_BPS}}": str(args.loss_bps),
        "{{B_IMPAIRMENT_SEED}}": str(args.seed),
        "{{B_QUEUE_OVERFLOW_POLICY}}": args.queue_overflow_policy,
        "{{B_QUEUE_LIMIT_BYTES}}": str(args.queue_limit_bytes),
        "{{CANDIDATE_ARTIFACTS_SHA256}}": candidate_artifacts_sha,
        "{{UI_BINDING_FILENAME}}": ui_binding_filename,
        "{{UI_BINDING_SHA256}}": ui_binding_sha,
        "{{TARGET_APK_SHA256}}": target_apk_sha,
        "{{TEST_APK_SHA256}}": test_apk_sha,
        "{{HELPER_SHA256}}": helper_exe_sha,
        "{{CONNECTION_ADMISSION_SHA256}}": conn_adm_sha,
        "{{TOPOLOGY_ADMISSION_SHA256}}": topo_adm_sha,
    }

    # DAG Step 2: common.ps1
    common_bytes = render_template(template_dir / "common.ps1.tmpl", common_tokens)
    common_sha = write_lf_bytes(out_dir / "common.ps1", common_bytes)

    # DAG Step 3: execute-once.ps1 and verify-cleanup-readonly.ps1
    execute_tokens = {
        "{{UI_VALIDATOR_FN}}": preset["ui_validator_fn"],
        "{{OUTCOME_FORMAT}}": preset["outcome_format"],
        "{{PROBE_CANDIDATE_HOSTS}}": preset["probe_candidate_hosts_ps"],
        "{{CARD_FORMAT}}": preset["card_format"],
        "{{CARD_PO}}": preset["card_po"],
        "{{PHASE_ORDER_PS}}": phase_order_ps,
        "{{INSTRUMENTATION_CLASS}}": preset["instrumentation_class"],
    }
    execute_bytes = render_template(template_dir / "execute-once.ps1.tmpl", execute_tokens)
    execute_sha = write_lf_bytes(out_dir / "execute-once.ps1", execute_bytes)

    verify_tokens = {
        "{{CARD_ROOT}}": posix_path(out_dir),
        "{{COMMON_SHA256}}": common_sha,
    }
    verify_bytes = render_template(template_dir / "verify-cleanup-readonly.ps1.tmpl", verify_tokens)
    verify_sha = write_lf_bytes(out_dir / "verify-cleanup-readonly.ps1", verify_bytes)

    # DAG Step 4: run-once.ps1
    run_tokens = {
        "{{CARD_ROOT}}": posix_path(out_dir),
        "{{COMMON_SHA256}}": common_sha,
        "{{EXECUTE_ONCE_SHA256}}": execute_sha,
        "{{VERIFY_CLEANUP_SHA256}}": verify_sha,
        "{{CARD_PO}}": preset["card_po"],
        "{{OUTER_FORMAT}}": preset["outer_format"],
        "{{CARD_BANNER}}": preset["card_banner"],
    }
    run_bytes = render_template(template_dir / "run-once.ps1.tmpl", run_tokens)
    run_sha = write_lf_bytes(out_dir / "run-once.ps1", run_bytes)

    uplink_spec = None
    if any(
        v is not None
        for v in (
            args.uplink_bps,
            args.uplink_delay_ms,
            args.uplink_loss_bps,
            args.uplink_queue_overflow_policy,
            args.uplink_queue_limit_bytes,
        )
    ):
        uplink_spec = {
            "uplink_ip_bytes_per_second": args.uplink_bps,
            "uplink_delay_ms": args.uplink_delay_ms,
            "uplink_loss_bps": args.uplink_loss_bps,
            "uplink_queue_overflow_policy": args.uplink_queue_overflow_policy,
            "uplink_queue_limit_bytes": args.uplink_queue_limit_bytes,
            "emitted_to_request_json": False,
        }

    # DAG Step 5: CARD_SPEC.json
    card_spec = {
        "format": "controlled-rb-card-spec-1",
        "card_id": args.card_id,
        "app": args.app,
        "business_package": preset["business_package"],
        "business_version_code": args.version,
        "business_version_name": args.version_name,
        "order": args.order,
        "phases": ["R", "B"] if args.order == "R->B" else ["B", "R"],
        "native_ttl_seconds": args.ttl,
        "run_id_prefix": run_id_prefix,
        "source_commit": source_commit,
        "source_branch": source_branch,
        "condition_b": {
            "downlink_ip_bytes_per_second": args.downlink_bps,
            "downlink_delay_ms": args.delay_ms,
            "downlink_loss_bps": args.loss_bps,
            "impairment_seed": args.seed,
            "queue_overflow_policy": args.queue_overflow_policy,
            "queue_limit_bytes": args.queue_limit_bytes,
            "uplink": uplink_spec,
        },
        "sha256_pins": {
            "CANDIDATE_ARTIFACTS.json": candidate_artifacts_sha,
            "common.ps1": common_sha,
            "execute-once.ps1": execute_sha,
            "verify-cleanup-readonly.ps1": verify_sha,
            "run-once.ps1": run_sha,
            "connection-admission.ps1": conn_adm_sha,
            "topology-admission.ps1": topo_adm_sha,
            ui_binding_filename: ui_binding_sha,
            "profile_json": profile_sha,
            "artifacts/probe-debug.apk": target_apk_sha,
            "artifacts/probe-debug-androidTest.apk": test_apk_sha,
            "artifacts/phone-condition.exe": helper_exe_sha,
        },
    }
    write_lf_bytes(
        out_dir / "CARD_SPEC.json",
        (json.dumps(card_spec, ensure_ascii=False, indent=4) + "\n").encode("utf-8"),
    )
    return card_spec


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    repo_default = Path(__file__).resolve().parent.parent.parent
    ap = argparse.ArgumentParser(description="Generate an immutable SHA-256-locked Controlled R/B card directory")
    ap.add_argument("--card-id", required=True, help="Card directory name / ID ([A-Za-z0-9._-]+)")
    ap.add_argument("--app", choices=sorted(APP_PRESETS.keys()), default="douyin")
    ap.add_argument("--version", type=int, default=None, help="Target business app versionCode")
    ap.add_argument("--version-name", default=None, help="Target business app versionName")
    ap.add_argument("--downlink-bps", "--rate", dest="downlink_bps", type=int, required=True,
                    help="Condition B downlink limit in IP bytes/sec (1..1000000000)")
    ap.add_argument("--delay-ms", "--delay", dest="delay_ms", type=int, default=0,
                    help="Condition B downlink delay in ms (0..2000)")
    ap.add_argument("--loss-bps", "--loss", dest="loss_bps", type=int, default=0,
                    help="Condition B downlink loss in basis points (0..5000)")
    ap.add_argument("--seed", type=int, default=None,
                    help="Deterministic PRNG seed (default 20261120 if loss > 0 else 0)")
    ap.add_argument("--queue-overflow-policy", default="", choices=list(ALLOWED_QUEUE_POLICIES))
    ap.add_argument("--queue-limit-bytes", type=int, default=0)
    ap.add_argument("--uplink-bps", type=int, default=None)
    ap.add_argument("--uplink-delay-ms", type=int, default=None)
    ap.add_argument("--uplink-loss-bps", type=int, default=None)
    ap.add_argument("--uplink-queue-overflow-policy", default=None)
    ap.add_argument("--uplink-queue-limit-bytes", type=int, default=None)
    ap.add_argument("--order", choices=("R->B", "B->R"), default="R->B")
    ap.add_argument("--ttl", type=int, default=100, help="WinDivert native TTL seconds (1..115)")
    ap.add_argument("--run-id-prefix", default=None)
    ap.add_argument("--case-root", default=DEFAULT_CASE_ROOT)
    ap.add_argument("--out-dir", default=None)
    ap.add_argument("--repo-root", default=str(repo_default))
    ap.add_argument("--reference-card-dir", default=None)
    ap.add_argument("--adb-exe", default=DEFAULT_ADB_EXE)
    ap.add_argument("--adb-serial", default=DEFAULT_ADB_SERIAL)
    ap.add_argument("--source-commit", default=None)
    ap.add_argument("--source-branch", default=None)
    ap.add_argument("--target-apk", default=None)
    ap.add_argument("--test-apk", default=None)
    ap.add_argument("--helper-exe", default=None)
    ap.add_argument("--profile-json", default=None)
    ap.add_argument("--ui-binding", default=None)
    ap.add_argument("--connection-admission", default=None)
    ap.add_argument("--topology-admission", default=None)
    return ap.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    spec = build_card(args)
    print(json.dumps({
        "generated_card_id": spec["card_id"],
        "app": spec["app"],
        "order": spec["order"],
        "run_once_sha256": spec["sha256_pins"]["run-once.ps1"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
