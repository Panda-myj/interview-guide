/**
 * 语音 WebSocket 韧性与并发基线（Node 20+，无第三方依赖需全局 ws）。
 *
 * 目标（TODO.md 已确认门槛）：
 *   - 本地 10 并发会话
 *   - 端到端延迟目标 2 秒（首帧音频 → 首帧回复）
 *   - 20% 丢包 / 网络抖动下能重连并恢复
 *
 * 用法：
 *   ACCESS_TOKEN=<token> SESSION_ID=1 node perf/ws/voice-reconnect.mjs
 *   ACCESS_TOKEN=<token> SESSION_IDS=1,2,3 CONCURRENCY=10 DROP_RATE=0.2 node perf/ws/voice-reconnect.mjs
 *
 * 说明：
 *   - 该脚本只连本地开发环境，不发送真实音频内容（发送静音 PCM 帧），避免隐私数据外流。
 *   - 丢包用「按概率主动丢弃发送帧 + 随机主动断开后重连」模拟，因为 Windows 开发机无法使用 tc netem。
 *   - 输出 JSON 报告到 stdout，并写入 perf/baseline/ws-reconnect-<timestamp>.json。
 */
import wsPkg from 'ws';
// 兼容不同 ws 版本的导出形式：v7 直接导出类，v8+ 以命名导出 WebSocket 提供
const WebSocket = typeof wsPkg === 'function' ? wsPkg : wsPkg.WebSocket;
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const WS_BASE = process.env.WS_BASE_URL || 'ws://localhost:8080';
const API_BASE = process.env.API_BASE || 'http://localhost:8080';
const TOKEN = process.env.ACCESS_TOKEN || '';
const CONCURRENCY = Number(process.env.CONCURRENCY || 10);
const DROP_RATE = Number(process.env.DROP_RATE || 0.2);
const DURATION_MS = Number(process.env.DURATION_MS || 30000);
const SESSION_IDS = (process.env.SESSION_IDS || process.env.SESSION_ID || '')
  .split(',')
  .map((v) => v.trim())
  .filter(Boolean);

if (!TOKEN) {
  console.error('缺少 ACCESS_TOKEN：握手拦截器要求一次性 ws_ticket Cookie，需要 Bearer 令牌换取');
  process.exit(2);
}

if (SESSION_IDS.length === 0) {
  console.error('缺少 SESSION_IDS / SESSION_ID：请先在开发环境创建语音面试会话');
  process.exit(2);
}

/**
 * 为一次 WS 连接换取一次性 ws_ticket。
 * 握手拦截器（VoiceInterviewHandshakeInterceptor）从 Cookie 读取 ws_ticket，
 * 校验后立即从 Redis 删除（单次有效），且绑定 userId:sessionId。
 * 因此每次连接——包括脚本内的每一次重连——都必须重新调用本方法。
 */
