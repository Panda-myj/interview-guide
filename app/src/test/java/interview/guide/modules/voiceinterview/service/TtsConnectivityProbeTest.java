package interview.guide.modules.voiceinterview.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Qwen-TTS-Realtime 连通性探针（绕过 DashScope SDK），一次跑多个场景定位到底是哪一步被服务端拒绝。
 *
 * <p>背景：线上只能看到 OkHttp 的 "Control frame must be less than 125B."，
 * 因为 {@code QwenTtsRealtimeCallback} 没有 onFailure，协议级断链不会回调到业务代码。
 * 用 JDK 自带 {@code java.net.http.WebSocket} 复现后可见：服务端在 session.created 之后
 * 发送了一个 178 字节的 CLOSE 帧（RFC 6455 上限 125），被客户端判为协议违规，
 * 真正的错误信息因此被丢弃。
 *
 * <p>本探针对比三种用法，找出服务端能接受的那一种：
 * <ol>
 *   <li>完整 session.update（当前线上写法）</li>
 *   <li>完全不发 session.update，直接用 session.created 给的默认 server_commit 配置</li>
 *   <li>最小 session.update（只指定 voice）</li>
 * </ol>
 *
 * <p>默认跳过。手动执行：
 * <pre>
 * RUN_TTS_PROBE=1 ./gradlew :app:test --tests "*TtsConnectivityProbe*" --no-daemon
 * </pre>
 * 报告写入 {@code app/build/tts-probe-report.txt}。
 */
@DisplayName("Qwen TTS Realtime 连通性探针（手工执行）")
class TtsConnectivityProbeTest {

  private static final String DEFAULT_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
  private static final String MODEL = "qwen3-tts-flash-realtime";
  private static final String PROBE_TEXT = "你好，这是一次语音合成连通性探测。";
  private static final Path REPORT_PATH = Path.of("build", "tts-probe-report.txt");

  /** 当前线上使用的完整配置。 */
  private static final String FULL_UPDATE = "{\"type\":\"session.update\",\"session\":{"
      + "\"voice\":\"Cherry\",\"response_format\":\"pcm\",\"sample_rate\":24000,"
      + "\"mode\":\"commit\",\"language_type\":\"Chinese\"}}";

  /** 最小配置，只指定音色，其余吃服务端默认值。 */
  private static final String MIN_UPDATE = "{\"type\":\"session.update\",\"session\":{"
      + "\"voice\":\"Cherry\"}}";

