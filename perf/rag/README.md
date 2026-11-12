# RAG 检索与引用评测（perf/rag）

本目录是 RAG 链路的**可复现评测基线**：固定评测集 + 固定语料 + 离线执行器 + 指标口径文档。
改动分块、检索、排序、提示词或引用逻辑时，先跑这里，再决定是否上线。

## 目录

| 文件 | 用途 |
| --- | --- |
| `eval-set-v1.jsonl` | 固定评测集，**恰好 30 条**，覆盖 召回 / 引用 / 拒答 / 多跳 四类场景 |
| `corpus-v1.jsonl` | 与评测集配套的 24 篇语料（面试知识文档，无任何真实个人信息） |
| `baseline-offline.md` | 离线基线报告，由执行器生成，**禁止手工编辑** |
| `run-offline-eval.sh` | 一键复现脚本 |

评测集与语料的 `docId` 强绑定：`RagEvalDatasetTest` 会校验每个 `expectedDocIds` 都能在语料中找到，
防止改了语料却忘了改样本导致基线失真。

## 一、指标口径

指标分为**在线**（真实链路采集，Micrometer）与**离线**（本目录执行器计算）两部分。
两边的公式必须一致，否则线上线下不可比。

| # | 指标 | 计算方式 | 采集位置 |
| --- | --- | --- | --- |
| 1 | 答案正确率 | `keywordHitRate = 回答中命中的期望关键词数 / 期望关键词总数`。作为可自动化的代理指标；需要人工判断时另记 `1/0` 并取均值 | 离线（需真实 LLM）/ 人工抽检 |
| 2 | 召回率 | `Recall@K = |expectedDocIds ∩ retrieved[0,K)| / |expectedDocIds|`，重复命中只算一次；`expectedDocIds` 为空（拒答样本）时记 1.0 | 离线 + 在线（`rag.citation.doc` 反向校验） |
| 3 | 引用准确率 | `|citedDoc ∩ supportingDoc| / |citedDoc|`，`citedDoc` 为回答中 `[S#]` 反解出的文档 ID；无引用记 0（不可核对） | 在线 `rag.citation.ratio` / `rag.citation.total` |
| 4 | 首 token 延迟 | 从 `answerQuestionStream` 进入服务，到 `sink` 第一次发出内容的时间差（纳秒 → 计时器） | 在线 `rag.llm.first_token{kb,provider}` |
| 5 | 向量检索耗时 | `similaritySearch` 内部从发起到返回的纳秒耗时，含候选池扩大与本地重排 | 在线 `rag.retrieval.duration{kb,mode,outcome}` |
| 6 | 改写成功率 | `success / (success + failure)`；`skipped`（改写关闭或空问题）不计入分母 | 在线 `rag.rewrite.total{status}` |

补充口径：

- `MRR@K = mean(1 / rank(第一个相关文档))`，用于衡量排序质量，拒答样本不参与聚合。
- `refusalAccuracy = 应拒答样本中「检索结果为空」的比例`。
- 百分位采用**最近秩法**：`index = ceil(p × n) - 1`，与 `RagEvaluationMetrics.percentile` 实现一致。

实现位置：`app/src/main/java/interview/guide/modules/knowledgebase/eval/RagEvaluationMetrics.java`（纯函数）
与 `app/src/main/java/interview/guide/modules/knowledgebase/service/RagMetrics.java`（Micrometer 埋点）。

### 标签基数约束

| 指标 | 标签 | 基数控制 |
| --- | --- | --- |
| `rag.retrieval.duration` | `kb` / `mode` / `outcome` | kb 拼接后截断到 64 字符；mode 为固定枚举；outcome ∈ hit/empty/error |
| `rag.llm.first_token` | `kb` / `provider` | provider 缺失归 `unknown` |
| `rag.citation.doc` | `doc` | 超过 500 个文档后统一归 `__overflow__` |
| `rag.citation.ratio` | `kb` | 只保留比例数值，不记录文档正文 |

**任何指标都不记录文档正文、用户问题原文、API Key**；`RagMetrics` 只接收数值和 ID。

关于「按查询聚合」：查询原文属于用户输入，既可能是隐私也可能是注入载荷，作为标签会同时带来
高基数与泄漏风险，因此**不把 query 作为指标标签**。查询级的排查依赖应用日志（`knowledgebase`
logger 输出的 kbIds、命中条数、来源数）与 `kb` + `outcome` 维度下钻，不依赖 Micrometer 标签。

## 二、评测集设计

30 条样本的分布：

| 类别 | 条数 | 考察点 |
| --- | --- | --- |
| `recall` | 10 | 单跳事实检索，`expectedDocIds` 只有 1 篇 |
| `citation` | 8 | 回答必须带 `[S#]`，用于计算引用准确率 |
| `refusal` | 6 | 语料外问题，必须返回空检索 + 拒答模板 |
| `multi_hop` | 6 | 需要 2 篇文档共同支撑，考察跨文档召回 |

