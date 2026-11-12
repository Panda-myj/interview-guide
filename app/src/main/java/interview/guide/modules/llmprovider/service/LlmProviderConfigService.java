package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.ApiPathResolver;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.ProviderEndpointValidator;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider 与语音服务配置的读写入口。
 *
 * <p><b>敏感信息落盘策略</b></p>
 * <ul>
 *   <li>Provider API Key 只以 AES-GCM 密文保存在数据库（{@code llm_provider.api_key_*}），是本服务的唯一主路径。</li>
 *   <li>默认不写 {@code .env}，也不生成任何含明文密钥的文件；本类没有写 ENV 的代码路径。</li>
 *   <li>ASR/TTS 的 API Key 只在当前进程内存中生效，重启后需通过 {@code AI_BAILIAN_API_KEY} 环境变量提供。</li>
 *   <li>仅在显式配置 {@code app.ai.config-yaml-path} 时，才会把非敏感字段写入受限目录下的本机文件：
 *       原子替换、POSIX 0600、目录 0700、跨进程 {@link FileChannel} 文件锁，且只写 {@code ${ENV}} 占位符。</li>
 * </ul>
 *
 * <p><b>多实例热加载边界</b></p>
 * <ul>
 *   <li>{@code registry.reload()} 只清空当前 JVM 的 ChatClient/ChatModel 缓存，不广播。</li>
 *   <li>其他实例会继续持有旧客户端（旧 baseUrl、旧密钥），直到重启或调用
 *       {@code POST /api/llm-providers/reload} 手动刷新；数据库始终是唯一真相。</li>
 *   <li>刷新只影响“下一次取用”，不做连通性校验；新配置不可用会在下次调用时抛
 *       {@code PROVIDER_CONFIG_READ_FAILED}，需回滚配置或修正密钥。</li>
 *   <li>刷新失败不会回滚已提交的数据库写入，只记录 WARN，避免热加载故障放大成写入故障。</li>
 * </ul>
 *
 * <p><b>加密密钥轮换</b></p>
 * <ul>
 *   <li>{@code APP_AI_CONFIG_ENCRYPTION_KEY} 变更后旧密文无法解密，且没有自动迁移。</li>
 *   <li>轮换顺序：准备新密钥 → 用新密钥启动一个实例并通过更新接口重新录入所有 Provider API Key
 *       （密文用新密钥重写）→ 全量实例切换到新密钥 → 回调 {@code /reload} → 校验连通性。</li>
 *   <li>同一时刻只允许一套加密密钥生效，不支持多密钥并行解密。</li>
 * </ul>
 */
@Service
@Slf4j
public class LlmProviderConfigService {

  private static final Pattern PLAINTEXT_API_KEY_LINE =
      Pattern.compile("^(\\s*api-key:\\s*)(\\S.*)$");
  private static final String DEFAULT_PROVIDER_ENV_KEY = "APP_AI_PROVIDER_API_KEY";
  private static final String BAILIAN_ENV_KEY = "AI_BAILIAN_API_KEY";
  private static final int PROVIDER_CONNECT_TIMEOUT_SECONDS = 5;
  private static final int PROVIDER_READ_TIMEOUT_SECONDS = 10;
  private static final int ASR_CONNECT_TIMEOUT_MS = 5000;

  private final LlmProviderProperties properties;
  private final LlmProviderRegistry registry;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;
  private final String yamlPath;
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private final AtomicBoolean posixPermissionWarningLogged = new AtomicBoolean();
  private final VoiceInterviewProperties voiceProperties;
  private final QwenAsrService asrService;
  private final QwenTtsService ttsService;

  private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
      "dashscope", "text-embedding-v3",
      "glm", "embedding-3",
      "zhipu", "embedding-3",
      "baidu", "Embedding-V1",
      "minimax", "embo-01"
  );

  @Autowired
  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      LlmProviderRepository providerRepository,
      LlmGlobalSettingRepository globalSettingRepository,
      ApiKeyEncryptionService encryptionService,
      VoiceInterviewProperties voiceProperties,
      QwenAsrService asrService,
      QwenTtsService ttsService) {
    this.properties = properties;
    this.registry = registry;
    this.providerRepository = providerRepository;
    this.globalSettingRepository = globalSettingRepository;
    this.encryptionService = encryptionService;
    this.yamlPath = properties.getConfigYamlPath();
    this.voiceProperties = voiceProperties;
    this.asrService = asrService;
    this.ttsService = ttsService;
  }

  public LlmProviderConfigService(
      LlmProviderProperties properties,
      LlmProviderRegistry registry,
      VoiceInterviewProperties voiceProperties,
      QwenAsrService asrService,
      QwenTtsService ttsService) {
    this(properties, registry, null, null, null, voiceProperties, asrService, ttsService);
  }

  @PostConstruct
  void validateWritablePaths() {
    if (isRuntimeConfigPathConfigured()) {
      Path path = requireRuntimeConfigPath();
      ensurePrivateDirectory(path.getParent());
      if (!Files.isWritable(path.getParent())) {
        throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
            "Provider 配置目录不可写: " + path.getParent());
      }
      sanitizeLegacyPlaintextSecrets(path);
      log.info("运行期配置文件解析为 {}（仅本机用户可读写，写入使用原子替换）", path);
      return;
    }
    log.info("未配置 app.ai.config-yaml-path：Provider/语音配置只写数据库与进程内存，不写任何本机文件");
  }

  private boolean isRuntimeConfigPathConfigured() {
    return yamlPath != null && !yamlPath.isBlank();
  }

  // ===== Read operations (read lock) =====
