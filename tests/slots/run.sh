#!/bin/sh
# Slots（下载并发名额）桌面压测。不参与打包。
# 用法：sh tests/slots/run.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="F:/DownX-Studio"
export JAVA_HOME="$ROOT/toolchain/jdk"

mkdir -p "$HERE/src/com/downx/app" "$HERE/out"
# 只留一份源码：每次跑之前从 app 同步，避免测试桩里的副本过期
cp "$ROOT/app/app/src/main/java/com/downx/app/Slots.java" "$HERE/src/com/downx/app/"

"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$HERE/out" \
    "$HERE/src/com/downx/app/Slots.java" "$HERE/src/Test.java"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$HERE/out" com.downx.app.Test
