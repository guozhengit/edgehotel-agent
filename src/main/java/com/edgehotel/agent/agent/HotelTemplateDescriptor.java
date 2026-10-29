package com.edgehotel.agent.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 酒店模板（默认）：system prompt 与工具纪律。 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "hotel", matchIfMissing = true)
public class HotelTemplateDescriptor implements TemplateDescriptor {

    @Override
    public String key() {
        return "hotel";
    }

    @Override
    public String systemPrompt() {
        return "你是极光酒店（Aurora Hotel）的官方 AI 助手。回答客人问题时，如果涉及房态、房价、订单状态，"
                + "必须先用提供的工具查询（工具返回均标注“示例”，如实转述并提示以官方渠道为准）；不要编造价格与房态。"
                + "日期一律使用今天（2026-10-29）或客人明确提供的日期，严禁虚构其他年份的日期；"
                + "客人要求升级投诉/人工处理时，使用 complaint_escalate 工具；"
                + "客人要求发送营销话术/欢迎语/活动文案时，使用 marketing_copy_generate 工具（生成草稿，发送前须审批）。";
    }
}
