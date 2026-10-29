package com.edgehotel.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 运维/治理侧访问控制（M6 安全加固）：可选 API Key 门禁（Bearer），只保护**有副作用的治理端点**。
 *
 * <p>保护面（POST）：审批放行/拒绝（会执行写工具！）、记忆写入（内容会被注入 system，可被投毒）。
 * 查询类（GET）与产品面（/api/agent/ask、渠道回调——各有独立验签）不受影响。
 *
 * <p>配置：EH_OPS_KEY 未设置 → 门禁关闭（本机联调模式，启动 WARN）；设置 → 受保护端点要求
 * {@code Authorization: Bearer <key>}，否则 401。
 */
@Component
@Order(-100)
public class OpsAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(OpsAuthFilter.class);

    /** 需要门禁的「方法 + 精确路径」集合（POST 副作用面）。 */
    private static final Set<String> PROTECTED = Set.of(
            "POST /api/approval",          // /grant 与 /deny 前缀匹配
            "POST /api/memory/add"
    );

    private final String opsKey;
    private final boolean configured;

    public OpsAuthFilter(@Value("${app.ops-key:${EH_OPS_KEY:}}") String opsKey) {
        this.opsKey = opsKey == null ? "" : opsKey.trim();
        this.configured = !this.opsKey.isEmpty();
        if (!configured) {
            log.warn("[OPS-AUTH] EH_OPS_KEY 未配置——审批放行/记忆写入等治理端点无鉴权（仅建议本机回环使用）");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!configured) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        boolean protectedPath = method.equals("POST") &&
                (path.startsWith("/api/approval/") || path.equals("/api/memory/add"));
        if (!protectedPath) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + opsKey)) {
            return chain.filter(exchange);
        }
        log.warn("[OPS-AUTH] 拒绝无凭据的治理操作: {} {}", method, path);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        byte[] body = "{\"error\":\"unauthorized: 需要 Authorization: Bearer <EH_OPS_KEY>\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
