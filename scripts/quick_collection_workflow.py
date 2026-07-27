#!/usr/bin/env python3
"""Shared fail-closed lifecycle for bounded Quick evidence collectors.

This module contains no phone, server, profile, or publication semantics.  A
category-specific backend owns those contracts.  Importing it has no external
side effects.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class WorkflowResult:
    success: bool
    primary_failure: str | None
    cleanup_failures: tuple[str, ...]
    publish_failure: str | None


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

    primary_failure: str | None = None
    cleanup_failures: list[str] = []
    publish_failure: str | None = None
    collected = False
    preflighted = False
    try:
        backend.preflight()
        preflighted = True
        backend.acquire()
        backend.collect()
        collected = True
    except BaseException as error:
        primary_failure = _error_text(error)
    finally:
        if preflighted:
            for cleanup in (backend.cleanup_phone, backend.cleanup_remote):
                try:
                    cleanup()
                except BaseException as error:
                    cleanup_failures.append(_error_text(error))
    if collected and primary_failure is None and not cleanup_failures:
        try:
            backend.publish()
        except BaseException as error:
            publish_failure = _error_text(error)
    return WorkflowResult(
        success=(
            collected
            and primary_failure is None
            and not cleanup_failures
            and publish_failure is None
        ),
        primary_failure=primary_failure,
        cleanup_failures=tuple(cleanup_failures),
        publish_failure=publish_failure,
    )


__all__ = ("WorkflowBackend", "WorkflowResult", "run_workflow")