  @Test
  @DisplayName("对比三种会话配置方式，找出能取到音频的那一种")
  void probe() throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_TTS_PROBE")),
        "跳过探针：设置 RUN_TTS_PROBE=1 才会真正发起网络请求");

    String apiKey = resolveApiKey();
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
        "跳过探针：未找到 AI_BAILIAN_API_KEY（环境变量或 .env）");

    String baseUrl = System.getenv().getOrDefault("APP_VOICE_TTS_URL", DEFAULT_URL);
    URI uri = URI.create(baseUrl + "?model=" + MODEL);
    String workspaceId = System.getenv("DASHSCOPE_WORKSPACE_ID");

    List<Scenario> scenarios = new ArrayList<>();
    scenarios.add(runScenario("场景1 完整 session.update（线上现状）", uri, apiKey, workspaceId, FULL_UPDATE));
    scenarios.add(runScenario("场景2 不发 session.update（用默认 server_commit）", uri, apiKey, workspaceId, null));
    scenarios.add(runScenario("场景3 最小 session.update（只设 voice）", uri, apiKey, workspaceId, MIN_UPDATE));

    String report = buildReport(uri, apiKey, scenarios);
    writeReport(report);

    if (scenarios.stream().noneMatch(Scenario::succeeded)) {
      fail("三个场景都没取到音频，完整诊断：\n" + report);
    }
  }

  private Scenario runScenario(String name, URI uri, String apiKey, String workspaceId,
                               String updatePayload) {
    List<String> events = new ArrayList<>();
    List<String> sendFailures = new ArrayList<>();
    AtomicInteger audioDeltas = new AtomicInteger();
    AtomicInteger audioBytes = new AtomicInteger();
    AtomicReference<Integer> closeCode = new AtomicReference<>();
    AtomicReference<String> closeReason = new AtomicReference<>();
    AtomicReference<Throwable> transportFailure = new AtomicReference<>();
    CountDownLatch closed = new CountDownLatch(1);
    long startMs = System.currentTimeMillis();
    boolean connected = false;

    WebSocket.Listener listener = new WebSocket.Listener() {
      private final StringBuilder buffer = new StringBuilder();

      @Override
      public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
      }

      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
          String message = buffer.toString();
          buffer.setLength(0);
          synchronized (events) {
            events.add(elapsed(startMs) + " " + message);
          }
          if (message.contains("\"response.audio.delta\"")) {
            audioDeltas.incrementAndGet();
            audioBytes.addAndGet(estimateBase64Bytes(message));
          }
        }
        webSocket.request(1);
        return null;
      }

      @Override
      public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeCode.set(statusCode);
        closeReason.set(reason);
        closed.countDown();
        return null;
      }

      @Override
      public void onError(WebSocket webSocket, Throwable error) {
        transportFailure.set(error);
        closed.countDown();
      }
    };

    try {
      WebSocket.Builder builder = HttpClient.newHttpClient()
          .newWebSocketBuilder()
          .header("Authorization", "Bearer " + apiKey)
          .connectTimeout(Duration.ofSeconds(10));
      if (workspaceId != null && !workspaceId.isBlank()) {
        builder.header("X-DashScope-WorkSpace", workspaceId.trim());
      }
      WebSocket webSocket = builder.buildAsync(uri, listener).get(15, TimeUnit.SECONDS);
      connected = true;

      // 等 session.created
      Thread.sleep(250);

      if (updatePayload != null) {
        sendText(webSocket, startMs, sendFailures, "session.update", updatePayload);
        Thread.sleep(250);
      }

      sendText(webSocket, startMs, sendFailures, "input_text_buffer.append",
          "{\"type\":\"input_text_buffer.append\",\"text\":\"" + PROBE_TEXT + "\"}");
      sendText(webSocket, startMs, sendFailures, "input_text_buffer.commit",
          "{\"type\":\"input_text_buffer.commit\"}");

      closed.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      sendFailures.add(elapsed(startMs) + " 场景中断: " + rootCause(e));
    }

    return new Scenario(name, connected, new ArrayList<>(events), audioDeltas.get(), audioBytes.get(),
        closeCode.get(), closeReason.get(), transportFailure.get(),
        new ArrayList<>(sendFailures), System.currentTimeMillis() - startMs);
  }

  private static String buildReport(URI uri, String apiKey, List<Scenario> scenarios) {
    StringBuilder report = new StringBuilder();
    report.append("=== Qwen-TTS-Realtime 探针报告 ===\n");
    report.append("URL     : ").append(uri).append('\n');
    report.append("API Key : ").append(mask(apiKey)).append('\n');
    report.append("Text    : ").append(PROBE_TEXT).append("\n\n");

    report.append("--- 结论速览 ---\n");
    for (Scenario scenario : scenarios) {
      report.append(String.format("%-46s => %s%n",
          scenario.name(),
          scenario.succeeded()
              ? "✅ 有音频 (" + scenario.audioDeltas() + " 片 / 约 " + scenario.audioBytes() + " B)"
              : "❌ 无音频"));
    }
    report.append('\n');

    for (Scenario scenario : scenarios) {
      report.append("========== ").append(scenario.name()).append(" ==========\n");
      report.append("握手成功   : ").append(scenario.connected()).append('\n');
      report.append("耗时       : ").append(scenario.durationMs()).append("ms\n");
      report.append("音频分片数 : ").append(scenario.audioDeltas()).append('\n');
      report.append("音频字节数 : ").append(scenario.audioBytes()).append('\n');
      report.append("close code : ")
          .append(scenario.closeCode() == null ? "(未收到 close)" : scenario.closeCode()).append('\n');
      report.append("close reason: ")
          .append(scenario.closeReason() == null || scenario.closeReason().isEmpty()
              ? "(空)" : truncate(scenario.closeReason(), 400))
          .append('\n');
      if (scenario.transportFailure() != null) {
        report.append("传输层异常 : ").append(rootCause(scenario.transportFailure())).append('\n');
      }
      report.append("服务端帧   :\n");
      if (scenario.events().isEmpty()) {
        report.append("  (一帧都没收到)\n");
      } else {
        for (String event : scenario.events()) {
          report.append("  ").append(truncate(event, 500)).append('\n');
        }
      }
      if (!scenario.sendFailures().isEmpty()) {
        report.append("发送失败   :\n");
        for (String failure : scenario.sendFailures()) {
          report.append("  ").append(failure).append('\n');
        }
      }
      report.append('\n');
    }
    return report.toString();
  }

  private static void sendText(WebSocket webSocket, long startMs, List<String> failures,
                               String eventName, String payload) {
    try {
      webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      failures.add(elapsed(startMs) + " 发送 " + eventName + " 失败: " + rootCause(e));
    }
  }

  private static String rootCause(Throwable throwable) {
    Throwable root = throwable;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName() + ": " + root.getMessage();
  }

  /** 只用于粗略统计音频体积，delta 是 base64 字符串。 */
  private static int estimateBase64Bytes(String message) {
    int idx = message.indexOf("\"delta\":\"");
    if (idx < 0) {
      return 0;
    }
    int start = idx + "\"delta\":\"".length();
    int end = message.indexOf('"', start);
    if (end < 0) {
      return 0;
    }
    return (end - start) * 3 / 4;
  }

  private static void writeReport(String content) throws Exception {
    System.out.println(content);
    Files.createDirectories(REPORT_PATH.getParent());
    Files.writeString(REPORT_PATH, content, StandardCharsets.UTF_8);
  }

  private static String elapsed(long startMs) {
    return "[" + (System.currentTimeMillis() - startMs) + "ms]";
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
  }

  private static String mask(String apiKey) {
    return apiKey.length() <= 10
        ? "***"
        : apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 4);
  }

  /**
   * 依次从环境变量、仓库各层 .env 中寻找 AI_BAILIAN_API_KEY
   * （build.gradle 只在 bootRun 任务注入 .env，跑测试时不会自动加载）。
   */
  private static String resolveApiKey() {
    String fromEnv = System.getenv("AI_BAILIAN_API_KEY");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    Path dir = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 4 && dir != null; depth++) {
      String key = readKeyFromEnvFile(dir.resolve(".env"));
      if (key != null) {
        return key;
      }
      dir = dir.getParent();
    }
    return null;
  }

  private static String readKeyFromEnvFile(Path envFile) {
    if (!Files.isRegularFile(envFile)) {
      return null;
    }
    try {
      for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("AI_BAILIAN_API_KEY=")) {
          String value = trimmed.substring("AI_BAILIAN_API_KEY=".length()).trim();
          return value.isEmpty() ? null : value;
        }
      }
    } catch (Exception ignored) {
      return null;
    }
    return null;
  }

  private record Scenario(
      String name,
      boolean connected,
      List<String> events,
      int audioDeltas,
      int audioBytes,
      Integer closeCode,
      String closeReason,
      Throwable transportFailure,
      List<String> sendFailures,
      long durationMs) {

    boolean succeeded() {
      return audioDeltas > 0;
    }
  }
}
