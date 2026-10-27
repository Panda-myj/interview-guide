# Apifox 实践指南（AI 面试平台）

> 面向本仓库：Spring Boot 4.1 / Java 21 / Spring AI 2.0 后端 + React 前端。
> 目标：把 Apifox 变成这个项目的"接口单一事实来源"，而不是临时的调试记事本。

## 0. 先看清这个项目的接口形状

在动手配置之前，有几个项目特征决定了 Apifox 该怎么配，配错了后面全是无用功：

1. **所有接口都返回 HTTP 200**：`GlobalExceptionHandler` 的业务异常也返回 200，真正的结果在响应体 `code` 里。Apifox 的断言不能只看 HTTP 状态码。
2. **统一响应体 `Result<T>`**：`{ code, message, data }`，`code === 200` 才算成功。前端 `frontend/src/api/request.ts` 的拦截器正是按这个规则解包和抛错的。
3. **接口带限流**：`@RateLimit` 是 Redis 令牌桶，撞上限流返回 200 + `code = 8001`，不是 429。
4. **有 2 个 SSE 流式接口**：知识库问答、RAG 聊天，响应类型是 `text/event-stream`。
5. **有 1 个 WebSocket 接口**：语音面试 `/ws/voice-interview/{sessionId}`。
6. **后端已经自带 OpenAPI**：SpringDoc 3.0.2，`http://localhost:8080/v3/api-docs`，Swagger UI 在 `/swagger-ui.html`。**这就是 Apifox 的接入点，不需要手抄接口。**

### 接口全景（8 个 Controller，约 70 个接口）

| 分组（Swagger Tag / 目录建议） | 路径前缀 | 接口数 | 特殊点 |
| --- | --- | --- | --- |
| 面试技能 | `/api/interview/skills` | 3 | `parse-jd` 调 LLM，IP 限流 5/秒 |
| 模拟面试 | `/api/interview/sessions` | 12 | 创建 5/秒、提交答案 10/秒；`export` 返回 PDF |
| 简历管理 | `/api/resumes` | 7 | `upload` multipart + 5/秒；`export` 返回 PDF |
| 知识库管理 | `/api/knowledgebase` | 14 | `upload` multipart；`query/stream` 是 SSE；`download` 返回二进制 |
| RAG 问答 | `/api/rag-chat` | 8 | `messages/stream` 是 SSE |
| 语音面试 | `/api/voice-interview` | 10 + WS | 另有 `ws://.../ws/voice-interview/{sessionId}` |
| 面试日程 | `/api/interview-schedule` | 7 | 不调 LLM，最适合当冒烟测试 |
| LLM Provider 管理 | `/api/llm-provider` | 15 | 写接口 5/秒；请求体含真实 API Key |
| 健康检查 | `/actuator/health` | 1 | 依赖最少的环境探针 |

数据库 / Redis / RustFS 没起来时后端起不来，所以"能不能打开 `/actuator/health`"是判断环境是否可用的第一步。

---

## 1. 第一天做完这四件事

### 1.1 把后端跑起来（Apifox 的数据源）

```bash
docker compose -f docker-compose.dev.yml up -d
./gradlew :app:bootRun
```

确认两个地址：

- `http://localhost:8080/actuator/health` → 服务活着
- `http://localhost:8080/v3/api-docs` → 拿到一大坨 OpenAPI JSON（关键）

### 1.2 建项目和"环境"

建一个项目（例如 `AI 面试平台`），然后建两个环境：

| 环境 | `baseUrl` | 用途 |
| --- | --- | --- |
| 本地 | `http://localhost:8080` | 自己电脑上 `bootRun` |
| 联调/测试 | `http://<服务器IP>:8080` | 部署后的实例 |

两个要点：

- **`baseUrl` 不要带 `/api`**。这个项目所有 Controller 的路径里已经含 `/api`，带了就变成 `/api/api/...`。
- 前端是通过 Vite 代理把 `/api` 转发到 8080 的（`frontend/vite.config.ts`）。Apifox 直连 8080，不走 5173。

