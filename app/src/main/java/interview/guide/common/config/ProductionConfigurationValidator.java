package interview.guide.common.config;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 生产启动前置校验。
 * <p>
 * 目标：把「部署后跑到一半才发现缺配置」变成「启动即失败」，并且失败信息只说明
 * 缺失/不安全的配置项名称与修复方式，绝不回显配置值本身（避免密钥进入日志）。
 * <p>
 * 开发环境不执行本校验，避免本地开发被生产约束阻塞。
 */
@Component
@RequiredArgsConstructor
public class ProductionConfigurationValidator {

    /** JWT HS256 要求密钥至少 256 bit。 */
    private static final int MIN_JWT_SECRET_BYTES = 32;

    private final CorsProperties corsProperties;
    private final LlmProviderProperties providerProperties;
    private final Environment environment;

    @PostConstruct
    void validate() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        requireHttpsNonLocalOrigins(corsProperties.getAllowedOrigins());

        // 加密密钥：生产必须提供，且禁止回退到内置默认密钥
        if (providerProperties.getSecurity().isRequireEncryptionKey()
            && (providerProperties.getSecurity().getApiKeyEncryptionKey() == null
                || providerProperties.getSecurity().getApiKeyEncryptionKey().isBlank())) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID, "APP_AI_CONFIG_ENCRYPTION_KEY must be configured");
        }

        validateJwtSecret();
        validateSmsChannel();
        validateDatasource();
        validateRedis();
        validateObjectStorage();
        validateProviderDefaults();
        validateExposureSwitches();
        validateActuatorEndpoints();
    }

    private void validateJwtSecret() {
        String jwtSecret = requiredProperty("app.auth.jwt-secret", "AUTH_JWT_SECRET must be configured");
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "AUTH_JWT_SECRET must contain at least " + MIN_JWT_SECRET_BYTES + " UTF-8 bytes");
        }
        if (isTrue("app.auth.sms.expose-debug-code")) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_AUTH_EXPOSE_DEBUG_CODE must be disabled in production");
        }
    }

    private void validateSmsChannel() {
        String provider = environment.getProperty("app.auth.sms.provider", "mock");
        if (!"aliyun".equalsIgnoreCase(provider)) {
            // 非阿里云通道（含 mock）不需要真实凭据；生产默认 mock 属于受支持形态
            return;
        }
        requiredProperty("app.auth.sms.aliyun.access-key-id", "ALIYUN_SMS_ACCESS_KEY_ID must be configured for aliyun SMS");
        requiredProperty("app.auth.sms.aliyun.access-key-secret",
            "ALIYUN_SMS_ACCESS_KEY_SECRET must be configured for aliyun SMS");
        requiredProperty("app.auth.sms.aliyun.sign-name", "ALIYUN_SMS_SIGN_NAME must be configured for aliyun SMS");
        requiredProperty("app.auth.sms.aliyun.login-code-template-code",
            "ALIYUN_SMS_LOGIN_CODE_TEMPLATE must be configured for aliyun SMS");
    }

    private void validateDatasource() {
        requiredProperty("spring.datasource.url", "POSTGRES_HOST/POSTGRES_DB must configure spring.datasource.url");
        requiredProperty("spring.datasource.username", "POSTGRES_USER must be configured");
        requiredProperty("spring.datasource.password", "POSTGRES_PASSWORD must be configured in production");
    }

    private void validateRedis() {
        String redissonConfig = environment.getProperty("spring.redis.redisson.config", "");
        if (redissonConfig.isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "spring.redis.redisson.config must be configured (REDIS_HOST/REDIS_PORT)");
        }
        if (redissonConfig.contains("localhost") || redissonConfig.contains("127.0.0.1")) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "REDIS_HOST must not point to localhost in production");
        }
    }

    private void validateObjectStorage() {
        requiredProperty("app.storage.endpoint", "APP_STORAGE_ENDPOINT must be configured in production");
        requiredProperty("app.storage.access-key", "APP_STORAGE_ACCESS_KEY must be configured in production");
        requiredProperty("app.storage.secret-key", "APP_STORAGE_SECRET_KEY must be configured in production");
        requiredProperty("app.storage.bucket", "APP_STORAGE_BUCKET must be configured in production");
        if (isTrue("app.storage.auto-create-bucket")) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_STORAGE_AUTO_CREATE_BUCKET must be disabled in production");
        }
        String accessKey = environment.getProperty("app.storage.access-key", "");
        String secretKey = environment.getProperty("app.storage.secret-key", "");
        if (!accessKey.isBlank() && accessKey.equals(secretKey)) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_STORAGE_SECRET_KEY must differ from APP_STORAGE_ACCESS_KEY");
        }
    }

    private void validateProviderDefaults() {
        requiredProperty("app.ai.default-provider", "APP_AI_DEFAULT_PROVIDER must be configured in production");
        requiredProperty("app.ai.default-embedding-provider",
            "APP_AI_DEFAULT_EMBEDDING_PROVIDER must be configured in production");
        if (isTrue("app.ai.security.allow-fallback-encryption-key")) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_AI_CONFIG_ALLOW_FALLBACK_ENCRYPTION_KEY must be disabled in production");
        }
    }

    private void validateExposureSwitches() {
        if (isTrue("springdoc.api-docs.enabled") || isTrue("springdoc.swagger-ui.enabled")) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "SPRINGDOC_API_DOCS_ENABLED and SPRINGDOC_SWAGGER_UI_ENABLED must be disabled in production");
        }
    }

    private void validateActuatorEndpoints() {
        String actuatorEndpoints = environment.getProperty("management.endpoints.web.exposure.include", "health");
        if (Arrays.stream(actuatorEndpoints.split(","))
            .map(String::trim)
            .anyMatch(endpoint -> !endpoint.equals("health"))) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "Only Actuator health may be exposed by default in production");
        }
    }

    private String requiredProperty(String key, String message) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID, message);
        }
        return value;
    }

    private boolean isTrue(String key) {
        return Boolean.parseBoolean(environment.getProperty(key, "false"));
    }

    private void requireHttpsNonLocalOrigins(String allowedOrigins) {
        requireHttpsNonLocalOrigins(allowedOrigins, true);
    }

    /**
     * 生产 CORS 来源校验：仅允许精确的 HTTPS 域名，拒绝通配符、IP 字面量与本地域名。
     * <p>
     * 只校验 scheme 不足以挡住 `https://localhost` 与 `https://127.0.0.1`
     * ——它们同样是本机入口，会被浏览器当作可信来源。
     */
    static void requireHttpsNonLocalOrigins(String allowedOrigins, boolean requireNonEmpty) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            if (requireNonEmpty) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID, "CORS allowed origins must be configured");
            }
            return;
        }
        for (String origin : allowedOrigins.split(",")) {
            String trimmedOrigin = origin.trim();
            if (!trimmedOrigin.matches("^https://[a-zA-Z0-9.-]+(:\\d+)?$")) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID,
                    "CORS allowed origins must use non-localhost HTTPS exact origins");
            }
            String host = trimmedOrigin.substring("https://".length()).replaceFirst(":\\d+$", "");
            if (isLocalOrIpHost(host)) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID,
                    "CORS allowed origins must not use localhost or IP literal hosts");
            }
        }
    }

    private static boolean isLocalOrIpHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
            || normalized.equals("0.0.0.0")) {
            return true;
        }
        // IPv4 字面量
        if (normalized.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return true;
        }
        // IPv6 字面量（方括号形式或含冒号）
        return normalized.startsWith("[") || normalized.contains(":");
    }
}
