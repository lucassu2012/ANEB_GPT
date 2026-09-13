"""Public offline CLI tests, entirely synthetic video observations."""
import hashlib
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'research_video.py'


def event(lo, hi, clock='sample-original-pts'):
    return {'clock_id': clock, 'pts_s': [lo, hi]}


def sample():
    return {'format': 'research-video-1', 'record_kind': 'SAMPLE', 'app': 'SAMPLE Video',
            'evidence': [{'reference': 'not-read.mp4', 'sha256': 'a' * 64}],
            'attempts': [{'slot': 'R1', 'status': 'EXECUTED',
                          'V0': event(10, 10.1), 'VF': event(12.3, 12.5),
                          'window_end': event(40.1, 40.2),
                          'visible_target_playback': 'yes', 'window_complete': 'yes',
                          'reason': 'SAMPLE only, motion confirmed synthetically'}]}


class VideoCliTest(unittest.TestCase):
    def invoke(self, root, payload):
        raw = (json.dumps(payload, ensure_ascii=False) + '\n').encode('utf-8')
        source = root / 'input.json'
        source.write_bytes(raw)
        result = subprocess.run([sys.executable, '-B', str(SCRIPT), str(source),
                                 '--output-json', str(root / 'analysis.json'),
                                 '--output-html', str(root / 'report.html')],
                                capture_output=True, timeout=10)
        return result, raw

    def test_sample_visible_open_to_first_frame_interval_and_window_page(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = sample()
            result, raw = self.invoke(root, payload)
            self.assertEqual(result.returncode, 0, result.stderr)
            output = json.loads((root / 'analysis.json').read_text(encoding='utf-8'))
            self.assertEqual(output['record_kind'], 'SAMPLE')
            self.assertEqual(output['source_sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(output['input_record'], payload)
            row = output['attempts'][0]
            self.assertEqual(row['first_frame_wait'],
                             {'status': 'valid', 'interval_s': [2.2, 2.5], 'reason': None})
            self.assertEqual(row['window']['planned_end_pts_s'], [40, 40.1])
            self.assertEqual(row['window']['status'], 'observed_complete')
            page = (root / 'report.html').read_text(encoding='utf-8')
            for text in ['SAMPLE', '可见打开至首画面', '[2.2, 2.5]',
                         '30秒观察窗', '不是播放完成或有效播放时长',
                         '缓冲/暂停：未计算', '文本TTFC/T3：不适用']:
                self.assertIn(text, page)
            self.assertFalse((root / 'not-read.mp4').exists())

    def test_blocked_observations_are_three_not_run_not_playback_failures(self):
        payload = {'record_kind': 'video_observation_preflight_blocked',
                   'app': 'Synthetic blocked App', 'planned_slots': 3,
                   'actual_measured_opens': 0, 'not_run': 3,
                   'attempts': [{'slot': 'R' + str(i), 'status': 'NOT_RUN',
                                 'V0': None, 'VF': None, 'window_end': None,
                                 'window_complete': 'not_started',
                                 'reason': 'tool policy <blocked>',
                                 'visible_target_playback': 'not_observed'}
                                for i in range(1, 4)]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, raw = self.invoke(root, payload)
            self.assertEqual(result.returncode, 0, result.stderr)
            output = json.loads((root / 'analysis.json').read_text(encoding='utf-8'))
            self.assertEqual(output['record_kind'], 'OBSERVED')
            self.assertEqual(output['input_record'], payload)
            self.assertEqual(output['counts'], {'planned': 3, 'executed': 0, 'not_run': 3})
            for row in output['attempts']:
                self.assertEqual(row['first_frame_wait'],
                                 {'status': 'NA', 'interval_s': None, 'reason': 'NOT_RUN'})
                self.assertIsNone(row['window']['planned_end_pts_s'])
                self.assertEqual(row['window']['status'], 'not_started')
            page = (root / 'report.html').read_text(encoding='utf-8')
            for text in ['OBSERVED', 'NOT_RUN', '未执行不等于播放失败', 'tool policy &lt;blocked&gt;']:
                self.assertIn(text, page)
            self.assertNotIn('0 秒', page)
            self.assertNotIn('None', page)
            self.assertIn('计划终点 PTS（秒，非实测终点）：不可计算（NA）', page)

    def test_missing_endpoint_remains_na_not_window_length_or_zero(self):
        for missing in ['VF', 'V0']:
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                payload = sample()
                payload['attempts'][0][missing] = None
                result, raw = self.invoke(root, payload)
                self.assertEqual(result.returncode, 0, result.stderr)
                output = json.loads((root / 'analysis.json').read_text(encoding='utf-8'))
                row = output['attempts'][0]
                self.assertEqual(row['first_frame_wait'],
                                 {'status': 'NA', 'interval_s': None, 'reason': missing + '_MISSING'})
                self.assertEqual(output['counts']['executed'], 1)
                if missing == 'V0':
                    self.assertIsNone(row['window']['planned_end_pts_s'])
                    self.assertEqual(row['window']['status'], 'NA')
                else:
                    self.assertEqual(row['window']['planned_end_pts_s'], [40, 40.1])
                page = (root / 'report.html').read_text(encoding='utf-8')
                self.assertIn(missing + '_MISSING', page)
                self.assertNotIn('30.0 秒', page)

    def test_first_frame_clock_and_order_uncertainty_never_becomes_a_number(self):
        cases = [(event(12, 13, 'other-pts'), 'uncertain', 'CLOCK_MISMATCH'),
                 (event(10.05, 11), 'uncertain', 'ORDER_OVERLAP'),
                 (event(8, 9), 'NA', 'ORDER_REVERSED')]
        for first, status, reason in cases:
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as temporary:
                payload = sample()
                payload['attempts'][0]['VF'] = first
                root = Path(temporary)
                result, _ = self.invoke(root, payload)
                self.assertEqual(result.returncode, 0, result.stderr)
                output = json.loads((root / 'analysis.json').read_text(encoding='utf-8'))
                self.assertEqual(output['attempts'][0]['first_frame_wait'],
                                 {'status': status, 'interval_s': None, 'reason': reason})
                self.assertIn(reason, (root / 'report.html').read_text(encoding='utf-8'))

    def test_window_state_requires_observed_end_and_explicit_coverage(self):
        cases = [(None, 'yes', 'NA', 'WINDOW_END_MISSING'),
                 (event(9, 11), 'no', 'uncertain', 'WINDOW_ORDER_OVERLAP'),
                 (event(41, 42, 'other'), 'yes', 'uncertain', 'WINDOW_CLOCK_MISMATCH'),
                 (event(20, 20.1), 'no', 'observed_partial', 'WINDOW_INCOMPLETE'),
                 (event(20, 20.1), 'yes', 'uncertain', 'WINDOW_CLAIM_CONFLICT'),
                 (event(40.05, 40.2), 'yes', 'uncertain', 'WINDOW_BOUNDARY_UNCERTAIN'),
                 (event(41, 42), 'uncertain', 'uncertain', 'WINDOW_COVERAGE_UNCERTAIN')]
        for end, declared, status, reason in cases:
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                payload = sample()
                payload['attempts'][0].update(window_end=end, window_complete=declared)
                result, _ = self.invoke(root, payload)
                self.assertEqual(result.returncode, 0, result.stderr)
                row = json.loads((root / 'analysis.json').read_text(encoding='utf-8'))['attempts'][0]
                self.assertEqual(row['window'], {'planned_end_pts_s': [40, 40.1],
                                               'status': status, 'reason': reason})
                self.assertEqual(row['first_frame_wait']['interval_s'], [2.2, 2.5])
                self.assertEqual(row['buffering_pause'], {'status': 'not_computed', 'count': None, 'duration_s': None})

    def test_invalid_pts_or_unknown_clock_cannot_produce_wait(self):
        bad_events = [event(13, 12), event(-1, 1), event(True, 12), event('12', 13),
                      event(12, 13, ''), event(12, 13, 'UNKNOWN')]
        for first in bad_events:
            with self.subTest(first=first), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                payload = sample()
                payload['attempts'][0]['VF'] = first
                result, _ = self.invoke(root, payload)
                self.assertEqual(result.returncode, 0, result.stderr)
                row = json.loads((root / 'analysis.json').read_text(encoding='utf-8'))['attempts'][0]
                self.assertEqual(row['first_frame_wait'],
                                 {'status': 'NA', 'interval_s': None, 'reason': 'VF_INVALID'})

    def test_non_json_numeric_literals_are_rejected_before_output(self):
        for value in [float('nan'), float('inf')]:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                payload = sample()
                payload['attempts'][0]['VF'] = event(value, value)
                result, _ = self.invoke(root, payload)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b'VIDEO_INPUT_INVALID', result.stderr)
                self.assertFalse((root / 'analysis.json').exists())
                self.assertFalse((root / 'report.html').exists())

    def test_outputs_never_overwrite_each_other_source_or_existing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'input.json'
            original = json.dumps(sample()).encode()
            source.write_bytes(original)
            existing = root / 'existing.html'
            existing.write_bytes(b'KEEP')
            for out_json, out_html in [(source, root / 'new.html'),
                                       (root / 'new.json', existing),
                                       (root / 'same', root / 'same')]:
                result = subprocess.run([sys.executable, '-B', str(SCRIPT), str(source),
                                         '--output-json', str(out_json), '--output-html', str(out_html)],
                                        capture_output=True, timeout=10)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b'VIDEO_OUTPUT_EXISTS_OR_COLLISION', result.stderr)
                self.assertEqual(source.read_bytes(), original)
                self.assertEqual(existing.read_bytes(), b'KEEP')
                self.assertFalse((root / 'new.json').exists())
                self.assertFalse((root / 'new.html').exists())
                self.assertFalse((root / 'same').exists())

    def test_wrong_format_status_duplicate_slots_and_not_run_conflict_reject(self):
        cases = []
        for key, value in [('format', 'text-alignment-1'), ('record_kind', 'UNKNOWN')]:
            payload = sample()
            payload[key] = value
            cases.append(payload)
        payload = sample()
        payload['attempts'][0]['status'] = 'SUCCESS'
        cases.append(payload)
        payload = sample()
        payload['attempts'].append(copy.deepcopy(payload['attempts'][0]))
        cases.append(payload)
        payload = sample()
        payload['attempts'][0]['status'] = 'NOT_RUN'
        cases.append(payload)  # Nonnull execution facts cannot be silently counted as NOT_RUN.
        for payload in cases:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                result, _ = self.invoke(root, payload)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b'VIDEO_INPUT_INVALID', result.stderr)
                self.assertFalse((root / 'analysis.json').exists())
                self.assertFalse((root / 'report.html').exists())

    def test_target_confirmation_and_window_time_reversal_are_explicit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = sample()
            payload['attempts'][0].update(visible_target_playback='uncertain',
                                          window_end=event(1, 2), window_complete='no')
            result, _ = self.invoke(root, payload)
            self.assertEqual(result.returncode, 0, result.stderr)
            output = json.loads((root / 'analysis.json').read_text(encoding='utf-8'))
            row = output['attempts'][0]
            self.assertEqual(row['first_frame_wait'], {'status': 'uncertain', 'interval_s': None,
                                                      'reason': 'TARGET_PLAYBACK_UNCONFIRMED'})
            self.assertEqual(row['window']['status'], 'NA')
            self.assertEqual(row['window']['reason'], 'WINDOW_ORDER_REVERSED')

    def test_chinese_page_shows_uncertainty_raw_source_and_offline_boundaries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = sample()
            payload['app'] = '<script>private</script>'
            payload['attempts'][0]['VF'] = event(11, 12, 'other')
            result, raw = self.invoke(root, payload)
            self.assertEqual(result.returncode, 0, result.stderr)
            page = (root / 'report.html').read_text(encoding='utf-8')
            for text in ['不确定（uncertain）', '时钟不一致', '原始记录',
                         'SAMPLE 演示', '实际执行', '来源 SHA256',
                         hashlib.sha256(raw).hexdigest(), '&lt;script&gt;',
                         "default-src 'none'"]:
                self.assertIn(text, page)
            self.assertNotIn('<script>', page)
            self.assertNotIn('<video', page)
            self.assertNotIn('<iframe', page)


if __name__ == '__main__':
    unittest.main()
