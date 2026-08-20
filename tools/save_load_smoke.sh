#!/bin/bash
set -euo pipefail

# 读档冒烟测试：save_load_cycle 自动化场景，游戏读档后自退出，校验遥测。
# 用法: tools/save_load_smoke.sh [GAME_DIR] [SAVE_DIR_NAME] [TIMEOUT_SEC]
GAME_DIR="${1:-/mnt/store/Games/Starsector098-linux-rt}"
SAVE_DIR_NAME="${2:-save_CascadeNova_1246447115782963985}"
TIMEOUT_SEC="${3:-300}"
LAUNCH_SCRIPT="${SSOPTIMIZER_SMOKE_LAUNCH_SCRIPT:-launch_nanoforge_ss.sh}"
OUTPUT_DIR="$GAME_DIR/saveload-smoke-output"
TELEMETRY_FILE="$OUTPUT_DIR/saveload-telemetry.json"
PROCESS_LOG_FILE="$GAME_DIR/saveload-smoke-process.log"

echo "=== SSOptimizer Save/Load Smoke Test ==="
echo "Game dir: $GAME_DIR"
echo "Save:     $SAVE_DIR_NAME"
echo "Timeout:  ${TIMEOUT_SEC}s"

if [[ ! -f "$GAME_DIR/$LAUNCH_SCRIPT" ]]; then
    echo "FAIL: $LAUNCH_SCRIPT not found in $GAME_DIR"
    exit 1
fi

if [[ ! -d "$GAME_DIR/saves/$SAVE_DIR_NAME" ]]; then
    echo "FAIL: save dir not found: $GAME_DIR/saves/$SAVE_DIR_NAME"
    exit 1
fi

# 写侧会执行真实保存，先在 scratch 副本上操作，避免污染原档
SCRATCH_SAVE_NAME="${SAVE_DIR_NAME}_ssbench"
echo "Scratch:  $SCRATCH_SAVE_NAME (copy of $SAVE_DIR_NAME)"
rm -rf "$GAME_DIR/saves/$SCRATCH_SAVE_NAME"
cp -a "$GAME_DIR/saves/$SAVE_DIR_NAME" "$GAME_DIR/saves/$SCRATCH_SAVE_NAME"

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

cd "$GAME_DIR"
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dssoptimizer.launcher.autostart=true -Dssoptimizer.launcher.autostart.res=1920x1080 -Dssoptimizer.launcher.autostart.fullscreen=false -Dssoptimizer.launcher.autostart.sound=true -DstartRes=1920x1080 -DstartFS=false -DstartSound=true -Dssoptimizer.automation.enabled=true -Dssoptimizer.automation.scenario=save_load_cycle -Dssoptimizer.automation.saveload.saveDir=${SCRATCH_SAVE_NAME} -Dssoptimizer.automation.outputDir=${OUTPUT_DIR}" \
    timeout "$TIMEOUT_SEC" ./"$LAUNCH_SCRIPT" > "$PROCESS_LOG_FILE" 2>&1 || true

echo ""
echo "=== Result ==="

PASS=true

if [[ ! -f "$TELEMETRY_FILE" ]]; then
    echo "FAIL: telemetry missing: $TELEMETRY_FILE"
    tail -n 30 "$PROCESS_LOG_FILE" || true
    exit 1
fi

cat "$TELEMETRY_FILE"

if grep -q '"success":[[:space:]]*true' "$TELEMETRY_FILE"; then
    LOAD_MS=$(grep -o '"loadMs":[[:space:]]*[0-9.]*' "$TELEMETRY_FILE" | grep -o '[0-9.]*')
    UNMARSHAL_MS=$(grep -o '"unmarshalMs":[[:space:]]*[0-9-]*' "$TELEMETRY_FILE" | grep -o '[0-9-]*')
    SAVE_MS=$(grep -o '"saveMs":[[:space:]]*[0-9.-]*' "$TELEMETRY_FILE" | grep -o '[0-9.-]*')
    echo "OK: load succeeded, loadMs=${LOAD_MS} unmarshalMs=${UNMARSHAL_MS} saveMs=${SAVE_MS}"
    if grep -q '"saveError":[[:space:]]*"' "$TELEMETRY_FILE"; then
        echo "FAIL: save failed"
        PASS=false
    fi
else
    echo "FAIL: load failed"
    PASS=false
fi

if grep -q -E "ClassFormatError|VerifyError|NoSuchMethodError|NoSuchFieldError|SIGSEGV" "$PROCESS_LOG_FILE" 2>/dev/null; then
    echo "FAIL: fatal marker in process log"
    PASS=false
fi

echo ""
if $PASS; then
    echo "=== Save/Load Smoke Test PASSED ==="
    exit 0
else
    echo "=== Save/Load Smoke Test FAILED ==="
    exit 1
fi
