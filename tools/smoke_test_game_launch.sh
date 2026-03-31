#!/bin/bash
set -euo pipefail

# Smoke test: launch Starsector with SSOptimizer agent, check for fatal errors.
GAME_DIR="${1:-/mnt/windows_data/Games/Starsector098-linux}"
TIMEOUT_SEC="${2:-15}"
MODE="${3:-launcher}"
LOG_FILE="$GAME_DIR/starsector.log"
PROCESS_LOG_FILE="$GAME_DIR/ssoptimizer-smoke-process.log"
SETTINGS_FILE="$GAME_DIR/data/config/settings.json"
SETTINGS_BACKUP=""
GAME_PID=""
GAME_PGID=""
LAST_LOG_SIZE=0
FATAL_LOG_PATTERN="ClassFormatError|VerifyError|LinkageError|NoSuchMethodError|NoSuchFieldError|A fatal error has been detected by the Java Runtime Environment|SIGSEGV|core dumped|FATAL"

echo "=== SSOptimizer Game Launch Smoke Test ==="
echo "Game dir: $GAME_DIR"
echo "Timeout:  ${TIMEOUT_SEC}s"
echo "Mode:     ${MODE}"

if [[ ! -f "$GAME_DIR/launch_injected_ss.sh" ]]; then
    echo "FAIL: launch_injected_ss.sh not found in $GAME_DIR"
    exit 1
fi

cleanup_game() {
    local pid="${GAME_PID:-}"
    local pgid="${GAME_PGID:-}"

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

    restore_settings_override
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
launcher_only_token = "github.kasuminova.ssoptimizer.launcher.LauncherDirectStarter"

exception_pattern = re.compile(re.escape(exception_name))

def is_relevant_block(block_lines):
    joined = "\n".join(block_lines)
    if relevant_token not in joined:
        return False

    for line in block_lines:
        if relevant_token in line and launcher_only_token not in line:
            return True

    first_line = block_lines[0] if block_lines else ""
    return relevant_token in first_line and launcher_only_token not in first_line

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
launcher_only_token = "github.kasuminova.ssoptimizer.launcher.LauncherDirectStarter"

exception_pattern = re.compile(re.escape(exception_name))

def is_relevant_block(block_lines):
    joined = "\n".join(block_lines)
    if relevant_token not in joined:
        return False
    for line in block_lines:
        if relevant_token in line and launcher_only_token not in line:
            return True
    first_line = block_lines[0] if block_lines else ""
    return relevant_token in first_line and launcher_only_token not in first_line

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
if [[ "$MODE" == "game" ]]; then
    START_RES="${SSOPTIMIZER_START_RES:-1920x1080}"
    START_FS="${SSOPTIMIZER_START_FS:-false}"
    START_SOUND="${SSOPTIMIZER_START_SOUND:-true}"
    SCREEN_SCALE_OVERRIDE="${SSOPTIMIZER_SCREEN_SCALE_OVERRIDE:-}"
    EXTRA_OPTS="-Dssoptimizer.launcher.autostart=true -Dssoptimizer.launcher.autostart.res=${START_RES} -Dssoptimizer.launcher.autostart.fullscreen=${START_FS} -Dssoptimizer.launcher.autostart.sound=${START_SOUND} -DstartRes=${START_RES} -DstartFS=${START_FS} -DstartSound=${START_SOUND}"
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
    setsid ./launch_injected_ss.sh > "$PROCESS_LOG_FILE" 2>&1 &
    GAME_PID=$!
else
    ./launch_injected_ss.sh > "$PROCESS_LOG_FILE" 2>&1 &
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

    if ! has_live_game_process; then
        echo "Game process tree exited before timeout"
        break
    fi

    if log_contains "$FATAL_LOG_PATTERN"; then
        echo "Fatal marker detected in log, stopping early"
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

if grep -q "\[SSOptimizer\] Agent loaded" "$LOG_FILE"; then
    echo "OK: Agent loaded successfully"
else
    echo "WARN: Agent load message not found"
fi

if [[ "$MODE" == "game" ]]; then
    if grep -q "\[SSOptimizer\] Loaded on Java" "$LOG_FILE"; then
        echo "OK: Game load path reached BaseModPlugin.onApplicationLoad"
    else
        echo "WARN: Game load path marker not found"
        PASS=false
    fi
fi

if grep -q "\[SSOptimizer\] Sanitized" "$LOG_FILE"; then
    echo "OK: Sanitizer activated"
    grep "\[SSOptimizer\] Sanitized" "$LOG_FILE"
else
    echo "INFO: No classes needed sanitization (or not loaded yet)"
fi

echo ""
if $PASS; then
    echo "=== Smoke Test PASSED ==="
    exit 0
else
    echo "=== Smoke Test FAILED ==="
    exit 1
fi
