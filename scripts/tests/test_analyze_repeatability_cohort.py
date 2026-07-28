from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

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


class RepeatabilityCohortTests(unittest.TestCase):
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
