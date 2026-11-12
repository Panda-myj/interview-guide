package interview.guide.common.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时必须有明确且安全的下限，否则一个 0 值就会让请求立刻超时或永不超时。
 */
@DisplayName("Provider HTTP 超时配置")
class ProviderHttpPropertiesTest {

    @Test
    @DisplayName("默认连接 10s、读取 180s")
    void hasSaneDefaults() {
        ProviderHttpProperties properties = new ProviderHttpProperties();

        assertThat(properties.connectTimeoutMillis()).isEqualTo(10_000);
        assertThat(properties.readTimeoutMillis()).isEqualTo(180_000);
        assertThat(properties.getMaxRetries()).isZero();
    }

    @Test
    @DisplayName("零值或负值回退到默认超时")
    void fallsBackOnInvalidDuration() {
        ProviderHttpProperties properties = new ProviderHttpProperties();
        properties.setConnectTimeout(Duration.ZERO);
        properties.setReadTimeout(Duration.ofSeconds(-5));

        assertThat(properties.connectTimeoutMillis()).isEqualTo(10_000);
        assertThat(properties.readTimeoutMillis()).isEqualTo(180_000);
    }

    @Test
    @DisplayName("过小的超时被抬升到 1s 下限")
    void clampsTinyTimeout() {
        ProviderHttpProperties properties = new ProviderHttpProperties();
        properties.setConnectTimeout(Duration.ofMillis(1));
        properties.setReadTimeout(Duration.ofMillis(2));

        assertThat(properties.connectTimeoutMillis()).isEqualTo(1_000);
        assertThat(properties.readTimeoutMillis()).isEqualTo(1_000);
    }
}
