"""Offline research-only video observation intervals; never reads media."""
import argparse
from decimal import Decimal
import hashlib
import html
import json
import math
from pathlib import Path
import sys


def event_valid(event):
    if not isinstance(event, dict):
        return False
    clock, interval = event.get('clock_id'), event.get('pts_s')
    return (isinstance(clock, str) and bool(clock.strip()) and clock.upper() != 'UNKNOWN'
            and isinstance(interval, list) and len(interval) == 2
            and all(type(v) in (int, float) and math.isfinite(v) and v >= 0 for v in interval)
            and interval[0] <= interval[1])


def window_result(record):
    start, end = record['V0'], record['window_end']
    planned = [float(Decimal(str(x)) + 30) for x in start['pts_s']] if event_valid(start) else None
    status, reason = 'observed_complete', None
    if start is None:
        status, reason = 'NA', 'V0_MISSING'
    elif not event_valid(start):
        status, reason = 'NA', 'V0_INVALID'
    elif end is None:
        status, reason = 'NA', 'WINDOW_END_MISSING'
    elif not event_valid(end):
        status, reason = 'NA', 'WINDOW_END_INVALID'
    elif end['clock_id'] != start['clock_id']:
        status, reason = 'uncertain', 'WINDOW_CLOCK_MISMATCH'
    elif end['pts_s'][1] < start['pts_s'][0]:
        status, reason = 'NA', 'WINDOW_ORDER_REVERSED'
    elif end['pts_s'][0] < start['pts_s'][1]:
        status, reason = 'uncertain', 'WINDOW_ORDER_OVERLAP'
    elif end['pts_s'][1] < planned[0]:
        status, reason = ('uncertain', 'WINDOW_CLAIM_CONFLICT') if record['window_complete'] == 'yes' else ('observed_partial', 'WINDOW_INCOMPLETE')
    elif end['pts_s'][0] < planned[1]:
        status, reason = 'uncertain', 'WINDOW_BOUNDARY_UNCERTAIN'
    elif record['window_complete'] != 'yes':
        status, reason = 'uncertain', 'WINDOW_COVERAGE_UNCERTAIN'
    return {'planned_end_pts_s': planned, 'status': status, 'reason': reason}


