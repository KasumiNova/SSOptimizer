#!/usr/bin/env python3
"""基准 profile 聚合分析（cpu 事件 collapsed fold 格式：根帧在前，分号分隔，空格计数结尾）。

用法：aggregate_bench_profile.py <bench-profile.collapsed.txt> [topN]

分组口径（与历史轮次一致）：
  main      根帧 com/gtnewhorizons/retrofuturabootstrap/Main.main
  render    根帧 java/lang/Thread.run 且第 3 帧为 RenderQueueImpl 的 lambda
  ai-worker 根帧 AiParallelExecutorImpl$WorkerThread.run
  jit       栈含 CompileBroker（C1/C2 编译线程）
  other     其余（GC、sound、awt 等）

输出：各组占比 + main/render 组内自身时间（叶帧）热点 topN。
"""
import collections
import sys


def main() -> None:
    path = sys.argv[1]
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 25

    groups = collections.Counter()
    main_leaf = collections.Counter()
    render_leaf = collections.Counter()
    total = 0

    with open(path) as f:
        for line in f:
            line = line.rstrip('\n')
            if not line:
                continue
            stack, _, cnt = line.rpartition(' ')
            cnt = int(cnt)
            total += cnt
            frames = stack.split(';')
            root = frames[0]
            leaf = frames[-1]
            if 'CompileBroker' in stack:
                groups['jit'] += cnt
            elif root.startswith('com/gtnewhorizons/retrofuturabootstrap/'):
                groups['main'] += cnt
                main_leaf[leaf] += cnt
            elif 'AiParallelExecutorImpl$WorkerThread.run' in root:
                groups['ai-worker'] += cnt
            elif root == 'java/lang/Thread.run' and len(frames) > 2 \
                    and 'RenderQueueImpl' in frames[2]:
                groups['render'] += cnt
                render_leaf[leaf] += cnt
            else:
                groups['other'] += cnt

    print(f'total samples: {total}')
    for name in ('main', 'render', 'ai-worker', 'jit', 'other'):
        c = groups[name]
        print(f'{name:10s} {c:8d}  {100 * c / total:5.1f}%')
    main_c = groups['main']
    render_c = groups['render']
    if main_c:
        print(f'render/main = {render_c / main_c:.2f}')

    def dump(title: str, counter: collections.Counter, base: int) -> None:
        print(f'\n== {title} 自身时间 top{top_n}（组内占比 / 全局占比）==')
        for frame, c in counter.most_common(top_n):
            print(f'{100 * c / base:5.1f}% {100 * c / total:5.2f}%  {frame[:150]}')

    dump('main', main_leaf, main_c)
    dump('render', render_leaf, render_c)


if __name__ == '__main__':
    main()
