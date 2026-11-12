package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音面试服务状态机与幂等性测试（PERF-001），不依赖真实 Redis。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音面试服务状态机")
class VoiceInterviewServiceStateMachineTest {

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;
    @Mock
    private VoiceInterviewMessageRepository messageRepository;
    @Mock
    private VoiceInterviewEvaluationRepository evaluationRepository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<VoiceInterviewSessionEntity> bucket;
    @Mock
    private VoiceEvaluateStreamProducer producer;
    @Mock
    private LlmProviderRegistry llmProviderRegistry;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private ResumeRepository resumeRepository;

    private VoiceInterviewService service;

    @BeforeEach
    void setUp() {
        service = new VoiceInterviewService(
            sessionRepository,
            messageRepository,
            evaluationRepository,
            redissonClient,
            new VoiceInterviewProperties(),
            producer,
            llmProviderRegistry,
            currentUserService,
            resumeRepository
        );
        when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);
    }

    @Nested
    @DisplayName("结束会话幂等")
    class EndSession {

        @Test
        @DisplayName("重复结束会话不会重复入队评估")
        void repeatedEndShouldEnqueueOnce() {
            VoiceInterviewSessionEntity session = session(VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
            when(sessionRepository.updateStatusConditionally(
                eq(1L), eq(VoiceInterviewSessionStatus.COMPLETED), anyList())).thenReturn(1);
            when(sessionRepository.initializeEvaluateStatus(
                eq(1L), eq(AsyncTaskStatus.PENDING), any())).thenReturn(1);

            service.endSession("1");
            service.endSession("1");

            verify(producer, times(1)).sendEvaluateTask("1");
        }

        @Test
        @DisplayName("WebSocket 重连（重复触发兜底结束）不会重复评估")
        void reconnectShouldNotDuplicateEvaluation() {
            VoiceInterviewSessionEntity session = session(VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
            when(sessionRepository.updateStatusConditionally(
                eq(1L), eq(VoiceInterviewSessionStatus.COMPLETED), anyList())).thenReturn(1);
            when(sessionRepository.initializeEvaluateStatus(
                eq(1L), eq(AsyncTaskStatus.PENDING), any())).thenReturn(1);

            service.endSessionIfInProgress("1");
            service.endSessionIfInProgress("1");

            verify(producer, times(1)).sendEvaluateTask("1");
        }

        @Test
        @DisplayName("并发结束导致条件更新失败时不入队")
        void concurrentEndShouldNotEnqueue() {
            VoiceInterviewSessionEntity session = session(VoiceInterviewSessionStatus.IN_PROGRESS);
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
            when(sessionRepository.updateStatusConditionally(
                eq(1L), eq(VoiceInterviewSessionStatus.COMPLETED), anyList())).thenReturn(0);

            service.endSession("1");

            verify(producer, never()).sendEvaluateTask(anyString());
        }
    }

    @Nested
    @DisplayName("暂停与恢复")
    class PauseAndResume {

        @Test
        @DisplayName("已完成的会话不能暂停，返回非法迁移错误")
        void completedCannotPause() {
            when(sessionRepository.findById(1L))
                .thenReturn(Optional.of(session(VoiceInterviewSessionStatus.COMPLETED)));

            assertThatThrownBy(() -> service.pauseSession("1", "user_initiated"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION.getCode());
        }

        @Test
        @DisplayName("重复暂停视为幂等，不写库")
        void repeatedPauseIsIdempotent() {
            when(sessionRepository.findById(1L))
                .thenReturn(Optional.of(session(VoiceInterviewSessionStatus.PAUSED)));

            service.pauseSession("1", "user_initiated");

            verify(sessionRepository, never()).save(any());
            verify(sessionRepository, never()).updateStatusConditionally(any(), any(), anyList());
        }

        @Test
        @DisplayName("已完成的会话不能恢复，返回非法迁移错误")
        void completedCannotResume() {
            when(sessionRepository.findById(1L))
                .thenReturn(Optional.of(session(VoiceInterviewSessionStatus.COMPLETED)));

            assertThatThrownBy(() -> service.resumeSession("1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION.getCode());
        }
    }

    @Nested
    @DisplayName("评估触发幂等")
    class TriggerEvaluation {

        @Test
        @DisplayName("状态可以迁移到 PENDING 时入队一次")
        void shouldEnqueueWhenClaimable() {
            when(sessionRepository.initializeEvaluateStatus(
                eq(1L), eq(AsyncTaskStatus.PENDING), any())).thenReturn(1);

            service.triggerEvaluation(1L);

            verify(producer).sendEvaluateTask("1");
        }

        @Test
        @DisplayName("已完成或处理中时重复触发不再入队")
        void shouldSkipWhenNotClaimable() {
            when(sessionRepository.initializeEvaluateStatus(
                eq(1L), eq(AsyncTaskStatus.PENDING), any())).thenReturn(0);
            when(sessionRepository.updateEvaluateStatusConditionally(
                eq(1L), eq(AsyncTaskStatus.PENDING), eq(List.of(AsyncTaskStatus.FAILED)), any()))
                .thenReturn(0);

            service.triggerEvaluation(1L);

            verify(producer, never()).sendEvaluateTask(anyString());
        }

        @Test
        @DisplayName("失败任务可重放：FAILED -> PENDING 后重新入队")
        void failedTaskCanBeReplayed() {
            when(sessionRepository.initializeEvaluateStatus(
                eq(1L), eq(AsyncTaskStatus.PENDING), any())).thenReturn(0);
            when(sessionRepository.updateEvaluateStatusConditionally(
                eq(1L), eq(AsyncTaskStatus.PENDING), eq(List.of(AsyncTaskStatus.FAILED)), any()))
                .thenReturn(1);

            service.triggerEvaluation(1L);

            verify(producer).sendEvaluateTask("1");
        }
    }

    @Nested
    @DisplayName("消费者状态迁移")
    class ConsumerTransitions {

        @Test
        @DisplayName("抢占失败返回 false，重复消息应 ACK 丢弃")
        void claimFailureShouldReturnFalse() {
            when(sessionRepository.claimEvaluateStatus(
                1L, AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING)).thenReturn(0);

            assertThat(service.claimEvaluationProcessing(1L)).isFalse();
        }

        @Test
        @DisplayName("FAILED 状态通过条件更新写入并截断错误信息")
        void failShouldWriteConditionally() {
            when(sessionRepository.updateEvaluateStatusConditionally(
                eq(1L), eq(AsyncTaskStatus.FAILED), anyList(), anyString())).thenReturn(1);

            service.failEvaluation(1L, "y".repeat(700));

            verify(sessionRepository).updateEvaluateStatusConditionally(
                eq(1L), eq(AsyncTaskStatus.FAILED), anyList(), eq("y".repeat(500)));
        }
    }

    private VoiceInterviewSessionEntity session(VoiceInterviewSessionStatus status) {
        return VoiceInterviewSessionEntity.builder()
            .id(1L)
            .status(status)
            .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
            .startTime(LocalDateTime.now().minusMinutes(5))
            .build();
    }
}
