#!/usr/bin/env bash
set -euo pipefail

game=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
release_id='@RELEASE_ID@'
if [[ ! $release_id =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$ ]]; then
    printf '无效的 Standalone 发布 ID：%s\n' "$release_id" >&2
    exit 2
fi
runtime="$game/ssoptimizer-standalone/main-runtime"
release="$game/ssoptimizer-standalone/versions/$release_id"
mod="$game/mods/ssoptimizer"
overlay="$runtime/optimizer-mod-$release_id"
jar="$mod/jars/SSOptimizer.jar"
verb=launch
case ${1:-} in
    launch|doctor|prepare|config) verb=$1; shift ;;
esac
if (( $# )) && [[ $1 != -- || $verb != launch ]]; then
    printf '用法：%s [launch|doctor|prepare|config] [-- 游戏参数...]\n' "$0" >&2
    exit 2
fi

mounts=()
for jar_name in starfarer_obf.jar starfarer.api.jar fs.common_obf.jar fs.sound_obf.jar; do
    mounts+=(--ro-bind "$runtime/zh-core/$jar_name" "$game/$jar_name")
done
# Bubblewrap 会为缺失的挂载目标创建文件。
for target in "$mod/mod_info.json" "$jar"; do
    if [[ ! -f $target ]]; then
        printf '缺少 SSOptimizer 挂载目标：%s\n' "$target" >&2
        exit 1
    fi
done
exec bwrap --dev-bind / / --chdir "$game" \
    "${mounts[@]}" \
    --ro-bind "$overlay/mod_info.json" "$mod/mod_info.json" \
    --ro-bind "$release/payload.jar" "$jar" \
    --setenv SSO_USE_GAME_DATA 1 \
    -- "$release/launch.sh" "$verb" --game "$game" --instance "$runtime" "$@"
