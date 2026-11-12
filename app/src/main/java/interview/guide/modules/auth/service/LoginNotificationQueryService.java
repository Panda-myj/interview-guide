package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.dto.LoginNotificationPageResponse;
import interview.guide.modules.auth.dto.LoginNotificationResponse;
import interview.guide.modules.auth.model.LoginNotificationEntity;
import interview.guide.modules.auth.repository.LoginNotificationRepository;
import interview.guide.modules.auth.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** 站内登录提醒的查询与已读，所有查询都带当前用户条件，避免越权。 */
@Service
@RequiredArgsConstructor
public class LoginNotificationQueryService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final LoginNotificationRepository notificationRepository;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public LoginNotificationPageResponse list(Integer page, Integer size) {
        Long userId = currentUserService.getRequiredUserId();
        int safePage = page == null ? 0 : Math.max(page, 0);
        int safeSize = size == null
            ? DEFAULT_PAGE_SIZE
            : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var items = notificationRepository
            .findByUser_IdOrderByLoginAtDesc(userId, PageRequest.of(safePage, safeSize))
            .stream()
            .map(this::toResponse)
            .toList();
        long unread = notificationRepository.countByUser_IdAndReadAtIsNull(userId);
        return new LoginNotificationPageResponse(items, unread);
    }

    @Transactional
    public void markRead(Long notificationId) {
        Long userId = currentUserService.getRequiredUserId();
        LoginNotificationEntity notification = notificationRepository
            .findByIdAndUser_Id(notificationId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "通知不存在"));
        if (notification.getReadAt() != null) {
            return;
        }
        notification.setReadAt(LocalDateTime.now(clock));
        notificationRepository.save(notification);
    }

    private LoginNotificationResponse toResponse(LoginNotificationEntity entity) {
        return new LoginNotificationResponse(
            entity.getId(),
            entity.getDeviceLabel() == null ? "未知设备" : entity.getDeviceLabel(),
            entity.getLoginAt(),
            entity.getReadAt() != null,
            entity.getReadAt()
        );
    }
}
