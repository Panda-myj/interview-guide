package interview.guide.modules.knowledgebase.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.knowledgebase.service.RagCandidateRanker;
import interview.guide.modules.knowledgebase.service.RagRetrievalMode;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG 离线评测执行器：在没有 PostgreSQL / Redis / 真实 LLM 的开发机上也能跑出**可复现**的检索基线。
 *
 * <p>为什么需要离线替身：端到端评测依赖 pgvector 与付费模型，CI 与本地都未必具备。
 * 这里用「词袋余弦 + 关键词覆盖率」替代 embedding 与 rerank 模型，只评测
 * <b>检索排序</b>这一段，因此结论只能用于「同一替身下的策略前后对比」，
 * 不能当作线上效果指标。在线指标（首 token 延迟、引用准确率、改写成功率）
 * 必须由真实链路采集，见 {@code perf/rag/README.md}。</p>
 *
 * <p>输出：{@code perf/rag/baseline-offline.md}（同样的输入必然生成同样的文件，便于 diff）。</p>
 */
public final class RagOfflineEvaluationHarness {

  /** 检索时使用的相似度下限，模拟 pgvector 的 minScore 过滤，拒答样本靠它被过滤为空。 */
  private static final double MIN_SCORE = 0.02;
  private static final int TOP_K = 5;
  private static final String BASELINE_FILE = "baseline-offline.md";

  private RagOfflineEvaluationHarness() {
  }

  /** 语料中的一篇文档（不含任何真实个人数据）。 */
  public record CorpusDoc(String docId, String title, String section, String text) {
  }

  /** 一条评测样本。 */
  public record EvalCase(
      String id,
      String category,
      String difficulty,
      String question,
      List<String> expectedKeywords,
      List<String> expectedDocIds,
      boolean mustRefuse,
      String providerProfile,
      String version) {
  }

  /** 单条样本的评测结果。 */
  public record CaseResult(
      String id,
      String category,
      double recallAtK,
      double reciprocalRank,
      double refusalAccuracy,
      long retrievalNanos,
      List<String> retrievedDocIds) {
  }

  /** 某个检索策略的聚合结果。 */
  public record ModeReport(
      String mode,
      int caseCount,
      double recallAtK,
      double mrr,
      double refusalAccuracy,
      long p50Nanos,
      long p95Nanos) {
  }

  public static void main(String[] args) throws IOException {
    Path perfDir = args.length > 0 ? Path.of(args[0]) : resolvePerfDir();
    Path baseline = evaluate(perfDir);
    System.out.println("基线已写入: " + baseline.toAbsolutePath());
  }

  /** 读取数据集、跑三种策略并写出基线报告，返回报告文件路径。 */
  public static Path evaluate(Path perfDir) throws IOException {
    List<CorpusDoc> corpus = loadCorpus(perfDir.resolve("corpus-v1.jsonl"));
    List<EvalCase> cases = loadEvalSet(perfDir.resolve("eval-set-v1.jsonl"));
    Map<String, List<CaseResult>> byMode = new LinkedHashMap<>();
    for (RagRetrievalMode mode : List.of(
        RagRetrievalMode.VECTOR, RagRetrievalMode.HYBRID, RagRetrievalMode.HYBRID_RERANK)) {
      byMode.put(mode.name().toLowerCase(Locale.ROOT), runMode(corpus, cases, mode));
    }
    String report = renderReport(perfDir, corpus, cases, byMode);
    Path output = perfDir.resolve(BASELINE_FILE);
    Files.createDirectories(perfDir);
    Files.writeString(output, report, StandardCharsets.UTF_8);
    System.out.println(report);
    return output;
  }

  /** 用指定策略跑全部样本。 */
  public static List<CaseResult> runMode(List<CorpusDoc> corpus, List<EvalCase> cases, RagRetrievalMode mode) {
    List<Map<String, Double>> corpusVectors = new ArrayList<>(corpus.size());
    for (CorpusDoc doc : corpus) {
      corpusVectors.add(embed(doc.title() + " " + doc.section() + " " + doc.text()));
    }
    List<CaseResult> results = new ArrayList<>(cases.size());
    for (EvalCase evalCase : cases) {
      long startedAt = System.nanoTime();
      List<String> retrieved = retrieve(corpus, corpusVectors, evalCase.question(), mode);
      long elapsed = System.nanoTime() - startedAt;
      results.add(new CaseResult(
          evalCase.id(),
          evalCase.category(),
          RagEvaluationMetrics.recallAtK(evalCase.expectedDocIds(), retrieved, TOP_K),
          RagEvaluationMetrics.reciprocalRank(evalCase.expectedDocIds(), retrieved),
          RagEvaluationMetrics.refusalAccuracy(evalCase.mustRefuse(), retrieved),
          elapsed,
          retrieved));
    }
    return results;
  }

