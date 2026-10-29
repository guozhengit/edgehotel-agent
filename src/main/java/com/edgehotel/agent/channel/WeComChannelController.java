package com.edgehotel.agent.agent.channel;

import com.edgehotel.agent.agent.AgentRunner;
import com.edgehotel.agent.agent.TemplateDescriptor;
import com.edgehotel.agent.agent.Tool;
import com.edgehotel.agent.store.ChannelMessageStore;
import io.agentscope.extensions.channel.wecom.WeComCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信渠道适配器（M4 收尾）：复用官方 WeComCrypto（SHA-1 排序签名 + AES-CBC 回调解密），薄接入本工程渠道层。
 *
 * <p>企微回调流：
 * <ol>
 *   <li>URL 验证（GET）：verify(msg_signature, echostr) → decrypt(echostr) → 回**解密后明文**；</li>
 *   <li>消息推送（POST）：XML 信封 {@code <Encrypt>} → 验签（对 encrypt 串）→ 解密得到消息 XML →
 *       MsgType=text + FromUserName=peer；幂等去重按 MsgId（同官方，重复 200 静默）；</li>
 *   <li>真实模式：异步 Agent 轮次（企微要求 5s 内应答），出站经企微 API 发送
 *       （需 EH_WECOM_CORP_ID/SECRET；未配置状态=pending_credentials，消息已落库可审计）。</li>
 * </ol>
 *
 * <p>配置：EH_WECOM_ENABLED / EH_WECOM_TOKEN / EH_WECOM_ENCODING_AES_KEY / EH_WECOM_RECEIVE_ID（corpId）；
 * echo 回环模式不调模型。全部可自生成夹具离线自测（scripts/GenWeComFixture.java），无需真实凭据。
 */
@RestController
@RequestMapping("/api/channel/wecom")
public class WeComChannelController {

    private static final Logger log = LoggerFactory.getLogger(WeComChannelController.class);
    private static final DocumentBuilderFactory DOM_FACTORY = DocumentBuilderFactory.newInstance();

    static {
        try {
            DOM_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // 防 XXE
        } catch (Exception ignored) {
            // 解析器不支持该特性时保持默认（本工程仅解析受签名保护的回调体）
        }
    }

    private final AgentRunner runner;
    private final TemplateDescriptor template;
    private final ChannelMessageStore messages;
    private final ChannelStats stats;
    private final ChannelRateLimiter rateLimiter;
    private final boolean enabled;
    private final boolean echo;
    private final WeComCrypto crypto;
    private final String corpId;
    private final String corpSecret;

    /** MsgId 幂等集（同官方 IdempotencyStore 语义，内存版足够单机渠道层）。 */
    private static final Set<String> recentMsgIds = ConcurrentHashMap.newKeySet();

    public WeComChannelController(AgentRunner runner, TemplateDescriptor template,
                                  ChannelMessageStore messages, ChannelStats stats,
                                  ChannelRateLimiter rateLimiter,
                                  @Value("${channel.wecom.enabled:false}") boolean enabled,
                                  @Value("${channel.wecom.token:}") String token,
                                  @Value("${channel.wecom.encoding-aes-key:}") String encodingAesKey,
                                  @Value("${channel.wecom.receive-id:}") String receiveId,
                                  @Value("${channel.wecom.corp-id:}") String corpId,
                                  @Value("${channel.wecom.corp-secret:}") String corpSecret,
                                  @Value("${channel.echo:false}") boolean echo) {
        this.runner = runner;
        this.template = template;
        this.messages = messages;
        this.stats = stats;
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.echo = echo;
        this.corpId = corpId;
        this.corpSecret = corpSecret;
        this.crypto = enabled && notBlank(token) && notBlank(encodingAesKey)
                ? new WeComCrypto(token, encodingAesKey, receiveId) : null;
    }

    /** URL 验证（GET）：企微要求回解密后的明文 echostr。 */
    @GetMapping("/callback")
    public ResponseEntity<String> verify(@RequestParam("msg_signature") String signature,
                                         @RequestParam("timestamp") String timestamp,
                                         @RequestParam("nonce") String nonce,
                                         @RequestParam("echostr") String echostr) {
        if (!enabled || crypto == null) {
            return ResponseEntity.status(404).build();
        }
        stats.inc("wecom.received");
        if (!crypto.verifySignature(signature, timestamp, nonce, echostr)) {
            stats.inc("wecom.drop_bad_signature");
            return ResponseEntity.status(401).body("signature mismatch");
        }
        try {
            String plain = crypto.decrypt(echostr);
            stats.inc("wecom.url_verification");
            log.info("[wecom] URL 验证通过");
            return ResponseEntity.ok(plain);
        } catch (Exception e) {
            stats.inc("wecom.drop_decrypt_failed");
            return ResponseEntity.status(400).body("decrypt failed");
        }
    }

