#!/bin/sh
# HlsMaster 桌面验证台：改完解析逻辑跑一遍，不用手机、不用连外网
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/f/DownX-Studio/toolchain/jdk}
SRC=../../app/app/src/main/java/com/downx/app

rm -rf out
mkdir -p out
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d out \
  "$SRC/Net.java" "$SRC/HlsMaster.java" src/TestHls.java
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp out TestHls
