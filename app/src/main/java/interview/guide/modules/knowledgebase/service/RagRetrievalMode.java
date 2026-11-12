package interview.guide.modules.knowledgebase.service;

import java.util.Locale;

/**
 * 检索排序策略。
 *
 * <p>默认 {@link #VECTOR} 与升级前行为完全一致，可作为一键回滚开关；
 * 其余模式只在「已通过权限过滤的向量候选」上追加本地关键词融合与重排，
 * 不改变向量索引结构、不额外调用外部模型，因此没有新增成本。</p>
 */
public enum RagRetrievalMode {

  /** 纯向量检索（默认，回滚目标）。 */
  VECTOR(1, 0.0),
  /** 扩大候选后做向量 + 关键词融合。 */
  HYBRID(2, 0.5),
  /** 保持候选规模，用关键词对向量结果重排。 */
  VECTOR_RERANK(2, 0.4),
  /** 扩大候选 + 更高关键词权重。 */
  HYBRID_RERANK(3, 0.6);

  private final int candidateMultiplier;
  private final double keywordWeight;

  RagRetrievalMode(int candidateMultiplier, double keywordWeight) {
    this.candidateMultiplier = candidateMultiplier;
    this.keywordWeight = keywordWeight;
  }

  /** 候选池相对 topK 的放大倍数，用于给重排留出空间。 */
  public int candidateMultiplier() {
    return candidateMultiplier;
  }

  /** 融合排序中关键词得分的权重，向量得分权重为 1 - keywordWeight。 */
  public double keywordWeight() {
    return keywordWeight;
  }

  public boolean needsRanking() {
    return this != VECTOR;
  }

  /**
   * 解析配置值：大小写不敏感，未知值回退到 {@link #VECTOR}，保证配置写错时行为最保守。
   */
  public static RagRetrievalMode from(String raw) {
    if (raw == null || raw.isBlank()) {
      return VECTOR;
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return VECTOR;
    }
  }
}
