package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断器的核心价值：供应商故障时快速失败，不再持续重试放大费用。
 */
@DisplayName("AI 调用熔断器")
class AiCircuitBreakerTest {

    private static final String KEY = "interview_question_generation";

    private LlmResilienceProperties properties;
    private AiCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        properties = new LlmResilienceProperties();
        properties.setFailureThreshold(2);
        properties.setOpenDuration(Duration.ofSeconds(30));
        circuitBreaker = new AiCircuitBreaker(properties, null);
    }

    @Nested
    @DisplayName("阈值与打开")
    class ThresholdTests {

        @Test
        @DisplayName("未达阈值时保持可用")
        void staysClosedBelowThreshold() {
            circuitBreaker.recordFailure(KEY);

            assertThat(circuitBreaker.isOpen(KEY)).isFalse();
            assertThatCode(() -> circuitBreaker.acquire(KEY)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("连续失败达到阈值后熔断并抛出 AI_SERVICE_UNAVAILABLE")
        void opensAfterThreshold() {
            circuitBreaker.recordFailure(KEY);
            circuitBreaker.recordFailure(KEY);

            assertThat(circuitBreaker.isOpen(KEY)).isTrue();
            assertThatThrownBy(() -> circuitBreaker.acquire(KEY))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                    .isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode()));
        }

        @Test
        @DisplayName("一次成功调用清零失败计数")
        void successResetsFailureCount() {
            circuitBreaker.recordFailure(KEY);
            circuitBreaker.recordSuccess(KEY);
            circuitBreaker.recordFailure(KEY);

            assertThat(circuitBreaker.isOpen(KEY)).isFalse();
        }

        @Test
        @DisplayName("reset 后立即恢复可用")
        void resetClearsWindow() {
            circuitBreaker.recordFailure(KEY);
            circuitBreaker.recordFailure(KEY);
            circuitBreaker.reset(KEY);

            assertThat(circuitBreaker.isOpen(KEY)).isFalse();
            assertThatCode(() -> circuitBreaker.acquire(KEY)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("半开探活")
    class HalfOpenTests {

        @Test
        @DisplayName("熔断到期后放行一次探活，探活成功即恢复")
        void halfOpenAllowsProbeAndRecovers() throws InterruptedException {
            properties.setFailureThreshold(1);
            properties.setOpenDuration(Duration.ofSeconds(1));
            circuitBreaker = new AiCircuitBreaker(properties, null);
            circuitBreaker.recordFailure(KEY);
            assertThat(circuitBreaker.isOpen(KEY)).isTrue();

            Thread.sleep(1100L);

            assertThatCode(() -> circuitBreaker.acquire(KEY)).doesNotThrowAnyException();
            circuitBreaker.recordSuccess(KEY);

            assertThat(circuitBreaker.isOpen(KEY)).isFalse();
        }

        @Test
        @DisplayName("探活失败继续熔断")
        void halfOpenProbeFailureKeepsOpen() throws InterruptedException {
            properties.setFailureThreshold(1);
            properties.setOpenDuration(Duration.ofSeconds(1));
            circuitBreaker = new AiCircuitBreaker(properties, null);
            circuitBreaker.recordFailure(KEY);

            Thread.sleep(1100L);
            circuitBreaker.acquire(KEY);
            circuitBreaker.recordFailure(KEY);

            assertThat(circuitBreaker.isOpen(KEY)).isTrue();
            assertThatThrownBy(() -> circuitBreaker.acquire(KEY))
                .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("开关关闭")
    class DisabledTests {

        @Test
        @DisplayName("关闭熔断后即使连续失败也不拒绝调用")
        void disabledNeverRejects() {
            properties.setCircuitBreakerEnabled(false);

            circuitBreaker.recordFailure(KEY);
            circuitBreaker.recordFailure(KEY);
            circuitBreaker.recordFailure(KEY);

            assertThat(circuitBreaker.isOpen(KEY)).isFalse();
            assertThatCode(() -> circuitBreaker.acquire(KEY)).doesNotThrowAnyException();
        }
    }
}
