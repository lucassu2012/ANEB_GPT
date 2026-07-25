from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from scripts.tests.test_verify_token_quick_client_db import (
    create_room_schema,
    valid_body as token_valid_body,
)


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify_realtime_quick_client_db.py"
RUN_ID = "019f6d5f-7400-7000-8000-000000000002"
PROFILE_DIR = ROOT / "profiles" / "published" / "ai_realtime_voice_quick"
PROFILE = json.loads((PROFILE_DIR / "profile.json").read_text(encoding="utf-8"))
RUNTIME = json.loads((PROFILE_DIR / "runtime_plan.json").read_text(encoding="utf-8"))
PROFILE_SHA = "701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7"
RUNTIME_SHA = "f2472d2faa7a3ab51582e1496a6925d106806fdd9747e097e20e38e921d9dc07"
SCORER_METRICS: dict[
    str,
    tuple[float | None, float | None, int, float | None, dict[str, float]],
] = {
    "LIVE-B01": (1.0, 1.0, 1, 100.0, {}),
    "LIVE-B02": (400.0, 1.0, 1, 100.0, {}),
    "LIVE-B03": (375.0, 1.0, 3, 100.0, {}),
    "LIVE-B04": (25.0, 1.0, 3, 100.0, {}),
    "LIVE-B05": (1.0, 1.0, 676, 100.0, {}),
    "LIVE-B06": (0.0, 1.0, 676, 100.0, {}),
    "LIVE-B07": (0.0, 1.0, 676, 100.0, {}),
    "LIVE-B08": (120.0, 1.0, 1, 100.0, {}),
    "LIVE-B09": (1.0, 1.0, 3, 100.0, {}),
    "LIVE-B10": (0.0, 1.0, 1, 100.0, {}),
    "LIVE-B11": (None, None, 0, None, {}),
    "LIVE-B12": (0.0, 1.0, 2, 100.0, {}),
    "LIVE-N01": (100.0, 1.0, 1, 100.0, {}),
    "LIVE-N02": (42.0, 1.0, 20, 100.0, {}),
    "LIVE-N03": (0.0, 1.0, 673, 100.0, {}),
    "LIVE-N04": (0.0, 1.0, 676, 100.0, {}),
    "LIVE-N05": (0.0, 1.0, 676, 100.0, {}),
    "LIVE-N06": (
        0.384,
        0.5,
        6,
        34.375,
        {"uplink_p05_mbps": 0.512, "downlink_p05_mbps": 0.384},
    ),
    "LIVE-N07": (59.5, 1.0, 3, 100.0, {}),
    "LIVE-N08": (0.0, None, 1, None, {}),
    "LIVE-R01": (None, None, 0, None, {}),
}
TARGET_COMPLIANCE = {
    metric_id: target
    for metric_id, target in {
        **{f"LIVE-B{index:02d}": 0.95 for index in (2, 3, 4, 8, 11)},
        **{f"LIVE-B{index:02d}": 0.99 for index in (1, 5, 6, 7, 9, 10, 12)},
        **{f"LIVE-N{index:02d}": 0.95 for index in (1, 2, 3, 5, 6, 7)},
        "LIVE-N04": 0.99,
        "LIVE-N08": 0.0,
        "LIVE-R01": 0.0,
    }.items()
}


def canonical_sha(value: object) -> str:
    payload = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def quality_target(definition: dict[str, object]) -> object:
    raw = definition.get("quality_target")
    if raw is None:
        return None
    assert isinstance(raw, dict)
    return {
        "operator": raw.get("operator", ""),
        "value": raw.get("value"),
        "values": dict(sorted(raw.get("values", {}).items())),
        "policy_id": raw.get("policy_id"),
        "required_compliance_ratio": raw.get("required_compliance_ratio"),
        "provenance": raw.get("provenance", ""),
    }


def envelope_metric(definition: dict[str, object]) -> dict[str, object]:
    metric_id = str(definition["metric_id"])
    value, compliance, sample_count, score, components = SCORER_METRICS[metric_id]
    observed = value is not None
    domain = str(definition["domain"])
    return {
        "label": definition.get("label", ""),
        "domain": domain if domain in {"business", "network", "radio"} else "diagnostic",
        "unit": definition.get("unit", ""),
        "measurement_level": definition.get("measurement_level", ""),
        "state": "observed" if observed else "missing",
        "value": value,
        "compliance_ratio": compliance,
        "sample_count": sample_count,
        "minimum_sample_count": definition["minimum_sample_count"],
        "source_event_ids": definition.get("source_event_ids", []),
        "direction": definition.get("direction", ""),
        "required_for_score": definition.get("required_for_score", False),
        "quality_target": quality_target(definition),
        "score": score,
        "formula_id": definition.get("formula_id", ""),
        "aggregation": definition.get("aggregation", ""),
        "components": components,
        "source_evidence_ref_ids": ["realtime-raw"],
        "invalid_reason": None if observed else "measurement_unavailable",
    }


