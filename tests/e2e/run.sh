#!/bin/sh
# 桌面端「真实网络」验证台：解析 -> 多清晰度 -> 真下载 -> 校验 -> 删文件
#
# 用法：
#   sh tests/e2e/run.sh            # 全部平台
#   sh tests/e2e/run.sh bili       # 只测 B 站
#   sh tests/e2e/run.sh x          # 只测 X
#   sh tests/e2e/run.sh yt         # 只测 YouTube
#   sh tests/e2e/run.sh u "链接"   # 直接测单条链接（可多个）
#
# IG 需要登录态，主机上可以用环境变量喂一份 Cookie：
#   IG_COOKIE="sessionid=...; csrftoken=..." sh tests/e2e/run.sh u <IG链接>
#
# 需要本机有可用的 SOCKS 代理（v2rayN/xray 默认 10808），可用 SOCKS_PORT 覆盖。
# 下载的文件每项校验完立刻删，work 目录只留当天日志。
set -e
cd "$(dirname "$0")"

JAVA_HOME=${JAVA_HOME:-/f/DownX-Studio/toolchain/jdk}
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
SOCKS_PORT=${SOCKS_PORT:-10808}
APP=../../app/app/src/main/java/com/downx/app

mkdir -p out work

echo "== 编译（Android 桩 + 真实抓取器 + 验证台）"
"$JAVAC" -encoding UTF-8 -nowarn -J-Duser.language=en -cp "../libs/json.jar" -d out \
  src/android/util/Log.java src/android/content/Context.java src/android/os/*.java \
  src/com/downx/app/BiliSession.java src/com/downx/app/IgSession.java src/com/downx/app/Parts.java \
  "$APP"/Net.java "$APP"/ChunkedFetch.java "$APP"/HlsMaster.java "$APP"/ShortVideo.java \
  "$APP"/TweetFetcher.java "$APP"/BiliFetcher.java "$APP"/IgFetcher.java "$APP"/XhsFetcher.java \
  "$APP"/YoutubeFetcher.java "$APP"/TikTokFetcher.java "$APP"/FbFetcher.java "$APP"/PinFetcher.java \
  src/E2E.java

echo "== 运行（SOCKS 127.0.0.1:$SOCKS_PORT）"
"$JAVA" -Dfile.encoding=UTF-8 -DsocksProxyHost=127.0.0.1 -DsocksProxyPort="$SOCKS_PORT" \
  -cp "out;../libs/json.jar" E2E "$@" 2>&1 | grep -v "^  \[I/"

# 只留 harness 自己产出的调试文件，真正的视频已在校验后删除
rm -f work/*.html work/*.mp4 2>/dev/null || true
