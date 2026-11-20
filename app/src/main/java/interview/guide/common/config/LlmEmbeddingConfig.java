package interview.guide.common.config;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.llmprovider.service.UserLlmConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class LlmEmbeddingConfig {

  @Bean
  public EmbeddingModel embeddingModel(LlmProviderRegistry registry,
                                       UserLlmConfigService userLlmConfigService) {
    log.info("EmbeddingModel bean initialized as user-aware registry delegate");
    return new EmbeddingModel() {
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        return resolveEmbeddingModel(registry, userLlmConfigService).call(request);
      }

      @Override
      public float[] embed(Document document) {
        return resolveEmbeddingModel(registry, userLlmConfigService).embed(document);
      }

      /**
       * 请求上下文中按当前用户的默认向量模型卡片解析；
       * 无用户上下文（系统任务）时回退到全局默认 Embedding Provider。
       */
      private EmbeddingModel resolveEmbeddingModel(LlmProviderRegistry registry,
                                                   UserLlmConfigService userLlmConfigService) {
        return userLlmConfigService.resolveEmbeddingModel();
      }
    };
  }
}
