#!/usr/bin/env python3
"""Pure-stdlib correlation & sensitivity analysis for Profile 4 voice calibration (R, B1, B2).

Zero third-party dependencies (Python 3.12+ stdlib only) so CI `behavior-model` runs
cleanly without pip installs. Replicates exact `AqsScorer.kt` `WEIGHTS_VOICE_SIM_V02`
anchors, M1 > 400 ms veto (`T4_VETO_CAP = 54.0`), and R-10 `KPI_MISSING` semantics.
"""

from __future__ import annotations

import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path
import sys
from typing import Any


INPUT_SCHEMA_VERSION = "voice-correlation-input-1"
OUTPUT_SCHEMA_VERSION = "voice-correlation-summary-1"
AQS_VERSION_VOICE_SIM_V02 = "aqs-voice-sim-v0.2"
M1_VETO_THRESHOLD_MS = 400.0
T4_VETO_CAP = 54.0

WEIGHTS_VOICE_SIM_V02: dict[str, float] = {
    "M1": 0.20,
    "M2": 0.12,
    "M3": 0.08,
    "M4": 0.15,
    "M5": 0.10,
    "M6": 0.10,
    "M7": 0.05,
    "N1": 0.10,
    "N2": 0.10,
}

SIM_KPI_FIELD_MAP: dict[str, str] = {
    "M1": "mouth_ear_proxy_ms",
    "M2": "down_net_jitter_ms",
    "M3": "up_frame_jitter_ms",
    "M4": "ttfb_p50_ms",
    "M5": "turn_switch_p50_ms",
    "M6": "barge_stop_max_ms",
    "M7": "m7_max_frame_gap_ms",
    "N1": "rtt_ms",
    "N2": "jitter_ms",
}

ANCHOR_MAPS: dict[str, list[tuple[float, float]]] = {
    "N1": [(0.0, 100.0), (30.0, 85.0), (60.0, 70.0), (100.0, 55.0), (300.0, 0.0)],
    "N2": [(0.0, 100.0), (10.0, 85.0), (30.0, 70.0), (80.0, 55.0), (240.0, 0.0)],
    "M1": [(0.0, 100.0), (150.0, 85.0), (300.0, 70.0), (400.0, 55.0), (1200.0, 0.0)],
    "M2": [(0.0, 100.0), (10.0, 85.0), (30.0, 70.0), (80.0, 55.0), (240.0, 0.0)],
    "M3": [(0.0, 100.0), (10.0, 85.0), (30.0, 70.0), (80.0, 55.0), (240.0, 0.0)],
    "M4": [(0.0, 100.0), (100.0, 85.0), (250.0, 70.0), (500.0, 55.0), (1500.0, 0.0)],
    "M5": [(0.0, 100.0), (150.0, 85.0), (300.0, 70.0), (600.0, 55.0), (2000.0, 0.0)],
    "M6": [(0.0, 100.0), (80.0, 85.0), (150.0, 70.0), (250.0, 55.0), (1000.0, 0.0)],
    "M7": [(0.0, 100.0), (60.0, 85.0), (150.0, 70.0), (400.0, 40.0), (1000.0, 0.0)],
}

TRACKED_SIM_NUMERIC_FIELDS: tuple[str, ...] = (
    "rtt_ms",
    "jitter_ms",
    "up_frame_jitter_ms",
    "down_net_jitter_ms",
    "mouth_ear_proxy_ms",
    "ttfb_p50_ms",
    "ttfb_p95_ms",
    "turn_switch_p50_ms",
    "barge_stop_max_ms",
    "m7_max_frame_gap_ms",
    "voice_near_zero_arrival_ratio",
)


def _is_finite_number(val: Any) -> bool:
    return isinstance(val, (int, float)) and not isinstance(val, bool) and math.isfinite(float(val))


