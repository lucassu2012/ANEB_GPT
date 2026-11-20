import json
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
TOOL_DIR = REPO_ROOT / "tools" / "business-video-review"
if str(TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(TOOL_DIR))

import correlate_voice_rb as cvr  # noqa: E402


def _sim_rec(
    *,
    rtt: float = 40.0,
    jitter: float = 2.0,
    up_jitter: float = 5.0,
    down_net_jitter: float | None = 4.0,
    mouth_ear_proxy: float = 130.0,
    ttfb_p50: float = 60.0,
    ttfb_p95: float = 95.0,
    turn_switch: float = 110.0,
    barge_stop: float = 45.0,
    m7_gap: float = 30.0,
    near_zero: float = 0.01,
    low_conf: bool = False,
) -> dict:
    return {
        "id": 1,
        "ts_epoch_ms": 1795000000000,
        "caliber": "server-sim(aneb-realtime-session-v1)",
        "low_confidence": low_conf,
        "rtt_ms": rtt,
        "jitter_ms": jitter,
        "up_frame_jitter_ms": up_jitter,
        "down_frame_jitter_ms": None,
        "mouth_ear_budget_ms": None,
        "frames_sent": 150,
        "frames_recv": 750,
        "ttfb_p50_ms": ttfb_p50,
        "ttfb_p95_ms": ttfb_p95,
        "down_net_jitter_ms": down_net_jitter,
        "mouth_ear_proxy_ms": mouth_ear_proxy,
        "turn_switch_p50_ms": turn_switch,
        "barge_stop_max_ms": barge_stop,
        "turns_ok": 8,
        "m7_max_frame_gap_ms": m7_gap,
        "voice_near_zero_arrival_ratio": near_zero,
    }


def _fixture_payload() -> dict:
    return {
        "schema_version": cvr.INPUT_SCHEMA_VERSION,
        "campaign_id": "test-voice-corr-1",
        "conditions": [
            {
                "condition_id": "R",
                "label": "Reference unshaped",
                "severity_rank": 0,
                "shaper": {
                    "mode": "inspect",
                    "synthetic": {"unconfirmed_packets": 0, "conserved": True},
                    "real_app": {"unconfirmed_packets": 0, "conserved": True},
                },
                "voice_sim": _sim_rec(
                    rtt=48.0,
                    jitter=2.0,
                    up_jitter=4.0,
                    down_net_jitter=3.0,
                    mouth_ear_proxy=125.0,
                    ttfb_p50=60.0,
                    ttfb_p95=90.0,
                    turn_switch=110.0,
                    barge_stop=40.0,
                    m7_gap=28.0,
                ),
                "real_voice_app": {
                    "app_package": "com.larus.nova",
                    "app_version_code": 15080040,
                    "status": "COMPLETED",
                    "observable_metrics": {
                        "turn_response_onset_median_ms": 1150.0,
                        "audio_max_gap_ms": 180.0,
                        "turns_completed": 3,
                    },
                },
            },
            {
                "condition_id": "B1",
                "label": "Symmetric 256k + 120ms + 2%",
                "severity_rank": 1,
                "shaper": {
                    "mode": "limit",
                    "synthetic": {"unconfirmed_packets": 0, "conserved": True},
                    "real_app": {"unconfirmed_packets": 0, "conserved": True},
                },
                "voice_sim": _sim_rec(
                    rtt=172.0,
                    jitter=12.0,
                    up_jitter=18.0,
                    down_net_jitter=16.0,
                    mouth_ear_proxy=290.0,
                    ttfb_p50=210.0,
                    ttfb_p95=320.0,
                    turn_switch=270.0,
                    barge_stop=140.0,
                    m7_gap=185.0,
                ),
                "real_voice_app": {
                    "app_package": "com.larus.nova",
                    "app_version_code": 15080040,
                    "status": "COMPLETED",
                    "observable_metrics": {
                        "turn_response_onset_median_ms": 1680.0,
                        "audio_max_gap_ms": 340.0,
                        "turns_completed": 3,
                    },
                },
            },
            {
                "condition_id": "B2",
                "label": "Asymmetric weak UL 128k + 200ms + 5%",
                "severity_rank": 2,
                "shaper": {
                    "mode": "limit",
                    "synthetic": {"unconfirmed_packets": 0, "conserved": True},
                    "real_app": {"unconfirmed_packets": 0, "conserved": True},
                },
                "voice_sim": _sim_rec(
                    rtt=265.0,
                    jitter=32.0,
                    up_jitter=68.0,
                    down_net_jitter=28.0,
                    mouth_ear_proxy=445.0,
                    ttfb_p50=410.0,
                    ttfb_p95=690.0,
                    turn_switch=490.0,
                    barge_stop=260.0,
                    m7_gap=410.0,
                    low_conf=True,
                ),
                "real_voice_app": {
                    "app_package": "com.larus.nova",
                    "app_version_code": 15080040,
                    "status": "COMPLETED",
                    "observable_metrics": {
                        "turn_response_onset_median_ms": 2540.0,
                        "audio_max_gap_ms": 620.0,
                        "turns_completed": 3,
                    },
                },
            },
        ],
    }


