package com.edgehotel.agent.agent;

import com.edgehotel.agent.store.TicketStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 写类演示工具：complaint_escalate（投诉升级/工单）。
 * 敏感度=高 → isSensitive()=true → 由 Approval Gate 拦截，不直接执行。
 * 演示数据全部标注"示例"；真实接入 PMS/值班表在 M2。
 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "hotel", matchIfMissing = true)
public class ComplaintEscalateTool implements Tool {

    private final TicketStore tickets;

    public ComplaintEscalateTool(TicketStore tickets) {
        this.tickets = tickets;
    }

    @Override
    public String name() {
        return "complaint_escalate";
    }

    @Override
    public String description() {
        return "将客人的投诉/严重问题升级到人工值班：生成工单记录并通知值班人员。"
                + "这是写操作，需要人工审批后才能生效。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "guest_name", Map.of("type", "string", "description", "客人姓名"),
                        "room_no", Map.of("type", "string", "description", "房号（可选）"),
                        "issue", Map.of("type", "string", "description", "投诉内容描述"),
                        "severity", Map.of("type", "string", "enum", new String[]{"low", "medium", "high"})
                ),
                "required", new String[]{"guest_name", "issue"});
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String guest = String.valueOf(args.getOrDefault("guest_name", "未知客人"));
        String issue = String.valueOf(args.getOrDefault("issue", ""));
        String severity = String.valueOf(args.getOrDefault("severity", "medium"));
        String ticket = tickets.create("complaint", ctx.sessionId(), guest,
                String.valueOf(args.getOrDefault("room_no", "-")), issue, "OPEN");
        return ToolResult.standard("已生成工单 " + ticket + "（severity=" + severity
                + "，客人 " + guest + "，问题：" + issue + "）。工单已留痕（OPEN），转人工跟进。");
    }

    @Override
    public boolean isSensitive() {
        return true;
    }
}
