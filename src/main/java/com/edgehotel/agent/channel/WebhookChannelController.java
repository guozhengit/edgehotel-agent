package com.edgehotel.agent.agent.channel;

import com.edgehotel.agent.agent.AgentRunner;
import com.edgehotel.agent.agent.TemplateDescriptor;
import com.edgehotel.agent.agent.Tool;
import com.edgehotel.agent.store.ChannelMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 私域渠道接入（M4-1）：通用 Webhook 入口。
 *
 * <p>消息流：平台回调 → 验签（HMAC + ±5min 防重放）→ peer→会话映射（确定性，跨轮次记忆连续）
 * → AgentRunner 编排（与 /api/agent/ask 完全同链路：记忆注入/工具/审批/静默失败校验）→ 文本回复。
 *
 * <p>两种模式：
 * <ul>
 *   <li>{@code EH_CHANNEL_ECHO=true}（回环测试）：不调模型，确定性回显并回报会话映射——
 *       渠道全链路可在无推理环境验证；</li>
 *   <li>默认（真实模式）：走 LLM 编排，回复最终答案。</li>
 * </ul>
 *
 * <p>安全边界：{@code enabled=true} 时必须配置 {@code EH_CHANNEL_SECRET}（echo 本地联调除外）；
 * 渠道关闭时端点整体 404 不暴露存在性。平台专有适配（飞书/企业微信回调加密格式）为后续增量，
 * 官方 agentscope-extensions-channel-* 已就位（feishu/weixin/dingtalk/wecom）。
 */
@RestController
@RequestMapping("/api/channel")
public class WebhookChannelController {

    private static final long REPLAY_WINDOW_SECONDS = 300;

    private final AgentRunner runner;
    private final TemplateDescriptor template;
    private final ChannelMessageStore messageStore;
    private final boolean enabled;
    private final String secret;
    private final boolean echo;

    public WebhookChannelController(AgentRunner runner, TemplateDescriptor template,
                                    ChannelMessageStore messageStore,
                                    @Value("${channel.enabled:false}") boolean enabled,
                                    @Value("${channel.secret:}") String secret,
                                    @Value("${channel.echo:false}") boolean echo) {
        this.runner = runner;
        this.template = template;
        this.messageStore = messageStore;
        this.enabled = enabled;
        this.secret = secret;
        this.echo = echo;
    }

    public record Inbound(String peer, String text, String externalSessionId) {
    }

    @PostMapping(value = "/webhook", produces = "application/json")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(value = "X-EdgeHotel-Timestamp", required = false) String tsHeader,
            @RequestHeader(value = "X-EdgeHotel-Signature", required = false) String sigHeader,
            @RequestBody String rawBody) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!echo && (secret == null || secret.isBlank())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "渠道未配置 EH_CHANNEL_SECRET，拒绝服务"));
        }
        // 验签 + 防重放
        if (tsHeader == null || sigHeader == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "缺少签名头"));
        }
        long ts;
        try {
            ts = Long.parseLong(tsHeader);
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "时间戳非法"));
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > REPLAY_WINDOW_SECONDS) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "时间戳超出重放窗口"));
        }
        if (!WebhookSignature.safeEquals(WebhookSignature.hmacHex(secret, tsHeader, rawBody), sigHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "签名校验失败"));
        }

        // 解析载荷（Jackson 读取，避免嵌套对象同名键取错值）
        String peer;
        String text;
        String externalSessionId;
        try {
            JsonNode root = new ObjectMapper().readTree(rawBody);
            peer = root.path("peer").asText(null);
            text = root.path("text").asText(null);
            externalSessionId = root.path("externalSessionId").asText(null);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "载荷非法 JSON"));
        }
        if (peer == null || peer.isBlank() || text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "peer 与 text 必填"));
        }

        // 会话映射：确定性（跨轮次记忆/审批留痕连续）；externalSessionId 细分同用户并发会话
        String userId = "ch:webhook:" + peer;
        String sessionId = "ch-webhook-" + peer
                + (externalSessionId == null || externalSessionId.isBlank() ? "" : "-" + externalSessionId);

        messageStore.record("webhook", peer, "inbound", sessionId, text, "received");

        // 回环测试模式：不调模型
        if (echo) {
            String reply = "【渠道回环测试】已收到消息并完成会话映射（不调模型）：text=" + text;
            messageStore.record("webhook", peer, "outbound", sessionId, reply, "echo");
            return ResponseEntity.ok(Map.of(
                    "mode", "echo",
                    "peer", peer,
                    "session_id", sessionId,
                    "reply", reply));
        }

        // 真实模式：与 /api/agent/ask 同编排链路
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", template.systemPrompt());
        messages.add(sys);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", text);
        messages.add(user);
        AgentRunner.AgentTurn turn = runner.run(messages, new Tool.ToolContext(userId, sessionId));
        messageStore.record("webhook", peer, "outbound", sessionId, turn.finalAnswer(), "sent");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "agent");
        out.put("peer", peer);
        out.put("session_id", sessionId);
        out.put("reply", turn.finalAnswer());
        if (!turn.toolCalls().isEmpty()) {
            out.put("tool_calls", turn.toolCalls());
        }
        if (!turn.verifierWarnings().isEmpty()) {
            out.put("verifier_warnings", turn.verifierWarnings());
        }
        if (!turn.approvals().isEmpty()) {
            out.put("approvals", turn.approvals());
        }
        return ResponseEntity.ok(out);
    }

}
