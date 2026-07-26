#!/usr/bin/env python3
"""Fail-closed verifier for one frozen Network Comprehensive Quick Room result."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import tempfile
from typing import Any, Sequence


PROFILE_DIR = Path(__file__).resolve().parents[1] / "profiles" / "published" / "network_comprehensive_quick"
PROFILE_ID = "network_comprehensive_quick"
PROFILE_VERSION = "1.2.0"
PROFILE_SHA256 = "15ae5187fac72d86b78ff89ad44d5a51706dc7c4e4cf01432f367acd9ed082cc"
RUNTIME_SHA256 = "8981267030abd4cd95dabe3e3bff8d2af4b7de6b8659cc8c267c97f519cf2603"
APP_VERSION_NAME = "0.5.14-codex"
APP_VERSION_CODE = 46
ROOM_IDENTITY_HASH = "5eea8eb8e2b9f91767a34e3499c77484"
MAX_DATABASE_BYTES = 128 * 1024 * 1024
RUN_ID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


class VerificationError(RuntimeError):
    pass


def fail(code: str) -> None:
    raise VerificationError(code)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("json_duplicate_member")
        result[key] = value
    return result


def strict_json(text: str, code: str) -> Any:
    try:
        return json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
        )
    except (TypeError, UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise VerificationError(code) from error


def canonical_sha256(value: object) -> str:
    try:
        payload = json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, UnicodeError, ValueError) as error:
        raise VerificationError("canonical_json_invalid") from error
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def require_dict(value: object, code: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(code)
    return value


def require_list(value: object, code: str) -> list[Any]:
    if not isinstance(value, list):
        fail(code)
    return value


def read_manifest(path: Path) -> dict[str, str]:
    try:
        text = path.read_text(encoding="ascii")
    except (OSError, UnicodeError) as error:
        raise VerificationError("manifest_unreadable") from error
    if "\r" in text or not text.endswith("\n"):
        fail("manifest_noncanonical")
    entries: dict[str, str] = {}
    for line in text.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if match is None or match.group(2) in entries:
            fail("manifest_noncanonical")
        entries[match.group(2)] = match.group(1)
    if set(entries) != {"profile.json", "runtime_plan.json"}:
        fail("manifest_coverage_invalid")
    return entries


def verify_published_bundle(profile_path: Path, runtime_path: Path, manifest_path: Path) -> tuple[str, str]:
    try:
        profile = strict_json(profile_path.read_text(encoding="utf-8"), "profile_json_invalid")
        runtime = strict_json(runtime_path.read_text(encoding="utf-8"), "runtime_json_invalid")
    except OSError as error:
        raise VerificationError("published_bundle_unreadable") from error
    entries = read_manifest(manifest_path)
    profile_digest = canonical_sha256(profile).removeprefix("sha256:")
    runtime_digest = canonical_sha256(runtime).removeprefix("sha256:")
    if (
        entries["profile.json"] != profile_digest
        or entries["runtime_plan.json"] != runtime_digest
        or profile_digest != PROFILE_SHA256
        or runtime_digest != RUNTIME_SHA256
    ):
        fail("published_bundle_digest_mismatch")
    profile_object = require_dict(profile, "profile_shape_invalid")
    runtime_object = require_dict(runtime, "runtime_shape_invalid")
    execution = require_dict(profile_object.get("execution_plan"), "execution_plan_missing")
    if (
        profile_object.get("profile_id") != PROFILE_ID
        or profile_object.get("version") != PROFILE_VERSION
        or execution.get("artifact_hash") != f"sha256:{RUNTIME_SHA256}"
        or execution.get("seed") != 20260727
        or execution.get("variant") != "quick"
        or runtime_object.get("profile_id") != PROFILE_ID
        or runtime_object.get("profile_version") != PROFILE_VERSION
        or runtime_object.get("seed") != 20260727
        or runtime_object.get("variant") != "quick"
    ):
        fail("published_bundle_identity_mismatch")
    return f"sha256:{profile_digest}", f"sha256:{runtime_digest}"


def source_hashes(database: Path) -> dict[str, str]:
    if database.is_symlink() or not database.is_file():
        fail("database_invalid")
    paths = [database, Path(str(database) + "-wal"), Path(str(database) + "-shm")]
    result: dict[str, str] = {}
    for path in paths:
        if path.exists():
            if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_DATABASE_BYTES:
                fail("database_sidecar_invalid")
            result[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def query_frozen_database(database: Path, run_id: str) -> tuple[sqlite3.Row, sqlite3.Row, dict[str, str]]:
    before = source_hashes(database)
    with tempfile.TemporaryDirectory(prefix="aneb-network-room-") as temporary:
        root = Path(temporary)
        for name in before:
            source = database.parent / name
            shutil.copy2(source, root / name)
        analysis = root / database.name
        connection = sqlite3.connect(analysis.resolve().as_uri() + "?mode=ro", uri=True)
        connection.row_factory = sqlite3.Row
        try:
            integrity = connection.execute("PRAGMA integrity_check").fetchall()
            if [row[0] for row in integrity] != ["ok"]:
                fail("database_integrity_failed")
            typed_rows = connection.execute(
                "SELECT * FROM network_comprehensive_result WHERE runId = ?", (run_id,)
            ).fetchall()
            envelope_rows = connection.execute(
                "SELECT * FROM result_envelope WHERE runId = ?", (run_id,)
            ).fetchall()
            identity_rows = connection.execute(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ).fetchall()
        except sqlite3.Error as error:
            raise VerificationError("database_query_failed") from error
        finally:
            connection.close()
    if source_hashes(database) != before:
        fail("frozen_source_modified_during_analysis")
    if (
        len(typed_rows) != 1
        or len(envelope_rows) != 1
        or len(identity_rows) != 1
        or identity_rows[0][0] != ROOM_IDENTITY_HASH
    ):
        fail("database_row_cardinality_invalid")
    return typed_rows[0], envelope_rows[0], before


def _null_network_metrics(typed: sqlite3.Row) -> bool:
    return all(
        typed[name] is None
        for name in (
            "downloadMbps",
            "uploadMbps",
            "idleRttMs",
            "loadedRttMs",
            "latencyDeltaMs",
            "jitterMs",
            "requestLossRate",
            "throughputRobustCv",
            "udpNonReturnRate",
            "postLoadPingMs",
        )
    )


def verify_database(
    database: Path,
    run_id: str,
    mode: str,
    profile_path: Path,
    runtime_path: Path,
    manifest_path: Path,
    expected_server_base: str | None,
) -> dict[str, object]:
    if RUN_ID_RE.fullmatch(run_id) is None or mode not in {"positive", "negative"}:
        fail("arguments_invalid")
    profile_digest, runtime_digest = verify_published_bundle(profile_path, runtime_path, manifest_path)
    typed, envelope, frozen_hashes = query_frozen_database(database, run_id)
    if envelope["schemaVersion"] != "aneb-result-v2" or envelope["testType"] != "network_comprehensive":
        fail("result_envelope_identity_mismatch")
    body_text = envelope["bodyJson"]
    if not isinstance(body_text, str):
        fail("result_body_invalid")
    body = require_dict(strict_json(body_text, "result_body_invalid"), "result_body_invalid")
    if canonical_sha256(body) != envelope["canonicalSha256"]:
        fail("result_envelope_digest_mismatch")
    if body.get("schema_version") != "aneb-result-v2" or body.get("test_type") != "network_comprehensive":
        fail("result_body_identity_mismatch")
    producer = require_dict(body.get("producer"), "producer_missing")
    run = require_dict(body.get("run"), "run_missing")
    profile = require_dict(body.get("profile"), "profile_missing")
    device = require_dict(require_dict(body.get("context"), "context_missing").get("device"), "device_missing")
    endpoint = require_dict(require_dict(body.get("context"), "context_missing").get("endpoint"), "endpoint_missing")
    category = require_dict(body.get("category_payload"), "category_payload_missing")
    transfer = require_dict(category.get("transfer_summary"), "transfer_summary_missing")
    raw = require_dict(category.get("raw_evidence"), "raw_evidence_missing")
    score = require_dict(require_dict(body.get("evaluation"), "evaluation_missing").get("score"), "score_missing")
    profile_hash = require_dict(profile.get("profile_fingerprint"), "profile_hash_missing")
    runtime_hash = require_dict(profile.get("runtime_artifact_hash"), "runtime_hash_missing")
    if (
        producer.get("component") != "aneb-probe-android"
        or producer.get("component_version") != APP_VERSION_NAME
        or device.get("app_package") != "com.aneb.probe.codex"
        or device.get("app_version_name") != APP_VERSION_NAME
        or device.get("app_version_code") != APP_VERSION_CODE
        or run.get("run_id") != run_id
        or run.get("source_record_version") != "room-v19-network-envelope-v1"
        or profile.get("profile_id") != PROFILE_ID
        or profile.get("profile_version") != PROFILE_VERSION
        or profile.get("variant") != "quick"
        or profile.get("runtime_artifact_status") != "resolved"
        or profile_hash.get("value") != profile_digest
        or runtime_hash.get("value") != runtime_digest
        or typed["profileId"] != PROFILE_ID
        or typed["profileVersion"] != PROFILE_VERSION
        or typed["variant"] != "quick"
    ):
        fail("cross_layer_identity_mismatch")
    if expected_server_base is not None and endpoint.get("server_base") != expected_server_base:
        fail("server_base_mismatch")

    attempts = raw.get("app_request_attempts")
    successes = raw.get("app_request_successes")
    udp_sent = raw.get("udp_packets_sent")
    udp_received = require_list(raw.get("udp_received_seqs"), "udp_received_invalid")
    if mode == "positive":
        if (
            run.get("status") != "completed"
            or run.get("validity") != "valid"
            or run.get("invalid_reason_codes") != []
            or typed["status"] != "completed"
            or typed["totalScore"] is None
            or typed["grade"] is None
            or typed["downloadBytes"] <= 0
            or typed["uploadBytes"] <= 0
            or transfer.get("download_bytes") != typed["downloadBytes"]
            or transfer.get("upload_bytes") != typed["uploadBytes"]
            or not isinstance(attempts, int)
            or not isinstance(successes, int)
            or attempts <= 0
            or not 0 < successes <= attempts
            or udp_sent != 50
            or len(set(udp_received)) != len(udp_received)
            or any(not isinstance(seq, int) or seq < 0 or seq >= 50 for seq in udp_received)
            or raw.get("invalid_reason") is not None
            or score.get("state") != "computed"
        ):
            fail("positive_result_contract_invalid")
        reason_code = None
    else:
        if (
            run.get("status") != "failed"
            or run.get("validity") != "invalid"
            or run.get("invalid_reason_codes") != ["receipt_missing"]
            or typed["status"] != "invalid"
            or typed["totalScore"] is not None
            or typed["grade"] is not None
            or not _null_network_metrics(typed)
            or typed["downloadBytes"] != 0
            or typed["uploadBytes"] != 0
            or transfer.get("download_bytes") != 0
            or transfer.get("upload_bytes") != 0
            or attempts != 0
            or successes != 0
            or udp_sent != 0
            or udp_received != []
            or raw.get("invalid_reason") != "receipt_missing"
            or score.get("state") != "suppressed_invalid"
            or score.get("value") is not None
            or score.get("grade") is not None
        ):
            fail("negative_zero_business_contract_invalid")
        reason_code = "receipt_missing"

    return {
        "schema": "aneb-network-quick-client-db-verification",
        "schema_version": "1.0.0",
        "status": "pass",
        "mode": mode,
        "run_id": run_id,
        "reason_code": reason_code,
        "profile_sha256": profile_digest,
        "runtime_plan_sha256": runtime_digest,
        "result_envelope_sha256": envelope["canonicalSha256"],
        "database_files": frozen_hashes,
        "business": {
            "app_request_attempts": attempts,
            "app_request_successes": successes,
            "download_bytes": typed["downloadBytes"],
            "upload_bytes": typed["uploadBytes"],
            "udp_packets_sent": udp_sent,
            "udp_unique_returns": len(set(udp_received)),
        },
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--mode", choices=("positive", "negative"), required=True)
    parser.add_argument("--profile", type=Path, default=PROFILE_DIR / "profile.json")
    parser.add_argument("--runtime-plan", type=Path, default=PROFILE_DIR / "runtime_plan.json")
    parser.add_argument("--manifest", type=Path, default=PROFILE_DIR / "manifest.sha256")
    parser.add_argument("--expected-server-base")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    report = verify_database(
        args.database,
        args.run_id,
        args.mode,
        args.profile,
        args.runtime_plan,
        args.manifest,
        args.expected_server_base,
    )
    output = json.dumps(report, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8", newline="\n")
    sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        print(f"ERROR code={error}", file=sys.stderr)
        raise SystemExit(2)
