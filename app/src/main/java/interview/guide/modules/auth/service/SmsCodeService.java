package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.CodePurpose;
import interview.guide.modules.auth.sms.SmsMessage;
import interview.guide.modules.auth.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * 验证码服务。所有 provider（mock / aliyun / pnvs）共用本地生成与哈希校验链路：
 * PNVS 短信认证使用服务端自定义验证码时，云端 CheckSmsVerifyCode 不参与校验，
 * 因此这里仍是验证码的唯一生成与比对点。
 *
 * <p>用途（{@link CodePurpose}）参与 Redis key 命名空间，登录码与注销码互不可复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "auth:otp:";

    private final RedissonClient redissonClient;
    private final AuthProperties authProperties;
    private final AuthSupport authSupport;
    private final SmsSender smsSender;

    public SendCodeResult sendLoginCode(String rawPhone) {
        return sendCode(rawPhone, CodePurpose.LOGIN);
    }

    public String verifyLoginCode(String rawPhone, String code) {
        return verifyCode(rawPhone, code, CodePurpose.LOGIN);
    }

    public SendCodeResult sendCode(String rawPhone, CodePurpose purpose) {
        String phone = authSupport.normalizePhone(rawPhone);
        String scope = scope(phone, purpose);
        String cooldownKey = scope + ":cooldown";
        acquiredCooldown(cooldownKey);

        String dailyKey = scope + ":daily:" + LocalDate.now();
        RAtomicLong dailyCounter = redissonClient.getAtomicLong(dailyKey);
        long dailyCount = dailyCounter.incrementAndGet();
        if (dailyCount == 1) {
            dailyCounter.expire(Duration.ofDays(1));
        }
        if (dailyCount > authProperties.getSms().getDailyLimit()) {
            throw new BusinessException(ErrorCode.AUTH_SMS_DAILY_LIMIT);
        }

        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        String codeHash = authSupport.hashSensitiveValue(phone + ":" + code, authProperties.getJwtSecret());
        RBucket<String> codeBucket = redissonClient.getBucket(codeKey(phone, purpose), StringCodec.INSTANCE);
        codeBucket.set(codeHash, authProperties.getSms().getCodeTtl());
        redissonClient.getBucket(attemptKey(phone, purpose), StringCodec.INSTANCE).delete();

        try {
            smsSender.send(new SmsMessage(
                phone,
                authProperties.getSms().getAliyun().getLoginCodeTemplateCode(),
                Map.of("code", code)
            ));
        } catch (RuntimeException e) {
            codeBucket.delete();
            dailyCounter.decrementAndGet();
            log.warn("Failed to send code: phone={}, purpose={}",
                authSupport.maskPhone(phone), purpose, e);
            throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED, "验证码发送失败，请稍后重试", e);
        }

        String debugCode = authProperties.getSms().isExposeDebugCode() ? code : null;
        return new SendCodeResult(phone, debugCode);
    }

    public String verifyCode(String rawPhone, String code, CodePurpose purpose) {
        String phone = authSupport.normalizePhone(rawPhone);
        RBucket<String> codeBucket = redissonClient.getBucket(codeKey(phone, purpose), StringCodec.INSTANCE);
        String expectedHash = codeBucket.get();
        if (expectedHash == null || code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_SMS_CODE_INVALID);
        }

        String actualHash = authSupport.hashSensitiveValue(phone + ":" + code.trim(), authProperties.getJwtSecret());
        if (!MessageDigest.isEqual(
            expectedHash.getBytes(StandardCharsets.UTF_8),
            actualHash.getBytes(StandardCharsets.UTF_8)
        )) {
            RAtomicLong attempts = redissonClient.getAtomicLong(attemptKey(phone, purpose));
            long count = attempts.incrementAndGet();
            if (count == 1) {
                attempts.expire(authProperties.getSms().getCodeTtl());
            }
            if (count >= authProperties.getSms().getMaxAttempts()) {
                codeBucket.delete();
                redissonClient.getBucket(attemptKey(phone, purpose), StringCodec.INSTANCE).delete();
                throw new BusinessException(ErrorCode.AUTH_SMS_CODE_ATTEMPTS_EXCEEDED);
            }
            throw new BusinessException(ErrorCode.AUTH_SMS_CODE_INVALID);
        }

        codeBucket.delete();
        redissonClient.getBucket(attemptKey(phone, purpose), StringCodec.INSTANCE).delete();
        return phone;
    }

    /** 清除某手机号全部验证码状态，供账号注销后释放手机号使用。 */
    public void clearCodes(String normalizedPhone) {
        for (CodePurpose purpose : CodePurpose.values()) {
            redissonClient.getBucket(codeKey(normalizedPhone, purpose), StringCodec.INSTANCE).delete();
            redissonClient.getBucket(attemptKey(normalizedPhone, purpose), StringCodec.INSTANCE).delete();
            redissonClient.getAtomicLong(scope(normalizedPhone, purpose) + ":cooldown").delete();
            redissonClient.getAtomicLong(
                scope(normalizedPhone, purpose) + ":daily:" + LocalDate.now()).delete();
        }
    }

    private void acquiredCooldown(String key) {
        RAtomicLong cooldown = redissonClient.getAtomicLong(key);
        long value = cooldown.incrementAndGet();
        if (value == 1) {
            cooldown.expire(authProperties.getSms().getResendCooldown());
            return;
        }
        if (cooldown.remainTimeToLive() < 0) {
            cooldown.set(1);
            cooldown.expire(authProperties.getSms().getResendCooldown());
            return;
        }
        throw new BusinessException(ErrorCode.AUTH_SMS_SEND_TOO_FREQUENT);
    }

    private String scope(String phone, CodePurpose purpose) {
        return KEY_PREFIX + purpose.name() + ":" + phone;
    }

    private String codeKey(String phone, CodePurpose purpose) {
        return scope(phone, purpose) + ":code";
    }

    private String attemptKey(String phone, CodePurpose purpose) {
        return scope(phone, purpose) + ":attempts";
    }

    public record SendCodeResult(String normalizedPhone, String debugCode) {
    }
}
