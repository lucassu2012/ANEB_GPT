import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
NEW_CARD = REPO_ROOT / "tools" / "controlled-rb-card" / "new_card.py"
SOURCE_COMMIT = "a" * 40


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ControlledRbCardGeneratorTests(unittest.TestCase):
    def _make_fixtures(self, tmp: Path) -> dict[str, Path]:
        fx = tmp / "fixtures"
        fx.mkdir(parents=True, exist_ok=True)
        (fx / "probe-debug.apk").write_bytes(b"PK\x03\x04dummy-debug-apk")
        (fx / "probe-debug-androidTest.apk").write_bytes(b"PK\x03\x04dummy-test-apk")
        (fx / "phone-condition.exe").write_bytes(b"MZdummy-phone-condition")
        (fx / "connection-admission.ps1").write_text("# dummy connection admission\n", encoding="ascii")
        (fx / "topology-admission.ps1").write_text("# dummy topology admission\n", encoding="ascii")
        (fx / "ui-binding-douyin-410101-v2.json").write_text(
            json.dumps(
                {
                    "observation_method": "targeted-public-selectors-2",
                    "app_version_code": 410101,
                }
            )
            + "\n",
            encoding="utf-8",
        )
        return fx if False else {
            "target_apk": fx / "probe-debug.apk",
            "test_apk": fx / "probe-debug-androidTest.apk",
            "helper_exe": fx / "phone-condition.exe",
            "conn_adm": fx / "connection-admission.ps1",
            "topo_adm": fx / "topology-admission.ps1",
            "douyin_ui": fx / "ui-binding-douyin-410101-v2.json",
        }

    def _run_new_card(
        self,
        extra_args: list[str],
        cwd: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(NEW_CARD), *extra_args],
            cwd=str(cwd or REPO_ROOT),
            capture_output=True,
            text=True,
        )

    def _assert_card_hashes_and_ascii(self, card_dir: Path, expected_ui_name: str):
        for fname in ("common.ps1", "execute-once.ps1", "verify-cleanup-readonly.ps1", "run-once.ps1"):
            raw = (card_dir / fname).read_bytes()
            self.assertEqual([b for b in raw if b > 127], [], f"Non-ASCII in {fname}")
            self.assertNotIn(b"\r\n", raw, f"CRLF found in {fname}")
            text = raw.decode("ascii")
            self.assertEqual(re.findall(r"\{\{[A-Z0-9_]+\}\}", text), [], f"Leftover token in {fname}")

        spec = json.loads((card_dir / "CARD_SPEC.json").read_text(encoding="utf-8"))
        pins = spec["sha256_pins"]
        for rel, expected_sha in pins.items():
            if rel == "profile_json":
                continue
            target = card_dir / rel
            self.assertTrue(target.is_file(), f"Missing pinned file: {target}")
            self.assertEqual(sha256_bytes(target.read_bytes()), expected_sha, f"SHA mismatch for {rel}")

        run_once_text = (card_dir / "run-once.ps1").read_text(encoding="ascii")
        self.assertIn(pins["common.ps1"], run_once_text)
        self.assertIn(pins["execute-once.ps1"], run_once_text)
        self.assertIn(pins["verify-cleanup-readonly.ps1"], run_once_text)

        verify_text = (card_dir / "verify-cleanup-readonly.ps1").read_text(encoding="ascii")
        self.assertIn(pins["common.ps1"], verify_text)

        common_text = (card_dir / "common.ps1").read_text(encoding="ascii")
        self.assertIn(pins["CANDIDATE_ARTIFACTS.json"], common_text)
        self.assertIn(pins["connection-admission.ps1"], common_text)
        self.assertIn(pins["topology-admission.ps1"], common_text)
        self.assertIn(pins[expected_ui_name], common_text)
        self.assertIn(pins["artifacts/probe-debug.apk"], common_text)
        self.assertIn(pins["artifacts/probe-debug-androidTest.apk"], common_text)
        self.assertIn(pins["artifacts/phone-condition.exe"], common_text)

        cand = json.loads((card_dir / "CANDIDATE_ARTIFACTS.json").read_text(encoding="utf-8"))
        self.assertEqual(cand["ui_binding_sha256"], pins[expected_ui_name])
        self.assertEqual(cand["profile_sha256"], pins["profile_json"])
        self.assertEqual(cand["artifacts"]["probe_debug_apk"]["sha256"], pins["artifacts/probe-debug.apk"])
        self.assertEqual(
            cand["artifacts"]["probe_debug_android_test_apk"]["sha256"],
            pins["artifacts/probe-debug-androidTest.apk"],
        )
        self.assertEqual(cand["artifacts"]["phone_condition_exe"]["sha256"], pins["artifacts/phone-condition.exe"])

    def test_generate_douyin_r_to_b_and_b_to_r_with_exact_pins(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            fx = self._make_fixtures(tmp)
            case_root = tmp / "case"
            case_root.mkdir()

            card_rb = case_root / "douyin-rb-4m"
            res_rb = self._run_new_card([
                "--card-id", "douyin-rb-4m",
                "--app", "douyin",
                "--version", "410101",
                "--downlink-bps", "500000",
                "--delay-ms", "200",
                "--loss-bps", "500",
                "--seed", "20261120",
                "--order", "R->B",
                "--ttl", "100",
                "--case-root", str(case_root),
                "--source-commit", SOURCE_COMMIT,
                "--source-branch", "codex/test",
                "--target-apk", str(fx["target_apk"]),
                "--test-apk", str(fx["test_apk"]),
                "--helper-exe", str(fx["helper_exe"]),
                "--ui-binding", str(fx["douyin_ui"]),
                "--connection-admission", str(fx["conn_adm"]),
                "--topology-admission", str(fx["topo_adm"]),
            ])
            self.assertEqual(res_rb.returncode, 0, res_rb.stderr)
            self._assert_card_hashes_and_ascii(card_rb, "ui-binding-douyin-410101-v2.json")

            exec_rb = (card_rb / "execute-once.ps1").read_text(encoding="ascii")
            self.assertIn("phases                            = @('R', 'B')", exec_rb)
            self.assertIn("foreach ($conditionId in @('R', 'B'))", exec_rb)
            self.assertIn("Assert-AnebDouyinUiBindingV2", exec_rb)
            self.assertIn("NativeVideoScenarioInstrumentation", exec_rb)

            # B->R order + immutability check
            card_br = case_root / "douyin-br-1m"
            res_br = self._run_new_card([
                "--card-id", "douyin-br-1m",
                "--app", "douyin",
                "--rate", "125000",
                "--order", "B->R",
                "--ttl", "90",
                "--case-root", str(case_root),
                "--source-commit", SOURCE_COMMIT,
                "--target-apk", str(fx["target_apk"]),
                "--test-apk", str(fx["test_apk"]),
                "--helper-exe", str(fx["helper_exe"]),
                "--ui-binding", str(fx["douyin_ui"]),
                "--connection-admission", str(fx["conn_adm"]),
                "--topology-admission", str(fx["topo_adm"]),
            ])
            self.assertEqual(res_br.returncode, 0, res_br.stderr)
            self._assert_card_hashes_and_ascii(card_br, "ui-binding-douyin-410101-v2.json")
            exec_br = (card_br / "execute-once.ps1").read_text(encoding="ascii")
            self.assertIn("phases                            = @('B', 'R')", exec_br)
            self.assertIn("foreach ($conditionId in @('B', 'R'))", exec_br)

            # Re-running into existing card dir must fail closed without altering existing files
            before_run_bytes = (card_rb / "run-once.ps1").read_bytes()
            res_dup = self._run_new_card([
                "--card-id", "douyin-rb-4m",
                "--app", "douyin",
                "--downlink-bps", "500000",
                "--case-root", str(case_root),
                "--source-commit", SOURCE_COMMIT,
                "--target-apk", str(fx["target_apk"]),
                "--test-apk", str(fx["test_apk"]),
                "--helper-exe", str(fx["helper_exe"]),
                "--ui-binding", str(fx["douyin_ui"]),
                "--connection-admission", str(fx["conn_adm"]),
                "--topology-admission", str(fx["topo_adm"]),
            ])
            self.assertNotEqual(res_dup.returncode, 0)
            self.assertEqual((card_rb / "run-once.ps1").read_bytes(), before_run_bytes)

    def test_generate_doubao_with_tail_drop_and_optional_uplink_spec(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            fx = self._make_fixtures(tmp)
            out_dir = tmp / "doubao-256k"

            res = self._run_new_card([
                "--card-id", "doubao-256k",
                "--app", "doubao",
                "--version", "15080040",
                "--version-name", "15.8.0",
                "--downlink-bps", "32000",
                "--delay-ms", "350",
                "--loss-bps", "500",
                "--seed", "20261120",
                "--queue-overflow-policy", "tail_drop",
                "--queue-limit-bytes", "131072",
                "--uplink-bps", "16000",
                "--uplink-delay-ms", "100",
                "--order", "R->B",
                "--ttl", "100",
                "--out-dir", str(out_dir),
                "--case-root", str(tmp),
                "--source-commit", SOURCE_COMMIT,
                "--target-apk", str(fx["target_apk"]),
                "--test-apk", str(fx["test_apk"]),
                "--helper-exe", str(fx["helper_exe"]),
                "--connection-admission", str(fx["conn_adm"]),
                "--topology-admission", str(fx["topo_adm"]),
            ])
            self.assertEqual(res.returncode, 0, res.stderr)
            self._assert_card_hashes_and_ascii(out_dir, "ui-binding-doubao-15080040-v1.json")

            common_text = (out_dir / "common.ps1").read_text(encoding="ascii")
            self.assertIn("$bDownlinkIpBytesPerSecond = [uint64]32000", common_text)
            self.assertIn("$bQueueOverflowPolicy      = 'tail_drop'", common_text)
            self.assertIn("$bQueueLimitBytes          = [uint32]131072", common_text)

            exec_text = (out_dir / "execute-once.ps1").read_text(encoding="ascii")
            self.assertIn("DoubaoControlledScenarioInstrumentation", exec_text)
            self.assertIn("Assert-AnebDoubaoUiBinding", exec_text)
            self.assertNotIn("uplink_ip_bytes_per_second", exec_text)

            spec = json.loads((out_dir / "CARD_SPEC.json").read_text(encoding="utf-8"))
            self.assertEqual(spec["condition_b"]["uplink"]["uplink_ip_bytes_per_second"], 16000)
            self.assertEqual(spec["condition_b"]["uplink"]["uplink_delay_ms"], 100)
            self.assertFalse(spec["condition_b"]["uplink"]["emitted_to_request_json"])

    def test_invalid_arguments_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            fx = self._make_fixtures(tmp)
            common = [
                "--app", "douyin",
                "--case-root", str(tmp),
                "--source-commit", SOURCE_COMMIT,
                "--target-apk", str(fx["target_apk"]),
                "--test-apk", str(fx["test_apk"]),
                "--helper-exe", str(fx["helper_exe"]),
                "--ui-binding", str(fx["douyin_ui"]),
                "--connection-admission", str(fx["conn_adm"]),
                "--topology-admission", str(fx["topo_adm"]),
            ]

            bad_cases = [
                ("bad-seed", ["--card-id", "c1", "--downlink-bps", "500000", "--loss-bps", "500", "--seed", "0"]),
                ("bad-qlim", ["--card-id", "c2", "--downlink-bps", "500000", "--queue-limit-bytes", "1024"]),
                ("bad-ttl", ["--card-id", "c3", "--downlink-bps", "500000", "--ttl", "120"]),
                ("bad-prefix", ["--card-id", "c4", "--downlink-bps", "500000", "--run-id-prefix", "bad.prefix!"]),
                ("non-ascii-id", ["--card-id", "card-\u4e2d\u6587", "--downlink-bps", "500000"]),
            ]
            for label, extra in bad_cases:
                res = self._run_new_card([*common, *extra])
                self.assertNotEqual(res.returncode, 0, f"Expected failure for {label}")

    def test_relative_out_dir_and_case_root_are_normalized_to_absolute(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            fx = self._make_fixtures(tmp)
            common_args = [
                "--app", "douyin",
                "--downlink-bps", "500000",
                "--repo-root", str(REPO_ROOT),
                "--source-commit", SOURCE_COMMIT,
                "--target-apk", str(fx["target_apk"]),
                "--test-apk", str(fx["test_apk"]),
                "--helper-exe", str(fx["helper_exe"]),
                "--ui-binding", str(fx["douyin_ui"]),
                "--connection-admission", str(fx["conn_adm"]),
                "--topology-admission", str(fx["topo_adm"]),
            ]

            # 1. Relative --out-dir ./rel_card from temp cwd
            res_out = self._run_new_card(
                [
                    *common_args,
                    "--card-id", "rel_card",
                    "--case-root", str(tmp),
                    "--out-dir", "./rel_card",
                ],
                cwd=tmp,
            )
            self.assertEqual(res_out.returncode, 0, res_out.stderr)
            rel_card_dir = tmp / "rel_card"
            self._assert_card_hashes_and_ascii(rel_card_dir, "ui-binding-douyin-410101-v2.json")

            expected_out_abs = os.path.abspath(rel_card_dir).replace("\\", "/")
            expected_case_abs = os.path.abspath(tmp).replace("\\", "/")
            self.assertTrue(os.path.isabs(expected_out_abs))

            common_text = (rel_card_dir / "common.ps1").read_text(encoding="ascii")
            run_text = (rel_card_dir / "run-once.ps1").read_text(encoding="ascii")
            verify_text = (rel_card_dir / "verify-cleanup-readonly.ps1").read_text(encoding="ascii")
            spec = json.loads((rel_card_dir / "CARD_SPEC.json").read_text(encoding="utf-8"))

            self.assertIn(f"$root = '{expected_out_abs}'", common_text)
            self.assertIn(f"$case = '{expected_case_abs}'", common_text)
            self.assertIn(f"$root = '{expected_out_abs}'", run_text)
            self.assertIn(f"$expectedRoot = '{expected_out_abs}'", verify_text)
            self.assertEqual(
                spec["sha256_pins"]["verify-cleanup-readonly.ps1"],
                sha256_bytes((rel_card_dir / "verify-cleanup-readonly.ps1").read_bytes()),
            )

            # 2. Relative --case-root ./rel_case without --out-dir
            rel_case = tmp / "rel_case"
            rel_case.mkdir()
            res_case = self._run_new_card(
                [
                    *common_args,
                    "--card-id", "rel_case_card",
                    "--case-root", "./rel_case",
                ],
                cwd=tmp,
            )
            self.assertEqual(res_case.returncode, 0, res_case.stderr)
            rel_case_card_dir = rel_case / "rel_case_card"
            self._assert_card_hashes_and_ascii(rel_case_card_dir, "ui-binding-douyin-410101-v2.json")

            expected_case2_abs = os.path.abspath(rel_case).replace("\\", "/")
            expected_card2_abs = os.path.abspath(rel_case_card_dir).replace("\\", "/")
            common_case_text = (rel_case_card_dir / "common.ps1").read_text(encoding="ascii")
            verify_case_text = (rel_case_card_dir / "verify-cleanup-readonly.ps1").read_text(encoding="ascii")

            self.assertIn(f"$root = '{expected_card2_abs}'", common_case_text)
            self.assertIn(f"$case = '{expected_case2_abs}'", common_case_text)
            self.assertIn("(Join-Path $case 'network-candidate-2.2.2/signature-files/WinDivert-2.2.2-A/x64')", common_case_text)
            self.assertIn("(Join-Path $case 'shaping-admission/desktop-host-admission-once/owned-host-check.ps1')", common_case_text)
            self.assertIn(f"$expectedRoot = '{expected_card2_abs}'", verify_case_text)

            # 3. Parent traversal '..' segments are normalized away
            sub = tmp / "sub"
            sub.mkdir()
            res_dotdot = self._run_new_card(
                [
                    *common_args,
                    "--card-id", "dotdot_card",
                    "--case-root", "../rel_case",
                    "--out-dir", "../dotdot_card",
                ],
                cwd=sub,
            )
            self.assertEqual(res_dotdot.returncode, 0, res_dotdot.stderr)
            dotdot_dir = tmp / "dotdot_card"
            self._assert_card_hashes_and_ascii(dotdot_dir, "ui-binding-douyin-410101-v2.json")
            expected_dotdot_abs = os.path.abspath(dotdot_dir).replace("\\", "/")
            dotdot_verify_text = (dotdot_dir / "verify-cleanup-readonly.ps1").read_text(encoding="ascii")
            self.assertIn(f"$expectedRoot = '{expected_dotdot_abs}'", dotdot_verify_text)
            self.assertNotIn("..", expected_dotdot_abs)

    def test_rejects_dot_and_dotdot_card_ids(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            fx = self._make_fixtures(tmp)
            case_dir = tmp / "case"
            case_dir.mkdir()
            for bad_id in (".", ".."):
                unused_out = tmp / f"out_{'dot' if bad_id == '.' else 'dotdot'}"
                res = self._run_new_card([
                    "--card-id", bad_id,
                    "--app", "douyin",
                    "--downlink-bps", "500000",
                    "--case-root", str(case_dir),
                    "--out-dir", str(unused_out),
                    "--source-commit", SOURCE_COMMIT,
                    "--target-apk", str(fx["target_apk"]),
                    "--test-apk", str(fx["test_apk"]),
                    "--helper-exe", str(fx["helper_exe"]),
                    "--ui-binding", str(fx["douyin_ui"]),
                    "--connection-admission", str(fx["conn_adm"]),
                    "--topology-admission", str(fx["topo_adm"]),
                ])
                self.assertNotEqual(res.returncode, 0, f"Expected non-zero exit for --card-id {bad_id!r}")
                self.assertFalse(unused_out.exists(), f"Output dir must not be created for --card-id {bad_id!r}")
                self.assertEqual(list(case_dir.iterdir()), [], f"Case dir must stay empty for --card-id {bad_id!r}")


if __name__ == "__main__":
    unittest.main()