// LlmProviderConfigService.listProviders()               │
//         │  职责：加读锁 → 查 Repository → Entity 转 DTO → 脱敏    │
//            │  @Service  rwLock.readLock()  providerRepository.findAll()
  public List<ProviderDTO> listProviders() {
    rwLock.readLock().lock();//可重入读写锁
    try {
//        YAML 模式分支
      if (!isDatabaseBacked()) {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null) return List.of();
        return providers.entrySet().stream()
            .map(e -> ProviderDTO.builder()
                .id(e.getKey())
                .baseUrl(e.getValue().getBaseUrl())
                .maskedApiKey(maskApiKey(e.getValue().getApiKey()))//两步处理敏感数据：先从数据库密文解密出真实 API Key，再把 sk-abc123def456 变成 sk-***456 返回。这样即使前端被 XSS 攻击，拿到的也是脱敏后的 Key。
                .model(e.getValue().getModel())
                .embeddingModel(e.getValue().getEmbeddingModel())
                .embeddingDimensions(resolveEmbeddingDimensions(e.getValue().getEmbeddingDimensions()))
                .supportsEmbedding(Boolean.TRUE.equals(e.getValue().getSupportsEmbedding())
                    || trimOrNull(e.getValue().getEmbeddingModel()) != null)
                .temperature(e.getValue().getTemperature())
                .defaultChatProvider(e.getKey().equals(properties.getDefaultProvider()))
                .defaultEmbeddingProvider(e.getKey().equals(properties.getDefaultEmbeddingProvider()))
                .build())//构建出不可变的 ProviderDTO record
            .toList();
      }
//      数据库模式
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      return providerRepository.findAll().stream()
          .map(provider -> ProviderDTO.builder()
              .id(provider.getId())
              .baseUrl(provider.getBaseUrl())
              .maskedApiKey(maskApiKey(decryptApiKey(provider)))//两步处理敏感数据：先从数据库密文解密出真实 API Key，再把 sk-abc123def456 变成 sk-***456 返回。这样即使前端被 XSS 攻击，拿到的也是脱敏后的 Key。
              .model(provider.getModel())
              .embeddingModel(provider.getEmbeddingModel())
              .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
              .supportsEmbedding(provider.isSupportsEmbedding())
              .temperature(provider.getTemperature())
              .defaultChatProvider(provider.getId().equals(setting.getDefaultChatProviderId()))
              .defaultEmbeddingProvider(provider.getId().equals(setting.getDefaultEmbeddingProviderId()))
              .build())//构建出不可变的 ProviderDTO record
          .toList();
    } finally {
      rwLock.readLock().unlock();//        放在 finally 块里一定会执行，即使中间抛了异常也不会死锁。

    }
  }

  public ProviderDTO getProvider(String id) {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        ProviderConfig config = getLegacyProviderConfigOrThrow(id);
        return ProviderDTO.builder()
            .id(id)
            .baseUrl(config.getBaseUrl())
            .maskedApiKey(maskApiKey(config.getApiKey()))
            .model(config.getModel())
            .embeddingModel(config.getEmbeddingModel())
            .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
            .supportsEmbedding(Boolean.TRUE.equals(config.getSupportsEmbedding())
                || trimOrNull(config.getEmbeddingModel()) != null)
            .temperature(config.getTemperature())
            .defaultChatProvider(id.equals(properties.getDefaultProvider()))
            .defaultEmbeddingProvider(id.equals(properties.getDefaultEmbeddingProvider()))
            .build();
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      LlmProviderEntity provider = getProviderEntityOrThrow(id);
      return ProviderDTO.builder()
          .id(id)
          .baseUrl(provider.getBaseUrl())
          .maskedApiKey(maskApiKey(decryptApiKey(provider)))
          .model(provider.getModel())
          .embeddingModel(provider.getEmbeddingModel())
          .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
          .supportsEmbedding(provider.isSupportsEmbedding())
          .temperature(provider.getTemperature())
          .defaultChatProvider(id.equals(setting.getDefaultChatProviderId()))
          .defaultEmbeddingProvider(id.equals(setting.getDefaultEmbeddingProviderId()))
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public DefaultProviderDTO getDefaultProvider() {
    rwLock.readLock().lock();
    try {
      if (!isDatabaseBacked()) {
        return new DefaultProviderDTO(properties.getDefaultProvider(), properties.getDefaultEmbeddingProvider());
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      return new DefaultProviderDTO(
          setting.getDefaultChatProviderId(),
          setting.getDefaultEmbeddingProviderId());
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public AsrConfigDTO getAsrConfig() {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      return AsrConfigDTO.builder()
          .url(asr.getUrl())
          .model(asr.getModel())
          .maskedApiKey(maskApiKey(asr.getApiKey()))
          .language(asr.getLanguage())
          .format(asr.getFormat())
          .sampleRate(asr.getSampleRate())
          .enableTurnDetection(asr.isEnableTurnDetection())
          .turnDetectionType(asr.getTurnDetectionType())
          .turnDetectionThreshold(asr.getTurnDetectionThreshold())
          .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public TtsConfigDTO getTtsConfig() {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      return TtsConfigDTO.builder()
          .model(tts.getModel())
          .maskedApiKey(maskApiKey(tts.getApiKey()))
          .voice(tts.getVoice())
          .format(tts.getFormat())
          .sampleRate(tts.getSampleRate())
          .mode(tts.getMode())
          .languageType(tts.getLanguageType())
          .speechRate(tts.getSpeechRate())
          .volume(tts.getVolume())
          .build();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testProvider(String id) {
    rwLock.readLock().lock();
    try {
      ProviderRuntimeConfig config = isDatabaseBacked()
          ? getProviderRuntimeConfigOrThrow(id)
          : toRuntimeConfig(getLegacyProviderConfigOrThrow(id));
      return doTestProvider(config, id);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  public ProviderTestResult testAsrConfig() {
    rwLock.readLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      String url = asr.getUrl();
      try {
        URI wsUri = ProviderEndpointValidator.requireWebSocketEndpoint(url, allowLocalEndpoints());
        String host = wsUri.getHost();
        int port = wsUri.getPort() > 0
            ? wsUri.getPort()
            : ("wss".equalsIgnoreCase(wsUri.getScheme()) ? 443 : 80);
        InetAddress address = ProviderEndpointValidator.resolveAllowedAddress(host, allowLocalEndpoints());
        try (Socket socket = new Socket()) {
          // 直接连接已校验的 IP，避免二次 DNS 解析被重绑定绕过
          socket.connect(new InetSocketAddress(address, port), ASR_CONNECT_TIMEOUT_MS);
        }
        return ProviderTestResult.builder()
            .success(true)
            .message("ASR WebSocket 连接成功: " + host)
            .model(asr.getModel())
            .build();
      } catch (BusinessException e) {
        log.warn("ASR 地址未通过安全检查: url={}, reason={}", url, e.getMessage());
        return ProviderTestResult.builder()
            .success(false)
            .message("ASR 连接被拒绝: " + e.getMessage())
            .model(asr.getModel())
            .build();
      } catch (IOException e) {
        log.warn("ASR 连接失败: url={}, reason={}", url, e.getMessage());
        return ProviderTestResult.builder()
            .success(false)
            .message("ASR 连接失败: " + e.getMessage())
            .model(asr.getModel())
            .build();
      }
    } finally {
      rwLock.readLock().unlock();
    }
  }

  // ===== Write operations (write lock) =====

  @Transactional//事务注解只放 Service 层，Controller 和 Repository 不关心事务。
  public void createProvider(CreateProviderRequest request) {
      /*
        // 读操作（listProviders）
        rwLock.readLock().lock();    // 共享锁：10 个人同时读，互不阻塞
        // 写操作（createProvider）
        rwLock.writeLock().lock();   // 排他锁：写的时候，其他人既不能读也不能写*/
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        createProviderLegacy(request);
        return;
      }
        // ===== 分支 1：查重 =====
      String providerId = trimOrNull(request.id());//主键查询
      if (providerRepository.existsById(providerId)) {
        throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
            "Provider '" + request.id() + "' 已存在");
      }
        // ===== 分支 2：必填校验 =====
      String baseUrl = requireNonBlank(request.baseUrl(), "baseUrl");
      /*为什么 Controller 里已经 @NotBlank 了，Service 还要再校验一次？
        @Valid 只能保护 Controller 入参。如果将来有其他代码（定时任务、消息队列消费者、内部调用）绕过 Controller 直接调用 Service，这层防御就是最后一道防线。这叫防御性编程——每一层都不信任上一层的数据。
      */
      String model = requireNonBlank(request.model(), "model");
      String apiKey = requireNonBlank(request.apiKey(), "apiKey");
      String embeddingModel = trimOrNull(request.embeddingModel());
      Integer embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions());
        // ===== 分支 3：Embedding 配置校验 =====
      boolean supportsEmbedding = request.supportsEmbedding() != null
          ? request.supportsEmbedding()
          : embeddingModel != null;
      validateEmbeddingConfig(providerId, supportsEmbedding, embeddingModel, embeddingDimensions);
      // ===== 分支 4：加密 API Key =====
      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
        // ===== 分支 5：持久化 =====
      providerRepository.save(LlmProviderEntity.builder()
          .id(providerId)
          .baseUrl(baseUrl)
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(model)
          .embeddingModel(embeddingModel)
          .embeddingDimensions(embeddingDimensions)
          .supportsEmbedding(supportsEmbedding)
          .temperature(request.temperature())
          .enabled(true)
          .builtin(false)
          .build());
      // ===== 分支 6：刷新内存注册表 =====
      refreshRegistry("createProvider"); //(registry.reload() 不在事务内
//        为什么这行放在事务外面？ 因为 registry.reload() 可能涉及网络调用或者类加载，跟数据库事务无关。
//        项目规则明确规定："事务内不得调用 LLM、S3、外部 HTTP"。如果 reload() 失败抛异常，数据库的写入也不应该回滚——数据已经正确落库了，registry 刷新失败是另一个独立问题。
      log.info("Created provider: id={}, baseUrl={}, model={}", providerId, baseUrl, model);
    } finally {
      rwLock.writeLock().unlock();// 无论成功还是异常，都会执行
//      为什么必须 finally？ 如果 save() 抛异常，代码跳到 catch（或直接向上抛），如果没有 finally，写锁永远不会释放 → 死锁 → 整个应用的 Provider 相关接口全部挂掉。
    }
  }

  @Transactional
  public void updateProvider(String id, UpdateProviderRequest request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateProviderLegacy(id, request);
        return;
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(id);

      String trimmedBaseUrl = trimOrNull(request.baseUrl());
      if (request.baseUrl() != null && trimmedBaseUrl == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
      }
      String trimmedModel = trimOrNull(request.model());
      if (request.model() != null && trimmedModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
      }
      String trimmedApiKey = trimOrNull(request.apiKey());
      if (request.apiKey() != null && trimmedApiKey == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
      }

      if (trimmedBaseUrl != null) provider.setBaseUrl(trimmedBaseUrl);
      if (trimmedModel != null) provider.setModel(trimmedModel);
      if (request.embeddingModel() != null) {
        provider.setEmbeddingModel(trimOrNull(request.embeddingModel()));
      }
      if (request.embeddingDimensions() != null) {
        provider.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
      }
      if (request.supportsEmbedding() != null) {
        provider.setSupportsEmbedding(request.supportsEmbedding());
      }
      validateEmbeddingConfig(
          id,
          provider.isSupportsEmbedding(),
          provider.getEmbeddingModel(),
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      if (request.temperature() != null) {
        provider.setTemperature(request.temperature());
      }
      if (trimmedApiKey != null) {
        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(trimmedApiKey);
        provider.setApiKeyNonce(encrypted.nonce());
        provider.setApiKeyCiphertext(encrypted.ciphertext());
      }

      providerRepository.save(provider);
      refreshRegistry("updateProvider");
      log.info("Updated provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void deleteProvider(String id) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        deleteProviderLegacy(id);
        return;
      }
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      if (id.equals(setting.getDefaultChatProviderId()) || id.equals(setting.getDefaultEmbeddingProviderId())) {
        throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
            "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
      }
      getProviderEntityOrThrow(id);

      providerRepository.deleteById(id);
      refreshRegistry("deleteProvider");
      log.info("Deleted provider: id={}", id);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      if (!isDatabaseBacked()) {
        updateDefaultProviderLegacy(request);
        return;
      }
      String providerId = trimOrNull(request.defaultProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
      }
      getProviderEntityOrThrow(providerId);
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      setting.setDefaultChatProviderId(providerId);
      globalSettingRepository.save(setting);
      refreshRegistry("updateDefaultProvider");
      log.info("Updated default provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Transactional
  public void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
    rwLock.writeLock().lock();
    try {
      String providerId = trimOrNull(request.defaultEmbeddingProvider());
      if (providerId == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空");
      }
      LlmProviderEntity provider = getProviderEntityOrThrow(providerId);
      String embeddingModel = trimOrNull(provider.getEmbeddingModel());
      if (!provider.isSupportsEmbedding() || embeddingModel == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务");
      }
      validateEmbeddingConfig(
          providerId,
          true,
          embeddingModel,
          resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
      LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
      setting.setDefaultEmbeddingProviderId(providerId);
      globalSettingRepository.save(setting);
      refreshRegistry("updateDefaultEmbeddingProvider");
      log.info("Updated default embedding provider: {}", providerId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void updateAsrConfig(AsrConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      if (request.url() != null) asr.setUrl(request.url());
      if (request.model() != null) asr.setModel(request.model());
      if (request.language() != null) asr.setLanguage(request.language());
      if (request.format() != null) asr.setFormat(request.format());
      if (request.sampleRate() != null) asr.setSampleRate(request.sampleRate());
      if (request.enableTurnDetection() != null) asr.setEnableTurnDetection(request.enableTurnDetection());
      if (request.turnDetectionType() != null) asr.setTurnDetectionType(request.turnDetectionType());
      if (request.turnDetectionThreshold() != null) asr.setTurnDetectionThreshold(request.turnDetectionThreshold());
      if (request.turnDetectionSilenceDurationMs() != null) asr.setTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
      if (request.apiKey() != null) {
        asr.setApiKey(request.apiKey());
        tts.setApiKey(request.apiKey());
        warnApiKeyNotPersisted("ASR/TTS", request.apiKey());
      }

      writeAsrConfigToYaml(asr);
      asrService.reload(voiceProperties);
      if (request.apiKey() != null) {
        ttsService.reload(voiceProperties);
      }
      log.info("Updated ASR config");
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void updateTtsConfig(TtsConfigRequest request) {
    rwLock.writeLock().lock();
    try {
      VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
      VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
      if (request.model() != null) tts.setModel(request.model());
      if (request.voice() != null) tts.setVoice(request.voice());
      if (request.format() != null) tts.setFormat(request.format());
      if (request.sampleRate() != null) tts.setSampleRate(request.sampleRate());
      if (request.mode() != null) tts.setMode(request.mode());
      if (request.languageType() != null) tts.setLanguageType(request.languageType());
      if (request.speechRate() != null) tts.setSpeechRate(request.speechRate());
      if (request.volume() != null) tts.setVolume(request.volume());
      if (request.apiKey() != null) {
        tts.setApiKey(request.apiKey());
        asr.setApiKey(request.apiKey());
        warnApiKeyNotPersisted("TTS/ASR", request.apiKey());
      }

      writeTtsConfigToYaml(tts);
      ttsService.reload(voiceProperties);
      if (request.apiKey() != null) {
        asrService.reload(voiceProperties);
      }
      log.info("Updated TTS config");
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  public void reloadProviders() {
    registry.reload();
    log.info("Manual provider reload triggered");
  }

  // ===== Internal helpers =====

  private boolean isDatabaseBacked() {
    return providerRepository != null && globalSettingRepository != null && encryptionService != null;
  }

  private Map<String, ProviderConfig> getLegacyProvidersOrThrow() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "Provider 配置未初始化");
    }
    return providers;
  }

  ProviderConfig getLegacyProviderConfigOrThrow(String id) {
    ProviderConfig config = getLegacyProvidersOrThrow().get(id);
    if (config == null) {
      throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
          "Provider '" + id + "' 不存在");
    }
    return config;
  }

  private void createProviderLegacy(CreateProviderRequest request) {
    Map<String, ProviderConfig> providers = getLegacyProvidersOrThrow();
    if (providers.containsKey(request.id())) {
      throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
          "Provider '" + request.id() + "' 已存在");
    }

    ProviderConfig config = new ProviderConfig();
    config.setBaseUrl(request.baseUrl());
    config.setApiKey(request.apiKey());
    config.setModel(request.model());
    config.setEmbeddingModel(request.embeddingModel());
    config.setEmbeddingDimensions(request.embeddingDimensions());
    config.setSupportsEmbedding(request.supportsEmbedding());
    config.setTemperature(request.temperature());
    providers.put(request.id(), config);

    String envKey = toEnvKey(request.id());
    writeProviderToYaml(request.id(), config, envKey);
    warnApiKeyNotPersisted(envKey, request.apiKey());
    refreshRegistry("createProviderLegacy");
  }

  private void updateProviderLegacy(String id, UpdateProviderRequest request) {
    ProviderConfig config = getLegacyProviderConfigOrThrow(id);
    String trimmedBaseUrl = trimOrNull(request.baseUrl());
    if (request.baseUrl() != null && trimmedBaseUrl == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
    }
    String trimmedModel = trimOrNull(request.model());
    if (request.model() != null && trimmedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
    }
    String trimmedApiKey = trimOrNull(request.apiKey());
    if (request.apiKey() != null && trimmedApiKey == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
    }

    if (trimmedBaseUrl != null) config.setBaseUrl(trimmedBaseUrl);
    if (trimmedModel != null) config.setModel(trimmedModel);
    if (request.embeddingModel() != null) {
      config.setEmbeddingModel(trimOrNull(request.embeddingModel()));
    }
    if (request.embeddingDimensions() != null) {
      config.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
    }
    if (request.supportsEmbedding() != null) {
      config.setSupportsEmbedding(request.supportsEmbedding());
    }
    if (request.temperature() != null) {
      config.setTemperature(request.temperature());
    }
    if (trimmedApiKey != null) {
      config.setApiKey(trimmedApiKey);
      warnApiKeyNotPersisted(toEnvKey(id), trimmedApiKey);
    }

    writeProviderToYaml(id, config, toEnvKey(id));
    refreshRegistry("updateProviderLegacy");
  }

  private void deleteProviderLegacy(String id) {
    if (id.equals(properties.getDefaultProvider())) {
      throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
          "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
    }
    getLegacyProviderConfigOrThrow(id);
    getLegacyProvidersOrThrow().remove(id);
    String envKey = toEnvKey(id);
    removeProviderFromYaml(id);
    log.info("Provider '{}' 已从本机配置移除；如需清理本机密钥，请手动删除 .env 中的 {}", id, envKey);
    refreshRegistry("deleteProviderLegacy");
  }

  private void updateDefaultProviderLegacy(DefaultProviderDTO request) {
    String providerId = trimOrNull(request.defaultProvider());
    if (providerId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
    }
    getLegacyProviderConfigOrThrow(providerId);
    properties.setDefaultProvider(providerId);
    writeDefaultProviderToYaml(providerId);
    refreshRegistry("updateDefaultProviderLegacy");
  }

  private ProviderRuntimeConfig toRuntimeConfig(ProviderConfig config) {
    return new ProviderRuntimeConfig(
        config.getBaseUrl(),
        config.getApiKey(),
        config.getModel(),
        config.getEmbeddingModel(),
        resolveEmbeddingDimensions(config.getEmbeddingDimensions()),
        Boolean.TRUE.equals(config.getSupportsEmbedding()) || trimOrNull(config.getEmbeddingModel()) != null,
        config.getTemperature()
    );
  }

  LlmProviderEntity getProviderEntityOrThrow(String id) {
    return providerRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
            "Provider '" + id + "' 不存在"));
  }

  private LlmGlobalSettingEntity getGlobalSettingOrThrow() {
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
            "默认 Provider 配置未初始化"));
  }

  private ProviderRuntimeConfig getProviderRuntimeConfigOrThrow(String id) {
    LlmProviderEntity provider = getProviderEntityOrThrow(id);
    return new ProviderRuntimeConfig(
        provider.getBaseUrl(),
        decryptApiKey(provider),
        provider.getModel(),
        provider.getEmbeddingModel(),
        resolveEmbeddingDimensions(provider.getEmbeddingDimensions()),
        provider.isSupportsEmbedding(),
        provider.getTemperature()
    );
  }

  private String decryptApiKey(LlmProviderEntity provider) {
    return encryptionService.decrypt(provider.getApiKeyNonce(), provider.getApiKeyCiphertext());
  }

  String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 6) {
      return "***";
    }
    return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
  }

  private String abbreviate(String text) {
    if (text == null || text.isBlank()) {
      return "[no body]";
    }
    String normalized = text.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= 200) {
      return normalized;
    }
    return normalized.substring(0, 200) + "...";
  }

  private List<String> buildConnectivityTestUrls(String baseUrl) {
    String normalizedBaseUrl = ApiPathResolver.stripTrailingSlashes(baseUrl);
    LinkedHashSet<String> candidateUrls = new LinkedHashSet<>();

    candidateUrls.add(normalizedBaseUrl + "/chat/completions");
    if (!ApiPathResolver.baseUrlContainsVersion(normalizedBaseUrl)) {
      candidateUrls.add(normalizedBaseUrl + "/v1/chat/completions");
    }

    return List.copyOf(candidateUrls);
  }

  private Map<String, Object> buildConnectivityTestRequestBody(String model) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("model", model);
    requestBody.put("messages", List.of(Map.of(
        "role", "user",
        "content", "Reply with OK only."
    )));
    requestBody.put("max_tokens", 1);
    return requestBody;
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String requireNonBlank(String value, String fieldName) {
    String normalized = trimOrNull(value);
    if (normalized == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
    }
    return normalized;
  }

  private void validateEmbeddingConfig(
      String providerId,
      boolean supportsEmbedding,
      String embeddingModel,
      Integer embeddingDimensions) {
    String normalizedModel = trimOrNull(embeddingModel);
    if (!supportsEmbedding) {
      return;
    }
    if (normalizedModel == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "支持 Embedding 的 Provider 必须填写 embeddingModel");
    }
    if (looksLikeChatModel(normalizedModel)) {
      String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
      String suffix = recommendation != null
          ? "，推荐填写 " + recommendation
          : "，请填写该厂商真实的 Embedding 模型名";
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "Embedding Model 不能填写聊天模型 '" + normalizedModel + "'" + suffix);
    }
    if (embeddingDimensions == null || embeddingDimensions <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
    }
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private boolean looksLikeChatModel(String model) {
    String lower = model.toLowerCase();
    return lower.startsWith("glm-")
        || lower.startsWith("deepseek")
        || lower.startsWith("kimi")
        || lower.startsWith("moonshot")
        || lower.startsWith("qwen")
        || lower.startsWith("ernie");
  }

  private String toEnvKey(String providerId) {
    return "PROVIDER_" + providerId.toUpperCase(Locale.ROOT).replace("-", "_") + "_API_KEY";
  }

  // ===== Provider test logic (called under read lock) =====

  private ProviderTestResult doTestProvider(ProviderRuntimeConfig config, String id) {
    Optional<ProviderTestResult> rejected = rejectUnsafeEndpoint(config, id);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    try {
      boolean allowLocal = allowLocalEndpoints();
      InetAddressFilter addressFilter = InetAddressFilter.externalAddresses();
      if (allowLocal) {
        addressFilter = addressFilter
            .or(InetAddressFilter.adapt(InetAddress::isLoopbackAddress))
            .or("198.18.0.0/15");
      }
      HttpClientSettings settings = HttpClientSettings.defaults()
          .withConnectTimeout(Duration.ofSeconds(PROVIDER_CONNECT_TIMEOUT_SECONDS))
          .withReadTimeout(Duration.ofSeconds(PROVIDER_READ_TIMEOUT_SECONDS))
          .withRedirects(HttpRedirects.DONT_FOLLOW)
          .withInetAddressFilter(addressFilter);

      RestClient restClient = RestClient.builder()
          .defaultHeader("Authorization", "Bearer " + config.apiKey())
          .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
          .build();

      Map<String, Object> requestBody = buildConnectivityTestRequestBody(config.model());

      List<String> candidateUrls = buildConnectivityTestUrls(config.baseUrl());
      String lastFailureMessage = "Unknown error";

      for (String targetUrl : candidateUrls) {
        try {
          restClient.post()
              .uri(URI.create(targetUrl))
              .body(requestBody)
              .retrieve()
              .toEntity(String.class);
          log.info("Provider connectivity test succeeded: providerId={}, baseUrl={}, targetUrl={}, model={}",
              id, config.baseUrl(), targetUrl, config.model());
          return ProviderTestResult.builder()
              .success(true)
              .message("连接成功")
              .model(config.model())
              .build();
        } catch (RestClientResponseException e) {
          String responseBody = abbreviate(e.getResponseBodyAsString());
          lastFailureMessage = String.format(
              "HTTP %s on %s, body=%s",
              e.getStatusCode().value(),
              targetUrl,
              responseBody
          );
          log.warn(
              "Provider connectivity test failed with response: providerId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getStatusCode().value(),
              responseBody,
              e
          );
        } catch (Exception e) {
          lastFailureMessage = String.format(
              "%s on %s: %s",
              e.getClass().getSimpleName(),
              targetUrl,
              e.getMessage()
          );
          log.warn(
              "Provider connectivity test failed: providerId={}, baseUrl={}, targetUrl={}, model={}, error={}",
              id,
              config.baseUrl(),
              targetUrl,
              config.model(),
              e.getMessage(),
              e
          );
        }
      }
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + lastFailureMessage)
          .model(config.model())
          .build();
    } catch (Exception e) {
      log.warn("Provider connectivity test setup failed: providerId={}, baseUrl={}, model={}, error={}",
          id, config.baseUrl(), config.model(), e.getMessage(), e);
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + e.getMessage())
          .model(config.model())
          .build();
    }
  }

  private Optional<ProviderTestResult> rejectUnsafeEndpoint(ProviderRuntimeConfig config, String id) {
    try {
      ProviderEndpointValidator.requireHttpEndpoint(config.baseUrl(), allowLocalEndpoints());
      return Optional.empty();
    } catch (BusinessException e) {
      log.warn("Provider 地址未通过安全检查，已拒绝连接: providerId={}, baseUrl={}, reason={}",
          id, config.baseUrl(), e.getMessage());
      return Optional.of(ProviderTestResult.builder()
          .success(false)
          .message("连接被拒绝: " + e.getMessage())
          .model(config.model())
          .build());
    }
  }

  private boolean allowLocalEndpoints() {
    LlmProviderProperties.SecurityConfig security = properties.getSecurity();
    return security != null && security.isAllowLocalEndpoints();
  }

  // ===== YAML text editing (preserves comments & formatting) =====

  private void writeProviderToYaml(String id, ProviderConfig config, String envKey) {
    mutateRuntimeConfig(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入 YAML 配置失败", true, editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("base-url", config.getBaseUrl());
      values.put("api-key", "${" + envKey + "}");
      values.put("model", config.getModel());
      if (config.getEmbeddingModel() != null) {
        values.put("embedding-model", config.getEmbeddingModel());
      }
      if (config.getEmbeddingDimensions() != null) {
        values.put("embedding-dimensions", config.getEmbeddingDimensions());
      }
      if (config.getTemperature() != null) {
        values.put("temperature", config.getTemperature());
      }
      editor.setBlock(new String[]{"app", "ai", "providers"}, id, values);
    });
  }

  private void removeProviderFromYaml(String id) {
    mutateRuntimeConfig(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "删除 YAML 配置失败", true, editor -> {
      editor.removeSection(new String[]{"app", "ai", "providers"}, id);
    });
  }

  private void writeDefaultProviderToYaml(String defaultProvider) {
    mutateRuntimeConfig(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入默认 Provider 配置失败", true, editor -> {
      editor.setScalar(new String[]{"app", "ai", "default-provider"}, defaultProvider);
      editor.removeSection(new String[]{"app", "ai"}, "module-defaults");
    });
  }

  private void writeAsrConfigToYaml(VoiceInterviewProperties.AsrConfig asr) {
    mutateRuntimeConfig(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 ASR 配置失败", false, editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("url", asr.getUrl());
      values.put("model", asr.getModel());
      values.put("api-key", "${" + BAILIAN_ENV_KEY + "}");
      values.put("language", asr.getLanguage());
      values.put("format", asr.getFormat());
      values.put("sample-rate", asr.getSampleRate());
      values.put("enable-turn-detection", asr.isEnableTurnDetection());
      values.put("turn-detection-type", asr.getTurnDetectionType());
      values.put("turn-detection-threshold", asr.getTurnDetectionThreshold());
      values.put("turn-detection-silence-duration-ms", asr.getTurnDetectionSilenceDurationMs());
      editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "asr", values);
    });
  }

  private void writeTtsConfigToYaml(VoiceInterviewProperties.QwenTtsConfig tts) {
    mutateRuntimeConfig(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 TTS 配置失败", false, editor -> {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("model", tts.getModel());
      values.put("api-key", "${" + BAILIAN_ENV_KEY + "}");
      values.put("voice", tts.getVoice());
      values.put("format", tts.getFormat());
      values.put("sample-rate", tts.getSampleRate());
      values.put("mode", tts.getMode());
      values.put("language-type", tts.getLanguageType());
      values.put("speech-rate", tts.getSpeechRate());
      values.put("volume", tts.getVolume());
      editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "tts", values);
    });
  }

  /**
   * 读取-修改-写入本机运行期配置。
   *
   * <p>并发安全分两层：进程内由 {@link #rwLock} 写锁串行化（同时避免同一 JVM 内
   * {@link java.nio.channels.OverlappingFileLockException}），跨进程 / 跨实例由文件锁串行化，
   * 并且文件内容在持锁后才读取，避免并发写入互相覆盖。</p>
   *
   * @param required true 表示该配置只有本机文件一种持久化方式，未配置路径时必须显式失败
   */
  private void mutateRuntimeConfig(
      ErrorCode errorCode,
      String errorMessage,
      boolean required,
      Consumer<YamlTextEditor> mutator) {
    if (!isRuntimeConfigPathConfigured()) {
      if (required) {
        throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
            "未配置 app.ai.config-yaml-path，无法持久化 Provider 配置；请改用数据库主路径");
      }
      log.warn("{} 未写入本机文件：未配置 app.ai.config-yaml-path，仅对当前进程生效", errorMessage);
      return;
    }
    Path path = requireRuntimeConfigPath();
    Path directory = path.getParent();
    ensurePrivateDirectory(directory);
    Path lockFile = directory.resolve(path.getFileName() + ".lock");
    try (FileChannel channel = FileChannel.open(lockFile,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      channel.lock();
      List<String> lines = Files.exists(path)
          ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
          : new ArrayList<>();
      YamlTextEditor editor = new YamlTextEditor(lines);
      mutator.accept(editor);
      String content = String.join("\n", editor.getLines());
      if (!content.endsWith("\n")) {
        content += "\n";
      }
      atomicWritePrivateFile(path, content);
    } catch (IOException e) {
      throw new BusinessException(errorCode, errorMessage + ": " + e.getMessage(), e);
    }
  }

  private Path requireRuntimeConfigPath() {
    Path path;
    try {
      path = Path.of(yamlPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "app.ai.config-yaml-path 不是合法路径: " + yamlPath, e);
    }
    if (Files.isDirectory(path)) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "app.ai.config-yaml-path 必须指向文件而不是目录: " + path);
    }
    String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    if (normalized.contains("/src/main/resources/")
        || normalized.contains("/target/classes/")
        || normalized.contains("/build/resources/")
        || normalized.contains(".jar!")) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "禁止把运行期配置写回 classpath 或源码目录: " + path);
    }
    return path;
  }

  private void ensurePrivateDirectory(Path directory) {
    if (directory == null) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "app.ai.config-yaml-path 缺少父目录");
    }
    boolean existed = Files.exists(directory);
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "创建配置目录失败: " + directory, e);
    }
    if (existed) {
      return;
    }
    try {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException e) {
      warnPosixPermissionUnsupported("把配置目录收紧为 0700", directory);
    } catch (IOException e) {
      log.warn("收紧配置目录权限失败，请手动确认目录仅本机用户可读写: {}", directory, e);
    }
  }

  /**
   * 临时文件 + 原子替换，写入后校验权限，失败时不破坏原文件。
   */
  private void atomicWritePrivateFile(Path path, String content) throws IOException {
    Path directory = path.getParent();
    Path tempFile = Files.createTempFile(directory, ".runtime-config", ".tmp");
    try {
      Files.writeString(tempFile, content, StandardCharsets.UTF_8,
          StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
      restrictToOwner(tempFile);
      try {
        Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        log.warn("文件系统不支持原子替换，退化为普通替换（存在极小概率读到半写内容）: {}", path);
        Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
      }
      verifyOwnerOnly(path);
    } finally {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException e) {
        log.warn("清理临时配置文件失败: {}", tempFile, e);
      }
    }
  }

  private void restrictToOwner(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException e) {
      warnPosixPermissionUnsupported("把文件收紧为 0600", path);
    }
  }

  /**
   * Windows 等非 POSIX 文件系统无法设置 0600，只告警一次避免刷屏，具体保护依赖目录 ACL。
   */
  private void warnPosixPermissionUnsupported(String action, Path path) {
    if (posixPermissionWarningLogged.compareAndSet(false, true)) {
      log.warn("当前文件系统不支持 POSIX 权限，无法{}（后续同类告警折叠为 DEBUG），"
          + "请确认配置目录 ACL 仅授予本机账号: {}", action, path);
      return;
    }
    log.debug("当前文件系统不支持 POSIX 权限，跳过 {}: {}", action, path);
  }

  private void verifyOwnerOnly(Path path) throws IOException {
    Set<PosixFilePermission> permissions;
    try {
      permissions = Files.getPosixFilePermissions(path);
    } catch (UnsupportedOperationException e) {
      warnPosixPermissionUnsupported("校验文件权限", path);
      return;
    }
    boolean tooOpen = permissions.stream()
        .anyMatch(permission -> permission.name().startsWith("GROUP_")
            || permission.name().startsWith("OTHERS_"));
    if (!tooOpen) {
      return;
    }
    log.warn("检测到运行期配置权限过宽，已收紧为 rw-------: {}", path);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
  }

  /**
   * 清理历史遗留的明文 API Key：替换为 {@code ${ENV}} 占位符，保留其余配置，不静默删除。
   */
  private void sanitizeLegacyPlaintextSecrets(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    Path lockFile = path.getParent().resolve(path.getFileName() + ".lock");
    try (FileChannel channel = FileChannel.open(lockFile,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      channel.lock();
      List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
      boolean changed = false;
      for (int index = 0; index < lines.size(); index++) {
        Matcher matcher = PLAINTEXT_API_KEY_LINE.matcher(lines.get(index));
        if (!matcher.matches()) {
          continue;
        }
        String value = matcher.group(2).trim();
        if (value.isEmpty() || value.startsWith("${") || value.startsWith("#")) {
          continue;
        }
        String envKey = resolveEnvKeyForApiKey(lines, index);
        lines.set(index, matcher.group(1) + "${" + envKey + "}");
        changed = true;
        log.warn("检测到 {} 第 {} 行存在明文 API Key（{}），已替换为 ${{{}}} 占位符；"
            + "请把真实密钥配置到 .env 的 {} 中", path, index + 1, maskApiKey(value), envKey, envKey);
      }
      if (!changed) {
        return;
      }
      atomicWritePrivateFile(path, String.join("\n", lines) + "\n");
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "清理历史明文 API Key 失败: " + path, e);
    }
  }

  /**
   * 按 YAML 缩进推断 api-key 所属的 provider / 语音分组，从而给出正确的环境变量名。
   */
  private String resolveEnvKeyForApiKey(List<String> lines, int apiKeyLineIndex) {
    List<String> ancestors = new ArrayList<>();
    int currentIndent = indentOf(lines.get(apiKeyLineIndex));
    for (int index = apiKeyLineIndex - 1; index >= 0; index--) {
      String line = lines.get(index);
      if (line.isBlank() || line.trim().startsWith("#")) {
        continue;
      }
      int indent = indentOf(line);
      if (indent >= currentIndent) {
        continue;
      }
      String key = extractMappingKey(line);
      if (key == null) {
        continue;
      }
      ancestors.add(0, key);
      currentIndent = indent;
      if (indent == 0) {
        break;
      }
    }
    if (ancestors.contains("providers") && !ancestors.isEmpty()) {
      return toEnvKey(ancestors.get(ancestors.size() - 1));
    }
    if (ancestors.contains("qwen")) {
      return BAILIAN_ENV_KEY;
    }
    return DEFAULT_PROVIDER_ENV_KEY;
  }

  private String extractMappingKey(String line) {
    String trimmed = line.trim();
    int colon = trimmed.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    return trimmed.substring(0, colon).trim();
  }

  private int indentOf(String line) {
    int count = 0;
    while (count < line.length() && line.charAt(count) == ' ') {
      count++;
    }
    return count;
  }

  private void warnApiKeyNotPersisted(String target, String apiKey) {
    log.warn("{} 的 API Key（{}）只在当前进程内存中生效，未写入 .env 或任何明文配置文件；"
        + "重启后请通过环境变量重新提供", target, maskApiKey(apiKey));
  }

  private void refreshRegistry(String reason) {
    try {
      registry.reload();
    } catch (RuntimeException e) {
      log.warn("Provider 客户端缓存刷新失败，已提交的配置不会回滚，下次访问时会按最新配置重建: reason={}, error={}",
          reason, e.getMessage(), e);
    }
  }

  // ===== YamlTextEditor: text-based YAML editing that preserves comments & formatting =====

  static class YamlTextEditor {
    private final List<String> lines;

    YamlTextEditor(List<String> lines) {
      this.lines = new ArrayList<>(lines);
    }

    List<String> getLines() {
      return lines;
    }

    void setScalar(String[] path, String value) {
      int searchFrom = path.length > 1
          ? ensureParents(Arrays.copyOf(path, path.length - 1))
          : 0;
      int indent = (path.length - 1) * 2;
      String key = path[path.length - 1];
      String newLine = " ".repeat(indent) + key + ": " + value;

      int found = findKey(key, indent, searchFrom);
      if (found >= 0) {
        lines.set(found, newLine);
      } else {
        int parentIndent = indent >= 2 ? indent - 2 : -1;
        int insertPos = findSectionEnd(searchFrom, parentIndent);
        lines.add(insertPos, newLine);
      }
    }

    void setBlock(String[] parentPath, String blockKey, LinkedHashMap<String, Object> values) {
      int parentSearchFrom = ensureParents(parentPath);
      int blockIndent = parentPath.length * 2;
      int valueIndent = blockIndent + 2;

      int blockLine = findKey(blockKey, blockIndent, parentSearchFrom);
      if (blockLine < 0) {
        int parentEnd = parentPath.length >= 1 ? blockIndent - 2 : -1;
        int insertPos = findSectionEnd(parentSearchFrom, parentEnd);
        lines.add(insertPos, " ".repeat(blockIndent) + blockKey + ":");
        blockLine = insertPos;
      }

      int blockEnd = findSectionEnd(blockLine + 1, blockIndent);

      for (Map.Entry<String, Object> entry : values.entrySet()) {
        String valueLine = " ".repeat(valueIndent) + entry.getKey() + ": " + formatValue(entry.getValue());
        int existing = findKeyInRange(entry.getKey(), valueIndent, blockLine + 1, blockEnd);
        if (existing >= 0) {
          lines.set(existing, valueLine);
        } else {
          lines.add(blockEnd, valueLine);
          blockEnd++;
        }
      }
    }

    void removeSection(String[] parentPath, String sectionKey) {
      int parentSearchFrom = navigateTo(parentPath);
      if (parentSearchFrom < 0) return;

      int sectionIndent = parentPath.length * 2;
      int sectionLine = findKey(sectionKey, sectionIndent, parentSearchFrom);
      if (sectionLine < 0) return;

      int endLine = sectionLine + 1;
      while (endLine < lines.size()) {
        String line = lines.get(endLine);
        if (line.isBlank()) { endLine++; continue; }
        if (indentOf(line) <= sectionIndent) break;
        endLine++;
      }

      for (int i = endLine - 1; i >= sectionLine; i--) {
        lines.remove(i);
      }
    }

    private int ensureParents(String[] path) {
      int searchFrom = 0;
      for (int i = 0; i < path.length; i++) {
        int indent = i * 2;
        int found = findKey(path[i], indent, searchFrom);
        if (found < 0) {
          int parentIndent = i > 0 ? indent - 2 : -1;
          int insertPos = findSectionEnd(searchFrom, parentIndent);
          lines.add(insertPos, " ".repeat(indent) + path[i] + ":");
          searchFrom = insertPos + 1;
        } else {
          searchFrom = found + 1;
        }
      }
      return searchFrom;
    }

    private int navigateTo(String[] path) {
      int searchFrom = 0;
      for (int i = 0; i < path.length; i++) {
        int indent = i * 2;
        int found = findKey(path[i], indent, searchFrom);
        if (found < 0) return -1;
        searchFrom = found + 1;
      }
      return searchFrom;
    }

    private int findKey(String key, int indent, int searchFrom) {
      String prefix = " ".repeat(indent) + key + ":";
      for (int i = searchFrom; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#")) continue;
        if (line.startsWith(prefix)) return i;
        if (indentOf(line) < indent) break;
      }
      return -1;
    }

    private int findKeyInRange(String key, int indent, int start, int end) {
      String prefix = " ".repeat(indent) + key + ":";
      for (int i = start; i < end && i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#")) continue;
        if (line.startsWith(prefix)) return i;
        if (indentOf(line) < indent) break;
      }
      return -1;
    }

    private int findSectionEnd(int searchFrom, int parentIndent) {
      for (int i = searchFrom; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isBlank() || line.trim().startsWith("#")) continue;
        if (indentOf(line) <= parentIndent) return i;
      }
      return lines.size();
    }

    private int indentOf(String line) {
      int count = 0;
      while (count < line.length() && line.charAt(count) == ' ') count++;
      return count;
    }

    private String formatValue(Object value) {
      if (value instanceof Boolean b) return b.toString();
      if (value instanceof Number n) return n.toString();
      return value.toString();
    }
  }

  private record ProviderRuntimeConfig(
      String baseUrl,
      String apiKey,
      String model,
      String embeddingModel,
      Integer embeddingDimensions,
      boolean supportsEmbedding,
      Double temperature
  ) {
  }
}
