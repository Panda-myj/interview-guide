package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 简历历史服务
 * 简历历史和导出简历分析报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHistoryService {

    private final ResumePersistenceService resumePersistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final InterviewMapper interviewMapper;
    private final FileStorageService fileStorageService;

    /** 列表接口默认返回条数。 */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** 列表接口单次返回的硬上限，防止全量拉取。 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 获取所有简历列表（截断式分页：默认 50 条、上限 200 条）。
     *
     * <p>N+1 修复（PERF-002）：最新评测与面试次数各由一条批量聚合查询取得，
     * 循环内不再发起单条查询。响应仍为 List 形状（截断而非改契约），
     * 后续如需翻页/总数应演进为 Page 契约并同步前端。</p>
     */
    public List<ResumeListItemDTO> getAllResumes(int size) {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<ResumeEntity> resumes = resumePersistenceService.findAllResumes(
            PageRequest.of(0, pageSize));
        if (resumes.isEmpty()) {
            return List.of();
        }

        List<Long> resumeIds = resumes.stream().map(ResumeEntity::getId).toList();
        Map<Long, ResumeAnalysisEntity> latestAnalyses =
            resumePersistenceService.findLatestAnalysesByResumeIds(resumeIds);
        Map<Long, Long> interviewCounts =
            interviewPersistenceService.countSessionsByResumeIds(resumeIds);

        return resumes.stream().map(resume -> {
            ResumeAnalysisEntity analysis = latestAnalyses.get(resume.getId());
            // 使用 MapStruct 映射
            return new ResumeListItemDTO(
                resume.getId(),
                resume.getOriginalFilename(),
                resume.getFileSize(),
                resume.getUploadedAt(),
                resume.getAccessCount(),
                analysis != null ? analysis.getOverallScore() : null,
                analysis != null ? analysis.getAnalyzedAt() : null,
                interviewCounts.getOrDefault(resume.getId(), 0L).intValue(),
                resume.getAnalyzeStatus(),
                resume.getAnalyzeError()
            );
        }).toList();
    }

    /**
     * 获取所有简历列表（保持向后兼容，默认 50 条）
     */
    public List<ResumeListItemDTO> getAllResumes() {
        return getAllResumes(DEFAULT_PAGE_SIZE);
    }

    /**
     * 获取简历详情（包含分析历史）
     */
    public ResumeDetailDTO getResumeDetail(Long id) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(id);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();

        // 获取所有分析记录，使用 MapStruct 批量转换
        List<ResumeAnalysisEntity> analyses = resumePersistenceService.findAnalysesByResumeId(id);
        List<ResumeDetailDTO.AnalysisHistoryDTO> analysisHistory = resumeMapper.toAnalysisHistoryDTOList(
            analyses,
            this::extractStrengths,
            this::extractSuggestions
        );

        // 使用 InterviewMapper 转换面试历史
        List<InterviewHistoryItemDTO> interviewHistory = interviewMapper.toInterviewHistoryList(
            interviewPersistenceService.findByResumeId(id)
        );

        return new ResumeDetailDTO(
            resume.getId(),
            resume.getOriginalFilename(),
            resume.getFileSize(),
            resume.getContentType(),
            resume.getStorageUrl(),
            resume.getUploadedAt(),
            resume.getAccessCount(),
            resume.getResumeText(),
            resume.getAnalyzeStatus(),
            resume.getAnalyzeError(),
            analysisHistory,
            interviewHistory
        );
    }

    /**
     * 从 JSON 提取 strengths
     */
    private List<String> extractStrengths(ResumeAnalysisEntity entity) {
        try {
            if (entity.getStrengthsJson() != null) {
                return objectMapper.readValue(
                    entity.getStrengthsJson(),
                        new TypeReference<>() {
                        }
                );
            }
        } catch (JacksonException e) {
            log.error("解析 strengths JSON 失败", e);
        }
        return List.of();
    }

    /**
     * 从 JSON 提取 suggestions
     */
    private List<Object> extractSuggestions(ResumeAnalysisEntity entity) {
        try {
            if (entity.getSuggestionsJson() != null) {
                return objectMapper.readValue(
                    entity.getSuggestionsJson(),
                        new TypeReference<>() {
                        }
                );
            }
        } catch (JacksonException e) {
            log.error("解析 suggestions JSON 失败", e);
        }
        return List.of();
    }

    /**
     * 导出简历分析报告为PDF
     */
    public ExportResult exportAnalysisPdf(Long resumeId) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(resumeId);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();
        Optional<ResumeAnalysisResponse> analysisOpt = resumePersistenceService.getLatestAnalysisAsDTO(resumeId);
        if (analysisOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND);
        }

        try {
            byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysisOpt.get());
            String filename = "简历分析报告_" + resume.getOriginalFilename() + ".pdf";

            return new ExportResult(pdfBytes, filename);
        } catch (Exception e) {
            log.error("导出PDF失败: resumeId={}", resumeId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }

    /**
     * PDF导出结果
     */
    public record ExportResult(byte[] pdfBytes, String filename) {}

    public DownloadResult downloadOriginal(Long resumeId) {
        ResumeEntity resume = resumePersistenceService.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        if (resume.getStorageKey() == null || resume.getStorageKey().isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "简历文件不存在");
        }
        return new DownloadResult(
            fileStorageService.downloadFile(resume.getStorageKey()),
            resume.getOriginalFilename(),
            resume.getContentType()
        );
    }

    public record DownloadResult(byte[] content, String filename, String contentType) {}
}

