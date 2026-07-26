#!/usr/bin/env python3
"""Fail-closed verifier for one frozen AI Realtime Quick Room result."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
from typing import Any
import uuid

from verify_token_quick_client_db import (
    RUN_ID_RE,
    VerificationFailure,
    canonical_sha256,
    epoch_ms,
    fail,
    finite_number,
    read_manifest,
    require_dict,
    require_list,
    select_exact_one,
    source_file_hashes,
    strict_json_file,
    strict_json_text,
    uuid7_unix_ms,
    verify_result_schema,
    verify_room_schema,
)


REPORT_SCHEMA = "aneb-realtime-quick-client-db-report"
REPORT_VERSION = "0.1.0"
PROFILE_ID = "ai_realtime_voice_quick"
PROFILE_VERSION = "1.1.1"
EXPECTED_ROOM_VERSION = 19
MAX_RUN_START_DELTA_MS = 5_000
ROOT = Path(__file__).resolve().parents[1]
PROFILE_DIR = ROOT / "profiles" / "published" / PROFILE_ID
PROFILE_ASSET_BASE = f"asset:///published/{PROFILE_ID}"
PROTOCOL_PATH = (
    ROOT
    / "spec"
    / "execution-contracts"
    / "ai_realtime_voice_quick-1.1.1.protocol.json"
)
SCORER_SPECS: dict[str, tuple[int, float, bool]] = {
    "LIVE-B01": (10, 0.99, True),
    "LIVE-B02": (10, 0.95, True),
    "LIVE-B03": (10, 0.95, False),
    "LIVE-B04": (10, 0.95, True),
    "LIVE-B05": (500, 0.99, True),
    "LIVE-B06": (500, 0.99, True),
    "LIVE-B07": (500, 0.99, True),
    "LIVE-B08": (2, 0.95, True),
    "LIVE-B09": (10, 0.99, True),
    "LIVE-B10": (10, 0.99, True),
    "LIVE-B11": (2, 0.95, False),
    "LIVE-B12": (10, 0.99, False),
    "LIVE-N01": (10, 0.95, True),
    "LIVE-N02": (20, 0.95, True),
    "LIVE-N03": (100, 0.95, True),
    "LIVE-N04": (500, 0.99, True),
    "LIVE-N05": (500, 0.95, False),
    "LIVE-N06": (20, 0.95, False),
    "LIVE-N07": (20, 0.95, False),
    "LIVE-N08": (1, 0.0, False),
    "LIVE-R01": (1, 0.0, False),
}


def verify_published_assets(
    manifest_path: Path,
    protocol_path: Path,
) -> tuple[
    dict[str, str],
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    dict[str, dict[str, Any]],
    str,
]:
    manifest = read_manifest(manifest_path)
    profile = require_dict(
        strict_json_file(PROFILE_DIR / "profile.json", "profile_artifact_invalid"),
        "profile_artifact_invalid",
    )
    runtime = require_dict(
        strict_json_file(
            PROFILE_DIR / "runtime_plan.json", "runtime_artifact_invalid"
        ),
        "runtime_artifact_invalid",
    )
    protocol = require_dict(
        strict_json_file(protocol_path, "protocol_contract_invalid"),
        "protocol_contract_invalid",
    )
    profile_digest = "sha256:" + manifest["profile.json"]
    runtime_digest = "sha256:" + manifest["runtime_plan.json"]
    if canonical_sha256(profile) != profile_digest:
        fail("profile_artifact_digest_mismatch")
    if canonical_sha256(runtime) != runtime_digest:
        fail("runtime_artifact_digest_mismatch")
    if (
        profile.get("contract_version") != "aneb-profile-v2"
        or profile.get("profile_id") != PROFILE_ID
        or profile.get("version") != PROFILE_VERSION
        or profile.get("claim_scope") != "application_end_to_end_to_probe_node"
        or profile.get("measurement_catalog_id") != "realtime-sim-measurements-v1"
        or runtime.get("contract_version") != "aneb-realtime-runtime-plan-v1"
        or runtime.get("variant") != "quick"
        or runtime.get("session_count") != 1
    ):
        fail("published_asset_identity_mismatch")
    business = require_dict(profile.get("business"), "profile_business_invalid")
    evaluation = require_dict(profile.get("evaluation"), "profile_evaluation_invalid")
    for field in ("model_id", "model_version", "model_hash", "calibration_status"):
        profile_value = business.get(
            {
                "model_id": "behavior_model_id",
                "model_version": "behavior_model_version",
                "model_hash": "behavior_model_hash",
                "calibration_status": "calibration_status",
            }[field]
        )
        if profile_value != runtime.get(field):
            fail("published_execution_contract_mismatch")
    if (
        evaluation.get("target_set_id") != "realtime-interaction-targets-v1"
        or evaluation.get("score_policy_id") != "realtime-interaction-score-v1"
        or evaluation.get("score_anchor_policy_id") != "compliance-anchors-v1"
        or evaluation.get("conclusion_policy_id")
        != "realtime-interaction-conclusions-v2"
    ):
        fail("published_execution_contract_mismatch")
    measurements = require_list(
        profile.get("measurements"), "profile_measurements_invalid"
    )
    measurement_specs: dict[str, dict[str, Any]] = {}
    for value in measurements:
        definition = require_dict(value, "profile_measurement_invalid")
        metric_id = definition.get("metric_id")
        if not isinstance(metric_id, str) or metric_id in measurement_specs:
            fail("profile_measurement_set_mismatch")
        measurement_specs[metric_id] = definition
    if set(measurement_specs) != (
        {f"LIVE-B{index:02d}" for index in range(1, 13)}
        | {f"LIVE-N{index:02d}" for index in range(1, 9)}
        | {"LIVE-R01"}
    ):
        fail("profile_measurement_set_mismatch")

    protocol_profile = require_dict(
        protocol.get("profile"), "protocol_contract_invalid"
    )
    protocol_runtime = require_dict(
        protocol.get("runtime"), "protocol_contract_invalid"
    )
    if (
        protocol.get("schema") != "aneb-realtime-protocol-bounded-contract"
        or protocol.get("contract_id") != "aneb-realtime-quick-protocol-bounds"
        or protocol.get("version") != "1.1.0"
        or protocol_profile
        != {
            "id": PROFILE_ID,
            "version": PROFILE_VERSION,
            "canonical_sha256": profile_digest,
        }
        or protocol_runtime.get("canonical_sha256") != runtime_digest
        or protocol.get("applies_to") != ["positive_completed"]
        or protocol.get("exact_business_counts") != {"realtime_sim": 1}
    ):
        fail("protocol_contract_identity_mismatch")
    runtime_sessions = require_list(
        runtime.get("sessions"), "runtime_sessions_invalid"
    )
    runtime_turns = [
        require_dict(turn, "runtime_turn_invalid")
        for session_value in runtime_sessions
        for turn in require_list(
            require_dict(session_value, "runtime_session_invalid").get("turns"),
            "runtime_turns_invalid",
        )
    ]
    frame_values = {
        require_dict(session, "runtime_session_invalid").get("frame_ms")
        for session in runtime_sessions
    }
    effective_downlink_frames = sum(
        int(turn["barge_in_after_frames"])
        if turn.get("interrupted") is True
        else int(turn["planned_downlink_frames"])
        for turn in runtime_turns
    )
    maximum_emitted_downlink_frames = sum(
        min(
            int(turn["planned_downlink_frames"]),
            int(turn["downlink_frames_before_stop"])
            + math.ceil(
                int(turn["expected_stop_within_ms"])
                / int(session["frame_ms"])
            ),
        )
        if turn.get("interrupted") is True
        else int(turn["planned_downlink_frames"])
        for session in runtime_sessions
        for turn in require_list(
            require_dict(session, "runtime_session_invalid").get("turns"),
            "runtime_turns_invalid",
        )
    )
    expected_runtime = {
        "canonical_sha256": runtime_digest,
        "session_count": len(runtime_sessions),
        "turn_count": len(runtime_turns),
        "frame_ms": next(iter(frame_values)) if len(frame_values) == 1 else None,
        "uplink_frames": sum(int(turn["uplink_frames"]) for turn in runtime_turns),
        "uplink_payload_bytes": sum(
            int(turn["uplink_frames"]) * int(turn["uplink_frame_bytes"])
            for turn in runtime_turns
        ),
        "planned_downlink_frames": sum(
            int(turn["planned_downlink_frames"]) for turn in runtime_turns
        ),
        "planned_downlink_payload_bytes": sum(
            int(turn["planned_downlink_frames"])
            * int(turn["downlink_frame_bytes"])
            for turn in runtime_turns
        ),
        "effective_downlink_frames": effective_downlink_frames,
        "effective_downlink_payload_bytes": sum(
            (
                int(turn["barge_in_after_frames"])
                if turn.get("interrupted") is True
                else int(turn["planned_downlink_frames"])
            )
            * int(turn["downlink_frame_bytes"])
            for turn in runtime_turns
        ),
        "max_emitted_downlink_frames": maximum_emitted_downlink_frames,
        "max_emitted_downlink_payload_bytes": sum(
            (
                min(
                    int(turn["planned_downlink_frames"]),
                    int(turn["downlink_frames_before_stop"])
                    + math.ceil(
                        int(turn["expected_stop_within_ms"])
                        / int(session["frame_ms"])
                    ),
                )
                if turn.get("interrupted") is True
                else int(turn["planned_downlink_frames"])
            )
            * int(turn["downlink_frame_bytes"])
            for session in runtime_sessions
            for turn in require_list(
                require_dict(session, "runtime_session_invalid").get("turns"),
                "runtime_turns_invalid",
            )
        ),
        "interrupted_turns": sum(
            turn.get("interrupted") is True for turn in runtime_turns
        ),
        "max_stop_within_ms": max(
            (
                int(turn["expected_stop_within_ms"])
                for turn in runtime_turns
                if turn.get("expected_stop_within_ms") is not None
            ),
            default=0,
        ),
    }
    if protocol_runtime != expected_runtime:
        fail("protocol_runtime_derivation_mismatch")
    return (
        manifest,
        profile,
        runtime,
        protocol_runtime,
        measurement_specs,
        canonical_sha256(protocol).removeprefix("sha256:"),
    )


def query_analysis_copy(
    database: Path,
    source_hashes: dict[str, str],
    analysis_root: Path,
    run_id: str,
) -> tuple[sqlite3.Row, sqlite3.Row, str]:
    for source_name, expected_hash in source_hashes.items():
        source = database.parent / source_name
        destination = analysis_root / source_name
        try:
            shutil.copyfile(source, destination)
        except OSError:
            fail("database_analysis_copy_failed")
        if hashlib.sha256(destination.read_bytes()).hexdigest() != expected_hash:
            fail("database_analysis_copy_digest_mismatch")
    connection: sqlite3.Connection | None = None
    try:
        analysis_db = analysis_root / database.name
        connection = sqlite3.connect(analysis_db.resolve().as_uri() + "?mode=ro", uri=True)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")
        quick_check = connection.execute("PRAGMA quick_check").fetchall()
        if len(quick_check) != 1 or quick_check[0][0] != "ok":
            fail("database_integrity_failed")
        room_identity = verify_room_schema(connection)
        typed = select_exact_one(
            connection,
            """
            SELECT runId, startedAtEpochMs, serverBase, claimScope, profileId,
                   profileVersion, behaviorModelId, behaviorModelVersion,
                   behaviorModelHash, calibrationStatus, variant, scorePolicyId,
                   scoreAnchorPolicyId, conclusionPolicyId, totalScore, grade,
                   verdict, confidence, capReason, metricsJson, conclusionsJson,
                   evidenceJson
              FROM realtime_simulation_result WHERE runId = ?
            """,
            run_id,
            "typed_result_cardinality_invalid",
        )
        envelope = select_exact_one(
            connection,
            """
            SELECT runId, schemaVersion, testType, startedAtEpochMs,
                   serializedAtEpochMs, canonicalSha256, bodyJson
              FROM result_envelope WHERE runId = ?
            """,
            run_id,
            "result_envelope_cardinality_invalid",
        )
        forbidden = (
            "test_run",
            "token_event",
            "scenario_result",
            "echo_sample",
            "report_body",
            "continuity_result",
            "ab_result",
            "basic_speed_result",
            "token_simulation_result",
            "network_comprehensive_result",
        )
        for table in forbidden:
            count = connection.execute(
                f'SELECT COUNT(*) FROM "{table}" WHERE runId = ?', (run_id,)
            ).fetchone()[0]
            if count != 0:
                fail("unexpected_same_run_rows")
        return typed, envelope, room_identity
    except VerificationFailure:
        raise
    except sqlite3.Error:
        fail("database_query_failed")
    finally:
        if connection is not None:
            connection.close()


def expected_quality_target(definition: dict[str, Any]) -> object:
    raw = definition.get("quality_target")
    if raw is None:
        return None
    target = require_dict(raw, "profile_quality_target_invalid")
    values = target.get("values", {})
    if not isinstance(values, dict):
        fail("profile_quality_target_invalid")
    return {
        "operator": target.get("operator", ""),
        "value": target.get("value"),
        "values": dict(sorted(values.items())),
        "policy_id": target.get("policy_id"),
        "required_compliance_ratio": target.get("required_compliance_ratio"),
        "provenance": target.get("provenance", ""),
    }


def normalized_metric_domain(value: object) -> str:
    domain = str(value or "")
    return domain if domain in {"business", "network", "radio"} else "diagnostic"


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = min(max(quantile, 0.0), 1.0) * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def ratio(values: list[bool]) -> float | None:
    if not values:
        return None
    return sum(values) / len(values)


def compliance_score(value: float) -> float:
    bounded = min(max(value, 0.0), 1.0)
    anchors = (
        (0.0, 0.0),
        (0.80, 55.0),
        (0.90, 70.0),
        (0.95, 85.0),
        (1.0, 100.0),
    )
    for index, upper in enumerate(anchors):
        if bounded <= upper[0]:
            if index == 0:
                return upper[1]
            lower = anchors[index - 1]
            fraction = (bounded - lower[0]) / (upper[0] - lower[0])
            return lower[1] + fraction * (upper[1] - lower[1])
    return anchors[-1][1]


def metric_projection(
    metric_id: str,
    value: float | None,
    compliance: float | None,
    sample_count: int,
    components: dict[str, float] | None = None,
) -> dict[str, object]:
    minimum, target, _ = SCORER_SPECS[metric_id]
    return {
        "value": value,
        "compliance_ratio": compliance,
        "sample_count": sample_count,
        "minimum_sample_count": minimum,
        "target_compliance_ratio": target,
        "score": None if compliance is None else compliance_score(compliance),
        "component_values": components or {},
    }


def kotlin_round1(value: float) -> float:
    return math.floor(value * 10.0 + 0.5) / 10.0


def recompute_positive(
    raw: dict[str, Any],
) -> tuple[
    dict[str, dict[str, object]],
    dict[str, float],
    dict[str, object],
]:
    sessions = [
        require_dict(value, "session_invalid")
        for value in require_list(raw.get("sessions"), "sessions_missing")
    ]
    turns = [
        require_dict(value, "turn_invalid")
        for session in sessions
        for value in require_list(session.get("turns"), "turns_missing")
    ]
    frames_expected = sum(int(turn["expected_frames"]) for turn in turns)
    frames_unique = sum(int(turn["unique_frames"]) for turn in turns)
    on_time_frames = sum(int(turn["on_time_frames"]) for turn in turns)
    stall_frames = sum(int(turn["stall_frames"]) for turn in turns)
    conceal_frames = sum(int(turn["conceal_frames"]) for turn in turns)
    variations = [
        float(value)
        for turn in turns
        for value in require_list(
            turn.get("arrival_variation_ms"), "turn_variation_invalid"
        )
    ]
    barge = [
        float(turn["barge_response_ms"])
        for turn in turns
        if turn.get("interrupted") is True
        and turn.get("barge_response_ms") is not None
    ]
    rtt = [
        float(value)
        for session in sessions
        for value in require_list(session.get("rtt_samples_ms"), "rtt_samples_missing")
    ]
    loaded_attempts = [
        value
        for session in sessions
        for value in require_list(
            session.get("loaded_rtt_samples_ms"), "loaded_rtt_samples_missing"
        )
    ]
    loaded = [float(value) for value in loaded_attempts if value is not None]
    setup = [
        float(session["setup_ms"])
        for session in sessions
        if session.get("setup_ms") is not None
    ]
    handshake = [
        float(session["handshake_ms"])
        for session in sessions
        if session.get("handshake_ms") is not None
    ]
    response_raw = [
        float(turn["response_ms"])
        for turn in turns
        if turn.get("response_ms") is not None
    ]
    response_excess = [
        float(turn["response_excess_ms"])
        for turn in turns
        if turn.get("response_excess_ms") is not None
    ]
    absolute_variations = [abs(value) for value in variations]
    variation_p95 = percentile(absolute_variations, 0.95)
    variation_p50 = percentile(absolute_variations, 0.50)
    variation_spread = (
        max(0.0, variation_p95 - variation_p50)
        if variation_p95 is not None and variation_p50 is not None
        else None
    )
    overlap = [
        bool(turn["unplanned_overlap"])
        for turn in turns
        if turn.get("unplanned_overlap") is not None
    ]
    missing_runs = [
        int(turn["max_missing_run_frames"])
        for turn in turns
        if turn.get("max_missing_run_frames") is not None
    ]
    uplink_mbps = [
        float(turn["uplink_goodput_kbps"]) / 1_000.0
        for turn in turns
        if turn.get("uplink_goodput_kbps") is not None
    ]
    downlink_mbps = [
        float(turn["downlink_goodput_kbps"]) / 1_000.0
        for turn in turns
        if turn.get("downlink_goodput_kbps") is not None
    ]
    uplink_p05 = percentile(uplink_mbps, 0.05)
    downlink_p05 = percentile(downlink_mbps, 0.05)
    goodput_components = {}
    if uplink_p05 is not None:
        goodput_components["uplink_p05_mbps"] = uplink_p05
    if downlink_p05 is not None:
        goodput_components["downlink_p05_mbps"] = downlink_p05
    goodput_compliance = ratio(
        [value >= 0.50 for value in uplink_mbps]
        + [value >= 0.50 for value in downlink_mbps]
    )
    establish = ratio([session.get("established") is True for session in sessions])
    session_continuity = ratio(
        [session.get("unexpected_disconnect") is False for session in sessions]
    )
    frame_completeness = (
        frames_unique / frames_expected if frames_expected > 0 else None
    )
    metrics = {
        "LIVE-B01": metric_projection(
            "LIVE-B01", establish, establish, len(sessions)
        ),
        "LIVE-B02": metric_projection(
            "LIVE-B02",
            percentile(setup, 0.95),
            ratio(
                [
                    session.get("setup_ms") is not None
                    and float(session["setup_ms"]) <= 2_000.0
                    for session in sessions
                ]
            ),
            len(sessions),
        ),
        "LIVE-B03": metric_projection(
            "LIVE-B03",
            percentile(response_raw, 0.95),
            ratio([value <= 200.0 for value in response_excess]),
            len(response_raw),
        ),
        "LIVE-B04": metric_projection(
            "LIVE-B04",
            percentile(response_excess, 0.95),
            ratio([value <= 200.0 for value in response_excess]),
            len(response_excess),
        ),
        "LIVE-B05": metric_projection(
            "LIVE-B05",
            on_time_frames / frames_expected if frames_expected > 0 else None,
            on_time_frames / frames_expected if frames_expected > 0 else None,
            frames_expected,
        ),
        "LIVE-B06": metric_projection(
            "LIVE-B06",
            stall_frames / frames_expected if frames_expected > 0 else None,
            1.0 - stall_frames / frames_expected if frames_expected > 0 else None,
            frames_expected,
        ),
        "LIVE-B07": metric_projection(
            "LIVE-B07",
            conceal_frames / frames_expected if frames_expected > 0 else None,
            1.0 - conceal_frames / frames_expected if frames_expected > 0 else None,
            frames_expected,
        ),
        "LIVE-B08": metric_projection(
            "LIVE-B08",
            percentile(barge, 0.95),
            ratio([value <= 300.0 for value in barge]),
            len(barge),
        ),
        "LIVE-B09": metric_projection(
            "LIVE-B09",
            ratio([turn.get("success") is True for turn in turns]),
            ratio([turn.get("success") is True for turn in turns]),
            len(turns),
        ),
        "LIVE-B10": metric_projection(
            "LIVE-B10",
            None if session_continuity is None else 1.0 - session_continuity,
            session_continuity,
            len(sessions),
        ),
        "LIVE-B11": metric_projection("LIVE-B11", None, None, 0),
        "LIVE-B12": metric_projection(
            "LIVE-B12",
            ratio(overlap),
            None if ratio(overlap) is None else 1.0 - float(ratio(overlap)),
            len(overlap),
        ),
        "LIVE-N01": metric_projection(
            "LIVE-N01",
            percentile(handshake, 0.95),
            ratio(
                [
                    session.get("handshake_ms") is not None
                    and float(session["handshake_ms"]) <= 1_000.0
                    for session in sessions
                ]
            ),
            len(sessions),
        ),
        "LIVE-N02": metric_projection(
            "LIVE-N02",
            percentile(rtt, 0.95),
            ratio([value <= 100.0 for value in rtt]),
            len(rtt),
        ),
        "LIVE-N03": metric_projection(
            "LIVE-N03",
            variation_spread,
            ratio([value <= 30.0 for value in absolute_variations]),
            len(variations),
        ),
        "LIVE-N04": metric_projection(
            "LIVE-N04",
            None if frame_completeness is None else 1.0 - frame_completeness,
            frame_completeness,
            frames_expected,
        ),
        "LIVE-N05": metric_projection(
            "LIVE-N05",
            float(max(missing_runs)) if missing_runs else None,
            None
            if not missing_runs
            else (1.0 if max(missing_runs) <= 3 else 0.0),
            frames_expected,
        ),
        "LIVE-N06": metric_projection(
            "LIVE-N06",
            (
                min(uplink_p05, downlink_p05)
                if uplink_p05 is not None and downlink_p05 is not None
                else None
            ),
            goodput_compliance,
            len(uplink_mbps) + len(downlink_mbps),
            goodput_components,
        ),
        "LIVE-N07": metric_projection(
            "LIVE-N07",
            percentile(loaded, 0.95),
            ratio(
                [
                    value is not None and float(value) <= 150.0
                    for value in loaded_attempts
                ]
            ),
            len(loaded_attempts),
        ),
        "LIVE-N08": metric_projection(
            "LIVE-N08",
            float(sum(int(session["reconnect_events"]) for session in sessions)),
            None,
            len(sessions),
        ),
        "LIVE-R01": metric_projection("LIVE-R01", None, None, 0),
    }
    required = [
        metric_id for metric_id, (_, _, required) in SCORER_SPECS.items() if required
    ]
    coverage = min(
        min(
            float(metrics[metric_id]["sample_count"])
            / float(metrics[metric_id]["minimum_sample_count"]),
            1.0,
        )
        for metric_id in required
    )
    minimum_satisfied = all(
        int(metrics[metric_id]["sample_count"])
        >= int(metrics[metric_id]["minimum_sample_count"])
        for metric_id in required
    )
    confidence = (
        "medium"
        if all(
            float(metrics[metric_id]["sample_count"])
            / float(metrics[metric_id]["minimum_sample_count"])
            >= 0.5
            for metric_id in required
        )
        else "low"
    )
    score_value = lambda metric_id: float(metrics[metric_id]["score"])
    groups = {
        "conversation_response": (
            score_value("LIVE-B04") * (20.0 / 35.0)
            + score_value("LIVE-B08") * (10.0 / 35.0)
            + score_value("LIVE-B02") * (2.5 / 35.0)
            + score_value("LIVE-N01") * (2.5 / 35.0)
        ),
        "playout_continuity": (
            score_value("LIVE-B05") * (15.0 / 35.0)
            + score_value("LIVE-B06") * (15.0 / 35.0)
            + score_value("LIVE-B07") * (5.0 / 35.0)
        ),
        "session_reliability": (
            score_value("LIVE-B09") * 0.40
            + score_value("LIVE-B10") * 0.40
            + score_value("LIVE-B01") * 0.20
        ),
        "network_readiness": (
            score_value("LIVE-N02") * 0.40
            + score_value("LIVE-N03") * 0.30
            + score_value("LIVE-N04") * 0.30
        ),
    }
    total = (
        groups["conversation_response"] * 0.35
        + groups["playout_continuity"] * 0.35
        + groups["session_reliability"] * 0.20
        + groups["network_readiness"] * 0.10
    )
    turn_success = metrics["LIVE-B09"]["value"]
    stall_rate = metrics["LIVE-B06"]["value"]
    cap_reason = (
        "turn_success_below_80_percent"
        if turn_success is not None and float(turn_success) < 0.80
        else (
            "audio_stall_rate_above_5_percent"
            if stall_rate is not None and float(stall_rate) > 0.05
            else None
        )
    )
    if cap_reason is not None:
        total = min(total, 54.0)
    all_targets_met = all(
        metrics[metric_id]["compliance_ratio"] is not None
        and float(metrics[metric_id]["compliance_ratio"]) + 1e-12
        >= float(metrics[metric_id]["target_compliance_ratio"])
        for metric_id in required
    )
    verdict = (
        "inconclusive"
        if confidence != "high"
        else ("fail" if cap_reason is not None or not all_targets_met else "pass")
    )
    rounded_total = kotlin_round1(total)
    score = {
        "state": "computed",
        "value": rounded_total,
        "grade": (
            "A"
            if total >= 85.0
            else "B"
            if total >= 70.0
            else "C"
            if total >= 55.0
            else "D"
        ),
        "verdict": verdict,
        "confidence": confidence,
        "confidence_basis": {
            "method_id": "realtime-sample-coverage-v1",
            "coverage_ratio": coverage,
            "minimum_sample_satisfied": minimum_satisfied,
        },
        "cap_reason_present": cap_reason is not None,
        "not_computable_reason": None,
    }
    return (
        metrics,
        {key: kotlin_round1(value) for key, value in groups.items()},
        score,
    )


def equal_number(actual: object, expected: object) -> bool:
    if actual is None or expected is None:
        return actual is expected
    return finite_number(actual) and finite_number(expected) and math.isclose(
        float(actual), float(expected), rel_tol=0.0, abs_tol=1e-9
    )


def verify_typed_projection(
    typed: sqlite3.Row,
    body: dict[str, Any],
    raw: dict[str, Any],
    measurement_specs: dict[str, dict[str, Any]],
    recomputed_metrics: dict[str, dict[str, object]],
    *,
    radio_sample_count: int | None,
) -> int:
    metrics = require_dict(
        require_dict(body.get("evaluation"), "evaluation_missing").get("metrics"),
        "metrics_missing",
    )
    if set(metrics) != set(measurement_specs):
        fail("envelope_metric_set_mismatch")
    typed_metrics = require_dict(
        strict_json_text(typed["metricsJson"], "typed_metrics_json_invalid"),
        "typed_metrics_json_invalid",
    )
    if set(typed_metrics) != set(metrics):
        fail("typed_metrics_mismatch")
    for metric_id, raw_metric in metrics.items():
        metric = require_dict(raw_metric, "envelope_metric_invalid")
        definition = measurement_specs[metric_id]
        expected = recomputed_metrics[metric_id]
        actual_typed = require_dict(typed_metrics[metric_id], "typed_metric_invalid")
        if set(actual_typed) != set(expected):
            fail("typed_metric_recomputation_mismatch")
        for field, value in expected.items():
            if field in {
                "value",
                "compliance_ratio",
                "target_compliance_ratio",
                "score",
            }:
                if not equal_number(actual_typed.get(field), value):
                    fail("typed_metric_recomputation_mismatch")
            elif actual_typed.get(field) != value:
                fail("typed_metric_recomputation_mismatch")
        envelope_components = require_dict(
            metric.get("components"), "envelope_metric_components_invalid"
        )
        if set(metric) != {
            "label",
            "domain",
            "unit",
            "measurement_level",
            "state",
            "value",
            "compliance_ratio",
            "sample_count",
            "minimum_sample_count",
            "source_event_ids",
            "direction",
            "required_for_score",
            "quality_target",
            "score",
            "formula_id",
            "aggregation",
            "components",
            "source_evidence_ref_ids",
            "invalid_reason",
        }:
            fail("envelope_metric_fields_mismatch")
        radio_series_observed = (
            metric_id == "LIVE-R01" and radio_sample_count is not None
        )
        expected_observed = expected["value"] is not None or radio_series_observed
        expected_source_ref = (
            "radio-context" if radio_series_observed else "realtime-raw"
        )
        if (
            metric.get("label") != definition.get("label", "")
            or metric.get("domain")
            != normalized_metric_domain(definition.get("domain"))
            or metric.get("unit") != definition.get("unit", "")
            or metric.get("measurement_level")
            != definition.get("measurement_level", "")
            or metric.get("source_event_ids")
            != definition.get("source_event_ids", [])
            or metric.get("direction") != definition.get("direction", "")
            or metric.get("required_for_score")
            != definition.get("required_for_score", False)
            or metric.get("formula_id") != definition.get("formula_id", "")
            or metric.get("aggregation") != definition.get("aggregation", "")
            or metric.get("source_evidence_ref_ids") != [expected_source_ref]
            or metric.get("invalid_reason")
            != (None if expected_observed else "measurement_unavailable")
        ):
            fail("envelope_metric_metadata_mismatch")
        for field in ("value", "compliance_ratio", "score"):
            if not equal_number(metric.get(field), expected[field]):
                fail("envelope_metric_recomputation_mismatch")
        if (
            metric.get("sample_count")
            != (
                radio_sample_count
                if radio_series_observed
                else expected["sample_count"]
            )
            or metric.get("minimum_sample_count")
            != expected["minimum_sample_count"]
            or metric.get("quality_target") != expected_quality_target(definition)
            or envelope_components != expected["component_values"]
            or metric.get("state")
            != ("observed" if expected_observed else "missing")
        ):
            fail("envelope_metric_recomputation_mismatch")
    conclusions = require_list(
        require_dict(body.get("evaluation"), "evaluation_missing").get("conclusions"),
        "conclusions_missing",
    )
    typed_conclusions = require_list(
        strict_json_text(typed["conclusionsJson"], "typed_conclusions_json_invalid"),
        "typed_conclusions_json_invalid",
    )
    expected_conclusions = [
        require_dict(value, "conclusion_invalid").get("text") for value in conclusions
    ]
    if typed_conclusions != expected_conclusions:
        fail("typed_conclusions_mismatch")
    typed_evidence = require_dict(
        strict_json_text(typed["evidenceJson"], "typed_evidence_json_invalid"),
        "typed_evidence_json_invalid",
    )
    if typed_evidence != {
        "contract_version": "aneb-realtime-run-evidence-v1",
        "variant": "quick",
        **raw,
    }:
        fail("typed_evidence_mismatch")
    return len(metrics)


def verify_negative_projection(
    typed: sqlite3.Row,
    body: dict[str, Any],
    raw: dict[str, Any],
    measurement_specs: dict[str, dict[str, Any]],
    *,
    radio_sample_count: int | None,
) -> int:
    evaluation = require_dict(body.get("evaluation"), "evaluation_missing")
    metrics = require_dict(evaluation.get("metrics"), "metrics_missing")
    if set(metrics) != set(measurement_specs):
        fail("envelope_metric_set_mismatch")
    typed_metrics = require_dict(
        strict_json_text(typed["metricsJson"], "typed_metrics_json_invalid"),
        "typed_metrics_json_invalid",
    )
    if typed_metrics != {}:
        fail("negative_typed_metrics_not_empty")
    for metric_id, raw_metric in metrics.items():
        metric = require_dict(raw_metric, "envelope_metric_invalid")
        radio_series_observed = (
            metric_id == "LIVE-R01" and radio_sample_count is not None
        )
        if (
            metric.get("state")
            != ("observed" if radio_series_observed else "missing")
            or metric.get("value") is not None
            or metric.get("compliance_ratio") is not None
            or metric.get("sample_count")
            != (radio_sample_count if radio_series_observed else 0)
            or metric.get("score") is not None
            or metric.get("components") != {}
            or metric.get("source_evidence_ref_ids")
            != ["radio-context" if radio_series_observed else "realtime-raw"]
            or metric.get("invalid_reason")
            != (
                None
                if radio_series_observed
                else "measurement_not_emitted_by_current_engine"
            )
            or metric.get("minimum_sample_count")
            != measurement_specs[metric_id].get("minimum_sample_count")
        ):
            fail("negative_envelope_metric_invalid")
    conclusions = require_list(
        evaluation.get("conclusions"), "conclusions_missing"
    )
    if len(conclusions) != 1:
        fail("negative_conclusion_set_mismatch")
    conclusion = require_dict(conclusions[0], "conclusion_invalid")
    if (
        conclusion.get("conclusion_id") != "realtime-invalid-evidence"
        or conclusion.get("severity") != "failure"
        or conclusion.get("policy_id")
        != "realtime-interaction-conclusions-v2"
        or conclusion.get("basis")
        != ["evidence:realtime-raw", "invalid_reason"]
        or not isinstance(conclusion.get("text"), str)
        or not conclusion["text"]
    ):
        fail("negative_conclusion_contract_mismatch")
    typed_conclusions = require_list(
        strict_json_text(typed["conclusionsJson"], "typed_conclusions_json_invalid"),
        "typed_conclusions_json_invalid",
    )
    if typed_conclusions != [conclusion["text"]]:
        fail("typed_conclusions_mismatch")
    typed_evidence = require_dict(
        strict_json_text(typed["evidenceJson"], "typed_evidence_json_invalid"),
        "typed_evidence_json_invalid",
    )
    if typed_evidence != {
        "contract_version": "aneb-realtime-run-evidence-v1",
        "variant": "quick",
        **raw,
    }:
        fail("typed_evidence_mismatch")
    return len(metrics)


def verify_positive_protocol(
    raw: dict[str, Any],
    runtime: dict[str, Any],
    protocol: dict[str, Any],
) -> dict[str, int]:
    if raw.get("invalid_reason") is not None:
        fail("raw_evidence_invalid")
    sessions = require_list(raw.get("sessions"), "sessions_missing")
    runtime_sessions = require_list(runtime.get("sessions"), "runtime_sessions_invalid")
    if len(sessions) != 1 or len(runtime_sessions) != 1:
        fail("session_count_mismatch")
    session = require_dict(sessions[0], "session_invalid")
    runtime_session = require_dict(runtime_sessions[0], "runtime_session_invalid")
    if (
        session.get("established") is not True
        or session.get("unexpected_disconnect") is not False
        or session.get("error") is not None
        or session.get("reconnect_events") != 0
        or session.get("controlled_disconnect_expected") is not False
        or session.get("controlled_disconnect_observed") is not False
        or session.get("recovery_ms") is not None
        or session.get("recovery_stimulus_baseline_ms") is not None
        or not finite_number(session.get("setup_ms"))
        or not finite_number(session.get("handshake_ms"))
        or float(session["setup_ms"]) < 0
        or float(session["handshake_ms"]) < 0
    ):
        fail("session_contract_mismatch")
    rtt = require_list(session.get("rtt_samples_ms"), "rtt_samples_missing")
    loaded_rtt = require_list(
        session.get("loaded_rtt_samples_ms"), "loaded_rtt_samples_missing"
    )
    if (
        not rtt
        or not loaded_rtt
        or any(
            value is not None and (not finite_number(value) or float(value) <= 0)
            for value in rtt + loaded_rtt
        )
    ):
        fail("rtt_samples_invalid")
    runtime_turns = require_list(
        runtime_session.get("turns"), "runtime_turns_invalid"
    )
    turns = require_list(session.get("turns"), "turns_missing")
    if len(turns) != 3 or len(runtime_turns) != 3:
        fail("turn_count_mismatch")
    expected_total = 0
    unique_total = 0
    interrupted_total = 0
    for index, (actual_value, runtime_value) in enumerate(
        zip(turns, runtime_turns, strict=True)
    ):
        turn = require_dict(actual_value, "turn_invalid")
        planned = require_dict(runtime_value, "runtime_turn_invalid")
        interrupted = planned.get("interrupted")
        expected = (
            planned.get("barge_in_after_frames")
            if interrupted is True
            else planned.get("planned_downlink_frames")
        )
        if (
            type(expected) is not int
            or expected <= 0
            or turn.get("expected_frames") != expected
            or turn.get("unique_frames") != expected
            or turn.get("interrupted") is not interrupted
            or turn.get("success") is not True
            or (
                index == 0
                and turn.get("unplanned_overlap") is not None
            )
            or (
                index > 0
                and turn.get("unplanned_overlap") is not False
            )
        ):
            fail("turn_runtime_contract_mismatch")
        on_time_frames = turn.get("on_time_frames")
        stall_frames = turn.get("stall_frames")
        conceal_frames = turn.get("conceal_frames")
        if (
            type(on_time_frames) is not int
            or type(stall_frames) is not int
            or type(conceal_frames) is not int
            or turn.get("max_missing_run_frames") != 0
            or not 0 <= on_time_frames <= expected
            or not 0 <= conceal_frames <= expected
            or on_time_frames + conceal_frames != expected
            or not 0 <= stall_frames <= conceal_frames
            or 0 < stall_frames < 3
        ):
            fail("turn_quality_accounting_mismatch")
        for field in (
            "response_excess_ms",
            "response_ms",
            "uplink_goodput_kbps",
            "downlink_goodput_kbps",
        ):
            if not finite_number(turn.get(field)) or float(turn[field]) < 0:
                fail("turn_metric_invalid")
        if interrupted is True:
            if not finite_number(turn.get("barge_response_ms")):
                fail("turn_barge_evidence_invalid")
            interrupted_total += 1
        elif turn.get("barge_response_ms") is not None:
            fail("turn_barge_evidence_invalid")
        variations = require_list(
            turn.get("arrival_variation_ms"), "turn_variation_invalid"
        )
        if len(variations) != expected - 1 or any(
            not finite_number(value) for value in variations
        ):
            fail("turn_variation_invalid")
        expected_total += expected
        unique_total += int(turn["unique_frames"])
    if (
        protocol.get("session_count") != 1
        or protocol.get("turn_count") != 3
        or protocol.get("effective_downlink_frames") != expected_total
        or protocol.get("interrupted_turns") != interrupted_total
        or expected_total != 676
        or unique_total != 676
    ):
        fail("protocol_count_mismatch")
    return {
        "session_count": len(sessions),
        "turn_count": len(turns),
        "expected_downlink_frames": expected_total,
        "unique_downlink_frames": unique_total,
        "interrupted_turns": interrupted_total,
        "loaded_rtt_attempts": len(loaded_rtt),
    }


def verify_negative_protocol(raw: dict[str, Any]) -> dict[str, int]:
    sessions = require_list(raw.get("sessions"), "sessions_missing")
    if set(raw) != {"invalid_reason", "sessions"}:
        fail("negative_raw_evidence_fields_mismatch")
    if raw.get("invalid_reason") != "receipt_missing" or sessions != []:
        fail("negative_raw_evidence_invalid")
    return {
        "session_count": 0,
        "turn_count": 0,
        "expected_downlink_frames": 0,
        "unique_downlink_frames": 0,
        "interrupted_turns": 0,
        "loaded_rtt_attempts": 0,
    }


def verify_evidence_refs(
    body: dict[str, Any],
    manifest: dict[str, str],
    *,
    raw_record_count: int,
    radio_sample_count: int | None,
) -> None:
    evidence = require_dict(body.get("evidence"), "evidence_missing")
    refs = require_list(evidence.get("refs"), "evidence_refs_invalid")
    by_id: dict[str, dict[str, Any]] = {}
    for value in refs:
        ref = require_dict(value, "evidence_ref_invalid")
        ref_id = ref.get("ref_id")
        if not isinstance(ref_id, str) or ref_id in by_id:
            fail("evidence_ref_identity_invalid")
        by_id[ref_id] = ref
    expected_ref_ids = {"profile-artifact", "runtime-artifact", "realtime-raw"}
    if radio_sample_count is not None:
        expected_ref_ids.add("radio-context")
    if set(by_id) != expected_ref_ids:
        fail("evidence_ref_set_mismatch")
    for ref_id, file_name in (
        ("profile-artifact", "profile.json"),
        ("runtime-artifact", "runtime_plan.json"),
    ):
        ref = by_id[ref_id]
        if (
            ref.get("kind") != "content_addressed_artifact"
            or ref.get("uri") != f"{PROFILE_ASSET_BASE}/{file_name}"
            or ref.get("media_type") != "application/json"
            or ref.get("digest")
            != {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": f"sha256:{manifest[file_name]}",
            }
            or ref.get("record_count") != 1
            or ref.get("redaction") != "none"
        ):
            fail("artifact_evidence_ref_mismatch")
    raw_ref = by_id["realtime-raw"]
    if (
        raw_ref.get("kind") != "inline_json_pointer"
        or raw_ref.get("uri") != "#/category_payload/raw_evidence"
        or raw_ref.get("media_type") != "application/json"
        or raw_ref.get("digest") is not None
        or raw_ref.get("record_count") != raw_record_count
        or raw_ref.get("redaction") != "none"
    ):
        fail("realtime_evidence_ref_mismatch")
    if radio_sample_count is not None:
        radio_ref = by_id["radio-context"]
        if (
            radio_ref.get("kind") != "inline_json_pointer"
            or radio_ref.get("uri") != "#/context/radio/samples"
            or radio_ref.get("media_type") != "application/json"
            or radio_ref.get("digest") is not None
            or radio_ref.get("record_count") != radio_sample_count
            or radio_ref.get("redaction") != "location_removed"
        ):
            fail("radio_evidence_ref_mismatch")


def verify(
    database: Path,
    *,
    run_id: str,
    mode: str,
    manifest_path: Path,
    protocol_path: Path,
    expected_server_base: str | None,
) -> tuple[dict[str, object], str]:
    if RUN_ID_RE.fullmatch(run_id) is None:
        fail("run_id_not_uuidv7")
    (
        manifest,
        profile_asset,
        runtime,
        protocol,
        measurement_specs,
        protocol_definition_sha256,
    ) = verify_published_assets(manifest_path, protocol_path)
    source_before = source_file_hashes(database)
    pending_error: Exception | None = None
    query_result: tuple[sqlite3.Row, sqlite3.Row, str] | None = None
    with tempfile.TemporaryDirectory(prefix="aneb-realtime-room-analysis-") as temporary:
        analysis_root = Path(temporary)
        try:
            query_result = query_analysis_copy(
                database, source_before, analysis_root, run_id
            )
        except Exception as error:
            pending_error = error
        source_after = source_file_hashes(database)
        if source_after != source_before:
            fail("frozen_source_modified_during_analysis")
        if pending_error is not None:
            raise pending_error
        if query_result is None:
            fail("database_query_failed")
        typed, envelope, room_identity = query_result
        result_text = envelope["bodyJson"]
        if not isinstance(result_text, str):
            fail("client_json_invalid")
        verify_result_schema(result_text, analysis_root)
        body = require_dict(
            strict_json_text(result_text, "client_json_invalid"),
            "result_body_invalid",
        )
        if (
            envelope["schemaVersion"] != "aneb-result-v2"
            or envelope["testType"] != "ai_realtime_simulation"
            or body.get("schema_version") != "aneb-result-v2"
            or body.get("test_type") != "ai_realtime_simulation"
        ):
            fail("result_envelope_identity_mismatch")
        if canonical_sha256(body) != envelope["canonicalSha256"]:
            fail("result_envelope_digest_mismatch")

        producer = require_dict(body.get("producer"), "producer_missing")
        if (
            producer.get("resolution_status") != "resolved"
            or producer.get("component") != "aneb-probe-android"
            or producer.get("component_version") != "0.5.13-codex"
            or producer.get("exporter_version") != "aneb-result-exporter-v2"
        ):
            fail("producer_identity_mismatch")
        run = require_dict(body.get("run"), "run_missing")
        expected_run_state = (
            ("completed", "valid", [])
            if mode == "positive"
            else ("failed", "invalid", ["receipt_missing"])
        )
        if (
            run.get("run_id") != run_id
            or (
                run.get("status"),
                run.get("validity"),
                run.get("invalid_reason_codes"),
            )
            != expected_run_state
            or run.get("source_record_version")
            != "room-v19-realtime-envelope-v1"
        ):
            fail(
                "run_not_completed_valid"
                if mode == "positive"
                else "negative_run_identity_mismatch"
            )
        run_uuid_ms = uuid7_unix_ms(run_id)
        started_at_ms = epoch_ms(
            run.get("started_at_epoch_ms"), "run_time_fields_invalid"
        )
        ended_at_ms = epoch_ms(
            run.get("ended_at_epoch_ms"), "run_time_fields_invalid"
        )
        serialized_at_ms = epoch_ms(
            producer.get("serialized_at_epoch_ms"), "run_time_fields_invalid"
        )
        duration_ms = epoch_ms(run.get("duration_ms"), "run_time_fields_invalid")
        run_start_delta_ms = started_at_ms - run_uuid_ms
        if not 0 <= run_start_delta_ms <= MAX_RUN_START_DELTA_MS:
            fail("run_time_identity_mismatch")
        if ended_at_ms < started_at_ms or duration_ms != ended_at_ms - started_at_ms:
            fail("run_duration_mismatch")
        if serialized_at_ms != ended_at_ms:
            fail("run_serialization_time_mismatch")

        profile = require_dict(body.get("profile"), "profile_missing")
        profile_digest = "sha256:" + manifest["profile.json"]
        runtime_digest = "sha256:" + manifest["runtime_plan.json"]
        if (
            profile.get("resolution_status") != "resolved"
            or profile.get("contract_version") != "aneb-profile-v2"
            or profile.get("profile_id") != PROFILE_ID
            or profile.get("profile_version") != PROFILE_VERSION
            or profile.get("variant") != "quick"
            or profile.get("profile_fingerprint")
            != {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": profile_digest,
            }
            or profile.get("runtime_artifact_status") != "resolved"
            or profile.get("runtime_artifact_hash")
            != {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": runtime_digest,
            }
            or profile.get("profile_evidence_ref_id") != "profile-artifact"
            or profile.get("runtime_artifact_evidence_ref_id")
            != "runtime-artifact"
            or profile.get("source_uri") != f"{PROFILE_ASSET_BASE}/profile.json"
        ):
            fail("profile_runtime_identity_mismatch")

        claim = require_dict(body.get("claim"), "claim_missing")
        context = require_dict(body.get("context"), "context_missing")
        endpoint = require_dict(context.get("endpoint"), "endpoint_context_missing")
        device = require_dict(context.get("device"), "device_context_missing")
        network = require_dict(context.get("network"), "network_context_missing")
        if network.get("evidence_ref_ids") != []:
            fail("network_evidence_refs_invalid")
        radio = require_dict(context.get("radio"), "radio_context_missing")
        radio_samples = require_list(radio.get("samples"), "radio_samples_invalid")
        radio_ref_ids = require_list(
            radio.get("evidence_ref_ids"), "radio_evidence_refs_invalid"
        )
        if radio.get("collection_status") == "collected":
            if (
                not radio_samples
                or radio.get("sample_count") != len(radio_samples)
                or radio_ref_ids != ["radio-context"]
                or radio.get("unavailable_reason") is not None
            ):
                fail("radio_context_invalid")
            radio_sample_count: int | None = len(radio_samples)
        else:
            if (
                radio_samples != []
                or radio.get("sample_count") != 0
                or radio_ref_ids != []
                or not isinstance(radio.get("unavailable_reason"), str)
                or not radio["unavailable_reason"]
            ):
                fail("radio_context_invalid")
            radio_sample_count = None
        if endpoint.get("server_version") is not None:
            fail("client_server_version_must_remain_null")
        if (
            expected_server_base is not None
            and endpoint.get("server_base") != expected_server_base
        ):
            fail("client_server_base_mismatch")
        if (
            claim.get("scope") != "application_end_to_end_to_probe_node"
            or device.get("availability") != "observed"
            or device.get("app_package") != "com.aneb.probe.codex"
            or device.get("app_version_name") != "0.5.13-codex"
            or device.get("app_version_code") != 45
        ):
            fail("client_identity_mismatch")

        evaluation = require_dict(body.get("evaluation"), "evaluation_missing")
        algorithms = require_dict(
            evaluation.get("algorithm_versions"), "algorithm_versions_missing"
        )
        expected_algorithms = {
            "measurement_engine_version": "realtime-simulation-engine-v1",
            "metric_catalog_id": profile_asset["measurement_catalog_id"],
            "target_set_id": profile_asset["evaluation"]["target_set_id"],
            "score_policy_id": profile_asset["evaluation"]["score_policy_id"],
            "score_anchor_policy_id": profile_asset["evaluation"][
                "score_anchor_policy_id"
            ],
            "conclusion_policy_id": profile_asset["evaluation"][
                "conclusion_policy_id"
            ],
            "calculation_origin": "measurement_engine",
            "finalized_at_epoch_ms": serialized_at_ms,
        }
        if algorithms != expected_algorithms:
            fail("algorithm_contract_mismatch")
        score = require_dict(evaluation.get("score"), "score_missing")
        if mode == "positive":
            if (
                score.get("state") != "computed"
                or not finite_number(score.get("value"))
                or not isinstance(score.get("grade"), str)
                or score.get("verdict") in {None, "invalid"}
                or score.get("confidence") in {None, "invalid"}
            ):
                fail("evaluation_not_computed")
        elif (
            score
            != {
                "state": "suppressed_invalid",
                "value": None,
                "grade": None,
                "verdict": "invalid",
                "confidence": "invalid",
                "confidence_basis": {
                    "method_id": "realtime-sample-coverage-v1",
                    "coverage_ratio": None,
                    "minimum_sample_satisfied": False,
                },
                "cap_reason": "receipt_missing",
                "not_computable_reason": "invalid_run:receipt_missing",
            }
            or evaluation.get("group_scores") != {}
        ):
            fail("negative_score_contract_mismatch")

        category = require_dict(
            body.get("category_payload"), "category_payload_missing"
        )
        model = require_dict(category.get("behavior_model"), "behavior_model_missing")
        raw = require_dict(category.get("raw_evidence"), "raw_evidence_missing")
        business = require_dict(profile_asset["business"], "profile_business_invalid")
        if (
            category.get("evidence_contract_version")
            != "aneb-realtime-run-evidence-v1"
            or category.get("variant") != "quick"
            or model.get("resolution_status") != "resolved"
            or model.get("model_id") != runtime["model_id"]
            or model.get("model_version") != runtime["model_version"]
            or model.get("model_hash")
            != {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": runtime["model_hash"],
            }
            or model.get("calibration_status") != runtime["calibration_status"]
            or model.get("source_kind") != business["model_source_kind"]
        ):
            fail("behavior_model_contract_mismatch")

        if (
            typed["runId"] != run_id
            or typed["startedAtEpochMs"] != started_at_ms
            or typed["serverBase"] != endpoint.get("server_base")
            or typed["claimScope"] != claim.get("scope")
            or typed["profileId"] != PROFILE_ID
            or typed["profileVersion"] != PROFILE_VERSION
            or typed["behaviorModelId"] != model.get("model_id")
            or typed["behaviorModelVersion"] != model.get("model_version")
            or typed["behaviorModelHash"]
            != require_dict(model.get("model_hash"), "model_hash_missing").get(
                "value"
            )
            or typed["calibrationStatus"] != model.get("calibration_status")
            or typed["variant"] != "quick"
            or typed["scorePolicyId"] != algorithms["score_policy_id"]
            or typed["scoreAnchorPolicyId"] != algorithms["score_anchor_policy_id"]
            or typed["conclusionPolicyId"] != algorithms["conclusion_policy_id"]
            or envelope["runId"] != run_id
            or envelope["startedAtEpochMs"] != started_at_ms
            or envelope["serializedAtEpochMs"] != serialized_at_ms
        ):
            fail("typed_envelope_identity_mismatch")
        if (
            typed["totalScore"] != score.get("value")
            or typed["grade"] != score.get("grade")
            or str(typed["verdict"]).casefold()
            != str(score.get("verdict")).casefold()
            or str(typed["confidence"]).casefold()
            != str(score.get("confidence")).casefold()
            or typed["capReason"] != score.get("cap_reason")
        ):
            fail("typed_envelope_score_mismatch")

        if mode == "positive":
            counts = verify_positive_protocol(raw, runtime, protocol)
            recomputed_metrics, recomputed_groups, recomputed_score = (
                recompute_positive(raw)
            )
            group_scores = require_dict(
                evaluation.get("group_scores"), "group_scores_missing"
            )
            if set(group_scores) != set(recomputed_groups) or any(
                not equal_number(group_scores.get(group_id), expected)
                for group_id, expected in recomputed_groups.items()
            ):
                fail("group_score_recomputation_mismatch")
            confidence_basis = require_dict(
                score.get("confidence_basis"), "score_confidence_basis_invalid"
            )
            if (
                score.get("state") != recomputed_score["state"]
                or not equal_number(score.get("value"), recomputed_score["value"])
                or score.get("grade") != recomputed_score["grade"]
                or score.get("verdict") != recomputed_score["verdict"]
                or score.get("confidence") != recomputed_score["confidence"]
                or confidence_basis != recomputed_score["confidence_basis"]
                or (score.get("cap_reason") is not None)
                is not recomputed_score["cap_reason_present"]
                or score.get("not_computable_reason")
                != recomputed_score["not_computable_reason"]
            ):
                fail("score_recomputation_mismatch")
            metric_count = verify_typed_projection(
                typed,
                body,
                raw,
                measurement_specs,
                recomputed_metrics,
                radio_sample_count=radio_sample_count,
            )
            invalid_reason: str | None = None
        else:
            metric_count = verify_negative_projection(
                typed,
                body,
                raw,
                measurement_specs,
                radio_sample_count=radio_sample_count,
            )
            counts = verify_negative_protocol(raw)
            invalid_reason = "receipt_missing"
        verify_evidence_refs(
            body,
            manifest,
            raw_record_count=counts["session_count"],
            radio_sample_count=radio_sample_count,
        )
        return {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "pass",
            "reason_code": "ok",
            "mode": mode,
            "run_id": run_id,
            "run_uuid_unix_ms": run_uuid_ms,
            "run_start_delta_ms": run_start_delta_ms,
            "started_at_epoch_ms": started_at_ms,
            "ended_at_epoch_ms": ended_at_ms,
            "serialized_at_epoch_ms": serialized_at_ms,
            "room_user_version": EXPECTED_ROOM_VERSION,
            "room_identity_hash": room_identity,
            "frozen_source_sha256": source_before,
            "frozen_source_unchanged": True,
            "analysis_copy_used": True,
            "result_body_sha256": hashlib.sha256(
                result_text.encode("utf-8")
            ).hexdigest(),
            "profile_sha256": profile_digest,
            "runtime_plan_sha256": runtime_digest,
            "protocol_contract_definition_sha256": protocol_definition_sha256,
            "typed_metrics_verified": metric_count,
            "radio_sample_count": radio_sample_count or 0,
            "score_state": score["state"],
            "invalid_reason": invalid_reason,
            "strict_result_schema": "pass",
            **counts,
        }, result_text


def canonical_path(path: Path) -> str:
    return os.path.normcase(os.path.abspath(os.fspath(path.resolve(strict=False))))


def assert_safe_result_output(
    output: Path,
    *,
    database: Path,
    manifest: Path,
    protocol: Path,
) -> None:
    forbidden = {
        canonical_path(database),
        canonical_path(Path(str(database) + "-wal")),
        canonical_path(Path(str(database) + "-shm")),
        canonical_path(manifest),
        canonical_path(protocol),
        canonical_path(PROFILE_DIR / "profile.json"),
        canonical_path(PROFILE_DIR / "runtime_plan.json"),
    }
    if canonical_path(output) in forbidden:
        fail("result_output_alias_forbidden")
    if os.path.lexists(output):
        fail("result_output_exists")
    try:
        if not output.parent.is_dir():
            fail("result_output_parent_invalid")
        current = output.parent
        while True:
            if current.is_symlink():
                fail("result_output_symlink_parent_forbidden")
            if current.parent == current:
                break
            current = current.parent
    except OSError:
        fail("result_output_parent_invalid")


def publish_result_output_atomic(output: Path, result_text: str) -> None:
    payload = result_text.encode("utf-8")
    temporary = output.parent / f".{output.name}.{uuid.uuid4().hex}.tmp"
    descriptor: int | None = None
    try:
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        with os.fdopen(descriptor, "wb", closefd=True) as stream:
            descriptor = None
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, output)
    except FileExistsError:
        fail("result_output_exists")
    except OSError:
        fail("result_output_publish_failed")
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            fail("result_output_temporary_cleanup_failed")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify one frozen AI Realtime Quick result through an isolated Room "
            "analysis copy"
        )
    )
    parser.add_argument("database", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--mode", choices=("positive", "negative"), required=True)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=PROFILE_DIR / "manifest.sha256",
    )
    parser.add_argument("--protocol", type=Path, default=PROTOCOL_PATH)
    parser.add_argument("--expected-server-base")
    parser.add_argument("--result-output", type=Path)
    args = parser.parse_args()
    try:
        if args.result_output is not None:
            assert_safe_result_output(
                args.result_output,
                database=args.database,
                manifest=args.manifest,
                protocol=args.protocol,
            )
        report, result_text = verify(
            args.database,
            run_id=args.run_id,
            mode=args.mode,
            manifest_path=args.manifest,
            protocol_path=args.protocol,
            expected_server_base=args.expected_server_base,
        )
        if args.result_output is not None:
            publish_result_output_atomic(args.result_output, result_text)
        exit_code = 0
    except VerificationFailure as error:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": error.reason_code,
            "mode": args.mode,
            "run_id": args.run_id if RUN_ID_RE.fullmatch(args.run_id) else None,
        }
        exit_code = 1
    except Exception:
        report = {
            "schema": REPORT_SCHEMA,
            "schema_version": REPORT_VERSION,
            "status": "fail",
            "reason_code": "internal_verification_error",
            "mode": args.mode,
            "run_id": args.run_id if RUN_ID_RE.fullmatch(args.run_id) else None,
        }
        exit_code = 1
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
