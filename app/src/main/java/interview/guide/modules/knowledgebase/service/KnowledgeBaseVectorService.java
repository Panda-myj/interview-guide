package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final RagMetrics ragMetrics;
    private final RagRetrievalMode retrievalMode;
    private final int maxCandidates;
    private final long rankingTimeoutMs;

    @Autowired
    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor,
        KnowledgeBaseQueryProperties queryProperties,
        RagMetrics ragMetrics
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        // 使用 TokenTextSplitter 默认配置，每个 chunk 约 800 tokens，基于标点边界切分（无重叠）
        this.textSplitter = TokenTextSplitter.builder().build();
        KnowledgeBaseQueryProperties.Retrieval retrieval = queryProperties == null
            ? null
            : queryProperties.getRetrieval();
        if (retrieval == null) {
            retrieval = new KnowledgeBaseQueryProperties.Retrieval();
        }
        this.retrievalMode = RagRetrievalMode.from(retrieval.getMode());
        this.maxCandidates = Math.max(1, retrieval.getMaxCandidates());
        this.rankingTimeoutMs = Math.max(0L, retrieval.getTimeoutMs());
        this.ragMetrics = ragMetrics == null ? RagMetrics.disabled() : ragMetrics;
    }

    KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this(vectorStore, vectorRepository, null, null, null);
    }

    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        this(vectorStore, vectorRepository, transactionalExecutor, null, null);
    }

    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, Long ownerUserId, String content) {
        String jobId = null;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}",
                knowledgeBaseId, jobId, content.length());

            // 1. 将文本分块
            List<Document> chunks = textSplitter.apply(
                List.of(new Document(content))
            );
            
            log.info("文本分块完成: {} 个chunks", chunks.size());

            // 可追踪元数据：文档 / 章节 / 块序号 / 原文字符区间，供引用回溯与分块质量分析。
            // 只写元数据、不改 chunk 文本，因此不影响向量内容与检索得分。
            RagChunkEnricher.enrich(chunks, knowledgeBaseId, content);

            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, ownerUserId, jobId);

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
            }
            activateVectorJob(knowledgeBaseId, ownerUserId, jobId);//⭐
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage());
        }
    }

    private void applyPendingMetadata(
        List<Document> chunks,
        Long knowledgeBaseId,
        Long ownerUserId,
        String jobId
    ) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
            chunk.getMetadata().put("user_id", ownerUserId.toString());
        });
    }
    
    /**
     * 基于多个知识库进行相似度搜索
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(
        String query,
        List<Long> knowledgeBaseIds,
        Long ownerUserId,
        int topK,
        double minScore
    ) {
        long startedAt = System.nanoTime();
        String kbTag = RagMetrics.kbTag(knowledgeBaseIds);
        int poolSize = resolvePoolSize(topK);
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, pool={}, mode={}, minScore={}",
            query, knowledgeBaseIds, topK, poolSize, retrievalMode, minScore);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(poolSize);

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds, ownerUserId));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                results = List.of();
            }

            // 防御性二次过滤：即使存储层 filterExpression 失效或 metadata 类型不一致，
            // 也不能把其他租户/其他知识库的文档返回给调用方。
            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                results = results.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds, ownerUserId))
                    .collect(Collectors.toList());
            }

            // 排序只发生在上面的过滤结果内部：条数受 topK 约束，租户/知识库过滤结果不变。
            List<Document> limitedResults = rankWithBudget(results, query, topK, startedAt);

            ragMetrics.recordCandidatePool(poolSize, modeTag());
            ragMetrics.recordRetrieval(System.nanoTime() - startedAt, kbTag, modeTag(),
                limitedResults.isEmpty() ? "empty" : "hit");

            log.info("搜索完成: 候选 {} 条，返回 {} 条，mode={}", poolSize, limitedResults.size(), retrievalMode);
            return limitedResults;
            
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            // 回退路径必须可观测：计数用于告警“pgvector metadata 过滤是否失效”，
            // 本地过滤会丢弃非本租户的命中，但召回和性能都会退化。
            Metrics.globalRegistry.counter("rag.vector.metadata_filter_fallback").increment();
            ragMetrics.recordDegraded("metadata_filter_error");
            ragMetrics.recordRetrieval(System.nanoTime() - startedAt, kbTag, modeTag(), "error");
            return similaritySearchFallback(query, knowledgeBaseIds, ownerUserId, topK, minScore);
        }
    }

    /**
     * 候选池大小：纯向量模式等价于 topK；其它模式按倍数放大但有硬上限。
     */
    private int resolvePoolSize(int topK) {
        int effectiveTopK = Math.max(topK, 1);
        long expanded = (long) effectiveTopK * retrievalMode.candidateMultiplier();
        int bounded = (int) Math.min(maxCandidates, Math.max(expanded, effectiveTopK));
        return Math.max(bounded, 1);
    }

    /**
     * 重排带时间预算：向量检索本身已超过预算时不再追加重排，保证总延迟不因新策略变差。
     */
    private List<Document> rankWithBudget(List<Document> results, String query, int topK, long startedAt) {
        if (results.isEmpty() || topK <= 0) {
            return List.of();
        }
        if (!retrievalMode.needsRanking()) {
            return RagCandidateRanker.rank(results, query, RagRetrievalMode.VECTOR, topK);
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        if (rankingTimeoutMs > 0 && elapsedMs > rankingTimeoutMs) {
            log.warn("向量检索耗时 {}ms 超过重排预算 {}ms，降级为纯向量顺序", elapsedMs, rankingTimeoutMs);
            ragMetrics.recordDegraded("ranking_timeout");
            return RagCandidateRanker.rank(results, query, RagRetrievalMode.VECTOR, topK);
        }
        try {
            return RagCandidateRanker.rank(results, query, retrievalMode, topK);
        } catch (RuntimeException rankError) {
            log.warn("候选重排失败，降级为纯向量顺序: {}", rankError.getMessage());
            ragMetrics.recordDegraded("ranking_error");
            return RagCandidateRanker.rank(results, query, RagRetrievalMode.VECTOR, topK);
        }
    }

    private String modeTag() {
        return retrievalMode.name().toLowerCase(java.util.Locale.ROOT);
    }

    private List<Document> similaritySearchFallback(
        String query,
        List<Long> knowledgeBaseIds,
        Long ownerUserId,
        int topK,
        double minScore
    ) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds, ownerUserId))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage());
        }
    }

    private boolean isDocInKnowledgeBases(
        Document doc,
        List<Long> knowledgeBaseIds,
        Long ownerUserId
    ) {
        Object metadataOwner = doc.getMetadata().get("user_id");
        if (metadataOwner == null || !ownerUserId.toString().equals(metadataOwner.toString())) {
            return false;
        }
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds, Long ownerUserId) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "user_id == '" + ownerUserId + "' && kb_id in [" + values + "]";
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     *
     * <p>删除失败必须向上抛出：调用方（删除补偿任务）依赖异常感知失败并进入重试，
     * 静默吞掉会让补偿任务被误标记为成功，遗留孤儿向量。
     *
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId, Long ownerUserId) {
        deleteByKnowledgeBaseIdStrict(knowledgeBaseId, ownerUserId);
    }

    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId, Long ownerUserId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId, ownerUserId));
    }

    private void activateVectorJob(Long knowledgeBaseId, Long ownerUserId, String jobId) {
        runVectorRepositoryMutation(() -> {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId, ownerUserId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        });
    }

    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, cleanupError.getMessage(), cleanupError);
        }
    }

    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }
}
