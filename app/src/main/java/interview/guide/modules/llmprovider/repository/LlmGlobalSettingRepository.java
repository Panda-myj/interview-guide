package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

//Spring Data JPA（Java Persistence API）作为Spring框架的一部分，提供了一种简化的方式来操作数据库，使得开发者可以更加专注于业务逻辑的实现，而不是繁琐的数据库交互工作。
public interface LlmGlobalSettingRepository extends JpaRepository<LlmGlobalSettingEntity, Long> {
}
//为什么是 interface 而不是 class？
//这正是 Spring IoC 的魔法所在。Spring 容器启动时，会扫描到这个接口，然后通过 JDK 动态代理 在内存中生成一个实现了该接口的类实例，并注册为 Bean。你只管定义“要什么”，框架负责“怎么做”。
//JpaRepository<T, ID> 泛型含义：
//T = LlmProviderEntity：操作的实体类型。
//ID = String：实体主键的类型（对应你上一个问题中 Entity 的 @Id private String id）。
//免费获得的 CRUD 能力：
//仅仅通过继承这一行，你就自动拥有了 20+ 个标准方法，无需手写：
//表格
//方法	等价 SQL
//save(entity)	INSERT 或 UPDATE
//findById(id)	SELECT ... WHERE id = ?
//findAll()	SELECT *
//deleteById(id)	DELETE ... WHERE id = ?
//count()	SELECT COUNT(*)
//existsById(id)	SELECT 1 ... LIMIT 1