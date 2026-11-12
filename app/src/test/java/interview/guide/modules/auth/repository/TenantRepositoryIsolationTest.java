package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@DisplayName("多用户数据隔离Repository测试")
class TenantRepositoryIsolationTest {

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private ResumeRepository resumeRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("不同用户可保存相同文件哈希且查询互不穿透")
    void sameFileHashIsScopedByOwner() {
        UserEntity alice = userRepository.save(user("+8613800138000", "Alice"));
        UserEntity bob = userRepository.save(user("+8613900139000", "Bob"));

        ResumeEntity aliceResume = entityManager.persistFlushFind(resume(alice, "same-hash"));
        ResumeEntity bobResume = entityManager.persistFlushFind(resume(bob, "same-hash"));

        assertThat(aliceResume.getId()).isNotEqualTo(bobResume.getId());
        assertThat(resumeRepository.findByOwnerUserIdAndFileHash(alice.getId(), "same-hash"))
            .contains(aliceResume);
        assertThat(resumeRepository.findAllByOwnerUserIdOrderByUploadedAtDesc(bob.getId()))
            .containsExactly(bobResume);
    }

    private UserEntity user(String phone, String displayName) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(displayName);
        user.setRole(UserRole.USER);
        return user;
    }

    private ResumeEntity resume(UserEntity owner, String hash) {
        ResumeEntity resume = new ResumeEntity();
        resume.setOwnerUser(owner);
        resume.setFileHash(hash);
        resume.setOriginalFilename("resume.txt");
        resume.setFileSize(10L);
        resume.setContentType("text/plain");
        return resume;
    }
}
