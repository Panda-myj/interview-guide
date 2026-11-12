package interview.guide.modules.auth.security;

import interview.guide.modules.auth.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthCookieService {

    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String DEVICE_COOKIE = "device_token";
    public static final String WS_TICKET_COOKIE = "ws_ticket";

    private static final String AUTH_PATH = "/api/auth";
    private static final String WS_PATH = "/ws/voice-interview";

    private final AuthProperties authProperties;

    public Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
            .filter(cookie -> name.equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> value != null && !value.isBlank())
            .findFirst();
    }

    public void setRefreshCookie(HttpServletResponse response, String token) {
        setCookie(response, REFRESH_COOKIE, token, authProperties.getRefreshTokenTtl(), AUTH_PATH);
    }

    public void setDeviceCookie(HttpServletResponse response, String token) {
        setCookie(response, DEVICE_COOKIE, token, authProperties.getDeviceCookieTtl(), AUTH_PATH);
    }

    public void setWsTicketCookie(HttpServletResponse response, String token) {
        setCookie(response, WS_TICKET_COOKIE, token, authProperties.getWsTicketTtl(), WS_PATH);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        setCookie(response, REFRESH_COOKIE, "", Duration.ZERO, AUTH_PATH);
    }

    public void clearWsTicketCookie(HttpServletResponse response) {
        setCookie(response, WS_TICKET_COOKIE, "", Duration.ZERO, WS_PATH);
    }

    private void setCookie(
        HttpServletResponse response,
        String name,
        String value,
        Duration maxAge,
        String path
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(authProperties.isSecureCookies())
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