async function fetchWsTicket(sessionId) {
  try {
    const res = await fetch(`${API_BASE}/api/auth/ws-ticket`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${TOKEN}`,
      },
      body: JSON.stringify({ sessionId: Number(sessionId) }),
    });
    if (!res.ok) return null;
    const setCookie = res.headers.get('set-cookie');
    if (!setCookie) return null;
    const match = setCookie.match(/ws_ticket=([^;]+)/);
    return match ? `ws_ticket=${match[1]}` : null;
  } catch (e) {
    return null;
  }
}

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, '..', 'baseline');
mkdirSync(outDir, { recursive: true });

const stats = {
  startedAt: new Date().toISOString(),
  concurrency: CONCURRENCY,
  dropRate: DROP_RATE,
  sessions: [],
};

function nowMs() {
  return Number(process.hrtime.bigint() / 1000000n);
}

/**
 * 服务端 VoiceInterviewWebSocketHandler 只接受 JSON 文本帧
 * （handleTextMessage），二进制帧会被 CloseStatus(1003) 关闭。
 * 静音 PCM（全零）以 base64 编码放入 data 字段，不外发真实音频。
 */
function audioFrame(bytes = 640) {
  return JSON.stringify({ type: 'audio', data: Buffer.alloc(bytes, 0).toString('base64') });
}

async function runOne(sessionId, index) {
  const result = {
    sessionId,
    connected: false,
    reconnects: 0,
    framesSent: 0,
    framesDropped: 0,
    firstReplyLatencyMs: null,
    recoveries: [],
    errors: [],
    finalState: 'unknown',
  };

  const url = `${WS_BASE}/ws/voice-interview/${sessionId}`;
  const startedFrameAt = nowMs();

  // 建立一次新的 WS 连接：先换取一次性 ticket，再携带 Cookie 握手。
  const openSocket = async () => {
    const ticketCookie = await fetchWsTicket(sessionId);
    if (!ticketCookie) return null;
    return new WebSocket(url, { headers: { Cookie: ticketCookie } });
  };

  let ws = await openSocket();
  if (!ws) {
    result.errors.push('ws-ticket fetch failed');
    stats.sessions.push(result);
    return;
  }

  const connect = () =>
    new Promise((resolve) => {
      let settled = false;
      ws.on('open', () => {
        if (!settled) {
          settled = true;
          result.connected = true;
          resolve(true);
        }
      });
      ws.on('message', (data) => {
        if (result.firstReplyLatencyMs === null) {
          result.firstReplyLatencyMs = nowMs() - startedFrameAt;
        }
        try {
          const payload = JSON.parse(data.toString());
          if (payload && payload.type) {
            result.finalState = payload.type;
          }
        } catch (e) {
          // 二进制音频帧，忽略
        }
      });
      ws.on('error', (err) => {
        result.errors.push(String(err.message || err));
        if (!settled) {
          settled = true;
          resolve(false);
        }
      });
      ws.on('close', () => {
        if (!settled) {
          settled = true;
          resolve(false);
        }
      });
    });

  const ok = await connect();
  if (!ok) {
    result.errors.push('initial connect failed');
    stats.sessions.push(result);
    return;
  }

  const tickMs = 100;
  const deadline = nowMs() + DURATION_MS;
  const reconnectDelayMs = 300 + index * 20;

  while (nowMs() < deadline) {
    if (Math.random() < DROP_RATE) {
      result.framesDropped += 1;
    } else if (ws.readyState === WebSocket.OPEN) {
      ws.send(audioFrame());
      result.framesSent += 1;
    }

    // 以 DROP_RATE 概率模拟网络抖动导致的断开，验证重连恢复
    if (ws.readyState === WebSocket.OPEN && Math.random() < DROP_RATE / 10) {
      const dropAt = nowMs();
      ws.terminate();
      await new Promise((r) => setTimeout(r, reconnectDelayMs));
      ws = await openSocket();
      if (!ws) {
        result.errors.push('reconnect failed: ws-ticket fetch failed');
        break;
      }
      const reconnected = await connect();
      result.reconnects += 1;
      result.recoveries.push({ reconnected, recoveryMs: nowMs() - dropAt });
      if (!reconnected) {
        result.errors.push('reconnect failed');
        break;
      }
    }
    await new Promise((r) => setTimeout(r, tickMs));
  }

  try {
    ws.close();
  } catch (e) {
    // 忽略关闭异常
  }
  stats.sessions.push(result);
}

await Promise.all(
  Array.from({ length: CONCURRENCY }, (_, i) => runOne(SESSION_IDS[i % SESSION_IDS.length], i)),
);

const latencies = stats.sessions
  .map((s) => s.firstReplyLatencyMs)
  .filter((v) => typeof v === 'number')
  .sort((a, b) => a - b);
const percentile = (p) => (latencies.length === 0 ? null : latencies[Math.min(latencies.length - 1, Math.floor((p / 100) * latencies.length))]);
const allRecoveries = stats.sessions.flatMap((s) => s.recoveries);

stats.summary = {
  sessions: stats.sessions.length,
  connected: stats.sessions.filter((s) => s.connected).length,
  totalReconnects: allRecoveries.length,
  successfulReconnects: allRecoveries.filter((r) => r.reconnected).length,
  framesSent: stats.sessions.reduce((a, s) => a + s.framesSent, 0),
  framesDropped: stats.sessions.reduce((a, s) => a + s.framesDropped, 0),
  firstReplyLatencyP50: percentile(50),
  firstReplyLatencyP95: percentile(95),
  firstReplyLatencyMax: latencies.length ? latencies[latencies.length - 1] : null,
  target: {
    concurrency: 10,
    endToEndLatencyMs: 2000,
    dropRate: 0.2,
  },
};

stats.finishedAt = new Date().toISOString();
const outFile = join(outDir, `ws-reconnect-${Date.now()}.json`);
writeFileSync(outFile, JSON.stringify(stats, null, 2), 'utf8');
console.log(JSON.stringify(stats.summary, null, 2));
console.log(`报告已写入: ${outFile}`);
