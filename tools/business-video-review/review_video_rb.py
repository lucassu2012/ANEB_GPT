#!/usr/bin/env python3
"""
ANEB Offline Independent Business-Video R/B Media Review Tool.

Reads immutable raw card artifacts (phase-{R,B}/device-original/video.mp4,
result.json, supervisor.json, unicast-delta.json) without modifying originals:
  1. Verifies H.264 stream metadata & strictly increasing 1/90000 PTS via ffprobe.
  2. Auto-detects 16:9 letterbox vs 9:16 portrait video bounds after swipe settle
     to strictly exclude top black-bar danmaku, status bar, pointer bar, right-side
     creator icons, bottom caption, and vertical x=600 pointer trace.
  3. Performs in-memory scan (0 PNGs during scan) to pinpoint:
       - A: first visible pointer crosshair / bar activation
       - S: first visible feed-item upward translation
       - Settle: panel scroll completion
       - B: first pure-video intrinsic motion onset (excluding letterbox danmaku)
       - Post-settle static/frozen video intervals (observational only; formal
         stall_status stays 'not_computed' per business-video-review-1).
  4. Extracts bounded candidate boundary PNGs (<= 24 PNGs per video) using
     `select=eq(n,...) -fps_mode passthrough` for mandatory visual inspection.
"""
import argparse
import csv
import hashlib
import json
import os
import subprocess
import sys
from decimal import Decimal
import numpy as np


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def detect_letterbox_y_bounds(frame_rgb: np.ndarray) -> tuple[int, int, str]:
    """Detect whether a settled 300x660 frame is 16:9 letterboxed or full portrait.
    Returns (y_lo, y_hi, layout_kind) in 300x660 coordinates, strictly inside video."""
    # Ignore status+pointer+top tabs (y<75) and bottom nav+caption (y>500)
    # Also exclude vertical x=142..158 pointer trace and right icons x>235
    left = frame_rgb[:, 20:138, :].astype(np.float32)
    right = frame_rgb[:, 162:235, :].astype(np.float32)
    band = np.concatenate([left, right], axis=1)
    row_mean = band.mean(axis=(1, 2))
    row_p90 = np.percentile(band, 90, axis=(1, 2))
    # Active video row has non-black luminance across the row (avoid danmaku text spikes by using median)
    row_med = np.median(band, axis=(1, 2))
    active = np.where((row_med[75:500] > 8.0) & (row_mean[75:500] > 10.0))[0] + 75
    if len(active) >= 80:
        ylo, yhi = int(active[0]), int(active[-1])
        span = yhi - ylo
        if 120 <= span <= 240 and ylo >= 160:
            # 16:9 letterboxed video: pad inward 8px to stay 100% clear of letterbox edge/danmaku
            return ylo + 8, yhi - 8, f"letterbox_16_9(y={ylo*4}..{yhi*4})"
    # Full portrait video: exclude top danmaku/tabs (y<190 => 760px) & bottom caption/nav (y>450 => 1800px)
    return 190, 450, "portrait_full(y=760..1800)"


