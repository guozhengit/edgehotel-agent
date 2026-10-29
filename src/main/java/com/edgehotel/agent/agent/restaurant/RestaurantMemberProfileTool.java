package com.edgehotel.agent.agent.restaurant;

import com.edgehotel.agent.agent.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 餐饮工具 4/4（读，脱敏）：会员等级与偏好摘要，不含手机号/证件等隐私字段。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "restaurant")
public class RestaurantMemberProfileTool implements Tool {

    private final RestaurantData data;
    private final Map<String, Object> schema;

    public RestaurantMemberProfileTool(RestaurantData data) {
        this.data = data;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of("member_id",
                Map.of("type", "string", "description", "会员号（示例会员：M-2001/M-2002）")));
        params.put("required", java.util.List.of("member_id"));
        schema = params;
    }

    @Override
    public String name() {
        return "restaurant_member_profile";
    }

    @Override
    public String description() {
        return "查询会员等级与偏好摘要（脱敏：不含手机号/证件等隐私字段）。";
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String id = RestaurantTableStatusTool.str(args, "member_id");
        Map<String, String> m = data.member(id);
        if (m == null) {
            return new ToolResult(true, "STANDARD", "status=NOT_FOUND | memberId=" + id, null);
        }
        return ToolResult.standard("status=OK | memberId=" + id + " | level=" + m.get("level")
                + " | preferences=" + m.get("preferences") + " | privacy=已脱敏（仅等级与偏好摘要）");
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }
}
