#!/usr/bin/env python3
"""在独占 Xvfb 中运行真实发行实例；通过窗口焦点及已验证的 1280×720 原版菜单操作驱动验收。"""
import argparse
import io
import json
import os
import re
import shlex
import signal
import subprocess
import time
from pathlib import Path

from PIL import Image
from probe_environment import CONFIG_HELP, JDK25, ROOT, private_display
from rc8 import HASHES, box, verify


def cmd(*args, capture=False):
    return subprocess.run(args, stdout=subprocess.PIPE if capture else None, check=True).stdout


def capture(window, file):
    data = cmd('import', '-window', window, 'png:-', capture=True)
    file.write_bytes(data)
    return Image.open(io.BytesIO(data)).convert('RGB')


def quick_cycle(proc, window, evidence, limit):
    process_log = evidence / 'process.log'
    for _ in range(4):
        cmd('xdotool', 'key', '1', 'sleep', '0.35')
    for key, marker in [('F5', 'time quicksaving:'), ('F9', 'time quickloading:')]:
        offset = process_log.stat().st_size
        cmd('xdotool', 'key', key)
        while time.monotonic() < limit and proc.poll() is None:
            with process_log.open('rb') as f:
                f.seek(offset)
                text = f.read().decode(errors='replace').lower()
            if any(error in text for error in ('error saving game', 'error loading', 'rejected-type')):
                raise SystemExit(f'{key} 游戏返回错误，见 {process_log}')
            if marker in text:
                capture(window, evidence / f'{key}.png')
                break
            time.sleep(.25)
        else:
            capture(window, evidence / f'{key}-missing.png')
            raise SystemExit(f'{key} 未产生已完成的调用记录，见 {process_log}')


