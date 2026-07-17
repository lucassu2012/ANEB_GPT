from __future__ import annotations

import unittest
from pathlib import Path

from aneb_behavior_model.fitting import fit_token_interval_markov, fit_token_model
from aneb_behavior_model.model import load_model, validate_model

ROOT = Path(__file__).resolve().parents[1]


class FittingTest(unittest.TestCase):
    def test_markov_rows_are_complete_and_normalized(self) -> None:
        model = fit_token_interval_markov(
            [[10, 12, 40, 55, 350, 25], [18, 22, 80, 600, 15]]
        )
        self.assertEqual(set(model["states"]), {"FAST", "NORMAL", "PAUSE"})
        self.assertAlmostEqual(sum(model["start_probabilities"].values()), 1.0)
        for row in model["transition_probabilities"].values():
            self.assertAlmostEqual(sum(row.values()), 1.0)

    def test_fit_produces_calibrated_valid_model_without_content(self) -> None:
        template = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        observations = [
            {
                "workload_kind": "text",
                "payload_bytes": 4096 + index * 100,
                "processing_delay_ms": 400 + index * 10,
                "output_token_count": 200 + index,
                "token_intervals_ms": [10, 12, 35, 45, 300, 30],
                "raw_prompt": "must be ignored",
            }
            for index in range(6)
        ]
        fitted = fit_token_model(
            observations,
            template,
            candidate_version="0.1.1",
            calibration_source={
                "kind": "authorized_observation_dataset",
                "dataset_id": "authorized-token-sessions",
                "dataset_version": "1.0.0",
                "dataset_manifest_sha256": "sha256:" + "0" * 64,
                "authorization_basis": "first_party_measurement",
                "training_partition": {
                    "canonical_sha256": "sha256:" + "1" * 64,
                    "observation_count": len(observations),
                },
                "content_retained": False,
            },
        )
        validate_model(fitted)
        self.assertEqual(fitted["status"], "calibrated")
        self.assertFalse(fitted["source"]["content_retained"])
        self.assertNotIn("raw_prompt", str(fitted))


if __name__ == "__main__":
    unittest.main()
