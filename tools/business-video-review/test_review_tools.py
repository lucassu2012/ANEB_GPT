import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import review_video_rb
import summarize_rb_campaign


class ReviewToolsTest(unittest.TestCase):
    def test_classify_static_photo_and_carousel_vs_video(self):
        # Single static photo: 1 step transition, rest ~0.0
        static_diffs = [0.05] * 100 + [2.8] + [0.02] * 700
        res_static = review_video_rb.classify_post_settle_content(static_diffs, 50)
        self.assertEqual(res_static["advisory"], "POSSIBLE_NON_VIDEO_STATIC_OR_CAROUSEL")
        self.assertLess(res_static["max_active_60f"], 15)

        # Photo carousel: 3 sparse 2-frame slide switches across 700 frames
        carousel_diffs = [0.05] * 750
        for idx in (180, 181, 400, 401, 620, 621):
            carousel_diffs[idx] = 4.5
        res_car = review_video_rb.classify_post_settle_content(carousel_diffs, 50)
        self.assertEqual(res_car["advisory"], "POSSIBLE_NON_VIDEO_STATIC_OR_CAROUSEL")

        # 30fps genuine video recorded at 60Hz (alternating active/dup frames)
        video_diffs = [0.05] * 100 + [1.2 if i % 2 == 0 else 0.03 for i in range(600)]
        res_vid = review_video_rb.classify_post_settle_content(video_diffs, 50)
        self.assertEqual(res_vid["advisory"], "LIKELY_CONTINUOUS_VIDEO")
        self.assertGreaterEqual(res_vid["max_active_60f"], 25)

    def test_summarize_campaign_generates_md_json_and_svg(self):
        with tempfile.TemporaryDirectory() as tmp:
            td = Path(tmp)
            card1 = td / "card1"
            card1.mkdir()
            def write_rev(p: Path, qual: str, iv, ev: str):
                p.write_text(json.dumps({
                    "format": "business-video-review-1",
                    "records": [{
                        "attempt_id": "run-1",
                        "qualification": qual,
                        "stall_status": "not_computed",
                        "metrics": {
                            "visible_pointer_to_target_wait": {
                                "status": "OBSERVED" if iv else "NA",
                                "interval_s": iv,
                                "target_s": 3.0,
                                "target_evaluation": ev,
                            },
                            "visible_switch_wait": {
                                "status": "OBSERVED" if iv else "NA",
                                "interval_s": iv,
                            },
                        },
                    }],
                }), encoding="utf-8")
            write_rev(card1 / "review-R.json", "QUALIFIED", [0.95, 0.99], "MET")
            write_rev(card1 / "review-B.json", "NOT_QUALIFIED", None, "NA")

            rows = summarize_rb_campaign.summarize_cards([("1M B->R", card1)])
            md = summarize_rb_campaign.render_markdown(rows)
            svg = summarize_rb_campaign.render_svg(rows)
            self.assertIn("1M B->R", md)
            self.assertIn("[0.950, 0.990]s (MET)", md)
            self.assertIn("NA (NOT_QUALIFIED)", md)
            self.assertIn("<svg", svg)
            self.assertIn("Target <= 3.0s", svg)


if __name__ == "__main__":
    unittest.main()
