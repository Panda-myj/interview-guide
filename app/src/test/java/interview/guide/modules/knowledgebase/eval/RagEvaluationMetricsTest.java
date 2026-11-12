package interview.guide.modules.knowledgebase.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 指标口径的单元测试：公式一旦改错，历史基线就不可比，所以这里逐条锁定。
 */
@DisplayName("RAG 评测指标口径")
class RagEvaluationMetricsTest {

  private static final double EPS = 1e-9;

  @Nested
  @DisplayName("召回与排序")
  class RecallTests {

    @Test
    @DisplayName("Recall@K 只统计前 K 条命中")
    void recallRespectsK() {
      List<String> expected = List.of("doc-a", "doc-b");
      List<String> retrieved = List.of("doc-x", "doc-a", "doc-y");

      assertThat(RagEvaluationMetrics.recallAtK(expected, retrieved, 3)).isCloseTo(0.5, within(EPS));
      assertThat(RagEvaluationMetrics.recallAtK(expected, retrieved, 1)).isCloseTo(0.0, within(EPS));
    }

    @Test
    @DisplayName("重复命中同一文档只算一次")
    void recallDeduplicatesHits() {
      List<String> retrieved = List.of("doc-a", "doc-a", "doc-b");

      assertThat(RagEvaluationMetrics.recallAtK(List.of("doc-a", "doc-b"), retrieved, 3))
          .isCloseTo(1.0, within(EPS));
    }

    @Test
    @DisplayName("无期望文档的拒答样本召回记 1，不拖累均值")
    void recallOfRefusalCaseIsOne() {
      assertThat(RagEvaluationMetrics.recallAtK(List.of(), List.of("doc-a"), 5))
          .isCloseTo(1.0, within(EPS));
    }

    @Test
    @DisplayName("MRR 取第一个相关文档的倒数排名")
    void reciprocalRankUsesFirstHit() {
      List<String> retrieved = List.of("doc-x", "doc-y", "doc-a");

      assertThat(RagEvaluationMetrics.reciprocalRank(List.of("doc-a"), retrieved))
          .isCloseTo(1.0 / 3, within(EPS));
      assertThat(RagEvaluationMetrics.reciprocalRank(List.of("doc-z"), retrieved))
          .isCloseTo(0.0, within(EPS));
    }
  }

  @Nested
  @DisplayName("引用与关键词")
  class CitationTests {

    @Test
    @DisplayName("引用准确率 = 命中支撑文档数 / 引用文档数")
    void citationAccuracyIsPrecision() {
      Collection<String> cited = List.of("doc-a", "doc-b");
      Collection<String> supporting = List.of("doc-a");

      assertThat(RagEvaluationMetrics.citationAccuracy(cited, supporting)).isCloseTo(0.5, within(EPS));
    }

    @Test
    @DisplayName("没有任何引用时引用准确率记 0，视为不可核对")
    void citationAccuracyWithoutCitationIsZero() {
      assertThat(RagEvaluationMetrics.citationAccuracy(List.of(), List.of("doc-a")))
          .isCloseTo(0.0, within(EPS));
    }

    @Test
    @DisplayName("关键词命中率大小写不敏感")
    void keywordHitRateIgnoresCase() {
      assertThat(RagEvaluationMetrics.keywordHitRate(List.of("HashMap", "红黑树"), "hashmap 与红黑树"))
          .isCloseTo(1.0, within(EPS));
      assertThat(RagEvaluationMetrics.keywordHitRate(List.of("HashMap", "红黑树"), "hashmap"))
          .isCloseTo(0.5, within(EPS));
    }

    @Test
    @DisplayName("期望关键词为空时记 1")
    void keywordHitRateWithoutExpectationIsOne() {
      assertThat(RagEvaluationMetrics.keywordHitRate(List.of(), "任意回答")).isCloseTo(1.0, within(EPS));
    }
  }

  @Nested
  @DisplayName("拒答与聚合")
  class RefusalTests {

    @Test
    @DisplayName("应拒答且检索为空才算正确")
    void refusalRequiresEmptyRetrieval() {
      assertThat(RagEvaluationMetrics.refusalAccuracy(true, List.of())).isCloseTo(1.0, within(EPS));
      assertThat(RagEvaluationMetrics.refusalAccuracy(true, List.of("doc-a"))).isCloseTo(0.0, within(EPS));
      assertThat(RagEvaluationMetrics.refusalAccuracy(false, List.of("doc-a"))).isCloseTo(1.0, within(EPS));
    }

    @Test
    @DisplayName("分母为 0 的比率返回 0 而不是 NaN")
    void ratioGuardsZeroDenominator() {
      assertThat(RagEvaluationMetrics.ratio(0, 0)).isCloseTo(0.0, within(EPS));
      assertThat(RagEvaluationMetrics.ratio(1, 4)).isCloseTo(0.25, within(EPS));
    }

    @Test
    @DisplayName("最近秩百分位")
    void percentileUsesNearestRank() {
      List<Long> nanos = List.of(10L, 30L, 20L, 40L, 50L);

      assertThat(RagEvaluationMetrics.percentile(nanos, 0.50)).isEqualTo(30L);
      assertThat(RagEvaluationMetrics.percentile(nanos, 0.95)).isEqualTo(50L);
      assertThat(RagEvaluationMetrics.percentile(List.of(), 0.95)).isZero();
    }

    @Test
    @DisplayName("均值忽略空集合")
    void meanHandlesEmpty() {
      assertThat(RagEvaluationMetrics.mean(List.of())).isCloseTo(0.0, within(EPS));
      assertThat(RagEvaluationMetrics.mean(List.of(0.5, 1.0))).isCloseTo(0.75, within(EPS));
    }
  }
}
