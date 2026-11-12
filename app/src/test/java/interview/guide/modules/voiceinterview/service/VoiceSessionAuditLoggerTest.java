package interview.guide.modules.voiceinterview.service;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音会话审计日志测试（VOICE-001）：只记录事件元数据，Redis 故障不阻断主流程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音会话审计日志")
class VoiceSessionAuditLoggerTest {

    @Mock
    private RedisService redisService;

    private VoiceSessionAuditLogger auditLogger;

    @BeforeEach
    void setUp() {
        auditLogger = new VoiceSessionAuditLogger(redisService, new VoiceInterviewProperties());
    }

    @Test
    @DisplayName("审计事件写入 Redis 列表并设置保留期")
    void recordShouldPersistWithTtl() {
        auditLogger.record("42", VoiceSessionAuditLogger.Event.SESSION_CONNECTED, "conn-a", "ok");

        ArgumentCaptor<String> lineCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisService).listRightPush(eq("voice:interview:audit:42"), lineCaptor.capture());
        verify(redisService).expire(eq("voice:interview:audit:42"), any(Duration.class));

        String line = lineCaptor.getValue();
        assertThat(line).contains("event=SESSION_CONNECTED", "sessionId=42", "connectionId=conn-a");
        assertThat(line).doesNotContain("audio", "base64", "pcm");
    }

    @Test
    @DisplayName("原因中的换行被压平且超长内容被截断")
    void reasonShouldBeSanitized() {
        String longReason = "line1\nline2\n" + "x".repeat(400);

        auditLogger.record("42", VoiceSessionAuditLogger.Event.PROTOCOL_VIOLATION, "conn-a", longReason);

        ArgumentCaptor<String> lineCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisService).listRightPush(anyString(), lineCaptor.capture());
        String line = lineCaptor.getValue();
        assertThat(line).doesNotContain("\n");
        assertThat(line.length()).isLessThan(320);
    }

    @Test
    @DisplayName("Redis 写入失败只记录告警，不向调用方抛异常")
    void redisFailureShouldNotBreakCaller() {
        doThrow(new IllegalStateException("redis down"))
            .when(redisService).listRightPush(anyString(), any());

        assertThatCode(() -> auditLogger.record("42", VoiceSessionAuditLogger.Event.HEARTBEAT_TIMEOUT,
            "conn-a", "idleMs=61000")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("读取审计事件失败时返回空列表")
    void recentEventsShouldReturnEmptyOnFailure() {
        when(redisService.listGetAll("voice:interview:audit:42"))
            .thenThrow(new IllegalStateException("redis down"));

        assertThat(auditLogger.recentEvents("42")).isEmpty();
    }

    @Test
    @DisplayName("可读取已写入的审计事件")
    void recentEventsShouldReturnPersistedLines() {
        when(redisService.listGetAll("voice:interview:audit:42"))
            .thenReturn(List.of("event=SESSION_CONNECTED sessionId=42"));

        assertThat(auditLogger.recentEvents("42"))
            .containsExactly("event=SESSION_CONNECTED sessionId=42");
    }

    @Test
    @DisplayName("空会话 ID 不写入任何审计")
    void blankSessionShouldBeIgnoredOnRead() {
        assertThat(auditLogger.recentEvents(" ")).isEmpty();
        verify(redisService, times(0)).listGetAll(anyString());
    }
}
