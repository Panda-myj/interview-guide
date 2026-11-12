package interview.guide.modules.auth.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.AuthResponse;
import interview.guide.modules.auth.dto.DeleteAccountRequest;
import interview.guide.modules.auth.dto.LoginNotificationPageResponse;
import interview.guide.modules.auth.dto.SendSmsCodeRequest;
import interview.guide.modules.auth.dto.SendSmsCodeResponse;
import interview.guide.modules.auth.dto.SmsLoginRequest;
import interview.guide.modules.auth.dto.UserResponse;
import interview.guide.modules.auth.dto.WsTicketRequest;
import interview.guide.modules.auth.security.AuthCookieService;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.service.AccountDeletionService;
import interview.guide.modules.auth.service.AuthService;
import interview.guide.modules.auth.service.LoginNotificationQueryService;
import interview.guide.modules.auth.service.SmsCodeService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SmsCodeService smsCodeService;
    private final AuthService authService;
    private final AuthCookieService cookieService;
    private final VoiceInterviewService voiceInterviewService;
    private final LoginNotificationQueryService notificationQueryService;
    private final AccountDeletionService accountDeletionService;

    @PostMapping("/sms/send")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 100, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.HOURS)
    @RateLimit(dimension = RateLimit.Dimension.PHONE, count = 1, interval = 60, timeUnit = RateLimit.TimeUnit.SECONDS)
    public Result<SendSmsCodeResponse> sendCode(@Valid @RequestBody SendSmsCodeRequest request) {
        SmsCodeService.SendCodeResult result = smsCodeService.sendLoginCode(request.phone());
        return Result.success(new SendSmsCodeResponse("验证码已发送", result.debugCode()));
    }

    @PostMapping("/login/sms")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 10, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.PHONE, count = 10, interval = 10, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<AuthResponse> login(
        @Valid @RequestBody SmsLoginRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        AuthService.LoginResult login = authService.login(
            request.phone(),
            request.code(),
            deviceContext(servletRequest)
        );
        cookieService.setRefreshCookie(servletResponse, login.refreshToken());
        if (login.setDeviceCookie()) {
            cookieService.setDeviceCookie(servletResponse, login.deviceToken());
        }
        return Result.success(login.response());
    }

    @PostMapping("/refresh")
    public Result<AuthResponse> refresh(
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        String refreshToken = cookieService.read(servletRequest, AuthCookieService.REFRESH_COOKIE)
            .orElse(null);
        AuthService.LoginResult login = authService.refresh(refreshToken, deviceContext(servletRequest));
        cookieService.setRefreshCookie(servletResponse, login.refreshToken());
        return Result.success(login.response());
    }

    @PostMapping("/logout")
    public Result<Void> logout(
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse,
        @AuthenticationPrincipal AuthPrincipal principal
    ) {
        String refreshToken = cookieService.read(servletRequest, AuthCookieService.REFRESH_COOKIE)
            .orElse(null);
        authService.logout(refreshToken, principal);
        cookieService.clearAuthCookies(servletResponse);
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserResponse> me() {
        return Result.success(authService.me());
    }

    /** 站内登录提醒：只返回当前用户自己的记录与未读数。 */
    @GetMapping("/notifications")
    public Result<LoginNotificationPageResponse> notifications(
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        return Result.success(notificationQueryService.list(page, size));
    }

    @PostMapping("/notifications/{id}/read")
    public Result<Void> markNotificationRead(@PathVariable Long id) {
        notificationQueryService.markRead(id);
        return Result.success();
    }

    /** 注销账号专用验证码：手机号由登录态解析，不接受请求体指定。 */
    @PostMapping("/account/delete/code")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.HOURS)
    public Result<SendSmsCodeResponse> sendDeleteCode() {
        SmsCodeService.SendCodeResult result = accountDeletionService.sendDeleteCode();
        return Result.success(new SendSmsCodeResponse("注销验证码已发送", result.debugCode()));
    }

    /** 注销账号：登录态 + 本人手机号 + 注销专用验证码三重校验，接口不在 permitAll 白名单中。 */
    @PostMapping("/account/delete")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 10, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<Void> deleteAccount(
        @Valid @RequestBody DeleteAccountRequest request,
        HttpServletResponse servletResponse,
        @AuthenticationPrincipal AuthPrincipal principal
    ) {
        accountDeletionService.deleteAccount(request.phone(), request.code(), principal);
        cookieService.clearAuthCookies(servletResponse);
        return Result.success();
    }

    @PostMapping("/ws-ticket")
    public Result<Void> createWsTicket(
        HttpServletResponse response,
        @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody WsTicketRequest request
    ) {
        voiceInterviewService.requireOwned(request.sessionId());
        String ticket = authService.createWsTicket(principal, request.sessionId());
        cookieService.setWsTicketCookie(response, ticket);
        return Result.success();
    }

    private AuthService.DeviceContext deviceContext(HttpServletRequest request) {
        String deviceToken = cookieService.read(request, AuthCookieService.DEVICE_COOKIE)
            .orElse(null);
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = forwardedFor == null || forwardedFor.isBlank()
            ? request.getRemoteAddr()
            : forwardedFor.split(",")[0].trim();
        return new AuthService.DeviceContext(
            deviceToken,
            request.getHeader("User-Agent"),
            ip
        );
    }
}
