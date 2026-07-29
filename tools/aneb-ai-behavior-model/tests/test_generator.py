from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import aneb_behavior_model.generator as generator
from aneb_behavior_model.generator import (
    build_artifacts,
    derive_realtime_runtime_variant,
    derive_token_runtime_variant,
)
from aneb_behavior_model.model import load_model

ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[1]


def _qualification_policy() -> dict:
    return json.loads(
        (
            REPO_ROOT
            / "spec/repeatability-policies/aneb-repeatability-qualification-balanced-v1.json"
        ).read_text(encoding="utf-8")
    )


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
        self.assertEqual(profile["version"], "1.2.1")
        artifact_tasks = [task for task in plan["tasks"] if task["response_artifact_bytes"] > 0]
        self.assertEqual(
            [(task["task_id"], task["workload_kind"], task["response_artifact_bytes"]) for task in artifact_tasks],
            [("task-0006", "text", 1024 * 1024)],
        )
        requirements = profile["execution_requirements"]
        self.assertEqual(requirements["contract_id"], "aneb-execution-requirements")
        self.assertEqual(requirements["contract_version"], "1.0.0")
        self.assertEqual(
            requirements["client_engine"],
            {
                "contract_id": "aneb-token-simulation-engine",
                "min_version": "1.0.0",
                "max_version_exclusive": "2.0.0",
            },
        )
        self.assertEqual(
            requirements["server_capability_receipt"]["contract_id"],
            "aneb-server-capability-receipt",
        )
        self.assertEqual(
            {item["primitive_id"]: item["wire_contract_id"] for item in requirements["required_primitives"]},
            {"echo": "aneb-echo-v1", "token_sim": "aneb-token-task-v1", "download": "aneb-download-v1"},
        )
        self.assertEqual(profile["evidence_tier"], "quick")
        self.assertEqual(profile["execution_plan"]["variant"], "quick")
        self.assertLess(profile["est_duration_s"], 240)
        repeated_profile, repeated_plan = derive_token_runtime_variant(artifacts, "quick")
        self.assertEqual(profile, repeated_profile)
        self.assertEqual(plan, repeated_plan)

    def test_standard_variant_has_minimum_task_sample_count(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 20260716)
        profile, plan = derive_token_runtime_variant(artifacts, "standard")
        self.assertGreaterEqual(plan["task_count"], 20)
        task_metric = next(metric for metric in profile["measurements"] if metric["metric_id"] == "TOK-B01")
        self.assertGreaterEqual(plan["task_count"], task_metric["minimum_sample_count"])

    def test_token_repeatability_qualification_freezes_balanced_ten_task_runtime(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 20260716)

        profile, plan = derive_token_runtime_variant(
            artifacts,
            "repeatability_qualification",
            qualification_policy=_qualification_policy(),
        )

        self.assertEqual("token_multimodal_repeatability_qualification", profile["profile_id"])
        self.assertEqual("1.0.0", profile["version"])
        self.assertEqual("repeatability_qualification", profile["evidence_tier"])
        self.assertEqual("repeatability_qualification", profile["execution_plan"]["variant"])
        self.assertEqual("repeatability_qualification", plan["variant"])
        self.assertEqual(10, plan["task_count"])
        self.assertEqual(
            [
                "task-0001",
                "task-0003",
                "task-0004",
                "task-0006",
                "task-0010",
                "task-0011",
                "task-0012",
                "task-0015",
                "task-0016",
                "task-0017",
            ],
            [task["task_id"] for task in plan["tasks"]],
        )
        self.assertEqual(
            {"text": 4, "document": 3, "image": 3},
            {
                workload_kind: sum(
                    task["workload_kind"] == workload_kind for task in plan["tasks"]
                )
                for workload_kind in ("text", "document", "image")
            },
        )
        quick_profile, _ = derive_token_runtime_variant(artifacts, "quick")
        self.assertEqual(
            quick_profile["execution_requirements"],
            profile["execution_requirements"],
        )
        self.assertEqual(artifacts.profile["evaluation"], profile["evaluation"])
        self.assertEqual(profile["qualification"], plan["qualification"])
        self.assertEqual(
            {
                "contract_version": "aneb-repeatability-profile-binding-v1",
                "policy_id": "aneb-repeatability-qualification-balanced-v1",
                "policy_version": "1.0.0",
                "decision_id": "D-110",
                "policy_sha256": profile["qualification"]["policy_sha256"],
                "stage_order": ["Q1_WIFI", "Q2_CELLULAR"],
                "transport_pooling": "forbidden",
                "q2_requires_q1_pass": True,
                "runs_per_family": 10,
                "repeatability_and_quality_gates_independent": True,
                "formal_baseline_eligible": False,
                "single_run_confidence_unchanged": True,
            },
            profile["qualification"],
        )
        self.assertRegex(profile["qualification"]["policy_sha256"], r"^[0-9a-f]{64}$")
        self.assertLess(profile["est_duration_s"], 600)

    def test_repeatability_qualification_rejects_relaxed_transport_pooling_policy(self) -> None:
        model = load_model(ROOT / "models/token_multimodal_hypothesis_v0.1.json")
        artifacts = build_artifacts(model, 20260716)
        relaxed = _qualification_policy()
        relaxed["stages"]["transport_pooling"] = "allowed"

        with self.assertRaisesRegex(ValueError, "approved D-110 contract"):
            derive_token_runtime_variant(
                artifacts,
                "repeatability_qualification",
                qualification_policy=relaxed,
            )

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
        self.assertEqual(profile["version"], "1.1.1")
        self.assertEqual(profile["evidence_tier"], "quick")
        self.assertEqual(plan["session_count"], 1)
        self.assertLessEqual(plan["sessions"][0]["turn_count"], 3)
        self.assertTrue(any(turn["interrupted"] for turn in plan["sessions"][0]["turns"]))
        self.assertLess(profile["est_duration_s"], 90)
        self.assertEqual(
            profile["execution_requirements"],
            {
                "contract_id": "aneb-execution-requirements",
                "contract_version": "1.0.0",
                "client_engine": {
                    "contract_id": "aneb-realtime-simulation-engine",
                    "min_version": "1.0.0",
                    "max_version_exclusive": "2.0.0",
                },
                "server_capability_receipt": {
                    "contract_id": "aneb-server-capability-receipt",
                    "min_version": "1.0.0",
                    "max_version_exclusive": "2.0.0",
                },
                "required_primitives": [
                    {
                        "primitive_id": "realtime_sim",
                        "wire_contract_id": "aneb-realtime-session-v1",
                    }
                ],
            },
        )
        turns = plan["sessions"][0]["turns"]
        self.assertEqual(sum(turn["uplink_frames"] for turn in turns), 400)
        self.assertEqual(sum(turn["downlink_frames_before_stop"] for turn in turns), 676)

    def test_realtime_repeatability_qualification_freezes_shortest_full_session(self) -> None:
        model = load_model(ROOT / "models/ai_realtime_voice_hypothesis_v0.2.json")
        artifacts = build_artifacts(model, 20260716)

        profile, plan = derive_realtime_runtime_variant(
            artifacts,
            "repeatability_qualification",
            qualification_policy=_qualification_policy(),
        )

        self.assertEqual("ai_realtime_voice_repeatability_qualification", profile["profile_id"])
        self.assertEqual("1.0.0", profile["version"])
        self.assertEqual("repeatability_qualification", profile["evidence_tier"])
        self.assertEqual("repeatability_qualification", profile["execution_plan"]["variant"])
        self.assertEqual("repeatability_qualification", plan["variant"])
        self.assertEqual(1, plan["session_count"])
        self.assertEqual("session-0005", plan["sessions"][0]["session_id"])
        self.assertEqual(12, plan["sessions"][0]["turn_count"])
        self.assertGreaterEqual(
            sum(turn["interrupted"] for turn in plan["sessions"][0]["turns"]),
            2,
        )
        quick_profile, _ = derive_realtime_runtime_variant(artifacts, "quick")
        self.assertEqual(
            quick_profile["execution_requirements"],
            profile["execution_requirements"],
        )
        self.assertEqual(artifacts.profile["evaluation"], profile["evaluation"])
        self.assertEqual(profile["qualification"], plan["qualification"])
        self.assertEqual(
            {
                "contract_version": "aneb-repeatability-profile-binding-v1",
                "policy_id": "aneb-repeatability-qualification-balanced-v1",
                "policy_version": "1.0.0",
                "decision_id": "D-110",
                "policy_sha256": profile["qualification"]["policy_sha256"],
                "stage_order": ["Q1_WIFI", "Q2_CELLULAR"],
                "transport_pooling": "forbidden",
                "q2_requires_q1_pass": True,
                "runs_per_family": 10,
                "repeatability_and_quality_gates_independent": True,
                "formal_baseline_eligible": False,
                "single_run_confidence_unchanged": True,
            },
            profile["qualification"],
        )
        self.assertLess(profile["est_duration_s"], 90)

    def test_realtime_recovery_isolated_faults_are_hash_bound_test_actions(self) -> None:
        model = load_model(ROOT / "models/ai_realtime_voice_hypothesis_v0.2.json")
        artifacts = build_artifacts(model, 20260716)
        profile, plan = derive_realtime_runtime_variant(artifacts, "recovery")

        self.assertEqual(profile["profile_id"], "ai_realtime_voice_recovery")
        self.assertEqual(profile["evidence_tier"], "recovery")
        self.assertEqual(profile["claim_scope"], "controlled_server_disconnect_recovery_to_probe_node")
        self.assertEqual(profile["evaluation"]["score_policy_id"], "realtime-recovery-score-v2")
        self.assertEqual(plan["variant"], "recovery")
        self.assertEqual(
            plan["recovery_probe_contract"],
            "fixed_model_derived_minimum_speech_plus_wait_v1",
        )
        self.assertEqual(plan["session_count"], 4)
        self.assertEqual(
            [session.get("controlled_disconnect_after_turn") for session in plan["sessions"]],
            [0, None, 0, None],
        )
        self.assertLess(profile["est_duration_s"], 120)
        expected_frames = sum(
            turn["downlink_frames_before_stop"] if turn["interrupted"] else turn["planned_downlink_frames"]
            for session in plan["sessions"]
            for turn in session["turns"]
        )
        self.assertGreaterEqual(expected_frames, 500)
        recovered_frames = sum(
            turn["downlink_frames_before_stop"] if turn["interrupted"] else turn["planned_downlink_frames"]
            for session in plan["sessions"]
            if session.get("controlled_disconnect_after_turn") is None
            for turn in session["turns"]
        )
        self.assertGreaterEqual(recovered_frames, 400)
        recovery_stimuli = [
            (session["turns"][0]["speech_ms"], session["turns"][0]["response_wait_ms"])
            for session in plan["sessions"]
            if session.get("controlled_disconnect_after_turn") is None
        ]
        self.assertEqual(recovery_stimuli, [(1200.0, 350.0), (1200.0, 350.0)])
        required = {
            metric["metric_id"] for metric in profile["measurements"] if metric["required_for_score"]
        }
        self.assertEqual(required, {"LIVE-B05", "LIVE-B09", "LIVE-B11", "LIVE-N02"})
        minimums = {
            metric["metric_id"]: metric["minimum_sample_count"]
            for metric in profile["measurements"]
            if metric["required_for_score"]
        }
        self.assertEqual(minimums, {"LIVE-B05": 400, "LIVE-B09": 6, "LIVE-B11": 2, "LIVE-N02": 10})
        serialized = json.dumps(plan)
        forbidden = {"arrival_ms", "network_delay_ms", "packet_loss", "measured_rtt_ms"}
        self.assertFalse(forbidden.intersection(serialized))

    def test_network_repeatability_qualification_hash_binds_standard_phases(self) -> None:
        source = json.loads(
            (
                REPO_ROOT
                / "profiles/published/network_comprehensive_standard/profile.json"
            ).read_text(encoding="utf-8")
        )

        profile, plan = generator.derive_network_runtime_variant(
            source,
            "repeatability_qualification",
            seed=20260727,
            qualification_policy=_qualification_policy(),
        )

        self.assertEqual("network_comprehensive_repeatability_qualification", profile["profile_id"])
        self.assertEqual("1.0.0", profile["version"])
        self.assertEqual("repeatability_qualification", profile["evidence_tier"])
        self.assertEqual("repeatability_qualification", profile["execution_plan"]["variant"])
        self.assertEqual("aneb-network-runtime-plan-v1", plan["contract_version"])
        self.assertEqual("repeatability_qualification", plan["variant"])
        self.assertEqual(20260727, plan["seed"])
        self.assertEqual(source["phases"], plan["phases"])
        self.assertEqual(source["phases"], profile["phases"])
        self.assertEqual(source["evaluation"], profile["evaluation"])
        self.assertEqual(profile["qualification"], plan["qualification"])
        self.assertEqual(
            {
                "contract_version": "aneb-repeatability-profile-binding-v1",
                "policy_id": "aneb-repeatability-qualification-balanced-v1",
                "policy_version": "1.0.0",
                "decision_id": "D-110",
                "policy_sha256": profile["qualification"]["policy_sha256"],
                "stage_order": ["Q1_WIFI", "Q2_CELLULAR"],
                "transport_pooling": "forbidden",
                "q2_requires_q1_pass": True,
                "runs_per_family": 10,
                "repeatability_and_quality_gates_independent": True,
                "formal_baseline_eligible": False,
                "single_run_confidence_unchanged": True,
            },
            profile["qualification"],
        )
        self.assertEqual(
            {
                "contract_id": "aneb-execution-requirements",
                "contract_version": "1.0.0",
                "client_engine": {
                    "contract_id": "aneb-network-comprehensive-engine",
                    "min_version": "1.0.0",
                    "max_version_exclusive": "2.0.0",
                },
                "server_capability_receipt": {
                    "contract_id": "aneb-server-capability-receipt",
                    "min_version": "1.0.0",
                    "max_version_exclusive": "2.0.0",
                },
                "required_primitives": [
                    {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"},
                    {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
                    {"primitive_id": "udp_echo", "wire_contract_id": "aneb-udp-echo-v2"},
                    {"primitive_id": "upload", "wire_contract_id": "aneb-upload-v1"},
                ],
            },
            profile["execution_requirements"],
        )
        expected_plan_hash = "sha256:" + hashlib.sha256(
            json.dumps(plan, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        self.assertEqual(expected_plan_hash, profile["execution_plan"]["artifact_hash"])
        self.assertEqual(42, profile["est_duration_s"])


if __name__ == "__main__":
    unittest.main()
