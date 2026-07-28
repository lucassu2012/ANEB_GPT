from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

import scripts.analyze_repeatability_cohort as repeatability
from scripts.analyze_repeatability_cohort import CohortError, analyze
from scripts.tests.test_verify_realtime_quick_client_db import valid_body as realtime_valid_body
from scripts.tests.test_verify_token_quick_client_db import valid_body as token_valid_body


ROOT = Path(__file__).resolve().parents[2]
NETWORK_FIXTURE = (
    ROOT
    / "spec/examples/aneb-result-v1/network_comprehensive.not-computable.valid-schema.json"
)


def _run_id(index: int) -> str:
    return f"00000000-0000-7000-8000-{index:012d}"


def _stamp(document: dict[str, object], index: int) -> dict[str, object]:
    body = copy.deepcopy(document)
    started = 1784246400000 + index * 60_000
    body["run"].update(
        run_id=_run_id(index),
        started_at_epoch_ms=started,
        ended_at_epoch_ms=started + 5_000,
        duration_ms=5_000,
    )
    body["producer"]["serialized_at_epoch_ms"] = started + 5_000
    body["evaluation"]["algorithm_versions"]["finalized_at_epoch_ms"] = started + 5_000
    body["context"]["network"].update(
        availability="observed",
        requested_transport="wifi",
        active_transport="wifi",
        capabilities=["INTERNET", "VALIDATED"],
        interface_name="wlan0",
        validated=True,
        not_suspended=True,
        metered=False,
        vpn_active=False,
        private_dns_mode="off",
        bound_network_generation=1,
        evidence_ref_ids=[],
    )
    radio_start = 10_000_000_000 + index * 10_000_000_000
    radio_samples = []
    for offset in (0, 1_000_000_000, 2_000_000_000):
        radio_samples.append(
            {
                "elapsed_realtime_nanos": radio_start + offset,
                "cell_elapsed_realtime_nanos": radio_start + offset - 50_000_000,
                "stale": False,
                "sub_id": 1,
                "sub_switched": False,
                "network_type": "LTE",
                "override_type": "NR_NSA",
                "nr_state": "nsa",
                "rat": "LTE",
                "pci": 101,
                "tac": 202,
                "arfcn": 1650,
                "rsrp_dbm": -91.0,
                "rsrq_db": -11.0,
                "sinr_db": 18.0,
                "operator_name": "test-operator",
            }
        )
    body["context"]["radio"].update(
        collection_status="collected",
        unavailable_reason=None,
        operator_name="test-operator",
        network_type="LTE",
        override_type="NR_NSA",
        nr_state="nsa",
        rat="LTE",
        rsrp_dbm=-91.0,
        rsrq_db=-11.0,
        sinr_db=18.0,
        sample_count=len(radio_samples),
        samples=radio_samples,
        evidence_ref_ids=["radio-context"],
    )
    return body


def _token_run(index: int, ttft_ms: float) -> dict[str, object]:
    body = _stamp(token_valid_body(), index)
    tasks = body["category_payload"]["raw_evidence"]["tasks"]
    for task in tasks:
        task["ttft_ms"] = ttft_ms
        task["ttft_excess_ms"] = max(0.0, ttft_ms - 500.0)
    metric = body["evaluation"]["metrics"]["TOK-B04"]
    metric.update(state="observed", value=ttft_ms, sample_count=len(tasks), invalid_reason=None)
    return body


def _token_qualification_run(index: int, ttft_ms: float) -> dict[str, object]:
    body = _token_run(index, ttft_ms)
    body["profile"].update(
        profile_id="token_multimodal_repeatability_qualification",
        profile_version="1.0.0",
        variant="repeatability_qualification",
        source_uri=(
            "asset:///published/"
            "token_multimodal_repeatability_qualification/profile.json"
        ),
    )
    for metric in body["evaluation"]["metrics"].values():
        if metric["required_for_score"] is not True:
            continue
        minimum = metric["minimum_sample_count"]
        metric.update(
            state="observed",
            value=metric["value"] if metric["value"] is not None else 0.0,
            compliance_ratio=1.0,
            sample_count=max(metric["sample_count"], minimum),
            score=100.0,
            invalid_reason=None,
        )
    if index > 5:
        gap_ms = 40 * 60_000
        body["run"]["started_at_epoch_ms"] += gap_ms
        body["run"]["ended_at_epoch_ms"] += gap_ms
        body["producer"]["serialized_at_epoch_ms"] += gap_ms
        body["evaluation"]["algorithm_versions"]["finalized_at_epoch_ms"] += gap_ms
    return body


