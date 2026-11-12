package interview.guide.modules.auth.sms;

import interview.guide.common.util.MaskUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.auth.sms.provider",
    havingValue = "mock",
    matchIfMissing = true
)
public class MockSmsSender implements SmsSender {

    @Override
    public void send(SmsMessage message) {
        // 不打印 templateParams：登录验证码等参数不得进入日志
        log.info("Mock SMS sent: phone={}, template={}",
            MaskUtil.maskPhone(message.phone()), message.templateCode());
    }
}
