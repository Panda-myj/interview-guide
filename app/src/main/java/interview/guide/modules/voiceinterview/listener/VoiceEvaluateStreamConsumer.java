package interview.guide.modules.voiceinterview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估 Stream 消费者
 */
@Slf4j
@Component
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload> {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final VoiceInterviewSessionRepository sessionRepository;

    public VoiceEvaluateStreamConsumer(
        RedisService redisService,
        VoiceInterviewService voiceInterviewService,
        VoiceInterviewEvaluationService evaluationService,
        VoiceInterviewSessionRepository sessionRepository
    ) {
        super(redisService);
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
        this.sessionRepository = sessionRepository;
    }

    record VoiceEvaluatePayload(Long sessionId) {}

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "voice-evaluate-consumer";
    }

    @Override
    protected VoiceEvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VoiceEvaluatePayload(Long.parseLong(sessionId));
    }

    @Override
    protected String payloadIdentifier(VoiceEvaluatePayload payload) {
        return "voiceSessionId=" + payload.sessionId();
    }

    @Override
    protected boolean shouldSkip(VoiceEvaluatePayload payload) {
        return sessionRepository.findById(payload.sessionId())
            .map(session -> session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    /**
     * 条件占位：PENDING → PROCESSING。
     * 返回 false 表示消息重复、乱序或已被其它实例抢占，直接 ACK 丢弃。
     */
    @Override
    protected boolean tryClaimProcessing(VoiceEvaluatePayload payload) {
        return voiceInterviewService.claimEvaluationProcessing(payload.sessionId());
    }

    @Override
    protected void markProcessing(VoiceEvaluatePayload payload) {
        // 条件占位由 tryClaimProcessing 完成；此处保留实现以兼容模板默认路径
        voiceInterviewService.claimEvaluationProcessing(payload.sessionId());
    }

    @Override
    protected void processBusiness(VoiceEvaluatePayload payload) {
        Long sessionId = payload.sessionId();
        if (!sessionRepository.existsById(sessionId)) {
            log.warn("语音面试会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }
        evaluationService.generateEvaluation(sessionId);
        log.info("语音面试评估完成: sessionId={}", sessionId);
    }

    @Override
    protected void markCompleted(VoiceEvaluatePayload payload) {
        voiceInterviewService.completeEvaluation(payload.sessionId());
    }

    @Override
    protected void markFailed(VoiceEvaluatePayload payload, String error) {
        voiceInterviewService.failEvaluation(payload.sessionId(), error);
    }

    @Override
    protected void retryMessage(VoiceEvaluatePayload payload, int retryCount) {
        Long sessionId = payload.sessionId();

        // PROCESSING → PENDING 条件回退；失败说明任务已完成或状态被其它实例修改，不再重复入队
        if (!voiceInterviewService.resetEvaluationForRetry(sessionId)) {
            log.info("语音评估任务状态不可回退，放弃重试: sessionId={}, retryCount={}",
                sessionId, retryCount);
            return;
        }

        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("语音面试评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);
        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, retryCount={}", sessionId, retryCount, e);
            voiceInterviewService.failEvaluation(
                sessionId, truncateError("重试入队失败: " + e.getMessage()));
        }
    }
}
