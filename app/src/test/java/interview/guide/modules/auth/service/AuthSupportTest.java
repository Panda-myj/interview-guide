package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("认证基础工具测试")
class AuthSupportTest {

    private final AuthSupport support = new AuthSupport();

    @Test
    @DisplayName("中国大陆手机号统一转换为 E.164 格式")
    void normalizesMainlandPhone() {
        assertThat(support.normalizePhone("138 0013 8000")).isEqualTo("+8613800138000");
        assertThat(support.normalizePhone("+8613800138000")).isEqualTo("+8613800138000");
    }

    @Test
    @DisplayName("非法手机号返回业务异常")
    void rejectsInvalidPhone() {
        assertThatThrownBy(() -> support.normalizePhone("12345"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("手机号");
    }

    @Test
    @DisplayName("手机号脱敏不泄露中间号码")
    void masksPhone() {
        assertThat(support.maskPhone("+8613800138000")).isEqualTo("+86****8000");
    }
}
