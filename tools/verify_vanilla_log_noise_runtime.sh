#!/bin/sh
# 生产运行时端到端验证：加载期噪音日志「消息级聚合压制」在「log4j-1.2-api 桥接 + log4j2」下
# 的真实生效（逐字节复现游戏运行时日志链路）。
#
# 与 tools/verify_vanilla_log_noise_filter.sh（真实 log4j-1.2.17，验证 1.x 适配层）的区别：
# 本脚本使用 SSOptimizer 部署产物（shade 的 log4j-1.2-api）与 NanoForge 运行时提供的
# log4j2，验证生产链路：
#   - configure() 后名单 logger 保持 INFO 可见（消息级聚合接管，非整 logger WARN）
#   - 聚合过滤器挂在 log4j2 root LoggerConfig：桥接转发的刷屏 INFO 被 DENY 并计数，
#     非目标日志/WARN 到达时 flush 成「Loaded N <分类>」汇总行写入 starsector.log
#   - SSOptimizer 自身 / ERROR 专用 logger（util.TextureData）不受影响
#
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
        // 模拟游戏代码 Logger.getLogger(Class)：运行时 logger 全名 = FQCN
        Logger gameLoading = Logger.getLogger("com.fs.starfarer.loading.LoadingUtils");
        Logger gameWeapon = Logger.getLogger("com.fs.starfarer.loading.WeaponSpecLoader");
        Logger gameRules = Logger.getLogger("com.fs.starfarer.campaign.rules.Rules");
        Logger gameSound = Logger.getLogger("sound.Sound"); // fs.sound_obf 顶层包，短名即全名

        // 基线（NanoForge log4j2.xml root=INFO）
        ok &= check("基线 INFO 可见", gameWeapon.isInfoEnabled(), true);

        VanillaLogNoiseConfigurator.configure();

        // 新语义：名单 logger 保持 INFO 可见，逐条压制移交消息级聚合过滤器
        ok &= check("com.fs.starfarer.loading.WeaponSpecLoader INFO 保留", gameWeapon.isInfoEnabled(), true);
        ok &= check("  WARN 保留", gameWeapon.isEnabledFor(Level.WARN), true);
        ok &= check("com.fs.starfarer.campaign.rules.Rules INFO 保留", gameRules.isInfoEnabled(), true);
        ok &= check("sound.Sound INFO 保留", gameSound.isInfoEnabled(), true);

        // 对照：日志截断名是无关 logger，未被改动（证明名单必须用 FQCN）
        ok &= check("截断名 loading.WeaponSpecLoader 不受影响（对照）",
                Logger.getLogger("loading.WeaponSpecLoader").isInfoEnabled(), true);

        // 决定性证据：聚合过滤器已挂到 log4j2 root LoggerConfig
        org.apache.logging.log4j.core.LoggerContext ctx =
                (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.config.LoggerConfig rootCfg = ctx.getConfiguration().getRootLogger();
        ok &= check("log4j2 root LoggerConfig 已挂聚合过滤器",
                rootCfg.getFilter() instanceof LoadingNoiseLog4j2Filter, true);

        // ---- 端到端：模拟加载期日志流（经桥接转发到 log4j2，写入 starsector.log） ----
        gameLoading.info("Loading JSON from [data/weapons/abyss.wpn]");
        gameLoading.info("Loading JSON from [data/weapons/asterism.wpn]");
        gameLoading.info("Loading JSON from [data/weapons/naginata.wpn]");
        gameRules.info("Loading rule: defaultOpenDialog");
        gameRules.info("Loading rule: defaultLeave");
        Logger.getLogger("com.fs.graphics.TextureLoader").info("Cleaned buffer for texture graphics/ui/launcher_bg.jpg (using reflection)");
        Logger.getLogger("com.fs.starfarer.loading.scripts.ScriptStore")
                .info("Class [data.scripts.ExampleModPlugin] already loaded (perhaps from jar file, or due to a reference from another class), skipping compilation.");

        // 非噪音：SSOptimizer 自身 INFO 与 WARN 保留
        Logger.getLogger("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin")
                .info("[SSOptimizer] CoreMod loaded");
        gameRules.warn("rule not found");
        // ERROR 专用 logger 保留
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

# ---- 刷屏行必须逐条压制 ----
for pattern in 'Loading JSON from' 'Loading rule:' 'Cleaned buffer for texture' 'already loaded'; do
    if grep -q "$pattern" "$LOG_FILE"; then
        fail_check "刷屏行未被压制: $pattern"
    else
        echo "  [OK] 刷屏行已压制: $pattern"
    fi
done

# ---- 聚合汇总行替代（经 log4j2 root Filter 链路输出） ----
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

# ---- 非目标日志保留 ----
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
