# AI Agent 开发面试知识库（完整解答版）

---

## 一、Agent 基础

### Agent 定义与核心架构
**Agent = LLM + Planning + Memory + Tools**
- **LLM**：大脑，负责推理、决策、生成
- **Planning**：任务分解、步骤规划、依赖分析
- **Memory**：短期（对话历史）、长期（向量库、知识图谱）、情景记忆
- **Tools**：外部能力扩展（搜索、代码执行、API 调用、文件操作）

**Agent Loop：Perceive → Reason → Act → Observe**
```python
while not done:
    perception = perceive(env)          # 感知：获取观察、工具结果、用户输入
    reasoning = llm.reason(perception)  # 推理：思考下一步、选择工具、生成参数
    action = reasoning.parse_action()   # 行动：调用工具、产出回答
    observation = execute(action)       # 观察：工具返回、环境反馈
    memory.update(observation)          # 更新记忆
    done = check_termination()          # 终止判断
```

**终止条件**：
1. 任务完成（LLM 判断或显式完成信号）
2. 达到最大步数/Token 预算（防死循环）
3. 用户中断
4. 错误熔断（连续失败 N 次、关键工具不可用）

---

### Agent vs 传统编程 vs 工作流

| 维度 | 传统编程 | 工作流 | Agent |
|------|----------|--------|-------|
| **决策者** | 程序员写死逻辑 | 预定义 DAG/状态机 | LLM 运行时决策 |
| **确定性** | 100% 确定 | 确定性路径 | 概率性、动态 |
| **适用场景** | 规则固定、高性能 | 标准化业务流程 | 开放性、模糊目标、需推理 |
| **维护成本** | 低（改代码） | 中（改配置） | 高（Prompt 调优、评测） |
| **可解释性** | 高 | 高 | 低（需链路追踪） |

**判断原则**：
- 能用 `if/else` 解决的别用 Agent
- 能用工作流跑通的别上 Agent
- 只有「目标明确但路径未知、需多轮推理+工具协作」才上 Agent

---

### Agent 范式对比     

类似原型模型，瀑布模型

| 范式 | 核心机制 | 优点 | 缺点 | 适用场景 |
|------|----------|------|------|----------|
| **ReAct** | Thought-Action-Observation 循环 | 简单、灵活、边做边想 | 易陷入局部最优、长任务上下文膨胀 | 简单问答、单跳工具调用、探索性任务 |
| **Plan-and-Execute** | 先规划全局 Plan，再逐步 Execute | 全局最优、可中断恢复、并行可控 | 规划失误代价大、需两次 LLM 调用 | 复杂多步任务、代码生成、科研分析 |
| **Reflection** | 生成 → 评判 → 修正（Self-Refine/Reflexion/CRITIC） | 质量高、可自我纠错 | 延迟高、Token 成本 2-3 倍 | 高质量生成、代码审查、创意写作 |

**工程落地常用组合**：`Planner (Plan-and-Execute) + Executor (ReAct) + Critic (Reflection)`

---

### 多 Agent 系统

**架构模式**：
```
Orchestrator-Subagent（主从）：
  Orchestrator（规划、分发、聚合）
    ├── Subagent A（专精领域 A，如代码生成）
    ├── Subagent B（专精领域 B，如数据分析）
    └── Subagent C（专精领域 C，如联网搜索）

Peer-to-Peer（对等）：
  Agent A ↔ Agent B ↔ Agent C  平等协商、共享黑板
```

**A2A 通信协议关键点**：
- **消息格式**：标准化（JSON-RPC / 自定义 Schema）
- **路由**：基于能力注册发现、意图路由
- **状态同步**：共享上下文、检查点同步
- **冲突解决**：投票、仲裁 Agent、人工介入

**一致性保障**：
- **事件溯源**：所有决策、工具调用、状态变更入 Event Log
- **幂等性**：工具调用带幂等键，重试安全
- **Saga 模式**：长事务补偿（每步有回滚动作）
- **人工审批关口**：敏感操作必须 Human-in-the-loop

---

### 安全

**Prompt Injection 攻击面与防御**：

| 攻击层 | 攻击手段 | 防御策略 |
|--------|----------|----------|
| **执行层** | 工具参数注入（`rm -rf /`）、代码执行逃逸 | 沙箱隔离、参数 Schema 严格校验、最小权限容器 |
| **认知层** | 指令覆盖、角色扮演、上下文污染 | System Prompt 置顶不可变、输入输出分离、结构化输出强约束 |
| **决策层** | 诱导恶意决策、绕过审批 | 敏感操作双因子确认、审批链路、操作审计日志 |

