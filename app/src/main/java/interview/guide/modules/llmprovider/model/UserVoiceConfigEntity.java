package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户级语音服务配置（1:1）：ASR 与 TTS 各自独立配置与密钥，
 * 字段与原全局语音配置一致；任一侧未配置时运行时回退到系统默认（yml）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_voice_config")
public class UserVoiceConfigEntity {

  @Id
  @Column(name = "user_id")
  private Long userId;

  // ===== ASR =====
  @Column(name = "asr_url", length = 512)
  private String asrUrl;

  @Column(name = "asr_model", length = 128)
  private String asrModel;

  @Column(name = "asr_api_key_ciphertext", length = 4096)
  private String asrApiKeyCiphertext;

  @Column(name = "asr_api_key_nonce", length = 64)
  private String asrApiKeyNonce;

  @Column(name = "asr_language", length = 32)
  private String asrLanguage;

  @Column(name = "asr_format", length = 32)
  private String asrFormat;

  @Column(name = "asr_sample_rate")
  private Integer asrSampleRate;

  @Column(name = "asr_enable_turn_detection")
  private Boolean asrEnableTurnDetection;

  @Column(name = "asr_turn_detection_type", length = 64)
  private String asrTurnDetectionType;

  @Column(name = "asr_turn_detection_threshold")
  private Double asrTurnDetectionThreshold;

  @Column(name = "asr_turn_detection_silence_duration_ms")
  private Integer asrTurnDetectionSilenceDurationMs;

  // ===== TTS =====
  @Column(name = "tts_model", length = 128)
  private String ttsModel;

  @Column(name = "tts_api_key_ciphertext", length = 4096)
  private String ttsApiKeyCiphertext;

  @Column(name = "tts_api_key_nonce", length = 64)
  private String ttsApiKeyNonce;

  @Column(name = "tts_voice", length = 128)
  private String ttsVoice;

  @Column(name = "tts_format", length = 32)
  private String ttsFormat;

  @Column(name = "tts_sample_rate")
  private Integer ttsSampleRate;

  @Column(name = "tts_mode", length = 64)
  private String ttsMode;

  @Column(name = "tts_language_type", length = 64)
  private String ttsLanguageType;

  @Column(name = "tts_speech_rate")
  private Double ttsSpeechRate;

  @Column(name = "tts_volume")
  private Integer ttsVolume;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
