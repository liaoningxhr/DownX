#!/bin/sh
# 对齐 + 签名 + 安装 + 存档
# 用法：sh scripts/sign.sh 1.0.0
# 签名优先级：keys/downx-release.jks（正式） → 没有则退回 ~/.android/debug.keystore（仅本机调试）
set -e
ROOT="F:/DownX-Studio"
VER="$1"
[ -z "$VER" ] && { echo "用法: sh scripts/sign.sh <版本名，如 1.0.0>"; exit 1; }

export MSYS_NO_PATHCONV=1
export JAVA_HOME="$ROOT/toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$ROOT/toolchain/sdk/build-tools/34.0.0"
SRC="$ROOT/app/app/build/outputs/apk/release/app-release-unsigned.apk"
TMP="$ROOT/releases/.align-$VER.apk"
OUT="$ROOT/releases/DownX-v$VER.apk"

KS="$ROOT/keys/downx-release.jks"
PASSFILE="$ROOT/keys/.storepass"

"$BT/zipalign.exe" -p -f 4 "$SRC" "$TMP"

if [ -f "$KS" ] && [ -f "$PASSFILE" ]; then
  PASS=$(cat "$PASSFILE")
  "$BT/apksigner.bat" sign \
    --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --ks-key-alias downx \
    --out "$OUT" "$TMP"
  echo "签名：正式 keystore（keys/downx-release.jks）"
else
  echo "!! 没找到 keys/downx-release.jks，退回 debug 签名 —— 这个包不能给别人用，也不能覆盖升级正式包"
  "$BT/apksigner.bat" sign \
    --ks C:/Users/Administrator/.android/debug.keystore \
    --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$OUT" "$TMP"
fi
rm -f "$TMP"

ADB="$ROOT/toolchain/sdk/platform-tools/adb.exe"
if [ -n "$INSTALL" ]; then
  # gradle 构建后 adb server 常被带掉，先拉起来再装（否则报 cannot connect to daemon）
  "$ADB" start-server >/dev/null 2>&1 || true
  if ! "$ADB" install -r "$OUT"; then
    echo
    echo "安装失败。如果提示 INSTALL_FAILED_UPDATE_INCOMPATIBLE / signature mismatch："
    echo "  手机上的旧包是 debug 签名，和这个正式签名冲突，必须先卸载旧包："
    echo "    adb uninstall com.downx.app"
    echo "  卸载前记得先把 Pictures/DownX、Movies/DownX 里的下载文件备份出来。"
    exit 1
  fi
fi
echo "已签名: $OUT"
