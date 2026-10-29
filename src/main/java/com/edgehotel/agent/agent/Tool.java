package com.edgehotel.agent.agent;

import java.util.Map;

/**
 * M1 工具抽象（对齐落地方案 §4 AgentTool 接口）：
 * name/description/jsonSchema 供模型选择，run 执行，isSensitive 由 Approval Gate 拦截。
 */
public interface Tool {

    /** 工具名（模型将按此调用），如 hotel_room_status */
    String name();

    /** 给模型的自然语言描述 */
    String description();

    /** 参数 JSON Schema（OpenAI function calling 格式） */
    Map<String, Object> jsonSchema();

    /** 执行工具；args 为模型解析后的参数 */
    ToolResult run(Map<String, Object> args, ToolContext ctx);

    /** 是否敏感（写/外发/费用类 → Approval Gate 拦截） */
    default boolean isSensitive() {
        return false;
    }

    record ToolContext(String userId, String sessionId) {
    }

    /** 统一返回：ok=true 正常；status 用 STANDARD/EMPTY/ERROR 区分（供 Verifier 判定静默失败）。工厂方法公开——行业模板在外部包（如 restaurant）同样构造。 */
    record ToolResult(boolean ok, String status, String output, String error) {
        public static ToolResult standard(String output) {
            return new ToolResult(true, "STANDARD", output, null);
        }

        public static ToolResult empty() {
            return new ToolResult(true, "EMPTY", "（查询无结果）", null);
        }

        public static ToolResult error(String msg) {
            return new ToolResult(false, "ERROR", null, msg);
        }
    }
}
