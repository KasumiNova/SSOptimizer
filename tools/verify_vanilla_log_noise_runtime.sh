#!/bin/sh
# 使用部署产物与 NanoForge 的 log4j2 验证消息级聚合过滤。
# 用法：./tools/verify_vanilla_log_noise_runtime.sh [游戏目录，默认 /mnt/store/Games/Starsector098-linux]

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(dirname "$SCRIPT_DIR")

GAME_DIR="${1:-/mnt/store/Games/Starsector098-linux}"
CORE_JAR="$PROJECT_ROOT/modules/internal/sso-app/build/libs/SSOptimizer.jar"
NANOFORGE_DIR="$GAME_DIR/mods/nanoforge"

[ -f "$CORE_JAR" ] || { echo "ERROR: 未找到 $CORE_JAR（先执行 ./gradlew :modules:internal:sso-app:jar）" >&2; exit 1; }
[ -f "$NANOFORGE_DIR/log4j-api-2.25.2.jar" ] || { echo "ERROR: 未找到 NanoForge log4j2（$NANOFORGE_DIR）" >&2; exit 1; }

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

unzip -o -q "$NANOFORGE_DIR/NanoForge-0.1.0-SNAPSHOT.jar" log4j2.xml -d "$WORK_DIR"

cat > "$WORK_DIR/RuntimeSim.java" << 'EOF'
import github.kasuminova.ssoptimizer.common.logging.LoadingNoiseLog4j2Filter;
import github.kasuminova.ssoptimizer.common.logging.VanillaLogNoiseConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class RuntimeSim {
    public static void main(String[] args) {
        boolean ok = true;
        Logger gameLoading = Logger.getLogger("com.fs.starfarer.loading.LoadingUtils");
        Logger gameWeapon = Logger.getLogger("com.fs.starfarer.loading.WeaponSpecLoader");
        Logger gameRules = Logger.getLogger("com.fs.starfarer.campaign.rules.Rules");
        Logger gameSound = Logger.getLogger("sound.Sound");

        ok &= check("基线 INFO 可见", gameWeapon.isInfoEnabled(), true);

        VanillaLogNoiseConfigurator.configure();

        ok &= check("com.fs.starfarer.loading.WeaponSpecLoader INFO 保留", gameWeapon.isInfoEnabled(), true);
        ok &= check("  WARN 保留", gameWeapon.isEnabledFor(Level.WARN), true);
        ok &= check("com.fs.starfarer.campaign.rules.Rules INFO 保留", gameRules.isInfoEnabled(), true);
        ok &= check("sound.Sound INFO 保留", gameSound.isInfoEnabled(), true);

        ok &= check("截断名 loading.WeaponSpecLoader 不受影响（对照）",
                Logger.getLogger("loading.WeaponSpecLoader").isInfoEnabled(), true);

        org.apache.logging.log4j.core.LoggerContext ctx =
                (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.LoggerConfig rootCfg = ctx.getConfiguration().getRootLogger();
        ok &= check("log4j2 root LoggerConfig 已挂聚合过滤器",
                rootCfg.getFilter() instanceof LoadingNoiseLog4j2Filter, true);

        gameLoading.info("Loading JSON from [data/weapons/abyss.wpn]");
        gameLoading.info("Loading JSON from [data/weapons/asterism.wpn]");
        gameLoading.info("Loading JSON from [data/weapons/naginata.wpn]");
        gameRules.info("Loading rule: defaultOpenDialog");
        gameRules.info("Loading rule: defaultLeave");
        Logger.getLogger("com.fs.graphics.TextureLoader").info("Cleaned buffer for texture graphics/ui/launcher_bg.jpg (using reflection)");
        Logger.getLogger("com.fs.starfarer.loading.scripts.ScriptStore")
                .info("Class [data.scripts.ExampleModPlugin] already loaded (perhaps from jar file, or due to a reference from another class), skipping compilation.");

        Logger.getLogger("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin")
                .info("[SSOptimizer] CoreMod loaded");
        gameRules.warn("rule not found");
        Logger.getLogger("util.TextureData").error("texture missing");

        if (!ok) {
            System.err.println("FAILED: 生产运行时原版加载噪音日志过滤验证未通过");
            System.exit(1);
        }
        System.out.println("PASSED: 配置与挂载检查通过，聚合过滤输出断言见脚本");
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

LOG_FILE="$WORK_DIR/starsector.log"
[ -f "$LOG_FILE" ] || { echo "ERROR: 未生成 $LOG_FILE" >&2; exit 1; }

FAIL=0
fail_check() {
    echo "  [FAIL] $1"
    FAIL=1
}

for pattern in 'Loading JSON from' 'Loading rule:' 'Cleaned buffer for texture' 'already loaded'; do
    if grep -q "$pattern" "$LOG_FILE"; then
        fail_check "刷屏行未被压制: $pattern"
    else
        echo "  [OK] 刷屏行已压制: $pattern"
    fi
done

check_line() {
    if grep -qF "$1" "$LOG_FILE"; then
        echo "  [OK] $1"
    else
        fail_check "缺少汇总行: $1"
    fi
}
check_line "[SSOptimizer] Loaded 3 JSON files"
check_line "[SSOptimizer] Loaded 2 rules"
check_line "[SSOptimizer] Loaded 1 texture buffers"
check_line "[SSOptimizer] Loaded 1 classes"

for line in '[SSOptimizer] CoreMod loaded' 'rule not found' 'texture missing'; do
    if grep -qF "$line" "$LOG_FILE"; then
        echo "  [OK] 非目标日志保留: $line"
    else
        fail_check "非目标日志被误伤: $line"
    fi
done

if [ "$FAIL" -ne 0 ]; then
    echo "FAILED: 生产运行时（log4j-1.2-api + log4j2）聚合过滤验证未通过（见上方 [FAIL]）"
    echo "--- starsector.log 实际内容 ---"
    cat "$LOG_FILE"
    exit 1
fi
echo "PASSED: 生产运行时（log4j-1.2-api + log4j2）消息级聚合过滤生效"
