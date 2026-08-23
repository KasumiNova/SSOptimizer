#!/bin/bash
set -euo pipefail

# Smoke test: launch Starsector with SSOptimizer as a NanoForge coremod, check for fatal errors.
GAME_DIR="${1:-/mnt/store/Games/Starsector098-linux}"
TIMEOUT_SEC="${2:-15}"
# 默认进入真实游戏（autostart 跳过启动器）；launcher 模式会永久卡在启动器 UI
MODE="${3:-game}"
LAUNCH_SCRIPT="${SSOPTIMIZER_SMOKE_LAUNCH_SCRIPT:-launch_nanoforge_ss.sh}"
LOG_FILE="$GAME_DIR/starsector.log"
PROCESS_LOG_FILE="$GAME_DIR/ssoptimizer-smoke-process.log"
SETTINGS_FILE="$GAME_DIR/data/config/settings.json"
SETTINGS_BACKUP=""
GAME_PID=""
GAME_PGID=""
LAST_LOG_SIZE=0
FATAL_LOG_PATTERN="ClassFormatError|VerifyError|LinkageError|NoSuchMethodError|NoSuchFieldError|A fatal error has been detected by the Java Runtime Environment|SIGSEGV|core dumped|FATAL"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IME_SMOKE_HELPER="$SCRIPT_DIR/ime_keyboard_smoke.py"
IME_SMOKE_PID=""
IME_SMOKE_LOG_FILE="$GAME_DIR/ssoptimizer-ime-smoke-input.log"
IME_SMOKE_TRIGGER_PATTERN="${SSOPTIMIZER_SMOKE_INPUT_TRIGGER_PATTERN:-IME text field focused}"
AUTOMATION_SCENARIO="${SSOPTIMIZER_AUTOMATION_SCENARIO:-arc_flare_aod7_basic}"
AUTOMATION_OUTPUT_DIR="${SSOPTIMIZER_AUTOMATION_OUTPUT_DIR:-$GAME_DIR/ssoptimizer-automation-output}"
AUTOMATION_TELEMETRY_FILE="$AUTOMATION_OUTPUT_DIR/astd-ingame-automation-telemetry.json"
AUTOMATION_VERIFY_SCRIPT="${SSOPTIMIZER_AUTOMATION_VERIFY_SCRIPT:-/home/hikari_nova/IdeaProjects/Asteria_Directorate/tools/verify_ingame_vfx_automation.py}"
AUTOMATION_REQUIRE_SCREENSHOT_FILE="${SSOPTIMIZER_AUTOMATION_REQUIRE_SCREENSHOT_FILE:-false}"

echo "=== SSOptimizer Game Launch Smoke Test ==="
echo "Game dir: $GAME_DIR"
echo "Timeout:  ${TIMEOUT_SEC}s"
echo "Mode:     ${MODE}"

if [[ ! -f "$GAME_DIR/$LAUNCH_SCRIPT" ]]; then
    echo "FAIL: $LAUNCH_SCRIPT not found in $GAME_DIR"
    exit 1
fi

cleanup_game() {
    local pid="${GAME_PID:-}"
    local pgid="${GAME_PGID:-}"
    local ime_pid="${IME_SMOKE_PID:-}"

    if [[ -n "$pgid" ]]; then
        kill -TERM -- "-$pgid" 2>/dev/null || true
        sleep 1
        kill -KILL -- "-$pgid" 2>/dev/null || true
    fi

    if [[ -n "$pid" ]]; then
        pkill -TERM -P "$pid" 2>/dev/null || true
        sleep 1
        pkill -KILL -P "$pid" 2>/dev/null || true
        kill -TERM "$pid" 2>/dev/null || true
        sleep 1
        kill -KILL "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi

    if [[ -n "$ime_pid" ]]; then
        kill -TERM "$ime_pid" 2>/dev/null || true
        sleep 1
        kill -KILL "$ime_pid" 2>/dev/null || true
        wait "$ime_pid" 2>/dev/null || true
        IME_SMOKE_PID=""
    fi

    restore_settings_override
}

