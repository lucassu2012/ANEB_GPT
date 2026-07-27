#!/usr/bin/env python3
"""Shared fail-closed lifecycle for bounded Quick evidence collectors.

This module contains no phone, server, profile, or publication semantics.  A
category-specific backend owns those contracts.  Importing it has no external
side effects.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


class CollectorError(RuntimeError):
    """A bounded Quick collection contract rejected the current state."""


@dataclass(frozen=True)
class WorkflowResult:
    success: bool
    primary_failure: str | None
    cleanup_failures: tuple[str, ...]
    publish_failure: str | None


@dataclass(frozen=True)
class WorkflowTraceEvent:
    phase: str
    outcome: str
    failure: str | None = None


@dataclass(frozen=True)
class WorkflowTraceDecision:
    publish_eligible: bool
    primary_failure: str | None
    cleanup_failures: tuple[str, ...]


_SUCCESS_PHASES = (
    "preflight",
    "acquire",
    "collect",
    "cleanup_phone",
    "cleanup_remote",
)


def evaluate_trace(
    events: tuple[WorkflowTraceEvent, ...],
) -> WorkflowTraceDecision:
    if tuple(event.phase for event in events) != _SUCCESS_PHASES:
        raise CollectorError("workflow_trace_invalid")

    for event in events:
        if event.outcome not in {"pass", "fail", "skip"}:
            raise CollectorError("workflow_trace_invalid")
        if event.outcome == "fail":
            if not isinstance(event.failure, str) or not event.failure.strip():
                raise CollectorError("workflow_trace_invalid")
        elif event.failure is not None:
            raise CollectorError("workflow_trace_invalid")

    primary_outcomes = tuple(event.outcome for event in events[:3])
    if primary_outcomes not in {
        ("pass", "pass", "pass"),
        ("pass", "pass", "fail"),
        ("pass", "fail", "skip"),
        ("fail", "skip", "skip"),
    }:
        raise CollectorError("workflow_trace_invalid")

    cleanup_outcomes = tuple(event.outcome for event in events[3:])
    if any(outcome == "skip" for outcome in cleanup_outcomes) and not (
        primary_outcomes[0] == "fail"
        and cleanup_outcomes == ("skip", "skip")
    ):
        raise CollectorError("workflow_trace_invalid")

    primary_failure = next(
        (
            event.failure
            for event in events[:3]
            if event.outcome == "fail"
        ),
        None,
    )
    cleanup_failures = tuple(
        event.failure
        for event in events[3:]
        if event.outcome == "fail" and event.failure is not None
    )
    return WorkflowTraceDecision(
        publish_eligible=(
            primary_failure is None and not cleanup_failures
        ),
        primary_failure=primary_failure,
        cleanup_failures=cleanup_failures,
    )


class WorkflowBackend(Protocol):
    def preflight(self) -> None: ...

    def acquire(self) -> None: ...

    def collect(self) -> None: ...

    def cleanup_phone(self) -> None: ...

    def cleanup_remote(self) -> None: ...

    def publish(self) -> None: ...


def _error_text(error: BaseException) -> str:
    value = str(error).strip()
    return value if value else error.__class__.__name__


def run_workflow(backend: WorkflowBackend) -> WorkflowResult:
    """Run one bounded collection and never publish before both cleanups pass."""

    events: list[WorkflowTraceEvent] = []
    publish_failure: str | None = None
    preflighted = False
    try:
        try:
            backend.preflight()
        except BaseException as error:
            events.extend(
                (
                    WorkflowTraceEvent(
                        "preflight",
                        "fail",
                        _error_text(error),
                    ),
                    WorkflowTraceEvent("acquire", "skip"),
                    WorkflowTraceEvent("collect", "skip"),
                )
            )
        else:
            events.append(WorkflowTraceEvent("preflight", "pass"))
            preflighted = True
            try:
                backend.acquire()
            except BaseException as error:
                events.extend(
                    (
                        WorkflowTraceEvent(
                            "acquire",
                            "fail",
                            _error_text(error),
                        ),
                        WorkflowTraceEvent("collect", "skip"),
                    )
                )
            else:
                events.append(WorkflowTraceEvent("acquire", "pass"))
                try:
                    backend.collect()
                except BaseException as error:
                    events.append(
                        WorkflowTraceEvent(
                            "collect",
                            "fail",
                            _error_text(error),
                        )
                    )
                else:
                    events.append(WorkflowTraceEvent("collect", "pass"))
    finally:
        if preflighted:
            for phase, cleanup in (
                ("cleanup_phone", backend.cleanup_phone),
                ("cleanup_remote", backend.cleanup_remote),
            ):
                try:
                    cleanup()
                except BaseException as error:
                    events.append(
                        WorkflowTraceEvent(
                            phase,
                            "fail",
                            _error_text(error),
                        )
                    )
                else:
                    events.append(WorkflowTraceEvent(phase, "pass"))
        else:
            events.extend(
                (
                    WorkflowTraceEvent("cleanup_phone", "skip"),
                    WorkflowTraceEvent("cleanup_remote", "skip"),
                )
            )

    decision = evaluate_trace(tuple(events))
    if decision.publish_eligible:
        try:
            backend.publish()
        except BaseException as error:
            publish_failure = _error_text(error)
    return WorkflowResult(
        success=(
            decision.publish_eligible and publish_failure is None
        ),
        primary_failure=decision.primary_failure,
        cleanup_failures=decision.cleanup_failures,
        publish_failure=publish_failure,
    )


__all__ = (
    "CollectorError",
    "WorkflowBackend",
    "WorkflowResult",
    "WorkflowTraceDecision",
    "WorkflowTraceEvent",
    "evaluate_trace",
    "run_workflow",
)
