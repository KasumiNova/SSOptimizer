#!/bin/sh
# 独立 JVM 端到端验证：加载期噪音日志「消息级聚合压制」在真实 log4j-1.2.17 下的生效效果。
#
# 背景：Gradle 测试 worker 的 classpath 中 org.apache.log4j 由 gradle-api 内嵌的 no-op
# 桥接提供（setLevel 无效、Filter/LoggingEvent 残缺），:modules:internal:sso-app:test 只能
# 验证行为契约；本脚本用真实 log4j-1.2.17 + 已编译的 VanillaLogNoiseConfigurator 验证：
#   - configure() 后名单 logger 保持 INFO 可见（消息级聚合接管，非整 logger 压制）
#   - 1.x 聚合过滤器（LoadingNoiseLog4j1Filter）挂在 FileAppender 上：刷屏行被逐条压制、
#     同类计数 flush 成「Loaded N <分类>」汇总行、非目标 INFO/WARN/ERROR 保留
#   - SSOptimizer 自身 logger、ERROR 专用 logger（util.TextureData）、第三方 mod logger 不受影响
#
# 注意：生产链路（log4j-1.2-api 桥接 + log4j2）的挂载与生效见
# tools/verify_vanilla_log_noise_runtime.sh；本脚本的 1.x Filter 是同一聚合逻辑的 1.x 适配层。
#
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

        // 真实 log4j-1.2.17：root 默认 DEBUG，挂 FileAppender 捕获输出
        final String logFile = args[0];
        final Logger root = Logger.getRootLogger();
        final FileAppender appender = new FileAppender(new PatternLayout("%c{2} %-5p %m%n"), logFile, false);
        root.addAppender(appender);

        // 基线：无配置时噪音 logger（FQCN）的 INFO 可见
        final Logger loading = Logger.getLogger("com.fs.starfarer.loading.LoadingUtils");
        ok &= check("基线 INFO 可见", loading.isInfoEnabled(), true);

        VanillaLogNoiseConfigurator.configure();

        // 新语义：名单 logger 保持 INFO 可见，逐条压制移交消息级聚合过滤器
        ok &= check("噪音 logger（FQCN）INFO 保留（消息级聚合接管）", loading.isInfoEnabled(), true);
        ok &= check("噪音 logger（FQCN）WARN 保留", loading.isEnabledFor(Level.WARN), true);
        ok &= check("噪音 logger（FQCN）ERROR 保留", loading.isEnabledFor(Level.ERROR), true);

        // 对照：日志显示截断名是另一个无关 logger（名单必须用 FQCN）
        final Logger truncated = Logger.getLogger("loading.LoadingUtils");
        ok &= check("截断名 logger 与本名不同（对照）",
                truncated != loading, true);

        // 端到端：1.x 聚合过滤器挂到 FileAppender，模拟加载期日志流
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

        // 非噪音：SSOptimizer 自身、第三方 mod、WARN/ERROR 均保留
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
# log4j-api/core 供 configure() 装配 log4j2 层过滤器（挂载不影响 1.x 输出链路）
javac -cp "$LOG4J_JAR:$LOG4J_API_JAR:$LOG4J_CORE_JAR:$MAIN_CLASSPATH" VerifyVanillaLogNoiseFilter.java
java -cp "$LOG4J_JAR:$LOG4J_API_JAR:$LOG4J_CORE_JAR:$MAIN_CLASSPATH:$WORK_DIR" \
    VerifyVanillaLogNoiseFilter "$WORK_DIR/out.log"

FAIL=0
fail_check() {
    echo "  [FAIL] $1"
    FAIL=1
}

# ---- 刷屏行必须逐条压制 ----
for pattern in 'Loading JSON from' 'Loading rule:' 'Cleaned buffer for texture' 'already loaded'; do
    if grep -q "$pattern" "$WORK_DIR/out.log"; then
        fail_check "刷屏行未被压制: $pattern"
    else
        echo "  [OK] 刷屏行已压制: $pattern"
    fi
done

# ---- 聚合汇总行替代 ----
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

# ---- 非目标日志保留 ----
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