### 1.3 从 URL 导入 OpenAPI（不要手建接口）

Apifox → 项目设置 → 数据源 / 导入数据 → 「URL 导入 OpenAPI」→ 填：

```
http://localhost:8080/v3/api-docs
```

导入后会得到分组和完整字段定义。注意分组名当前是**不一致**的：

- 已有中文 `@Tag` 的 4 个模块：`模拟面试`、`简历管理`、`知识库管理`、`RAG 问答`。
- 还没有 `@Tag` 的 4 个 Controller：语音面试、面试日程、LLM Provider、面试技能。它们会退化成由类名推导的默认分组名（类似 `voice-interview-controller`）。

两种处理方式，任选一种但**要统一**：

1. 在 Apifox 里把默认分组重命名成中文，并记住同步时可能被还原成默认名；
2. 更好的是给那 4 个 Controller 补上 `@Tag(name = "语音面试", description = "...")`，这样 Swagger UI、OpenAPI 和 Apifox 三处永远一致（这属于代码改动，需要跑 `./gradlew :app:compileJava` 验证）。

接口变更后的正确姿势：启动后端 → 数据源里点「重新同步」→ 只处理有差异的接口。不要手改导入的接口字段，手改会在下次同步时被覆盖。

# TODO

### 1.4 配超时（这个项目必须做）

后端有大量 LLM 调用，慢是常态。参考前端 `request.ts` 的设定对齐：

| 接口类型 | 建议超时 |
| --- | --- |
| 普通 CRUD、健康检查 | 5–10 秒 |
| AI 类（创建面试、生成评估、解析 JD） | 60 秒 |
| 文件上传（简历、知识库） | 300 秒 |

在 Apifox 里按**目录**统一设置，比一个个接口改省事。

---

## 2. 三条铁律：不遵守会得出错误结论

### 铁律一：只看 `code`，不看 HTTP 状态码

后端在任何异常下都返回 HTTP 200。如果你在 Apifox 里断言"状态码 200 = 通过"，接口内部出错也会显示绿色。

### 铁律二：`code = 8001` 是限流，不是接口坏了

`@RateLimit` 触发的响应是 `{"code":8001,"message":"请求过于频繁，请稍后再试"}`。项目里的阈值不高，例如：

- `POST /api/interview/sessions`：全局 5/秒 + IP 5/秒
- `POST /api/resumes/{id}/reanalyze`：全局 2/秒
- `POST /api/llm-provider/**` 的写接口：全局 5/秒

两个实际影响：

- **自动化和性能测试必须加间隔**，否则跑出来的失败全是限流，白折腾。
- **全局维度是全团队共享的**。同事在跑自动化时你手动调试，可能一起抢令牌。看到 8001 先等一秒再判断是不是真 bug。

### 铁律三：AI 接口不是免费靶子

创建面试、提交答案、生成评估、知识库问答都会真实调用 DashScope（用 `.env` 里的 `AI_BAILIAN_API_KEY`），又慢又花钱。原则：

- 日常回归只跑「健康检查 + 面试日程 + 简历列表」这类不调 LLM 的接口。
- 必须跑 AI 接口时，用最小的 `questionCount`（最小 3，有 `@Min(3)` 校验）。
- 前端高频联调改用 Mock（见第 8 节），不要反复打真模型。

---

## 3. 统一断言脚本（挂在项目级后置操作）

在项目设置 → 后置操作里加一段脚本，全项目自动生效。这是投入产出比最高的一步。

