package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UserDefaultRequest;
import interview.guide.modules.llmprovider.dto.UserProviderDTO;
import interview.guide.modules.llmprovider.dto.UserSaveProviderRequest;
import interview.guide.modules.llmprovider.model.UserLlmProviderEntity;
import interview.guide.modules.llmprovider.model.UserLlmSettingEntity;
import interview.guide.modules.llmprovider.model.UserVoiceConfigEntity;
import interview.guide.modules.llmprovider.repository.UserLlmProviderRepository;
import interview.guide.modules.llmprovider.repository.UserLlmSettingRepository;
import interview.guide.modules.llmprovider.repository.UserVoiceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户级模型配置中心：多卡片（文本 + 向量）管理与调用解析。
 *
 * <p>每个登录用户可保存多张 Provider 卡片并在 user_llm_setting 中选择默认文本 /
 * 向量模型；语音（ASR/TTS）按用户 1:1 配置。所有调用按当前用户的默认卡片解析，
 * 未配置时抛 {@link ErrorCode#PROVIDER_NOT_CONFIGURED} 给出明确提示，
 * 调用成本由用户自行承担。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserLlmConfigService {

  public static final String CHAT_NOT_CONFIGURED_MESSAGE =
      "您尚未设置默认文本模型，请前往「设置」页添加模型卡片并将其设为默认文本模型";
  public static final String EMBEDDING_NOT_CONFIGURED_MESSAGE =
      "您尚未设置默认向量模型，请前往「设置」页在卡片上填写向量模型并将其设为默认向量模型";

  private final UserLlmProviderRepository providerRepository;
  private final UserLlmSettingRepository settingRepository;
  private final UserVoiceConfigRepository voiceConfigRepository;
  private final ApiKeyEncryptionService encryptionService;
  private final LlmProviderRegistry registry;
  private final LlmProviderConfigService providerConfigService;
  private final CurrentUserService currentUserService;

  // ===== 卡片 CRUD =====

  @Transactional(readOnly = true)
  public java.util.List<UserProviderDTO> listMyProviders(Long userId) {
    UserLlmSettingEntity setting = settingRepository.findById(userId).orElse(null);
    return providerRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
        .map(card -> toDto(card, setting))
        .toList();
  }

  @Transactional
  public UserProviderDTO createMyProvider(Long userId, UserSaveProviderRequest request) {
    validateCard(request);
    UserLlmProviderEntity card = UserLlmProviderEntity.builder()
        .userId(userId)
        .name(request.name().trim())
        .baseUrl(request.baseUrl().trim())
        .model(request.model().trim())
        .temperature(request.temperature())
        .supportsEmbedding(Boolean.TRUE.equals(request.supportsEmbedding()))
        .embeddingModel(trimOrNull(request.embeddingModel()))
        .embeddingDimensions(request.embeddingDimensions())
        .build();
    applyApiKey(card, trimOrNull(request.apiKey()), null);

    boolean firstCard = providerRepository.findByUserIdOrderByCreatedAtAsc(userId).isEmpty();
    UserLlmProviderEntity saved = providerRepository.save(card);
    if (firstCard) {
      // 第一张卡片自动成为默认文本模型，保证开箱即用
      settingRepository.save(UserLlmSettingEntity.builder()
          .userId(userId)
          .defaultChatProviderId(saved.getId())
          .build());
    }
    log.info("Created user LLM provider card: userId={}, cardId={}, name={}",
        userId, saved.getId(), saved.getName());
    return toDto(saved, settingRepository.findById(userId).orElse(null));
  }

  @Transactional
  public void updateMyProvider(Long userId, Long cardId, UserSaveProviderRequest request) {
    validateCard(request);
    UserLlmProviderEntity card = requireOwnedCard(userId, cardId);
    card.setName(request.name().trim());
    card.setBaseUrl(request.baseUrl().trim());
    card.setModel(request.model().trim());
    if (request.temperature() != null) {
      card.setTemperature(request.temperature());
    }
    card.setSupportsEmbedding(Boolean.TRUE.equals(request.supportsEmbedding()));
    card.setEmbeddingModel(trimOrNull(request.embeddingModel()));
    card.setEmbeddingDimensions(request.embeddingDimensions());
    applyApiKey(card, trimOrNull(request.apiKey()), card.getApiKeyCiphertext());

    providerRepository.save(card);
    registry.evictCardClients(cardId);
    log.info("Updated user LLM provider card: userId={}, cardId={}", userId, cardId);
  }

  @Transactional
  public void deleteMyProvider(Long userId, Long cardId) {
    UserLlmProviderEntity card = requireOwnedCard(userId, cardId);
    providerRepository.delete(card);
    registry.evictCardClients(cardId);

    settingRepository.findById(userId).ifPresent(setting -> {
      if (cardId.equals(setting.getDefaultChatProviderId())) {
        setting.setDefaultChatProviderId(null);
      }
      if (cardId.equals(setting.getDefaultEmbeddingProviderId())) {
        setting.setDefaultEmbeddingProviderId(null);
      }
      settingRepository.save(setting);
    });
    log.info("Deleted user LLM provider card: userId={}, cardId={}", userId, cardId);
  }

  // ===== 默认模型选择 =====

  @Transactional
  public void setDefaultChatProvider(Long userId, UserDefaultRequest request) {
    if (request == null || request.providerId() == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "providerId 不能为空");
    }
    requireOwnedCard(userId, request.providerId());
    UserLlmSettingEntity setting = settingRepository.findById(userId)
        .orElseGet(() -> UserLlmSettingEntity.builder().userId(userId).build());
    setting.setDefaultChatProviderId(request.providerId());
    settingRepository.save(setting);
  }

  @Transactional
  public void setDefaultEmbeddingProvider(Long userId, UserDefaultRequest request) {
    if (request == null || request.providerId() == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "providerId 不能为空");
    }
    UserLlmProviderEntity card = requireOwnedCard(userId, request.providerId());
    if (!card.isSupportsEmbedding() || card.getEmbeddingModel() == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "该卡片未配置向量模型，请先在卡片中填写向量模型");
    }
    UserLlmSettingEntity setting = settingRepository.findById(userId)
        .orElseGet(() -> UserLlmSettingEntity.builder().userId(userId).build());
    setting.setDefaultEmbeddingProviderId(request.providerId());
    settingRepository.save(setting);
  }

  // ===== 连通性测试 =====

  @Transactional(readOnly = true)
  public ProviderTestResult testMyProvider(Long userId, Long cardId) {
    UserLlmProviderEntity card = requireOwnedCard(userId, cardId);
    return providerConfigService.testProviderRuntime(
        card.getBaseUrl(), decryptApiKey(card.getApiKeyNonce(), card.getApiKeyCiphertext()),
        card.getModel());
  }

  // ===== 语音配置（ASR / TTS，按用户 1:1） =====

  @Transactional(readOnly = true)
  public AsrConfigDTO getMyAsrConfig(Long userId) {
    return voiceConfigRepository.findById(userId)
        .map(entity -> AsrConfigDTO.builder()
            .url(entity.getAsrUrl())
            .model(entity.getAsrModel())
            .maskedApiKey(entity.getAsrApiKeyCiphertext() == null ? null
                : maskApiKey(decryptApiKey(entity.getAsrApiKeyNonce(), entity.getAsrApiKeyCiphertext())))
            .language(entity.getAsrLanguage())
            .format(entity.getAsrFormat())
            .sampleRate(entity.getAsrSampleRate())
            .enableTurnDetection(entity.getAsrEnableTurnDetection())
            .turnDetectionType(entity.getAsrTurnDetectionType())
            .turnDetectionThreshold(entity.getAsrTurnDetectionThreshold() == null ? null
                : entity.getAsrTurnDetectionThreshold().floatValue())
            .turnDetectionSilenceDurationMs(entity.getAsrTurnDetectionSilenceDurationMs())
            .build())
        .orElseGet(() -> AsrConfigDTO.builder().build());
  }

  @Transactional
  public void saveMyAsrConfig(Long userId, AsrConfigRequest request) {
    UserVoiceConfigEntity entity = voiceConfigRepository.findById(userId)
        .orElseGet(() -> UserVoiceConfigEntity.builder().userId(userId).build());
    if (trimOrNull(request.url()) != null) {
      entity.setAsrUrl(request.url().trim());
    }
    if (trimOrNull(request.model()) != null) {
      entity.setAsrModel(request.model().trim());
    }
    applySideKey(trimOrNull(request.apiKey()), entity::setAsrApiKeyNonce,
        entity::setAsrApiKeyCiphertext);
    if (request.language() != null) {
      entity.setAsrLanguage(request.language());
    }
    if (request.format() != null) {
      entity.setAsrFormat(request.format());
    }
    if (request.sampleRate() != null) {
      entity.setAsrSampleRate(request.sampleRate());
    }
    if (request.enableTurnDetection() != null) {
      entity.setAsrEnableTurnDetection(request.enableTurnDetection());
    }
    if (request.turnDetectionType() != null) {
      entity.setAsrTurnDetectionType(request.turnDetectionType());
    }
    if (request.turnDetectionThreshold() != null) {
      entity.setAsrTurnDetectionThreshold(request.turnDetectionThreshold().doubleValue());
    }
    if (request.turnDetectionSilenceDurationMs() != null) {
      entity.setAsrTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
    }
    voiceConfigRepository.save(entity);
  }

  @Transactional(readOnly = true)
  public TtsConfigDTO getMyTtsConfig(Long userId) {
    return voiceConfigRepository.findById(userId)
        .map(entity -> TtsConfigDTO.builder()
            .model(entity.getTtsModel())
            .maskedApiKey(entity.getTtsApiKeyCiphertext() == null ? null
                : maskApiKey(decryptApiKey(entity.getTtsApiKeyNonce(), entity.getTtsApiKeyCiphertext())))
            .voice(entity.getTtsVoice())
            .format(entity.getTtsFormat())
            .sampleRate(entity.getTtsSampleRate())
            .mode(entity.getTtsMode())
            .languageType(entity.getTtsLanguageType())
            .speechRate(entity.getTtsSpeechRate() == null ? null
                : entity.getTtsSpeechRate().floatValue())
            .volume(entity.getTtsVolume())
            .build())
        .orElseGet(() -> TtsConfigDTO.builder().build());
  }

  @Transactional
  public void saveMyTtsConfig(Long userId, TtsConfigRequest request) {
    UserVoiceConfigEntity entity = voiceConfigRepository.findById(userId)
        .orElseGet(() -> UserVoiceConfigEntity.builder().userId(userId).build());
    if (trimOrNull(request.model()) != null) {
      entity.setTtsModel(request.model().trim());
    }
    applySideKey(trimOrNull(request.apiKey()), entity::setTtsApiKeyNonce,
        entity::setTtsApiKeyCiphertext);
    if (request.voice() != null) {
      entity.setTtsVoice(request.voice());
    }
    if (request.format() != null) {
      entity.setTtsFormat(request.format());
    }
    if (request.sampleRate() != null) {
      entity.setTtsSampleRate(request.sampleRate());
    }
    if (request.mode() != null) {
      entity.setTtsMode(request.mode());
    }
    if (request.languageType() != null) {
      entity.setTtsLanguageType(request.languageType());
    }
    if (request.speechRate() != null) {
      entity.setTtsSpeechRate(request.speechRate().doubleValue());
    }
    if (request.volume() != null) {
      entity.setTtsVolume(request.volume());
    }
    voiceConfigRepository.save(entity);
  }

  @Transactional(readOnly = true)
  public ProviderTestResult testMyAsrConfig(Long userId) {
    UserVoiceConfigEntity entity = voiceConfigRepository.findById(userId).orElse(null);
    if (entity == null || entity.getAsrUrl() == null || entity.getAsrModel() == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先保存 ASR 地址与模型后再测试");
    }
    return providerConfigService.testAsrRuntime(entity.getAsrUrl(), entity.getAsrModel());
  }

  // ===== 调用解析（供业务 Service 使用） =====

  /**
   * 按用户的默认文本模型卡片解析 ChatClient；未设置时抛 PROVIDER_NOT_CONFIGURED。
   * 适用于异步消费等无法从 SecurityContext 取到用户的场景。
   */
  public ChatClient requireChatClientForUser(Long userId) {
    UserLlmProviderEntity card = requireDefaultCard(userId, true);
    return registry.getCardChatClient(card.getId(), card.getBaseUrl(),
        decryptApiKey(card.getApiKeyNonce(), card.getApiKeyCiphertext()),
        card.getModel(), card.getTemperature());
  }

  /**
   * 解析当前登录用户的 ChatClient。
   */
  public ChatClient requireCurrentUserChatClient() {
    return requireChatClientForUser(currentUserService.getRequiredUserId());
  }

  /**
   * 解析当前登录用户的不带工具 ChatClient，用于出题、简历分析等结构化输出场景。
   */
  public ChatClient requirePlainChatClientForCurrentUser() {
    UserLlmProviderEntity card = requireDefaultCard(
        currentUserService.getRequiredUserId(), true);
    return registry.getCardPlainChatClient(card.getId(), card.getBaseUrl(),
        decryptApiKey(card.getApiKeyNonce(), card.getApiKeyCiphertext()),
        card.getModel(), card.getTemperature());
  }

  /**
   * 解析用户的语音面试 ChatClient（SkillsTool + 流式工具调用）。
   */
  public ChatClient requireVoiceChatClientForUser(Long userId) {
    UserLlmProviderEntity card = requireDefaultCard(userId, true);
    return registry.getCardVoiceChatClient(card.getId(), card.getBaseUrl(),
        decryptApiKey(card.getApiKeyNonce(), card.getApiKeyCiphertext()),
        card.getModel(), card.getTemperature());
  }

  /**
   * 解析当前登录用户的默认向量模型；未设置时抛 PROVIDER_NOT_CONFIGURED。
   * 供 EmbeddingModel 委托 Bean 在请求上下文中调用。
   */
  public EmbeddingModel requireCurrentUserEmbeddingModel() {
    return requireEmbeddingModelForUser(currentUserService.getRequiredUserId());
  }

  /**
   * 按用户的默认向量模型卡片解析 EmbeddingModel。
   */
  public EmbeddingModel requireEmbeddingModelForUser(Long userId) {
    UserLlmProviderEntity card = requireDefaultCard(userId, false);
    if (!card.isSupportsEmbedding() || card.getEmbeddingModel() == null) {
      throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED,
          EMBEDDING_NOT_CONFIGURED_MESSAGE);
    }
    return registry.getCardEmbeddingModel(card.getId(), card.getBaseUrl(),
        decryptApiKey(card.getApiKeyNonce(), card.getApiKeyCiphertext()),
        card.getEmbeddingModel(), card.getEmbeddingDimensions());
  }

  /**
   * Embedding 委托入口：请求上下文中按当前用户解析；无用户上下文（系统任务）
   * 时回退到全局默认 Embedding Provider。
   */
  public EmbeddingModel resolveEmbeddingModel() {
    Long userId = currentUserService.getOptionalUserId();
    if (userId == null) {
      return registry.getDefaultEmbeddingModel();
    }
    return requireEmbeddingModelForUser(userId);
  }

  /**
   * 是否已设置默认文本模型卡片（用于异步任务前置校验，避免任务反复失败）。
   */
  @Transactional(readOnly = true)
  public boolean hasChatConfig(Long userId) {
    UserLlmSettingEntity setting = settingRepository.findById(userId).orElse(null);
    if (setting == null || setting.getDefaultChatProviderId() == null) {
      return false;
    }
    return providerRepository.findById(setting.getDefaultChatProviderId())
        .filter(card -> card.getUserId().equals(userId))
        .isPresent();
  }

  /**
   * 是否已设置默认向量模型卡片。
   */
  @Transactional(readOnly = true)
  public boolean hasEmbeddingConfig(Long userId) {
    UserLlmSettingEntity setting = settingRepository.findById(userId).orElse(null);
    if (setting == null || setting.getDefaultEmbeddingProviderId() == null) {
      return false;
    }
    return providerRepository.findById(setting.getDefaultEmbeddingProviderId())
        .filter(card -> card.getUserId().equals(userId))
        .filter(UserLlmProviderEntity::isSupportsEmbedding)
        .filter(card -> card.getEmbeddingModel() != null)
        .isPresent();
  }

  /**
   * 解析用户的语音运行时配置；未配置语音时返回 null，语音服务走系统默认。
   */
  @Transactional(readOnly = true)
  public VoiceRuntime resolveVoiceRuntimeOrNull(Long userId) {
    UserVoiceConfigEntity entity = voiceConfigRepository.findById(userId).orElse(null);
    if (entity == null) {
      return null;
    }
    VoiceRuntime.AsrRuntime asr = null;
    if (entity.getAsrApiKeyCiphertext() != null && entity.getAsrModel() != null) {
      asr = new VoiceRuntime.AsrRuntime(
          entity.getAsrUrl(),
          entity.getAsrModel(),
          decryptApiKey(entity.getAsrApiKeyNonce(), entity.getAsrApiKeyCiphertext()),
          entity.getAsrLanguage(),
          entity.getAsrFormat(),
          entity.getAsrSampleRate(),
          entity.getAsrEnableTurnDetection(),
          entity.getAsrTurnDetectionType(),
          entity.getAsrTurnDetectionThreshold(),
          entity.getAsrTurnDetectionSilenceDurationMs());
    }
    VoiceRuntime.TtsRuntime tts = null;
    if (entity.getTtsApiKeyCiphertext() != null && entity.getTtsModel() != null) {
      tts = new VoiceRuntime.TtsRuntime(
          decryptApiKey(entity.getTtsApiKeyNonce(), entity.getTtsApiKeyCiphertext()),
          entity.getTtsModel(),
          entity.getTtsVoice(),
          entity.getTtsLanguageType());
    }
    if (asr == null && tts == null) {
      return null;
    }
    return new VoiceRuntime(asr, tts);
  }

  // ===== 内部辅助 =====

  private UserLlmProviderEntity requireOwnedCard(Long userId, Long cardId) {
    UserLlmProviderEntity card = providerRepository.findById(cardId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模型卡片不存在"));
    if (!card.getUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该模型卡片");
    }
    return card;
  }

  private UserLlmProviderEntity requireDefaultCard(Long userId, boolean chat) {
    UserLlmSettingEntity setting = settingRepository.findById(userId).orElse(null);
    Long cardId = setting == null ? null
        : (chat ? setting.getDefaultChatProviderId() : setting.getDefaultEmbeddingProviderId());
    String message = chat ? CHAT_NOT_CONFIGURED_MESSAGE : EMBEDDING_NOT_CONFIGURED_MESSAGE;
    if (cardId == null) {
      throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED, message);
    }
    return providerRepository.findById(cardId)
        .filter(c -> c.getUserId().equals(userId))
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED, message));
  }

  private void validateCard(UserSaveProviderRequest request) {
    if (request.temperature() != null && (request.temperature() < 0 || request.temperature() > 2)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "temperature 取值范围为 0 ~ 2");
    }
    if (Boolean.TRUE.equals(request.supportsEmbedding())
        && trimOrNull(request.embeddingModel()) == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "启用向量模型时必须填写向量模型名称");
    }
  }

  private void applyApiKey(UserLlmProviderEntity card, String apiKey, String existingCiphertext) {
    if (apiKey != null) {
      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
      card.setApiKeyNonce(encrypted.nonce());
      card.setApiKeyCiphertext(encrypted.ciphertext());
      return;
    }
    if (existingCiphertext == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "创建卡片时必须填写 apiKey");
    }
  }

  private void applySideKey(String apiKey,
                            java.util.function.Consumer<String> nonceSetter,
                            java.util.function.Consumer<String> cipherSetter) {
    if (apiKey == null) {
      return; // 更新时留空保持原值
    }
    ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
    nonceSetter.accept(encrypted.nonce());
    cipherSetter.accept(encrypted.ciphertext());
  }

  private String decryptApiKey(String nonce, String ciphertext) {
    return encryptionService.decrypt(nonce, ciphertext);
  }

  private String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 6) {
      return "***";
    }
    return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private UserProviderDTO toDto(UserLlmProviderEntity card, UserLlmSettingEntity setting) {
    boolean defaultChat = setting != null
        && card.getId().equals(setting.getDefaultChatProviderId());
    boolean defaultEmbedding = setting != null
        && card.getId().equals(setting.getDefaultEmbeddingProviderId());
    return UserProviderDTO.builder()
        .id(card.getId())
        .name(card.getName())
        .baseUrl(card.getBaseUrl())
        .maskedApiKey(maskApiKey(decryptApiKey(card.getApiKeyNonce(), card.getApiKeyCiphertext())))
        .model(card.getModel())
        .embeddingModel(card.getEmbeddingModel())
        .embeddingDimensions(card.getEmbeddingDimensions())
        .supportsEmbedding(card.isSupportsEmbedding())
        .temperature(card.getTemperature())
        .defaultChatProvider(defaultChat)
        .defaultEmbeddingProvider(defaultEmbedding)
        .build();
  }
}
