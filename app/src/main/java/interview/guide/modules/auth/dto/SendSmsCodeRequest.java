package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SendSmsCodeRequest(
    @NotBlank(message = "手机号不能为空")
    String phone
) {
}