start_keyboard_smoke_macro() {
    if [[ -n "${IME_SMOKE_PID:-}" ]]; then
        return 0
    fi

    local sequence="${SSOPTIMIZER_SMOKE_INPUT_SEQUENCE:-}"
    if [[ -z "$sequence" ]]; then
        return 0
    fi

    if [[ ! -f "$IME_SMOKE_HELPER" ]]; then
        echo "WARN: keyboard smoke helper not found at $IME_SMOKE_HELPER"
        return 0
    fi

    local window_regex="${SSOPTIMIZER_SMOKE_INPUT_WINDOW_REGEX:-(?i)(starsector|starfarer)}"
    local wait_timeout="${SSOPTIMIZER_SMOKE_INPUT_WAIT_TIMEOUT:-45}"
    local focus_delay="${SSOPTIMIZER_SMOKE_INPUT_FOCUS_DELAY:-0.25}"
    local action_delay="${SSOPTIMIZER_SMOKE_INPUT_ACTION_DELAY:-0.05}"

    echo "Keyboard smoke macro: enabled"
    echo "  window regex:  $window_regex"
    echo "  sequence:      $sequence"
    echo "  wait timeout:   ${wait_timeout}s"
    if [[ -n "$IME_SMOKE_TRIGGER_PATTERN" ]]; then
        echo "  trigger:       $IME_SMOKE_TRIGGER_PATTERN"
    else
        echo "  trigger:       <immediate>"
    fi
    echo "  output log:     $IME_SMOKE_LOG_FILE"

    python3 "$IME_SMOKE_HELPER" \
        --window-regex "$window_regex" \
        --sequence "$sequence" \
        --wait-timeout "$wait_timeout" \
        --focus-delay "$focus_delay" \
        --action-delay "$action_delay" \
        > "$IME_SMOKE_LOG_FILE" 2>&1 &
    IME_SMOKE_PID=$!
}

restore_settings_override() {
    local backup="${SETTINGS_BACKUP:-}"
    if [[ -n "$backup" && -f "$backup" ]]; then
        mv "$backup" "$SETTINGS_FILE"
        SETTINGS_BACKUP=""
        echo "Restored settings.json"
    fi
}

apply_screen_scale_override() {
    local scale="$1"

    if [[ -z "$scale" ]]; then
        return 0
    fi

    if [[ ! "$scale" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        echo "FAIL: invalid SSOPTIMIZER_SCREEN_SCALE_OVERRIDE value: $scale"
        exit 1
    fi

    if [[ ! -f "$SETTINGS_FILE" ]]; then
        echo "FAIL: settings.json not found at $SETTINGS_FILE"
        exit 1
    fi

    SETTINGS_BACKUP=$(mktemp "$GAME_DIR/settings.json.smoke.XXXXXX")
    cp "$SETTINGS_FILE" "$SETTINGS_BACKUP"

    python3 - "$SETTINGS_FILE" "$scale" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
scale = sys.argv[2]
text = path.read_text(encoding="utf-8")
updated, count = re.subn(r'("screenScaleOverride"\s*:\s*)([^,]+)(\s*,)', rf'\g<1>{scale}\g<3>', text, count=1)
if count != 1:
    raise SystemExit("Failed to update screenScaleOverride in settings.json")
path.write_text(updated, encoding="utf-8")
PY

    echo "Applied screenScaleOverride=${scale} to settings.json"
}

log_size_bytes() {
    if [[ -f "$LOG_FILE" ]]; then
        stat -c%s "$LOG_FILE" 2>/dev/null || echo 0
    else
        echo 0
    fi
}

log_contains() {
    local pattern="$1"
    grep -q -E "$pattern" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null
}

print_log_matches() {
    local pattern="$1"
    grep -E "$pattern" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null || true
}

has_relevant_missing_class_failure() {
    local exception_name="$1"

    python3 - "$LOG_FILE" "$PROCESS_LOG_FILE" "$exception_name" <<'PY'
import pathlib
import re
import sys

log_paths = [pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])]
exception_name = sys.argv[3]
relevant_token = "github.kasuminova.ssoptimizer."
launcher_only_tokens = {
    "github.kasuminova.ssoptimizer.launcher.LauncherDirectStarter",
    "github.kasuminova.ssoptimizer.common.launcher.LauncherDirectStarter",
}
ignored_class_not_found_tokens = (
    "org.magiclib.achievements.MagicAchievementManager",
    "org.magiclib.Magic_modPlugin",
)

exception_pattern = re.compile(re.escape(exception_name))

def is_launcher_only_line(line):
    return any(token in line for token in launcher_only_tokens)

def is_ignored_block(block_lines):
    if exception_name != "ClassNotFoundException":
        return False
    joined = "\n".join(block_lines)
    return all(token in joined for token in ignored_class_not_found_tokens)

def is_relevant_block(block_lines):
    if is_ignored_block(block_lines):
        return False

    joined = "\n".join(block_lines)
    if relevant_token not in joined:
        return False

    for line in block_lines:
        if relevant_token in line and not is_launcher_only_line(line):
            return True

    first_line = block_lines[0] if block_lines else ""
    return relevant_token in first_line and not is_launcher_only_line(first_line)

for path in log_paths:
    if not path.exists():
        continue
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for index, line in enumerate(lines):
        if not exception_pattern.search(line):
            continue

        block = [line]
        for follow in lines[index + 1:index + 25]:
            if not follow.strip():
                break
            if re.match(r"\d{2}:\d{2}:\d{2} ", follow):
                break
            block.append(follow)

        if is_relevant_block(block):
            print(path)
            sys.exit(0)

sys.exit(1)
PY
}

