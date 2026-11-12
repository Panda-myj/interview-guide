package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StructuredOutputInvoker 是结构化输出的唯一重试入口：
 * 可重试错误按退避重试，不可重试错误立即失败，熔断期内不再发起外部调用。
 */
@DisplayName("结构化输出调用韧性")
class StructuredOutputInvokerResilienceTest {

    private static final Logger LOG = LoggerFactory.getLogger(StructuredOutputInvokerResilienceTest.class);
    private static final String SYSTEM_PROMPT = "你是面试评估助手";
    private static final String USER_PROMPT = "请输出评估结果";

    private ChatClient chatClient;
    private StructuredOutputProperties properties;
    private LlmResilienceProperties resilience;
    private AiCircuitBreaker circuitBreaker;
    private StructuredOutputInvoker invoker;
    private BeanOutputConverter<Payload> converter;

    record Payload(String name) {
    }

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        properties = new StructuredOutputProperties();
        properties.setStructuredSchemaValidationEnabled(false);
        properties.setStructuredMetricsEnabled(false);
        properties.setStructuredMaxAttempts(2);

        resilience = new LlmResilienceProperties();
        resilience.setBackoffInitial(Duration.ofMillis(10));
        resilience.setBackoffMax(Duration.ofMillis(20));

        circuitBreaker = new AiCircuitBreaker(resilience, null);
        invoker = new StructuredOutputInvoker(properties, resilience, circuitBreaker, null);
        converter = new BeanOutputConverter<>(Payload.class);
    }

    /**
     * ChatClient#content() 不声明受检异常，这里用包装后的 IOException 模拟网络抖动，
     * 分类器会沿 cause 链识别为可重试的临时故障。
     */
    private static RuntimeException transientFailure() {
        return new RuntimeException("connection reset", new IOException("connection reset"));
    }

    private Payload invoke() {
        return invoker.invoke(chatClient, SYSTEM_PROMPT, USER_PROMPT, converter,
            ErrorCode.AI_SERVICE_ERROR, "结构化输出失败：", "测试场景", LOG);
    }

    private void stubContentSequence(String content) {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
            .thenReturn(content);
        clearInvocations(chatClient);
    }

    private void stubContentFailureThenSuccess() {
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
            .thenThrow(transientFailure())
            .thenReturn("{\"name\":\"ok\"}");
        clearInvocations(chatClient);
    }

    @Nested
    @DisplayName("可重试错误")
    class RetryTests {

        @Test
        @DisplayName("网络抖动后重试成功")
        void retriesTransientFailureThenSucceeds() {
            stubContentFailureThenSuccess();

            Payload payload = invoke();

            assertThat(payload.name()).isEqualTo("ok");
            verify(chatClient, times(2)).prompt();
        }

        @Test
        @DisplayName("关闭重试后不再重复调用，避免费用放大")
        void doesNotRetryWhenDisabled() {
            resilience.setRetryEnabled(false);
            when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(transientFailure())
                .thenReturn("{\"name\":\"ok\"}");
            clearInvocations(chatClient);

            assertThatThrownBy(() -> invoke()).isInstanceOf(BusinessException.class);

            verify(chatClient, times(1)).prompt();
        }
    }

    @Nested
    @DisplayName("不可重试错误")
    class NonRetryableTests {

        @Test
        @DisplayName("密钥无效时立即失败并暴露 AI_API_KEY_INVALID")
        void failsFastOnUnauthorized() {
            when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new RestClientResponseException(
                    "unauthorized", 401, "Unauthorized", null, null, null));
            clearInvocations(chatClient);

            assertThatThrownBy(() -> invoke())
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                    .isEqualTo(ErrorCode.AI_API_KEY_INVALID.getCode()));

            verify(chatClient, times(1)).prompt();
        }
    }

    @Nested
    @DisplayName("熔断")
    class CircuitTests {

        @Test
        @DisplayName("熔断打开后直接失败，不再发起外部调用")
        void rejectsWhenCircuitOpen() {
            properties.setStructuredMaxAttempts(1);
            resilience.setFailureThreshold(1);
            circuitBreaker = new AiCircuitBreaker(resilience, null);
            invoker = new StructuredOutputInvoker(properties, resilience, circuitBreaker, null);

            when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(transientFailure());
            clearInvocations(chatClient);

            assertThatThrownBy(() -> invoke()).isInstanceOf(BusinessException.class);

            assertThatThrownBy(() -> invoke())
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                    .isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode()));

            verify(chatClient, times(1)).prompt();
        }
    }

    @Nested
    @DisplayName("正常路径")
    class SuccessTests {

        @Test
        @DisplayName("一次成功不触发重试")
        void singleAttemptOnSuccess() {
            stubContentSequence("{\"name\":\"ok\"}");

            assertThat(invoke().name()).isEqualTo("ok");
            verify(chatClient, times(1)).prompt();
        }
    }
}
