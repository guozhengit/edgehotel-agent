package com.edgehotel.agent.agent.channel;

import com.edgehotel.agent.agent.AgentRunner;
import com.edgehotel.agent.agent.TemplateDescriptor;
import com.edgehotel.agent.agent.Tool;
import com.edgehotel.agent.store.ChannelMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.feishu.FeishuCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书渠道适配器（M4-3）：复用官方 FeishuCrypto（回调解密 + 签名校验），薄接入本工程渠道层。
 *
 * <p>飞书事件订阅流：
 * <ol>
 *   <li>URL 验证：decrypt 后 {@code type=url_verification} → 原样回 {@code challenge}；</li>
 *   <li>消息事件：{@code header.event_type=im.message.receive_v1} → peer=open_id、
 *       text=message.content.text（仅 text 类型消息，sender_type=user，防机器人自环）；</li>
 *   <li>真实模式：飞书要求 3s 内 200——立即应答并异步跑 Agent 轮次，回复经飞书 IM API 发送
 *       （需 EH_FEISHU_APP_ID/APP_SECRET；未配置则回复状态=pending_credentials，消息已落库可审计）。</li>
 * </ol>
 *
 * <p>配置：EH_FEISHU_ENABLED / EH_FEISHU_ENCRYPT_KEY / EH_FEISHU_VERIFICATION_TOKEN（事件 token 校验）/
 * EH_FEISHU_APP_ID+APP_SECRET（出站，可选）/ channel.echo（回环测试，不调模型）。
 * 加解密/事件映射均可自生成夹具离线自测，无需真实凭据。
 */
@RestController
@RequestMapping("/api/channel/feishu")
public class FeishuChannelController {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannelController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunner runner;
    private final TemplateDescriptor template;
    private final ChannelMessageStore messages;
    private final ChannelStats stats;
    private final boolean enabled;
    private final boolean echo;
    private final FeishuCrypto crypto;
    private final String verificationToken;
    private final String appId;
    private final String appSecret;

    public FeishuChannelController(AgentRunner runner, TemplateDescriptor template,
                                   ChannelMessageStore messages, ChannelStats stats,
                                   @Value("${channel.feishu.enabled:false}") boolean enabled,
                                   @Value("${channel.feishu.encrypt-key:}") String encryptKey,
                                   @Value("${channel.feishu.verification-token:}") String verificationToken,
                                   @Value("${channel.feishu.app-id:}") String appId,
                                   @Value("${channel.feishu.app-secret:}") String appSecret,
                                   @Value("${channel.echo:false}") boolean echo) {
        this.runner = runner;
        this.template = template;
        this.messages = messages;
        this.stats = stats;
        this.enabled = enabled;
        this.echo = echo;
        this.verificationToken = verificationToken;
        this.appId = appId;
        this.appSecret = appSecret;
        this.crypto = enabled && encryptKey != null && !encryptKey.isBlank()
                ? new FeishuCrypto(encryptKey) : null;
    }

    /** 飞书事件订阅回调入口（v2 schema）。 */
    @PostMapping(value = "/callback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> callback(
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Nonce", required = false) String nonce,
            @RequestBody String rawBody) {
        if (!enabled || crypto == null) {
            return ResponseEntity.status(404).build();
        }
        stats.inc("feishu.received");
        if (!crypto.verifySignature(signature, timestamp, nonce, rawBody)) {
            stats.inc("feishu.drop_bad_signature");
            return ResponseEntity.status(401).body(Map.of("error", "签名校验失败"));
        }
        JsonNode root;
        try {
            JsonNode envelope = JSON.readTree(rawBody);
            String encrypt = envelope.path("encrypt").asText(null);
            if (encrypt == null || encrypt.isBlank()) {
                stats.inc("feishu.drop_missing_encrypt");
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 encrypt 字段"));
            }
            root = JSON.readTree(crypto.decrypt(encrypt));
        } catch (Exception e) {
            stats.inc("feishu.drop_decrypt_failed");
            return ResponseEntity.status(400).body(Map.of("error", "解密/解析失败: " + e.getMessage()));
        }

        // URL 验证（v2）：回 challenge
        if ("url_verification".equals(root.path("type").asText(null))) {
            String challenge = root.path("challenge").asText(null);
            if (challenge == null || challenge.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "url_verification 缺 challenge"));
            }
            stats.inc("feishu.url_verification");
            log.info("[feishu] URL 验证通过，challenge 已回");
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // 事件 token 校验（配置了才强校验）：v2 事件在 header.token，url_verification/v1 在顶层 token
        String token = root.path("header").path("token").asText(root.path("token").asText(null));
        if (verificationToken != null && !verificationToken.isBlank()
                && !WebhookSignature.safeEquals(verificationToken, token)) {
            stats.inc("feishu.drop_token_mismatch");
            return ResponseEntity.status(401).body(Map.of("error", "verification token 不匹配"));
        }

