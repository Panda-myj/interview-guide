package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotNull;

public record WsTicketRequest(
    @NotNull(message = "语音面试会话 ID 不能为空")
    Long sessionId
) {
}
