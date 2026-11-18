#!/usr/bin/env bash
set -euo pipefail
home=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
verb=launch
game=${SSOPTIMIZER_GAME_DIR:-}
instance=${SSOPTIMIZER_INSTANCE:-}
import_args=()
usage() { printf '%s\n' '用法：launch.sh [launch|doctor|prepare|config] --game <RC8目录> [--instance <实例目录>] [-- 游戏参数...]' '导入：launch.sh import-save --game <RC8目录> [--instance <实例目录>] --source <原存档槽目录> --confirm-source <windows-en-rc8|windows-zh-rc8> [--slot <新槽名>]'; }
case ${1:-} in launch|doctor|prepare|config|import-save) verb=$1; shift ;; esac
while (($#)); do
    case $1 in
        --game|--instance) (($# >= 2)) && [[ -n $2 ]] || { usage >&2; exit 2; }; if [[ $1 == --game ]]; then game=$2; else instance=$2; fi; shift 2 ;;
        --source|--confirm-source|--slot) [[ $verb == import-save ]] && (($# >= 2)) && [[ -n $2 ]] || { usage >&2; exit 2; }; import_args+=("$1" "$2"); shift 2 ;;
        --) [[ $verb == launch ]] || { usage >&2; exit 2; }; shift; break ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
[[ -d $game ]] || { printf '%s\n' '请选择已有的 RC8 游戏目录：https://fractalsoftworks.com/preorder/' >&2; exit 2; }
game=$(realpath -- "$game")
instance=$(realpath -m -- "${instance:-$game/ssoptimizer-standalone/instance}")
if [[ -n ${JAVA_HOME:-} ]]; then java=$JAVA_HOME/bin/java; else java=$(command -v java) || { echo '需要本机 x64 JDK 25，请设置 JAVA_HOME。' >&2; exit 1; }; fi
java=$(realpath -- "$java")
[[ -x $java && -x ${java%/*}/javac ]] || { echo 'JAVA_HOME 必须指向完整 JDK 25。' >&2; exit 1; }
modules=$("$java" --list-modules)
for module in java.base java.instrument jdk.compiler jdk.attach; do
    [[ $modules =~ (^|$'\n')${module//./\\.}@25([.+-]|$'\n'|$) ]] || { printf '需要 JDK 25 模块 %s：%s\n' "$module" "$java" >&2; exit 1; }
done
args=("$verb" --game "$game" --home "$home" --instance "$instance")
if [[ $verb == import-save ]]; then printf '%s\n' '只复制旧档至实例中的新槽。请确认该存档写入时使用的 RC8 版本；原存档保留。'; args+=("${import_args[@]}"); fi
if (($#)); then args+=(-- "$@"); fi
exec "$java" -cp "$home/agent.jar:$home/lib/*" ssoptimizer.standalone.launch.Main "${args[@]}"
