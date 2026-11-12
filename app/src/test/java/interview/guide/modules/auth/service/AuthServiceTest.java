package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.config.AuthProperties;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户认证服务测试")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthIdentityRepository identityRepository;
    @Mock
    private UserDeviceRepository deviceRepository;
    @Mock
    private AuthSessionRepository sessionRepository;
    @Mock
    private LoginNotificationRepository notificationRepository;
    @Mock
    private SmsCodeService smsCodeService;
    @Mock
    private JwtService jwtService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private RedisService redisService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Spy
    private AuthSupport authSupport = new AuthSupport();
    @Spy
    private AuthProperties authProperties = new AuthProperties();
    // TIME-001：AuthService 通过构造注入 Clock；@InjectMocks 只会注入 mock/spy，
    // 因此用 @Spy 包装真实 Clock（Asia/Shanghai，与 AppClockConfig 一致）
    @Spy
    private java.time.Clock clock = java.time.Clock.system(java.time.ZoneId.of("Asia/Shanghai"));
    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authProperties.setJwtSecret("test-secret-with-more-than-thirty-two-characters");
        authProperties.setRefreshTokenTtl(Duration.ofDays(30));
        authProperties.setRefreshReuseGrace(Duration.ofSeconds(30));
        lenient().when(jwtService.issue(any(), any()))
            .thenReturn(new JwtService.IssuedAccessToken("access-token", 900));
    }

    @Test
    @DisplayName("新手机号首次登录自动注册并发送新设备提醒")
    void registersUserAndQueuesNewDeviceNotification() {
        String phone = "+8613800138000";
        when(smsCodeService.verifyLoginCode("13800138000", "123456")).thenReturn(phone);
        when(identityRepository.findByProviderAndSubject(IdentityProvider.PHONE, phone))
            .thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(11L);
            return user;
        });
        lenient().when(deviceRepository.findByUser_IdAndDeviceKeyHash(any(), any()))
            .thenReturn(Optional.empty());
        when(deviceRepository.save(any(UserDeviceEntity.class))).thenAnswer(invocation -> {
            UserDeviceEntity device = invocation.getArgument(0);
            device.setId(21L);
            return device;
        });
        when(sessionRepository.save(any(AuthSessionEntity.class))).thenAnswer(invocation -> {
            AuthSessionEntity session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(UUID.randomUUID());
            }
            return session;
        });
        when(notificationRepository.save(any(LoginNotificationEntity.class))).thenAnswer(invocation -> {
            LoginNotificationEntity notification = invocation.getArgument(0);
            notification.setId(31L);
            return notification;
        });

        AuthService.LoginResult result = authService.login(
            "13800138000",
            "123456",
            new AuthService.DeviceContext(null, "JUnit Browser", "127.0.0.1")
        );

        assertThat(result.response().accessToken()).isEqualTo("access-token");
        assertThat(result.response().user().maskedPhone()).isEqualTo("+86****8000");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.setDeviceCookie()).isTrue();
        ArgumentCaptor<LoginNotificationCreatedEvent> event =
            ArgumentCaptor.forClass(LoginNotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().notificationId()).isEqualTo(31L);
    }

    @Test
    @DisplayName("已知设备再次登录不重复发送提醒")
    void knownDeviceDoesNotQueueNotification() {
        UserEntity user = user(11L, "+8613800138000");
        String rawDeviceToken = "known-device-token";
        UserDeviceEntity device = new UserDeviceEntity();
        device.setId(21L);
        device.setUser(user);
        device.setDeviceKeyHash(authSupport.sha256(rawDeviceToken));
        device.setUserAgentHash(authSupport.sha256("JUnit Browser"));
        when(smsCodeService.verifyLoginCode("13800138000", "123456"))
            .thenReturn(user.getPhone());
        AuthIdentityEntity identity = new AuthIdentityEntity();
        identity.setUser(user);
        when(identityRepository.findByProviderAndSubject(IdentityProvider.PHONE, user.getPhone()))
            .thenReturn(Optional.of(identity));
        when(deviceRepository.findByUser_IdAndDeviceKeyHash(user.getId(), device.getDeviceKeyHash()))
            .thenReturn(Optional.of(device));
        when(deviceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionRepository.save(any(AuthSessionEntity.class))).thenAnswer(invocation -> {
            AuthSessionEntity session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(UUID.randomUUID());
            }
            return session;
        });

        AuthService.LoginResult result = authService.login(
            "13800138000",
            "123456",
            new AuthService.DeviceContext(rawDeviceToken, "JUnit Browser", "127.0.0.1")
        );

        assertThat(result.setDeviceCookie()).isFalse();
        verify(notificationRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("刷新令牌轮换后旧令牌再次使用会撤销整个会话族")
    void revokesFamilyWhenRotatedTokenIsReusedAfterGrace() {
        String rawRefresh = "old-refresh-token";
        UserEntity user = user(11L, "+8613800138000");
        UserDeviceEntity device = new UserDeviceEntity();
        device.setId(21L);
        device.setUser(user);
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId(UUID.randomUUID());
        session.setFamilyId(UUID.randomUUID());
        session.setUser(user);
        session.setDevice(device);
        session.setRefreshTokenHash(authSupport.sha256(rawRefresh));
        session.setExpiresAt(LocalDateTime.now().plusDays(1));
        session.setRotatedAt(LocalDateTime.now().minusSeconds(31));
        when(sessionRepository.findByRefreshTokenHash(authSupport.sha256(rawRefresh)))
            .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> authService.refresh(
            rawRefresh,
            new AuthService.DeviceContext("device", "UA", "127.0.0.1")
        )).isInstanceOf(BusinessException.class)
            .hasMessageContaining("重新登录");

        verify(sessionRepository).revokeFamily(any(UUID.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("退出登录会撤销刷新会话并按剩余时间拉黑 Access Token")
    void logoutRevokesRefreshSessionAndDeniesAccessToken() {
        String rawRefresh = "refresh-token";
        UserEntity user = user(11L, "+8613800138000");
        AuthSessionEntity session = new AuthSessionEntity();
        session.setUser(user);
        session.setRefreshTokenHash(authSupport.sha256(rawRefresh));
        when(sessionRepository.findByRefreshTokenHash(authSupport.sha256(rawRefresh)))
            .thenReturn(Optional.of(session));
        AuthPrincipal principal = new AuthPrincipal(
            11L,
            UserRole.USER,
            UUID.randomUUID().toString(),
            "jwt-id",
            Instant.now().plusSeconds(60)
        );

        authService.logout(rawRefresh, principal);

        assertThat(session.getRevokedAt()).isNotNull();
        verify(redisService).set(any(), any(), any(Duration.class));
    }

    private UserEntity user(Long id, String phone) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setPhone(phone);
        user.setDisplayName("面试者8000");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
