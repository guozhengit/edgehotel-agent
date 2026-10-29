package com.edgehotel.agent.agent.restaurant;

import com.edgehotel.agent.agent.Tool;
import com.edgehotel.agent.store.TicketStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 餐饮工具（写，敏感）：客诉/人工升级工单。审批语义归运行时 Approval Gate。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "restaurant")
public class RestaurantComplaintEscalateTool implements Tool {

    private final Map<String, Object> schema;
    private final TicketStore tickets;

    public RestaurantComplaintEscalateTool(TicketStore tickets) {
        this.tickets = tickets;

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("guest_name", Map.of("type", "string", "description", "顾客姓名"));
        props.put("issue", Map.of("type", "string", "description", "投诉问题简述"));
        props.put("table_no", Map.of("type", "string", "description", "桌号，可选"));
        params.put("properties", props);
        params.put("required", java.util.List.of("guest_name", "issue"));
        schema = params;
    }

    @Override
    public String name() {
        return "restaurant_complaint_escalate";
    }

    @Override
    public String description() {
        return "创建餐饮客诉/人工升级工单（敏感写操作，调用方须走审批门；返回工单号供留痕）。";
    }

    @Override
    public boolean isSensitive() {
        return true;
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String guest = RestaurantTableStatusTool.str(args, "guest_name");
        String issue = RestaurantTableStatusTool.str(args, "issue");
        String table = RestaurantTableStatusTool.str(args, "table_no");
        String ticket = tickets.create("complaint", ctx.sessionId(), guest,
                table.isBlank() ? "-" : table, issue, "OPEN");
        return ToolResult.standard("status=ESCALATED | ticket=" + ticket + " | guest=" + guest
                + " | table_no=" + (table.isBlank() ? "-" : table) + " | issue=" + issue
                + " | message=工单已留痕（OPEN），转门店值班经理跟进");
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }
}
