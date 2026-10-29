package com.edgehotel.agent.agent.channel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhook 签名（渠道接入 M4）：HMAC-SHA256(secret, "{timestamp}.{rawBody}") 的 hex，
 * 恒定时间比较防时序侧信道。对齐官方 dataagent WebhookSignature 模式。
 */
public final class WebhookSignature {

    private WebhookSignature() {
    }

    public static String hmacHex(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败: " + e.getMessage(), e);
        }
    }

    public static boolean safeEquals(String a, String b) {
        // 空白串恒不匹配：上游 bug 传空签名时不得通过（纵深防御）
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
