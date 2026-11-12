package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.dto.AuthResponse;
import interview.guide.modules.auth.dto.UserResponse;
import interview.guide.modules.auth.event.LoginNotificationCreatedEvent;
import interview.guide.modules.auth.model.AuthIdentityEntity;
import interview.guide.modules.auth.model.AuthSessionEntity;
import interview.guide.modules.auth.model.IdentityProvider;
import interview.guide.modules.auth.model.LoginNotificationEntity;
import interview.guide.modules.auth.model.UserDeviceEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.AuthIdentityRepository;
import interview.guide.modules.auth.repository.AuthSessionRepository;
import interview.guide.modules.auth.repository.LoginNotificationRepository;
import interview.guide.modules.auth.repository.UserDeviceRepository;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.auth.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AuthIdentityRepository identityRepository;
    private final UserDeviceRepository deviceRepository;
    private final AuthSessionRepository sessionRepository;
    private final LoginNotificationRepository notificationRepository;
    private final SmsCodeService smsCodeService;
    private final JwtService jwtService;
    private final AuthSupport authSupport;
    private final AuthProperties authProperties;
    private final CurrentUserService currentUserService;
    private final RedisService redisService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public LoginResult login(String rawPhone, String code, DeviceContext deviceContext) {
        String phone = smsCodeService.verifyLoginCode(rawPhone, code);
        UserEntity user = findOrCreateUser(phone);
        ensureActive(user);

        DeviceResolution deviceResolution = resolveDevice(user, deviceContext);
        LocalDateTime now = LocalDateTime.now(clock);
        user.setLastLoginAt(now);
        userRepository.save(user);

        String refreshToken = randomToken();
        AuthSessionEntity session = new AuthSessionEntity();
        session.setUser(user);
        session.setDevice(deviceResolution.device());
        session.setFamilyId(UUID.randomUUID());
        session.setRefreshTokenHash(authSupport.sha256(refreshToken));
        session.setExpiresAt(now.plus(authProperties.getRefreshTokenTtl()));
        session = sessionRepository.save(session);

        if (deviceResolution.newDevice()) {
            LoginNotificationEntity notification = new LoginNotificationEntity();
            notification.setUser(user);
            notification.setAuthSession(session);
            notification.setDeviceLabel(deviceResolution.device().getUserAgentLabel());
            notification.setLoginIp(deviceContext.ipAddress());
            notification.setLoginAt(now);
            notification = notificationRepository.save(notification);
            eventPublisher.publishEvent(new LoginNotificationCreatedEvent(notification.getId()));
        }

        AuthResponse response = createResponse(user, session);
        return new LoginResult(
            response,
            refreshToken,
            deviceResolution.rawDeviceToken(),
            deviceResolution.newCookie()
        );
    }

    @Transactional
    public LoginResult refresh(String rawRefreshToken, DeviceContext deviceContext) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        AuthSessionEntity current = sessionRepository
            .findByRefreshTokenHash(authSupport.sha256(rawRefreshToken))
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));
        LocalDateTime now = LocalDateTime.now(clock);

        if (current.getRotatedAt() != null) {
            Duration sinceRotation = Duration.between(current.getRotatedAt(), now);
            if (sinceRotation.compareTo(authProperties.getRefreshReuseGrace()) > 0) {
                sessionRepository.revokeFamily(current.getFamilyId(), now);
            }
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        if (current.getRevokedAt() != null || current.getExpiresAt().isBefore(now)) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        if (current.getDevice().getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        UserEntity user = current.getUser();
        ensureActive(user);
        updateDeviceSeen(current.getDevice(), deviceContext);

        String nextRefreshToken = randomToken();
        AuthSessionEntity next = new AuthSessionEntity();
        next.setUser(user);
        next.setDevice(current.getDevice());
        next.setFamilyId(current.getFamilyId());
        next.setRefreshTokenHash(authSupport.sha256(nextRefreshToken));
        next.setExpiresAt(now.plus(authProperties.getRefreshTokenTtl()));
        next = sessionRepository.save(next);

        current.setRotatedAt(now);
        current.setReplacedBySessionId(next.getId());
        current.setLastUsedAt(now);
        sessionRepository.save(current);

        return new LoginResult(createResponse(user, next), nextRefreshToken, null, false);
    }

    @Transactional
    public UserResponse me() {
        return toResponse(currentUserService.getRequiredUser());
    }

    @Transactional
    public void logout(String rawRefreshToken, AuthPrincipal principal) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            sessionRepository.findByRefreshTokenHash(authSupport.sha256(rawRefreshToken))
                .filter(session -> session.getUser().getId().equals(principal.userId()))
                .ifPresent(session -> {
                    session.setRevokedAt(LocalDateTime.now(clock));
                    sessionRepository.save(session);
                });
        }
        if (principal.tokenId() != null && principal.expiresAt() != null) {
            Duration remaining = Duration.between(clock.instant(), principal.expiresAt());
            if (!remaining.isNegative() && !remaining.isZero()) {
                redisService.set("auth:jwt:deny:" + principal.tokenId(), "1", remaining);
            }
        }
    }

    public String createWsTicket(AuthPrincipal principal, Long voiceSessionId) {
        String ticket = randomToken();
        String value = principal.userId() + ":" + voiceSessionId;
        redisService.set(
            "auth:ws-ticket:" + authSupport.sha256(ticket),
            value,
            authProperties.getWsTicketTtl()
        );
        return ticket;
    }

    private UserEntity findOrCreateUser(String phone) {
        Optional<AuthIdentityEntity> existing =
            identityRepository.findByProviderAndSubject(IdentityProvider.PHONE, phone);
        if (existing.isPresent()) {
            return existing.get().getUser();
        }
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName("面试者" + phone.substring(phone.length() - 4));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);

        // 身份表是唯一性权威来源；users.phone 仅作展示冗余，微信身份无需手机号。
        AuthIdentityEntity phoneIdentity = new AuthIdentityEntity();
        phoneIdentity.setUser(user);
        phoneIdentity.setProvider(IdentityProvider.PHONE);
        phoneIdentity.setSubject(phone);
        identityRepository.save(phoneIdentity);
        return user;
    }

    private DeviceResolution resolveDevice(UserEntity user, DeviceContext context) {
        String rawDeviceToken = context.deviceToken();
        String deviceKeyHash = rawDeviceToken == null || rawDeviceToken.isBlank()
            ? null
            : authSupport.sha256(rawDeviceToken);
        String userAgentHash = authSupport.sha256(context.userAgent() == null ? "" : context.userAgent());

        Optional<UserDeviceEntity> existing = deviceKeyHash == null
            ? Optional.empty()
            : deviceRepository.findByUser_IdAndDeviceKeyHash(user.getId(), deviceKeyHash)
                .filter(device -> device.getRevokedAt() == null);
        boolean newCookie = existing.isEmpty() || rawDeviceToken == null || rawDeviceToken.isBlank();
        String nextRawToken = newCookie ? randomToken() : rawDeviceToken;

        UserDeviceEntity device;
        boolean newDevice;
        if (existing.isPresent()) {
            device = existing.get();
            newDevice = !userAgentHash.equals(device.getUserAgentHash());
            device.setUserAgentHash(userAgentHash);
            device.setUserAgentLabel(authSupport.safeUserAgent(context.userAgent()));
        } else {
            device = new UserDeviceEntity();
            device.setUser(user);
            device.setDeviceKeyHash(newCookie
                ? authSupport.sha256(nextRawToken)
                : deviceKeyHash);
            device.setUserAgentHash(userAgentHash);
            device.setUserAgentLabel(authSupport.safeUserAgent(context.userAgent()));
            device.setFirstSeenIp(context.ipAddress());
            newDevice = true;
        }
        device.setLastSeenIp(context.ipAddress());
        device.setLastSeenAt(LocalDateTime.now(clock));
        device = deviceRepository.save(device);
        return new DeviceResolution(device, nextRawToken, newDevice, newCookie);
    }

    private void updateDeviceSeen(UserDeviceEntity device, DeviceContext context) {
        device.setLastSeenIp(context.ipAddress());
        device.setLastSeenAt(LocalDateTime.now(clock));
        deviceRepository.save(device);
    }

    private AuthResponse createResponse(UserEntity user, AuthSessionEntity session) {
        JwtService.IssuedAccessToken issued = jwtService.issue(user, session.getId());
        return new AuthResponse(issued.token(), issued.expiresInSeconds(), toResponse(user));
    }

    private UserResponse toResponse(UserEntity user) {
        return new UserResponse(
            user.getId(),
            authSupport.maskPhone(user.getPhone()),
            user.getDisplayName(),
            user.getRole()
        );
    }

    private void ensureActive(UserEntity user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.AUTH_USER_DISABLED);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record DeviceContext(
        String deviceToken,
        String userAgent,
        String ipAddress
    ) {
    }

    public record LoginResult(
        AuthResponse response,
        String refreshToken,
        String deviceToken,
        boolean setDeviceCookie
    ) {
    }

    private record DeviceResolution(
        UserDeviceEntity device,
        String rawDeviceToken,
        boolean newDevice,
        boolean newCookie
    ) {
    }
}
