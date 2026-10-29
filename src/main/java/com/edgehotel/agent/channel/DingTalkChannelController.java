package com.edgehotel.agent.agent.channel;

import com.edgehotel.agent.agent.AgentRunner;
import com.edgehotel.agent.agent.TemplateDescriptor;
import com.edgehotel.agent.agent.Tool;
import com.edgehotel.agent.store.ChannelMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.dingtalk.DingTalkCallbackCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉渠道适配器（M4 收尾）：复用官方 DingTalkCallbackCrypto（HMAC-SHA256 时间戳签名 + 可选 AES 信封解密）。
 *
 * <p>钉钉机器人回调流：POST JSON（签名头 timestamp/sign）→ 验签 → 有 encrypt 信封则解密 →
 * msgtype=text → peer 规则（单聊 conversationType=1 用 senderStaffId；群聊 =2 用 conversationId）。
 * **恒 200**（钉钉不重试；仅验签失败 401），对齐官方控制器语义。
 *
 * <p>出站：钉钉机器人回复走载荷里的 {@code sessionWebhook}（平台签发的一次性回发地址）——
 * **无需任何凭据**即可出站，是三渠道中唯一开箱即闭环的；webhook 缺失时状态=pending。
 *
 * <p>配置：EH_DINGTALK_ENABLED / EH_DINGTALK_APP_SECRET（签名密钥）/ EH_DINGTALK_AES_KEY（可选加密）。
 * 离线自测：scripts/GenDingTalkFixture.java 生成签名载荷，无需真实凭据。
 */
@RestController
@RequestMapping("/api/channel/dingtalk")
public class DingTalkChannelController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannelController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunner runner;
    private final TemplateDescriptor template;
    private final ChannelMessageStore messages;
    private final ChannelStats stats;
    private final ChannelRateLimiter rateLimiter;
    private final boolean enabled;
    private final boolean echo;
    private final DingTalkCallbackCrypto crypto;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public DingTalkChannelController(AgentRunner runner, TemplateDescriptor template,
                                     ChannelMessageStore messages, ChannelStats stats,
                                     ChannelRateLimiter rateLimiter,
                                     @Value("${channel.dingtalk.enabled:false}") boolean enabled,
                                     @Value("${channel.dingtalk.app-secret:}") String appSecret,
                                     @Value("${channel.dingtalk.aes-key:}") String aesKey,
                                     @Value("${channel.echo:false}") boolean echo) {
        this.runner = runner;
        this.template = template;
        this.messages = messages;
        this.stats = stats;
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.echo = echo;
        this.crypto = enabled && notBlank(appSecret)
                ? new DingTalkCallbackCrypto(appSecret, notBlank(aesKey) ? aesKey : null) : null;
    }

    @PostMapping("/callback")
    public Mono<ResponseEntity<String>> callback(
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String sign,
            @RequestBody String rawBody) {
        if (!enabled || crypto == null) {
            return Mono.just(ResponseEntity.status(404).build());
        }
        stats.inc("dingtalk.received");
        if (!crypto.verify(timestamp, sign)) {
            stats.inc("dingtalk.drop_bad_signature");
            return Mono.just(ResponseEntity.status(401).body("signature mismatch"));
        }

        JsonNode payload;
        try {
            payload = JSON.readTree(rawBody);
        } catch (Exception e) {
            stats.inc("dingtalk.drop_bad_payload");
            return Mono.just(ResponseEntity.ok("bad json")); // 恒 200（钉钉不重试）
        }
        // 可选 AES 信封：{"encrypt": ...} → 解密得明文载荷
        JsonNode encrypt = payload.get("encrypt");
        if (encrypt != null && encrypt.isTextual() && !encrypt.asText().isBlank()) {
            try {
                payload = JSON.readTree(crypto.decrypt(encrypt.asText()));
            } catch (Exception e) {
                stats.inc("dingtalk.drop_decrypt_failed");
                log.warn("[dingtalk] 解密失败: {}", e.getMessage());
                return Mono.just(ResponseEntity.ok("decrypt failed"));
            }
        }

        String msgType = payload.path("msgtype").asText(null);
        String text = "text".equalsIgnoreCase(msgType)
                ? payload.path("text").path("content").asText(null) : null;
        if (text != null) {
            text = text.strip();
            if (text.isEmpty()) {
                text = null;
            }
        }
        String conversationType = payload.path("conversationType").asText("1");
        String senderStaffId = payload.path("senderStaffId").asText(null);
        String conversationId = payload.path("conversationId").asText(null);
        // 同官方映射：群聊 peer=conversationId，单聊 peer=senderStaffId
        String peer = "2".equals(conversationType) && notBlank(conversationId)
                ? conversationId : senderStaffId;
        if (text == null || peer == null || peer.isBlank()) {
            stats.inc("dingtalk.drop_non_text");
            log.info("[dingtalk] 丢弃非文本/无 peer 消息: msgType={}, peer={}", msgType, peer);
            return Mono.just(ResponseEntity.ok(""));
        }
        if (!rateLimiter.allow(peer)) {
            stats.inc("dingtalk.drop_rate_limited");
            return Mono.just(ResponseEntity.status(429).body("rate limited"));
        }

        String sessionId = "ch-dingtalk-" + peer;
        String userId = "ch:dingtalk:" + peer;
        messages.record("dingtalk", peer, "inbound", sessionId, text, "received");
        stats.inc("dingtalk.verified_inbound");

        if (echo) {
            String reply = "【渠道回环测试·钉钉】已收到消息并完成会话映射（不调模型）：text=" + text;
            messages.record("dingtalk", peer, "outbound", sessionId, reply, "echo");
            stats.inc("dingtalk.echo_reply");
            log.info("[dingtalk] echo 回复 peer={}", peer);
            return Mono.just(ResponseEntity.ok(""));
        }

        // 真实模式：立即 200，Agent 轮次异步；回复经 sessionWebhook 出站（平台签发地址，无需凭据）
        final String sessionWebhook = payload.path("sessionWebhook").asText(null);
        final String inboundText = text;
        Mono.fromCallable(() -> runTurn(inboundText, userId, sessionId, peer, sessionWebhook))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(r -> { }, err -> log.warn("[dingtalk] Agent 轮次失败 peer={}: {}", peer, err.toString()));
        return Mono.just(ResponseEntity.ok(""));
    }

    private Map<String, Object> runTurn(String text, String userId, String sessionId, String peer,
                                        String sessionWebhook) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", template.systemPrompt());
        msgs.add(sys);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", text);
        msgs.add(user);
        AgentRunner.AgentTurn turn = runner.run(msgs, new Tool.ToolContext(userId, sessionId));
        String reply = turn.finalAnswer();
        String status;
        if (notBlank(sessionWebhook)) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(sessionWebhook))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                                Map.of("msgtype", "text", "text", Map.of("content", reply))))).build();
                status = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
                        ? "sent" : "webhook_error";
            } catch (Exception e) {
                status = "webhook_error";
                log.warn("[dingtalk] sessionWebhook 出站失败: {}", e.getMessage());
            }
        } else {
            status = "pending";
            log.warn("[dingtalk] 回复已生成但载荷无 sessionWebhook，状态=pending（消息可审计）");
        }
        messages.record("dingtalk", peer, "outbound", sessionId, reply, status);
        stats.inc("dingtalk.agent_reply_" + status);
        return Map.of("reply", reply, "status", status);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
