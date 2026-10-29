package com.edgehotel.agent.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端（Streamable HTTP）：主应用作为 MCP 客户端消费 edgehotel-mcp-server 的工具。
 *
 * <p>会话：initialize → Mcp-Session-Id → notifications/initialized；会话失效（404）自动重初始化一次再重试。
 * 仅封装本工程用到的三个方法（initialize / tools/list / tools/call），不做通用 SDK——
 * 协议面足够小，直连 HTTP 比 引入完整 MCP 客户端 SDK 更薄（与 mcp-server 的官方 SDK 分工：服务端厚、客户端薄）。
 */
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final String endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String sessionId;
    private int nextId = 1;

    public McpClientService(@Value("${app.mcp.base-url:http://localhost:8601/mcp}") String endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        init();
    }

    /** initialize 握手并持有会话。 */
    private synchronized void init() {
        try {
            Map<String, Object> resp = rpc("initialize", Map.of(
                    "protocolVersion", "2025-06-18",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "edgehotel-agent", "version", "0.1.0")), null);
            var headers = lastHeaders;
            String sid = null;
            if (headers != null && headers.get("mcp-session-id") != null && !headers.get("mcp-session-id").isEmpty()) {
                sid = headers.get("mcp-session-id").get(0);
            }
            if (sid == null || sid.isBlank()) {
                throw new IllegalStateException("MCP server 未返回 Mcp-Session-Id");
            }
            sessionId = sid;
            notify("notifications/initialized");
            log.info("MCP 会话就绪: {} ({})", endpoint,
                    ((Map<?, ?>) resp.get("serverInfo")).get("name"));
        } catch (Exception e) {
            throw new IllegalStateException("MCP 初始化失败 @ " + endpoint + ": " + e.getMessage(), e);
        }
    }

    /** 工具清单：[{name, description, inputSchema}]。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools() {
        try {
            Map<String, Object> resp = rpc("tools/list", Map.of(), sessionId);
            return (List<Map<String, Object>>) resp.get("tools");
        } catch (McpSessionExpiredException e) {
            return listTools(); // 重初始化后重试一次
        } catch (Exception e) {
            throw new IllegalStateException("MCP tools/list 失败: " + e.getMessage(), e);
        }
    }

    /** 工具调用：返回 {text, isError}。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(String name, Map<String, Object> args) {
        try {
            Map<String, Object> resp = rpc("tools/call", Map.of("name", name, "arguments", args == null ? Map.of() : args), sessionId);
            Object content = resp.get("content");
            String text = "";
            if (content instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> c) {
                text = String.valueOf(c.get("text"));
            }
            return Map.of("text", text, "isError", Boolean.TRUE.equals(resp.get("isError")));
        } catch (McpSessionExpiredException e) {
            return callTool(name, args); // 重初始化后重试一次
        } catch (Exception e) {
            throw new IllegalStateException("MCP tools/call(" + name + ") 失败: " + e.getMessage(), e);
        }
    }

    // ---------- JSON-RPC ----------

    private Map<String, List<String>> lastHeaders;

    private synchronized Map<String, Object> rpc(String method, Object params, String sid) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", nextId++);
            body.put("method", method);
            if (params != null) {
                body.put("params", params);
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (sid != null) {
                rb.header("Mcp-Session-Id", sid);
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            lastHeaders = resp.headers().map();
            if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                // 会话失效（server 重启等）：重初始化后由调用方重试
                init();
                throw new McpSessionExpiredException();
            }
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            String payload = resp.body();
            if (payload.isBlank()) {
                return Map.of(); // notification 无响应体
            }
            if (payload.startsWith("data:") || payload.contains("\ndata:") || payload.startsWith("event:")) {
                for (String line : payload.split("\n")) {
                    if (line.startsWith("data:")) {
                        payload = line.substring(5).trim();
                        break;
                    }
                }
            }
            Map<String, Object> json = mapper.readValue(payload, Map.class);
            if (json.get("error") != null) {
                throw new IllegalStateException("MCP error: " + json.get("error"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) json.get("result");
            return result == null ? Map.of() : result;
        } catch (McpSessionExpiredException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private synchronized void notify(String method) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Mcp-Session-Id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("jsonrpc", "2.0", "method", method, "params", Map.of()))));
            http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("MCP notification({}) 失败（不阻断）: {}", method, e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        // HTTP 会话由 server 端 TTL/生命周期管理；客户端无长连接可关。
    }

    private static final class McpSessionExpiredException extends RuntimeException {
    }
}
