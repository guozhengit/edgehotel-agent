package com.edgehotel.agent.agent.restaurant;

import com.edgehotel.agent.agent.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 餐饮工具 2/4（读）：菜品价格查询。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "restaurant")
public class RestaurantMenuPriceTool implements Tool {

    private final RestaurantData data;
    private final Map<String, Object> schema;

    public RestaurantMenuPriceTool(RestaurantData data) {
        this.data = data;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of("dish_name",
                Map.of("type", "string", "description", "菜品名称，如 金汤酸菜鱼")));
        params.put("required", java.util.List.of("dish_name"));
        schema = params;
    }

    @Override
    public String name() {
        return "restaurant_menu_price";
    }

    @Override
    public String description() {
        return "查询菜品价格与分类。未知菜品如实返回（不编造价格）。";
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String dish = RestaurantTableStatusTool.str(args, "dish_name");
        Map<String, Object> d = data.dish(dish);
        if (d == null) {
            return ToolResult.empty();
        }
        return ToolResult.standard("status=OK | dish=" + dish + " | price=" + d.get("price") + "元 | category=" + d.get("category"));
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }
}