def _realtime_run(index: int, values: tuple[float, float, float]) -> dict[str, object]:
    body = _stamp(realtime_valid_body(), index)
    for metric_id, value in zip(("LIVE-B05", "LIVE-N02", "LIVE-B08"), values, strict=True):
        body["evaluation"]["metrics"][metric_id].update(
            state="observed",
            value=value,
            sample_count=20,
            invalid_reason=None,
        )
    return body


def _realtime_qualification_run(
    index: int, values: tuple[float, float, float]
) -> dict[str, object]:
    body = _realtime_run(index, values)
    body["profile"].update(
        profile_id="ai_realtime_voice_repeatability_qualification",
        profile_version="1.0.0",
        variant="repeatability_qualification",
        source_uri=(
            "asset:///published/"
            "ai_realtime_voice_repeatability_qualification/profile.json"
        ),
    )
    for metric in body["evaluation"]["metrics"].values():
        if metric["required_for_score"] is not True:
            continue
        minimum = metric["minimum_sample_count"]
        metric.update(
            state="observed",
            value=metric["value"] if metric["value"] is not None else 0.0,
            compliance_ratio=1.0,
            sample_count=max(metric["sample_count"], minimum),
            score=100.0,
            invalid_reason=None,
        )
    return body


def _network_run(index: int, values: tuple[float, float, float]) -> dict[str, object]:
    fixture = json.loads(NETWORK_FIXTURE.read_text(encoding="utf-8"))
    fixture["schema_version"] = "aneb-result-v2"
    body = _stamp(fixture, index)
    body["producer"].update(
        component="aneb-probe-android",
        component_version="0.5.14-codex",
        exporter_version="aneb-result-exporter-v2",
        build_type="debug",
    )
    body["profile"].update(
        profile_id="network_comprehensive_quick",
        profile_version="1.2.0",
        variant="quick",
        source_uri="asset:///published/network_comprehensive_quick/profile.json",
    )
    body["claim"].update(
        scope="application_end_to_end_to_probe_node",
        measurement_subject="ANEB application-layer path to the selected probe node",
    )
    body["context"]["device"].update(
        availability="observed",
        manufacturer="HUAWEI",
        model="P40 Pro",
        os_name="Android",
        os_release="12",
        api_level=31,
        app_package="com.aneb.probe.codex",
        app_version_name="0.5.14-codex",
        app_version_code=46,
    )
    body["evaluation"]["algorithm_versions"].update(
        measurement_engine_version="network-comprehensive-engine-v1",
        metric_catalog_id="network-comprehensive-measurements-v1",
        target_set_id="network-comprehensive-targets-v1",
        score_policy_id="network-comprehensive-score-v1",
        conclusion_policy_id="network-comprehensive-conclusions-v1",
    )
    template = copy.deepcopy(body["evaluation"]["metrics"]["NET-B03"])
    metrics: dict[str, object] = copy.deepcopy(body["evaluation"]["metrics"])
    for metric_id, label, unit, value in zip(
        ("NET-B01", "NET-B02", "NET-B04"),
        ("download sustained goodput", "upload sustained goodput", "loaded RTT"),
        ("Mbps", "Mbps", "ms"),
        values,
        strict=True,
    ):
        metric = copy.deepcopy(template)
        metric.update(
            label=label,
            unit=unit,
            state="observed",
            value=value,
            compliance_ratio=1.0,
            sample_count=20,
            minimum_sample_count=10,
            score=100.0,
            invalid_reason=None,
        )
        metrics[metric_id] = metric
    body["evaluation"]["metrics"] = metrics
    return body


