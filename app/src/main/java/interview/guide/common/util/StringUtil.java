package interview.guide.common.util;

/**
 * 字符串通用处理工具：统一各处重复的判空与截断实现。
 */
public final class StringUtil {

    private StringUtil() {
    }

    /** null 或空白返回 true，与 {@link String#isBlank()} 语义一致但容忍 null。 */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 非空且包含非空白字符返回 true。 */
    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /**
     * 截断字符串到指定最大长度（按 char，与历史行为一致），null 原样返回。
     * 主要用于错误信息、日志字段的长度兜底。
     */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
