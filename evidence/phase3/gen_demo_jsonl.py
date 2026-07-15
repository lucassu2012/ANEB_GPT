#!/usr/bin/env python3
"""Generate deterministic demo results JSONL matching ResultReporter.kt schema 1.0.
Fallback demo data for the phase-3 dashboard (E-01 only has 1 smoke record)."""
import json
import random
import sys

random.seed(42)

EDGES = sorted(set([2 ** i for i in range(14)] + [100, 200, 400, 1000]))  # 1..8192
GRADE = lambda v, a, b, c: "excellent" if v < a else "good" if v < b else "fair" if v <= c else "poor"

PROFILES = ["s1_chat", "s2_coding_agent", "s3_multimodal"]


def itl_hist(p95):
    counts = [0] * (len(EDGES) + 1)
    for _ in range(600):
        v = random.lognormvariate(0, 0.6) * p95 / 2.2
        i = 0
        while i < len(EDGES) and v >= EDGES[i]:
            i += 1
        counts[i] += 1
    return {"buckets_version": "log2-1..8192+thresholds-v1",
            "edges_ms": [float(e) for e in EDGES], "counts": counts,
            "total": sum(counts)}


def scenario(pid, order, degrade=False):
    mult = 2.2 if degrade else 1.0
    ttft = random.uniform(350, 900) * mult
    itl95 = random.uniform(45, 140) * mult
    stall = random.uniform(0.0, 0.004) * (4 if degrade else 1)
    rtt = random.uniform(18, 60) * mult
    goodput = random.uniform(8, 30) / mult
    validity = "valid"
    reasons = ""
    if degrade and random.random() < 0.5:
        validity = "degraded"
        reasons = "CLOCK_DRIFT_SUSPECT"
    if degrade and random.random() < 0.15:
        validity = "invalid"
        reasons = "NETWORK_TRANSITION"
    return {
        "profile_id": pid, "profile_version": "0.2.0",
        "repeat_index": 0, "order_index": order,
        "validity": validity, "invalid_reasons": reasons,
        "kpi": {
            "t1_ttft_ms": round(ttft, 1), "t1_grade": GRADE(ttft, 500, 1000, 2000),
            "t2_itl_p95_ms": round(itl95, 1), "t2_grade": GRADE(itl95, 80, 150, 300),
            "t2_itl_p95_incl_coalesced_ms": round(itl95 * 1.1, 1),
            "t3_stall_rate": round(stall, 5),
            "t3_grade": "excellent" if stall == 0 else GRADE(stall, 0.002, 0.005, 0.01),
            "t3_stall_rate_incl_resume": round(stall * 1.2, 5),
            "t4_severe_stall_rate": round(stall / 3, 5),
            "t4_grade": "excellent" if stall < 0.001 else "good",
            "t5_resume_p95_ms": round(random.uniform(200, 800), 1),
            "n1_rtt_p50_ms": round(rtt, 1), "n1_grade": GRADE(rtt, 30, 60, 120),
            "n2_jitter_ms": round(random.uniform(2, 15) * mult, 2),
            "n2_grade": GRADE(random.uniform(2, 15) * mult, 5, 12, 25),
            "u1_goodput_mbps": round(goodput, 2),
            "u1_grade": "excellent" if goodput > 20 else "good" if goodput >= 5 else "fair",
            "u1_goodput_excl_slow_start_mbps": round(goodput * 1.15, 2),
            "u2_tool_loop_p95_ms": round(random.uniform(600, 1800) * mult, 1),
            "u2_grade": GRADE(random.uniform(600, 1800) * mult, 1000, 2000, 4000),
            "seq_gap_count": 0, "seq_dup_count": 0,
        },
        "clock": {"offset_start_us": random.randint(-800, 800),
                  "offset_end_us": random.randint(-900, 900),
                  "drift_ppm": round(random.uniform(-4, 4), 2), "offset_suspect": False},
        "network_snapshot": {"transport": "WIFI", "capabilities": "NOT_METERED|VALIDATED",
                             "interface": "wlan0", "server_observed_addr": "120.79.148.0"},
        "parse": {"parse_dur_us": random.randint(9000, 30000),
                  "per_event_parse_us": round(random.uniform(8, 25), 1)},
        "itl_histogram": itl_hist(itl95),
    }


def run(i, t0):
    degrade = i in (4, 5)  # simulate a netem-degraded window
    scenarios = [scenario(p, j, degrade) for j, p in enumerate(PROFILES)]
    base = 88 - (30 if degrade else 0) + random.uniform(-6, 6)
    aqs = max(5.0, min(100.0, base))
    return {
        "claim_scope": "application_end_to_end_to_probe_node",
        "kpi_set": "agent-qoe-kpi-v0.2", "aqs_version": "aqs-v0.1",
        "profile_versions": "0.2.0", "schema_version": "1.0",
        "run": {
            "run_id": f"demo-{i:03d}", "started_at_epoch_ms": t0 + i * 3_600_000,
            "mode": "standard", "scenario_order": "latin_square",
            "transport": "WIFI", "profile_source": "bundled",
            "app_version_name": "0.3.0-p3", "app_version_code": 30,
            "guard_metadata": "netguard=pass;radio=1hz", "status": "COMPLETED",
            "aqs": {"score": round(aqs, 1), "low_confidence": degrade,
                    "veto_applied": bool(degrade and aqs < 55),
                    "not_computable_reason": None,
                    "input_mapping": "aqs-input-mapping-v0.1",
                    "sub_scores": {"responsiveness": round(aqs * 0.9, 1),
                                   "fluency": round(min(100, aqs * 1.05), 1),
                                   "reliability": round(min(100, aqs * 1.1), 1)}},
        },
        "scenarios": scenarios,
    }


def main():
    out = sys.argv[1]
    t0 = 1_784_000_000_000  # 2026-07 epoch-ms neighborhood
    with open(out, "w", encoding="utf-8") as f:
        for i in range(12):
            f.write(json.dumps(run(i, t0), ensure_ascii=False) + "\n")
    print(f"wrote 12 demo records -> {out}")


if __name__ == "__main__":
    main()
