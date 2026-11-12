package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索策略开关与本地重排：默认必须与升级前完全一致，重排只改变顺序、不改变过滤结果。
 */
@DisplayName("检索策略与候选重排")
class RagCandidateRankerTest {

  private static Document doc(String id, String text) {
    return new Document(id, text, java.util.Map.of());
  }

  @Nested
  @DisplayName("策略开关")
  class ModeTests {

    @Test
    @DisplayName("缺省与非法配置都回退到 vector，保证可一键回滚")
    void fallsBackToVector() {
      assertThat(RagRetrievalMode.from(null)).isEqualTo(RagRetrievalMode.VECTOR);
      assertThat(RagRetrievalMode.from("  ")).isEqualTo(RagRetrievalMode.VECTOR);
      assertThat(RagRetrievalMode.from("unknown-mode")).isEqualTo(RagRetrievalMode.VECTOR);
    }

    @Test
    @DisplayName("配置值大小写与下划线不敏感")
    void parsesKnownModes() {
      assertThat(RagRetrievalMode.from("hybrid")).isEqualTo(RagRetrievalMode.HYBRID);
      assertThat(RagRetrievalMode.from("HYBRID_RERANK")).isEqualTo(RagRetrievalMode.HYBRID_RERANK);
      assertThat(RagRetrievalMode.from(" vector_rerank ")).isEqualTo(RagRetrievalMode.VECTOR_RERANK);
    }

    @Test
    @DisplayName("只有 vector 模式不放大候选池、不重排")
    void vectorNeedsNoRanking() {
      assertThat(RagRetrievalMode.VECTOR.needsRanking()).isFalse();
      assertThat(RagRetrievalMode.VECTOR.candidateMultiplier()).isEqualTo(1);
      assertThat(RagRetrievalMode.HYBRID.needsRanking()).isTrue();
      assertThat(RagRetrievalMode.HYBRID.candidateMultiplier()).isGreaterThan(1);
    }
  }

  @Nested
  @DisplayName("排序与截断")
  class RankTests {

    private final List<Document> candidates = List.of(
        doc("doc-a", "与查询完全无关的说明文字"),
        doc("doc-b", "HashMap 使用数组加链表与红黑树"),
        doc("doc-c", "ArrayList 基于动态数组"));

    @Test
    @DisplayName("vector 模式保持原有向量顺序，只做截断")
    void vectorKeepsOriginalOrder() {
      List<Document> ranked = RagCandidateRanker.rank(candidates, "HashMap 红黑树", RagRetrievalMode.VECTOR, 2);

      assertThat(ranked).extracting(Document::getId).containsExactly("doc-a", "doc-b");
    }

    @Test
    @DisplayName("hybrid 模式把关键词命中的候选提前")
    void hybridPromotesKeywordMatch() {
      List<Document> ranked = RagCandidateRanker.rank(candidates, "HashMap 红黑树", RagRetrievalMode.HYBRID, 2);

      assertThat(ranked).extracting(Document::getId).containsExactly("doc-b", "doc-a");
    }

    @Test
    @DisplayName("截断到 topK 且不产生重复文档")
    void truncatesAndDeduplicates() {
      List<Document> duplicated = List.of(
          doc("doc-a", "内容一"),
          doc("doc-a", "内容一"),
          doc("doc-b", "内容二"));

      List<Document> ranked = RagCandidateRanker.rank(duplicated, "内容", RagRetrievalMode.HYBRID, 10);

      assertThat(ranked).extracting(Document::getId).containsExactly("doc-a", "doc-b");
    }

    @Test
    @DisplayName("空候选与非法 topK 返回空列表")
    void handlesEmptyInput() {
      assertThat(RagCandidateRanker.rank(List.of(), "查询", RagRetrievalMode.HYBRID, 5)).isEmpty();
      assertThat(RagCandidateRanker.rank(null, "查询", RagRetrievalMode.HYBRID, 5)).isEmpty();
      assertThat(RagCandidateRanker.rank(candidates, "查询", RagRetrievalMode.HYBRID, 0)).isEmpty();
    }

    @Test
    @DisplayName("关键词覆盖率用于离线评测，取值在 0~1 之间")
    void keywordScoreIsBounded() {
      assertThat(RagCandidateRanker.keywordScore("HashMap 红黑树", "HashMap 使用红黑树"))
          .isBetween(0.5, 1.0);
      assertThat(RagCandidateRanker.keywordScore("HashMap", "完全无关"))
          .isBetween(0.0, 1.0);
    }
  }
}
