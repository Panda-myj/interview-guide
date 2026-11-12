package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiCallClassifier;
import interview.guide.common.ai.AiCircuitBreaker;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.RagContextBuilder;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;
    /** 知识库问答的熔断键：同一场景共用一条熔断窗口。 */
    private static final String RAG_CIRCUIT_KEY = "knowledgebase_answer";
    /** 引用 ID 形如 [S1]、[S12]。 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S(\\d+)]");
    /** 流式回答只保留前 4000 字用于引用统计，避免长回答占用内存。 */
    private static final int MAX_CITATION_SCAN_CHARS = 4000;

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;
    private final RagContextBuilder ragContextBuilder;
    private final AiCircuitBreaker circuitBreaker;
    private final RagMetrics ragMetrics;
    private final int maxQuestionChars;
    private final int maxHistoryMessages;
    private final int maxHistoryMessageChars;

    @Autowired
    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader,
            PromptSanitizer promptSanitizer,
            AiCircuitBreaker circuitBreaker,
            RagMetrics ragMetrics) throws IOException {
        this(llmProviderRegistry, vectorService, listService, countService, queryProperties,
            resourceLoader, promptSanitizer, circuitBreaker, ragMetrics, queryProperties.getContext());
    }

    /**
     * 兼容旧装配方式（单测无 Spring 容器时使用）：不做 Prompt 净化、熔断与指标采集。
     */
    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this(llmProviderRegistry, vectorService, listService, countService, queryProperties,
            resourceLoader, null, null, null, queryProperties.getContext());
    }

    private KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader,
            PromptSanitizer promptSanitizer,
            AiCircuitBreaker circuitBreaker,
            RagMetrics ragMetrics,
            KnowledgeBaseQueryProperties.Context context) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
        this.ragContextBuilder = new RagContextBuilder(
            promptSanitizer,
            context.getMaxDocs(),
            context.getMaxCharsPerDoc(),
            context.getMaxTotalChars());
        this.circuitBreaker = circuitBreaker;
        this.ragMetrics = ragMetrics == null ? RagMetrics.disabled() : ragMetrics;
        this.maxQuestionChars = context.getMaxQuestionChars();
        this.maxHistoryMessages = Math.max(0, queryProperties.getHistory().getMaxMessages());
        this.maxHistoryMessageChars = context.getMaxHistoryMessageChars();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
        }
        listService.requireOwned(knowledgeBaseIds);

        String kbTag = RagMetrics.kbTag(knowledgeBaseIds);
        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        if (!hasEffectiveHit(relevantDocs)) {
            ragMetrics.recordResponse(kbTag, "no_result");
            return NO_RESULT_RESPONSE;
        }

        // 计数语义：只有真正检索到有效内容并参与回答时才 +1。
        // 放在检索之后，保证检索失败不会留下与结果不一致的计数。
        countService.updateQuestionCounts(knowledgeBaseIds);

        List<RagContextBuilder.RagSnippet> snippets = ragContextBuilder.buildSnippets(relevantDocs);
        String context = ragContextBuilder.renderContext(snippets);
        String sourceFooter = ragContextBuilder.renderSourceFooter(snippets);

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        acquireCircuit();
        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);
            recordCircuitSuccess();

            if (NO_RESULT_RESPONSE.equals(answer)) {
                ragMetrics.recordResponse(kbTag, "no_result");
                return answer;
            }
            recordCitation(kbTag, answer, snippets);
            ragMetrics.recordResponse(kbTag, "answer");

            log.info("知识库问答完成: kbIds={}, sources={}", knowledgeBaseIds, snippets.size());
            return answer + sourceFooter;

        } catch (Exception e) {
            recordCircuitFailure(e);
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        // 用户问题在进入提示词前统一净化 + 截断，避免注入与超长输入放大费用。
        variables.put("question", ragContextBuilder.sanitizeQuestion(question, maxQuestionChars));
        return userPromptTemplate.render(variables);
    }

    /**
     * 记录引用命中：citedCount 为回答中出现的引用 ID 数（含虚构的越界 ID），
     * matchedDocIds 只包含真实命中注入片段的文档，二者差值即「引用幻觉」。
     */
    private void recordCitation(String kbTag, String answer, List<RagContextBuilder.RagSnippet> snippets) {
        if (answer == null || answer.isBlank() || snippets == null || snippets.isEmpty()) {
            ragMetrics.recordCitation(kbTag, 0, snippets == null ? 0 : snippets.size(), List.of());
            return;
        }
        Set<Integer> citedIndexes = new TreeSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            try {
                citedIndexes.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 非法编号直接忽略，不影响其它引用统计
            }
        }
        List<String> matchedDocIds = new ArrayList<>();
        for (Integer index : citedIndexes) {
            if (index != null && index >= 1 && index <= snippets.size()) {
                String docId = snippets.get(index - 1).docId();
                if (docId != null && !docId.isBlank()) {
                    matchedDocIds.add(docId);
                }
            }
        }
        ragMetrics.recordCitation(kbTag, citedIndexes.size(), snippets.size(), matchedDocIds);
    }

    /**
     * 流式回答分段到达，累积前 {@value #MAX_CITATION_SCAN_CHARS} 字用于结束时的引用统计。
     * 超出部分直接丢弃：引用标记几乎总在回答前半段，继续累积只会放大内存。
     */
    private void accumulateForCitationScan(StringBuilder buffer, String chunk) {
        if (chunk == null || chunk.isEmpty() || buffer.length() >= MAX_CITATION_SCAN_CHARS) {
            return;
        }
        int remaining = MAX_CITATION_SCAN_CHARS - buffer.length();
        buffer.append(chunk.length() <= remaining ? chunk : chunk.substring(0, remaining));
    }

    private void acquireCircuit() {
        if (circuitBreaker != null) {
            circuitBreaker.acquire(RAG_CIRCUIT_KEY);
        }
    }

    private void recordCircuitSuccess() {
        if (circuitBreaker != null) {
            circuitBreaker.recordSuccess(RAG_CIRCUIT_KEY);
        }
    }

    private void recordCircuitFailure(Throwable error) {
        if (circuitBreaker == null) {
            return;
        }
        if (AiCallClassifier.classify(error).availabilityFailure()) {
            circuitBreaker.recordFailure(RAG_CIRCUIT_KEY);
        }
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
                return Flux.just("【错误】知识库查询失败：请选择至少一个知识库。");
            }
            long startedAt = System.nanoTime();
            String kbTag = RagMetrics.kbTag(knowledgeBaseIds);
            listService.requireOwned(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

            if (!hasEffectiveHit(relevantDocs)) {
                ragMetrics.recordResponse(kbTag, "no_result");
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 计数语义：检索成功后、真正生成回答前 +1，避免检索失败导致计数与结果不一致。
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 3. 构建上下文（带 [S#] 引用标记 + 长度上限，文档内容视为不可信数据）
            List<RagContextBuilder.RagSnippet> snippets = ragContextBuilder.buildSnippets(relevantDocs);
            String context = ragContextBuilder.renderContext(snippets);
            String sourceFooter = ragContextBuilder.renderSourceFooter(snippets);

            log.debug("检索到 {} 个相关文档片段，实际注入 {} 段（{}）",
                relevantDocs.size(), snippets.size(), ragContextBuilder.describeLimits());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            acquireCircuit();
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            // 无结果模板不追加来源清单，正常回答在流结束时补上确定来源
            AtomicBoolean noResult = new AtomicBoolean(false);
            AtomicBoolean firstTokenRecorded = new AtomicBoolean(false);
            // 只保留前 MAX_CITATION_SCAN_CHARS 字用于引用统计，长回答不占额外内存
            StringBuilder citationBuffer = new StringBuilder();
            String providerTag = llmProviderRegistry.defaultChatProviderId();
            return normalizeStreamOutput(responseFlux, noResult)
                .doOnNext(chunk -> {
                    if (firstTokenRecorded.compareAndSet(false, true)) {
                        ragMetrics.recordFirstToken(System.nanoTime() - startedAt, kbTag, providerTag);
                    }
                    accumulateForCitationScan(citationBuffer, chunk);
                })
                .concatWith(Flux.defer(() -> noResult.get() ? Flux.empty() : Flux.just(sourceFooter)))
                .doOnComplete(() -> {
                    recordCircuitSuccess();
                    recordCitation(kbTag, citationBuffer.toString(), snippets);
                    ragMetrics.recordResponse(kbTag, noResult.get() ? "no_result" : "answer");
                    log.info("流式输出完成: kbIds={}", knowledgeBaseIds);
                })
                .onErrorResume(e -> {
                    recordCircuitFailure(e);
                    ragMetrics.recordResponse(kbTag, "error");
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    /**
     * 历史消息属于用户可控输入：限制条数、逐条净化并截断，避免注入与超长上下文。
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, history.size() - maxHistoryMessages);
        List<Message> sanitized = new ArrayList<>(history.size() - fromIndex);
        for (Message message : history.subList(fromIndex, history.size())) {
            if (message == null) {
                continue;
            }
            String text = ragContextBuilder.sanitizeHistoryText(message.getText(), maxHistoryMessageChars);
            if (text.isBlank()) {
                continue;
            }
            if (message instanceof AssistantMessage) {
                sanitized.add(new AssistantMessage(text));
            } else {
                sanitized.add(new UserMessage(text));
            }
        }
        return sanitized;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    向量检索
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                currentOwnerUserId(),
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
            if (hasEffectiveHit(docs)) {
                return docs;
            }
        }
        return List.of();
    }

    private Long currentOwnerUserId() {
        return listService.getOwnerUserId();
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            ragMetrics.recordRewrite("skipped");
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                ragMetrics.recordRewrite("failure");
                return question;
            }
            String normalized = rewritten.trim();
            ragMetrics.recordRewrite("success");
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            ragMetrics.recordRewrite("failure");
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /*
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    /**
     * 规范化流输出：在完全接收流数据之前，提前探测是否为"无结果"响应，
     * 如果是则截断流并返回固定响应；否则将流原样透传。
     *
     * @param rawFlux 原始的字符串 Flux 流
     * @param noResultFlag 输出是否为“未检索到相关信息”模板，供调用方决定是否追加来源清单
     * @return 经过探测和规范化处理后的 Flux 流
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux, AtomicBoolean noResultFlag) {
        // 使用 Flux.create 创建自定义的 Flux，允许手动控制数据的发射和完成
        return Flux.create(sink -> {

            // 缓冲区：用于累积尚未进入透传模式的字符片段，供探测逻辑分析
            StringBuilder probeBuffer = new StringBuilder();

            // 标记位：是否已经确认不是"无结果"，进入透传模式
            AtomicBoolean passthrough = new AtomicBoolean(false);

            // 标记位：是否已经完成（探测到无结果并已处理完毕）
            AtomicBoolean completed = new AtomicBoolean(false);

            // 持有 disposable 引用的数组，方便在 onCancel 中释放订阅
            final Disposable[] disposableRef = new Disposable[1];

            // 订阅原始流 rawFlux
            disposableRef[0] = rawFlux.subscribe(

                    // --- onNext：每收到一个数据块时的回调 ---
                    chunk -> {
                        // 如果已经完成或 sink 已被取消，忽略此数据块
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }

                        // 如果已进入透传模式，直接将数据块转发给下游
                        if (passthrough.get()) {
                            sink.next(chunk);
                            return;
                        }

                        // 否则，将数据块追加到探测缓冲区中
                        probeBuffer.append(chunk);
                        String probeText = probeBuffer.toString();

                        // 检查缓冲区内容是否匹配"无结果"的模式
                        if (isNoResultLike(probeText)) {
                            // 确认为无结果响应：标记已完成
                            completed.set(true);
                            noResultFlag.set(true);

                            // 向下游发射预设的无结果响应消息
                            sink.next(NO_RESULT_RESPONSE);

                            // 完成整个 Flux（不再接收后续数据）
                            sink.complete();

                            // 取消对原始流的订阅，停止继续消费数据
                            if (disposableRef[0] != null) {
                                disposableRef[0].dispose();
                            }
                            return;
                        }

                        // 如果缓冲区长度达到了探测阈值（足够长的内容仍不匹配无结果模式）
                        if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                            // 进入透传模式
                            passthrough.set(true);

                            // 将之前累积的缓冲区内容一次性发射出去
                            sink.next(probeText);

                            // 清空缓冲区（后续数据直接透传，不再缓存）
                            probeBuffer.setLength(0);
                        }
                    },

                    // --- onError：原始流出错时的回调 ---
                    sink::error,  // 直接将错误传递给下游

                    // --- onComplete：原始流正常结束时的回调 ---
                    () -> {
                        // 如果已经完成或 sink 已被取消，不做任何操作
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }

                        // 如果从未进入透传模式（说明数据量小于探测阈值）
                        if (!passthrough.get()) {
                            // 对缓冲区中的完整内容进行规范化处理后发射
                            String normalized = normalizeAnswer(probeBuffer.toString());
                            noResultFlag.set(NO_RESULT_RESPONSE.equals(normalized));
                            sink.next(normalized);
                        }

                        // 完成整个 Flux
                        sink.complete();
                    }
            );

            // 注册取消回调：当下游取消订阅时，释放对原始流的订阅
            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}
