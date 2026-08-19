#!/bin/sh
# 生产运行时端到端验证：原版加载噪音日志阈值在「log4j-1.2-api 桥接 + log4j2」下的真实生效。
#
# 与 tools/verify_vanilla_log_noise_filter.sh（真实 log4j-1.2.17，轻量、不依赖游戏目录）
# 的区别：本脚本使用 SSOptimizer 部署产物（shade 的 log4j-1.2-api）与 NanoForge 运行时
# 提供的 log4j2，逐字节复现游戏运行时日志链路，验证：
#   - FQCN 名单 setLevel 在 log4j2 中精确生效（LoggerConfig 级别变 WARN）
#   - 日志显示截断名（loading.X）是无关 logger，不受影响（证明名单必须用 FQCN）
#   - SSOptimizer 自身 / ERROR 专用 logger（util.TextureData）不受影响
#
# 用法：./tools/verify_vanilla_log_noise_runtime.sh [游戏目录，默认 /mnt/store/Games/Starsector098-linux]

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(dirname "$SCRIPT_DIR")

GAME_DIR="${1:-/mnt/store/Games/Starsector098-linux}"
CORE_JAR="$PROJECT_ROOT/app/build/libs/SSOptimizer.jar"
NANOFORGE_DIR="$GAME_DIR/mods/nanoforge"

[ -f "$CORE_JAR" ] || { echo "ERROR: 未找到 $CORE_JAR（先执行 ./gradlew :app:jar）" >&2; exit 1; }
[ -f "$NANOFORGE_DIR/log4j-api-2.25.2.jar" ] || { echo "ERROR: 未找到 NanoForge log4j2（$NANOFORGE_DIR）" >&2; exit 1; }

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

unzip -o -q "$NANOFORGE_DIR/NanoForge-0.1.0-SNAPSHOT.jar" log4j2.xml -d "$WORK_DIR"

cat > "$WORK_DIR/RuntimeSim.java" << 'EOF'
import github.kasuminova.ssoptimizer.common.logging.VanillaLogNoiseConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class RuntimeSim {
    public static void main(String[] args) {
        boolean ok = true;
        // 模拟游戏代码 Logger.getLogger(Class)：运行时 logger 全名 = FQCN
        Logger gameWeapon = Logger.getLogger("com.fs.starfarer.loading.WeaponSpecLoader");
        Logger gameRules = Logger.getLogger("com.fs.starfarer.campaign.rules.Rules");
        Logger gameSound = Logger.getLogger("sound.Sound"); // fs.sound_obf 顶层包，短名即全名

        // 基线（NanoForge log4j2.xml root=INFO）
        System.out.println("基线 weapon isInfo=" + gameWeapon.isInfoEnabled());
        ok &= check("基线 INFO 可见", gameWeapon.isInfoEnabled(), true);

        VanillaLogNoiseConfigurator.configure();

        ok &= check("com.fs.starfarer.loading.WeaponSpecLoader INFO 压制", gameWeapon.isInfoEnabled(), false);
        ok &= check("  WARN 保留", gameWeapon.isEnabledFor(Level.WARN), true);
        ok &= check("com.fs.starfarer.campaign.rules.Rules INFO 压制", gameRules.isInfoEnabled(), false);
        ok &= check("sound.Sound INFO 压制", gameSound.isInfoEnabled(), false);

        // 对照：日志截断名是无关 logger，未被压制（证明名单必须用 FQCN）
        ok &= check("截断名 loading.WeaponSpecLoader 不受影响（对照）",
                Logger.getLogger("loading.WeaponSpecLoader").isInfoEnabled(), true);

        // SSOptimizer 自身与 ERROR 专用 logger 保留
        ok &= check("SSOptimizer 自身 INFO 保留",
                Logger.getLogger("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin").isInfoEnabled(), true);
        ok &= check("util.TextureData ERROR 保留",
                Logger.getLogger("util.TextureData").isEnabledFor(Level.ERROR), true);

        // 决定性证据：log4j2 LoggerConfig 级别
        org.apache.logging.log4j.core.LoggerContext ctx =
                (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.LoggerConfig cfg =
                ctx.getConfiguration().getLoggerConfig("com.fs.starfarer.loading.WeaponSpecLoader");
        System.out.println("log4j2 LoggerConfig level = " + cfg.getLevel());
        ok &= check("log4j2 LoggerConfig = WARN", cfg.getLevel() == org.apache.logging.log4j.Level.WARN, true);

        if (!ok) {
            System.err.println("FAILED: 生产运行时原版加载噪音日志过滤验证未通过");
            System.exit(1);
        }
        System.out.println("PASSED: 生产运行时（log4j-1.2-api + log4j2）过滤生效");
    }

    private static boolean check(String label, boolean actual, boolean expected) {
        boolean pass = actual == expected;
        System.out.println((pass ? "  [OK] " : "  [FAIL] ") + label + ": " + actual + " (期望 " + expected + ")");
        return pass;
    }
}
EOF

NF="$NANOFORGE_DIR"
cd "$WORK_DIR"
javac -cp "$CORE_JAR:$NF/log4j-api-2.25.2.jar:$NF/log4j-core-2.25.2.jar" RuntimeSim.java
java -Dlog4j.configurationFile="$WORK_DIR/log4j2.xml" \
    -cp "$WORK_DIR:$CORE_JAR:$NF/log4j-api-2.25.2.jar:$NF/log4j-core-2.25.2.jar" \
    RuntimeSim 2>&1 | grep -vE '^log4j2?:|^\['
