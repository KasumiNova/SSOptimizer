#!/bin/bash
set -euo pipefail

# SSOptimizer 自动化基准测试运行器：
# 启动游戏（跳过启动器）→ 标题界面自动进入 gl_benchmark（GraphicsLib 基准测试）→
# 抑制部署对话框 → 采样（周期截图 + async-profiler）→ 写 bench-summary.json → 退出。
#
# 用法: benchmark_run.sh [GAME_DIR] [DURATION_SEC]
# 环境变量:
#   SSOPTIMIZER_BENCH_MISSION     mission id（默认 gl_benchmark）
#   SSOPTIMIZER_BENCH_WARMUP_SEC  预热秒数（默认 20，之后启动 profiler）
#   SSOPTIMIZER_BENCH_SCREENSHOT_INTERVAL_SEC 截图间隔秒数（默认 15，0 禁用）
#   SSOPTIMIZER_BENCH_PROFILER    true/false（默认 true）
#   SSOPTIMIZER_BENCH_EVENT       采样事件（默认 wall）
#   SSOPTIMIZER_BENCH_OUTPUT_DIR  输出目录（默认 $GAME_DIR/ssoptimizer-bench-output）
#   SSOPTIMIZER_START_RES         分辨率（默认 1920x1080）
#   SSOPTIMIZER_SMOKE_LAUNCH_SCRIPT 启动脚本（默认 launch_nanoforge_ss.sh）

GAME_DIR="${1:-/mnt/store/Games/Starsector098-linux}"
DURATION_SEC="${2:-90}"
LAUNCH_SCRIPT="${SSOPTIMIZER_SMOKE_LAUNCH_SCRIPT:-launch_nanoforge_ss.sh}"
LOG_FILE="$GAME_DIR/starsector.log"
PROCESS_LOG_FILE="$GAME_DIR/ssoptimizer-bench-process.log"
SETTINGS_FILE="$GAME_DIR/data/config/settings.json"
SETTINGS_BACKUP=""

MISSION="${SSOPTIMIZER_BENCH_MISSION:-gl_benchmark}"
WARMUP_SEC="${SSOPTIMIZER_BENCH_WARMUP_SEC:-20}"
SCREENSHOT_INTERVAL="${SSOPTIMIZER_BENCH_SCREENSHOT_INTERVAL_SEC:-15}"
PROFILER="${SSOPTIMIZER_BENCH_PROFILER:-true}"
EVENT="${SSOPTIMIZER_BENCH_EVENT:-wall}"
OUTPUT_DIR="${SSOPTIMIZER_BENCH_OUTPUT_DIR:-$GAME_DIR/ssoptimizer-bench-output}"
SUMMARY_FILE="$OUTPUT_DIR/bench-summary.json"
START_RES="${SSOPTIMIZER_START_RES:-1920x1080}"

# 启动 + 标题界面 + 预热 + 采样 + 余量
TIMEOUT_SEC=$((DURATION_SEC + WARMUP_SEC + 240))

echo "=== SSOptimizer Benchmark Run ==="
echo "Game dir:  $GAME_DIR"
echo "Mission:   $MISSION"
echo "Duration:  ${DURATION_SEC}s (warmup ${WARMUP_SEC}s)"
echo "Profiler:  $PROFILER (event=$EVENT)"
echo "Output:    $OUTPUT_DIR"
echo "Timeout:   ${TIMEOUT_SEC}s"

if [[ ! -f "$GAME_DIR/$LAUNCH_SCRIPT" ]]; then
    echo "FAIL: $LAUNCH_SCRIPT not found in $GAME_DIR"
    exit 1
fi

restore_settings() {
    if [[ -n "$SETTINGS_BACKUP" && -f "$SETTINGS_BACKUP" ]]; then
        mv "$SETTINGS_BACKUP" "$SETTINGS_FILE"
        SETTINGS_BACKUP=""
        echo "Restored settings.json"
    fi
}

cleanup_game() {
    if [[ -n "${GAME_PGID:-}" ]]; then
        kill -TERM -- "-$GAME_PGID" 2>/dev/null || true
        sleep 1
        kill -KILL -- "-$GAME_PGID" 2>/dev/null || true
    fi
    restore_settings
}

trap cleanup_game EXIT INT TERM

# 失焦停摆规避：原版在窗口失焦且满足暂停条件时主循环直接 continue，
# 自动化必须关闭 idleWhileWindowNotVisible（备份后改，退出恢复）。
if [[ -f "$SETTINGS_FILE" ]]; then
    SETTINGS_BACKUP=$(mktemp "$GAME_DIR/settings.json.bench.XXXXXX")
    cp "$SETTINGS_FILE" "$SETTINGS_BACKUP"
    python3 - "$SETTINGS_FILE" <<'PY'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
