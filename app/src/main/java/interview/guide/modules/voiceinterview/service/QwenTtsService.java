package interview.guide.modules.voiceinterview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import interview.guide.modules.llmprovider.service.VoiceRuntime;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DashScope Qwen text-to-speech service.
 *
 * <p>Uses the qwen3-tts-flash HTTP endpoint directly. The DashScope SDK's
 * {@code HttpSpeechSynthesizer} targets the legacy qwen-tts SSE endpoint
 * (TaskGroup.AUDIO / Task.TEXT_TO_SPEECH / Function.SPEECH_SYNTHESIZER), which
 * returns {@code 400 InvalidParameter: url error} for qwen3-tts-flash. The
 * official endpoint is {@code /api/v1/services/aigc/multimodal-generation/generation}
 * and returns an audio URL pointing at a 24kHz / mono / 16-bit WAV file.</p>
 */
@Slf4j
@Service
public class QwenTtsService {

  public static final int OUTPUT_SAMPLE_RATE = 24000;

  private static final String DEFAULT_HTTP_MODEL = "qwen3-tts-flash";
  private static final String REALTIME_MODEL_SUFFIX = "-realtime";
  private static final String DEFAULT_VOICE = "Cherry";
  // qwen-audio-* 系列（3.1-tts-flash / 3.1-tts-plus 等）走独立的 SpeechSynthesizer 端点，
  // 且音色是 longanhuan_v3.1 这类 v3.x 音色，不能沿用 qwen3 的 Cherry。
  private static final String AUDIO_MODEL_PREFIX = "qwen-audio-";
  private static final String DEFAULT_AUDIO_ENDPOINT =
      "https://llm-rmolmfgsop3yk6f6.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";
  private static final String DEFAULT_AUDIO_VOICE = "longanhuan_v3.1";
  private static final String TTS_HTTP_ENDPOINT =
      "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
  private static final String AUDIO_HOST_SUFFIX = ".aliyuncs.com";
  private static final int MAX_RAW_ERROR_CHARS = 180;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private String model;
  private String apiKey;
  private String voice;
  private String format;
  private Integer sampleRate;
  private String mode;
  private String languageType;
  private Float speechRate;
  private Integer volume;
  private String audioEndpoint = DEFAULT_AUDIO_ENDPOINT;
  private int timeoutSeconds = 8;
  private final ExecutorService synthesisExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  private volatile String lastError;

