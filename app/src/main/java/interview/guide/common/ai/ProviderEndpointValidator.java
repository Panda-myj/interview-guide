package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Provider / 语音服务出站地址校验，用于阻断 SSRF 与内网探测。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只允许显式声明的协议（HTTP 场景 http/https，WebSocket 场景 ws/wss）。</li>
 *   <li>默认拒绝回环、私网、链路本地（含 169.254.169.254 云元数据）、CGNAT、基准测试网段和 IPv6 ULA。</li>
 *   <li>主机名会先解析全部 A/AAAA 记录，任一条命中保留地址即拒绝，避免 DNS 指向内网。</li>
 *   <li>本机调试（如 lmstudio 的 http://localhost:1234）必须通过
 *       {@code app.ai.security.allow-local-endpoints=true} 显式放开，生产环境保持 false。</li>
 * </ul>
 */
public final class ProviderEndpointValidator {

  private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");
  private static final Set<String> WEB_SOCKET_SCHEMES = Set.of("ws", "wss");

  private ProviderEndpointValidator() {
  }

  /**
   * 校验 Provider 的 HTTP baseUrl / 测试连接地址。
   *
   * @param rawUrl            用户填写的地址
   * @param allowLocalEndpoints 是否显式允许本机/内网地址
   * @return 解析后的 URI
   */
  public static URI requireHttpEndpoint(String rawUrl, boolean allowLocalEndpoints) {
    URI uri = parse(rawUrl, HTTP_SCHEMES, "Provider 地址");
    verifyHost(uri, allowLocalEndpoints, "Provider 地址");
    return uri;
  }

  /**
   * 校验语音 ASR/TTS 的 WebSocket 地址。
   */
  public static URI requireWebSocketEndpoint(String rawUrl, boolean allowLocalEndpoints) {
    URI uri = parse(rawUrl, WEB_SOCKET_SCHEMES, "语音服务地址");
    verifyHost(uri, allowLocalEndpoints, "语音服务地址");
    return uri;
  }

  /**
   * 解析主机并返回一个已通过内网校验的地址，调用方应直接连接该地址，避免二次解析被 DNS 重绑定绕过。
   */
  public static InetAddress resolveAllowedAddress(String host, boolean allowLocalEndpoints) {
    if (host == null || host.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "目标主机不能为空");
    }
    InetAddress address;
    try {
      address = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "目标主机无法解析: " + host, e);
    }
    if (!allowLocalEndpoints && isBlockedAddress(address)) {
      throw new BusinessException(ErrorCode.FORBIDDEN,
          "目标地址 '" + host + "' 指向本机或内网，已拒绝连接");
    }
    return address;
  }

  /**
   * 判断地址是否属于必须拒绝的范围。
   */
  public static boolean isBlockedAddress(InetAddress address) {
    if (address == null) {
      return true;
    }
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      int first = bytes[0] & 0xff;
      int second = bytes[1] & 0xff;
      if (first == 0) {
        // 0.0.0.0/8
        return true;
      }
      if (first == 100 && (second & 0xc0) == 0x40) {
        // 100.64.0.0/10 运营商级 NAT
        return true;
      }
      // 198.18.0.0/15 基准测试保留段，常被本地代理用作 fake-ip，按内网同等级处理
      return first == 198 && (second == 18 || second == 19);
    }
    if (bytes.length == 16) {
      // fc00::/7 唯一本地地址
      return (bytes[0] & 0xfe) == 0xfc;
    }
    return false;
  }

  private static URI parse(String rawUrl, Set<String> allowedSchemes, String label) {
    String trimmed = rawUrl == null ? null : rawUrl.trim();
    if (trimmed == null || trimmed.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, label + " 不能为空");
    }
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, label + " 不是合法 URL: " + trimmed, e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          label + " 只允许 " + String.join("/", allowedSchemes) + " 协议: " + trimmed);
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, label + " 缺少主机名: " + trimmed);
    }
    if (uri.getUserInfo() != null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, label + " 不允许在 URL 中携带用户名密码");
    }
    return uri;
  }

  private static void verifyHost(URI uri, boolean allowLocalEndpoints, String label) {
    if (allowLocalEndpoints) {
      return;
    }
    String host = uri.getHost();
    List<InetAddress> addresses;
    try {
      addresses = List.of(InetAddress.getAllByName(host));
    } catch (UnknownHostException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, label + " 主机无法解析: " + host, e);
    }
    for (InetAddress address : addresses) {
      if (isBlockedAddress(address)) {
        throw new BusinessException(ErrorCode.FORBIDDEN,
            label + " '" + host + "' 解析到本机或内网地址 " + address.getHostAddress() + "，已拒绝连接");
      }
    }
  }
}
