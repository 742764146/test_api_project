#!/usr/bin/env bash
# 启动网页版性能测试平台
# 用法: ./run.sh [端口,默认8080]
set -euo pipefail
cd "$(dirname "$0")"
PORT="${1:-8080}"

if ! command -v java >/dev/null; then
  echo "错误: 未安装 java(需要 JDK 21+)" >&2; exit 1
fi

echo "启动后浏览器访问: http://127.0.0.1:${PORT}"
exec java PerfServer.java "$PORT"
