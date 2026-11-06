#!/bin/sh
# 使用真实 log4j-1.2.17 验证消息级聚合过滤；Gradle 测试 worker 内嵌的 no-op 桥接无法覆盖该链路。
# 用法：./tools/verify_vanilla_log_noise_filter.sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(dirname "$SCRIPT_DIR")

LOG4J_JAR=$(find /mnt/data/hikari_nova/.gradle /home/hikari_nova/.gradle \
    -path '*/modules-2/files-2.1/log4j/log4j/1.2.17/*/log4j-1.2.17.jar' 2>/dev/null | head -n 1)
if [ -z "$LOG4J_JAR" ]; then
    echo "ERROR: 未找到 log4j-1.2.17.jar（先执行 ./gradlew :modules:internal:sso-app:test 下载依赖）" >&2
    exit 1
fi

LOG4J_API_JAR=$(find /mnt/data/hikari_nova/.gradle /home/hikari_nova/.gradle \
    -path '*/modules-2/files-2.1/org.apache.logging.log4j/log4j-api/2.25.2/*/log4j-api-2.25.2.jar' 2>/dev/null | head -n 1)
LOG4J_CORE_JAR=$(find /mnt/data/hikari_nova/.gradle /home/hikari_nova/.gradle \
    -path '*/modules-2/files-2.1/org.apache.logging.log4j/log4j-core/2.25.2/*/log4j-core-2.25.2.jar' 2>/dev/null | head -n 1)
if [ -z "$LOG4J_API_JAR" ] || [ -z "$LOG4J_CORE_JAR" ]; then
    echo "ERROR: 未找到 log4j-api/core-2.25.2.jar（configure() 装配 log4j2 层过滤器需要）" >&2
    exit 1
fi

CLASSES_DIR="$PROJECT_ROOT/modules/internal/sso-app/build/classes/java/main"
CORE_CLASSES_DIR="$PROJECT_ROOT/modules/internal/sso-core/build/classes/java/main"
if [ ! -d "$CLASSES_DIR" ] || [ ! -d "$CORE_CLASSES_DIR" ]; then
    echo "ERROR: 未找到编译产物 $CLASSES_DIR / $CORE_CLASSES_DIR（先执行 ./gradlew :modules:internal:sso-app:compileJava）" >&2
    exit 1
fi
MAIN_CLASSPATH="$CLASSES_DIR:$CORE_CLASSES_DIR"

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

cat > "$WORK_DIR/VerifyVanillaLogNoiseFilter.java" << 'EOF'
import github.kasuminova.ssoptimizer.common.logging.LoadingNoiseLog4j1Filter;
import github.kasuminova.ssoptimizer.common.logging.VanillaLogNoiseConfigurator;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

