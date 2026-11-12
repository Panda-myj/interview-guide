package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * LLM 调用异常分类：决定是否重试、是否计入熔断，以及对外暴露的错误码。
 *
 * <p>原则：</p>
 * <ul>
 *   <li>格式解析失败（JSON/Markdown）可重试，但不算可用性故障，不影响熔断。</li>
 *   <li>超时、网络中断、5xx、429 可重试，并计入可用性故障。</li>
 *   <li>401/403、配额耗尽、400 等客户端错误不可重试，避免重复计费。</li>
 * </ul>
 */
public final class AiCallClassifier {

  private static final int MAX_CAUSE_DEPTH = 6;
  private static final String[] QUOTA_HINTS = {
      "insufficient_quota", "insufficient quota", "quota", "billing", "exceeded your current quota",
      "余额不足", "配额"
  };
  private static final String[] AUTH_HINTS = {
      "unauthorized", "invalid api key", "incorrect api key", "invalid_api_key",
      "authentication", "api key not valid", "401"
  };

  private AiCallClassifier() {
  }

  public enum Category {
    /** 输出格式不可解析，可通过重新提示修复。 */
    PARSE(true, false, null),
    /** 网络中断、5xx 等临时故障。 */
    TRANSIENT(true, true, null),
    /** 429 限流，可退避重试。 */
    RATE_LIMIT(true, true, ErrorCode.AI_RATE_LIMIT_EXCEEDED),
    /** 读/连接超时。 */
    TIMEOUT(true, true, ErrorCode.AI_SERVICE_TIMEOUT),
    /** 密钥无效或权限不足，重试无意义且会放大费用。 */
    AUTH(false, true, ErrorCode.AI_API_KEY_INVALID),
    /** 配额/余额耗尽。 */
    QUOTA(false, true, ErrorCode.AI_RATE_LIMIT_EXCEEDED),
    /** 其余 4xx 客户端错误。 */
    CLIENT(false, true, null),
    /** 业务异常，不重试。 */
    BUSINESS(false, false, null);

    private final boolean retryable;
    private final boolean availabilityFailure;
    private final ErrorCode errorCode;

    Category(boolean retryable, boolean availabilityFailure, ErrorCode errorCode) {
      this.retryable = retryable;
      this.availabilityFailure = availabilityFailure;
      this.errorCode = errorCode;
    }

    public boolean isRetryable() {
      return retryable;
    }

    public boolean isAvailabilityFailure() {
      return availabilityFailure;
    }

    public ErrorCode getErrorCode() {
      return errorCode;
    }
  }

  public record Assessment(Category category, String reason) {

    public boolean retryable() {
      return category.isRetryable();
    }

    public boolean availabilityFailure() {
      return category.isAvailabilityFailure();
    }

    public ErrorCode errorCode() {
      return category.getErrorCode();
    }
  }

  public static Assessment classify(Throwable error) {
    if (error == null) {
      return new Assessment(Category.TRANSIENT, "unknown-error");
    }
    Throwable current = error;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      Assessment assessment = classifySingle(current);
      if (assessment != null) {
        return assessment;
      }
      current = current.getCause();
    }
    return new Assessment(Category.PARSE, "unrecognized-error");
  }

  private static Assessment classifySingle(Throwable error) {
    if (error instanceof BusinessException) {
      return new Assessment(Category.BUSINESS, "business-exception");
    }
    if (error instanceof RestClientResponseException responseException) {
      int status = responseException.getStatusCode().value();
      if (status == 401 || status == 403) {
        return new Assessment(Category.AUTH, "http-" + status);
      }
      if (status == 429) {
        return new Assessment(Category.RATE_LIMIT, "http-429");
      }
      if (status >= 500) {
        return new Assessment(Category.TRANSIENT, "http-" + status);
      }
      if (status >= 400) {
        return new Assessment(Category.CLIENT, "http-" + status);
      }
    }
    if (error instanceof SocketTimeoutException) {
      return new Assessment(Category.TIMEOUT, "socket-timeout");
    }
    if (error instanceof IOException) {
      return new Assessment(Category.TRANSIENT, "io-error");
    }
    String type = error.getClass().getSimpleName();
    String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
    if ("NonTransientAiException".equals(type) || "NonTransientAiApiException".equals(type)) {
      if (containsAny(message, QUOTA_HINTS)) {
        return new Assessment(Category.QUOTA, "non-transient-quota");
      }
      if (containsAny(message, AUTH_HINTS)) {
        return new Assessment(Category.AUTH, "non-transient-auth");
      }
      return new Assessment(Category.CLIENT, "non-transient-client");
    }
    if ("TransientAiException".equals(type) || "TransientAiApiException".equals(type)) {
      return new Assessment(Category.TRANSIENT, "transient-ai-exception");
    }
    if (containsAny(message, QUOTA_HINTS)) {
      return new Assessment(Category.QUOTA, "quota-hint");
    }
    if (containsAny(message, AUTH_HINTS)) {
      return new Assessment(Category.AUTH, "auth-hint");
    }
    return null;
  }

  private static boolean containsAny(String text, String[] hints) {
    if (text.isEmpty()) {
      return false;
    }
    for (String hint : hints) {
      if (text.contains(hint)) {
        return true;
      }
    }
    return false;
  }
}
