package interview.guide.modules.voiceinterview.service;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 语音会话审计日志。
 *
 * <p>只记录「事件元数据」：事件类型、会话 ID、连接 ID、原因与时间戳。
 * 严禁写入音频内容、ASR 识别文本、简历或 JD 正文——这些字段永远不会传入本类。</p>
 *
 * <p>落盘策略：结构化 SLF4J（logger 名 {@code voice.session.audit}，便于日志平台按 logger 过滤）
 * + Redis List（{@code voice:interview:audit:{sessionId}}，默认保留 7 天），
 * Redis 写失败只记 warn，绝不阻断会话主流程。</p>
 */
@Service
@Slf4j(topic = "voice.session.audit")
@RequiredArgsConstructor
public class VoiceSessionAuditLogger {

    static final String AUDIT_KEY_PREFIX = "voice:interview:audit:";
    private static final int MAX_REASON_LENGTH = 160;

    private final RedisService redisService;
    private final VoiceInterviewProperties properties;
    private final Clock clock = Clock.systemUTC();

    /**
     * 记录一条审计事件。
     *
     * @param sessionId    语音会话 ID
     * @param event        事件类型
     * @param connectionId 连接标识，可为 null（如对账任务触发）
     * @param reason       结构化原因，会截断并去除换行
     */
    public void record(String sessionId, Event event, String connectionId, String reason) {
        String safeReason = sanitize(reason);
        log.info("event={} sessionId={} connectionId={} reason={}",
            event, sessionId, connectionId, safeReason);
        String line = "at=" + Instant.ofEpochMilli(clock.millis())
            + " event=" + event
            + " sessionId=" + sessionId
            + " connectionId=" + (connectionId == null ? "-" : connectionId)
            + " reason=" + safeReason;
        try {
            redisService.listRightPush(auditKey(sessionId), line);
            redisService.expire(auditKey(sessionId), Duration.ofDays(auditTtlDays()));
        } catch (RuntimeException e) {
            log.warn("Voice session audit persistence failed: sessionId={}, event={}, error={}",
                sessionId, event, e.getMessage());
        }
    }

    /**
     * 读取会话最近的审计事件（按写入顺序）。
     *
     * @return Redis 不可用时返回空列表，调用方按「无审计数据」处理
     */
    public List<String> recentEvents(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            List<String> events = redisService.listGetAll(auditKey(sessionId));
            return events == null ? List.of() : new ArrayList<>(events);
        } catch (RuntimeException e) {
            log.warn("Voice session audit read failed: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private int auditTtlDays() {
        return Math.max(1, properties.getLifecycle().getAuditTtlDays());
    }

    private static String auditKey(String sessionId) {
        return AUDIT_KEY_PREFIX + sessionId;
    }

    private static String sanitize(String reason) {
        if (reason == null || reason.isBlank()) {
            return "-";
        }
        String singleLine = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= MAX_REASON_LENGTH
            ? singleLine
            : singleLine.substring(0, MAX_REASON_LENGTH);
    }

    /**
     * 会话生命周期事件类型。事件名会被日志平台与审计检索直接使用，重命名需评估兼容性。
     */
    public enum Event {
        SESSION_CONNECTED,
        CONNECTION_REJECTED_DUPLICATE,
        CONNECTION_CLOSED_STALE,
        LEASE_ACQUIRED,
        LEASE_DEGRADED,
        LEASE_LOST,
        HEARTBEAT_TIMEOUT,
        SLOW_CLIENT_DISCONNECT,
        PROTOCOL_VIOLATION,
        ASR_RESTART,
        ASR_UNAVAILABLE,
        TTS_FALLBACK,
        SESSION_PAUSED,
        SESSION_FINALIZED,
        SESSION_FINALIZE_RETRY,
        SESSION_FINALIZE_DEAD_LETTER,
        SESSION_ORPHAN_RECONCILED,
        SERVICE_SHUTDOWN
    }
}