def _assert_no_nonfinite(obj: Any, path: str = "$") -> None:
    if isinstance(obj, bool) or obj is None or isinstance(obj, str):
        return
    if isinstance(obj, (int, float)):
        if not math.isfinite(float(obj)):
            raise ValueError(f"Non-finite number at {path}: {obj}")
        return
    if isinstance(obj, list):
        for idx, item in enumerate(obj):
            _assert_no_nonfinite(item, f"{path}[{idx}]")
        return
    if isinstance(obj, dict):
        for k, v in obj.items():
            _assert_no_nonfinite(v, f"{path}.{k}")
        return
    raise ValueError(f"Unsupported JSON type at {path}: {type(obj).__name__}")


def anchor_score(anchors: list[tuple[float, float]], value: float) -> float:
    if not _is_finite_number(value):
        raise ValueError(f"Anchor input must be finite number, got {value!r}")
    v = float(value)
    if v <= anchors[0][0]:
        return anchors[0][1]
    if v >= anchors[-1][0]:
        return anchors[-1][1]
    for i in range(1, len(anchors)):
        x0, y0 = anchors[i - 1]
        x1, y1 = anchors[i]
        if v <= x1:
            return y0 + (v - x0) / (x1 - x0) * (y1 - y0)
    return anchors[-1][1]


def score_voice_sim_v02(
    sim_record: dict[str, Any],
    weights: dict[str, float] | None = None,
    apply_m1_veto: bool = True,
) -> dict[str, Any]:
    active_weights = WEIGHTS_VOICE_SIM_V02 if weights is None else weights
    weight_sum = sum(active_weights.values())
    if not math.isclose(weight_sum, 1.0, rel_tol=0.0, abs_tol=1e-6):
        raise ValueError(f"Weights must sum to 1.0, got {weight_sum}")

    missing: list[str] = []
    values: dict[str, float] = {}
    for kpi in sorted(active_weights.keys()):
        if kpi not in SIM_KPI_FIELD_MAP:
            raise ValueError(f"Unsupported KPI key: {kpi}")
        field = SIM_KPI_FIELD_MAP[kpi]
        raw_val = sim_record.get(field)
        if raw_val is None:
            missing.append(kpi)
        elif not _is_finite_number(raw_val):
            raise ValueError(f"Non-finite or invalid value for {field}: {raw_val!r}")
        else:
            values[kpi] = float(raw_val)

    if missing:
        return {
            "aqs_version": AQS_VERSION_VOICE_SIM_V02,
            "score": None,
            "raw_weighted_score": None,
            "sub_scores": {},
            "veto_applied": False,
            "low_confidence": bool(sim_record.get("low_confidence", False)),
            "not_computable_reason": "KPI_MISSING:" + ",".join(sorted(missing)),
        }

    sub_scores = {
        kpi: anchor_score(ANCHOR_MAPS[kpi], val)
        for kpi, val in values.items()
    }
    raw_total = sum(sub_scores[kpi] * active_weights[kpi] for kpi in active_weights)
    m1_val = sim_record.get(SIM_KPI_FIELD_MAP["M1"])
    veto_applied = bool(
        apply_m1_veto
        and _is_finite_number(m1_val)
        and float(m1_val) > M1_VETO_THRESHOLD_MS
    )
    final_score = min(raw_total, T4_VETO_CAP) if veto_applied else raw_total
    return {
        "aqs_version": AQS_VERSION_VOICE_SIM_V02,
        "score": final_score,
        "raw_weighted_score": raw_total,
        "sub_scores": sub_scores,
        "veto_applied": veto_applied,
        "low_confidence": bool(sim_record.get("low_confidence", False)),
        "not_computable_reason": None,
    }


def _average_ranks(vals: list[float]) -> list[float]:
    n = len(vals)
    indexed = sorted(enumerate(vals), key=lambda p: p[1])
    ranks = [0.0] * n
    i = 0
    while i < n:
        j = i + 1
        while j < n and indexed[j][1] == indexed[i][1]:
            j += 1
        avg_rank = (i + 1 + j) / 2.0
        for k in range(i, j):
            ranks[indexed[k][0]] = avg_rank
        i = j
    return ranks


