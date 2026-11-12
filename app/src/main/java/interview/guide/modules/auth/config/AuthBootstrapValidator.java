package interview.guide.modules.auth.config;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthBootstrapValidator {

    private final AuthProperties authProperties;
    private final Environment environment;

    @PostConstruct
    void validate() {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && (authProperties.getBootstrapPhone() == null
            || authProperties.getBootstrapPhone().isBlank())) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_AUTH_BOOTSTRAP_PHONE must be configured in production"
            );
        }
        if (authProperties.getBootstrapPhone() != null
            && !authProperties.getBootstrapPhone().isBlank()
            && !authProperties.getBootstrapPhone().matches("^\\+861[3-9]\\d{9}$")) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_AUTH_BOOTSTRAP_PHONE must use E.164 format, for example +8613800000000"
            );
        }

        String provider = authProperties.getSms().getProvider();
        if (production && "mock".equalsIgnoreCase(provider)) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "Mock SMS provider is not allowed in production; "
                    + "configure APP_AUTH_SMS_PROVIDER=pnvs (个人主体) or aliyun (企业主体)"
            );
        }
        if (production && authProperties.getSms().isExposeDebugCode()) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_AUTH_EXPOSE_DEBUG_CODE must be false in production"
            );
        }

        if ("aliyun".equalsIgnoreCase(provider)) {
            AuthProperties.Aliyun aliyun = authProperties.getSms().getAliyun();
            // 登录提醒已改为站内通知，不再需要 loginAlertTemplateCode。
            if (isBlank(aliyun.getAccessKeyId())
                || isBlank(aliyun.getAccessKeySecret())
                || isBlank(aliyun.getSignName())
                || isBlank(aliyun.getLoginCodeTemplateCode())) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID, "Aliyun SMS configuration is incomplete");
            }
        } else if ("pnvs".equalsIgnoreCase(provider)) {
            AuthProperties.Pnvs pnvs = authProperties.getSms().getPnvs();
            if (isBlank(pnvs.getAccessKeyId())
                || isBlank(pnvs.getAccessKeySecret())
                || isBlank(pnvs.getSignName())
                || isBlank(pnvs.getTemplateCode())) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID,
                    "PNVS 短信认证配置不完整：需要 ALIYUN_PNVS_ACCESS_KEY_ID / "
                        + "ALIYUN_PNVS_ACCESS_KEY_SECRET / ALIYUN_PNVS_SIGN_NAME / ALIYUN_PNVS_TEMPLATE_CODE"
                );
            }
            if (authProperties.getSms().isExposeDebugCode()) {
                throw new BusinessException(ErrorCode.CONFIG_INVALID,
                    "PNVS 真实短信必须关闭 APP_AUTH_EXPOSE_DEBUG_CODE，避免验证码从接口回传"
                );
            }
        } else if (!"mock".equalsIgnoreCase(provider)) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "Unknown APP_AUTH_SMS_PROVIDER: " + provider);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
