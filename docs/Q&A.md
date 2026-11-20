# DashScope 连接失败排查 Q&A

> 记录时间：2026-11-18
> 环境：本地开发，Spring Boot 8080，PostgreSQL 容器，`APP_AUTH_SMS_PROVIDER=mock`
> 涉及模块：`common/ai`、`modules/llmprovider`、`modules/auth`
> 变量名口径：本文已按当前配置更新（`PROVIDER_DASHSCOPE_API_KEY`、`PROVIDER_DASHSCOPE_MODEL`、`RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`）；历史排障命令中的旧变量名请按文末「配置变量重命名」对照表换算。

**一句话结论**：三个互相独立的问题叠加，不是一个故障。
① base-url 用了原生协议路径 → 404；② 管理接口要求 ADMIN 登录态 → 401；③ 数据库里存的是旧 API Key → 403。
deepseek / kimi 一直正常，是因为它们的 Key 从未更换过，且用的是公共域名。

---

## Q1：现象是什么？

| 阶段 | 现象 |
| --- | --- |
| 最初 | 设置页 dashscope「测试连接」失败，deepseek / kimi 正常 |
| 换端点后 | 仍失败，报 `403 Workspace endpoint access denied` |
| 手动调接口 | `POST /api/llm-providers/reload` 返回 `401 请先登录或登录已过期` |

期间先后出现 404、401、403 三种错误，分别对应**路径、鉴权、凭据**三个不同层面。

---

## Q2：为什么一开始是 404？

用 `.env` 里的 Key 直接 curl 实测三个地址（脱敏后）：

| 请求地址 | HTTP | 说明 |
| --- | --- | --- |
| `https://llm-<workspace>.cn-beijing.maas.aliyuncs.com/api/v1/chat/completions` | **404** | 项目原配置，空响应体 |
| `https://llm-<workspace>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions` | **200** | 正确 |
| `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | **200** | 公共域名也可行 |

项目用 Spring AI 的 `OpenAiChatModel`（OpenAI 兼容协议）发请求，客户端固定追加 `/chat/completions`：

- `ApiPathResolver.resolveVersionedBaseUrl()`：末尾已有 `/v1` 就原样返回，否则补 `/v1`
- `LlmProviderConfigService.buildConnectivityTestUrls()`：拼 `<base>/chat/completions`

而 `/api/v1` 是 **DashScope 原生协议**路径（对应 `/api/v1/services/aigc/...`），并不提供 `/chat/completions`。两者协议不匹配，服务端直接回 404 且不带响应体，看起来很像"网络连不上"。

**修复**：`base_url` 改为
`https://llm-rmolmfgsop3yk6f6.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`
（`application.yml` 与数据库两处都要改，见 Q3）

> 注意：`llm-xxx.cn-beijing.maas.aliyuncs.com` 是 2026 年百炼推出的**业务空间专属域名**，域名本身是对的；错的是后面的路径前缀。OpenAI 兼容用 `/compatible-mode/v1`，原生 DashScope 协议才用 `/api/v1`。

---

## Q3：为什么改了 `application.yml` 不生效？

因为**数据库才是运行时唯一真相**：

- `LlmProviderBootstrapService.seedProvidersIfNecessary()` 只在 `llm_provider_config` 表为空时，把 yml 里的 `app.ai.providers` 灌进去一次
- 之后 `LlmProviderRegistry.loadProviderOrThrow()` 和 `LlmProviderConfigService.testProvider()` 一律读数据库
- 表非空时，改 yml、改 `.env` 都不会同步到数据库

所以正确做法是三选一：设置页保存、`PUT /api/llm-provider/{id}`、或直接改库后调 `/reload`。

---

## Q4：401 未登录，难道必须用真实号码认证短信？

**不需要。** `APP_AUTH_SMS_PROVIDER=mock` 本来就是给本地登录用的：`.env` 里 `APP_AUTH_EXPOSE_DEBUG_CODE=true`，`POST /api/auth/sms/send` 会把验证码放在 `data.debugCode` 里直接返回，不发任何短信。两步即可拿到 `ROLE_ADMIN` 的 token（`APP_AUTH_BOOTSTRAP_PHONE` 在 mock 模式下就是引导管理员，V9 迁移脚本创建）。

