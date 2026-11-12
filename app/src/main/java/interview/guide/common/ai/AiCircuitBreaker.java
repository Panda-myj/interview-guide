package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量熔断器：按调用场景（如 interview_question_generation）统计连续可用性故障。
 *
 * <p>用于防止 LLM 供应商故障时的持续重试与费用放大：熔断期内直接返回
 * {@code AI_SERVICE_UNAVAILABLE}，不再发起外部调用；到期后放行一次探活，成功即恢复。</p>
 *
 * <p>只统计“可用性故障”（超时、网络、5xx、429、鉴权/配额），输出格式解析失败不计入。</p>
 */
@Slf4j
@Component
public class AiCircuitBreaker {

  private static final String METRIC_REJECTED = "app.ai.circuit.rejected";
  private static final String METRIC_OPENED = "app.ai.circuit.opened";

  private final LlmResilienceProperties properties;
  private final MeterRegistry meterRegistry;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public AiCircuitBreaker(
      LlmResilienceProperties properties,
      @Autowired(required = false) MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  /**
   * 判断当前是否允许调用；熔断中抛出 {@link BusinessException}。
   */
  public void acquire(String key) {
    if (!properties.isCircuitBreakerEnabled()) {
      return;
    }
    Window window = windows.computeIfAbsent(normalize(key), ignored -> new Window());
    synchronized (window) {
      if (!window.open) {
        return;
      }
      long now = System.currentTimeMillis();
      long openDuration = properties.openDurationMillis();
      if (now - window.openedAtMillis >= openDuration && !window.probeInFlight) {
        window.probeInFlight = true;
        window.probeStartedAtMillis = now;
        log.info("AI 熔断进入半开状态，放行一次探活请求: key={}", key);
        return;
      }
      // 探活请求可能因“非可用性错误”（如输出格式解析失败）既不计成功也不计失败，
      // 导致 probeInFlight 永久为 true、熔断再也无法恢复。超过一个熔断窗口后
      // 视为上一次探活结果已丢失，重新放行，保证进程内不会永久熔断。
      if (window.probeInFlight && now - window.probeStartedAtMillis >= openDuration) {
        window.probeStartedAtMillis = now;
        log.warn("AI 熔断探活请求超时未回报，重新放行一次探活: key={}", key);
        return;
      }
      recordRejected(key);
      throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
          "AI 服务暂时不可用（已熔断: " + key + "），请稍后重试");
    }
  }

  public void recordSuccess(String key) {
    if (!properties.isCircuitBreakerEnabled()) {
      return;
    }
    Window window = windows.get(normalize(key));
    if (window == null) {
      return;
    }
    synchronized (window) {
      if (window.open) {
        log.info("AI 熔断已恢复: key={}", key);
      }
      window.open = false;
      window.failures = 0;
      window.probeInFlight = false;
      window.probeStartedAtMillis = 0L;
      window.openedAtMillis = 0L;
    }
  }

  public void recordFailure(String key) {
    if (!properties.isCircuitBreakerEnabled()) {
      return;
    }
    Window window = windows.computeIfAbsent(normalize(key), ignored -> new Window());
    synchronized (window) {
      if (window.open) {
        // 半开探活失败：重新计时，继续熔断
        window.openedAtMillis = System.currentTimeMillis();
        window.probeInFlight = false;
        window.probeStartedAtMillis = 0L;
        log.warn("AI 熔断探活失败，继续保持熔断: key={}", key);
        return;
      }
      window.failures++;
      if (window.failures >= properties.effectiveFailureThreshold()) {
        window.open = true;
        window.openedAtMillis = System.currentTimeMillis();
        window.probeInFlight = false;
        window.probeStartedAtMillis = 0L;
        count(METRIC_OPENED, key);
        log.warn("AI 调用连续失败 {} 次，开启熔断 {}s: key={}",
            window.failures, properties.openDurationMillis() / 1000, key);
      }
    }
  }

  /** 当前是否处于熔断状态（运维/测试观察用）。 */
  public boolean isOpen(String key) {
    Window window = windows.get(normalize(key));
    if (window == null) {
      return false;
    }
    synchronized (window) {
      return window.open;
    }
  }

  /** 手动复位（配置变更或运维恢复后使用）。 */
  public void reset(String key) {
    windows.remove(normalize(key));
  }

  private void recordRejected(String key) {
    count(METRIC_REJECTED, key);
  }

  private void count(String metric, String key) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.counter(metric, "key", normalize(key)).increment();
  }

  private String normalize(String key) {
    return key == null || key.isBlank() ? "unknown" : key;
  }

  private static final class Window {
    private int failures;
    private boolean open;
    private boolean probeInFlight;
    /** 本次探活开始时间；用于识别“探活结果丢失”。 */
    private long probeStartedAtMillis;
    private long openedAtMillis;
  }
}
