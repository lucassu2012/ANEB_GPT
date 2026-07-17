from __future__ import annotations

import copy
import unittest

from scripts.analyze_ttft_repeatability import RepeatabilityError, analyze


def result(run_index: int, ttft_values: tuple[float, float, float]) -> dict:
    tasks = []
    for task_index, (kind, ttft) in enumerate(zip(("text", "document", "image"), ttft_values)):
        tasks.append(
            {
                "task_id": f"quick-{kind}-0",
                "workload_kind": kind,
                "upload_bytes": (task_index + 1) * 8192,
                "response_artifact_bytes": 0,
                "expected_tokens": 10,
                "server_processing_ms": 300.0 + task_index * 100.0,
                "ttft_ms": ttft,
            }
        )
    p95 = sorted(ttft_values)[1] + (sorted(ttft_values)[2] - sorted(ttft_values)[1]) * 0.9
    return {
        "schema_version": "aneb-result-v1",
        "test_type": "token_simulation",
        "claim": {"scope": "application_end_to_end_to_probe_node"},
        "run": {
            "run_id": f"run-{run_index}",
            "started_at_epoch_ms": 1_000_000 + run_index * 60_000,
            "status": "completed",
            "validity": "valid",
        },
        "producer": {"component_version": "0.5.6-codex"},
        "profile": {
            "profile_id": "token_multimodal_quick",
            "profile_version": "1.0.0",
            "variant": "quick",
            "profile_fingerprint": {"value": "sha256:profile"},
            "runtime_artifact_hash": {"value": "sha256:runtime"},
        },
        "context": {
            "device": {
                "manufacturer": "HUAWEI",
                "model": "ELS-AN00",
                "os_release": "12",
                "api_level": 31,
                "app_package": "com.aneb.probe.codex",
                "app_version_name": "0.5.6-codex",
                "app_version_code": 38,
            },
            "endpoint": {"server_base": "https://example.test"},
            "network": {
                "requested_transport": "auto",
                "active_transport": "wifi",
                "vpn_active": False,
            },
        },
        "evaluation": {
            "score": {"verdict": "inconclusive"},
            "metrics": {
                "TOK-B04": {
                    "state": "observed",
                    "value": p95,
                    "sample_count": 3,
                }
            },
        },
        "category_payload": {"raw_evidence": {"tasks": tasks}},
    }


class TtftRepeatabilityTest(unittest.TestCase):
    def test_task_aligned_stable_cohort_passes(self) -> None:
        documents = [result(i, (340.0 + i, 460.0 + i, 580.0 + i)) for i in range(5)]
        report = analyze(documents)
        self.assertEqual("pass", report["status"])
        self.assertEqual(15, report["total_ttft_samples"])
        self.assertLess(report["median_task_ttft_cv"], 0.01)

    def test_high_variation_fails_without_becoming_invalid(self) -> None:
        values = (300.0, 600.0, 900.0, 1200.0, 1500.0)
        documents = [result(i, (values[i], values[i] * 1.2, values[i] * 1.4)) for i in range(5)]
        report = analyze(documents)
        self.assertEqual("fail", report["status"])
        self.assertGreater(report["median_task_ttft_cv"], 0.10)

    def test_mixed_transport_is_rejected(self) -> None:
        documents = [result(i, (340.0, 460.0, 580.0)) for i in range(5)]
        documents[-1]["context"]["network"]["active_transport"] = "cellular"
        with self.assertRaisesRegex(RepeatabilityError, "heterogeneous_cohort"):
            analyze(documents)

    def test_raw_and_frozen_b04_mismatch_is_rejected(self) -> None:
        documents = [result(i, (340.0, 460.0, 580.0)) for i in range(5)]
        damaged = copy.deepcopy(documents)
        damaged[2]["evaluation"]["metrics"]["TOK-B04"]["value"] += 1.0
        with self.assertRaisesRegex(RepeatabilityError, "ttft_metric_raw_mismatch"):
            analyze(damaged)

    def test_missing_task_identity_is_rejected(self) -> None:
        documents = [result(i, (340.0, 460.0, 580.0)) for i in range(5)]
        documents[0]["category_payload"]["raw_evidence"]["tasks"][0]["task_id"] = None
        with self.assertRaisesRegex(RepeatabilityError, "missing_task_id"):
            analyze(documents)


if __name__ == "__main__":
    unittest.main()
