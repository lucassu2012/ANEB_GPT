import importlib.util
from pathlib import Path
import unittest
from html.parser import HTMLParser
import json
import subprocess
import sys
import tempfile

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'research_report.py'


class ReportTests(unittest.TestCase):
    def render(self, data):
        spec = importlib.util.spec_from_file_location('research_report', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.render_report(data)

    def sample(self):
        return {'record_kind': 'SAMPLE', 'source_sha256': 'a' * 64,
                'counts': {'planned': 3, 'attempted': 2, 'not_run': 1,
                           'visible_completed_confirmed': 1}, 'groups': [],
                'records': [{'attempt_id': 'R1', 'input_record': {'app': {'name': 'Kimi'}},
                             'ttfr': {'status': 'interval', 'interval_s': [0.76, 0.84]},
                             'ttfc': {'status': 'uncertain', 'interval_s': None},
                             'completion': {'status': 'NA', 'interval_s': None},
                             'stalls': {'status': 'partial', 'intervals': [],
                                        'total_count': None, 'max_interval_s': None}}]}

    def test_consumes_derived_values_without_recomputing(self):
        page = self.render(self.sample())
        for text in ['SAMPLE', 'Kimi', 'R1', '0.76–0.84 秒', 'NA', 'uncertain',
                     '计划：3', '发送/尝试：2', '未执行：1', '已确认完成：1', 'a' * 64]:
            self.assertIn(text, page)

    def test_private_record_is_not_dumped_and_hostile_text_is_inert(self):
        data = self.sample()
        data['record_kind'] = 'OBSERVED'
        record = data['records'][0]['input_record']
        record.update(action_text='PRIVATE_PROMPT', api_key='PRIVATE_KEY',
                      evidence={'local_ref': '<img src="https://bad">', 'sha256': 'b' * 64})
        record['app']['name'] = '<script>alert(1)</script>'
        page = self.render(data)
        self.assertIn('&lt;script&gt;', page)
        self.assertIn('&lt;img', page)
        self.assertIn('声明，不是真实性认证', page)
        for secret in ['PRIVATE_PROMPT', 'PRIVATE_KEY']:
            self.assertNotIn(secret, page)
        class Tags(HTMLParser):
            def handle_starttag(self, tag, attrs):
                self.tags.append(tag)
                self.attrs.extend(attrs)
        parser = Tags()
        parser.tags, parser.attrs = [], []
        parser.feed(page)
        self.assertFalse(set(parser.tags) & {'script', 'iframe', 'img', 'video', 'audio', 'link'})
        self.assertFalse(any(k in {'src', 'href'} or k.startswith('on') for k, v in parser.attrs))

    def test_stall_and_group_limits_preserve_unknown_and_subset(self):
        data = self.sample()
        data['records'][0]['stalls']['intervals'] = [
            {'classification': 'right_censored', 'interval_s': [2.96, 3.04]}]
        empty = self.render(data)
        for phrase in ['无已确认可比组', 'right_censored', '2.96–3.04 秒', '总停顿次数：NA',
                       '不是完整停顿时长', '不代表网络因果']:
            self.assertIn(phrase, empty)
        data['groups'] = [{'group_id': 'G1', 'metrics': {'ttfr': {
            'valid_n': 3, 'point_n': 2, 'na_n': 1, 'uncertain_n': 0,
            'point_median_s': 2, 'point_range_s': [1, 3],
            'attempts': [{'attempt_id': 'R1', 'status': 'interval', 'interval_s': [1, 1]}]}}}]
        page = self.render(data)
        for phrase in ['G1', 'valid_n：3', 'point_n：2', 'na_n：1', 'uncertain_n：0',
                       '点值子集，不是整组中位数', '2 秒', '1–3 秒']:
            self.assertIn(phrase, page)

    def test_cli_creates_utf8_report_without_overwriting_input_or_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'analysis.json'
            output = Path(directory) / 'report.html'
            original = json.dumps(self.sample()).encode('utf-8')
            source.write_bytes(original)
            command = [sys.executable, '-B', str(SCRIPT), str(source), '-o', str(output)]
            run = subprocess.run(command, capture_output=True)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertTrue(output.exists())
            before = output.read_bytes()
            self.assertIn('离线观察报告', before.decode('utf-8'))
            self.assertFalse(before.startswith(b'\xef\xbb\xbf'))
            again = subprocess.run(command, capture_output=True)
            self.assertNotEqual(0, again.returncode)
            self.assertEqual(before, output.read_bytes())
            self.assertEqual(original, source.read_bytes())

    def test_sample_disclosure_and_not_run_precede_completion_reason(self):
        data = self.sample()
        data['records'][0]['input_record']['outcome'] = {'executed': False, 'status': 'not_run'}
        data['records'][0]['completion']['reason'] = 'completion_unconfirmed'
        page = self.render(data)
        self.assertIn('SAMPLE 演示｜没有真实 App 测量', page)
        self.assertIn('未执行，因此无此指标', page)
        self.assertNotIn('未确认正常完成，完成时长不可用', page)
        self.assertIn('未知／未提供', page)
        self.assertIn('端到端可见体验不隔离网络、模型、服务端与设备贡献', page)

    def test_each_card_translates_outcome_without_conflating_uncertain(self):
        data = self.sample()
        for status, flag, translated, answer in [
                ('completed', 'yes', '已完成（completed）', '是（yes）'),
                ('incomplete', 'uncertain', '未完成（incomplete）', '不确定（uncertain）')]:
            with self.subTest(status=status):
                data['records'][0]['input_record']['outcome'] = {
                    'executed': True, 'status': status,
                    'visible_completion': flag, 'instruction_following': flag}
                page = self.render(data)
                card = page.split('<section>', 1)[1].split('</section>', 1)[0]
                self.assertIn('执行状态：' + translated, card)
                self.assertIn('可见完成：' + answer, card)
                self.assertIn('指令满足：' + answer, card)
                self.assertNotIn('失败', card)

    def test_censored_and_uncomputed_explanations_are_inside_attempt_card(self):
        data = self.sample()
        row = data['records'][0]
        row['stalls']['intervals'] = [{'classification': 'right_censored', 'interval_s': [2.96, 3.04]}]
        card = self.render(data).split('<section>', 1)[1].split('</section>', 1)[0]
        for phrase in ['right_censored', '2.96–3.04 秒',
                       '未观察到恢复的尾段，非完整停顿', '总停顿次数：NA（未知）', '最长停顿：NA（未知）']:
            self.assertIn(phrase, card)
        row['input_record']['outcome'] = {'executed': False, 'status': 'not_run'}
        row['stalls'] = {'status': 'not_computed', 'intervals': [], 'confirmed_observed_count': 0,
                         'total_count': None, 'max_interval_s': None}
        card = self.render(data).split('<section>', 1)[1].split('</section>', 1)[0]
        self.assertIn('停顿未计算（not_computed）', card)
        self.assertIn('已确认片段数：0', card)
        self.assertIn('未执行、未计算，不代表无停顿', card)


if __name__ == '__main__':
    unittest.main()