def _paired_finite(xs: list[float | None], ys: list[float | None]) -> tuple[list[float], list[float]]:
    if len(xs) != len(ys):
        raise ValueError(f"Length mismatch: {len(xs)} != {len(ys)}")
    px: list[float] = []
    py: list[float] = []
    for x, y in zip(xs, ys):
        if x is None or y is None:
            continue
        if not (_is_finite_number(x) and _is_finite_number(y)):
            raise ValueError(f"Non-finite input pair: ({x!r}, {y!r})")
        px.append(float(x))
        py.append(float(y))
    return px, py


def spearman_rho(xs: list[float | None], ys: list[float | None]) -> float | None:
    px, py = _paired_finite(xs, ys)
    n = len(px)
    if n < 2:
        return None
    rx = _average_ranks(px)
    ry = _average_ranks(py)
    mx = sum(rx) / n
    my = sum(ry) / n
    dx = [a - mx for a in rx]
    dy = [b - my for b in ry]
    var_x = sum(a * a for a in dx)
    var_y = sum(b * b for b in dy)
    if var_x <= 0.0 or var_y <= 0.0:
        return None
    cov = sum(a * b for a, b in zip(dx, dy))
    val = cov / math.sqrt(var_x * var_y)
    return max(-1.0, min(1.0, val))


def kendall_tau_b(xs: list[float | None], ys: list[float | None]) -> float | None:
    px, py = _paired_finite(xs, ys)
    n = len(px)
    if n < 2:
        return None
    concordant = 0
    discordant = 0
    ties_x = 0
    ties_y = 0
    for i in range(n):
        for j in range(i + 1, n):
            dx = px[i] - px[j]
            dy = py[i] - py[j]
            if dx == 0.0 and dy == 0.0:
                ties_x += 1
                ties_y += 1
            elif dx == 0.0:
                ties_x += 1
            elif dy == 0.0:
                ties_y += 1
            elif dx * dy > 0.0:
                concordant += 1
            else:
                discordant += 1
    n0 = n * (n - 1) // 2
    denom_x = n0 - ties_x
    denom_y = n0 - ties_y
    if denom_x <= 0 or denom_y <= 0:
        return None
    val = (concordant - discordant) / math.sqrt(denom_x * denom_y)
    return max(-1.0, min(1.0, val))


def _ratio_and_delta(val: float | None, base: float | None) -> dict[str, float | None]:
    if val is None or base is None:
        return {"value": val, "baseline": base, "delta": None, "ratio": None}
    delta = val - base
    ratio = (val / base) if base > 0.0 else None
    return {"value": val, "baseline": base, "delta": delta, "ratio": ratio}


def _extract_voice_sim_record(entry: dict[str, Any]) -> dict[str, Any]:
    vs = entry.get("voice_sim")
    if not isinstance(vs, dict):
        raise ValueError(f"Condition {entry.get('condition_id')} missing voice_sim object")
    if "records" in vs and isinstance(vs["records"], list):
        if not vs["records"]:
            raise ValueError(f"Condition {entry.get('condition_id')} voice_sim.records is empty")
        rec = vs["records"][0]
        if not isinstance(rec, dict):
            raise ValueError("voice_sim.records[0] must be object")
        return rec
    return vs


