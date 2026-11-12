package interview.guide.modules.auth.dto;

public record SendSmsCodeResponse(
    String message,
    String debugCode
) {
}