def realtime_turn(
    *,
    expected_frames: int,
    interrupted: bool,
    previous_turn: bool,
) -> dict[str, object]:
    return {
        "response_excess_ms": 25.0,
        "response_ms": 375.0,
        "expected_frames": expected_frames,
        "unique_frames": expected_frames,
        "on_time_frames": expected_frames,
        "stall_frames": 0,
        "conceal_frames": 0,
        "arrival_variation_ms": [0.0] * (expected_frames - 1),
        "barge_response_ms": 120.0 if interrupted else None,
        "max_missing_run_frames": 0,
        "uplink_goodput_kbps": 512.0,
        "downlink_goodput_kbps": 384.0,
        "unplanned_overlap": False if previous_turn else None,
        "interrupted": interrupted,
        "success": True,
    }


def valid_body() -> dict[str, object]:
    body = token_valid_body()
    body["test_type"] = "ai_realtime_simulation"
    body["producer"].update(
        component_version="0.5.13-codex",
        serialized_at_epoch_ms=1784246405000,
    )
    body["run"].update(
        run_id=RUN_ID,
        status="completed",
        validity="valid",
        invalid_reason_codes=[],
        source_record_version="room-v19-realtime-envelope-v1",
    )
    body["profile"].update(
        resolution_status="resolved",
        contract_version="aneb-profile-v2",
        profile_id="ai_realtime_voice_quick",
        profile_version="1.1.1",
        variant="quick",
        profile_fingerprint={
            "algorithm": "sha256",
            "canonicalization": "canonical-json-v1",
            "value": f"sha256:{PROFILE_SHA}",
        },
        profile_evidence_ref_id="profile-artifact",
        runtime_artifact_status="resolved",
        runtime_artifact_hash={
            "algorithm": "sha256",
            "canonicalization": "canonical-json-v1",
            "value": f"sha256:{RUNTIME_SHA}",
        },
        runtime_artifact_evidence_ref_id="runtime-artifact",
        source_uri="asset:///published/ai_realtime_voice_quick/profile.json",
    )
    body["claim"].update(
        scope="application_end_to_end_to_probe_node",
        measurement_subject="ANEB controlled AI realtime interaction simulation to the selected probe node",
        limitations=[
            "The workload simulates AI realtime behavior and does not call a third-party AI API.",
            "Audio frames and model waiting are deterministic transport stimuli, not generated speech or model inference.",
            "Quality conclusions apply only to this device, node, Profile and captured context.",
        ],
    )
    body["context"]["device"].update(
        app_version_name="0.5.13-codex",
        app_version_code=45,
    )
    body["evaluation"]["algorithm_versions"].update(
        measurement_engine_version="realtime-simulation-engine-v1",
        metric_catalog_id="realtime-sim-measurements-v1",
        target_set_id="realtime-interaction-targets-v1",
        score_policy_id="realtime-interaction-score-v1",
        score_anchor_policy_id="compliance-anchors-v1",
        conclusion_policy_id="realtime-interaction-conclusions-v2",
    )
    body["evaluation"]["score"] = {
        "state": "computed",
        "value": 100.0,
        "grade": "A",
        "verdict": "inconclusive",
        "confidence": "low",
        "confidence_basis": {
            "method_id": "realtime-sample-coverage-v1",
            "coverage_ratio": 0.1,
            "minimum_sample_satisfied": False,
        },
        "cap_reason": None,
        "not_computable_reason": None,
    }
    body["evaluation"]["group_scores"] = {
        "conversation_response": 100.0,
        "network_readiness": 100.0,
        "playout_continuity": 100.0,
        "session_reliability": 100.0,
    }
    body["evaluation"]["metrics"] = {
        str(definition["metric_id"]): envelope_metric(definition)
        for definition in PROFILE["measurements"]
    }
    body["evaluation"]["conclusions"] = [
        {
            "conclusion_id": "realtime-completion",
            "severity": "info",
            "policy_id": "realtime-interaction-conclusions-v2",
            "text": "Quick realtime interaction completed with retained evidence.",
            "basis": ["metric:LIVE-B09", "evidence:realtime-raw"],
        }
    ]
    raw = {
        "invalid_reason": None,
        "sessions": [
            {
                "established": True,
                "setup_ms": 400.0,
                "handshake_ms": 100.0,
                "unexpected_disconnect": False,
                "error": None,
                "rtt_samples_ms": [42.0] * 20,
                "loaded_rtt_samples_ms": [50.0, 55.0, 60.0],
                "recovery_ms": None,
                "reconnect_events": 0,
                "controlled_disconnect_expected": False,
                "controlled_disconnect_observed": False,
                "recovery_stimulus_baseline_ms": None,
                "turns": [
                    realtime_turn(expected_frames=350, interrupted=False, previous_turn=False),
                    realtime_turn(expected_frames=225, interrupted=False, previous_turn=True),
                    realtime_turn(expected_frames=101, interrupted=True, previous_turn=True),
                ],
            }
        ],
    }
    body["category_payload"] = {
        "evidence_contract_version": "aneb-realtime-run-evidence-v1",
        "variant": "quick",
        "behavior_model": {
            "resolution_status": "resolved",
            "model_id": RUNTIME["model_id"],
            "model_version": RUNTIME["model_version"],
            "model_hash": {
                "algorithm": "sha256",
                "canonicalization": "canonical-json-v1",
                "value": RUNTIME["model_hash"],
            },
            "calibration_status": RUNTIME["calibration_status"],
            "source_kind": PROFILE["business"]["model_source_kind"],
        },
        "raw_evidence": raw,
    }
    body["evidence"] = {
        "raw_evidence_retained": True,
        "invalid_evidence_retained": True,
        "refs": [
            {
                "ref_id": "profile-artifact",
                "kind": "content_addressed_artifact",
                "uri": "asset:///published/ai_realtime_voice_quick/profile.json",
                "media_type": "application/json",
                "digest": {
                    "algorithm": "sha256",
                    "canonicalization": "canonical-json-v1",
                    "value": f"sha256:{PROFILE_SHA}",
                },
                "record_count": 1,
                "redaction": "none",
                "description": "Canonical AI realtime Profile parsed for this run.",
            },
            {
                "ref_id": "runtime-artifact",
                "kind": "content_addressed_artifact",
                "uri": "asset:///published/ai_realtime_voice_quick/runtime_plan.json",
                "media_type": "application/json",
                "digest": {
                    "algorithm": "sha256",
                    "canonicalization": "canonical-json-v1",
                    "value": f"sha256:{RUNTIME_SHA}",
                },
                "record_count": 1,
                "redaction": "none",
                "description": "Hash-bound AI realtime runtime plan executed for this run.",
            },
            {
                "ref_id": "realtime-raw",
                "kind": "inline_json_pointer",
                "uri": "#/category_payload/raw_evidence",
                "media_type": "application/json",
                "digest": None,
                "record_count": 1,
                "redaction": "none",
                "description": "Inline session, turn, frame, RTT and recovery evidence.",
            },
        ],
        "environment_events": [],
    }
    return body