实测结果：`sms/send` 返回 `debugCode` → `login/sms` 拿到 JWT（`sub=4, role=ADMIN`）→ `POST /api/llm-provider/reload` 返回 200。

三个容易踩的细节：

1. 请求必须带 `Authorization: Bearer <accessToken>`；`accessToken` 只有 15 分钟（`AUTH_ACCESS_TOKEN_TTL`），过期会再次 401
2. 路径是**单数** `/api/llm-provider`，没有 `llm-providers`。`LlmProviderController` 的 `@RequestMapping` 写的是单数；带 token 访问复数路径会返回 HTTP 200 + body `{"code":404,"message":"API 接口不存在"}`，极具迷惑性。`LlmProviderConfigService` 的类注释曾写成复数，已修正
3. `SecurityConfig` 里 `/api/llm-provider/**` 要求 `hasRole("ADMIN")`，普通 USER 登录会 403 而不是 401

---

## Q5：怎么在 mock 模式下免登录测试？（已做的改动）

新增开关 `APP_AUTH_DEV_ANONYMOUS_ADMIN`。仅当 **`APP_AUTH_SMS_PROVIDER=mock` 且非 `prod` profile** 时，把 `/api/llm-provider/**` 从 `hasRole("ADMIN")` 放开为 `permitAll()`，方便 Apifox / curl 免登录调试。

改动文件：

| 文件 | 改动 |
| --- | --- |
| `modules/auth/config/SecurityConfig.java` | 条件化授权规则，启用时打 WARN 日志 |
| `modules/auth/config/AuthBootstrapValidator.java` | prod 或真实短信通道下误开 → 启动直接失败 |
| `app/src/main/resources/application.yml` | 新增 `app.auth.dev.anonymous-admin` 配置键 |
| `app/src/main/resources/application-prod.yml` | 生产硬编码 `false`，不读环境变量 |
| `.env` | 设为 `true` |
| `.env.example` | 加说明，模板默认 `false` |
| `LlmProviderConfigService.java` | 修正注释里的复数路径 |

`./gradlew :app:compileJava` 通过。
**安全边界**：只放开 `/api/llm-provider/**`，其它 `/api/**` 仍需登录；生产环境两道防线（prod 覆盖 + 启动校验）。

> 状态：截至记录时后端 JVM 启动于 11:35、`.env` 最后修改于 11:47，**该开关尚未重启生效**，匿名访问仍是 401。

---

## Q6：路径修好后为什么变成 `403 Workspace endpoint access denied`？

调 `/api/llm-provider/dashscope/test` 拿到真实报错：

```
HTTP 403 on https://llm-<workspace>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions
body={"error":{"message":"Workspace endpoint access denied.","code":"access_denied"}}
```

但用 `.env` 的 Key curl 同一地址是 200 —— 说明**应用发出的凭据和 `.env` 不是同一把**。指纹对比：

| Provider | `.env` 尾号 | 应用回显 `maskedApiKey` | 结果 |
| --- | --- | --- | --- |
| dashscope | `…BlJFiwYc`（`sk-ws-` 新 Key） | `sk-***FdA` ← **旧 Key** | 403 |
| deepseek | `…23adf2` | `sk-***df2` ✅ | 成功 |
| kimi | `…Big7rRQic` | `sk-***Qic` ✅ | 成功 |

原因链：

1. 建库时 seed 把当时的百炼 Key 加密写进 `llm_provider_config`
2. 后来 `.env` 换成了新版 `sk-ws-`（工作空间级）Key，但**改 `.env` 不回写数据库**（Q3）
3. 设置页保存时如果 `apiKey` 留空，`updateProvider` 会沿用旧密文（只有非空白才重新加密）
4. 专属域名按官方规则「仅限业务空间所属 API Key 调用」，旧 Key 被拒；公共域名 `dashscope.aliyuncs.com` 接受跨工作空间 Key，所以以前未必暴露

**修复**：走应用接口而不是直接改密文：

```bash
curl -X PUT http://localhost:8080/api/llm-provider/dashscope \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"apiKey\":\"$KEY\"}"
```

`updateProvider` 只更新传入字段、重新加密落库、并自动 `refreshRegistry()` 清缓存。
验证：`maskedApiKey` 变为 `sk-***wYc`，`/test` 返回 `{"success":true,"message":"连接成功"}`。

---

