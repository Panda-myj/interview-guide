# 微信登录扩展位设计（当前仅预留，未实现）

> 对应迁移 V8 / `IdentityProvider.WECHAT`。本文只定义契约，不引入微信 SDK、不实现 OAuth 回调。

## 1. 已落地的扩展位

- `auth_identities(id, user_id, provider, subject, verified_at, created_at, updated_at)`，
  `UNIQUE(provider, subject)`，`user_id` 外键 `ON DELETE CASCADE`。
- 手机号已降为一条 `provider=PHONE` 的身份记录；`users.phone` 放宽为可空的展示冗余字段，
  **身份唯一性只由 `auth_identities` 保证**。
- `users`、`auth_sessions`、`user_devices`、`login_notifications` 仍以 `users.id` 作为租户键，
  微信用户同样能拥有独立数据空间。
- 存量账号与引导管理员由 V8 幂等回填为 PHONE 身份。

## 2. 接入微信时需要实现的接口契约

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/auth/wechat/qrcode` | GET | 生成带 `state` 的二维码 URL；`state` 一次性、Redis 短 TTL（建议 5 分钟） |
| `/api/auth/wechat/status` | GET | 前端轮询：`WAITING / SCANNED / CONFIRMED / CANCELLED / EXPIRED` |
| `/api/auth/wechat/callback` | GET | 微信授权回调，校验 `state`，用 `code` 换 `access_token + openid` |
| `/api/auth/wechat/bind` | POST | 已登录用户绑定微信身份，需短信验证码二次确认 |
| `/api/auth/account/identities` | GET | 列出当前用户已绑定身份 |

回调成功后复用现有 `AuthService` 的会话签发能力：抽出发放双 Token、设备识别、
Refresh 轮换的公共方法，登录方式只负责「解析出 user」，不重复实现会话逻辑。

## 3. 身份绑定与会话撤销

- 微信 `openid`（跨应用用 `unionid`）作为 `provider=WECHAT` 的 `subject`，**绝不能写入 `users.phone`**。
- 绑定必须是幂等的：回调重放时按 `(provider, subject)` 命中同一用户，不创建第二个账号。
- 绑定/解绑关系变化后，按 `auth_sessions.family_id` 撤销该用户全部会话族，并把当前 JWT `jti`
  写入 `auth:jwt:deny:*`，避免旧令牌继续访问。
- 一个微信号只能绑定一个用户；一个用户当前只允许一个 PHONE 与一个 WECHAT 身份
  （`UNIQUE(provider, subject)` + 业务层 `findByUser_IdAndProvider` 校验）。
- 账号注销时 `auth_identities` 随 `users` 级联删除，PHONE 与 WECHAT 身份同时释放。

## 4. 通知渠道

登录提醒已改为站内通知（`login_notifications` + `read_at`），与短信通道解耦。
将来接微信模板消息时，应新建与 `SmsSender` 同级的 `NotificationSender` 策略，
由业务 Service 依赖抽象而非厂商 SDK，便于分别度量投递成功率。

## 5. 外部前置条件（当前不具备，故不实施）

- 微信开放平台**企业/组织主体**，个人主体无法创建「网站应用」；开发者资质认证 300 元/年。
- 网站应用审核通过后的 `AppID` / `AppSecret`。
- 已 ICP 备案的公网域名作为「授权回调域」，HTTPS、443 端口，不接受 localhost / IP / 端口。
- 公网可访问的服务端回调地址。

在此之前，`IdentityProvider.WECHAT` 只是一个枚举值，不含任何可调用入口。
