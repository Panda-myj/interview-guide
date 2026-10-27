package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.http_tts.HttpSpeechSynthesisParam;
import com.alibaba.dashscope.audio.http_tts.HttpSpeechSynthesizer;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * <p>The previous realtime WebSocket SDK path silently dropped server close
 * reasons and waited up to 30 seconds before returning empty audio. This
 * implementation uses the synchronous HTTP TTS endpoint so account, quota and
 * configuration errors are surfaced immediately and can be handled by the UI.</p>
 */
@Slf4j
@Service
public class QwenTtsService {

  public static final int OUTPUT_SAMPLE_RATE = 24000;

  private static final String DEFAULT_HTTP_MODEL = "qwen3-tts-flash";
  private static final String REALTIME_MODEL_SUFFIX = "-realtime";

  private String model;
  private String apiKey;
  /**
   * Retained for settings/API compatibility. HTTP TTS does not use a WebSocket URL.
   */
  private String url;
  private String voice;
  private String format;
  private Integer sampleRate;
  private String mode;
  private String languageType;
  private Float speechRate;
  private Integer volume;
  private int timeoutSeconds = 8;
  private final ExecutorService synthesisExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
    this.url = tts.getUrl();
    this.voice = tts.getVoice();
    this.format = "pcm";
    if (tts.getFormat() != null && !"pcm".equalsIgnoreCase(tts.getFormat())) {
      log.warn("HTTP TTS 当前统一返回 24kHz PCM，配置格式 {} 已被忽略", tts.getFormat());
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
    log.info("QwenTtsService initialized with HTTP model: {}, voice: {}, sampleRate: {}Hz",
        model, voice, sampleRate);
  }

  /**
   * Synthesize text to raw 24kHz, mono, 16-bit PCM.
   *
   * @return non-empty PCM bytes on success, otherwise an empty array. The
   * user-facing failure reason is available from {@link #getLastError()}.
   */
  public byte[] synthesize(String text) {
    lastError = null;
    if (text == null || text.trim().isEmpty()) {
      log.debug("Empty or null text provided, returning empty audio array");
      return new byte[0];
    }

    try {
      HttpSpeechSynthesisParam param = HttpSpeechSynthesisParam.builder()
          .apiKey(apiKey)
          .model(model)
          .text(text)
          .voice(voice)
          .format(format)
          .sampleRate(sampleRate)
          .volume(volume == null ? 60 : volume)
          .rate(speechRate == null ? 1.0f : speechRate)
          .build();

      ByteBuffer audioBuffer = callWithTimeout(param);
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
      log.info("TTS synthesis completed: {} bytes", pcm.length);
      return pcm;
    } catch (Exception e) {
      lastError = toUserFacingError(e);
      log.error("TTS synthesis failed: {}", lastError, e);
      return new byte[0];
    }
  }

  private ByteBuffer callWithTimeout(HttpSpeechSynthesisParam param) {
    Future<ByteBuffer> future = synthesisExecutor.submit(
        () -> new HttpSpeechSynthesizer().callAndReturnAudio(param));
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
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
      log.error("TTS synthesis failed: {}", lastError, cause);
      return null;
    }
  }

  static String toUserFacingError(Throwable exception) {
    String raw = exception.getMessage() == null ? exception.toString() : exception.getMessage();
    String lower = raw.toLowerCase(Locale.ROOT);
    if (lower.contains("allocationquota.freetieronly") || lower.contains("free quota exhausted")) {
      return "语音合成免费额度已用完，请在阿里云百炼控制台关闭“仅使用免费额度”或开通付费额度";
    }
    if (lower.contains("401") || lower.contains("403")
        || lower.contains("unauthorized") || lower.contains("access denied")) {
      return "语音合成鉴权失败，请检查 API Key、模型权限和账户状态";
    }
    if (lower.contains("timeout") || lower.contains("timed out")) {
      return "语音合成服务连接超时，请稍后重试";
    }
    return "语音合成失败，请检查 TTS 配置和账户额度";
  }

  /**
   * Convert an accidental WAV response back to raw PCM before the WebSocket
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
      int dataStart = offset + 8;
      if ("data".equals(chunkId)) {
        int dataEnd = Math.min(audioData.length, dataStart + chunkSize);
        return Arrays.copyOfRange(audioData, dataStart, dataEnd);
      }
      offset = dataStart + chunkSize + (chunkSize & 1);
    }
    return audioData;
  }

  private static String ascii(byte[] bytes, int offset, int length) {
    return new String(bytes, offset, length, StandardCharsets.US_ASCII);
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

  public void setModel(String model) {
    this.model = normalizeModel(model);
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public void setVoice(String voice) {
    this.voice = voice;
  }

  public void setUrl(String url) {
    this.url = url;
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
}
