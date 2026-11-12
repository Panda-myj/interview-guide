package interview.guide.modules.knowledgebase.service;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 候选重排：在**已通过权限过滤**的向量候选上做本地融合排序。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>不发起任何外部调用，因此没有额外 token/费用，也不会引入新的超时点。</li>
 *   <li>输入候选数由调用方按 {@code maxCandidates} 收敛，排序复杂度为 O(n log n)，n 有上界。</li>
 *   <li>去重按 document id 进行，不改变知识库/租户过滤结果，只改变顺序。</li>
 * </ul>
 */
public final class RagCandidateRanker {

  private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9_]{2,}");
  private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
  private static final int MIN_CJK_BIGRAM = 2;

  private RagCandidateRanker() {
  }

  /**
   * 融合排序并截断到 topK。
   *
   * @param candidates 已过滤的向量候选（按向量相似度降序）
   * @param query      用户查询
   * @param mode       检索模式，{@link RagRetrievalMode#VECTOR} 时原样截断
   * @param topK       返回条数
   */
  public static List<Document> rank(List<Document> candidates, String query, RagRetrievalMode mode, int topK) {
    if (candidates == null || candidates.isEmpty() || topK <= 0) {
      return List.of();
    }
    List<Document> deduped = dedupe(candidates);
    if (mode == null || !mode.needsRanking()) {
      return deduped.size() <= topK ? deduped : new ArrayList<>(deduped.subList(0, topK));
    }

    Set<String> queryTokens = tokenize(query);
    double keywordWeight = mode.keywordWeight();
    int candidateCount = deduped.size();
    List<Scored> scored = new ArrayList<>(candidateCount);
    for (int index = 0; index < candidateCount; index++) {
      Document document = deduped.get(index);
      double vectorScore = 1.0 - index / (double) Math.max(1, candidateCount);
      double keywordScore = queryTokens.isEmpty() ? 0.0 : overlap(queryTokens, tokenize(document.getText()));
      double fused = keywordWeight * keywordScore + (1 - keywordWeight) * vectorScore;
      scored.add(new Scored(document, fused, index));
    }
    scored.sort(Comparator.comparingDouble(Scored::score).reversed()
        .thenComparingInt(Scored::originalIndex));

    int limit = Math.min(topK, scored.size());
    List<Document> ranked = new ArrayList<>(limit);
    for (int index = 0; index < limit; index++) {
      ranked.add(scored.get(index).document());
    }
    return ranked;
  }

  /** 查询词在文档中的覆盖率（0~1），用于离线评测与重排打分。 */
  public static double keywordScore(String query, String text) {
    Set<String> queryTokens = tokenize(query);
    if (queryTokens.isEmpty()) {
      return 0.0;
    }
    return overlap(queryTokens, tokenize(text));
  }

  /** 分词：ASCII 词 + 中文二元组，避免引入额外分词依赖。 */
  public static Set<String> tokenize(String text) {
    Set<String> tokens = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return tokens;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    Matcher ascii = ASCII_TOKEN.matcher(lower);
    while (ascii.find()) {
      tokens.add(ascii.group());
    }
    Matcher cjk = CJK_RUN.matcher(lower);
    while (cjk.find()) {
      String run = cjk.group();
      if (run.length() < MIN_CJK_BIGRAM) {
        tokens.add(run);
        continue;
      }
      for (int index = 0; index + MIN_CJK_BIGRAM <= run.length(); index++) {
        tokens.add(run.substring(index, index + MIN_CJK_BIGRAM));
      }
    }
    return tokens;
  }

  private static double overlap(Set<String> queryTokens, Set<String> documentTokens) {
    if (queryTokens.isEmpty()) {
      return 0.0;
    }
    int matched = 0;
    for (String token : queryTokens) {
      if (documentTokens.contains(token)) {
        matched++;
      }
    }
    return matched / (double) queryTokens.size();
  }

  private static List<Document> dedupe(List<Document> candidates) {
    Set<String> seen = new LinkedHashSet<>();
    List<Document> deduped = new ArrayList<>(candidates.size());
    for (Document document : candidates) {
      if (document == null) {
        continue;
      }
      String id = document.getId();
      if (id != null && !seen.add(id)) {
        continue;
      }
      deduped.add(document);
    }
    return deduped;
  }

  private record Scored(Document document, double score, int originalIndex) {
  }
}
