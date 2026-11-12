package interview.guide.modules.auth.dto;

import java.util.List;

public record LoginNotificationPageResponse(
    List<LoginNotificationResponse> items,
    long unreadCount
) {
}
