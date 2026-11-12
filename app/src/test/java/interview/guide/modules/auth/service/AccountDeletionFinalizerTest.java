package interview.guide.modules.auth.service;

import interview.guide.common.repository.ResourceDeletionCompensationRepository;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("账号注销收尾测试")
class AccountDeletionFinalizerTest {

    private static final Long USER_ID = 11L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private ResourceDeletionCompensationRepository compensationRepository;
    @Mock
    private TransactionalExecutor transactionalExecutor;
    @InjectMocks
    private AccountDeletionFinalizer finalizer;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            Runnable action = invocation.getArgument(0);
            action.run();
            return null;
        }).when(transactionalExecutor).run(any());
        lenient().when(userRepository.findByStatus(UserStatus.DELETING))
            .thenReturn(List.of(user(USER_ID)));
    }

    @Test
    @DisplayName("仍有未完成补偿任务时不删除 users，保证外键与清理可用")
    void keepsUserWhileCleanupPending() {
        when(compensationRepository.existsByOwnerUserIdAndStatusIn(eq(USER_ID), any()))
            .thenReturn(true);

        finalizer.finalizeDeletingAccounts();

        verify(compensationRepository, never()).deleteByOwnerUserId(USER_ID);
        verify(userRepository, never()).deleteById(USER_ID);
    }

    @Test
    @DisplayName("补偿任务全部终结后删除补偿记录与 users 行")
    void deletesUserAfterCleanupCompleted() {
        when(compensationRepository.existsByOwnerUserIdAndStatusIn(eq(USER_ID), any()))
            .thenReturn(false);
        when(compensationRepository.countByOwnerUserIdAndStatus(
            eq(USER_ID), eq(interview.guide.common.model.ResourceCleanupStatus.DEAD)))
            .thenReturn(0L);

        finalizer.finalizeDeletingAccounts();

        verify(compensationRepository).deleteByOwnerUserId(USER_ID);
        verify(userRepository).deleteById(USER_ID);
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDisplayName("已注销用户");
        user.setStatus(UserStatus.DELETING);
        return user;
    }
}
