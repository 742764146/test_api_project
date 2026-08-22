// k6 阶梯压测脚本
// 安装: brew install k6
// 用法: k6 run k6-load.js
// 自定义: 修改下方 CONFIG 中的 URL、阶段并发、阈值
//
// 输出重点看: http_req_duration 的 p(95) / p(99),以及每阶段的错误率

import http from 'k6/http';
import { check, sleep } from 'k6';

// ========== 按需修改 ==========
const CONFIG = {
  url: 'https://api.example.com/your/endpoint', // 目标接口
  headers: { 'Content-Type': 'application/json' }, // 如需带 token 在此加
  stages: [
    { duration: '1m',  target: 5   },  // 预热
    { duration: '3m',  target: 20  },  // 日常负载
    { duration: '3m',  target: 50  },  // 峰值负载
    { duration: '3m',  target: 100 },  // 超预期,找拐点
    { duration: '3m',  target: 200 },  // 容量极限
    { duration: '1m',  target: 0   },  // 收尾
  ],
  thresholds: {
    // 性能门禁:不达标则 k6 退出码非 0,可直接接入 CI
    http_req_failed: ['rate<0.01'],        // 错误率 < 1%
    'http_req_duration': ['p(95)<500'],    // P95 < 500ms(按业务调整)
    'http_req_duration': ['p(99)<1000'],   // P99 < 1000ms
  },
};
// ==============================

export const options = {
  stages: CONFIG.stages,
  thresholds: CONFIG.thresholds,
};

export default function () {
  const res = http.get(CONFIG.url, { headers: CONFIG.headers });
  check(res, {
    'status is 2xx': (r) => r.status >= 200 && r.status < 300,
  });
  sleep(1); // 每个 VU 每秒 1 个请求;模拟真实用户可保留,纯压极限可调小
}

// 压测结束输出汇总摘要
export function handleSummary(data) {
  const d = data.metrics.http_req_duration.values;
  return {
    stdout: `
==== 压测摘要 ====
请求数:   ${data.metrics.http_reqs.values.count}
QPS:      ${data.metrics.http_reqs.values.rate.toFixed(1)}
错误率:   ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
AVG:      ${d.avg.toFixed(0)}ms
P90:      ${d['p(90)'].toFixed(0)}ms
P95:      ${d['p(95)'].toFixed(0)}ms
P99:      ${d['p(99)'].toFixed(0)}ms
MAX:      ${d.max.toFixed(0)}ms
==================
`,
  };
}
