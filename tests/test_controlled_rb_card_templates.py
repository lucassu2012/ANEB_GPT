import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
TEMPLATE_DIR = REPO_ROOT / "tools" / "controlled-rb-card" / "templates"
EXPECTED_TEMPLATES = (
    "common.ps1.tmpl",
    "execute-once.ps1.tmpl",
    "run-once.ps1.tmpl",
    "verify-cleanup-readonly.ps1.tmpl",
)


class ControlledRbCardTemplateTests(unittest.TestCase):
    def test_templates_exist_and_are_pure_ascii(self):
        for name in EXPECTED_TEMPLATES:
            path = TEMPLATE_DIR / name
            self.assertTrue(path.is_file(), f"Missing template: {path}")
            raw = path.read_bytes()
            non_ascii = [b for b in raw if b > 127]
            self.assertEqual(non_ascii, [], f"Non-ASCII byte(s) found in {name}")

    def test_execute_once_derives_phase_b_rate_from_request_json(self):
        execute_text = (TEMPLATE_DIR / "execute-once.ps1.tmpl").read_text(encoding="ascii")
        for forbidden in ("250000", "125000", "187500", "500000"):
            self.assertNotIn(forbidden, execute_text, f"Hardcoded rate literal {forbidden} found")
        self.assertIn("b_downlink_ip_bytes_per_second    = $bDownlinkIpBytesPerSecond", execute_text)
        self.assertIn("Get-AnebPhaseInstrumentationConditionArgs $conditionId $savedRequest", execute_text)
        self.assertIn("Assert-AnebPhaseResultConditionMatch $conditionId $devResult $savedRequest", execute_text)
        self.assertIn("phases                            = @({{PHASE_ORDER_PS}})", execute_text)
        self.assertIn("foreach ($conditionId in @({{PHASE_ORDER_PS}}))", execute_text)
        self.assertNotIn("@('R', 'B')", execute_text)
        self.assertIn("FINAL_MEDIA.json", execute_text)
        self.assertIn("[Math]::Max(60, $nativeTtlSeconds - 20)", execute_text)

    def test_common_defines_condition_arg_and_provenance_helpers(self):
        common_text = (TEMPLATE_DIR / "common.ps1.tmpl").read_text(encoding="ascii")
        self.assertIn("function Get-AnebPhaseInstrumentationConditionArgs", common_text)
        self.assertIn("function Assert-AnebPhaseResultConditionMatch", common_text)
        self.assertIn("function Assert-AnebDoubaoUiBinding", common_text)
        self.assertIn("com\\.larus\\.nova", common_text)
        self.assertIn("com\\.huawei\\.himovie\\b", common_text)
        self.assertNotIn("|himovie|", common_text)
        self.assertIn("downlink_ip_bytes_per_second", common_text)
        self.assertIn("downlinkIpBytesPerSecond", common_text)

    def test_verify_cleanup_reads_terminal_before_checking_before_json(self):
        verify_text = (TEMPLATE_DIR / "verify-cleanup-readonly.ps1.tmpl").read_text(encoding="ascii")
        terminal_pos = verify_text.find("runtime/TERMINAL.json")
        before_pos = verify_text.find("runtime/before.json")
        self.assertGreater(terminal_pos, -1)
        self.assertGreater(before_pos, terminal_pos)
        self.assertIn("Test-Path -LiteralPath $beforePath", verify_text)
        self.assertIn("early_abort_verified", verify_text)
        self.assertIn("hotspot-start-intent.json", verify_text)
        self.assertIn("Assert-AnebSharingDisabled", verify_text)
        self.assertIn("instrumentation_dispatch_permit_count", verify_text)
        self.assertIn("runtime/CARD.json", verify_text)


if __name__ == "__main__":
    unittest.main()
