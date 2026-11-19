#!/usr/bin/env python3
"""在独立 systemd 用户 scope 中运行游戏命令，记录启动及战斗阶段的 CPU 和帧间隔。"""
import argparse
import bisect
import csv
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

NS = 1_000_000_000
CPU_FIELDS = ['monotonic_ns','wall_ns','usage_usec','host_busy_ticks','host_total_ticks','pswpin_pages','pswpout_pages']


def require(condition, message):
    if not condition:
        raise ValueError(message)


def interpolated(xs, ys, x):
    require(xs[0] <= x <= xs[-1], '阶段超出 CPU/帧采样范围')
    i = min(bisect.bisect_right(xs, x) - 1, len(xs) - 2)
    return ys[i] + (ys[i + 1] - ys[i]) * ((x - xs[i]) / (xs[i + 1] - xs[i]))


def one_second_peak(xs, ys):
    if xs[-1] - xs[0] < NS:
        return None
    starts = {x for x in xs if x + NS <= xs[-1]}
    starts.update(x - NS for x in xs if x - NS >= xs[0])
    return max(interpolated(xs, ys, s + NS) - interpolated(xs, ys, s) for s in starts)


def cpu_phase(samples, start, end, cores):
    require(end > start, 'CPU 阶段长度无效')
    xs, ys = zip(*samples)
    a, b = bisect.bisect_right(xs, start), bisect.bisect_left(xs, end)
    xp = [start, *xs[a:b], end]
    yp = [interpolated(xs, ys, start), *ys[a:b], interpolated(xs, ys, end)]
    seconds = (end - start) / NS
    used = (yp[-1] - yp[0]) / 1e6
    avg = 100 * used / seconds
    peak = max(100_000 * (v-u)/(y-x) for x,y,u,v in zip(xp,xp[1:],yp,yp[1:]))
    sustained = one_second_peak(xp, yp)
    return dict(duration_s=seconds, cpu_s=used, mean_cpu_pct=avg, mean_machine_pct=avg/cores,
                peak_sample_cpu_pct=peak, peak_sample_machine_pct=peak/cores,
                peak_1s_cpu_pct=None if sustained is None else sustained/10000,
                peak_1s_machine_pct=None if sustained is None else sustained/10000/cores)


def complete_frames_peak(times):
    if times[-1] - times[0] < NS:
        return None
    left = peak = 0
    for right, end in enumerate(times):
        while end - times[left] > NS:
            left += 1
        peak = max(peak, right - left)
    return peak