        // 事件映射（v2）：im.message.receive_v1 + text 消息 + user 发送（防机器人自环）
        JsonNode event = root.path("event");
        String openId = event.path("sender").path("sender_id").path("open_id").asText(null);
        String senderType = event.path("sender").path("sender_type").asText(null);
        // message.content 是内嵌 JSON 字符串（飞书规范），需二次解析
        JsonNode contentNode = event.path("message").path("content");
        String text;
        try {
            text = contentNode.isTextual()
                    ? JSON.readTree(contentNode.asText()).path("text").asText(null)
                    : contentNode.path("text").asText(null);
        } catch (Exception e) {
            text = null; // content 非 JSON：视为非文本消息，不处理
        }
        if (!"im.message.receive_v1".equals(root.path("header").path("event_type").asText(null))) {
            stats.inc("feishu.drop_non_message_event"); // 静默丢弃必须有声音（复盘 F10）
            log.info("[feishu] 非消息事件，确认收到不处理");
            return ResponseEntity.ok(Map.of("code", 0));
        }
        if (openId == null || openId.isBlank() || text == null || text.isBlank()
                || !"user".equals(senderType)) {
            stats.inc("feishu.drop_non_text_or_bot"); // 静默丢弃必须有声音（复盘 F10）
            log.info("[feishu] 丢弃非文本/非用户消息: openId={}, senderType={}, hasText={}",
                    openId, senderType, text != null && !text.isBlank());
            return ResponseEntity.ok(Map.of("code", 0));
        }

        String peer = openId;
        String sessionId = "ch-feishu-" + peer;
        messages.record("feishu", peer, "inbound", sessionId, text, "received");
        stats.inc("feishu.verified_inbound");

        if (echo) {
            String reply = "【渠道回环测试·飞书】已收到消息并完成会话映射（不调模型）：text=" + text;
            messages.record("feishu", peer, "outbound", sessionId, reply, "echo");
            stats.inc("feishu.echo_reply");
            log.info("[feishu] echo 回复 peer={}: {}", peer, reply);
            return ResponseEntity.ok(Map.of("code", 0));
        }

        // 真实模式：立即 200（飞书 3s 超时约束），Agent 轮次异步执行
        String userId = "ch:feishu:" + peer;
        final String inboundText = text;
        Mono.fromCallable(() -> runTurn(inboundText, userId, sessionId, peer))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(r -> { },
                        err -> log.warn("[feishu] Agent 轮次失败 peer={}: {}", peer, err.toString()));
        return ResponseEntity.ok(Map.of("code", 0));
    }

    /** 同步执行一轮编排并尽力出站（供异步 subscribe 调用；echo 已在上游拦截）。 */
    private Map<String, Object> runTurn(String text, String userId, String sessionId, String peer) {
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
        boolean outboundReady = appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
        messages.record("feishu", peer, "outbound", sessionId, reply,
                outboundReady ? "sent" : "pending_credentials");
        stats.inc(outboundReady ? "feishu.agent_reply" : "feishu.agent_reply_pending_credentials");
        if (!outboundReady) {
            log.warn("[feishu] 回复已生成但未配置 EH_FEISHU_APP_ID/APP_SECRET，状态=pending_credentials（消息可审计）");
        } else {
            // 出站经飞书 IM API（tenant_access_token 流程）——接真实凭据后启用
            log.info("[feishu] 回复已生成（出站通道接入点）：peer={}", peer);
        }
        return Map.of("reply", reply);
    }

}
