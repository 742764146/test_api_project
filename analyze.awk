# 对 monitor.sh 生成的 samples.log 统计分位数
# 用法: awk -f analyze.awk samples.log
# 输入格式: "2026-08-22 10:00:00 123ms 200"
$3 !~ /ms$/ { next }
{
    n = split($3, a, "ms")
    if (a[1] + 0 > 0) {
        cnt++
        lat[cnt] = a[1]
        if (a[1] > max) max = a[1]
    }
}
END {
    if (cnt == 0) { print "无有效采样"; exit }
    # 排序(插入排序,采样量通常几百~几千,足够)
    for (i = 2; i <= cnt; i++) {
        v = lat[i]
        for (j = i - 1; j >= 1 && lat[j] > v; j--) lat[j+1] = lat[j]
        lat[j+1] = v
    }
    p = sprintf("%.0f", cnt * 0.50); print "P50  = " lat[p]   "ms"
    p = sprintf("%.0f", cnt * 0.90); print "P90  = " lat[p]   "ms"
    p = sprintf("%.0f", cnt * 0.95); print "P95  = " lat[p]   "ms"
    p = sprintf("%.0f", cnt * 0.99); print "P99  = " lat[p]   "ms"
    print  "MAX  = " max "ms"
    print  "样本数 = " cnt
}
