from __future__ import annotations

import hashlib
import io
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

import scripts.update_shared_test_status as status_module
from scripts.update_shared_test_status import (
    HANDOFF,
    IDLE,
    LOCKED,
    RUNNING,
    SHANGHAI_TZ,
    StatusDocument,
    StatusError,
    atomic_replace_if_unchanged,
    build_parser,
    default_status_file,
    execute,
    main,
)


BASE = """# Codex / Claude 共享测试状态

## 当前状态

| 项目 | 内容 |
|---|---|
| 状态 | **空闲** |
| 当前执行者 | 无 |
| 当前任务 | 无 |
| 占用资源 | 无 |
| 开始时间 | 无 |
| 最近更新 | 2026-07-18 16:43 |
| 交接说明 | 已完成独立复核。 |

## 共同约定

正文必须原样保留。

## 最近记录

| 时间 | 执行者 | 任务 | 结果 |
|---|---|---|---|
| 2026-07-18 16:43 | Claude → Codex | 旧任务 | 已复核并释放；当前空闲 |
"""

LEASE_ID = "0123456789abcdef0123456789abcdef"
OTHER_LEASE_ID = "fedcba9876543210fedcba9876543210"


class SharedTestStatusStateMachineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.status = self.root / "SHARED_TEST_STATUS.md"
        self.status.write_bytes(BASE.replace("\n", "\r\n").encode("utf-8"))
        self.now = datetime(2026, 7, 18, 17, 5, tzinfo=SHANGHAI_TZ)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def args(self, *values: str):
        return build_parser().parse_args(["--status-file", str(self.status), *values])

    def run_at(self, *values: str) -> tuple[str, str, str]:
        return execute(self.args(*values), now=self.now)

    def parsed(self) -> StatusDocument:
        return StatusDocument.parse(self.status.read_bytes())

    def status_sha256(self) -> str:
        return hashlib.sha256(self.status.read_bytes()).hexdigest()

    def claim(self, executor: str = "Codex") -> None:
        self.run_at(
            "claim",
            "--executor",
            executor,
            "--task",
            "验证 ANEB 包",
            "--resources",
            "P40 Pro、ANEB Codex",
            "--note",
            "仅运行 Quick，并在结束后退出。",
            "--lease-id",
            LEASE_ID,
        )

    def test_default_path_is_two_levels_outside_repository(self) -> None:
        script = Path(__file__).resolve().parents[1] / "update_shared_test_status.py"
        repository = script.resolve().parents[1]
        self.assertEqual(
            repository.parent.parent / "SHARED_TEST_STATUS.md",
            default_status_file(),
        )

    def test_common_options_are_accepted_after_command(self) -> None:
        args = build_parser().parse_args(
            [
                "claim",
                "--executor", "Codex",
                "--task", "验证",
                "--resources", "P40 Pro",
                "--note", "只验证参数顺序",
                "--lease-id", LEASE_ID,
                "--status-file", str(self.status),
                "--dry-run",
            ]
        )
        self.assertEqual(self.status, args.status_file)
        self.assertTrue(args.dry_run)

    def test_claim_updates_all_owner_fields_and_appends_history(self) -> None:
        before_body = "正文必须原样保留。"
        self.assertEqual((IDLE, RUNNING, "written"), self.run_at(
            "claim",
            "--executor", "Codex",
            "--task", "验证 ANEB 包",
            "--resources", "P40 Pro、ANEB Codex",
            "--note", "仅运行 Quick，并在结束后退出。",
            "--lease-id", LEASE_ID,
        ))
        document = self.parsed()
        self.assertEqual(RUNNING, document.snapshot.state)
        self.assertEqual("Codex", document.snapshot.executor)
        self.assertEqual("验证 ANEB 包", document.snapshot.task)
        self.assertEqual("P40 Pro、ANEB Codex", document.snapshot.resources)
        self.assertEqual("2026-07-18 17:05", document.snapshot.started_at)
        self.assertEqual("2026-07-18 17:05", document.snapshot.updated_at)
        text = self.status.read_text(encoding="utf-8")
        self.assertIn(before_body, text)
        self.assertIn("| 2026-07-18 17:05 | Codex | 验证 ANEB 包 | 自动接手；状态进行中", text)

    def test_handoff_requires_same_current_executor(self) -> None:
        self.claim()
        claimed = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "handoff_executor_mismatch"):
            self.run_at(
                "handoff", "--executor", "Claude", "--note", "清理完成。",
                "--lease-id", LEASE_ID,
            )
        self.assertEqual(claimed, self.status.read_bytes())
        with self.assertRaisesRegex(StatusError, "lease_id_mismatch"):
            self.run_at(
                "handoff", "--executor", "Codex", "--note", "清理完成。",
                "--lease-id", OTHER_LEASE_ID,
            )
        self.assertEqual(claimed, self.status.read_bytes())
        self.assertEqual(
            (RUNNING, HANDOFF, "written"),
            self.run_at(
                "handoff", "--executor", " codex ", "--note", "清理完成。",
                "--lease-id", LEASE_ID,
            ),
        )
        self.assertEqual(HANDOFF, self.parsed().snapshot.state)
        self.assertIn(
            f"[ANEB_LEASE_ID:{LEASE_ID}]", self.parsed().snapshot.note
        )

    def test_only_fixed_roles_are_accepted(self) -> None:
        original = self.status.read_bytes()
        for invalid in (
            "Codex reviewer",
            "Claude reviewer",
            "Verifier reviewer",
            "operator",
        ):
            with self.subTest(invalid=invalid):
                with self.assertRaisesRegex(StatusError, "executor_role_invalid"):
                    self.run_at(
                        "claim",
                        "--executor", invalid,
                        "--task", "测试",
                        "--resources", "E-01",
                        "--note", "不会写入",
                        "--lease-id", LEASE_ID,
                    )
                self.assertEqual(original, self.status.read_bytes())

        self.claim()
        malformed = self.status.read_text(encoding="utf-8").replace(
            "| 当前执行者 | Codex |", "| 当前执行者 | Codex reviewer |"
        )
        self.status.write_text(malformed, encoding="utf-8", newline="")
        with self.assertRaisesRegex(StatusError, "executor_role_invalid"):
            self.parsed()

    def test_verifier_cannot_operate_or_own_shared_resources(self) -> None:
        idle = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "verifier_operation_forbidden"):
            self.claim(executor="Verifier")
        self.assertEqual(idle, self.status.read_bytes())

        with self.assertRaisesRegex(StatusError, "verifier_operation_forbidden"):
            self.run_at(
                "lock",
                "--executor", "Verifier",
                "--note", "不得由复核角色接管。",
                "--lease-id", LEASE_ID,
            )
        self.assertEqual(idle, self.status.read_bytes())

        self.claim()
        running = self.status.read_bytes()
        forbidden_commands = (
            (
                "handoff",
                "--executor", "Verifier",
                "--note", "不得交接。",
                "--lease-id", LEASE_ID,
            ),
            (
                "lock",
                "--executor", "Verifier",
                "--note", "不得普通锁定。",
                "--lease-id", LEASE_ID,
            ),
            (
                "assert-lease",
                "--executor", "Verifier",
                "--lease-id", LEASE_ID,
                "--resource", "P40 Pro",
            ),
        )
        for command in forbidden_commands:
            with self.subTest(command=command[0]):
                with self.assertRaisesRegex(
                    StatusError, "verifier_operation_forbidden"
                ):
                    self.run_at(*command)
                self.assertEqual(running, self.status.read_bytes())

        malformed = self.status.read_text(encoding="utf-8").replace(
            "| 当前执行者 | Codex |", "| 当前执行者 | Verifier |"
        )
        self.status.write_text(malformed, encoding="utf-8", newline="")
        with self.assertRaisesRegex(StatusError, "verifier_operation_forbidden"):
            self.parsed()

    def test_claim_requires_fresh_128_bit_hex_lease(self) -> None:
        for invalid in ("", "abc", "g" * 32, "0" * 31, "0" * 33):
            with self.subTest(invalid=invalid):
                with self.assertRaisesRegex(StatusError, "lease_id_invalid"):
                    self.run_at(
                        "claim",
                        "--executor", "Codex",
                        "--task", "测试",
                        "--resources", "E-01",
                        "--note", "不会写入",
                        "--lease-id", invalid,
                    )

    def test_running_lock_requires_same_actor_and_same_lease(self) -> None:
        self.claim()
        claimed = self.status.read_bytes()
        for executor, lease, code in (
            ("Claude", LEASE_ID, "lock_executor_mismatch"),
            ("Codex", OTHER_LEASE_ID, "lease_id_mismatch"),
        ):
            with self.subTest(code=code):
                with self.assertRaisesRegex(StatusError, code):
                    self.run_at(
                        "lock",
                        "--executor", executor,
                        "--note", "检测到清理状态不明",
                        "--lease-id", lease,
                    )
                self.assertEqual(claimed, self.status.read_bytes())
        self.assertEqual(
            (RUNNING, LOCKED, "written"),
            self.run_at(
                "lock",
                "--executor", "Codex",
                "--note", "检测到清理状态不明",
                "--lease-id", LEASE_ID,
            ),
        )

    def test_caller_cannot_spoof_reserved_lease_marker(self) -> None:
        original = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "lease_marker_reserved"):
            self.run_at(
                "claim",
                "--executor", "Codex",
                "--task", "测试",
                "--resources", "E-01",
                "--note", f"伪装 [ANEB_LEASE_ID:{OTHER_LEASE_ID}]",
                "--lease-id", LEASE_ID,
            )
        self.assertEqual(original, self.status.read_bytes())

    def test_assert_lease_is_read_only_and_exact(self) -> None:
        self.run_at(
            "claim",
            "--executor", "Codex",
            "--task", "部署后继续 P40 验证",
            "--resources", "E-01、P40 Pro、ANEB Codex",
            "--note", "部署脚本只验证租约，不转换状态",
            "--lease-id", LEASE_ID,
        )
        claimed = self.status.read_bytes()
        self.assertEqual(
            (RUNNING, RUNNING, "verified"),
            self.run_at(
                "assert-lease",
                "--executor", "Codex",
                "--lease-id", LEASE_ID,
                "--resource", "E-01",
            ),
        )
        self.assertEqual(
            (RUNNING, RUNNING, "verified"),
            self.run_at(
                "assert-lease",
                "--executor", "Codex",
                "--lease-id", LEASE_ID,
                "--resource", "P40 Pro",
            ),
        )
        self.assertEqual(claimed, self.status.read_bytes())

        failures = (
            ("Claude", LEASE_ID, "E-01", "lease_executor_mismatch"),
            ("Codex", OTHER_LEASE_ID, "E-01", "lease_id_mismatch"),
            ("Codex", LEASE_ID, "E-010", "lease_resource_mismatch"),
            ("Codex", LEASE_ID, "NO-E-01", "resource_token_negated"),
            ("Codex", LEASE_ID, "E-01-disabled", "resource_token_negated"),
            ("Codex", LEASE_ID, "not E-01", "resource_token_negated"),
        )
        for executor, lease, resource, code in failures:
            with self.subTest(code=code):
                with self.assertRaisesRegex(StatusError, code):
                    self.run_at(
                        "assert-lease",
                        "--executor", executor,
                        "--lease-id", lease,
                        "--resource", resource,
                    )
                self.assertEqual(claimed, self.status.read_bytes())

    def test_resource_list_is_tokenized_and_rejects_empty_or_duplicates(self) -> None:
        for resources, code in (
            ("E-01、E-01", "resource_token_duplicated"),
            ("E-01,,P40 Pro", "resource_token_empty"),
            ("E-01｜P40 Pro", "resource_separator_invalid"),
            ("NO-E-01、P40 Pro", "resource_token_negated"),
            ("E-01-disabled、P40 Pro", "resource_token_negated"),
            ("not E-01、P40 Pro", "resource_token_negated"),
            ("E-01/disabled", "resource_token_negated"),
        ):
            with self.subTest(resources=resources):
                with self.assertRaisesRegex(StatusError, code):
                    self.run_at(
                        "claim",
                        "--executor", "Codex",
                        "--task", "测试资源解析",
                        "--resources", resources,
                        "--note", "不会写入",
                        "--lease-id", LEASE_ID,
                    )

    def test_completed_lease_cannot_be_reused(self) -> None:
        self.claim()
        self.run_at(
            "handoff", "--executor", "Codex", "--note", "清理完成",
            "--lease-id", LEASE_ID,
        )
        self.run_at(
            "review-release",
            "--executor", "Claude",
            "--evidence", "独立复核通过",
        )
        idle = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "lease_id_already_used"):
            self.run_at(
                "claim",
                "--executor", "Codex",
                "--task", "新任务",
                "--resources", "E-01",
                "--note", "错误复用旧 lease",
                "--lease-id", LEASE_ID,
            )
        self.assertEqual(idle, self.status.read_bytes())

    def test_review_release_requires_independent_reviewer_and_writes_evidence(self) -> None:
        self.claim()
        self.run_at(
            "handoff", "--executor", "Codex", "--note", "已退出并清理。",
            "--lease-id", LEASE_ID,
        )
        pending = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "reviewer_must_be_independent"):
            self.run_at(
                "review-release",
                "--executor", "CODEX",
                "--evidence", "T+0/T+10 通过。",
            )
        self.assertEqual(pending, self.status.read_bytes())
        self.assertEqual(
            (HANDOFF, IDLE, "written"),
            self.run_at(
                "review-release",
                "--executor", "Claude",
                "--evidence", "T+0/T+10 均无 PID，tun0=0。",
            ),
        )
        snapshot = self.parsed().snapshot
        self.assertEqual(IDLE, snapshot.state)
        self.assertEqual("无", snapshot.executor)
        self.assertEqual("无", snapshot.task)
        self.assertEqual("无", snapshot.resources)
        self.assertEqual("无", snapshot.started_at)
        self.assertEqual("T+0/T+10 均无 PID，tun0=0。", snapshot.note)

    def test_codex_and_claude_can_review_release_each_other(self) -> None:
        for owner, reviewer in (("Codex", "Claude"), ("Claude", "Codex")):
            with self.subTest(owner=owner, reviewer=reviewer):
                self.status.write_bytes(BASE.replace("\n", "\r\n").encode("utf-8"))
                self.claim(executor=owner)
                self.run_at(
                    "handoff",
                    "--executor", owner,
                    "--note", "已清理，等待复核。",
                    "--lease-id", LEASE_ID,
                )
                self.assertEqual(
                    (HANDOFF, IDLE, "written"),
                    self.run_at(
                        "review-release",
                        "--executor", reviewer,
                        "--evidence", "另一固定角色复核通过。",
                    ),
                )

    def test_verifier_review_release_requires_exact_raw_status_sha(self) -> None:
        self.claim()
        self.run_at(
            "handoff",
            "--executor", "Codex",
            "--note", "已退出并清理。",
            "--lease-id", LEASE_ID,
        )
        pending = self.status.read_bytes()
        raw_sha = hashlib.sha256(pending).hexdigest()
        normalized_sha = hashlib.sha256(pending.replace(b"\r\n", b"\n")).hexdigest()
        self.assertNotEqual(raw_sha, normalized_sha)

        failures = (
            (
                "expected_status_sha256_required",
                (),
            ),
            (
                "expected_status_sha256_invalid",
                ("--expected-status-sha256", "g" * 64),
            ),
            (
                "expected_status_sha256_mismatch",
                ("--expected-status-sha256", normalized_sha),
            ),
        )
        for code, extra in failures:
            with self.subTest(code=code):
                with self.assertRaisesRegex(StatusError, code):
                    self.run_at(
                        "review-release",
                        "--executor", "Verifier",
                        "--evidence", "独立复核通过。",
                        *extra,
                    )
                self.assertEqual(pending, self.status.read_bytes())

        self.status.write_bytes(pending + b"<!-- non-cooperating change -->\r\n")
        changed = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "expected_status_sha256_mismatch"):
            self.run_at(
                "review-release",
                "--executor", "Verifier",
                "--evidence", "不得使用陈旧快照。",
                "--expected-status-sha256", raw_sha,
            )
        self.assertEqual(changed, self.status.read_bytes())

        self.status.write_bytes(pending)
        self.assertEqual(
            (HANDOFF, IDLE, "written"),
            self.run_at(
                "review-release",
                "--executor", "verifier",
                "--evidence", "精确原始字节复核通过。",
                "--expected-status-sha256", raw_sha.upper(),
            ),
        )

    def test_review_sha_is_checked_while_sidecar_lock_is_held(self) -> None:
        self.claim()
        self.run_at(
            "handoff",
            "--executor", "Codex",
            "--note", "已清理，等待复核。",
            "--lease-id", LEASE_ID,
        )
        lock_path = self.status.with_name(
            self.status.name + ".aneb-status.lock"
        )
        observed_lock_state: list[bool] = []
        original = status_module._verify_review_baseline

        def observe(**kwargs):
            observed_lock_state.append(lock_path.is_file())
            return original(**kwargs)

        with mock.patch.object(
            status_module, "_verify_review_baseline", side_effect=observe
        ):
            self.run_at(
                "review-release",
                "--executor", "Verifier",
                "--evidence", "锁内复核通过。",
                "--expected-status-sha256", self.status_sha256(),
            )
        self.assertEqual([True], observed_lock_state)
        self.assertFalse(lock_path.exists())

    def test_review_lock_only_accepts_an_independent_handoff_reviewer(self) -> None:
        idle = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "review_lock_requires_handoff"):
            self.run_at(
                "review-lock",
                "--executor", "Claude",
                "--evidence", "空闲态不能复核锁定。",
            )
        self.assertEqual(idle, self.status.read_bytes())

        self.claim()
        running = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "review_lock_requires_handoff"):
            self.run_at(
                "review-lock",
                "--executor", "Claude",
                "--evidence", "运行态不能复核锁定。",
            )
        self.assertEqual(running, self.status.read_bytes())
        self.run_at(
            "lock",
            "--executor", "Codex",
            "--note", "执行者发现异常。",
            "--lease-id", LEASE_ID,
        )
        locked = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "review_lock_requires_handoff"):
            self.run_at(
                "review-lock",
                "--executor", "Claude",
                "--evidence", "已锁定态不能再次复核锁定。",
            )
        self.assertEqual(locked, self.status.read_bytes())

        self.run_at(
            "handoff",
            "--executor", "Codex",
            "--note", "异常已清理，等待复核。",
            "--lease-id", LEASE_ID,
        )
        pending = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "reviewer_must_be_independent"):
            self.run_at(
                "review-lock",
                "--executor", "Codex",
                "--evidence", "不能自我复核。",
            )
        self.assertEqual(pending, self.status.read_bytes())
        self.assertEqual(
            (HANDOFF, LOCKED, "written"),
            self.run_at(
                "review-lock",
                "--executor", "Claude",
                "--evidence", "发现 VPN 仍活动。",
            ),
        )

    def test_verifier_review_lock_pins_sha_and_preserves_owner_lease(self) -> None:
        self.claim()
        self.run_at(
            "handoff",
            "--executor", "Codex",
            "--note", "已清理，等待独立复核。",
            "--lease-id", LEASE_ID,
        )
        before = self.parsed().snapshot
        pending = self.status.read_bytes()
        pending_sha = self.status_sha256()

        with self.assertRaisesRegex(StatusError, "expected_status_sha256_required"):
            self.run_at(
                "review-lock",
                "--executor", "Verifier",
                "--evidence", "发现残留服务。",
            )
        self.assertEqual(pending, self.status.read_bytes())
        with self.assertRaisesRegex(StatusError, "expected_status_sha256_mismatch"):
            self.run_at(
                "review-lock",
                "--executor", "Verifier",
                "--evidence", "发现残留服务。",
                "--expected-status-sha256", "0" * 64,
            )
        self.assertEqual(pending, self.status.read_bytes())

        self.assertEqual(
            (HANDOFF, LOCKED, "written"),
            self.run_at(
                "review-lock",
                "--executor", "Verifier",
                "--evidence", "发现残留服务与 tun0。",
                "--expected-status-sha256", pending_sha,
            ),
        )
        after = self.parsed().snapshot
        self.assertEqual(LOCKED, after.state)
        self.assertEqual(before.executor, after.executor)
        self.assertEqual(before.task, after.task)
        self.assertEqual(before.resources, after.resources)
        self.assertEqual(before.started_at, after.started_at)
        self.assertEqual(1, after.note.count(f"[ANEB_LEASE_ID:{LEASE_ID}]"))
        self.assertIn("Verifier 独立复核失败", after.note)
        self.assertIn("发现残留服务与 tun0。", after.note)
        text = self.status.read_text(encoding="utf-8")
        self.assertIn("Codex → Verifier", text)

        self.assertEqual(
            (LOCKED, HANDOFF, "written"),
            self.run_at(
                "handoff",
                "--executor", "Codex",
                "--note", "所有残留已清理。",
                "--lease-id", LEASE_ID,
            ),
        )

    def test_every_active_snapshot_requires_exactly_one_lease_marker(self) -> None:
        marker = f"[ANEB_LEASE_ID:{LEASE_ID}]"
        self.claim()
        running = self.status.read_bytes()
        self.run_at(
            "lock",
            "--executor", "Codex",
            "--note", "状态待复核",
            "--lease-id", LEASE_ID,
        )
        locked = self.status.read_bytes()
        self.run_at(
            "handoff",
            "--executor", "Codex",
            "--note", "清理完成",
            "--lease-id", LEASE_ID,
        )
        handoff = self.status.read_bytes()

        cases = (
            (
                "running",
                running,
                (
                    "assert-lease",
                    "--executor", "Codex",
                    "--lease-id", LEASE_ID,
                    "--resource", "P40 Pro",
                ),
            ),
            (
                "locked",
                locked,
                (
                    "handoff",
                    "--executor", "Codex",
                    "--note", "清理完成",
                    "--lease-id", LEASE_ID,
                ),
            ),
            (
                "handoff",
                handoff,
                (
                    "review-release",
                    "--executor", "Claude",
                    "--evidence", "独立复核通过",
                ),
            ),
        )
        for state, valid, command in cases:
            text = valid.decode("utf-8")
            self.assertIn(marker, text)
            variants = (
                ("lease_id_missing", text.replace(marker, "", 1)),
                ("lease_id_ambiguous", text.replace(marker, f"{marker} {marker}", 1)),
            )
            for code, malformed in variants:
                with self.subTest(state=state, code=code):
                    payload = malformed.encode("utf-8")
                    self.status.write_bytes(payload)
                    with self.assertRaisesRegex(StatusError, code):
                        self.run_at(*command)
                    self.assertEqual(payload, self.status.read_bytes())

    def test_lock_is_available_from_idle_and_preserves_fail_closed_ownership(self) -> None:
        self.assertEqual(
            (IDLE, LOCKED, "written"),
            self.run_at(
                "lock",
                "--executor", "Codex",
                "--note", "设备状态无法确认，停止后续测试。",
                "--lease-id", LEASE_ID,
            ),
        )
        snapshot = self.parsed().snapshot
        self.assertEqual(LOCKED, snapshot.state)
        self.assertEqual("Codex", snapshot.executor)
        self.assertIn("触发异常锁定", snapshot.note)

    def test_locked_owner_can_clean_then_handoff_for_independent_release(self) -> None:
        self.run_at(
            "lock",
            "--executor", "Codex",
            "--note", "VPN 状态无法确认。",
            "--lease-id", LEASE_ID,
        )
        self.assertEqual(
            (LOCKED, HANDOFF, "written"),
            self.run_at(
                "handoff",
                "--executor", "Codex",
                "--note", "已停止 VPN，T+0/T+10 均无 tun0。",
                "--lease-id", LEASE_ID,
            ),
        )
        pending_text = self.status.read_text(encoding="utf-8")
        self.assertIn("异常已处理并清理；待独立复核", pending_text)
        self.assertEqual(
            (HANDOFF, IDLE, "written"),
            self.run_at(
                "review-release",
                "--executor", "Claude",
                "--evidence", "独立复核无活动 VPN，设备已释放。",
            ),
        )
        self.assertEqual(IDLE, self.parsed().snapshot.state)

    def test_dry_run_validates_but_does_not_write_or_echo_fields(self) -> None:
        original = self.status.read_bytes()
        stdout = io.StringIO()
        stderr = io.StringIO()
        secret_like_but_noncredential = "内部代号只用于不回显验证"
        rc = main(
            [
                "--status-file", str(self.status),
                "--dry-run",
                "claim",
                "--executor", "Codex",
                "--task", secret_like_but_noncredential,
                "--resources", "P40 Pro",
                "--note", "不写文件",
                "--lease-id", LEASE_ID,
            ],
            stdout=stdout,
            stderr=stderr,
        )
        self.assertEqual(0, rc)
        self.assertEqual(original, self.status.read_bytes())
        combined = stdout.getvalue() + stderr.getvalue()
        self.assertNotIn(secret_like_but_noncredential, combined)
        self.assertIn("mode=dry-run", combined)
        self.assertIn("transition=idle->running", combined)

    def test_sensitive_input_is_rejected_without_echo_or_write(self) -> None:
        original = self.status.read_bytes()
        credential = "ghp_" + "x" * 36
        stdout = io.StringIO()
        stderr = io.StringIO()
        rc = main(
            [
                "--status-file", str(self.status),
                "claim",
                "--executor", "Codex",
                "--task", "测试",
                "--resources", "P40 Pro",
                "--note", credential,
                "--lease-id", LEASE_ID,
            ],
            stdout=stdout,
            stderr=stderr,
        )
        self.assertEqual(2, rc)
        self.assertEqual(original, self.status.read_bytes())
        combined = stdout.getvalue() + stderr.getvalue()
        self.assertNotIn(credential, combined)
        self.assertIn("sensitive_input_rejected", combined)

    def test_malformed_or_ambiguous_document_fails_without_write(self) -> None:
        malformed = self.status.read_text(encoding="utf-8").replace(
            "| 状态 | **空闲** |", "| 状态 | **未知** |"
        )
        self.status.write_text(malformed, encoding="utf-8", newline="")
        original = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "status_value_unknown"):
            self.run_at(
                "claim",
                "--executor", "Codex",
                "--task", "测试",
                "--resources", "P40 Pro",
                "--note", "不会写入",
                "--lease-id", LEASE_ID,
            )
        self.assertEqual(original, self.status.read_bytes())

    def test_existing_sidecar_lock_rejects_concurrent_writer(self) -> None:
        original = self.status.read_bytes()
        lock = self.status.with_name(self.status.name + ".aneb-status.lock")
        lock.write_text("held", encoding="ascii")
        try:
            with self.assertRaisesRegex(StatusError, "status_file_locked"):
                self.run_at(
                    "claim",
                    "--executor", "Codex",
                    "--task", "测试",
                    "--resources", "P40 Pro",
                    "--note", "不会写入",
                    "--lease-id", LEASE_ID,
                )
        finally:
            lock.unlink()
        self.assertEqual(original, self.status.read_bytes())

    def test_atomic_writer_detects_noncooperating_change(self) -> None:
        baseline = self.status.read_bytes()
        external = baseline + b"<!-- external -->\r\n"
        self.status.write_bytes(external)
        with self.assertRaisesRegex(StatusError, "concurrent_status_change"):
            atomic_replace_if_unchanged(self.status, baseline, b"replacement")
        self.assertEqual(external, self.status.read_bytes())

    def test_encoding_bom_crlf_and_prior_history_are_preserved(self) -> None:
        prior = self.status.read_bytes()
        self.status.write_bytes(b"\xef\xbb\xbf" + prior)
        self.claim()
        result = self.status.read_bytes()
        self.assertTrue(result.startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(b"\n", result.replace(b"\r\n", b""))
        text = result.decode("utf-8-sig")
        self.assertIn("| 2026-07-18 16:43 | Claude → Codex | 旧任务 |", text)
        self.assertIn("| 2026-07-18 17:05 | Codex | 验证 ANEB 包 |", text)

    def test_illegal_second_claim_does_not_mutate_file(self) -> None:
        self.claim()
        claimed = self.status.read_bytes()
        with self.assertRaisesRegex(StatusError, "claim_requires_idle"):
            self.claim(executor="Claude")
        self.assertEqual(claimed, self.status.read_bytes())


if __name__ == "__main__":
    unittest.main()
