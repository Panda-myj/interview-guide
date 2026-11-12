package interview.guide.modules.interview.model;

import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面试会话状态迁移表测试（PERF-001）。
 */
@DisplayName("面试会话状态机")
class InterviewSessionStatusTest {

    @Nested
    @DisplayName("合法迁移")
    class LegalTransitions {

        @Test
        @DisplayName("CREATED 可以进入 IN_PROGRESS 或直接 COMPLETED")
        void createdCanStartOrComplete() {
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.CREATED, SessionStatus.IN_PROGRESS))
                .isTrue();
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.CREATED, SessionStatus.COMPLETED))
                .isTrue();
        }

        @Test
        @DisplayName("IN_PROGRESS 可以推进或完成，同状态视为幂等")
        void inProgressCanContinueOrComplete() {
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.IN_PROGRESS, SessionStatus.IN_PROGRESS))
                .isTrue();
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED))
                .isTrue();
        }

        @Test
        @DisplayName("只有 COMPLETED 可以进入 EVALUATED")
        void onlyCompletedCanBeEvaluated() {
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.COMPLETED, SessionStatus.EVALUATED))
                .isTrue();
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.EVALUATED, SessionStatus.EVALUATED))
                .isTrue();
        }

        @Test
        @DisplayName("null 状态只能初始化到 CREATED")
        void nullOnlyInitializesToCreated() {
            assertThat(InterviewSessionEntity.canTransition(null, SessionStatus.CREATED)).isTrue();
            assertThat(InterviewSessionEntity.allowedTargets(null))
                .containsExactly(SessionStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("非法迁移")
    class IllegalTransitions {

        @Test
        @DisplayName("EVALUATED 为终态，不可回退")
        void evaluatedIsTerminal() {
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.EVALUATED, SessionStatus.COMPLETED))
                .isFalse();
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.EVALUATED, SessionStatus.IN_PROGRESS))
                .isFalse();
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.EVALUATED, SessionStatus.CREATED))
                .isFalse();
            assertThat(InterviewSessionEntity.allowedTargets(SessionStatus.EVALUATED))
                .containsExactly(SessionStatus.EVALUATED);
        }

        @Test
        @DisplayName("COMPLETED 不可回退到进行中或创建中")
        void completedCannotRegress() {
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS))
                .isFalse();
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.COMPLETED, SessionStatus.CREATED))
                .isFalse();
        }

        @Test
        @DisplayName("未完成会话不可直接评估")
        void unfinishedCannotBeEvaluated() {
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.CREATED, SessionStatus.EVALUATED))
                .isFalse();
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.IN_PROGRESS, SessionStatus.EVALUATED))
                .isFalse();
        }

        @Test
        @DisplayName("目标状态为 null 时一律非法")
        void nullTargetIsIllegal() {
            assertThat(InterviewSessionEntity.canTransition(SessionStatus.CREATED, null)).isFalse();
            assertThat(InterviewSessionEntity.canTransition(null, null)).isFalse();
        }
    }
}
