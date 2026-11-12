package interview.guide.modules.voiceinterview.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VoiceInterviewSessionStatusTest {

    @Test
    void shouldHaveFourStatuses() {
        VoiceInterviewSessionStatus[] statuses = VoiceInterviewSessionStatus.values();
        assertEquals(4, statuses.length);
    }

    @Test
    void shouldHaveExpectedStatusNames() {
        assertEquals("IN_PROGRESS", VoiceInterviewSessionStatus.IN_PROGRESS.name());
        assertEquals("PAUSED", VoiceInterviewSessionStatus.PAUSED.name());
        assertEquals("COMPLETED", VoiceInterviewSessionStatus.COMPLETED.name());
        assertEquals("FAILED", VoiceInterviewSessionStatus.FAILED.name());
    }

    @Nested
    @DisplayName("语音会话状态迁移表")
    class StatusTransitions {

        @Test
        @DisplayName("IN_PROGRESS 可暂停、完成或失败")
        void inProgressCanPauseCompleteOrFail() {
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.IN_PROGRESS, VoiceInterviewSessionStatus.PAUSED)).isTrue();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.IN_PROGRESS, VoiceInterviewSessionStatus.COMPLETED)).isTrue();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.IN_PROGRESS, VoiceInterviewSessionStatus.FAILED)).isTrue();
        }

        @Test
        @DisplayName("PAUSED 可恢复、完成或失败")
        void pausedCanResumeCompleteOrFail() {
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.PAUSED, VoiceInterviewSessionStatus.IN_PROGRESS)).isTrue();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.PAUSED, VoiceInterviewSessionStatus.COMPLETED)).isTrue();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.PAUSED, VoiceInterviewSessionStatus.FAILED)).isTrue();
        }

        @Test
        @DisplayName("COMPLETED 与 FAILED 为终态")
        void terminalStatusesCannotMove() {
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.COMPLETED, VoiceInterviewSessionStatus.IN_PROGRESS)).isFalse();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.COMPLETED, VoiceInterviewSessionStatus.PAUSED)).isFalse();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.FAILED, VoiceInterviewSessionStatus.IN_PROGRESS)).isFalse();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.FAILED, VoiceInterviewSessionStatus.COMPLETED)).isFalse();
        }

        @Test
        @DisplayName("同状态迁移视为幂等")
        void sameStateIsIdempotent() {
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.IN_PROGRESS, VoiceInterviewSessionStatus.IN_PROGRESS)).isTrue();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.PAUSED, VoiceInterviewSessionStatus.PAUSED)).isTrue();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                VoiceInterviewSessionStatus.COMPLETED, VoiceInterviewSessionStatus.COMPLETED)).isTrue();
        }

        @Test
        @DisplayName("null 状态只能初始化到 IN_PROGRESS")
        void nullOnlyInitializesToInProgress() {
            assertThat(VoiceInterviewSessionEntity.canTransition(
                null, VoiceInterviewSessionStatus.IN_PROGRESS)).isTrue();
            assertThat(VoiceInterviewSessionEntity.canTransition(
                null, VoiceInterviewSessionStatus.COMPLETED)).isFalse();
        }
    }
}
