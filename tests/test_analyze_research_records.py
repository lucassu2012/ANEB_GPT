import unittest
import json
import subprocess
import sys
import tempfile
from pathlib import Path
import hashlib
import copy

from scripts.analyze_research_records import analyze


def sample():
    return {"input_revision": "alignment-1", "record_kind": "SAMPLE", "records": [{
        "attempt_id": "sample-1", "record_kind": "SAMPLE",
        "outcome": {"executed": True},
        "clock": {"unit": "ms", "domain_id": "sample-video", "video_id": "sample-video", "mapping": "sample mapping"},
        "events": {"send": {"time_ms": [1000, 1040], "clock_domain_id": "sample-video"},
                   "first_feedback": {"time_ms": [1800, 1840], "clock_domain_id": "sample-video"}}
    }]}


class ResearchTests(unittest.TestCase):
    def test_unknown_metadata_or_same_id_different_app_never_groups(self):
        document = sample()
        document["records"][0]["summary_group"] = {"confirmed": True, "id": "g"}
        self.assertEqual(analyze(document)["groups"], [])
        r = document["records"][0]
        r.update(app={"name": "A", "version": "1", "model_mode": "text"},
                 method_id="m", action_text="a", condition="c",
                 metadata={"device": "d", "network_description": "n"},
                 anchors={"send": "s", "feedback": "f", "body": "b"})
        other = copy.deepcopy(r)
        other["attempt_id"] = "other"
        other["app"]["name"] = "B"
        document["records"].append(other)
        self.assertEqual(len(analyze(document)["groups"]), 2)

    def test_confirmed_group_retains_intervals_and_point_only_statistics(self):
        document = sample()
        template = document["records"][0]
        template.update(app={"name": "SAMPLE_APP", "version": "1", "model_mode": "text"},
            method_id="sample-method", action_text="sample-action", condition="C0",
            metadata={"device": "sample-device", "network_description": "sample-network"},
            anchors={"send": "send", "feedback": "indicator", "body": "text"},
            summary_group={"confirmed": True, "id": "sample-group"})
        document["records"] = []
        for i, end in enumerate(([2000, 2000], [4000, 4000], [5000, 5100], None)):
            r = copy.deepcopy(template)
            r["attempt_id"] = str(i)
            r["events"]["send"]["time_ms"] = [1000, 1000]
            r["events"]["first_feedback"]["time_ms"] = end
            document["records"].append(r)
        summary = analyze(document)["groups"][0]["metrics"]["ttfr"]
        self.assertEqual(summary["valid_n"], 3)
        self.assertEqual(summary["point_n"], 2)
        self.assertEqual(summary["na_n"], 1)
        self.assertEqual(summary["point_median_s"], 2)
        self.assertEqual(summary["point_range_s"], [1, 3])
        self.assertEqual(len(summary["attempts"]), 4)
        self.assertEqual(summary["attempts"][2]["interval_s"], [4, 4.1])

    def test_observed_requires_local_source_reference(self):
        document = sample()
        document["record_kind"] = document["records"][0]["record_kind"] = "OBSERVED"
        with self.assertRaisesRegex(ValueError, "observed_source"):
            analyze(document)
        document["records"][0]["evidence"] = {"local_ref": "local-field-note", "sha256": None}
        document["records"][0]["missing_reasons"] = {"sha256": "not acquired"}
        self.assertEqual(analyze(document)["record_kind"], "OBSERVED")

    def test_separate_observation_gap_prevents_bridged_stall(self):
        document = sample()
        document["records"][0]["observed_intervals"] = [
            {"kind": "update_gap", "start_ms": [4000, 4040], "end_ms": [8000, 8040],
             "resumed": True, "fully_visible": True},
            {"kind": "gap", "start_ms": [5000, 5040], "end_ms": [6000, 6040]}]
        result = analyze(document)["records"][0]["stalls"]
        self.assertEqual(result["intervals"][0]["classification"], "observation_gap")
        self.assertEqual(result["confirmed_observed_count"], 0)

    def test_unknown_and_mixed_record_kind_rejected(self):
        for outer, inner in [("UNKNOWN", "UNKNOWN"), ("OBSERVED", "SAMPLE"), ("SAMPLE", "OBSERVED")]:
            document = sample()
            document["record_kind"] = outer
            document["records"][0]["record_kind"] = inner
            with self.subTest(outer=outer, inner=inner), self.assertRaisesRegex(ValueError, "record_kind"):
                analyze(document)

    def test_stall_threshold_censor_and_visibility_boundaries(self):
        for kind, end, resumed, visible, expected in [
            ("update_gap", [6000, 6040], True, True, "uncertain"),
            ("update_gap", [5900, 5940], True, True, "below_threshold"),
            ("update_gap", [6040, 6080], True, True, "confirmed"),
            ("unclosed_tail", [8000, 8040], False, True, "right_censored"),
            ("gap", [8000, 8040], True, True, "observation_gap"),
            ("update_gap", [8000, 8040], True, False, "observation_gap"),
            ("update_gap", [8000, 8040], False, True, "right_censored"),
            ("update_gap", None, True, True, "NA"),
        ]:
            with self.subTest(expected=expected, kind=kind):
                document = sample()
                document["records"][0]["observed_intervals"] = [{"kind": kind,
                    "start_ms": [4000, 4040], "end_ms": end, "resumed": resumed, "fully_visible": visible}]
                result = analyze(document)["records"][0]["stalls"]
                self.assertEqual(result["intervals"][0]["classification"], expected)
                self.assertEqual(result["confirmed_observed_count"], int(expected == "confirmed"))
                self.assertIsNone(result["total_count"])

    def test_visible_resumed_gap_uses_entire_interval(self):
        document = sample()
        r = document["records"][0]
        r["observed_intervals"] = [{"kind": "update_gap", "start_ms": [4000, 4040],
            "end_ms": [6400, 6440], "resumed": True, "fully_visible": True}]
        result = analyze(document)["records"][0]["stalls"]
        self.assertEqual(result["intervals"][0]["classification"], "confirmed")
        self.assertEqual(result["intervals"][0]["interval_s"], [2.36, 2.44])
        self.assertEqual(result["confirmed_observed_count"], 1)
        self.assertIsNone(result["total_count"])
        self.assertIsNone(result["max_interval_s"])

    def test_failed_uncertain_and_not_run_slots_remain_in_counts(self):
        document = sample()
        base = document["records"][0]
        document["records"] = []
        for i, (executed, status, visible) in enumerate([
                (True, "failed", "no"), (True, "incomplete", "uncertain"),
                (False, "not_run", None), (True, "completed", "yes")]):
            row = copy.deepcopy(base)
            row["attempt_id"] = str(i)
            row["outcome"] = {"executed": executed, "status": status, "visible_completion": visible}
            document["records"].append(row)
        result = analyze(document)
        self.assertEqual(result["counts"], {"planned": 4, "attempted": 3,
                         "not_run": 1, "visible_completed_confirmed": 0})
        self.assertEqual(len(result["records"]), 4)
        self.assertIsNone(result["records"][0]["completion"]["interval_s"])

    def test_uncertain_order_preserves_input_and_does_not_report_duration(self):
        document = sample()
        document["records"][0]["events"]["first_feedback"]["time_ms"] = [1020, 1080]
        before = copy.deepcopy(document)
        result = analyze(document)
        self.assertEqual(result["records"][0]["ttfr"]["status"], "uncertain")
        self.assertIsNone(result["records"][0]["ttfr"]["interval_s"])
        self.assertEqual(result["records"][0]["input_record"], before["records"][0])
        self.assertEqual(document, before)
        self.assertEqual(result["records"][0]["stalls"]["status"], "not_computed")

    def test_invalid_interval_and_duplicate_are_not_silently_counted(self):
        for value in ([True, 1040], [1040, 1000], [0, float("nan")], [1], ["1", 2]):
            with self.subTest(value=value):
                document = sample()
                document["records"][0]["events"]["send"]["time_ms"] = value
                self.assertIsNone(analyze(document)["records"][0]["ttfr"]["interval_s"])
        document = sample()
        document["records"].append(document["records"][0])
        with self.assertRaisesRegex(ValueError, "duplicate_attempt_id"):
            analyze(document)

    def test_content_and_confirmed_completion(self):
        document = sample()
        r = document["records"][0]
        for name, times in {"first_content": [3000, 3040], "last_content": [9000, 9040],
                            "complete_confirm": [12100, 12140]}.items():
            r["events"][name] = {"time_ms": times, "clock_domain_id": "sample-video"}
        r["outcome"].update(status="completed", visible_completion="yes", completion_basis="visible normal exit")
        r["completion_observation"] = {"body_continuously_visible": True, "ui_exited_generation_normally": True}
        row = analyze(document)["records"][0]
        self.assertEqual(row["ttfc"]["interval_s"], [1.96, 2.04])
        self.assertEqual(row["completion"]["interval_s"], [7.96, 8.04])
        r["completion_observation"]["ui_exited_generation_normally"] = None
        self.assertIsNone(analyze(document)["records"][0]["completion"]["interval_s"])
        r["completion_observation"]["ui_exited_generation_normally"] = True
        r["events"]["complete_confirm"]["time_ms"] = [12039, 12100]
        self.assertIsNone(analyze(document)["records"][0]["completion"]["interval_s"])
        r["events"]["complete_confirm"]["time_ms"] = [12040, 12100]
        self.assertEqual(analyze(document)["records"][0]["completion"]["interval_s"], [7.96, 8.04])

    def test_bad_execution_flag_is_rejected_not_counted_as_not_run(self):
        document = sample()
        document["records"][0]["outcome"]["executed"] = "true"
        with self.assertRaisesRegex(ValueError, "executed"):
            analyze(document)

    def test_only_alignment_one_input_is_accepted(self):
        document = sample()
        document["input_revision"] = "old-draft"
        with self.assertRaisesRegex(ValueError, "input_revision"):
            analyze(document)

    def test_alignment_one_milliseconds_and_domain_mismatch(self):
        document = sample()
        r = document["records"][0]
        r["clock"].update(unit="ms", domain_id="sample-video")
        r["events"] = {"send": {"time_ms": [1000, 1040], "clock_domain_id": "sample-video"},
                       "first_feedback": {"time_ms": [1800, 1840], "clock_domain_id": "sample-video"}}
        self.assertEqual(analyze(document)["records"][0]["ttfr"]["interval_s"], [0.76, 0.84])
        r["events"]["first_feedback"]["clock_domain_id"] = "other-video"
        self.assertEqual(analyze(document)["records"][0]["ttfr"]["reason"], "clock_domain_mismatch")

    def test_cli_preserves_input_and_binds_its_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.json"
            raw = json.dumps(sample()).encode("utf-8")
            path.write_bytes(raw)
            run = subprocess.run([sys.executable, "-B", "scripts/analyze_research_records.py", str(path)],
                                 capture_output=True, check=False)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertEqual(run.stderr, b"")
            result = json.loads(run.stdout)
            self.assertEqual(result["source_sha256"], hashlib.sha256(raw).hexdigest())
            self.assertNotIn("input_sha256", result)
            self.assertEqual(result["records"][0]["ttfr"]["interval_s"], [0.76, 0.84])
            self.assertEqual(path.read_bytes(), raw)

    def test_visible_feedback_preserves_interval_and_sample(self):
        result = analyze(sample())
        self.assertEqual(result["record_kind"], "SAMPLE")
        row = result["records"][0]
        self.assertEqual(row["attempt_id"], "sample-1")
        self.assertEqual(row["ttfr"]["interval_s"], [0.76, 0.84])
        self.assertEqual(row["ttfr"]["status"], "interval")

    def test_unavailable_timing_is_na_without_dropping_attempt(self):
        for change, reason in [
            (lambda r: r["outcome"].update(executed=False), "not_executed"),
            (lambda r: r["clock"].update(mapping=None), "clock_unavailable"),
            (lambda r: r["events"].update(send=None), "event_unavailable"),
        ]:
            with self.subTest(reason=reason):
                document = sample()
                change(document["records"][0])
                result = analyze(document)
                self.assertEqual(len(result["records"]), 1)
                self.assertEqual(result["records"][0]["ttfr"],
                                 {"status": "NA", "interval_s": None, "reason": reason})
