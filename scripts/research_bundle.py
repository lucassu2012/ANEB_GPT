"""Local research delivery bundle; reuse report values, never calculate metrics."""
import argparse
import html
import hashlib
import json
from pathlib import Path
import re
import sys

from research_report import render_report
from research_video import render as render_video


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def read_bound(root, entry, key):
    path = root / entry[key + '_file']
    if path.suffix.lower() != ('.md' if key == 'conclusion' else '.json'):
        raise ValueError(key.upper() + '_EXTENSION_INVALID')
    raw = path.read_bytes()
    if digest(raw) != entry[key + '_sha256'].lower():
        raise ValueError(key.upper() + '_HASH_MISMATCH')
    return raw


def bind_video(analysis, source, app):
    """Bind existing annotations/rows only; do not recalculate video metrics."""
    blocked = source.get('record_kind') == 'video_observation_preflight_blocked'
    if not blocked and (source.get('format') != 'research-video-1'
                        or source.get('record_kind') not in ('SAMPLE', 'OBSERVED')):
        raise ValueError('VIDEO_SOURCE_TYPE_INVALID')
    if source['app'] != app:
        raise ValueError('APP_MISMATCH')
    if analysis['input_record'] != source:
        raise ValueError('VIDEO_INPUT_COPY_MISMATCH')
    expected_kind = 'OBSERVED' if blocked else source['record_kind']
    if analysis['record_kind'] != expected_kind:
        raise ValueError('VIDEO_RECORD_KIND_MISMATCH')
    slots = [(row['slot'], row['status']) for row in source['attempts']]
    if (not slots or any(not isinstance(slot, str) or not slot.strip()
                         or state not in ('EXECUTED', 'NOT_RUN') for slot, state in slots)
            or len({slot for slot, _ in slots}) != len(slots)
            or [(row['slot'], row['status']) for row in analysis['attempts']] != slots):
        raise ValueError('VIDEO_SLOT_MISMATCH')
    counts = {'planned': len(slots), 'executed': sum(state == 'EXECUTED' for _, state in slots),
              'not_run': sum(state == 'NOT_RUN' for _, state in slots)}
    if analysis['counts'] != counts or any(type(v) is not int for v in analysis['counts'].values()):
        raise ValueError('VIDEO_COUNTS_MISMATCH')
    if blocked and (counts['executed'] != 0 or source['actual_measured_opens'] != 0
                    or source['planned_slots'] != counts['planned'] or source['not_run'] != counts['not_run']):
        raise ValueError('VIDEO_BLOCKED_CONFLICT')


def batch_metadata(analysis, content_kind):
    """Display declared conditions per record; never infer or aggregate metrics."""
    if content_kind == 'video':
        return '<p>视频方法／版本／模式：未知／未提供（未核验）；以原标注页为准。</p>'
    rows = []
    for row in analysis['records']:
        record = row['input_record']
        app = record['app']
        values = [row.get('attempt_id'), record.get('method_id'),
                  app.get('version'), app.get('model_mode')]
        cells = [html.escape(str(value)) if value is not None and value != ''
                 else '未知／未提供' for value in values]
        rows.append('<li>记录：' + cells[0] + '；方法：' + cells[1]
                    + '；版本：' + cells[2] + '；模式：' + cells[3] + '</li>')
    return '<p>原记录元信息（未核验，不推定同批条件一致）</p><ul>' + ''.join(rows) + '</ul>'