def scan_condition(card_dir: str, cond: str, out_dir: str, ffprobe: str, ffmpeg: str) -> dict:
    pdir = os.path.join(card_dir, "runtime", f"phase-{cond}")
    dev = os.path.join(pdir, "device-original")
    mp4 = os.path.join(dev, "video.mp4")
    if not os.path.isfile(mp4):
        raise FileNotFoundError(f"Missing {mp4}")

    os.makedirs(out_dir, exist_ok=True)
    probe_out = os.path.join(out_dir, f"probe-frames-{cond}.json")
    probe_err = os.path.join(out_dir, f"probe-frames-{cond}.stderr.txt")
    res = subprocess.run(
        [ffprobe, "-v", "error", "-select_streams", "v:0",
         "-show_format", "-show_streams", "-show_frames", "-of", "json", mp4],
        capture_output=True,
    )
    with open(probe_out, "wb") as f:
        f.write(res.stdout)
    with open(probe_err, "wb") as f:
        f.write(res.stderr)
    if res.returncode != 0:
        raise RuntimeError(f"ffprobe failed {cond}: {res.stderr.decode(errors='replace')}")

    probe = json.loads(res.stdout.decode("utf-8"))
    stream = probe["streams"][0]
    frames_meta = probe["frames"]
    n_frames = len(frames_meta)
    tb = stream["time_base"]
    if tb != "1/90000":
        raise RuntimeError(f"Unexpected time_base {tb}")
    pts_list = [int(fr["pts"]) for fr in frames_meta]
    dur_list = [int(fr.get("duration", fr.get("pkt_duration", 0))) for fr in frames_meta]
    strict_inc = all(pts_list[i] > pts_list[i - 1] for i in range(1, n_frames))

    # Pass 1: fast 300x660 scan for A (pointer bar), S (caption scroll), settle, and settled frame layout
    sw, sh = 300, 660
    fbytes = sw * sh * 3
    err_path = os.path.join(out_dir, f"scan-{cond}.stderr.txt")
    frames_small = []
    with open(err_path, "wb") as ef:
        proc = subprocess.Popen(
            [ffmpeg, "-nostdin", "-v", "error", "-i", mp4,
             "-vf", f"scale={sw}:{sh}:flags=area",
             "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"],
            stdout=subprocess.PIPE, stderr=ef, bufsize=fbytes * 2,
        )
        while True:
            buf = proc.stdout.read(fbytes)
            if not buf:
                break
            if len(buf) != fbytes:
                raise RuntimeError(f"Short frame read {cond}")
            frames_small.append(np.frombuffer(buf, dtype=np.uint8).reshape((sh, sw, 3)))
        rc = proc.wait()
        if rc != 0 or len(frames_small) != n_frames:
            raise RuntimeError(f"ffmpeg scan failed {cond}: rc={rc} got={len(frames_small)} want={n_frames}")

    ptr0 = frames_small[0][39:49, 0:200, :].astype(np.int16)
    ptr_diffs0 = []
    ptr_dprev = []
    cap_diffs = []
    prev_pb = ptr0
    prev_cap = frames_small[0][500:590, 5:220, :].astype(np.int16)
    for i, arr in enumerate(frames_small):
        # Strict pointer_location top strip y=39..49 (orig 156..196), x=0..200 (P:, dX:, dY:, Xv:, Yv:)
        # Strictly below status bar (y<38) and left of static Prs/Size boxes
        pb = arr[39:49, 0:200, :].astype(np.int16)
        cp = arr[500:590, 5:220, :].astype(np.int16)
        ptr_diffs0.append(float(np.mean(np.abs(pb - ptr0))))
        ptr_dprev.append(0.0 if i == 0 else float(np.mean(np.abs(pb - prev_pb))))
        cap_diffs.append(0.0 if i == 0 else float(np.mean(np.abs(cp - prev_cap))))
        prev_pb = pb
        prev_cap = cp

    # Find the swipe window first via the largest sustained pointer-bar + caption scroll cluster
    # Then backtrack to the exact first pointer activation A (dprev > 2.5 within 60 frames before peak)
    peak_ptr_n = max(range(1, min(n_frames, 400)), key=lambda i: ptr_diffs0[i])
    n_a = None
    for i in range(max(1, peak_ptr_n - 80), peak_ptr_n + 1):
        if ptr_dprev[i] > 2.5:
            # Verify pointer strip remains elevated afterward (not a 1-frame status glitch)
            if max(ptr_diffs0[i:min(n_frames, i + 10)]) > 4.0:
                n_a = i
                break
    # Find S: first frame after A where bottom caption shifts sharply (>15.0 or >2x baseline)
    n_s = None
    if n_a is not None:
        base_cap = float(np.median(cap_diffs[max(1, n_a - 15):n_a])) if n_a > 5 else 0.0
        thresh_s = max(12.0, base_cap * 2.2 + 5.0)
        n_s = next((i for i in range(n_a + 1, min(n_frames, n_a + 25)) if cap_diffs[i] > thresh_s), None)
    # Find settle: LAST sharp caption movement (>3.5) inside [n_a, n_a + 100], followed by stability (<0.8)
    n_settle = None
    if n_a is not None:
        last_scroll = max(
            (i for i in range(n_a + 15, min(n_frames - 6, n_a + 100)) if cap_diffs[i] > 3.5),
            default=n_a + 45,
        )
        n_settle = next(
            (i for i in range(last_scroll + 1, min(n_frames - 5, last_scroll + 20))
             if all(cap_diffs[j] < 0.8 for j in range(i, i + 3))),
            min(n_frames - 1, last_scroll + 2),
        )
    if n_settle is None:
        n_settle = min(n_frames - 1, (n_a or 60) + 65)

    # Detect pure-video Y bounds on settled frame (auto-excludes 16:9 letterbox danmaku!)
    ylo, yhi, layout = detect_letterbox_y_bounds(frames_small[min(n_settle + 5, n_frames - 1)])

    def extract_pure_roi(arr):
        l = arr[ylo:yhi, 25:138, :].astype(np.int16)
        r = arr[ylo:yhi, 162:235, :].astype(np.int16)
        return np.concatenate([l, r], axis=1)

    ref_roi = extract_pure_roi(frames_small[min(n_settle + 2, n_frames - 1)])
    prev_roi = extract_pure_roi(frames_small[0])
    roi_prev_diffs = []
    roi_ref_diffs = []
    rows = []
    for i, arr in enumerate(frames_small):
        cur = extract_pure_roi(arr)
        dp = 0.0 if i == 0 else float(np.mean(np.abs(cur - prev_roi)))
        dr = float(np.mean(np.abs(cur - ref_roi)))
        roi_prev_diffs.append(dp)
        roi_ref_diffs.append(dr)
        rows.append({
            "n": i, "pts": pts_list[i], "pts_s": f"{pts_list[i]/90000:.6f}", "dur": dur_list[i],
            "ptr_diff0": round(ptr_diffs0[i], 3), "cap_diff": round(cap_diffs[i], 3),
            "pure_roi_prev": round(dp, 3), "pure_roi_ref_settle": round(dr, 3),
        })
        prev_roi = cur

    csv_path = os.path.join(out_dir, f"frame-metrics-{cond}.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    # Check if video is already moving right at settle vs frozen on cover frame
    imm_motion = max(roi_prev_diffs[n_settle:min(n_frames, n_settle + 6)]) > 1.2
    if imm_motion:
        b_kind_cand = "CONFIRMED_POST_SETTLE_UPPER_BOUND"
        n_b = next((i for i in range(n_settle, min(n_frames, n_settle + 10)) if roi_prev_diffs[i] > 1.2), n_settle + 1)
    else:
        b_kind_cand = "EXACT_ADJACENT_PAIR"
        # First frame after settle where pure video diverges meaningfully from settled cover (dr > 1.5 and dp > 0.6)
        n_b = next((i for i in range(n_settle + 2, n_frames) if roi_ref_diffs[i] > 1.5 and roi_prev_diffs[i] > 0.6), None)
        if n_b is None:
            # Fallback: sustained drift > 2.5 from settled cover
            n_b = next((i for i in range(n_settle + 2, n_frames) if roi_ref_diffs[i] > 2.5), None)

    # Compute observational static intervals (coalescing single-frame H.264 IDR keyframe bumps at <=1.0 MAD)
    static_segs = []
    cur_s = None
    for i in range(n_settle, n_frames):
        # Static if pure-video diff < 0.25 OR isolated keyframe blip where prev&next are static and ref barely moved
        is_static = roi_prev_diffs[i] < 0.28
        if not is_static and 0 < i < n_frames - 1:
            if roi_prev_diffs[i] < 1.1 and roi_prev_diffs[i - 1] < 0.25 and roi_prev_diffs[i + 1] < 0.25:
                is_static = True
        if is_static:
            if cur_s is None:
                cur_s = i
        else:
            if cur_s is not None:
                dur_ms = (pts_list[i - 1] - pts_list[cur_s]) / 90.0
                if dur_ms >= 300.0:
                    static_segs.append({
                        "start_n": cur_s, "end_n": i - 1,
                        "start_s": round(pts_list[cur_s] / 90000.0, 3),
                        "end_s": round(pts_list[i - 1] / 90000.0, 3),
                        "dur_ms": round(dur_ms, 1),
                    })
                cur_s = None
    if cur_s is not None:
        dur_ms = (pts_list[n_frames - 1] - pts_list[cur_s]) / 90.0
        if dur_ms >= 300.0:
            static_segs.append({
                "start_n": cur_s, "end_n": n_frames - 1,
                "start_s": round(pts_list[cur_s] / 90000.0, 3),
                "end_s": round(pts_list[n_frames - 1] / 90000.0, 3),
                "dur_ms": round(dur_ms, 1),
            })

    # Extract bounded candidate PNGs (<= 16 PNGs) for visual verification
    picks = set()
    for base_n in (n_a, n_s, n_settle, n_b):
        if base_n is not None:
            for d in (-1, 0, 1):
                if 0 <= base_n + d < n_frames:
                    picks.add(base_n + d)
    if n_b is not None and n_b + 8 < n_frames:
        picks.add(n_b + 8)
    picks.add(min(n_frames - 1, n_settle + 180))
    picks.add(n_frames - 1)
    picks = sorted(picks)[:24]

    ddir = os.path.join(out_dir, f"decoded-{cond}")
    os.makedirs(ddir, exist_ok=True)
    expr = "+".join(f"eq(n\\,{i})" for i in picks)
    tmp_pat = os.path.join(ddir, "tmp-%03d.png")
    dec_err = os.path.join(out_dir, f"decode-{cond}.stderr.txt")
    with open(dec_err, "wb") as ef:
        rc = subprocess.run(
            [ffmpeg, "-nostdin", "-v", "error", "-i", mp4,
             "-vf", f"select={expr}", "-fps_mode", "passthrough", tmp_pat],
            stderr=ef,
        ).returncode
    if rc != 0:
        raise RuntimeError(f"decode PNG failed {cond}: rc={rc}")
    for k, i in enumerate(picks, 1):
        dst = os.path.join(ddir, f"n{i:04d}.png")
        if os.path.exists(dst):
            os.remove(dst)
        os.rename(os.path.join(ddir, f"tmp-{k:03d}.png"), dst)

    summary = {
        "condition": cond,
        "mp4_bytes": os.path.getsize(mp4),
        "mp4_sha256": sha256_file(mp4),
        "frames": n_frames,
        "strict_monotonic_pts": strict_inc,
        "duration_s": round(pts_list[-1] / 90000.0, 6),
        "detected_layout": layout,
        "cand_A": None if n_a is None else {"neg_n": n_a - 1, "pos_n": n_a, "neg_pts": pts_list[n_a - 1], "pos_pts": pts_list[n_a]},
        "cand_S": None if n_s is None else {"neg_n": n_s - 1, "pos_n": n_s, "neg_pts": pts_list[n_s - 1], "pos_pts": pts_list[n_s]},
        "cand_settle": {"n": n_settle, "pts": pts_list[n_settle], "pts_s": round(pts_list[n_settle] / 90000.0, 4)},
        "cand_B_kind": b_kind_cand if n_b is not None else "UNAVAILABLE",
        "cand_B": None if n_b is None else {"neg_n": n_b - 1, "pos_n": n_b, "neg_pts": pts_list[n_b - 1], "pos_pts": pts_list[n_b]},
        "observational_static_segments": static_segs,
        "observational_static_total_ms": round(sum(s["dur_ms"] for s in static_segs), 1),
        "extracted_pngs": picks,
    }
    if n_a is not None and n_b is not None:
        lo_ptr = round((pts_list[n_b - 1] - pts_list[n_a]) / 90000.0, 6)
        hi_ptr = round((pts_list[n_b] - pts_list[n_a - 1]) / 90000.0, 6)
        summary["cand_ptr_wait_s"] = [max(0.0, lo_ptr), max(0.0, hi_ptr)]
    if n_s is not None and n_b is not None:
        lo_sw = round((pts_list[n_b - 1] - pts_list[n_s]) / 90000.0, 6)
        hi_sw = round((pts_list[n_b] - pts_list[n_s - 1]) / 90000.0, 6)
        summary["cand_sw_wait_s"] = [max(0.0, lo_sw), max(0.0, hi_sw)]
    return summary


def main():
    ap = argparse.ArgumentParser(description="ANEB offline business-video R/B media scanner & bounded extractor")
    ap.add_argument("--card-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--ffprobe", required=True)
    ap.add_argument("--ffmpeg", required=True)
    ap.add_argument("--conditions", nargs="+", default=["R", "B"])
    args = ap.parse_args()

    report = {"card_dir": args.card_dir, "conditions": {}}
    for cond in args.conditions:
        summary = scan_condition(args.card_dir, cond, args.out_dir, args.ffprobe, args.ffmpeg)
        report["conditions"][cond] = summary
        print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)

    out_json = os.path.join(args.out_dir, "scan-summary.json")
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
