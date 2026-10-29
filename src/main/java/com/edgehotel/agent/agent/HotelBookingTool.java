package com.edgehotel.agent.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工具 3/3：查订单状态。参数：order_id 或 phone（手机号后4位）。 */
@Component
public class HotelBookingTool implements Tool {

    private final Map<String, Object> schema;

    public HotelBookingTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("order_id", Map.of("type", "string", "description", "订单号，如 B20261001"));
        props.put("phone", Map.of("type", "string", "description", "手机号后4位，order_id 缺失时使用"));
        params.put("properties", props);
        schema = params;
    }

    @Override
    public String name() {
        return "hotel_booking_lookup";
    }

    @Override
    public String description() {
        return "查询极光酒店订单状态（已确认/变更/取消）。提供 order_id（订单号）或 phone（手机号后4位，如 6688）；客人只报手机尾号时可直接传 phone。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String orderId = String.valueOf(args.getOrDefault("order_id", "")).trim();
        String phone = String.valueOf(args.getOrDefault("phone", "")).trim();
        if (orderId.isBlank() && phone.isBlank()) {
            return ToolResult.empty();
        }
        return ToolResult.standard(HotelData.booking(orderId, phone));
    }
}