def build_bundle(manifest_path, output):
    manifest_path, output = Path(manifest_path), Path(output)
    if output.exists():
        raise ValueError('OUTPUT_EXISTS')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if not isinstance(manifest['apps'], list) or not manifest['apps']:
        raise ValueError('EMPTY_MANIFEST')
    files, bindings, dossiers, seen = {}, [], {}, set()
    observed_text_apps, observed_videos, sample_count = set(), [], 0
    for entry in manifest['apps']:
        content_kind = entry.get('kind', 'text')
        if content_kind not in ('text', 'video'):
            raise ValueError('KIND_UNSUPPORTED')
        identity = entry['id']
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', identity) or identity == 'index':
            raise ValueError('APP_ID_INVALID')
        if identity in seen:
            raise ValueError('APP_ID_DUPLICATE')
        seen.add(identity)
        raw = read_bound(manifest_path.parent, entry, 'analysis')
        source = read_bound(manifest_path.parent, entry, 'source')
        analysis = json.loads(raw.decode('utf-8'))
        if analysis['source_sha256'].lower() != digest(source):
            raise ValueError('ANALYSIS_SOURCE_MISMATCH')
        bound = {'id': identity, 'app': entry['app']}
        copies = [('analysis', raw, 'json'), ('source', source, 'json')]
        labels = [('analysis', '分析 JSON'), ('source', '适配输入 JSON')]
        if content_kind == 'video':
            bind_video(analysis, json.loads(source.decode('utf-8')), entry['app'])
            bound['kind'] = 'video'
            page = render_video(analysis)
            conclusion = ''
            description = ('SAMPLE 演示（不计真实观察）' if analysis['record_kind'] == 'SAMPLE' else
                           ('视频计划未执行 · 实际打开0次' if analysis['counts']['executed'] == 0 else 'OBSERVED 视频原记录声明'))
            nav = '视频原标注、未执行原因和来源绑定；不编造体验结论卡'
            if analysis['record_kind'] == 'OBSERVED':
                observed_videos.append(analysis['counts'])
        else:
            card_raw = read_bound(manifest_path.parent, entry, 'conclusion')
            card = card_raw.decode('utf-8')
            if entry['conclusion_analysis_sha256'].lower() != digest(raw):
                raise ValueError('CONCLUSION_ANALYSIS_MISMATCH')
            if digest(raw) not in [token.lower() for token in re.findall(r'(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])', card)]:
                raise ValueError('CONCLUSION_ANALYSIS_UNBOUND')
            if not analysis['records'] or any(row['input_record']['app']['name'] != entry['app'] for row in analysis['records']):
                raise ValueError('APP_MISMATCH')
            bound['conclusion_analysis_sha256'] = digest(raw)
            copies.append(('conclusion', card_raw, 'md'))
            labels.append(('conclusion', '结论卡 Markdown'))
            page = render_report(analysis)
            conclusion = '<section id="conclusion"><h2>研究结论卡原文（保留标注与边界）</h2><pre style="white-space:pre-wrap;font-family:inherit">' + html.escape(card) + '</pre></section>'
            kind = 'SAMPLE 演示' if analysis['record_kind'] == 'SAMPLE' else 'OBSERVED 声明（非真实性认证）'
            description = kind + ' · 已有指标、NA/uncertain、结论卡原文和来源绑定'
            nav = '下方含结论卡原文与限制'
            if analysis['record_kind'] == 'OBSERVED' and any(row['input_record']['outcome']['executed'] is True for row in analysis['records']):
                observed_text_apps.add(entry['app'])
        sample_count += analysis['record_kind'] == 'SAMPLE'
        for key, content, suffix in copies:
            name = 'sources/' + identity + '-' + key + '.' + suffix
            files[name] = content
            bound[key + '_file'], bound[key + '_sha256'] = name, digest(content)
        bindings.append(bound)
        page = page.replace('<body>', '<body><nav><a href="index.html">返回研究包首页</a> · ' + nav + '</nav>')
        conclusion += '<section><h2>本页来源绑定</h2><p>哈希一致只证明选定字节相符，不认证研究事实。</p>'
        for key, label in labels:
            conclusion += '<p><a href="' + bound[key + '_file'] + '">' + label + '</a><br><code>SHA256 ' + bound[key + '_sha256'] + '</code></p>'
        conclusion += '</section>'
        page = page.replace('</body>', conclusion + '</body>')
        files[identity + '.html'] = page.encode('utf-8')
        batch_links = '<a href="' + identity + '.html">原指标／NA报告</a>'
        if content_kind == 'text':
            batch_links += ' · <a href="' + identity + '.html#conclusion">对应结论</a>'
        for key, label in labels:
            batch_links += ' · <a href="' + bound[key + '_file'] + '">' + label + '</a>'
        dossiers.setdefault(entry['app'], []).append(
            '<li><h3>独立 entry／批次：' + identity + '</h3><p>来源类型：'
            + content_kind + ' · ' + description + '</p>'
            + batch_metadata(analysis, content_kind) + '<p>' + batch_links + '</p></li>')
    links = []
    for app, batches in dossiers.items():
        links.append('<section><h2>' + html.escape(app) + '</h2><p>独立批次：'
                     + str(len(batches)) + '；按声明顺序保留，不选最新为真、不合并分母。</p><ul>'
                     + ''.join(batches) + '</ul></section>')
    summary = ''
    if any(bound.get('kind') == 'video' for bound in bindings):
        summary = '<p>' + str(len(observed_text_apps)) + '款App有文本观察（原记录声明）'
        if observed_videos:
            if all(counts['executed'] == 0 for counts in observed_videos):
                summary += ' · 视频计划未执行（' + str(sum(c['planned'] for c in observed_videos)) + '槽，实际打开0次）'
            else:
                summary += ' · 视频有执行记录声明，请逐页查看，非真实性认证'
        summary += '</p><p>SAMPLE 演示入口：' + str(sample_count) + '（不计真实观察）</p>'
    files['index.html'] = ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">'
        '<title>离线 App 研究档案</title><style>body{font-family:system-ui,sans-serif;max-width:960px;margin:auto;padding:32px;background:#f4f6f8;color:#172b3a;line-height:1.7;overflow-wrap:anywhere}li{background:white;padding:20px;margin:16px 0;border-radius:12px}li li{padding:4px;margin:4px 0}a{color:#075d80}aside{background:#fff0cc;padding:16px}ul{list-style:none;padding:0}</style>'
        '<body><h1>App → 独立研究批次</h1><aside>仅本地研究交付，不作跨App排名。可见结束不等于指令成功或可计完成时长；NA/uncertain不等于零。</aside>'
        + summary + '<p>按原声明App查找各entry；分组仅导航，不合并数据、分母或指标。逐页保留已有分析与结论；没有统一评分。SAMPLE不计真实观察。</p>' + ''.join(links)
        + '<p><a href="README.txt">使用与隐私说明</a> · <a href="bundle-manifest.json">来源清单</a> · <a href="SHA256SUMS.txt">文件校验清单</a></p>'
        '<p>不包含原视频、截图或可执行脚本；结论中的媒体路径仅为原文引用，不会自动打开或复制。</p></body></html>\n').encode('utf-8')
    files['bundle-manifest.json'] = (json.dumps({'apps': bindings}, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    files['README.txt'] = ('R1 离线研究包\n双击 index.html；移动时保留整个目录。无需服务器或网络。\n'
        '逐App页面复用现有分析值，结论卡以转义原文显示；不重算指标、不排名。\n'
        '视频入口复用视频renderer；无结论卡要求，原始原因自动显示。SAMPLE不计入真实观察，NOT_RUN不代表播放失败。\n'
        'sources含选定分析/适配输入/结论卡的原字节，可含本地研究文字和原路径，仅限授权本地使用。\n'
        '不包含视频、截图、私人画面；不会沿JSON或卡中引用复制其他文件。不要公开上传此目录。\n'
        'SHA256SUMS.txt逐项列出其他文件的SHA256；清单自排除，不是签名或真实性认证。\n'
        'bundle-manifest.json是本地交付清单，不是正式研究evidence schema。\n').encode('utf-8')
    files['SHA256SUMS.txt'] = ''.join(digest(content) + '  ' + name + '\n' for name, content in sorted(files.items())).encode('ascii')
    output.mkdir()
    (output / 'sources').mkdir()
    for name, content in files.items():
        with (output / name).open('xb') as stream:
            stream.write(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    parser.add_argument('-o', '--output', required=True)
    args = parser.parse_args()
    try:
        build_bundle(args.manifest, args.output)
    except OSError:
        print('BUNDLE_INPUT_INVALID:FILE_UNAVAILABLE', file=sys.stderr)
        return 1
    except (ValueError, KeyError, TypeError, AttributeError) as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch('[A-Z_]+', str(error)) else 'SHAPE_INVALID'
        print('BUNDLE_INPUT_INVALID:' + code, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
