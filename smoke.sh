#!/usr/bin/env bash
# 单请求耗时拆解(冒烟测试)
# 用法: ./smoke.sh <URL> [次数,默认5]
set -euo pipefail

URL="${1:?用法: ./smoke.sh <URL> [次数]}"
N="${2:-5}"

FMT='time_namelookup:%{time_namelookup}s time_connect:%{time_connect}s time_appconnect:%{time_appconnect}s time_starttransfer:%{time_starttransfer}s time_total:%{time_total}s http_code:%{http_code}\n'

echo "== 目标: $URL x $N 次 =="
for i in $(seq 1 "$N"); do
  curl -o /dev/null -sS --compressed -w "[$i] $FMT" "$URL"
  sleep 1
done

cat <<'TIP'

判读:
  DNS(namelookup)高          -> DNS 问题,换 DNS 或加本地缓存
  connect 高                  -> 网络链路/服务端 accept 队列问题
  appconnect 高               -> TLS 握手慢,考虑会话复用/HTTP2
  starttransfer-connect 高    -> 服务端处理慢(重点排查)
  total-starttransfer 高      -> 响应体大/带宽不足,考虑 gzip、精简字段
TIP
