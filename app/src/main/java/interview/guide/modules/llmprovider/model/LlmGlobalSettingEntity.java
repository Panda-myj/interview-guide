package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/*注解	作用	为什么 Entity 需要
@Data	getter/setter/toString/equals/hashCode	JPA 需要 getter/setter 读写字段
@NoArgsConstructor	无参构造器	⭐ JPA 强制要求，反射实例化用
@AllArgsConstructor	全参构造器	配合 Builder 使用
@Builder	构建者模式	方便测试和初始化单例数据
*/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_global_setting")
public class LlmGlobalSettingEntity {

  public static final Long SINGLETON_ID = 1L;

  @Id//主键是通过 @Id 注解来标识的。
  private Long id;

  //DDL 生成时添加 NOT NULL 约束；运行时 JPA 不会自动校验，需配合 Bean Validation。
  //length = 64: 对应 VARCHAR(64)，与 LlmProviderEntity.id 长度一致，保证外键引用兼容。
  @Column(name = "default_chat_provider_id", nullable = false, length = 64)
  private String defaultChatProviderId;

  @Column(name = "default_embedding_provider_id", nullable = false, length = 64)
  private String defaultEmbeddingProviderId;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
//    在 entityManager.persist() / repository.save() 插入前自动调用，初始化创建时间和更新时间。
  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }
//@PreUpdate: 在 entityManager.merge() / repository.save() 更新前自动调用，刷新更新时间。
  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
//✅ 优势：时间戳维护逻辑内聚在实体中，Service 层无需手动 set。
//        ⚠️ 注意：LocalDateTime.now() 使用的是 JVM 所在服务器时区。生产环境建议统一 UTC 或使用 @CreationTimestamp / @UpdateTimestamp（Hibernate 专属注解，支持时区配置）。