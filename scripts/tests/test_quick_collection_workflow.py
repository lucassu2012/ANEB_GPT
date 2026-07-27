from __future__ import annotations

import unittest

from scripts.quick_collection_workflow import WorkflowResult, run_workflow


class FakeBackend:
    def __init__(self, failures: dict[str, str] | None = None) -> None:
        self.failures = failures or {}
        self.calls: list[str] = []

    def _call(self, name: str) -> None:
        self.calls.append(name)
        if name in self.failures:
            raise RuntimeError(self.failures[name])

    def preflight(self) -> None:
        self._call("preflight")

    def acquire(self) -> None:
        self._call("acquire")

    def collect(self) -> None:
        self._call("collect")

    def cleanup_phone(self) -> None:
        self._call("cleanup_phone")

    def cleanup_remote(self) -> None:
        self._call("cleanup_remote")

    def publish(self) -> None:
        self._call("publish")


class QuickCollectionWorkflowTest(unittest.TestCase):
    def test_success_runs_cleanup_before_publish(self) -> None:
        backend = FakeBackend()
        result = run_workflow(backend)
        self.assertEqual(
            [
                "preflight",
                "acquire",
                "collect",
                "cleanup_phone",
                "cleanup_remote",
                "publish",
            ],
            backend.calls,
        )
        self.assertEqual(WorkflowResult(True, None, (), None), result)

    def test_preflight_failure_has_no_cleanup_or_publish(self) -> None:
        backend = FakeBackend({"preflight": "live_state_dirty"})
        result = run_workflow(backend)
        self.assertEqual(["preflight"], backend.calls)
        self.assertEqual("live_state_dirty", result.primary_failure)
        self.assertFalse(result.success)

    def test_collect_failure_still_runs_both_cleanups(self) -> None:
        backend = FakeBackend({"collect": "terminal_timeout"})
        result = run_workflow(backend)
        self.assertEqual(
            [
                "preflight",
                "acquire",
                "collect",
                "cleanup_phone",
                "cleanup_remote",
            ],
            backend.calls,
        )
        self.assertEqual("terminal_timeout", result.primary_failure)
        self.assertIsNone(result.publish_failure)

    def test_cleanup_failures_are_all_retained_and_suppress_publish(self) -> None:
        backend = FakeBackend(
            {
                "cleanup_phone": "phone_cleanup_failed",
                "cleanup_remote": "remote_cleanup_failed",
            }
        )
        result = run_workflow(backend)
        self.assertEqual(
            ("phone_cleanup_failed", "remote_cleanup_failed"),
            result.cleanup_failures,
        )
        self.assertNotIn("publish", backend.calls)
        self.assertFalse(result.success)

    def test_publish_failure_is_reported_after_clean_cleanup(self) -> None:
        backend = FakeBackend({"publish": "ready_collision"})
        result = run_workflow(backend)
        self.assertEqual("ready_collision", result.publish_failure)
        self.assertIsNone(result.primary_failure)
        self.assertEqual((), result.cleanup_failures)
        self.assertFalse(result.success)

    def test_empty_exception_uses_class_name(self) -> None:
        class EmptyError(RuntimeError):
            pass

        backend = FakeBackend()

        def fail_collect() -> None:
            backend.calls.append("collect")
            raise EmptyError()

        backend.collect = fail_collect  # type: ignore[method-assign]
        result = run_workflow(backend)
        self.assertEqual("EmptyError", result.primary_failure)


if __name__ == "__main__":
    unittest.main()
