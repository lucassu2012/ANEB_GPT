#!/usr/bin/env python3
"""ANEB phase-3 local dashboard generator (stdlib only, no third-party deps).

Usage:
    python dashboard.py results/*.jsonl [-o dashboard.html]

Reads server-side results JSONL (contract schema 1.0, same input as
analyze_results.py) and emits a SELF-CONTAINED single-file HTML dashboard:
  - overview cards (runs / AQS median / scenario valid rate)
  - AQS timeline (hand-written inline SVG line chart)
  - per-scenario KPI median table (four-level grade colors)
  - validity distribution
  - aggregated ITL histogram from per-scenario `itl_histogram` (inline SVG bars)
  - versions + claim_scope footer

Field paths follow app/probe ResultReporter.kt: top-level contract fields,
`run.aqs.score`, `scenarios[].kpi.*` (+ `*_grade`), `scenarios[].validity`,
`scenarios[].itl_histogram.{edges_ms,counts,total}`. Tolerant of legacy
variants (top-level `aqs`, `scenarios[].kpis`).
"""
import argparse
import glob
import html
import json
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

GRADE_COLORS = {  # four-level grading, KpiGrading.kt
    "excellent": ("#e6f4ea", "#137333"),
    "good":      ("#e8f0fe", "#1a56b0"),
    "fair":      ("#fef7e0", "#b06000"),
    "poor":      ("#fce8e6", "#c5221f"),
}
VALIDITY_COLORS = {"valid": "#137333", "degraded": "#b06000", "invalid": "#c5221f"}


