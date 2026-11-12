package interview.guide.common.ai;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把检索到的知识库文档组装成「带引用标记、带长度上限」的不可信上下文。
 *
 * <p>约束：</p>
 * <ul>
 *   <li>每段文档前加 {@code [S1]} 形式引用 ID，便于模型在回答中引用、便于用户核对来源。</li>
 *   <li>限制文档数量、单段字符数与总字符数，避免超长输入撑爆上下文或放大费用。</li>
 *   <li>文档文本先经 {@link PromptSanitizer} 清洗，再由 system prompt 声明为不可信数据。</li>
 * </ul>
 */
public final class RagContextBuilder {

  private static final String DEFAULT_DOC_LABEL = "知识库文档";
  private static final int MAX_DOC_ID_LENGTH = 12;

  private final PromptSanitizer sanitizer;
  private final int maxDocs;
  private final int maxCharsPerDoc;
  private final int maxTotalChars;

  public RagContextBuilder(
      PromptSanitizer sanitizer,
      int maxDocs,
      int maxCharsPerDoc,
      int maxTotalChars) {
    this.sanitizer = sanitizer;
    this.maxDocs = Math.max(1, maxDocs);
    this.maxCharsPerDoc = Math.max(100, maxCharsPerDoc);
    this.maxTotalChars = Math.max(this.maxCharsPerDoc, maxTotalChars);
  }

  /**
   * 清洗并截断用户问题，超长输入直接截断而不是抛错，避免整条链路失败。
   */
  public String sanitizeQuestion(String question, int maxChars) {
    if (question == null) {
      return "";
    }
    String cleaned = stripControlChars(question.trim());
    if (sanitizer != null) {
      cleaned = sanitizer.sanitize(cleaned);
    }
    return truncate(cleaned, Math.max(1, maxChars));
  }

  /**
   * 清洗并截断单条历史消息。
   */
  public String sanitizeHistoryText(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String cleaned = stripControlChars(text.trim());
    if (sanitizer != null) {
      cleaned = sanitizer.sanitize(cleaned);
    }
    return truncate(cleaned, Math.max(1, maxChars));
  }

  /**
   * 生成带引用标记的上下文片段列表。
   */
  public List<RagSnippet> buildSnippets(List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return List.of();
    }
    List<RagSnippet> snippets = new ArrayList<>();
    int totalChars = 0;
    int index = 0;
    for (Document document : documents) {
      if (snippets.size() >= maxDocs || totalChars >= maxTotalChars) {
        break;
      }
      String text = document == null ? null : document.getText();
      if (text == null || text.isBlank()) {
        continue;
      }
      String cleaned = sanitizer == null ? text.trim() : sanitizer.sanitize(text.trim());
      int remaining = maxTotalChars - totalChars;
      if (remaining <= 0) {
        break;
      }
      String bounded = truncate(cleaned, Math.min(maxCharsPerDoc, remaining));
      if (bounded.isBlank()) {
        continue;
      }
      index++;
      totalChars += bounded.length();
      snippets.add(new RagSnippet("[S" + index + "]", resolveLabel(document), bounded, document.getId()));
    }
    return snippets;
  }

  /**
   * 渲染成 prompt 中的文档上下文。
   */
  public String renderContext(List<RagSnippet> snippets) {
    if (snippets == null || snippets.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (RagSnippet snippet : snippets) {
      if (!builder.isEmpty()) {
        builder.append("\n\n---\n\n");
      }
      builder.append(snippet.marker())
          .append(" 来源: ")
          .append(snippet.label())
          .append('\n')
          .append(snippet.text());
    }
    return builder.toString();
  }

  /**
   * 生成回答末尾的确定来源清单，保证即使模型漏写引用也能看到来源 ID。
   */
  public String renderSourceFooter(List<RagSnippet> snippets) {
    if (snippets == null || snippets.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder("\n\n参考来源：\n");
    for (RagSnippet snippet : snippets) {
      builder.append("- ").append(snippet.marker()).append(' ').append(snippet.label()).append('\n');
    }
    return builder.toString().stripTrailing();
  }

  private String resolveLabel(Document document) {
    Map<String, Object> metadata = document.getMetadata();
    String name = firstNonBlank(
        metadata.get("file_name"),
        metadata.get("fileName"),
        metadata.get("source"),
        metadata.get("title")
    );
    String kbId = firstNonBlank(metadata.get("kb_target_id"), metadata.get("kb_id"));
    StringBuilder label = new StringBuilder();
    if (name != null) {
      label.append(name);
    } else {
      label.append(DEFAULT_DOC_LABEL);
    }
    if (kbId != null) {
      label.append("（知识库 ").append(kbId).append('）');
    }
    String documentId = document.getId();
    if (documentId != null && !documentId.isBlank()) {
      label.append(" #").append(truncate(documentId, MAX_DOC_ID_LENGTH));
    }
    return label.toString();
  }

  private String firstNonBlank(Object... candidates) {
    for (Object candidate : candidates) {
      if (candidate != null && !candidate.toString().isBlank()) {
        return candidate.toString().trim();
      }
    }
    return null;
  }

  private String stripControlChars(String text) {
    StringBuilder builder = new StringBuilder(text.length());
    for (int index = 0; index < text.length(); index++) {
      char ch = text.charAt(index);
      if (ch == '\n' || ch == '\t' || ch == '\r' || !Character.isISOControl(ch)) {
        builder.append(ch);
      }
    }
    return builder.toString();
  }

  private String truncate(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxChars) {
      return text;
    }
    String suffix = "…(内容已截断)";
    int keep = Math.max(0, maxChars - suffix.length());
    return text.substring(0, keep) + suffix;
  }

  public String describeLimits() {
    return String.format(Locale.ROOT,
        "maxDocs=%d, maxCharsPerDoc=%d, maxTotalChars=%d", maxDocs, maxCharsPerDoc, maxTotalChars);
  }

  /**
   * @param marker 引用 ID，形如 {@code [S1]}
   * @param label  展示用来源标签（文件名 + 知识库 + 文档 ID）
   * @param text   已清洗并截断的文档内容
   * @param docId  原始文档 ID，用于引用命中统计
   */
  public record RagSnippet(String marker, String label, String text, String docId) {
  }
}