```javascript
// 项目级后置脚本：统一校验 Result 规范
let body = pm.response.json();

pm.test('HTTP 状态为 200', function () {
  pm.response.to.have.status(200);
});

pm.test('响应体符合 Result 结构', function () {
  pm.expect(body).to.be.an('object');
  pm.expect(body).to.have.property('code');
  pm.expect(body).to.have.property('message');
});

if (body && body.code === 8001) {
  console.warn('触发限流（code=8001）：降低循环频率或稍后重试，不代表接口异常');
}

if (body && body.code === 500) {
  console.error('服务端内部错误，去后端日志找 "系统异常" 的栈信息');
}

pm.test('业务 code 为 200（实际 ' + (body && body.code) + '：' + (body && body.message) + '）', function () {
  pm.expect(body.code).to.eql(200);
});
```

需要验证参数校验时，在那个接口用例里单独断言 `code === 400`，不要改通用脚本。

### 目录级后置脚本：自动收集 ID

这个项目的接口是**链路式**的：上传简历拿到 `resumeId`，创建面试拿到 `sessionId`，后面十几个接口都依赖它们。给每个模块目录挂一个提取脚本，把 ID 自动写进环境变量，省掉手抄。

简历目录：

```javascript
let body = pm.response.json();
if (body && body.code === 200 && body.data && body.data.id !== undefined) {
  pm.environment.set('resumeId', String(body.data.id));
  console.log('已保存 resumeId =', body.data.id);
}
```

知识库目录：

```javascript
let body = pm.response.json();
if (body && body.code === 200 && body.data && body.data.id !== undefined) {
  pm.environment.set('knowledgeBaseId', String(body.data.id));
}
```

RAG 会话目录：

```javascript
let body = pm.response.json();
if (body && body.code === 200 && body.data && body.data.id !== undefined) {
  pm.environment.set('ragSessionId', String(body.data.id));
}
```

语音面试目录（返回的是 `sessionId` 字段，不是 `id`，而且响应里带着 `webSocketUrl`）：

```javascript
let body = pm.response.json();
if (body && body.code === 200 && body.data && body.data.sessionId !== undefined) {
  pm.environment.set('voiceSessionId', String(body.data.sessionId));
  if (body.data.webSocketUrl) {
    pm.environment.set('voiceWsUrl', body.data.webSocketUrl);
  }
}
```

文本面试（`sessionId` 是字符串，创建接口的响应 `data` 里就有）：

```javascript
let body = pm.response.json();
if (body && body.code === 200 && body.data && body.data.sessionId) {
  pm.environment.set('interviewSessionId', String(body.data.sessionId));
}
```

用的时候就是 `{{resumeId}}`、`{{knowledgeBaseId}}`、`{{interviewSessionId}}`。

---

## 4. 高频接口的示例请求体

导入后字段定义都有了，但示例值要自己填。下面几段可以直接粘贴使用。

**创建文本面试** `POST /api/interview/sessions`

```json
{
  "skillId": "java-backend",
  "difficulty": "mid",
  "questionCount": 3,
  "resumeId": {{resumeId}},
  "llmProvider": "dashscope",
  "forceCreate": true
}
```

`skillId` 必填（`@NotBlank`）。可选值先调 `GET /api/interview/skills` 拿到，别猜。

**提交答案** `POST /api/interview/sessions/{sessionId}/answers`

```json
{
  "sessionId": "{{interviewSessionId}}",
  "questionIndex": 0,
  "answer": "我用 Redis 做缓存，主要解决热点数据的读取压力……"
}
```

**知识库问答** `POST /api/knowledgebase/query`

```json
{
  "knowledgeBaseIds": [{{knowledgeBaseId}}],
  "question": "什么是索引下推？"
}
```

**创建 RAG 会话** `POST /api/rag-chat/sessions`

```json
{
  "knowledgeBaseIds": [{{knowledgeBaseId}}],
  "title": "Apifox 调试会话"
}
```

**创建语音面试会话** `POST /api/voice-interview/sessions`

```json
{
  "skillId": "java-backend",
  "difficulty": "mid",
  "techEnabled": true,
  "projectEnabled": true,
  "hrEnabled": false,
  "plannedDuration": 15
}
```

