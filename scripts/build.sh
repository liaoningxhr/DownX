#!/bin/sh
# DownX 一键构建（Debug 未签名 release 包）
# 用法：sh scripts/build.sh [版本名]   例：sh scripts/build.sh 2.9.1
set -e
ROOT="F:/DownX-Studio"
VER="${1:-}"

export JAVA_HOME="$ROOT/toolchain/jdk"
export GRADLE_USER_HOME="$ROOT/toolchain/gradle-home"
export ANDROID_HOME="$ROOT/toolchain/sdk"
export ANDROID_SDK_ROOT="$ROOT/toolchain/sdk"
unset ANDROID_USER_HOME
export MSYS_NO_PATHCONV=1

cd "$ROOT/app"
"$ROOT/toolchain/gradle-8.7/bin/gradle" -p . assembleRelease 2>&1 | tr -d '\000' | grep -aE "java:[0-9]+|error:|BUILD|warning:" || true

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ -f "$APK" ]; then
  echo "产物: $ROOT/app/$APK"
  if [ -n "$VER" ]; then
    sh "$ROOT/scripts/sign.sh" "$VER"
  fi
else
  echo "构建失败：未找到产物"
  exit 1
fi
