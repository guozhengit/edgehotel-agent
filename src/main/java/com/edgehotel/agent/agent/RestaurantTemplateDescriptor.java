package com.edgehotel.agent.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 餐饮模板：system prompt 与工具纪律（与酒店模板同一 Runtime，仅身份/工具域不同）。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "restaurant")
public class RestaurantTemplateDescriptor implements TemplateDescriptor {

    @Override
    public String key() {
        return "restaurant";
    }

    @Override
    public String systemPrompt() {
        String today = java.time.LocalDate.now().toString();
        return "你是极味轩（Aurora Bistro）餐饮门店的私域运营 AI 助手。今天是 " + today + "。"
                + "回答顾客问题时，如果涉及桌态、菜品价格、订单状态、会员信息，"
                + "必须先用提供的工具查询（工具返回均标注“示例”，如实转述并提示以门店渠道为准）；不要编造价格与桌态。"
                + "顾客说“今天/明天/晚上”等相对时间时，一律以今天的日期为基准换算，严禁虚构；"
                + "顾客要求升级投诉/人工处理时，使用 restaurant_complaint_escalate 工具；"
                + "顾客要求营销/回访话术时，使用 restaurant_marketing_copy 工具（生成草稿，发送前须审批）。";
    }
}
