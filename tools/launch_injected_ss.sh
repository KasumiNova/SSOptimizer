#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

is_java_25() {
    candidate="$1"
    if [ ! -x "$candidate" ]; then
        return 1
    fi
    "$candidate" -version 2>&1 | grep -Eq 'version "25([.\"]|$)|openjdk version "25([.\"]|$)'
}

resolve_java() {
    find_java_under() {
        search_root="$1"
        if [ ! -d "$search_root" ]; then
            return 0
        fi

        find "$search_root" -type f -name java 2>/dev/null | while IFS= read -r candidate; do
            if is_java_25 "$candidate"; then
                printf '%s\n' "$candidate"
                break
            fi
        done
    }

    candidate=$(find_java_under "$SCRIPT_DIR" | head -n 1 || true)
    if [ -n "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi

    if [ -n "${JAVA_HOME:-}" ] && is_java_25 "$JAVA_HOME/bin/java"; then
        printf '%s\n' "$JAVA_HOME/bin/java"
        return 0
    fi

    for system_root in /usr/lib/jvm /usr/lib64/jvm /usr/java /opt/java /opt/jdk /opt/jdks; do
        candidate=$(find_java_under "$system_root" | head -n 1 || true)
        if [ -n "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

JAVA_EXE=$(resolve_java) || {
    echo "ERROR: 未找到可用的 Java 25 运行时。已尝试扫描脚本目录、JAVA_HOME 和 /usr/lib/jvm 等系统目录。" >&2
    exit 1
}

exec "$JAVA_EXE" \
    -javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar \
    -Dfile.encoding=UTF-8 \
    -noverify \
    -XX:+UnlockDiagnosticVMOptions \
    -XX:+ShowCodeDetailsInExceptionMessages \
    -XX:+PrintCommandLineFlags \
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
    -Dcom.fs.starfarer.settings.paths.saves=./saves \
    -Dcom.fs.starfarer.settings.paths.screenshots=./screenshots \
    -Dcom.fs.starfarer.settings.paths.mods=./mods \
    -Dcom.fs.starfarer.settings.paths.logs=. \
    -Dssoptimizer.font.ttf.enable=true \
    -Dlog4j.configuration=file:./log4j.properties \
    -Dcom.fs.starfarer.settings.linux=true \
    -classpath janino.jar:commons-compiler.jar:commons-compiler-jdk.jar:starfarer.api.jar:starfarer_obf.jar:jogg-0.0.7.jar:jorbis-0.0.15.jar:json.jar:lwjgl.jar:jinput.jar:log4j-1.2.9.jar:lwjgl_util.jar:fs.sound_obf.jar:fs.common_obf.jar:xstream-1.4.10.jar:txw2-3.0.2.jar:jaxb-api-2.4.0-b180830.0359.jar:webp-imageio-0.1.6.jar \
    com.fs.starfarer.StarfarerLauncher \
    "$@"
