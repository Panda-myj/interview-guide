package interview.guide.modules.interview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.llmprovider.service.UserLlmConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文字面试出题降级")
class InterviewQuestionServiceFallbackTest {

    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;
    @Mock
    private InterviewSkillService skillService;
    @Mock
    private UserLlmConfigService userLlmConfigService;

    @Test
    @DisplayName("LLM 客户端不可用时应返回本地默认题库而不是让页面失败")
    void shouldFallbackWhenLlmClientUnavailable() throws Exception {
        SkillDTO skill = new SkillDTO(
            "java-backend",
            "Java 后端",
            "Java 后端面试",
            List.of(),
            true,
            null,
            null,
            null
        );
        when(skillService.getSkill("java-backend")).thenReturn(skill);
        when(userLlmConfigService.requirePlainChatClientForCurrentUser())
            .thenThrow(new IllegalStateException("429 limit_requests"));

        InterviewQuestionService service = new InterviewQuestionService(
            structuredOutputInvoker,
            skillService,
            new InterviewQuestionProperties(),
            new DefaultResourceLoader(),
            userLlmConfigService,
            new PromptSanitizer(new LlmProviderProperties())
        );

        List<InterviewQuestionDTO> questions = service.generateQuestionsBySkill(
            "java-backend",
            "mid",
            "",
            3,
            List.of(),
            null,
            null
        );

        assertFalse(questions.isEmpty());
    }
}
