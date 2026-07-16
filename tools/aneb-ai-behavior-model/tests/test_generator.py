from __future__ import annotations

import json
import unittest
from pathlib import Path

from aneb_behavior_model.generator import build_artifacts
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
        self.assertGreaterEqual(len(artifacts.profile["measurements"]), 20)
        self.assertTrue(
            all("quality_target" in metric for metric in artifacts.profile["measurements"])
        )

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


if __name__ == "__main__":
    unittest.main()
