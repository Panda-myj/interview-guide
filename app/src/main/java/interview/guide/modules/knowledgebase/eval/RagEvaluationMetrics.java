package interview.guide.modules.knowledgebase.eval;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG 离线评测的指标口径（纯函数，无 IO、无外部依赖）。
 *
 * <p>公式定义见 {@code perf/rag/README.md}，实现与文档必须保持一致，
 * 否则历史基线不可比。</p>
 */
public final class RagEvaluationMetrics {

  private RagEvaluationMetrics() {
  }

  /**
   * Recall@K = |expected ∩ retrieved[0,K)| / |expected|。
   *
   * <p>expected 为空（拒答类样本）时返回 1.0，表示「没有必须召回的目标」，
   * 拒答是否正确由 {@link #refusalAccuracy} 单独统计。</p>
   */
  public static double recallAtK(Collection<String> expectedDocIds, List<String> retrievedDocIds, int k) {
    Set<String> expected = normalize(expectedDocIds);
    if (expected.isEmpty()) {
      return 1.0;
    }
    if (retrievedDocIds == null || retrievedDocIds.isEmpty() || k <= 0) {
      return 0.0;
    }
    int limit = Math.min(k, retrievedDocIds.size());
    int hits = 0;
    Set<String> seen = new LinkedHashSet<>();
    for (int index = 0; index < limit; index++) {
      String id = retrievedDocIds.get(index);
      if (id != null && expected.contains(id) && seen.add(id)) {
        hits++;
      }
    }
    return hits / (double) expected.size();
  }

  /**
   * MRR = 1 / rank(第一个相关文档)，无相关命中记 0。
   */
  public static double reciprocalRank(Collection<String> expectedDocIds, List<String> retrievedDocIds) {
    Set<String> expected = normalize(expectedDocIds);
    if (expected.isEmpty() || retrievedDocIds == null) {
      return 0.0;
    }
    for (int index = 0; index < retrievedDocIds.size(); index++) {
      if (expected.contains(retrievedDocIds.get(index))) {
        return 1.0 / (index + 1);
      }
    }
    return 0.0;
  }

  /**
   * 引用准确率 = |citedDoc ∩ supportingDoc| / |citedDoc|。
   * 没有引用时记 0（没引用即不可核对）。
   */
  public static double citationAccuracy(Collection<String> citedDocIds, Collection<String> supportingDocIds) {
    Set<String> cited = normalize(citedDocIds);
    if (cited.isEmpty()) {
      return 0.0;
    }
    Set<String> supporting = normalize(supportingDocIds);
    int hits = 0;
    for (String id : cited) {
      if (supporting.contains(id)) {
        hits++;
      }
    }
    return hits / (double) cited.size();
  }

  /**
   * 关键词命中率 = 回答中命中的期望关键词数 / 期望关键词总数。
   */
  public static double keywordHitRate(Collection<String> expectedKeywords, String answer) {
    Set<String> expected = normalize(expectedKeywords);
    if (expected.isEmpty()) {
      return 1.0;
    }
    if (answer == null || answer.isBlank()) {
      return 0.0;
    }
    String haystack = answer.toLowerCase(Locale.ROOT);
    int hits = 0;
    for (String keyword : expected) {
      if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
        hits++;
      }
    }
    return hits / (double) expected.size();
  }

  /** 拒答准确率：应拒答且实际未返回文档记 1，否则记 0。 */
  public static double refusalAccuracy(boolean mustRefuse, List<String> retrievedDocIds) {
    if (!mustRefuse) {
      return 1.0;
    }
    return retrievedDocIds == null || retrievedDocIds.isEmpty() ? 1.0 : 0.0;
  }

  /** 比率，分母为 0 时返回 0，避免 NaN 污染聚合结果。 */
  public static double ratio(long numerator, long denominator) {
    return denominator <= 0 ? 0.0 : numerator / (double) denominator;
  }

  public static double mean(Collection<Double> values) {
    if (values == null || values.isEmpty()) {
      return 0.0;
    }
    double sum = 0.0;
    for (Double value : values) {
      if (value != null) {
        sum += value;
      }
    }
    return sum / values.size();
  }

  /** 最近秩百分位（p 取 0~1），输入为纳秒耗时。 */
  public static long percentile(Collection<Long> nanos, double p) {
    if (nanos == null || nanos.isEmpty()) {
      return 0L;
    }
    List<Long> sorted = nanos.stream().filter(value -> value != null).sorted().toList();
    if (sorted.isEmpty()) {
      return 0L;
    }
    double clamped = Math.max(0.0, Math.min(1.0, p));
    int index = (int) Math.ceil(clamped * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
  }

  private static Set<String> normalize(Collection<String> values) {
    Set<String> normalized = new LinkedHashSet<>();
    if (values == null) {
      return normalized;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        normalized.add(value.trim());
      }
    }
    return normalized;
  }
}