**权限边界设计**：
- **能力最小化**：每 Agent 只注册必要 Tools
- **数据最小化**：RAG 检索按权限过滤、工具参数脱敏
- **网络最小化**：Agent 网络隔离、只能访问白名单域名

---

## 二、LLM 调用

### Token 与上下文窗口
- **Token 计算**：英文 ~0.75 词/token，中文 ~1.5 字/token
- **窗口公式**：`window ≥ system_tokens + history_tokens + rag_tokens + tools_schema_tokens + max_output_tokens`
- **预算分配策略**：
  - System Prompt：固定预算（2-5%）
  - 历史对话：滑动窗口 + 摘要压缩
  - RAG：动态 TopK、按相关性截断
  - 工具定义：仅注册当前任务需要的工具

**Prompt Caching**：
- 静态内容（系统提示、工具定义、少样例）放**前缀**，命中缓存
- 动态内容（用户输入、检索结果、历史）放**后缀**
- OpenAI/Anthropic 支持显式 `cache_control` 标记

---

### 采样参数实战

| 参数 | 含义 | 推荐值 | 场景 |
|------|------|--------|------|
| **Temperature** | 随机性 | 0.1-0.3（代码/提取）<br>0.7-1.0（创意/头脑风暴） | 低=确定性复现，高=多样性 |
| **Top-p** | 核采样累积概率 | 0.9-0.95 | 配合 Temperature，裁剪长尾 |
| **Top-k** | 保留前 k 个候选 | 40-50 | 硬性限制词汇表 |
| **Presence Penalty** | 鼓励新话题 | 0.0-0.6 | 长对话防循环 |
| **Frequency Penalty** | 抑制重复词 | 0.0-0.5 | 防止「的的的」「是是是」 |

**推理模型（o1/o3）限制**：
- 不支持 Temperature/Top-p/Top-penalty
- 仅支持 `max_completion_tokens`、`stop`
- 内部自带思维链，外部不可干预



Temperature 管敢不敢冒险，Top-p/Top-k 管候选池多大，Penalty 管要不要重复——前两类二选一调，别一起动；遇到推理模型，这三个旋钮全部焊死，改用 `reasoning_effort` 控算力、用 prompt 控方向。

---

### Function Calling 工程实践

**工具粒度设计原则**：
```
❌ 粗粒度：search_and_summarize(query)  → LLM 难控制中间步骤
✅ 原子操作：search(query) → extract(url) → summarize(text)
✅ 组合操作：research_topic(topic) 内部编排原子工具
```

**并行工具调用**：
```json
{
  "tool_calls": [
    {"id": "call_1", "function": {"name": "search", "arguments": "{\"q\": \"A\"}"}},
    {"id": "call_2", "function": {"name": "search", "arguments": "{\"q\": \"B\"}"}}
  ]
}
```
- 批量发起、聚合返回、减少 RTT
- 注意：并行调用总 Token 计入同一请求

**错误处理策略**：
```python
async def call_tool_with_retry(tool, args, max_retries=3):
    for attempt in range(max_retries):
        try:
            return await tool.execute(args)
        except TransientError as e:
            if attempt == max_retries - 1: raise
            await asyncio.sleep(2 ** attempt)  # 指数退避
        except PermanentError as e:
            return ToolResult(error=str(e), fallback=True)  # 降级
```

---

### 结构化输出与流式输出

**JSON Mode vs Structured Output**：
| 特性 | JSON Mode | Structured Output (OpenAI) |
|------|-----------|---------------------------|
| 约束方式 | 提示词要求输出 JSON | 强制 Schema 约束（logit bias） |
| 成功率 | ~85% | >99% |
| 支持 Schema | 任意 | JSON Schema 子集 |
| 流式支持 | 困难（需增量解析） | 原生支持增量解析 |

**SSE 流式机制**：
```
data: {"choices": [{"delta": {"content": "Hello"}}]}
data: {"choices": [{"delta": {"content": " world"}}]}
data: {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "{\"q"}}}}]}}
data: {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "uery\": \"test\"}}]}}]}
data: [DONE]
```

**TTFT 优化**：time to first token
- 首包延迟 = 网络 + 推理首 Token
- 优化：模型预热、流式解码、小模型首包 + 大模型补全

AI大模型推理关键性能指标TTFT、TPOT 和 ITL .Time Per Output Token  Inter-Token Latency

---

### 成本优化

