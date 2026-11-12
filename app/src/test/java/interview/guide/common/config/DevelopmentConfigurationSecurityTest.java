package interview.guide.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开发 / 生产配置分离基线：不依赖网络、不启动 Spring 上下文，只做声明式断言。
 */
@DisplayName("开发与生产配置安全基线")
class DevelopmentConfigurationSecurityTest {

    @Nested
    @DisplayName("开发默认配置")
    class DevelopmentDefaults {

        @Test
        @DisplayName("开发默认不保留数据库弱密码")
        void doesNotUseDatabaseWeakDefault() throws Exception {
            String application = readMainResource("application.yml");

            assertThat(application).doesNotContain("POSTGRES_PASSWORD:123456");
            assertThat(application).doesNotContain("POSTGRES_PASSWORD: 123456");
        }

        @Test
        @DisplayName("短信 debug code 默认关闭")
        void smsDebugCodeDisabledByDefault() throws Exception {
            String application = readMainResource("application.yml");

            assertThat(application).contains("APP_AUTH_EXPOSE_DEBUG_CODE:false");
        }

        @Test
        @DisplayName("开发依赖不向宿主机映射数据库、Redis 与对象存储端口")
        void devComposeDoesNotPublishDependencyPorts() throws Exception {
            String compose = readRepoFile("docker-compose.dev.yml");

            assertThat(compose).doesNotContain("5432:5432");
            assertThat(compose).doesNotContain("6379:6379");
            assertThat(compose).doesNotContain("9000:9000");
            assertThat(compose).doesNotContain("9001:9001");
        }
    }

    @Nested
    @DisplayName("生产默认配置")
    class ProductionDefaults {

        @Test
        @DisplayName("生产 CORS 不回退到 localhost 或 IP")
        void productionCorsHasNoLocalDefault() throws Exception {
            String prod = readMainResource("application-prod.yml");

            assertThat(prod).doesNotContain("http://localhost");
            assertThat(prod).doesNotContain("http://127.0.0.1");
        }

        @Test
        @DisplayName("生产默认关闭 Swagger 与接口文档")
        void productionDisablesSpringdoc() throws Exception {
            String prod = readMainResource("application-prod.yml");

            assertThat(prod).contains("SPRINGDOC_API_DOCS_ENABLED:false");
            assertThat(prod).contains("SPRINGDOC_SWAGGER_UI_ENABLED:false");
        }

        @Test
        @DisplayName("生产默认只暴露 health 且不展示健康详情")
        void productionExposesOnlyHealth() throws Exception {
            String prod = readMainResource("application-prod.yml");

            assertThat(prod).contains("ACTUATOR_ENDPOINTS:health");
            assertThat(prod).contains("show-details: never");
        }

        @Test
        @DisplayName("生产默认关闭短信 debug code")
        void productionDisablesSmsDebugCode() throws Exception {
            String prod = readMainResource("application-prod.yml");

            assertThat(prod).contains("expose-debug-code: false");
        }

        @Test
        @DisplayName("生产不提供对象存储默认凭据与自动建桶")
        void productionHasNoStorageDefaults() throws Exception {
            String prod = readMainResource("application-prod.yml");

            assertThat(prod).doesNotContain("rustfsadmin");
            assertThat(prod).contains("auto-create-bucket: false");
        }
    }

    private String readMainResource(String name) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readRepoFile(String name) throws Exception {
        Path fromWorkingDir = Paths.get("..", name);
        Path path = Files.exists(fromWorkingDir) ? fromWorkingDir : Paths.get(name);
        assertThat(Files.exists(path)).as("仓库根文件 %s 应存在", name).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
