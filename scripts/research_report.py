"""Render derived R1 analysis values only; never calculate measurement metrics."""
import html
import argparse
import json
from pathlib import Path
import sys


def esc(value):
    return html.escape('NA' if value is None else str(value), quote=True)


def interval(value):
    if value is None:
        return 'NA'
    return esc(value[0]) + '–' + esc(value[1]) + ' 秒'


def translated(value):
    labels = {'completed': '已完成', 'incomplete': '未完成',
              'yes': '是', 'no': '否', 'uncertain': '不确定', 'not_run': '未执行'}
    return labels.get(value, '未知／未提供') + '（' + esc(value) + '）'


def render_report(data):
    parts = ['<!doctype html><html lang="zh-CN"><meta charset="utf-8">',
             '<meta name="viewport" content="width=device-width,initial-scale=1">',
             '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">',
             '<style>body{font-family:system-ui,"Microsoft YaHei",sans-serif;max-width:980px;margin:0 auto;padding:24px;background:#f4f6f8;color:#172b3a;line-height:1.65;overflow-wrap:anywhere}section{background:white;border:1px solid #d6e0e6;border-radius:12px;padding:20px;margin:20px 0}h1{color:#123d51}h2{border-bottom:2px solid #e2ecef;padding-bottom:10px}h3{color:#24576a}.notice{background:#fff0cc;border-left:5px solid #b77913;padding:14px}code{font-size:12px}@media print{body{background:white}section{break-inside:avoid}} </style>',
             '<title>R1 离线观察报告</title><body><h1>R1 离线观察报告</h1>',
             '<p>SAMPLE 是虚构样例；OBSERVED 是声明，不是真实性认证。未读取或认证外部媒体。</p>',
             '<p>' + esc(data['record_kind']) + '</p>',
             '<p>source_sha256：' + esc(data['source_sha256']) + '</p>']
    sample = data['record_kind'] == 'SAMPLE'
    if sample:
        parts.append('<aside class="notice"><h2>SAMPLE 演示｜没有真实 App 测量</h2>'
                     '<p>本页使用虚构时间与不存在的录像演示报告功能；不是Kimi或其他App的实测表现，不能用于体验排名或网络判断。</p></aside>')
    counts = data['counts']
    for key, label in [('planned', '计划'), ('attempted', '发送/尝试'),
                       ('not_run', '未执行'), ('visible_completed_confirmed', '已确认完成')]:
        parts.append('<p>' + label + '：' + esc(counts.get(key)) + '</p>')
    for row in data['records']:
        parts.append('<section><h2>' + esc(row['input_record']['app']['name'])
                     + ' · ' + esc(row['attempt_id']) + '</h2>')
        if sample:
            parts.append('<p class="notice">SAMPLE 演示｜没有真实 App 测量</p>')
        parts.append('<p>任务与观察条件：未知字段不补填；App版本：'
                     + esc(row['input_record']['app'].get('version') or '未知／未提供')
                     + '；模式：' + esc(row['input_record']['app'].get('model_mode') or '未知／未提供') + '</p>')
        outcome = row['input_record'].get('outcome') or {}
        not_run = outcome.get('executed') is False
        parts.append('<p>执行状态：' + (translated('not_run') if not_run else translated(outcome.get('status')))
                     + '；可见完成：' + translated(outcome.get('visible_completion'))
                     + '；指令满足：' + translated(outcome.get('instruction_following')) + '</p>')
        evidence = row['input_record'].get('evidence') or {}
        parts.append('<p>证据引用（仅文本）：' + esc(evidence.get('local_ref'))
                     + '；证据 SHA256：' + esc(evidence.get('sha256')) + '</p>')
        for key, label in [('ttfr', '首次反馈'), ('ttfc', '首次正文'), ('completion', '完成')]:
            metric = row[key]
            parts.append('<p>' + label + '：' + esc(metric['status']) + ' / '
                         + interval(metric['interval_s']) + '</p>')
            if not_run:
                parts.append('<p>未执行，因此无此指标</p>')
            elif metric.get('reason') == 'completion_unconfirmed':
                parts.append('<p>未确认正常完成，完成时长不可用</p>')
            elif metric['status'] in ('NA', 'uncertain'):
                parts.append('<p>信息不足，待核对原记录</p>')
        stalls = row['stalls']
        stall_label = {'not_computed': '停顿未计算', 'partial': '仅部分观察'}.get(stalls['status'], '停顿观察')
        parts.append('<h3>' + stall_label + '（' + esc(stalls['status']) + '）</h3>')
        parts.append('<p>已观察片段，不等于全程；已确认片段数：'
                     + esc(stalls.get('confirmed_observed_count')) + '</p>')
        if not_run and stalls['status'] == 'not_computed':
            parts.append('<p>未执行、未计算，不代表无停顿。</p>')
        for gap in stalls['intervals']:
            parts.append('<p>' + esc(gap['classification']) + '：' + interval(gap['interval_s']) + '</p>')
            if gap['classification'] == 'right_censored':
                parts.append('<p>未观察到恢复的尾段，非完整停顿；未确认完整片段不代表全程无停顿。</p>')
        parts.append('<p>总停顿次数：' + esc(stalls.get('total_count'))
                     + ('（未知）' if stalls.get('total_count') is None else '')
                     + '；最长停顿：' + interval(stalls.get('max_interval_s'))
                     + ('（未知）' if stalls.get('max_interval_s') is None else '') + '</p>')
        parts.append('</section>')
    parts.append('<section><h2>比较限制与分组</h2><p>仅显示已有分析值，不重新计算。'
                 '输入毫秒保持在原记录；此处区间单位为秒。NA/uncertain 不等于零。'
                 'right_censored 是观察尾段，不是完整停顿时长；partial 不代表整次覆盖。'
                 '不代表网络因果、模型速度、运营商排名或统一评分。</p>')
    if not data['groups']:
        parts.append('<p>无已确认可比组；不自动合并未知条件。</p>')
    for group in data['groups']:
        parts.append('<h3>' + esc(group['group_id']) + '</h3>')
        for name, metric in group['metrics'].items():
            parts.append('<h4>' + esc(name) + '</h4>')
            for key in ['valid_n', 'point_n', 'na_n', 'uncertain_n']:
                parts.append('<p>' + key + '：' + esc(metric[key]) + '</p>')
            parts.append('<p>点值子集，不是整组中位数：' + esc(metric['point_median_s'])
                         + ' 秒；点值范围：' + interval(metric['point_range_s']) + '</p>')
            for attempt in metric['attempts']:
                parts.append('<p>' + esc(attempt['attempt_id']) + '：' + esc(attempt['status'])
                             + ' / ' + interval(attempt['interval_s']) + '</p>')
    parts.append('<p>端到端可见体验不隔离网络、模型、服务端与设备贡献；不生成真实App RPI或厂商网络门限。</p></section>')
    if sample:
        parts.append('<section><h2>待真实数据的逐 App 结论</h2><p>尚未收到本批真实记录，暂不提供体验结论。上方SAMPLE仅展示格式，不证明该版本或模式的实际表现。</p></section>')
    return '\n'.join(parts) + '\n</body></html>\n'


def main():
    parser = argparse.ArgumentParser(description='R1 analysis JSON to private offline HTML; no media reads.')
    parser.add_argument('analysis')
    parser.add_argument('-o', '--output', required=True)
    args = parser.parse_args()
    try:
        data = json.loads(Path(args.analysis).read_text(encoding='utf-8'))
        page = render_report(data)
        with open(args.output, 'x', encoding='utf-8', newline='\n') as output:
            output.write(page)
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        print('REPORT_INPUT_OR_OUTPUT_INVALID', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
