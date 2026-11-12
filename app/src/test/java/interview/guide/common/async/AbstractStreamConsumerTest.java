package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 异步任务模板的状态机与幂等行为测试（PERF-001），不依赖真实 Redis。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("异步任务消费者模板")
class AbstractStreamConsumerTest {

    @Mock
    private RedisService redisService;

    private FakeConsumer consumer;

    private final StreamMessageId messageId = new StreamMessageId(1L, 0L);

    @BeforeEach
    void setUp() {
        consumer = new FakeConsumer(redisService);
    }

    @Nested
    @DisplayName("条件占位（幂等）")
    class ClaimProcessing {

        @Test
        @DisplayName("未抢到处理权时直接 ACK，不执行业务")
        void notClaimedShouldAckWithoutBusiness() {
            consumer.claimResult = false;

            consumer.processMessage(messageId, payload("s-1", 0));

            assertThat(consumer.processed).isEmpty();
            assertThat(consumer.completed).isEmpty();
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
        }

        @Test
        @DisplayName("抢到处理权时执行业务并标记完成")
        void claimedShouldProcessOnce() {
            consumer.claimResult = true;

            consumer.processMessage(messageId, payload("s-1", 0));

            assertThat(consumer.processed).containsExactly("s-1");
            assertThat(consumer.completed).containsExactly("s-1");
            assertThat(consumer.failed).isEmpty();
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
        }
    }

    @Nested
    @DisplayName("重试上限")
    class RetryLimit {

        @Test
        @DisplayName("未达上限时重新入队并传递 retryCount + 1")
        void belowLimitShouldRetry() {
            consumer.processShouldThrow = true;

            consumer.processMessage(messageId, payload("s-1", 0));

            assertThat(consumer.retried).containsExactly("s-1:1");
            assertThat(consumer.failed).isEmpty();
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
        }

        @Test
        @DisplayName("达到 3 次重试后进入 FAILED，不再重投")
        void maxRetryShouldFail() {
            consumer.processShouldThrow = true;

            consumer.processMessage(messageId, payload("s-1", AsyncTaskStreamConstants.MAX_RETRY_COUNT));

            assertThat(consumer.retried).isEmpty();
            assertThat(consumer.failed).hasSize(1);
            assertThat(consumer.failed.get(0)).startsWith("s-1:");
            verify(redisService, never()).streamAdd(anyString(), anyMap(), anyInt());
        }

        @Test
        @DisplayName("失败原因超过 500 字符会被截断")
        void errorMessageShouldBeTruncated() {
            consumer.processShouldThrow = true;
            consumer.exceptionMessage = "x".repeat(600);

            consumer.processMessage(messageId, payload("s-1", AsyncTaskStreamConstants.MAX_RETRY_COUNT));

            assertThat(consumer.failed).hasSize(1);
            String error = consumer.failed.get(0).substring("s-1:".length());
            assertThat(error).hasSize(500);
        }
    }

    @Nested
    @DisplayName("消息格式异常")
    class BadPayload {

        @Test
        @DisplayName("无法解析的消息 ACK 丢弃，不触发重试")
        void nullPayloadShouldBeAcked() {
            consumer.processMessage(messageId, Map.of("unknown", "value"));

            assertThat(consumer.processed).isEmpty();
            assertThat(consumer.retried).isEmpty();
            assertThat(consumer.failed).isEmpty();
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
        }
    }

    @Nested
    @DisplayName("异步任务状态迁移表")
    class AsyncTaskTransitions {

        @Test
        @DisplayName("PENDING 可抢占、失败或保持幂等")
        void pendingTargets() {
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING)).isTrue();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.PENDING, AsyncTaskStatus.FAILED)).isTrue();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.PENDING, AsyncTaskStatus.COMPLETED)).isFalse();
        }

        @Test
        @DisplayName("PROCESSING 可完成、失败或回退重试")
        void processingTargets() {
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.PROCESSING, AsyncTaskStatus.COMPLETED)).isTrue();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.PROCESSING, AsyncTaskStatus.FAILED)).isTrue();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.PROCESSING, AsyncTaskStatus.PENDING)).isTrue();
        }

        @Test
        @DisplayName("COMPLETED 为终态，FAILED 仅可重放")
        void terminalStatuses() {
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.COMPLETED, AsyncTaskStatus.PROCESSING)).isFalse();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.COMPLETED, AsyncTaskStatus.PENDING)).isFalse();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.FAILED, AsyncTaskStatus.PENDING)).isTrue();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                AsyncTaskStatus.FAILED, AsyncTaskStatus.COMPLETED)).isFalse();
        }

        @Test
        @DisplayName("null 状态只能初始化到 PENDING 或 FAILED")
        void nullStatus() {
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                null, AsyncTaskStatus.PENDING)).isTrue();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                null, AsyncTaskStatus.FAILED)).isTrue();
            assertThat(AbstractStreamConsumer.canTransitAsyncTaskStatus(
                null, AsyncTaskStatus.PROCESSING)).isFalse();
            assertThat(AbstractStreamConsumer.allowedAsyncTaskTargets(null))
                .containsExactlyInAnyOrder(AsyncTaskStatus.PENDING, AsyncTaskStatus.FAILED);
        }
    }

    private Map<String, String> payload(String id, int retryCount) {
        return Map.of(
            "id", id,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        );
    }

    /**
     * 可配置行为的测试消费者。
     */
    private static class FakeConsumer extends AbstractStreamConsumer<String> {

        private boolean claimResult = true;
        private boolean processShouldThrow = false;
        private String exceptionMessage = "boom";
        private final List<String> processed = new ArrayList<>();
        private final List<String> completed = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();
        private final List<String> retried = new ArrayList<>();

        FakeConsumer(RedisService redisService) {
            super(redisService);
        }

        @Override
        protected String taskDisplayName() {
            return "测试任务";
        }

        @Override
        protected String streamKey() {
            return "test:stream";
        }

        @Override
        protected String groupName() {
            return "test-group";
        }

        @Override
        protected String consumerPrefix() {
            return "test-consumer-";
        }

        @Override
        protected String threadName() {
            return "test-consumer";
        }

        @Override
        protected String parsePayload(StreamMessageId messageId, Map<String, String> data) {
            return data.get("id");
        }

        @Override
        protected String payloadIdentifier(String payload) {
            return payload;
        }

        @Override
        protected boolean tryClaimProcessing(String payload) {
            return claimResult;
        }

        @Override
        protected void markProcessing(String payload) {
            // 使用 tryClaimProcessing 占位
        }

        @Override
        protected void processBusiness(String payload) {
            if (processShouldThrow) {
                throw new IllegalStateException(exceptionMessage);
            }
            processed.add(payload);
        }

        @Override
        protected void markCompleted(String payload) {
            completed.add(payload);
        }

        @Override
        protected void markFailed(String payload, String error) {
            failed.add(payload + ":" + error);
        }

        @Override
        protected void retryMessage(String payload, int retryCount) {
            retried.add(payload + ":" + retryCount);
        }
    }
}
