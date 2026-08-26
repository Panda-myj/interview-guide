# AI 智能面试平台 · 总学习文档（合并整理版）

> 学习顺序：**业务 → 架构 → 代码细节**。面试讲架构决策（为什么分层、为什么 Redis Stream、为什么向量检索）比讲方法实现更值钱。

---

## 目录

1. [项目概述](#第一部分项目概述)
2. [宏观架构](#第二部分宏观架构)
3. [代码目录结构](#第三部分代码目录结构)
4. [六大业务模块](#第四部分六大业务模块)
5. [学习路线图](#第五部分学习路线图)
6. [Java 后端基础八股（小白补课）](#第六部分java-后端基础八股小白补课)
7. [技术选型与设计决策](#第七部分技术选型与设计决策面试核心弹药)
8. [核心技术机制深入](#第八部分核心技术机制深入)
9. [面试高频考点速查](#第九部分面试高频考点速查)
10. [可继续深挖的方向](#第十部分可继续深挖的方向)

---

# 第一部分：项目概述

## 一、这是什么

**一句话总结**：一个**智能 AI 面试官平台**，用户上传简历后，AI 能自动分析简历、出面试题、模拟面试对话、评估回答，还支持语音面试、知识库问答、面试日程管理。

**核心价值**：把大语言模型（LLM）做成一个"面试官"，能出题、能追问、能打分。

**项目规模**：后端约 80+ Java 文件，前端约 40+ TSX 文件，属于中等规模的完整全栈项目。

## 二、技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 4.1.0 / Java 21（虚拟线程）/ Gradle / Spring AI 2.0.0 |
| 数据库 | PostgreSQL + pgvector（向量维度 1024，COSINE 距离，HNSW 索引） |
| 缓存与异步 | Redis（Redisson）/ Redis Stream |
| 存储与解析 | RustFS/S3 兼容对象存储 / Apache Tika |
| 映射与导出 | MapStruct / iText 8 / SpringDoc OpenAPI |
| 前端 | React 18 / TypeScript / Vite / TailwindCSS 4 / lucide-react |

## 三、业务闭环

一个**前后端分离的全栈 AI 面试训练平台**，覆盖简历分析、AI 模拟面试（文字 + 语音实时对答）、知识库 RAG 问答、面试邀约解析与日程管理、多供应商大模型配置等完整闭环。

> **传简历 → AI 打分 → 按岗位/技能生成问题 → 文字或语音模拟面试 → 统一评估出报告 → 导出 PDF；同时积累面试邀约，自动解析日程，用知识库文档做 RAG 问答。**

---

# 第二部分：宏观架构

## 一、四层架构（先看这张"地图"）

```
┌─────────────────────────────────────────────────┐
│                  前端 (React)                     │
│         用户界面：上传简历、聊天面试、看报告          │
├─────────────────────────────────────────────────┤
│              后端 Controller 层                   │
│      接收 HTTP 请求，校验参数，调用 Service         │
├─────────────────────────────────────────────────┤
│              后端 Service 层                      │
│       业务逻辑：出题、打分、文件处理、评估           │
├─────────────────────────────────────────────────┤
│              后端 基础设施层                        │
│   AI调用 | 数据库 | 缓存 | 文件存储 | 异步消息       │
│  (LLM)    (PG)   (Redis) (S3)   (Redis Stream)   │
└─────────────────────────────────────────────────┘
```

## 二、整体架构图

```
frontend (React)  --REST / SSE / WebSocket-->  app (Spring Boot)
                                                │
                    ┌───────────────────────────┼────────────────────────────┐
                    │ Controller（路由+校验）      │ Controller                  │
              modules 各业务模块                  │                            │
                resume / interview /             │                            │
                voiceinterview / knowledgebase   │                            │
                interviewschedule / llmprovider  │                            │
                    │                            │                            │
                    └── Service（业务编排，@Transactional 只在此层）── Repository (JPA)
                                                    │
                 ┌──────────────────────────────────┼──────────────────────────────┐
                 │ common（横切通用能力）               │ infrastructure（技术底座）      │
              ai（StructuredOutputInvoker、          │   redis（RedisService、        │
                 LlmProviderRegistry）               │      InterviewSessionCache）    │
              async（AbstractStreamProducer /        │   file（Tika、哈希、清洗）      │
                 AbstractStreamConsumer 模板）        │   export（iText 8 PDF）        │
              aspect（RateLimit Lua 限流）            │   mapper（MapStruct）          │
              exception（ErrorCode + 全局处理）        │                              │
              result（Result<T>）                    └──────────────────────────────┘
              evaluation（UnifiedEvaluationService）
              transaction（TransactionalExecutor）
```

## 三、数据流（一次模拟面试的完整路径）

```
用户点击"开始面试" → Controller 收请求 → Service 调 LLM 出题
→ 题目存 Redis 缓存 → 返回前端显示 → 用户输入回答
→ 回答存 DB → 后台 Redis Stream 异步触发评估
→ Consumer 拿到消息 → 调 LLM 打分 → 结果写回 DB
→ 前端查看评估报告
```

## 四、统一约定

- 对外响应统一 `Result<T>`，**禁止直接返回 Entity**；错误统一 `BusinessException(ErrorCode.XXX, "...")`，全局异常处理器返回 HTTP 200 + `Result.error`。
- 请求/响应体用不可变 `record`（`XxxRequest` / `XxxResponse` / `XxxDTO` / `XxxEntity`）。
- Entity → DTO/Response 用 MapStruct；构造器注入 + Lombok `@RequiredArgsConstructor`。
- 代码规范：2 空格缩进、无通配符导入、SLF4J 占位符（异常作最后参数）。

## 五、产品各组成部分（每个部件是干什么的）

> 从"整个产品由哪些组件组成、各自干什么"的角度看。**前端是门面，后端是大脑指挥中枢，PostgreSQL 记账、Redis 加速+排号、MinIO 存文件、AI 是外包的大脑、Docker 是一键启动工具、JDK 是后端起作用的运行环境。**

### 系统全景（6 个角色）

```
用户(浏览器) --请求--> 前端(React) --API--> 后端(Spring Boot)
                                              │  ├── 存取数据 → PostgreSQL(含向量)
                                              │  ├── 缓存/异步 → Redis
                                              │  ├── 存文件   → MinIO
                                              │  └── 调用AI   → 阿里云百炼(千问)
                                              └── 所有服务由 Docker 编排运行
```

### 逐个组件解释

| 组件 | 是什么 | 干什么 | 类比 | 关键理解 |
|------|--------|--------|------|----------|
| **JDK / Java 21** | Java 运行环境 | 编译并执行后端 Java 源码（Spring Boot） | 后厨炉灶 | 没有 JDK，后端程序起不来；版本 21 决定可用语法（如虚拟线程） |
| **Docker** | 容器化/一键启动器 | 把 PostgreSQL/Redis/MinIO/后端/前端打包成独立容器，一条命令全部启动 | 打包送货系统 | `docker-compose.yml` 是"清单"，声明服务、镜像、端口、数据卷 |
| **PostgreSQL（pgvector）** | 业务数据库 + 向量数据库 | ① 存业务数据（简历/面试/配置）；② 用 pgvector 存储文档向量，支撑 RAG 语义检索 | 台账/账本 | 普通 PG 只能查文字，pgvector 插件让它能查"语义相似"，是 LLM 应用核心基础设施 |
| **Redis** | 缓存 + 消息队列 | ① 存会话/高频数据（快）；② Redis Stream 做异步任务队列（简历分析、向量化），避免用户等待 | 便签 + 排号叫号 | 异步任务"丢给队列后台处理"，前端轮询进度 |
| **MinIO** | 对象存储（S3 兼容） | 存大文件：简历 PDF/Word、知识库文档、头像 | 仓库 | 兼容 AWS S3 协议，代码用 S3 SDK，可无缝换云上 OSS/COS；9000=API、9001=控制台 |
| **后端（Spring Boot）** | 业务逻辑核心 | 提供 RESTful API、编排 AI（分析/出题/评估/RAG/语音） | 后厨+店长 | 项目里你掌握的那 80+ Java 文件的所在处 |
| **前端（React+TS）** | 用户界面 | 所有页面展示与交互，SSE/WebSocket 实现打字机与语音实时效果，Nginx 托管 | 柜台/菜单 | 打包后用 Nginx 托管并提供访问 |
| **阿里云百炼（千问）** | AI 大脑（外部服务） | 提供三种能力：文本 LLM（对话/分析）、ASR（听懂说话）、TTS（AI 说话） | 外包的大厨 | 项目本身无 AI 能力，需 `.env` 配 `AI_BAILIAN_API_KEY`；可切 DeepSeek/Kimi/GLM |

### 回答常见疑问：你要"配置/安装"的东西实际在给谁装

| 你配置的东西 | 实际对象 | 一句话作用 |
|--------------|----------|-----------|
| 装 JDK 21 | 后端程序 | Java 代码的编译运行环境 |
| `docker-compose up` | PostgreSQL / Redis / MinIO | 免手动安装，一键启动基础设施 |
| `.env` 里配 API Key | 后端连"阿里云大脑" | 给 AI 服务授权 |
| `pnpm install` | 前端 | 下载 React 等前端依赖包 |

### 关键认识：哪些组件真正必须 Docker 管？

- 必须由 **Docker**（或手动安装）启动的：**PostgreSQL / Redis / MinIO**（它们是你电脑外的程序）。
- **后端**（你电脑装 JDK，`gradlew bootRun` 跑）、**前端**（装 Node，`pnpm dev` 跑）开发时直接本机运行；Docker 方式只是为了生产一键部署。

---

# 第三部分：代码目录结构

## 📁 后端 `app/src/main/java/interview/guide/`

### 1. `App.java` — 启动类

```java
@EnableScheduling      // 开启定时任务
@SpringBootApplication // Spring Boot 主程序
```

整个程序的入口。`main()` 一行代码启动整个后端。它还**排除了 Spring AI 的默认 OpenAI 自动配置**，因为项目自己管理 LLM Provider。

### 2. `common/` — 通用基础能力（最重要，先看懂这层）

| 子目录 | 核心文件 | 干什么 | 为什么要学 |
|--------|----------|--------|------------|
| `ai/` | `LlmProviderRegistry.java` | **LLM 模型注册中心**，管理所有 AI 模型（DashScope、Kimi、DeepSeek、GLM 等），用 `ConcurrentHashMap` 缓存 ChatClient | ⭐⭐⭐ 这是 Agent 项目的核心——如何动态切换和管理多个大模型 |
| `ai/` | `StructuredOutputInvoker.java` | **结构化输出调用器**，让 LLM 返回严格 JSON，带重试（最多 N 次）+ JSON 修复 | ⭐⭐⭐ Agent 项目必须解决的问题：LLM 输出不稳定，需要重试+修复机制 |
| `ai/` | `PromptSanitizer.java` | 防止 Prompt 注入攻击 | 安全基础 |
| `annotation/` | `@RateLimit` 注解 | 可重复的限流注解 | Java 注解进阶用法 |
| `aspect/` | `RateLimitAspect.java` | **AOP 限流切面**，用 Redis + Lua 脚本实现滑动窗口限流 | ⭐⭐ 学 AOP 切面编程 + Redis Lua |
| `async/` | `AbstractStreamConsumer.java` | **Redis Stream 消费者模板**（抽象类），子类只需实现 9 个抽象方法 | ⭐⭐⭐ 设计模式——模板方法模式的实际应用 |
| `async/` | `AbstractStreamProducer.java` | Redis Stream 生产者模板 | 同上 |
| `config/` | `CorsConfig.java`, `JacksonConfig.java` 等 | 跨域、JSON 序列化、S3 存储等配置 | Spring Boot 配置类写法 |
| `evaluation/` | `UnifiedEvaluationService.java` | **统一评估引擎**：文字面试和语音面试共用同一套打分逻辑 | ⭐⭐ 业务抽象——如何让不同场景共享同一引擎 |
| `exception/` | `GlobalExceptionHandler.java` | **全局异常处理器**，所有异常统一返回 `Result` | ⭐⭐ Spring 全局异常处理标准写法 |
| `exception/` | `ErrorCode.java` | 错误码枚举（1xxx~11xxx），每个模块有自己的错误码段 | 错误码设计规范 |
| `result/` | `Result.java` | **统一响应体** `Result<T>`，`{code, message, data}` | ⭐ 前后端分离标准实践 |

**🔑 重点**：`LlmProviderRegistry` 和 `StructuredOutputInvoker` 是 Agent 项目的灵魂。

### 3. `infrastructure/` — 技术基础设施

| 子目录 | 核心文件 | 干什么 |
|--------|----------|--------|
| `file/` | `FileStorageService.java` | 文件上传到 S3（MinIO/RustFS） |
| `file/` | `DocumentParseService.java` | 用 Apache Tika 解析 PDF/DOCX/MD 文件内容 |
| `file/` | `FileHashService.java` | 计算文件哈希，检测重复上传 |
| `export/` | `PdfExportService.java` | 用 iText 8 生成 PDF 报告 |
| `mapper/` | `InterviewMapper.java` 等 | MapStruct 对象转换（Entity ↔ DTO） |
| `redis/` | `RedisService.java` | Redis 操作封装 |
| `redis/` | `InterviewSessionCache.java` | 面试会话缓存管理 |

### 4. `modules/` — 六大业务模块

每个模块内部都遵循 **Controller → Service → Repository** 三层结构。

| 模块 | 核心功能 | 学习重点 |
|------|----------|----------|
| **`resume/`** | 简历上传 → Tika 解析文本 → Redis Stream 异步触发 LLM 分析 → 返回评估结果 | ⭐⭐ 异步任务模式（Producer/Consumer） |
| **`interview/`** | 创建面试会话 → Skill 驱动出题 → 用户回答 → LLM 评估打分 → 生成 PDF 报告 | ⭐⭐⭐ 核心业务，包含追问、去重、时长联动 |
| **`voiceinterview/`** | WebSocket 实时通话 → 千问 ASR 语音识别 → LLM 对话 → TTS 语音合成 | ⭐⭐ 实时通信 + 语音 AI |
| **`knowledgebase/`** | 上传文档 → 分块 → 向量化存 pgvector → RAG 检索增强问答 → SSE 流式响应 | ⭐⭐ 知识库/RAG 完整实现 |
| **`interviewschedule/`** | 面试邀请文本解析（规则+AI 双引擎）→ 日历视图管理 → 状态流转 | 规则引擎+AI 混合 |
| **`llmprovider/`** | 多模型配置管理、API Key 加密、模型连接测试、全局设置切换 | 多模型管理架构 |

### 5. `resources/` — 资源文件

| 路径 | 干什么 |
|------|--------|
| `prompts/*.st` | **StringTemplate 提示词模板**（如评估 prompt、出题 prompt） |
| `skills/*/SKILL.md` | **面试 Skill 定义**（Java/前端/算法等方向的考察范围和题库） |
| `skills/*/references/*.md` | Skill 参考知识库 |
| `scripts/*.lua` | **Redis Lua 脚本**（限流滑动窗口） |
| `application.yml` | Spring Boot 配置 |

## 📁 前端 `frontend/src/`

| 目录 | 干什么 |
|------|--------|
| `api/` | Axios 封装的 API 请求函数，和后端 Controller 一一对应 |
| `components/` | 可复用 UI 组件（录音器、消息气泡、代码块渲染等） |
| `pages/` | 页面组件（面试中心、简历库、知识库管理、模型设置等） |
| `hooks/` | 自定义 React Hooks |
| `types/` | TypeScript 类型定义 |
| `utils/` | 工具函数 |

---

# 第四部分：六大业务模块

## 一、模块一览（按复杂度）

| 模块 | 功能 | 复杂度 |
| ------------------- | ---------------------------------------------------------- | ---------- |
| `llmprovider` | AI 提供商配置管理（API Key、模型、端点 的 CRUD） | ⭐ 简单 |
| `resume` | 简历上传、解析、AI 评分分析 | ⭐⭐ 中等 |
| `interviewschedule` | 面试日程管理，解析面试邀约文本 | ⭐⭐ 中等 |
| `knowledgebase` | 知识库文档管理 + 向量化 + 语义搜索（RAG） | ⭐⭐ 中等 |
| `interview` | **核心**：创建面试 → 出题 → 作答 → AI 评分 → 生成 PDF 报告 | ⭐⭐⭐ 复杂 |
| `voiceinterview` | 语音面试：WebSocket 实时通信 + TTS/ASR 语音 | ⭐⭐⭐ 最复杂 |

## 二、模块间依赖关系

```
interviewschedule  knowledgebase     ← 完全独立，只依赖 common
      (独立)           (独立)

     resume  ←────→  interview       ← 双向依赖，是项目的核心双模块

  llmprovider  ←──  voiceinterview   ← 基础设施 → 业务模块（反向依赖）
```

**两个重要细节：**

1. **`resume` 和 `interview` 双向依赖** — 简历关联面试记录，面试也关联简历。学 `resume` 时把遇到的 `InterviewPersistenceService` 当黑盒看；学到 `interview` 时再回头理解，反而加深印象。
2. **`interviewschedule` 和 `knowledgebase` 完全独立** — 它们不依赖其他业务模块，只依赖公共框架。学习顺序可互换。

## 三、逐个模块详解

### 1. `llmprovider` — 多供应商大模型配置（入门最佳）

**功能**：管理页配置多个供应商（dashscope / kimi / deepseek / glm / lmstudio），填 API Key、选模型、开关 embedding。运行时热写入 `~/.interview-guide/llm-providers.yml`（与 classpath 主配置隔离，避免污染源码/Jar）。

**核心要点：**
- `LlmProviderRegistry`：**动态构建** `ChatClient` / `EmbeddingModel`，统一注册表按 name 取，业务代码无感。
- API Key **AES/GCM/NoPadding 加密存储**（12 字节 nonce、128 位 tag），开发环境可用 fallback key。
- 默认 `dashscope` 即默认供应商，`app.ai.default-provider` 可切。
- Advisor 可配：SafeGuard（安全）、Logger、MessageChatMemory（多轮记忆）、ToolCalling。
- 所有 key 只从 `.env` 注入，`SETUP_API_KEYS.md` 有配置指引。

**你能学到什么：**
- Spring Boot 标准三层架构：`Controller → Service → Repository`
- `@Entity`、`@RestController`、`@PostMapping` 等基础注解
- `record` 类型的 Request/Response（Java 21 新特性）
- `@RequiredArgsConstructor` 构造器注入
- `@Valid` 参数校验

**为什么第一个学它：** 它体量最小、逻辑最直白，是理解"一个请求怎么从前端走到数据库再返回"的最佳样本。其他所有模块都依赖它提供的 AI 配置能力。

### 2. `resume` — 简历分析（引入文件处理和异步）

**用户流程：**
1. 上传 PDF / DOCX / TXT 简历（业务层限制 10MB）。
2. 后端先做文件哈希去重 → 存储到 S3 → Tika 解析出纯文本 → 异步（Redis Stream）触发 AI 分析。
3. 前端轮询/查看分析结果：五维评分（内容、结构、技能匹配、表达、项目）+ 总结 + 优势 + 分类建议。
4. 可导出 PDF 版简历分析报告。

**核心要点：**
- 文件格式校验白名单（`application.yml` 的 `resume.allowed-types`），不只靠扩展名。
- 文件哈希去重：同一份简历重复上传不重复分析（`FileHashService`）。
- **异步解耦**：上传请求立即返回，AI 分析走 Redis Stream 消费，避免用户长时间等待、避免占用 HTTP 线程。
- 文档解析用 Apache Tika `AutoDetectParser` + `BodyContentHandler`（只取正文、限 5MB）+ 关闭嵌入资源提取 + PDF 按坐标排序（多栏布局不乱序）。
- 解析失败返回业务码 `RESUME_ANALYSIS_FAILED`，不抛出裸 RuntimeException。
- 比纯 CRUD 多一层 Service 拆分（`UploadService` / `DeleteService` / `HistoryService`）——能看到什么时候该拆 Service、怎么拆。

### 3. `interviewschedule` — 面试邀约解析与日程（引入时间处理）

**用户流程：**
1. 粘贴飞书等来源的面试邀约文本（时间地点岗位等）。
2. 系统**规则正则 + LLM 双路解析**：能正则匹配的直接落库，歧义/复杂文本走 AI 结构化输出。
3. 生成日程/待办，前端 `InterviewSchedulePage` 展示。

**核心要点：**
- **双路解析降成本**：规则命中就不调 LLM，控制成本与延迟；LLM 只兜底复杂场景。
- 中文数字轮次映射（"一面/二面" → 1/2），`PromptSanitizer` 清洗注入文本防提示词污染。
- 可配置 `follow-up-count` 等，调度配置集中在前端 hook（`useInterviewSchedule`）。

**你能学到什么：**
- Java 日期时间 API（`LocalDateTime`、`@DateTimeFormat`）
- 自然语言文本解析（调用 LLM 提取结构化信息）
- 状态枚举管理（`InterviewStatus`：待确认→已确认→已完成→已取消）
- 日历/日程类业务的数据建模。状态机模式在这里初现。

### 4. `knowledgebase` — 知识库 + RAG 问答（引入向量数据库）

**用户流程：**
1. 上传文档（PDF/DOCX/MD/TXT，multipart 上限 50MB）。
2. 异步：Tika 解析 → TokenTextSplitter 分块 → DashScope text-embedding-v3 批量嵌入（≤10/批）→ 写 pgvector。
3. 查询：RAG 检索 → 可选查询改写（短查询加宽 top-k）→ SSE 流式返回 → 无结果时降级文案。

**核心要点（RAG 是高频考点）：**
- 向量维度 1024 与 `text-embedding-v3` 对齐；HNSW + COSINE 索引。
- `KnowledgeBaseVectorService`：分块 + Spring AI `VectorStore` + **临时 `pending:kbId` 事务化替换**——向量写入先落"待定"标记，全部成功后原子替换，失败可回滚，避免"半库"。
- `VectorRepository` 用**原生 SQL 删向量**（`metadata->>'kb_id'`），刻意避开 jsonb `?` 操作符与 JDBC 占位符冲突。
- `KnowledgeBaseQueryService`：按查询长度分档 `top-k`（短查询 20 / 中 12 / 长 8）+ 阈值过滤（0.18 / 0.28），短查询更宽容。
- **SSE 流式**：Flux 逐段推送；用 `STREAM_PROBE_CHARS=120` 探测流式响应是否真的可用，不行就降级为一次性返回，保证体验稳定。
- RAG 提示词模板在 `prompts/knowledgebase-query.st`，含"只依据文档回答，无依据则明确说不知道"的约束。

**你能学到什么：**
- PostgreSQL + pgvector 向量存储（`COSINE` 距离，1024 维）
- Embedding 嵌入过程
- 语义搜索 / RAG（检索增强生成）完整链路
- `Flux`（Reactor 响应式流）用于流式返回
- 文件下载（`ResponseEntity<byte[]>`）

### 5. `interview` — 文字面试（核心业务模块）

**核心实体：** `InterviewSessionEntity`（会话）→ `InterviewQuestionEntity`（问题）→ 回答文本 → 统一评估报告。

**用户流程：**
1. 选择技能分类（如 java-backend / bytedance-backend / system-design）或上传岗位 JD。
2. 选择简历（可带 context）→ 创建会话。
3. AI 逐题提问 → 用户作答 → 追加追问（`follow-up-count` 可配）→ 结束。
4. 批量评估 → 汇总报告。

**核心要点：**
- **问题生成基于技能元数据**：技能目录 `skills/**/skill.meta.yml` 预置分类与 references，`InterviewSkillService` 支持 `parse-jd` 从岗位 JD 动态生成技能树（并发解析 JD）。
- `InterviewQuestionService` 基于技能分类生成问题，并**避免与历史问题重复**。
- 会话状态与进度放在 Redis 缓存（`InterviewSessionCache`，TTL 24h，键前缀 `interview:session:`）。

**你能学到什么：**
- 复杂业务编排（多 Service 协作）
- 会话状态管理（创建→进行中→已完成）
- LLM 调用 + 结构化输出（`StructuredOutputInvoker`）
- Prompt 模板引擎（StringTemplate `.st` 文件）
- PDF 报告生成（iText 8）
- 综合运用前几个模块的能力（AI 配置、知识库检索）

### 6. `voiceinterview` — 语音面试（最亮眼的模块）

**链路：** WebSocket 实时 → 用户语音 PCM/Opus → Qwen3 Realtime ASR（流式转文字，服务端 VAD 断句）→ LLM 生成面试官回复 → 按句子分块 TTS 合成（Qwen TTS）→ 边合成边回推语音流 + 实时字幕。

**设计要点（讲价值）：**
- 用 **Qwen3 Realtime ASR** 的 `server_vad`（静音 2s 断句）+ `min-commit-chars`（20 字才提交）+ `user-utterance-debounce-ms`（2.5s）组合，解决"停顿=说完"的误判，中文面试思考停顿常见 2-3s。
- **分块 TTS**（`chunked-audio-enabled`）：按句子边界合成即推，首段语音不用等整段回复生成，配合 `tts-timeout-seconds` 防止单句卡死整轮。
- **面试阶段机**：intro → tech → project → hr 四阶段，每阶段有可配置的最短/建议/最长时长和题目数量上下限（`voice-interview.phase.*`），用 `voice-interview-opening.yml` 配置开场白。
- 红锁 / 事务同步（`TransactionSynchronization` 后置发消息）保证"落库后才有下一步动作"，避免并发状态错乱。
- 异步评估：`VoiceEvaluateStreamConsumer` 消费评估，避免阻塞语音会话。
- 限流三件套：`max-per-session`（每秒消息数）、`max-per-ip`（每小时面试数）、`max-concurrent`（全局并发）。
- 语音相关密钥（`AI_BAILIAN_API_KEY`）只放 `.env`。

**你能学到什么：**
- WebSocket 实时通信
- TTS（文本转语音）/ ASR（语音转文本）集成
- Redis Stream 生产者/消费者模式（`AbstractStreamProducer` / `AbstractStreamConsumer`）
- 实时流式处理和异步回调
- 比 `interview` 更复杂的会话生命周期管理

---

# 第五部分：学习路线图

## 一、推荐学习顺序

```
llmprovider → resume → interviewschedule → knowledgebase → interview → voiceinterview
   (基础)      (文件)      (时间/状态)        (向量/RAG)       (核心业务)      (实时通信)
```

**核心思路：先打基础（CRUD → 文件 → 时间），再学新概念（向量 → 编排 → 实时），每一步只增加一个主要的新知识维度。**

```
第1站 llmprovider     第2站 resume        第3站 interviewschedule
  ┌──────────┐        ┌──────────┐        ┌──────────┐
  │ 纯 CRUD  │  ──→   │ + 文件    │  ──→   │ + 时间    │
  │ 三层架构  │        │ + 异步    │        │ + 状态机  │
  └──────────┘        └──────────┘        └──────────┘
                                                    │
  ┌──────────┐        ┌──────────┐        ┌──────────┐
  │ WebSocket│  ←──   │ 核心编排  │  ←──   │ + 向量库  │
  │ + 实时语音│        │ + 出题评分│        │ + RAG    │
  └──────────┘        └──────────┘        └──────────┘
第6站 voiceinterview  第5站 interview      第4站 knowledgebase
```

每学完一个模块，下一个只增加 **一项主要新知识维度**，循序渐进不会迷路。

## 二、前端页面与后端 API 对应关系

| 前端页面 | 后端 Controller | 核心功能 |
|----------|----------------|----------|
| 简历上传页 | `ResumeController` | POST `/api/resumes/upload` |
| 简历历史列表 | `ResumeController` | GET `/api/resumes` |
| 面试中心页 | `InterviewController` | GET `/api/interview/sessions` |
| 创建面试页 | `InterviewController` + `InterviewSkillController` | POST 创建会话 + GET 获取 Skill 列表 |
| 面试聊天页 | `InterviewController` | POST 提交答案 + GET 下一题 |
| 面试报告页 | `InterviewController` | GET 评估报告 + 导出 PDF |
| 语音面试页 | `VoiceInterviewController` | WebSocket 实时通信 |
| 知识库管理 | `KnowledgeBaseController` | CRUD 知识库，触发向量化 |
| RAG 问答 | `RagChatController` | POST 提问 → SSE 流式返回 |
| 面试日程 | `InterviewScheduleController` | CRUD 日程，AI 解析邀请文本 |
| 模型设置 | `LlmProviderController` | CRUD Provider，切换默认模型 |

---

# 第六部分：Java 后端基础八股（小白补课）

> 说明：本部分是小白读本项目代码前需要补的基础。每一题都给【一句话答案】+【本项目对应代码】+【面试延伸】。学完本部分再看第七、八部分的技术决策和机制，就能对上号了。

---

## 6.1 Java 基础与语法

### 1. 为什么项目里到处是 `record`？它和普通类有什么区别？

- **一句话**：`record` 是 Java 16 正式引入的**不可变数据载体**，自动生成构造器、`getter`（`x()`）、`equals/hashCode/toString`，专门用来"只存数据不写行为"。本项目用它定义 `XxxRequest` / `XxxResponse` / `XxxDTO` / `ProviderSnapshot`。
- **本项目对应**：`CreateProviderRequest(String id, String baseUrl, ...)` 就是一个 record——声明即完成，不用写 getter/setter/构造器。
- **面试延伸**：record 字段默认 `final`，不可变是线程安全的天然保障；与 Lombok `@Data` 的取舍（record 适合 DTO，`@Data` 适合要可变字段的实体）。

### 2. Java 21 的"虚拟线程"是什么？为什么适合本项目？

- **一句话**：虚拟线程是 JVM 管理、不映射到系统线程的轻量级线程（JDK 21 正式版），**阻塞时几乎零成本切换**，可以让"一个请求一个线程"的模型支撑上万个并发。平台线程数受操作系统限制，虚拟线程可以成千上万。
- **本项目对应**：后端是 Spring Boot 4.1.0 / Java 21，AI/SSE 这种大量阻塞 IO 的请求靠虚拟线程提升并发。
- **面试延伸**：虚拟线程 vs 平台线程的底层（JVM 调度、park/unpark）；为什么虚拟线程不能用在 CPU 密集计算上；synchronized 与虚拟线程的配合。

### 3. Stream API 在项目里是怎么用的？

- **一句话**：Stream 是集合的函数式处理管道，核心三连：`stream()` → 中间操作（`filter`/`map`/`sorted`/`distinct`）→ 终端操作（`collect`/`toList`/`count`）。惰性求值，只有终端操作才真正执行。
- **本项目对应**：`providerRepository.findAll().stream().map(...).toList()`——查库后把每个 `Entity` 转成 `DTO` 就是最典型的 map 用法。
- **面试延伸**：`map` vs `flatMap`；并行流 `parallel()` 的线程池和线程安全陷阱；`peek` 与 `map` 的区别。

### 4. `Optional` 是什么？

- **一句话**：`Optional<T>` 是 Java 8 引入的**空值容器**，强迫你显式处理"可能没有值"，避免 NPE。常用 `map`/`filter`/`orElse`/`orElseGet`/`orElseThrow`。
- **本项目对应**：`Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);` 后接 `if (existingKb.isPresent())` 做去重判断。
- **面试延伸**：`orElse` vs `orElseGet`（后者才惰性，前者总会执行）；什么时候不该用 Optional（字段、方法入参）。

### 5. `String` 为什么不可变？`equals` 和 `==` 的区别？

- **一句话**：`String` 不可变是为了**常量池复用、线程安全、哈希缓存安全**。`==` 比的是引用地址，`equals` 比的是内容（`String` 已重写）。
- **本项目对应**：`Result` 的 `message`、日志占位符 `{}` 拼接等都是 String；代码规范要求 SLF4J 用占位符而不是字符串拼接（性能）。
- **面试延伸**：`StringBuilder` vs `StringBuffer`（非线程安全 vs 线程安全，拼接循环场景）；intern 机制。

### 6. 集合：`HashMap` 底层结构？

- **一句话**：`HashMap` = **数组 + 链表 + 红黑树**。key 的 `hashCode` 取模定位数组下标，冲突用链表（Java 8 起超过 8 个且数组 ≥64 转红黑树），链表长度低于 6 再退化回链表。默认容量 16，负载因子 0.75，扩容时 `tableSizeFor` 翻倍。
- **本项目对应**：`LlmProviderRegistry` 用 `ConcurrentHashMap`（线程安全版 HashMap）缓存 ChatClient。
- **面试延伸**：为什么用 `(n-1) & hash` 而不是取模（位运算快，前提是容量是 2 的幂）；`put` 流程；多线程下 HashMap 为什么丢数据/死循环（是JDK7 头插法扩容导致的丢数据/死循环）。

## 6.2 Java 并发

### 1. `ConcurrentHashMap` 凭什么线程安全？和 `HashMap`/`Hashtable` 的区别？

- **一句话**：JDK 8 起 `ConcurrentHashMap` 用 **CAS + synchronized 锁桶（数组下标）**：`put` 时空节点 CAS 写入，非空节点锁住链表/红黑树头。读操作基本无锁（volatile 读），所以"读多写少"场景并发极高。
- **本项目对应**：`LlmProviderRegistry` 用 `ConcurrentHashMap` + `computeIfAbsent` 保证同一个 provider 只创建一次 ChatClient——这就是本项目写锁的教科书用法。
- **面试延伸**：`computeIfAbsent` 的原子性；`size()` 为什么不准（并发修改）；分段锁（JDK7）vs 锁桶（JDK8）区别。

### 2. `ReentrantReadWriteLock` 读写锁为什么本项目读列表要用它？

- **一句话**：读写锁把锁拆成**读锁（共享）和写锁（排他）**：多个读可以并行，写必须独占。适合"读多写少"场景，显著提升并发读吞吐。
- **本项目对应**：`LlmProviderConfigService.java:67`——`listProviders()` 加 `readLock`，`createProvider()` 加 `writeLock`。10 个人同时查列表互不阻塞，但创建时要等写锁。
- **面试延伸**：锁降级（写锁→读锁）；"读锁可以被多个线程持有"是否意味着读到旧数据；`ReentrantReadWriteLock` vs `StampedLock`。

### 3. `synchronized` 的原理和锁升级？

- **一句话**：`synchronized` 基于**对象头的 Mark Word**，Java 6 优化后有四态：无锁 → 偏向锁（一个线程反复进入）→ 轻量级锁（CAS 自旋）→ 重量级锁（互斥量，阻塞）。作用在对象、方法、静态方法、代码块上。
- **本项目对应**：`QwenAsrService` 按 session 粒度用 `synchronized` 块防止同一会话并发启停 ASR；`VoiceInterviewWebSocketHandler` 的 `OrderedTtsChunkEmitter` 用 `synchronized` + `wait/notify` 实现"TTS 块乱序生成但必须有序发送"。
- **面试延伸**：为什么说 JDK 6 后 synchronized 性能不比 ReentrantLock 差；wait/notify 为什么必须在 synchronized 块内（管程模型）。

### 4. `synchronized` 和 `ReentrantLock` 怎么选？

- **一句话**：`synchronized` 写法简单、JVM 自动释放锁；`ReentrantLock` 需要手动 `lock/unlock`（必须 finally 释放），但支持**可中断、可超时、公平锁、多个条件队列**。
- **本项目对应**：本项目两个都用——简单互斥用 `synchronized`，需要读共享写独占的复杂场景用 `ReentrantReadWriteLock`（其父类 `ReentrantLock` 的进阶）。
- **面试延伸**：公平锁 vs 非公平锁（非公平吞吐更高，可能饥饿）；`tryLock(timeout)` 防死锁的经典写法。

### 5. 原子类（`AtomicInteger` 等）和 CAS？

- **一句话**：CAS（Compare And Swap）是"比较并交换"的 CPU 原子指令，原子类用它实现无锁并发更新。失败就重试（自旋）。`AtomicBoolean/Integer/Long/Reference` 覆盖基本类型和引用。
- **本项目对应**：6 个文件用 `Atomic*` 做无锁原子状态读写（如计数、标志位）。
- **面试延伸**：CAS 的 ABA 问题（`AtomicStampedReference` 解决）；自旋的 CPU 空转代价；`LongAdder` 在高竞争下的优势。

### 6. 线程池（`ThreadPoolExecutor`）7 个核心参数？

- **一句话**：核心线程数 `corePoolSize`、最大线程数 `maximumPoolSize`、存活时间 `keepAliveTime`、时间单位、阻塞队列 `workQueue`、线程工厂、拒绝策略。流程：先核心线程 → 再队列 → 再最大线程 → 触发拒绝策略。
- **本项目对应**：`AbstractStreamConsumer` 创建 `ThreadPoolExecutor(1, 1, LinkedBlockingQueue)` 单线程 daemon 池跑消费循环——每个 Stream 一个串行消费线程，保证顺序。
- **面试延伸**：4 种拒绝策略（AbortPolicy 抛异常/CallerRunsPolicy 调用者执行/DiscardPolicy 丢弃/DiscardOldestPolicy 丢最旧）；为什么不能无界队列（内存耗尽）；为什么不建议 `Executors.newFixedThreadPool`（无界队列风险）。

### 7. `CompletableFuture` 异步编排？

- **一句话**：`CompletableFuture` 是 Java 8 的异步编程工具，支持 `supplyAsync` 在别的线程池跑任务、`thenApply/thenCompose` 串接、`allOf` 聚合等待。回调式，不阻塞主线程。
- **本项目对应**：语音面试 TTS 并发——`CompletableFuture.supplyAsync(() -> ttsService.synthesize(sentence), voicePipelineExecutor)` 让多个句子的语音合成并行，最后 `future.get(ttsTimeoutSec, SECONDS)` 限时等待。
- **面试延伸**：`thenApply` vs `thenCompose`；异常处理 `exceptionally`/`handle`；与 WebFlux Reactor 的 `Flux/Mono` 的关系（本项目 RAG 用 `Flux` 流式推送）。

### 8. `volatile` 解决什么问题？  不熟

- **一句话**：`volatile` 保证字段的**可见性**（写立刻对其它线程可见，基于内存屏障）和**有序性**（禁止指令重排），但**不保证原子性**。
- **本项目对应**：消费者的 `running` 标志位用 volatile 控制消费循环的启停（`while(running)`）。
- **面试延伸**：为什么 `i++` 用 volatile 还是会错（读-改-写三步非原子）；DCL 单例为什么需要 volatile（禁止指令重排导致拿到半初始化对象）。

### 9. 死锁怎么产生、怎么避免？

- **一句话**：四个必要条件：互斥、持有并等待、不可剥夺、循环等待。破坏任一条即可避免，常见手段是**统一加锁顺序 + 超时释放**。
- **本项目对应**：代码里每个 `lock()` 都配 `try { ... } finally { unlock() }`——即使抛异常也释放锁，就是防止死锁的铁律。
- **面试延伸**：用 `jstack` 排查死锁；活锁 vs 死锁；数据库死锁（锁顺序不一致）。

### 10. `Semaphore` 和 `CountDownLatch` 分别是干嘛的？

- **一句话**：`Semaphore`（信号量）控制**同时访问的并发数**，acquire 拿许可、release 归还；`CountDownLatch`（倒计数门闩）让一个线程**等 N 个任务都完成后**再继续。
- **本项目对应**：`Semaphore` 限制每个语音面试会话的并发 TTS 合成数；`CountDownLatch` 异步转同步——等待 WebSocket 就绪或语音合成完成。
- **面试延伸**：`CountDownLatch` 不可复用 vs `CyclicBarrier` 可复用；`Semaphore` 是共享锁的实现（信号量，资源量，N个可同时处理，限流神器）。

**上述0815过完**

**下面知识点0816过完，重整简历。**

---

## 6.3 Spring 全家桶

### 1. IoC / DI 是什么？构造器注入为什么优于字段注入？

- **一句话**：**IoC**（控制反转）把"对象由谁创建"从你手里反转给 Spring 容器；**DI**（依赖注入）是容器把依赖对象"塞进"你需要的地方。三者关系：`@Component` 让类交给容器管理，`@Autowired` 或构造器让它把依赖装进来。
- **本项目对应**：`LlmProviderConfigService` 用 `private final LlmProviderRepository providerRepository;` + Lombok `@RequiredArgsConstructor`——Spring 自动为所有 `final` 字段生成构造器并注入。
- **面试延伸**：字段注入（`@Autowired` 直接写在字段上）的缺点——无法测试、隐藏依赖、循环依赖难发现；Spring 官方推荐构造器注入。**为什么要用 `@RequiredArgsConstructor`？** 因为 Spring 4.3+ 对"只有一个构造器"的类自动注入，Lombok 帮你生成这个构造器，省得手写。

### 2. Bean 的生命周期？

- **一句话**：**实例化 → 属性填充（依赖注入）→ Aware 接口回调 → `@PostConstruct`（BeanPostProcessor 前置）→ 初始化方法 → 就绪可用 → `@PreDestroy`（销毁前）**。
- **本项目对应**：`AbstractStreamConsumer` 用 `@PostConstruct init()` 启动消费线程、`@PreDestroy shutdown()` 优雅停止；`ApiKeyEncryptionService.init()` 用 `@PostConstruct` 从环境变量读加密密钥。
- **面试延伸**：`InitializingBean`/`DisposableBean` vs `@PostConstruct/@PreDestroy` vs 自定义 init-method 的执行顺序；BeanPostProcessor 是 AOP 的底层基础。

### 3. `@Component` / `@Service` / `@Repository` / `@Controller` 区别？

- **一句话**：都是把类注册为 Spring Bean 的注解，`@Service`/`@Repository`/`@Controller` 语义上分别是"业务层/数据层/控制层"组件，功能上唯一多出来的是：`@Controller` 能被 `@RequestMapping` 识别、`@Repository` 上的异常会被转成 Spring 数据访问异常。
- **本项目对应**：`LlmProviderConfigService` 标 `@Service`，`LlmProviderRepository` 是 `@Repository`（其实是 `JpaRepository` 子接口），`LlmProviderController` 标 `@RestController`。
- **面试延伸**：`@Component` 加在任意类上的场景（工具类、配置类）；`@ComponentScan` 扫描范围（默认扫描启动类所在包及子包）。

### 4. AOP 是什么？`@Aspect` 切面怎么理解？

- **一句话**：AOP（面向切面编程）把"和业务无关但横切所有接口"的逻辑（日志、限流、事务、安全）抽出来统一处理。三要素：**切点**（Pointcut，匹配哪些方法）、**通知**（Advice，执行什么逻辑，@Before/@After/@Around）、**切面**（Aspect，把两者组合）。
- **本项目对应**：`RateLimitAspect` + `@RateLimit` 注解——在 Controller 方法上标 `@RateLimit(count=30)`，切面统一拦截做 Redis Lua 滑动窗口限流。你**完全不用改业务方法代码**，注解一加就生效。
- **面试延伸**：`@Around` vs `@Before/@AfterReturning`；AOP 底层是动态代理（JDK 动态代理 vs CGLIB）；**同类内部调用为什么 AOP 失效**（代理自调用，详见事务部分）。

### 5. Spring MVC 一次请求的完整流程？

- **一句话**：请求 → `DispatcherServlet`（前端控制器）→ `HandlerMapping` 找 Controller 方法 → `HandlerAdapter` 调用（含参数绑定、`@Valid` 校验）→ Controller 返回 → `HttpMessageConverter` 序列化 JSON → 响应。**异常则交给 `@RestControllerAdvice` 统一处理**。
- **本项目对应**：`LlmProviderController` → `LlmProviderConfigService` → `LlmProviderRepository` 就是这条链路的业务部分；`GlobalExceptionHandler`（`@RestControllerAdvice`）把所有 `BusinessException` 转成 `Result.error` 返回。
- **面试延伸**：`@RestController` = `@Controller` + `@ResponseBody`；`@RequestBody` 反序列化 JSON、`@ResponseBody` 序列化返回（本项目走 Jackson）；`@Valid` 在校验失败时抛出 `MethodArgumentNotValidException`。

### 6. `@Transactional` 的传播行为和失效场景？

- **一句话**：事务传播 = 多个带事务的方法嵌套调用时如何合并事务（`REQUIRED` 默认加入已有事务、`REQUIRES_NEW` 新开独立事务）。**失效场景**：① 方法不是 public；② 同类内部自调用（没走代理）；③ 异常被 try-catch 吞掉；④ 抛出的是受检异常（默认只回滚 RuntimeException 和 Error）。
- **本项目对应**：`TransactionalExecutor` 用 `REQUIRES_NEW` 开独立子事务；铁律"LLM、S3、外部 HTTP 调用不得放在数据库事务内"。本项目用 `TransactionSynchronization` 后置回调保证"DB 提交成功后再发消息/推进状态"——因为事务提交事件发在事务外。
- **面试延伸**：为什么自调用失效（this 调用没经过代理对象）；`@Transactional` 必须配 `rollbackFor = Exception.class` 才回滚受检异常；**不要用 `@Transactional` 包大范围逻辑**（长事务占连接）。

### 7. Spring Boot 自动装配原理？

- **一句话**：启动类 `@SpringBootApplication` 包含 `@EnableAutoConfiguration`，它通过 `spring.factories` / `AutoConfiguration.imports` 加载所有自动配置类，再配合 `@ConditionalOnXxx` 条件注解按需生效（类路径有才装配）。
- **本项目对应**：`App.java` 排除了 Spring AI 的默认 OpenAI 自动配置（`@SpringBootApplication(exclude = {...})`），因为项目自己管理 LLM Provider。
- **面试延伸**：为什么叫"约定优于配置"；`@ConditionalOnClass/@ConditionalOnProperty` 用法；starter 机制（`spring-boot-starter-web` 帮你引入依赖 + 自动配置）。

### 8. `@ConfigurationProperties` 绑定配置？

- **一句话**：把 `application.yml` 里的一坨配置（如 `app.ai.providers`）绑定到一个 Java 类上，`@Component` + `@ConfigurationProperties(prefix="app.ai")`，类型安全地读取配置。
- **本项目对应**：`LlmProviderProperties`（含 `providers` Map、Advisor 开关、`defaultProvider`）就是绑定 `application.yml` 的 `app.ai.*`；`application.yml` 的 `resume.allowed-types` 是格式白名单配置。
- **面试延伸**：`@ConfigurationProperties` vs `@Value`；宽松绑定（kebab-case → camelCase）；配置文件优先级。

### 9. 全局异常处理怎么做的？

- **一句话**：`@RestControllerAdvice` + `@ExceptionHandler(异常类型)` 集中处理所有 Controller 抛出的异常，返回统一 `Result`。业务错误抛 `BusinessException(ErrorCode.XXX, "message")`，框架错误（参数校验、类型转换）在 Advice 里兜底转成 `Result.error`。
- **本项目对应**：`GlobalExceptionHandler` + `ErrorCode` 枚举（错误码分段 1xxx~11xxx）+ `Result<T>`。约定：HTTP 始终 200，业务状态看 `code` 字段。
- **面试延伸**：`@ExceptionHandler` 匹配优先级（越具体的异常越优先）；如何避免把堆栈泄露给前端。

### 10. `application.yml`、`.env`、`profile` 分别是什么？

- **一句话**：`application.yml` 是 Spring 主配置（端口、数据源、业务参数）；`.env` 存敏感密钥（API Key），由脚本/`@PostConstruct` 读进环境变量，**不进 Git**；profile（`application-dev.yml`）按环境切换配置。

- **本项目对应**：所有 `AI_BAILIAN_API_KEY` 等密钥只在 `.env`；`app.ai.default-provider` 在 yml 可切换默认供应商；pgvector 的 `initialize-schema` 仅开发环境开启。

- **面试延伸**：为什么密钥不能写进 yml/Jar（`~/.interview-guide/llm-providers.yml` 运行时隔离）；环境变量 vs 配置文件的优先级。

  根据 Spring Boot 的配置优先级规则（从高到低）：

  1. **命令行参数** (`java -jar app.jar --AI_BAILIAN_API_KEY=xxx`)
  2. **系统环境变量** (`export AI_BAILIAN_API_KEY=xxx`) —— **.env 文件通常被脚本读取后注入到这里**
  3. Jar 包外部的 `application-{profile}.yml`
  4. Jar 包内部的 `application-{profile}.yml`
  5. Jar 包外部的 `application.yml`
  6. Jar 包内部的 `application.yml`

---

## 6.4 Spring Data JPA / 数据库 / Redis

### 1. JPA 是什么？和 MyBatis 怎么选？

- **一句话**：JPA 是 Java 官方 ORM 规范，Hibernate 是主流实现；Spring Data JPA 再包一层，让你**只写接口方法名就自动生成 SQL**。MyBatis 是半自动、SQL 手写、灵活可控；JPA 是对象驱动、SQL 自动生成、适合 CRUD 为主的业务。
- **本项目对应**：全项目用 `JpaRepository`；`findByFileHash`、`findByEnabledTrueOrderByIdAsc` 都是方法名派生查询。
- **面试延伸**：为什么本项目不用 MyBatis（CRUD 多、无需精细 SQL 调优）；JPA 的 N+1 问题和二级缓存；`@Query` 写复杂 SQL。

### 2. `JpaRepository` 为什么不用写实现类？

- **一句话**：Spring Data JPA 在运行时用 **JDK 动态代理**为接口生成实现类。自带 `save`/`findAll`/`findById`/`deleteById` 等全套 CRUD；方法名按约定解析成 SQL（`findBy + 属性 + OrderBy`）。

- **本项目对应**：`LlmProviderRepository` 只有接口声明，`findByEnabledTrueOrderByIdAsc()` 自动翻译成 `SELECT * FROM llm_provider_config WHERE enabled = true ORDER BY id ASC`。

- **面试延伸**：`save()` 的 INSERT/UPDATE 判断（主键是否存在，存在走 merge）；`@Query("...")` 写 JPQL 或 `nativeQuery=true` 写原生 SQL（本项目的 `VectorRepository` 就是原生 SQL 删向量）。

  **删除和更新要加什么注解？**@Modifying + @Transactional。JPA 默认只做查询，增删改必须加 @Modifying 开启修改语句。

### 3. 主键策略有哪些？本项目用什么？

- **一句话**：自增（`IDENTITY`/`AUTO`）、`SEQUENCE`、UUID、雪花算法、自然主键。自增简单但分库分表麻烦；雪花算法全局唯一且有序；自然主键（业务本身唯一）语义清晰但不能改。
- **本项目对应**：`LlmProviderEntity` 用**自然主键**（provider 名称如 `"dashscope"` 就是主键），所以查重直接用 `existsById()`。
- **面试延伸**：自然主键 vs 代理主键优缺点；为什么 `@GeneratedValue(strategy = IDENTITY)` 在 Hibernate batch 插入会失效。

### 4. 懒加载 / N+1 问题？

- **一句话**：关联对象默认**懒加载**（用到才查），避免一次查一堆。但循环遍历 `session.getQuestions()` 时每条都发一条 SQL，就是 **N+1 问题**（1 条主查询 + N 条关联查询）。
- **本项目对应**：`InterviewSessionEntity` → `InterviewQuestionEntity` 是 1:N；面试评估批量取问题时要警惕 N+1。
- **面试延伸**：解决方案——`@EntityGraph`、`join fetch`、批量加载 `@BatchSize`；懒加载在事务外访问抛 `LazyInitializationException`。

### 5. 事务隔离级别？

- **一句话**：四个级别解决三种读异常：**读未提交**（脏读）、**读已提交**（不可重复读）、**可重复读**（幻读）、**串行化**（全锁，性能最差）。MySQL 默认可重复读，PostgreSQL 默认读已提交。
- **本项目对应**：本项目用 PostgreSQL；`@Transactional` 写操作靠数据库隐式行锁保证并发一致性。
- **面试延伸**：脏读/不可重复读/幻读的区别（三次读同一数据 vs 一次范围查询条数变化）；`MVCC` 多版本并发控制是什么；间隙锁如何解决幻读。

### 6. 索引是什么？B+ 树为什么快？

- **一句话**：索引是数据库的"目录"，B+ 树把查找从全表扫描 O(n) 降到 O(log n)。**唯一索引**保证列不重复（本项目 `fileHash` 加 `unique` 做去重兜底）、**联合索引**按最左前缀匹配、**覆盖索引**让查询不用回表。
- **本项目对应**：`KnowledgeBaseEntity` 上 `idx_kb_hash unique`（去重）+ `idx_kb_category`（分类查询）；pgvector 的 **HNSW** 索引是向量专用索引。
- **面试延伸**：为什么选 B+ 树而不是 B 树/哈希（范围查询 + 磁盘 IO 次数）；`explain` 看执行计划；最左前缀原则。

### 7. Redis 有哪些数据类型？本项目各用在哪？

- **一句话**：**String**（缓存/计数）、**Hash**（对象字段）、**List**（消息队列/栈）、**Set**（去重/交集）、**ZSet**（排行榜，有序）、**Stream**（消息队列，本项目核心）。
- **本项目对应**：Redis Stream 做异步任务队列（`resume:analyze:stream` 等）；ZSet 做滑动窗口限流计数；`InterviewSessionCache` 存会话缓存（TTL 24h，键前缀 `interview:session:`）；Redisson 提供分布式锁。
- **面试延伸**：Redis 为什么单线程还快（内存 + IO 多路复用 + 避免锁竞争）；Redis 6/7 多线程只用于网络 IO。

### 8. Redis Stream 是什么？它和普通 List 当队列用有什么区别？

- **一句话**：Stream 是 Redis 5.0 的**原生消息队列**数据类型，支持消费组（group）、手动 ACK、Pending 列表（PEL）、消息认领（XCLAIM）。List 当队列只有 `LPUSH/BRPOP`，**没有 ACK 和消费失败恢复**，消息可能丢。
- **本项目对应**：`AbstractStreamProducer/Consumer` 的 `XADD / XREADGROUP / XACK / XPENDING + XCLAIM` 就是 Stream 全套能力——处理成功才 ACK，失败进 PENDING 超时后 XCLAIM 认领重试。
- **面试延伸**：Stream vs Kafka 的区别（吞吐量级、分区、消息保留）；`XAUTOCLAIM` vs `XCLAIM`。

### 9. 缓存穿透 / 击穿 / 雪崩？

- **一句话**：**穿透**=查不存在的数据每次都打 DB（解决方案：缓存空值、布隆过滤器）；**击穿**=热点 key 过期瞬间大量请求打 DB（互斥锁、逻辑过期）；**雪崩**=大量 key 同时过期（过期时间加随机、多级缓存）。
- **本项目对应**：本项目缓存用在会话/限流，暂无热点数据，但这三问是 Redis 必问八股，面试官常从"为什么用 Redis 缓存"引申到这里。
- **面试延伸**：缓存与 DB 一致性问题（先更新 DB 再删缓存、延迟双删、binlog 订阅）；本地缓存（Caffeine）+ 分布式缓存（Redis）组合。

### 10. pgvector 是什么？（项目专属八股）

- **一句话**：pgvector 是 PostgreSQL 的开源向量检索扩展，`CREATE EXTENSION vector` 后可以在同一张表里存普通列 + 向量列，支持 HNSW/IVFFlat 索引和 COSINE/内积/L2 距离。和业务数据同库同事务是最大优势。

- **本项目对应**：1024 维 + HNSW + COSINE，`text-embedding-v3` 对齐维度；`initialize-schema` 开发环境自动建表。

- **面试延伸**：HNSW vs IVFFlat（HNSW 图索引召回高、构建慢；IVFFlat 聚类快但参数敏感）；向量维度 vs 召回质量 vs 存储成本。

  ​

  **0817看完上面，周二周三18，19，审稿 + 准备华为ai笔试**

---

23看下面

## 6.5 计算机网络 / Web 协议

### 1. HTTP 请求/响应结构、常见状态码？

- **一句话**：请求 = 请求行（方法 + 路径 + 版本）+ 请求头 + 空行 + 请求体；响应 = 状态行 + 响应头 + 空行 + 响应体。常见状态码：**200** 成功、**201** 已创建、**400** 参数错误、**401** 未认证、**403** 无权限、**404** 不存在、**500** 服务器内部错误。
- **本项目对应**：本项目约定**业务错误时 HTTP 仍然返回 200**，前端只看 `Result.code` 判断成败——这是项目自己约定的"业务码"体系（`ErrorCode`）。
- **面试延伸**：RESTful 里 PUT（全量更新） vs PATCH（部分更新）vs POST；为什么推荐语义化状态码而不全是 200。

### 2. GET 和 POST 的区别？

- **一句话**：GET 请求数据（参数在 URL、可缓存、幂等）；POST 提交数据（参数在 body、不可缓存、不保证幂等）。REST 语义上 GET 只读、POST 创建资源。
- **本项目对应**：`GET /api/llm-provider/list`（读，限流 30 次/分）vs `POST /api/llm-provider`（写，限流 5 次/分，`@RequestBody` + `@Valid`）。
- **面试延伸**：POST 一定比 GET 安全吗（用 HTTPS 才谈得上安全）；RESTful 设计规范（资源用名词、动作用 HTTP 方法）。

### 3. 什么是 RESTful？

- **一句话**：REST 是把**资源**作为核心的接口设计风格：URL 只描述资源（名词复数），用 HTTP 方法表达动作（GET 查 / POST 增 / PUT 改 / DELETE 删），配合状态码表达结果。
- **本项目对应**：`/api/resumes/upload`、`/api/interview/sessions`、`/api/knowledge-bases` 都是资源化路径；Controller 里 `@GetMapping/@PostMapping` 按语义使用。
- **面试延伸**：REST vs RPC（gRPC/Dubbo）；无状态性（每次请求独立）；为什么返回统一 `Result` 包一层。

### 4. CORS 跨域是什么？本项目为什么配 `CorsConfig`？

- **一句话**：浏览器同源策略（协议+域名+端口必须一致）限制跨域请求；CORS（跨域资源共享）通过响应头 `Access-Control-Allow-Origin` 允许指定源访问。前后端分离必须处理。
- **本项目对应**：`common/config/CorsConfig.java` 配置跨域——前端 `localhost:5173`，后端 `8080`，端口不同就是跨域。
- **面试延伸**：简单请求 vs 预检请求（`OPTIONS`）；跨域放行 `*` 与 `allowCredentials(true)` 不能共存的安全问题。

### 5. SSE、WebSocket、轮询各自适用场景？

- **一句话**：**轮询**=前端定时发请求，简单但浪费；**SSE**=服务端单向推流，基于 HTTP、自动重连，适合"服务端 → 客户端"单向实时数据（打字机效果）；**WebSocket**=全双工双向实时通信，需要握手升级协议，适合语音/聊天这种双向高频场景。
- **本项目对应**：RAG 问答用 **SSE**（Flux 流式推送，前端逐字渲染）；语音面试用 **WebSocket**（`/ws/voice-interview/{sessionId}` 音频双向）；简历分析结果用**轮询**（前端定时 GET 状态）。
- **面试延伸**：SSE 是单向的、WebSocket 是全双工的；WebSocket 握手流程（`HTTP/1.1 101 Switching Protocols`）；SSE 断线自动重连机制。

### 6. WebSocket 是怎么建立连接的？

- **一句话**：先用普通 HTTP 发 `Upgrade: websocket` 请求，服务端返回 `101 Switching Protocols` 完成**握手**，之后双方在同一个 TCP 连接上收发帧（文本帧/二进制帧）。本项目用 Spring 的 `TextWebSocketHandler` 处理。
- **本项目对应**：`WebSocketConfig` 注册 `/ws/voice-interview/{sessionId}`；`VoiceInterviewWebSocketHandler` 里按消息类型分发（audio / control / text / subtitle）。
- **面试延伸**：WS 的文本帧 vs 二进制帧（本项目音频是 Base64 文本或 PCM 二进制）；心跳/保活机制；断线重连策略。

### 7. JSON 序列化（Jackson）？

- **一句话**：Java 对象 ↔ JSON 的转换由 Jackson 完成。默认 Java Bean 的 getter 对应 JSON 字段；`record` 用访问器名（`id()`）映射字段；`@JsonIgnore` 可排除字段。
- **本项目对应**：`Result<T>` 就是靠 Jackson 序列化成 `{code, message, data}`；`@RequestBody` 反序列化请求、`@ResponseBody` 序列化响应。
- **面试延伸**：LocalDateTime 序列化格式；循环引用（A↔B）用 `@JsonIgnore`/`@JsonManagedReference` 解决；为什么返回给前端的 DTO 不要直接序列化 Entity（懒加载 + 泄漏敏感字段）。

### 8. 本项目用的 TCP/UDP 概念？

- **一句话**：TCP 面向连接、可靠（三次握手、四次挥手、丢包重传），WebSocket/HTTP 都基于 TCP；UDP 无连接、不保证有序可靠但快（视频/语音实时场景可用）。ASR/TTS 流式语音对实时性要求高。
- **本项目对应**：WebSocket 基于 TCP；语音音频流走 WebSocket 帧（底层 TCP 保证不丢帧、有序）。
- **面试延伸**：三次握手为什么是三次（确认双方收发能力）；TCP vs UDP 何时选 UDP（实时音视频、容忍少量丢包）。

---

## 6.6 设计模式（对应本项目代码）

| 模式 | 一句话核心 | 本项目对应代码 | 面试延伸 |
| --- | --- | --- | --- |
| **模板方法** | 基类定义算法骨架，子类实现抽象步骤 | `AbstractStreamConsumer`（基类固定 `consumeLoop`，子类实现 `processBusiness` 等 9 个方法）；`AbstractStreamProducer` | 为什么用"继承 + 抽象方法"而不是接口默认方法；模板方法与策略模式区别 |
| **工厂方法** | 由工厂决定创建哪个对象，调用方不关心细节 | `LlmProviderRegistry.createChatClient` 链式构建；`computeIfAbsent` 懒加载按需创建 | 工厂 vs 直接 new（解耦 + 复用 + 可测试）；Spring 的 `@Bean` 工厂本质 |
| **策略模式** | 定义算法族，运行时替换 | 多供应商 LLM（`LlmProviderRegistry.getChatClientOrDefault(provider)` 按 name 取不同实现）；RAG 查询改写失败兜底原始查询 | 策略 + 注册表（Map<name, 实现>）的组合用法；与 if/else 的区别 |
| **单例模式** | 整个 JVM 只一个实例 | Spring Bean 默认就是单例；`LlmProviderRegistry` 的 `ConcurrentHashMap` 缓存保证一个 provider 一个 ChatClient | 双检锁 + volatile；饿汉 vs 懒汉；Spring 单例为什么线程安全要自己保证（无状态） |
| **建造者（Builder）** | 链式设置多个可选参数，最后 build | Lombok `@Builder` 生成 `ProviderDTO.builder().id(...).baseUrl(...).build()` | Builder vs 构造器（参数多、可读性）；record 与 Builder 的取舍 |
| **观察者模式** | 事件发生时通知订阅者，不阻塞主体 | ASR 回调（`onFinal`/`onPartial`/`onReady` 注册的函数在结果到达时被调用） | 回调 vs 观察者；Spring `ApplicationEvent`/`@EventListener`；本项目 `TransactionSynchronization` 后置回调 |
| **责任链** | 请求依次经过多个处理器，每个可处理或放行 | Spring AI **Advisor 链**：SafeGuard → Memory → Logger → ToolCalling | AOP 的拦截器链本质；过滤器和拦截器的顺序 |
| **外观模式（门面）** | 对外暴露简单接口，隐藏内部子系统复杂度 | Controller 只调 Service 一个门面方法，Service 内部编排多个组件（本项目 `ResumeUploadService` 编排存储/解析/哈希/发消息） | 门面 vs 中间层；为什么 Controller 不写业务逻辑 |

---

**0824看完上面，**



**补充agent知识，项目**

**下面的部分结合简历对照着有针对性地看**

# 第七部分：技术选型与设计决策（面试核心弹药）

## 1. 为什么用 Redis Stream 做异步任务，而不是 MQ？

- 不需要引入额外中间件，复用 Redis 基础设施（Redisson 已接入）。
- `AbstractStreamProducer` / `AbstractStreamConsumer` 模板方法模式，实现 `XADD / XREADGROUP / XACK / XPENDING + XCLAIM`：
  - 消费组（group）保证同一条消息只被一个消费者处理；
  - **手动 ACK**：处理成功才 XACK，失败不 ACK 会进 PENDING，超过 `PENDING_IDLE_TIMEOUT_MS=5min` 可被 XCLAIM 认领重试；
  - `MAX_RETRY_COUNT=3`、`STREAM_MAX_LEN=1000` 控制消息保留。
- 优势：**无 MQ 的学习与运维成本**、消费进度落 Redis 天然可恢复、适合"异步 + 至少一次"语义。
- 面试延伸：Redis Stream 与 RabbitMQ/Kafka 的取舍（吞吐 vs 简单性）；XCLAIM 与 PEL（Pending Entries List）。

## 2. 为什么用 pgvector 而不是独立向量库？

- 项目数据量级（个人/团队知识库文档）用独立 Milvus/Weaviate 是过度设计；
- pgvector 让**向量检索和业务数据在同一事务里**，减少跨系统一致性问题；
- 1024 维 + HNSW + COSINE 对当前规模完全够用；`initialize-schema` 仅开发环境开启，生产手动管理 schema。
- 面试延伸：HNSW vs IVFFlat；COSINE 距离 vs 内积；维度对存储与召回的影响。

## 3. 为什么 LLM 结构化输出要自己包一层 `StructuredOutputInvoker`？

- LLM 直接输出 JSON 不稳定（多字、markdown 包裹、缺字段），直接把失败抛给用户体验极差。
- 实现：`BeanOutputConverter` 把类结构注入提示词 → 解析失败**业务层重试**（`structured-max-attempts=2`）→ 重试时把上次错误信息注入提示词让模型"定向修复"（`structured-retry-use-repair-prompt`）→ 追加严格 JSON 指令 → 输出长度裁剪防污染。
- 结果：JSON 解析成功率从裸调用的约 70% 提升到 99%+；同时用 Micrometer 记录 invocations/attempts/latency 指标。
- 面试延伸：为什么 Spring AI 自带 retry 不重试了（`max-attempts=1`）——因为默认重试对"模型输出格式错误"无效，必须带反馈信息重试。

## 4. 多供应商 LLM 注册表设计

- 业务代码不写死模型，统一 `LlmProviderRegistry.getChatClientOrDefault(provider)`；
- `ProviderProperties` 驱动动态构建 `ChatClient`（含 base-url、apiKey、model、temperature）；
- embedding 能力按供应商开关（`supports-embedding`），无 embedding 的供应商只服务对话。
- 面试延伸：插件式/策略式架构、可测试性（Mock 掉 Registry 即可单测业务）。

## 5. 面试评估统一抽象 `UnifiedEvaluationService`

- 文字面试与语音面试**共用一套评估**：分批评估（`batch-size=8`）+ BeanOutputConverter 结构化输出 + 二次汇总 + 降级兜底。
- 好处：两条业务线评估口径一致，避免逻辑重复。
- 面试延伸：批量调 LLM 的 Token 与延迟权衡；结构化 schema 设计（评估维度先定义好再让模型填）。

## 6. 限流：可重复 `@RateLimit` + Redis Lua 滑动窗口

- 注解式、可重复（一个接口可配多个限流规则），切面 `RateLimitAspect` 统一拦截；
- 用 Lua 脚本保证"取窗口计数 + 判断 + 自增"的原子性（避免竞态），脚本 `scripts/rate_limit_single.lua`；
- 面试延伸：滑动窗口 vs 固定窗口 vs 令牌桶；Lua 为什么能保证原子（Redis 单线程执行脚本）。

## 7. 事务边界设计

- `@Transactional` 只在 Service 层，范围最小化；
- `TransactionalExecutor`（REQUIRES_NEW）用于需要独立事务的片段；
- **铁律：LLM、S3、外部 HTTP 调用不得放在数据库事务内**（网络 IO 长占事务连接是灾难）；所以 AI 分析全部走 Redis Stream 异步，写库动作本身很快。
- 语音面试用红锁/`TransactionSynchronization` 后置回调，保证"DB 提交成功后再发消息/状态推进"。
- 面试延伸：为什么同类内部调用 `@Transactional` 不生效（代理自调用）；`REQUIRES_NEW` 何时用。

## 8. API Key 安全

- AES/GCM/NoPadding 加密（GCM 提供认证加密），12 字节随机 nonce、128 位 tag；
- 加密密钥来自 `.env`，生产 `require-encryption-key=true`，开发才允许 fallback；
- 运行时可写配置落 `~/.interview-guide/llm-providers.yml`，避免密钥进 Git/Jar。
- 面试延伸：对称加密 vs 非对称；nonce 不可重用（GCM 致命缺陷）；密钥管理（KMS/HSM）。

## 9. 文件上传与解析链路

- Multipart 上限 50MB（知识库），简历 10MB 业务层校验；
- 白名单校验 → 哈希去重 → S3 落盘 → Tika 解析（防嵌套资源、限 5MB 文本、PDF 按坐标排序）；
- 面试延伸：为什么哈希去重（省 AI 调用钱）；Tika vs pdfbox/poi 直接解析；OSS/S3 预签名直传。

## 10. 前端关键点

- API 集中在 `src/api/` 复用 Axios 实例（`request.ts`，含 token/错误拦截）；类型放 `src/types/`；
- SSE 用 fetch 流式读取（RAG 问答逐字渲染）；语音面试走 WebSocket（音频分块 + 实时字幕）；
- 路由常量收敛在 `constants/routes.ts`；复用组件 `components/`（UnifiedInterviewModal、InterviewDetailPanel）。
- 面试延伸：SSE vs WebSocket vs 轮询的适用场景（单向推送 vs 双向实时 vs 低频状态）。

## 11. 关键技术决策速查

| 决策 | 原因 |
|------|------|
| PostgreSQL + pgvector 而不是 PG + Milvus | pgvector 够用，减少组件 |
| Redis Stream 而不是 Kafka | 项目规模不需要 Kafka，减少运维负担 |
| 统一评估引擎（文字/语音共用） | 避免两套打分逻辑，评估结果可对比 |
| Skill 定义用 Markdown 文件而不是数据库 | 可以版本管理，方便社区贡献 |
| API 返回 HTTP 200 + 业务错误码 | 异常也走统一 `Result` 格式，前端处理简单 |
| LLM 调用不在事务内 | 避免长事务占用数据库连接 |

---

# 第八部分：核心技术机制深入

## 7.1 LlmProviderRegistry 关键设计与实现细节

### 1. 缓存策略 —— 为什么用 `ConcurrentHashMap`？

```java
private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
```

- **线程安全**：允许多线程并发 `computeIfAbsent`，保证同一 provider 只创建一次，避免重复构建。
- **高性能**：读多写少，使用分段锁，比 `synchronized` 更高效。
- **扩展性**：未来若需更复杂的缓存淘汰策略（如 LRU），可替换为 `Caffeine`，但当前规模下足够。

**缓存 Key 设计：**

- 普通 Client → `providerId`
- Plain Client → `providerId + ":plain"`
- Voice Client → `providerId + ":voice"`

三个场景共享同一个底层 `OpenAiChatModel`，但拥有不同的 Advisor 组合。

### 2. 工厂方法 + 懒加载

- 所有 `getXxx()` 方法都调用 `computeIfAbsent`，按需创建。
- `createChatClient` → `getChatModel` → `buildChatModel`，形成链式创建。
- 首次调用时才初始化，启动速度快，且未启用的 provider 不会被实例化。

### 3. Provider 配置加载 —— 双数据源策略

`loadProviderOrThrow` 方法：
- 若 `providerRepository` 存在（即启用了数据库配置），优先从 DB 读取 `LlmProviderEntity`，校验 `isEnabled`。
- 否则从 `properties` 的 `providers` Map 中读取静态配置。
- 统一封装为 `ProviderSnapshot` 记录（Record），便于后续构建客户端。

兼顾**运维灵活性**（数据库动态生效）和**开发便捷性**（配置文件快速启动）。

### 4. 构建 OpenAI 兼容客户端

`buildChatModel` 使用 `ApiPathResolver.buildOpenAiClient(baseUrl, apiKey)` 构造 `OpenAIClient`（底层基于 OkHttp），然后通过 `OpenAiChatModel.builder()` 注入 client、options（temperature、model）和观测注册表。

**注意**：所有第三方模型（DashScope、Kimi 等）均以 OpenAI 兼容接口对接，这是 Spring AI 的通用做法。

### 5. 三种 ChatClient 变体

| 类型 | 使用场景 | 特点 |
| ------------ | ----------------------- | ------------------------------------------------------------ |
| 默认 Client | 普通聊天、Agent 任务 | 带 SkillsTool + 全部 Advisor（Memory、ToolCall、Safeguard、Logger） |
| Plain Client | 结构化输出（出题/评分） | 仅带 Safeguard，无 Tools，避免工具调用消息混入 JSON 解析失败 |
| Voice Client | 语音面试（流式） | 带 SkillsTool + ToolCall（流式），无 Memory Advisor（由前端管理历史） |

### 6. Advisor 机制 —— 核心增强点

- **MessageChatMemoryAdvisor**：基于内存窗口保存对话历史，实现多轮上下文。
- **ToolCallingAdvisor**：处理工具调用，支持流式（`conversationHistoryEnabled` 控制是否将工具消息加入历史）。
- **SimpleLoggerAdvisor**：打印请求/响应日志，便于调试。
- **SafeGuardAdvisor**：敏感词过滤，拦截非法输入并返回固定回复。

所有 Advisor 通过配置文件 (`LlmProviderProperties.AdvisorConfig`) 控制开关，实现可插拔。

### 7. Embedding 模型校验与兜底

- `createEmbeddingModel` 会检查 `supportsEmbedding` 及 `embeddingModel` 是否为空。
- 若 `embeddingModel` 看起来像聊天模型（如 `glm-4`），抛出明确异常并**推荐正确的 Embedding 模型名**（通过 `RECOMMENDED_EMBEDDING_MODELS` 映射），帮助运维人员快速纠错。
- 维度设置：优先取 provider 配置的 `embeddingDimensions`，否则取全局默认值。

### 8. 默认 Provider 解析

`resolveDefaultChatProviderId` 先从 `LlmGlobalSettingEntity`（数据库全局配置）读取，若未设置则 fallback 到 `properties.getDefaultProvider()`。同理 `Embedding` 也有独立的默认配置。为多租户或不同场景指定不同默认模型提供了弹性。

### 9. 重新加载与清除缓存

`reload()` 方法清空所有缓存，用于管理员更新模型配置后**热生效**，无需重启应用。

### 10. 工具回调（ToolCallback）注入

`interviewSkillsToolCallback` 通过 `@Qualifier` 注入，若存在则自动加到默认 Client 和 Voice Client 中。这是 Spring AI 提供的函数调用能力，用于执行"技能查询"等自定义操作。

## 7.2 llmprovider 模块请求全链路（以代码实例学三层架构）

> 记忆口诀：**Controller 是门（接待请求、送走响应），Service 是脑（业务决策、数据转换），Repository 是手（操作数据库）。** 后面其他 5 个模块无论多复杂，骨架都是这套模式。

### 读操作：`GET /api/llm-provider/list`

```
GET /api/llm-provider/list
   │
   ▼
Controller.listProviders()   ← 只做路由 + @RateLimit(30/分钟)
   │  configService.listProviders()
   ▼
Service.listProviders()      ← 加读锁(rwLock.readLock) → 查全局设置 → 查库
   │                          → Entity 转 DTO → 解密+脱敏 API Key
   ▼
Repository.findAll()         ← Spring Data JPA 生成 SELECT * FROM llm_provider_config
   │
   ▼
PostgreSQL → 2 行数据 → JPA 映射为 Entity 列表
   │
   ▼
Service → 解密 Key → 脱敏(sk-***123) → ProviderDTO(defaultChatProvider=true)
   │
   ▼
Controller → Result.success(data) → {code:200, message:"success", data:[...]}
```

**Controller 只做路由和委托**：`listProviders()` 就一行 `return Result.success(configService.listProviders())`。

**`Result<T>` 统一响应信封：**

```java
{
  "code": 200,      // 200=成功；业务出错时 HTTP 仍返回 200，但 code 不是 200
  "message": "success",
  "data": [ { "id": "dashscope", "maskedApiKey": "sk-***xyz", ... } ]
}
```

**Repository 的魔法：**

```java
public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, String> {
  List<LlmProviderEntity> findByEnabledTrueOrderByIdAsc();  // 方法名派生查询
}
```

没有实现类——Spring 用 JDK 动态代理在运行时生成实现，`findByEnabledTrueOrderByIdAsc()` 自动翻译成 `SELECT * FROM llm_provider_config WHERE enabled = true ORDER BY id ASC`。

**Entity 关键点：**

```java
@Id private String id;                    // 自然主键（provider 名称本身）
@Column(name = "api_key_ciphertext") private String apiKeyCiphertext;  // 绝不存明文
@Column(name = "api_key_nonce") private String apiKeyNonce;
@PrePersist void prePersist() { ... }     // JPA 生命周期回调，自动填时间戳
```

### 写操作：`POST /api/llm-provider`

| 维度 | 读（listProviders） | 写（createProvider） |
| ------------- | ---------------------------------- | ------------------------------- |
| **HTTP 方法** | GET | POST |
| **参数校验** | 无入参 | `@Valid` + `@NotBlank` |
| **限流** | 30 次/分钟 | 5 次/分钟 |
| **事务** | 无需 `@Transactional` | `@Transactional` 保证原子性 |
| **锁** | `readLock`（共享，高并发） | `writeLock`（排他，防并发冲突） |
| **查重** | 不需要 | `existsById()` |
| **加密** | 解密+脱敏（输出方向） | 加密（输入方向） |
| **注册表** | 不刷新 | `registry.reload()` |
| **返回值** | `Result<List<ProviderDTO>>` 有数据 | `Result<Void>` 无数据 |

**Service 写操作流水线（每一步都可能失败，都有防御性校验）：**

```
1. @Transactional 开事务
2. rwLock.writeLock().lock()        ← 排他锁，防止并发重复创建
3. existsById(id) 查重              ← 自然主键，存在即抛 PROVIDER_ALREADY_EXISTS
4. requireNonBlank(...) 二次校验    ← 防御性编程：Controller @Valid 只保护入参，
                                       其他调用方（定时任务/消费者）靠这层兜底
5. validateEmbeddingConfig(...)     ← 填了 embeddingModel 就自动认为支持 embedding
6. encryptionService.encrypt(key)   ← AES/GCM/NoPadding，12 字节随机 nonce + 128 位 tag
7. repository.save(entity)          ← 密文入库（@PrePersist 自动填时间戳）
8. 事务提交
9. registry.reload()                ← 刷新内存注册表（放事务外！）
10. finally { writeLock().unlock() } ← 铁律：无论成功还是异常都会执行
```

**三个关键设计原则：**

| 原则 | 在功能里怎么体现的 |
| --------------------------- | ------------------------------------------------------------ |
| **Controller 不写业务逻辑** | `listProviders()` 就一行委托代码 |
| **Entity 不直接返回前端** | 返回的是 `ProviderDTO`——API Key 被脱敏，还多了 `defaultChatProvider` 计算字段 |
| **Repository 只管数据访问** | 接口只定义方法签名，业务逻辑留给 Service |

> **读操作是"数据从库到前端"的单向管道，写操作是"校验 → 转换 → 加密 → 落库 → 刷新"的多阶段流水线——事务和锁保证这些步骤的原子性和隔离性。**

## 7.3 异步任务处理模式（以 resume 模块为例）

### 为什么需要异步

简历上传后需要调用 LLM 做 AI 分析，是**耗时操作**（几秒到几十秒）。如果同步等待，HTTP 请求会超时。

```
上传请求 → 保存DB(PENDING) → 发消息到Stream → 立即返回"已收到"
                                              ↓
                     后台消费线程 → 调用LLM → 更新DB(COMPLETED/FAILED)
                                              ↓
                             前端轮询 /api/resumes/{id}/detail 查看状态
```

### 三大核心角色

| 角色 | 文件 | 职责 |
| ---------- | -------------------------------------------- | ---------------------------------------- |
| **生产者** | `AnalyzeStreamProducer.java` | 把任务包装成消息，推入 Redis Stream |
| **消费者** | `AnalyzeStreamConsumer.java` | 从 Stream 拉取消息，执行真正的 AI 分析 |
| **编排者** | `ResumeUploadService.java` | 串联上传→入库→发消息，协调同步与异步边界 |

### 生产者：如何"发任务"

继承链：`AnalyzeStreamProducer` → `AbstractStreamProducer<T>`。需实现 5 个抽象方法：

```java
String streamKey()              → "resume:analyze:stream"
Map<String, String> buildMessage(payload) → {resumeId, content, retryCount}
taskDisplayName() / payloadIdentifier() / onSendFailed()
```

`sendTask()` 内部调用 `redisService.streamAdd()`，写入失败则回调 `onSendFailed()` 把 DB 状态标记为 FAILED。

### 消费者：如何"处理任务"（模板方法模式经典应用）

继承链：`AnalyzeStreamConsumer` → `AbstractStreamConsumer<T>`。基类固定骨架，子类填 9+ 个抽象方法：

```
consumeLoop()  ←──────────────── 基类固定骨架
  ├── parsePayload()           ← 子类：反序列化消息
  ├── shouldSkip()             ← 子类：是否跳过（已完成/已删除）
  ├── markProcessing()         ← 子类：更新状态为 PROCESSING
  ├── processBusiness()        ← 子类：★ 核心业务逻辑（调用 LLM）
  ├── markCompleted()          ← 子类：更新状态为 COMPLETED
  ├── markFailed()             ← 子类：更新状态为 FAILED
  └── retryMessage()           ← 子类：重新入队
```

**消息处理完整流程（AbstractStreamConsumer.java:160-203）：**

```
收到消息
  ├── parsePayload() 失败 → ACK 丢弃（避免阻塞队列）
  ├── payload == null  → ACK 丢弃
  ├── shouldSkip() == true → ACK 丢弃（已完成/已删除）
  ├── markProcessing() → 更新 DB 为 PROCESSING
  ├── processBusiness() → ★ 调用 ResumeGradingService.analyzeResume()
  │     ├── 成功 → markCompleted() → ACK
  │     └── 失败 → retryCount < 3 → retryMessage() 重新入队 → ACK
  │              → retryCount >= 3 → markFailed() → ACK
  └── 最终一定会 ACK（防止 Pending 队列堆积）
```

### 可靠性保障机制

1. **Pending 消息恢复**：消费者崩溃后，未 ACK 的消息留在 Pending 列表。新消息通过 `XREADGROUP` 读取，超时 Pending 消息（`PENDING_IDLE_TIMEOUT_MS=5min`）通过 `XCLAIM` 认领恢复（`PENDING_CLAIM_BATCH_SIZE=10`）。
2. **重试机制**：最多重试 3 次（`MAX_RETRY_COUNT=3`），`retryCount` 递增作为消息字段；超过后标记 FAILED。
3. **防重复处理**：`shouldSkip()` 检查已 COMPLETED 的跳过、已删除的 ACK 丢弃。
4. **发送失败补偿**：消息入队失败时，`onSendFailed` 通过独立事务（`REQUIRES_NEW`）将 DB 状态标记 FAILED，确保状态不被主事务回滚。

### 状态机设计

```
PENDING ──→ PROCESSING ──→ COMPLETED
                 │
                 └──→ FAILED (重试3次后)

重新分析：FAILED/COMPLETED → PENDING → PROCESSING → ...
```

### 关键设计原则

1. **模板方法模式**：基类定义处理骨架，子类只填业务逻辑，避免重复代码
2. **事务边界清晰**：LLM/S3/外部调用绝不能放在 `@Transactional` 内
3. **消息一定会 ACK**：无论成功/失败/跳过，最终都要确认消息，防止 Pending 堆积
4. **异步前先落库**：任务入队前 DB 已保存，消费者只负责更新状态
5. **实体存在性校验**：消费者处理前检查实体是否仍存在，避免操作已删除数据

## 7.4 生产者/消费者运行时位置与生命周期

### 1. 生产者和消费者在项目中的位置？

各业务模块的 `listener/` 目录下，基类在公共层：

| 模块 | 生产者 (Producer) | 消费者 (Consumer) |
| ---------------------------------- | ----------------------------- | ----------------------------- |
| `modules/interview/listener/` | `EvaluateStreamProducer` | `EvaluateStreamConsumer` |
| `modules/resume/listener/` | `AnalyzeStreamProducer` | `AnalyzeStreamConsumer` |
| `modules/knowledgebase/listener/` | `VectorizeStreamProducer` | `VectorizeStreamConsumer` |
| `modules/voiceinterview/listener/` | `VoiceEvaluateStreamProducer` | `VoiceEvaluateStreamConsumer` |

基类：`common/async/AbstractStreamProducer.java`（生产者模板）、`common/async/AbstractStreamConsumer.java`（消费者模板，含生命周期管理）、`common/constant/AsyncTaskStreamConstants.java`（所有 Stream Key、Consumer Group 常量）、`infrastructure/redis/RedisService.java`（底层 Redisson 操作 XADD、XREADGROUP、XAUTOCLAIM、XACK）。

### 2. 启动后就在后台一直监听吗？

**是的。** 4 个 Consumer 都是 `@Component`，从基类继承了 `@PostConstruct void init()`，**Spring 容器启动后消费者立刻自动启动**：

1. 创建 **单线程 daemon 线程池**（`ThreadPoolExecutor(1, 1, LinkedBlockingQueue)`）
2. 创建 Consumer Group（`XGROUP CREATE ... MKSTREAM`，已存在则忽略）
3. 进入 **无限循环** `while(running)`：
   - 先用 **XAUTOCLAIM** 认领超时的 PENDING 消息（idle > 5 分钟）
   - 再用 **XREADGROUP BLOCK 1000ms** 阻塞等待新消息
   - 处理消息：解析 → 跳过检查 → 业务处理 → ACK
   - 失败时重试最多 3 次，超过则标记 FAILED 并 ACK

关闭时通过 `@PreDestroy shutdown()` 优雅停止。

**生产者不自动运行**——没有生命周期注解，只被动被 Service 层调用：

- 用户上传简历 → `ResumeUploadService` → `AnalyzeStreamProducer.sendAnalyzeTask()`
- 用户上传知识库 → `KnowledgeBaseUploadService` → `VectorizeStreamProducer.sendVectorizeTask()`
- 面试答题完毕 → `InterviewSessionService` → `EvaluateStreamProducer.sendEvaluateTask()`
- 语音面试结束 → `VoiceInterviewService` → `VoiceEvaluateStreamProducer.sendEvaluateTask()`

### 3. 各个模块的 Listener 同时运行吗？

**全部并行运行：**

```
Spring Boot 启动
  │
  ├─ 线程 [vectorize-consumer-xxxx]  ── 轮询 knowledgebase:vectorize:stream
  ├─ 线程 [analyze-consumer-xxxx]    ── 轮询 resume:analyze:stream
  ├─ 线程 [evaluate-consumer-xxxx]   ── 轮询 interview:evaluate:stream
  └─ 线程 [voice-evaluate-consumer-xxxx] ── 轮询 voice:evaluate:stream
```

- 每个 Consumer 独占一个线程，4 个 Stream 完全隔离、互不干扰
- 所有线程都是 **daemon 线程**（JVM 关闭时不等它们）
- 使用 **4 个不同的 Redis Stream Key** 和 **4 个不同的 Consumer Group**，同时运行也不冲突
- 单 Stream 内**严格串行消费**（保证消息顺序），跨 Stream **并行**

## 7.5 语音面试模块深入（WebSocket / TTS / ASR / 流式评估 / 会话生命周期）

### 一、如何学习：看三条文件路径

| 层次 | 路径 | 内容 |
| ------------ | ------------------------------------------------------------ | ---------------------------------------- |
| **人机交互** | `voiceinterview/handler/VoiceInterviewWebSocketHandler.java` | WebSocket 双向通信、实时字幕、音频收发 |
| **业务逻辑** | `voiceinterview/service/VoiceInterviewService.java` | 会话生命周期、阶段切换、消息持久化 |
| **AI 服务** | `voiceinterview/service/QwenAsrService.java` `QwenTtsService.java` `DashscopeLlmService.java` | ASR 语音识别、TTS 语音合成、LLM 对话生成 |

```
用户音频 → ASR（QwenAsrService）→ STT 回调 → LLM（DashscopeLlmService）→ TTS（QwenTtsService）→ AI 音频下发
```

### 二、WebSocket 在哪里体现？（三处关键代码）

1. **注册端点** — `WebSocketConfig.java:24`
   ```java
   registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
   ```
   前端 `new WebSocket("ws://localhost:8080/ws/voice-interview/123")` 连接。

2. **连接建立** — `VoiceInterviewWebSocketHandler.java:143`
   ```java
   afterConnectionEstablished() {
       sessions.put(sessionId, safeSession);        // 维护连接池
       sessionStates.put(sessionId, new SessionState()); // 初始化会话状态
       startDashScopeStt(sessionId, safeSession);   // 启动 ASR 识别
       sendMessage(safeSession, createWelcomeMessage()); // 发送欢迎消息
       triggerOpeningQuestionIfNeeded(sessionId, safeSession); // 自动开场
   }
   ```

3. **消息类型处理** — `VoiceInterviewWebSocketHandler.java:303-346`
   - **`type: "audio"`** — 前端发来的用户录音（Base64 PCM），转给 ASR 服务
   - **`type: "control"`** — 控制消息：`submit`（提交当前文本给 LLM）、`end_interview`（结束面试）、`start_phase`（切换阶段）
   - **后端下发**：`text`（AI 回复文本）、`subtitle`（实时字幕）、`audio`（TTS 语音）、`control`（状态通知）

   连接断开时自动结束会话（`afterConnectionClosed` → `endSessionIfInProgress`），还有 5 分钟超时自动暂停（`checkPauseTimeout`）。

### 三、TTS 和 ASR 如何体现？

**ASR（语音识别）→ `QwenAsrService.java`** — 阿里云 DashScope `qwen3-asr-flash-realtime` 模型，WebSocket 双向传输：

```java
sttService.startTranscription(
    sessionId,
    text -> handleSttResult(sessionId, text, true),   // onFinal: 最终定稿文本
    text -> handleSttResult(sessionId, text, false),  // onPartial: 实时部分文本（字幕）
    () -> sendAsrReady(session),                      // onReady: ASR 就绪通知
    error -> sendError(session, "...")                // onError
);
sttService.sendAudio(sessionId, audioData);  // PCM, 16kHz
// turnDetectionType = "server_vad", turnDetectionSilenceDurationMs = 1000
```

ASR 回调用到了 **回调函数 (Consumer) 模式**：ASR 底层有自己的 WebSocket 线程，结果出来时调用你注册的函数——不在当前线程等待结果。这就是**异步回调**的体现。

**TTS（语音合成）→ `QwenTtsService.java`** — 阿里云 DashScope `qwen3-tts-flash-realtime` 模型：

```java
byte[] pcm = ttsService.synthesize("你好，请介绍一下你自己");
// 内部流程：建 WebSocket → send text → 收 audio.delta 事件 → 拼成完整 PCM → 返回
```

两种模式：
- **流式模式** (`chunkedAudioEnabled=true`)：LLM 边说边合成，每句独立 TTS 并发执行（`OrderedTtsChunkEmitter`），不等全部说完
- **合并模式** (`chunkedAudioEnabled=false`)：LLM 全部返回后统一合成

### 四、流式评估在哪里？

**评估不是流式的，而是异步离线评估。** 流式体现在 LLM 对话生成。

**LLM 流式输出 → `DashscopeLlmService.java:69-155` 的 `chatStreamSentences()`：**

```java
chatClient.prompt()
    .system(promptContext.systemPrompt())
    .user(promptContext.userPrompt())
    .stream()        // ← Spring AI 的流式调用
    .content()
    .doOnNext(token -> {
        // 每收到一个 token：
        // 1. 检测句子边界（遇到。！？）→ 回调 onSentence，触发并发 TTS
        // 2. 间隔推送实时文本给前端（onToken）
    })
    .blockLast();    // 阻塞等待流结束
```

**评估则是异步批处理：**

```java
// 会话结束时：Producer 发送任务到 Redis Stream
endSession() → sendEvaluateTaskAfterCommit() → voiceEvaluateStreamProducer.sendEvaluateTask(sessionId)
// Consumer 在后台消费
VoiceEvaluateStreamConsumer.processBusiness() → evaluationService.generateEvaluation(sessionId)
```

### 五、异步回调是什么？

1. **ASR 回调（函数式接口，不阻塞当前线程）**：把 `onFinal` / `onPartial` / `onReady` / `onError` 四个函数传进去，ASR 服务在结果到达时调用，不需要 `while(true)` 轮询。
2. **CompletableFuture 异步编排（TTS 并发）**：

```java
CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
    return ttsService.synthesize(sentence);  // 在线程池中跑
}, voicePipelineExecutor);
ttsFutures.add(future);
byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
```

多个句子的 TTS 并发执行（`supplyAsync`），CPU 不会空等一个句子合成完才开始下一个。

### 六、会话生命周期管理（本质是一个状态机）

```
                   创建会话
                      ↓
                  IN_PROGRESS ←────────────────┐
                   /    |    \                 │
          用户主动结束  超时    WS 异常断开       │
           endSession  pauseSession  endSessionIfInProgress
              ↓         ↓              ↓        │
          COMPLETED    PAUSED       COMPLETED   │
                         │                      │
                     用户恢复                   │
                     resumeSession              │
                         └──────────────────────┘
```

`VoiceInterviewSessionStatus`：`IN_PROGRESS` → `PAUSED` / `COMPLETED` / `FAILED`。

| 生命周期方法 | 做了什么 |
| -------------------------- | ------------------------------------------------------------ |
| `createSession()` | 创建 Entity + 写入 DB + 缓存 Redis + 返回含 WebSocket URL 的 DTO |
| `endSession()` | 设 endTime + status=COMPLETED + 触发异步评估（发送到 Redis Stream） |
| `endSessionIfInProgress()` | WebSocket 异常断开时兜底，只对 IN_PROGRESS 的会话生效 |
| `pauseSession()` | status=PAUSED + 记录 pausedAt |
| `resumeSession()` | status=IN_PROGRESS + 记录 resumedAt + 返回 WebSocket URL |
| `cleanupStaleSessions()` | 定时任务，清理超过 2 小时的僵死 IN_PROGRESS 会话 + 卡在 PROCESSING 超过 30 分钟的评估 |

### 七、一条完整的语音面试链路

```
1. POST /api/voice-interview/sessions  → 创建会话，返回 WebSocket URL
2. 前端连接 ws://host/ws/voice-interview/{sessionId}
3. 服务端启动 ASR（QwenAsrService），发送欢迎消息 + 开场问题
4. 前端录音 → Base64 → WebSocket type=audio
5. 服务端 handleUserAudio → ASR sendAudio → VAD 自动断句
6. ASR 回调 onFinal → 合并到 mergeBuffer → 实时字幕
7. 前端点"提交" → type=control, action=submit → flushMergedUtteranceToLlm
8. LLM 流式生成（DashscopeLlmService.chatStreamSentences）
   → onToken: 实时推送文本到前端
   → onSentence: 每句触发并发 TTS
9. TTS 合成完毕 → sendAudio（PCM→WAV→Base64→WebSocket）
10. 重复 4-9...
11. 前端 type=control, action=end_interview
12. endSession → COMPLETED → 发评估任务到 Redis Stream
13. VoiceEvaluateStreamConsumer 消费 → evaluationService.generateEvaluation()
14. 评估结果落库 → 前端查询展示
```

## 7.6 上下文窗口如何处理？

项目**没有统一的上下文窗口管理策略**，不同场景采用分散的、各不相同的处理方式：

### 1. Spring AI 自动记忆管理（默认关闭）

`LlmProviderRegistry.java:337-346` 使用 `MessageWindowChatMemory`：

```java
MessageWindowChatMemory.builder()
    .maxMessages(maxMessages)  // 默认 120
    .build()
```

**默认关闭**（`application.yml` 中 `message-chat-memory-enabled: false`），原因是"避免会话串扰"。

### 2. 语音面试 — 全量历史，无截断（风险最大的场景）

- `VoiceInterviewWebSocketHandler.getHistory()` 从数据库加载**全部**对话历史，不做任何截断
- `DashscopeLlmService.buildPromptContext()` 把所有历史拼进 prompt 的`【之前的对话】`部分
- 唯一的"截断"在**输出端**：`optimizeForVoice()` 将 AI 回复截断到 ~120 字符（语音播报不需要太长）

### 3. RAG 知识库问答 — 有明确限制

```java
KnowledgeBaseQueryProperties.java:
History:
  enabled: true
  maxMessages: 10    // 最多加载 10 条历史消息
```

- `RagChatSessionService.loadHistoryMessages()` 通过 DB 分页查询限制历史条数
- 查询改写时，助手消息进一步截断到 **200 字符**（`MAX_REWRITE_HISTORY_CHAR = 200`）

### 4. 面试评估 — 字符级硬截断

`UnifiedEvaluationService.java` 中的处理：

| 内容 | 截断阈值 | 估算 Token |
| -------- | --------- | ---------- |
| 简历内容 | 3000 字符 | ~1500-2000 |
| 参考基线 | 6000 字符 | — |

这是项目中**唯一出现 token 估算注释**的地方（`// 约 1500~2000 tokens`），用的是粗略的 1.5-2 字符/token 比例。

### 5. 其他小规模截断

- **Provider 连通性测试**：`max_tokens: 1`，最小化成本
- **结构化输出错误信息**：截断到 200 字符
- **PromptSanitizer**：用随机的 `<data-boundary>` 标签包裹用户数据，防止注入（安全边界，非截断）

### 总结：缺失的能力与风险

| 缺失项 | 风险 |
| ------------------------------------------- | -------------------- |
| 统一的 Token 计数服务 | 无法准确知道当前用量 |
| 语音面试历史截断/摘要 | 长对话会超出模型窗口 |
| 滑动窗口或上下文压缩 | 对话无限增长 |
| `max_tokens` / `max_completion_tokens` 配置 | 无法限制输出长度 |

> 最值得关注的是**语音面试场景**：全量加载对话历史且不做截断，随着对话轮次增加，最终一定会超出模型的上下文窗口上限。建议至少增加"最近 N 轮"的滑动窗口，或历史过长时触发摘要压缩。（todo: 可以改进）

## 7.7 RAG 在本项目具体怎么用？

### 整体架构

```
文件上传 → Tika 解析 → 文本清洗 → TokenTextSplitter 分块
    → DashScope text-embedding-v3 向量化(1024维)
    → pgvector (HNSW + COSINE) 存储
    → 用户提问 → 查询改写 → 向量相似度检索 → prompt 拼装 → LLM 生成 → SSE 流式输出
```

### 一、文档摄入（Ingestion）

**上传入口**：`KnowledgeBaseUploadService.uploadKnowledgeBase()`

1. **校验**：文件大小 ≤50MB，类型限 PDF/DOCX/DOC/TXT/MD
2. **去重**：SHA-256 哈希，相同文件直接返回已有记录
3. **解析**：Apache Tika `AutoDetectParser`，关闭内嵌图片提取，PDF 按坐标排序文本
4. **清洗**：`TextCleaningService` 两阶段处理——语义去噪（移除控制字符、图片文件名、图片 URL、`file://` URI、分隔线）+ 格式归一（统一换行、压缩连续空行）
5. **存储**：上传至 S3/MinIO，路径 `knowledgebases/yyyy/MM/dd/uuid_safename`
6. **异步向量化**：通过 Redis Stream 发送任务到 `knowledgebase:vectorize:stream`

### 二、分块策略（Chunking）

`KnowledgeBaseVectorService:52`：

```java
this.textSplitter = TokenTextSplitter.builder().build();  // 全部默认参数
```

- 每块约 **800 tokens**
- **无重叠**（`DEFAULT_MIN_CHUNK_OVERLAP = 0`）
- 按段落/换行边界切分

### 三、向量化（Embedding）

| 项目 | 配置 |
| ------ | ---------------------------------------------- |
| 模型 | 阿里云 DashScope `text-embedding-v3` |
| 维度 | **1024** |
| 距离 | **COSINE** |
| 索引 | **HNSW** |
| 批处理 | 每次最多 **10** 个 chunk（DashScope API 限制） |

向量化采用**两阶段提交**模式：先用临时 `kb_id` 写入，全部成功后再原子性地替换为正式 ID，失败则回滚清理。

### 四、检索（Retrieval）

**动态 Top-K 策略**（根据用户问题长度自适应）：

| 问题长度 | topK | 相似度阈值 |
| --------------- | ------ | ---------- |
| ≤4 字符（短） | **20** | **0.18** |
| 5-12 字符（中） | **12** | **0.28** |
| >12 字符（长） | **8** | **0.28** |

短问题给更高召回量 + 更低阈值，长问题收紧范围。

**双候选检索**：先尝试**改写后的查询**，无命中则回退到**原始查询**：

```java
for (String candidateQuery : List.of(rewrittenQuestion, originalQuestion)) {
    List<Document> docs = vectorService.similaritySearch(candidateQuery, kbIds, topK, minScore);
    if (hasEffectiveHit(docs)) return docs;
}
```

**查询改写（Query Rewrite）**：用 LLM 将用户问题改写为更适合检索的单句查询——补充短问题的语义、结合多轮对话历史理解追问意图。失败时兜底原始问题。

**多知识库过滤**：pgvector 层面使用元数据过滤 `WHERE metadata->>'kb_id' in ('1', '2', '3')`；原生过滤失败则回退为宽检索 + Java 内存过滤。

### 五、生成（Generation）

1. **上下文拼装**：检索到的文档用 `\n\n---\n\n` 拼接
2. **Prompt 构造**：System prompt（73 行）定义专业问答助手角色、回答原则、Markdown 格式规范；User prompt 注入检索文档 + 用户问题，文档内容用反注入指令包裹
3. **流式输出**：SSE 逐字推送
4. **"探针窗口"机制**：缓冲前 120 字符判断是否包含"未找到"话术，命中则直接返回固定拒答文案，不再等待完整流

### 六、多轮对话

`RagChatSessionService` 管理会话：每次加载最近 **10 条**历史消息（DB 分页）；历史中助手消息截断到 **200 字符**（仅查询改写时）；历史注入到 LLM 调用中。

### 本项目 RAG 的局限

| 缺失的能力 | 影响 |
| -------------------------- | ---------------------------------------- |
| **无重叠分块** | 关键信息可能被切在边界处丢失 |
| **纯向量检索，无混合检索** | 精确关键词匹配场景（如人名、编号）召回差 |
| **无 Reranker** | top-K 结果未精排，噪声可能进入上下文 |
| **无查询扩展 / HyDE** | 短查询仅靠改写，缺乏多角度召回 |
| **硬截断而非摘要** | 历史消息直接截断 200 字符，丢失语义 |

## 7.8 为什么很多项目现在不用 RAG？

并不是 RAG 被淘汰了，而是行业在经历从"粗暴上 RAG"到"审慎评估 ROI"的转变：

### 1. 长上下文窗口让简单 RAG 不再必要

Gemini 2.5 Pro 支持 **1M tokens**，Claude 支持 **200K tokens**。如果知识库只有几十页文档，直接全塞进 prompt 比搭一套 RAG 管道简单得多，还避免了检索丢失的召回率问题。

> 本项目如果单个知识库文档在 10 万字以内，其实可以考虑直接整本放进上下文窗口。

### 2. RAG 的工程复杂度被严重低估

一套生产级 RAG 需要解决：文档解析（表格、图片、公式、排版）、分块策略（大小、重叠、语义边界）、向量模型选型和维度适配、检索质量调优（混合检索、重排序、阈值校准）、查询理解（改写、扩展、意图分类）、幻觉检测和拒答策略、向量库运维（索引重建、扩容、延迟监控）。每一项都可能成为瓶颈。

### 3. 很多场景根本不需要检索

- **结构化任务**：信息提取、分类、情感分析 → prompt + few-shot 就够了
- **模型已内化知识**：通用编程问题、常见概念解释 → 模型训练数据已覆盖
- **确定性业务逻辑**：计算、规则判断、数据转换 → 应该写代码而不是靠 RAG
- **创意生成**：文案、命名、头脑风暴 → 不需要"事实准确性"

### 4. GraphRAG / Agentic RAG 的门槛更高

简单 RAG 只能处理"找出来直接回答"的问题。稍微复杂的问题——"比较 A 和 B 的差异"、"总结多个文档的核心观点"——就需要：多跳推理（GraphRAG / Knowledge Graph）、多步检索（Agentic RAG：Plan → Retrieve → Evaluate → Re-retrieve）、结构化数据（Text-to-SQL）。这些实现复杂度远超基础 RAG，很多团队选择用 Agent + 工具调用替代。

### 5. 模型能力的提升降低了精细调优的收益

现在模型基础能力大幅提升：更好的指令遵循（prompt 设计比检索精度更关键）、更强的推理能力（检索结果不够好也能做出合理判断）、内置工具调用（Agent 模式可以自己决定"什么时候需要查什么"）。结果：**简单的 RAG 收益变小，复杂的 RAG 投入变高**。

### 总结

```
需要 RAG：大规模/频繁更新/高准确性要求的知识库问答
不需要：小规模知识库（塞进上下文即可）、创意任务、模型已知领域、有结构化数据源可替代
```

本项目作为面试平台的**知识库问答模块**，RAG 是合理的架构选择——知识库可能很大且持续更新。但当前实现偏基础，如果能加入**重叠分块、混合检索、Reranker**这三项，检索质量会有显著提升。

## 7.9 RAG 存储与数据冲突处理

### 一、RAG 存储用了什么？三层分离

| 层 | 存储 | 内容 |
| ---------- | ------------------------------------------- | ------------------------------------------------------------ |
| 第一层 | S3/MinIO（RustFS） | 原始文件，路径 `knowledgebases/yyyy/MM/dd/uuid_safename`，由 `FileStorageService` 管理 |
| 第二层 | PostgreSQL（JPA） | `KnowledgeBaseEntity` 对应 `knowledge_bases` 表，记录文件名、哈希、分类、向量化状态。关键索引：`@Index(name="idx_kb_hash", columnList="fileHash", unique=true)` 去重索引、`@Index(name="idx_kb_category", columnList="category")` |
| 第三层 | PostgreSQL + pgvector | Spring AI 的 `PgVectorStore`，底层表 `vector_store` |

pgvector 配置：

```yaml
spring.ai.vectorstore.pgvector:
  index-type: HNSW           # 分层可导航小世界图，检索快
  distance-type: COSINE_DISTANCE
  dimensions: 1024           # 与 embedding 模型输出对齐
  initialize-schema: true    # 开发环境自动建表
  remove-existing-vector-store-table: false  # 不删已有数据
```

向量元数据通过 JSONB 字段存储 `kb_id`、`kb_target_id`、`kb_vector_job_id` 等标识，支持按知识库过滤检索。

### 二、为什么不用 Redis 存向量？

**Redis 在本项目的实际用途**：异步向量化队列（Redis Stream `knowledgebase:vectorize:stream`）、限流计数器（`@RateLimit` 滑动窗口）、语音面试缓存（`voice:interview:session:*`，TTL 1 小时）、通用缓存（Redisson）。Redis 是**消息队列 + 缓存层**，而非向量存储。

**四个原因：**

1. **向量维度和规模不匹配**：Redis Stack 的向量搜索适用于低延迟、小规模场景。本项目 1024 维向量 + COSINE + 上万 chunk，pgvector 的 HNSW 索引在**百万级**向量上性能更稳定，且支持 `WHERE` 过滤（按 kb_id）与向量搜索的组合查询，这是 Redis 的弱项。
2. **数据一致性与事务**：向量的生命周期和知识库元数据强绑定——删知识库时要同时删向量。pgvector 和 `knowledge_bases` 表在**同一个 PostgreSQL 实例**，可用数据库事务保证一致性。Redis 的数据模型不支持这种跨结构的 ACID 保证。
3. **运维成本**：pgvector 只是一条 `CREATE EXTENSION`；Redis 存向量需要额外维护 Redis Stack（普通 Redis 没有向量能力），增加部署复杂度和内存成本。
4. **内存 vs 磁盘的成本模型**：1024 维向量每条占 4KB+，几千 chunk 就是几十 MB 起步。pgvector 数据存磁盘，HNSW 索引才加载到内存，成本远低于 Redis 全内存方案。

> **Redis 管"什么时候做"（异步任务队列），PostgreSQL 管"数据在哪"（元数据 + 向量），S3 管"文件存哪"（原始文档）。** 三层分离，各取所长。

### 三、数据冲突如何处理？（四个层面）

**层面 1：上传去重 — SHA-256 文件哈希**

```java
String fileHash = fileHashService.calculateHash(file);
Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
if (existingKb.isPresent()) {
    return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
}
```

`fileHash` 列有 `unique` 索引（数据库层面兜底）。重复上传直接返回已有记录 + `duplicate: true`，**不会创建新记录也不会重新向量化**。

**层面 2：向量写入 — 两阶段提交（最精妙的部分）**

`KnowledgeBaseVectorService` 的向量化过程：

**阶段一：写入临时标记**

```java
// 所有 chunk 的 kb_id 先设为 "pending:{realKbId}:{uuid}"
String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
chunks.forEach(chunk -> {
    chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);        // 临时 ID
    chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString()); // 目标 ID
    chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);      // 任务 ID
});
```

此时旧向量还在（正式 `kb_id`），新向量以临时 `kb_id` 写入，**互不干扰**。查询请求按正式 `kb_id` 过滤，只命中旧向量。

**阶段二：原子提升**

```java
private void activateVectorJob(Long knowledgeBaseId, String jobId) {
    runVectorRepositoryMutation(() -> {
        vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);  // 删除旧向量
        vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);  // 提升新向量
    });
}
```

`promoteVectorJob` 用一条 JSONB UPDATE 把 `kb_id` 从 `"pending:123:uuid"` 替换为 `"123"`，同时清除临时元数据字段。

**失败回滚：**

```java
catch (Exception e) {
    cleanupPendingVectorJob(knowledgeBaseId, jobId);  // 删除所有临时向量
    throw new BusinessException(...);
}
```

**层面 3：删除 — 级联顺序 + 柔性容错**

```
1. [事务内] 解除 RAG 会话关联 → 删除 knowledge_bases 行（外键约束要求先解关联）
2. [事务外] 删除向量数据（pgvector，非 JPA 管理）
3. [事务外] 删除 S3 文件（外部存储，失败只 warn 不阻断）
```

向量删除放事务外：pgvector 是原生 SQL，不参与 JPA 事务；通过 `TransactionExecutor` 包装确保 JDBC 层面原子性。S3 文件删除失败**不抛异常**，只记录 warn 日志，允许后续手动补偿。

**层面 4：重新向量化 — 覆盖式更新**

`revectorize()`：设置状态 PENDING → 发送新向量化任务 → 两阶段提交（新向量写入成功 → 原子替换旧向量）。与上传流程相同，保证**重新向量化期间查询不受影响**——始终有一份完整的向量数据可用。

**冲突处理全景：**

```
上传冲突  → SHA-256 去重，同文件直接返回已有记录
写入冲突  → 两阶段提交（临时标记 → 原子替换），查询不受影响
删除冲突  → 级联顺序（关联 → 元数据 → 向量 → 文件），柔性容错
重建冲突  → 覆盖式更新，新数据就绪后才替换旧数据
并发写入 → fileHash unique 索引兜底，数据库层面防重复
```

整体设计思路：**宁可短暂冗余（新旧向量共存），也不让查询返回空或脏数据**。

## 7.10 项目中的锁总结

### 一、Java 并发锁

| 类型 | 位置 | 用途 |
| ---------------------------------- | ------------------------------------------------------------ | --------------------------------------------------------- |
| **`ReentrantReadWriteLock`** | `LlmProviderConfigService.java:67` | 保护 LLM 提供商配置的读写——读操作共享锁、写操作独占锁 |
| **`synchronized` 块** | `QwenAsrService.java` | 按 session 粒度的 ASR 启停互斥，防止同一会话并发启动/停止 |
| **`synchronized` + `wait/notify`** | `VoiceInterviewWebSocketHandler.java` (OrderedTtsChunkEmitter) | TTS 音频块乱序生成但必须有序发送的生产者-消费者模式 |
| **`synchronized` 方法** | `QwenTtsService.java` (ByteArrayContainer) | 多线程并发追加音频字节数组时的线程安全写入 |

### 二、Redisson 分布式锁（`RLock`）

定义在 `RedisService.java`，提供完整分布式锁基础设施：

- `getLock()` — 获取可重入锁
- `tryLock()` — 带超时的非阻塞获取
- `unlock()` — 安全释放（校验当前线程持有）
- `executeWithLock()` — 高阶模板，自动获取→执行→finally 释放

> **当前状态：** 基础设施已就绪，但暂无业务代码调用——实际互斥由 JVM 内锁和数据库事务承担。

### 三、Redis Lua 原子限流

通过 `rate_limit_single.lua` + `RateLimitAspect` + `@RateLimit` 实现：

- 基于 ZSET 的滑动窗口算法，Lua 脚本在 Redis 端原子执行
- `@RateLimit` 支持 `GLOBAL` / `IP` / `USER` 三个维度独立限流，可重复标注

### 四、数据库事务锁

- **`@Transactional`** — 11 个文件中的写操作通过 PostgreSQL 隐式获取行级锁
- **`TransactionalExecutor`** — 使用 `Propagation.REQUIRES_NEW` 创建独立子事务隔离

**未使用的 JPA 锁：** `@Lock`、`@Version`（乐观锁）、`LockModeType.PESSIMISTIC_*`、`SELECT ... FOR UPDATE` 均未在项目中找到。

### 五、并发辅助原语（类锁机制）

| 类型 | 用途 |
| -------------------------------------- | ----------------------------------------- |
| `Semaphore` | 限制每个语音面试会话的并发 TTS 合成数量 |
| `CountDownLatch` | 异步转同步——等待 WebSocket 就绪或合成完成 |
| `ConcurrentHashMap` | 7 处使用，线程安全的会话/缓存 Map |
| `AtomicBoolean/Integer/Long/Reference` | 6 个文件中的无锁原子状态读写 |
| `RAtomicLong`（Redisson） | 分布式原子计数器 |

**总结：** 项目目前以 **JVM 单机锁**（`ReentrantReadWriteLock`、`synchronized`）和 **数据库事务锁** 为主，**分布式锁基础设施**（Redisson RLock）已封装好但尚未被业务代码实际调用。没有使用 JPA 显式锁模式或 Redisson 高级锁（公平锁、读写锁、红锁等）。

---

# 第九部分：面试高频考点速查

| 考点 | 答案一句话 | 深入方向 |
|---|---|---|
| 异步任务方案 | Redis Stream 模板方法 + XACK/XPENDING/XCLAIM | 与 MQ 对比、消息幂等 |
| 结构化输出不稳定 | 带错误反馈的业务层重试 + 修复提示词 + 指标监控 | BeanOutputConverter、schema validation |
| RAG 检索 | pgvector + 分块 + 查询改写 + 分档 top-k + 阈值过滤 | HNSW、embedding 维度、召回率 |
| SSE 流式 | Flux 推送 + 探测字符降级 | 背压、连接生命周期 |
| 限流 | Lua 滑动窗口 + 可重复注解 | 原子性、与其他算法对比 |
| 密钥安全 | AES-GCM + 环境变量注入 + 运行时配置隔离 | GCM nonce、KMS |
| 事务边界 | AI/IO 不进事务，事务只放 Service，REQUIRES_NEW 补丁 | 自调用失效 |
| 多模型平台化 | 注册表动态构建 ChatClient | 策略模式、可测试性 |
| 虚拟线程 | Java 21 虚拟线程提升 AI/SSE 并发 | 平台线程对比、阻塞点 |
| 简历去重 | 文件哈希 + 异步分析 | 幂等、成本控制 |
| 锁 | JVM 锁 + DB 事务锁为主，Redisson RLock 已封装未使用 | 分布式锁演进、乐观锁 |

---

# 第十部分：可继续深挖的方向

2. 语音面试 WebSocket 协议细节（消息帧格式、断线重连、音频编解码 opus→pcm）。
3. RAG 查询改写的具体提示词（`prompts/knowledgebase-rewrite.st`）。
4. 统一评估报告的具体维度与打分逻辑（`ResumeAnalysisResponse.ScoreDetail` 五维）。
4. 已识别的可改进点（todo）：
   - 语音面试上下文窗口加"最近 N 轮"滑动窗口或历史过长时摘要压缩。
   - RAG 增加**重叠分块、混合检索、Reranker** 三项以显著提升检索质量。
