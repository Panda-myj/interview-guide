# 手机号登录与多用户隔离学习手册

本文档对应当前项目的认证实现，适合按顺序阅读和动手验证。

先建立最关键的一句话：**Access Token 是不携带用户敏感数据的短期签名 JWT；Refresh Token 才代表可撤销的长期登录会话。** 两者不是把同一份 JWT 复制两份，而是承担完全不同的职责。

## 1. 先理解双 Token：为什么不是只有一份 JWT

| 对比项 | Access Token | Refresh Token |
|---|---|---|
| 类型 | JWT（`JwtService.issue` 生成） | `SecureRandom` 生成的 32 字节 URL-safe Base64 随机串 |
| 外形 | Header.Payload.Signature 三段 | 一个不可猜测的长字符串 |
| 默认生存期 | 15 分钟 | 30 天 |
| 存储位置 | 前端内存变量；HTTP 放 `Authorization: Bearer` | HttpOnly Cookie，只对 `/api/auth` 暴露 |
| 服务端凭据 | 验签即可，本身无状态；可通过 Redis 黑名单撤销 | 只有 SHA-256 存于 `auth_sessions.refresh_token_hash` |
| 用途 | 验证身份、角色和所属会话 | 只用于调用 `/api/auth/refresh` 换新一对 Token |
| 轮换 | 签发时生成新的 `jti` | 每次刷新都生成新随机串，旧会话标记为已轮换 |
| 撤销 | Redis Key：`auth:jwt:deny:{jti}`，TTL 到 JWT 原过期时间 | 打上 `revoked_at`，并可按 `family_id` 撤销同一会话族 |

访问受保护接口时，服务端不会用 Access Token 去 Redis 查“登录状态”，它先用相同密钥验签，验证过期时间、`iss`、`aud` 和撤销标记。这就是 JWT 的轻量、可跨实例验证特性。但登出是立即操作，不能等 15 分钟自然过期，因此 `JwtService` 为 JWT 生成独立 `jti`，登出时把剩余有效期写进 Redis；`JwtDenylistValidator` 在解码阶段检查这个 Key。

Refresh Token 则必须查数据库，因为轮换取新、旧令牌重放检测和用户点“退出登录”都依赖服务端状态。把它存成 SHA-256，是让数据库即使泄露也不能直接用于刷新，也让登录服务只依赖不可逆摘要。

## 2. Access Token 的实际 JWT 结构

`JwtService` 负责构造 claim（[JwtService.java:28](app/src/main/java/interview/guide/modules/auth/security/JwtService.java:28)）：

```text
Header:
  alg = HS256
  typ = JWT

Payload:
  iss = ai-interview-platform
  aud = [ai-interview-web]
  sub = "<users.id>"                 // 租户隔离和鉴权身份的唯一来源
  iat = <签发时间>
  exp = <签发时间 + AUTH_ACCESS_TOKEN_TTL>
  jti = <每次签发都重新生成的 UUID>
  role = USER | ADMIN
  sid = <auth_sessions.id>          // 当前会话族 ID，不是 Refresh Token内容
```

有几个细节值得记住：

- 不写手机号、昵称和完整用户资料，减少泄露面。
- 写 `role` 可以让路由和过滤器直接判断角色，不必每请求加载用户；Active User 检查仍会补查数据库，避免禁用用户靠未过期 JWT 继续访问。
- `sub` 必须是当前登录用户的 `Long id`。业务代码应从 `CurrentUserService` 获取，而不是信任 `X-User-Id` 或任意 HTTP header。
- `jti` 让短期 JWT 也有服务端黑名单的抓手；`sid` 让 Access Token 能关联到生成它的 `auth_sessions` 行。

HS256 是对称签名：签发和验证使用同一个 `AUTH_JWT_SECRET`。因此该密钥只能通过环境变量或安全密钥服务注入，不能写进 `application.yml`、日志或前端。非对称算法（RS256/ES256）适合多服务验证场景，本项目目前是单体后端，使用 HS256 已经满足。

解码时，`JwtConfiguration.jwtDecoder` 做了项目要求的三重检查（[JwtConfiguration.java:56](app/src/main/java/interview/guide/modules/auth/config/JwtConfiguration.java:56)）：

