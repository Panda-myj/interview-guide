package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿语音会话对账任务。
 *
 * <p>覆盖「服务重启 / Redis 丢失 / WebSocket 未触发关闭回调」等场景：
 * 扫描 {@code IN_PROGRESS} 且超过宽限期未更新的会话，若不存在有效租约则认为连接已失联，
 * 交给 {@link VoiceSessionFinalizer} 做幂等收尾（结束会话 + 入队评估）。</p>
 *
 * <p>安全阀：Redis 处于降级状态时整体跳过，避免在无法判断其它节点存活租约时误结束会话。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceSessionReconciler {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceSessionLeaseService leaseService;
    private final VoiceSessionFinalizer finalizer;
    private final VoiceSessionAuditLogger auditLogger;
    private final VoiceInterviewProperties properties;

    /**
     * 回收孤儿会话。
     *
     * @return 本轮成功收尾的会话数
     */
    @Scheduled(fixedDelayString = "${app.voice-interview.lifecycle.orphan-reconcile-interval-ms:60000}")
    public int reconcileOrphanSessions() {
        if (leaseService.isDegraded()) {
            log.warn("Skip orphan voice session reconciliation: lease service degraded");
            return 0;
        }
        try {
            LocalDateTime threshold = LocalDateTime.now()
                .minusSeconds(Math.max(1, properties.getLifecycle().getOrphanReconcileGraceSeconds()));
            List<VoiceInterviewSessionEntity> candidates = sessionRepository
                .findByStatusAndUpdatedAtBefore(VoiceInterviewSessionStatus.IN_PROGRESS, threshold);

            int finalized = 0;
            for (VoiceInterviewSessionEntity session : candidates) {
                String sessionId = String.valueOf(session.getId());
                if (leaseService.hasActiveLease(sessionId)) {
                    continue;
                }
                log.info("Reconciling orphan voice session: sessionId={}, updatedAt={}",
                    sessionId, session.getUpdatedAt());
                if (finalizer.finalizeSession(sessionId, "orphan_reconciled", null)) {
                    auditLogger.record(sessionId, VoiceSessionAuditLogger.Event.SESSION_ORPHAN_RECONCILED,
                        null, "updatedAt=" + session.getUpdatedAt());
                    finalized++;
                }
            }
            return finalized;
        } catch (RuntimeException e) {
            // 定时任务不允许异常逃逸，否则会被调度器静默取消；这里显式记录
            log.error("Orphan voice session reconciliation failed", e);
            return 0;
        }
    }
}
