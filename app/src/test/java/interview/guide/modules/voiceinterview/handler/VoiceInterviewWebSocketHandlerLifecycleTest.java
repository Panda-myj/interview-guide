package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.service.VoiceSessionAuditLogger;
import interview.guide.modules.voiceinterview.service.VoiceSessionFinalizer;
import interview.guide.modules.voiceinterview.service.VoiceSessionLeaseService;
import interview.guide.modules.voiceinterview.service.VoiceSessionLeaseService.LeaseAttempt;
import interview.guide.modules.voiceinterview.service.VoiceSessionLeaseService.LeaseSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音 WebSocket 生命周期测试（VOICE-001）。
 *
 * <p>全部使用 Mock WebSocket/ASR/TTS/Redis，不启动真实容器，也不依赖真实语音服务。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音 WebSocket 生命周期")
class VoiceInterviewWebSocketHandlerLifecycleTest {

    private static final String SESSION_ID = "42";
    private static final String PATH = "ws://localhost/ws/voice-interview/" + SESSION_ID;

    @Mock
    private QwenAsrService sttService;
    @Mock
    private QwenTtsService ttsService;
    @Mock
    private DashscopeLlmService llmService;
    @Mock
    private VoiceInterviewService interviewService;
    @Mock
    private VoiceSessionLeaseService leaseService;
    @Mock
    private VoiceSessionFinalizer sessionFinalizer;
    @Mock
    private VoiceSessionAuditLogger auditLogger;
    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;
    @Mock
    private WebSocketSession session;

