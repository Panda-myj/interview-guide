package interview.guide.common.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退避策略既要能迅速脱离抖动，又不能让单次等待无限增长拖住请求线程。
 */
@DisplayName("重试退避配置")
class LlmResiliencePropertiesTest {

    @Test
    @DisplayName("退避按指数增长且不超过上限")
    void backoffGrowsExponentially() {
        LlmResilienceProperties properties = new LlmResilienceProperties();
        properties.setBackoffInitial(Duration.ofMillis(100));
        properties.setBackoffMax(Duration.ofMillis(500));

        long first = properties.backoffMillis(1);
        long third = properties.backoffMillis(3);
        long tenth = properties.backoffMillis(10);

        assertThat(first).isBetween(100L, 110L);
        assertThat(third).isBetween(400L, 440L);
        assertThat(tenth).isBetween(450L, 500L);
    }

    @Test
    @DisplayName("退避有 50ms 下限，避免忙等")
    void backoffHasFloor() {
        LlmResilienceProperties properties = new LlmResilienceProperties();
        properties.setBackoffInitial(Duration.ofMillis(1));
        properties.setBackoffMax(Duration.ofMillis(1));

        assertThat(properties.backoffMillis(1)).isGreaterThanOrEqualTo(50L);
    }

    @Test
    @DisplayName("熔断阈值与持续时间有安全下限")
    void hasSafeFloors() {
        LlmResilienceProperties properties = new LlmResilienceProperties();
        properties.setFailureThreshold(0);
        properties.setOpenDuration(Duration.ofMillis(1));

        assertThat(properties.effectiveFailureThreshold()).isEqualTo(1);
        assertThat(properties.openDurationMillis()).isGreaterThanOrEqualTo(1_000L);
    }
}
