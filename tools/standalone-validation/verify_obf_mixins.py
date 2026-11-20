#!/usr/bin/env python3
"""在独立 RFB JVM 中织入 obf stage 中的游戏 Mixin，并保存逐目标报告。"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


HERE = Path(__file__).resolve().parent


def jdk_bin(java_home, name):
    if java_home:
        executable = name + '.exe' if os.name == 'nt' else name
        return (Path(java_home) / 'bin' / executable).resolve(strict=True)
    path = shutil.which(name)
    if path is None:
        raise OSError(f'找不到 {name}，请设置 --java-home')
    return Path(path).resolve(strict=True)


def execute(run, cwd, logfile, args, xstream=None):
    private_home = run / 'home'
    private_tmp = run / 'tmp'
    environment = {key: os.environ[key] for key in ('SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT') if key in os.environ}
    environment.update({'HOME': str(private_home), 'USERPROFILE': str(private_home),
                        'APPDATA': str(private_home), 'LOCALAPPDATA': str(private_home),
                        'TEMP': str(private_tmp), 'TMP': str(private_tmp), 'TMPDIR': str(private_tmp),
                        'LANG': 'C.UTF-8'})
    if xstream is not None:
        environment['SSO_XSTREAM'] = str(xstream)
    command = [str(arg) for arg in args]
    bwrap = shutil.which('bwrap') if os.name == 'posix' else None
    if bwrap:
        box = [bwrap, '--die-with-parent', '--new-session', '--unshare-pid', '--unshare-net',
               '--ro-bind', '/', '/', '--proc', '/proc', '--dev', '/dev',
               '--bind', str(run), str(run), '--chdir', str(cwd), '--clearenv']
        for key, value in environment.items():
            box.extend(['--setenv', key, value])
        command = box + ['--'] + command
    with logfile.open('x', encoding='utf-8') as log:
        result = subprocess.run(command, cwd=cwd, env=environment if not bwrap else None,
                                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True, help='Linux 或 Windows obf stage 的 version 目录')
    parser.add_argument('--game', type=Path, required=True, help='ABI 匹配的完整原版游戏安装目录')
    parser.add_argument('--out', type=Path, required=True, help='报告父目录，每次执行创建独立目录')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'), help='本机 JDK 25 目录')
    parser.add_argument('--xstream', type=Path, help='为本轮选择指定的 XStream JAR')
    args = parser.parse_args()
    try:
        stage = args.stage.resolve(strict=True)
        game = args.game.resolve(strict=True)
        core = game / 'starsector-core' if (game / 'starsector-core').is_dir() else game
        xstream = args.xstream.resolve(strict=True) if args.xstream else None
        output = args.out.resolve()
        if output.is_relative_to(stage) or output.is_relative_to(game):
            raise OSError('--out 必须位于游戏和发行版本目录之外')
        output.mkdir(parents=True, exist_ok=True)
        run = Path(tempfile.mkdtemp(prefix='obf-mixins-', dir=output))
        print(f'Mixin 织入记录：{run}', flush=True)
        for name in ('home', 'tmp', 'classes'):
            (run / name).mkdir()
        isolation = 'bubblewrap' if os.name == 'posix' and shutil.which('bwrap') else 'standalone-process'
        (run / 'isolation.txt').write_text(isolation + '\n', encoding='utf-8')
        java = jdk_bin(args.java_home, 'java')
        javac = jdk_bin(args.java_home, 'javac')
        libraries = sorted((stage / 'lib').glob('*.jar'))
        cp = os.pathsep.join(map(str, [run / 'classes', stage / 'agent.jar', *libraries]))
        compile_log = run / 'compile.log'
        if execute(run, run, compile_log, [javac, '-proc:none', '--release', '25', '-encoding', 'UTF-8',
                   '-cp', cp, '-d', run / 'classes', HERE / 'ObfMixinPlan.java', HERE / 'ObfMixinAudit.java', HERE / 'ObfMixinChecks.java']):
            raise OSError(f'验收程序编译失败：{compile_log}')
        prepare_log = run / 'prepare.log'
        props = ['-Dfile.encoding=UTF-8', '-Duser.home=' + str(run / 'home'), '-Djava.awt.headless=true']
        if execute(run, run, prepare_log, [java, *props, '-cp', cp, 'ssoptimizer.standalone.launch.ObfMixinPlan',
                                         stage, game, run], xstream):
            raise OSError(f'发行库或游戏 ABI 准备失败：{prepare_log}')
        launch_cp = os.pathsep.join([cp, *(str(core / name) for name in ('lwjgl.jar', 'lwjgl_util.jar'))])
        vm = ['-Xverify:all', '--enable-native-access=ALL-UNNAMED', *props,
              '-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader',
              '-Dlog4j2.configurationFile=classpath:standalone-log4j2.xml', '-Dmixin.env.compatLevel=JAVA_17',
              '-Dmixin.debug.verbose=true', '-Dmixin.debug.countInjections=true',
              '-Dssoptimizer.standalone.plan=' + str(run / 'launch.properties')]
        success = True
        for enabled in ('true', 'false'):
            mode = run / ('split' if enabled == 'true' else 'direct')
            mode.mkdir()
            for name in ('mods', 'saves', 'logs', 'screenshots'):
                (mode / name).mkdir()
            options = [f'-Dcom.fs.starfarer.settings.paths.{name}={mode / name}' for name in ('mods', 'saves', 'logs', 'screenshots')]
            log = mode / 'process.log'
            try:
                code = execute(run, mode, log, [java, *vm, *options, '-Dssoptimizer.audit.out=' + str(mode),
                               '-Dssoptimizer.renderthread.enable=' + enabled, '-cp', launch_cp,
                               'com.gtnewhorizons.retrofuturabootstrap.Main', '--gameDir', mode,
                               '--tweakClass', 'ssoptimizer.qa.ObfMixinAudit$Tweaker'])
            except subprocess.TimeoutExpired:
                code = 124
            result = mode / 'summary.tsv'
            print(f'{mode.name}: 退出 {code}，{result if result.is_file() else log}', flush=True)
            success &= code == 0 and result.is_file()
        return 0 if success else 1
    except (OSError, subprocess.TimeoutExpired) as error:
        parser.exit(2, f'{error}\n')


if __name__ == '__main__':
    raise SystemExit(main())
