package interview.guide.common.service;

import interview.guide.common.repository.OrphanResourceQueryRepository;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.ObjectStorageInventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 孤儿资源扫描与回收。
 *
 * <p>孤儿资源指数据库记录已不存在、但对象存储或 pgvector 中仍残留的数据。扫描默认只告警不删除：
 * 对象存储中的新文件可能存在“已上传未入库”的短暂窗口，因此只有早于宽限期的对象才视为孤儿。
 * 回收需要显式传入 {@code reclaim = true}，由运维或定时任务按需触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceOrphanScanService {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("RESOURCE_DELETION_AUDIT");

  private static final String USER_OBJECT_PREFIX = "users/";
  private static final String LEGACY_OBJECT_PREFIX = "legacy/";
  private static final int OBJECT_SCAN_LIMIT = 20000;
  private static final int ORPHAN_VECTOR_LIMIT = 200;
  private static final int REPORT_SAMPLE_SIZE = 20;
  private static final Duration DEFAULT_FILE_GRACE = Duration.ofHours(24);

  private final OrphanResourceQueryRepository orphanResourceRepository;
  private final ObjectStorageInventory objectStorageInventory;
  private final FileStorageService storageService;
  private final Clock clock;

  /**
   * 默认扫描策略：24 小时宽限期，只扫描告警不删除。
   */
  public OrphanScanReport scan() {
    return scan(DEFAULT_FILE_GRACE, false);
  }

  public OrphanScanReport scan(Duration fileGrace, boolean reclaim) {
    List<String> orphanVectorKbIds = scanOrphanVectors(reclaim);
    List<String> orphanFileKeys = scanOrphanFiles(fileGrace, reclaim);
    return new OrphanScanReport(
        orphanVectorKbIds.size(),
        orphanFileKeys.size(),
        sample(orphanVectorKbIds),
        sample(orphanFileKeys),
        reclaim);
  }

  private List<String> scanOrphanVectors(boolean reclaim) {
    List<String> orphanKbIds;
    try {
      orphanKbIds = orphanResourceRepository.findOrphanVectorKnowledgeBaseIds(ORPHAN_VECTOR_LIMIT);
    } catch (RuntimeException e) {
      log.error("扫描孤儿向量失败", e);
      return List.of();
    }

    if (orphanKbIds.isEmpty()) {
      return List.of();
    }
    log.error("检测到孤儿向量数据，需要回收: count={}, sampleKbIds={}",
        orphanKbIds.size(), sample(orphanKbIds));
    AUDIT_LOG.warn("action=SCAN_ORPHAN_VECTOR orphanCount={} reclaimed={} sampleKbIds={}",
        orphanKbIds.size(), reclaim, sample(orphanKbIds));

    if (reclaim) {
      for (String kbId : orphanKbIds) {
        reclaimVector(kbId);
      }
    }
    return orphanKbIds;
  }

  private void reclaimVector(String kbId) {
    try {
      Long knowledgeBaseId = Long.valueOf(kbId);
      int deletedRows = orphanResourceRepository.deleteVectorsByKnowledgeBaseId(knowledgeBaseId);
      log.info("已回收孤儿向量: kbId={}, deletedRows={}", kbId, deletedRows);
    } catch (NumberFormatException e) {
      log.warn("跳过非法向量知识库标识: kbId={}", kbId, e);
    } catch (RuntimeException e) {
      log.error("回收孤儿向量失败: kbId={}", kbId, e);
    }
  }

  private List<String> scanOrphanFiles(Duration fileGrace, boolean reclaim) {
    List<String> orphanKeys;
    try {
      Set<String> knownKeys = new HashSet<>(orphanResourceRepository.findKnownStorageKeys());
      Instant threshold = clock.instant().minus(fileGrace);
      orphanKeys = listCandidateObjects().stream()
          .filter(object -> object.lastModified() == null
              || !object.lastModified().isAfter(threshold))
          .map(ObjectStorageInventory.StoredObject::key)
          .filter(key -> !knownKeys.contains(key))
          .distinct()
          .toList();
    } catch (RuntimeException e) {
      log.error("扫描孤儿对象文件失败", e);
      return List.of();
    }

    if (orphanKeys.isEmpty()) {
      return List.of();
    }
    log.error("检测到孤儿对象文件，需要回收: count={}, sampleKeys={}",
        orphanKeys.size(), sample(orphanKeys));
    AUDIT_LOG.warn("action=SCAN_ORPHAN_FILE orphanCount={} reclaimed={} sampleKeys={}",
        orphanKeys.size(), reclaim, sample(orphanKeys));

    if (reclaim) {
      for (String key : orphanKeys) {
        reclaimFile(key);
      }
    }
    return orphanKeys;
  }

  private List<ObjectStorageInventory.StoredObject> listCandidateObjects() {
    List<ObjectStorageInventory.StoredObject> objects =
        new ArrayList<>(objectStorageInventory.list(USER_OBJECT_PREFIX, OBJECT_SCAN_LIMIT));
    int remaining = Math.max(OBJECT_SCAN_LIMIT - objects.size(), 1);
    objects.addAll(objectStorageInventory.list(LEGACY_OBJECT_PREFIX, remaining));
    return objects;
  }

  private void reclaimFile(String key) {
    try {
      if (key.contains("/resumes/")) {
        storageService.deleteResume(key);
      } else if (key.contains("/knowledgebases/")) {
        storageService.deleteKnowledgeBase(key);
      } else {
        log.warn("无法判定孤儿对象归属，跳过回收: key={}", key);
        return;
      }
      log.info("已回收孤儿对象文件: key={}", key);
    } catch (RuntimeException e) {
      log.error("回收孤儿对象文件失败: key={}", key, e);
    }
  }

  private List<String> sample(List<String> values) {
    return values.size() <= REPORT_SAMPLE_SIZE ? values : values.subList(0, REPORT_SAMPLE_SIZE);
  }

  /**
   * 扫描结果，用于日志、告警和运维查询。
   */
  public record OrphanScanReport(
      int orphanVectorCount,
      int orphanFileCount,
      List<String> sampleOrphanVectorIds,
      List<String> sampleOrphanFileKeys,
      boolean reclaimed
  ) {
  }
}
