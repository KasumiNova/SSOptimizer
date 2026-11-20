#!/usr/bin/env python3
"""使用本机 .20 构件在私有工作区迁移一个 named 槽。"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--from-18e67d2', required=True, type=Path, metavar='RELEASE', help='实际写档的 .20 发布目录')
    args.add_argument('--plan', required=True, type=Path, help='写档实例当时的 launch.properties')
    args.add_argument('--core', required=True, type=Path, help='匹配的原版核心目录')
    args.add_argument('source', type=Path, help='旧槽目录')
    args.add_argument('target', type=Path, help='未使用过的新槽路径')
    options = args.parse_args()
    tool = Path(__file__).resolve().parent
    package = tool.parents[1] / 'modules/internal/sso-standalone/src/main/java/ssoptimizer/standalone/instance'
    release = options.from_18e67d2.resolve(strict=True)
    core = options.core.resolve(strict=True)
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else Path('')
    cp = os.pathsep.join(map(str, (release / 'agent.jar', release / 'payload.jar', core / 'jaxb-api-2.4.0-b180830.0359.jar')))
    files = ['SaveFields', 'SaveSymbols', 'SaveXml', 'SaveDescriptor', 'InstanceFiles', 'HistoricalSave']
    with tempfile.TemporaryDirectory(prefix='sso-named-migration-') as classes:
        subprocess.run([jdk / 'javac', '--release', '25', '-encoding', 'UTF-8', '-cp', cp, '-d', classes,
                        *(package / (f + '.java') for f in files), tool / 'MigrateNamedSlot.java'], check=True)
        subprocess.run([jdk / 'java', '--enable-native-access=ALL-UNNAMED', '-cp', classes + os.pathsep + cp,
                        'ssoptimizer.standalone.instance.MigrateNamedSlot', '--from-18e67d2', options.plan,
                        release / 'mappings/game-full.tiny.gz', core, options.source, options.target], check=True)


if __name__ == '__main__':
    main()
