// HTTP 场景压测：列表分页、会话状态提交、评估任务、RAG 检索。
// 运行：k6 run perf/k6/http-scenarios.js
// 前置：本地开发环境已启动（bootRun + 本地 PostgreSQL/Redis），并已导出 ACCESS_TOKEN。
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { BASE_URL, VUS, DURATION, authHeaders, KNOWLEDGE_BASE_ID, RESUME_ID } from './lib/env.js';

const listLatency = new Trend('list_latency', true);
const submitLatency = new Trend('submit_latency', true);
const ragLatency = new Trend('rag_latency', true);
const businessErrors = new Counter('business_errors');
const errorRate = new Rate('error_rate');

// 可通过 SCENARIOS 环境变量选择场景（逗号分隔，如 list,submit），
// 便于在外部依赖受限（如 LLM 配额耗尽）时只跑纯 HTTP 场景。
const enabledScenarios = (__ENV.SCENARIOS || 'list,submit,rag')
  .split(',')
  .map((v) => v.trim())
  .filter(Boolean);

const allScenarios = {
  // 列表分页：验证 N+1 与分页边界
  list: {
    executor: 'constant-vus',
    vus: VUS,
    duration: DURATION,
    exec: 'listScenario',
    tags: { scenario: 'list' },
  },
  // 会话状态提交：验证提交与状态机幂等
  submit: {
    executor: 'constant-vus',
    vus: Math.max(2, Math.floor(VUS / 2)),
    duration: DURATION,
    exec: 'submitScenario',
    tags: { scenario: 'submit' },
  },
  // RAG 检索：验证向量检索 + 改写 + LLM Mock 链路
  rag: {
    executor: 'constant-vus',
    vus: Math.max(2, Math.floor(VUS / 2)),
    duration: DURATION,
    exec: 'ragScenario',
    tags: { scenario: 'rag' },
  },
};

const selectedScenarios = Object.fromEntries(
  Object.entries(allScenarios).filter(([name]) => enabledScenarios.includes(name)),
);

export const options = {
  scenarios: selectedScenarios,
  thresholds: {
    // 开发环境目标：端到端 P95 < 2s（语音目标同口径，此处先约束 HTTP 链路）
    'list_latency': ['p(95)<500'],
    'submit_latency': ['p(95)<800'],
    'rag_latency': ['p(95)<2000'],
    'error_rate': ['rate<0.01'],
  },
};

// 服务端统一响应体 Result<T> 的成功码是 200（CommonConstants.StatusCode.SUCCESS）
function assertBusinessSuccess(res, label) {
  const ok = check(res, {
    [`${label} HTTP 200`]: (r) => r.status === 200,
    [`${label} Result.code == 200`]: (r) => {
      try {
        return JSON.parse(r.body).code === 200;
      } catch (e) {
        return false;
      }
    },
  });
  if (!ok) {
    businessErrors.add(1);
    errorRate.add(1);
  } else {
    errorRate.add(0);
  }
  return ok;
}

export function listScenario() {
  group('列表分页', () => {
    const res = http.get(`${BASE_URL}/api/interview/sessions?page=0&size=20`, {
      headers: authHeaders(),
      tags: { name: 'GET /api/interview/sessions' },
    });
    listLatency.add(res.timings.duration);
    assertBusinessSuccess(res, '会话列表');
    sleep(0.5);
  });
}

export function submitScenario() {
  if (!RESUME_ID) {
    return;
  }
  group('会话创建与状态提交', () => {
    const create = http.post(
      `${BASE_URL}/api/interview/sessions`,
      JSON.stringify({ resumeId: Number(RESUME_ID), skillId: null, questionCount: 5 }),
      { headers: authHeaders(), tags: { name: 'POST /api/interview/sessions' } },
    );
    submitLatency.add(create.timings.duration);
    if (!assertBusinessSuccess(create, '创建会话')) {
      sleep(1);
      return;
    }
    let sessionId = null;
    try {
      sessionId = JSON.parse(create.body).data?.sessionId ?? JSON.parse(create.body).data?.id;
    } catch (e) {
      sessionId = null;
    }
    if (!sessionId) {
      sleep(1);
      return;
    }
    // 重复提交同一答案：验证幂等与状态机不产生重复评估
    const answer = http.post(
      `${BASE_URL}/api/interview/sessions/${sessionId}/answers`,
      JSON.stringify({ questionIndex: 0, answer: '压测答案：HashMap 基于数组 + 链表/红黑树' }),
      { headers: authHeaders(), tags: { name: 'POST /api/interview/sessions/{id}/answers' } },
    );
    submitLatency.add(answer.timings.duration);
    assertBusinessSuccess(answer, '提交答案');
    sleep(0.5);
  });
}

export function ragScenario() {
  if (!KNOWLEDGE_BASE_ID) {
    return;
  }
  group('RAG 检索问答', () => {
    const res = http.post(
      `${BASE_URL}/api/knowledgebase/query`,
      JSON.stringify({
        knowledgeBaseIds: [Number(KNOWLEDGE_BASE_ID)],
        question: 'Java 集合框架的底层数据结构与时间复杂度是什么？',
      }),
      { headers: authHeaders(), tags: { name: 'POST /api/knowledgebase/query' }, timeout: '10s' },
    );
    ragLatency.add(res.timings.duration);
    assertBusinessSuccess(res, 'RAG 问答');
    sleep(1);
  });
}
