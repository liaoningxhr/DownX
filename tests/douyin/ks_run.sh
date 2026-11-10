#!/bin/sh
# 快手快路径验证台：python tests/douyin/ks_fast.py
cd "$(dirname "$0")/.." || exit 1
python douyin/ks_fast.py "$@"
