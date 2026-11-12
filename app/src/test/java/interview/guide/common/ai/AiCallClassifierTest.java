package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常分类决定三件事：是否重试、是否计入熔断、对外暴露什么错误码。
 * 分类错了会直接造成「无限重试放大费用」或「一次性网络抖动就失败」。
 */
@DisplayName("AI 调用异常分类")
class AiCallClassifierTest {

    private static RestClientResponseException httpError(int status) {
        return new RestClientResponseException(
            "http " + status, status, "status", null, null, null);
    }

    @Nested
    @DisplayName("可重试的临时故障")
    class RetryableTests {

        @Test
        @DisplayName("超时归为 TIMEOUT 且映射为 AI_SERVICE_TIMEOUT")
        void socketTimeoutIsRetryableTimeout() {
            AiCallClassifier.Assessment assessment =
                AiCallClassifier.classify(new SocketTimeoutException("read timed out"));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.TIMEOUT);
            assertThat(assessment.retryable()).isTrue();
            assertThat(assessment.availabilityFailure()).isTrue();
            assertThat(assessment.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT);
        }

        @Test
        @DisplayName("普通 IO 异常归为 TRANSIENT")
        void ioExceptionIsTransient() {
            AiCallClassifier.Assessment assessment =
                AiCallClassifier.classify(new IOException("connection reset"));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.TRANSIENT);
            assertThat(assessment.retryable()).isTrue();
            assertThat(assessment.errorCode()).isNull();
        }

        @Test
        @DisplayName("5xx 归为 TRANSIENT 并计入可用性故障")
        void serverErrorIsTransient() {
            AiCallClassifier.Assessment assessment = AiCallClassifier.classify(httpError(503));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.TRANSIENT);
            assertThat(assessment.retryable()).isTrue();
            assertThat(assessment.availabilityFailure()).isTrue();
        }

        @Test
        @DisplayName("429 归为 RATE_LIMIT 且映射为 AI_RATE_LIMIT_EXCEEDED")
        void tooManyRequestsIsRateLimit() {
            AiCallClassifier.Assessment assessment = AiCallClassifier.classify(httpError(429));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.RATE_LIMIT);
            assertThat(assessment.retryable()).isTrue();
            assertThat(assessment.errorCode()).isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("输出格式解析失败可重试，但不能触发熔断")
        void parseFailureRetryableWithoutTrippingCircuit() {
            AiCallClassifier.Assessment assessment =
                AiCallClassifier.classify(new IllegalStateException("not a json object"));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.PARSE);
            assertThat(assessment.retryable()).isTrue();
            assertThat(assessment.availabilityFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("不可重试的错误")
    class NonRetryableTests {

        @Test
        @DisplayName("401 归为 AUTH，不重试以免重复计费")
        void unauthorizedIsNotRetryable() {
            AiCallClassifier.Assessment assessment = AiCallClassifier.classify(httpError(401));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.AUTH);
            assertThat(assessment.retryable()).isFalse();
            assertThat(assessment.errorCode()).isEqualTo(ErrorCode.AI_API_KEY_INVALID);
        }

        @Test
        @DisplayName("400 归为 CLIENT，不重试")
        void badRequestIsClientError() {
            AiCallClassifier.Assessment assessment = AiCallClassifier.classify(httpError(400));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.CLIENT);
            assertThat(assessment.retryable()).isFalse();
        }

        @Test
        @DisplayName("配额耗尽归为 QUOTA，不重试")
        void quotaExhaustedIsTerminal() {
            AiCallClassifier.Assessment assessment =
                AiCallClassifier.classify(new IllegalStateException("insufficient_quota: 余额不足"));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.QUOTA);
            assertThat(assessment.retryable()).isFalse();
            assertThat(assessment.errorCode()).isEqualTo(ErrorCode.AI_RATE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("业务异常原样放行，既不算故障也不重试")
        void businessExceptionIsTerminal() {
            AiCallClassifier.Assessment assessment =
                AiCallClassifier.classify(new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "查询失败"));

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.BUSINESS);
            assertThat(assessment.retryable()).isFalse();
            assertThat(assessment.availabilityFailure()).isFalse();
        }
    }

    @Nested
    @DisplayName("异常链穿透")
    class CauseTraversalTests {

        @Test
        @DisplayName("包装后的超时异常仍能识别出来")
        void detectsNestedTimeout() {
            RuntimeException wrapped = new RuntimeException("invoke failed", new SocketTimeoutException("timeout"));

            AiCallClassifier.Assessment assessment = AiCallClassifier.classify(wrapped);

            assertThat(assessment.category()).isEqualTo(AiCallClassifier.Category.TIMEOUT);
        }

        @Test
        @DisplayName("空异常按可重试处理，避免丢弃未知故障")
        void nullErrorIsTransient() {
            AiCallClassifier.Assessment assessment = AiCallClassifier.classify(null);

            assertThat(assessment.retryable()).isTrue();
            assertThat(assessment.availabilityFailure()).isTrue();
        }
    }
}