  private static List<String> retrieve(
      List<CorpusDoc> corpus, List<Map<String, Double>> corpusVectors, String question, RagRetrievalMode mode) {
    Map<String, Double> queryVector = embed(question);
    List<ScoredDoc> scored = new ArrayList<>(corpus.size());
    for (int index = 0; index < corpus.size(); index++) {
      CorpusDoc doc = corpus.get(index);
      double cosine = cosine(queryVector, corpusVectors.get(index));
      if (cosine < MIN_SCORE) {
        continue;
      }
      double keyword = RagCandidateRanker.keywordScore(question, doc.title() + doc.text());
      double fused = mode.needsRanking()
          ? mode.keywordWeight() * keyword + (1 - mode.keywordWeight()) * cosine
          : cosine;
      scored.add(new ScoredDoc(doc, fused, cosine));
    }
    if (scored.isEmpty()) {
      return List.of();
    }
    scored.sort(Comparator.comparingDouble(ScoredDoc::fused).reversed()
        .thenComparing(item -> item.doc().docId()));

    if (mode != RagRetrievalMode.HYBRID_RERANK) {
      return limit(scored);
    }
    // 重排模式复用生产代码：候选池按融合分给出，再由 RagCandidateRanker 决定最终顺序。
    List<Document> pool = new ArrayList<>(scored.size());
    for (ScoredDoc item : scored) {
      pool.add(new Document(item.doc().docId(), item.doc().text(), Map.of()));
    }
    List<Document> ranked = RagCandidateRanker.rank(pool, question, mode, TOP_K);
    List<String> ids = new ArrayList<>(ranked.size());
    for (Document document : ranked) {
      ids.add(document.getId());
    }
    return ids;
  }

  private static List<String> limit(List<ScoredDoc> scored) {
    int limit = Math.min(TOP_K, scored.size());
    List<String> ids = new ArrayList<>(limit);
    for (int index = 0; index < limit; index++) {
      ids.add(scored.get(index).doc().docId());
    }
    return ids;
  }

  private static String renderReport(
      Path perfDir,
      List<CorpusDoc> corpus,
      List<EvalCase> cases,
      Map<String, List<CaseResult>> byMode) {
    StringBuilder builder = new StringBuilder();
    builder.append("# RAG 离线评测基线（自动生成）\n\n");
    builder.append("> 本文件由 `RagOfflineEvaluationTest` / `RagOfflineEvaluationHarness` 生成，请勿手工编辑。\n");
    builder.append("> 召回与排序指标可逐次复现；延迟列是本次运行实测值，每次略有差异，只看量级。\n\n");
    builder.append("- 评测集：").append(perfDir.resolve("eval-set-v1.jsonl").getFileName())
        .append("（").append(cases.size()).append(" 条，version=")
        .append(cases.isEmpty() ? "-" : cases.getFirst().version()).append("）\n");
    builder.append("- 语料：").append(perfDir.resolve("corpus-v1.jsonl").getFileName())
        .append("（").append(corpus.size()).append(" 篇）\n");
    builder.append("- 检索器：离线替身（词袋余弦 + 关键词覆盖率），无任何外部调用\n");
    builder.append("- 相似度下限：").append(MIN_SCORE).append("，TopK：").append(TOP_K).append("\n");
    builder.append("- 未覆盖：首 token 延迟、引用准确率、改写成功率（需要在线 LLM 与 pgvector，见 README）\n\n");

    builder.append("| 策略 | 样本数 | Recall@5 | MRR@5 | 拒答准确率 | 检索 p50 (ms) | 检索 p95 (ms) |\n");
    builder.append("| --- | --- | --- | --- | --- | --- | --- |\n");
    for (Map.Entry<String, List<CaseResult>> entry : byMode.entrySet()) {
      ModeReport report = aggregate(entry.getKey(), entry.getValue());
      builder.append(String.format(Locale.ROOT,
          "| %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f |%n",
          report.mode(), report.caseCount(), report.recallAtK(), report.mrr(),
          report.refusalAccuracy(), report.p50Nanos() / 1_000_000.0, report.p95Nanos() / 1_000_000.0));
    }
    builder.append("\n");
    builder.append("口径说明：\n\n");
    builder.append("- Recall@5 / MRR@5 只统计 `expectedDocIds` 非空的样本；拒答样本单独看拒答准确率。\n");
    builder.append("- 拒答准确率 = 应拒答样本中「检索结果为空」的比例。\n");
    builder.append("- 检索耗时只包含本进程内的打分与排序，不含 embedding 网络往返，不能当作线上 P95。\n");
    return builder.toString();
  }

