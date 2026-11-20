package interview.guide.common;

import interview.guide.common.ai.RagContextBuilder;
import interview.guide.common.util.MaskUtil;
import interview.guide.modules.auth.service.AuthSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开发阶段自查中修复的确定性缺陷的回归用例。
 */
@DisplayName("开发阶段自查回归测试")
class SelfCheckRegressionTest {

  @Test
  @DisplayName("短手机号/令牌脱敏不得把原文完整还原出来")
  void shortSensitiveValuesAreFullyMasked() {
    assertEquals("***", MaskUtil.maskPhone("1234567"));
    assertEquals("***", MaskUtil.maskToken("0123456789"));
    assertEquals("***", MaskUtil.maskPhone(null));
    assertEquals("***", MaskUtil.maskToken(null));
    assertEquals("138****8000", MaskUtil.maskPhone("13800138000"));
    assertFalse(MaskUtil.maskPhone("13800138000").contains("0013"));
  }

  @Test
  @DisplayName("手机号格式变体归一化到同一个限流键")
  void phoneVariantsShareRateLimitKey() {
    String base = AuthSupport.normalizePhoneKey("13800138000");
    assertNotNull(base);
    assertEquals(base, AuthSupport.normalizePhoneKey("+8613800138000"));
    assertEquals(base, AuthSupport.normalizePhoneKey("138-0013-8000"));
    assertEquals(base, AuthSupport.normalizePhoneKey(" 13800138000 "));
    assertNull(AuthSupport.normalizePhoneKey("not-a-phone"));
    assertNull(AuthSupport.normalizePhoneKey(null));
  }

  @Test
  @DisplayName("RAG 截断不得突破字符预算，也不得切断代理对")
  void ragTruncateRespectsBudget() throws Exception {
    Method truncate = RagContextBuilder.class.getDeclaredMethod(
        "truncate", String.class, int.class);
    truncate.setAccessible(true);

    for (int budget : new int[]{0, 1, 3, 7, 8, 9, 20}) {
      String result = (String) truncate.invoke(null, "abcdefghijklmnopqrstuvwxyz", budget);
      assertTrue(result.length() <= budget,
          "budget=" + budget + " 返回长度=" + result.length());
    }
    assertEquals("", truncate.invoke(null, "abc", 0));

    String withEmoji = (String) truncate.invoke(null, "a😀b😀c😀d", 5);
    assertTrue(withEmoji.length() <= 5);
    assertFalse(Character.isHighSurrogate(withEmoji.charAt(withEmoji.length() - 1)),
        "截断结果不得以孤立高位代理结尾");
  }
}
