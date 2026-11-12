package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.UserRole;

public record UserResponse(
    Long id,
    String maskedPhone,
    String displayName,
    UserRole role
) {
}