    private VoiceInterviewProperties properties;
    private VoiceInterviewWebSocketHandler handler;
    private final List<String> sentPayloads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        properties = new VoiceInterviewProperties();
        handler = new VoiceInterviewWebSocketHandler(
            new ObjectMapper(),
            sttService,
            ttsService,
            llmService,
            interviewService,
            leaseService,
            sessionFinalizer,
            auditLogger,
            properties,
            meterRegistryProvider
        );

        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        when(session.getUri()).thenReturn(URI.create(PATH));
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            WebSocketMessage<?> message = invocation.getArgument(0);
            if (message instanceof TextMessage textMessage) {
                sentPayloads.add(textMessage.getPayload());
            }
            return null;
        }).when(session).sendMessage(any());
        when(leaseService.acquire(anyString(), anyString())).thenReturn(grantedLease());
        when(leaseService.renew(anyString(), anyString(), anyLong())).thenReturn(true);
        when(leaseService.isLeaseHolder(anyString(), anyString(), anyLong())).thenReturn(true);
        when(interviewService.getConversationHistory(SESSION_ID)).thenReturn(List.of(existingMessage()));
    }

    @AfterEach
    void tearDown() {
        handler.destroy();
    }

    @Nested
    @DisplayName("连接与租约")
    class Connection {

        @Test
        @DisplayName("重复连接被拒绝且不触发收尾")
        void duplicateConnectionShouldBeRejected() throws Exception {
            when(leaseService.acquire(anyString(), anyString()))
                .thenReturn(LeaseAttempt.rejected(new LeaseSnapshot("other-conn", 1L, 0L, 0L)));

            handler.afterConnectionEstablished(session);

            verify(session).close(CloseStatus.POLICY_VIOLATION);
            verify(sessionFinalizer, never()).finalizeSession(anyString(), anyString(), any());
            verify(auditLogger).record(eq(SESSION_ID),
                eq(VoiceSessionAuditLogger.Event.CONNECTION_REJECTED_DUPLICATE), anyString(),
                contains("holder=other-conn"));
        }

        @Test
        @DisplayName("正常连接申请租约并记录审计")
        void normalConnectionShouldAcquireLease() throws Exception {
            handler.afterConnectionEstablished(session);

            verify(leaseService).acquire(eq(SESSION_ID), anyString());
            verify(auditLogger).record(eq(SESSION_ID),
                eq(VoiceSessionAuditLogger.Event.SESSION_CONNECTED), anyString(), eq("ok"));
            assertThat(sessionStates()).containsKey(SESSION_ID);
        }

        @Test
        @DisplayName("旧连接关闭时不影响已被新连接接管的会话")
        void staleConnectionCloseShouldNotFinalize() throws Exception {
            handler.afterConnectionEstablished(session);
            when(leaseService.isLeaseHolder(anyString(), anyString(), anyLong())).thenReturn(false);

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            verify(sttService, never()).stopTranscription(SESSION_ID);
            verify(sessionFinalizer, never()).finalizeSession(anyString(), anyString(), any());
            verify(auditLogger).record(eq(SESSION_ID),
                eq(VoiceSessionAuditLogger.Event.CONNECTION_CLOSED_STALE), anyString(), anyString());
        }

        @Test
        @DisplayName("持有者正常关闭时停 ASR、释放租约并收尾")
        void holderCloseShouldFinalize() throws Exception {
            handler.afterConnectionEstablished(session);

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            verify(sttService).stopTranscription(SESSION_ID);
            verify(leaseService).release(eq(SESSION_ID), anyString(), anyLong());
            verify(sessionFinalizer).finalizeSession(eq(SESSION_ID), eq("websocket_closed"), anyString());
        }
    }

    @Nested
    @DisplayName("心跳与租约续期")
    class Heartbeat {

        @Test
        @DisplayName("心跳超时主动断开并收尾")
        void heartbeatTimeoutShouldFinalize() throws Exception {
            handler.afterConnectionEstablished(session);
            forceIdle(SESSION_ID, 120_000L);

            handler.heartbeatTick();

            verify(sessionFinalizer).finalizeSession(eq(SESSION_ID), eq("heartbeat_timeout"), anyString());
            verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
        }

        @Test
        @DisplayName("续期失败视为租约丢失并收尾")
        void leaseLossShouldFinalize() throws Exception {
            handler.afterConnectionEstablished(session);
            when(leaseService.renew(anyString(), anyString(), anyLong())).thenReturn(false);

            handler.heartbeatTick();

            verify(sessionFinalizer).finalizeSession(eq(SESSION_ID), eq("lease_lost"), anyString());
            verify(auditLogger).record(eq(SESSION_ID), eq(VoiceSessionAuditLogger.Event.LEASE_LOST),
                anyString(), eq("renew_rejected"));
        }

        @Test
        @DisplayName("客户端 pong 刷新心跳，避免被误判超时")
        void pongShouldRefreshHeartbeat() throws Exception {
            handler.afterConnectionEstablished(session);
            forceIdle(SESSION_ID, 120_000L);

            handler.handleTextMessage(session, new TextMessage("{\"type\":\"control\",\"action\":\"pong\"}"));
            handler.heartbeatTick();

            verify(sessionFinalizer, never()).finalizeSession(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("心跳周期内会下发 ping 控制帧")
        void heartbeatShouldSendPing() throws Exception {
            handler.afterConnectionEstablished(session);
            properties.getLifecycle().setHeartbeatIntervalSeconds(1);

            handler.heartbeatTick();

            assertThat(sentPayloads).anyMatch(payload -> payload.contains("\"action\":\"ping\""));
        }
    }

    @Nested
    @DisplayName("异常帧与降级")
    class FaultTolerance {

        @Test
        @DisplayName("坏帧只回错误，不破坏会话状态")
        void malformedFrameShouldNotBreakSession() throws Exception {
            handler.afterConnectionEstablished(session);

            handler.handleTextMessage(session, new TextMessage("not-a-json"));

            assertThat(sessionStates()).containsKey(SESSION_ID);
            verify(sessionFinalizer, never()).finalizeSession(anyString(), anyString(), any());
            assertThat(sentPayloads).anyMatch(payload -> payload.contains("\"type\":\"error\""));
        }

        @Test
        @DisplayName("协议异常超过上限时断开连接")
        void tooManyViolationsShouldClose() throws Exception {
            properties.getLifecycle().setMaxProtocolViolations(1);
            handler.afterConnectionEstablished(session);

            handler.handleTextMessage(session, new TextMessage("{\"foo\":1}"));

            verify(sessionFinalizer).finalizeSession(eq(SESSION_ID), eq("protocol_violation"), anyString());
            verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
        }

        @Test
        @DisplayName("ASR 连续失败超过上限后降级并提示改用文字提交")
        @SuppressWarnings("unchecked")
        void asrFailureShouldDegradeAfterRetries() throws Exception {
            properties.getLifecycle().setMaxAsrRestarts(1);
            handler.afterConnectionEstablished(session);

            ArgumentCaptor<Consumer<Throwable>> errorCaptor = ArgumentCaptor.forClass(Consumer.class);
            verify(sttService).startTranscription(eq(SESSION_ID), any(), any(), any(), errorCaptor.capture());

            errorCaptor.getValue().accept(new IllegalStateException("asr down"));
            errorCaptor.getValue().accept(new IllegalStateException("asr down"));

            verify(auditLogger).record(eq(SESSION_ID), eq(VoiceSessionAuditLogger.Event.ASR_UNAVAILABLE),
                anyString(), contains("restarts_exhausted"));
            assertThat(sentPayloads).anyMatch(payload -> payload.contains("asr_unavailable"));
        }
    }

    private LeaseAttempt grantedLease() {
        long now = System.currentTimeMillis();
        return LeaseAttempt.granted(new LeaseSnapshot("conn-a", 1L, now + 60_000L, now));
    }

    private VoiceInterviewMessageEntity existingMessage() {
        return VoiceInterviewMessageEntity.builder()
            .sessionId(Long.parseLong(SESSION_ID))
            .messageType("DIALOGUE")
            .aiGeneratedText("请介绍一下你的项目")
            .sequenceNum(1)
            .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionStates() throws Exception {
        Field field = VoiceInterviewWebSocketHandler.class.getDeclaredField("sessionStates");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(handler);
    }

    private void forceIdle(String sessionId, long idleMillis) throws Exception {
        Object state = sessionStates().get(sessionId);
        Field field = state.getClass().getDeclaredField("lastInboundAt");
        field.setAccessible(true);
        ((AtomicLong) field.get(state)).set(System.currentTimeMillis() - idleMillis);
    }
}