## Q7：为什么 deepseek / kimi 一直正常？

- 它们的 API Key 从没换过，seed 时的值与当前 `.env` 一致
- 它们走公共域名（`api.deepseek.com` / `api.moonshot.cn`），没有工作空间归属校验
- 两者都验证了"网络、SSRF 校验、限流、鉴权链"本身没问题，把问题范围缩小到百炼这一条配置

---

## Q8：`PROVIDER_DASHSCOPE_API_KEY` 为什么有两套来源？

```
.env 的 PROVIDER_DASHSCOPE_API_KEY（新 Key）
  ├─→ 进程环境变量（每次启动读取）→ application.yml 的 app.voice-interview.qwen.*.api-key
  │                                 → ASR / TTS（不走数据库）
  └─→ 首次 seed（仅表为空时）→ llm_provider_config 密文 → Chat / Embedding
                                 ↑ 换 .env 不会回写这里
```

于是会出现"语音正常但对话失败"的分裂。改 Key 只影响左边那条路（而且还要重启才生效）。

---

## Q9：改完配置要不要 reload / 重启？

| 改动方式 | 生效方式 |
| --- | --- |
| 设置页保存 / `PUT /api/llm-provider/{id}` | 自动 `refreshRegistry()` 清缓存，**立即生效** |
| 直接改数据库 / `application.yml` | 需 `POST /api/llm-provider/reload` 或重启（`LlmProviderRegistry` 有 `ConcurrentHashMap` 缓存） |
| 改 Java 代码 / `.env` | **必须重启后端**（`.env` 由 Gradle 在启动 `bootRun` 时注入） |

`registry.reload()` 只清当前 JVM 缓存，不广播给其它实例；多实例部署时每个实例都要刷新。

---

## Q10：以后换百炼 Key 的正确姿势

1. 在设置页把 dashscope 的 **API Key 重新填一次**（不要留空），或调 `PUT /api/llm-provider/dashscope` 只传 `apiKey`
2. 点「测试连接」确认返回「连接成功」
3. 重启后端，让 ASR / TTS 的环境变量也用上新 Key
4. 如果同时换了工作空间，确认 `base_url` 里的 `llm-<workspace>` 与 Key 归属一致，且路径是 `/compatible-mode/v1`

可选改进（未实现）：在 `LlmProviderBootstrapService` 启动时比对环境变量 Key 与数据库解密后 Key 的指纹，不一致就打 WARN 日志；只告警、不覆盖用户在设置页的显式配置。

---

## Q11：语音面试与文字面试体检（追加）

### 文字面试：功能可用，不是代码故障

实测 `POST /api/interview/sessions`（`skillId=java-backend`、`questionCount=2`）返回 **HTTP 200**，46 秒生成 4 道题（含追问），说明出题链路、结构化输出、`BeanOutputConverter` 均正常。

属于**模型选型 / 配置**范畴的两点：

| 现象 | 原因 | 影响 |
| --- | --- | --- |
| 单次出题约 46 秒 | `qwen3.5-flash` 是思考模型，实测 `reasoning_content` 约 2300 字符，`content` 才是最终 JSON | 延迟与 token 成本偏高；`structured-max-attempts: 2` 时重试代价翻倍 |
| 裸测 `response_format: json_schema` 返回 400 | 百炼要求 `messages` 中含 "json" 字样才允许结构化格式 | 项目链路因 `BeanOutputConverter` 的格式指令含 JSON 字样而正常，属脆弱点：换模型或改 prompt 可能触发 |

结论：**文字面试没有功能性故障**，若嫌慢应换非思考模型或关闭思考模式（需代码支持 `enable_thinking` 参数）。

### 语音面试：ASR 模型名配错，属于模型配置问题

`application.yml` 里 `app.voice-interview.qwen.asr.model` 被写成 `qwen3-asr-flash`。官方区分：

- `qwen3-asr-flash`：**离线 HTTP / OpenAI 兼容**模型（≤5 分钟、10 MB）
- `qwen3-asr-flash-realtime`：**实时 WebSocket** 模型

而 `QwenAsrService` 用 `OmniRealtimeParam` + `OmniRealtimeTranscriptionParam` 连 `wss://dashscope.aliyuncs.com/api-ws/v1/realtime`，必须用实时模型。真实 WebSocket 会话对比：

