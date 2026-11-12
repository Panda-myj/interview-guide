package interview.guide.modules.voiceinterview.listener;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音面试评估消费者幂等性测试（PERF-001），不依赖真实 Redis。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音面试评估消费者幂等性")
class VoiceEvaluateStreamConsumerIdempotencyTest {

    @Mock
    private RedisService redisService;
    @Mock
    private VoiceInterviewService voiceInterviewService;
    @Mock
    private VoiceInterviewEvaluationService evaluationService;
    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    private TestableConsumer consumer;

    private final StreamMessageId messageId = new StreamMessageId(3L, 0L);

    @BeforeEach
    void setUp() {
        consumer = new TestableConsumer(redisService, voiceInterviewService, evaluationService,
            sessionRepository);
    }

    @Nested
    @DisplayName("重复投递")
    class DuplicateDelivery {

        @Test
        @DisplayName("未抢到处理权的重复消息直接 ACK，不重复评估")
        void notClaimedShouldNotEvaluate() {
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(1L, AsyncTaskStatus.PENDING)));
            when(voiceInterviewService.claimEvaluationProcessing(1L)).thenReturn(false);

            consumer.process(messageId, payload(1L, 0));

            verify(evaluationService, never()).generateEvaluation(any());
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
            verify(voiceInterviewService, never()).completeEvaluation(any());
        }

        @Test
        @DisplayName("已完成评估的会话直接 ACK")
        void completedShouldBeSkipped() {
            when(sessionRepository.findById(1L))
                .thenReturn(Optional.of(session(1L, AsyncTaskStatus.COMPLETED)));

            consumer.process(messageId, payload(1L, 0));

            verify(voiceInterviewService, never()).claimEvaluationProcessing(any());
            verify(evaluationService, never()).generateEvaluation(any());
        }

        @Test
        @DisplayName("会话已删除时 ACK 丢弃")
        void missingSessionShouldBeAcked() {
            when(sessionRepository.findById(1L)).thenReturn(Optional.empty());

            consumer.process(messageId, payload(1L, 0));

            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
            verify(evaluationService, never()).generateEvaluation(any());
        }
    }

    @Nested
    @DisplayName("正常评估")
    class HappyPath {

        @Test
        @DisplayName("抢到处理权后执行一次评估并标记完成")
        void claimedShouldEvaluateOnce() {
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(1L, AsyncTaskStatus.PENDING)));
            when(sessionRepository.existsById(1L)).thenReturn(true);
            when(voiceInterviewService.claimEvaluationProcessing(1L)).thenReturn(true);

            consumer.process(messageId, payload(1L, 0));

            verify(evaluationService).generateEvaluation(1L);
            verify(voiceInterviewService).completeEvaluation(1L);
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
        }
    }

    @Nested
    @DisplayName("失败与重试")
    class RetryBehaviour {

        @Test
        @DisplayName("状态无法回退时不重复入队")
        void retryShouldSkipWhenStatusChanged() {
            prepareFailingSession();
            when(voiceInterviewService.resetEvaluationForRetry(1L)).thenReturn(false);

            consumer.process(messageId, payload(1L, 0));

            verify(redisService, never()).streamAdd(anyString(), anyMap(), anyInt());
            verify(voiceInterviewService, never()).failEvaluation(any(), anyString());
        }

        @Test
        @DisplayName("未达上限时回退状态并重新入队")
        void retryShouldRequeue() {
            prepareFailingSession();
            when(voiceInterviewService.resetEvaluationForRetry(1L)).thenReturn(true);

            consumer.process(messageId, payload(1L, 0));

            verify(redisService).streamAdd(anyString(), anyMap(), anyInt());
        }

        @Test
        @DisplayName("达到 3 次重试后标记 FAILED，不再入队")
        void maxRetryShouldMarkFailed() {
            prepareFailingSession();

            consumer.process(messageId, payload(1L, AsyncTaskStreamConstants.MAX_RETRY_COUNT));

            verify(voiceInterviewService).failEvaluation(eq(1L), anyString());
            verify(redisService, never()).streamAdd(anyString(), anyMap(), anyInt());
        }
    }

    private void prepareFailingSession() {
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(1L, AsyncTaskStatus.PENDING)));
        when(sessionRepository.existsById(1L)).thenReturn(true);
        when(voiceInterviewService.claimEvaluationProcessing(1L)).thenReturn(true);
        doThrow(new IllegalStateException("llm down"))
            .when(evaluationService).generateEvaluation(1L);
    }

    private VoiceInterviewSessionEntity session(Long id, AsyncTaskStatus evaluateStatus) {
        return VoiceInterviewSessionEntity.builder()
            .id(id)
            .evaluateStatus(evaluateStatus)
            .build();
    }

    private Map<String, String> payload(Long sessionId, int retryCount) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId.toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        );
    }

    private static class TestableConsumer extends VoiceEvaluateStreamConsumer {

        TestableConsumer(RedisService redisService,
                         VoiceInterviewService voiceInterviewService,
                         VoiceInterviewEvaluationService evaluationService,
                         VoiceInterviewSessionRepository sessionRepository) {
            super(redisService, voiceInterviewService, evaluationService, sessionRepository);
        }

        void process(StreamMessageId messageId, Map<String, String> data) {
            processMessage(messageId, data);
        }
    }
}