def directional_ratios(conditions: list[dict[str, Any]]) -> dict[str, Any]:
    by_id = {c["condition_id"]: c for c in conditions}
    if "R" not in by_id:
        raise ValueError("Baseline condition 'R' is required")
    ref = by_id["R"]
    ref_sim = ref["voice_sim_record"]
    ref_score = ref["aqs_v02"]["score"]
    ref_app = ref["real_voice_app"].get("observable_metrics", {})

    app_metric_keys = sorted(
        {
            k
            for c in conditions
            for k, v in c["real_voice_app"].get("observable_metrics", {}).items()
            if v is None or _is_finite_number(v)
        }
    )

    per_condition: dict[str, Any] = {}
    for cid in [c["condition_id"] for c in conditions]:
        if cid == "R":
            continue
        cur = by_id[cid]
        cur_sim = cur["voice_sim_record"]
        cur_app = cur["real_voice_app"].get("observable_metrics", {})
        sim_metrics = {}
        for field in TRACKED_SIM_NUMERIC_FIELDS:
            v = cur_sim.get(field)
            b = ref_sim.get(field)
            sim_metrics[field] = _ratio_and_delta(
                float(v) if _is_finite_number(v) else None,
                float(b) if _is_finite_number(b) else None,
            )
        sim_metrics["aqs_sim_v02"] = _ratio_and_delta(
            float(cur["aqs_v02"]["score"]) if _is_finite_number(cur["aqs_v02"]["score"]) else None,
            float(ref_score) if _is_finite_number(ref_score) else None,
        )
        app_metrics = {}
        for key in app_metric_keys:
            v = cur_app.get(key)
            b = ref_app.get(key)
            app_metrics[key] = _ratio_and_delta(
                float(v) if _is_finite_number(v) else None,
                float(b) if _is_finite_number(b) else None,
            )
        per_condition[f"{cid}_vs_R"] = {
            "voice_sim": sim_metrics,
            "real_voice_app": app_metrics,
        }

    b2_vs_b1: dict[str, Any] = {}
    if "B1" in by_id and "B2" in by_id:
        b1_sim = by_id["B1"]["voice_sim_record"]
        b2_sim = by_id["B2"]["voice_sim_record"]
        b1_app = by_id["B1"]["real_voice_app"].get("observable_metrics", {})
        b2_app = by_id["B2"]["real_voice_app"].get("observable_metrics", {})
        for field in TRACKED_SIM_NUMERIC_FIELDS:
            v2 = b2_sim.get(field)
            v1 = b1_sim.get(field)
            b2_vs_b1.setdefault("voice_sim", {})[field] = _ratio_and_delta(
                float(v2) if _is_finite_number(v2) else None,
                float(v1) if _is_finite_number(v1) else None,
            )
        b2_vs_b1["voice_sim"]["aqs_sim_v02"] = _ratio_and_delta(
            float(by_id["B2"]["aqs_v02"]["score"]) if _is_finite_number(by_id["B2"]["aqs_v02"]["score"]) else None,
            float(by_id["B1"]["aqs_v02"]["score"]) if _is_finite_number(by_id["B1"]["aqs_v02"]["score"]) else None,
        )
        for key in app_metric_keys:
            v2 = b2_app.get(key)
            v1 = b1_app.get(key)
            b2_vs_b1.setdefault("real_voice_app", {})[key] = _ratio_and_delta(
                float(v2) if _is_finite_number(v2) else None,
                float(v1) if _is_finite_number(v1) else None,
            )

    return {
        "vs_R": per_condition,
        "B2_vs_B1": b2_vs_b1,
    }


def _renormalized_loo_weights(drop_kpi: str) -> dict[str, float]:
    remaining = {k: v for k, v in WEIGHTS_VOICE_SIM_V02.items() if k != drop_kpi}
    total = sum(remaining.values())
    return {k: v / total for k, v in remaining.items()}


def _score_rank_order(scores_by_cid: dict[str, float | None]) -> list[str] | None:
    if any(v is None for v in scores_by_cid.values()):
        return None
    return [
        cid
        for cid, _ in sorted(
            scores_by_cid.items(),
            key=lambda item: (-float(item[1]), item[0]),  # type: ignore[arg-type]
        )
    ]


