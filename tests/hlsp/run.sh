#!/bin/sh
# HLS 分片并行（ParallelSegs）桌面验证台。不参与打包。
# 用法：sh tests/hlsp/run.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="F:/DownX-Studio"
export JAVA_HOME="$ROOT/toolchain/jdk"

mkdir -p "$HERE/src/com/downx/app" "$HERE/out" "$HERE/work"
# 源码只留一份：每次跑之前从 app 同步
cp "$ROOT/app/app/src/main/java/com/downx/app/ParallelSegs.java" "$HERE/src/com/downx/app/"

"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$HERE/out" \
    "$HERE/src/com/downx/app/ParallelSegs.java" "$HERE/src/Test.java"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$HERE/out" com.downx.app.Test