| URL 上的 model | 结果 |
| --- | --- |
| `qwen3-asr-flash` | `session.created` 后发送 `session.update` → **CLOSE 1007**：`InternalError.Algo.InvalidParameter: Either "prompt" or "messages" must exist and cannot both be none`（服务端按文本生成模型处理） |
| `qwen3-asr-flash-realtime` | `session.created` → `session.updated` 正常返回，会话可用 |

TTS 侧实测正常：`qwen3-tts-flash` HTTP 合成在公共域名与专属域名均返回 200 和音频 URL。语音面试的 LLM 部分走 `getVoiceChatClient()`，与文字面试同一套已修好的数据库配置。

所以语音面试不可用的**直接原因是 ASR 模型名配置错误**，不是 Java 业务代码写错。

### 一处代码缺陷：ASR「连接测试」假阳性

`LlmProviderConfigService.testAsrConfig()` 原实现只做 `new Socket().connect(...)` 到 443 端口，既不校验鉴权也不发协议事件，却返回「ASR WebSocket 连接成功」。因此配置错模型名时，设置页仍显示成功，误导排查。

已改为：

1. 地址安全（SSRF）校验保持最高优先级，不因模型名错误被绕过
2. 新增纯函数 `isRealtimeAsrModel(String)` 校验模型名后缀，配套 5 个断言的单测
3. 成功消息改为「ASR 端点 TCP 连通（模型名格式已校验，未验证鉴权与协议握手）」

单测 `./gradlew :app:test --tests "*LlmProviderConfigServiceTest*"` 通过。

### 本轮修复与生效状态

| 动作 | 生效方式 |
| --- | --- |
| `application.yml` 的 `asr.model` 改回 `qwen3-asr-flash-realtime` | 重启后端后生效 |
| `PUT /api/llm-provider/voice/asr` 只传 `{"model":"qwen3-asr-flash-realtime"}` | 已执行，运行时内存立即生效（`GET /voice/asr` 已回显正确值） |
| `testAsrConfig` 代码与单测 | 重启后端后生效 |

---

## Q12：运行时 TTS 合成失败（追加，已定位并修复）

### 现象

后端重启后，语音面试日志出现：

```text
ERROR ... i.g.m.v.service.QwenTtsService : TTS synthesis failed: 语音合成失败，请检查 TTS 配置和账户额度
ASR 端点 TCP 连通（模型名格式已校验，未验证鉴权与协议握手）: dashscope.aliyuncs.com
```

第二行是 Q11 修改后的新文案，说明新代码已加载、ASR 端不是本次故障点。

### 结论：这是**代码问题**，与 Key、免费额度、账户状态、模型权限都无关

Q11 里「curl 直连 `qwen3-tts-flash` 返回 200」是对的，但漏了**应用运行时走的不是那条路径**。真实原因是：

`QwenTtsService` 调用 DashScope SDK 2.23.3 的 `HttpSpeechSynthesizer.callAndReturnAudio()`。反编译可见它构造的是老版 TTS 端点（`Protocol.HTTP` + `TaskGroup.AUDIO` / `Task.TEXT_TO_SPEECH` / `Function.SPEECH_SYNTHESIZER`，且 `callAndReturnAudio` 走 `streamCall` 的 SSE 通道）；而 `qwen3-tts-flash` 只支持新端点
`POST /api/v1/services/aigc/multimodal-generation/generation`。SDK 把请求打到了不匹配的 URL，百炼返回 `400 InvalidParameter: url error, please check url!`。

### 证据链（同一把 Key、同一个模型，用项目同一套 SDK 做对照探针）

| 调用方式 | 结果 |
| --- | --- |
| 裸 HTTP POST 官方端点（`model` / `input.text` / `input.voice` / `parameters.language_type`） | `HTTP 200`，返回 `output.audio.url` |
| SDK `callAndReturnAudio()`（项目原实现，SSE） | `400 InvalidParameter: url error` |
| SDK `call()`（非 SSE，排除「SSE 协议」嫌疑） | 同样 `400 InvalidParameter: url error` → 是**端点 URL 不匹配**，不是流式协议问题 |
| SDK + 旧模型 `qwen-tts` | `404 Model not exist.` → 该模型在本工作空间不可用，**不能靠改回旧模型绕过** |
| 下载音频 URL 并解析 WAV 头 | `RIFF/WAVE`，`24kHz / mono / 16bit`，正好等于项目 `OUTPUT_SAMPLE_RATE` |

