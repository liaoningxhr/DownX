#!/bin/sh
# 下载记录验证（不依赖 Android、不联网）：
#   HT —— 裁剪/去重规则（回归"下 40 张多图把 30 多个老视频记录挤没了"）
#   LT —— 追加写日志的编解码 / 合并 / 一万条 / 迁移顺序 / 写放大
#   RF —— 真实文件读写：追加、原子替换、写一半崩溃后的自愈
#   PN —— 多图归组：补回来的记录按文件名前缀叠成一组，不再各自占一格
# 用法：sh tests/history/run.sh
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/f/DownX-Studio/toolchain/jdk}
out=$(mktemp -d 2>/dev/null || echo ./out)
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$out" \
    ../../app/app/src/main/java/com/downx/app/RecRules.java \
    ../../app/app/src/main/java/com/downx/app/RecLog.java \
    ../../app/app/src/main/java/com/downx/app/RecFile.java \
    ../../app/app/src/main/java/com/downx/app/PhotoNaming.java \
    HT.java LT.java RF.java PN.java
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.HT
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.LT
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.RF
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.PN
