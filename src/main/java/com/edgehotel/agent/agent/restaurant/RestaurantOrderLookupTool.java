package com.edgehotel.agent.agent.restaurant;

import com.edgehotel.agent.agent.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 餐饮工具 3/4（读）：按订单号查订座/消费单状态。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "restaurant")
public class RestaurantOrderLookupTool implements Tool {

    private final RestaurantData data;
    private final Map<String, Object> schema;

    public RestaurantOrderLookupTool(RestaurantData data) {
        this.data = data;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of("order_id",
                Map.of("type", "string", "description", "订单号（示例订单：R20261001/R20261002）")));
        params.put("required", java.util.List.of("order_id"));
        schema = params;
    }

    @Override
    public String name() {
        return "restaurant_order_lookup";
    }

    @Override
    public String description() {
        return "按订单号查询订座/消费单状态（菜品明细与金额）。未知订单如实返回 NOT_FOUND。";
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String id = RestaurantTableStatusTool.str(args, "order_id");
        Map<String, String> o = data.order(id);
        if (o == null) {
            return new ToolResult(true, "STANDARD", "status=NOT_FOUND | order_id=" + id + " | message=未找到该订单（示例订单仅 R20261001/R20261002）", null);
        }
        return ToolResult.standard("status=OK | orderId=" + id + " | guest=" + o.get("guest")
                + " | items=" + o.get("items") + " | orderStatus=" + o.get("status") + " | amount=" + o.get("amount") + "元");
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }
}
