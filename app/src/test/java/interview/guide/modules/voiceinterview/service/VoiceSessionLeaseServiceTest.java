package interview.guide.modules.voiceinterview.service;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 语音会话租约测试（VOICE-001），不依赖真实 Redis。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音会话租约")
class VoiceSessionLeaseServiceTest {

    private static final long TTL_MILLIS = 45_000L;

    @Mock
    private RedisService redisService;

    private VoiceInterviewProperties properties;
    private MutableClock clock;
    private Map<String, String> redisStore;
    private VoiceSessionLeaseService leaseService;

    @BeforeEach
    void setUp() {
        properties = new VoiceInterviewProperties();
        properties.getLifecycle().setLeaseTtlSeconds(45);
        properties.getLifecycle().setDuplicateConnectionWindowSeconds(5);
        clock = new MutableClock(1_700_000_000_000L);
        redisStore = new ConcurrentHashMap<>();

        when(redisService.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            // Mockito 的 getArgument 是泛型方法：直接传给 String.valueOf 会被重载解析成
            // valueOf(char[]) 并在运行时抛 ClassCastException，写入会被误判为 Redis 降级。
            // 因此显式按 Object/String 取值。
            String key = invocation.<String>getArgument(0);
            Object value = invocation.<Object>getArgument(1);
            redisStore.put(key, String.valueOf(value));
            return null;
        }).when(redisService).set(anyString(), any(), any(Duration.class));
        doAnswer(invocation -> redisStore.remove(invocation.getArgument(0)))
            .when(redisService).delete(anyString());

        leaseService = new VoiceSessionLeaseService(redisService, properties, clock);
    }

    @Nested
    @DisplayName("申请租约")
    class Acquire {

        @Test
        @DisplayName("首次申请成功并写入带 TTL 的租约")
        void firstAcquireShouldGrant() {
            VoiceSessionLeaseService.LeaseAttempt attempt = leaseService.acquire("42", "conn-a");

            assertThat(attempt.granted()).isTrue();
            assertThat(attempt.degraded()).isFalse();
            assertThat(attempt.lease().connectionId()).isEqualTo("conn-a");
            assertThat(attempt.lease().fencingToken()).isEqualTo(1L);
            assertThat(redisStore).containsKey("voice:interview:lease:42");
        }

        @Test
        @DisplayName("重复连接窗口内第二个连接被拒绝")
        void duplicateConnectionWithinWindowShouldBeRejected() {
            leaseService.acquire("42", "conn-a");
            clock.advanceMillis(1_000L);

            VoiceSessionLeaseService.LeaseAttempt second = leaseService.acquire("42", "conn-b");

            assertThat(second.granted()).isFalse();
            assertThat(second.rejected()).isTrue();
            assertThat(second.lease().connectionId()).isEqualTo("conn-a");
        }

        @Test
        @DisplayName("窗口外的连接视为重连并接管，fencing token 递增")
        void acquireOutsideWindowShouldTakeOver() {
            leaseService.acquire("42", "conn-a");
            clock.advanceMillis(6_000L);

            VoiceSessionLeaseService.LeaseAttempt takeover = leaseService.acquire("42", "conn-b");

            assertThat(takeover.granted()).isTrue();
            assertThat(takeover.lease().connectionId()).isEqualTo("conn-b");
            assertThat(takeover.lease().fencingToken()).isEqualTo(2L);
        }

        @Test
        @DisplayName("租约过期后允许新的连接接管")
        void expiredLeaseShouldAllowTakeover() {
            leaseService.acquire("42", "conn-a");
            clock.advanceMillis(TTL_MILLIS + 1);

            VoiceSessionLeaseService.LeaseAttempt takeover = leaseService.acquire("42", "conn-b");

            assertThat(takeover.granted()).isTrue();
            assertThat(takeover.lease().fencingToken()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("续期与释放")
    class RenewAndRelease {

        @Test
        @DisplayName("持有者续期成功并延后过期时间")
        void holderShouldRenew() {
            VoiceSessionLeaseService.LeaseSnapshot lease = leaseService.acquire("42", "conn-a").lease();
            clock.advanceMillis(20_000L);

            boolean renewed = leaseService.renew("42", "conn-a", lease.fencingToken());

            assertThat(renewed).isTrue();
            assertThat(leaseService.inspect("42").orElseThrow().expiresAt())
                .isEqualTo(clock.millis() + TTL_MILLIS);
        }

        @Test
        @DisplayName("旧连接的续期被拒绝，避免双写")
        void staleConnectionRenewShouldFail() {
            leaseService.acquire("42", "conn-a");
            clock.advanceMillis(6_000L);
            VoiceSessionLeaseService.LeaseSnapshot takeover = leaseService.acquire("42", "conn-b").lease();

            boolean staleRenewed = leaseService.renew("42", "conn-a", 1L);

            assertThat(staleRenewed).isFalse();
            assertThat(leaseService.isLeaseHolder("42", "conn-b", takeover.fencingToken())).isTrue();
        }

        @Test
        @DisplayName("租约丢失后持有者可重建，不中断进行中的会话")
        void renewShouldRebuildLostLease() {
            VoiceSessionLeaseService.LeaseSnapshot lease = leaseService.acquire("42", "conn-a").lease();
            redisStore.clear();

            boolean renewed = leaseService.renew("42", "conn-a", lease.fencingToken());

            assertThat(renewed).isTrue();
            assertThat(leaseService.hasActiveLease("42")).isTrue();
            assertThat(leaseService.inspect("42").orElseThrow().fencingToken())
                .isEqualTo(lease.fencingToken());
        }

        @Test
        @DisplayName("只有当前持有者可以释放租约")
        void releaseShouldRequireHolder() {
            leaseService.acquire("42", "conn-a");
            clock.advanceMillis(6_000L);
            VoiceSessionLeaseService.LeaseSnapshot takeover = leaseService.acquire("42", "conn-b").lease();

            boolean releasedByStale = leaseService.release("42", "conn-a", 1L);

            assertThat(releasedByStale).isFalse();
            assertThat(leaseService.hasActiveLease("42")).isTrue();

            assertThat(leaseService.release("42", "conn-b", takeover.fencingToken())).isTrue();
            assertThat(redisStore).doesNotContainKey("voice:interview:lease:42");
        }
    }

    @Nested
    @DisplayName("Redis 故障降级")
    class Degradation {

        @Test
        @DisplayName("Redis 不可用时降级为进程内租约，会话不被中断")
        void redisFailureShouldDegradeToLocalLease() {
            when(redisService.get(anyString())).thenThrow(new IllegalStateException("redis down"));
            doAnswer(invocation -> {
                throw new IllegalStateException("redis down");
            }).when(redisService).set(anyString(), any(), any(Duration.class));

            VoiceSessionLeaseService.LeaseAttempt attempt = leaseService.acquire("42", "conn-a");

            assertThat(attempt.granted()).isTrue();
            assertThat(attempt.degraded()).isTrue();
            assertThat(leaseService.isDegraded()).isTrue();
            assertThat(leaseService.isLeaseHolder("42", "conn-a", attempt.lease().fencingToken())).isTrue();
            assertThat(leaseService.renew("42", "conn-a", attempt.lease().fencingToken())).isTrue();
        }
    }

    /**
     * 可推进的测试时钟，避免依赖 sleep。
     */
    private static final class MutableClock extends Clock {

        private long currentMillis;

        private MutableClock(long currentMillis) {
            this.currentMillis = currentMillis;
        }

        void advanceMillis(long millis) {
            currentMillis += millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }

        @Override
        public long millis() {
            return currentMillis;
        }
    }
}
