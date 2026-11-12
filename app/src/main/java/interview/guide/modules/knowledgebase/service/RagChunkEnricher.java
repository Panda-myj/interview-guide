package interview.guide.modules.knowledgebase.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 为分块补齐可追踪元数据：文档、章节、块序号与原文位置。
 *
 * <p>目的：回答里的 {@code [S#]} 引用需要能回溯到「哪篇文档的哪一段」，
 * 否则引用只是装饰。位置信息只记录字符偏移，不复制原文，避免索引膨胀。</p>
 */
public final class RagChunkEnricher {

  public static final String META_DOC_ID = "doc_id";
  public static final String META_CHUNK_INDEX = "chunk_index";
  public static final String META_CHUNK_TOTAL = "chunk_total";
  public static final String META_SECTION = "section";
  public static final String META_CHAR_START = "char_start";
  public static final String META_CHAR_END = "char_end";

  private static final String DEFAULT_SECTION = "全文";

  private RagChunkEnricher() {
  }

  /**
   * 就地写入元数据。{@code content} 为空或 chunk 为空时安全跳过。
   */
  public static void enrich(List<Document> chunks, Long knowledgeBaseId, String content) {
    if (chunks == null || chunks.isEmpty()) {
      return;
    }
    String source = content == null ? "" : content;
    String docId = knowledgeBaseId == null ? "unknown" : "kb_" + knowledgeBaseId;
    int total = chunks.size();
    int cursor = 0;
    for (int index = 0; index < total; index++) {
      Document chunk = chunks.get(index);
      if (chunk == null) {
        continue;
      }
      String text = chunk.getText();
      int start = locate(source, text, cursor);
      int end = Math.min(source.length(), start + (text == null ? 0 : text.length()));
      cursor = Math.max(cursor, start);

      chunk.getMetadata().put(META_DOC_ID, docId);
      chunk.getMetadata().put(META_CHUNK_INDEX, index);
      chunk.getMetadata().put(META_CHUNK_TOTAL, total);
      chunk.getMetadata().put(META_CHAR_START, start);
      chunk.getMetadata().put(META_CHAR_END, end);
      chunk.getMetadata().put(META_SECTION, resolveSection(source, start));
    }
  }

  private static int locate(String source, String text, int cursor) {
    if (source.isEmpty()) {
      return 0;
    }
    if (text == null || text.isEmpty()) {
      return Math.min(cursor, source.length());
    }
    int direct = source.indexOf(text, cursor);
    if (direct >= 0) {
      return direct;
    }
    // 分块器可能压缩了空白，退化为去掉首尾空白后再找
    int trimmed = source.indexOf(text.trim(), cursor);
    return trimmed >= 0 ? trimmed : Math.min(cursor, source.length());
  }

  /**
   * 取该位置之前最近的一级/二级标题，构建「章节」标签；没有标题时归为「全文」。
   */
  static String resolveSection(String content, int charStart) {
    if (content == null || content.isEmpty()) {
      return DEFAULT_SECTION;
    }
    int limit = Math.min(Math.max(charStart, 0), content.length());
    if (limit == 0) {
      // 首块从 0 开始，标题就在块内首行
      int newline = content.indexOf('\n');
      String firstLine = (newline < 0 ? content : content.substring(0, newline)).trim();
      return firstLine.startsWith("#")
          ? firstLine.replaceAll("^#+\\s*", "").trim()
          : DEFAULT_SECTION;
    }
    String section = DEFAULT_SECTION;
    int lineStart = 0;
    while (lineStart < content.length()) {
      // 标题行本身可能正好是块的起点（charStart == 标题行首），所以允许扫描到 limit 所在行。
      if (lineStart > limit) {
        break;
      }
      int lineEnd = content.indexOf('\n', lineStart);
      if (lineEnd < 0) {
        lineEnd = content.length();
      }
      String line = content.substring(lineStart, lineEnd).trim();
      if (line.startsWith("#")) {
        String candidate = line.replaceAll("^#+\\s*", "").trim();
        if (!candidate.isEmpty()) {
          section = candidate;
        }
      }
      lineStart = lineEnd + 1;
    }
    return section;
  }
}