def analyze(source, raw):
    blocked = source['record_kind'] == 'video_observation_preflight_blocked'
    if not blocked and (source.get('format') != 'research-video-1' or source['record_kind'] not in ('SAMPLE', 'OBSERVED')):
        raise ValueError('FORMAT_INVALID')
    if not isinstance(source.get('app'), str) or not source['app'].strip():
        raise ValueError('APP_REQUIRED')
    if not isinstance(source['attempts'], list) or not source['attempts']:
        raise ValueError('ATTEMPTS_REQUIRED')
    seen = set()
    for record in source['attempts']:
        if not isinstance(record['slot'], str) or not record['slot'].strip() or record['slot'] in seen:
            raise ValueError('SLOT_INVALID')
        seen.add(record['slot'])
        if record['status'] not in ('EXECUTED', 'NOT_RUN'):
            raise ValueError('STATUS_INVALID')
        if record['status'] == 'NOT_RUN':
            if any(record[key] is not None for key in ('V0', 'VF', 'window_end')) or record['window_complete'] != 'not_started' or record['visible_target_playback'] != 'not_observed':
                raise ValueError('NOT_RUN_CONFLICT')
        elif record['window_complete'] not in ('yes', 'no', 'uncertain') or record['visible_target_playback'] not in ('yes', 'no', 'uncertain'):
            raise ValueError('OBSERVATION_INVALID')
    if blocked and (any(r['status'] != 'NOT_RUN' for r in source['attempts'])
                    or source['actual_measured_opens'] != 0
                    or source['planned_slots'] != len(seen) or source['not_run'] != len(seen)):
        raise ValueError('BLOCKED_COUNT_CONFLICT')
    rows = []
    for record in source['attempts']:
        if record['status'] == 'NOT_RUN':
            rows.append({'slot': record['slot'], 'status': 'NOT_RUN', 'reason': record.get('reason'),
                         'first_frame_wait': {'status': 'NA', 'interval_s': None, 'reason': 'NOT_RUN'},
                         'window': {'planned_end_pts_s': None, 'status': 'not_started'}})
            continue
        start = record['V0']['pts_s'] if event_valid(record['V0']) else None
        first = record['VF']['pts_s'] if event_valid(record['VF']) else None
        reason = None
        for name in ['V0', 'VF']:
            if record[name] is None:
                reason = name + '_MISSING'
                break
            if not event_valid(record[name]):
                reason = name + '_INVALID'
                break
        status = 'NA' if reason else 'valid'
        if not reason:
            if record['V0']['clock_id'] != record['VF']['clock_id']:
                status, reason = 'uncertain', 'CLOCK_MISMATCH'
            elif first[1] < start[0]:
                status, reason = 'NA', 'ORDER_REVERSED'
            elif first[0] < start[1]:
                status, reason = 'uncertain', 'ORDER_OVERLAP'
            elif record['visible_target_playback'] != 'yes':
                status, reason = 'uncertain', 'TARGET_PLAYBACK_UNCONFIRMED'
            elif Decimal(str(first[0])) > Decimal(str(start[1])) + 30:
                status, reason = 'NA', 'VF_AFTER_PLANNED_WINDOW'
            elif (event_valid(record['window_end'])
                  and record['window_end']['clock_id'] == record['VF']['clock_id']
                  and first[0] > record['window_end']['pts_s'][1]):
                status, reason = 'NA', 'VF_AFTER_OBSERVATION_END'
            elif Decimal(str(first[1])) > Decimal(str(start[0])) + 30:
                status, reason = 'uncertain', 'VF_PLANNED_BOUNDARY_UNCERTAIN'
            elif (event_valid(record['window_end'])
                  and record['window_end']['clock_id'] == record['VF']['clock_id']
                  and first[1] > record['window_end']['pts_s'][0]):
                status, reason = 'uncertain', 'VF_OBSERVATION_BOUNDARY_UNCERTAIN'
        wait = None if reason else [float(Decimal(str(first[0])) - Decimal(str(start[1]))),
                                     float(Decimal(str(first[1])) - Decimal(str(start[0])))]
        rows.append({'slot': record['slot'], 'status': record['status'], 'reason': record.get('reason'),
                     'first_frame_wait': {'status': status, 'interval_s': wait, 'reason': reason},
                     'window': window_result(record)})
    for row in rows:
        row['buffering_pause'] = {'status': 'not_computed', 'count': None, 'duration_s': None}
    return {'record_kind': 'OBSERVED' if source['record_kind'] == 'video_observation_preflight_blocked' else source['record_kind'],
            'counts': {'planned': len(rows), 'executed': sum(r['status'] == 'EXECUTED' for r in rows),
                       'not_run': sum(r['status'] == 'NOT_RUN' for r in rows)},
            'source_sha256': hashlib.sha256(raw).hexdigest(),
            'input_record': source, 'attempts': rows}


