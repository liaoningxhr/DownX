#!/bin/sh
# 下载速度对比实验台（连接复用 / 批量并行 / 落盘复制）。
# 不参与 APK 打包。用法：sh tests/speed/run.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="F:/DownX-Studio"
export JAVA_HOME="$ROOT/toolchain/jdk"
mkdir -p "$HERE/out" "$HERE/work"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$HERE/out" "$HERE/src/Test.java"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$HERE/out" Test
