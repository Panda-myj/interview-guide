package interview.guide.modules.auth.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.util.MaskUtil;
import interview.guide.modules.auth.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阿里云号码认证服务（短信认证 / PNVS）发送通道，适用于个人实名主体。
 *
 * <p>选型依据（官方「个人开发者如何接入短信验证码服务」FAQ）：短信认证支持通过
 * {@code Code} / 模板参数传入服务端自定义验证码，但此时 {@code CheckSmsVerifyCode}
 * 不参与校验，需开发者自行存储与比对。项目已有 {@link SmsCodeService} 的
 * Redis 哈希校验链路，因此这里只负责发送，验证码仍由本地生成与校验。
 *
 * <p>签名与模板必须使用控制台的系统赠送资源，个人主体不能自建；
 * 计费仅按运营商回执成功的短信条数，发送失败不计费，核验免费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.sms.provider", havingValue = "pnvs")
public class PnvsSmsSender implements SmsSender {

  private final AuthProperties authProperties;
  private final ObjectMapper objectMapper;

  @Override
  public void send(SmsMessage message) {
    AuthProperties.Pnvs pnvs = authProperties.getSms().getPnvs();
    String code = message.templateParams() == null ? null : message.templateParams().get("code");
    if (isBlank(pnvs.getAccessKeyId()) || isBlank(pnvs.getAccessKeySecret())
        || isBlank(pnvs.getSignName()) || isBlank(pnvs.getTemplateCode())
        || isBlank(code)) {
      throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED,
          "短信认证配置不完整或缺少验证码");
    }

    try {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("code", code);
      params.put("min", String.valueOf(pnvs.getTemplateExpiryMinutes()));

      Config config = new Config()
          .setAccessKeyId(pnvs.getAccessKeyId())
          .setAccessKeySecret(pnvs.getAccessKeySecret())
          .setEndpoint(pnvs.getEndpoint());
      Client client = new Client(config);

      SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
          .setPhoneNumber(localPhoneNumber(message.phone()))
          .setSignName(pnvs.getSignName())
          .setTemplateCode(pnvs.getTemplateCode())
          .setTemplateParam(objectMapper.writeValueAsString(params))
          .setCountryCode(pnvs.getCountryCode())
          // 与本地 SmsCodeService 对齐：6 位码 / 5 分钟有效 / 60 秒重发冷却；
          // 不显式设置时云端默认 4 位码，会让短信文案与本地校验语义不一致。
          .setCodeLength((long) pnvs.getCodeLength())
          .setValidTime((long) pnvs.getValidTimeSeconds())
          .setInterval((long) pnvs.getIntervalSeconds())
          // 验证码由本地生成并哈希存储，禁止云端在响应里回传明文
          .setReturnVerifyCode(false);
      if (!isBlank(pnvs.getSchemeName())) {
        request.setSchemeName(pnvs.getSchemeName());
      }

      SendSmsVerifyCodeResponse response =
          client.sendSmsVerifyCodeWithOptions(request, new RuntimeOptions());
      String resultCode = response.getBody() == null ? null : response.getBody().getCode();
      String requestId = response.getBody() == null ? null : response.getBody().getRequestId();
      if (!"OK".equalsIgnoreCase(resultCode)) {
        String providerMessage = response.getBody() == null
            ? "empty response"
            : response.getBody().getMessage();
        // 记录服务商状态码与 requestId 便于提工单；不打模板参数、验证码与完整手机号
        log.warn("PNVS 发码失败: phone={}, providerCode={}, requestId={}",
            MaskUtil.maskPhone(message.phone()), resultCode, requestId);
        throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED,
            "短信认证服务返回失败: " + providerMessage);
      }
      log.info("PNVS 验证码已发送: phone={}, requestId={}",
          MaskUtil.maskPhone(message.phone()), requestId);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      // 不打印模板参数：验证码不得进入日志
      throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED, "短信认证服务调用失败", e);
    }
  }

  /** AuthSupport.normalizePhone 返回 E.164（+86...），PNVS 需要纯号码 + CountryCode。 */
  private String localPhoneNumber(String normalizedPhone) {
    String phone = normalizedPhone == null ? "" : normalizedPhone.trim();
    if (phone.startsWith("+86")) {
      return phone.substring(3);
    }
    if (phone.startsWith("86") && phone.length() == 13) {
      return phone.substring(2);
    }
    return phone;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
