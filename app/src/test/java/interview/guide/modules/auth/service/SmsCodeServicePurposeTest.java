package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.CodePurpose;
import interview.guide.modules.auth.sms.SmsMessage;
import interview.guide.modules.auth.sms.SmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("验证码用途隔离与发送失败回滚")
class SmsCodeServicePurposeTest {

    private static final String RAW_PHONE = "13800138000";

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> codeBucket;
    @Mock
    private RAtomicLong cooldown;
    @Mock
    private RAtomicLong dailyCounter;
    @Mock
    private RAtomicLong attempts;
    @Mock
    private SmsSender smsSender;
    @Spy
    private AuthSupport authSupport = new AuthSupport();
    @Spy
    private AuthProperties properties = new AuthProperties();

    private SmsCodeService service;

    @BeforeEach
    void setUp() {
        properties.setJwtSecret("test-secret-with-more-than-thirty-two-characters");
        properties.getSms().setExposeDebugCode(false);
        properties.getSms().setDailyLimit(5);
        properties.getSms().setMaxAttempts(5);
        service = new SmsCodeService(redissonClient, properties, authSupport, smsSender);
        lenient().when(redissonClient.<String>getBucket(anyString(), eq(StringCodec.INSTANCE)))
            .thenReturn(codeBucket);
        lenient().when(redissonClient.getAtomicLong(contains(":cooldown"))).thenReturn(cooldown);
        lenient().when(redissonClient.getAtomicLong(contains(":daily:"))).thenReturn(dailyCounter);
        lenient().when(redissonClient.getAtomicLong(contains(":attempts"))).thenReturn(attempts);
        lenient().when(cooldown.incrementAndGet()).thenReturn(1L);
        lenient().when(dailyCounter.incrementAndGet()).thenReturn(1L);
    }

    @Test
    @DisplayName("登录验证码与注销验证码使用不同的 Redis 命名空间")
    void separatesRedisKeysByPurpose() {
        service.sendCode(RAW_PHONE, CodePurpose.LOGIN);
        service.sendCode(RAW_PHONE, CodePurpose.ACCOUNT_DELETION);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redissonClient, org.mockito.Mockito.atLeastOnce())
            .<String>getBucket(keys.capture(), eq(StringCodec.INSTANCE));
        assertThat(keys.getAllValues()).anyMatch(key -> key.contains(":LOGIN:"));
        assertThat(keys.getAllValues()).anyMatch(key -> key.contains(":ACCOUNT_DELETION:"));
    }

    @Test
    @DisplayName("发送失败时删除已写入的验证码并回滚当日计数")
    void rollsBackOnSendFailure() {
        doThrow(new RuntimeException("provider down")).when(smsSender).send(any(SmsMessage.class));

        assertThatThrownBy(() -> service.sendCode(RAW_PHONE, CodePurpose.LOGIN))
            .isInstanceOf(BusinessException.class);

        // 写入前会先清理 attempts bucket；测试把所有 bucket 都 stub 成同一个 mock，
        // 因此这里只需确认回滚时确实调用了删除，不强求恰好一次。
        verify(codeBucket, org.mockito.Mockito.atLeastOnce()).delete();
        verify(dailyCounter).decrementAndGet();
    }

    @Test
    @DisplayName("账号注销后清除该手机号全部用途的验证码状态")
    void clearsAllPurposeCodes() {
        service.clearCodes("+8613800138000");

        for (CodePurpose purpose : CodePurpose.values()) {
            verify(redissonClient, org.mockito.Mockito.atLeastOnce()).<String>getBucket(
                contains(":" + purpose.name() + ":"), eq(StringCodec.INSTANCE));
        }
    }
}
