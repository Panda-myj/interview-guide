package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库问答计数与检索的事务语义测试。
 * <p>
 * 计数表示“该知识库真正参与回答的次数”，必须发生在检索成功之后，
 * 否则检索失败会留下与结果不一致的计数（TECH-006）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("知识库问答计数顺序")
class KnowledgeBaseQueryServiceCountOrderTest {

    private static final Long OWNER_USER_ID = 100L;
    private static final List<Long> KB_IDS = List.of(1L);
    private static final String QUESTION = "Java 集合的底层实现是什么";

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private KnowledgeBaseVectorService vectorService;

    @Mock
    private KnowledgeBaseListService listService;

    @Mock
    private KnowledgeBaseCountService countService;

    private KnowledgeBaseQueryService queryService;

    @BeforeEach
    void setUp() throws Exception {
        KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
        properties.getRewrite().setEnabled(false);
        queryService = new KnowledgeBaseQueryService(
            llmProviderRegistry,
            vectorService,
            listService,
            countService,
            properties,
            new DefaultResourceLoader()
        );
        when(listService.getOwnerUserId()).thenReturn(OWNER_USER_ID);
    }

    @Nested
    @DisplayName("检索未命中")
    class NoHitTests {

        @Test
        @DisplayName("检索结果为空时不增加提问计数")
        void doesNotCountWhenNoDocumentFound() {
            when(vectorService.similaritySearch(anyString(), anyList(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

            String answer = queryService.answerQuestion(KB_IDS, QUESTION);

            assertThat(answer).contains("未检索到相关信息");
            verify(countService, never()).updateQuestionCounts(anyList());
        }

        @Test
        @DisplayName("向量检索抛异常时不增加提问计数且异常向上传播")
        void doesNotCountWhenVectorSearchFails() {
            when(vectorService.similaritySearch(anyString(), anyList(), any(), anyInt(), anyDouble()))
                .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量检索失败"));

            assertThatThrownBy(() -> queryService.answerQuestion(KB_IDS, QUESTION))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("向量检索失败");

            verify(countService, never()).updateQuestionCounts(anyList());
        }
    }

    @Nested
    @DisplayName("检索命中")
    class HitTests {

        @Test
        @DisplayName("检索命中后在生成回答前计数一次")
        void countsOnceAfterRetrievalSucceeds() {
            Document document = new Document("Java HashMap 基于数组加链表实现",
                Map.of("kb_id", "1", "user_id", String.valueOf(OWNER_USER_ID)));
            when(vectorService.similaritySearch(anyString(), anyList(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(document));
            // 不装配 ChatClient：让 LLM 调用失败，用于验证计数发生在 LLM 调用之前
            when(llmProviderRegistry.getDefaultChatClient()).thenReturn(null);

            assertThatThrownBy(() -> queryService.answerQuestion(KB_IDS, QUESTION))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库查询失败");

            verify(countService, times(1)).updateQuestionCounts(KB_IDS);
        }

        @Test
        @DisplayName("流式问答在检索未命中时不增加提问计数")
        void streamDoesNotCountWhenNoDocumentFound() {
            when(vectorService.similaritySearch(anyString(), anyList(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

            String first = queryService.answerQuestionStream(KB_IDS, QUESTION, List.of()).blockFirst();

            assertThat(first).contains("未检索到相关信息");
            verify(countService, never()).updateQuestionCounts(anyList());
            verify(llmProviderRegistry, never()).getDefaultChatClient();
        }
    }
}
