package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final FileHashService fileHashService;
    private final CurrentUserService currentUserService;
    
    /**
     * 检查简历是否已存在（基于文件内容hash）
     * 
     * @param file 上传的文件
     * @return 如果存在返回已有的简历实体，否则返回空
     */
    public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
        try {
            String fileHash = fileHashService.calculateHash(file);
            Optional<ResumeEntity> existing = resumeRepository.findByOwnerUserIdAndFileHash(
                currentUserService.getRequiredUserId(), fileHash);
            
            if (existing.isPresent()) {
                log.info("检测到重复简历: hash={}", fileHash);
                ResumeEntity resume = existing.get();
                resume.incrementAccessCount();
                resumeRepository.save(resume);
            }
            
            return existing;
        } catch (Exception e) {
            // 去重是上传链路里的可选优化：失败时降级为“非重复”，不影响上传主流程，
            // 但必须保留异常堆栈与文件上下文，否则重复简历问题无法定位
            log.error("检查简历重复失败，按非重复处理: originalFilename={}", file.getOriginalFilename(), e);
            return Optional.empty();
        }
    }
    
    /**
     * 保存新简历
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity saveResume(MultipartFile file, String resumeText,
                                   String storageKey, String storageUrl) {
        try {
            String fileHash = fileHashService.calculateHash(file);
            
            ResumeEntity resume = new ResumeEntity();
            resume.setOwnerUser(currentUserService.getRequiredUser());
            resume.setFileHash(fileHash);
            resume.setOriginalFilename(file.getOriginalFilename());
            resume.setFileSize(file.getSize());
            resume.setContentType(file.getContentType());
            resume.setStorageKey(storageKey);
            resume.setStorageUrl(storageUrl);
            resume.setResumeText(resumeText);
            
            ResumeEntity saved = resumeRepository.save(resume);
            log.info("简历已保存: id={}, hash={}", saved.getId(), fileHash);
            
            return saved;
        } catch (Exception e) {
            log.error("保存简历失败: originalFilename={}", file.getOriginalFilename(), e);
            // 保留根因，便于上层区分数据库失败与序列化失败
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存简历失败", e);
        }
    }
    
    /**
     * 保存简历评测结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeAnalysisEntity saveAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        try {
            // 使用 MapStruct 映射基础字段
            ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(analysis);
            entity.setResume(resume);

            // JSON 字段需要手动序列化
            entity.setStrengthsJson(objectMapper.writeValueAsString(analysis.strengths()));
            entity.setSuggestionsJson(objectMapper.writeValueAsString(analysis.suggestions()));

            ResumeAnalysisEntity saved = analysisRepository.save(entity);
            log.info("简历评测结果已保存: analysisId={}, resumeId={}, score={}",
                    saved.getId(), resume.getId(), analysis.overallScore());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化评测结果失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "保存评测结果失败");
        }
    }
    
    /**
     * 获取简历的最新评测结果
     */
    public Optional<ResumeAnalysisEntity> getLatestAnalysis(Long resumeId) {
        return Optional.ofNullable(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId));
    }
    
    /**
     * 获取简历的最新评测结果（返回DTO）
     */
    public Optional<ResumeAnalysisResponse> getLatestAnalysisAsDTO(Long resumeId) {
        return getLatestAnalysis(resumeId).map(this::entityToDTO);
    }
    
    /**
     * 获取所有简历列表
     */
    public List<ResumeEntity> findAllResumes() {
        return resumeRepository.findAllByOwnerUserIdOrderByUploadedAtDesc(
            currentUserService.getRequiredUserId());
    }

    /**
     * 分页获取简历列表（列表页用，上限由调用方控制，PERF-002）。
     */
    public List<ResumeEntity> findAllResumes(Pageable pageable) {
        return resumeRepository.findAllByOwnerUserIdOrderByUploadedAtDesc(
            currentUserService.getRequiredUserId(), pageable);
    }

    /**
     * 批量获取多份简历的最新评测记录（每份简历 analyzedAt 最大的一条，PERF-002）。
     */
    public Map<Long, ResumeAnalysisEntity> findLatestAnalysesByResumeIds(List<Long> resumeIds) {
        if (resumeIds == null || resumeIds.isEmpty()) {
            return Map.of();
        }
        return analysisRepository.findByResumeIdsOrderByAnalyzedAtDesc(resumeIds).stream()
            .collect(Collectors.toMap(
                a -> a.getResume().getId(),
                Function.identity(),
                // 已按 analyzedAt 倒序，首个即最新
                (first, ignored) -> first
            ));
    }

    /**
     * 批量统计各简历的评测次数（PERF-002）。
     */
    public Map<Long, Long> countAnalysesByResumeIds(List<Long> resumeIds) {
        if (resumeIds == null || resumeIds.isEmpty()) {
            return Map.of();
        }
        return analysisRepository.countByResumeIds(resumeIds).stream()
            .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }
    
    /**
     * 获取简历的所有评测记录
     */
    public List<ResumeAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
    }
    
    /**
     * 将实体转换为DTO
     */
    public ResumeAnalysisResponse entityToDTO(ResumeAnalysisEntity entity) {
        try {
            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson() != null ? entity.getStrengthsJson() : "[]",
                    new TypeReference<>() {
                    }
            );
            
            List<ResumeAnalysisResponse.Suggestion> suggestions = objectMapper.readValue(
                entity.getSuggestionsJson() != null ? entity.getSuggestionsJson() : "[]",
                    new TypeReference<>() {
                    }
            );
            
            return new ResumeAnalysisResponse(
                entity.getOverallScore(),
                resumeMapper.toScoreDetail(entity),  // 使用MapStruct自动映射
                entity.getSummary(),
                strengths,
                suggestions,
                entity.getResume().getResumeText()
            );
        } catch (JacksonException e) {
            log.error("反序列化评测结果失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "获取评测结果失败");
        }
    }
    
    /**
     * 根据ID获取简历
     */
    public Optional<ResumeEntity> findById(Long id) {
        return resumeRepository.findByIdAndOwnerUserId(id, currentUserService.getRequiredUserId());
    }

    public void ensureOwned(Long id) {
        if (findById(id).isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }
    }
    
    /**
     * 删除简历及其所有关联数据
     * 包括：简历分析记录、面试会话（会自动删除面试答案）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long id) {
        Optional<ResumeEntity> resumeOpt = resumeRepository.findByIdAndOwnerUserId(
            id, currentUserService.getRequiredUserId());
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }
        
        ResumeEntity resume = resumeOpt.get();
        
        // 1. 删除所有简历分析记录
        List<ResumeAnalysisEntity> analyses = analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id);
        if (!analyses.isEmpty()) {
            analysisRepository.deleteAll(analyses);
            log.info("已删除 {} 条简历分析记录", analyses.size());
        }
        
        // 2. 删除简历实体（面试会话会在服务层删除）
        resumeRepository.delete(resume);
        log.info("简历已删除: id={}, filename={}", id, resume.getOriginalFilename());
    }
}