def negative_body() -> dict[str, object]:
    body = valid_body()
    body["run"].update(
        status="failed",
        validity="invalid",
        invalid_reason_codes=["receipt_missing"],
    )
    body["evaluation"]["score"] = {
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
    body["evaluation"]["group_scores"] = {}
    for metric in body["evaluation"]["metrics"].values():
        metric.update(
            state="missing",
            value=None,
            compliance_ratio=None,
            sample_count=0,
            score=None,
            components={},
            invalid_reason="measurement_not_emitted_by_current_engine",
        )
    body["evaluation"]["conclusions"] = [
        {
            "conclusion_id": "realtime-invalid-evidence",
            "severity": "failure",
            "policy_id": "realtime-interaction-conclusions-v2",
            "text": "Realtime evidence is invalid: receipt_missing; raw evidence retained and score suppressed.",
            "basis": ["evidence:realtime-raw", "invalid_reason"],
        }
    ]
    body["category_payload"]["raw_evidence"] = {
        "invalid_reason": "receipt_missing",
        "sessions": [],
    }
    body["evidence"]["refs"][2]["record_count"] = 0
    return body


def with_collected_radio(body: dict[str, object]) -> dict[str, object]:
    sample = {
        "elapsed_realtime_nanos": 1000000000,
        "cell_elapsed_realtime_nanos": 999000000,
        "stale": False,
        "sub_id": 1,
        "sub_switched": False,
        "network_type": "NR",
        "override_type": "NR_NSA",
        "nr_state": "CONNECTED",
        "rat": "NR_NSA",
        "pci": 123,
        "tac": 456,
        "arfcn": 640000,
        "rsrp_dbm": -92,
        "rsrq_db": -11,
        "sinr_db": 18,
        "operator_name": "test-operator",
    }
    body["context"]["radio"] = {
        "collection_status": "collected",
        "unavailable_reason": None,
        "operator_name": "test-operator",
        "network_type": "NR",
        "override_type": "NR_NSA",
        "nr_state": "CONNECTED",
        "rat": "NR_NSA",
        "rsrp_dbm": -92,
        "rsrq_db": -11,
        "sinr_db": 18,
        "sample_count": 1,
        "samples": [sample],
        "evidence_ref_ids": ["radio-context"],
    }
    body["evaluation"]["metrics"]["LIVE-R01"].update(
        state="observed",
        sample_count=1,
        source_evidence_ref_ids=["radio-context"],
        invalid_reason=None,
    )
    body["evidence"]["refs"].append(
        {
            "ref_id": "radio-context",
            "kind": "inline_json_pointer",
            "uri": "#/context/radio/samples",
            "media_type": "application/json",
            "digest": None,
            "record_count": 1,
            "redaction": "location_removed",
            "description": "Inline 1Hz public Android radio observations; coordinates are excluded.",
        }
    )
    return body


def typed_metrics(body: dict[str, object]) -> dict[str, object]:
    result: dict[str, object] = {}
    definitions = {
        str(definition["metric_id"]): definition
        for definition in PROFILE["measurements"]
    }
    for metric_id, metric in body["evaluation"]["metrics"].items():
        value, compliance, sample_count, score, components = SCORER_METRICS[metric_id]
        result[metric_id] = {
            "value": value,
            "compliance_ratio": compliance,
            "sample_count": sample_count,
            "minimum_sample_count": definitions[metric_id]["minimum_sample_count"],
            "target_compliance_ratio": TARGET_COMPLIANCE[metric_id],
            "score": score,
            "component_values": components,
        }
    return result


def typed_evidence(body: dict[str, object]) -> dict[str, object]:
    return {
        "contract_version": "aneb-realtime-run-evidence-v1",
        "variant": "quick",
        **body["category_payload"]["raw_evidence"],
    }


def write_database(path: Path, body: dict[str, object]) -> None:
    serialized = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    score = body["evaluation"]["score"]
    algorithms = body["evaluation"]["algorithm_versions"]
    model = body["category_payload"]["behavior_model"]
    connection = sqlite3.connect(path)
    try:
        create_room_schema(connection)
        connection.execute(
            """
            INSERT INTO realtime_simulation_result (
                runId, startedAtEpochMs, serverBase, claimScope, profileId,
                profileVersion, behaviorModelId, behaviorModelVersion,
                behaviorModelHash, calibrationStatus, variant, scorePolicyId,
                scoreAnchorPolicyId, conclusionPolicyId, totalScore, grade,
                verdict, confidence, capReason, metricsJson, conclusionsJson,
                evidenceJson
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                RUN_ID,
                body["run"]["started_at_epoch_ms"],
                body["context"]["endpoint"]["server_base"],
                body["claim"]["scope"],
                "ai_realtime_voice_quick",
                "1.1.1",
                model["model_id"],
                model["model_version"],
                model["model_hash"]["value"],
                model["calibration_status"],
                "quick",
                algorithms["score_policy_id"],
                algorithms["score_anchor_policy_id"],
                algorithms["conclusion_policy_id"],
                score["value"],
                score["grade"],
                score["verdict"].upper(),
                score["confidence"].upper(),
                score["cap_reason"],
                json.dumps(
                    (
                        typed_metrics(body)
                        if body["run"]["validity"] == "valid"
                        else {}
                    ),
                    separators=(",", ":"),
                ),
                json.dumps(
                    [item["text"] for item in body["evaluation"]["conclusions"]],
                    separators=(",", ":"),
                ),
                json.dumps(typed_evidence(body), separators=(",", ":")),
            ),
        )
        connection.execute(
            """
            INSERT INTO result_envelope (
                runId, schemaVersion, testType, startedAtEpochMs,
                serializedAtEpochMs, canonicalSha256, bodyJson
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                RUN_ID,
                "aneb-result-v2",
                "ai_realtime_simulation",
                body["run"]["started_at_epoch_ms"],
                body["producer"]["serialized_at_epoch_ms"],
                canonical_sha(body),
                serialized,
            ),
        )
        connection.commit()
    finally:
        connection.close()


class RealtimeQuickClientDbVerifierTests(unittest.TestCase):
    def run_verifier(
        self,
        database: Path,
        *,
        mode: str = "positive",
        protocol: Path | None = None,
        result_output: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            sys.executable,
            str(SCRIPT),
            str(database),
            "--run-id",
            RUN_ID,
            "--mode",
            mode,
        ]
        if protocol is not None:
            command.extend(("--protocol", str(protocol)))
        if result_output is not None:
            command.extend(("--result-output", str(result_output)))
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )

    def test_accepts_exact_positive_quick_result_and_reports_protocol_counts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, valid_body())
            completed = self.run_verifier(database)

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual("positive", report["mode"])
        self.assertEqual(1, report["session_count"])
        self.assertEqual(3, report["turn_count"])
        self.assertEqual(676, report["expected_downlink_frames"])
        self.assertEqual(676, report["unique_downlink_frames"])
        self.assertEqual(1, report["interrupted_turns"])
        self.assertEqual(3, report["loaded_rtt_attempts"])
        self.assertEqual(f"sha256:{PROFILE_SHA}", report["profile_sha256"])
        self.assertEqual(f"sha256:{RUNTIME_SHA}", report["runtime_plan_sha256"])
        self.assertTrue(report["frozen_source_unchanged"])
        self.assertTrue(report["analysis_copy_used"])

    def test_accepts_exact_receipt_missing_negative_without_business_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, negative_body())
            completed = self.run_verifier(database, mode="negative")

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual("pass", report["status"])
        self.assertEqual("ok", report["reason_code"])
        self.assertEqual("negative", report["mode"])
        self.assertEqual("receipt_missing", report["invalid_reason"])
        self.assertEqual(0, report["session_count"])
        self.assertEqual(0, report["turn_count"])
        self.assertEqual(0, report["expected_downlink_frames"])
        self.assertEqual(0, report["unique_downlink_frames"])
        self.assertEqual(0, report["interrupted_turns"])
        self.assertEqual(0, report["loaded_rtt_attempts"])
        self.assertEqual("suppressed_invalid", report["score_state"])

    def test_rejects_self_consistent_positive_with_rewritten_protocol_frame_count(self) -> None:
        body = valid_body()
        turn = body["category_payload"]["raw_evidence"]["sessions"][0]["turns"][0]
        turn.update(
            expected_frames=349,
            unique_frames=349,
            on_time_frames=349,
            arrival_variation_ms=[0.0] * 348,
        )
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "turn_runtime_contract_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_protocol_contract_with_rewritten_uplink_count(self) -> None:
        protocol = json.loads(
            (
                ROOT
                / "spec"
                / "execution-contracts"
                / "ai_realtime_voice_quick-1.1.1.protocol.json"
            ).read_text(encoding="utf-8")
        )
        protocol["runtime"]["uplink_frames"] = 399
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            protocol_path = root / "protocol.json"
            protocol_path.write_text(
                json.dumps(protocol, separators=(",", ":")),
                encoding="utf-8",
            )
            write_database(database, valid_body())
            completed = self.run_verifier(database, protocol=protocol_path)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "protocol_runtime_derivation_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_self_consistent_fabricated_score(self) -> None:
        body = valid_body()
        body["evaluation"]["score"].update(value=99.0, grade="B")
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "score_recomputation_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_rejects_metric_metadata_detached_from_published_profile(self) -> None:
        body = valid_body()
        body["evaluation"]["metrics"]["LIVE-B05"]["label"] = "fabricated label"
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(1, completed.returncode)
        self.assertEqual(
            "envelope_metric_metadata_mismatch",
            json.loads(completed.stdout)["reason_code"],
        )

    def test_accepts_collected_radio_without_conflating_radio_series_with_scorer_metric(self) -> None:
        body = with_collected_radio(valid_body())
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "aneb-probe.db"
            write_database(database, body)
            completed = self.run_verifier(database)

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(1, report["radio_sample_count"])
        self.assertEqual(21, report["typed_metrics_verified"])

    def test_exclusively_publishes_the_verified_client_result(self) -> None:
        body = valid_body()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "aneb-probe.db"
            result_output = root / "client-result.json"
            write_database(database, body)
            completed = self.run_verifier(
                database,
                result_output=result_output,
            )

            self.assertEqual(
                0,
                completed.returncode,
                completed.stdout + completed.stderr,
            )
            self.assertEqual(
                body,
                json.loads(result_output.read_text(encoding="utf-8")),
            )
            self.assertEqual([], list(root.glob(".client-result.json.*.tmp")))


if __name__ == "__main__":
    unittest.main()