def sensitivity_analysis_v02(conditions: list[dict[str, Any]]) -> dict[str, Any]:
    ordered = sorted(conditions, key=lambda c: int(c["severity_rank"]))
    base_scores = {
        c["condition_id"]: c["aqs_v02"]["score"]
        for c in ordered
    }
    base_order = _score_rank_order(base_scores)

    variants: list[tuple[str, dict[str, float], bool]] = [
        ("baseline_v02", dict(WEIGHTS_VOICE_SIM_V02), True),
        ("no_veto_diagnostic", dict(WEIGHTS_VOICE_SIM_V02), False),
        (
            "uplink_emphasized_v1",
            {
                "M1": 0.18,
                "M2": 0.10,
                "M3": 0.16,
                "M4": 0.20,
                "M5": 0.12,
                "M6": 0.08,
                "M7": 0.06,
                "N1": 0.05,
                "N2": 0.05,
            },
            True,
        ),
        (
            "latency_uplink_v2",
            {
                "M1": 0.25,
                "M2": 0.08,
                "M3": 0.15,
                "M4": 0.20,
                "M5": 0.10,
                "M6": 0.07,
                "M7": 0.05,
                "N1": 0.05,
                "N2": 0.05,
            },
            True,
        ),
    ]
    for kpi in sorted(WEIGHTS_VOICE_SIM_V02.keys()):
        variants.append((f"loo_without_{kpi}", _renormalized_loo_weights(kpi), True))

    results: dict[str, Any] = {}
    for name, w_map, apply_veto in variants:
        scores: dict[str, float | None] = {}
        deltas_vs_base: dict[str, float | None] = {}
        vetoes: dict[str, bool] = {}
        for c in ordered:
            cid = c["condition_id"]
            scored = score_voice_sim_v02(
                c["voice_sim_record"],
                weights=w_map,
                apply_m1_veto=apply_veto,
            )
            scores[cid] = scored["score"]
            vetoes[cid] = scored["veto_applied"]
            b = base_scores[cid]
            deltas_vs_base[cid] = (
                (scored["score"] - b)
                if (scored["score"] is not None and b is not None)
                else None
            )
        order = _score_rank_order(scores)
        results[name] = {
            "weights": {k: round(v, 6) for k, v in w_map.items()},
            "apply_m1_veto": apply_veto,
            "scores": scores,
            "veto_applied": vetoes,
            "delta_vs_baseline_v02": deltas_vs_base,
            "rank_order_best_to_worst": order,
            "rank_preserved_vs_baseline": (order == base_order) if (order is not None and base_order is not None) else None,
        }

    return {
        "baseline_rank_order_best_to_worst": base_order,
        "variants": results,
    }