print_relevant_missing_class_matches() {
    local exception_name="$1"

    python3 - "$LOG_FILE" "$PROCESS_LOG_FILE" "$exception_name" <<'PY'
import pathlib
import re
import sys

log_paths = [pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])]
exception_name = sys.argv[3]
relevant_token = "github.kasuminova.ssoptimizer."
launcher_only_tokens = {
    "github.kasuminova.ssoptimizer.launcher.LauncherDirectStarter",
    "github.kasuminova.ssoptimizer.common.launcher.LauncherDirectStarter",
}
ignored_class_not_found_tokens = (
    "org.magiclib.achievements.MagicAchievementManager",
    "org.magiclib.Magic_modPlugin",
)

exception_pattern = re.compile(re.escape(exception_name))

def is_launcher_only_line(line):
    return any(token in line for token in launcher_only_tokens)

def is_ignored_block(block_lines):
    if exception_name != "ClassNotFoundException":
        return False
    joined = "\n".join(block_lines)
    return all(token in joined for token in ignored_class_not_found_tokens)

def is_relevant_block(block_lines):
    if is_ignored_block(block_lines):
        return False

    joined = "\n".join(block_lines)
    if relevant_token not in joined:
        return False
    for line in block_lines:
        if relevant_token in line and not is_launcher_only_line(line):
            return True
    first_line = block_lines[0] if block_lines else ""
    return relevant_token in first_line and not is_launcher_only_line(first_line)

for path in log_paths:
    if not path.exists():
        continue
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for index, line in enumerate(lines):
        if not exception_pattern.search(line):
            continue

        block = [line]
        for follow in lines[index + 1:index + 25]:
            if not follow.strip():
                break
            if re.match(r"\d{2}:\d{2}:\d{2} ", follow):
                break
            block.append(follow)

        if is_relevant_block(block):
            print(f"== {path} ==")
            print("\n".join(block))
PY
}

log_age_seconds() {
    if [[ ! -f "$LOG_FILE" ]]; then
        echo "n/a"
        return 0
    fi

    local mtime now
    mtime=$(stat -c%Y "$LOG_FILE" 2>/dev/null || echo 0)
    now=$(date +%s)
    if [[ "$mtime" -le 0 ]]; then
        echo "n/a"
        return 0
    fi
    echo $((now - mtime))
}

has_live_game_process() {
    if [[ -n "$GAME_PID" ]] && kill -0 "$GAME_PID" 2>/dev/null; then
        return 0
    fi

    if [[ -n "$GAME_PGID" ]] && pgrep -g "$GAME_PGID" >/dev/null 2>&1; then
        return 0
    fi

    return 1
}

