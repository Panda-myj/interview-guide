package interview.guide.modules.auth.dto;

import java.time.LocalDateTime;

/** 站内登录提醒。不返回手机号快照与登录 IP，避免敏感信息进入前端。 */
public record LoginNotificationResponse(
    Long id,
    String deviceLabel,
    LocalDateTime loginAt,
    boolean read,
    LocalDateTime readAt
) {
}
