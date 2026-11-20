package interview.guide.modules.llmprovider.dto;

import lombok.Builder;

/**
 * 用户自己的 Provider 卡片（API Key 只返回脱敏值）。
 */
@Builder
public record UserProviderDTO(
    Long id,
    String name,
    String baseUrl,
    String maskedApiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    boolean supportsEmbedding,
    Double temperature,
    boolean defaultChatProvider,
    boolean defaultEmbeddingProvider
) {
}