updated, count = re.subn(r'("idleWhileWindowNotVisible"\s*:\s*)(true|false)', r'\g<1>false', text, count=1)
if count != 1:
    raise SystemExit("Failed to update idleWhileWindowNotVisible in settings.json")
path.write_text(updated, encoding="utf-8")
PY
    echo "settings.json: idleWhileWindowNotVisible=false (backed up)"
else
    echo "WARN: settings.json not found, focus-idle override skipped"
fi

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
: > "$LOG_FILE" 2>/dev/null || true
: > "$PROCESS_LOG_FILE"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dssoptimizer.launcher.autostart=true -Dssoptimizer.launcher.autostart.res=${START_RES} -Dssoptimizer.launcher.autostart.fullscreen=false -Dssoptimizer.launcher.autostart.sound=true -DstartRes=${START_RES} -DstartFS=false -DstartSound=true -Dssoptimizer.bench.enabled=true -Dssoptimizer.bench.mission=${MISSION} -Dssoptimizer.bench.durationSec=${DURATION_SEC} -Dssoptimizer.bench.warmupSec=${WARMUP_SEC} -Dssoptimizer.bench.screenshotIntervalSec=${SCREENSHOT_INTERVAL} -Dssoptimizer.bench.profiler.enabled=${PROFILER} -Dssoptimizer.bench.profiler.event=${EVENT} -Dssoptimizer.bench.outputDir=${OUTPUT_DIR} -Dssoptimizer.bench.exit=true"

cd "$GAME_DIR"
setsid ./"$LAUNCH_SCRIPT" > "$PROCESS_LOG_FILE" 2>&1 &
GAME_PID=$!
GAME_PGID=$(ps -o pgid= -p "$GAME_PID" 2>/dev/null | tr -d ' ' || true)
GAME_PGID="${GAME_PGID:-$GAME_PID}"
echo "Game PID: $GAME_PID (PGID $GAME_PGID)"

PASS=true
for ((elapsed = 0; elapsed < TIMEOUT_SEC; elapsed++)); do
    sleep 1

    if [[ -f "$SUMMARY_FILE" ]]; then
        echo "[bench] summary detected after ${elapsed}s"
        sleep 2
        break
    fi

    if ! kill -0 "$GAME_PID" 2>/dev/null && ! pgrep -g "$GAME_PGID" >/dev/null 2>&1; then
        echo "[bench] game process exited after ${elapsed}s"
        if [[ ! -f "$SUMMARY_FILE" ]]; then
            echo "FAIL: game exited without writing summary"
            PASS=false
        fi
        break
    fi

    if grep -q -E "A fatal error has been detected by the Java Runtime Environment|SIGSEGV" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null; then
        echo "FAIL: JVM crash detected"
        PASS=false
        break
    fi
done

cleanup_game
trap - EXIT INT TERM

echo ""
echo "=== Benchmark Result ==="

if [[ -f "$SUMMARY_FILE" ]]; then
    cat "$SUMMARY_FILE"
else
    echo "FAIL: bench-summary.json not found"
    PASS=false
fi

# GraphicsLib 内建统计（每秒 FPS / 每 10s 汇总 / 最终结果块）直接进 starsector.log
if grep -q "基准测试结果" "$LOG_FILE" 2>/dev/null; then
    echo ""
    echo "--- GraphicsLib 基准测试结果 ---"
    grep -A 10 "基准测试结果" "$LOG_FILE" | tail -12
fi

# collapsed 采样转主线程火焰图 SVG（浏览器打开，可搜索/缩放）
FLAMEGRAPH_PL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/flamegraph.pl"
COLLAPSED_FILE="$OUTPUT_DIR/bench-profile.collapsed.txt"
if [[ -f "$COLLAPSED_FILE" && -f "$FLAMEGRAPH_PL" ]]; then
    MAIN_COLLAPSED="$OUTPUT_DIR/.main-thread.collapsed"
    grep -F "com/fs/state/AppDriver.begin" "$COLLAPSED_FILE" > "$MAIN_COLLAPSED" || true
    if [[ -s "$MAIN_COLLAPSED" ]]; then
        perl "$FLAMEGRAPH_PL" --title "SSOptimizer ${MISSION} ${DURATION_SEC}s main thread" \
            --width 1800 "$MAIN_COLLAPSED" > "$OUTPUT_DIR/main-thread-flamegraph.svg"
        echo "Flamegraph: $OUTPUT_DIR/main-thread-flamegraph.svg"
    fi
    rm -f "$MAIN_COLLAPSED"
fi

echo ""
echo "Artifacts:"
ls -R "$OUTPUT_DIR" 2>/dev/null || true

if $PASS; then
    echo "=== Benchmark Run PASSED ==="
    exit 0
else
    echo "=== Benchmark Run FAILED ==="
    exit 1
fi
