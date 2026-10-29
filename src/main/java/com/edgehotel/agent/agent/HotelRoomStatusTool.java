package com.edgehotel.agent.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工具 1/3：查房态。参数：date（YYYY-MM-DD）。 */
@Component
public class HotelRoomStatusTool implements Tool {

    private final Map<String, Object> schema;

    public HotelRoomStatusTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("date", Map.of("type", "string", "description", "日期 YYYY-MM-DD，默认今天"));
        params.put("properties", props);
        params.put("required", java.util.List.of());
        schema = params;
    }

    @Override
    public String name() {
        return "hotel_room_status";
    }

    @Override
    public String description() {
        return "查询极光酒店指定日期的房态与可订房型（standard/deluxe/suite）及起价。参数 date 为 YYYY-MM-DD。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String date = String.valueOf(args.getOrDefault("date", HotelData.today()));
        if (!HotelData.isKnownDate(date)) {
            // 查不到数据但不报错 → EMPTY（静默失败信号，由 Verifier 标记人工核对）
            return ToolResult.empty();
        }
        return ToolResult.standard(HotelData.roomStatus(date));
    }
}