所以：Key 有效、额度有效、模型已开通、音频规格与项目预期一致，**唯一坏掉的是 SDK 那一层**。

### 为什么日志把人带偏了

`toUserFacingError()` 只对「免费额度 / 401 / 403 / timeout」做了分类，其余一律落到兜底句「请检查 TTS 配置和账户额度」。原始异常里的 `url error`、`InvalidParameter` 被吞掉，日志首行看起来就像额度问题。**这是第二处代码缺陷（可观测性）**。

### 修复内容

| 文件 | 改动 |
| --- | --- |
| `modules/voiceinterview/service/QwenTtsService.java` | 不再用 `HttpSpeechSynthesizer`；改由 JDK `HttpClient` 直调官方端点，解析 `output.audio.url`（兼容内联 `output.audio.data`），下载 WAV 后剥头得到 24kHz PCM。下载域名做 `*.aliyuncs.com` 白名单校验防 SSRF |
| 同上 | `toUserFacingError()` 增加 `url error` / `InvalidParameter`、`Model not exist`、401/403、429 限流、`AllocationQuota` 欠费等分类；未知错误兜底句附带原始错误摘要；日志增加 `raw=` 原始异常链 |
| 同上 | `stripWavHeaderIfPresent()` 用 `long` 计算 `dataStart + chunkSize`，修复流式 WAV 把 `data size` 写成 `0x7FFFFFFF` 附近值时的整数溢出风险；新增音频规格不符的告警 |
| `QwenTtsServiceTest.java` | 新增 5 个单测：端点不匹配 / 模型不存在 / 限流 / 兜底保留原始错误 / 超大 WAV chunkSize |

所有原有字段与构造签名保持不变，`reload()`、设置页、`getLastError()` 契约不受影响。

### 验证结果

```
./gradlew :app:compileJava :app:test --tests '*QwenTtsServiceTest*' --tests '*LlmProviderConfigServiceTest*'
→ BUILD SUCCESSFUL
```

用真实 Key 端到端调用修复后的 `synthesize()`：

```text
elapsedMs=1469
pcmBytes=199680          # 199724 字节 WAV - 44 字节头
lastError=null
durationAt24kHzMono16bitSec=4.16
```

### 生效状态与建议

- **Java 代码改动需重启后端才生效**；当前运行的 PID 50992 仍是旧实现。
- 不需要改 `.env`、不需要改数据库、不需要换 Key、不需要动额度开关。
- 单句超时默认 8 秒（`APP_VOICE_TTS_TIMEOUT_SECONDS`），实测单次约 1.5 秒；若并发 3 路或网络较慢，可调到 `15`，该值同时影响 `OrderedTtsChunkEmitter` 的等待窗口。
- 遗留观察项：ASR 的「连接测试」目前仍只验证 TCP + 模型名格式，不做完整 WebSocket 握手与鉴权验证（Q11 已如实标注）。若要让设置页真正反映语音链路可用性，可后续补一个带真实 `session.update` 握手的 ASR 测试，以及一个真实合成的 TTS 测试接口。

---

## Q13：生产模式 `docker compose up -d --build` 拉取 `minio/minio` 失败

### 现象

```text
✘ Image minio/minio Error pull access denied for minio/minio, repository does not exist
! Image minio/mc    Interrupted
```

而 `docker compose -f docker-compose.dev.yml up -d` 之前一直正常。

### 结论：不是凭据、不是网络权限，是镜像仓库本身已下线

- MinIO 社区版自 2025-10 起停止发布免费 Docker 镜像 / 二进制，2026-04 官方仓库归档；Docker Hub 上的 `minio/minio`、`minio/mc` 已移除，`docker login` 无法解决。
- 「开发模式能跑」是因为 `docker-compose.dev.yml` 早就换成了 `rustfs/rustfs:latest`，只有主 `docker-compose.yml`（生产模式）还留在 MinIO，且 `createbuckets` 依赖同样下架的 `minio/mc`。
- 改用 `quay.io/minio/minio` 不是好方案：匿名拉取不稳定，还得为建桶再找 `mc` 镜像，等于同时维护两套存储后端。直接向开发模式对齐最省事。

