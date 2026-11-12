package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 S3 ListObjectsV2 的对象清单实现，仅供孤儿资源扫描使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3ObjectStorageInventory implements ObjectStorageInventory {

  private static final int MAX_PAGE_SIZE = 1000;

  private final S3Client s3Client;
  private final StorageConfigProperties storageConfig;

  @Override
  public List<StoredObject> list(String prefix, int maxObjects) {
    List<StoredObject> objects = new ArrayList<>();
    String continuationToken = null;
    do {
      ListObjectsV2Request request = ListObjectsV2Request.builder()
          .bucket(storageConfig.getBucket())
          .prefix(prefix)
          .maxKeys(Math.min(Math.max(maxObjects - objects.size(), 1), MAX_PAGE_SIZE))
          .continuationToken(continuationToken)
          .build();
      ListObjectsV2Response response = s3Client.listObjectsV2(request);
      for (S3Object object : response.contents()) {
        objects.add(new StoredObject(object.key(), object.lastModified(), object.size()));
        if (objects.size() >= maxObjects) {
          log.warn("对象存储列举达到上限，可能存在未扫描对象: prefix={}, maxObjects={}",
              prefix, maxObjects);
          return List.copyOf(objects);
        }
      }
      continuationToken = Boolean.TRUE.equals(response.isTruncated())
          ? response.nextContinuationToken()
          : null;
    } while (continuationToken != null && !continuationToken.isEmpty());

    return List.copyOf(objects);
  }
}
