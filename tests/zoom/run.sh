#!/bin/sh
# 播放器/图片查看器「双指缩放」纯逻辑验证（不依赖 Android、不联网）：
#   捏合倍数、锚点跟随、边界夹取、轻点还原、换画布归位
# 用法：sh tests/zoom/run.sh
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/f/DownX-Studio/toolchain/jdk}
out=$(mktemp -d 2>/dev/null || echo ./out)
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$out" \
    View.java MotionEvent.java Resources.java DisplayMetrics.java \
    DecelerateInterpolator.java ValueAnimator.java \
    ../../app/app/src/main/java/com/downx/app/Zoomer.java ZT.java
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$out" com.downx.app.ZT