### 已做的改动（`docker-compose.yml`）

| 位置 | 原来 | 现在 |
| --- | --- | --- |
| 对象存储服务 | `minio` / `minio/minio` | `rustfs` / `rustfs/rustfs:latest` |
| 建桶 init 容器 | `createbuckets` / `minio/mc` + `mc mb` | `storage-init` / 同一个 RustFS 镜像 + `curl --aws-sigv4` |
| 凭据变量 | `MINIO_ROOT_USER / MINIO_ROOT_PASSWORD` | `RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY`（唯一口径，compose 缺失即报错） |
| app 端点 | `http://minio:9000` | `http://rustfs:9000`，同时保留 `minio` 网络别名兼容存量配置 |
| 数据卷 | `minio_data` | `object_storage_data`，与开发 compose 的 `rustfs_data` 隔离 |

- 生产关闭 9001 Web 控制台，9000 只绑定 `127.0.0.1`；真正公网部署请删掉该端口映射。
- 为什么还需要 init 容器：`application-prod.yml` 里 `app.storage.auto-create-bucket: false`，且 `ProductionConfigurationValidator.validateObjectStorage()` 会拒绝把它改成 true，所以「谁建桶」必须由外部一次性任务完成。
- 为什么不用额外镜像：RustFS 镜像自带 curl 8.21，支持 `--aws-sigv4`。脚本先发签名 `HEAD /{bucket}`，200 就跳过、403 直接判凭据错误，否则签名 `PUT` 建桶；随后复核 `HEAD=200` 且匿名 `HEAD≠200`（默认 403），任一不满足即非零退出。`app.depends_on` 用 `service_completed_successfully` 等它成功。

### 顺带修掉的三个「换了镜像也起不来」的问题

1. **PNVS 凭据没进容器**：`.env` 里 `APP_AUTH_SMS_PROVIDER=pnvs`，但主 compose 的 `app.environment` 只声明了 `ALIYUN_SMS_*`，没有 `ALIYUN_PNVS_*`。`.env` 只参与 compose 插值、不会自动注入容器，于是 `AuthBootstrapValidator` 会报「PNVS 短信认证配置不完整」并拒绝启动。现已补 5 个变量。
2. **`PGVECTOR_INIT_SCHEMA` 默认值冲突**：compose 写死 `false`，而 `application-prod.yml` 默认 `true`；空库首次部署会缺 `vector_store` 表。已改为 `${PGVECTOR_INIT_SCHEMA:-true}`，与 profile 默认值一致。
3. **`.env.example` 的 Actuator 示例会触发启动失败**：原来建议 `ACTUATOR_ENDPOINTS=health,info`，但生产校验只允许 `health`。已改为只给 `health` 并写明约束。

### 验证结果

```text
docker compose -f docker-compose.yml config            → exit 0，嵌套插值/别名/依赖均正常
docker compose up -d rustfs storage-init
  interview-rustfs        → Healthy
  [storage-init] creating bucket 'interview-guide' (HEAD=404)
  [storage-init] bucket 'interview-guide' ready (anonymous HEAD=403)
  exit=0
再次执行（幂等）
  [storage-init] bucket 'interview-guide' already exists
  exit=0
```

### 仍存在的环境阻塞（不是代码问题）

- 当前机器访问 Docker Hub 不通：`registry-1.docker.io/v2/` 返回 `502 Bad Gateway`，直连 IPv6 超时，且 Docker Desktop 未配置 HTTPS 代理 / registry mirror。
- 三个**运行时**镜像 `pgvector/pgvector:pg16`、`redis:7`、`rustfs/rustfs:latest` 已本地缓存，依赖服务可以直接起；但 `--build` 需要拉 `gradle:8.14-jdk21`、`eclipse-temurin:21-jre`、`node:20-alpine`、`nginx:alpine`，目前会失败。
- 解决办法：在 Docker Desktop 配置可用的 HTTPS 代理或国内镜像加速并重启 Docker，或在网络恢复后预拉上述四个基础镜像，再执行 `docker compose up -d --build`。

### 生产模式启动前检查

