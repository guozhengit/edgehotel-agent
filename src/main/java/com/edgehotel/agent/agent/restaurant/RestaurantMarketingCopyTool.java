package com.edgehotel.agent.agent.restaurant;

import com.edgehotel.agent.agent.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 餐饮工具（写，敏感）：私域营销/回访话术草稿。仅生成不发送，审批语义归运行时。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "restaurant")
public class RestaurantMarketingCopyTool implements Tool {

    private final Map<String, Object> schema;

    public RestaurantMarketingCopyTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scenario", Map.of("type", "string", "description", "场景：welcome 到店欢迎 / winback 流失回访 / promotion 会员活动"));
        props.put("guest_name", Map.of("type", "string", "description", "顾客姓名"));
        params.put("properties", props);
        params.put("required", java.util.List.of("scenario", "guest_name"));
        schema = params;
    }

    @Override
    public String name() {
        return "restaurant_marketing_copy";
    }

    @Override
    public String description() {
        return "生成餐饮私域营销/回访话术草稿（敏感操作，调用方须走审批门；仅生成草稿不发送）。";
    }

    @Override
    public boolean isSensitive() {
        return true;
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String scenario = RestaurantTableStatusTool.str(args, "scenario");
        String guest = RestaurantTableStatusTool.str(args, "guest_name");
        String draft = switch (scenario) {
            case "welcome" -> guest + "您好，欢迎光临极味轩！已为您留好座位，今日招牌金汤酸菜鱼值得一试，祝您用餐愉快。";
            case "winback" -> guest + "您好，好久不见！极味轩本月上新了黑椒和牛粒，凭本条消息到店赠杨枝甘露一份，期待您的光临。";
            case "promotion" -> guest + "您好，极味轩会员日来了！本周五前到店消费满 200 减 40，登录小程序即可领取，期待您的光临。";
            default -> "话术草稿（未识别的场景：" + scenario + "）。";
        };
        return ToolResult.standard("status=DRAFT | scenario=" + scenario + " | draft=" + draft + " | message=仅生成草稿，发送前须审批");
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }
}
