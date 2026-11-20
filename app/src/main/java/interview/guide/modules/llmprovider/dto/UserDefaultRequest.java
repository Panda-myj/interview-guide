package interview.guide.modules.llmprovider.dto;

/**
 * 用户设置默认文本 / 向量模型的请求体。
 */
public record UserDefaultRequest(
    Long providerId
) {
}
