package interview.guide.modules.auth.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PNVS 短信认证发送器配置校验")
class PnvsSmsSenderTest {

    private AuthProperties properties;
    private PnvsSmsSender sender;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        AuthProperties.Pnvs pnvs = properties.getSms().getPnvs();
        pnvs.setAccessKeyId("test-ak");
        pnvs.setAccessKeySecret("test-sk");
        pnvs.setSignName("系统赠送签名");
        pnvs.setTemplateCode("100001");
        sender = new PnvsSmsSender(properties, new ObjectMapper());
    }

    @Test
    @DisplayName("AccessKey 缺失时快速失败，不发起外部调用")
    void rejectsMissingAccessKey() {
        properties.getSms().getPnvs().setAccessKeyId("");

        assertThatThrownBy(() -> sender.send(new SmsMessage(
            "+8613800138000", "100001", Map.of("code", "123456"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("配置不完整");
    }

    @Test
    @DisplayName("赠送签名或模板缺失时快速失败")
    void rejectsMissingSignOrTemplate() {
        properties.getSms().getPnvs().setTemplateCode("");

        assertThatThrownBy(() -> sender.send(new SmsMessage(
            "+8613800138000", "100001", Map.of("code", "123456"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("配置不完整");
    }

    @Test
    @DisplayName("短信模板参数缺少验证码时拒绝发送，避免把通知类消息误走验证码通道")
    void rejectsMessageWithoutCode() {
        assertThatThrownBy(() -> sender.send(new SmsMessage(
            "+8613800138000", "100001", Map.of("time", "16:00"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("配置不完整");
    }
}
