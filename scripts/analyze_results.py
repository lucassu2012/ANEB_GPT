#!/usr/bin/env python3
"""ANEB results analyzer (stdlib only) — precursor of the phase-3 dashboard.

Usage:
    python analyze_results.py data/results/*.jsonl [--csv out.csv]

Reads server-side results JSONL (one JSON object per line, contract schema 1.0)
and prints a markdown summary: run inventory, validity breakdown, per-scenario
KPI medians, AQS distribution. Tolerant of missing/unknown fields — the schema
is still evolving (kpi_set / aqs_version are read from the records themselves).
"""
import json
import sys
import glob
import statistics
from collections import defaultdict


def load_records(patterns):
    records = []
    for pat in patterns:
        for path in glob.glob(pat):
            with open(path, encoding="utf-8") as f:
                for lineno, line in enumerate(f, 1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        records.append(json.loads(line))
                    except json.JSONDecodeError as e:
                        print(f"<!-- skip {path}:{lineno}: {e} -->", file=sys.stderr)
    return records


def fnum(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def median_or_none(vals):
    vals = [v for v in vals if v is not None]
    return statistics.median(vals) if vals else None


def main(argv):
    csv_out = None
    if "--csv" in argv:
        i = argv.index("--csv")
        csv_out = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if not argv:
        print(__doc__)
        return 1

    recs = load_records(argv)
    print(f"# ANEB results summary\n\nrecords: **{len(recs)}**\n")
    if not recs:
        return 0

    by_version = defaultdict(int)
    validity = defaultdict(int)
    aqs_vals = []
    # scenario -> kpi -> [values]
    kpis = defaultdict(lambda: defaultdict(list))

    for r in recs:
        by_version[(r.get("kpi_set", "?"), r.get("aqs_version", "?"),
                    r.get("schema_version", "?"))] += 1
        # 实际上报体（ResultReporter.kt）：AQS 在 run.aqs.score；保留旧路径兜底
        aqs = fnum(((r.get("run") or {}).get("aqs") or {}).get("score"))
        if aqs is None:
            aqs = fnum(r.get("aqs")) or fnum((r.get("aqs_result") or {}).get("score"))
        if aqs is not None:
            aqs_vals.append(aqs)
        for s in r.get("scenarios", []) or []:
            # 实际字段：profile_id + kpi（不是 scenario_id/kpis）；*_grade 为字符串分级，跳过
            sid = s.get("profile_id") or s.get("scenario_id") or "?"
            validity[s.get("validity", "?")] += 1
            for k, v in (s.get("kpi") or s.get("kpis") or {}).items():
                if k.endswith("_grade"):
                    continue
                val = fnum(v) if not isinstance(v, dict) else fnum(v.get("value"))
                if val is not None:
                    kpis[sid][k].append(val)

    print("## versions\n")
    print("| kpi_set | aqs | schema | runs |")
    print("|---|---|---|---|")
    for (k, a, sv), n in sorted(by_version.items()):
        print(f"| {k} | {a} | {sv} | {n} |")

    print("\n## validity (scenario level)\n")
    print("| validity | count |")
    print("|---|---|")
    for k, n in sorted(validity.items()):
        print(f"| {k} | {n} |")

    if aqs_vals:
        print("\n## AQS\n")
        print(f"- n={len(aqs_vals)} median={statistics.median(aqs_vals):.1f} "
              f"min={min(aqs_vals):.1f} max={max(aqs_vals):.1f}")

    print("\n## per-scenario KPI medians\n")
    for sid in sorted(kpis):
        print(f"### {sid}\n")
        print("| kpi | median | n |")
        print("|---|---|---|")
        for k in sorted(kpis[sid]):
            vals = kpis[sid][k]
            print(f"| {k} | {median_or_none(vals):.3f} | {len(vals)} |")
        print()

    if csv_out:
        import csv
        with open(csv_out, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["scenario", "kpi", "value"])
            for sid in kpis:
                for k, vals in kpis[sid].items():
                    for v in vals:
                        w.writerow([sid, k, v])
        print(f"csv written: {csv_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
