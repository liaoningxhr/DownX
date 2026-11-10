#!/bin/sh
# 「锁定画面框」算法验证台（纯桌面 JDK，不参与打包）
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JDK=F:/DownX-Studio/toolchain/jdk
APP=F:/DownX-Studio/app/app/src/main/java/com/downx/app
rm -rf "$HERE/out"
mkdir -p "$HERE/out"
"$JDK/bin/javac" -encoding UTF-8 -d "$HERE/out" "$APP/FitBox.java" "$HERE/src/Test.java"
"$JDK/bin/java" -Dfile.encoding=UTF-8 -cp "$HERE/out" com.downx.app.Test
