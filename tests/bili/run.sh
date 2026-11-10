#!/bin/sh
# B 站 403 复现台。不参与打包。
# 用法：sh tests/bili/run.sh [BV号]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
export PYTHONIOENCODING=utf-8
python "$HERE/probe.py" "$1"