resolve_active_game_pid() {
    local pid=""

    if [[ -n "$GAME_PGID" ]]; then
        pid=$(pgrep -g "$GAME_PGID" -f 'com\.fs\.starfarer\.(StarfarerLauncher|combat\.CombatMain)' | head -n 1 || true)
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi

        pid=$(pgrep -g "$GAME_PGID" -f '/zulu25_linux/bin/java' | head -n 1 || true)
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi
    fi

    if [[ -n "$GAME_PID" ]] && kill -0 "$GAME_PID" 2>/dev/null; then
        echo "$GAME_PID"
        return 0
    fi

    return 1
}

print_progress() {
    local elapsed="$1"
    local active_pid="$2"
    local log_size log_age delta log_age_display

    log_size=$(log_size_bytes)
    log_age=$(log_age_seconds)
    delta=$((log_size - LAST_LOG_SIZE))
    if ((delta < 0)); then
        delta=$log_size
    fi
    LAST_LOG_SIZE="$log_size"

    if [[ "$log_age" == "n/a" ]]; then
        log_age_display="n/a"
    else
        log_age_display="${log_age}s"
    fi

    echo "[smoke] elapsed=${elapsed}s/${TIMEOUT_SEC}s active_pid=${active_pid:-none} pgid=${GAME_PGID:-none} log_bytes=${log_size} delta=${delta} log_age=${log_age_display}"
}

trap cleanup_game EXIT INT TERM

# Clear old log
: > "$LOG_FILE" 2>/dev/null || true
: > "$PROCESS_LOG_FILE" 2>/dev/null || true

ORIGINAL_JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
if [[ "$MODE" == "game" || "$MODE" == "automation" ]]; then
    if [[ "$MODE" == "automation" ]]; then
        START_RES="${SSOPTIMIZER_START_RES:-2560x1440}"
    else
        START_RES="${SSOPTIMIZER_START_RES:-1920x1080}"
    fi
    START_FS="${SSOPTIMIZER_START_FS:-false}"
    START_SOUND="${SSOPTIMIZER_START_SOUND:-true}"
    SCREEN_SCALE_OVERRIDE="${SSOPTIMIZER_SCREEN_SCALE_OVERRIDE:-}"
    EXTRA_OPTS="-Dssoptimizer.launcher.autostart=true -Dssoptimizer.launcher.autostart.res=${START_RES} -Dssoptimizer.launcher.autostart.fullscreen=${START_FS} -Dssoptimizer.launcher.autostart.sound=${START_SOUND} -DstartRes=${START_RES} -DstartFS=${START_FS} -DstartSound=${START_SOUND}"
    if [[ "$MODE" == "automation" ]]; then
        rm -rf "$AUTOMATION_OUTPUT_DIR"
        mkdir -p "$AUTOMATION_OUTPUT_DIR"
        EXTRA_OPTS="$EXTRA_OPTS -Dssoptimizer.automation.enabled=true -Dssoptimizer.automation.scenario=${AUTOMATION_SCENARIO} -Dssoptimizer.automation.outputDir=${AUTOMATION_OUTPUT_DIR} -Dssoptimizer.automation.requireScreenshotFile=${AUTOMATION_REQUIRE_SCREENSHOT_FILE}"
        echo "Automation profile: enabled"
        echo "  scenario:   ${AUTOMATION_SCENARIO}"
        echo "  output dir: ${AUTOMATION_OUTPUT_DIR}"
        echo "  telemetry:  ${AUTOMATION_TELEMETRY_FILE}"
    fi
    if [[ -n "$ORIGINAL_JAVA_TOOL_OPTIONS" ]]; then
        export JAVA_TOOL_OPTIONS="$ORIGINAL_JAVA_TOOL_OPTIONS $EXTRA_OPTS"
    else
        export JAVA_TOOL_OPTIONS="$EXTRA_OPTS"
    fi
    apply_screen_scale_override "$SCREEN_SCALE_OVERRIDE"
    echo "Auto-enter game: enabled (${START_RES}, fullscreen=${START_FS}, sound=${START_SOUND}, mode=direct-hook)"
    if [[ -n "$SCREEN_SCALE_OVERRIDE" ]]; then
        echo "Screen scale override: ${SCREEN_SCALE_OVERRIDE}"
    fi
