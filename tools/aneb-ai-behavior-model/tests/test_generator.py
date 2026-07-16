from __future__ import annotations

import json
import unittest
from pathlib import Path

from aneb_behavior_model.generator import (
    build_artifacts,
    derive_realtime_runtime_variant,
    derive_token_runtime_variant,
)
from aneb_behavior_model.model import load_model

ROOT = Path(__file__).resolve().parents[1]


class GeneratorTest(unittest.TestCase):
    def test_token_trace_is_byte_deterministic_for_same_seed(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        first = build_artifacts(model, 20260716)
        second = build_artifacts(model, 20260716)
        self.assertEqual(
            json.dumps(first.trace, sort_keys=True, separators=(",", ":")),
            json.dumps(second.trace, sort_keys=True, separators=(",", ":")),
        )
        self.assertEqual(first.manifest, second.manifest)
        self.assertEqual(first.runtime_plan, second.runtime_plan)

    def test_different_seed_changes_trace(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        first = build_artifacts(model, 1)
        second = build_artifacts(model, 2)
        self.assertNotEqual(first.manifest["golden_trace.jsonl"], second.manifest["golden_trace.jsonl"])

    def test_business_trace_never_injects_network_outcomes(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 42)
        self.assertTrue(artifacts.validation["structural_valid"])
        forbidden = {"arrival_ms", "network_delay_ms", "packet_loss", "measured_rtt_ms"}
        self.assertFalse(any(forbidden.intersection(event) for event in artifacts.trace))

    def test_profile_binds_model_hash_and_claim_scope(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 42)
        self.assertEqual(
            artifacts.profile["claim_scope"],
            "application_end_to_end_to_probe_node",
        )
        self.assertEqual(
            artifacts.profile["business"]["calibration_status"],
            "hypothesis",
        )
        self.assertTrue(artifacts.profile["business"]["behavior_model_hash"].startswith("sha256:"))
        self.assertEqual(artifacts.profile["live_presentation"]["stale_after_ms"], 1500)
        self.assertGreaterEqual(len(artifacts.profile["measurements"]), 20)
        self.assertTrue(
            all("quality_target" in metric for metric in artifacts.profile["measurements"])
        )
        self.assertEqual(
            artifacts.profile["execution_plan"]["artifact_hash"],
            artifacts.manifest["runtime_plan.json"],
        )

    def test_token_runtime_plan_preserves_exact_business_schedule(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 20260716)
        plan = artifacts.runtime_plan
        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual(plan["contract_version"], "aneb-token-runtime-plan-v1")
        self.assertEqual(plan["task_count"], len(plan["tasks"]))
        self.assertGreater(plan["task_count"], 0)
        for task in plan["tasks"]:
            self.assertEqual(
                len(task["token_stream"]["intervals_ms"]),
                len(task["token_stream"]["sizes_bytes"]),
            )
            self.assertEqual(task["token_stream"]["intervals_ms"][0], 0.0)
            self.assertGreater(task["upload"]["payload_bytes"], 0)
            self.assertGreater(task["processing_ms"], 0)
        forbidden = {"arrival_ms", "network_delay_ms", "packet_loss", "measured_rtt_ms"}
        self.assertFalse(forbidden.intersection(json.dumps(plan)))

    def test_realtime_trace_contains_duplex_frames_and_barge_in(self) -> None:
        model = load_model(ROOT / "models/ai_realtime_voice_hypothesis_v0.1.json")
        event_types: set[str] = set()
        saw_barge_in = False
        for seed in range(1, 30):
            artifacts = build_artifacts(model, seed)
            event_types.update(event["event_type"] for event in artifacts.trace)
            saw_barge_in = saw_barge_in or any(
                event["event_type"] == "barge_in_plan" for event in artifacts.trace
            )
        self.assertIn("audio_uplink_frame_plan", event_types)
        self.assertIn("audio_downlink_frame_plan", event_types)
        self.assertTrue(saw_barge_in)

    def test_quick_variant_is_one_short_task_per_workload_and_hash_bound(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 20260716)
        profile, plan = derive_token_runtime_variant(artifacts, "quick")
        self.assertEqual(plan["variant"], "quick")
        self.assertEqual(plan["task_count"], 3)
        self.assertEqual({task["workload_kind"] for task in plan["tasks"]}, {"text", "document", "image"})
        self.assertEqual(profile["profile_id"], "token_multimodal_quick")
        self.assertEqual(profile["evidence_tier"], "quick")
        self.assertEqual(profile["execution_plan"]["variant"], "quick")
        self.assertLess(profile["est_duration_s"], 240)

    def test_standard_variant_has_minimum_task_sample_count(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 20260716)
        profile, plan = derive_token_runtime_variant(artifacts, "standard")
        self.assertGreaterEqual(plan["task_count"], 20)
        task_metric = next(metric for metric in profile["measurements"] if metric["metric_id"] == "TOK-B01")
        self.assertGreaterEqual(plan["task_count"], task_metric["minimum_sample_count"])

    def test_stress_variant_is_isolated_100mib_bidirectional_task(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_stress_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 20260716)
        profile, plan = derive_token_runtime_variant(artifacts, "stress")
        self.assertEqual(profile["profile_id"], "token_multimodal_stress")
        self.assertEqual(profile["evidence_tier"], "stress")
        self.assertEqual(profile["evaluation"]["score_policy_id"], "token-stress-score-v1")
        self.assertEqual(plan["variant"], "stress")
        self.assertEqual(plan["task_count"], 1)
        task = plan["tasks"][0]
        self.assertEqual(task["workload_kind"], "video")
        self.assertEqual(task["upload"]["payload_bytes"], 100 * 1024 * 1024)
        self.assertEqual(task["response_artifact_bytes"], 100 * 1024 * 1024)
        required = {
            metric["metric_id"] for metric in profile["measurements"] if metric["required_for_score"]
        }
        self.assertEqual(
            required,
            {"TOK-B01", "TOK-B02", "TOK-B11", "TOK-N05", "TOK-N06", "TOK-N07", "TOK-N08", "TOK-N09"},
        )

    def test_realtime_standard_has_connection_and_interruption_evidence(self) -> None:
        model = load_model(ROOT / "models/ai_realtime_voice_hypothesis_v0.2.json")
        artifacts = build_artifacts(model, 20260716)
        profile, plan = derive_realtime_runtime_variant(artifacts, "standard")
        self.assertEqual(plan["contract_version"], "aneb-realtime-runtime-plan-v1")
        self.assertEqual(plan["session_count"], 10)
        self.assertTrue(all(session["turn_count"] >= 12 for session in plan["sessions"]))
        self.assertTrue(
            all(sum(turn["interrupted"] for turn in session["turns"]) >= 2 for session in plan["sessions"])
        )
        connection_metric = next(
            metric for metric in profile["measurements"] if metric["metric_id"] == "LIVE-B01"
        )
        self.assertGreaterEqual(plan["session_count"], connection_metric["minimum_sample_count"])
        forbidden = {"arrival_ms", "network_delay_ms", "packet_loss", "measured_rtt_ms"}
        self.assertFalse(forbidden.intersection(json.dumps(plan)))

    def test_realtime_quick_is_short_and_contains_barge_in(self) -> None:
        model = load_model(ROOT / "models/ai_realtime_voice_hypothesis_v0.2.json")
        artifacts = build_artifacts(model, 20260716)
        profile, plan = derive_realtime_runtime_variant(artifacts, "quick")
        self.assertEqual(profile["profile_id"], "ai_realtime_voice_quick")
        self.assertEqual(profile["evidence_tier"], "quick")
        self.assertEqual(plan["session_count"], 1)
        self.assertLessEqual(plan["sessions"][0]["turn_count"], 3)
        self.assertTrue(any(turn["interrupted"] for turn in plan["sessions"][0]["turns"]))
        self.assertLess(profile["est_duration_s"], 90)


if __name__ == "__main__":
    unittest.main()
