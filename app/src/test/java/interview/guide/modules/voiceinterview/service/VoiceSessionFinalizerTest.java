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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音会话收尾与补偿测试（VOICE-001）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音会话收尾")
class VoiceSessionFinalizerTest {

    @Mock
    private VoiceInterviewService voiceInterviewService;
    @Mock
    private RedisService redisService;
    @Mock
    private VoiceSessionAuditLogger auditLogger;

    private VoiceInterviewProperties properties;
    private VoiceSessionFinalizer finalizer;

    @BeforeEach
    void setUp() {
        properties = new VoiceInterviewProperties();
        properties.getLifecycle().setFinalizeMaxAttempts(3);
        finalizer = new VoiceSessionFinalizer(voiceInterviewService, redisService, auditLogger, properties);
    }

    @Nested
    @DisplayName("首次收尾")
    class FirstAttempt {

        @Test
        @DisplayName("收尾成功时记录审计且不进入补偿队列")
        void successShouldAuditWithoutRetry() {
            boolean finalized = finalizer.finalizeSession("42", "websocket_closed", "conn-a");

            assertThat(finalized).isTrue();
            verify(voiceInterviewService).endSessionIfInProgress("42");
            verify(auditLogger).record(eq("42"), eq(VoiceSessionAuditLogger.Event.SESSION_FINALIZED),
                eq("conn-a"), eq("websocket_closed"));
            verify(redisService, never()).listRightPush(eq(VoiceSessionFinalizer.RETRY_KEY), any());
        }

        @Test
        @DisplayName("数据库异常时进入补偿队列并返回失败")
        void failureShouldEnqueueRetry() {
            doThrow(new IllegalStateException("db down"))
                .when(voiceInterviewService).endSessionIfInProgress("42");

            boolean finalized = finalizer.finalizeSession("42", "heartbeat_timeout", "conn-a");

            assertThat(finalized).isFalse();
            verify(redisService).listRightPush(eq(VoiceSessionFinalizer.RETRY_KEY),
                argThat(value -> value.toString().startsWith("42|heartbeat_timeout|conn-a|1")));
            verify(auditLogger).record(eq("42"), eq(VoiceSessionAuditLogger.Event.SESSION_FINALIZE_RETRY),
                eq("conn-a"), contains("IllegalStateException"));
        }

        @Test
        @DisplayName("会话 ID 为空时直接返回失败，不触发任何副作用")
        void blankSessionShouldBeIgnored() {
            assertThat(finalizer.finalizeSession(" ", "websocket_closed", "conn-a")).isFalse();

            verify(voiceInterviewService, never()).endSessionIfInProgress(anyString());
            verify(redisService, never()).listRightPush(anyString(), any());
        }
    }

    @Nested
    @DisplayName("补偿重试")
    class Retry {

        @Test
        @DisplayName("补偿重试成功后清空队列")
        void retrySuccessShouldClearQueue() {
            when(redisService.listGetAll(VoiceSessionFinalizer.RETRY_KEY))
                .thenReturn(List.of("42|websocket_closed|conn-a|1"));

            int remaining = finalizer.retryPendingFinalizations();

            assertThat(remaining).isZero();
            verify(voiceInterviewService).endSessionIfInProgress("42");
            verify(redisService).delete(VoiceSessionFinalizer.RETRY_KEY);
            verify(redisService, never()).listRightPush(eq(VoiceSessionFinalizer.RETRY_KEY), any());
        }

        @Test
        @DisplayName("补偿重试仍失败时累加次数并保留在队列")
        void retryFailureShouldKeepTask() {
            when(redisService.listGetAll(VoiceSessionFinalizer.RETRY_KEY))
                .thenReturn(List.of("42|websocket_closed|conn-a|1"));
            doThrow(new IllegalStateException("db down"))
                .when(voiceInterviewService).endSessionIfInProgress("42");

            int remaining = finalizer.retryPendingFinalizations();

            assertThat(remaining).isEqualTo(1);
            verify(redisService).listRightPush(eq(VoiceSessionFinalizer.RETRY_KEY),
                argThat(value -> value.toString().endsWith("|2")));
        }

        @Test
        @DisplayName("超过重试上限进入死信队列")
        void retryExhaustedShouldMoveToDeadLetter() {
            when(redisService.listGetAll(VoiceSessionFinalizer.RETRY_KEY))
                .thenReturn(List.of("42|heartbeat_timeout|conn-a|3"));
            doThrow(new IllegalStateException("db down"))
                .when(voiceInterviewService).endSessionIfInProgress("42");

            finalizer.retryPendingFinalizations();

            verify(redisService).listRightPush(eq(VoiceSessionFinalizer.DEAD_LETTER_KEY),
                argThat(value -> value.toString().startsWith("42|heartbeat_timeout|conn-a|4")));
            verify(auditLogger).record(eq("42"),
                eq(VoiceSessionAuditLogger.Event.SESSION_FINALIZE_DEAD_LETTER), eq("conn-a"), eq("attempts=4"));
        }

        @Test
        @DisplayName("补偿队列不可读时不抛异常")
        void queueFailureShouldNotThrow() {
            when(redisService.listGetAll(VoiceSessionFinalizer.RETRY_KEY))
                .thenThrow(new IllegalStateException("redis down"));

            assertThat(finalizer.retryPendingFinalizations()).isZero();
        }

        @Test
        @DisplayName("可查询补偿队列与死信队列积压量")
        void queueCountsShouldBeQueryable() {
            when(redisService.listGetAll(VoiceSessionFinalizer.RETRY_KEY))
                .thenReturn(List.of("1|websocket_closed|conn-a|1"));
            when(redisService.listGetAll(VoiceSessionFinalizer.DEAD_LETTER_KEY))
                .thenReturn(List.of("2|heartbeat_timeout|conn-b|4"));

            assertThat(finalizer.pendingRetryCount()).isEqualTo(1);
            assertThat(finalizer.deadLetterCount()).isEqualTo(1);
        }
    }
}
