package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, String> {

  List<LlmProviderEntity> findByEnabledTrueOrderByIdAsc();
}
//要素
//
//        说明
//
//JpaRepository<LlmProviderEntity, String>
//
//第一个泛型是 Entity 类型，第二个是主键类型。只要继承它，就自动获得 findAll()、findById()、save()、deleteById() 等全套 CRUD 方法。
//
//findByEnabledTrueOrderByIdAsc()
//
//这是方法名派生查询。Spring 会解析方法名，自动生成 SQL：SELECT * FROM llm_provider_config WHERE enabled = true ORDER BY id ASC。
//
//为什么是接口？
//
//Spring 在运行时会用 JDK 动态代理生成实现类。你只管定义方法签名，SQL 由框架生成。