fi

# Launch game in background
cd "$GAME_DIR"
if command -v setsid >/dev/null 2>&1; then
    setsid ./"$LAUNCH_SCRIPT" > "$PROCESS_LOG_FILE" 2>&1 &
    GAME_PID=$!
else
    ./"$LAUNCH_SCRIPT" > "$PROCESS_LOG_FILE" 2>&1 &
    GAME_PID=$!
fi

GAME_PGID=$(ps -o pgid= -p "$GAME_PID" 2>/dev/null | tr -d ' ' || true)
if [[ -z "$GAME_PGID" ]]; then
    GAME_PGID="$GAME_PID"
fi

echo "Game PID: $GAME_PID"
echo "Game PGID: $GAME_PGID"
echo "Waiting ${TIMEOUT_SEC}s for startup..."
for ((elapsed = 0; elapsed < TIMEOUT_SEC; elapsed++)); do
    sleep 1

    ACTIVE_PID=$(resolve_active_game_pid || true)
    print_progress "$((elapsed + 1))" "$ACTIVE_PID"

    if [[ -n "${SSOPTIMIZER_SMOKE_INPUT_SEQUENCE:-}" && -z "${IME_SMOKE_PID:-}" ]]; then
        if [[ -z "$IME_SMOKE_TRIGGER_PATTERN" ]] || grep -qF "$IME_SMOKE_TRIGGER_PATTERN" "$LOG_FILE" 2>/dev/null; then
            start_keyboard_smoke_macro
        fi
    fi

    if ! has_live_game_process; then
        echo "Game process tree exited before timeout"
        break
    fi

    if log_contains "$FATAL_LOG_PATTERN"; then
        echo "Fatal marker detected in log, stopping early"
        break
    fi

    if [[ "$MODE" == "automation" && -f "$AUTOMATION_TELEMETRY_FILE" ]] && grep -q '"state"[[:space:]]*:[[:space:]]*"Completed"' "$AUTOMATION_TELEMETRY_FILE" 2>/dev/null; then
        echo "Automation completion telemetry detected"
        break
    fi

    if has_relevant_missing_class_failure "NoClassDefFoundError" || has_relevant_missing_class_failure "ClassNotFoundException"; then
        echo "Relevant missing-class marker detected in log, stopping early"
        break
    fi
done

cleanup_game
trap - EXIT INT TERM

echo ""
echo "=== Log Analysis ==="

PASS=true

if log_contains "ClassFormatError"; then
    echo "FAIL: ClassFormatError found in log"
    print_log_matches "ClassFormatError"
    PASS=false
fi

if log_contains "VerifyError"; then
    echo "FAIL: VerifyError found in log"
    print_log_matches "VerifyError"
    PASS=false
fi

if log_contains "LinkageError"; then
    echo "FAIL: LinkageError found in log"
    print_log_matches "LinkageError"
    PASS=false
fi

if log_contains "NoSuchMethodError"; then
    echo "FAIL: NoSuchMethodError found in log"
    print_log_matches "NoSuchMethodError"
    PASS=false
fi

if log_contains "NoSuchFieldError"; then
    echo "FAIL: NoSuchFieldError found in log"
    print_log_matches "NoSuchFieldError"
    PASS=false
fi

if has_relevant_missing_class_failure "NoClassDefFoundError"; then
    echo "FAIL: NoClassDefFoundError found in log"
    print_relevant_missing_class_matches "NoClassDefFoundError"
    PASS=false
fi

if has_relevant_missing_class_failure "ClassNotFoundException"; then
    echo "FAIL: ClassNotFoundException found in log"
    print_relevant_missing_class_matches "ClassNotFoundException"
    PASS=false
fi

if log_contains "A fatal error has been detected by the Java Runtime Environment"; then
    echo "FAIL: JVM fatal crash marker found in log"
    print_log_matches "A fatal error has been detected by the Java Runtime Environment"
    PASS=false
fi