def analyze_correlation(payload: dict[str, Any]) -> dict[str, Any]:
    _assert_no_nonfinite(payload)
    if payload.get("schema_version") != INPUT_SCHEMA_VERSION:
        raise ValueError(
            f"Unsupported schema_version: {payload.get('schema_version')!r}, expected {INPUT_SCHEMA_VERSION!r}"
        )
    raw_conditions = payload.get("conditions")
    if not isinstance(raw_conditions, list) or not raw_conditions:
        raise ValueError("Input JSON must contain non-empty 'conditions' list")

    seen_ids: set[str] = set()
    parsed_conditions: list[dict[str, Any]] = []
    for item in raw_conditions:
        if not isinstance(item, dict):
            raise ValueError("Each condition entry must be a JSON object")
        cid = item.get("condition_id")
        if not isinstance(cid, str) or not cid:
            raise ValueError("Each condition must have non-empty string condition_id")
        if cid in seen_ids:
            raise ValueError(f"Duplicate condition_id: {cid}")
        seen_ids.add(cid)
        sev = item.get("severity_rank")
        if not isinstance(sev, int) or isinstance(sev, bool):
            raise ValueError(f"Condition {cid} must have integer severity_rank")
        shaper = item.get("shaper")
        if not isinstance(shaper, dict):
            raise ValueError(f"Condition {cid} missing shaper object")
        real_app = item.get("real_voice_app")
        if not isinstance(real_app, dict):
            raise ValueError(f"Condition {cid} missing real_voice_app object")
        sim_rec = _extract_voice_sim_record(item)
        aqs = score_voice_sim_v02(sim_rec)
        parsed_conditions.append(
            {
                "condition_id": cid,
                "label": item.get("label", cid),
                "severity_rank": sev,
                "shaper": shaper,
                "voice_sim_record": sim_rec,
                "aqs_v02": aqs,
                "real_voice_app": real_app,
            }
        )

    if "R" not in seen_ids:
        raise ValueError("Baseline condition 'R' is missing from conditions")

    parsed_conditions.sort(key=lambda c: c["severity_rank"])

    severities = [float(c["severity_rank"]) for c in parsed_conditions]
    aqs_scores = [
        float(c["aqs_v02"]["score"]) if _is_finite_number(c["aqs_v02"]["score"]) else None
        for c in parsed_conditions
    ]

    # Collect numeric real_voice_app observable metrics common across conditions.
    real_metric_keys = sorted(
        {
            k
            for c in parsed_conditions
            for k, v in c["real_voice_app"].get("observable_metrics", {}).items()
            if v is None or _is_finite_number(v)
        }
    )

    sim_vs_severity: dict[str, Any] = {}
    for field in TRACKED_SIM_NUMERIC_FIELDS:
        vals = [
            float(c["voice_sim_record"].get(field))
            if _is_finite_number(c["voice_sim_record"].get(field))
            else None
            for c in parsed_conditions
        ]
        sim_vs_severity[field] = {
            "values": vals,
            "spearman_rho": spearman_rho(severities, vals),
            "kendall_tau_b": kendall_tau_b(severities, vals),
        }
    sim_vs_severity["aqs_sim_v02"] = {
        "values": aqs_scores,
        "spearman_rho": spearman_rho(severities, aqs_scores),
        "kendall_tau_b": kendall_tau_b(severities, aqs_scores),
    }

    real_vs_severity: dict[str, Any] = {}
    for key in real_metric_keys:
        vals = [
            float(c["real_voice_app"].get("observable_metrics", {}).get(key))
            if _is_finite_number(c["real_voice_app"].get("observable_metrics", {}).get(key))
            else None
            for c in parsed_conditions
        ]
        real_vs_severity[key] = {
            "values": vals,
            "spearman_rho": spearman_rho(severities, vals),
            "kendall_tau_b": kendall_tau_b(severities, vals),
        }

    sim_vs_real_matrix: dict[str, dict[str, Any]] = {}
    sim_series_map: dict[str, list[float | None]] = {
        field: sim_vs_severity[field]["values"]
        for field in TRACKED_SIM_NUMERIC_FIELDS
    }
    sim_series_map["aqs_sim_v02"] = aqs_scores

    for real_key in real_metric_keys:
        r_vals = real_vs_severity[real_key]["values"]
        row: dict[str, Any] = {}
        for sim_key, s_vals in sim_series_map.items():
            row[sim_key] = {
                "spearman_rho": spearman_rho(s_vals, r_vals),
                "kendall_tau_b": kendall_tau_b(s_vals, r_vals),
            }
        sim_vs_real_matrix[real_key] = row

    ratios = directional_ratios(parsed_conditions)
    sensitivity = sensitivity_analysis_v02(parsed_conditions)

    n_cond = len(parsed_conditions)
    power_note = (
        f"N={n_cond} ordered conditions (R/B1/B2). For N=3, there are 3!=6 permutations "
        "so the minimum achievable two-sided permutation p-value is 2/6=0.333. "
        "Spearman rho and Kendall tau-b here verify directional rank-order consistency "
        "and structural sensitivity rather than frequentist p<0.05 significance."
    )

    shaper_conservation = [
        {
            "condition_id": c["condition_id"],
            "synthetic_unconfirmed_packets": c["shaper"].get("synthetic", {}).get("unconfirmed_packets"),
            "synthetic_conserved": c["shaper"].get("synthetic", {}).get("conserved"),
            "real_app_unconfirmed_packets": c["shaper"].get("real_app", {}).get("unconfirmed_packets"),
            "real_app_conserved": c["shaper"].get("real_app", {}).get("conserved"),
        }
        for c in parsed_conditions
    ]

    return {
        "schema_version": OUTPUT_SCHEMA_VERSION,
        "generated_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "campaign_id": payload.get("campaign_id", "profile4-voice-correlation"),
        "condition_order": [c["condition_id"] for c in parsed_conditions],
        "statistical_power_caveat": power_note,
        "weights_voice_sim_v02": dict(WEIGHTS_VOICE_SIM_V02),
        "m1_veto_threshold_ms": M1_VETO_THRESHOLD_MS,
        "t4_veto_cap": T4_VETO_CAP,
        "shaper_conservation": shaper_conservation,
        "conditions": [
            {
                "condition_id": c["condition_id"],
                "label": c["label"],
                "severity_rank": c["severity_rank"],
                "shaper": c["shaper"],
                "voice_sim_kpis": {k: c["voice_sim_record"].get(k) for k in TRACKED_SIM_NUMERIC_FIELDS},
                "voice_sim_meta": {
                    "caliber": c["voice_sim_record"].get("caliber"),
                    "turns_ok": c["voice_sim_record"].get("turns_ok"),
                    "low_confidence": c["voice_sim_record"].get("low_confidence"),
                },
                "aqs_v02": c["aqs_v02"],
                "real_voice_app": c["real_voice_app"],
            }
            for c in parsed_conditions
        ],
        "correlations": {
            "voice_sim_vs_severity": sim_vs_severity,
            "real_voice_app_vs_severity": real_vs_severity,
            "voice_sim_vs_real_voice_app": sim_vs_real_matrix,
        },
        "directional_ratios": ratios,
        "sensitivity_v02": sensitivity,
    }


