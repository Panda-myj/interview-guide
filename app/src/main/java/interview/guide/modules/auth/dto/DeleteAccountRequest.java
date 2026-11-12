package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 注销账号请求：必须同时提供本人手机号与注销专用验证码。 */
public record DeleteAccountRequest(
    @NotBlank(message = "请输入手机号")
    String phone,

    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "\\d{6}", message = "请输入 6 位验证码")
    String code
) {
}
