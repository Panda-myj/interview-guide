package interview.guide.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 孤儿资源定时扫描，默认每天凌晨执行一次，只告警不自动删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceOrphanScanScheduler {

  private final ResourceOrphanScanService orphanScanService;

  @Scheduled(cron = "${app.resource-cleanup.orphan-scan-cron:0 30 3 * * *}")
  public void scanOrphanResources() {
    ResourceOrphanScanService.OrphanScanReport report = orphanScanService.scan();
    log.info("孤儿资源扫描完成: orphanVectors={}, orphanFiles={}",
        report.orphanVectorCount(), report.orphanFileCount());
  }
}
