package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 统一评估服务的失败传播语义测试。
 *
 * <p>核心约束：批次评估失败不允许降级为「0 分报告 + 成功状态」，
 * 否则异步评估任务会被误判成功，用户拿到一份无法重试的错误报告。</p>
 */
@DisplayName("统一面试评估失败传播")
class UnifiedEvaluationServiceFailureTest {

    @Nested
    @DisplayName("批次评估失败")
    class BatchFailureTests {

        @Test
        @DisplayName("批次评估抛出非业务异常时，向上抛 INTERVIEW_EVALUATION_FAILED，不返回全 0 分报告")
        void shouldPropagateBatchFailureAsBusinessException() throws IOException {
            StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
            doThrow(new RuntimeException("upstream 502"))
                .when(invoker)
                .invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(),
                    any(Logger.class));

            UnifiedEvaluationService service = newService(invoker);
            ChatClient chatClient = mock(ChatClient.class);
            List<QaRecord> qaRecords = List.of(new QaRecord(0, "自我介绍", "INTRO", "我是候选人"));

            assertThatThrownBy(() -> service.evaluate(chatClient, "session-1", qaRecords, "简历正文"))
                .as("批次评估失败必须让整次评估失败")
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                    .isEqualTo(ErrorCode.INTERVIEW_EVALUATION_FAILED.getCode()));
        }

        @Test
        @DisplayName("批次评估抛出业务异常时原样透传，错误码不被二次包装")
        void shouldKeepOriginalBusinessException() throws IOException {
            StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
            doThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "模型服务不可用"))
                .when(invoker)
                .invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(),
                    any(Logger.class));

            UnifiedEvaluationService service = newService(invoker);
            ChatClient chatClient = mock(ChatClient.class);
            List<QaRecord> qaRecords = List.of(new QaRecord(0, "自我介绍", "INTRO", "我是候选人"));

            assertThatThrownBy(() -> service.evaluate(chatClient, "session-2", qaRecords, "简历正文"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                    .isEqualTo(ErrorCode.AI_SERVICE_ERROR.getCode()));
        }
    }

    private UnifiedEvaluationService newService(StructuredOutputInvoker invoker) throws IOException {
        return new UnifiedEvaluationService(
            invoker,
            new DefaultResourceLoader(),
            new InterviewEvaluationProperties()
        );
    }
}
