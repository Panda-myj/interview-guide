package interview.guide.common.config;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("生产 CORS 配置安全测试")
class ProductionCorsConfigTest {

    private ProductionCorsConfig config(String origins) {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(origins);
        return new ProductionCorsConfig(properties);
    }

    @Test
    @DisplayName("拒绝 localhost 与 IP 来源")
    void rejectsLocalAndIpOrigins() {
        assertThatThrownBy(() -> config("https://example.com,http://localhost:5173")
                .corsConfigurationSource())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CORS");
        assertThatThrownBy(() -> config("http://127.0.0.1")
                .corsConfigurationSource())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CORS");
    }

    @Test
    @DisplayName("允许非 localhost HTTPS 精确来源")
    void allowsProductionHttpsOrigin() {
        UrlBasedCorsConfigurationSource source = config("https://app.example.com").corsConfigurationSource();
        assertThat(source.getCorsConfigurations()).hasSize(1);
    }

    @Test
    @DisplayName("拒绝缺少来源或通配符")
    void rejectsMissingOrWildcardOrigins() {
        assertThatThrownBy(() -> config("").corsConfigurationSource())
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> config("*").corsConfigurationSource())
            .isInstanceOf(BusinessException.class);
    }
}
