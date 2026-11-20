#!/usr/bin/env python3
"""
ANEB Multi-Card Business-Video R/B Campaign Summarizer & SVG Curve Generator.

Reads verified `business-video-review-1` JSON files (review-R.json / review-B.json)
without modifying originals, and generates:
  - campaign-summary.json
  - campaign-table.md
  - inflection-curve.svg (self-contained interval plot with 3.0s target threshold)
"""
import argparse
import html
import json
import os
from pathlib import Path


def load_review_entry(review_path: Path) -> dict | None:
    if not review_path.is_file():
        return None
    doc = json.loads(review_path.read_text(encoding="utf-8"))
    if doc.get("format") != "business-video-review-1":
        raise ValueError(f"Unsupported review format in {review_path}: {doc.get('format')}")
    rec = doc["records"][0]
    metrics = rec.get("metrics", {})
    ptr = metrics.get("visible_pointer_to_target_wait", {})
    sw = metrics.get("visible_switch_wait", {})
    net = rec.get("network_condition_review", {})
    return {
        "path": str(review_path),
        "attempt_id": rec.get("attempt_id"),
        "qualification": rec.get("qualification"),
        "ptr_status": ptr.get("status"),
        "ptr_interval_s": ptr.get("interval_s"),
        "ptr_eval": ptr.get("target_evaluation"),
        "ptr_target_s": ptr.get("target_s"),
        "ptr_kind": ptr.get("endpoint_b_kind"),
        "sw_status": sw.get("status"),
        "sw_interval_s": sw.get("interval_s"),
        "stall_status": rec.get("stall_status"),
        "network_mode": net.get("mode"),
        "network_summary": net.get("summary", ""),
    }


def summarize_cards(card_specs: list[tuple[str, Path]]) -> list[dict]:
    rows = []
    for label, review_dir in card_specs:
        r_entry = load_review_entry(review_dir / "review-R.json")
        b_entry = load_review_entry(review_dir / "review-B.json")
        rows.append({
            "label": label,
            "review_dir": str(review_dir),
            "R": r_entry,
            "B": b_entry,
        })
    return rows


def fmt_interval(entry: dict | None) -> str:
    if entry is None:
        return "—"
    iv = entry.get("ptr_interval_s")
    ev = entry.get("ptr_eval") or entry.get("ptr_status") or "NA"
    if not iv or len(iv) != 2:
        return f"NA ({entry.get('qualification', 'NOT_QUALIFIED')})"
    return f"[{iv[0]:.3f}, {iv[1]:.3f}]s ({ev})"


def render_markdown(rows: list[dict]) -> str:
    lines = [
        "# ANEB Business-Video R/B Campaign Summary",
        "",
        "| Card / Condition | R (`inspect`) Pointer-to-Target Wait | B (`limit`) Pointer-to-Target Wait | B Qualification |",
        "|---|---|---|---|",
    ]
    for row in rows:
        b = row.get("B")
        b_qual = b.get("qualification", "—") if b else "—"
        lines.append(
            f"| `{row['label']}` | {fmt_interval(row.get('R'))} | {fmt_interval(b)} | `{b_qual}` |"
        )
    lines.append("")
    return "\n".join(lines)


