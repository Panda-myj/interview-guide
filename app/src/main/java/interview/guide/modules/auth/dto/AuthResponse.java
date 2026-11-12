package interview.guide.modules.auth.dto;

public record AuthResponse(
    String accessToken,
    long expiresIn,
    UserResponse user
) {
}
