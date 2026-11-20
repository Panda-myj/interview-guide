package interview.guide.modules.voiceinterview.config;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.security.AuthCookieService;
import interview.guide.modules.auth.service.AuthSupport;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceInterviewHandshakeInterceptor implements HandshakeInterceptor {

    private final RedisService redisService;
    private final AuthSupport authSupport;
    private final VoiceInterviewSessionRepository sessionRepository;

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) {
        String ticket = readCookie(request, AuthCookieService.WS_TICKET_COOKIE);
        String path = request.getURI().getPath();
        String sessionId = path.substring(path.lastIndexOf('/') + 1);
        if (ticket == null || ticket.isBlank()) {
            log.warn("WS handshake rejected: no ws_ticket cookie, origin={}, path={}",
                request.getHeaders().getFirst("Origin"), path);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String ticketKey = "auth:ws-ticket:" + authSupport.sha256(ticket);
        Object value;
        try {
            value = redisService.get(ticketKey);
        } catch (Exception e) {
            // Redis 编码/连接失败不能让握手静默挂起，必须明确拒绝并记录原因
            log.error("WS handshake failed reading ticket from Redis: {}", e.getMessage(), e);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        redisService.delete(ticketKey);
        if (value == null) {
            log.warn("WS handshake rejected: ticket missing/expired or read with wrong codec, sessionId={}", sessionId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String[] parts = value.toString().split(":", 2);
        if (parts.length != 2 || !parts[1].equals(sessionId)) {
            log.warn("WS handshake rejected: ticket session mismatch, expected={}, actual={}", sessionId,
                parts.length == 2 ? parts[1] : "invalid");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        long userId;
        long voiceSessionId;
        try {
            userId = Long.parseLong(parts[0]);
            voiceSessionId = Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        if (sessionRepository.findByIdAndOwnerUserId(voiceSessionId, userId).isEmpty()) {
            log.warn("WS handshake rejected: session {} not owned by user {}", voiceSessionId, userId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        log.info("WS handshake accepted: sessionId={}, userId={}", sessionId, userId);
        attributes.put("userId", userId);
        attributes.put("sessionId", sessionId);
        return true;
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception
    ) {
        // No-op.
    }

    private String readCookie(ServerHttpRequest request, String name) {
        for (String header : request.getHeaders().getOrEmpty("Cookie")) {
            for (String cookie : header.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && name.equals(pair[0])) {
                    return pair[1];
                }
            }
        }
        return null;
    }
}
