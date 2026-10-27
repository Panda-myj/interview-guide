package interview.guide.modules.voiceinterview.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Qwen-TTS-Realtime 原始帧探针：手工完成 TLS + WebSocket 握手，直接读字节流。
 *
 * <p>动机：服务端在 session.created 之后会发送一个 178 字节的 CLOSE 帧（RFC 6455 规定控制帧
 * 负载不得超过 125 字节）。OkHttp 报 "Control frame must be less than 125B."，
 * JDK 报 "CLOSE's payload length 178" —— 两个合规客户端都会拒收，
 * 服务端真正的错误信息因此永远丢失。
 *
 * <p>本探针不校验 RFC 限制，把 close 帧的 payload 原样打印（文本 + 十六进制），
 * 从而拿到服务端真正的错误原因。
 *
 * <p>默认跳过。手动执行：
 * <pre>
 * RUN_TTS_PROBE=1 ./gradlew :app:test --tests "*TtsRawCloseProbe*" --no-daemon
 * </pre>
 * 报告写入 {@code app/build/tts-raw-close-report.txt}。
 */
@DisplayName("Qwen TTS Realtime 原始帧探针（取 close payload）")
class TtsRawCloseProbeTest {

  private static final String HOST = "dashscope.aliyuncs.com";
  private static final int PORT = 443;
  private static final String PATH = "/api-ws/v1/realtime?model=qwen3-tts-flash-realtime";
  private static final int MAX_FRAME_BYTES = 1 << 20;
  private static final Path REPORT_PATH = Path.of("build", "tts-raw-close-report.txt");