class CorrelateVoiceRbTest(unittest.TestCase):

    def test_score_voice_sim_v02_anchors_veto_and_r10_missing(self) -> None:
        self.assertAlmostEqual(sum(cvr.WEIGHTS_VOICE_SIM_V02.values()), 1.0, places=9)
        self.assertAlmostEqual(cvr.anchor_score(cvr.ANCHOR_MAPS["M7"], 400.0), 40.0, places=9)
        self.assertAlmostEqual(cvr.anchor_score(cvr.ANCHOR_MAPS["M7"], 105.0), 77.5, places=9)
        self.assertAlmostEqual(cvr.anchor_score(cvr.ANCHOR_MAPS["M1"], 150.0), 85.0, places=9)
        self.assertAlmostEqual(cvr.anchor_score(cvr.ANCHOR_MAPS["M1"], 300.0), 70.0, places=9)
        self.assertAlmostEqual(cvr.anchor_score(cvr.ANCHOR_MAPS["M1"], 400.0), 55.0, places=9)

        # M1 > 400ms triggers hard veto cap at 54.0 even when other KPIs are good.
        vetoed = cvr.score_voice_sim_v02(_sim_rec(mouth_ear_proxy=410.0))
        self.assertTrue(vetoed["veto_applied"])
        self.assertLessEqual(vetoed["score"], cvr.T4_VETO_CAP)
        self.assertGreater(vetoed["raw_weighted_score"], cvr.T4_VETO_CAP)

        # R-10: None KPI returns score=None and KPI_MISSING:<sorted>
        missing = cvr.score_voice_sim_v02(_sim_rec(down_net_jitter=None))
        self.assertIsNone(missing["score"])
        self.assertEqual(missing["not_computable_reason"], "KPI_MISSING:M2")

    def test_spearman_and_kendall_exact_ties_and_edge_cases(self) -> None:
        self.assertAlmostEqual(cvr.spearman_rho([1.0, 2.0, 3.0], [10.0, 20.0, 30.0]), 1.0, places=9)
        self.assertAlmostEqual(cvr.spearman_rho([1.0, 2.0, 3.0], [30.0, 20.0, 10.0]), -1.0, places=9)
        self.assertAlmostEqual(cvr.kendall_tau_b([1.0, 2.0, 3.0], [10.0, 20.0, 30.0]), 1.0, places=9)
        self.assertAlmostEqual(cvr.kendall_tau_b([1.0, 2.0, 3.0], [30.0, 20.0, 10.0]), -1.0, places=9)

        # Ties
        rho_tie = cvr.spearman_rho([1.0, 2.0, 3.0, 4.0], [10.0, 20.0, 20.0, 30.0])
        tau_tie = cvr.kendall_tau_b([1.0, 2.0, 3.0, 4.0], [10.0, 20.0, 20.0, 30.0])
        self.assertIsNotNone(rho_tie)
        self.assertIsNotNone(tau_tie)
        self.assertGreater(rho_tie, 0.9)
        self.assertGreater(tau_tie, 0.8)

        # Zero variance / too short -> None
        self.assertIsNone(cvr.spearman_rho([1.0, 2.0, 3.0], [5.0, 5.0, 5.0]))
        self.assertIsNone(cvr.kendall_tau_b([1.0, 2.0, 3.0], [5.0, 5.0, 5.0]))
        self.assertIsNone(cvr.spearman_rho([1.0], [2.0]))
        self.assertIsNone(cvr.kendall_tau_b([1.0], [2.0]))

    def test_analyze_correlation_ratios_sensitivity_and_cli(self) -> None:
        payload = _fixture_payload()
        summary = cvr.analyze_correlation(payload)

        self.assertEqual(summary["schema_version"], cvr.OUTPUT_SCHEMA_VERSION)
        self.assertEqual(summary["condition_order"], ["R", "B1", "B2"])
        self.assertIn("N=3", summary["statistical_power_caveat"])

        # Monotonic correlation between severity and AQS (negative) & real onset (positive)
        aqs_corr = summary["correlations"]["voice_sim_vs_severity"]["aqs_sim_v02"]
        self.assertAlmostEqual(aqs_corr["spearman_rho"], -1.0, places=9)
        self.assertAlmostEqual(aqs_corr["kendall_tau_b"], -1.0, places=9)

        onset_vs_m1 = summary["correlations"]["voice_sim_vs_real_voice_app"]["turn_response_onset_median_ms"]["mouth_ear_proxy_ms"]
        self.assertAlmostEqual(onset_vs_m1["spearman_rho"], 1.0, places=9)
        self.assertAlmostEqual(onset_vs_m1["kendall_tau_b"], 1.0, places=9)

        # Directional ratios
        b2_vs_r_ul = summary["directional_ratios"]["vs_R"]["B2_vs_R"]["voice_sim"]["up_frame_jitter_ms"]
        self.assertAlmostEqual(b2_vs_r_ul["ratio"], 68.0 / 4.0, places=6)

        # Sensitivity sweeps preserve weights=1 and R > B1 > B2 ordering
        sens = summary["sensitivity_v02"]
        self.assertEqual(sens["baseline_rank_order_best_to_worst"], ["R", "B1", "B2"])
        for name, v in sens["variants"].items():
            self.assertAlmostEqual(sum(v["weights"].values()), 1.0, places=5, msg=name)
            self.assertTrue(v["rank_preserved_vs_baseline"], msg=name)

        # End-to-end CLI test with read-only input check
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            in_file = tmp_path / "input.json"
            out_dir = tmp_path / "out"
            raw_in = json.dumps(payload, indent=2)
            in_file.write_text(raw_in, encoding="utf-8")
            rc = cvr.main(["--input", str(in_file), "--out-dir", str(out_dir)])
            self.assertEqual(rc, 0)
            self.assertEqual(in_file.read_text(encoding="utf-8"), raw_in)
            out_json = json.loads((out_dir / "voice-correlation-summary.json").read_text(encoding="utf-8"))
            self.assertEqual(out_json["schema_version"], cvr.OUTPUT_SCHEMA_VERSION)
            md_text = (out_dir / "voice-correlation-table.md").read_text(encoding="utf-8")
            self.assertIn("WEIGHTS_VOICE_SIM_V02", md_text)
            self.assertIn("N=3", md_text)

    def test_fail_closed_on_missing_r_or_nan(self) -> None:
        bad = _fixture_payload()
        bad["conditions"] = [c for c in bad["conditions"] if c["condition_id"] != "R"]
        with self.assertRaises(ValueError):
            cvr.analyze_correlation(bad)

        bad_nan = _fixture_payload()
        bad_nan["conditions"][0]["voice_sim"]["rtt_ms"] = float("nan")
        with self.assertRaises(ValueError):
            cvr.analyze_correlation(bad_nan)


if __name__ == "__main__":
    unittest.main()
