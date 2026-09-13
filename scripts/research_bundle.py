"""Local research delivery bundle; reuse report values, never calculate metrics."""
import argparse
import html
import hashlib
import json
from pathlib import Path
import re
import sys

from research_report import render_report


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


def build_bundle(manifest_path, output):
    manifest_path, output = Path(manifest_path), Path(output)
    if output.exists():
        raise ValueError('OUTPUT_EXISTS')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if not isinstance(manifest['apps'], list) or not manifest['apps']:
        raise ValueError('EMPTY_MANIFEST')
    files, bindings, links, seen = {}, [], [], set()
    for entry in manifest['apps']:
        identity = entry['id']
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', identity) or identity == 'index':
            raise ValueError('APP_ID_INVALID')
        if identity in seen:
            raise ValueError('APP_ID_DUPLICATE')
        seen.add(identity)
        raw = read_bound(manifest_path.parent, entry, 'analysis')
        source = read_bound(manifest_path.parent, entry, 'source')
        card_raw = read_bound(manifest_path.parent, entry, 'conclusion')
        analysis = json.loads(raw.decode('utf-8'))
        card = card_raw.decode('utf-8')
        if entry['conclusion_analysis_sha256'].lower() != digest(raw):
            raise ValueError('CONCLUSION_ANALYSIS_MISMATCH')
        if digest(raw) not in [token.lower() for token in re.findall(r'(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])', card)]:
            raise ValueError('CONCLUSION_ANALYSIS_UNBOUND')
        if analysis['source_sha256'].lower() != digest(source):
            raise ValueError('ANALYSIS_SOURCE_MISMATCH')
        if not analysis['records'] or any(row['input_record']['app']['name'] != entry['app'] for row in analysis['records']):
            raise ValueError('APP_MISMATCH')
        bound = {'id': identity, 'app': entry['app'], 'conclusion_analysis_sha256': digest(raw)}
        for key, content, suffix in [('analysis', raw, 'json'), ('source', source, 'json'),
                                     ('conclusion', card_raw, 'md')]:
            name = 'sources/' + identity + '-' + key + '.' + suffix
            files[name] = content
            bound[key + '_file'], bound[key + '_sha256'] = name, digest(content)
        bindings.append(bound)
        page = render_report(analysis)
        page = page.replace('<body>', '<body><nav><a href="index.html">返回研究包首页</a> · 下方含结论卡原文与限制</nav>')
        conclusion = '<section id="conclusion"><h2>研究结论卡原文（保留标注与边界）</h2><pre style="white-space:pre-wrap;font-family:inherit">' + html.escape(card) + '</pre></section>'
        conclusion += '<section><h2>本页来源绑定</h2><p>哈希一致只证明选定字节相符，不认证研究事实。</p>'
        for key, label in [('analysis', '分析 JSON'), ('source', '适配输入 JSON'), ('conclusion', '结论卡 Markdown')]:
            conclusion += '<p><a href="' + bound[key + '_file'] + '">' + label + '</a><br><code>SHA256 ' + bound[key + '_sha256'] + '</code></p>'
        conclusion += '</section>'
        page = page.replace('</body>', conclusion + '</body>')
        files[identity + '.html'] = page.encode('utf-8')
        kind = 'SAMPLE 演示' if analysis['record_kind'] == 'SAMPLE' else 'OBSERVED 声明（非真实性认证）'
        links.append('<li><a href="' + identity + '.html">' + html.escape(entry['app']) + '</a><p>' + kind + ' · 已有指标、NA/uncertain、结论卡原文和来源绑定</p></li>')
    files['index.html'] = ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">'
        '<title>R1 离线研究包</title><style>body{font-family:system-ui,sans-serif;max-width:960px;margin:auto;padding:32px;background:#f4f6f8;color:#172b3a;line-height:1.7}li{background:white;padding:20px;margin:16px 0;border-radius:12px}a{color:#075d80}aside{background:#fff0cc;padding:16px}ul{list-style:none;padding:0}</style>'
        '<body><h1>R1 · App 观察研究包</h1><aside>仅本地研究交付，不作跨App排名。可见结束不等于指令成功或可计完成时长；NA/uncertain不等于零。</aside>'
        '<p>从下列页面分别阅读指标与结论；保留各自观察方法和缺口，不计算跨App汇总。</p><ul>' + ''.join(links) + '</ul>'
        '<p><a href="README.txt">使用与隐私说明</a> · <a href="bundle-manifest.json">来源清单</a> · <a href="SHA256SUMS.txt">文件校验清单</a></p>'
        '<p>不包含原视频、截图或可执行脚本；结论中的媒体路径仅为原文引用，不会自动打开或复制。</p></body></html>\n').encode('utf-8')
    files['bundle-manifest.json'] = (json.dumps({'apps': bindings}, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    files['README.txt'] = ('R1 离线研究包\n双击 index.html；移动时保留整个目录。无需服务器或网络。\n'
        '逐App页面复用现有分析值，结论卡以转义原文显示；不重算指标、不排名。\n'
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