def _fmt(val: float | None, decimals: int = 2) -> str:
    if val is None:
        return "—"
    return f"{val:.{decimals}f}"


def render_markdown_table(summary: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append(f"# Profile 4 Voice Correlation Summary (`{summary['campaign_id']}`)")
    lines.append("")
    lines.append(f"- **Schema**: `{summary['schema_version']}`")
    lines.append(f"- **Generated**: `{summary['generated_at_utc']}`")
    lines.append(f"- **Condition Order**: `{' -> '.join(summary['condition_order'])}`")
    lines.append(f"- **Statistical Power Caveat**: {summary['statistical_power_caveat']}")
    lines.append("")
    lines.append("## 1. Synthetic `VoiceRunner.runSim()` vs `AQS_v02`")
    lines.append("")
    lines.append("| Cond | N1 RTT (ms) | N2 Jitter (ms) | M3 UL Jitter (ms) | M2' DL Jitter (ms) | M4 TTFB P50/P95 (ms) | M1' Proxy (ms) | M5 Switch (ms) | M6 Barge (ms) | M7 MaxGap (ms) | NearZero | AQS_v02 (Raw) | Veto |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for c in summary["conditions"]:
        k = c["voice_sim_kpis"]
        a = c["aqs_v02"]
        lines.append(
            "| {cid} | {n1} | {n2} | {m3} | {m2} | {m4p50} / {m4p95} | {m1} | {m5} | {m6} | {m7} | {nz} | {sc} ({raw}) | {veto} |".format(
                cid=c["condition_id"],
                n1=_fmt(k.get("rtt_ms")),
                n2=_fmt(k.get("jitter_ms")),
                m3=_fmt(k.get("up_frame_jitter_ms")),
                m2=_fmt(k.get("down_net_jitter_ms")),
                m4p50=_fmt(k.get("ttfb_p50_ms")),
                m4p95=_fmt(k.get("ttfb_p95_ms")),
                m1=_fmt(k.get("mouth_ear_proxy_ms")),
                m5=_fmt(k.get("turn_switch_p50_ms")),
                m6=_fmt(k.get("barge_stop_max_ms")),
                m7=_fmt(k.get("m7_max_frame_gap_ms")),
                nz=_fmt(k.get("voice_near_zero_arrival_ratio"), 4),
                sc=_fmt(a.get("score")),
                raw=_fmt(a.get("raw_weighted_score")),
                veto=str(a.get("veto_applied")),
            )
        )
    lines.append("")
    lines.append("## 2. Real Voice App (`real_voice_app`) Observables")
    lines.append("")
    real_keys = sorted(summary["correlations"]["real_voice_app_vs_severity"].keys())
    header = "| Cond | App | Status | " + " | ".join(real_keys) + " |"
    sep = "|---|---|---|" + "|".join(["---"] * len(real_keys)) + "|"
    lines.append(header)
    lines.append(sep)
    for c in summary["conditions"]:
        app = c["real_voice_app"]
        obs = app.get("observable_metrics", {})
        cells = [_fmt(obs.get(rk), 3) if isinstance(obs.get(rk), (int, float)) else str(obs.get(rk, "—")) for rk in real_keys]
        lines.append(f"| {c['condition_id']} | `{app.get('app_package', 'unknown')}` ({app.get('app_version_code', '')}) | `{app.get('status', '')}` | " + " | ".join(cells) + " |")
    lines.append("")
    lines.append("## 3. Rank Correlation vs Condition Severity (`Spearman rho` / `Kendall tau-b`)")
    lines.append("")
    lines.append("| Domain | Metric | Values (`R -> B1 -> B2`) | Spearman rho | Kendall tau-b |")
    lines.append("|---|---|---|---|---|")
    for metric, info in summary["correlations"]["voice_sim_vs_severity"].items():
        vals_str = ", ".join(_fmt(v) for v in info["values"])
        lines.append(f"| `voice_sim` | `{metric}` | {vals_str} | {_fmt(info['spearman_rho'], 3)} | {_fmt(info['kendall_tau_b'], 3)} |")
    for metric, info in summary["correlations"]["real_voice_app_vs_severity"].items():
        vals_str = ", ".join(_fmt(v, 3) for v in info["values"])
        lines.append(f"| `real_voice_app` | `{metric}` | {vals_str} | {_fmt(info['spearman_rho'], 3)} | {_fmt(info['kendall_tau_b'], 3)} |")
    lines.append("")
    lines.append("## 4. `WEIGHTS_VOICE_SIM_V02` Sensitivity Analysis")
    lines.append("")
    lines.append("| Variant | Rank Order (Best -> Worst) | Preserved? | Scores |")
    lines.append("|---|---|---|---|")
    for vname, vinfo in summary["sensitivity_v02"]["variants"].items():
        order = " > ".join(vinfo["rank_order_best_to_worst"] or ["N/A"])
        scores_str = ", ".join(f"{cid}={_fmt(sc)}" for cid, sc in vinfo["scores"].items())
        lines.append(f"| `{vname}` | {order} | {vinfo['rank_preserved_vs_baseline']} | {scores_str} |")
    lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Profile 4 voice correlation & sensitivity analysis (pure stdlib)")
    parser.add_argument("--input", required=True, help="Path to voice-correlation-input-1 JSON")
    parser.add_argument("--out-dir", required=True, help="Directory for voice-correlation-summary.json & table")
    args = parser.parse_args(argv)

    in_path = Path(args.input)
    out_dir = Path(args.out_dir)
    raw_text = in_path.read_text(encoding="utf-8")
    payload = json.loads(raw_text)
    if not isinstance(payload, dict):
        raise ValueError("Top-level input JSON must be an object")

    summary = analyze_correlation(payload)
    md = render_markdown_table(summary)

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "voice-correlation-summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    (out_dir / "voice-correlation-table.md").write_text(md, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
