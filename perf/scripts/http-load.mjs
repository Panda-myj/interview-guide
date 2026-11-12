/**
 * HTTP 列表接口压测（Node 20+，零依赖，k6 未安装时的等价替代）。
 *
 * 口径与 perf/k6/http-scenarios.js 的 list 场景一致：
 *   GET /api/interview/sessions?page=0&size=20，Bearer 令牌，固定并发，固定时长。
 * 输出并发数、请求数、P50/P95/P99、错误率（HTTP 非 200 或 Result.code != 200）。
 *
 * 用法：
 *   BASE_URL=http://localhost:59236 ACCESS_TOKEN=<token> VUS=10 DURATION_MS=60000 \
 *   node perf/scripts/http-load.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const TOKEN = process.env.ACCESS_TOKEN || '';
const VUS = Number(process.env.VUS || 10);
const DURATION_MS = Number(process.env.DURATION_MS || 60000);
const PATH = process.env.LOAD_PATH || '/api/interview/sessions?page=0&size=20';

if (!TOKEN) {
  console.error('缺少 ACCESS_TOKEN');
  process.exit(2);
}

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, '..', 'baseline');
mkdirSync(outDir, { recursive: true });

const latencies = [];
let okCount = 0;
let errCount = 0;
const errors = [];

async function worker(deadline) {
  while (Date.now() < deadline) {
    const started = performance.now();
    let ok = false;
    try {
      const res = await fetch(`${BASE_URL}${PATH}`, {
        headers: { Authorization: `Bearer ${TOKEN}` },
      });
      const body = await res.json();
      ok = res.status === 200 && body.code === 200;
      if (!ok) errors.push(`status=${res.status} code=${body.code} msg=${body.message}`);
    } catch (e) {
      errors.push(String(e.message || e));
    }
    latencies.push(performance.now() - started);
    if (ok) okCount += 1; else errCount += 1;
  }
}

const deadline = Date.now() + DURATION_MS;
const startedAt = new Date().toISOString();
await Promise.all(Array.from({ length: VUS }, () => worker(deadline)));
const finishedAt = new Date().toISOString();

latencies.sort((a, b) => a - b);
const pct = (p) => {
  if (latencies.length === 0) return null;
  return Number(latencies[Math.min(latencies.length - 1, Math.floor((p / 100) * latencies.length))].toFixed(1));
};
const total = okCount + errCount;
const summary = {
  path: PATH,
  startedAt,
  finishedAt,
  concurrency: VUS,
  durationMs: DURATION_MS,
  requests: total,
  ok: okCount,
  errors: errCount,
  errorRate: total === 0 ? null : Number((errCount / total).toFixed(4)),
  rps: Number((total / (DURATION_MS / 1000)).toFixed(2)),
  latencyP50: pct(50),
  latencyP95: pct(95),
  latencyP99: pct(99),
  latencyMax: latencies.length ? Number(latencies[latencies.length - 1].toFixed(1)) : null,
  sampleErrors: errors.slice(0, 5),
};

const outFile = join(outDir, `http-load-${Date.now()}.json`);
writeFileSync(outFile, JSON.stringify(summary, null, 2), 'utf8');
console.log(JSON.stringify(summary, null, 2));
console.log(`报告已写入: ${outFile}`);