if log_contains "SIGSEGV"; then
    echo "FAIL: SIGSEGV found in log"
    print_log_matches "SIGSEGV"
    PASS=false
fi

if log_contains "FATAL"; then
    echo "FAIL: FATAL error found in log"
    print_log_matches "FATAL"
    PASS=false
fi

if grep -q "\[SSOptimizer\] CoreMod loaded" "$LOG_FILE"; then
    echo "OK: CoreMod loaded successfully"
else
    echo "WARN: CoreMod load message not found"
fi

if [[ "$MODE" == "game" || "$MODE" == "automation" ]]; then
    if grep -q "\[SSOptimizer\] Loaded on Java" "$LOG_FILE"; then
        echo "OK: Game load path reached BaseModPlugin.onApplicationLoad"
    else
        echo "WARN: Game load path marker not found"
        PASS=false
    fi
fi

if [[ "$MODE" == "automation" ]]; then
    echo ""
    echo "=== Automation Analysis ==="
    if grep -q "\[SSO-Automation\] enabled" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null; then
        echo "OK: SSOptimizer automation profile enabled"
        grep "\[SSO-Automation\] enabled" "$LOG_FILE" "$PROCESS_LOG_FILE" 2>/dev/null || true
    else
        echo "WARN: SSOptimizer automation profile marker not found"
        PASS=false
    fi

    if [[ -f "$AUTOMATION_TELEMETRY_FILE" ]]; then
        echo "OK: Automation telemetry found: $AUTOMATION_TELEMETRY_FILE"
        if [[ -f "$AUTOMATION_VERIFY_SCRIPT" ]]; then
            VERIFY_ARGS=("$AUTOMATION_TELEMETRY_FILE")
            if [[ "$AUTOMATION_REQUIRE_SCREENSHOT_FILE" == "true" ]]; then
                VERIFY_ARGS+=("--require-screenshot-file")
            fi
            if python3 "$AUTOMATION_VERIFY_SCRIPT" "${VERIFY_ARGS[@]}"; then
                echo "OK: Automation telemetry verifier passed"
            else
                echo "FAIL: Automation telemetry verifier failed"
                PASS=false
            fi
        else
            echo "FAIL: Automation verify script not found: $AUTOMATION_VERIFY_SCRIPT"
            PASS=false
        fi
    else
        if grep -q "\[ASTD-Automation\] Completed: arc_flare/aod7/astd_aod7_shot/VFX observed" "$LOG_FILE" 2>/dev/null; then
            echo "OK: ASTD automation completion marker found in log"
            if [[ -f "$AUTOMATION_VERIFY_SCRIPT" ]]; then
                VERIFY_ARGS=("$AUTOMATION_TELEMETRY_FILE" "--log" "$LOG_FILE")
                if [[ "$AUTOMATION_REQUIRE_SCREENSHOT_FILE" == "true" ]]; then
                    VERIFY_ARGS+=("--require-screenshot-file")
                fi
                if python3 "$AUTOMATION_VERIFY_SCRIPT" "${VERIFY_ARGS[@]}"; then
                    echo "OK: Automation log verifier passed"
                else
                    echo "FAIL: Automation log verifier failed"
                    PASS=false
                fi
            else
                echo "FAIL: Automation verify script not found: $AUTOMATION_VERIFY_SCRIPT"
                PASS=false
            fi
        else
            echo "FAIL: Automation telemetry missing: $AUTOMATION_TELEMETRY_FILE"
            PASS=false
        fi
    fi
fi

if grep -q "\[SSOptimizer\] Sanitized" "$LOG_FILE"; then
    echo "OK: Sanitizer activated"
    grep "\[SSOptimizer\] Sanitized" "$LOG_FILE"
else
    echo "INFO: No classes needed sanitization (or not loaded yet)"
fi

if [[ -f "$IME_SMOKE_LOG_FILE" ]]; then
    echo ""
    echo "=== Keyboard Smoke Output ==="
    cat "$IME_SMOKE_LOG_FILE"
fi

echo ""
if $PASS; then
    echo "=== Smoke Test PASSED ==="
    exit 0
else
    echo "=== Smoke Test FAILED ==="
    exit 1
fi
