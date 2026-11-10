#!/bin/sh
# Namer 主机验证台：纯 Java 编译 + 断言，不需要 Android SDK、不联网。
# 用法：sh tests/naming/run.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out="$here/out"
rm -rf "$out"
mkdir -p "$out"
JAVAC="$root/toolchain/jdk/bin/javac.exe"
JAVA="$root/toolchain/jdk/bin/java.exe"
[ -x "$JAVAC" ] || { JAVAC=javac; JAVA=java; }
"$JAVAC" -encoding UTF-8 -nowarn -d "$out" \
    "$root/app/app/src/main/java/com/downx/app/Namer.java" \
    "$here/NamingTest.java"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.NamingTest
