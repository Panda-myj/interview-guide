# 开发环境性能与韧性基线

本目录提供**可重复执行**的压测脚本和指标口径，用于验证 TODO.md 中已确认的性能目标：

| 目标 | 口径 | 脚本 |
|---|---|---|
| 本地 10 并发语音会话 | 10 个并发 WebSocket 会话同时收发 | `perf/ws/voice-reconnect.mjs` |
| 端到端延迟 2 秒 | 首帧音频发送 → 首帧服务端响应 | 同上（`firstReplyLatencyP95`） |
| 20% 丢包可恢复 | 20% 帧主动丢弃 + 随机断开重连，统计恢复成功率 | 同上（`DROP_RATE=0.2`） |
| 列表分页与 N+1 | `GET /api/interview/sessions` P95 与错误率 | `perf/k6/http-scenarios.js` |
| 会话状态提交 | 创建会话 + 重复提交答案的幂等性 | `perf/k6/http-scenarios.js` |
| 评估任务与 Redis Stream | 队列长度 + 消费错误率 | `perf/scripts/collect-metrics.sh` |
| RAG 检索与改写 | `POST /api/knowledgebase/query` P95 | `perf/k6/http-scenarios.js` |
| 删除补偿 | 补偿任务表积压量 | 见下文 SQL |
| WebSocket 重连/异常恢复 | 重连成功率、恢复耗时 | `perf/ws/voice-reconnect.mjs` |

## 前置条件

1. 本地开发模式：`docker compose -f docker-compose.dev.yml up -d`（PostgreSQL/pgvector、Redis、RustFS）。
2. 后端：`./gradlew :app:bootRun`（默认 `http://localhost:8080`）。
3. Provider 使用本地 Mock 或测试 Key，**不使用真实付费 LLM**。
4. 工具：`k6`（HTTP 场景）、`node >= 20` + `ws`（WebSocket）、可选 `psql` / `redis-cli`。

## 执行

```bash
# 0) 准备令牌与会话（开发环境）
export ACCESS_TOKEN="<登录后获取的 access token>"
export RESUME_ID="<已上传简历 id>"
export KNOWLEDGE_BASE_ID="<已向量化知识库 id>"

# 1) HTTP 场景（10 VU，2 分钟）
k6 run perf/k6/http-scenarios.js

# 2) WebSocket 韧性（10 并发，20% 丢包，30 秒）
SESSION_IDS="<sid1>,<sid2>" CONCURRENCY=10 DROP_RATE=0.2 node perf/ws/voice-reconnect.mjs

# 3) 指标快照
perf/scripts/collect-metrics.sh
```

## 需要记录的结果字段

- 并发数、总请求数、P50/P95/P99、错误率
- CPU / 内存（应用 + 容器）
- PostgreSQL 慢查询与索引命中、Redis 命中率与 Stream 积压、RustFS 请求失败率、LLM Mock 调用次数与耗时
- 测试数据规模（简历数、知识库文档数、会话数）与运行环境（OS、Java 版本、机器配置）

## 删除补偿积压查询

```sql
-- 依实际表名调整；用于验证 TECH-002 的补偿任务可检索
select status, count(*) from resource_deletion_compensation group by status;
```

## 当前执行状态

- 2026-11-05：脚本已落地并做静态校验；**未执行**——本机 Docker 守护进程未运行，
  无本地 PostgreSQL/Redis/RustFS，无法启动 `bootRun` 完成端到端压测。
- 未执行项不得标记为通过；恢复路径见根目录交付报告「未完成风险与最小恢复路径」。
