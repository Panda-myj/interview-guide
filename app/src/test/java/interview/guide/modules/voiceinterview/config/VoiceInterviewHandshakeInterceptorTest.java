package interview.guide.modules.voiceinterview.config;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.service.AuthSupport;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试 WebSocket 握手测试")
class VoiceInterviewHandshakeInterceptorTest {

    @Mock
    private RedisService redisService;
    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Test
    @DisplayName("有效一次性凭证只允许连接所属语音会话")
    void acceptsOwnedSessionAndConsumesTicket() {
        AuthSupport authSupport = new AuthSupport();
        VoiceInterviewHandshakeInterceptor interceptor = new VoiceInterviewHandshakeInterceptor(
            redisService, authSupport, sessionRepository
        );
        String ticket = "one-time-ticket";
        String key = "auth:ws-ticket:" + authSupport.sha256(ticket);
        when(redisService.get(key)).thenReturn("11:42");
        when(sessionRepository.findByIdAndOwnerUserId(42L, 11L))
            .thenReturn(Optional.of(VoiceInterviewSessionEntity.builder().id(42L).build()));

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/ws/voice-interview/42"));
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", "ws_ticket=" + ticket);
        when(request.getHeaders()).thenReturn(headers);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
            request, response, mock(WebSocketHandler.class), attributes
        );

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("userId", 11L).containsEntry("sessionId", "42");
        verify(redisService).delete(key);
    }

    @Test
    @DisplayName("缺少凭证时拒绝 WebSocket 握手")
    void rejectsMissingTicket() {
        VoiceInterviewHandshakeInterceptor interceptor = new VoiceInterviewHandshakeInterceptor(
            redisService, new AuthSupport(), sessionRepository
        );
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/ws/voice-interview/42"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        AtomicReference<HttpStatusCode> status = new AtomicReference<>();
        doAnswer(invocation -> {
            status.set(invocation.getArgument(0));
            return null;
        }).when(response).setStatusCode(org.mockito.ArgumentMatchers.any());

        boolean accepted = interceptor.beforeHandshake(
            request,
            response,
            mock(WebSocketHandler.class),
            new HashMap<>()
        );

        assertThat(accepted).isFalse();
        assertThat(status.get()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
