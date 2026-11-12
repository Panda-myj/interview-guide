package interview.guide.modules.auth.notification;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.LoginNotificationStatus;
import interview.guide.modules.auth.repository.LoginNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class LoginNotificationProducer extends AbstractStreamProducer<LoginNotificationProducer.Task> {

    private final LoginNotificationRepository notificationRepository;

    public LoginNotificationProducer(
        RedisService redisService,
        LoginNotificationRepository notificationRepository
    ) {
        super(redisService);
        this.notificationRepository = notificationRepository;
    }

    public void send(Long notificationId, int retryCount) {
        sendTask(new Task(notificationId, retryCount));
    }

    @Override
    protected String taskDisplayName() {
        return "登录短信提醒";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.LOGIN_NOTIFICATION_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(Task payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_NOTIFICATION_ID, payload.notificationId().toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(payload.retryCount())
        );
    }

    @Override
    protected String payloadIdentifier(Task payload) {
        return "notificationId=" + payload.notificationId();
    }

    @Override
    protected void onSendFailed(Task payload, String error) {
        notificationRepository.findById(payload.notificationId()).ifPresent(notification -> {
            notification.setStatus(LoginNotificationStatus.FAILED);
            notification.setLastError(truncateError(error));
            notificationRepository.save(notification);
        });
        log.error("登录提醒入队失败: notificationId={}, error={}", payload.notificationId(), error);
    }

    public record Task(Long notificationId, int retryCount) {
    }
}
