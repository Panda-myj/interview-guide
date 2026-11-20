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
        // 开发态匿名调试入口只允许出现在「mock 短信 + 非 prod」，误开到生产/真实短信时直接拒绝启动。
        boolean devAnonymousAdmin = environment.getProperty(
            "app.auth.dev.anonymous-admin", Boolean.class, false);
        if (devAnonymousAdmin && (production || !"mock".equalsIgnoreCase(provider))) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_AUTH_DEV_ANONYMOUS_ADMIN is only allowed with APP_AUTH_SMS_PROVIDER=mock outside prod profile"
            );
        }
        if (production && "mock".equalsIgnoreCase(provider)) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "Mock SMS provider is not allowed in production; "
                    + "configure APP_AUTH_SMS_PROVIDER=pnvs (个人主体) or aliyun (企业主体)"
            );
        }
        // 回传验证码只允许 mock 通道。原实现只在 prod 下拦截，导致 aliyun/pnvs + 非 prod
        // 组合下真实短信验证码会随 HTTP 响应返回，等同于认证绕过。
        if (authProperties.getSms().isExposeDebugCode()
            && !"mock".equalsIgnoreCase(provider)) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "APP_AUTH_EXPOSE_DEBUG_CODE is only allowed with APP_AUTH_SMS_PROVIDER=mock; "
                    + "never expose SMS codes when a real SMS provider is configured"
            );
        }

        if ("aliyun".equalsIgnoreCase(provider)) {
            AuthProperties.Aliyun aliyun = authProperties.getSms().getAliyun();
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
        } else if (!"mock".equalsIgnoreCase(provider)) {
            throw new BusinessException(ErrorCode.CONFIG_INVALID,
                "Unknown APP_AUTH_SMS_PROVIDER: " + provider);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