1. Spring Security 的默认过期时间和 `iss` 检查；
2. `aud` 必须是 `ai-interview-web`；
3. `JwtDenylistValidator` 检查 `auth:jwt:deny:{jti}`。

验证通过后，`SecurityConfig.toAuthentication` 把 JWT 转换成 Spring Security 的 `AuthPrincipal`（[SecurityConfig.java:82](app/src/main/java/interview/guide/modules/auth/config/SecurityConfig.java:82)）。`AuthPrincipal` 包含 `userId`、角色、`sid`、`jti` 和 `exp`，不携带 Entity，因此不会让业务层绕过数据库检查。

## 3. 登录到 Refresh 的执行层

接下来四步串起真实代码调用：

- 登录时先创建数据库会话，再签发 JWT，并把两类凭据分配给不同存储层。
- 每个 HTTP 请求用 SecurityFilter 验证 JWT，再用 Active User Filter 确认用户仍在线状态有效。
- 页面刷新时用 Refresh Token 换取新的 JWT；服务端不记明文，只按可信度查数据库。
- 登出会同时撤销服务端 refresh 会话和当前 JWT，使前端立即断开。

每一步的具体调用都在下一节展开，代码以类名和行号嵌入。

## 4. 整体流程

```text
浏览器输入手机号
  -> POST /api/auth/sms/send
  -> AuthController 限流并校验手机号
  -> SmsCodeService 生成 6 位验证码并写入 Redis（只保存哈希）
  -> SmsSender 选择 Mock 或阿里云实现发送短信

浏览器提交验证码
  -> AuthService 校验 Redis 验证码，手机号统一成 +86 格式
  -> 未注册手机号在数据库事务中自动创建 USER
  -> 识别/创建设备记录，只在新浏览器或新 UA 时写 login_notifications
  -> 创建 auth_sessions，Refresh Token 只保存 SHA-256
  -> 根据用户、设备和当前 session 生成短期 JWT
  -> 返回 Access Token，并把 Refresh Token 写入 HttpOnly Cookie
  -> 事务提交后事件监听器把通知 ID 交给 Redis Stream
  -> 通知消费者发送短信并回写 sent_at；消费失败仍可在配置策略内重试

后续每个受保护 HTTP 请求只携带 JWT；Refresh Token 不会自动发送给普通接口，因为它的 Cookie 限定在 `/api/auth` 路径。
```

Access Token 只放在前端内存 snapshot，不写入 `localStorage`。页面刷新时，React 供应商初始化会调用 `/api/auth/refresh`；Refresh Token 由浏览器自动发送，前端只保存响应里新的短期 JWT 和用户资料。

### 登录时为 Token 选择正确的抽象

`AuthService.login` 先通过验证码确认手机号，用户不存在则自动注册，然后创建设备和会话，最后调用 `createResponse`（[AuthService.java:63](app/src/main/java/interview/guide/modules/auth/service/AuthService.java:63)、[AuthService.java:234](app/src/main/java/interview/guide/modules/auth/service/AuthService.java:234)）：

```text
auth_sessions:
  id            = 新 UUID => 生成 JWT 的 sid
  family_id     = 新 UUID => 同一次登录后续轮换的会话族
  refresh_token_hash = SHA-256(raw Refresh Token)
  user          = resolve 后的 UserEntity
  device        = resolve 后的 UserDeviceEntity

AuthResponse:
  accessToken = HS256 JWT，sub=users.id，role=USER，sid=auth_sessions.id
  expiresIn   = 900（秒）
  user        = { id, maskedPhone, displayName, role }
```

数据库行先保存，数据库事务提交后再发布“新设备登录”事件；短信不放进事务，而是通过 Redis Stream 异步执行。这样阿里云短信慢不影响登录提交，也不会因发送成功但事务回滚留下状态不一致。

### HTTP 请求时的 JWT 验证链

`SecurityConfig` 指定 `SessionCreationPolicy.STATELESS`，并依次使用以下组件：

```text
BearerTokenAuthenticationFilter
  -> NimbusJwtDecoder 验签、exp、iss、aud、jti 黑名单
  -> jwtAuthenticationConverter 生成 UsernamePasswordAuthenticationToken
  -> ActiveUserFilter 再次确认用户仍为 ACTIVE
  -> Route authorization（ADMIN / authenticated）
  -> Controller/Service 通过 CurrentUserService 读取 AuthPrincipal
```

