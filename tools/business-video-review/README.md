# ANEB Business-Video R/B Offline Media Review Tool

`review_video_rb.py` automates read-only `ffprobe` PTS verification, automatic letterbox/portrait ROI isolation (excluding status bar, `pointer_location` overlay, top black-bar danmaku, right icons, and bottom caption), in-memory transition & static-interval scanning (0 PNGs during scan), and bounded boundary PNG extraction (`<= 24` PNGs via `select=eq(n,...) -fps_mode passthrough`) for mandatory human/independent visual confirmation.

## Usage

```bash
python tools/business-video-review/review_video_rb.py \
  --card-dir <path-to-consumed-card> \
  --out-dir <path-to-separate-review-dir> \
  --ffprobe <path-to-ffprobe> \
  --ffmpeg <path-to-ffmpeg>
```

## Scientific Boundaries
- Never mutates raw `result.json`, `video.mp4`, or consumed card directories.
- Uses stream `time_base = 1/90000` actual `pts` ticks only (no nominal FPS or `best_effort_timestamp`).
- Extracted candidate boundaries (`A`, `S`, `settle`, `B`) MUST still be visually verified on the extracted PNGs before emitting `business-video-review-1` JSON records.
- `observational_static_segments` is descriptive media evidence only; formal `business-video-review-1` `stall_status` stays `"not_computed"`.
