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

/*1. 核心职责：智能拼接 URL 版本号
这是这个类最主要的设计目的。调用方可能会传入 https://api.openai.com 或 https://api.deepseek.com（不带版本号），也可能传入 https://api.openai.com/v1（带版本号）。

resolveVersionedBaseUrl 方法会先去掉末尾多余的反斜杠。

利用正则 TRAILING_VERSION（匹配末尾的 /v1、/v2beta 等）进行检测：

若已包含版本（如 /v1），直接原样返回。

若未包含版本，自动在末尾追加 /v1。

作用：防止开发者手误忘写版本号导致接口报错，同时也避免了重复添加 /v1/v1 的乌龙。*/
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