Spring Security Stateless 配置的直接结果是：不会创建 HTTP Session，不会使用 Cookie `JSESSIONID`,也不存在“服务重启后 Session 丢失”。但这是针对 HTTP 认证会话说的，不是“整个登录体系无状态”；Refresh Session 和 Access Token 黑名单仍保存在服务端。

### 双 Token 刷新轮转

`AuthService.refresh` 的行为是整个机制中最需要掌握的（[AuthService.java:98](app/src/main/java/interview/guide/modules/auth/service/AuthService.java:98)）：

1. 计算 `SHA-256(raw Refresh Token)`，按索引查 `auth_sessions`。不存在、已被撤销或过期都返回同一业务错误 `12007`，避免泄露具体原因。
2. 如果会话已经轮换过：
   - 距离轮换不足默认 30 秒时允许旧令牌做一次提醒，但仍返回 401 让前端重新取新 Cookie；
   - 超过 30 秒继续携带旧令牌，按疑似重放处理，把整个 `family_id` 的未撤销会话统一撤销。
3. 用户或设备被禁用立即失效。
4. 生成一个新的 32 字节随机 Refresh Token 和新的 `auth_sessions` 行；两行共享同一个 `family_id`。
5. 旧行写入 `rotated_at`、`last_used_at`、新的 `replaced_by_session_id`。
6. 为新会话签发新的 15 分钟 JWT。

```text
第一次刷新：
  old session.revoked_at=null, rotated_at=null
    -> new session(hash=B, family=F)
    -> old session.rotated_at=now, replaced_by_session_id=new session.id

30 秒内同一旧令牌重试：
  查 old -> rotated_at != null -> 返回失效，但不撤销 family

30 秒后同一旧令牌重试：
  查 old -> 超过宽限 -> revokeFamily(family=F) -> 返回失效
```

这个 30 秒不是令牌有效时间，而是多标签页/多组件同时发现 401 的协调窗口。前端所有标签页通过 `navigator.locks.request('ai-interview-auth-refresh', ...)` 排队，并使用同一个 `refreshPromise`；只发起一次 `POST /api/auth/refresh`。拿到新 Refresh Cookie 后，通过 `BroadcastChannel` 同步内存中的 Access Token，因此通常不会有第二个标签页再把刚轮换掉的旧令牌提交给服务端。

### 退出登录如何同时撤销两 Token

1. Controller 从 HttpOnly Cookie 读取当前 Refresh Token，Service 验证它属于当前 `AuthPrincipal` 用户后写 `revoked_at`。
2. Service 计算当前 Access Token 的剩余秒数，在 Redis 写 `auth:jwt:deny:{jti}`，TTL 就是剩余时间。
3. Controller 立即将 Refresh Cookie 的 `max-age` 设为 0。
4. 前端清空内存 snapshot 并广播 logout。

即使 Access Token 已经被发到攻击者手里，服务端解码时也会发现 `jti` 在黑名单中。即使用户只清空浏览器 Cookie 没有调用 logout，短期 JWT 最多 15 分钟失效；调用 logout 才能立刻终止会话。

### 前端登录态恢复细节

`AuthProvider` 初始化时不管内存里是否已有 Access Token，都会调用一次 `refreshAccessToken()`；`loading=true` 时页面应显示加载态。Axios 请求拦截器为每个请求加 `Bearer` 内存 JWT；响应 401 且不是刷新接口时，先用 Web Lock 刷新，然后重发原请求一次。若此时其他标签页已完成刷新，`waitForAccessToken` 会借用广播得到的新 token。操作面包括下列文件，便于跟着调用链阅读：

- [frontend/src/auth/session.ts](frontend/src/auth/session.ts)：内存 snapshot、订阅、广播；
- [frontend/src/auth/AuthProvider.tsx](frontend/src/auth/AuthProvider.tsx)：初始化静默刷新；
- [frontend/src/api/request.ts](frontend/src/api/request.ts)：Axios 注入、Web Lock 和 401 重试。

普通 API 调用不会因为 Cookie 是 HttpOnly 就自动携带；只有 `withCredentials=true` 且满足同站/同域规则时浏览器才会发送它。本地开发通过同域代理解决；生产必须启用 HTTPS 和 `secure-cookies=true`。

## 5. 关键类和职责