字段说明（每行一个 JSON 对象）：

```json
{
  "version": "v1",
  "id": "rag-001",
  "category": "recall",
  "difficulty": "easy",
  "question": "JVM 的堆和虚拟机栈分别存放什么？",
  "expectedKeywords": ["堆", "虚拟机栈", "栈帧"],
  "expectedDocIds": ["doc-jvm-memory-model"],
  "mustRefuse": false,
  "providerProfile": "offline-mock-v1"
}
```

- `version`：数据集版本，与文件名一致；**改一条样本就要升版本**。
- `providerProfile`：复现该基线时使用的 Provider 配置档位（离线为 `offline-mock-v1`）。
  在线评测时必须改成真实档位（例如 `dashscope-qwen-plus`），否则结果不可复现。

### 版本策略

1. `eval-set-v1.jsonl` / `corpus-v1.jsonl` 一旦发布即冻结，只增不改；需要调整就新建 `v2`。
2. 基线文件按版本命名保留：`baseline-offline.md`（当前版本）与历史报告一起提交。
3. 升级版本时同步更新 `RagEvalDatasetTest` 中的 `EXPECTED_CASE_COUNT`（若条数变化）与本文档的分布表。
4. `RagEvalDatasetTest` 会拦截：条数不等于 30、字段缺失、分类/难度非法、引用不存在的文档、出现疑似密钥或手机号/身份证号。

## 三、如何复现

### 离线（无需数据库、无需模型，本机可跑）

```bash
bash perf/rag/run-offline-eval.sh
# 或等价的手工命令（在仓库根目录执行）
./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.eval.RagOfflineEvaluationTest'
```

执行器会用「词袋余弦 + 关键词覆盖率」替代 embedding 与 rerank 模型，跑
`vector` / `hybrid` / `hybrid_rerank` 三种策略，输出 `perf/rag/baseline-offline.md`。

也可以直接跑主类（需要先有编译产物）：

```bash
java -cp "app/build/classes/java/test;app/build/classes/java/main;<依赖>" \
  interview.guide.modules.knowledgebase.eval.RagOfflineEvaluationHarness perf/rag
```

### 在线（需要 PostgreSQL + pgvector + 真实 Provider）

前置条件：Docker 可用，`.env` 中配置了可用的 Chat / Embedding Provider。

```bash
docker compose up -d postgres redis minio
./gradlew :app:bootRun
```

然后按 `eval-set-v1.jsonl` 逐条调用知识库问答接口，采集：

```bash
# 指标端点
curl -s localhost:8080/actuator/prometheus | grep -E 'rag_(retrieval|rewrite|llm|citation|response)'
```

在线指标对应关系：

| 指标 | Prometheus 名称 |
| --- | --- |
| 检索耗时 | `rag_retrieval_duration_seconds{kb,mode,outcome}` |
| 首 token 延迟 | `rag_llm_first_token_seconds{kb,provider}` |
| 改写成功率 | `rag_rewrite_total{status}` |
| 引用命中 | `rag_citation_total{kb,hit}` / `rag_citation_ratio{kb}` / `rag_citation_doc{doc}` |
| 回答结果 | `rag_response_total{kb,status}` |

## 四、当前基线

### 4.1 离线基线（已执行）

执行环境：Windows 11 + JDK 21，无 Docker、无 PostgreSQL/Redis、无外部 LLM 调用。
数据来源：本机实际运行 `RagOfflineEvaluationTest` 生成，见 [`baseline-offline.md`](./baseline-offline.md)。

| 策略 | 样本 | Recall@5 | MRR@5 | 拒答准确率 |
| --- | --- | --- | --- | --- |
| `vector`（默认） | 30 | 0.979 | 0.979 | 0.833 |
| `hybrid` | 30 | 0.979 | 0.979 | 0.833 |
| `hybrid_rerank` | 30 | 0.979 | 0.958 | 0.833 |

延迟列（p50/p95）随每次运行的机器负载波动，不在此处固化，以 `baseline-offline.md` 为准；
离线延迟只包含本地打分与排序（亚毫秒级），**不具备线上参考价值**。

说明：

- 召回/MRR/拒答三列可逐次复现，可用作回归对比的锚点。
- 三种策略在离线替身下 Recall@5 持平，`hybrid_rerank` 的 MRR 略低（关键词权重 0.6 会让部分样本的首位互换）。
  **这是替身下的结论，不能直接推断线上效果**，只能说明「新策略没有把明显相关的文档挤出前 5」。
