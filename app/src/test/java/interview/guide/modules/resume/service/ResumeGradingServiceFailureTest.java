package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * QA-002：简历分析失败必须抛出业务异常，不能返回「0 分成功」的假结果。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("简历分析失败传播测试")
class ResumeGradingServiceFailureTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private ResumeAnalysisProperties properties;

  @Mock
  private ResourceLoader resourceLoader;

  private ResumeGradingService service;

  @BeforeEach
  void setUp() throws Exception {
    Resource resource = mock(Resource.class);
    when(resource.getContentAsString(StandardCharsets.UTF_8)).thenReturn("prompt");
    when(properties.getSystemPromptPath()).thenReturn("system.st");
    when(properties.getUserPromptPath()).thenReturn("user.st");
    when(resourceLoader.getResource(anyString())).thenReturn(resource);
    service = new ResumeGradingService(
        llmProviderRegistry, structuredOutputInvoker, properties, resourceLoader);
  }

  @Test
  @DisplayName("AI 调用失败抛出业务异常且不泄露内部错误细节")
  void throwsBusinessExceptionInsteadOfFakeResult() {
    when(llmProviderRegistry.getDefaultChatClient())
        .thenThrow(new IllegalStateException("provider secret-key=abc123 unavailable"));

    assertThatThrownBy(() -> service.analyzeResume("简历文本"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException businessException = (BusinessException) error;
          assertThat(businessException.getCode())
              .isEqualTo(ErrorCode.RESUME_ANALYSIS_FAILED.getCode());
          assertThat(businessException.getMessage())
              .doesNotContain("secret-key")
              .doesNotContain("abc123");
        });
  }
}
