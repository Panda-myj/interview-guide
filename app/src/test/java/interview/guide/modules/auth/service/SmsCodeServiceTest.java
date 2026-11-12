package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.config.AuthProperties;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("短信验证码服务测试")
class SmsCodeServiceTest {

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
        properties.getSms().setExposeDebugCode(true);
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
    @DisplayName("发送验证码时只保存哈希，并在 Mock 模式返回调试验证码")
    void sendsAndStoresHashedCode() {
        SmsCodeService.SendCodeResult result = service.sendLoginCode("13800138000");

        ArgumentCaptor<SmsMessage> message = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsSender).send(message.capture());
        String code = message.getValue().templateParams().get("code");
        assertThat(code).matches("\\d{6}");
        assertThat(result.debugCode()).isEqualTo(code);
        assertThat(message.getValue().phone()).isEqualTo("+8613800138000");
        verify(codeBucket).set(
            authSupport.hashSensitiveValue(
                "+8613800138000:" + code,
                properties.getJwtSecret()
            ),
            properties.getSms().getCodeTtl()
        );
    }

    @Test
    @DisplayName("重发冷却期间拒绝再次发送短信")
    void rejectsResendDuringCooldown() {
        when(cooldown.incrementAndGet()).thenReturn(2L);
        when(cooldown.remainTimeToLive()).thenReturn(30_000L);

        assertThatThrownBy(() -> service.sendLoginCode("13800138000"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("频繁");
        verify(smsSender, never()).send(any());
    }

    @Test
    @DisplayName("正确验证码验证后立即失效")
    void verifiesAndConsumesCode() {
        SmsCodeService.SendCodeResult sent = service.sendLoginCode("13800138000");
        String expectedHash = authSupport.hashSensitiveValue(
            "+8613800138000:" + sent.debugCode(),
            properties.getJwtSecret()
        );
        when(codeBucket.get()).thenReturn(expectedHash);

        String phone = service.verifyLoginCode("13800138000", sent.debugCode());

        assertThat(phone).isEqualTo("+8613800138000");
        verify(codeBucket, atLeastOnce()).delete();
    }
}