def _network_qualification_run(
    index: int, values: tuple[float, float, float]
) -> dict[str, object]:
    body = _network_run(index, values)
    body["profile"].update(
        profile_id="network_comprehensive_repeatability_qualification",
        profile_version="1.0.0",
        variant="repeatability_qualification",
        source_uri=(
            "asset:///published/"
            "network_comprehensive_repeatability_qualification/profile.json"
        ),
    )
    for metric in body["evaluation"]["metrics"].values():
        if metric["required_for_score"] is not True:
            continue
        minimum = metric["minimum_sample_count"]
        metric.update(
            state="observed",
            value=metric["value"] if metric["value"] is not None else 0.0,
            compliance_ratio=1.0,
            sample_count=max(metric["sample_count"], minimum),
            score=100.0,
            invalid_reason=None,
        )
    return body


def _as_cellular(body: dict[str, object]) -> dict[str, object]:
    result = copy.deepcopy(body)
    result["context"]["network"].update(
        requested_transport="cellular",
        active_transport="cellular",
        interface_name="rmnet_data0",
        metered=True,
    )
    return result


class RepeatabilityCohortTests(unittest.TestCase):
    def test_realtime_q1_qualification_applies_d110_without_promoting_baseline(self) -> None:
        documents = [
            _realtime_qualification_run(
                index,
                (
                    0.999 - (index % 3) * 0.001,
                    40.0 + (index % 3),
                    100.0 + (index % 2),
                ),
            )
            for index in range(1, 11)
        ]

        report = repeatability.analyze_qualification(
            documents,
            root=ROOT,
            stage_id="Q1_WIFI",
        )

        self.assertEqual("aneb-repeatability-qualification-v1", report["schema_version"])
        self.assertEqual("repeatability_passed", report["status"])
        self.assertEqual(
            "aneb-repeatability-qualification-balanced-v1",
            report["policy"]["policy_id"],
        )
        self.assertEqual("D-110", report["policy"]["decision_id"])
        self.assertEqual("Q1_WIFI", report["policy"]["stage_id"])
        self.assertEqual("pass", report["repeatability_gate"]["status"])
        self.assertEqual(
            {"LIVE-B05", "LIVE-N02", "LIVE-B08"},
            {
                metric["metric_id"]
                for metric in report["repeatability_gate"]["metric_gates"]
            },
        )
        self.assertTrue(
            all(
                metric["status"] == "pass"
                for metric in report["repeatability_gate"]["metric_gates"]
            )
        )
        self.assertEqual("pass", report["radio_integrity"]["status"])
        self.assertTrue(
            all(
                run["cadence_verdict"] == "pass"
                for run in report["radio_integrity"]["runs"]
            )
        )
        self.assertEqual("pass", report["profile_quality_gate"]["status"])
        self.assertFalse(report["formal_baseline_eligible"])
        self.assertTrue(report["single_run_confidence_unchanged"])

    def test_network_q1_qualification_applies_d110_family_thresholds(self) -> None:
        documents = [
            _network_qualification_run(
                index,
                (
                    100.0 + (index % 2),
                    20.0 + (index % 2) * 0.1,
                    50.0 + (index % 2),
                ),
            )
            for index in range(1, 11)
        ]

        report = repeatability.analyze_qualification(
            documents,
            root=ROOT,
            stage_id="Q1_WIFI",
        )

        self.assertEqual("repeatability_passed", report["status"])
        self.assertEqual(
            {"NET-B01", "NET-B02", "NET-B04"},
            {
                metric["metric_id"]
                for metric in report["repeatability_gate"]["metric_gates"]
            },
        )
        self.assertTrue(
            all(
                metric["status"] == "pass"
                for metric in report["repeatability_gate"]["metric_gates"]
            )
        )
        self.assertEqual("pass", report["radio_integrity"]["status"])
        self.assertFalse(report["formal_baseline_eligible"])

    def test_profile_quality_failure_stays_separate_from_repeatability(self) -> None:
        documents = [
            _realtime_qualification_run(index, (0.999, 40.0, 100.0))
            for index in range(1, 11)
        ]
        documents[0]["evaluation"]["metrics"]["LIVE-B05"][
            "compliance_ratio"
        ] = 0.0

        report = repeatability.analyze_qualification(
            documents,
            root=ROOT,
            stage_id="Q1_WIFI",
        )

        self.assertEqual("repeatability_passed", report["status"])
        self.assertEqual("pass", report["repeatability_gate"]["status"])
        self.assertEqual("pass", report["radio_integrity"]["status"])
        self.assertEqual("fail", report["profile_quality_gate"]["status"])
        self.assertEqual(
            "quality_target_not_met",
            report["profile_quality_gate"]["failures"][0]["reason"],
        )
        self.assertFalse(report["formal_baseline_eligible"])

    def test_authorized_radio_cadence_failure_blocks_repeatability_status(self) -> None:
        documents = [
            _realtime_qualification_run(index, (0.999, 40.0, 100.0))
            for index in range(1, 11)
        ]
        samples = documents[0]["context"]["radio"]["samples"]
        samples[2]["elapsed_realtime_nanos"] += 1_000_000_000
        samples[2]["cell_elapsed_realtime_nanos"] += 1_000_000_000

        report = repeatability.analyze_qualification(
            documents,
            root=ROOT,
            stage_id="Q1_WIFI",
        )

        self.assertEqual("repeatability_failed", report["status"])
        self.assertEqual("pass", report["repeatability_gate"]["status"])
        self.assertEqual("fail", report["radio_integrity"]["status"])
        self.assertEqual(
            "fail",
            report["radio_integrity"]["runs"][0]["cadence_verdict"],
        )
        self.assertFalse(report["formal_baseline_eligible"])

    def test_token_q1_qualification_requires_both_d58_batches_and_pooled_gate(self) -> None:
        documents = [
            _token_qualification_run(index, 500.0 + (index % 2))
            for index in range(1, 11)
        ]

        report = repeatability.analyze_qualification(
            documents,
            root=ROOT,
            stage_id="Q1_WIFI",
        )

        gate = report["repeatability_gate"]
        self.assertEqual("repeatability_passed", report["status"])
        self.assertEqual("two_subcohorts_and_pooled", gate["mode"])
        self.assertEqual("pass", gate["status"])
        self.assertEqual(
            [("A", "pass", 5), ("B", "pass", 5)],
            [
                (item["batch_id"], item["status"], item["run_count"])
                for item in gate["subcohorts"]
            ],
        )
        self.assertTrue(
            all(item["authority"] == "D-58" for item in gate["subcohorts"])
        )
        self.assertEqual("pass", gate["pooled"]["status"])
        self.assertEqual("D-58", gate["pooled"]["authority"])
        self.assertEqual(
            90.0,
            gate["pooled"]["evaluation"]["criterion"]["maximum_span_minutes"],
        )
        self.assertFalse(report["formal_baseline_eligible"])

    def test_q2_cellular_requires_and_binds_a_passed_q1_report(self) -> None:
        q1_documents = [
            _realtime_qualification_run(index, (0.999, 40.0, 100.0))
            for index in range(1, 11)
        ]
        q1_report = repeatability.analyze_qualification(
            q1_documents,
            root=ROOT,
            stage_id="Q1_WIFI",
        )
        q2_documents = [
            _as_cellular(
                _realtime_qualification_run(index, (0.998, 41.0, 101.0))
            )
            for index in range(11, 21)
        ]

        q2_report = repeatability.analyze_qualification(
            q2_documents,
            root=ROOT,
            stage_id="Q2_CELLULAR",
            prerequisite_report=q1_report,
        )

        self.assertEqual("repeatability_passed", q2_report["status"])
        self.assertEqual("Q2_CELLULAR", q2_report["policy"]["stage_id"])
        self.assertEqual("pass", q2_report["prerequisite_gate"]["status"])
        self.assertEqual("Q1_WIFI", q2_report["prerequisite_gate"]["stage_id"])
        self.assertEqual(
            repeatability._canonical_digest(q1_report),
            q2_report["prerequisite_gate"]["report_sha256"],
        )
        self.assertFalse(q2_report["formal_baseline_eligible"])

    def test_q2_rejects_malformed_prerequisite_as_a_cohort_error(self) -> None:
        q1_report = repeatability.analyze_qualification(
            [
                _realtime_qualification_run(index, (0.999, 40.0, 100.0))
                for index in range(1, 11)
            ],
            root=ROOT,
            stage_id="Q1_WIFI",
        )
        q1_report["repeatability_gate"] = []
        q2_documents = [
            _as_cellular(
                _realtime_qualification_run(index, (0.998, 41.0, 101.0))
            )
            for index in range(11, 21)
        ]

        with self.assertRaisesRegex(
            CohortError,
            "q2_prerequisite_report_invalid",
        ):
            repeatability.analyze_qualification(
                q2_documents,
                root=ROOT,
                stage_id="Q2_CELLULAR",
                prerequisite_report=q1_report,
            )

    def test_q2_rejects_malformed_prerequisite_network_as_a_cohort_error(self) -> None:
        q1_report = repeatability.analyze_qualification(
            [
                _realtime_qualification_run(index, (0.999, 40.0, 100.0))
                for index in range(1, 11)
            ],
            root=ROOT,
            stage_id="Q1_WIFI",
        )
        q1_report["cohort"]["identity"]["network"] = []
        q2_documents = [
            _as_cellular(
                _realtime_qualification_run(index, (0.998, 41.0, 101.0))
            )
            for index in range(11, 21)
        ]

        with self.assertRaisesRegex(
            CohortError,
            "q2_prerequisite_report_invalid",
        ):
            repeatability.analyze_qualification(
                q2_documents,
                root=ROOT,
                stage_id="Q2_CELLULAR",
                prerequisite_report=q1_report,
            )

    def test_q2_rejects_incomplete_prerequisite_identity_as_a_cohort_error(self) -> None:
        q1_report = repeatability.analyze_qualification(
            [
                _realtime_qualification_run(index, (0.999, 40.0, 100.0))
                for index in range(1, 11)
            ],
            root=ROOT,
            stage_id="Q1_WIFI",
        )
        del q1_report["cohort"]["identity"]["device"]
        q2_documents = [
            _as_cellular(
                _realtime_qualification_run(index, (0.998, 41.0, 101.0))
            )
            for index in range(11, 21)
        ]

        with self.assertRaisesRegex(
            CohortError,
            "q2_prerequisite_report_invalid",
        ):
            repeatability.analyze_qualification(
                q2_documents,
                root=ROOT,
                stage_id="Q2_CELLULAR",
                prerequisite_report=q1_report,
            )

    def test_q2_rejects_prerequisite_that_violates_the_published_schema(self) -> None:
        q1_report = repeatability.analyze_qualification(
            [
                _realtime_qualification_run(index, (0.999, 40.0, 100.0))
                for index in range(1, 11)
            ],
            root=ROOT,
            stage_id="Q1_WIFI",
        )
        q1_report["unverified_authority"] = True
        q2_documents = [
            _as_cellular(
                _realtime_qualification_run(index, (0.998, 41.0, 101.0))
            )
            for index in range(11, 21)
        ]

        with self.assertRaisesRegex(
            CohortError,
            "q2_prerequisite_report_invalid",
        ):
            repeatability.analyze_qualification(
                q2_documents,
                root=ROOT,
                stage_id="Q2_CELLULAR",
                prerequisite_report=q1_report,
            )

    def test_qualification_report_conforms_to_published_strict_schema(self) -> None:
        schema_path = (
            ROOT
            / "spec/schemas/aneb-repeatability-qualification-v1.schema.json"
        )
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema)
        report = repeatability.analyze_qualification(
            [
                _realtime_qualification_run(index, (0.999, 40.0, 100.0))
                for index in range(1, 11)
            ],
            root=ROOT,
            stage_id="Q1_WIFI",
        )

        self.assertEqual([], list(validator.iter_errors(report)))
        tampered = copy.deepcopy(report)
        tampered["unverified_authority"] = True
        self.assertNotEqual([], list(validator.iter_errors(tampered)))
        nested_tampered = copy.deepcopy(report)
        nested_tampered["radio_integrity"]["thresholds"][
            "unverified_gap_seconds"
        ] = 99
        self.assertNotEqual([], list(validator.iter_errors(nested_tampered)))

    def test_token_qualification_schema_rejects_unverified_gate_fields(self) -> None:
        schema = json.loads(
            (
                ROOT
                / "spec/schemas/aneb-repeatability-qualification-v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        validator = Draft202012Validator(schema)
        report = repeatability.analyze_qualification(
            [
                _token_qualification_run(index, 500.0 + (index % 2))
                for index in range(1, 11)
            ],
            root=ROOT,
            stage_id="Q1_WIFI",
        )
        self.assertEqual([], list(validator.iter_errors(report)))

        tampered = copy.deepcopy(report)
        tampered["repeatability_gate"]["subcohorts"][0][
            "unverified_authority"
        ] = True
        self.assertNotEqual([], list(validator.iter_errors(tampered)))

    def test_reports_inline_one_hz_radio_integrity_without_inventing_a_quality_threshold(self) -> None:
        report = analyze(
            [_realtime_run(index, (0.98, 42.0 + index, 0.01)) for index in range(1, 3)],
            root=ROOT,
        )

        self.assertEqual("diagnostic_only", report["radio_integrity"]["policy_mode"])
        self.assertEqual(1.0, report["radio_integrity"]["nominal_frequency_hz"])
        self.assertFalse(report["radio_integrity"]["formal_baseline_eligible"])
        run = report["radio_integrity"]["runs"][0]
        self.assertEqual("structurally_valid", run["status"])
        self.assertEqual(3, run["sample_count"])
        self.assertEqual(1.0, run["median_gap_seconds"])
        self.assertEqual(1.0, run["observed_median_frequency_hz"])
        self.assertEqual(1.0, run["p95_gap_seconds"])
        self.assertEqual(0, run["stale_sample_count"])
        self.assertIsNone(run["cadence_verdict"])

    def test_rejects_radio_reference_without_inline_samples_for_integrity_audit(self) -> None:
        documents = [_realtime_run(index, (0.98, 42.0, 0.01)) for index in range(1, 3)]
        documents[1]["context"]["radio"]["samples"] = []

        with self.assertRaisesRegex(CohortError, "radio_inline_series_required"):
            analyze(documents, root=ROOT)

    def test_rejects_non_monotonic_radio_timestamps(self) -> None:
        documents = [_network_run(index, (45.0, 18.0, 80.0)) for index in range(1, 3)]
        samples = documents[0]["context"]["radio"]["samples"]
        samples[2]["elapsed_realtime_nanos"] = samples[1]["elapsed_realtime_nanos"]

        with self.assertRaisesRegex(CohortError, "radio_timestamps_not_strictly_increasing"):
            analyze(documents, root=ROOT)

    def test_token_delegates_the_only_authorized_threshold_to_d58(self) -> None:
        report = analyze(
            [_token_run(index, 640.0 + index) for index in range(1, 6)],
            root=ROOT,
        )

        self.assertEqual("aneb-repeatability-cohort-v1", report["schema_version"])
        self.assertEqual("pass", report["status"])
        self.assertEqual("D-58", report["policy"]["authority"])
        self.assertEqual("TOK-B04", report["policy"]["metric_id"])
        self.assertFalse(report["policy"]["formal_baseline_eligible"])
        self.assertTrue(report["policy"]["single_run_confidence_unchanged"])
        self.assertEqual(5, report["cohort"]["run_count"])
        self.assertIn("TOK-B04", report["metric_diagnostics"])

    def test_dynamic_link_bandwidth_estimates_do_not_split_a_wifi_cohort(self) -> None:
        documents = [_token_run(index, 640.0 + index) for index in range(1, 6)]
        for index, document in enumerate(documents, 1):
            document["context"]["network"]["capabilities"] = [
                "validated=true",
                "transports=wifi",
                f"down_kbps={85_000 + index}",
                f"up_kbps={37_000 + index}",
            ]

        report = analyze(documents, root=ROOT)

        self.assertEqual("pass", report["status"])
        self.assertEqual(
            ["validated=true", "transports=wifi"],
            report["cohort"]["identity"]["network"]["capabilities"],
        )

    def test_non_bandwidth_network_capability_drift_still_splits_a_cohort(self) -> None:
        documents = [_token_run(index, 640.0 + index) for index in range(1, 3)]
        documents[0]["context"]["network"]["capabilities"] = [
            "validated=true",
            "transports=wifi",
            "up_kbps=40000",
        ]
        documents[1]["context"]["network"]["capabilities"] = [
            "validated=false",
            "transports=wifi",
            "up_kbps=41000",
        ]

        with self.assertRaisesRegex(CohortError, "heterogeneous_cohort"):
            analyze(documents, root=ROOT)

    def test_realtime_is_diagnostic_only_and_does_not_inherit_d58(self) -> None:
        report = analyze(
            [_realtime_run(index, (0.98, 42.0 + index, 0.01)) for index in range(1, 6)],
            root=ROOT,
        )

        self.assertEqual("policy_pending", report["status"])
        self.assertIsNone(report["policy"]["authority"])
        self.assertEqual("diagnostic_only", report["policy"]["mode"])
        self.assertFalse(report["policy"]["formal_baseline_eligible"])
        self.assertTrue(report["policy"]["single_run_confidence_unchanged"])
        self.assertEqual(
            {"LIVE-B05", "LIVE-N02", "LIVE-B08"},
            set(report["metric_diagnostics"]),
        )

    def test_network_is_diagnostic_only_and_does_not_inherit_d58(self) -> None:
        report = analyze(
            [_network_run(index, (45.0 + index, 18.0 + index, 80.0 + index)) for index in range(1, 6)],
            root=ROOT,
        )

        self.assertEqual("policy_pending", report["status"])
        self.assertIsNone(report["policy"]["authority"])
        self.assertEqual("diagnostic_only", report["policy"]["mode"])
        self.assertEqual(
            {"NET-B01", "NET-B02", "NET-B04"},
            set(report["metric_diagnostics"]),
        )

    def test_rejects_non_v2_documents_before_cohort_analysis(self) -> None:
        documents = [_realtime_run(index, (0.98, 42.0, 0.01)) for index in range(1, 3)]
        documents[0]["schema_version"] = "aneb-result-v1"

        with self.assertRaisesRegex(CohortError, "strict_v2_required"):
            analyze(documents, root=ROOT)

    def test_rejects_algorithm_version_drift(self) -> None:
        documents = [_network_run(index, (45.0, 18.0, 80.0)) for index in range(1, 4)]
        documents[2]["evaluation"]["algorithm_versions"]["measurement_engine_version"] = "changed"

        with self.assertRaisesRegex(CohortError, "heterogeneous_cohort"):
            analyze(documents, root=ROOT)

    def test_missing_primary_metric_is_not_coerced_to_zero(self) -> None:
        documents = [_realtime_run(index, (0.98, 42.0, 0.01)) for index in range(1, 3)]
        documents[1]["evaluation"]["metrics"].pop("LIVE-N02")

        with self.assertRaisesRegex(CohortError, "missing_field:.*/LIVE-N02"):
            analyze(documents, root=ROOT)

    def test_zero_mean_reports_undefined_cv_instead_of_zero(self) -> None:
        report = analyze(
            [_realtime_run(index, (0.0, 42.0, 0.01)) for index in range(1, 4)],
            root=ROOT,
        )

        metric = report["metric_diagnostics"]["LIVE-B05"]
        self.assertEqual("undefined_zero_mean", metric["cv_state"])
        self.assertIsNone(metric["sample_cv"])


if __name__ == "__main__":
    unittest.main()