| 类 | 具体职责 | 读代码时的重点 |
|---|---|---|
| `AuthController` | 验证码、登录、刷新、登出、当前用户、WS 票据接口 | Token 入 HttpOnly Cookie，JWT 只在 JSON / `Authorization` 里 |
| `SecurityConfig` | 无 Session、公开路径、角色规则、JWT 资源服务器 | 身份只从 Access Token 转换，不接受请求头 X-User-Id |
| `JwtConfiguration` | 构建 HS256 `SecretKey`、Encoder、Decoder、Validator 链 | 密钥至少 32 字节；prod 不配置密钥直接启动失败 |
| `JwtService` | 生成 Access Token 的 claim 与签名 | `sub`、`role`、`sid`、`jti` 是租户和撤销的核心 |
| `JwtDenylistValidator` | 解码后检查已登出的 `jti` | Redis Key TTL 就是 Access Token 剩余时间 |
| `AuthService` | 登录、自动注册、设备识别、Refresh 轮转、登出 | `refresh()` 是理解令牌重放的关键方法 |
| `AuthSupport` | 手机号规范化、SHA-256、UA 脱敏 | 验证码使用 `SHA-256(secret:phone:code)` 防彩虹表 |
| `SmsCodeService` | Redis 验证码、冷却、日限、失败次数 | Bucket / AtomicLong 只存哈希或整数 |
| `SmsSender` / `MockSmsSender` / `AliyunSmsSender` | 短信发送策略接口 | Mock 生产禁止；发送异常须清理 Redis 验证码 |
| `CurrentUserService` | 从 SecurityContext 取得 `AuthPrincipal` / `UserEntity` | 业务通过 `getRequiredUserId()` 得到租户 ID |
| `ActiveUserFilter` | JWT 有效后再确认 Entity 仍 ACTIVE | 禁用用户不能靠最长 15 分钟 JWT TTL 继续访问 |
| `AuthCookieService` | Refresh / 设备 / WS Cookie | HttpOnly、SameSite=Lax、生产 Secure、路径隔离 |
| `AuthBootstrapValidator` | 生产启动校验引导手机号、短信配置 | 配置错误应尽早失败，不能运行中才暴露 |
| `LoginNotificationConsumer` | 消费 Redis Stream 发送提醒并更新状态 | 登录事务提交后才允许发送提醒 |
| `VoiceInterviewHandshakeInterceptor` | 验证一次性 WebSocket 凭证和语音会话归属 | ticket 取出即删除；查询也带 owner |

浏览器原生 WebSocket 不能可靠设置 `Authorization` 头，把 JWT 放 URL 又会进入访问日志或 Referrer。因此项目先签发 60 秒 ticket，握手时把 ticket 从 Redis 删除，再解析 `user_id + voice_session_id`，调用 `findByIdAndOwnerUserId` 验证归属。

## 6. 认证 Redis Key

分配消息的来源分别是 `SmsCodeService`、`AuthService`、`JwtDenylistValidator` 和 WebSocket 票据逻辑。

| Key | 内容 | TTL | 保护目标 |
|---|---|---|---|
| `auth:otp:{phone}:cooldown` | `RAtomicLong` 发送冷却值 | 60 秒 | 防止重复触发短信费用 |
| `auth:otp:{phone}:daily:{date}` | 当日发送次数 | 1 天 | 账号级轰炸限制 |
| `auth:otp:{phone}:code` | `SHA-256(secret:phone:code)` | 5 分钟 | Redis 泄露不能直接登录 |
| `auth:otp:{phone}:attempts` | 当前验证码错误次数 | 与验证码同 TTL | 暴力尝试后作废验证码 |
| `auth:jwt:deny:{jti}` | Access Token 登出标记 | JWT 剩余时间 | 不等 15 分钟自然过期 |
| `auth:ws-ticket:{SHA-256(ticket)}` | `user_id:voice_session_id` | 60 秒，取出即删 | WebSocket 短证、一次使用 |

发送验证码和登录受 `@RateLimit` 保护，缺省上界分别见 `AuthController.sendCode` 和 `AuthController.login`；认证相关配置的实际值由 `AuthProperties` 利用环境变量映射。Redis 发送失败会造成拒绝操作，不能吞掉异常后当作没有黑名单继续。

## 7. Token 安全与配置