> **砍输出 > 砍未命中输入 > 复用（缓存）> 降级（路由）**

理由很直接——路由省的是"1x vs 0.06x"的差额（用 mini 替代 4o 省约 94%），看着最猛，但**一旦路由错判，代价是整单任务失败 + 重试，反而更贵**。而"把 50k 的网页抓取结果压到 5k"省的是实打实的 45k × 单价，且零风险。

**路由策略**：

```python
def route_model(query: str, complexity: str) -> str:
    if complexity == "simple" or estimate_tokens(query) < 1000:
        return "gpt-4o-mini"  # $0.15/1M in
    elif complexity == "coding":
        return "claude-3.5-sonnet"
    else:
        return "gpt-4o"  # $2.5/1M in
```

**缓存策略**：

厂商侧 prompt caching

- **精确缓存**：Prompt+参数完全一致 → Redis 缓存响应
- **语义缓存**：Embedding 相似度 > 0.95 → 复用回答
- **缓存键设计**：`hash(system_prompt + user_prompt + tools_schema + temperature)`

**Token 裁剪**：
- 输入端：历史摘要、RAG 截断、工具结果压缩
- 输出端：设置 `max_tokens`、停止词

---

## 三、MCP 协议

### MCP 定位与关系
```
Function Calling（LLM 原生能力）
    ↓ 需标准化接入
Prompt（意图表达）
    ↓ 需标准化模板
MCP（连接层协议）← 统一「AI 如何发现/调用外部能力」
    ↓ 编排层
Skills/Agents（业务编排）
```

**MCP ≠ Function Calling**：
- Function Calling 是「模型怎么调工具」
- MCP 是「工具怎么暴露给模型/客户端」（跨语言、跨进程、跨网络）

---

### MCP 四大核心能力

| 能力 | 方向 | 典型场景 |
|------|------|----------|
| **Resources** | 只读 | 文件读取、数据库查询、配置获取 |
| **Tools** | 读写/副作用 | 文件写入、API 调用、代码执行、发邮件 |
| **Prompts** | 模板化 | 复用 Prompt 模板、参数化注入 |
| **Sampling** | 反向委托 | Server 请求 Client 代为推理（如摘要、分类） |

---

### 架构与传输

**四层架构**：
```
   USER
	↓
LLM/Host（IDE/Chat App/CLI） 
    ↓ 管理生命周期、路由
Client（MCP Client SDK） 
    ↓ JSON-RPC 2.0
Server（MCP Server：stdio / HTTP） 
    ↓ 适配器
Data Source（文件系统/数据库/API/浏览器）
```

**传输协议选择**：
| 传输 | 场景 | 优点 | 缺点 |
|------|------|------|------|
| **stdio** | 本地工具、CLI 插件、开发调试 | 启动快、无网络开销、进程隔离 | 仅本机、难负载均衡 |
| **Streamable HTTP** | 远程服务、生产环境、多客户端 | 标准 HTTP、可 LB、支持认证、流式 | 需部署运维、网络延迟 |

> ⚠️ **已废弃**：HTTP+SSE（双连接复杂、代理不友好）

---

### 生产级 MCP Server 设计清单

```python
# 1. 工具幂等性
@tool(idempotency_key="user_id:action:resource_id")
async def transfer_money(from_acct, to_acct, amount, idempotency_key):
    # 幂等键去重
    
# 2. 退避策略
@retry(stop=stop_after_attempt(3), wait=wait_exponential_jitter(1, 10))
async def call_external_api(...):

# 3. P99 延迟目标
# - 简单工具 < 200ms
# - 复杂工具 < 2s
# - 超时熔断 + 降级

# 4. 上下文窗口管理
async def large_result_handler(result, max_tokens=4000):
    if estimate_tokens(result) > max_tokens:
        return summarize(result, max_tokens)  # 或分页返回 cursor

# 5. 安全
- 输入：Pydantic 模型强校验、SQL 注入防护、路径遍历防护
- 权限：OAuth2/JWT、RBAC、工具级 ACL
- 审计：结构化日志（request_id, user_id, tool, args_hash, latency, status）
```

---

## 四、RAG 检索增强

### 基本原理与流程

**离线索引**：
```
加载 → 清洗 → 分块 → Embedding → 向量库存储
  ↓         ↓       ↓        ↓           ↓
PDF/HTML  去噪/去重  语义完整  向量化    pgvector/Milvus/ES
```