**解析面试日程** `POST /api/interview-schedule/parse`

```json
{
  "rawText": "您好，邀请您参加字节跳动后端开发岗一面，时间为 2026-09-20 14:00，飞书会议。",
  "source": "feishu"
}
```

**新增 LLM Provider**（会写入真实密钥，注意第 7 节）

```json
{
  "id": "my-deepseek",
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "{{providerApiKey}}",
  "model": "deepseek-chat",
  "supportsEmbedding": false,
  "temperature": 0.7
}
```

---

## 5. 三类特殊接口怎么调

### 5.1 SSE 流式接口（2 个）

```
POST /api/knowledgebase/query/stream                 Flux<String>，data 行是文本片段
POST /api/rag-chat/sessions/{id}/messages/stream     ServerSentEvent，换行被转义成 \n
```

调试点：

- 请求头带 `Accept: text/event-stream`。
- 超时至少 60 秒，流没结束就断开会被误判失败。
- 较新版本的 Apifox 新建接口时可以把请求方式选成 **SSE / 流式请求**，能实时看到分片。版本不支持时用 POST 也能拿到完整响应，只是看不到逐字输出。
- 两个接口都有 5/秒 的全局 + IP 限流，重复连接要留间隔。
- `query/stream` 的错误可能以 `event: error` 分片下发，前端 `frontend/src/api/stream.ts` 专门解析了这种事件。在 Apifox 里验证时留意最后几行分片里有没有 `error`。

命令行兜底（PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，所以要写 `curl.exe`）：

```powershell
curl.exe -N -X POST "http://localhost:8080/api/knowledgebase/query/stream" `
  -H "Content-Type: application/json" `
  -H "Accept: text/event-stream" `
  -d '{"knowledgeBaseIds":[1],"question":"什么是索引下推"}'
