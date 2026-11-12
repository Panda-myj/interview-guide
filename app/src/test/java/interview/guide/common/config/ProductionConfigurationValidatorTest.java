package interview.guide.common.config;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产启动校验：覆盖 JWT、短信、数据库、Redis、对象存储、Provider 与暴露开关。
 * 断言重点是「缺失/不安全配置必须启动失败」以及「错误信息不回显敏感值」。
 */
@DisplayName("生产启动配置校验")
class ProductionConfigurationValidatorTest {

    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";

    private MockEnvironment environment;
    private CorsProperties corsProperties;
    private LlmProviderProperties providerProperties;
    private ProductionConfigurationValidator validator;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        corsProperties = new CorsProperties();
        corsProperties.setAllowedOrigins("https://interview.example.com");

        providerProperties = new LlmProviderProperties();
        providerProperties.getSecurity().setRequireEncryptionKey(true);
        providerProperties.getSecurity().setApiKeyEncryptionKey("encryption-key-for-test-only");

        applyBaselineProductionConfig();
    }

    private void applyBaselineProductionConfig() {
        environment.setProperty("app.auth.jwt-secret", JWT_SECRET);
        environment.setProperty("app.auth.sms.provider", "mock");
        environment.setProperty("app.auth.sms.expose-debug-code", "false");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/interview");
        environment.setProperty("spring.datasource.username", "interview_app");
        environment.setProperty("spring.datasource.password", "db-password");
        environment.setProperty("spring.redis.redisson.config",
            "singleServerConfig:\n  address: \"redis://redis.internal:6379\"");
        environment.setProperty("app.storage.endpoint", "https://storage.example.com");
        environment.setProperty("app.storage.access-key", "access-key");
        environment.setProperty("app.storage.secret-key", "secret-key");
        environment.setProperty("app.storage.bucket", "interview-guide");
        environment.setProperty("app.storage.auto-create-bucket", "false");
        environment.setProperty("app.ai.default-provider", "dashscope");
        environment.setProperty("app.ai.default-embedding-provider", "dashscope");
        environment.setProperty("app.ai.security.allow-fallback-encryption-key", "false");
        environment.setProperty("springdoc.api-docs.enabled", "false");
        environment.setProperty("springdoc.swagger-ui.enabled", "false");
        environment.setProperty("management.endpoints.web.exposure.include", "health");

        validator = new ProductionConfigurationValidator(corsProperties, providerProperties, environment);
    }

    @Nested
    @DisplayName("完整且安全的生产配置")
    class ValidConfiguration {

        @Test
        @DisplayName("全部必需配置齐备时校验通过")
        void passesWithCompleteConfiguration() {
            assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非生产环境跳过校验")
        void skipsValidationOutsideProduction() {
            environment.setActiveProfiles("dev");
            environment.setProperty("app.auth.jwt-secret", "");

            assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("CORS 与加密密钥")
    class CorsAndEncryption {

        @Test
        @DisplayName("拒绝 localhost、IP 与通配符来源")
        void rejectsUnsafeOrigins() {
            assertThatThrownBy(() -> ProductionConfigurationValidator.requireHttpsNonLocalOrigins("", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CORS");
            assertThatThrownBy(() -> ProductionConfigurationValidator.requireHttpsNonLocalOrigins("*", true))
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> ProductionConfigurationValidator.requireHttpsNonLocalOrigins(
                "http://localhost:5173", true)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> ProductionConfigurationValidator.requireHttpsNonLocalOrigins(
                "https://127.0.0.1", true)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("接受非 localhost 的 HTTPS 精确来源")
        void acceptsSafeOrigins() {
            assertThatCode(() -> ProductionConfigurationValidator.requireHttpsNonLocalOrigins(
                "https://example.com,https://app.example.com:8443", true
            )).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("缺少加密密钥时启动失败")
        void rejectsMissingEncryptionKey() {
            providerProperties.getSecurity().setApiKeyEncryptionKey("");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APP_AI_CONFIG_ENCRYPTION_KEY");
        }
    }

    @Nested
    @DisplayName("认证与短信")
    class AuthAndSms {

        @Test
        @DisplayName("缺少 JWT 密钥时启动失败")
        void rejectsMissingJwtSecret() {
            environment.setProperty("app.auth.jwt-secret", "");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AUTH_JWT_SECRET");
        }

        @Test
        @DisplayName("JWT 密钥长度不足 32 字节时启动失败")
        void rejectsShortJwtSecret() {
            environment.setProperty("app.auth.jwt-secret", "too-short");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("32");
        }

        @Test
        @DisplayName("生产开启短信 debug code 时启动失败")
        void rejectsSmsDebugCode() {
            environment.setProperty("app.auth.sms.expose-debug-code", "true");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APP_AUTH_EXPOSE_DEBUG_CODE");
        }

        @Test
        @DisplayName("选择 aliyun 通道但缺少凭据时启动失败")
        void rejectsAliyunWithoutCredentials() {
            environment.setProperty("app.auth.sms.provider", "aliyun");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ALIYUN_SMS");
        }

        @Test
        @DisplayName("mock 通道不需要真实短信凭据")
        void mockChannelNeedsNoCredentials() {
            assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("依赖服务配置")
    class DependencyConfiguration {

        @Test
        @DisplayName("缺少数据库密码时启动失败且不回显密码")
        void rejectsMissingDatasourcePassword() {
            environment.setProperty("spring.datasource.password", "");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("POSTGRES_PASSWORD");
        }

        @Test
        @DisplayName("Redis 指向 localhost 时启动失败")
        void rejectsLocalhostRedis() {
            environment.setProperty("spring.redis.redisson.config",
                "singleServerConfig:\n  address: \"redis://localhost:6379\"");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("REDIS_HOST");
        }

        @Test
        @DisplayName("缺少对象存储凭据时启动失败")
        void rejectsMissingStorageCredentials() {
            environment.setProperty("app.storage.secret-key", "");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APP_STORAGE_SECRET_KEY")
                .hasMessageNotContaining("secret-key");
        }

        @Test
        @DisplayName("对象存储 access key 与 secret key 相同时启动失败")
        void rejectsIdenticalStorageKeys() {
            environment.setProperty("app.storage.access-key", "same-key");
            environment.setProperty("app.storage.secret-key", "same-key");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APP_STORAGE_SECRET_KEY");
        }

        @Test
        @DisplayName("生产允许启动时自动创建 bucket 时启动失败")
        void rejectsAutoCreateBucket() {
            environment.setProperty("app.storage.auto-create-bucket", "true");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APP_STORAGE_AUTO_CREATE_BUCKET");
        }
    }

    @Nested
    @DisplayName("暴露面开关")
    class ExposureSwitches {

        @Test
        @DisplayName("生产开启 Swagger 时启动失败")
        void rejectsSwaggerEnabled() {
            environment.setProperty("springdoc.api-docs.enabled", "true");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SPRINGDOC");
        }

        @Test
        @DisplayName("生产暴露非 health 的 Actuator 端点时启动失败")
        void rejectsExtraActuatorEndpoints() {
            environment.setProperty("management.endpoints.web.exposure.include", "health,env");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Actuator");
        }

        @Test
        @DisplayName("生产允许回退加密密钥时启动失败")
        void rejectsFallbackEncryptionKey() {
            environment.setProperty("app.ai.security.allow-fallback-encryption-key", "true");

            assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APP_AI_CONFIG_ALLOW_FALLBACK_ENCRYPTION_KEY");
        }
    }
}
