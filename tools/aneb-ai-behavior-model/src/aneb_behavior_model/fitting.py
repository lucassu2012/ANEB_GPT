"""Fit auditable empirical and three-state token interval models."""

from __future__ import annotations

from collections import defaultdict
from copy import deepcopy
from typing import Any

from .statistics import normalize_weights

STATES = ("FAST", "NORMAL", "PAUSE")


def interval_state(interval_ms: float) -> str:
    if interval_ms <= 25:
        return "FAST"
    if interval_ms <= 200:
        return "NORMAL"
    return "PAUSE"


def fit_token_interval_markov(
    sessions: list[list[float]],
    *,
    laplace_alpha: float = 1.0,
) -> dict[str, Any]:
    state_values: dict[str, list[float]] = {state: [] for state in STATES}
    starts = {state: 0.0 for state in STATES}
    transitions = {state: {target: 0.0 for target in STATES} for state in STATES}

    for intervals in sessions:
        cleaned = [float(value) for value in intervals if float(value) >= 0]
        if not cleaned:
            continue
        classified = [interval_state(value) for value in cleaned]
        starts[classified[0]] += 1
        for value, state in zip(cleaned, classified):
            state_values[state].append(value)
        for current, target in zip(classified, classified[1:]):
            transitions[current][target] += 1

    all_values = [value for values in state_values.values() for value in values]
    if not all_values:
        raise ValueError("at least one non-negative token interval is required")
    defaults = {
        "FAST": [min(all_values)],
        "NORMAL": [sorted(all_values)[len(all_values) // 2]],
        "PAUSE": [max(all_values)],
    }
    return {
        "type": "markov_empirical",
        "state_definition": {"FAST": "<=25ms", "NORMAL": "25..200ms", "PAUSE": ">200ms"},
        "states": {
            state: {
                "type": "empirical",
                "values": state_values[state] or defaults[state],
            }
            for state in STATES
        },
        "start_probabilities": normalize_weights(starts, alpha=laplace_alpha),
        "transition_probabilities": {
            state: normalize_weights(row, alpha=laplace_alpha)
            for state, row in transitions.items()
        },
    }


def fit_token_model(
    observations: list[dict[str, Any]],
    template: dict[str, Any],
    *,
    calibration_source: dict[str, Any],
    candidate_version: str,
) -> dict[str, Any]:
    """Fit a calibrated token model from session-level JSON observations.

    Expected observation fields:
      workload_kind, payload_bytes, processing_delay_ms, output_token_count,
      token_intervals_ms. Unknown fields are ignored.
    """

    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in observations:
        grouped[str(row["workload_kind"])].append(row)
    if not grouped:
        raise ValueError("no observations")

    model = deepcopy(template)
    model["model_version"] = candidate_version
    model["status"] = "calibrated"
    model["source"] = deepcopy(calibration_source)
    workloads_by_kind = {
        workload["kind"]: workload for workload in model["generation"]["workloads"]
    }
    missing = sorted(set(grouped) - workloads_by_kind.keys())
    if missing:
        raise ValueError(f"template does not define workload kinds: {missing}")
    for kind, rows in grouped.items():
        workload = workloads_by_kind[kind]
        workload["payload_bytes"] = _empirical(rows, "payload_bytes")
        workload["processing_delay_ms"] = _empirical(rows, "processing_delay_ms")
        workload["output_token_count"] = _empirical(rows, "output_token_count")
        workload["token_interval_model"] = fit_token_interval_markov(
            [list(map(float, row["token_intervals_ms"])) for row in rows]
        )
    return model


def _empirical(rows: list[dict[str, Any]], field: str) -> dict[str, Any]:
    values = [float(row[field]) for row in rows if field in row]
    if not values:
        raise ValueError(f"observation field {field} is empty")
    return {"type": "empirical", "values": values}
