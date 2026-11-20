package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户保存 / 更新自己的 Provider 卡片。更新时 apiKey 留空表示保持原值。
 */
public record UserSaveProviderRequest(
    @NotBlank(message = "name 不能为空")
    @Size(max = 128, message = "name 长度不能超过 128")
    String name,

    @NotBlank(message = "baseUrl 不能为空")
    @Size(max = 512, message = "baseUrl 长度不能超过 512")
    String baseUrl,

    @Size(max = 4096, message = "apiKey 长度不能超过 4096")
    String apiKey,

    @NotBlank(message = "model 不能为空")
    @Size(max = 128, message = "model 长度不能超过 128")
    String model,

    @Size(max = 128, message = "embeddingModel 长度不能超过 128")
    String embeddingModel,

    Integer embeddingDimensions,

    Boolean supportsEmbedding,

    Double temperature
) {
}
