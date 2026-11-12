package interview.guide.modules.auth.sms;

import java.util.Map;

public record SmsMessage(
    String phone,
    String templateCode,
    Map<String, String> templateParams
) {
}
