package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiCircuitBreaker;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.LlmResilienceProperties;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式问答的指标埋点：首 token 延迟、引用命中、回答结果都必须被记录，
 * 且指标记录不能改变流式输出的内容。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识库问答指标埋点")
class KnowledgeBaseQueryServiceMetricsTest {

  private static final List<Long> KB_IDS = List.of(7L);

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private KnowledgeBaseVectorService vectorService;

  @Mock
  private KnowledgeBaseListService listService;

  @Mock
  private KnowledgeBaseCountService countService;

  private ChatClient chatClient;
  private SimpleMeterRegistry registry;
  private KnowledgeBaseQueryService queryService;

  @BeforeEach
  void setUp() throws Exception {
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getRewrite().setEnabled(false);

    chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    registry = new SimpleMeterRegistry();

    queryService = new KnowledgeBaseQueryService(
        llmProviderRegistry,
        vectorService,
        listService,
        countService,
        properties,
        new DefaultResourceLoader(),
        new PromptSanitizer(new LlmProviderProperties()),
        new AiCircuitBreaker(new LlmResilienceProperties(), null),
        new RagMetrics(registry));
  }

  private void stubStream(String answer) {
    when(listService.getOwnerUserId()).thenReturn(100L);
    when(vectorService.similaritySearch(anyString(), anyList(), any(), anyInt(), anyDouble()))
        .thenReturn(List.of(new Document("doc-1", "HashMap 基于数组加链表实现", Map.of("kb_id", "7"))));
    when(llmProviderRegistry.getDefaultChatClient()).thenReturn(chatClient);
    when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
        .thenReturn(Flux.just(answer));
  }

  @Test
  @DisplayName("流式回答记录首 token 延迟、引用命中与回答状态")
  void recordsStreamingMetrics() {
    stubStream("HashMap 使用数组加链表 [S1]");

    List<String> chunks = queryService.answerQuestionStream(KB_IDS, "HashMap 怎么实现的").collectList().block();

    assertThat(chunks).isNotNull().isNotEmpty();
    assertThat(registry.get(RagMetrics.METRIC_FIRST_TOKEN).tag("provider", "unknown").timer().count())
        .isEqualTo(1L);
    assertThat(registry.get(RagMetrics.METRIC_CITATION).tag("hit", "true").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get(RagMetrics.METRIC_RESPONSE).tag("status", "answer").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("检索为空时记录 no_result，不进入模型调用")
  void recordsNoResultWithoutCallingModel() {
    when(listService.getOwnerUserId()).thenReturn(100L);
    when(vectorService.similaritySearch(anyString(), anyList(), any(), anyInt(), anyDouble()))
        .thenReturn(List.of());

    List<String> chunks = queryService.answerQuestionStream(KB_IDS, "语料之外的问题").collectList().block();

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst()).contains("未检索到相关信息");
    assertThat(registry.get(RagMetrics.METRIC_RESPONSE).tag("status", "no_result").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.find(RagMetrics.METRIC_FIRST_TOKEN).timer()).isNull();
  }
}