- 先 `docker compose -f docker-compose.dev.yml down`：两个 compose 的 `container_name` 都是 `interview-postgres` / `interview-redis`，同时运行会冲突；项目名同为 `interview-guide`，postgres / redis 卷也是同一份数据，不要混着跑。
- prod 禁止 `mock` 短信；`CORS_ALLOWED_ORIGINS` 必须是 HTTPS 精确域名（非 localhost / IP）；`AUTH_JWT_SECRET` 至少 32 字节；`APP_AUTH_EXPOSE_DEBUG_CODE`、`APP_AUTH_DEV_ANONYMOUS_ADMIN` 必须为 false。
- 新增任何自定义环境变量时，记得在 `docker-compose.yml` 的 `app.environment` 里同步补一行，否则容器内读到的永远是空值。

## Q14：开发模式 `bootRun` 后端启动失败（追加）

### 现象

```text
> Task :app:bootRun FAILED
Caused by: BeanCreationException: Error creating bean with name 'flywayInitializer'
  → Connection to localhost:5432 refused
```

修好端口后可能变成第二个错误：

```text
FATAL: password authentication failed for user "postgres"
```

### 结论：不是代码问题，是依赖容器没按 `bootRun` 的方式启动，且 `.env` 写的是容器服务名

三个问题叠加：

1. 当时运行的是**生产 compose** 起的容器（只有容器网络端口，宿主机 5432 / 6380 均 CLOSED），宿主机 `bootRun` 连不上。
2. `docker-compose.dev.yml` 默认**不映射宿主机端口**；必须叠加 `docker-compose.dev.local.yml` 才会开放 5432 / 6380 / 9000 / 9001。
3. `.env` 里 `REDIS_HOST=redis`、`REDIS_PORT=6379`、`APP_STORAGE_ENDPOINT=http://rustfs:9000` 属于容器网络内的名字，宿主机无法解析；而 `app/build.gradle` 会把 `.env` 原样注入 JVM，**不做 `${...}` 插值**，所以 `APP_STORAGE_ACCESS_KEY=${RUSTFS_ACCESS_KEY}` 这类写法在 `bootRun` 下不可靠。

### 修复步骤

```bash
# 1. 切回开发依赖（同名容器重建，命名卷保留，数据不丢）
docker compose down
docker compose -f docker-compose.dev.yml -f docker-compose.dev.local.yml up -d
```

> `docker compose down` 需要 `CORS_ALLOWED_ORIGINS` 非空（主 compose 用 `${...:?}` 必填），所以该项不要注释掉。

2. `.env` 改成宿主机口径：`POSTGRES_HOST=localhost` / `5432`、`REDIS_HOST=localhost` / `6380`、`APP_STORAGE_ENDPOINT=http://localhost:9000`，`APP_STORAGE_ACCESS_KEY/SECRET_KEY` 写实际值 `rustfsadmin`。

3. 密码：`postgres_data` 卷是早期用另一个密码初始化的，而 `POSTGRES_PASSWORD` 只在**首次初始化**时生效，卷已存在时不会重写。用容器内本地 socket（trust 认证）重置，保留全部表和数据：

```bash
docker exec interview-postgres psql -U postgres -d interview_guide \
  -c "ALTER USER postgres WITH PASSWORD '123456';"
docker compose -f docker-compose.dev.yml -f docker-compose.dev.local.yml up -d postgres
```

> 注意容器内 `127.0.0.1` 是 trust 规则，随便给密码都能连，所以**不要**用容器内 `psql -h 127.0.0.1` 去"验证"密码；宿主机走的是 scram 规则。

4. `./gradlew :app:bootRun`

### 验证结果

```text
Started App in 16.355 seconds / Tomcat started on port 8080
curl /actuator/health  → {"status":"UP"}
curl /v3/api-docs      → 200（开发 profile 开启 SpringDoc）
RustFS 签名 HEAD /interview-guide → 200（dev profile 自动建桶）
```

### 附带修正

- `README.md`「启动依赖服务」补上 `dev.local.yml` 叠加用法与端口表，删除错误的 `localhost:6379` 说明。
- `.env.example` 的 `REDIS_PORT` 改为 `6380`；`CORS_ALLOWED_ORIGINS` 给出开发来源并写明生产必须换 HTTPS 域名。
- `.env.example` 与 `.env` 都注明 `bootRun` 不做变量插值，`APP_STORAGE_*` 必须写实际值。

---

## 排查命令速查

