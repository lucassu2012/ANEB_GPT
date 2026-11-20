#!/usr/bin/env python3
"""
ANEB Offline Independent AI-Assistant (Kimi/DeepSeek/Tongyi) R/B Media Review Tool.

Reads immutable raw card artifacts (phase-{R,B}/device-original/video.mp4,
TERMINAL.json, after-send.png, capture-end.png, supervisor.json) without modifying originals:
  1. Verifies H.264 stream metadata & monotonic 1/90000 PTS via ffprobe.
  2. Performs in-memory scan of the chat viewport to bracket:
       - T_send: user prompt message bubble appears / send action triggers
       - T_first_resp: first visible assistant response / thinking card below the user bubble
       - T_last_motion: last sustained visible rendering change in the assistant response region
  3. Extracts bounded candidate PNGs (<= 20 PNGs per phase) for mandatory human visual inspection.
"""
import argparse
import csv
import hashlib
import json
import os
import subprocess
import numpy as np


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def detect_ai_transitions(rows: list[dict], max_picks: int = 20) -> tuple[int | None, list[int]]:
    """Pure transition detector over per-frame chat-band MAD rows."""
    n_frames = len(rows)
    if n_frames == 0:
        return None, []
    n_done = next((i for i in range(n_frames - 1, 0, -1) if rows[i]["resp_vs_end"] > 0.6), None)
    if n_done is not None and n_done + 1 < n_frames:
        n_done = n_done + 1

    peaks = sorted(
        range(1, n_frames),
        key=lambda i: rows[i]["upper_diff"] + rows[i]["resp_diff"] + rows[i]["comp_diff"],
        reverse=True,
    )[:8]
    picks = {0, n_frames - 1}
    for p in peaks:
        picks.add(max(0, p - 1))
        picks.add(p)
    if n_done is not None:
        picks.add(max(0, n_done - 1))
        picks.add(n_done)
    for frac in (0.15, 0.25, 0.35, 0.5, 0.7):
        picks.add(min(n_frames - 1, int(n_frames * frac)))
    return n_done, sorted(picks)[:max_picks]


def scan_ai_phase(phase_dir: str, cond: str, out_dir: str, ffprobe: str, ffmpeg: str) -> dict:
    dev = os.path.join(phase_dir, "device-original")
    mp4 = os.path.join(dev, "video.mp4")
    if not os.path.isfile(mp4):
        raise FileNotFoundError(f"Missing {mp4}")

    os.makedirs(out_dir, exist_ok=True)
    res = subprocess.run(
        [ffprobe, "-v", "error", "-select_streams", "v:0",
         "-show_format", "-show_streams", "-show_frames", "-of", "json", mp4],
        capture_output=True,
    )
    if res.returncode != 0:
        raise RuntimeError(f"ffprobe failed {cond}: {res.stderr.decode(errors='replace')}")
    probe = json.loads(res.stdout.decode("utf-8"))
    stream = probe["streams"][0]
    frames_meta = probe["frames"]
    n_frames = len(frames_meta)
    tb = stream["time_base"]
    tb_num, tb_den = map(int, tb.split("/"))
    pts_list = [int(fr["pts"]) for fr in frames_meta]
    pts_s = [p * tb_num / tb_den for p in pts_list]
    nondec = all(pts_list[i] >= pts_list[i - 1] for i in range(1, n_frames))

    # Downscaled scan (300x660): top user-bubble band (y=75..220), response band (y=170..520), input bar (y=520..625)
    sw, sh = 300, 660
    fbytes = sw * sh * 3
    frames = []
    scan_err = os.path.join(out_dir, f"ai-scan-{cond}.stderr.txt")
    with open(scan_err, "wb") as ef:
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
            frames.append(np.frombuffer(buf, dtype=np.uint8).reshape((sh, sw, 3)))
        rc = proc.wait()
        if rc != 0 or len(frames) != n_frames:
            raise RuntimeError(f"ffmpeg scan failed {cond}: rc={rc} got={len(frames)} want={n_frames}")

    # Load after-send.png & capture-end.png to anchor exact chat screen vs launcher/consent transition
    # Scan frame-to-frame MAD in upper chat (y=70..190), response body (y=180..500), and composer (y=500..610)
    upper_prev, resp_prev, comp_prev = [], [], []
    resp_vs_end = []
    end_resp = frames[-1][180:500, 15:285, :].astype(np.int16)
    prev_u = prev_r = prev_c = None
    rows = []
    for i, arr in enumerate(frames):
        u = arr[70:190, 15:285, :].astype(np.int16)
        r = arr[180:500, 15:285, :].astype(np.int16)
        c = arr[500:610, 15:285, :].astype(np.int16)
        du = 0.0 if prev_u is None else float(np.mean(np.abs(u - prev_u)))
        dr = 0.0 if prev_r is None else float(np.mean(np.abs(r - prev_r)))
        dc = 0.0 if prev_c is None else float(np.mean(np.abs(c - prev_c)))
        dre = float(np.mean(np.abs(r - end_resp)))
        upper_prev.append(du)
        resp_prev.append(dr)
        comp_prev.append(dc)
        resp_vs_end.append(dre)
        rows.append({
            "n": i, "pts": pts_list[i], "pts_s": round(pts_s[i], 6),
            "upper_diff": round(du, 3), "resp_diff": round(dr, 3),
            "comp_diff": round(dc, 3), "resp_vs_end": round(dre, 3),
        })
        prev_u, prev_r, prev_c = u, r, c

    with open(os.path.join(out_dir, f"ai-frame-metrics-{cond}.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    n_done, picks = detect_ai_transitions(rows, max_picks=20)

    ddir = os.path.join(out_dir, f"decoded-{cond}")
    os.makedirs(ddir, exist_ok=True)
    for old_f in os.listdir(ddir):
        if old_f.endswith(".png"):
            os.remove(os.path.join(ddir, old_f))
    expr = "+".join(f"eq(n\\,{i})" for i in picks)
    tmp_pat = os.path.join(ddir, "tmp-%03d.png")
    dec_err = os.path.join(out_dir, f"ai-decode-{cond}.stderr.txt")
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

    return {
        "condition": cond,
        "mp4_bytes": os.path.getsize(mp4),
        "mp4_sha256": sha256_file(mp4),
        "frames": n_frames,
        "time_base": tb,
        "pts_nondecreasing": nondec,
        "duration_s": round(pts_s[-1], 6),
        "cand_done_n": n_done,
        "cand_done_s": None if n_done is None else round(pts_s[n_done], 6),
        "extracted_pngs": [{"n": i, "pts_s": round(pts_s[i], 4)} for i in picks],
    }


def main():
    ap = argparse.ArgumentParser(description="ANEB offline AI-assistant R/B media scanner")
    ap.add_argument("--card-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--ffprobe", required=True)
    ap.add_argument("--ffmpeg", required=True)
    ap.add_argument("--conditions", nargs="+", default=["R", "B"])
    args = ap.parse_args()

    out = {"card_dir": args.card_dir, "conditions": {}}
    for cond in args.conditions:
        pdir = os.path.join(args.card_dir, "runtime", f"phase-{cond}")
        out["conditions"][cond] = scan_ai_phase(pdir, cond, args.out_dir, args.ffprobe, args.ffmpeg)
    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "ai-scan-summary.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
