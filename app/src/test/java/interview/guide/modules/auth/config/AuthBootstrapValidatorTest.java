package interview.guide.modules.auth.config;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("认证启动配置校验测试")
class AuthBootstrapValidatorTest {

    @Test
    @DisplayName("开发环境允许 mock 通道")
    void allowsMockInDevelopment() {
        assertThatCode(() -> validator("mock", false, new MockEnvironment()).validate())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("生产环境禁止 mock 通道")
    void rejectsMockInProduction() {
        Environment prod = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        assertThatThrownBy(() -> validator("mock", false, prod).validate())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Mock SMS provider is not allowed");
    }

    @Test
    @DisplayName("生产环境禁止回显验证码")
    void rejectsDebugCodeInProduction() {
        Environment prod = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        assertThatThrownBy(() -> validator("pnvs", true, prod).validate())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("APP_AUTH_EXPOSE_DEBUG_CODE");
    }

    @Test
    @DisplayName("pnvs 配置缺失时快速失败")
    void rejectsIncompletePnvsConfig() {
        assertThatThrownBy(() -> validator("pnvs", false, new MockEnvironment()).validate())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PNVS 短信认证配置不完整");
    }

    @Test
    @DisplayName("非生产环境使用 pnvs 时也必须关闭验证码回显")
    void rejectsDebugCodeWithPnvsInDevelopment() {
        AuthProperties properties = properties("pnvs", true);
        AuthProperties.Pnvs pnvs = properties.getSms().getPnvs();
        pnvs.setAccessKeyId("ak");
        pnvs.setAccessKeySecret("sk");
        pnvs.setSignName("赠送签名");
        pnvs.setTemplateCode("100001");

        assertThatThrownBy(() -> new AuthBootstrapValidator(properties, new MockEnvironment()).validate())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("APP_AUTH_EXPOSE_DEBUG_CODE");
    }

    @Test
    @DisplayName("pnvs 配置完整时允许启动")
    void acceptsCompletePnvsConfig() {
        AuthProperties properties = properties("pnvs", false);
        AuthProperties.Pnvs pnvs = properties.getSms().getPnvs();
        pnvs.setAccessKeyId("ak");
        pnvs.setAccessKeySecret("sk");
        pnvs.setSignName("赠送签名");
        pnvs.setTemplateCode("100001");

        assertThatCode(() -> new AuthBootstrapValidator(properties, new MockEnvironment()).validate())
            .doesNotThrowAnyException();
    }

    private AuthBootstrapValidator validator(String provider, boolean debug, Environment environment) {
        return new AuthBootstrapValidator(properties(provider, debug), environment);
    }

    private AuthProperties properties(String provider, boolean debug) {
        AuthProperties properties = new AuthProperties();
        properties.setBootstrapPhone("+8613800000000");
        properties.getSms().setProvider(provider);
        properties.getSms().setExposeDebugCode(debug);
        return properties;
    }
}