认证配置集中在 `AuthProperties`，环境变量映射在 [application.yml](app/src/main/resources/application.yml:106) 的 `app.auth` 段。

| 配置 | 默认值 | 作用 |
|---|---:|---|
| `AUTH_JWT_SECRET` | 空 | HS256 密钥；不足 32 UTF-8 字节或 prod 为空时启动失败 |
| `AUTH_ACCESS_TOKEN_TTL` | `15m` | 短期 JWT 生存时间 |
| `AUTH_REFRESH_TOKEN_TTL` | `30d` | `auth_sessions.expires_at` 和 Refresh Cookie TTL |
| `AUTH_REFRESH_REUSE_GRACE` | `30s` | 多标签页轮换协调窗口，不是旧令牌长期有效 |
| `AUTH_DEVICE_COOKIE_TTL` | `180d` | 设备持久 Cookie TTL |
| `AUTH_WS_TICKET_TTL` | `60s` | WebSocket 短生命周期凭证 |
| `AUTH_SECURE_COOKIES` | 开发 false / 生产 true | Refresh Cookie 仅 HTTPS 发送 |
| `APP_AUTH_BOOTSTRAP_PHONE` | 空 | Flyway 将旧数据归入引导管理员 |
| `APP_AUTH_SMS_PROVIDER` | `mock` | 策略选择；生产禁止 Mock |
| `AUTH_SMS_CODE_TTL` / `AUTH_SMS_DAILY_LIMIT` / `AUTH_SMS_MAX_ATTEMPTS` | `5m` / `5` / `5` | 验证码生存时间、发送上限、错误上限 |

生产部署还必须满足：

- 通过密钥管理服务或安全环境变量提供新的多服务共享密钥；
- 启用 HTTPS；否则浏览器不会接受 `Secure` Refresh Cookie；
- `APP_AUTH_SMS_PROVIDER=aliyun` 且所有短信模板配置完整；
- Redis 的持久化、访问控制与告警可用；
- JWT、验证码会话到期与用户禁用优先级经过验证。

不要把 JWT、Refresh Token 或完整手机号打印到日志；`AuthResponse.user` 手机字段是掩码值，验证码错误日志也应先由 `SmsCodeService` 做掩码。不要把 JWT 放 URL、localStorage 或前端请求体。

## 8. 数据库归属规则

- 一级资源保存 `owner_user_id`：简历、知识库、文字面试、语音面试、RAG 会话、面试日程。
- 答案、消息和评估结果通过父资源确定归属，不重复保存用户 ID。
- 认证侧的 `Users` 是租户身份表，`auth_sessions` 保存设备级登录会话，不属于业务资源。
- 简历和知识库唯一键为 `(owner_user_id, file_hash)`，不同用户上传同一文件不会互相命中。
- 向量元数据保存 `user_id`，查询同时过滤用户和知识库。
- 所有按 ID 的读取、更新、删除都必须带当前用户条件；越权访问返回“资源不存在”。

### 当前代码中的隔离写法

下面都是项目实际采用的模式：

```sql
-- 主键加 owner；越权与不存在返回同一业务语义
findByIdAndOwnerUserId(Long id, Long ownerUserId)

-- owner 过滤进入 SQL，不把全部行放进 JVM 再过滤
@Query("select b.category, count(b) ... where b.ownerUserId = :ownerUserId")

-- 创建时同时传入当前用户
findByOwnerUserIdAndFileHash(ownerUserId, fileHash)
```

例如 [InterviewScheduleRepository](app/src/main/java/interview/guide/modules/interviewschedule/repository/InterviewScheduleRepository.java) 的自定义查询同时约束 `id` 和 `ownerUserId`。业务代码不要写成“先按 ID 查、Entity 不存在再返回 404”的宽泛查询，避免 ID 枚举和跨用户探测。

服务层每次调用 `currentUserService.getRequiredUserId()` 时必须区分来源：

- HTTP 主线程：来自 Spring SecurityContext 的 `AuthPrincipal.userId`；
- 异步消费者：不能引用 HTTP SecurityContext，通常从数据库行恢复 `userId`/tenant；
- WebSocket：握手拦截器验证 ticket 后写入连接 attribute，不把 JWT claim 放进消息体；
- Redis 任务：恢复关联实体后再应用 owner 规则。

## 9. 本地开发

在 `.env` 中至少配置：

