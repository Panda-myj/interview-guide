package interview.guide.modules.knowledgebase.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 指标埋点的两条底线：标签低基数（不能爆时间序列）、无 registry 时不影响主流程。
 */
@DisplayName("RAG 指标记录器")
class RagMetricsTest {

  @Nested
  @DisplayName("标签收敛")
  class TagTests {

    @Test
    @DisplayName("知识库标签为空时归为 all")
    void emptyKbTagFallsBack() {
      assertThat(RagMetrics.kbTag(null)).isEqualTo("all");
      assertThat(RagMetrics.kbTag(List.of())).isEqualTo("all");
      assertThat(RagMetrics.kbTag(List.of(1L, 2L))).isEqualTo("1_2");
    }

    @Test
    @DisplayName("超多知识库时标签截断到 64 字符")
    void kbTagIsTruncated() {
      List<Long> ids = new ArrayList<>();
      for (long index = 1; index <= 100; index++) {
        ids.add(index);
      }

      assertThat(RagMetrics.kbTag(ids)).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("文档标签超过 500 个后统一归为 __overflow__")
    void docTagOverflows() {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      RagMetrics metrics = new RagMetrics(registry);

      for (int index = 0; index < 501; index++) {
        metrics.recordCitation("1", 1, 1, List.of("doc-" + index));
      }
      metrics.recordCitation("1", 1, 1, List.of("doc-overflow-check"));

      assertThat(registry.get(RagMetrics.METRIC_CITATION_DOC).tag("doc", "__overflow__").counter().count())
          .isEqualTo(2.0);
    }
  }

  @Nested
  @DisplayName("埋点行为")
  class RecordingTests {

    @Test
    @DisplayName("引用命中率与文档命中都会被记录")
    void recordsCitation() {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      RagMetrics metrics = new RagMetrics(registry);

      metrics.recordCitation("1", 1, 2, List.of("doc-1"));

      assertThat(registry.get(RagMetrics.METRIC_CITATION).tag("hit", "true").counter().count())
          .isEqualTo(1.0);
      assertThat(registry.get(RagMetrics.METRIC_CITATION_RATIO).tag("kb", "1").summary().count())
          .isEqualTo(1L);
      assertThat(registry.get(RagMetrics.METRIC_CITATION_DOC).tag("doc", "doc-1").counter().count())
          .isEqualTo(1.0);
    }

    @Test
    @DisplayName("provider 缺失时首 token 指标使用 unknown 标签")
    void firstTokenFallsBackToUnknownProvider() {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      RagMetrics metrics = new RagMetrics(registry);

      metrics.recordFirstToken(1_000_000L, "1", null);

      assertThat(registry.get(RagMetrics.METRIC_FIRST_TOKEN).tag("provider", "unknown").timer().count())
          .isEqualTo(1L);
    }

    @Test
    @DisplayName("没有 registry 时所有方法都是空操作，不抛异常")
    void disabledMetricsAreNoOp() {
      RagMetrics metrics = RagMetrics.disabled();

      assertThatCode(() -> {
        metrics.recordRetrieval(1L, "1", "vector", "hit");
        metrics.recordCandidatePool(10, "vector");
        metrics.recordDegraded("ranking_timeout");
        metrics.recordRewrite("success");
        metrics.recordFirstToken(1L, "1", "p");
        metrics.recordResponse("1", "answer");
        metrics.recordCitation("1", 0, 0, List.of());
      }).doesNotThrowAnyException();
    }
  }
}
