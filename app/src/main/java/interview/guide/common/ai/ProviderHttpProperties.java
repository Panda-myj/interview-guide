package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Provider HTTP 客户端超时配置。
 *
 * <p>显式限制连接与读取超时，避免 LLM 请求挂死线程池。读取超时按“单次读取”计，
 * 流式输出不会因为总时长超限而中断。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.http")
public class ProviderHttpProperties {

  /** 建连超时，默认 10s。 */
  private Duration connectTimeout = Duration.ofSeconds(10);

  /** 读取超时，默认 180s（流式按分片计算）。 */
  private Duration readTimeout = Duration.ofSeconds(180);

  /** 客户端层重试次数，默认 0：重试统一由 StructuredOutputInvoker 做分类与退避。 */
  private int maxRetries = 0;

  public int connectTimeoutMillis() {
    return toMillis(connectTimeout, Duration.ofSeconds(10));
  }

  public int readTimeoutMillis() {
    return toMillis(readTimeout, Duration.ofSeconds(180));
  }

  private int toMillis(Duration value, Duration fallback) {
    Duration effective = value == null || value.isNegative() || value.isZero() ? fallback : value;
    long millis = effective.toMillis();
    return (int) Math.min(Integer.MAX_VALUE, Math.max(1000L, millis));
  }
}
