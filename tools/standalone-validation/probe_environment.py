"""Linux 验收路径和私有 X11 显示。"""
import os
import secrets
import shutil
import subprocess
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch


def location(name, default):
    return Path(os.environ.get(name, default)).expanduser().resolve()


ROOT = location('SSO_VALIDATION_ROOT', '/mnt/store/Games/ssoptimizer-standalone-validation-rc8')
LINUX = location('SSO_VALIDATION_LINUX', ROOT / 'linux/starsector')
JRE17 = location('SSO_VALIDATION_JRE17', LINUX / 'jre_linux')
JDK25 = location('SSO_VALIDATION_JDK25', os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-25-openjdk'))
XVFB = location('SSO_VALIDATION_XVFB', shutil.which('Xvfb') or ROOT / 'linux/xvfb/usr/bin/Xvfb')
PREFS = location('SSO_VALIDATION_PREFS', Path.home() / '.java/.userPrefs/com/fs/starfarer')
CONFIG_HELP = ('环境变量：SSO_VALIDATION_ROOT 指定含发行输入的验收根，SSO_VALIDATION_JDK25 和 '
               'SSO_VALIDATION_JRE17 指向对应的 Java 主目录，SSO_VALIDATION_LINUX 指向原版 Linux 启动脚本和 native 所在目录，'
               'SSO_VALIDATION_XVFB 指向 Xvfb 可执行文件，SSO_VALIDATION_PREFS 指向仅供原版启动器检查的只读授权节点。')


def free_display():
    # Xwayland 的文件名可能消失但 socket 仍在侦听；这种显示号也必须排除。
    endpoints = {line.split()[-1].lstrip('@') for line in Path('/proc/net/unix').read_text().splitlines() if line.split()}
    for n in range(140 + secrets.randbelow(500), 1000):
        socket = f'/tmp/.X11-unix/X{n}'
        if socket not in endpoints and not os.path.lexists(socket) and not os.path.lexists(f'/tmp/.X{n}-lock'):
            return f':{n}'
    raise SystemExit('没有可用的高编号私有显示')


@contextmanager
def private_display(out):
    display = free_display()
    with tempfile.NamedTemporaryFile(dir=out) as auth, (out / 'xvfb.log').open('xb') as log:
        subprocess.run(['xauth', '-f', auth.name], input=f'add {display} . {secrets.token_hex(16)}\n'.encode(), check=True)
        server = subprocess.Popen([XVFB, display, '-auth', auth.name, '-screen', '0', '1600x900x24',
                                   '-nolisten', 'tcp', '+extension', 'GLX'], stdout=log, stderr=log)
        try:
            with patch.dict(os.environ, DISPLAY=display, XAUTHORITY=auth.name, LIBGL_ALWAYS_SOFTWARE='1', ALSOFT_DRIVERS='null'):
                for _ in range(100):
                    if server.poll() is not None:
                        raise SystemExit(f'Xvfb 启动失败，见 {out}/xvfb.log')
                    if subprocess.run(['xset', 'q'], capture_output=True).returncode == 0:
                        (out / 'display.txt').write_text(display + '\n')
                        print(f'私有显示 {display}；XAUTHORITY={auth.name}', flush=True)
                        yield display
                        return
                    time.sleep(.1)
                raise SystemExit('Xvfb 未在 10 秒内就绪')
        finally:
            if server.poll() is None:
                server.terminate()
            server.wait()