  @Test
  @DisplayName("手工握手并导出 close 帧的原始 payload")
  void dumpClosePayload() throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_TTS_PROBE")),
        "跳过探针：设置 RUN_TTS_PROBE=1 才会真正发起网络请求");

    String apiKey = resolveApiKey();
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
        "跳过探针：未找到 AI_BAILIAN_API_KEY（环境变量或 .env）");

    List<String> log = new ArrayList<>();
    byte[] closePayload = null;
    int closeCode = -1;
    int audioFrames = 0;
    String handshakeStatus = "(未完成)";

    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(HOST, PORT);
    try {
      socket.setSoTimeout(20_000);
      socket.startHandshake();

      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      byte[] nonce = new byte[16];
      new SecureRandom().nextBytes(nonce);
      String request = "GET " + PATH + " HTTP/1.1\r\n"
          + "Host: " + HOST + "\r\n"
          + "Upgrade: websocket\r\n"
          + "Connection: Upgrade\r\n"
          + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
          + "Sec-WebSocket-Version: 13\r\n"
          + "Authorization: Bearer " + apiKey + "\r\n"
          + "\r\n";
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();

      handshakeStatus = readHttpResponseHead(in);
      log.add("握手响应首行: " + handshakeStatus);

      String sessionUpdate = "{\"type\":\"session.update\",\"session\":{"
          + "\"voice\":\"Cherry\",\"response_format\":\"pcm\",\"sample_rate\":24000,"
          + "\"mode\":\"commit\",\"language_type\":\"Chinese\"}}";
      String appendText = "{\"type\":\"input_text_buffer.append\",\"text\":\"你好，这是一次语音合成连通性探测。\"}";
      String commitText = "{\"type\":\"input_text_buffer.commit\"}";
      boolean synthesisSent = false;

      while (true) {
        int b0 = in.read();
        if (b0 < 0) {
          log.add("对端直接 EOF，没有 close 帧");
          break;
        }
        int b1 = in.read();
        if (b1 < 0) {
          log.add("读取帧头时 EOF");
          break;
        }
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (length == 126) {
          length = ((long) in.read() << 8) | in.read();
        } else if (length == 127) {
          length = 0;
          for (int i = 0; i < 8; i++) {
            length = (length << 8) | in.read();
          }
        }
        if (length > MAX_FRAME_BYTES) {
          log.add("帧过大，放弃: opcode=" + opcode + " len=" + length);
          break;
        }
        byte[] mask = masked ? readFully(in, 4) : null;
        byte[] payload = readFully(in, (int) length);
        if (masked && mask != null) {
          for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
          }
        }

        if (opcode == 8) {
          closePayload = payload;
          closeCode = payload.length >= 2
              ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
              : -1;
          break;
        } else if (opcode == 1) {
          String text = new String(payload, StandardCharsets.UTF_8);
          if (text.contains("\"response.audio.delta\"")) {
            audioFrames++;
            if (audioFrames <= 3) {
              log.add("[audio.delta] 第 " + audioFrames + " 个音频分片，payload " + payload.length + " 字节");
            }
          } else {
            log.add("[text] " + truncate(text, 800));
          }
          // 会话就绪后按官方流程请求合成，这才是触发服务端拒绝的动作
          if (!synthesisSent && text.contains("session.created")) {
            synthesisSent = true;
            log.add(">>> 发送 session.update / input_text_buffer.append / commit");
            sendTextFrame(out, sessionUpdate);
            sendTextFrame(out, appendText);
            sendTextFrame(out, commitText);
            log.add(">>> 已发送，等待服务端响应");
          }
        } else if (opcode == 9) {
          log.add("[ping] " + toHex(payload, 32));
        } else {
          log.add("[opcode=" + opcode + "] len=" + length);
        }
      }
    } catch (Exception e) {
      log.add("探针异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      try {
        socket.close();
      } catch (Exception ignored) {
        // 关闭失败不影响结论
      }
    }

    String report = buildReport(apiKey, handshakeStatus, log, closeCode, closePayload, audioFrames);
    writeReport(report);

    if (closePayload == null) {
      throw new AssertionError("没有捕获到 close 帧，报告如下：\n" + report);
    }
  }

  private static String buildReport(String apiKey, String handshakeStatus, List<String> log,
                                    int closeCode, byte[] closePayload, int audioFrames) {
    StringBuilder report = new StringBuilder();
    report.append("=== Qwen-TTS-Realtime 原始帧探针 ===\n");
    report.append("URL      : wss://").append(HOST).append(PATH).append('\n');
    report.append("API Key  : ").append(mask(apiKey)).append('\n');
    report.append("握手响应 : ").append(handshakeStatus).append('\n');
    report.append("音频分片 : ").append(audioFrames).append("\n\n");

    report.append("--- 收到的帧 ---\n");
    for (String line : log) {
      report.append(truncate(line, 1200)).append('\n');
    }

    report.append("\n--- CLOSE 帧 ---\n");
    if (closePayload == null) {
      report.append("(没有捕获到 close 帧)\n");
      return report.toString();
    }
    report.append("close code  : ").append(closeCode).append('\n');
    report.append("payload 长度: ").append(closePayload.length).append(" 字节\n");
    String reason = closePayload.length > 2
        ? new String(closePayload, 2, closePayload.length - 2, StandardCharsets.UTF_8)
        : "";
    report.append("payload 文本:\n").append(reason).append("\n\n");
    report.append("payload 十六进制:\n").append(toHex(closePayload, closePayload.length)).append('\n');
    return report.toString();
  }

  /** 读取 HTTP 响应头，直到遇到空行；返回状态行。 */
  private static String readHttpResponseHead(InputStream in) throws Exception {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    final String terminator = "\r\n\r\n";
    int matched = 0;
    while (matched < terminator.length()) {
      int b = in.read();
      if (b < 0) {
        throw new IllegalStateException("读取握手响应头时 EOF");
      }
      head.write(b);
      if ((char) b == terminator.charAt(matched)) {
        matched++;
      } else {
        matched = (b == '\r') ? 1 : 0;
      }
    }
    String text = head.toString(StandardCharsets.US_ASCII);
    String[] lines = text.split("\r\n");
    return lines.length > 0 ? lines[0] : text.trim();
  }

  /** 发送一个带掩码的客户端文本帧（RFC 6455 要求客户端发送的帧必须掩码）。 */
  private static void sendTextFrame(OutputStream out, String text) throws Exception {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    byte[] mask = new byte[4];
    new SecureRandom().nextBytes(mask);

    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    frame.write(0x81); // FIN + opcode=1(text)
    if (payload.length < 126) {
      frame.write(0x80 | payload.length);
    } else if (payload.length <= 0xFFFF) {
      frame.write(0x80 | 126);
      frame.write((payload.length >>> 8) & 0xFF);
      frame.write(payload.length & 0xFF);
    } else {
      frame.write(0x80 | 127);
      for (int i = 7; i >= 0; i--) {
        frame.write((int) ((((long) payload.length) >>> (8 * i)) & 0xFF));
      }
    }
    frame.write(mask);
    for (int i = 0; i < payload.length; i++) {
      frame.write(payload[i] ^ mask[i % 4]);
    }
    out.write(frame.toByteArray());
    out.flush();
  }

  private static byte[] readFully(InputStream in, int length) throws Exception {
    byte[] buffer = new byte[length];
    int offset = 0;
    while (offset < length) {
      int read = in.read(buffer, offset, length - offset);
      if (read < 0) {
        throw new IllegalStateException("读取 payload 时 EOF，已读 " + offset + "/" + length);
      }
      offset += read;
    }
    return buffer;
  }

  private static String toHex(byte[] data, int max) {
    StringBuilder hex = new StringBuilder();
    int limit = Math.min(data.length, max);
    for (int i = 0; i < limit; i++) {
      hex.append(String.format("%02x", data[i]));
      if (i < limit - 1) {
        hex.append(' ');
      }
    }
    if (data.length > limit) {
      hex.append(" ...(").append(data.length).append(" bytes)");
    }
    return hex.toString();
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
  }

  private static String mask(String apiKey) {
    return apiKey.length() <= 10
        ? "***"
        : apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 4);
  }

  private static void writeReport(String content) throws Exception {
    System.out.println(content);
    Files.createDirectories(REPORT_PATH.getParent());
    Files.writeString(REPORT_PATH, content, StandardCharsets.UTF_8);
  }

  private static String resolveApiKey() {
    String fromEnv = System.getenv("AI_BAILIAN_API_KEY");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    Path dir = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 4 && dir != null; depth++) {
      String key = readKeyFromEnvFile(dir.resolve(".env"));
      if (key != null) {
        return key;
      }
      dir = dir.getParent();
    }
    return null;
  }

  private static String readKeyFromEnvFile(Path envFile) {
    if (!Files.isRegularFile(envFile)) {
      return null;
    }
    try {
      for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("AI_BAILIAN_API_KEY=")) {
          String value = trimmed.substring("AI_BAILIAN_API_KEY=".length()).trim();
          return value.isEmpty() ? null : value;
        }
      }
    } catch (Exception ignored) {
      return null;
    }
    return null;
  }
}
