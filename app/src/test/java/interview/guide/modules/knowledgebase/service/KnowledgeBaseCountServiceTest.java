package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库计数服务测试")
class KnowledgeBaseCountServiceTest {

    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock private CurrentUserService currentUserService;
    @InjectMocks private KnowledgeBaseCountService countService;

    @Test
    @DisplayName("按当前 Owner 校验并批量更新计数")
    void updatesWithCurrentOwner() {
        when(currentUserService.getRequiredUserId()).thenReturn(100L);
        when(knowledgeBaseRepository.findAllByIdInAndOwnerUserId(eq(List.of(1L)), eq(100L)))
            .thenReturn(List.of(knowledgeBase(1L)));
        when(knowledgeBaseRepository.incrementQuestionCountBatch(eq(List.of(1L)), eq(100L)))
            .thenReturn(1);

        countService.updateQuestionCounts(List.of(1L));

        verify(knowledgeBaseRepository).incrementQuestionCountBatch(eq(List.of(1L)), eq(100L));
    }

    @Test
    @DisplayName("批量更新行数不足时失败")
    void failsWhenUpdateCountMismatches() {
        when(currentUserService.getRequiredUserId()).thenReturn(100L);
        when(knowledgeBaseRepository.findAllByIdInAndOwnerUserId(eq(List.of(1L)), eq(100L)))
            .thenReturn(List.of(knowledgeBase(1L)));
        when(knowledgeBaseRepository.incrementQuestionCountBatch(anyList(), eq(100L))).thenReturn(0);

        assertThatThrownBy(() -> countService.updateQuestionCounts(List.of(1L)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("计数更新失败");
    }

    private KnowledgeBaseEntity knowledgeBase(Long id) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        return entity;
    }
}
