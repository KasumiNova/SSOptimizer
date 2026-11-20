#!/usr/bin/env python3
"""在独占 Xvfb 中运行发行包的新局、读档、重存或战斗场景。"""
import argparse
import io
import json
import os
import shlex
import signal
import subprocess
import time
from pathlib import Path

from PIL import Image
from probe_environment import CONFIG_HELP, JDK25, private_display
from rc8 import box, copy_game, game_core


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


def run(a, game, release, evidence):
    display = os.environ.get('DISPLAY', '')
    opts = ['-DlaunchDirect=true', '-DstartRes=1280x720', '-DstartFS=false', '-DstartSound=false',
            f'-Duser.home={evidence}/home']
    if a.action == 'battle':
        opts += ['-Dssoptimizer.bench.enabled=true', '-Dssoptimizer.bench.mission=afistfulofcredits',
                 '-Dssoptimizer.bench.durationSec=5', '-Dssoptimizer.bench.warmupSec=0',
                 '-Dssoptimizer.bench.profiler.enabled=false', '-Dssoptimizer.bench.screenshotIntervalSec=2',
                 f'-Dssoptimizer.bench.outputDir={evidence}']
    if a.action in ('load', 'resave'):
        slot = (game / 'saves' / a.slot).resolve(strict=True)
        if not slot.is_relative_to(game / 'saves'):
            raise SystemExit(f'存档必须位于游戏副本的 saves 目录：{slot}')
        opts += ['-Dssoptimizer.automation.enabled=true', '-Dssoptimizer.automation.scenario=save_load_cycle',
                 f'-Dssoptimizer.automation.saveload.saveAfterLoad={str(a.action == "resave").lower()}',
                 f'-Dssoptimizer.automation.saveload.saveDir={slot}',
                 f'-Dssoptimizer.automation.outputDir={evidence}']
    start = box(evidence, game_core(game), licensed_direct=True,
                extra_env={'JAVA_TOOL_OPTIONS': shlex.join(opts), 'JAVA_HOME': str(JDK25)})
    start += [release / 'launch.sh', 'launch', '--game', game]
    (evidence / 'input.json').write_text(json.dumps({'action': a.action, 'game': str(game),
        'release': str(release), 'jdk': str(JDK25), 'display': display, 'jvmOptions': opts},
        ensure_ascii=False, indent=2) + '\n')
    limit = time.monotonic() + a.timeout
    window = None
    submitted = False
    previous = set((game / 'saves').glob('*/descriptor.xml'))
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
                        slots = set((game / 'saves').glob('*/descriptor.xml')) - previous
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
        visible = False
        for screenshot in (evidence / 'frames').glob('*.png'):
            with Image.open(screenshot) as image:
                if image.size != (1280, 720):
                    raise SystemExit(f'战斗截图分辨率不符：{screenshot}')
                lo, hi = image.convert('L').getextrema()
                visible |= hi > lo
        if data['mission'] != 'afistfulofcredits' or data['frames'] <= 0 or not visible:
            raise SystemExit('缺少真实战斗帧或非单色截图')
    elif a.action in ('load', 'resave'):
        data = json.loads((evidence / 'saveload-telemetry.json').read_text())
        if a.action == 'resave' and data['saveMs'] < 0:
            raise SystemExit('存档场景未执行写入')
        if data['success'] is not True or data['error'] or data['saveError'] or data['saveDir'] != str(slot):
            raise SystemExit(f'读档失败：{data}')
    else:
        raise SystemExit('进程结束前未生成新局存档')
    print(json.dumps(data, ensure_ascii=False))


def enable_release(game, release):
    mods = game / 'mods'
    mods.mkdir(exist_ok=True)
    if mods.resolve() != mods:
        raise ValueError(f'游戏副本的 mods 目录指向外部位置 {mods}')
    optimizer = mods / 'ssoptimizer'
    if optimizer.is_symlink():
        optimizer.unlink()
    elif optimizer.exists():
        raise ValueError(f'游戏副本需要空的 SSOptimizer 安装位置 {optimizer}')
    optimizer.symlink_to(release / 'mod', target_is_directory=True)
    selection = mods / 'enabled_mods.json'
    if selection.is_symlink():
        enabled = json.loads(selection.read_text())
        selection.unlink()
    elif selection.exists():
        enabled = json.loads(selection.read_text())
    else:
        enabled = {'enabledMods': []}
    if 'ssoptimizer' not in enabled['enabledMods']:
        enabled['enabledMods'].append('ssoptimizer')
    selection.write_text(json.dumps(enabled, ensure_ascii=False, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=CONFIG_HELP)
    parser.add_argument('action', choices=('new', 'battle', 'load', 'resave'))
    parser.add_argument('--game', type=Path, required=True, help='用于复制场景的游戏目录')
    parser.add_argument('--release', type=Path, required=True, help='发行包 version 目录')
    parser.add_argument('--out', type=Path, required=True, help='新建证据目录')
    parser.add_argument('--slot', default='')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--quick-cycle', action='store_true', help='新局首存后继续验证 F5/F9')
    args = parser.parse_args()
    if args.quick_cycle and args.action != 'new':
        parser.error('--quick-cycle 仅用于新局')
    if args.timeout <= 0:
        parser.error('超时须为正数')
    if args.action in ('load', 'resave') and (not args.slot or args.slot in ('.', '..') or Path(args.slot).name != args.slot):
        parser.error('读档和重存需要 --slot <游戏目录中实际的存档槽名>')
    release = args.release.resolve(strict=True)
    if args.out.resolve().is_relative_to(release):
        parser.error('证据目录必须位于发行目录之外')
    if not (release / 'mod/jars/payload.jar').is_file():
        parser.error('发行目录缺少 mod/jars/payload.jar')
    game, output = copy_game(args.game, args.out)
    fixture = output / 'version'
    fixture.mkdir()
    release_files = [entry for entry in release.iterdir() if entry.name != 'work']
    subprocess.run(['cp', '-a', '--reflink=auto', '--', *release_files, fixture], check=True)
    enable_release(game, fixture)
    with private_display(output):
        run(args, game, fixture, output)


if __name__ == '__main__':
    main()
