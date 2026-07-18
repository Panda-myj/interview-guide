package interview.guide.common.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一封装结构化输出调用与重试策略。
 * <p>
 * 结构化输出调用器，让 LLM 返回严格 JSON，带重试（最多 N 次）+ JSON 修复
 * ⭐⭐⭐ Agent 项目必须解决的问题：LLM 输出不稳定，需要重试+修复机制
 * <p>
 * 第一层：业务痛点（为什么要写这段代码？）
 * 面试/学习切入点：LLM 原生输出是概率性的，永远不可信。
 * 痛点 1：即使 Prompt 要求输出 JSON，模型仍会返回 Markdown 或夹杂解释文字。
 * 痛点 2：模型返回的 JSON 内部字符串常包含未转义的双引号（如 {"name": "O"Reilly"}），导致 Jackson/Gson 直接报错。
 * 痛点 3：一次失败就中断流程，在 AI 应用中不可接受。
 * 结论：这段代码是AI 应用中的“防崩溃保险丝”。
 */
@Component
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
            请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
            1) 不要输出 Markdown 代码块（如 ```json）。
            2) 不要输出任何解释文字、前后缀、注释。
            3) 所有字符串内引号必须正确转义。
            """;

    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    private final boolean schemaValidationEnabled;
    private final MeterRegistry meterRegistry;

    public StructuredOutputInvoker(
            StructuredOutputProperties properties,
            @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.schemaValidationEnabled = properties.isStructuredSchemaValidationEnabled();
        this.meterRegistry = meterRegistry;
    }


    /*第二层：核心骨架（主干流程 invoke 方法）
    不要先从辅助方法看起，先死磕主干。invoke 方法定义了一个标准的重试模板：
    入口：接收 ChatClient、SystemPrompt、UserPrompt 和 BeanOutputConverter。
    安全加固：强制在系统提示词后拼接防注入指令（ANTI_INJECTION_INSTRUCTION）。
    重试闭环：for (attempt=1; attempt<=maxAttempts; attempt++)。
    第 1 次用原始 Prompt；
    后续重试用 buildRetrySystemPrompt（带上上次的错误信息）。
    结果返回：成功即返回，失败则抛出封装好的 BusinessException。
    结构化记忆：这个骨架就是 “装饰器模式 + 模板方法” 的结合体——它把不稳定的 chatClient.call() 装饰成了稳定可靠的 invoke()
    */
    public <T> T invoke(
            ChatClient chatClient,
            String systemPromptWithFormat,
            String userPrompt,
            BeanOutputConverter<T> outputConverter,
            ErrorCode errorCode,
            String errorPrefix,
            String logContext,
            Logger log
    ) {
        /*在Java中，System.currentTimeMillis() 和 System.nanoTime() 都能获取时间，但用途和特性完全不同。
        前者返回自1970-01-01 UTC以来的毫秒数，基于系统时钟，适合记录绝对时间（如日志、业务时间戳）；
        后者返回高精度、单调递增的纳秒计数，基于系统计时器，适合测量时间间隔。*/
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        String securedSystemPrompt = systemPromptWithFormat
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;/*安全加固*/
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptSystemPrompt = attempt == 1
                    ? securedSystemPrompt
                    : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                T result = callStructuredOutput(
                        chatClient, attemptSystemPrompt, userPrompt, outputConverter, logContext, log);
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE);
                if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                            logContext, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                            logContext, maxAttempts, e.getMessage());
                }
            }
        }

        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        throw new BusinessException(
                errorCode,
                errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    private <T> T callStructuredOutput(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            BeanOutputConverter<T> outputConverter,
            String logContext,
            Logger log
    ) {
        var call = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call();
        if (schemaValidationEnabled) {
            return call.entity(outputConverter, spec -> spec.validateSchema());
        }
        String content = call.content();
        return convertWithRepair(content, outputConverter, logContext, log);
    }


    /*这是面试官最爱问的地方：“LLM 返回的不是有效 JSON 你怎么处理？” 代码给了 两层防线：
防线层级	触发时机	核心逻辑（方法）
Prompt 层防御	重试时	buildRetrySystemPrompt：追加“仅返回合法 JSON”指令，并将上次的报错信息（Error Message）回填给 LLM，让模型自我纠错（这是目前业界最有效的 SOTA 手法）。
本地修复层防御	解析抛异常时	convertWithRepair + repairUnescapedQuotesInJsonStrings：利用有限状态机（FSM）逐字符扫描，识别 JSON 字符串边界，自动在内部未转义的双引号前加 \。
学习重点：repairUnescapedQuotesInJsonStrings 方法中维护了 inString 和 escaping 两个布尔变量，这就是手写简易 JSON Lexer（词法分析器） 的体现——不依赖第三方修复库，用极低的成本解决了 80% 的格式乱码问题。*/
    private <T> T convertWithRepair(
            String content,
            BeanOutputConverter<T> outputConverter,
            String logContext,
            Logger log
    ) {
        try {
            return outputConverter.convert(content);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);//修复未转义
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /*问题一：重试全失败后，如何“强行读取部分字段”做兜底？（Partially Parsing）
    ❌ 低级回答：“就在 catch 里返回个 null 或者空对象。”
    ✅ 高级回答：“在最后一次异常抛出前，引入 ‘Best-Effort Partial Extraction’（尽力部分提取） 模式，保证 Agent 的思考链路不中断。”

    “当 LLM 连续 3 次返回的 JSON 都无法完整解析时，我不会直接抛出 BusinessException 杀死主流程。
    我会在 convertWithRepair 的 catch 块中，利用 Jackson 的 JsonParser 或 TreeModel 尝试将字符串转化为 JsonNode。
    能读取到的字段（如 action 或 intent）就保留，缺失或报错的字段（如 parameters）填充为安全的默认值或错误占位符。
    最后，我会将‘解析状态码（如 PARTIAL_SUCCESS）’和‘原始报错信息’一并封装进 Result 对象返回给上层 Agent，由 Agent 决定是自我修正（Re-prompt）还是转人工。”

    为什么这个优化在 Agent 场景极其重要？
    在 ReAct（推理+行动） 模式下，Agent 的一次工具调用（Tool Call）可能包含 {"tool": "search_weather", "params": {"city": "北京"}}。
    如果 params 里的 city 解析失败，但你成功读出了 tool，你完全可以让 Agent 不报错，而是返回“请补充城市名称”的结果给 LLM，让它多轮对话修正，而不是直接崩溃。这叫做 “容错性对话恢复”。
    */

    // 在 convertWithRepair 的 catch (Exception firstError) 内部末尾加入：
//    private <T> T convertWithRepair(...) {
//        try {
//            return outputConverter.convert(content);
//        } catch (Exception firstError) {
//            // 1. 尝试修复引号 (现有逻辑)
//            String repaired = repairUnescapedQuotesInJsonStrings(content);
//            try { return outputConverter.convert(repaired); } catch (Exception e) { /*忽略*/ }
//
//            // 2. ⭐ 新增：部分提取兜底（Partial Extraction）
//            if (isLastAttempt) { // 通过外层传入 attempt 计数器判断
//                try {
//                    // 重点：转为通用 JsonNode，不走强类型 Bean 转换
//                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(content);
//                    T partialResult = buildPartialResult(root, outputConverter.getTargetType());
//                    log.warn("使用部分解析结果，缺失字段已填充默认值");
//                    return partialResult;
//                } catch (Exception partialEx) {
//                    firstError.addSuppressed(partialEx);
//                }
//            }
//            throw firstError; // 实在不行才最终抛出
//        }
//    }

    /*手写json lexer词法分析器，简易，有缺陷，不能替代完整的分隔符*/
    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
                .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                    .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }
    // 错误信息截断（sanitizeErrorMessage）：把换行替换为空格并截断长度，防止 LLM 返回的超长错误日志导致监控系统 OOM。
    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    /*第四层：可观测性与兜底（非功能性需求）
优秀的基建代码必须“可监控、可降级”：
指标埋点（Metrics）：记录了 invocations（调用总次数）和 attempts（重试次数），并区分 success/failure 状态。注意 recordLatency 使用了 System.nanoTime()，极其严谨。
上下文标签归一化（normalizeContextTag）：这是容易被忽略的优雅细节。它将业务传入的 logContext（如“用户下单”）通过 正则 + 小写 + 去重下划线 + 截断，确保流入 MeterRegistry 的 Tag 符合 Prometheus 规范（防止高基数爆炸）。

错误信息截断（sanitizeErrorMessage）：把换行替换为空格并截断长度，防止 LLM 返回的超长错误日志导致监控系统 OOM。*/
    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
                METRIC_ATTEMPTS,
                Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
                .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    /*上下文标签归一化（normalizeContextTag）：这是容易被忽略的优雅细节。它将业务传入的 logContext（如“用户下单”）
    通过 正则 + 小写 + 去重下划线 + 截断，确保流入 MeterRegistry 的 Tag 符合 Prometheus 规范（防止高基数爆炸）。*/
    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}

/*
第五层：设计模式与架构抽象（举一反三）
学完具体代码，要提炼出可复用的设计思想：

依赖倒置（DIP）：通过 StructuredOutputProperties 将所有硬编码参数（重试次数、是否开启修复）抽离，便于不同业务线调优。

策略模式（修复策略）：通过 schemaValidationEnabled 开关，决定走 call.entity(spec.validateSchema())（强校验）还是 convertWithRepair（柔性修复）。

资源清理与超时：虽然没有显式写出，但 startNanos 的设计暗示了将来可以扩展为“超时熔断”。
*/
