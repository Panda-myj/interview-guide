package interview.guide.common.service;

import interview.guide.common.repository.OrphanResourceQueryRepository;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.ObjectStorageInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 孤儿资源扫描测试：孤儿判定、告警、回收与异常隔离。
 */
@DisplayName("孤儿资源扫描服务测试")
class ResourceOrphanScanServiceTest {

  private static final Duration GRACE = Duration.ofHours(24);
  private static final String KEPT_KEY = "users/1/resumes/2026/01/01/keep.pdf";
  private static final String ORPHAN_RESUME_KEY = "users/1/resumes/2026/01/01/orphan.pdf";
  private static final String ORPHAN_KB_KEY = "users/1/knowledgebases/2026/01/01/orphan.pdf";

  private ResourceOrphanScanService scanService;

  @Mock
  private OrphanResourceQueryRepository orphanResourceRepository;

  @Mock
  private ObjectStorageInventory objectStorageInventory;

  @Mock
  private FileStorageService storageService;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    scanService = new ResourceOrphanScanService(
        orphanResourceRepository, objectStorageInventory, storageService,
        Clock.system(ZoneId.of("Asia/Shanghai")));
  }

  private ObjectStorageInventory.StoredObject object(String key, Instant lastModified) {
    return new ObjectStorageInventory.StoredObject(key, lastModified, 1024L);
  }

  @Nested
  @DisplayName("孤儿对象文件扫描")
  class OrphanFileScanTests {

    @Test
    @DisplayName("只把超过宽限期且未入库的对象判定为孤儿")
    void detectsOnlyAgedUnknownObjects() {
      Instant old = Instant.now().minus(Duration.ofDays(2));
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of(KEPT_KEY));
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of(
          object(KEPT_KEY, old),
          object(ORPHAN_RESUME_KEY, old),
          object("users/1/resumes/2026/01/01/uploading.pdf", Instant.now())));

      ResourceOrphanScanService.OrphanScanReport report = scanService.scan(GRACE, false);

      assertThat(report.orphanFileCount()).isEqualTo(1);
      assertThat(report.sampleOrphanFileKeys()).containsExactly(ORPHAN_RESUME_KEY);
      assertThat(report.reclaimed()).isFalse();
      verify(storageService, never()).deleteResume(anyString());
    }

    @Test
    @DisplayName("回收模式按对象归属调用对应的存储删除方法")
    void reclaimsOrphanFilesByOwnerPath() {
      Instant old = Instant.now().minus(Duration.ofDays(2));
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of(
          object(ORPHAN_RESUME_KEY, old),
          object(ORPHAN_KB_KEY, old)));

      scanService.scan(GRACE, true);

      verify(storageService).deleteResume(ORPHAN_RESUME_KEY);
      verify(storageService).deleteKnowledgeBase(ORPHAN_KB_KEY);
    }

    @Test
    @DisplayName("单个对象回收失败不影响其他对象，也不抛出异常")
    void continuesReclaimWhenSingleDeleteFails() {
      Instant old = Instant.now().minus(Duration.ofDays(2));
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of(
          object(ORPHAN_RESUME_KEY, old),
          object(ORPHAN_KB_KEY, old)));
      doThrow(new IllegalStateException("S3 不可用"))
          .when(storageService).deleteResume(ORPHAN_RESUME_KEY);

      assertThatNoException().isThrownBy(() -> scanService.scan(GRACE, true));

      verify(storageService).deleteKnowledgeBase(ORPHAN_KB_KEY);
    }

    @Test
    @DisplayName("对象存储扫描异常时返回空结果且不抛出")
    void returnsEmptyReportWhenStorageScanFails() {
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt()))
          .thenThrow(new IllegalStateException("S3 不可用"));

      ResourceOrphanScanService.OrphanScanReport report = scanService.scan(GRACE, false);

      assertThat(report.orphanFileCount()).isZero();
    }
  }

  @Nested
  @DisplayName("孤儿向量扫描")
  class OrphanVectorScanTests {

    @Test
    @DisplayName("扫描到孤儿向量时统计数量并给出样本")
    void detectsOrphanVectors() {
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of());
      when(orphanResourceRepository.findOrphanVectorKnowledgeBaseIds(anyInt()))
          .thenReturn(List.of("12", "13"));

      ResourceOrphanScanService.OrphanScanReport report = scanService.scan(GRACE, false);

      assertThat(report.orphanVectorCount()).isEqualTo(2);
      assertThat(report.sampleOrphanVectorIds()).containsExactly("12", "13");
      verify(orphanResourceRepository, never()).deleteVectorsByKnowledgeBaseId(12L);
    }

    @Test
    @DisplayName("回收模式删除孤儿向量")
    void reclaimsOrphanVectors() {
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of());
      when(orphanResourceRepository.findOrphanVectorKnowledgeBaseIds(anyInt()))
          .thenReturn(List.of("12"));

      scanService.scan(GRACE, true);

      verify(orphanResourceRepository).deleteVectorsByKnowledgeBaseId(12L);
    }

    @Test
    @DisplayName("非法知识库标识跳过回收但不中断扫描")
    void skipsInvalidVectorId() {
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of());
      when(orphanResourceRepository.findOrphanVectorKnowledgeBaseIds(anyInt()))
          .thenReturn(List.of("pending:1:abc"));

      assertThatNoException().isThrownBy(() -> scanService.scan(GRACE, true));

      verify(orphanResourceRepository, never()).deleteVectorsByKnowledgeBaseId(anyLong());
    }

    @Test
    @DisplayName("样本数量被限制，避免日志过大")
    void limitsSampleSize() {
      List<String> vectorIds = IntStream.rangeClosed(1, 25)
          .mapToObj(String::valueOf)
          .toList();
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of());
      when(orphanResourceRepository.findOrphanVectorKnowledgeBaseIds(anyInt()))
          .thenReturn(vectorIds);

      ResourceOrphanScanService.OrphanScanReport report = scanService.scan(GRACE, false);

      assertThat(report.orphanVectorCount()).isEqualTo(25);
      assertThat(report.sampleOrphanVectorIds()).hasSize(20);
    }

    @Test
    @DisplayName("向量扫描异常时返回空结果且不抛出")
    void returnsEmptyReportWhenVectorScanFails() {
      when(orphanResourceRepository.findKnownStorageKeys()).thenReturn(List.of());
      when(objectStorageInventory.list(anyString(), anyInt())).thenReturn(List.of());
      when(orphanResourceRepository.findOrphanVectorKnowledgeBaseIds(anyInt()))
          .thenThrow(new IllegalStateException("数据库不可用"));

      ResourceOrphanScanService.OrphanScanReport report = scanService.scan(GRACE, false);

      assertThat(report.orphanVectorCount()).isZero();
    }
  }
}