def run(a, game, release, inst, evidence):
    display = os.environ.get('DISPLAY', '')
    if not re.fullmatch(r':\d+', display) or int(display[1:]) < 140:
        raise SystemExit('必须使用本探针创建的独立 Xvfb 显示')
    opts = ['-DlaunchDirect=true', '-DstartRes=1280x720', '-DstartFS=false', '-DstartSound=false',
            f'-Duser.home={inst}/home']
    if a.action == 'battle':
        opts += ['-Dssoptimizer.bench.enabled=true', '-Dssoptimizer.bench.mission=afistfulofcredits',
                 '-Dssoptimizer.bench.durationSec=5', '-Dssoptimizer.bench.warmupSec=0',
                 '-Dssoptimizer.bench.profiler.enabled=false', '-Dssoptimizer.bench.screenshotIntervalSec=2',
                 f'-Dssoptimizer.bench.outputDir={evidence}']
    if a.action in ('load', 'resave'):
        slot = inst / 'saves' / a.slot
        if slot.resolve() != slot or not all((slot / n).is_file() for n in ('descriptor.xml', 'campaign.xml')):
            raise SystemExit(f'隔离存档不完整：{slot}')
        opts += ['-Dssoptimizer.automation.enabled=true', '-Dssoptimizer.automation.scenario=save_load_cycle',
                 f'-Dssoptimizer.automation.saveload.saveAfterLoad={str(a.action == "resave").lower()}',
                 f'-Dssoptimizer.automation.saveload.saveDir={slot}',
                 f'-Dssoptimizer.automation.outputDir={evidence}']
    start = box(inst, game / 'starsector-core', direct=True,
                extra_env={'JAVA_TOOL_OPTIONS': shlex.join(opts), 'JAVA_HOME': str(JDK25)})
    start += [release / 'launch.sh', 'launch', '--game', game, '--instance', inst]
    limit = time.monotonic() + a.timeout
    window = None
    submitted = False
    previous = set((inst / 'saves').glob('*/descriptor.xml'))
    with (evidence / 'process.log').open('xb') as log:
        proc = subprocess.Popen(start, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            while time.monotonic() < limit:
                status = proc.poll()
                if status is not None:
                    if status != 0:
                        raise SystemExit(f'游戏退出 {status}，见 {evidence}')
                    break
                if window is None:
                    ids = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name', 'Starsector'], capture_output=True).stdout.split()
                    if ids:
                        window = ids[-1].decode()
                        cmd('xdotool', 'windowfocus', window)
                if a.action == 'new' and window:
                    if not submitted:
                        img = capture(window, evidence / 'window.png')
                        if img.size != (1280, 720):
                            time.sleep(.5)
                            continue
                        def button(y):
                            r, g, b = img.getpixel((970, y)); return 25 < g < 140 and g-r > 20 and b-g >= 5
                        if all(button(y) for y in (273, 306, 341, 376)):
                            cmd('xdotool', 'mousemove', '--window', window, '1057', '307', 'click', '1', 'sleep', '0.75',
                                'mousemove', '--window', window, '810', '359', 'click', '1',
                                'mousemove', '--window', window, '850', '150', 'click', '1', 'type', 'RC8Validation')
                            cmd('xdotool', 'mousemove', '--window', window, '222', '528', 'click', '1', 'sleep', '0.7',
                                'key', '1', 'sleep', '0.5', 'key', '1', 'sleep', '0.5', 'key', '1', 'sleep', '0.5', 'key', '1')
                            submitted = True
                    else:
                        slots = set((inst / 'saves').glob('*/descriptor.xml')) - previous
                        if slots:
                            if len(slots) != 1:
                                raise SystemExit('本次新局产生了多个槽，请检查实例是否被独占')
                            descriptor = slots.pop()
                            if (descriptor.parent / 'campaign.xml').stat().st_size > 0:
                                time.sleep(1.5)
                                capture(window, evidence / 'campaign.png')
                                print('新局首存：', descriptor.parent, flush=True)
                                if a.quick_cycle:
                                    quick_cycle(proc, window, evidence, limit)
                                return
                        if 'Error saving game' in (evidence / 'process.log').read_text(errors='replace'):
                            raise SystemExit(f'原版保存返回错误，见 {evidence}')
                time.sleep(.4)
            else:
                if window:
                    capture(window, evidence / 'timeout.png')
                raise SystemExit(f'超过 {a.timeout} 秒，见 {evidence}')
        finally:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(8)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait()
    if a.action == 'battle':
        data = json.loads((evidence / 'bench-summary.json').read_text())
        if data['mission'] != 'afistfulofcredits' or data['frames'] <= 0 or not any((evidence / 'frames').glob('*.png')):
            raise SystemExit('缺少战斗帧或截图')
    elif a.action in ('load', 'resave'):
        data = json.loads((evidence / 'saveload-telemetry.json').read_text())
        if a.action == 'resave' and data['saveMs'] < 0:
            raise SystemExit('存档场景未执行写入')
        if data['success'] is not True or data['error'] or data['saveError'] or data['saveDir'] != str(slot):
            raise SystemExit(f'读档失败：{data}')
    else:
        raise SystemExit('进程结束前未生成新局存档')
    print(json.dumps(data, ensure_ascii=False))


def main():
    p = argparse.ArgumentParser(description=__doc__, epilog=CONFIG_HELP)
    p.add_argument('action', choices=('new', 'battle', 'load', 'resave'))
    p.add_argument('language', choices=HASHES)
    p.add_argument('--release', required=True, help='验收根下的版本快照目录名')
    p.add_argument('--instance', required=True, help='验收根下的独立实例目录名')
    p.add_argument('--label', required=True, help='本次新证据目录名')
    p.add_argument('--slot', default='')
    p.add_argument('--timeout', type=int, default=180)
    p.add_argument('--quick-cycle', action='store_true', help='新局首存后在同一进程继续验证 F5/F9')
    p.add_argument('--coordinated-launch', action='store_true')
    a = p.parse_args()
    if not a.coordinated_launch or not all(re.fullmatch('[A-Za-z0-9_-]+', s) for s in (a.release, a.instance, a.label)):
        p.error('需已协调启动，名称仅允许英文、数字、短横线和下划线')
    if a.quick_cycle and a.action != 'new':
        p.error('--quick-cycle 仅用于新局')
    if not a.instance.endswith('-instance') or a.timeout <= 0:
        p.error('实例名称须以 -instance 结尾，超时须为正数')
    if a.action in ('load', 'resave') and not re.fullmatch(r'[A-Za-z0-9_-]+', a.slot):
        p.error('读档需明确指定隔离槽名称')
    game, release, inst = verify(a.language), ROOT / a.release, ROOT / a.instance
    if inst == release or release.resolve() != release or not (release/'launch.sh').is_file():
        p.error('需要独立的发布快照和实例目录')
    out = inst/'evidence'/a.label
    for private in (inst/'saves', inst/'home/.java/.userPrefs/com/fs/starfarer', out):
        if private.resolve() != private:
            p.error(f'隔离目录中存在符号链接：{private}')
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    with private_display(out):
        run(a, game, release, inst, out)


if __name__ == '__main__':
    main()