def render_svg(rows: list[dict], target_s: float = 3.0) -> str:
    width = max(720, 140 + len(rows) * 125)
    height = 380
    pad_l, pad_r, pad_t, pad_b = 70, 40, 48, 84
    plot_w = width - pad_l - pad_r
    plot_h = height - pad_t - pad_b

    max_y = max(target_s * 1.4, 4.0)
    for row in rows:
        for cond in ("R", "B"):
            e = row.get(cond)
            if e and e.get("ptr_interval_s"):
                max_y = max(max_y, float(e["ptr_interval_s"][1]) * 1.15)
    # Round max_y cleanly to next integer >= 4
    max_y = float(int(max_y) + 1)

    def y_px(val: float) -> float:
        return pad_t + plot_h * (1.0 - max(0.0, min(val, max_y)) / max_y)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#0f172a"/>',
        f'<text x="{pad_l}" y="28" fill="#f8fafc" font-family="Segoe UI, Arial, sans-serif" font-size="16" font-weight="600">'
        'ANEB Business-Video R/B Startup Wait Intervals (Pointer-to-Target)</text>',
    ]

    # Grid lines
    step = 1.0 if max_y <= 6 else (2.0 if max_y <= 14 else 5.0)
    v = 0.0
    while v <= max_y + 1e-6:
        yy = y_px(v)
        parts.append(f'<line x1="{pad_l}" y1="{yy:.1f}" x2="{width - pad_r}" y2="{yy:.1f}" stroke="#1e293b" stroke-width="1"/>')
        parts.append(f'<text x="{pad_l - 10}" y="{yy + 4:.1f}" text-anchor="end" fill="#94a3b8" font-family="Segoe UI, Arial, sans-serif" font-size="11">{v:.0f}s</text>')
        v += step

    # 3.0s target dashed line
    ty = y_px(target_s)
    parts.append(f'<line x1="{pad_l}" y1="{ty:.1f}" x2="{width - pad_r}" y2="{ty:.1f}" stroke="#f59e0b" stroke-width="1.5" stroke-dasharray="6,4"/>')
    parts.append(f'<text x="{width - pad_r - 4}" y="{ty - 6:.1f}" text-anchor="end" fill="#fbbf24" font-family="Segoe UI, Arial, sans-serif" font-size="11">Target <= {target_s:.1f}s</text>')

    # Legend
    lx = width - pad_r - 220
    parts.append(f'<circle cx="{lx}" cy="24" r="5" fill="#38bdf8"/><text x="{lx + 10}" y="28" fill="#cbd5e1" font-family="Segoe UI, Arial, sans-serif" font-size="11">R (inspect)</text>')
    parts.append(f'<circle cx="{lx + 85}" cy="24" r="5" fill="#f43f5e"/><text x="{lx + 95}" y="28" fill="#cbd5e1" font-family="Segoe UI, Arial, sans-serif" font-size="11">B (limit)</text>')
    parts.append(f'<text x="{lx + 155}" y="28" fill="#94a3b8" font-family="Segoe UI, Arial, sans-serif" font-size="11">× NA</text>')

    n = max(1, len(rows))
    for idx, row in enumerate(rows):
        cx = pad_l + plot_w * (idx + 0.5) / n
        lbl = html.escape(row["label"])
        parts.append(f'<text x="{cx:.1f}" y="{height - pad_b + 24}" text-anchor="middle" fill="#e2e8f0" font-family="Segoe UI, Arial, sans-serif" font-size="11">{lbl}</text>')

        for cond, dx, color in (("R", -16, "#38bdf8"), ("B", 16, "#f43f5e")):
            e = row.get(cond)
            if not e:
                continue
            iv = e.get("ptr_interval_s")
            x = cx + dx
            if iv and len(iv) == 2:
                y_lo = y_px(float(iv[0]))
                y_hi = y_px(float(iv[1]))
                y_mid = (y_lo + y_hi) / 2.0
                parts.append(f'<line x1="{x:.1f}" y1="{y_lo:.1f}" x2="{x:.1f}" y2="{y_hi:.1f}" stroke="{color}" stroke-width="3"/>')
                parts.append(f'<circle cx="{x:.1f}" cy="{y_mid:.1f}" r="5" fill="{color}"/>')
                parts.append(f'<text x="{x:.1f}" y="{min(y_lo, y_hi) - 8:.1f}" text-anchor="middle" fill="{color}" font-family="Segoe UI, Arial, sans-serif" font-size="10">{iv[1]:.2f}s</text>')
            else:
                yy = y_px(0.25)
                parts.append(f'<text x="{x:.1f}" y="{yy:.1f}" text-anchor="middle" fill="#94a3b8" font-family="Segoe UI, Arial, sans-serif" font-size="12" font-weight="700">×</text>')
                parts.append(f'<text x="{x:.1f}" y="{yy - 12:.1f}" text-anchor="middle" fill="#94a3b8" font-family="Segoe UI, Arial, sans-serif" font-size="9">NA</text>')

    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def main():
    ap = argparse.ArgumentParser(description="Summarize verified ANEB business-video R/B review directories")
    ap.add_argument("--card", action="append", required=True, help="LABEL=REVIEW_DIR (repeatable)")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--target-s", type=float, default=3.0)
    args = ap.parse_args()

    specs = []
    for item in args.card:
        if "=" not in item:
            raise SystemExit(f"Expected LABEL=REVIEW_DIR, got: {item}")
        label, path_str = item.split("=", 1)
        specs.append((label.strip(), Path(path_str.strip())))

    rows = summarize_cards(specs)
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "campaign-summary.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "campaign-table.md").write_text(render_markdown(rows), encoding="utf-8")
    (out / "inflection-curve.svg").write_text(render_svg(rows, target_s=args.target_s), encoding="utf-8")
    print(f"Wrote {len(rows)} cards to {out}")


if __name__ == "__main__":
    main()
