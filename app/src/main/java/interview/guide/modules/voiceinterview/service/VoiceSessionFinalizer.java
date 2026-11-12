package interview.guide.modules.voiceinterview.service;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音会话收尾器。
 *
 * <p>WebSocket 断开、心跳超时、租约丢失、孤儿回收等场景统一走这里收尾：
 * 调用 {@link VoiceInterviewService#endSessionIfInProgress(String)} 做幂等结束
 * （内部是条件更新 + 评估入队幂等，重复调用不会产生重复报告）。</p>
 *
 * <p>数据库异常时不吞异常：记录 error 日志与审计，写入 Redis 补偿队列，
 * 由 {@link #retryPendingFinalizations()} 定时重试；超过上限进入死信队列，便于人工检索与重放。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceSessionFinalizer {

    static final String RETRY_KEY = "voice:interview:finalize:retry";
    static final String DEAD_LETTER_KEY = "voice:interview:finalize:dead-letter";
    private static final String FIELD_SEPARATOR = "|";

    private final VoiceInterviewService voiceInterviewService;
    private final RedisService redisService;
    private final VoiceSessionAuditLogger auditLogger;
    private final VoiceInterviewProperties properties;

    /**
     * 收尾一次会话。失败时进入补偿队列并返回 false。
     */
    public boolean finalizeSession(String sessionId, String reason, String connectionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (attemptFinalize(sessionId, reason, connectionId)) {
            return true;
        }
        enqueueRetry(new FinalizeTask(sessionId, reason, connectionId, 1));
        return false;
    }

    /**
     * 重试补偿队列中的收尾任务，超过上限转死信。
     *
     * @return 本轮仍有待重试的任务数
     */
    @Scheduled(fixedDelay = 30_000)
    public int retryPendingFinalizations() {
        List<String> pending;
        try {
            pending = redisService.listGetAll(RETRY_KEY);
        } catch (RuntimeException e) {
            log.warn("Voice session finalize retry queue unavailable: {}", e.getMessage());
            return 0;
        }
        if (pending == null || pending.isEmpty()) {
            return 0;
        }

        List<FinalizeTask> survivors = new ArrayList<>();
        for (String raw : pending) {
            FinalizeTask task = FinalizeTask.parse(raw);
            if (task == null) {
                log.warn("Malformed finalize retry entry ignored");
                continue;
            }
            if (attemptFinalize(task.sessionId(), task.reason(), task.connectionId())) {
                continue;
            }
            int nextAttempt = task.attempt() + 1;
            if (nextAttempt > maxAttempts()) {
                deadLetter(task, nextAttempt);
                continue;
            }
            survivors.add(new FinalizeTask(task.sessionId(), task.reason(), task.connectionId(), nextAttempt));
        }

        // RedisService 未提供列表元素删除，这里采用「读全量 → 删除 → 重写存活项」的方式保证幂等收敛
        try {
            redisService.delete(RETRY_KEY);
            survivors.forEach(this::enqueueRetry);
        } catch (RuntimeException e) {
            log.warn("Voice session finalize retry queue rewrite failed: {}", e.getMessage());
        }
        return survivors.size();
    }

    /**
     * 待重试的收尾任务数，供运维排查与测试断言。
     */
    public int pendingRetryCount() {
        try {
            List<String> pending = redisService.listGetAll(RETRY_KEY);
            return pending == null ? 0 : pending.size();
        } catch (RuntimeException e) {
            log.warn("Voice session finalize retry queue unavailable: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 死信任务数，供运维排查。
     */
    public int deadLetterCount() {
        try {
            List<String> deadLetters = redisService.listGetAll(DEAD_LETTER_KEY);
            return deadLetters == null ? 0 : deadLetters.size();
        } catch (RuntimeException e) {
            log.warn("Voice session dead letter queue unavailable: {}", e.getMessage());
            return 0;
        }
    }

    private boolean attemptFinalize(String sessionId, String reason, String connectionId) {
        try {
            voiceInterviewService.endSessionIfInProgress(sessionId);
            auditLogger.record(sessionId, VoiceSessionAuditLogger.Event.SESSION_FINALIZED, connectionId, reason);
            return true;
        } catch (RuntimeException e) {
            log.error("Voice session finalize failed: sessionId={}, reason={}", sessionId, reason, e);
            auditLogger.record(sessionId, VoiceSessionAuditLogger.Event.SESSION_FINALIZE_RETRY, connectionId,
                reason + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void enqueueRetry(FinalizeTask task) {
        try {
            redisService.listRightPush(RETRY_KEY, task.encode());
        } catch (RuntimeException e) {
            log.error("Voice session finalize retry enqueue failed: sessionId={}, reason={}",
                task.sessionId(), task.reason(), e);
            auditLogger.record(task.sessionId(), VoiceSessionAuditLogger.Event.SESSION_FINALIZE_DEAD_LETTER,
                task.connectionId(), "retry_queue_unavailable");
        }
    }

    private void deadLetter(FinalizeTask task, int attempts) {
        String line = task.sessionId() + FIELD_SEPARATOR + task.reason()
            + FIELD_SEPARATOR + task.connectionId() + FIELD_SEPARATOR + attempts;
        try {
            redisService.listRightPush(DEAD_LETTER_KEY, line);
        } catch (RuntimeException e) {
            log.error("Voice session dead letter write failed: sessionId={}", task.sessionId(), e);
        }
        log.error("Voice session finalize moved to dead letter: sessionId={}, reason={}, attempts={}",
            task.sessionId(), task.reason(), attempts);
        auditLogger.record(task.sessionId(), VoiceSessionAuditLogger.Event.SESSION_FINALIZE_DEAD_LETTER,
            task.connectionId(), "attempts=" + attempts);
    }

    private int maxAttempts() {
        return Math.max(1, properties.getLifecycle().getFinalizeMaxAttempts());
    }

    private record FinalizeTask(String sessionId, String reason, String connectionId, int attempt) {

        String encode() {
            return sessionId + FIELD_SEPARATOR + reason
                + FIELD_SEPARATOR + (connectionId == null ? "-" : connectionId)
                + FIELD_SEPARATOR + attempt;
        }

        static FinalizeTask parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.split("\\" + FIELD_SEPARATOR, -1);
            if (parts.length != 4) {
                return null;
            }
            try {
                String reason = "-".equals(parts[1]) ? null : parts[1];
                String connectionId = "-".equals(parts[2]) ? null : parts[2];
                return new FinalizeTask(parts[0], reason, connectionId, Integer.parseInt(parts[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
