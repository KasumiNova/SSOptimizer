#!/usr/bin/env bash
set -euo pipefail
release=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
verb=launch
game=${SSOPTIMIZER_GAME_DIR:-}
usage() { printf '%s\n' '用法：launch.sh [launch|prepare|config] --game <游戏目录> [-- 游戏参数...]'; }
case ${1:-} in launch|prepare|config) verb=$1; shift ;; esac
while (($#)); do
    case $1 in
        --game) (($# >= 2)) || { usage >&2; exit 2; }; game=$2; shift 2 ;;
        --) [[ $verb == launch ]] || { usage >&2; exit 2; }; shift; break ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
[[ -d $game ]] || { usage >&2; exit 2; }
game=$(realpath -- "$game")
[[ -d $game/mods ]] || { printf '缺少游戏模组目录：%s/mods\n' "$game" >&2; exit 1; }
mod="$game/mods/ssoptimizer"
if [[ ! -e $mod && ! -L $mod ]]; then
    ln -sT -- "$release/mod" "$mod"
elif [[ ! $mod -ef $release/mod ]]; then
    printf '请先将现有 SSOptimizer 模组移出，再启用此发行版：%s\n' "$mod" >&2
    exit 1
fi
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
args=("$verb" --game "$game" --home "$release")
if (($#)); then args+=(-- "$@"); fi
exec "$java" -cp "$release/agent.jar:$release/lib/*" ssoptimizer.standalone.launch.Main "${args[@]}"
