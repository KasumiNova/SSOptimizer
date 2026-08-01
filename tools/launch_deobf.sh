#!/bin/sh
# 全量 deobf 模式启动：named jar 已在游戏目录 classpath（starsector.sh 引用的 4 个游戏 jar
# 需先替换为 build/named-game-jars/<platform> 下的 named 版本），agent 以
# -Dssoptimizer.deobf.full=true 加载全量映射表，对 mod 字节码做 obf→named 全类覆写。
#
# 环境变量：
#   GAME_DIR        游戏目录（默认 /mnt/store/Games/Starsector098-linux）
#   DEOBF_MODS_DIR  mods 目录（默认 <repo>/.dev/deobf-empty-mods，纯原版）
#   DEOBF_SAVES_DIR 存档目录（默认 <repo>/.dev/deobf-saves，实测存档时拷入副本）
#
# 参数：
#   --build  启动前重建 named jar + agent jar，并把 agent jar 部署到游戏 mods 目录
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
GAME_DIR="${GAME_DIR:-/mnt/store/Games/Starsector098-linux}"
NAMED_DIR="$PROJECT_DIR/build/named-game-jars/linux"

if [ "${1:-}" = "--build" ]; then
    shift
    (cd "$PROJECT_DIR" && ./gradlew :mapping:remapGameClasspathToNamed :app:jar)
    cp "$PROJECT_DIR/app/build/libs/SSOptimizer.jar" "$GAME_DIR/mods/ssoptimizer/jars/SSOptimizer.jar"
fi

# 空 mods 目录：纯原版启动，避免引用混淆名的 mod 干扰 named jar 验证
DEOBF_MODS_DIR="${DEOBF_MODS_DIR:-$PROJECT_DIR/.dev/deobf-empty-mods}"
DEOBF_SAVES_DIR="${DEOBF_SAVES_DIR:-$PROJECT_DIR/.dev/deobf-saves}"
mkdir -p "$DEOBF_MODS_DIR" "$DEOBF_SAVES_DIR"

# 空 mods 目录缺字体资源，TTF 开启会刷屏报错，随目录内容自动开关。
# ssoptimizer 自身会在 mods 目录下写 cache 子目录，不算真实 mod，判定时排除。
if [ -z "$(ls -A "$DEOBF_MODS_DIR" 2>/dev/null | grep -vx ssoptimizer)" ]; then
    TTF_ENABLE=false
else
    TTF_ENABLE=true
fi

JAVA_EXE="$GAME_DIR/zulu25_linux/bin/java"

cd "$GAME_DIR"

export mesa_glthread=false

exec "$JAVA_EXE" \
    -javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar \
    -Dfile.encoding=UTF-8 \
    -noverify \
    -XX:+UnlockDiagnosticVMOptions \
    -XX:+ShowCodeDetailsInExceptionMessages \
    -XX:+TieredCompilation \
    -XX:+DisableExplicitGC \
    -XX:+AlwaysPreTouch \
    -XX:+ParallelRefProcEnabled \
    -XX:+UseZGC \
    -XX:ReservedCodeCacheSize=256m \
    -XX:CompilerDirectivesFile=./compiler_directives.txt \
    -Djdk.xml.maxElementDepth=10000 \
    -XX:-BytecodeVerificationLocal \
    -XX:-BytecodeVerificationRemote \
    -Djava.util.Arrays.useLegacyMergeSort=true \
    --enable-preview \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --add-opens=java.base/java.nio.Buffer.UNSAFE=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
    --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
    --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.lang.ref=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt.Rectangle=ALL-UNNAMED \
    --add-opens=java.desktop/java.awt=ALL-UNNAMED \
    --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
    -Xms4096m \
    -Xmx4096m \
    -Xss4m \
    -Dcom.fs.starfarer.settings.paths.saves="$DEOBF_SAVES_DIR" \
    -Dcom.fs.starfarer.settings.paths.screenshots=./screenshots \
    -Dcom.fs.starfarer.settings.paths.mods="$DEOBF_MODS_DIR" \
    -Dcom.fs.starfarer.settings.paths.logs=. \
    -Djava.library.path=./native/linux \
    -Dssoptimizer.deobf.full=true \
    -Dssoptimizer.font.ttf.enable="$TTF_ENABLE" \
    -Dlog4j.configuration=file:./log4j.properties \
    -Dcom.fs.starfarer.settings.linux=true \
    -Dssoptimizer.launcher.autostart=true \
    -Dssoptimizer.launcher.autostart.res=1920x1080 \
    -Dssoptimizer.launcher.autostart.fullscreen=false \
    -Dssoptimizer.launcher.autostart.sound=true \
    -classpath "janino.jar:commons-compiler.jar:commons-compiler-jdk.jar:$NAMED_DIR/starfarer.api.jar:$NAMED_DIR/starfarer_obf.jar:jogg-0.0.7.jar:jorbis-0.0.15.jar:json.jar:lwjgl.jar:jinput.jar:log4j-1.2.9.jar:lwjgl_util.jar:$NAMED_DIR/fs.sound_obf.jar:$NAMED_DIR/fs.common_obf.jar:xstream-1.4.21_miko.jar:txw2-3.0.2.jar:jaxb-api-2.4.0-b180830.0359.jar:webp-imageio-0.1.6.jar" \
    com.fs.starfarer.StarfarerLauncher \
    "$@"
