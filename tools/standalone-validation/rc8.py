#!/usr/bin/env python3
"""在本机隔离目录检查 RC8 原始输入，或在独立副本中运行原版启动器。"""
import argparse
import hashlib
import os
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

from probe_environment import CONFIG_HELP, JRE17, LINUX, PREFS, ROOT, private_display

NAMES = ('starfarer_obf.jar', 'starfarer.api.jar', 'fs.common_obf.jar', 'fs.sound_obf.jar')
HASHES = {
    'english': ('5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8',
                'a7ba18f3476ffe704729bd0a7a47443f035fea98a32ac2930eae8b391d013c2a',
                '5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b',
                'd70e2760c9785770818607edd7be502ac75f7b87f8af5770c178a8d723c96dab'),
    'zh-hans': ('2e207732388bfb8290406362647c3578e00a4fdb6c1a4b9ef04a68001f69ed82',
                '769c8e9478df6303f3be1fb2514049e3d40453bc5478f4e0271da7c836e3ee2e',
                'b04b6f0203241f70e513fcd25b97b905e073eaa8acf8d2eacc83efe5966d6d13',
                '81df15baebf134eb3a9feca017223fa3e4a310704c7ab180f5aa1bbd4cc409fc'),
}
for font_variant in ('zh-hans-zongyi', 'zh-hans-songti'):
    HASHES[font_variant] = HASHES['zh-hans']


def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def verify(lang):
    source = ROOT / lang
    core = source / 'starsector-core'
    for name, expected in zip(NAMES, HASHES[lang], strict=True):
        if digest(core / name) != expected:
            raise SystemExit(f'核心指纹不符：{core / name}')
    for library in ('liblwjgl', 'libopenal', 'libjinput-linux'):
        for suffix in ('', '64'):
            name = f'{library}{suffix}.so'
            if digest(LINUX / 'native/linux' / name) != digest(core / 'native/linux' / name):
                raise SystemExit(f'Linux native 指纹不符：{name}')
    for d in ('saves', 'mods'):
        if any((source / d).iterdir()):
            raise SystemExit(f'基线 {source / d} 必须为空')
    return source


def box(run, cwd, direct=False, extra_env=None):
    home = run / 'home'
    display = os.environ.get('DISPLAY', ':1')
    if not re.fullmatch(r':\d+(?:\.\d+)?', display):
        raise SystemExit(f'需要本机 X11 显示：{display}')
    env = {'HOME': str(home), 'DISPLAY': display, 'PATH': '/usr/bin:/bin',
           'LANG': 'C.UTF-8', 'XDG_CONFIG_HOME': str(home / '.config'),
           'XDG_CACHE_HOME': str(home / '.cache'), 'XDG_DATA_HOME': str(home / '.local/share')}
    # 声音走宿主音频套接字，文件系统保持只读；X11 授权只供连接原有显示。
    for key in ('XAUTHORITY', 'XDG_RUNTIME_DIR', 'PULSE_SERVER', 'LIBGL_ALWAYS_SOFTWARE', 'ALSOFT_DRIVERS', '__NV_PRIME_RENDER_OFFLOAD',
                '__GLX_VENDOR_LIBRARY_NAME', 'DRI_PRIME'):
        if key in os.environ:
            env[key] = os.environ[key]
    authority = Path.home() / '.Xauthority'
    if 'XAUTHORITY' not in env and authority.is_file():
        env['XAUTHORITY'] = str(authority)
    args = ['bwrap', '--die-with-parent', '--unshare-pid', '--new-session', '--ro-bind', '/', '/',
            '--proc', '/proc', '--dev-bind', '/dev', '/dev', '--tmpfs', '/tmp',
            '--ro-bind', '/tmp/.X11-unix', '/tmp/.X11-unix', '--bind', run, run,
            '--chdir', cwd, '--clearenv']
    if 'XAUTHORITY' in env:
        path = Path(env['XAUTHORITY']).resolve(strict=True)
        args += ['--ro-bind', path, '/tmp/.rc8-xauthority']
        env['XAUTHORITY'] = '/tmp/.rc8-xauthority'
    if direct:
        if not PREFS.is_dir():
            raise SystemExit('未找到原版游戏的 Java 偏好目录；请通过普通启动器授权。')
        private = home / '.java/.userPrefs/com/fs/starfarer'
        private.mkdir(parents=True, exist_ok=True)
        args += ['--ro-bind', PREFS, private]
    env.update(extra_env or {})
    for key, value in env.items():
        args += ['--setenv', key, value]
    return args + ['--']


