package interview.guide.modules.auth.security;

import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final AuthProperties authProperties;

    public IssuedAccessToken issue(UserEntity user, UUID sessionId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(authProperties.getAccessTokenTtl());
        String tokenId = UUID.randomUUID().toString();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(authProperties.getIssuer())
            .audience(List.of(authProperties.getAudience()))
            .subject(user.getId().toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(tokenId)
            .claim("role", user.getRole().name())
            .claim("sid", sessionId.toString())
            .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        return new IssuedAccessToken(token, authProperties.getAccessTokenTtl().toSeconds());
    }

    public record IssuedAccessToken(String token, long expiresInSeconds) {
    }
}
