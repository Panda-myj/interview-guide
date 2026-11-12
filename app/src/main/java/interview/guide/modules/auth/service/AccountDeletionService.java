package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.service.ResourceCleanupRequestedEvent;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号注销（硬删除）。
 *
 * <p>两阶段设计，解决「补偿任务外键指向 users」与「事务内不得调用 S3 / 外部 HTTP」：
 * <ol>
 *   <li>短事务：短信验证码二次确认 -> 抹除 PII 并置 DELETING（立即停权）-> 撤销全部会话
 *       -> 登记对象文件 / 向量清理补偿任务 -> 删除全部业务表与身份数据（保留 users 行）；</li>
 *   <li>提交后：既有 ResourceCleanupExecutor 按补偿任务清理外部资源，
 *       {@link AccountDeletionFinalizer} 在任务全部终结后物理删除 users 行，释放手机号。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("ACCOUNT_DELETION_AUDIT");

  private final SmsCodeService smsCodeService;
  private final AuthSupport authSupport;
  private final CurrentUserService currentUserService;
  private final UserRepository userRepository;
  private final AuthIdentityRepository identityRepository;
  private final AuthSessionRepository sessionRepository;
  private final AccountDataCleanupDao cleanupDao;
  private final ResourceDeletionCompensationService compensationService;
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionalExecutor transactionalExecutor;
  private final RedisService redisService;
  private final Clock clock;

  /** 从登录态解析本人手机号，不接受请求体传入手机号发码，避免骚扰他人。 */
  public SmsCodeService.SendCodeResult sendDeleteCode() {
    Long userId = currentUserService.getRequiredUserId();
    String phone = requireOwnPhone(userId);
    return smsCodeService.sendCode(phone, CodePurpose.ACCOUNT_DELETION);
  }

  public void deleteAccount(String rawPhone, String code, AuthPrincipal principal) {
    Long userId = principal.userId();
    String phone = authSupport.normalizePhone(rawPhone);
    transactionalExecutor.run(() -> deleteInTransaction(userId, phone, rawPhone, code, principal));
  }

  private void deleteInTransaction(
      Long userId,
      String phone,
      String rawPhone,
      String code,
      AuthPrincipal principal
  ) {
    // 1. 身份归属：手机号必须确实绑定在当前登录用户身上，防止越权删除他人账号。
    AuthIdentityEntity identity = identityRepository
        .findByProviderAndSubject(IdentityProvider.PHONE, phone)
        .filter(item -> item.getUser().getId().equals(userId))
        .orElseThrow(() -> new BusinessException(
            ErrorCode.AUTH_ACCOUNT_DELETE_PHONE_MISMATCH, "手机号与当前账号不一致"));

    // 2. 注销专用验证码，与登录码 Redis 命名空间完全隔离。
    smsCodeService.verifyCode(rawPhone, code, CodePurpose.ACCOUNT_DELETION);

    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在"));
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DELETE_IN_PROGRESS, "账号注销正在处理中");
    }
    if (user.getRole() == UserRole.ADMIN
        && userRepository.countByRoleAndStatusNot(UserRole.ADMIN, UserStatus.DELETING) <= 1) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DELETE_LAST_ADMIN,
          "系统需保留至少一名管理员，无法注销该账号");
    }

    // 3. 停权并抹除 users 上的个人信息（手机号、含尾号的昵称）。
    user.setStatus(UserStatus.DELETING);
    user.setPhone(null);
    user.setDisplayName("已注销用户");
    userRepository.save(user);
    sessionRepository.revokeAllForUser(userId, LocalDateTime.now(clock));
    denyCurrentAccessToken(principal);

    // 4. 登记外部资源清理任务；事务提交后由既有 Handler 处理文件与向量。
    List<AccountDataCleanupDao.StorageResource> resumes = cleanupDao.findResumeFiles(userId);
    List<AccountDataCleanupDao.StorageResource> knowledgeBases =
        cleanupDao.findKnowledgeBaseFiles(userId);
    for (AccountDataCleanupDao.StorageResource resume : resumes) {
      publishCleanup(compensationService.enqueue(
          ResourceCleanupType.RESUME_FILE,
          resume.resourceId(), resume.storageKey(), userId));
    }
    for (AccountDataCleanupDao.StorageResource kb : knowledgeBases) {
      publishCleanup(compensationService.enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_FILE,
          kb.resourceId(), kb.storageKey(), userId));
      publishCleanup(compensationService.enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_VECTOR,
          kb.resourceId(), "", userId));
    }

    // 5. 删除全部业务数据与身份数据；users 行留给收尾任务，保证补偿外键有效。
    cleanupDao.deleteBusinessAndIdentityData(userId);
    smsCodeService.clearCodes(phone);

    AUDIT_LOG.info(
        "action=DELETE_ACCOUNT userId={} resumeCount={} knowledgeBaseCount={} outcome=SUCCESS",
        userId, resumes.size(), knowledgeBases.size());
    log.info("账号注销已提交，等待外部资源清理后物理删除: userId={}", userId);
  }

  private void publishCleanup(Long taskId) {
    if (taskId != null) {
      eventPublisher.publishEvent(new ResourceCleanupRequestedEvent(taskId));
    }
  }

  private void denyCurrentAccessToken(AuthPrincipal principal) {
    if (principal.tokenId() == null || principal.expiresAt() == null) {
      return;
    }
    Duration remaining = Duration.between(clock.instant(), principal.expiresAt());
    if (!remaining.isNegative() && !remaining.isZero()) {
      redisService.set("auth:jwt:deny:" + principal.tokenId(), "1", remaining);
    }
  }

  private String requireOwnPhone(Long userId) {
    return identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.PHONE)
        .map(AuthIdentityEntity::getSubject)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.AUTH_ACCOUNT_DELETE_NO_PHONE, "当前账号未绑定手机号"));
  }
}
