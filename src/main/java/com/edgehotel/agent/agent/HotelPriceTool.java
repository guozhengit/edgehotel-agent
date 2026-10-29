package com.edgehotel.agent.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具 2/3：查房价。参数：room_type（standard/deluxe/suite）、date。 */
@Component
public class HotelPriceTool implements Tool {

    private final Map<String, Object> schema;

    public HotelPriceTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("room_type", Map.of("type", "string",
                "enum", List.of("standard", "deluxe", "suite"),
                "description", "房型"));
        props.put("date", Map.of("type", "string", "description", "日期 YYYY-MM-DD，默认今天"));
        params.put("properties", props);
        params.put("required", java.util.List.of("room_type"));
        schema = params;
    }

    @Override
    public String name() {
        return "hotel_price_quote";
    }

    @Override
    public String description() {
        return "查询极光酒店指定房型与日期的价格、含早情况与官方直订会员专属价。参数 room_type 必填。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String roomType = String.valueOf(args.getOrDefault("room_type", "standard"));
        String date = String.valueOf(args.getOrDefault("date", HotelData.today()));
        if (!HotelData.isKnownDate(date)) {
            return ToolResult.empty();
        }
        return ToolResult.standard(HotelData.price(date, roomType));
    }
}
