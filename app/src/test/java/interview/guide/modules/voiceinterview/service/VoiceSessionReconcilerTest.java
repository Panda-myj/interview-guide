package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 孤儿语音会话对账测试（VOICE-001）：服务重启 / Redis 丢失后的可恢复最终化。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("孤儿语音会话对账")
class VoiceSessionReconcilerTest {

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;
    @Mock
    private VoiceSessionLeaseService leaseService;
    @Mock
    private VoiceSessionFinalizer finalizer;
    @Mock
    private VoiceSessionAuditLogger auditLogger;

    private VoiceSessionReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new VoiceSessionReconciler(sessionRepository, leaseService, finalizer, auditLogger,
            new VoiceInterviewProperties());
    }

    @Test
    @DisplayName("无有效租约的 IN_PROGRESS 会话被收尾")
    void orphanSessionShouldBeFinalized() {
        VoiceInterviewSessionEntity session = session(7L);
        when(sessionRepository.findByStatusAndUpdatedAtBefore(
            eq(VoiceInterviewSessionStatus.IN_PROGRESS), any(LocalDateTime.class)))
            .thenReturn(List.of(session));
        when(leaseService.hasActiveLease("7")).thenReturn(false);
        when(finalizer.finalizeSession("7", "orphan_reconciled", null)).thenReturn(true);

        int finalized = reconciler.reconcileOrphanSessions();

        assertThat(finalized).isEqualTo(1);
        verify(finalizer).finalizeSession("7", "orphan_reconciled", null);
        verify(auditLogger).record(eq("7"), eq(VoiceSessionAuditLogger.Event.SESSION_ORPHAN_RECONCILED),
            isNull(), anyString());
    }

    @Test
    @DisplayName("仍有有效租约的会话不会被误收尾")
    void sessionWithActiveLeaseShouldBeSkipped() {
        when(sessionRepository.findByStatusAndUpdatedAtBefore(
            eq(VoiceInterviewSessionStatus.IN_PROGRESS), any(LocalDateTime.class)))
            .thenReturn(List.of(session(7L)));
        when(leaseService.hasActiveLease("7")).thenReturn(true);

        assertThat(reconciler.reconcileOrphanSessions()).isZero();
        verify(finalizer, never()).finalizeSession(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("租约服务降级时整体跳过对账，避免误判其它节点存活会话")
    void degradedLeaseServiceShouldSkipReconciliation() {
        when(leaseService.isDegraded()).thenReturn(true);

        assertThat(reconciler.reconcileOrphanSessions()).isZero();
        verify(sessionRepository, never()).findByStatusAndUpdatedAtBefore(any(), any());
    }

    @Test
    @DisplayName("数据库异常被捕获，定时任务不中断")
    void repositoryFailureShouldNotEscape() {
        when(sessionRepository.findByStatusAndUpdatedAtBefore(any(), any()))
            .thenThrow(new IllegalStateException("db down"));

        assertThat(reconciler.reconcileOrphanSessions()).isZero();
    }

    private VoiceInterviewSessionEntity session(Long id) {
        VoiceInterviewSessionEntity entity = VoiceInterviewSessionEntity.builder()
            .id(id)
            .roleType("java-backend")
            .build();
        entity.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        entity.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return entity;
    }
}
