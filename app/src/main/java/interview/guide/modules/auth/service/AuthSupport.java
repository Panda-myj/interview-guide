package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.util.MaskUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
public class AuthSupport {

    private static final Pattern MAINLAND_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    public String normalizePhone(String rawPhone) {
        String phone = rawPhone == null ? "" : rawPhone.replaceAll("[\\s-]", "");
        if (phone.startsWith("+86")) {
            phone = phone.substring(3);
        } else if (phone.startsWith("86") && phone.length() == 13) {
            phone = phone.substring(2);
        }
        if (!MAINLAND_MOBILE.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_PHONE, "请输入正确的 11 位手机号");
        }
        return "+86" + phone;
    }

    public String maskPhone(String phone) {
        return MaskUtil.maskPhone(phone);
    }

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public String hashSensitiveValue(String value, String pepper) {
        return sha256(pepper + ":" + value);
    }

    public String safeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "未知设备";
        }
        String normalized = userAgent.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }
}
