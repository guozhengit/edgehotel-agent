package com.edgehotel.agent.agent.restaurant;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 餐饮模板示例数据（结构性对齐 HotelData）：桌态 / 菜单 / 订单 / 会员（脱敏）。
 * 仅证明"换一套行业数据 + 工具，Runtime 全链路组件零改动可复用"，不是生产实现。
 */
@Component
public class RestaurantData {

    /** 桌态：date → slot(HH:mm) → table_type → 可订数 */
    private final Map<String, Map<String, Map<String, Integer>>> tables = new LinkedHashMap<>();
    /** 菜单：dish_name → {price, category} */
    private final Map<String, Map<String, Object>> menu = new LinkedHashMap<>();
    /** 订单：order_id → 明细 */
    private final Map<String, Map<String, String>> orders = new LinkedHashMap<>();
    /** 会员：member_id → {level, preferences}（脱敏：无手机号/证件） */
    private final Map<String, Map<String, String>> members = new LinkedHashMap<>();

    public RestaurantData() {
        Map<String, Map<String, Integer>> day1 = new LinkedHashMap<>();
        day1.put("18:00", new LinkedHashMap<>(Map.of("deuce", 4, "four", 2, "private", 1)));
        day1.put("19:00", new LinkedHashMap<>(Map.of("deuce", 1, "four", 0, "private", 1)));
        day1.put("20:00", new LinkedHashMap<>(Map.of("deuce", 6, "four", 3, "private", 0)));
        tables.put("2026-10-30", day1);

        menu.put("金汤酸菜鱼", Map.of("price", 128, "category", "招牌"));
        menu.put("黑椒和牛粒", Map.of("price", 168, "category", "招牌"));
        menu.put("蒜蓉粉丝虾", Map.of("price", 88, "category", "热菜"));
        menu.put("杨枝甘露", Map.of("price", 32, "category", "甜品"));

        orders.put("R20261001", Map.of("guest", "陈女士", "items", "金汤酸菜鱼x1 杨枝甘露x2", "status", "已预订", "amount", "192"));
        orders.put("R20261002", Map.of("guest", "刘先生", "items", "黑椒和牛粒x1 蒜蓉粉丝虾x1", "status", "已完成", "amount", "256"));

        members.put("M-2001", Map.of("level", "黑金卡", "preferences", "靠窗位 / 忌香菜 / 生日月"));
        members.put("M-2002", Map.of("level", "银卡", "preferences", "辣度中辣 / 常点酸菜鱼"));
    }

    /** 桌态查询；未知日期返回 null 由工具决定如实语义。 */
    public Map<String, Integer> tables(String date, String slot) {
        var byDate = tables.get(date);
        if (byDate == null) {
            return null;
        }
        return slot == null || slot.isBlank() ? mergeSlots(byDate) : byDate.get(slot);
    }

    private static Map<String, Integer> mergeSlots(Map<String, Map<String, Integer>> byDate) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        byDate.values().forEach(m -> m.forEach((type, n) -> merged.merge(type, n, Integer::sum)));
        return merged;
    }

    public Map<String, Object> dish(String dishName) {
        return menu.get(dishName);
    }

    public Map<String, String> order(String orderId) {
        return orders.get(orderId);
    }

    public Map<String, String> member(String memberId) {
        return members.get(memberId);
    }
}
