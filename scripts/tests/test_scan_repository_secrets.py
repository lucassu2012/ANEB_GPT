import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scan_repository_secrets.py"
SPEC = importlib.util.spec_from_file_location("scan_repository_secrets", MODULE_PATH)
assert SPEC and SPEC.loader
scanner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = scanner
SPEC.loader.exec_module(scanner)


class RepositorySecretScanTest(unittest.TestCase):
    def test_detects_supported_high_confidence_credentials(self):
        samples = {
            "github_classic_token": "gh" + "p_" + "A" * 36,
            "github_fine_grained_token": "github_" + "pat_" + "A" * 60,
            "openai_api_key": "sk-" + "A" * 40,
            "anthropic_api_key": "sk-" + "ant-" + "A" * 40,
            "aws_access_key": "AK" + "IA" + "A" * 16,
            "alibaba_access_key": "LT" + "AI" + "A" * 16,
            "google_api_key": "AI" + "za" + "A" * 35,
            "slack_token": "xox" + "b-" + "A" * 24,
            "pem_private_key": "-----BEGIN " + "PRIVATE KEY-----",
        }
        for expected_rule, sample in samples.items():
            with self.subTest(expected_rule=expected_rule):
                findings = scanner.scan_text("fixture.txt", f"value={sample}\n")
                self.assertIn(expected_rule, {finding.rule_id for finding in findings})

    def test_output_redacts_the_matched_value(self):
        secret = "gh" + "p_" + "B" * 36
        finding = scanner.scan_text("settings.txt", secret)[0]
        rendered = scanner.format_finding(finding)
        self.assertNotIn(secret, rendered)
        self.assertEqual(
            "secret-scan finding rule=github_classic_token location=settings.txt:1",
            rendered,
        )

    def test_clean_placeholders_and_environment_names_are_allowed(self):
        text = "\n".join(
            [
                "ANEB_RELEASE_STORE_PASSWORD",
                "github_pat_<store-in-repository-secret>",
                "-----BEGIN CERTIFICATE-----",
                "sha256:0123456789abcdef",
            ]
        )
        self.assertEqual([], scanner.scan_text("docs/example.md", text))

    def test_binary_files_are_skipped_without_decoding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "image.bin"
            binary.write_bytes(b"\0gh" + b"p_" + b"C" * 36)
            self.assertEqual([], scanner.scan_paths(root, [binary]))

    def test_tracked_symlink_is_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target.txt"
            target.write_text("clean", encoding="utf-8")
            link = root / "link.txt"
            try:
                link.symlink_to(target)
            except OSError:
                self.skipTest("symlinks are not available in this environment")
            findings = scanner.scan_paths(root, [link])
            self.assertEqual("tracked_symlink_not_scanned", findings[0].rule_id)


if __name__ == "__main__":
    unittest.main()
