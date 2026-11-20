#!/usr/bin/env python3
"""使用指定原版游戏副本运行 RC8 基线。"""
import argparse
import os
import shlex
import subprocess
from pathlib import Path

from probe_environment import CONFIG_HELP, JRE17, LINUX, PREFS, private_display


def game_core(game):
    core = game / 'starsector-core'
    return core if core.is_dir() else game


def copy_game(source, output):
    source = source.resolve(strict=True)
    output = output.resolve()
    if output.is_relative_to(source):
        raise ValueError('证据目录必须位于游戏目录之外')
    output.mkdir(parents=True, exist_ok=False)
    game = output / 'game'
    subprocess.run(['cp', '-a', '--reflink=auto', '--', source, game], check=True)
    return game, output


def box(output, cwd, licensed_direct=False, extra_env=None):
    private_home = output / 'home'
    private_home.mkdir(exist_ok=True)
    env = {'HOME': str(private_home), 'DISPLAY': os.environ['DISPLAY'], 'PATH': '/usr/bin:/bin',
           'LANG': 'C.UTF-8', 'XDG_CONFIG_HOME': str(private_home / '.config'),
           'XDG_CACHE_HOME': str(private_home / '.cache'), 'XDG_DATA_HOME': str(private_home / '.local/share')}
    for key in ('XAUTHORITY', 'XDG_RUNTIME_DIR', 'PULSE_SERVER', 'LIBGL_ALWAYS_SOFTWARE', 'ALSOFT_DRIVERS', '__NV_PRIME_RENDER_OFFLOAD',
                '__GLX_VENDOR_LIBRARY_NAME', 'DRI_PRIME'):
        if key in os.environ:
            env[key] = os.environ[key]
    args = ['bwrap', '--die-with-parent', '--unshare-pid', '--new-session', '--ro-bind', '/', '/',
            '--proc', '/proc', '--dev-bind', '/dev', '/dev', '--tmpfs', '/tmp',
            '--ro-bind', '/tmp/.X11-unix', '/tmp/.X11-unix', '--bind', output, output,
            '--chdir', cwd, '--clearenv']
    if 'XAUTHORITY' in env:
        authority = Path(env['XAUTHORITY']).resolve(strict=True)
        args += ['--ro-bind', authority, '/tmp/.rc8-xauthority']
        env['XAUTHORITY'] = '/tmp/.rc8-xauthority'
    if licensed_direct:
        prefs = PREFS.resolve(strict=True)
        private = private_home / '.java/.userPrefs/com/fs/starfarer'
        private.mkdir(parents=True, exist_ok=True)
        args += ['--ro-bind', prefs, private]
    env.update(extra_env or {})
    for key, value in env.items():
        args += ['--setenv', key, value]
    return args + ['--']


def java_args(game, output):
    args = shlex.split((LINUX / 'starsector.sh').read_text().replace('\\\n', ' '), comments=True)
    cp = args[args.index('-classpath') + 1]
    core = game_core(game)
    module_options = [a for a in args if a.startswith(('--add-opens=', '--add-exports='))]
    return [JRE17 / 'bin/java', '-noverify', '--enable-preview', '-Xms512m', '-Xmx2048m', '-Xss4m',
            '-Dfile.encoding=UTF-8', '-Djava.util.Arrays.useLegacyMergeSort=true', *module_options,
            '-Dcom.fs.starfarer.settings.linux=true', f'-Djava.library.path={core}/native/linux',
            f'-Duser.home={output}/home', f'-Dcom.fs.starfarer.settings.paths.mods={game}/mods',
            f'-Dcom.fs.starfarer.settings.paths.saves={game}/saves',
            f'-Dcom.fs.starfarer.settings.paths.screenshots={game}/screenshots',
            f'-Dcom.fs.starfarer.settings.paths.logs={game}/logs', '-classpath', cp]


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=CONFIG_HELP)
    parser.add_argument('--game', type=Path, required=True, help='原版游戏目录，运行时使用证据目录中的副本')
    parser.add_argument('--out', type=Path, required=True, help='新建证据目录')
    parser.add_argument('--licensed-direct', action='store_true', help='只读使用已有授权并直接进入标题')
    args = parser.parse_args()
    game, output = copy_game(args.game, args.out)
    with private_display(output):
        launch = box(output, game_core(game), args.licensed_direct) + java_args(game, output)
        launch += [f'-XX:StartFlightRecording=settings=profile,filename={output}/game.jfr,dumponexit=true,maxsize=128m']
        if args.licensed_direct:
            launch += ['-DlaunchDirect=true', '-DstartFS=false', '-DstartRes=1280x720', '-DstartSound=false']
        print(f'进程日志和 JFR 位于 {output}', flush=True)
        with (output / 'process.log').open('xb') as log:
            subprocess.run(launch + ['com.fs.starfarer.StarfarerLauncher'], stdout=log, stderr=subprocess.STDOUT, check=True)


if __name__ == '__main__':
    main()
