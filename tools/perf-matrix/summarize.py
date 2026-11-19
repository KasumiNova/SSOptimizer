#!/usr/bin/env python3
"""汇总配对启动的原始计量结果，以每组每轮为单位比较。"""
import argparse
import csv
import json
from pathlib import Path
from statistics import median

GROUPS = {'stock': '原版', 'fr': 'FastRender', 'sso': 'SSOptimizer'}
FIELDS = [('startup', 'duration_s', '加载秒'), ('startup', 'cpu_s', '加载CPU秒'),
          ('startup', 'mean_cpu_pct', '加载CPU%'), ('startup', 'mean_machine_pct', '加载整机%'),
          ('startup', 'peak_1s_cpu_pct', '加载峰值CPU%'), ('battle', 'duration_s', '采样秒'),
          ('battle', 'frames', '帧数'), ('battle', 'fps', '平均FPS'), ('battle', 'low95_fps', 'Low95 FPS'),
          ('battle', 'p95_frame_ms', 'P95帧时ms'), ('battle', 'peak_1s_fps', '一秒峰值FPS'),
          ('battle', 'peak_single_frame_fps', '单帧峰值FPS'), ('battle', 'cpu_s', '战斗CPU秒'),
          ('battle', 'mean_cpu_pct', '战斗CPU%'), ('battle', 'mean_machine_pct', '战斗整机%'),
          ('battle', 'peak_1s_cpu_pct', '战斗峰值CPU%')]


def load(folder):
    rows = {}
    for file in sorted(folder.glob('*/*/*/result.json')):
        rep, group, state, _ = file.relative_to(folder).parts
        if group not in GROUPS or state not in ('cold', 'hot'):
            raise ValueError(f'目录应为 轮次/{{stock,fr,sso}}/{{cold,hot}}：{file}')
        data = json.loads(file.read_text(encoding='utf-8'))
        if data['status'] != 'ok' or data['cache_state'] != state:
            raise ValueError(f'无效测量：{file}')
        rows[rep, group, state] = data
    reps = sorted({key[0] for key in rows})
    if not reps:
        raise ValueError('指定目录中没有测量数据')
    for rep in reps:
        for group in GROUPS:
            for state in ('cold', 'hot'):
                if (rep, group, state) not in rows:
                    raise ValueError(f'缺少 {rep}/{group}/{state}，请将诊断运行与计分运行分开存放')
    if len({r['logical_cpus'] for r in rows.values()}) != 1:
        raise ValueError('各轮逻辑 CPU 数不同')
    return reps, rows


def describe(values):
    if any(v is None for v in values):
        return '无完整窗口'
    if len(values) == 1:
        return f'{values[0]:.2f}'
    return f'{median(values):.2f} ({min(values):.2f}–{max(values):.2f})'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('runs', type=Path, help='包含 轮次/组别/cold 和 hot 的目录')
    p.add_argument('out', type=Path, help='本次汇总的新目录')
    a = p.parse_args()
    reps, rows = load(a.runs)
    a.out.mkdir(parents=True, exist_ok=False)
    with (a.out/'runs.csv').open('x', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['轮次', '组别', '启动次序', *(label for _, _, label in FIELDS)])
        for (rep, group, state), data in rows.items():
            writer.writerow([rep, GROUPS[group], state, *(data[phase][key] for phase, key, _ in FIELDS)])
    lines = [f'共 {len(reps)} 轮配对，每轮每组在独立进程中首启、次启各一次。多轮显示中位数（最小至最大）。', '']
    for state, title in [('cold', '首次启动'), ('hot', '第二次启动')]:
        lines += [f'## {title}', '', '|指标|原版|FastRender|SSOptimizer|', '|---|---:|---:|---:|']
        for phase, key, label in FIELDS:
            cells = [describe([rows[rep, group, state][phase][key] for rep in reps]) for group in GROUPS]
            lines.append('|' + '|'.join([label, *cells]) + '|')
        lines.append('')
    (a.out/'metrics.md').write_text('\n'.join(lines), encoding='utf-8')


if __name__ == '__main__':
    main()