```dotenv
AUTH_JWT_SECRET=替换为至少32字节的随机字符串
APP_AUTH_BOOTSTRAP_PHONE=+8618913371131
APP_AUTH_SMS_PROVIDER=mock
APP_AUTH_EXPOSE_DEBUG_CODE=true
```

生成 JWT 密钥的示例：

```bash
openssl rand -base64 48
```

启动依赖和后端：

```bash
docker compose -f docker-compose.dev.yml up -d
./gradlew :app:bootRun
```

Mock 模式下，发送验证码接口的 `debugCode` 会直接显示在登录页面。该字段在生产 Profile 中关闭。

## 10. 接口练习

发送验证码：

```bash
curl -c cookies.txt -H "Content-Type: application/json" \
  -d '{"phone":"13800138000"}' \
  http://localhost:8080/api/auth/sms/send
```

登录：

```bash
curl -b cookies.txt -c cookies.txt -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","code":"123456"}' \
  http://localhost:8080/api/auth/login/sms
```

把响应中的 `accessToken` 放入变量后访问受保护接口：

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/api/auth/me
```

刷新 Access Token：

```bash
curl -b cookies.txt -c cookies.txt -X POST \
  http://localhost:8080/api/auth/refresh
```

退出登录：

```bash
curl -b cookies.txt -c cookies.txt -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/api/auth/logout
```

## 11. 为什么这样设计

- Access Token 短、只在内存：降低 XSS 窃取 Refresh Token 的风险。
- Refresh Token 不可读、只存哈希：数据库泄露时不能直接拿 Cookie 内容登录。
- 每次刷新轮换：旧令牌重放可以被检测并撤销整个会话族。
- 新设备提醒只发首次/新浏览器：避免每次登录产生短信费用和打扰。
- 登录事务只写数据库；短信 HTTP 调用交给 Redis Stream：外部服务慢或失败不会卡住登录。
- WebSocket 使用 60 秒一次性 Cookie 凭证：浏览器原生 WebSocket 不能安全设置 Authorization 头，也不把 JWT 放进 URL。
- 刷新后新旧会话共享 family：可以撤销某次登录的所有轮换令牌，而不影响同账号其他设备。
- 主键 + owner 的 SQL 只在两者同时成立时返回资源：目标用户无法判断某个 ID 是否真实存在。

## 12. 常见问题

### 页面刷新后短暂显示登录页

`AuthProvider` 会先执行一次静默刷新；守卫在 `loading=true` 时只显示加载状态，不应提前跳转。

### 多个标签页同时刷新导致 401

前端使用 Web Locks 串行化刷新，并通过 BroadcastChannel 同步 Access Token。服务端对 30 秒内的轮换令牌重复使用不会立即撤销会话族。

### 生产启动提示缺少配置

生产 Profile 强制要求 `AUTH_JWT_SECRET`、`APP_AUTH_BOOTSTRAP_PHONE` 和阿里云短信配置；Mock Provider 在生产禁止启动。

### 老数据登录后看不到

Flyway `V2` 会把已有数据归入 `APP_AUTH_BOOTSTRAP_PHONE` 对应的管理员。该变量首次迁移后不要依赖修改来重新分配数据。
后续若需要更换管理员手机号，本地 Mock 模式由 `V9` 幂等收敛：提升目标账号、把全部文件与面试记录改挂到该账号、注销其余账号并清空旧会话；真实短信通道不会执行 `V9`。

## 13. 后续微信扩展

建议新增独立的用户身份绑定表，例如 `auth_identities(user_id, provider, subject, verified_at)`，微信 OpenID 作为 `provider=WECHAT` 的身份标识。不要把微信 OpenID 直接塞进手机号字段。通知渠道继续使用 `SmsSender` 同级的 `NotificationSender` 策略，避免业务 Service 依赖具体厂商。

微信扩展还应该考虑：

- 独立 `auth_identities`，手机、用户名与微信 OpenID 不应混在同一字段；
- 身份绑定关系变化后按会话族撤销；
- 绑定流程需幂等回调与设备确认；
- 通知渠道共用 `NotificationSender`，可分别度量短信和微信投递。

本文档对应 2026-11-06 当前工作区实现。只验证认证逻辑时执行：

```bash
./gradlew :app:test --tests "interview.guide.modules.auth.*" --no-daemon
```
