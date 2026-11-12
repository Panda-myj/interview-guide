package interview.guide.modules.auth.security;

import interview.guide.infrastructure.redis.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtDenylistValidator implements OAuth2TokenValidator<Jwt> {

    private final RedisService redisService;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String tokenId = token.getId();
        if (tokenId != null && redisService.exists("auth:jwt:deny:" + tokenId)) {
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Token has been revoked", null)
            );
        }
        return OAuth2TokenValidatorResult.success();
    }
}
