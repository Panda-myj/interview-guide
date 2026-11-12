package interview.guide.modules.auth.notification;

import interview.guide.modules.auth.model.LoginNotificationStatus;
import interview.guide.modules.auth.repository.LoginNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class LoginNotificationRetryScheduler {

    private final LoginNotificationRepository notificationRepository;
    private final LoginNotificationProducer producer;

    @Scheduled(fixedDelayString = "${app.auth.notification-retry-delay:60000}")
    public void republishPendingNotifications() {
        notificationRepository
            .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                LoginNotificationStatus.PENDING,
                LocalDateTime.now().minusMinutes(5)
            )
            .forEach(notification -> producer.send(notification.getId(), 0));
    }
}
