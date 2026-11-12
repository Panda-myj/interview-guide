package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块元数据是引用可追溯的前提：没有 doc/位置信息，[S#] 只是装饰。
 * 同时必须保证「只写元数据、不改文本」，否则会改变向量内容、影响已有索引。
 */
@DisplayName("分块元数据增强")
class RagChunkEnricherTest {

  private static final String CONTENT = """
      # JVM 内存区域
      JVM 的堆用来存放对象实例。

      # 垃圾回收
      G1 按 Region 回收，优先回收收益最高的区域。
      """;

  private static Document chunk(String text) {
    return new Document(text);
  }

  private static List<Document> chunks(String... texts) {
    List<Document> chunks = new ArrayList<>();
    for (String text : texts) {
      chunks.add(chunk(text));
    }
    return chunks;
  }

  @Nested
  @DisplayName("元数据写入")
  class MetadataTests {

    @Test
    @DisplayName("每个块都带上文档、序号、总数与原文区间")
    void writesTraceableMetadata() {
      List<Document> chunks = chunks(
          "# JVM 内存区域\nJVM 的堆用来存放对象实例。",
          "# 垃圾回收\nG1 按 Region 回收，优先回收收益最高的区域。");

      RagChunkEnricher.enrich(chunks, 42L, CONTENT);

      assertThat(chunks).hasSize(2);
      for (int index = 0; index < chunks.size(); index++) {
        Document document = chunks.get(index);
        assertThat(document.getMetadata()).containsEntry(RagChunkEnricher.META_DOC_ID, "kb_42");
        assertThat(document.getMetadata()).containsEntry(RagChunkEnricher.META_CHUNK_INDEX, index);
        assertThat(document.getMetadata()).containsEntry(RagChunkEnricher.META_CHUNK_TOTAL, 2);
        assertThat(document.getMetadata().get(RagChunkEnricher.META_CHAR_START)).isInstanceOf(Integer.class);
        assertThat(document.getMetadata().get(RagChunkEnricher.META_CHAR_END)).isInstanceOf(Integer.class);
      }
    }

    @Test
    @DisplayName("字符区间单调递增且不超过原文长度")
    void charRangesAreOrderedAndBounded() {
      List<Document> chunks = chunks(
          "# JVM 内存区域\nJVM 的堆用来存放对象实例。",
          "# 垃圾回收\nG1 按 Region 回收，优先回收收益最高的区域。");

      RagChunkEnricher.enrich(chunks, 1L, CONTENT);

      int previousStart = -1;
      for (Document document : chunks) {
        int start = (Integer) document.getMetadata().get(RagChunkEnricher.META_CHAR_START);
        int end = (Integer) document.getMetadata().get(RagChunkEnricher.META_CHAR_END);
        assertThat(start).isGreaterThanOrEqualTo(previousStart);
        assertThat(end).isGreaterThanOrEqualTo(start).isLessThanOrEqualTo(CONTENT.length());
        previousStart = start;
      }
    }

    @Test
    @DisplayName("章节取自块所在位置之前最近的标题")
    void resolvesSectionFromNearestHeading() {
      List<Document> chunks = chunks(
          "# JVM 内存区域\nJVM 的堆用来存放对象实例。",
          "# 垃圾回收\nG1 按 Region 回收，优先回收收益最高的区域。");

      RagChunkEnricher.enrich(chunks, 1L, CONTENT);

      assertThat(chunks.get(0).getMetadata()).containsEntry(RagChunkEnricher.META_SECTION, "JVM 内存区域");
      assertThat(chunks.get(1).getMetadata()).containsEntry(RagChunkEnricher.META_SECTION, "垃圾回收");
    }

    @Test
    @DisplayName("原文没有标题时章节回退为「全文」")
    void fallsBackToWholeDocumentSection() {
      List<Document> chunks = chunks("第一段内容。", "第二段内容。");

      RagChunkEnricher.enrich(chunks, 1L, "第一段内容。\n第二段内容。");

      assertThat(chunks.get(0).getMetadata()).containsEntry(RagChunkEnricher.META_SECTION, "全文");
    }
  }

  @Nested
  @DisplayName("不影响原有行为")
  class SafetyTests {

    @Test
    @DisplayName("只写元数据，不修改 chunk 文本")
    void doesNotMutateText() {
      List<Document> chunks = chunks("# 标题\n正文内容");
      String before = chunks.getFirst().getText();

      RagChunkEnricher.enrich(chunks, 1L, CONTENT);

      assertThat(chunks.getFirst().getText()).isEqualTo(before);
    }

    @Test
    @DisplayName("空输入与 null 元数据安全跳过")
    void handlesEmptyInput() {
      RagChunkEnricher.enrich(List.of(), 1L, CONTENT);
      RagChunkEnricher.enrich(null, 1L, CONTENT);

      List<Document> chunks = chunks("内容");
      RagChunkEnricher.enrich(chunks, null, null);

      assertThat(chunks.getFirst().getMetadata()).containsEntry(RagChunkEnricher.META_DOC_ID, "unknown");
    }
  }
}
