package interview.guide.modules.knowledgebase.eval;

import interview.guide.modules.knowledgebase.service.RagRetrievalMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线评测回归：不连接数据库、不调用任何模型，只验证「脚本能跑通、口径有产出」。
 *
 * <p>按要求这里**不设通过门槛**（新策略允许暂时更差），但必须保证：
 * 三种策略都能在 30 条样本上产出完整结果，且基线文件可重复生成。</p>
 */
@DisplayName("RAG 离线评测")
class RagOfflineEvaluationTest {

  private static List<RagOfflineEvaluationHarness.CorpusDoc> corpus;
  private static List<RagOfflineEvaluationHarness.EvalCase> cases;

  @BeforeAll
  static void load() throws IOException {
    Path perfDir = RagOfflineEvaluationHarness.resolvePerfDir();
    corpus = RagOfflineEvaluationHarness.loadCorpus(perfDir.resolve("corpus-v1.jsonl"));
    cases = RagOfflineEvaluationHarness.loadEvalSet(perfDir.resolve("eval-set-v1.jsonl"));
  }

  @Nested
  @DisplayName("检索策略")
  class ModeTests {

    @Test
    @DisplayName("三种策略都能跑完 30 条样本并给出合法指标")
    void allModesProduceValidMetrics() {
      for (RagRetrievalMode mode : List.of(
          RagRetrievalMode.VECTOR, RagRetrievalMode.HYBRID, RagRetrievalMode.HYBRID_RERANK)) {
        List<RagOfflineEvaluationHarness.CaseResult> results =
            RagOfflineEvaluationHarness.runMode(corpus, cases, mode);
        assertThat(results).as("%s 结果条数", mode).hasSize(cases.size());

        RagOfflineEvaluationHarness.ModeReport report =
            RagOfflineEvaluationHarness.aggregate(mode.name(), results);
        assertThat(report.recallAtK()).isBetween(0.0, 1.0);
        assertThat(report.mrr()).isBetween(0.0, 1.0);
        assertThat(report.refusalAccuracy()).isBetween(0.0, 1.0);
        assertThat(report.p95Nanos()).isGreaterThanOrEqualTo(0L);
      }
    }

    @Test
    @DisplayName("同一输入重复运行结果完全一致（可复现）")
    void evaluationIsDeterministic() {
      List<RagOfflineEvaluationHarness.CaseResult> first =
          RagOfflineEvaluationHarness.runMode(corpus, cases, RagRetrievalMode.HYBRID);
      List<RagOfflineEvaluationHarness.CaseResult> second =
          RagOfflineEvaluationHarness.runMode(corpus, cases, RagRetrievalMode.HYBRID);

      for (int index = 0; index < first.size(); index++) {
        assertThat(second.get(index).retrievedDocIds())
            .as("样本 %s 的检索顺序不稳定", first.get(index).id())
            .isEqualTo(first.get(index).retrievedDocIds());
      }
    }
  }

  @Nested
  @DisplayName("基线报告")
  class BaselineTests {

    @Test
    @DisplayName("生成 baseline-offline.md 且包含全部策略行")
    void writesBaselineReport() throws IOException {
      Path perfDir = RagOfflineEvaluationHarness.resolvePerfDir();
      Path baseline = RagOfflineEvaluationHarness.evaluate(perfDir);

      assertThat(baseline).exists();
      String content = Files.readString(baseline);
      assertThat(content).contains("Recall@5", "MRR@5", "拒答准确率");
      assertThat(content).contains("vector", "hybrid", "hybrid_rerank");
      assertThat(content).contains("30 条");
    }
  }
}