  /** 聚合：Recall/MRR 过滤掉无期望文档的拒答样本，延迟取最近秩百分位。 */
  public static ModeReport aggregate(String mode, List<CaseResult> results) {
    List<Double> recalls = new ArrayList<>();
    List<Double> rrs = new ArrayList<>();
    List<Double> refusals = new ArrayList<>();
    List<Long> latencies = new ArrayList<>();
    for (CaseResult result : results) {
      latencies.add(result.retrievalNanos());
      if ("refusal".equals(result.category())) {
        refusals.add(result.refusalAccuracy());
      } else {
        recalls.add(result.recallAtK());
        rrs.add(result.reciprocalRank());
      }
    }
    return new ModeReport(
        mode,
        results.size(),
        RagEvaluationMetrics.mean(recalls),
        RagEvaluationMetrics.mean(rrs),
        RagEvaluationMetrics.mean(refusals),
        RagEvaluationMetrics.percentile(latencies, 0.50),
        RagEvaluationMetrics.percentile(latencies, 0.95));
  }

  // ==================== 数据集读取 ====================

  public static List<CorpusDoc> loadCorpus(Path path) throws IOException {
    List<CorpusDoc> docs = new ArrayList<>();
    for (JsonNode node : readJsonLines(path)) {
      docs.add(new CorpusDoc(
          node.path("docId").asText(),
          node.path("title").asText(),
          node.path("section").asText(),
          node.path("text").asText()));
    }
    return docs;
  }

  public static List<EvalCase> loadEvalSet(Path path) throws IOException {
    List<EvalCase> cases = new ArrayList<>();
    for (JsonNode node : readJsonLines(path)) {
      cases.add(new EvalCase(
          node.path("id").asText(),
          node.path("category").asText(),
          node.path("difficulty").asText(),
          node.path("question").asText(),
          textList(node.path("expectedKeywords")),
          textList(node.path("expectedDocIds")),
          node.path("mustRefuse").asBoolean(false),
          node.path("providerProfile").asText(),
          node.path("version").asText()));
    }
    return cases;
  }

  private static List<JsonNode> readJsonLines(Path path) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<JsonNode> nodes = new ArrayList<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.trim().startsWith("#")) {
        continue;
      }
      nodes.add(mapper.readTree(line));
    }
    return nodes;
  }

  private static List<String> textList(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node == null || !node.isArray()) {
      return values;
    }
    for (JsonNode item : node) {
      values.add(item.asText());
    }
    return values;
  }

  // ==================== 离线替身 ====================

  /**
   * 词袋余弦：用精确词项（ASCII 词 + 中文二元组）构造稀疏向量，
   * 不引入哈希碰撞，也不依赖任何 embedding 服务。数量级与真实 embedding 无关，
   * 只用于在同一替身内部比较排序策略。
   */
  static Map<String, Double> embed(String text) {
    Map<String, Double> vector = new LinkedHashMap<>();
    for (String token : RagCandidateRanker.tokenize(text)) {
      vector.merge(token, 1.0, Double::sum);
    }
    double norm = 0.0;
    for (double value : vector.values()) {
      norm += value * value;
    }
    if (norm <= 0.0) {
      return vector;
    }
    double sqrt = Math.sqrt(norm);
    vector.replaceAll((token, value) -> value / sqrt);
    return vector;
  }

  static double cosine(Map<String, Double> left, Map<String, Double> right) {
    if (left.isEmpty() || right.isEmpty()) {
      return 0.0;
    }
    Map<String, Double> smaller = left.size() <= right.size() ? left : right;
    Map<String, Double> larger = smaller == left ? right : left;
    double dot = 0.0;
    for (Map.Entry<String, Double> entry : smaller.entrySet()) {
      Double other = larger.get(entry.getKey());
      if (other != null) {
        dot += entry.getValue() * other;
      }
    }
    return dot;
  }

  /** 从当前工作目录向上找 perf/rag，兼容在 app/ 或仓库根目录运行。 */
  public static Path resolvePerfDir() {
    Path current = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 5 && current != null; depth++) {
      Path candidate = current.resolve("perf").resolve("rag");
      if (Files.exists(candidate.resolve("eval-set-v1.jsonl"))) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("未找到 perf/rag/eval-set-v1.jsonl，请从仓库内运行");
  }

  private record ScoredDoc(CorpusDoc doc, double fused, double cosine) {
  }
}
