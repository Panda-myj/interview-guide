package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.time.Duration;
import java.util.regex.Pattern;
//这段代码是一个工厂工具类，专门用于构建 OpenAI 兼容的 API 客户端实例。它的核心价值在于自动处理 Base URL 的版本路径，并统一管理超时配置。
public final class ApiPathResolver {

  private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
  private static final int DEFAULT_READ_TIMEOUT = 300000;

  private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

  private ApiPathResolver() {}

  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
    return buildOpenAiClient(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }
/*. 工厂方法：构建带超时的 OpenAIClient
buildOpenAiClient 方法负责把 HttpClient 组装好，具体做了四件事：
凭证注入：将 apiKey 包装成 BearerTokenCredential（HTTP Header Authorization: Bearer xxx）。
超时配置：默认连接超时 10 秒，读取超时 300 秒（5分钟）。注意这个读超时极长，说明这个客户端很可能用于流式输出（Streaming）或处理耗时极长的推理任务。
HttpClient 适配：复用了 Spring AI 框架提供的 SpringAiOpenAiHttpClient，使得底层网络层与 Spring 生态保持一致（便于链路追踪和代理配置）。
实例化：最终生成 OpenAIClientImpl 具体实现类。*/
  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey,
      int connectTimeout, int readTimeout) {
    Timeout timeout = Timeout.builder()
        .connect(Duration.ofMillis(connectTimeout))
        .read(Duration.ofMillis(readTimeout))
        .build();
    ClientOptions options = ClientOptions.Companion.builder()
        .apiKey(apiKey)
        .credential(BearerTokenCredential.create(apiKey))
        .baseUrl(resolveVersionedBaseUrl(baseUrl))
        .timeout(timeout)
        .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
        .build();
    return new OpenAIClientImpl(options);
  }

  // baseUrl 可能带或不带 /v1 版本段（TRAILING_VERSION 检测）：已有版本原样返回，没有则补 /v1，避免手误或重复拼接。
  public static String resolveVersionedBaseUrl(String baseUrl) {
    String stripped = stripTrailingSlashes(baseUrl);
    if (baseUrlContainsVersion(stripped)) {
      return stripped;
    }
    return stripped + "/v1";
  }

  public static boolean baseUrlContainsVersion(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    String stripped = stripTrailingSlashes(baseUrl.trim());
    return TRAILING_VERSION.matcher(stripped).find();
  }

  public static String stripTrailingSlashes(String value) {
    if (value == null) {
      return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
