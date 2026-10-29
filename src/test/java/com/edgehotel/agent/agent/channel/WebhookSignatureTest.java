package com.edgehotel.agent.agent.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 签名单元测试：KAT（已知答案向量，独立 Python 实现预计算，防循环自证）
 * + 恒定时间比较语义。
 */
class WebhookSignatureTest {

    @Test
    void 已知答案向量() {
        // 独立预计算：hmac_sha256(key="kat-secret", msg="1700000000.{\"peer\":\"k\",\"text\":\"hello\"}")
        String sig = WebhookSignature.hmacHex("kat-secret", "1700000000",
                "{\"peer\":\"k\",\"text\":\"hello\"}");
        assertEquals("dcc38300b5e4df5a2f5b74fe7bb85d2d9580797f81fb1e0d979fd1754a8f3206", sig);
    }

    @Test
    void 中文载荷签名稳定() {
        String body = "{\"peer\":\"客人\",\"text\":\"你好\"}";
        String a = WebhookSignature.hmacHex("s", "123", body);
        String b = WebhookSignature.hmacHex("s", "123", body);
        assertEquals(a, b);
    }

    @Test
    void 不同密钥或载荷产生不同签名() {
        String base = WebhookSignature.hmacHex("s", "123", "body");
        assertNotEquals(base, WebhookSignature.hmacHex("s2", "123", "body"));
        assertNotEquals(base, WebhookSignature.hmacHex("s", "124", "body"));
        assertNotEquals(base, WebhookSignature.hmacHex("s", "123", "body2"));
    }

    @Test
    void 恒定时间比较() {
        String sig = WebhookSignature.hmacHex("s", "123", "body");
        assertTrue(WebhookSignature.safeEquals(sig, sig));
        assertFalse(WebhookSignature.safeEquals(sig, "deadbeef"));
        assertFalse(WebhookSignature.safeEquals(null, sig));
        assertFalse(WebhookSignature.safeEquals(sig, null));
        assertFalse(WebhookSignature.safeEquals("", ""));
    }
}
