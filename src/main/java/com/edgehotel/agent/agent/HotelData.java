package com.edgehotel.agent.agent;

import java.time.LocalDate;

/**
 * 极光酒店示例数据层（M1-1 demo 口径；M2 经 MCP Bridge 对接真实 PMS，只读）。
 * 所有返回均含"示例"标注，防止模型把 demo 数据当真实承诺。
 */
final class HotelData {

    private HotelData() {
    }

    /** 已知房态/价格的日期集（示例数据只覆盖这些天；其余日期查不到 → EMPTY，供 Verifier 判静默失败） */
    static final java.util.Set<String> KNOWN_DATES = java.util.Set.of("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02");

    static boolean isKnownDate(String date) {
        return date != null && KNOWN_DATES.contains(date);
    }

    static String roomStatus(String date) {
        return switch (date) {
            case "2026-10-30", "2026-10-31" ->
                    "2026-10-30/31 房态（示例）：standard 可订 ¥599 起；deluxe 可订 ¥899 起；suite 仅剩 2 间 ¥1599 起。";
            case "2026-11-01", "2026-11-02" ->
                    "2026-11-01/02 房态（示例）：展会期间满房，standard/deluxe 不可订；suite 剩 1 间 ¥1899；建议官方小程序预约候补。";
            default ->
                    "（示例数据未覆盖该日期，无法确认房态）";
        };
    }

    static String price(String date, String roomType) {
        if (!isKnownDate(date)) {
            return "（示例数据未覆盖该日期，无法确认房价）";
        }
        String base = switch (roomType) {
            case "deluxe" -> "豪华房 ¥899/晚，含双早；官方直订会员专属价 ¥809";
            case "suite" -> "套房 ¥1599/晚，含双早；官方直订会员专属价 ¥1439";
            default -> "标准间 ¥599/晚，含单早；官方直订会员专属价 ¥539";
        };
        return date + " 房价（示例）：" + base + "。";
    }

    static String booking(String orderId, String phone) {
        if (orderId.isBlank() && phone.isBlank()) {
            return "（查询无结果：请提供订单号或手机号后4位）";
        }
        String key = orderId.isBlank() ? phone : orderId;
        return "订单查询（示例）：订单 B" + key + " 状态=已确认，入住极光酒店，含早，12:00 退房；如需变更请走官方小程序。";
    }

    static String today() {
        return LocalDate.now().toString();
    }
}
