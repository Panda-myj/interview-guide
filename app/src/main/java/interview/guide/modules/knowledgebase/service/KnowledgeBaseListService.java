package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.auth.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;
    private final CurrentUserService currentUserService;

    /**
     * 获取知识库列表（支持状态过滤和排序，排序下推数据库，PERF-002）
     *
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        Long ownerUserId = currentUserService.getRequiredUserId();
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.findByOwnerUserIdAndVectorStatus(
                ownerUserId, vectorStatus, resolveSort(sortBy))
        );
    }

    /**
     * 解析排序字段为数据库 ORDER BY（排序下推，PERF-002）。
     * 未知字段回退到 uploadedAt，与旧行为一致。
     */
    private Sort resolveSort(String sortBy) {
        String field = sortBy == null || sortBy.isBlank() ? "time" : sortBy.toLowerCase();
        return switch (field) {
            case "size" -> Sort.by(Sort.Direction.DESC, "fileSize");
            case "access" -> Sort.by(Sort.Direction.DESC, "accessCount");
            case "question" -> Sort.by(Sort.Direction.DESC, "questionCount");
            default -> Sort.by(Sort.Direction.DESC, "uploadedAt");
        };
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    /**
     * 根据ID获取知识库详情
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findByIdAndOwnerUserId(id, currentUserService.getRequiredUserId())
            .map(knowledgeBaseMapper::toListItemDTO);
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作）
     */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return knowledgeBaseRepository.findByIdAndOwnerUserId(id, currentUserService.getRequiredUserId());
    }

    /**
     * 根据ID列表获取知识库名称列表
     *
     * <p>N+1 修复（PERF-002）：一次 IN 批量查询后按原顺序回填，
     * 缺失的 id 保留「未知知识库」占位，保持原有语义。</p>
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Long ownerUserId = currentUserService.getRequiredUserId();
        Map<Long, String> namesById = knowledgeBaseRepository
            .findAllByIdInAndOwnerUserId(ids.stream().distinct().toList(), ownerUserId)
            .stream()
            .collect(Collectors.toMap(KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName));
        return ids.stream()
            .map(id -> namesById.getOrDefault(id, "未知知识库"))
            .toList();
    }

    // ========== 分类管理 ==========

    /**
     * 获取所有分类
     */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategories(currentUserService.getRequiredUserId());
    }

    /**
     * 根据分类获取知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository
                .findByOwnerUserIdAndCategoryIsNullOrderByUploadedAtDesc(
                    currentUserService.getRequiredUserId());
        } else {
            entities = knowledgeBaseRepository.findByOwnerUserIdAndCategoryOrderByUploadedAtDesc(
                currentUserService.getRequiredUserId(), category);
        }
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 更新知识库分类
     */
    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByIdAndOwnerUserId(
                id, currentUserService.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByKeyword(
                currentUserService.getRequiredUserId(), keyword.trim())
        );
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.countByOwnerUserId(currentUserService.getRequiredUserId()),
            ragChatMessageRepository.countByTypeAndOwner(
                MessageType.USER, currentUserService.getRequiredUserId()),
            knowledgeBaseRepository.sumAccessCount(currentUserService.getRequiredUserId()),
            knowledgeBaseRepository.countByOwnerUserIdAndVectorStatus(
                currentUserService.getRequiredUserId(), VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByOwnerUserIdAndVectorStatus(
                currentUserService.getRequiredUserId(), VectorStatus.PROCESSING)
        );
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByIdAndOwnerUserId(
                id, currentUserService.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findByIdAndOwnerUserId(id, currentUserService.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }

    public List<KnowledgeBaseEntity> requireOwned(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        List<KnowledgeBaseEntity> entities = knowledgeBaseRepository
            .findAllByIdInAndOwnerUserId(uniqueIds, currentUserService.getRequiredUserId());
        if (entities.size() != uniqueIds.size()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "部分知识库不存在");
        }
        return entities;
    }

    public Long getOwnerUserId() {
        return currentUserService.getRequiredUserId();
    }
}
