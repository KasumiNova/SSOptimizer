#!/usr/bin/env bash
set -euo pipefail
src=$(cd -- "$(dirname -- "$0")" && pwd -P)
core=$(realpath -e -- "${1:?Windows RC8 starsector-core directory required}")
out=$(realpath -m -- "${2:?isolated output directory required}")
[[ -f "$core/starfarer_obf.jar" && -f "$core/starfarer.api.jar" ]]
mkdir -p -- "$out" "$out/sso_perf_probe/jars"
classes=$(mktemp -d -- "$out/compile.XXXXXXXX")
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$core/*" -d "$classes" "$src"/src/perfmatrix/*.java
jar --create --date=2020-01-01T00:00:00Z --no-manifest --file "$out/sso_perf_probe/jars/probe.jar" -C "$classes" .
cp -r -- "$src/mod/." "$out/sso_perf_probe/"
sha256sum -- "$out/sso_perf_probe/jars/probe.jar"
