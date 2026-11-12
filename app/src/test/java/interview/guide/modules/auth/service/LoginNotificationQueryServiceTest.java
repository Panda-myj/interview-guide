package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.dto.LoginNotificationPageResponse;
import interview.guide.modules.auth.model.LoginNotificationEntity;
import interview.guide.modules.auth.repository.LoginNotificationRepository;
import interview.guide.modules.auth.security.CurrentUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("站内登录提醒查询测试")
class LoginNotificationQueryServiceTest {

    @Mock
    private LoginNotificationRepository notificationRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Spy
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
    @InjectMocks
    private LoginNotificationQueryService service;

    @Test
    @DisplayName("列表只查询当前用户自己的记录并返回未读数")
    void listsOnlyOwnNotifications() {
        when(currentUserService.getRequiredUserId()).thenReturn(11L);
        when(notificationRepository.findByUser_IdOrderByLoginAtDesc(eq(11L), any(Pageable.class)))
            .thenReturn(List.of(notification(31L, null)));
        when(notificationRepository.countByUser_IdAndReadAtIsNull(11L)).thenReturn(1L);

        LoginNotificationPageResponse result = service.list(0, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).read()).isFalse();
        assertThat(result.unreadCount()).isEqualTo(1L);
        verify(notificationRepository).findByUser_IdOrderByLoginAtDesc(eq(11L), any(Pageable.class));
    }

    @Test
    @DisplayName("分页参数被限制在安全范围")
    void clampsPageParameters() {
        when(currentUserService.getRequiredUserId()).thenReturn(11L);
        when(notificationRepository.findByUser_IdOrderByLoginAtDesc(eq(11L), any(Pageable.class)))
            .thenReturn(List.of());
        when(notificationRepository.countByUser_IdAndReadAtIsNull(11L)).thenReturn(0L);

        service.list(-5, 9999);

        verify(notificationRepository).findByUser_IdOrderByLoginAtDesc(
            eq(11L), eq(org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    @Test
    @DisplayName("不能把他人的通知标记为已读")
    void rejectsMarkingOtherUsersNotification() {
        when(currentUserService.getRequiredUserId()).thenReturn(11L);
        when(notificationRepository.findByIdAndUser_Id(99L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(99L)).isInstanceOf(BusinessException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("重复标记已读是幂等的")
    void markReadIsIdempotent() {
        LocalDateTime alreadyRead = LocalDateTime.now().minusDays(1);
        when(currentUserService.getRequiredUserId()).thenReturn(11L);
        when(notificationRepository.findByIdAndUser_Id(31L, 11L))
            .thenReturn(Optional.of(notification(31L, alreadyRead)));

        service.markRead(31L);

        verify(notificationRepository, never()).save(any());
    }

    private LoginNotificationEntity notification(Long id, LocalDateTime readAt) {
        LoginNotificationEntity entity = new LoginNotificationEntity();
        entity.setId(id);
        entity.setDeviceLabel("JUnit Browser");
        entity.setLoginAt(LocalDateTime.now().minusHours(1));
        entity.setReadAt(readAt);
        return entity;
    }
}