    /** 消息推送（POST，XML 信封）。 */
    @PostMapping(value = "/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> callback(@RequestParam("msg_signature") String signature,
                                                 @RequestParam("timestamp") String timestamp,
                                                 @RequestParam("nonce") String nonce,
                                                 @RequestBody String rawBody) {
        if (!enabled || crypto == null) {
            return Mono.just(ResponseEntity.status(404).build());
        }
        stats.inc("wecom.received");
        String encrypt = xmlText(rawBody, "Encrypt");
        if (encrypt == null || encrypt.isBlank()) {
            stats.inc("wecom.drop_missing_encrypt");
            return Mono.just(ResponseEntity.badRequest().body(""));
        }
        if (!crypto.verifySignature(signature, timestamp, nonce, encrypt)) {
            stats.inc("wecom.drop_bad_signature");
            return Mono.just(ResponseEntity.status(401).body("signature mismatch"));
        }
        final String xml;
        try {
            xml = crypto.decrypt(encrypt);
        } catch (Exception e) {
            stats.inc("wecom.drop_decrypt_failed");
            return Mono.just(ResponseEntity.status(400).body(""));
        }
        final String msgId = xmlText(xml, "MsgId");
        final String msgType = xmlText(xml, "MsgType");
        final String fromUser = xmlText(xml, "FromUserName");
        final String text = "text".equalsIgnoreCase(msgType) ? xmlText(xml, "Content") : null;

        // 幂等：同 MsgId 重复推送 200 静默（企微停止重试），同官方模式
        if (msgId != null && !msgId.isBlank() && !recentMsgIds.add("wecom|" + msgId)) {
            stats.inc("wecom.drop_duplicate");
            return Mono.just(ResponseEntity.ok(""));
        }
        if (fromUser == null || fromUser.isBlank() || text == null || text.isBlank()) {
            stats.inc("wecom.drop_non_text");
            log.info("[wecom] 丢弃非文本消息: from={}, msgType={}", fromUser, msgType);
            return Mono.just(ResponseEntity.ok("success"));
        }
        if (!rateLimiter.allow(fromUser)) {
            stats.inc("wecom.drop_rate_limited");
            return Mono.just(ResponseEntity.status(429).body("rate limited"));
        }

        String peer = fromUser;
        String sessionId = "ch-wecom-" + peer;
        String userId = "ch:wecom:" + peer;
        messages.record("wecom", peer, "inbound", sessionId, text, "received");
        stats.inc("wecom.verified_inbound");

        if (echo) {
            String reply = "【渠道回环测试·企业微信】已收到消息并完成会话映射（不调模型）：text=" + text;
            messages.record("wecom", peer, "outbound", sessionId, reply, "echo");
            stats.inc("wecom.echo_reply");
            log.info("[wecom] echo 回复 peer={}", peer);
            return Mono.just(ResponseEntity.ok("success"));
        }

        // 真实模式：立即 200（企微 5s 超时约束），Agent 轮次异步
        final String inboundText = text;
        Mono.fromCallable(() -> runTurn(inboundText, userId, sessionId, peer))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(r -> { }, err -> log.warn("[wecom] Agent 轮次失败 peer={}: {}", peer, err.toString()));
        return Mono.just(ResponseEntity.ok("success"));
    }

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
        boolean outboundReady = notBlank(corpId) && notBlank(corpSecret);
        messages.record("wecom", peer, "outbound", sessionId, reply,
                outboundReady ? "sent" : "pending_credentials");
        stats.inc(outboundReady ? "wecom.agent_reply" : "wecom.agent_reply_pending_credentials");
        if (!outboundReady) {
            log.warn("[wecom] 回复已生成但未配置 EH_WECOM_CORP_ID/SECRET，状态=pending_credentials");
        } else {
            log.info("[wecom] 回复已生成（出站通道接入点）：peer={}", peer);
        }
        return Map.of("reply", reply);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 解析 XML 顶层元素的文本值（已禁 DTD 防 XXE；仅用于受签名保护的回调体）。 */
    private static String xmlText(String xml, String tag) {
        try {
            Document doc = DOM_FACTORY.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            if (root == null) {
                return null;
            }
            var nodes = root.getElementsByTagName(tag);
            return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
