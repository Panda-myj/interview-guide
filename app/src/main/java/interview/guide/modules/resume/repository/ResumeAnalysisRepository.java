package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 简历评测Repository
 */
@Repository
public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysisEntity, Long> {

    /**
     * 根据简历查找所有评测记录
     */
    List<ResumeAnalysisEntity> findByResumeOrderByAnalyzedAtDesc(ResumeEntity resume);

    /**
     * 根据简历ID查找最新评测记录
     */
    ResumeAnalysisEntity findFirstByResumeIdOrderByAnalyzedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找所有评测记录
     */
    List<ResumeAnalysisEntity> findByResumeIdOrderByAnalyzedAtDesc(Long resumeId);

    /**
     * 批量加载多份简历的评测记录（analyzed_at 倒序）。
     * 列表页取「每份简历最新一条」时在内存中按 resumeId 取首条即可，
     * 避免逐条简历发起 N 次查询（PERF-002）。
     */
    @Query("SELECT a FROM ResumeAnalysisEntity a WHERE a.resume.id IN :resumeIds ORDER BY a.analyzedAt DESC")
    List<ResumeAnalysisEntity> findByResumeIdsOrderByAnalyzedAtDesc(@Param("resumeIds") List<Long> resumeIds);

    /**
     * 批量统计各简历的评测次数（单条 GROUP BY，替代逐简历 count）。
     * 返回列：resumeId, cnt。
     */
    @Query("SELECT a.resume.id, COUNT(a) FROM ResumeAnalysisEntity a WHERE a.resume.id IN :resumeIds GROUP BY a.resume.id")
    List<Object[]> countByResumeIds(@Param("resumeIds") List<Long> resumeIds);
}
