package interview.guide.infrastructure.file;

import java.time.Instant;
import java.util.List;

/**
 * 对象存储清单能力抽象，用于孤儿文件扫描；测试中可直接替换为内存实现。
 */
public interface ObjectStorageInventory {

  /**
   * 列出指定前缀下的对象。
   *
   * @param prefix     对象键前缀
   * @param maxObjects 最多返回的对象数量，避免全量扫描压垮存储
   */
  List<StoredObject> list(String prefix, int maxObjects);

  record StoredObject(String key, Instant lastModified, long size) {
  }
}
