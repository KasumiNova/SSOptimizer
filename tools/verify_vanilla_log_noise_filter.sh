#!/bin/sh
# 独立 JVM 端到端验证：原版加载噪音日志阈值（setLevel）在真实 log4j-1.x 下的生效效果。
#
# 背景：Gradle 测试 worker 的 classpath 中 org.apache.log4j 由 gradle-api 内嵌的
# no-op 桥接提供（setLevel 无效），:modules:internal:sso-app:test 只能验证行为契约；本脚本用真实
# log4j-1.2.17 + 已编译的 VanillaLogNoiseConfigurator 验证：
#   - configure() 后 loading.LoadingUtils 的 INFO 被压制、WARN/ERROR 保留
#   - SSOptimizer 自身 logger 与 ERROR 专用 logger（util.TextureData）不受影响
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

CLASSES_DIR="$PROJECT_ROOT/modules/internal/sso-app/build/classes/java/main"
if [ ! -d "$CLASSES_DIR" ]; then
    echo "ERROR: 未找到编译产物 $CLASSES_DIR（先执行 ./gradlew :modules:internal:sso-app:compileJava）" >&2
    exit 1
fi

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

cat > "$WORK_DIR/VerifyVanillaLogNoiseFilter.java" << 'EOF'
import github.kasuminova.ssoptimizer.common.logging.VanillaLogNoiseConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class VerifyVanillaLogNoiseFilter {
    public static void main(String[] args) {
        boolean ok = true;

        // 基线：无配置时 log4j 1.x root 默认 DEBUG，噪音 logger（FQCN）的 INFO 可见
        Logger loading = Logger.getLogger("com.fs.starfarer.loading.LoadingUtils");
        ok &= check("基线 INFO 可见", loading.isInfoEnabled(), true);

        VanillaLogNoiseConfigurator.configure();

        ok &= check("噪音 logger（FQCN）INFO 被压制", loading.isInfoEnabled(), false);
        ok &= check("噪音 logger（FQCN）WARN 保留", loading.isEnabledFor(Level.WARN), true);
        ok &= check("噪音 logger（FQCN）ERROR 保留", loading.isEnabledFor(Level.ERROR), true);

        // 对照：日志显示截断名是另一个无关 logger——setLevel 对它无影响（名单必须用 FQCN）
        Logger truncated = Logger.getLogger("loading.LoadingUtils");
        ok &= check("截断名 logger 与本名不同（对照）",
                truncated != Logger.getLogger("com.fs.starfarer.loading.LoadingUtils"), true);

        ok &= check("SSOptimizer 自身 logger INFO 保留",
                Logger.getLogger("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin").isInfoEnabled(), true);
        ok &= check("ERROR 专用 logger util.TextureData ERROR 保留",
                Logger.getLogger("util.TextureData").isEnabledFor(Level.ERROR), true);
        ok &= check("非噪音原版 logger com.fs.starfarer.StarfarerLauncher INFO 保留",
                Logger.getLogger("com.fs.starfarer.StarfarerLauncher").isInfoEnabled(), true);
        ok &= check("第三方 mod logger util.ShipColors INFO 保留",
                Logger.getLogger("util.ShipColors").isInfoEnabled(), true);

        if (!ok) {
            System.err.println("FAILED: 原版加载噪音日志过滤验证未通过");
            System.exit(1);
        }
        System.out.println("PASSED: 原版加载噪音日志过滤端到端验证通过");
    }

    private static boolean check(String label, boolean actual, boolean expected) {
        boolean pass = actual == expected;
        System.out.println((pass ? "  [OK] " : "  [FAIL] ") + label + ": " + actual + " (期望 " + expected + ")");
        return pass;
    }
}
EOF

cd "$WORK_DIR"
javac -cp "$LOG4J_JAR:$CLASSES_DIR" VerifyVanillaLogNoiseFilter.java
java -cp "$LOG4J_JAR:$CLASSES_DIR:$WORK_DIR" VerifyVanillaLogNoiseFilter
