package interview.guide.modules.voiceinterview.repository;

import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 语音面试消息Repository
 */
@Repository
public interface VoiceInterviewMessageRepository extends JpaRepository<VoiceInterviewMessageEntity, Long> {

    /**
     * 根据会话ID查找所有消息，按序号升序排列
     */
    List<VoiceInterviewMessageEntity> findBySessionIdOrderBySequenceNumAsc(Long sessionId);

    Optional<VoiceInterviewMessageEntity>
        findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
            Long sessionId);

    long countBySessionId(Long sessionId);

    /**
     * 批量统计多个会话的消息数，避免列表页循环单查（N+1）。
     */
    @Query("select m.sessionId, count(m) from VoiceInterviewMessageEntity m "
        + "where m.sessionId in :sessionIds group by m.sessionId")
    List<Object[]> countBySessionIds(@Param("sessionIds") List<Long> sessionIds);

    void deleteBySessionId(Long sessionId);
}
