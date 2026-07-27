#!/usr/bin/env python3
"""Strict byte adapter for the shared Quick collection WorkflowTrace."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.quick_collection_workflow import (
    CollectorError,
    WorkflowTraceEvent,
    evaluate_trace,
)


TRACE_SCHEMA = "aneb-quick-workflow-trace@1.0.0"
DECISION_SCHEMA = "aneb-quick-workflow-decision@1.0.0"
CLI_ERROR_SCHEMA = "aneb-quick-workflow-cli-error@1.0.0"
MAX_TRACE_BYTES = 64 * 1024


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate_json_key")
        value[key] = item
    return value


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _load_document(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) > MAX_TRACE_BYTES:
        raise CollectorError("workflow_trace_document_invalid")
    try:
        value = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_unique_object,
            parse_constant=lambda item: (_ for _ in ()).throw(
                ValueError(item)
            ),
        )
    except (UnicodeDecodeError, ValueError, TypeError) as error:
        raise CollectorError("workflow_trace_document_invalid") from error
    if not isinstance(value, dict):
        raise CollectorError("workflow_trace_document_invalid")
    return value


def evaluate_trace_document(raw: bytes) -> bytes:
    document = _load_document(raw)
    if set(document) != {"schema", "events"}:
        raise CollectorError("workflow_trace_document_invalid")
    if document["schema"] != TRACE_SCHEMA:
        raise CollectorError("workflow_trace_document_invalid")
    raw_events = document["events"]
    if not isinstance(raw_events, list):
        raise CollectorError("workflow_trace_document_invalid")

    events: list[WorkflowTraceEvent] = []
    for raw_event in raw_events:
        if not isinstance(raw_event, dict):
            raise CollectorError("workflow_trace_document_invalid")
        if set(raw_event) not in (
            {"phase", "outcome"},
            {"phase", "outcome", "failure"},
        ):
            raise CollectorError("workflow_trace_document_invalid")
        phase = raw_event.get("phase")
        outcome = raw_event.get("outcome")
        failure = raw_event.get("failure")
        if not isinstance(phase, str) or not isinstance(outcome, str):
            raise CollectorError("workflow_trace_document_invalid")
        if failure is not None and not isinstance(failure, str):
            raise CollectorError("workflow_trace_document_invalid")
        events.append(WorkflowTraceEvent(phase, outcome, failure))

    decision = evaluate_trace(tuple(events))
    return _canonical_json(
        {
            "schema": DECISION_SCHEMA,
            "publish_eligible": decision.publish_eligible,
            "primary_failure": decision.primary_failure,
            "cleanup_failures": list(decision.cleanup_failures),
        }
    )


def _emit_cli_error(reason_code: str) -> int:
    sys.stderr.buffer.write(
        _canonical_json(
            {
                "schema": CLI_ERROR_SCHEMA,
                "reason_code": reason_code,
            }
        )
    )
    return 1


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    if len(arguments) != 1:
        return _emit_cli_error("workflow_trace_usage_invalid")
    try:
        raw = Path(arguments[0]).read_bytes()
        decision = evaluate_trace_document(raw)
    except CollectorError as error:
        return _emit_cli_error(_error_reason(error))
    except OSError:
        return _emit_cli_error("workflow_trace_io_failed")
    sys.stdout.buffer.write(decision)
    return 0


def _error_reason(error: CollectorError) -> str:
    reason = str(error).strip()
    return reason if reason else "workflow_trace_invalid"


__all__ = (
    "CLI_ERROR_SCHEMA",
    "DECISION_SCHEMA",
    "MAX_TRACE_BYTES",
    "TRACE_SCHEMA",
    "evaluate_trace_document",
    "main",
)


if __name__ == "__main__":
    raise SystemExit(main())
