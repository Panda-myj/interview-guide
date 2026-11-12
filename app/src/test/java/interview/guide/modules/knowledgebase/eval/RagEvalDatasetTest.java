package interview.guide.modules.knowledgebase.eval;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测集是「可复现」的前提：条数、字段、引用完整性、敏感信息都必须被测试守住，
 * 否则一次随手编辑就会让历史基线失去意义。
 */
@DisplayName("RAG 评测集结构校验")
class RagEvalDatasetTest {

  private static final int EXPECTED_CASE_COUNT = 30;
  private static final Set<String> ALLOWED_CATEGORIES =
      Set.of("recall", "citation", "refusal", "multi_hop");
  private static final Set<String> ALLOWED_DIFFICULTIES = Set.of("easy", "medium", "hard");
  /** 明显是密钥/口令的片段，评测集不允许出现。 */
  private static final Pattern SECRET_PATTERN = Pattern.compile(
      "(sk-[A-Za-z0-9]{8,})|(Bearer\\s+[A-Za-z0-9._-]{8,})|(?i)(api[_-]?key\\s*[:=])|(?i)(password\\s*[:=])");

  private static Path perfDir;
  private static List<RagOfflineEvaluationHarness.EvalCase> cases;
  private static List<RagOfflineEvaluationHarness.CorpusDoc> corpus;

  @BeforeAll
  static void load() throws IOException {
    perfDir = RagOfflineEvaluationHarness.resolvePerfDir();
    cases = RagOfflineEvaluationHarness.loadEvalSet(perfDir.resolve("eval-set-v1.jsonl"));
    corpus = RagOfflineEvaluationHarness.loadCorpus(perfDir.resolve("corpus-v1.jsonl"));
  }

  @Nested
  @DisplayName("样本数量与字段")
  class StructureTests {

    @Test
    @DisplayName("评测集恰好 30 条")
    void hasExactlyThirtyCases() {
      assertThat(cases).hasSize(EXPECTED_CASE_COUNT);
    }

    @Test
    @DisplayName("样本 ID 唯一且非空")
    void hasUniqueIds() {
      Set<String> ids = new HashSet<>();
      for (RagOfflineEvaluationHarness.EvalCase evalCase : cases) {
        assertThat(evalCase.id()).as("样本 ID 不能为空").isNotBlank();
        assertThat(ids.add(evalCase.id())).as("样本 ID 重复: %s", evalCase.id()).isTrue();
      }
    }

    @Test
    @DisplayName("分类与难度取值受控")
    void categoriesAndDifficultiesAreControlled() {
      for (RagOfflineEvaluationHarness.EvalCase evalCase : cases) {
        assertThat(ALLOWED_CATEGORIES).as("未知分类: %s", evalCase.category())
            .contains(evalCase.category());
        assertThat(ALLOWED_DIFFICULTIES).as("未知难度: %s", evalCase.difficulty())
            .contains(evalCase.difficulty());
      }
    }

    @Test
    @DisplayName("每条样本都带 version 与 providerProfile，保证可复现")
    void everyCaseCarriesReproducibleMetadata() {
      for (RagOfflineEvaluationHarness.EvalCase evalCase : cases) {
        assertThat(evalCase.version()).as("%s 缺少 version", evalCase.id()).isEqualTo("v1");
        assertThat(evalCase.providerProfile()).as("%s 缺少 providerProfile", evalCase.id()).isNotBlank();
        assertThat(evalCase.question()).as("%s 问题为空", evalCase.id()).isNotBlank();
      }
    }

    @Test
    @DisplayName("四类场景都有覆盖，且拒答样本必须标记 mustRefuse")
    void coversAllCategories() {
      Set<String> seen = new HashSet<>();
      for (RagOfflineEvaluationHarness.EvalCase evalCase : cases) {
        seen.add(evalCase.category());
        if ("refusal".equals(evalCase.category())) {
          assertThat(evalCase.mustRefuse()).as("%s 是拒答样本但未标记", evalCase.id()).isTrue();
          assertThat(evalCase.expectedDocIds()).as("%s 拒答样本不应有期望文档", evalCase.id()).isEmpty();
        } else {
          assertThat(evalCase.mustRefuse()).as("%s 非拒答样本不应标记 mustRefuse", evalCase.id()).isFalse();
          assertThat(evalCase.expectedDocIds()).as("%s 缺少期望文档", evalCase.id()).isNotEmpty();
        }
      }
      assertThat(seen).containsExactlyInAnyOrderElementsOf(ALLOWED_CATEGORIES);
    }
  }

  @Nested
  @DisplayName("与语料的一致性")
  class ConsistencyTests {

    @Test
    @DisplayName("期望文档 ID 都能在语料中找到")
    void expectedDocsExistInCorpus() {
      Set<String> corpusIds = new HashSet<>();
      for (RagOfflineEvaluationHarness.CorpusDoc doc : corpus) {
        corpusIds.add(doc.docId());
      }
      for (RagOfflineEvaluationHarness.EvalCase evalCase : cases) {
        assertThat(corpusIds).as("%s 引用了不存在的文档", evalCase.id())
            .containsAll(evalCase.expectedDocIds());
      }
    }

    @Test
    @DisplayName("语料文档 ID 唯一且正文非空")
    void corpusIsWellFormed() {
      Set<String> ids = new HashSet<>();
      for (RagOfflineEvaluationHarness.CorpusDoc doc : corpus) {
        assertThat(ids.add(doc.docId())).as("语料文档 ID 重复: %s", doc.docId()).isTrue();
        assertThat(doc.title()).isNotBlank();
        assertThat(doc.text()).isNotBlank();
      }
    }
  }

  @Nested
  @DisplayName("敏感信息")
  class SecretTests {

    @Test
    @DisplayName("评测集与语料都不包含密钥、口令等敏感片段")
    void datasetContainsNoSecrets() throws IOException {
      for (String fileName : List.of("eval-set-v1.jsonl", "corpus-v1.jsonl")) {
        String content = Files.readString(perfDir.resolve(fileName), StandardCharsets.UTF_8);
        assertThat(SECRET_PATTERN.matcher(content).find())
            .as("%s 中出现了疑似密钥或口令", fileName)
            .isFalse();
      }
    }

    @Test
    @DisplayName("评测集不包含个人身份信息（手机号 / 身份证号）")
    void datasetContainsNoPersonalData() throws IOException {
      String content = Files.readString(perfDir.resolve("eval-set-v1.jsonl"), StandardCharsets.UTF_8);
      assertThat(Pattern.compile("1[3-9]\\d{9}").matcher(content).find()).isFalse();
      assertThat(Pattern.compile("\\d{17}[0-9Xx]").matcher(content).find()).isFalse();
      assertThat(content.toLowerCase(Locale.ROOT)).doesNotContain("password", "secret=");
    }
  }
}