- 拒答准确率 0.833 = 5/6，有 1 条语料外问题与语料存在共同词项而未被 `minScore` 过滤。
  真实链路的拒答由 `minScore` 阈值 + 系统提示词的拒答约束共同保证，离线替身无法覆盖后者。

### 4.2 在线基线（未执行）

| 指标 | 结果 | 原因 | 恢复命令 |
| --- | --- | --- | --- |
| 答案正确率 | 未执行 | 本机 Docker 未运行，无 PostgreSQL/pgvector；且需要真实付费 Provider | `docker compose up -d postgres redis minio && ./gradlew :app:bootRun` 后按评测集逐条调用 |
| 引用准确率 | 未执行 | 需真实 LLM 生成带 `[S#]` 的回答 | 同上；观察 `rag_citation_ratio` |
| 首 token 延迟 | 未执行 | 需真实模型流式响应 | 同上；观察 `rag_llm_first_token_seconds` |
| 改写成功率 | 未执行 | 需真实 LLM 执行 query rewrite | 同上；观察 `rag_rewrite_total{status}` |
| 线上向量检索耗时 | 未执行 | 需 pgvector 中有真实索引数据 | 同上；观察 `rag_retrieval_duration_seconds` |
| 费用对比 | 未执行 | 需真实计费账单或 token 统计 | 同上；对比 `vector` 与 `hybrid_rerank` 两种 `mode` 标签下的调用量 |

**上述「未执行」项在补齐环境前不得填写任何估算值。**

## 五、策略切换与回滚

检索策略由 `app.ai.rag.retrieval.mode` 控制，默认 `vector`，与升级前行为完全一致：

| 配置值 | 行为 | 候选池 | 外部调用 |
| --- | --- | --- | --- |
| `vector` | 纯向量顺序，直接截断 topK | `topK` | 无额外 |
| `hybrid` | 向量分 0.5 + 关键词覆盖率 0.5 融合 | `topK × 3`（上限 50） | 无 |
| `vector_rerank` | 向量分 0.6 + 关键词 0.4 融合 | `topK × 2`（上限 50） | 无 |
| `hybrid_rerank` | 向量分 0.4 + 关键词 0.6 融合 | `topK × 3`（上限 50） | 无 |

成本边界：

- 候选池硬上限 `app.ai.rag.retrieval.max-candidates`（默认 50），重排是纯本地 O(n log n)，**不产生 token 费用**。
- 重排时间预算 `app.ai.rag.retrieval.timeout-ms`（默认 3000ms）：向量检索本身已超预算则跳过重排，
  直接按向量顺序返回，并累加 `rag.retrieval.degraded{reason="ranking_timeout"}`。
- 重排抛异常时同样降级为 `vector` 顺序，累加 `rag.retrieval.degraded{reason="ranking_error"}`。
- 元数据过滤失败时回退到本地过滤，累加 `rag.vector.metadata_filter_fallback` 与
  `rag.retrieval.degraded{reason="metadata_filter_error"}`。

**回滚方式**：

1. 配置回滚（秒级，无需发版）：把 `APP_AI_RAG_RETRIEVAL_MODE` 改回 `vector` 并重启。
2. 代码回滚：`RagCandidateRanker` 只在 `needsRanking()` 为 true 时生效，`vector` 分支直接截断，
   与升级前逻辑等价，无需回退索引或迁移数据。

**对既有数据的影响**：分块元数据（`doc_id` / `chunk_index` / `chunk_total` / `section` /
`char_start` / `char_end`）只写入 `Document.metadata`，**不修改 chunk 文本**，因此：

- 向量内容与检索得分不变，已有 pgvector 索引无需重建；
- 租户/知识库过滤条件（`user_id`、`kb_id`）完全不参与重排，权限边界不变；
- 分页由调用方的 `topK` 决定，重排只改变顺序，不改变条数上限。

新增元数据要到**下一次文档向量化**才会写入旧文档；旧数据缺字段时，`RagContextBuilder`
按「无章节信息」降级展示，不影响问答。

## 六、已知限制

1. 离线替身用词袋余弦，不理解同义改写（「虚拟机栈」与「线程栈」被视为不同词项），
   因此离线 Recall 只能横向比较策略，不能当作线上召回率。
2. 离线执行器无法覆盖 LLM 生成质量，答案正确率、引用准确率、首 token 延迟必须在线采集。
3. 首 token 延迟从 Service 方法进入开始计时，若上游排队（限流、连接池等待）不计入该指标。
4. 引用准确率依赖模型严格遵守 `[S#]` 输出格式；模型不输出引用时该指标为 0，需结合
   `rag.citation.total{hit="false"}` 判断是「模型没引用」还是「引用错了」。
5. 评测集为中文面试知识域，跨领域的召回表现需要用同版本格式自建 `v2` 数据集。
