package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.service.ResourceDeletionCompensationService;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.AuthIdentityEntity;
import interview.guide.modules.auth.model.CodePurpose;
import interview.guide.modules.auth.model.IdentityProvider;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.AccountDataCleanupDao;
import interview.guide.modules.auth.repository.AuthIdentityRepository;
import interview.guide.modules.auth.repository.AuthSessionRepository;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("账号注销服务测试")
class AccountDeletionServiceTest {

    private static final String RAW_PHONE = "13800138000";
    private static final String PHONE = "+8613800138000";
    private static final Long USER_ID = 11L;

    @Mock
    private SmsCodeService smsCodeService;
    @Spy
    private AuthSupport authSupport = new AuthSupport();
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthIdentityRepository identityRepository;
    @Mock
    private AuthSessionRepository sessionRepository;
    @Mock
    private AccountDataCleanupDao cleanupDao;
    @Mock
    private ResourceDeletionCompensationService compensationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private TransactionalExecutor transactionalExecutor;
    @Mock
    private RedisService redisService;
    @Spy
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    @InjectMocks
    private AccountDeletionService service;

    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = new AuthPrincipal(USER_ID, UserRole.USER, "session-id", null, null);
        // 被测服务通过 TransactionalExecutor 包事务，测试中直接执行 lambda。
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(0);
            action.run();
            return null;
        }).when(transactionalExecutor).run(any());
        lenient().when(cleanupDao.findResumeFiles(USER_ID)).thenReturn(List.of());
        lenient().when(cleanupDao.findKnowledgeBaseFiles(USER_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("手机号不属于当前用户时拒绝注销且不消耗验证码")
    void rejectsPhoneBelongingToAnotherUser() {
        UserEntity other = user(99L);
        when(identityRepository.findByProviderAndSubject(IdentityProvider.PHONE, PHONE))
            .thenReturn(Optional.of(identity(other)));

        assertThatThrownBy(() -> service.deleteAccount(RAW_PHONE, "123456", principal))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AUTH_ACCOUNT_DELETE_PHONE_MISMATCH.getCode()));

        verify(smsCodeService, never()).verifyCode(any(), any(), any());
        verify(cleanupDao, never()).deleteBusinessAndIdentityData(any());
    }

    @Test
    @DisplayName("注销验证码错误时中止，不改变账号状态")
    void rejectsInvalidDeletionCode() {
        when(identityRepository.findByProviderAndSubject(IdentityProvider.PHONE, PHONE))
            .thenReturn(Optional.of(identity(user(USER_ID))));
        doThrow(new BusinessException(ErrorCode.AUTH_SMS_CODE_INVALID))
            .when(smsCodeService).verifyCode(RAW_PHONE, "000000", CodePurpose.ACCOUNT_DELETION);

        assertThatThrownBy(() -> service.deleteAccount(RAW_PHONE, "000000", principal))
            .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
        verify(cleanupDao, never()).deleteBusinessAndIdentityData(any());
    }

    @Test
    @DisplayName("唯一管理员禁止自助注销")
    void rejectsLastAdmin() {
        UserEntity admin = user(USER_ID);
        admin.setRole(UserRole.ADMIN);
        when(identityRepository.findByProviderAndSubject(IdentityProvider.PHONE, PHONE))
            .thenReturn(Optional.of(identity(admin)));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(admin));
        when(userRepository.countByRoleAndStatusNot(UserRole.ADMIN, UserStatus.DELETING))
            .thenReturn(1L);

        assertThatThrownBy(() -> service.deleteAccount(RAW_PHONE, "123456", principal))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.AUTH_ACCOUNT_DELETE_LAST_ADMIN.getCode()));
    }

    @Test
    @DisplayName("注销成功时停权抹除手机号、登记外部资源清理并删除业务数据")
    void enqueuesCleanupAndErasesPersonalData() {
        UserEntity user = user(USER_ID);
        when(identityRepository.findByProviderAndSubject(IdentityProvider.PHONE, PHONE))
            .thenReturn(Optional.of(identity(user)));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(cleanupDao.findResumeFiles(USER_ID)).thenReturn(
            List.of(new AccountDataCleanupDao.StorageResource("101", "resumes/101.pdf")));
        when(cleanupDao.findKnowledgeBaseFiles(USER_ID)).thenReturn(
            List.of(new AccountDataCleanupDao.StorageResource("202", "kb/202.pdf")));
        when(compensationService.enqueue(any(), any(), any(), eq(USER_ID))).thenReturn(1L);

        service.deleteAccount(RAW_PHONE, "123456", principal);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETING);
        assertThat(user.getPhone()).isNull();
        assertThat(user.getDisplayName()).isEqualTo("已注销用户");
        verify(smsCodeService).verifyCode(RAW_PHONE, "123456", CodePurpose.ACCOUNT_DELETION);
        verify(compensationService).enqueue(
            ResourceCleanupType.RESUME_FILE, "101", "resumes/101.pdf", USER_ID);
        verify(compensationService).enqueue(
            ResourceCleanupType.KNOWLEDGE_BASE_VECTOR, "202", "", USER_ID);
        verify(cleanupDao).deleteBusinessAndIdentityData(USER_ID);
        verify(smsCodeService).clearCodes(PHONE);
        verify(sessionRepository).revokeAllForUser(eq(USER_ID), any());
    }

    @Test
    @DisplayName("注销发码只使用登录态解析出的本人手机号")
    void sendsCodeToOwnPhoneOnly() {
        when(currentUserService.getRequiredUserId()).thenReturn(USER_ID);
        AuthIdentityEntity phoneIdentity = new AuthIdentityEntity();
        phoneIdentity.setSubject(PHONE);
        when(identityRepository.findByUser_IdAndProvider(USER_ID, IdentityProvider.PHONE))
            .thenReturn(Optional.of(phoneIdentity));
        when(smsCodeService.sendCode(PHONE, CodePurpose.ACCOUNT_DELETION))
            .thenReturn(new SmsCodeService.SendCodeResult(PHONE, null));

        service.sendDeleteCode();

        verify(smsCodeService).sendCode(PHONE, CodePurpose.ACCOUNT_DELETION);
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setPhone(PHONE);
        user.setDisplayName("面试者8000");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private AuthIdentityEntity identity(UserEntity user) {
        AuthIdentityEntity identity = new AuthIdentityEntity();
        identity.setUser(user);
        identity.setProvider(IdentityProvider.PHONE);
        identity.setSubject(PHONE);
        return identity;
    }
}
