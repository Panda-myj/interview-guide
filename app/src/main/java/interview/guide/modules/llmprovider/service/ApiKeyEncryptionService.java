package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ApiKeyEncryptionService {

  private static final int NONCE_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;
  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final String DEV_FALLBACK_KEY =
      "interview-guide-dev-only-provider-api-key-encryption";

  private final LlmProviderProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();
  private SecretKeySpec secretKey;

  public ApiKeyEncryptionService(LlmProviderProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void init() {
    String configuredKey = resolveConfiguredKey(properties.getSecurity());
    secretKey = new SecretKeySpec(resolveKeyBytes(configuredKey), "AES");
  }

  private String resolveConfiguredKey(LlmProviderProperties.SecurityConfig security) {
    String configuredKey = security != null ? security.getApiKeyEncryptionKey() : null;
    if (configuredKey != null && !configuredKey.isBlank()) {
      return configuredKey;
    }

    boolean requireEncryptionKey = security == null || security.isRequireEncryptionKey();
    if (requireEncryptionKey) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，无法初始化 Provider API Key 加密");
    }

    boolean allowFallback = security != null && security.isAllowFallbackEncryptionKey();
    if (!allowFallback) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，且未显式允许 Provider API Key 开发 fallback");
    }

    log.warn("APP_AI_CONFIG_ENCRYPTION_KEY is not configured; using explicitly enabled fallback key");
    return DEV_FALLBACK_KEY;
  }

  public EncryptedValue encrypt(String plainText) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];// ① 生成 12 字节随机 nonce
      secureRandom.nextBytes(nonce);//Random 是伪随机，可被预测；SecureRandom 是密码学安全的随机数生成器（CSPRNG），不可预测。用错这里等于加密白做。
        //关键点：Nonce 不需要保密，但绝不能重复。所以它和密文一起存储/传输。
      Cipher cipher = Cipher.getInstance(CIPHER);
      //CIPHER = "AES/GCM/NoPadding"
//        "AES/GCM/NoPadding" 是一个固定字符串，含义：
//        AES：算法
//        GCM：工作模式（自带认证，替代了传统的 CBC+HMAC）
//        NoPadding：GCM 是流式模式，不需要填充（PKCS5Padding 等只用于块模式）

//        AES-256 从哪来？ 由 secretKey 决定。如果 key 是 32 字节，就是 AES-256；16 字节就是 AES-128。
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));// ③ GCM 模式带认证标签
      // GCM_TAG_BITS：认证标签（Auth Tag）长度，单位 bit。128 bit 是最高安全级别，NIST 强烈推荐。低于 96 bit 不建议使用。
      //  Auth Tag 的作用：解密时 GCM 会自动验证这个标签。如果密文被篡改哪怕 1 bit，解密会直接抛 AEADBadTagException，不会返回错误明文。这就是“认证加密”的核心价值。
      byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
//        doFinal() 一步完成两件事：
//        加密明文 → 产出密文
//        计算 Auth Tag → 自动追加到密文末尾
//        所以返回的 ciphertext 实际上是 [加密数据 || AuthTag] 的拼接体。
//⚠️ 注意：getBytes(UTF_8) 显式指定编码，避免不同平台默认编码不一致导致解密失败。
      return new EncryptedValue(
          Base64.getEncoder().encodeToString(nonce),
          Base64.getEncoder().encodeToString(ciphertext)
      );
//      为什么要 Base64？ 密文和 Nonce 都是原始字节数组，包含不可打印字符，无法直接存入数据库 VARCHAR 字段或 JSON 传输。Base64 将其转为安全的 ASCII 字符串。
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
          "加密 Provider API Key 失败", e);
    }
  }

  public String decrypt(String nonceBase64, String ciphertextBase64) {
    try {
      byte[] nonce = Base64.getDecoder().decode(nonceBase64);
      byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);

      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] plainText = cipher.doFinal(ciphertext);
      return new String(plainText, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "解密 Provider API Key 失败，请检查 APP_AI_CONFIG_ENCRYPTION_KEY", e);
    }
  }

  private byte[] resolveKeyBytes(String configuredKey) {
    String trimmed = configuredKey.trim();
    try {
      byte[] decoded = Base64.getDecoder().decode(trimmed);
      if (decoded.length == 32) {
        return decoded;
      }
    } catch (IllegalArgumentException ignored) {
      // Fall through to SHA-256 derivation for human-readable keys.
    }
    return sha256(trimmed);
  }

  private byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
          "初始化 Provider API Key 加密密钥失败", e);
    }
  }

  public record EncryptedValue(String nonce, String ciphertext) {
  }
}