def load_records(patterns):
    records, files = [], []
    for pat in patterns:
        paths = glob.glob(pat) or ([pat] if not any(c in pat for c in "*?[") else [])
        for path in paths:
            files.append(path)
            with open(path, encoding="utf-8") as f:
                for lineno, line in enumerate(f, 1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        records.append(json.loads(line))
                    except json.JSONDecodeError as e:
                        print(f"skip {path}:{lineno}: {e}", file=sys.stderr)
    return records, files


def fnum(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def esc(s):
    return html.escape(str(s))


# ---------------------------------------------------------------- extraction

def extract(recs):
    d = {
        "runs": [], "versions": Counter(), "claims": Counter(),
        "validity": Counter(),
        "kpis": defaultdict(lambda: defaultdict(list)),      # sid -> kpi -> [v]
        "grades": defaultdict(lambda: defaultdict(Counter)), # sid -> kpi -> grade counter
        "itl": {},   # edges tuple -> summed counts
        "itl_total": 0, "scenario_count": 0, "missing_body": 0,
    }
    for r in recs:
        d["versions"][(r.get("kpi_set", "?"), r.get("aqs_version", "?"),
                       r.get("schema_version", "?"), r.get("profile_versions", "?"))] += 1
        d["claims"][r.get("claim_scope", "?")] += 1

        run = r.get("run") or {}
        aqs_obj = run.get("aqs") or {}
        aqs = fnum(aqs_obj.get("score"))
        if aqs is None:  # legacy fallback
            aqs = fnum(r.get("aqs")) or fnum((r.get("aqs_result") or {}).get("score"))
        d["runs"].append({
            "run_id": run.get("run_id") or r.get("run_id") or "?",
            "t": fnum(run.get("started_at_epoch_ms")),
            "aqs": aqs,
            "low_conf": bool(aqs_obj.get("low_confidence")),
            "veto": bool(aqs_obj.get("veto_applied")),
            "status": run.get("status") or "?",
        })
        scenarios = r.get("scenarios") or []
        if not run and not scenarios:
            d["missing_body"] += 1
        for s in scenarios:
            d["scenario_count"] += 1
            sid = s.get("profile_id") or s.get("scenario_id") or "?"
            d["validity"][s.get("validity", "?")] += 1
            kpi = s.get("kpi") or s.get("kpis") or {}
            for k, v in kpi.items():
                if k.endswith("_grade"):
                    # 分级键按 KPI 前缀记（t1_grade -> t1），供 t1_ttft_ms 等值键查色
                    if isinstance(v, str):
                        d["grades"][sid][k[:-6]][v] += 1
                    continue
                val = fnum(v) if not isinstance(v, dict) else fnum(v.get("value"))
                if val is not None:
                    d["kpis"][sid][k].append(val)
            hist = s.get("itl_histogram") or {}
            edges, counts = hist.get("edges_ms"), hist.get("counts")
            if isinstance(edges, list) and isinstance(counts, list) and counts:
                key = tuple(edges)
                acc = d["itl"].setdefault(key, [0] * len(counts))
                for i, c in enumerate(counts[:len(acc)]):
                    if fnum(c):
                        acc[i] += c
                d["itl_total"] += fnum(hist.get("total")) or sum(
                    c for c in counts if fnum(c) is not None)
    return d


# ---------------------------------------------------------------- SVG helpers

def svg_line_chart(points, w=860, h=240, pad=42):
    """points: list of (label, y). Returns inline SVG of an AQS timeline."""
    if not points:
        return "<p class='empty'>无 AQS 数据（上报体缺 run.aqs.score）</p>"
    ys = [p[1] for p in points]
    lo, hi = min(0, min(ys)), max(100, max(ys))
    iw, ih = w - 2 * pad, h - 2 * pad
    n = len(points)

    def X(i): return pad + (iw * i / max(1, n - 1) if n > 1 else iw / 2)
    def Y(v): return pad + ih - ih * (v - lo) / (hi - lo or 1)

    parts = [f"<svg viewBox='0 0 {w} {h}' xmlns='http://www.w3.org/2000/svg' "
             f"role='img' aria-label='AQS timeline'>"]
    for gv in range(0, 101, 25):
        y = Y(gv)
        parts.append(f"<line x1='{pad}' y1='{y:.1f}' x2='{w-pad}' y2='{y:.1f}' "
                     "stroke='#e0e0e0' stroke-width='1'/>")
        parts.append(f"<text x='{pad-8}' y='{y+4:.1f}' text-anchor='end' "
                     f"font-size='11' fill='#666'>{gv}</text>")
    pts = " ".join(f"{X(i):.1f},{Y(v):.1f}" for i, (_, v) in enumerate(points))
    if n > 1:
        parts.append(f"<polyline points='{pts}' fill='none' stroke='#1a56b0' stroke-width='2'/>")
    for i, (label, v) in enumerate(points):
        parts.append(f"<circle cx='{X(i):.1f}' cy='{Y(v):.1f}' r='4' fill='#1a56b0'>"
                     f"<title>{esc(label)}: {v:.1f}</title></circle>")
        step = max(1, n // 12)
        if i % step == 0:
            parts.append(f"<text x='{X(i):.1f}' y='{h-pad+16}' text-anchor='middle' "
                         f"font-size='10' fill='#666'>{esc(label)}</text>")
    parts.append("</svg>")
    return "".join(parts)


def svg_bar_chart(labels, counts, w=860, h=260, pad=42):
    """Hand-written SVG histogram bars."""
    if not counts or not any(counts):
        return "<p class='empty'>无 ITL 直方图数据（上报体缺 scenarios[].itl_histogram）</p>"
    iw, ih = w - 2 * pad, h - 2 * pad - 18
    mx = max(counts)
    n = len(counts)
    bw = iw / n
    parts = [f"<svg viewBox='0 0 {w} {h}' xmlns='http://www.w3.org/2000/svg' "
             f"role='img' aria-label='ITL histogram'>"]
    for frac in (0, 0.5, 1.0):
        y = pad + ih - ih * frac
        parts.append(f"<line x1='{pad}' y1='{y:.1f}' x2='{w-pad}' y2='{y:.1f}' "
                     "stroke='#e0e0e0'/>")
        parts.append(f"<text x='{pad-8}' y='{y+4:.1f}' text-anchor='end' font-size='11' "
                     f"fill='#666'>{int(mx*frac)}</text>")
    for i, c in enumerate(counts):
        bh = ih * c / mx
        x, y = pad + i * bw, pad + ih - bh
        parts.append(f"<rect x='{x+1:.1f}' y='{y:.1f}' width='{bw-2:.1f}' height='{bh:.1f}' "
                     f"fill='#1a9850' opacity='0.85'><title>{esc(labels[i])} ms: {c}</title></rect>")
        step = max(1, n // 14)
        if i % step == 0:
            parts.append(f"<text x='{x+bw/2:.1f}' y='{h-pad+14}' text-anchor='middle' "
                         f"font-size='9' fill='#666'>{esc(labels[i])}</text>")
    parts.append(f"<text x='{w/2}' y='{h-6}' text-anchor='middle' font-size='11' "
                 "fill='#666'>ITL bucket lower edge (ms, log grid + T2/T3/T4 anchors)</text>")
    parts.append("</svg>")
    return "".join(parts)


# ---------------------------------------------------------------- HTML

def modal_grade(counter):
    return counter.most_common(1)[0][0] if counter else None


def grade_cell(val, grade, n):
    txt = f"{val:.3f}".rstrip("0").rstrip(".") if val is not None else "—"
    bg, fg = GRADE_COLORS.get(grade or "", ("#f5f5f5", "#444"))
    g = esc(grade) if grade else ""
    return (f"<td style='background:{bg};color:{fg}'><b>{txt}</b>"
            f"<span class='sub'>{g} · n={n}</span></td>")


def build_html(d, files, generated_at):
    runs = d["runs"]
    aqs_pts = [(r["run_id"], r["aqs"]) for r in sorted(
        runs, key=lambda r: (r["t"] is None, r["t"] or 0)) if r["aqs"] is not None]
    aqs_median = statistics.median([v for _, v in aqs_pts]) if aqs_pts else None
    valid_n = d["validity"].get("valid", 0)
    valid_rate = (100.0 * valid_n / d["scenario_count"]) if d["scenario_count"] else None

    cards = [
        ("runs（上报记录数）", str(len(runs))),
        ("AQS 中位", f"{aqs_median:.1f}" if aqs_median is not None else "N/A"),
        ("场景有效率", f"{valid_rate:.1f}% ({valid_n}/{d['scenario_count']})"
         if valid_rate is not None else "N/A"),
        ("含 KPI 正文的记录", str(len(runs) - d["missing_body"])),
    ]
    card_html = "".join(
        f"<div class='card'><div class='cv'>{esc(v)}</div><div class='cl'>{esc(k)}</div></div>"
        for k, v in cards)

    warn = ""
    if d["missing_body"]:
        warn = (f"<p class='warn'>注意：{d['missing_body']}/{len(runs)} 条记录缺少 "
                "run/scenarios 正文（仅合同顶层字段，如冒烟验证记录），未计入 KPI/AQS 统计。</p>")

    # per-scenario KPI table
    all_kpis = sorted({k for m in d["kpis"].values() for k in m})
    if all_kpis:
        head = "<tr><th>scenario</th>" + "".join(f"<th>{esc(k)}</th>" for k in all_kpis) + "</tr>"
        rows = []
        for sid in sorted(d["kpis"]):
            cells = [f"<td class='sid'>{esc(sid)}</td>"]
            for k in all_kpis:
                vals = d["kpis"][sid].get(k)
                if vals:
                    # 主指标按 KPI 前缀取分级众数上色；伴随口径（incl_/excl_）不套用主分级
                    grade = None
                    if "incl_" not in k and "excl_" not in k:
                        grade = modal_grade(d["grades"][sid].get(k.split("_")[0], Counter()))
                    cells.append(grade_cell(statistics.median(vals), grade, len(vals)))
                else:
                    cells.append("<td>—</td>")
            rows.append("<tr>" + "".join(cells) + "</tr>")
        kpi_table = f"<div class='scroll'><table>{head}{''.join(rows)}</table></div>"
    else:
        kpi_table = "<p class='empty'>无场景 KPI 数据</p>"

    # validity distribution
    if d["validity"]:
        total = sum(d["validity"].values())
        vrows = "".join(
            f"<tr><td><span class='dot' style='background:{VALIDITY_COLORS.get(k, '#888')}'></span>"
            f"{esc(k)}</td><td>{n}</td><td>{100.0*n/total:.1f}%</td></tr>"
            for k, n in sorted(d["validity"].items(), key=lambda x: -x[1]))
        validity_html = f"<table class='narrow'><tr><th>validity</th><th>count</th><th>%</th></tr>{vrows}</table>"
    else:
        validity_html = "<p class='empty'>无 validity 数据</p>"

    # ITL histogram (largest edge-set)
    if d["itl"]:
        edges, counts = max(d["itl"].items(), key=lambda kv: sum(kv[1]))
        labels = [f"{e:g}" for e in edges[:len(counts)]]
        while len(labels) < len(counts):
            labels.append("+")
        itl_html = svg_bar_chart(labels, counts)
        itl_note = f"<p class='note'>聚合 {d['itl_total']} 个 ITL 样本；桶界版本组数：{len(d['itl'])}</p>"
    else:
        itl_html, itl_note = svg_bar_chart([], []), ""

    ver_rows = "".join(
        f"<tr><td>{esc(k)}</td><td>{esc(a)}</td><td>{esc(sv)}</td><td>{esc(pv)}</td><td>{n}</td></tr>"
        for (k, a, sv, pv), n in sorted(d["versions"].items()))
    claims = ", ".join(f"{esc(c)} ({n})" for c, n in d["claims"].items())

    return f"""<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ANEB 数据看板</title>
<style>
body{{font-family:'Segoe UI',system-ui,sans-serif;margin:0;background:#f6f7f9;color:#202124}}
.wrap{{max-width:960px;margin:0 auto;padding:24px 16px 48px}}
h1{{font-size:22px}} h2{{font-size:16px;margin:28px 0 10px;border-bottom:1px solid #ddd;padding-bottom:6px}}
.cards{{display:flex;gap:12px;flex-wrap:wrap}}
.card{{flex:1 1 150px;background:#fff;border:1px solid #e0e0e0;border-radius:8px;padding:14px}}
.cv{{font-size:26px;font-weight:600}} .cl{{font-size:12px;color:#5f6368;margin-top:4px}}
.panel{{background:#fff;border:1px solid #e0e0e0;border-radius:8px;padding:12px}}
svg{{width:100%;height:auto;display:block}}
table{{border-collapse:collapse;width:100%;font-size:12.5px;background:#fff}}
th,td{{border:1px solid #e0e0e0;padding:5px 8px;text-align:right;white-space:nowrap}}
th{{background:#f1f3f4;text-align:center}} td.sid{{text-align:left;font-weight:600}}
td .sub{{display:block;font-size:10px;opacity:.75;font-weight:400}}
.narrow{{max-width:360px}} .narrow td:first-child{{text-align:left}}
.scroll{{overflow-x:auto}}
.dot{{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:6px}}
.empty{{color:#5f6368;font-style:italic}} .warn{{color:#b06000;background:#fef7e0;padding:8px 12px;border-radius:6px}}
.note{{font-size:12px;color:#5f6368}}
footer{{margin-top:36px;font-size:12px;color:#5f6368;border-top:1px solid #ddd;padding-top:12px}}
</style></head><body><div class="wrap">
<h1>ANEB 数据看板</h1>
<p class="note">生成时间：{esc(generated_at)} · 输入文件：{esc(', '.join(files) or '（无）')}</p>
{warn}
<div class="cards">{card_html}</div>
<h2>AQS 时间线</h2><div class="panel">{svg_line_chart(aqs_pts)}</div>
<h2>各场景 KPI 中位（色块 = 该 KPI 上报分级众数）</h2>{kpi_table}
<h2>Validity 分布（场景级三态）</h2>{validity_html}
<h2>ITL 直方图（跨场景聚合）</h2><div class="panel">{itl_html}</div>{itl_note}
<h2>版本</h2>
<div class="scroll"><table><tr><th>kpi_set</th><th>aqs_version</th><th>schema_version</th><th>profile_versions</th><th>runs</th></tr>{ver_rows}</table></div>
<footer>claim_scope: <b>{claims or '—'}</b> · 结论口径以 claim_scope 为界，不代表运营商网络端到端体验。<br>
ANEB phase-3 dashboard · stdlib-only 生成 · 图表为手写内联 SVG，无外部依赖。</footer>
</div></body></html>"""


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("inputs", nargs="+", help="results JSONL files / globs")
    ap.add_argument("-o", "--output", default="dashboard.html")
    args = ap.parse_args(argv)

    recs, files = load_records(args.inputs)
    d = extract(recs)
    now = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S %z")
    out = build_html(d, files, now)
    with open(args.output, "w", encoding="utf-8") as f:
        f.write(out)
    print(f"records={len(recs)} files={len(files)} -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
