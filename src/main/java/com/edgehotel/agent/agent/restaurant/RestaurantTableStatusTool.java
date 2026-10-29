package com.edgehotel.agent.agent.restaurant;

import com.edgehotel.agent.agent.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 餐饮工具 1/4（读）：桌态查询。未知日期返回 EMPTY（防"查不到却答可订"静默失败）。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "restaurant")
public class RestaurantTableStatusTool implements Tool {

    private final RestaurantData data;
    private final Map<String, Object> schema;

    public RestaurantTableStatusTool(RestaurantData data) {
        this.data = data;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("date", Map.of("type", "string", "description", "日期 YYYY-MM-DD（示例数据仅 2026-10-30）"));
        props.put("slot", Map.of("type", "string", "description", "到店时段 HH:mm，可选（18:00/19:00/20:00）；不传返回全天汇总"));
        props.put("table_type", Map.of("type", "string", "description", "桌型，可选：deuce 双人桌 / four 四人桌 / private 包房"));
        params.put("properties", props);
        params.put("required", java.util.List.of("date"));
        schema = params;
    }

    @Override
    public String name() {
        return "restaurant_table_status";
    }

    @Override
    public String description() {
        return "查询餐饮门店指定日期/时段的可订桌态。未知日期返回 UNKNOWN_DATE，务必如实转述，不得编造。";
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String date = str(args, "date");
        String slot = str(args, "slot");
        String type = str(args, "table_type");
        Map<String, Integer> tables = data.tables(date, slot);
        if (tables == null) {
            // 对齐酒店模板：未知日期是"如实领域答案"（STANDARD），明示数据覆盖范围供模型转述
            return ToolResult.standard("status=UNKNOWN_DATE | message=该日期暂无桌态数据（示例数据仅覆盖 2026-10-30），请如实告知顾客并以门店渠道为准");
        }
        StringBuilder sb = new StringBuilder("status=AVAILABLE | date=" + date);
        if (!slot.isBlank()) {
            sb.append(" | slot=").append(slot);
        }
        if (type.isBlank()) {
            tables.forEach((t, n) -> sb.append(" | ").append(t).append("=").append(n));
        } else {
            Integer n = tables.get(type);
            sb.append(" | ").append(type).append("=").append(n == null ? 0 : n);
        }
        return ToolResult.standard(sb.toString());
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }

    static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
