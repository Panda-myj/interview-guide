package interview.guide.modules.auth.security;

import interview.guide.modules.auth.model.UserRole;

import java.time.Instant;

public record AuthPrincipal(
    Long userId,
    UserRole role,
    String sessionId,
    String tokenId,
    Instant expiresAt
) {
}
