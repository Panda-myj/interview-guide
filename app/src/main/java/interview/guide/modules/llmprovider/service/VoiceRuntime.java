package interview.guide.modules.llmprovider.service;

/**
 * 用户级语音运行时配置：由用户的语音设置解析而来，
 * 任一字段为 null 时由语音服务回退到系统默认（yml / 全局配置）。
 *
 * <p>用户未配置语音时整体为 null，语音服务完全走系统默认。</p>
 */
public record VoiceRuntime(AsrRuntime asr, TtsRuntime tts) {

  /**
   * ASR 运行时覆盖值。
   */
  public record AsrRuntime(
      String url,
      String model,
      String apiKey,
      String language,
      String format,
      Integer sampleRate,
      Boolean enableTurnDetection,
      String turnDetectionType,
      Double turnDetectionThreshold,
      Integer turnDetectionSilenceDurationMs
  ) {
  }

  /**
   * TTS 运行时覆盖值（仅包含会真正进入 HTTP 请求的字段；
   * format/sampleRate 等仅为设置页兼容保留，不参与请求）。
   */
  public record TtsRuntime(
      String apiKey,
      String model,
      String voice,
      String languageType
  ) {
  }
}
