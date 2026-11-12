package interview.guide.modules.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 认证模块配置。
 * <p>
 * 短信通道：mock（本地）/ aliyun（企业主体普通短信）/ pnvs（个人主体短信认证）。
 * 登录提醒已降级为站内通知，不再占用短信模板。微信登录仅预留身份类型，未实现。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String jwtSecret = "";
    private String issuer = "ai-interview-platform";
    private String audience = "ai-interview-web";
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private Duration refreshReuseGrace = Duration.ofSeconds(30);
    private Duration deviceCookieTtl = Duration.ofDays(180);
    private Duration wsTicketTtl = Duration.ofSeconds(60);
    private String bootstrapPhone = "";
    private boolean secureCookies = false;

    private final Sms sms = new Sms();

    @Data
    public static class Sms {
        private String provider = "mock";
        private boolean exposeDebugCode = false;
        private Duration codeTtl = Duration.ofMinutes(5);
        private Duration resendCooldown = Duration.ofSeconds(60);
        private int dailyLimit = 5;
        private int maxAttempts = 5;
        private final Aliyun aliyun = new Aliyun();
        private final Pnvs pnvs = new Pnvs();
    }

    /**
     * 阿里云号码认证服务（短信认证）。个人实名主体可开通，
     * 使用系统赠送签名与模板，支持通过模板参数传入服务端自定义验证码。
     */
    @Data
    public static class Pnvs {
        private String endpoint = "dypnsapi.aliyuncs.com";
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String signName = "";
        private String templateCode = "";
        private String schemeName = "";
        private String countryCode = "86";
        private int templateExpiryMinutes = 5;
        /** 与 SmsCodeService 生成的 6 位验证码一致；官方默认是 4 位。 */
        private int codeLength = 6;
        /** 云端验证码有效期（秒），与 AUTH_SMS_CODE_TTL 对齐。 */
        private int validTimeSeconds = 300;
        /** 云端同号码重发间隔（秒），与 AUTH_SMS_RESEND_COOLDOWN 对齐。 */
        private int intervalSeconds = 60;
    }

    @Data
    public static class Aliyun {
        private String endpoint = "dysmsapi.aliyuncs.com";
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String signName = "";
        private String loginCodeTemplateCode = "";
        private String loginAlertTemplateCode = "";
    }
}
