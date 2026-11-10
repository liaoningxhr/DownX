#!/bin/sh
# ChunkedFetch 桌面验证台：起一个支持 Range 的本地服务，五项跑一遍。
# 不参与 APK 打包，只用来验证下载内核（真机之外的第一道关）。
#
# 用法：sh tests/chunked/run.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="F:/DownX-Studio"
export JAVA_HOME="$ROOT/toolchain/jdk"

# 引擎源码只留一份：每次跑之前从 app 同步过来，避免测试桩里的副本过期
cp "$ROOT/app/app/src/main/java/com/downx/app/ChunkedFetch.java" "$HERE/src/com/downx/app/"

mkdir -p "$HERE/out" "$HERE/work"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$HERE/out" \
    "$HERE"/src/com/downx/app/*.java "$HERE/src/Test.java"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$HERE/out" Test