public class VerifyVanillaLogNoiseFilter {
    public static void main(String[] args) throws Exception {
        boolean ok = true;

        final String logFile = args[0];
        final Logger root = Logger.getRootLogger();
        final FileAppender appender = new FileAppender(new PatternLayout("%c{2} %-5p %m%n"), logFile, false);
        root.addAppender(appender);

        final Logger loading = Logger.getLogger("com.fs.starfarer.loading.LoadingUtils");
        ok &= check("基线 INFO 可见", loading.isInfoEnabled(), true);

        VanillaLogNoiseConfigurator.configure();

        ok &= check("噪音 logger（FQCN）INFO 保留（消息级聚合接管）", loading.isInfoEnabled(), true);
        ok &= check("噪音 logger（FQCN）WARN 保留", loading.isEnabledFor(Level.WARN), true);
        ok &= check("噪音 logger（FQCN）ERROR 保留", loading.isEnabledFor(Level.ERROR), true);

        final Logger truncated = Logger.getLogger("loading.LoadingUtils");
        ok &= check("截断名 logger 与本名不同（对照）",
                truncated != loading, true);

        appender.addFilter(new LoadingNoiseLog4j1Filter());

        loading.info("Loading JSON from [data/weapons/abyss.wpn]");
        loading.info("Loading JSON from [data/weapons/asterism.wpn]");
        loading.info("Loading JSON from [data/weapons/naginata.wpn]");
        Logger rules = Logger.getLogger("com.fs.starfarer.campaign.rules.Rules");
        rules.info("Loading rule: defaultOpenDialog");
        rules.info("Loading rule: defaultLeave");
        Logger texture = Logger.getLogger("com.fs.graphics.TextureLoader");
        texture.info("Cleaned buffer for texture graphics/ui/launcher_bg.jpg (using reflection)");
        Logger scripts = Logger.getLogger("com.fs.starfarer.loading.scripts.ScriptStore");
        scripts.info("Class [data.scripts.ExampleModPlugin] already loaded (perhaps from jar file, or due to a reference from another class), skipping compilation.");

        Logger own = Logger.getLogger("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin");
        own.info("[SSOptimizer] CoreMod loaded");
        Logger mod = Logger.getLogger("hullmods.No101_CoincidenceRangefinder");
        mod.info("  Range bonus: 12.3%");
        rules.warn("rule not found");
        Logger textureData = Logger.getLogger("util.TextureData");
        textureData.error("texture missing");

        if (!ok) {
            System.err.println("FAILED: 原版加载噪音日志过滤验证未通过（配置/基线检查）");
            System.exit(1);
        }
        System.out.println("PASSED: 配置与基线检查通过，聚合过滤输出断言见脚本");
    }

    private static boolean check(String label, boolean actual, boolean expected) {
        boolean pass = actual == expected;
        System.out.println((pass ? "  [OK] " : "  [FAIL] ") + label + ": " + actual + " (期望 " + expected + ")");
        return pass;
    }
}
EOF

cd "$WORK_DIR"
javac -cp "$LOG4J_JAR:$LOG4J_API_JAR:$LOG4J_CORE_JAR:$MAIN_CLASSPATH" VerifyVanillaLogNoiseFilter.java
java -cp "$LOG4J_JAR:$LOG4J_API_JAR:$LOG4J_CORE_JAR:$MAIN_CLASSPATH:$WORK_DIR" \
    VerifyVanillaLogNoiseFilter "$WORK_DIR/out.log"

FAIL=0
fail_check() {
    echo "  [FAIL] $1"
    FAIL=1
}

for pattern in 'Loading JSON from' 'Loading rule:' 'Cleaned buffer for texture' 'already loaded'; do
    if grep -q "$pattern" "$WORK_DIR/out.log"; then
        fail_check "刷屏行未被压制: $pattern"
    else
        echo "  [OK] 刷屏行已压制: $pattern"
    fi
done

check_line() {
    if grep -qF "$1" "$WORK_DIR/out.log"; then
        echo "  [OK] $1"
    else
        fail_check "缺少汇总行: $1"
    fi
}
check_line "[SSOptimizer] Loaded 3 JSON files"
check_line "[SSOptimizer] Loaded 2 rules"
check_line "[SSOptimizer] Loaded 1 texture buffers"
check_line "[SSOptimizer] Loaded 1 classes"

for line in '[SSOptimizer] CoreMod loaded' 'Range bonus: 12.3%' 'rule not found' 'texture missing'; do
    if grep -qF "$line" "$WORK_DIR/out.log"; then
        echo "  [OK] 非目标日志保留: $line"
    else
        fail_check "非目标日志被误伤: $line"
    fi
done

if [ "$FAIL" -ne 0 ]; then
    echo "FAILED: 原版加载噪音日志过滤端到端验证未通过（见上方 [FAIL]）"
    echo "--- 实际输出 ---"
    cat "$WORK_DIR/out.log"
    exit 1
fi
echo "PASSED: 原版加载噪音日志聚合过滤端到端验证通过（真实 log4j-1.2.17）"
