#!/usr/bin/env bash
# 长时间低频采样监测:抓"偶尔慢"的间歇性问题
# 用法: ./monitor.sh <URL> [总时长秒,默认1800] [采样间隔秒,默认2] [慢阈值ms,默认500]
# 输出:
#   samples.log         每次采样:时间戳 + 耗时ms + http码
#   slow-requests.log   超过阈值的慢请求明细
set -euo pipefail

URL="${1:?用法: ./monitor.sh <URL> [总时长秒] [间隔秒] [慢阈值ms]}"
DURATION="${2:-1800}"
INTERVAL="${3:-2}"
THRESHOLD_MS="${4:-500}"

OUTDIR="$(cd "$(dirname "$0")" && pwd)"
SAMPLES="$OUTDIR/samples.log"
SLOW="$OUTDIR/slow-requests.log"
: > "$SAMPLES"; : > "$SLOW"

echo "== 监测 $URL,时长 ${DURATION}s,间隔 ${INTERVAL}s,慢阈值 ${THRESHOLD_MS}ms =="
echo "采样中... (Ctrl+C 可提前结束,已采数据保留)"

END=$(( $(date +%s) + DURATION ))
SLOW_CNT=0; TOTAL_CNT=0

while [ "$(date +%s)" -lt "$END" ]; do
  TS=$(date '+%Y-%m-%d %H:%M:%S')
  RESULT=$(curl -o /dev/null -sS --compressed \
    -w '%{time_total} %{http_code}' \
    --max-time 30 "$URL" 2>/dev/null) || RESULT="timeout 000"
  TIME_S=$(echo "$RESULT" | awk '{print $1}')
  CODE=$(echo "$RESULT" | awk '{print $2}')
  MS=$(awk -v t="$TIME_S" 'BEGIN{printf "%.0f", t*1000}')

  TOTAL_CNT=$((TOTAL_CNT+1))
  echo "$TS ${MS}ms ${CODE}" >> "$SAMPLES"

  if [ "$MS" -ge "$THRESHOLD_MS" ] || [ "$CODE" != "200" ]; then
    SLOW_CNT=$((SLOW_CNT+1))
    echo "$TS ${MS}ms http=${CODE} url=$URL" >> "$SLOW"
    echo "  [慢] $TS ${MS}ms http=${CODE}"
  fi

  sleep "$INTERVAL"
done

echo
echo "== 完成: 共 $TOTAL_CNT 次,慢/异常 $SLOW_CNT 次 ($(( TOTAL_CNT > 0 ? SLOW_CNT*100/TOTAL_CNT : 0 ))%) =="
echo "分位数统计:"
awk -f "$OUTDIR/analyze.awk" "$SAMPLES"
echo "明细: $SAMPLES / $SLOW"