```

### 5.2 WebSocket（语音面试）

```
ws://localhost:8080/ws/voice-interview/{sessionId}
```

在 Apifox 里新建 WebSocket 接口，连上后：

1. 服务端会先推一条欢迎消息 `{"type":"control","action":"welcome",...}`，收到就说明握手成功。
2. **用文本驱动整轮面试（重要技巧）**：正常流程靠 Base64 PCM 音频，但 Apifox 里没法喂麦克风。后端支持 `submit` 控制消息直接注入文本：

```json
{"type":"control","action":"submit","data":{"text":"我做过一个基于 Redis 的分布式锁，用来解决重复下单问题。"}}
```

   发送后服务端会走完整的 LLM 问答流程，推回 `subtitle` 和 `audio` 消息。这样能完整验证会话状态机，不必先解决音频输入问题。

3. 其他可用动作（见 `VoiceInterviewWebSocketHandler.handleControl`）：`start_phase`（要带 `phase`：`INTRO`/`TECH`/`PROJECT`/`HR`）、`end_interview`。
4. 服务端下发的消息类型：`subtitle`、`audio`、`audio_chunk`、`text`、`control`、`error`。
5. 握手返回 403 是 WebSocket 的 Origin 校验（后端用 `app.cors.allowed-origins` 控制）。确认客户端没带非白名单的 `Origin`，或把调试来源加进 `CORS_ALLOWED_ORIGINS`。

完整消息协议看 `frontend/src/api/voiceInterview.ts` 的类型定义，那是前端的真实契约。

### 5.3 文件上传与二进制下载

上传（`POST /api/resumes/upload`、`POST /api/knowledgebase/upload`）：

- Body 选 `form-data`，字段名必须是 `file`（后端是 `@RequestParam("file")`）。
- 知识库上传还支持可选的 `name`、`category`。
- 简历只接受 PDF / DOC / DOCX / TXT，见 `application.yml` 的 `app.resume.allowed-types`；大小上限 50MB。
- 超时设 300 秒，上传会触发 Tika 解析和 LLM 分析，很慢。
- 简历上传响应里有 `duplicate` 字段：如果 `message` 是"检测到相同简历…"，说明命中了去重逻辑，此时 `data.id` 是历史记录。自动化断言要容忍这个分支，否则第二次跑就红了。

下载（`GET /api/resumes/{id}/export`、`/api/knowledgebase/{id}/download`、`/api/interview/sessions/{id}/export`）：

- 这三个接口**不走 `Result` 包装**，直接返回文件字节流。通用后置脚本里的 `pm.response.json()` 在这些接口上会报错，需要在接口上关掉通用断言，改成断言 `Content-Type` 和响应大小。
- 用 Apifox 的"保存响应到文件"来查看结果。

---

## 6. 用场景编排把接口串成业务流

单接口调试只能证明"接口能通"，能证明"业务能跑"的是链路。在 Apifox 的「自动化测试 / 测试场景」里建下面 4 条，按顺序执行，靠第 3 节的环境变量传数据。

**链路 A：简历（无 LLM，可高频跑）**

上传 → 列表 → 详情 → 导出 PDF → 删除

**链路 B：文本面试（消耗 LLM，低频跑）**

技能列表取 `skillId` → 创建面试 → 取当前问题 → 提交答案（循环 3 次，`questionIndex` 用变量自增）→ 完成面试 → 取报告 → 取详情 → 导出 PDF → 删除会话

**链路 C：知识库 + RAG（消耗 embedding）**

上传文档 → 轮询列表里的 `vectorStatus` 直到就绪 → 建 RAG 会话 → 流式问答 → 会话列表 → 会话详情 → 删除会话 → 删除知识库

**链路 D：语音面试（消耗 LLM + TTS）**

创建会话 → 记录 `webSocketUrl` → 连接 WS → 发 `submit` 文本 → 等 `subtitle` 回复 → 发 `end_interview` → 关闭 WS → `GET messages` → `POST evaluation` → 轮询 `GET evaluation` 直到 `COMPLETED` → 删除会话

编排要点：

- 场景**必须串行**。并行一定会撞限流，你看到的是 8001 而不是真 bug。
- 每个场景最后加一步清理，删掉自己造的数据。这里用的是真实的 PostgreSQL / RustFS，不清理会攒垃圾。
- 评估类是异步任务（`GET /voice-interview/sessions/{id}/evaluation` 返回 `PENDING/PROCESSING/COMPLETED/FAILED`），用循环 + 间隔 3 秒轮询，别写成死断言。
- 长链路在开头加一次 `/actuator/health`，环境没起来时能立刻定位，而不是从第 6 步的莫名失败开始查。

---

## 7. 敏感信息与团队协作

这个项目有两处和密钥打交道，是 Apifox 里最容易出事的地方。

**第一处：`.env` 里的 `AI_BAILIAN_API_KEY`。** 它只应存在于后端运行环境，**不要**复制进 Apifox 环境变量。Apifox 调的是后端自己的接口，后端用它自己的密钥，Apifox 不需要知道。

**第二处：LLM Provider 管理接口的请求体。** `POST /api/llm-provider`、`PUT /api/llm-provider/{id}`、`PUT /api/llm-provider/voice/asr|tts` 需要真实 `apiKey`，这些值会落盘到 `~/.interview-guide/llm-providers.yml`。做法：

- 密钥存成 Apifox 的**私有变量**（只对自己可见），请求体写 `{{providerApiKey}}`，不要把明文写进接口用例。
- 响应里的 `maskedApiKey` 是脱敏的，可以正常用于断言。
- `PUT` 类接口如果传空 `apiKey`，可能覆盖已有配置。调试前先 `GET` 一份当前配置备着。

其他协作约定：

- **环境变量分层**：`baseUrl` 这类共用项放公共环境变量；`providerApiKey`、个人测试数据 ID 放私有变量。
- **后端改接口后**：先同步 OpenAPI，再更新自动化测试。手改过的接口会在同步时被覆盖，所以别把它当成"不能重新生成"的项目。
- **接口用例写清依赖**：注明"需要先执行简历上传"这类前置条件，比口头交接可靠。

---

## 8. 用 Mock 解耦前端联调（收益最大的一步）

真调 LLM 的接口慢（几十秒）且收费，前端没法高频联调。Apifox 的 Mock 正好补这个缺口，而且这个项目改造成本极低——前端所有请求都走 `frontend/src/api/request.ts` 的 Axios 实例，基地址来自 `VITE_API_BASE_URL`。

步骤：

1. 给慢接口建 Mock 期望，返回符合 `Result` 结构的 JSON，例如 `{"code":200,"message":"success","data":{...}}`。
2. 优先 Mock 这几个：`POST /api/interview/sessions`、`GET /api/interview/sessions/{id}/question`、`POST /api/rag-chat/sessions/{id}/messages/stream`。
3. 前端本地新建 `frontend/.env.local`，把基地址指向 Apifox 的本地 Mock 服务：

```
VITE_API_BASE_URL=http://127.0.0.1:4523/m1/<你的项目ID>
```

4. 打开 Apifox 的「本地 Mock」开关，前端 `pnpm run dev`，此时前端完全不依赖后端和 AI 服务。

注意：Mock 的 `data` 结构必须和真实响应一致（尤其是 `sessionId`、`question` 这些字段名），否则前端解构时报错——这本身也顺带验证了契约。SSE 接口的 Mock 只能给固定片段，验证"逐字输出"的交互效果还是要连真后端。

---

## 9. 长期：把 Apifox 接进开发流程

**契约同步**：后端加新接口 → 启动服务 → Apifox 数据源同步 → 前端从 Apifox 生成 TypeScript 类型 → 对齐 `frontend/src/types/`。前端类型来自实际契约，而不是人肉抄写。

**CI 冒烟**：Apifox 提供命令行工具（`apifox run`），可在流水线里跑测试场景。建议只跑「链路 A（简历）+ 面试日程 + 健康检查」这组无 LLM 的用例；含 AI 的链路留到人工或定时任务，避免每次提交都烧 token。

**目录顺序建议**（新人从上往下点一遍就能跑通主流程）：

```
00 健康检查
01 面试技能
02 简历管理
03 模拟面试
04 知识库管理
05 RAG 问答
06 语音面试
07 面试日程
08 LLM Provider 管理
```

---

## 10. 常见坑速查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `404 API 接口不存在` | `baseUrl` 多带了 `/api`，或后端没启动 | 检查 `baseUrl`，确认 `/actuator/health` 可访问 |
| 接口显示通过但拿不到数据 | 只断言了 HTTP 200，没断言 `code` | 用第 3 节的项目级后置脚本 |
| 突然大量失败、`code=8001` | 撞上限流（尤其自动化并发跑） | 场景改串行、加间隔、稍后重试 |
| AI 接口转圈到超时 | 默认超时太短 | 目录超时设 60 秒（上传 300 秒） |
| PDF 下载接口断言报错 | 下载接口是二进制流，不返回 `Result` | 该接口关闭通用 JSON 断言，改断言 `Content-Type` |
| WS 握手 403 | Origin 不在 `app.cors.allowed-origins` 白名单 | 调整 `CORS_ALLOWED_ORIGINS` 或确认客户端未带 Origin |
| 语音面试 WS 连上后没反应 | 没发音频也没发 `submit` | 发 `{"type":"control","action":"submit","data":{"text":"..."}}` 用文本驱动 |
| 导入后缺少新接口 | 后端没重启，OpenAPI 是旧的 | 重启后端后重新同步数据源 |
| 简历上传第二次返回"检测到相同简历" | 后端有去重逻辑（`duplicate=true`） | 断言里容忍该分支，或换文件 |