```bash
cd E:/project/interview-guide/interview-guide

# 从 .env 取变量（不回显明文）
KEY=$(awk -F= '$1=="PROVIDER_DASHSCOPE_API_KEY"{print substr($0,index($0,"=")+1)}' .env | tr -d '\r\n')
PHONE=$(awk -F= '$1=="APP_AUTH_BOOTSTRAP_PHONE"{print substr($0,index($0,"=")+1)}' .env | tr -d '\r\n')

# 1) 绕过应用，直接验证端点与 Key 是否有效
curl -sS -o /tmp/r.txt -w "HTTP=%{http_code}\n" \
  -X POST "https://llm-<workspace>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"model":"qwen3.5-flash","messages":[{"role":"user","content":"hi"}],"max_tokens":1}'
head -c 300 /tmp/r.txt

# 2) mock 登录拿 ADMIN token
curl -sS -X POST http://localhost:8080/api/auth/sms/send \
  -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\"}"     # 取 data.debugCode
curl -sS -X POST http://localhost:8080/api/auth/login/sms \
  -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"code\":\"<debugCode>\"}"  # 取 data.accessToken

# 3) 让应用自己测，拿到真实报错（关键一步）
curl -sS -X POST http://localhost:8080/api/llm-provider/dashscope/test \
  -H "Authorization: Bearer $TOKEN"

# 4) 看应用实际用的 Key 指纹，与 .env 对比
curl -sS http://localhost:8080/api/llm-provider/dashscope -H "Authorization: Bearer $TOKEN"
```

排查顺序建议：**先用 curl 直接打厂商端点**（区分「厂商/凭据问题」与「应用配置问题」）→ **再调应用的 `/test` 拿真实错误** → **最后比对应用回显的 maskedApiKey 与 `.env` 指纹**。

---

## 本次改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `app/src/main/resources/application.yml` | 配置 | dashscope `base-url` 改 `/compatible-mode/v1`；新增 `dev.anonymous-admin` |
| `app/src/main/resources/application-prod.yml` | 配置 | 生产强制 `anonymous-admin: false` |
| `modules/auth/config/SecurityConfig.java` | 代码 | 条件化 `/api/llm-provider/**` 授权 |
| `modules/auth/config/AuthBootstrapValidator.java` | 代码 | 非法组合启动即失败 |
| `modules/llmprovider/service/LlmProviderConfigService.java` | 代码 | 修正注释中错误的复数路径 |
| `.env` / `.env.example` | 环境 | 新增 `APP_AUTH_DEV_ANONYMOUS_ADMIN`（`.env` 被 gitignore，仅本地生效） |
| `llm_provider_config` 表 | 运行数据 | `base_url` 修正；dashscope `apiKey` 更新为新 Key |

Java 代码改动已通过 `./gradlew :app:compileJava`。**重启后端后所有改动才全部生效**；重启不会覆盖已修正的数据库配置（表非空，不触发 seed）。

---

## 配置变量重命名对照表（2026-11-18 追加）

后续统一变量口径后，历史记录中的旧名按下表换算；运行时取值未变。

| 旧名（已删除） | 当前名 | 说明 |
| --- | --- | --- |
| `AI_BAILIAN_API_KEY` | `PROVIDER_DASHSCOPE_API_KEY` | 与 `PROVIDER_KIMI_API_KEY` 等 Provider 命名体系统一 |
| `AI_MODEL` | `PROVIDER_DASHSCOPE_MODEL` | 明确是 DashScope 的对话模型，而非全局模型 |
| `MINIO_ROOT_USER` | `RUSTFS_ACCESS_KEY` | 对象存储已迁移到 RustFS，旧名指向已下架的 MinIO |
| `MINIO_ROOT_PASSWORD` | `RUSTFS_SECRET_KEY` | 同上 |
| `APP_VOICE_TTS_URL` | `TTS_PROBE_WS_URL`（仅手工探针测试） | 生产 HTTP TTS 使用固定端点，不再有可配置 URL |

同时删除的零引用项：`APP_AI_CONFIG_ENV_PATH`、`ALIYUN_SMS_LOGIN_ALERT_TEMPLATE`、`APP_VOICE_INTERVIEW_TTS_DEFAULT_VOICE`、`APP_VOICE_INTERVIEW_RATE_LIMIT_*`、`APP_VOICE_INTERVIEW_AUDIO_*`。
