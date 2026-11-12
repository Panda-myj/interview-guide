package interview.guide.modules.auth.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.sms.provider", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void send(SmsMessage message) {
        AuthProperties.Aliyun aliyun = authProperties.getSms().getAliyun();
        if (isBlank(aliyun.getAccessKeyId()) || isBlank(aliyun.getAccessKeySecret())
            || isBlank(aliyun.getSignName()) || isBlank(message.templateCode())) {
            throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED, "阿里云短信配置不完整");
        }

        try {
            Config config = new Config()
                .setAccessKeyId(aliyun.getAccessKeyId())
                .setAccessKeySecret(aliyun.getAccessKeySecret())
                .setEndpoint(aliyun.getEndpoint());
            Client client = new Client(config);
            SendSmsRequest request = new SendSmsRequest()
                .setPhoneNumbers(message.phone())
                .setSignName(aliyun.getSignName())
                .setTemplateCode(message.templateCode())
                .setTemplateParam(objectMapper.writeValueAsString(message.templateParams()));
            SendSmsResponse response = client.sendSms(request);
            String code = response.getBody() == null ? null : response.getBody().getCode();
            if (!"OK".equalsIgnoreCase(code)) {
                String providerMessage = response.getBody() == null
                    ? "empty response"
                    : response.getBody().getMessage();
                throw new BusinessException(
                    ErrorCode.AUTH_SMS_SEND_FAILED,
                    "短信服务返回失败: " + providerMessage
                );
            }
            log.info("SMS sent: template={}, requestId={}",
                message.templateCode(),
                response.getBody().getRequestId());
        } catch (BusinessException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED, "短信模板参数序列化失败", e);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED, "短信服务调用失败", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