def render(result):
    escape = lambda value: html.escape(str(value))
    labels = {'valid': '可计算区间（valid）', 'uncertain': '不确定（uncertain）', 'NA': '不可计算（NA）',
              'observed_complete': '据标注已观察完整', 'observed_partial': '仅部分观察',
              'not_started': '窗口未开始', 'EXECUTED': '已执行', 'NOT_RUN': '未执行（NOT_RUN）'}
    reasons = {'NOT_RUN': '未执行，不计播放失败', 'V0_MISSING': '缺少可见打开边界',
               'VF_MISSING': '缺少目标首画面边界，不以窗口长度替代',
               'V0_INVALID': '打开边界或时钟无效', 'VF_INVALID': '首画面边界或时钟无效',
               'CLOCK_MISMATCH': '时钟不一致', 'ORDER_OVERLAP': '先后顺序区间重叠',
               'ORDER_REVERSED': '首画面早于打开边界', 'TARGET_PLAYBACK_UNCONFIRMED': '目标播放尚未确认',
               'VF_AFTER_PLANNED_WINDOW': '首画面确定晚于计划30秒截止，本窗不可计算，不计播放失败',
               'VF_AFTER_OBSERVATION_END': '首画面确定晚于已记录观察截止，本窗不可计算，不计播放失败',
               'VF_PLANNED_BOUNDARY_UNCERTAIN': '首画面区间跨越可能的计划30秒截止，无法确定在本窗内',
               'VF_OBSERVATION_BOUNDARY_UNCERTAIN': '首画面区间跨越可能的实际观察截止，无法确定已在截止前观察到',
               'WINDOW_END_MISSING': '缺少观察终点', 'WINDOW_END_INVALID': '观察终点无效',
               'WINDOW_CLOCK_MISMATCH': '窗口时钟不一致', 'WINDOW_ORDER_REVERSED': '观察终点早于打开',
               'WINDOW_ORDER_OVERLAP': '观察终点与打开的顺序区间重叠',
               'WINDOW_INCOMPLETE': '未覆盖完整窗口', 'WINDOW_CLAIM_CONFLICT': '完整声明与终点冲突',
               'WINDOW_BOUNDARY_UNCERTAIN': '终点区间不足以确定覆盖', 'WINDOW_COVERAGE_UNCERTAIN': '覆盖情况未确认'}
    def reason_text(code):
        return '' if code is None else escape(reasons.get(code, code)) + ' · ' + escape(code)
    cards = []
    for row in result['attempts']:
        wait = row['first_frame_wait']
        window = row['window']
        value = '—' if wait['interval_s'] is None else escape(wait['interval_s']) + ' 秒'
        cards.append('<section><h2>' + escape(row['slot']) + ' · ' + labels[row['status']] + '</h2><h3>可见打开至首画面</h3>'
                     + '<p class="metric">' + value + '</p><p>' + labels[wait['status']] + ' · ' + reason_text(wait['reason'])
                     + '</p><h3>30秒观察窗</h3><p>' + labels[window['status']] + ' · ' + reason_text(window.get('reason'))
                     + '</p><p>计划终点 PTS（秒，非实测终点）：' + (labels['NA'] if window['planned_end_pts_s'] is None else escape(window['planned_end_pts_s']))
                     + '</p><p>原始说明：' + (escape(row['reason']) if row['reason'] is not None else '未提供') + '</p></section>')
    counts = result['counts']
    source = result['input_record']
    kind = 'SAMPLE 演示' if result['record_kind'] == 'SAMPLE' else 'OBSERVED 原记录声明'
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">'
            '<title>视频观察研究</title><style>body{font-family:system-ui,sans-serif;max-width:960px;margin:auto;padding:28px;background:#f3f6f8;color:#183548;line-height:1.7}section{background:white;border-radius:12px;padding:24px;margin:20px 0}h1,h2{line-height:1.35}.metric{font-size:28px;color:#075d80}aside{background:#fff0ce;padding:18px;border-radius:8px}pre,code{white-space:pre-wrap;overflow-wrap:anywhere}h3{margin-bottom:0}</style>'
            '<body><h1>' + kind + ' · 视频观察</h1><h2>' + escape(source['app']) + '</h2>'
            + '<aside>未执行不等于播放失败；OBSERVED仅表示原始记录声明，不认证播放事实。'
            + '<br>30秒从V0开始，包括首次等待；不是播放完成或有效播放时长。</aside>'
            + '<p>计划槽 ' + str(counts['planned']) + ' · 实际执行 ' + str(counts['executed'])
            + ' · 未执行 ' + str(counts['not_run']) + '。不计算尝试成功率或播放失败率。</p>' + ''.join(cards)
            + '<section><h2>范围与来源</h2><p>缓冲/暂停：未计算（次数、时长均为null，不是0）</p><p>文本TTFC/T3：不适用</p>'
            + '<p>仅逐次可见观察，不推网络因果、不计算视频RPI。静态封面不算VF；需要原标注确认目标播放。'
            + '窗口完整是标注与终点一致性，不是软件检查录像后的证明。</p><p>来源 SHA256：<code>'
            + result['source_sha256'] + '</code></p><details><summary>原始记录（仅文本，不追读媒体）</summary><pre>'
            + escape(json.dumps(source, ensure_ascii=False, indent=2)) + '</pre></details></section></body></html>\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input')
    parser.add_argument('--output-json', required=True)
    parser.add_argument('--output-html', required=True)
    args = parser.parse_args()
    outputs = [Path(args.output_json), Path(args.output_html)]
    if any(p.exists() or p.is_symlink() for p in outputs) or outputs[0].resolve() == outputs[1].resolve():
        print('VIDEO_OUTPUT_EXISTS_OR_COLLISION', file=sys.stderr)
        return 1
    def reject_constant(value):
        raise ValueError('NON_JSON_NUMBER')
    try:
        raw = Path(args.input).read_bytes()
        result = analyze(json.loads(raw.decode('utf-8'), parse_constant=reject_constant), raw)
        contents = [(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8'),
                    render(result).encode('utf-8')]
        for path, content in zip(outputs, contents):
            with path.open('xb') as stream:
                stream.write(content)
    except (ValueError, KeyError, TypeError, OSError, OverflowError, AttributeError):
        print('VIDEO_INPUT_INVALID', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