**在线检索**：
```
查询 → 向量化 → ANN检索 → 重排序 → 上下文构建 → LLM生成
       ↓         ↓         ↓          ↓           ↓
    query_emb  HNSW/IVF  Cross-Encoder  截断/引用   答案+引用
```

---

### 分块策略选择

| 策略 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| **固定长度**（如 512 tokens） | 简单、可预测 | 破坏语义边界 | 结构化文档、代码、日志 |
| **语义分块**（标题/段落/语义边界） | 保持语义完整 | 块大小不均、实现复杂 | 文章、文档、长文本 |
| **递归分块**（大→小、保留层级） | 兼顾粗/细粒度 | 索引量大 | 多层级文档（书籍、规范） |

**黄金法则**：
- 块大小 ≈ 目标模型上下文的 10-20%（如 4k 窗口 → 400-800 tokens）
- 重叠 10-20% 防止边界信息丢失
- 保留元数据：`doc_id, chunk_index, section_title, page_num`

---

### 向量检索与混合检索

**ANN 索引选择**：近似最近邻
| 规模 | 推荐索引 | 内存 | 召回 | 建立速度 |
|------|----------|------|------|----------|
| < 100万 | HNSW (M=16, ef=200) | 高 | >0.98 | 慢 |
| 100万-1亿 | IVFFLAT (nlist=√N) | 中 | 0.90-0.95 | 快 |
| > 1亿 | DiskANN / ScaNN | 低 | >0.95 | 中 |

**距离度量**：
- **余弦相似度**：归一化向量，语义相似度标准选择
- **内积**：未归一化时等价余弦，推荐系统常用
- **欧氏距离**：未归一化向量，数值型特征

**混合检索 = 向量 + BM25 + RRF**：
```python
def hybrid_search(query, top_k=10):
    # 1. 向量检索
    vec_results = vector_store.search(query_emb, top_k * 3)
    # 2. 关键词检索
    kw_results = bm25.search(query, top_k * 3)
    # 3. RRF 融合
    fused = rrf_fusion([vec_results, kw_results], k=60)
    # 4. 重排
    reranked = cross_encoder.rerank(query, fused[:top_k*2])
    return reranked[:top_k]
```

> 生产最佳实践：**向量召回宽（3-5x） → 重排精 → 最终 TopK**

---

### RAG 局限与治理

**GIGO 原则**：检索垃圾 → 生成垃圾
- **召回率评估**：标注集上测 Recall@K、MRR
- **噪声控制**：相似度阈值、重排分阈值、上下文去重
- **Lost in the Middle**：关键信息放在上下文首尾、或分段生成+聚合

**线上幻觉排查清单**：
1. 检索是否命中相关文档？（看检索日志、相似度分）
2. 文档内容是否包含答案？（人工核对）
3. Prompt 是否引导「基于上下文回答、无则拒答」？
4. 模型是否忽略上下文自造？（对比有/无 RAG 的输出）
5. 上下文是否过长导致注意力稀释？（截断策略）

---

## 五、上下文工程

### Harness 六层架构详解

```
┌─────────────────────────────────────┐
│ L6 约束与恢复：熔断、重试、降级、审批 │  ← 兜底安全网
├─────────────────────────────────────┤
│ L5 评估与可观测：评测集、链路追踪、指标 │  ← 看得见、改得好
├─────────────────────────────────────┤
│ L4 记忆与状态：短期/长期/情景/程序性  │  ← 状态连续性
├─────────────────────────────────────┤
│ L3 执行编排：Loop、分支、并行、检查点  │  ← 控制流核心
├─────────────────────────────────────┤
│ L2 工具系统：注册、Schema、权限、沙箱  │  ← 能力边界
├─────────────────────────────────────┤
│ L1 信息边界：System Prompt、约束、角色  │  ← 行为准则
└─────────────────────────────────────┘
```

**核心洞察**：Model 决定上限，**Harness 决定下限**。同模型，Harness 完善的系统可达 90%+ 完成率，裸模型可能 < 30%。

---

### Token 预算与设计模式

**40% 利用率阈值**：
- 实际可用上下文 ≈ 窗口 × 0.4
- 超过后：注意力稀释、幻觉率指数上升、推理崩溃

**上下文压缩技术**：
| 技术 | 原理 | 适用场景 |
|------|------|----------|
| **摘要压缩** | LLM 生成历史摘要替代原文 | 长对话、代理记忆 |
| **语义裁剪** | Embedding 相似度保留高相关段 | RAG 上下文构建 |
| **结构化遗忘** | 保留关键实体/决策/事实，丢弃闲聊 | 长周期 Agent |

