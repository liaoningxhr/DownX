#!/bin/sh
# YouTube 失败原因文案验证（纯逻辑，不联网）
# 用法：sh tests/yt/run.sh
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/f/DownX-Studio/toolchain/jdk}
out=$(mktemp -d 2>/dev/null || echo ./out)
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$out" \
    ../../app/app/src/main/java/com/downx/app/YtDeny.java YD.java
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.YD
