package com.edgehotel.agent.agent.mcp;

import com.edgehotel.agent.agent.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * MCP 数据源模式（app.mcp.enabled=true 时生效）：主应用作为 MCP 客户端，
 * 把 edgehotel-mcp-server 声明的工具桥接进本地 ToolRegistry（同名覆盖内联实现）。
 *
 * <p>接入故事：内联工具（默认，零依赖）→ MCP 桥接（EH_MCP_ENABLED=true，数据源在 MCP 侧可切
 * demo/外置JSON/真实PMS aurora）——Agent 语义层不变，只换数据源。
 *
 * <p>排除名单：写类工具（投诉/话术）不桥接——审批语义归运行时（Approval Gate），
 * 且持久化在本地 SQLite（tickets 表），MCP 侧的 mock 实现不覆盖运行时实现。
 */
@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(McpToolsConfig.class);

    @Bean
    public McpClientService mcpClientService(@Value("${app.mcp.base-url:http://localhost:8601/mcp}") String endpoint) {
        return new McpClientService(endpoint);
    }

    /** 启动即拉取 MCP 工具清单并覆盖注册（在 ToolRegistry 构造收集完内联工具之后执行）。 */
    @Bean
    public ApplicationRunner mcpToolRegistrar(McpClientService client, ToolRegistry registry,
                                              @Value("${app.mcp.sensitive-tools:}") String sensitiveCsv,
                                              @Value("${app.mcp.exclude-tools:complaint_escalate,marketing_copy_generate}") String excludeCsv) {
        return (ApplicationArguments args) -> {
            List<String> sensitive = Arrays.asList(sensitiveCsv.split(","));
            List<String> exclude = Arrays.asList(excludeCsv.split(","));
            var tools = client.listTools();
            int registered = 0;
            for (var t : tools) {
                String name = String.valueOf(t.get("name"));
                if (exclude.contains(name)) {
                    log.info("MCP 工具 {} 按排除名单跳过（审批语义归运行时）", name);
                    continue;
                }
                registry.register(new McpBridgeTool(client, t, sensitive));
                registered++;
                log.info("MCP 桥接注册: {}（覆盖内联同名工具）", name);
            }
            log.info("MCP 数据源模式生效：桥接 {} 个工具（server 共 {} 个）", registered, tools.size());
        };
    }
}
