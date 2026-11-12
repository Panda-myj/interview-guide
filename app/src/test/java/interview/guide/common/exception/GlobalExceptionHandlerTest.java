package interview.guide.common.exception;

import interview.guide.common.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常响应契约：统一 Result 结构、不回显内部细节。
 */
@DisplayName("全局异常响应契约")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("业务异常")
    class BusinessExceptions {

        @Test
        @DisplayName("业务异常按原错误码与消息返回 Result")
        void returnsBusinessErrorCode() {
            Result<Void> result = handler.handleBusinessException(
                new BusinessException(ErrorCode.CONFIG_INVALID, "APP_AI_CONFIG_ENCRYPTION_KEY must be configured"));

            assertThat(result.getCode()).isEqualTo(ErrorCode.CONFIG_INVALID.getCode());
            assertThat(result.getMessage()).isEqualTo("APP_AI_CONFIG_ENCRYPTION_KEY must be configured");
        }

        @Test
        @DisplayName("业务异常响应不包含堆栈信息")
        void doesNotExposeStackTrace() {
            Result<Void> result = handler.handleBusinessException(
                new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

            assertThat(result.getMessage()).doesNotContain("at interview.guide");
        }
    }

    @Nested
    @DisplayName("未知异常")
    class UnknownExceptions {

        @Test
        @DisplayName("未知异常返回统一内部错误码与通用提示")
        void masksUnknownException() {
            Result<Void> result = handler.handleException(
                new IllegalStateException("jdbc:postgresql://db:5432/x password=secret"));

            assertThat(result.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
            assertThat(result.getMessage()).isEqualTo("系统繁忙，请稍后重试");
            assertThat(result.getMessage()).doesNotContain("password", "jdbc", "secret");
        }
    }
}
