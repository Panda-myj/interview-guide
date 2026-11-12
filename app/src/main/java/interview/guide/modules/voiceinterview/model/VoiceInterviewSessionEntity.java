package interview.guide.modules.voiceinterview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.model.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "voice_interview_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private UserEntity ownerUser;

    @Column(name = "role_type", nullable = false)
    private String roleType;

    @Column(name = "skill_id", length = 64)
    @Builder.Default
    private String skillId = "java-backend";

    @Column(name = "difficulty", length = 16)
    @Builder.Default
    private String difficulty = "mid";

    @Column(name = "custom_jd_text", columnDefinition = "TEXT")
    private String customJdText;

    @Column(name = "resume_id")
    private Long resumeId;

    @Column(name = "intro_enabled")
    @Builder.Default
    private Boolean introEnabled = true;

    @Column(name = "tech_enabled")
    @Builder.Default
    private Boolean techEnabled = true;

    @Column(name = "project_enabled")
    @Builder.Default
    private Boolean projectEnabled = true;

    @Column(name = "hr_enabled")
    @Builder.Default
    private Boolean hrEnabled = true;

    @Column(name = "llm_provider", length = 50)
    @Builder.Default
    private String llmProvider = "dashscope";

    @Column(name = "current_phase")
    @Enumerated(EnumType.STRING)
    private InterviewPhase currentPhase;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private VoiceInterviewSessionStatus status = VoiceInterviewSessionStatus.IN_PROGRESS;

    @Column(name = "planned_duration")
    @Builder.Default
    private Integer plannedDuration = 30;

    @Column(name = "actual_duration")
    private Integer actualDuration;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    @Column(name = "evaluate_status")
    @Enumerated(EnumType.STRING)
    private AsyncTaskStatus evaluateStatus;

    @Column(name = "evaluate_error", length = 500)
    private String evaluateError;

    @Column(name = "evaluate_attempt")
    @Builder.Default
    private Integer evaluateAttempt = 0;

    /**
     * 语音面试会话状态迁移表：key 为当前状态，value 为允许迁移到的目标状态。
     * 同状态迁移视为幂等空操作（例如 WebSocket 重连重复结束会话）。
     */
    private static final Map<VoiceInterviewSessionStatus, Set<VoiceInterviewSessionStatus>>
        ALLOWED_STATUS_TRANSITIONS = Map.of(
            VoiceInterviewSessionStatus.IN_PROGRESS, EnumSet.of(
                VoiceInterviewSessionStatus.IN_PROGRESS,
                VoiceInterviewSessionStatus.PAUSED,
                VoiceInterviewSessionStatus.COMPLETED,
                VoiceInterviewSessionStatus.FAILED),
            VoiceInterviewSessionStatus.PAUSED, EnumSet.of(
                VoiceInterviewSessionStatus.PAUSED,
                VoiceInterviewSessionStatus.IN_PROGRESS,
                VoiceInterviewSessionStatus.COMPLETED,
                VoiceInterviewSessionStatus.FAILED),
            VoiceInterviewSessionStatus.COMPLETED, EnumSet.of(VoiceInterviewSessionStatus.COMPLETED),
            VoiceInterviewSessionStatus.FAILED, EnumSet.of(VoiceInterviewSessionStatus.FAILED)
        );

    /**
     * 判断会话状态迁移是否合法。
     *
     * @param from 当前状态，null 表示尚未初始化
     * @param to   目标状态
     */
    public static boolean canTransition(VoiceInterviewSessionStatus from,
                                        VoiceInterviewSessionStatus to) {
        if (to == null) {
            return false;
        }
        if (from == null) {
            return to == VoiceInterviewSessionStatus.IN_PROGRESS;
        }
        return ALLOWED_STATUS_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 返回指定状态允许迁移到的目标状态集合。
     */
    public static Set<VoiceInterviewSessionStatus> allowedTargets(VoiceInterviewSessionStatus from) {
        if (from == null) {
            return EnumSet.of(VoiceInterviewSessionStatus.IN_PROGRESS);
        }
        return ALLOWED_STATUS_TRANSITIONS.getOrDefault(from, Set.of());
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.startTime = LocalDateTime.now();
    }

    public enum InterviewPhase {
        INTRO, TECH, PROJECT, HR, COMPLETED
    }
}
