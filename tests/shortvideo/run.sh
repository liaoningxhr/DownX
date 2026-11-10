#!/bin/sh
# 抖音/快手纯逻辑验证（不依赖 Android、不联网）：
#   口令文案 -> 标题、作品 ID 提取、候选页顺序
# 用法：sh tests/shortvideo/run.sh
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/f/DownX-Studio/toolchain/jdk}
out=$(mktemp -d 2>/dev/null || echo ./out)
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$out" \
    Net.java ../../app/app/src/main/java/com/downx/app/ShortVideo.java T.java
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.T
cat result.txt
