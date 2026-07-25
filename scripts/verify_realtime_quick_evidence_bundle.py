#!/usr/bin/env python3
"""Cross-bind one AI Realtime Quick client, audit, and capability evidence set."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any

try:
    import verify_realtime_quick_negative_delivery as negative_delivery_verifier
    import verify_realtime_quick_client_db as client_db_verifier
    import verify_realtime_quick_run_audit as run_audit_verifier
except ModuleNotFoundError:  # imported as scripts.<module> by unit tests
    from scripts import (
        verify_realtime_quick_client_db as client_db_verifier,
        verify_realtime_quick_negative_delivery as negative_delivery_verifier,
        verify_realtime_quick_run_audit as run_audit_verifier,
    )


REPORT_SCHEMA = "aneb-realtime-quick-cross-bound-evidence-report"
REPORT_VERSION = "0.1.0"
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PROFILE_SHA = "701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7"
RUNTIME_SHA = "f2472d2faa7a3ab51582e1496a6925d106806fdd9747e097e20e38e921d9dc07"
MAX_INPUT_BYTES = 16 * 1024 * 1024
SERVERINFO_KEYS = {
    "version",
    "srv_ts_us",
    "anchor_wall_unix_ns",
    "uptime_s",
    "goos",
    "goarch",
    "h3_enabled",
    "tcp_slow_start_after_idle",
    "congestion_control",
    "execution_capabilities",
}


class VerificationFailure(Exception):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def fail(reason_code: str) -> None:
    raise VerificationFailure(reason_code)


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def read_json(path: Path, reason: str) -> tuple[dict[str, Any], bytes]:
    try:
        if path.is_symlink() or not path.is_file():
            fail(reason)
        stat = path.stat()
        if stat.st_size <= 0 or stat.st_size > MAX_INPUT_BYTES:
            fail(reason)
        payload = path.read_bytes()
        after = path.stat()
        if (
            stat.st_dev,
            stat.st_ino,
            stat.st_size,
            stat.st_mtime_ns,
        ) != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
        ):
            fail("input_changed_while_reading")
        value = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
        )
    except VerificationFailure:
        raise
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
        fail(reason)
    if not isinstance(value, dict):
        fail(reason)
    return value, payload


def read_bytes(path: Path, *, maximum: int, reason: str) -> bytes:
    try:
        if path.is_symlink() or not path.is_file():
            fail(reason)
        before = path.stat()
        if before.st_size <= 0 or before.st_size > maximum:
            fail(reason)
        payload = path.read_bytes()
        after = path.stat()
    except VerificationFailure:
        raise
    except OSError:
        fail(reason)
    if (
        len(payload) != before.st_size
        or (
            before.st_dev,
            before.st_ino,
            before.st_size,
            before.st_mtime_ns,
        )
        != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
        )
    ):
        fail("input_changed_while_reading")
    return payload


def require_dict(value: object, reason: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(reason)
    return value


def require_list(value: object, reason: str) -> list[Any]:
    if not isinstance(value, list):
        fail(reason)
    return value


def verify_client(
    report: dict[str, Any],
    result: dict[str, Any],
    result_payload: bytes,
    *,
    mode: str,
) -> tuple[str, dict[str, int], str]:
    run_id = report.get("run_id")
    if (
        report.get("schema") != "aneb-realtime-quick-client-db-report"
        or report.get("schema_version") != "0.1.0"
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("mode") != mode
        or not isinstance(run_id, str)
        or RUN_ID_RE.fullmatch(run_id) is None
        or report.get("frozen_source_unchanged") is not True
        or report.get("analysis_copy_used") is not True
        or report.get("strict_result_schema") != "pass"
        or report.get("room_user_version") != 19
        or report.get("typed_metrics_verified") != 21
        or report.get("profile_sha256") != f"sha256:{PROFILE_SHA}"
        or report.get("runtime_plan_sha256") != f"sha256:{RUNTIME_SHA}"
    ):
        fail("client_report_invalid")
    result_hash = hashlib.sha256(result_payload).hexdigest()
    if (
        report.get("result_body_sha256") != result_hash
        or result.get("schema_version") != "aneb-result-v2"
        or result.get("test_type") != "ai_realtime_simulation"
    ):
        fail("client_result_binding_mismatch")
    run = require_dict(result.get("run"), "client_result_invalid")
    profile = require_dict(result.get("profile"), "client_result_invalid")
    if (
        run.get("run_id") != run_id
        or profile.get("profile_id") != "ai_realtime_voice_quick"
        or profile.get("profile_version") != "1.1.1"
        or profile.get("variant") != "quick"
        or require_dict(
            profile.get("profile_fingerprint"), "client_result_invalid"
        ).get("value")
        != f"sha256:{PROFILE_SHA}"
        or require_dict(
            profile.get("runtime_artifact_hash"), "client_result_invalid"
        ).get("value")
        != f"sha256:{RUNTIME_SHA}"
    ):
        fail("client_result_identity_mismatch")
    category = require_dict(
        result.get("category_payload"), "client_result_invalid"
    )
    raw = require_dict(category.get("raw_evidence"), "client_result_invalid")
    sessions = require_list(raw.get("sessions"), "client_result_invalid")
    turns = [
        require_dict(turn, "client_result_invalid")
        for session_value in sessions
        for turn in require_list(
            require_dict(session_value, "client_result_invalid").get("turns"),
            "client_result_invalid",
        )
    ]
    loaded = sum(
        len(
            require_list(
                require_dict(session, "client_result_invalid").get(
                    "loaded_rtt_samples_ms"
                ),
                "client_result_invalid",
            )
        )
        for session in sessions
    )
    counts = {
        "session_count": len(sessions),
        "turn_count": len(turns),
        "expected_downlink_frames": sum(
            int(turn.get("expected_frames", -1)) for turn in turns
        ),
        "unique_downlink_frames": sum(
            int(turn.get("unique_frames", -1)) for turn in turns
        ),
        "interrupted_turns": sum(
            turn.get("interrupted") is True for turn in turns
        ),
        "loaded_rtt_attempts": loaded,
    }
    for key, value in counts.items():
        if report.get(key) != value:
            fail("client_count_binding_mismatch")
    if mode == "positive":
        if (
            run.get("status") != "completed"
            or run.get("validity") != "valid"
            or run.get("invalid_reason_codes") != []
            or raw.get("invalid_reason") is not None
            or report.get("score_state") != "computed"
        ):
            fail("client_positive_state_mismatch")
    elif (
        run.get("status") != "failed"
        or run.get("validity") != "invalid"
        or run.get("invalid_reason_codes") != ["receipt_missing"]
        or raw.get("invalid_reason") != "receipt_missing"
        or report.get("invalid_reason") != "receipt_missing"
        or report.get("score_state") != "suppressed_invalid"
    ):
        fail("client_negative_state_mismatch")
    protocol_digest = report.get("protocol_contract_definition_sha256")
    if not isinstance(protocol_digest, str) or SHA256_RE.fullmatch(
        protocol_digest
    ) is None:
        fail("client_protocol_contract_digest_invalid")
    return run_id, counts, protocol_digest


def verify_audit(
    report: dict[str, Any],
    *,
    run_id: str,
    mode: str,
    protocol_digest: str,
    client_counts: dict[str, int],
) -> int:
    if (
        report.get("schema") != "aneb-realtime-request-entry-audit-report"
        or report.get("schema_version") != "1.0.0"
        or report.get("status") != "pass"
        or report.get("reason_code") != "ok"
        or report.get("run_id") != run_id
        or report.get("mode") != mode
        or report.get("profile_contract")
        != "ai_realtime_voice_quick@1.1.1"
        or report.get("profile_contract_definition_sha256")
        != protocol_digest
        or report.get("evidence_scope")
        != "request_entry_and_protocol_summary_only"
    ):
        fail("audit_report_invalid")
    counts = require_dict(report.get("counts"), "audit_report_invalid")
    business = require_dict(counts.get("business"), "audit_report_invalid")
    echo = business.get("echo")
    if not isinstance(echo, int) or isinstance(echo, bool) or echo < 0:
        fail("audit_report_invalid")
    if mode == "positive":
        summary = require_dict(
            report.get("protocol_summary"), "audit_protocol_summary_missing"
        )
        if (
            business.get("realtime_sim") != 1
            or business.get("unexpected") != 0
            or counts.get("business_total") != echo + 1
            or counts.get("unattributed_business") != 0
            or counts.get("unexpected_control") != 0
            or echo != client_counts["loaded_rtt_attempts"]
            or summary
            != {
                "sessions": client_counts["session_count"],
                "turns": client_counts["turn_count"],
                "uplink_frames": 400,
                "downlink_frames": client_counts["unique_downlink_frames"],
                "interrupted_turns": client_counts["interrupted_turns"],
                "protocol_ok": True,
            }
        ):
            fail("cross_bound_protocol_mismatch")
    elif (
        echo != 0
        or business.get("realtime_sim") != 0
        or business.get("unexpected") != 0
        or counts.get("business_total") != 0
        or report.get("protocol_summary") is not None
    ):
        fail("negative_audit_not_zero_business")
    return echo


def verify_serverinfo(
    body: dict[str, Any],
) -> str:
    if (
        set(body) != SERVERINFO_KEYS
        or body.get("version") != "aneb-server/0.8.1"
        or body.get("goos") != "linux"
        or body.get("goarch") != "amd64"
        or body.get("h3_enabled") is not True
        or body.get("tcp_slow_start_after_idle") != "0"
        or body.get("congestion_control") != "cubic"
        or type(body.get("srv_ts_us")) is not int
        or int(body["srv_ts_us"]) <= 0
        or type(body.get("anchor_wall_unix_ns")) is not int
        or int(body["anchor_wall_unix_ns"]) <= 0
        or type(body.get("uptime_s")) is not int
        or int(body["uptime_s"]) <= 0
    ):
        fail("serverinfo_invalid")
    receipt = require_dict(
        body.get("execution_capabilities"), "serverinfo_capability_invalid"
    )
    if (
        set(receipt)
        != {
            "contract_id",
            "contract_version",
            "primitives",
            "validated_profiles",
        }
        or receipt.get("contract_id") != "aneb-server-capability-receipt"
        or receipt.get("contract_version") != "1.0.0"
    ):
        fail("serverinfo_capability_invalid")
    primitives: dict[str, str] = {}
    for value in require_list(
        receipt.get("primitives"), "serverinfo_capability_invalid"
    ):
        primitive = require_dict(value, "serverinfo_capability_invalid")
        primitive_id = primitive.get("primitive_id")
        wire = primitive.get("wire_contract_id")
        if (
            set(primitive) != {"primitive_id", "wire_contract_id"}
            or not isinstance(primitive_id, str)
            or not isinstance(wire, str)
            or primitive_id in primitives
        ):
            fail("serverinfo_capability_invalid")
        primitives[primitive_id] = wire
    if primitives != {
        "download": "aneb-download-v1",
        "echo": "aneb-echo-v1",
        "realtime_sim": "aneb-realtime-session-v1",
        "token_sim": "aneb-token-task-v1",
    }:
        fail("serverinfo_primitive_set_mismatch")
    profiles: dict[str, tuple[str, str]] = {}
    for value in require_list(
        receipt.get("validated_profiles"), "serverinfo_capability_invalid"
    ):
        profile = require_dict(value, "serverinfo_capability_invalid")
        profile_id = profile.get("profile_id")
        version = profile.get("profile_version")
        digest = profile.get("profile_sha256")
        if (
            set(profile)
            != {"profile_id", "profile_version", "profile_sha256"}
            or not isinstance(profile_id, str)
            or not isinstance(version, str)
            or not isinstance(digest, str)
            or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None
            or profile_id in profiles
        ):
            fail("serverinfo_capability_invalid")
        profiles[profile_id] = (version, digest)
    if profiles.get("ai_realtime_voice_quick") != (
        "1.1.1",
        f"sha256:{PROFILE_SHA}",
    ):
        fail("serverinfo_realtime_profile_mismatch")
    return str(body["version"])


def verify(
    *,
    mode: str,
    client_database_path: Path,
    client_report_path: Path,
    client_result_path: Path,
    audit_report_path: Path,
    journal_path: Path,
    start_barrier_id: str,
    barrier_id: str,
    serverinfo_path: Path,
    negative_proxy_bundle: Path | None = None,
    negative_upstream_url: str | None = None,
    negative_ca_file_sha256: str | None = None,
    negative_device_port: int | None = None,
) -> dict[str, object]:
    if mode == "positive":
        if any(
            value is not None
            for value in (
                negative_proxy_bundle,
                negative_upstream_url,
                negative_ca_file_sha256,
                negative_device_port,
            )
        ):
            fail("negative_delivery_evidence_unexpected")
    elif mode == "negative":
        if (
            negative_proxy_bundle is None
            or not isinstance(negative_upstream_url, str)
            or not negative_upstream_url
            or not isinstance(negative_ca_file_sha256, str)
            or SHA256_RE.fullmatch(negative_ca_file_sha256) is None
            or isinstance(negative_device_port, bool)
            or not isinstance(negative_device_port, int)
        ):
            fail("negative_delivery_evidence_required")
    else:
        fail("mode_invalid")
    client_report, _ = read_json(client_report_path, "client_report_unreadable")
    client_result, client_result_payload = read_json(
        client_result_path, "client_result_unreadable"
    )
    audit_report, _ = read_json(audit_report_path, "audit_report_unreadable")
    serverinfo, serverinfo_payload = read_json(
        serverinfo_path, "serverinfo_unreadable"
    )
    reported_run_id = client_report.get("run_id")
    if not isinstance(reported_run_id, str) or RUN_ID_RE.fullmatch(reported_run_id) is None:
        fail("client_report_invalid")
    if mode == "negative":
        expected_server_base = f"http://127.0.0.1:{negative_device_port}"
    else:
        result_context = require_dict(
            client_result.get("context"), "client_result_invalid"
        )
        expected_server_base = require_dict(
            result_context.get("endpoint"), "client_result_invalid"
        ).get("server_base")
        if not isinstance(expected_server_base, str) or not expected_server_base:
            fail("client_result_invalid")
    try:
        recomputed_client, recomputed_result_text = client_db_verifier.verify(
            client_database_path,
            run_id=reported_run_id,
            mode=mode,
            manifest_path=client_db_verifier.PROFILE_DIR / "manifest.sha256",
            protocol_path=client_db_verifier.PROTOCOL_PATH,
            expected_server_base=expected_server_base,
        )
    except client_db_verifier.VerificationFailure:
        fail("client_raw_revalidation_failed")
    if (
        recomputed_client != client_report
        or recomputed_result_text.encode("utf-8") != client_result_payload
    ):
        fail("client_raw_revalidation_failed")
    journal_payload = read_bytes(
        journal_path,
        maximum=run_audit_verifier.MAX_JOURNAL_BYTES,
        reason="audit_raw_revalidation_failed",
    )
    try:
        journal_text = journal_payload.decode("utf-8")
    except UnicodeError:
        fail("audit_raw_revalidation_failed")
    recomputed_audit = run_audit_verifier.verify_journal(
        journal_text,
        run_id=reported_run_id,
        start_barrier_id=start_barrier_id,
        barrier_id=barrier_id,
        mode=mode,
        profile_contract="ai_realtime_voice_quick@1.1.1",
    )
    if (
        recomputed_audit.get("status") != "pass"
        or recomputed_audit.get("reason_code") != "ok"
        or recomputed_audit != audit_report
    ):
        fail("audit_raw_revalidation_failed")
    run_id, client_counts, protocol_digest = verify_client(
        recomputed_client,
        client_result,
        client_result_payload,
        mode=mode,
    )
    echo = verify_audit(
        recomputed_audit,
        run_id=run_id,
        mode=mode,
        protocol_digest=protocol_digest,
        client_counts=client_counts,
    )
    server_version = verify_serverinfo(serverinfo)
    delivery: dict[str, object] | None = None
    if mode == "negative":
        try:
            delivery = negative_delivery_verifier.verify(
                negative_proxy_bundle,
                run_id=run_id,
                upstream_url=negative_upstream_url,
                ca_file_sha256=negative_ca_file_sha256,
                device_port=negative_device_port,
            )
        except negative_delivery_verifier.NegativeDeliveryEvidenceFailure as error:
            fail(error.reason_code)
        serverinfo_sha256 = hashlib.sha256(serverinfo_payload).hexdigest()
        if (
            delivery.get("status") != "pass"
            or delivery.get("reason_code") != "ok"
            or delivery.get("run_id") != run_id
            or delivery.get("upstream_body_sha256") != serverinfo_sha256
            or delivery.get("proxy_response_write_completed") is not True
        ):
            fail("negative_delivery_cross_binding_mismatch")
    return {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_VERSION,
        "status": "pass",
        "reason_code": "ok",
        "mode": mode,
        "run_id": run_id,
        "server_version": server_version,
        "profile_sha256": f"sha256:{PROFILE_SHA}",
        "runtime_plan_sha256": f"sha256:{RUNTIME_SHA}",
        "protocol_contract_definition_sha256": protocol_digest,
        "loaded_rtt_attempts": client_counts["loaded_rtt_attempts"],
        "server_echo_entries": echo,
        "client_result_sha256": hashlib.sha256(client_result_payload).hexdigest(),
        "serverinfo_sha256": hashlib.sha256(serverinfo_payload).hexdigest(),
        "journal_sha256": hashlib.sha256(journal_payload).hexdigest(),
        "raw_client_revalidated": True,
        "raw_audit_revalidated": True,
        "proxy_response_write_completed": (
            delivery["proxy_response_write_completed"]
            if delivery is not None
            else None
        ),
        "client_receipt_missing_bound": mode == "negative",
        "negative_delivery_receipt_sha256": (
            delivery["delivery_receipt_sha256"] if delivery is not None else None
        ),
        "cross_bound": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Cross-bind AI Realtime Quick client and server evidence"
    )
    parser.add_argument("--mode", choices=("positive", "negative"), required=True)
    parser.add_argument("--client-database", type=Path, required=True)
    parser.add_argument("--client-report", type=Path, required=True)
    parser.add_argument("--client-result", type=Path, required=True)
    parser.add_argument("--audit-report", type=Path, required=True)
    parser.add_argument("--journal", type=Path, required=True)
    parser.add_argument("--start-barrier-id", required=True)
    parser.add_argument("--barrier-id", required=True)
    parser.add_argument("--serverinfo", type=Path, required=True)
    parser.add_argument("--negative-proxy-bundle", type=Path)
    parser.add_argument("--negative-upstream-url")
    parser.add_argument("--negative-ca-file-sha256")
    parser.add_argument("--negative-device-port", type=int)
    args = parser.parse_args()
    try:
        report = verify(
            mode=args.mode,
            client_database_path=args.client_database,
            client_report_path=args.client_report,
            client_result_path=args.client_result,
            audit_report_path=args.audit_report,
            journal_path=args.journal,
            start_barrier_id=args.start_barrier_id,
            barrier_id=args.barrier_id,
            serverinfo_path=args.serverinfo,
            negative_proxy_bundle=args.negative_proxy_bundle,
            negative_upstream_url=args.negative_upstream_url,
            negative_ca_file_sha256=args.negative_ca_file_sha256,
            negative_device_port=args.negative_device_port,
        )
        exit_code = 0
    except VerificationFailure as error:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": error.reason_code,
            "mode": args.mode,
            "cross_bound": False,
        }
        exit_code = 1
    except Exception:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": "internal_verification_error",
            "mode": args.mode,
            "cross_bound": False,
        }
        exit_code = 1
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
