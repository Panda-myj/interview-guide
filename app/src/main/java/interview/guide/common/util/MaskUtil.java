package interview.guide.common.util;

/**
 * 日志脱敏工具：统一手机号与令牌的掩码规则，敏感值禁止原文进入日志。
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /** 保留前 3 后 4，中间掩码。至少遮蔽 1 个原字符，否则整体返回 ***。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 保留前 6 后 4，中间掩码。至少遮蔽 1 个原字符，否则整体返回 ***。 */
    public static String maskToken(String token) {
        if (token == null || token.length() < 11) {
            return "***";
        }
        return token.substring(0, 6) + "****" + token.substring(token.length() - 4);
    }
}
