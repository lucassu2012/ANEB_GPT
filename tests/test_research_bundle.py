import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from html.parser import HTMLParser


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'research_bundle.py'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class BundleTests(unittest.TestCase):
    def video_fixture(self, root, blocked=False, identity='video'):
        if blocked:
            source = {'record_kind': 'video_observation_preflight_blocked', 'app': 'SAMPLE blocked video',
                      'planned_slots': 3, 'actual_measured_opens': 0, 'not_run': 3,
                      'attempts': [{'slot': 'R' + str(i), 'status': 'NOT_RUN', 'V0': None, 'VF': None,
                                    'window_end': None, 'window_complete': 'not_started',
                                    'visible_target_playback': 'not_observed',
                                    'reason': 'opening blocked <not a playback failure>'} for i in range(1, 4)]}
        else:
            source = json.loads((SCRIPT.parents[1] / 'docs/real-app-research/samples/video-observation.sample.json').read_text(encoding='utf-8'))
        path = root / (identity + '-source.json')
        path.write_text(json.dumps(source, ensure_ascii=False), encoding='utf-8')
        analysis_path = root / (identity + '-analysis.json')
        result = subprocess.run([sys.executable, '-B', str(SCRIPT.with_name('research_video.py')), str(path),
                                 '--output-json', str(analysis_path), '--output-html', str(root / (identity + '-standalone.html'))],
                                capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return {'id': identity, 'kind': 'video', 'app': source['app'],
                'source_file': path.name, 'source_sha256': sha(path.read_bytes()),
                'analysis_file': analysis_path.name, 'analysis_sha256': sha(analysis_path.read_bytes())}

    def test_video_sample_or_not_run_has_offline_entry_without_conclusion_card(self):
        for blocked in [False, True]:
            with self.subTest(blocked=blocked), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                entry = self.video_fixture(root, blocked)
                result = self.run_bundle(root, [entry])
                self.assertEqual(result.returncode, 0, result.stderr)
                output = root / 'bundle'
                for key in ('source', 'analysis'):
                    self.assertEqual((output / ('sources/video-' + key + '.json')).read_bytes(),
                                     (root / entry[key + '_file']).read_bytes())
                bound = json.loads((output / 'bundle-manifest.json').read_text(encoding='utf-8'))['apps'][0]
                self.assertEqual(bound['kind'], 'video')
                self.assertNotIn('conclusion_file', bound)
                self.assertFalse((output / 'sources/video-conclusion.md').exists())
                page = (output / 'video.html').read_text(encoding='utf-8')
                index = (output / 'index.html').read_text(encoding='utf-8')
                self.assertIn('video.html', index)
                self.assertIn('index.html', page)
                self.assertIn('可见打开至首画面', page)
                self.assertIn('原始记录', page)
                self.assertNotIn('研究结论卡原文', page)
                if blocked:
                    self.assertIn('视频计划未执行', index)
                    self.assertIn('实际打开0次', index)
                    self.assertIn('opening blocked &lt;not a playback failure&gt;', page)
                    self.assertIn('OBSERVED', page)
                else:
                    self.assertIn('SAMPLE 演示（不计真实观察）', index)
                    self.assertIn('[2.2, 2.5]', page)

    def fixture(self, root, name='SAMPLE App', identity='sample', record_kind='SAMPLE'):
        source = json.dumps({'record_kind': record_kind, 'private_unused': 'synthetic only'}).encode()
        data = {'record_kind': record_kind, 'source_sha256': sha(source),
                'counts': {'planned': 3, 'attempted': 3, 'not_run': 0,
                           'visible_completed_confirmed': 2}, 'groups': [],
                'records': [{'attempt_id': 'SAMPLE-1', 'input_record': {
                    'app': {'name': name}, 'outcome': {'executed': True,
                    'visible_completion': 'yes', 'instruction_following': 'uncertain'}},
                    'ttfr': {'status': 'uncertain', 'interval_s': None},
                    'ttfc': {'status': 'interval', 'interval_s': [2, 3]},
                    'completion': {'status': 'NA', 'interval_s': None,
                                   'reason': 'completion_timing_unavailable'},
                    'stalls': {'status': 'not_computed', 'intervals': [],
                               'total_count': None, 'max_interval_s': None}}]}
        analysis = json.dumps(data).encode()
        card = ('# SAMPLE conclusion\nNot an App ranking.\nAnalysis SHA256: ' + sha(analysis) + '\n').encode()
        entry = {'id': identity, 'app': name, 'conclusion_analysis_sha256': sha(analysis)}
        for key, raw, suffix in [('source', source, 'json'), ('analysis', analysis, 'json'),
                                  ('conclusion', card, 'md')]:
            path = root / (identity + '-' + key + '.' + suffix)
            path.write_bytes(raw)
            entry[key + '_file'] = path.name
            entry[key + '_sha256'] = sha(raw)
        return entry

    def test_explicit_type_and_video_source_slot_kind_binding_reject_mixing(self):
        cases = ['unknown_kind', 'video_as_text', 'text_as_video', 'wrong_app', 'input_copy',
                 'slot', 'duplicate_slot', 'row_status', 'record_kind', 'counts', 'format']
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                entry = self.fixture(root) if case == 'text_as_video' else self.video_fixture(root, True)
                analysis_path = root / entry['analysis_file']
                analysis = json.loads(analysis_path.read_text(encoding='utf-8'))
                if case == 'unknown_kind':
                    entry['kind'] = 'automatic'
                elif case == 'video_as_text':
                    del entry['kind']
                elif case == 'text_as_video':
                    entry['kind'] = 'video'
                elif case == 'wrong_app':
                    entry['app'] = 'another App'
                elif case == 'input_copy':
                    analysis['input_record']['attempts'][0]['reason'] = 'forged'
                elif case == 'slot':
                    analysis['attempts'][0]['slot'] = 'R9'
                elif case == 'duplicate_slot':
                    analysis['attempts'][1]['slot'] = analysis['attempts'][0]['slot']
                elif case == 'row_status':
                    analysis['attempts'][0]['status'] = 'EXECUTED'
                elif case == 'record_kind':
                    analysis['record_kind'] = 'SAMPLE'
                elif case == 'counts':
                    analysis['counts']['executed'] = 3
                elif case == 'format':
                    source = analysis['input_record']
                    source.update(format='text-automatic', record_kind='OBSERVED')
                    new_source = json.dumps(source).encode()
                    (root / entry['source_file']).write_bytes(new_source)
                    analysis['source_sha256'] = entry['source_sha256'] = sha(new_source)
                raw = json.dumps(analysis).encode()
                analysis_path.write_bytes(raw)
                entry['analysis_sha256'] = sha(raw)
                run = self.run_bundle(root, [entry])
                self.assertEqual(run.returncode, 1)
                self.assertIn(b'BUNDLE_INPUT_INVALID:', run.stderr)
                self.assertFalse((root / 'bundle').exists())

    def run_bundle(self, root, entries):
        manifest = root / 'input.json'
        manifest.write_text(json.dumps({'apps': entries}), encoding='utf-8')
        return subprocess.run([sys.executable, '-B', str(SCRIPT), str(manifest),
                               '-o', str(root / 'bundle')], capture_output=True)

    def test_mixed_bundle_preserves_text_bindings_links_and_separates_sample_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            texts = [self.fixture(root, 'Synthetic ' + key, key, 'OBSERVED') for key in ('one', 'two', 'three')]
            entries = texts + [self.video_fixture(root, True), self.video_fixture(root, False, 'sample-video')]
            result = self.run_bundle(root, entries)
            self.assertEqual(result.returncode, 0, result.stderr)
            output = root / 'bundle'
            index = (output / 'index.html').read_text(encoding='utf-8')
            self.assertIn('3款App有文本观察', index)
            self.assertIn('视频计划未执行（3槽，实际打开0次）', index)
            self.assertIn('SAMPLE 演示入口：1（不计真实观察）', index)
            self.assertNotIn('4款实测', index)
            self.assertNotIn('成功率', index)
            expected = {'index.html', 'README.txt', 'bundle-manifest.json', 'SHA256SUMS.txt'}
            for entry in entries:
                expected.add(entry['id'] + '.html')
                for key in (('source', 'analysis', 'conclusion') if entry in texts else ('source', 'analysis')):
                    name = 'sources/' + entry['id'] + '-' + key + ('.md' if key == 'conclusion' else '.json')
                    expected.add(name)
                    self.assertEqual((output / name).read_bytes(), (root / entry[key + '_file']).read_bytes())
            self.assertEqual({p.relative_to(output).as_posix() for p in output.rglob('*') if p.is_file()}, expected)
            for line in (output / 'SHA256SUMS.txt').read_text().splitlines():
                hashed, name = line.split('  ', 1)
                self.assertEqual(hashed, sha((output / name).read_bytes()))
            class Links(HTMLParser):
                def __init__(self):
                    super().__init__()
                    self.links, self.tags = [], []
                def handle_starttag(self, tag, attrs):
                    self.tags.append(tag)
                    self.links.extend(value for key, value in attrs if key in ('href', 'src'))
            for page in output.glob('*.html'):
                parser = Links()
                parser.feed(page.read_text(encoding='utf-8'))
                self.assertFalse(set(parser.tags) & {'script', 'iframe', 'video', 'audio', 'img'})
                for link in parser.links:
                    self.assertNotIn(':', link)
                    self.assertTrue((page.parent / link).is_file())
            # The new route must not make the old text card optional.
            del texts[0]['conclusion_file']
            fresh = root / 'second'
            fresh.mkdir()
            for entry in texts:
                for key in ('source_file', 'analysis_file', 'conclusion_file'):
                    if key in entry:
                        entry[key] = str(root / entry[key])
            run = self.run_bundle(fresh, texts)
            self.assertEqual(run.returncode, 1)
            self.assertFalse((fresh / 'bundle').exists())

    def test_offline_page_combines_existing_metrics_and_bound_conclusion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = self.fixture(root)
            result = self.run_bundle(root, [entry])
            self.assertEqual(result.returncode, 0, result.stderr)
            page = (root / 'bundle/sample.html').read_text(encoding='utf-8')
            for text in ['2–3 秒', 'uncertain', 'NA', 'SAMPLE conclusion', 'Not an App ranking.',
                         '已确认完成：2', '源标注可见完成；完成时长不可计算']:
                self.assertIn(text, page)
            self.assertIn('sample.html', (root / 'bundle/index.html').read_text(encoding='utf-8'))

    def test_missing_or_mismatched_binding_fails_before_output(self):
        cases = [('analysis_sha256', '0' * 64, 'ANALYSIS_HASH_MISMATCH'),
                 ('source_sha256', '0' * 64, 'SOURCE_HASH_MISMATCH'),
                 ('conclusion_sha256', '0' * 64, 'CONCLUSION_HASH_MISMATCH'),
                 ('conclusion_analysis_sha256', '0' * 64, 'CONCLUSION_ANALYSIS_MISMATCH'),
                 ('app', 'Wrong App', 'APP_MISMATCH'),
                 ('conclusion_file', 'missing.md', 'FILE_UNAVAILABLE')]
        for field, value, code in cases:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                entry = self.fixture(root)
                entry[field] = value
                run = self.run_bundle(root, [entry])
                self.assertEqual(run.returncode, 1)
                self.assertEqual(run.stderr.decode().strip(), 'BUNDLE_INPUT_INVALID:' + code)
                self.assertFalse((root / 'bundle').exists())

    def test_card_and_source_must_bind_analysis_not_only_manifest(self):
        for altered, code in [('card', 'CONCLUSION_ANALYSIS_UNBOUND'),
                              ('source', 'ANALYSIS_SOURCE_MISMATCH')]:
            with self.subTest(altered=altered), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                entry = self.fixture(root)
                key = 'conclusion' if altered == 'card' else 'source'
                raw = b'SAMPLE stale card, no current analysis hash' if altered == 'card' else b'{}'
                (root / entry[key + '_file']).write_bytes(raw)
                entry[key + '_sha256'] = sha(raw)
                run = self.run_bundle(root, [entry])
                self.assertEqual(run.returncode, 1)
                self.assertEqual(run.stderr.decode().strip(), 'BUNDLE_INPUT_INVALID:' + code)
                self.assertFalse((root / 'bundle').exists())

    def test_three_app_bundle_is_portable_complete_and_never_follows_media(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entries = [self.fixture(root, 'SAMPLE ' + key, key) for key in ('one', 'two', 'three')]
            (root / 'private.mp4').write_bytes(b'NEVER_COPY')
            card_path = root / entries[0]['conclusion_file']
            card_path.write_bytes(card_path.read_bytes() + b'\n<script>bad()</script>\n![private](private.mp4)\n')
            entries[0]['conclusion_sha256'] = sha(card_path.read_bytes())
            run = self.run_bundle(root, entries)
            self.assertEqual(run.returncode, 0, run.stderr)
            output = root / 'bundle'
            expected = {'index.html', 'README.txt', 'bundle-manifest.json', 'SHA256SUMS.txt'}
            for entry in entries:
                identity = entry['id']
                expected.add(identity + '.html')
                for key, suffix in [('analysis', 'json'), ('source', 'json'), ('conclusion', 'md')]:
                    relative = 'sources/' + identity + '-' + key + '.' + suffix
                    expected.add(relative)
                    self.assertEqual((root / entry[key + '_file']).read_bytes(), (output / relative).read_bytes())
            actual = {p.relative_to(output).as_posix() for p in output.rglob('*') if p.is_file()}
            self.assertEqual(actual, expected)
            checks = (output / 'SHA256SUMS.txt').read_text().splitlines()
            self.assertEqual(len(checks), len(expected) - 1)
            for line in checks:
                hashed, relative = line.split('  ', 1)
                self.assertEqual(hashed, sha((output / relative).read_bytes()))
            class Links(HTMLParser):
                def __init__(self):
                    super().__init__()
                    self.links, self.tags = [], []
                def handle_starttag(self, tag, attrs):
                    self.tags.append(tag)
                    self.links.extend(value for key, value in attrs if key in ('href', 'src'))
            for page in output.glob('*.html'):
                parser = Links()
                parser.feed(page.read_text(encoding='utf-8'))
                self.assertFalse(set(parser.tags) & {'script', 'iframe', 'img', 'video', 'audio'})
                for link in parser.links:
                    self.assertNotIn(':', link)
                    self.assertTrue((page.parent / link).is_file(), link)
            index = (output / 'index.html').read_text(encoding='utf-8')
            for entry in entries:
                self.assertIn(entry['id'] + '.html', index)
            self.assertIn('SAMPLE 演示', index)
            self.assertIn('不作跨App排名', index)
            self.assertIn('index.html', (output / 'one.html').read_text(encoding='utf-8'))

    def test_rejects_output_escape_duplicate_ids_and_existing_directory(self):
        for identity in ('../escape', 'index', 'UPPER', 'one/two'):
            with self.subTest(identity=identity), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                entry = self.fixture(root)
                entry['id'] = identity
                run = self.run_bundle(root, [entry])
                self.assertEqual(run.returncode, 1)
                self.assertFalse((root / 'bundle').exists())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = self.fixture(root)
            self.assertEqual(self.run_bundle(root, [entry, entry]).returncode, 1)
            self.assertFalse((root / 'bundle').exists())
            output = root / 'bundle'
            output.mkdir()
            (output / 'keep').write_bytes(b'KEEP')
            self.assertEqual(self.run_bundle(root, [entry]).returncode, 1)
            self.assertEqual(list(output.iterdir()), [output / 'keep'])
            self.assertEqual((output / 'keep').read_bytes(), b'KEEP')

    def test_only_declared_json_and_markdown_inputs_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = self.fixture(root)
            raw = (root / entry['source_file']).read_bytes()
            (root / 'not-research.mp4').write_bytes(raw)
            entry['source_file'] = 'not-research.mp4'
            run = self.run_bundle(root, [entry])
            self.assertEqual(run.returncode, 1)
            self.assertEqual(run.stderr.decode().strip(), 'BUNDLE_INPUT_INVALID:SOURCE_EXTENSION_INVALID')
            self.assertFalse((root / 'bundle').exists())


if __name__ == '__main__':
    unittest.main()
