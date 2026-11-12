package interview.guide.modules.voiceinterview.config;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.security.AuthCookieService;
import interview.guide.modules.auth.service.AuthSupport;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

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
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String ticketKey = "auth:ws-ticket:" + authSupport.sha256(ticket);
        Object value = redisService.get(ticketKey);
        redisService.delete(ticketKey);
        if (value == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String[] parts = value.toString().split(":", 2);
        if (parts.length != 2 || !parts[1].equals(sessionId)) {
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
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

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
