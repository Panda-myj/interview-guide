package interview.guide.modules.auth.notification;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.LoginNotificationEntity;
import interview.guide.modules.auth.model.LoginNotificationStatus;
import interview.guide.modules.auth.repository.LoginNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 登录提醒已由短信投递降级为站内通知。
 *
 * <p>login_notifications 表本身就是站内消息，因此消费者不再调用 SmsSender，
 * 只把记录置为 SENT（= 已生成可展示）。保留 Redis Stream 的幂等、状态机与
 * 有限重试，避免遗留任务无限循环；实体已删除时按异步规范直接跳过。
 */
@Slf4j
@Component
public class LoginNotificationConsumer
    extends AbstractStreamConsumer<LoginNotificationConsumer.Task> {

    private final LoginNotificationRepository notificationRepository;
    private final LoginNotificationProducer producer;
    private final Clock clock;

    public LoginNotificationConsumer(
        RedisService redisService,
        LoginNotificationRepository notificationRepository,
        LoginNotificationProducer producer,
        Clock clock
    ) {
        super(redisService);
        this.notificationRepository = notificationRepository;
        this.producer = producer;
        this.clock = clock;
    }

    @Override
    protected String taskDisplayName() {
        return "站内登录提醒";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.LOGIN_NOTIFICATION_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.LOGIN_NOTIFICATION_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.LOGIN_NOTIFICATION_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "login-notification-consumer";
    }

    @Override
    protected Task parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String rawId = data.get(AsyncTaskStreamConstants.FIELD_NOTIFICATION_ID);
        if (rawId == null) {
            return null;
        }
        return new Task(Long.parseLong(rawId), parseRetryCount(data));
    }

    @Override
    protected String payloadIdentifier(Task payload) {
        return "notificationId=" + payload.notificationId();
    }

    @Override
    protected boolean shouldSkip(Task payload) {
        return notificationRepository.findById(payload.notificationId())
            .map(notification -> notification.getStatus() == LoginNotificationStatus.SENT
                || notification.getStatus() == LoginNotificationStatus.FAILED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(Task payload) {
        notificationRepository.findById(payload.notificationId()).ifPresent(notification -> {
            notification.setStatus(LoginNotificationStatus.PROCESSING);
            notification.setAttempts(notification.getAttempts() + 1);
            notification.setLastError(null);
            notificationRepository.save(notification);
        });
    }

    @Override
    protected void processBusiness(Task payload) {
        // 站内通知无需外部投递：确认记录存在即可，缺失时视为已删除并正常结束。
        notificationRepository.findById(payload.notificationId())
            .orElseThrow(() -> new IllegalStateException("登录提醒记录不存在"));
    }

    @Override
    protected void markCompleted(Task payload) {
        notificationRepository.findById(payload.notificationId()).ifPresent(notification -> {
            notification.setStatus(LoginNotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now(clock));
            notification.setLastError(null);
            notificationRepository.save(notification);
        });
    }

    @Override
    protected void markFailed(Task payload, String error) {
        log.error("站内登录提醒生成失败 notificationId={}, attempts={}, error={}",
            payload.notificationId(), currentAttempts(payload), truncateError(error));
        notificationRepository.findById(payload.notificationId()).ifPresent(notification -> {
            notification.setStatus(LoginNotificationStatus.FAILED);
            notification.setLastError(truncateError(error));
            notificationRepository.save(notification);
        });
    }

    private int currentAttempts(Task payload) {
        return notificationRepository.findById(payload.notificationId())
            .map(LoginNotificationEntity::getAttempts)
            .orElse(-1);
    }

    @Override
    protected void retryMessage(Task payload, int retryCount) {
        notificationRepository.findById(payload.notificationId()).ifPresent(notification -> {
            notification.setStatus(LoginNotificationStatus.PENDING);
            notificationRepository.save(notification);
        });
        producer.send(payload.notificationId(), retryCount);
    }

    public record Task(Long notificationId, int retryCount) {
    }
}