def frame_stats(path, begin, end):
    times = [begin]
    intervals = []
    with path.open(newline='', encoding='utf-8') as f:
        rows = csv.reader(f)
        require(next(rows, []) == ['frame','end_ns','dt_ns'], 'frames.csv 表头不符')
        for row in rows:
            number, stamp, dt = map(int, row)
            require(number == 1 + len(intervals) and dt > 0 and stamp - dt == times[-1], '帧编号或时间轴不连续')
            times.append(stamp)
            intervals.append(dt)
    require(intervals and times[-1] == end, '缺少战斗结束帧')
    p95 = sorted(intervals)[(95*len(intervals)+99)//100-1]
    return dict(frames=len(intervals), fps=len(intervals)*NS/sum(intervals), low95_fps=NS/p95,
                p95_frame_ms=p95/1e6, peak_1s_fps=complete_frames_peak(times),
                peak_single_frame_fps=NS/min(intervals))


def clocks():
    mono = time.monotonic_ns()
    wall = time.time_ns()
    return (mono + time.monotonic_ns())//2, wall


def host_counters():
    with open('/proc/stat') as f:
        label, *fields = f.readline().split()
    require(label == 'cpu' and len(fields) >= 8, '/proc/stat 缺少全机 CPU 计数')
    # guest 已计入 user/nice；idle 和 iowait 都不计作 busy。
    user,nice,system,idle,iowait,irq,softirq,steal = map(int,fields[:8])
    busy = user + nice + system + irq + softirq + steal
    with open('/proc/vmstat') as f:
        swap = {k:int(v) for k,v in map(str.split,f) if k in ('pswpin','pswpout')}
    return busy,busy+idle+iowait,swap['pswpin'],swap['pswpout']


def host_phase(samples, start, end):
    xs,*counters = zip(*samples)
    busy,total,swap_in,swap_out = [interpolated(xs,y,end)-interpolated(xs,y,start) for y in counters]
    return dict(host_cpu_pct=100*busy/total if total > 0 else None,
                host_swap_in_pages=swap_in,host_swap_out_pages=swap_out)


def read_events(path):
    events = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        name, ns, wall = line.split('\t')
        require(name not in events, f'探针重复记录 {name}')
        events[name] = (int(ns), int(wall))
    expected = ['TITLE_READY','WARMUP_BEGIN','SAMPLE_BEGIN','SAMPLE_END','DONE']
    require(list(events) == expected, '阶段缺失、顺序错误或探针报告失败')
    stamps = [ns for ns,_ in events.values()]
    require(all(y >= x for x,y in zip(stamps,stamps[1:])), 'JVM 时钟倒退')
    require(events['SAMPLE_BEGIN'][0] - events['WARMUP_BEGIN'][0] >= 20*NS, '战斗预热不足 20 秒')
    return events


def summary(out, metadata):
    samples,host = [],[]
    with (out/'cpu.csv').open(newline='') as f:
        rows = csv.reader(f)
        require(next(rows, []) == CPU_FIELDS, 'cpu.csv 表头不符')
        for row in rows:
            m,wall,usage,busy,total,swap_in,swap_out = map(int,row)
            require(not samples or (m > samples[-1][0] and usage >= samples[-1][1]), 'CPU 样本时间或计数倒退')
            samples.append((m,usage)); host.append((m,busy,total,swap_in,swap_out))
    require(len(samples) >= 2, 'CPU 样本不足')
    start = metadata['command_start_monotonic_ns']
    anchors = metadata['clock_anchors']
    min_duration = metadata['minimum_battle_s']
    events = read_events(out/'events.tsv')
    offsets = [a['monotonic_ns']-a['wall_ns'] for a in anchors]
    require(abs(offsets[0]-offsets[1]) < 20_000_000, '测量期间宿主墙钟跳变')
    ns0, wall0 = events['TITLE_READY']
    for ns,wall in events.values():
        require(abs((wall-wall0)*1_000_000 - (ns-ns0)) < 20_000_000, 'JVM 阶段时钟不一致')
    title = wall0*1_000_000 + sum(offsets)//2
    warm, begin, end = (events[k][0] for k in ('WARMUP_BEGIN','SAMPLE_BEGIN','SAMPLE_END'))
    require(end-begin >= min_duration*NS, '战斗样本时长不足')
    cores = metadata['logical_cpus']
    require(cores and cores > 0, '无法读取宿主逻辑 CPU 数')
    def phase(a,b):
        return {**cpu_phase(samples,a,b,cores), **host_phase(host,a,b)}
    bounds = dict(startup=[start,title], warmup=[title+warm-ns0,title+begin-ns0], battle=[title+begin-ns0,title+end-ns0])
    if 'phase_monotonic_ns' in metadata:
        require(metadata['phase_monotonic_ns'] == bounds, '原始事件与保存的阶段边界不一致')
    metadata['phase_monotonic_ns'] = bounds
    durations = {k:b-a for k,(a,b) in bounds.items()}
    if 'phase_duration_ns' in metadata:
        require(metadata['phase_duration_ns'] == durations, '原始事件与保存的阶段时长不一致')
    metadata['phase_duration_ns'] = durations
    return dict(logical_cpus=cores, startup=phase(start,title),
                battle={**phase(title+begin-ns0,title+end-ns0),
                        **frame_stats(out/'frames.csv',begin,end)})


def gate(out, command):
    # scope 内的监护进程保持 cgroup 存活，直至外部采样进程读完退出时的计数。
    group = next(x[3:] for x in Path('/proc/self/cgroup').read_text().splitlines() if x.startswith('0::'))
    (out/'cgroup.tmp').write_text(group)
    (out/'cgroup.tmp').rename(out/'cgroup')
    while not (out/'start').exists():
        time.sleep(.01)
    result = subprocess.run(command)
    (out/'exit.tmp').write_text(str(result.returncode))
    (out/'exit.tmp').rename(out/'exit-code')
    while not (out/'release').exists():
        time.sleep(.02)
    return result.returncode if result.returncode >= 0 else 128-result.returncode


def run(args, out, unit, command, metadata):
    started = time.monotonic()
    env = os.environ.copy()
    env['SSO_PERF_OUT'] = 'Z:' + str(out).replace('/', '\\') if args.wine_path else str(out)
    deadline = time.monotonic() + args.timeout
    wrapper = [sys.executable, str(Path(__file__).resolve()), '_gate', str(out), *command]
    with (out/'process.log').open('xb') as log, (out/'cpu.csv').open('x',newline='') as raw:
        proc = subprocess.Popen(['systemd-run','--user','--scope','--quiet','--unit='+unit,'--',*wrapper],
                                stdout=log,stderr=subprocess.STDOUT,env=env)
        try:
            while not (out/'cgroup').exists():
                require(proc.poll() is None, 'systemd-run 提前退出，请查看 process.log')
                require(time.monotonic() < min(deadline, started+15), '创建 scope 超时')
                time.sleep(.01)
            group = Path((out/'cgroup').read_text())
            require(group.is_absolute() and '..' not in group.parts and group.name == unit, 'cgroup 路径与本轮 scope 不符')
            statfile = Path('/sys/fs/cgroup')/group.relative_to('/')/'cpu.stat'
            writer = csv.writer(raw); writer.writerow(CPU_FIELDS)
            def sample():
                t = time.monotonic_ns()
                usage = int(dict(line.split() for line in statfile.read_text().splitlines())['usage_usec'])
                counters = host_counters()
                m,w = clocks(); middle=(m+t)//2; w += middle-m; m=middle
                writer.writerow([m,w,usage,*counters]); raw.flush()
            anchor = clocks(); sample()
            start,wall = clocks(); metadata.update(command_start_monotonic_ns=start,command_start_wall_ns=wall)
            (out/'start').touch(exist_ok=False)
            while not (out/'exit-code').exists():
                require(proc.poll() is None, 'scope 在回收 CPU 计数前退出')
                require(time.monotonic() < deadline, '命令运行超时')
                time.sleep(args.interval); sample()
            sample(); final_anchor = clocks()
            metadata['clock_anchors'] = [dict(monotonic_ns=m,wall_ns=w) for m,w in (anchor,final_anchor)]
            metadata.update(command_exit_observed_monotonic_ns=final_anchor[0],command_exit_observed_wall_ns=final_anchor[1])
            (out/'release').touch(exist_ok=False)
            code = proc.wait(timeout=10)
            require(code == 0 and int((out/'exit-code').read_text()) == 0, f'命令退出码 {code}')
            require(not (out/'failure.txt').exists(), '探针报告失败，见 failure.txt')
            if (out/'frames.csv').is_symlink():
                from probe.link_output import collect
                collect(out)
            return summary(out,metadata)
        finally:
            if proc.poll() is None:
                subprocess.run(['systemctl','--user','stop',unit],stdout=log,stderr=log,timeout=30)
                try: proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    proc.kill(); proc.wait()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-dir',type=Path,required=True,help='每次测量使用的全新宿主目录')
    p.add_argument('--cache-state',choices=['cold','hot'],required=True,help='调用者已准备的实例缓存状态')
    p.add_argument('--min-battle-seconds',type=float,required=True)
    p.add_argument('--interval',type=float,default=.1,help='CPU 采样间隔，秒')
    p.add_argument('--timeout',type=float,default=900)
    p.add_argument('--wine-path',action='store_true',help='将 SSO_PERF_OUT 转为 Wine Z: 路径')
    p.add_argument('command',nargs=argparse.REMAINDER)
    a=p.parse_args(); cmd=a.command[1:] if a.command[:1]==['--'] else a.command
    if not cmd or not all(math.isfinite(x) and x>0 for x in (a.interval,a.timeout,a.min_battle_seconds)) or a.interval>1:
        p.error('需指定命令、正数超时与样本时长，CPU 采样间隔不超过一秒')
    out=a.run_dir.resolve(); out.mkdir(parents=True,exist_ok=False)
    unit='sso-perf-'+uuid.uuid4().hex+'.scope'
    meta=dict(scope=unit,cache_state=a.cache_state,interval_s=a.interval,minimum_battle_s=a.min_battle_seconds)
    m,w=clocks()
    metadata=dict(**meta,command=cmd,timeout_s=a.timeout,wine_path=a.wine_path,logical_cpus=os.cpu_count(),
                  clock_ticks_per_s=os.sysconf('SC_CLK_TCK'),page_size_bytes=os.sysconf('SC_PAGE_SIZE'),
                  run_start_monotonic_ns=m,run_start_wall_ns=w)
    (out/'metadata.json').write_text(json.dumps(metadata,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    try:
        result=run(a,out,unit,cmd,metadata); meta.update(status='ok',**result); status=0
    except (Exception,KeyboardInterrupt) as error:
        meta.update(status='failed',error=str(error) or type(error).__name__); status=1
    m,w=clocks(); metadata.update(run_end_monotonic_ns=m,run_end_wall_ns=w,status=meta['status'])
    (out/'metadata.json').write_text(json.dumps(metadata,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    text=json.dumps(meta,indent=2,ensure_ascii=False,allow_nan=False)
    (out/'result.json').write_text(text+'\n',encoding='utf-8');print(text); return status


if __name__=='__main__':
    if sys.argv[1:2]==['--recompute']:
        p=argparse.ArgumentParser(description='从成功样本的四份原始文件复算并输出 JSON，不修改文件')
        p.add_argument('--recompute', type=Path, required=True)
        out=p.parse_args().recompute
        try:
            metadata=json.loads((out/'metadata.json').read_text(encoding='utf-8'))
            require(metadata['status']=='ok', '只复算已成功完成的轮次')
            result={k:metadata[k] for k in ('scope','cache_state','interval_s','minimum_battle_s','status')}
            print(json.dumps({**result, **summary(out,metadata)},indent=2,ensure_ascii=False,allow_nan=False))
        except (OSError,KeyError,ValueError) as error:
            p.exit(1,f'复算失败：{error}\n')
        sys.exit(0)
    if sys.argv[1:2]==['_gate']:
        sys.exit(gate(Path(sys.argv[2]),sys.argv[3:]))
    def interrupted(*_):
        raise KeyboardInterrupt('收到终止信号')
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
