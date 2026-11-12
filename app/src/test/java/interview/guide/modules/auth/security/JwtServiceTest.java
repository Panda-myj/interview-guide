package interview.guide.modules.auth.security;

import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JWT 服务测试")
class JwtServiceTest {

    @Test
    @DisplayName("签发的 Access Token 只包含约定声明")
    void issuesExpectedClaims() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("test-secret-with-more-than-thirty-two-characters");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        SecretKey key = new SecretKeySpec(
            properties.getJwtSecret().getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"
        );
        JwtEncoder encoder = NimbusJwtEncoder.withSecretKey(key).build();
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        JwtService service = new JwtService(encoder, properties);

        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setRole(UserRole.ADMIN);
        UUID sessionId = UUID.randomUUID();

        JwtService.IssuedAccessToken issued = service.issue(user, sessionId);
        Jwt jwt = decoder.decode(issued.token());

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        assertThat(jwt.getAudience()).contains("ai-interview-web");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsString("phone")).isNull();
    }
}
