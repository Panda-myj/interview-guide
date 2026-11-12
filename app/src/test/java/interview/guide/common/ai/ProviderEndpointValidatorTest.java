package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProviderEndpointValidator 出站地址安全校验")
class ProviderEndpointValidatorTest {

  @Nested
  @DisplayName("协议限制")
  class SchemeRules {

    @Test
    @DisplayName("只允许 http / https")
    void rejectsNonHttpSchemes() {
      BusinessException fileError = assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.requireHttpEndpoint("file:///etc/passwd", false));
      assertEquals(ErrorCode.BAD_REQUEST.getCode(), fileError.getCode());

      assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.requireHttpEndpoint("ftp://example.com", false));
      assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.requireHttpEndpoint("javascript:alert(1)", false));
    }

    @Test
    @DisplayName("语音地址只允许 ws / wss")
    void rejectsHttpSchemeForWebSocketEndpoint() {
      assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.requireWebSocketEndpoint("https://example.com", false));
      assertDoesNotThrow(() -> ProviderEndpointValidator.requireWebSocketEndpoint(
          "wss://8.8.8.8/api-ws/v1/realtime", false));
    }

    @Test
    @DisplayName("拒绝在 URL 里携带用户名密码")
    void rejectsUserInfo() {
      BusinessException exception = assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.requireHttpEndpoint("http://user:pass@example.com", false));

      assertTrue(exception.getMessage().contains("用户名密码"));
    }

    @Test
    @DisplayName("空地址直接拒绝")
    void rejectsBlankUrl() {
      assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.requireHttpEndpoint(null, false));
      assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.requireHttpEndpoint("   ", false));
    }
  }

  @Nested
  @DisplayName("内网与特殊地址")
  class InternalAddressRules {

    @Test
    @DisplayName("默认拒绝回环、私网、链路本地与云元数据地址")
    void rejectsInternalHosts() {
      String[] blockedUrls = {
          "http://localhost:1234",
          "http://127.0.0.1:8080",
          "http://169.254.169.254/latest/meta-data",
          "http://10.0.0.5",
          "http://172.16.0.10",
          "http://192.168.1.10",
          "http://100.64.0.1",
          "http://198.18.0.1",
          "http://[::1]:8080",
          "http://0.0.0.0:8080"
      };

      for (String url : blockedUrls) {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> ProviderEndpointValidator.requireHttpEndpoint(url, false), url);
        assertEquals(ErrorCode.FORBIDDEN.getCode(), exception.getCode(), url);
      }
    }

    @Test
    @DisplayName("显式开启 allow-local-endpoints 后允许本机地址")
    void allowsLocalHostWhenExplicitlyEnabled() {
      assertDoesNotThrow(
          () -> ProviderEndpointValidator.requireHttpEndpoint("http://localhost:1234", true));
      assertDoesNotThrow(
          () -> ProviderEndpointValidator.requireWebSocketEndpoint("ws://127.0.0.1:9000/asr", true));
    }

    @Test
    @DisplayName("公网地址默认可通过")
    void allowsPublicAddress() {
      assertDoesNotThrow(
          () -> ProviderEndpointValidator.requireHttpEndpoint("https://8.8.8.8/v1", false));
    }

    @Test
    @DisplayName("isBlockedAddress 覆盖私网、CGNAT 与 IPv6 ULA")
    void detectsBlockedAddresses() throws UnknownHostException {
      assertTrue(ProviderEndpointValidator.isBlockedAddress(InetAddress.getByName("10.1.2.3")));
      assertTrue(ProviderEndpointValidator.isBlockedAddress(InetAddress.getByName("192.168.0.1")));
      assertTrue(ProviderEndpointValidator.isBlockedAddress(InetAddress.getByName("169.254.169.254")));
      assertTrue(ProviderEndpointValidator.isBlockedAddress(InetAddress.getByName("100.100.1.1")));
      assertTrue(ProviderEndpointValidator.isBlockedAddress(InetAddress.getByName("fd00::1")));
      assertFalse(ProviderEndpointValidator.isBlockedAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    @DisplayName("resolveAllowedAddress 在严格模式下拒绝回环地址")
    void resolveAllowedAddressRejectsLoopback() {
      BusinessException exception = assertThrows(BusinessException.class,
          () -> ProviderEndpointValidator.resolveAllowedAddress("127.0.0.1", false));

      assertEquals(ErrorCode.FORBIDDEN.getCode(), exception.getCode());
      assertDoesNotThrow(
          () -> ProviderEndpointValidator.resolveAllowedAddress("127.0.0.1", true));
    }
  }
}
