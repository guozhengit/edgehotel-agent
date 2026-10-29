package com.edgehotel.agent.agent.mcp;

import com.edgehotel.agent.agent.Tool;

import java.util.List;
import java.util.Map;

/**
 * MCP 桥接工具：把 edgehotel-mcp-server 的一个远端工具适配成本地 Tool SPI。
 * name/description/inputSchema 来自 MCP tools/list（数据源模式的 schema 真源在 MCP server 侧，
 * 主应用不再各自维护一份）。执行 → tools/call；isError=true 映射 ToolResult.error（走 Verifier 失败采集）。
 *
 * <p>敏感判定：MCP 协议未定义 annotations 消费语义前，按配置名单（app.mcp.sensitive-tools）判定——
 * 实际上写类工具（投诉/话术）不从 MCP 注册（审批语义归运行时，见 McpToolsConfig 排除名单），此为双保险。
 */
public class McpBridgeTool implements Tool {

    private final McpClientService client;
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final boolean sensitive;

    public McpBridgeTool(McpClientService client, Map<String, Object> toolMeta, List<String> sensitiveNames) {
        this.client = client;
        this.name = String.valueOf(toolMeta.get("name"));
        this.description = String.valueOf(toolMeta.get("description"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) toolMeta.get("inputSchema");
        this.inputSchema = schema == null ? Map.of("type", "object") : schema;
        this.sensitive = sensitiveNames.contains(this.name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return inputSchema;
    }

    @Override
    public boolean isSensitive() {
        return sensitive;
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        Map<String, Object> r = client.callTool(name, args);
        String text = String.valueOf(r.get("text"));
        if (Boolean.TRUE.equals(r.get("isError"))) {
            return ToolResult.error(text);
        }
        return ToolResult.standard(text);
    }
}