**渐进式披露**：
```
L1 元数据（常驻）：工具 Schema、系统约束、核心规则
L2 正文（按需）：当前任务相关文档、最近 N 轮对话
L3 资源（隔离）：大文件、代码库、数据库 → 通过工具按需读取
```

---

### 实践案例精华

**OpenAI（Operator/Computer Use）**：
- 机械约束 > 文档约束（`click(x,y)` 比「点击按钮」更可靠）
- 熵管理：限制动作空间、强制结构化输出、异常即停止

**Anthropic（Claude Code / Computer Use）**：
- GAN 式三 Agent：Planner（规划）+ Worker（执行）+ Critic（评判）
- 上下文重置：任务阶段切换时清理无关历史、注入新 System Prompt

**Stripe（Minions 系统）**：
- 主 Agent 分解任务 → 生成 Minion 规格（Prompt+Tools+Context）
- Minion 独立执行、返回结构化结果 → 主 Agent 聚合
- 隔离故障域、并行加速、Token 预算可控

---

## 面试高频追问·标准答案

### Agent 基础

**Q: Agent Loop 中如何防止无限循环？**
```
A: 多层防护：
1. 硬性上限：max_steps (通常 10-30)、max_tokens、wall_time
2. 进度检测：连续 N 步无新信息/无状态变化 → 强制终止
3. 循环检测：Action+Params 哈希去重、Observation 语义去重
4. Critic 介入：独立 Evaluator 打分，连续低分触发人工/重规划
5. 用户中断：SSE 流式下随时可发送中断信号
```

**Q: ReAct 和 Plan-and-Execute 各自优缺点？**
```
A: 见上表。工程上常混合：Planner 生成高层 Plan（步骤+依赖），Executor 用 ReAct 执行单步，Critic 评估步骤产出，失败则局部重规划。
```

**Q: 多 Agent 如何保证一致性？**
```
A: 
- 共享 Event Log（单一事实来源）
- 关键状态用分布式锁/乐观锁
- 跨 Agent 调用用 Saga 模式（每步有补偿）
- 最终一致性：异步消息队列 + 幂等消费 + 对账任务
```

---

### LLM 调用

**Q: Token 超出上下文窗口怎么处理？**
```
A: 分级裁剪策略：
1. 优先保留：System Prompt、当前任务目标、最近 3-5 轮对话
2. 压缩历史：LLM 摘要化早期对话 → 单条摘要替代
3. 截断 RAG：按相关性保留 Top-K、或仅保留元数据+引用
4. 工具结果：仅保留关键字段、大结果存对象存储返回引用
5. 最后手段：分阶段执行（子任务独立上下文）+ 结果聚合
```

**Q: Function Calling vs Prompt 里写工具？**
```
A: 
Function Calling：
- 结构化参数、模型原生支持、并行调用、流式增量解析
- 约束强、不易幻觉参数名

Prompt 写工具（早期做法）：
- 灵活、兼容不支持 FC 的模型
- 易出现格式错误、参数幻觉、难并行

结论：有 FC 必用 FC；无 FC 用结构化输出+正则兜底。
```

**Q: 如何降低 LLM 调用 P99 延迟？**
```
A: 
1. 模型路由：简单任务小模型、复杂任务大模型
2. 流式首包：小模型/缓存命中快速首包、大模型异步补全
3. 并行化：独立子任务并行调用、工具并行调用
4. 缓存：精确/语义缓存命中直接返回
5. 预热：保持长连接、模型预热请求
6. 降级：超时快速失败、返回缓存/模板/拒答
```

---

### MCP 协议

**Q: MCP 和直接用 Function Calling 有什么区别？**
```
A: 
Function Calling：模型侧能力，工具定义在 Prompt/请求里，耦合特定模型厂商
MCP：标准化协议层，工具在 Server 侧注册，Client 发现调用，跨语言/进程/网络
MCP 解决：「工具一次开发，多模型/多客户端复用」「工具服务独立部署演进」「安全隔离」
```

**Q: stdio 和 Streamable HTTP 适合什么场景？**
```
A: 
stdio：本地开发工具（文件、Git、Shell）、IDE 插件、CLI Agent、测试调试
Streamable HTTP：生产服务（数据库、API、浏览器）、多租户、需认证/限流/负载均衡、远程调用
```

**Q: 如何设计生产级 MCP Server？**
```
A: 见上文「生产级 MCP Server 设计清单」五点：幂等性、退避重试、P99目标、上下文管理、安全审计。
补充：健康检查端点、指标导出、版本化工具 Schema、文档自动生成。
```