  public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
    this.timeoutSeconds = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
    applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
  }

  public void reload(VoiceInterviewProperties voiceInterviewProperties) {
    this.timeoutSeconds = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
    applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
    log.info("QwenTtsService reloaded: model={}, voice={}", model, voice);
  }

  private void applyTtsConfig(VoiceInterviewProperties.QwenTtsConfig tts) {
    String configuredModel = tts.getModel();
    this.model = normalizeModel(configuredModel);
    if (configuredModel != null && !configuredModel.equals(this.model)) {
      log.warn("TTS 模型 {} 已切换为 HTTP 模型 {}", configuredModel, this.model);
    }
    this.apiKey = tts.getApiKey();
    this.voice = tts.getVoice();
    // 官方端点固定返回 24kHz/mono/16bit WAV，由本类剥离头部后输出 PCM。
    // format / sampleRate / volume / rate 不作为请求参数发送，保留字段仅为兼容设置页与旧配置。
    this.format = "pcm";
    if (tts.getFormat() != null && !"pcm".equalsIgnoreCase(tts.getFormat())) {
      log.warn("HTTP TTS 当前统一返回 24kHz WAV 并转为 PCM，配置格式 {} 已被忽略", tts.getFormat());
    }
    if (tts.getSampleRate() > 0 && tts.getSampleRate() != OUTPUT_SAMPLE_RATE) {
      log.warn("HTTP TTS 当前固定使用 {}Hz，配置的 {}Hz 已被忽略",
          OUTPUT_SAMPLE_RATE, tts.getSampleRate());
    }
    this.sampleRate = OUTPUT_SAMPLE_RATE;
    this.mode = tts.getMode();
    this.languageType = tts.getLanguageType();
    this.speechRate = tts.getSpeechRate();
    this.volume = tts.getVolume();
    this.audioEndpoint = tts.getAudioEndpoint() == null || tts.getAudioEndpoint().isBlank()
        ? DEFAULT_AUDIO_ENDPOINT : tts.getAudioEndpoint().trim();
  }

  /** 是否为 qwen-audio-* 系列模型（走独立 SpeechSynthesizer 端点与新音色） */
  private static boolean isAudioModel(String model) {
    return model != null && model.toLowerCase(Locale.ROOT).startsWith(AUDIO_MODEL_PREFIX);
  }

  private static String normalizeModel(String configuredModel) {
    if (configuredModel == null || configuredModel.isBlank()) {
      return DEFAULT_HTTP_MODEL;
    }
    String normalized = configuredModel.trim();
    if (normalized.endsWith(REALTIME_MODEL_SUFFIX)) {
      return normalized.substring(0, normalized.length() - REALTIME_MODEL_SUFFIX.length());
    }
    return normalized;
  }

  @PostConstruct
  public void init() {
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new IllegalStateException("API key must be configured before initializing QwenTtsService");
    }
    log.info("QwenTtsService initialized with HTTP model: {}, voice: {}, sampleRate: {}Hz, endpoint: {}",
        model, voice, sampleRate, TTS_HTTP_ENDPOINT);
  }

  /**
   * Synthesize text to raw 24kHz, mono, 16-bit PCM.
   *
   * @return non-empty PCM bytes on success, otherwise an empty array. The
   * user-facing failure reason is available from {@link #getLastError()}.
   */
  public byte[] synthesize(String text) {
    return synthesize(text, null);
  }

  /**
   * 按用户级运行时配置合成语音；rt 为 null 或字段为 null 时回退到系统默认配置。
   */
  public byte[] synthesize(String text, VoiceRuntime.TtsRuntime rt) {
    // 不在入口清空 lastError：并发合成时线程 A 的清空会抹掉线程 B 刚写入的错误。
    // getLastError 仅在本次返回空音频（必然对应一次失败）时被读取，旧值不会误报。
    if (text == null || text.trim().isEmpty()) {
      log.debug("Empty or null text provided, returning empty audio array");
      return new byte[0];
    }

    try {
      ByteBuffer audioBuffer = callWithTimeout(text, rt);
      if (audioBuffer == null) {
        return new byte[0];
      }
      byte[] audioData = new byte[audioBuffer.remaining()];
      audioBuffer.get(audioData);
      if (audioData.length == 0) {
        lastError = "语音合成服务未返回音频，请检查 TTS 模型配置和账户额度";
        log.error("TTS synthesis returned empty audio");
        return new byte[0];
      }

      byte[] pcm = stripWavHeaderIfPresent(audioData);
      log.info("TTS synthesis completed: {} bytes PCM", pcm.length);
      return pcm;
    } catch (Exception e) {
      lastError = toUserFacingError(e);
      log.error("TTS synthesis failed: {} | raw={}", lastError, describeException(e), e);
      return new byte[0];
    }
  }

  private ByteBuffer callWithTimeout(String text, VoiceRuntime.TtsRuntime rt) {
    // 合成与音频下载是两次串行 HTTP，必须共享同一个总 deadline。
    // 否则两次各自 timeoutSeconds，外层 Future 会在下载完成前提前判超时并丢弃成功结果。
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    Future<ByteBuffer> future = synthesisExecutor.submit(
        () -> ByteBuffer.wrap(synthesizeViaHttp(text, deadlineNanos, rt)));
    try {
      // 留 2 秒给 JSON 解析与线程调度；真正的网络预算由 deadlineNanos 控制。
      return future.get(timeoutSeconds + 2L, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      lastError = "语音合成服务响应超时，请稍后重试";
      log.warn("TTS synthesis timed out after {} seconds", timeoutSeconds);
      return null;
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      lastError = "语音合成被中断，请稍后重试";
      log.warn("TTS synthesis interrupted", e);
      return null;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      lastError = toUserFacingError(cause);
      log.error("TTS synthesis failed: {} | raw={}", lastError, describeException(cause), cause);
      return null;
    }
  }

  /**
   * Call the qwen3-tts-flash generation endpoint, then download the returned WAV.
   * Kept independent from the DashScope SDK so the request/response contract is
   * explicit and server errors can be surfaced verbatim.
   */
  private byte[] synthesizeViaHttp(String text, long deadlineNanos,
                                   VoiceRuntime.TtsRuntime rt) throws Exception {
    String effectiveModel = firstNonNull(rt == null ? null : rt.model(), model);
    String effectiveApiKey = firstNonNull(rt == null ? null : rt.apiKey(), apiKey);
    String effectiveVoice = firstNonNull(rt == null ? null : rt.voice(), voice);
    String effectiveLanguageType = firstNonNull(rt == null ? null : rt.languageType(), languageType);

    boolean audioModel = isAudioModel(effectiveModel);
    String endpoint;
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("model", effectiveModel);

    if (audioModel) {
      // qwen-audio-* 系列：SpeechSynthesizer 端点，音色/格式/采样率写在 input 内。
      // 该系列不认 qwen3 的 Cherry 音色，默认换成 v3.x 系统音色。
      endpoint = audioEndpoint;
      ObjectNode input = MAPPER.createObjectNode();
      input.put("text", text);
      input.put("voice", effectiveVoice == null || effectiveVoice.isBlank()
          || DEFAULT_VOICE.equals(effectiveVoice) ? DEFAULT_AUDIO_VOICE : effectiveVoice);
      input.put("format", "wav");
      input.put("sample_rate", OUTPUT_SAMPLE_RATE);
      payload.set("input", input);
      log.debug("TTS via qwen-audio endpoint: model={}, voice={}", effectiveModel, input.get("voice").asText());
    } else {
      // qwen3-tts-flash 等旧模型：保留原 multimodal-generation 端点与参数结构。
      endpoint = TTS_HTTP_ENDPOINT;
      ObjectNode input = MAPPER.createObjectNode();
      input.put("text", text);
      input.put("voice", effectiveVoice == null || effectiveVoice.isBlank() ? DEFAULT_VOICE : effectiveVoice);
      ObjectNode parameters = MAPPER.createObjectNode();
      if (effectiveLanguageType != null && !effectiveLanguageType.isBlank()) {
        parameters.put("language_type", effectiveLanguageType);
      }
      payload.set("input", input);
      payload.set("parameters", parameters);
    }

    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(remainingTimeout(deadlineNanos))
        .header("Authorization", "Bearer " + effectiveApiKey)
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(
            MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> response = httpClient.send(
        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    String responseBody = response.body() == null ? "" : response.body();
    if (response.statusCode() / 100 != 2) {
      throw new TtsApiException(response.statusCode(), summarizeApiError(responseBody));
    }

    JsonNode audio = MAPPER.readTree(responseBody).path("output").path("audio");
    String audioUrl = audio.path("url").asText(null);
    byte[] audioData;
    if (audioUrl != null && !audioUrl.isBlank()) {
      audioData = downloadAudio(audioUrl, deadlineNanos);
    } else {
      String inlineAudio = audio.path("data").asText(null);
      if (inlineAudio == null || inlineAudio.isBlank()) {
        throw new IllegalStateException(
            "TTS 响应缺少 output.audio.url / output.audio.data: " + abbreviate(responseBody));
      }
      audioData = Base64.getDecoder().decode(inlineAudio);
    }
    if (audioData.length == 0) {
      throw new IllegalStateException("TTS 音频下载结果为空");
    }
    warnIfUnexpectedWavFormat(audioData);
    // 统一由 synthesize() 剥离一次 WAV 头，避免对已是 PCM 的数据重复判断。
    return audioData;
  }

  private static Duration remainingTimeout(long deadlineNanos) {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new IllegalStateException("TTS synthesis timeout: total deadline exceeded");
    }
    return Duration.ofNanos(remaining);
  }

  private byte[] downloadAudio(String rawUrl, long deadlineNanos) throws Exception {
    URI uri;
    try {
      uri = URI.create(rawUrl);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("TTS 返回的音频地址非法: " + abbreviate(rawUrl));
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
        || host == null
        || !(host.equalsIgnoreCase("aliyuncs.com")
            || host.toLowerCase(Locale.ROOT).endsWith(AUDIO_HOST_SUFFIX))) {
      // 音频地址来自厂商响应，仍做一次白名单校验，避免被响应内容引导访问内网。
      throw new IllegalStateException("TTS 音频地址不在允许范围: " + host);
    }
    HttpRequest download = HttpRequest.newBuilder(uri)
        .timeout(remainingTimeout(deadlineNanos))
        .GET()
        .build();
    HttpResponse<byte[]> response = httpClient.send(
        download, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("TTS 音频下载失败，HTTP " + response.statusCode());
    }
    return response.body();
  }

  private static String summarizeApiError(String responseBody) {
    try {
      JsonNode root = MAPPER.readTree(responseBody);
      StringBuilder sb = new StringBuilder("DashScope TTS error");
      if (root.hasNonNull("code")) {
        sb.append(" code=").append(root.get("code").asText());
      }
      if (root.hasNonNull("message")) {
        sb.append(" message=").append(root.get("message").asText());
      }
      if (root.hasNonNull("request_id")) {
        sb.append(" requestId=").append(root.get("request_id").asText());
      }
      if (sb.toString().equals("DashScope TTS error")) {
        return "DashScope TTS error: " + abbreviate(responseBody);
      }
      return sb.toString();
    } catch (Exception parseFailure) {
      return "DashScope TTS error: " + abbreviate(responseBody);
    }
  }

  private static void warnIfUnexpectedWavFormat(byte[] audioData) {
    if (audioData.length < 44 || !"RIFF".equals(ascii(audioData, 0, 4))) {
      return;
    }
    int channels = readShortLittleEndian(audioData, 22);
    int rate = readIntLittleEndian(audioData, 24);
    int bits = readShortLittleEndian(audioData, 34);
    if (rate != OUTPUT_SAMPLE_RATE || channels != 1 || bits != 16) {
      log.warn("TTS 返回音频规格与预期不符（期望 24kHz/mono/16bit，实际 {}Hz/{}ch/{}bit），播放可能变调",
          rate, channels, bits);
    }
  }

  /** 保留原始错误类型与 message，避免只剩一句兜底文案导致无法定位。 */
  private static String describeException(Throwable exception) {
    StringBuilder sb = new StringBuilder();
    Throwable current = exception;
    for (int depth = 0; current != null && depth < 4; depth++) {
      if (depth > 0) {
        sb.append(" <- ");
      }
      sb.append(current.getClass().getSimpleName()).append(": ")
          .append(abbreviate(String.valueOf(current.getMessage())));
      current = current.getCause() == current ? null : current.getCause();
    }
    return sb.toString();
  }

  private static String abbreviate(String value) {
    if (value == null) {
      return "";
    }
    String flat = value.replaceAll("\\s+", " ").trim();
    return flat.length() <= MAX_RAW_ERROR_CHARS
        ? flat
        : flat.substring(0, MAX_RAW_ERROR_CHARS) + "...";
  }

  static String toUserFacingError(Throwable exception) {
    String raw = exception.getMessage() == null ? exception.toString() : exception.getMessage();
    String lower = raw.toLowerCase(Locale.ROOT);
    int statusCode = exception instanceof TtsApiException apiException
        ? apiException.statusCode : -1;
    if (lower.contains("allocationquota.freetieronly") || lower.contains("free quota exhausted")
        || lower.contains("allocationquota") || lower.contains("arrearage")) {
      return "语音合成免费额度已用完或账户欠费，请在阿里云百炼控制台关闭“仅使用免费额度”或开通付费额度";
    }
    if (statusCode == 401 || statusCode == 403 || lower.contains("unauthorized")
        || lower.contains("access denied") || lower.contains("invalidapikey")) {
      return "语音合成鉴权失败，请检查 API Key、模型权限和账户状态";
    }
    if (statusCode == 404 || lower.contains("model not exist")) {
      return "语音合成模型不存在或未开通，请在百炼控制台确认当前 TTS 模型已开通";
    }
    if (lower.contains("url error") || lower.contains("invalidparameter")) {
      return "语音合成接口参数或端点不匹配：qwen3-tts-flash 用 multimodal-generation 端点，"
          + "qwen-audio-* 用 SpeechSynthesizer 端点且音色需为 v3.x（如 longanhuan_v3.1）";
    }
    if (statusCode == 429 || lower.contains("throttling") || lower.contains("rate limit")) {
      return "语音合成触发限流，请稍后重试";
    }
    if (lower.contains("timeout") || lower.contains("timed out")) {
      return "语音合成服务连接超时，请稍后重试";
    }
    return "语音合成失败，请检查 TTS 配置和账户额度（原始错误：" + abbreviate(raw) + "）";
  }

  /**
   * Convert a WAV response back to raw PCM before the WebSocket
   * handler adds its own WAV header.
   */
  private static byte[] stripWavHeaderIfPresent(byte[] audioData) {
    if (audioData.length < 12
        || !"RIFF".equals(ascii(audioData, 0, 4))
        || !"WAVE".equals(ascii(audioData, 8, 4))) {
      return audioData;
    }

    int offset = 12;
    while (offset + 8 <= audioData.length) {
      String chunkId = ascii(audioData, offset, 4);
      int chunkSize = readIntLittleEndian(audioData, offset + 4);
      if (chunkSize < 0) {
        return audioData;
      }
      long dataStart = (long) offset + 8;
      if ("data".equals(chunkId)) {
        long dataEnd = Math.min((long) audioData.length, dataStart + chunkSize);
        return Arrays.copyOfRange(audioData, (int) dataStart, (int) dataEnd);
      }
      long next = dataStart + chunkSize + (chunkSize & 1);
      if (next > audioData.length) {
        return audioData;
      }
      offset = (int) next;
    }
    return audioData;
  }

  private static String ascii(byte[] bytes, int offset, int length) {
    return new String(bytes, offset, length, StandardCharsets.US_ASCII);
  }

  private static int readShortLittleEndian(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
  }

  private static int readIntLittleEndian(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF)
        | ((bytes[offset + 1] & 0xFF) << 8)
        | ((bytes[offset + 2] & 0xFF) << 16)
        | ((bytes[offset + 3] & 0xFF) << 24);
  }

  public String getLastError() {
    return lastError;
  }

  @PreDestroy
  public void destroy() {
    synthesisExecutor.shutdownNow();
    log.info("QwenTtsService destroyed successfully");
  }

  /** 携带 HTTP 状态码，便于 toUserFacingError 精确分类而不依赖字符串猜测。 */
  private static final class TtsApiException extends RuntimeException {
    private final int statusCode;

    private TtsApiException(int statusCode, String message) {
      super("HTTP " + statusCode + " " + message);
      this.statusCode = statusCode;
    }
  }

  public void setModel(String model) {
    this.model = normalizeModel(model);
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public void setVoice(String voice) {
    this.voice = voice;
  }

  public void setFormat(String format) {
    this.format = format;
  }

  public void setSampleRate(Integer sampleRate) {
    this.sampleRate = sampleRate;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public void setLanguageType(String languageType) {
    this.languageType = languageType;
  }

  public void setSpeechRate(Float speechRate) {
    this.speechRate = speechRate;
  }

  public void setVolume(Integer volume) {
    this.volume = volume;
  }

  private static String firstNonNull(String override, String fallback) {
    return override != null && !override.isBlank() ? override : fallback;
  }
}
