"""Small dependency-free statistical helpers used by fitting and QA."""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence


def quantile(values: Sequence[float], probability: float) -> float | None:
    if not values:
        return None
    if not 0 <= probability <= 1:
        raise ValueError("probability must be in [0, 1]")
    ordered = sorted(float(value) for value in values)
    if len(ordered) == 1:
        return ordered[0]
    position = probability * (len(ordered) - 1)
    lower = int(math.floor(position))
    upper = int(math.ceil(position))
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def describe(values: Iterable[float]) -> dict[str, float | int | None]:
    sample = [float(value) for value in values]
    if not sample:
        return {"count": 0, "p05": None, "p50": None, "p95": None, "mean": None}
    return {
        "count": len(sample),
        "p05": quantile(sample, 0.05),
        "p50": quantile(sample, 0.50),
        "p95": quantile(sample, 0.95),
        "mean": sum(sample) / len(sample),
    }


def relative_error(actual: float | None, expected: float | None) -> float | None:
    if actual is None or expected is None:
        return None
    if expected == 0:
        return 0.0 if actual == 0 else None
    return abs(actual - expected) / abs(expected)


def normalize_weights(counts: dict[str, float], alpha: float = 0.0) -> dict[str, float]:
    adjusted = {key: max(0.0, float(value)) + alpha for key, value in counts.items()}
    total = sum(adjusted.values())
    if total <= 0:
        if not adjusted:
            return {}
        uniform = 1.0 / len(adjusted)
        return {key: uniform for key in adjusted}
    return {key: value / total for key, value in adjusted.items()}


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float] | None:
    if total <= 0 or successes < 0 or successes > total:
        return None
    proportion = successes / total
    denominator = 1 + z * z / total
    centre = proportion + z * z / (2 * total)
    margin = z * math.sqrt(
        proportion * (1 - proportion) / total + z * z / (4 * total * total)
    )
    return ((centre - margin) / denominator, (centre + margin) / denominator)