---

### RAG

**Q: 分块策略如何选择？过大/过小问题？**
```
A: 
选择依据：文档结构、查询类型、模型窗口、Embedding 模型特性
过大（>1500 tokens）：语义稀释、检索精度低、上下文浪费、Embedding 质量下降
过小（<100 tokens）：上下文碎片化、丢失跨段依赖、索引膨胀、检索噪声大
实战：递归分块（父块 1000t + 子块 200t）、检索命中子块 → 返回父块上下文
```

**Q: 向量 vs 关键词检索优缺点？为什么混合？**
```
A: 
向量：语义匹配强、同义词/多语言友好、但错过精确实体/数字/缩写、冷启动需训练
关键词（BM25）：精确匹配强、无需训练、解释性好、但不懂语义、同义词盲区
混合（RRF）：互补、召回率提升 15-30%、工程成熟度高、生产标配
```

**Q: RAG 线上幻觉排查思路？**
```
A: 
1. 复现：固定随机种子、记录完整链路
2. 检查检索：日志看召回文档、相似度分、是否命中金标准
3. 检查重排：Cross-Encoder 分数、是否过滤掉关键文档
4. 检查上下文构建：截断位置、引用格式、是否超窗口
5. 检查 Prompt：是否有「不知则拒答」「引用来源」约束
6. 对照实验：关闭 RAG 对比、换模型对比、人工标注评测集回归
```

---

### 上下文工程

**Q: Agent 上下文超出窗口如何处理？**
```
A: 
1. 监控：实时计算 token_usage / window_size，>70% 告警
2. 分级裁剪（按重要性）：
   - L3 资源引用化（不入上下文）
   - L2 历史摘要化（保留关键决策/事实）
   - L1 必保（System Prompt、当前目标、工具 Schema）
3. 检查点机制：阶段性保存状态、新窗口加载检查点继续
4. 子任务隔离：复杂步骤派生子 Agent，独立上下文、返回结构化产物
```

**Q: AGENTS.md / SKILL.md 设计原则？**
```
A: 
AGENTS.md（项目级）：
- 单一事实来源：架构决策、约束、接口规范、运行命令
- 可执行：命令可直接复制运行、路径绝对化
- 版本化：随代码提交、PR 同步更新

SKILL.md（能力级）：
- 单一职责：一个 Skill 解决一类问题
- 可组合：标准化输入输出、无副作用依赖
- 可测试：含示例、边界用例、评测基准
```

**Q: Harness 哪层影响最大？为什么？**
```
A: L3 执行编排（Agent Loop）影响最大。
理由：
- L1/L2 是静态配置，写完基本不变
- L4/L5/L6 是支撑能力，锦上添花
- L3 决定「怎么把任务跑完」：循环控制、分支决策、并行编排、错误恢复、检查点
- 同模型同工具，Loop 设计优劣直接决定完成率、Token 效率、鲁棒性
- 核心难点：如何在不确定性中收敛、如何平衡探索与利用、如何最小化人工干预
```

---

## 附：工程落地检查清单（面试可背可用）

### Agent 系统上线前 Must-Have
- [ ] **评测集**：≥50 个代表性案例、含边界/对抗样本
- [ ] **链路追踪**：每步输入输出、Token、延迟、工具调用全记录
- [ ] **指标看板**：完成率、平均步数、Token 成本、P50/P99 延迟、错误率分类
- [ ] **熔断降级**：模型不可用/超时/超预算 → 静默降级/模板回复/人工工单
- [ ] **数据飞轮**：用户反馈→标注→评测集扩充→Prompt/工具优化→回归测试→发布
- [ ] **安全审计**：敏感操作审批日志、数据脱敏、权限变更审计

### 简历可量化指标模板
- **完成率**：从 X% 提升至 Y%（基于 N 个评测案例）
- **Token 成本**：单任务均值从 $A 降至 $B（路由+缓存+压缩）
- **P99 延迟**：从 C 秒降至 D 秒（流式+并行+小模型首包）
- **幻觉率**：人工标注集上从 E% 降至 F%（RAG+重排+约束 Prompt）
- **工具调用成功率**：从 G% 升至 H%（Schema 约束+重试+幂等）

---

> **核心心法**：面试不考背诵，考**工程判断力**——给定约束（成本、延迟、质量、团队能力），你如何取舍、如何落地、如何迭代。每个技术点准备：**原理一句、实现两句、坑三句、指标四句**。
