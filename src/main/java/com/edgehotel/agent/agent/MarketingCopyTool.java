package com.edgehotel.agent.agent;

import com.edgehotel.agent.store.TicketStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类工具：marketing_copy_generate（私域话术草稿生成）。
 * 敏感度=中 → isSensitive()=true → 由 Approval Gate 拦截（发送前须人工审批）。
 * 输出为话术草稿（示例模板 + 客人上下文），M2 接消息通道后走审批后发送。
 */
@Component
@ConditionalOnProperty(name = "app.template", havingValue = "hotel", matchIfMissing = true)
public class MarketingCopyTool implements Tool {

    private final Map<String, Object> schema;
    private final TicketStore tickets;

    public MarketingCopyTool(TicketStore tickets) {
        this.tickets = tickets;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scenario", Map.of("type", "string", "enum",
                List.of("welcome", "checkout_followup", "promotion", "complaint_soothe", "reminder"),
                "description", "话术场景：welcome 入住欢迎 / checkout_followup 离店关怀 / promotion 活动推广 / complaint_soothe 投诉安抚 / reminder 到店提醒"));
        props.put("guest_name", Map.of("type", "string", "description", "客人称呼（可选）"));
        props.put("room_type", Map.of("type", "string", "description", "房型（可选）"));
        props.put("tone", Map.of("type", "string", "enum", List.of("formal", "friendly", "premium"),
                "description", "语气：正式/友好/尊贵"));
        params.put("properties", props);
        params.put("required", java.util.List.of("scenario"));
        schema = params;
    }

    @Override
    public String name() {
        return "marketing_copy_generate";
    }

    @Override
    public String description() {
        return "生成酒店私域运营话术草稿（欢迎语/离店关怀/活动推广/投诉安抚/到店提醒）。"
                + "这是写操作（对外发送类），生成后需人工审批才能发送。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return schema;
    }

    @Override
    public ToolResult run(Map<String, Object> args, ToolContext ctx) {
        String scenario = String.valueOf(args.getOrDefault("scenario", "welcome"));
        String guest = String.valueOf(args.getOrDefault("guest_name", "贵宾"));
        String room = String.valueOf(args.getOrDefault("room_type", "标准间"));
        String tone = String.valueOf(args.getOrDefault("tone", "friendly"));

        String copy = switch (scenario) {
            case "welcome" -> switch (tone) {
                case "premium" -> "尊贵的" + guest + "，欢迎入住极光酒店。您的" + room + "已精心布置，管家将随时为您服务。愿您在城心的这一隅，尽享从容。";
                case "formal" -> guest + "您好！欢迎下榻极光酒店。您的" + room + "已准备就绪，如需帮助请拨0。祝您入住愉快！";
                default -> guest + "欢迎回家～您的" + room + "已经准备好啦，有任何需要随时找我们，祝您住得开心！";
            };
            case "checkout_followup" -> "亲爱的" + guest + "，感谢您选择极光酒店！期待您的下次光临，官方直订可享会员价与免费延迟退房哦～";
            case "promotion" -> "【会员专享】" + guest + "，本月官方直订豪华房立减 ¥100 并赠双早，仅限 3 天，点击小程序即刻预订！";
            case "complaint_soothe" -> guest + "您好，非常抱歉给您带来不便。我们已记录您的问题并安排专人跟进，稍后将有同事与您确认处理方案，再次致歉。";
            default -> guest + "温馨提醒：您预订的极光酒店即将入住，到店路径与停车指引已发至您手机，期待与您相见！";
        };
        return ToolResult.standard("【话术草稿·示例】场景=" + scenario + " tone=" + tone + " →\n" + copy
                + "\n（注：示例模板，发送前须经人工审批；M2 对接消息通道后自动发送）");
    }

    @Override
    public boolean isSensitive() {
        return true;
    }
}
