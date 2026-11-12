package interview.guide.modules.auth.notification;

import interview.guide.modules.auth.event.LoginNotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class LoginNotificationEventListener {

    private final LoginNotificationProducer producer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(LoginNotificationCreatedEvent event) {
        producer.send(event.notificationId(), 0);
    }
}
