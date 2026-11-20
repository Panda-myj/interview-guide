# 🔑 API 密钥配置指南

语音面试功能需要配置以下 API 密钥才能正常工作。

## 📋 必需的 API 服务

### 阿里云百炼 AI (DashScope)

**用途**:
- **LLM 对话**: 生成面试问题、评估回答
- **ASR 语音识别**: 将用户语音实时转换为文本（qwen3-asr-flash-realtime）
- **TTS 语音合成**: 将 AI 回答转换为语音（qwen3-tts-flash，官方 HTTP 端点）

**统一 API Key**: 一个密钥即可使用所有功能，无需分别申请！变量名统一为 `PROVIDER_DASHSCOPE_API_KEY`。

**获取步骤**:
1. 访问 [阿里云百炼平台](https://bailian.console.aliyun.com/)
2. 登录/注册阿里云账号
3. 开通 DashScope 服务（有免费额度）
4. 创建 API Key
5. 复制 API Key

**配置变量**:
```bash
PROVIDER_DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

**费用**:
- 新用户有免费额度
- LLM（qwen3.5-flash 模型）: 请以阿里云最新价格页为准
- ASR 语音识别: ¥2.4/小时（实际使用流式服务）
- TTS 语音合成: ¥2/百万字符

---

## ⚙️ 配置步骤

### 方式 1: 使用 .env 文件（推荐）

1. 复制示例配置文件：
```bash
cp .env.example .env
```

2. 编辑 `.env` 文件，填入您的实际密钥：
```bash
# 使用您自己的密钥替换以下占位符
PROVIDER_DASHSCOPE_API_KEY=sk-your-actual-key-here

# 可选：选择不同的 LLM 模型
PROVIDER_DASHSCOPE_MODEL=qwen3.5-flash  # 默认值，也可改为 qwen3.5-plus、qwen3.5-122b-a10b 等当前工作空间已开通的模型
```

3. 启动应用时会自动读取 `.env` 文件

### 方式 2: 使用环境变量

```bash
# Linux/Mac
export PROVIDER_DASHSCOPE_API_KEY=sk-your-key
export PROVIDER_DASHSCOPE_MODEL=qwen3.5-flash  # 可选

# Windows PowerShell
$env:PROVIDER_DASHSCOPE_API_KEY="sk-your-key"
$env:PROVIDER_DASHSCOPE_MODEL="qwen3.5-flash"  # 可选
```

### 方式 3: 在 IDE 中配置

**IDEA**:
1. Run → Edit Configurations
2. 选择 Spring Boot 配置
3. Environment variables 中添加上述变量

**VS Code**:
1. 创建 `.vscode/launch.json`
2. 添加 env 配置

---

## 🔍 验证配置

启动应用后，检查日志：

```
✅ 成功日志示例:
QwenAsrService initialized with model: qwen3-asr-flash-realtime
QwenTtsService initialized with HTTP model: qwen3-tts-flash, voice: Cherry, sampleRate: 24000Hz
DashScope LLM service initialized

❌ 失败日志示例:
WebSocket failed: Expected HTTP 101 response but was '401 Unauthorized'
（说明 API Key 无效或未配置）
```

---

## 💡 成本优化建议

1. **使用免费额度**: 新用户都有免费试用额度
2. **限制并发**: 配置 `rate-limit` 参数控制并发数
3. **选择合适模型**:
   - 默认 `qwen3.5-flash`；旧文档推荐的 `qwen-turbo` 在部分工作空间已下线（404 Model not exist），请勿直接照抄
   - 生产环境优先按场景选择，并确认模型在当前业务空间已开通
4. **控制面试时长**: 通过 `plannedDuration` 参数限制面试时长

---

## 🆘 常见问题

**Q: 必须使用阿里云吗？**

A: 目前 LLM 支持多家提供商（DashScope / Kimi / DeepSeek / GLM / LM Studio），但语音服务使用 Qwen3 模型，需要阿里云 DashScope API Key。

**Q: 如何降低成本？**

A: 1) 使用 `qwen3.5-flash` 等当前默认模型；2) 限制面试时长；3) 添加用户配额限制

**Q: API 密钥会泄露吗？**

A: `.env` 文件已加入 `.gitignore`，不会提交到 Git。请妥善保管您的密钥。

**Q: 测试时需要付费吗？**

A: 阿里云新用户有免费额度，足够测试使用。正式上线后再考虑付费。

**Q: 一个 API Key 真的够用吗？**

A: 是的！LLM、实时 ASR、HTTP TTS 共用一个 DashScope API Key，无需分别申请。

---

## 📞 获取帮助

- 阿里云文档: https://help.aliyun.com/
- DashScope 文档: https://help.aliyun.com/zh/dashscope/
- Qwen3 实时语音文档: https://help.aliyun.com/zh/model-studio/realtime-api-reference
