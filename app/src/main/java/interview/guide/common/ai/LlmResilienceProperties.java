package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 调用韧性配置：重试退避与熔断阈值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.resilience")
public class LlmResilienceProperties {

  /** 是否对可重试错误做重试（不可重试错误始终立即失败）。 */
  private boolean retryEnabled = true;

  /** 首次退避时长，默认 300ms，之后指数增长。 */
  private Duration backoffInitial = Duration.ofMillis(300);

  /** 单次退避上限，默认 2s。 */
  private Duration backoffMax = Duration.ofSeconds(2);

  /** 是否启用按调用场景的熔断器。 */
  private boolean circuitBreakerEnabled = true;

  /** 连续可用性失败多少次后熔断。 */
  private int failureThreshold = 5;

  /** 熔断持续时间，超时后放行一次探活请求。 */
  private Duration openDuration = Duration.ofSeconds(30);

  public long backoffMillis(int failedAttempts) {
    long initial = Math.max(50L, backoffInitial == null ? 300L : backoffInitial.toMillis());
    long max = Math.max(initial, backoffMax == null ? 2000L : backoffMax.toMillis());
    int shift = Math.min(6, Math.max(0, failedAttempts - 1));
    long base = Math.min(max, initial << shift);
    long jitter = (long) (base * 0.1 * Math.random());
    return Math.min(max, base + jitter);
  }

  public long openDurationMillis() {
    return Math.max(1000L, openDuration == null ? 30000L : openDuration.toMillis());
  }

  public int effectiveFailureThreshold() {
    return Math.max(1, failureThreshold);
  }
}