def java_args(game, run):
    # 从已校验的 Linux 发行脚本读取类路径和模块开放列表；游戏数据来自 Windows 树。
    args = shlex.split((LINUX / 'starsector.sh').read_text().replace('\\\n', ' '), comments=True)
    cp = args[args.index('-classpath') + 1]
    core = game / 'starsector-core'
    for jar in cp.split(':'):
        if not (core / jar).is_file():
            raise SystemExit(f'缺少发行依赖：{core / jar}')
    jvm = [a for a in args if a.startswith(('--add-opens=', '--add-exports='))]
    return [JRE17 / 'bin/java', '-noverify', '--enable-preview', '-Xms512m', '-Xmx2048m', '-Xss4m',
            '-Dfile.encoding=UTF-8', '-Djava.util.Arrays.useLegacyMergeSort=true', *jvm,
            '-Dcom.fs.starfarer.settings.linux=true', f'-Djava.library.path={core}/native/linux',
            f'-Duser.home={run}/home',
            f'-Dcom.fs.starfarer.settings.paths.mods={game}/mods',
            f'-Dcom.fs.starfarer.settings.paths.saves={game}/saves',
            f'-Dcom.fs.starfarer.settings.paths.screenshots={game}/screenshots',
            f'-Dcom.fs.starfarer.settings.paths.logs={run}/logs', '-classpath', cp]


def main():
    p = argparse.ArgumentParser(description=__doc__, epilog=CONFIG_HELP)
    p.add_argument('action', choices=['check', 'clone', 'play'])
    p.add_argument('language', choices=HASHES)
    p.add_argument('--session', default='baseline', help='同一语言和会话使用同一份测试存档、授权及设置')
    p.add_argument('--coordinated-launch', action='store_true', help='仅在已协调显示和游戏启动后启用')
    p.add_argument('--licensed-direct', action='store_true', help='由原版启动器只读检查现有授权，通过后进入标题')
    args = p.parse_args()
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,47}', args.session):
        p.error('会话名须为小写字母、数字或短横线')
    if args.action == 'play' and not args.coordinated_launch:
        p.error('实际启动需传入 --coordinated-launch')
    source = verify(args.language)
    sessions = ROOT / 'runs'
    sessions.mkdir(mode=0o700, exist_ok=True)
    if sessions.resolve() != sessions:
        raise SystemExit(f'运行目录包含符号链接：{sessions}')
    if args.action == 'check':
        with tempfile.TemporaryDirectory(dir=sessions) as temp:
            run = Path(temp)
            (run / 'home').mkdir()
            (run / 'logs').mkdir()
            isolated = box(run, source / 'starsector-core')
            subprocess.run(isolated + ['xset', 'q'], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(isolated + java_args(source, run) + ['--dry-run', 'com.fs.starfarer.StarfarerLauncher'], check=True)
            print(f'{args.language}: 四个核心、Linux native、Java 17 入口及 {os.environ.get("DISPLAY", ":1")} 显示检查通过')
        return
    run = sessions / (args.language + '-' + args.session)
    if run.resolve() != run:
        raise SystemExit(f'运行目录包含符号链接：{run}')
    game = run / 'game'
    if args.action == 'clone':
        run.mkdir(mode=0o700)
        (run / 'home').mkdir()
        (run / 'logs').mkdir()
        subprocess.run(['cp', '-a', '--reflink=auto', '--', source, game], check=True)
        print(game)
        return
    if not (game / 'starsector-core').is_dir():
        p.error('请先对相同语言和会话执行 clone')
    out = Path(tempfile.mkdtemp(dir=run / 'logs', prefix='play-'))
    with private_display(out):
        jvm = java_args(game, run)
        jvm += [f'-XX:StartFlightRecording=settings=profile,filename={out}/game.jfr,dumponexit=true,maxsize=128m']
        if args.licensed_direct:
            jvm += ['-DlaunchDirect=true', '-DstartFS=false', '-DstartRes=1280x720', '-DstartSound=false']
        print(f'本次进程日志和 JFR：{out}', flush=True)
        with (out / 'process.log').open('xb') as log:
            subprocess.run(box(run, game / 'starsector-core', args.licensed_direct) + jvm + ['com.fs.starfarer.StarfarerLauncher'],
                           stdout=log, stderr=subprocess.STDOUT, check=True)


if __name__ == '__main__':
    main()
