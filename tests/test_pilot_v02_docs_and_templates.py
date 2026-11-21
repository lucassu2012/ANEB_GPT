import json
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
PILOT_DIR = REPO_ROOT / "docs" / "pilot-readiness"
RESEARCH_DIR = REPO_ROOT / "docs" / "research"

ABS_PATH_RE = re.compile(r"([A-Za-z]:\\|/Users/|/home/|/root/)")
MD_LINK_RE = re.compile(r"\[[^\]]+\]\(([^)#]+)(?:#[^)]*)?\)")

STATUS_ENUM = {"PASS", "HOLD", "FAIL", "NOT_RUN"}
ASSISTANCE_ENUM = {"NONE", "OPERATOR_ASSISTED", "NOT_RUN"}
ASSESSMENT_ENUM = {"PASS", "PARTIAL", "BLOCKED", "NOT_RUN"}


class PilotV02DocsAndTemplatesTests(unittest.TestCase):
    def _read_utf8_no_bom(self, path: Path) -> str:
        raw = path.read_bytes()
        self.assertFalse(
            raw.startswith(b"\xef\xbb\xbf"),
            f"UTF-8 BOM must not be present in {path.relative_to(REPO_ROOT)}",
        )
        return raw.decode("utf-8")

    def test_pilot_readiness_required_files_exist_and_historical_preserved(self) -> None:
        expected_files = [
            "README.md",
            "MOBILE_PILOT_QUICKSTART_ZH.md",
            "PILOT_V02_QUICKSTART_ZH.md",
            "PILOT_V02_FEEDBACK_TEMPLATE.md",
            "PILOT_V02_FEEDBACK_TEMPLATE.json",
            "PILOT_BRIEF.md",
            "OPERATOR_CHECKLIST.md",
            "INVITATION_SCREENING.md",
            "N3_ACCEPTANCE.md",
        ]
        for name in expected_files:
            self.assertTrue((PILOT_DIR / name).is_file(), f"missing {name}")

        readme = self._read_utf8_no_bom(PILOT_DIR / "README.md")
        self.assertIn("NOT_RUN", readme)
        for target in (
            "MOBILE_PILOT_QUICKSTART_ZH.md",
            "PILOT_V02_QUICKSTART_ZH.md",
            "PILOT_V02_FEEDBACK_TEMPLATE.md",
            "PILOT_V02_FEEDBACK_TEMPLATE.json",
            "../research/ANEB_AI_NETWORK_EXPERIENCE_BASELINE_REPORT_V02.md",
            "PILOT_BRIEF.md",
            "OPERATOR_CHECKLIST.md",
            "INVITATION_SCREENING.md",
            "N3_ACCEPTANCE.md",
        ):
            self.assertIn(target, readme)

    def test_pilot_readiness_has_no_absolute_developer_paths_or_broken_links(self) -> None:
        for path in sorted(PILOT_DIR.iterdir()):
            if path.suffix not in {".md", ".json"}:
                continue
            text = self._read_utf8_no_bom(path)
            match = ABS_PATH_RE.search(text)
            self.assertIsNone(
                match,
                f"absolute developer path {match.group(0) if match else ''} found in {path.name}",
            )
            if path.suffix == ".md":
                for link in MD_LINK_RE.findall(text):
                    if link.startswith(("http://", "https://", "mailto:")):
                        continue
                    resolved = (path.parent / link).resolve()
                    self.assertTrue(
                        resolved.exists(),
                        f"broken relative link '{link}' in {path.name}",
                    )

    def test_quickstart_covers_dual_apks_stage2_ui_and_boundaries(self) -> None:
        qs = self._read_utf8_no_bom(PILOT_DIR / "PILOT_V02_QUICKSTART_ZH.md")
        for token in (
            "verify-pilot-v0.2-kit.ps1",
            "PASS PILOT_V02_KIT_VERIFIED",
            "probe-release.apk",
            "probe-prototypeRelease.apk",
            "b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003",
            "LoadedResponsiveness v2",
            "aneb-profile-v2",
            "PairedComparisonCard",
            "设置 -> 高级 -> API 探针",
            "START_ANEB.bat",
            "Quick",
            "Acceptance",
            "Export ZIP",
            "G0_VERIFY_OK",
            "NOT_RUN",
        ):
            self.assertIn(token, qs)

    def test_mobile_pilot_quickstart_is_phone_only_and_covers_miw2_sharing_and_boundaries(self) -> None:
        qs = self._read_utf8_no_bom(PILOT_DIR / "MOBILE_PILOT_QUICKSTART_ZH.md")
        for token in (
            "ANEB-Probe-v0.2.0-public-pilot.apk",
            "probe-release.apk",
            "无需电脑",
            "https://120.77.174.207:8443",
            "b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003",
            "LoadedResponsiveness v2",
            "分享图片",
            "导出 MD",
            "不会静默上传",
            "NOT_RUN",
        ):
            self.assertIn(token, qs)
        self.assertNotIn("START_ANEB.bat", qs)
        self.assertNotIn("verify-pilot-v0.2-kit.ps1", qs)

    def test_feedback_template_json_schema_and_enums(self) -> None:
        tpl_path = PILOT_DIR / "PILOT_V02_FEEDBACK_TEMPLATE.json"
        raw = tpl_path.read_bytes()
        self.assertNotIn(b"\r\n", raw, "JSON template should use LF line endings")
        payload = json.loads(self._read_utf8_no_bom(tpl_path))

        self.assertEqual(payload["schema_version"], "aneb-pilot-v0.2-feedback-0.1")
        self.assertEqual(payload["kit_metadata"]["kit_version"], "pilot-v0.2")
        self.assertIn(payload["kit_metadata"]["kit_verifier_status"], STATUS_ENUM)
        self.assertEqual(payload["session_metadata"]["network_environment"], "UNKNOWN")
        self.assertIs(payload["data_custody_agreement"]["pii_and_secrets_redacted"], False)

        expected_steps = [
            "kit_offline_verify",
            "apk_install_and_switch",
            "miw2_basic_and_loaded_responsiveness",
            "miw2_token_aqs_and_paired_compare",
            "miw2_profile_v2_trace",
            "prototype_launcher_ready_and_compatible",
            "prototype_quick_3",
            "prototype_acceptance_9",
            "saved_campaigns_reopen",
            "clean_exit_and_device_cleanup",
        ]
        checks = payload["workflow_checks"]
        self.assertEqual(list(checks.keys()), expected_steps)
        for step_name, step_obj in checks.items():
            self.assertIn(step_obj["status"], STATUS_ENUM, step_name)
            if "assistance" in step_obj:
                self.assertIn(step_obj["assistance"], ASSISTANCE_ENUM, step_name)

        self.assertIn("hardest_step", payload["usability_and_comprehension"])
        self.assertIn(
            payload["adoption_and_pilot_gate"]["independent_completion_assessment"],
            ASSESSMENT_ENUM,
        )

    def test_baseline_report_v02_exists_with_all_nine_sections_and_reconciliations(self) -> None:
        report_path = RESEARCH_DIR / "ANEB_AI_NETWORK_EXPERIENCE_BASELINE_REPORT_V02.md"
        self.assertTrue(report_path.is_file())
        report = self._read_utf8_no_bom(report_path)
        for heading in (
            "## 1. Executive Summary",
            "## 2. Experimental Methodology, Topology & Integrity Invariants",
            "## 3. Controlled Business Baseline I",
            "## 4. Controlled Business Baseline II",
            "## 5. Field Paired Benchmark",
            "## 6. Profile 4 Synthetic Voice",
            "## 7. Platform & Runtime Foundations",
            "## 8. Cross-Domain Synthesis",
            "## 9. Reproducible Artifact Index",
        ):
            self.assertIn(heading, report)
        # Reconciled precision guards
        self.assertIn("douyin-controlled-rb-4mbps-200ms-5pct-410101-20261120-r3", report)
        self.assertIn("41.1.0", report)
        self.assertIn("16.64×", report)
        self.assertNotIn("26x", report)
        self.assertIn("replay = 8", report)
        self.assertIn("voice-correlation-summary.json", report)


if __name__ == "__main__":
    unittest.main()
