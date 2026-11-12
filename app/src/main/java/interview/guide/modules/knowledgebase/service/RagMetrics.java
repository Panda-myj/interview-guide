package interview.guide.modules.knowledgebase.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * RAG 链路指标记录器。
 *
 * <p>只记录**数值与低基数标签**，绝不记录文档正文、用户问题原文或任何密钥，
 * 避免把敏感内容带进 Prometheus 时间序列。</p>
 *
 * <p>标签约定：</p>
 * <ul>
 *   <li>{@code kb}：知识库 ID 拼接串，截断到 64 字符；为空时用 {@code all}。</li>
 *   <li>{@code mode}：检索策略（vector/hybrid/...），固定枚举值。</li>
 *   <li>{@code provider}：Provider ID，未解析到时用 {@code unknown}。</li>
 *   <li>{@code doc}：文档 ID，超过 {@value #MAX_DOC_TAGS} 个后统一归为 {@code __overflow__}，防止基数爆炸。</li>
 * </ul>
 */
@Component
public class RagMetrics {

  public static final String METRIC_RETRIEVAL_DURATION = "rag.retrieval.duration";
  public static final String METRIC_RETRIEVAL_CANDIDATES = "rag.retrieval.candidates";
  public static final String METRIC_RETRIEVAL_DEGRADED = "rag.retrieval.degraded";
  public static final String METRIC_REWRITE = "rag.rewrite.total";
  public static final String METRIC_FIRST_TOKEN = "rag.llm.first_token";
  public static final String METRIC_RESPONSE = "rag.response.total";
  public static final String METRIC_CITATION = "rag.citation.total";
  public static final String METRIC_CITATION_RATIO = "rag.citation.ratio";
  public static final String METRIC_CITATION_DOC = "rag.citation.doc";

  private static final int MAX_DOC_TAGS = 500;
  private static final int MAX_KB_TAG_LENGTH = 64;
  private static final String OVERFLOW_DOC_TAG = "__overflow__";
  private static final String UNKNOWN = "unknown";

  private final MeterRegistry registry;
  private final Set<String> seenDocIds = ConcurrentHashMap.newKeySet();

  @Autowired
  public RagMetrics(@Autowired(required = false) MeterRegistry registry) {
    this.registry = registry;
  }

  /** 未注入 registry 时使用（单测/离线评测），所有方法为空操作。 */
  public static RagMetrics disabled() {
    return new RagMetrics(null);
  }

  /** 知识库标签：多知识库合并，长度收敛，避免高基数。 */
  public static String kbTag(List<Long> knowledgeBaseIds) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
      return "all";
    }
    StringBuilder builder = new StringBuilder();
    for (Long id : knowledgeBaseIds) {
      if (id == null) {
        continue;
      }
      if (!builder.isEmpty()) {
        builder.append('_');
      }
      builder.append(id);
      if (builder.length() >= MAX_KB_TAG_LENGTH) {
        break;
      }
    }
    String tag = builder.isEmpty() ? "all" : builder.toString();
    return tag.length() > MAX_KB_TAG_LENGTH ? tag.substring(0, MAX_KB_TAG_LENGTH) : tag;
  }

  /** 向量检索耗时。outcome: hit / empty / error。 */
  public void recordRetrieval(long durationNanos, String kbTag, String mode, String outcome) {
    if (registry == null) {
      return;
    }
    registry.timer(METRIC_RETRIEVAL_DURATION, "kb", kbTag, "mode", mode, "outcome", outcome)
        .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
  }

  /** 候选池大小，用于观察扩大候选带来的成本边界。 */
  public void recordCandidatePool(int candidateCount, String mode) {
    if (registry == null) {
      return;
    }
    DistributionSummary.builder(METRIC_RETRIEVAL_CANDIDATES)
        .tag("mode", mode)
        .register(registry)
        .record(Math.max(0, candidateCount));
  }

  /** 降级次数：超时、过滤回退、重排异常等。 */
  public void recordDegraded(String reason) {
    if (registry == null) {
      return;
    }
    registry.counter(METRIC_RETRIEVAL_DEGRADED, "reason", reason).increment();
  }

  /** 查询改写结果：success / failure / skipped。 */
  public void recordRewrite(String status) {
    if (registry == null) {
      return;
    }
    registry.counter(METRIC_REWRITE, "status", status).increment();
  }

  /** 首 token 延迟（流式）。provider 未解析到时归为 unknown，避免标签缺失产生空值序列。 */
  public void recordFirstToken(long durationNanos, String kbTag, String provider) {
    if (registry == null) {
      return;
    }
    registry.timer(METRIC_FIRST_TOKEN,
            "kb", tagOrFallback(kbTag, "all"),
            "provider", tagOrFallback(provider, UNKNOWN))
        .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
  }

  /** 回答结果：answer / no_result。 */
  public void recordResponse(String kbTag, String status) {
    if (registry == null) {
      return;
    }
    registry.counter(METRIC_RESPONSE, "kb", kbTag, "status", status).increment();
  }

  /**
   * 引用命中率：回答中出现的引用 ID 有多少能对上注入的片段。
   *
   * @param citedCount      回答里识别到的引用 ID 数
   * @param snippetCount    注入的片段总数
   * @param matchedDocIds   被真实引用到的文档 ID（用于文档级命中统计）
   */
  public void recordCitation(String kbTag, int citedCount, int snippetCount, List<String> matchedDocIds) {
    if (registry == null) {
      return;
    }
    boolean hit = citedCount > 0 && snippetCount > 0;
    registry.counter(METRIC_CITATION, "kb", kbTag, "hit", Boolean.toString(hit)).increment();
    if (snippetCount > 0) {
      double ratio = Math.min(1.0, citedCount / (double) snippetCount);
      DistributionSummary.builder(METRIC_CITATION_RATIO)
          .tag("kb", kbTag)
          .register(registry)
          .record(ratio);
    }
    if (matchedDocIds == null || matchedDocIds.isEmpty()) {
      return;
    }
    for (String docId : matchedDocIds) {
      registry.counter(METRIC_CITATION_DOC, "doc", docTag(docId)).increment();
    }
  }

  private String docTag(String docId) {
    if (docId == null || docId.isBlank()) {
      return UNKNOWN;
    }
    if (seenDocIds.contains(docId)) {
      return docId;
    }
    if (seenDocIds.size() >= MAX_DOC_TAGS) {
      return OVERFLOW_DOC_TAG;
    }
    seenDocIds.add(docId);
    return docId;
  }

  /** Micrometer 不接受空标签值，统一收敛到固定兜底值。 */
  private static String tagOrFallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
