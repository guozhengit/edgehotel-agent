package com.edgehotel.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商家示例数据（HotelOps Template）：房态/价格/订单/会员。
 * 独立部署时替换为商家自己的数据源（DB/JSON 文件）。
 */
public class HotelData implements McpServerMain.PmsDataSource {

    /** 房态表：date -> room_type -> 可订数。示例数据。 */
    private final Map<String, Map<String, Integer>> inventory = new LinkedHashMap<>();
    /** 价格表：room_type -> 价格。 */
    private final Map<String, Map<String, Integer>> prices = new LinkedHashMap<>();
    /** 订单表：order_id -> (guest, room_type, status)。 */
    private final Map<String, Map<String, String>> bookings = new LinkedHashMap<>();
    /** 会员表：member_id -> (level, preferences)。脱敏展示。 */
    private final Map<String, Map<String, String>> members = new LinkedHashMap<>();

    public HotelData() {
        // 示例房态（2026-10-30 / 10-31 / 11-01 及未知日期缺省）
        inventory.put("2026-10-30", Map.of("standard", 12, "deluxe", 8, "suite", 2));
        inventory.put("2026-10-31", Map.of("standard", 9, "deluxe", 5, "suite", 0));
        inventory.put("2026-11-01", Map.of("standard", 15, "deluxe", 10, "suite", 3));
        // 示例价格
        prices.put("standard", Map.of("price", 599, "breakfast", 0));
        prices.put("deluxe", Map.of("price", 899, "breakfast", 1));
        prices.put("suite", Map.of("price", 1599, "breakfast", 1));
        // 示例订单
        bookings.put("B20261001", Map.of("guest", "王先生", "roomType", "deluxe", "status", "已确认"));
        bookings.put("B20261002", Map.of("guest", "李女士", "roomType", "suite", "status", "已入住"));
        // 会员（脱敏：只暴露等级与偏好摘要，不含手机号/身份证等隐私）
        members.put("M-1001", Map.of("level", "铂金", "preferences", "高层无烟房 / 早餐素食 / 忌花生"));
        members.put("M-1002", Map.of("level", "金卡", "preferences", "安静朝向 / 行政房"));
    }

    /** 房态查询；未知日期返回空（供客户端 Verifier 判定静默失败，杜绝"查不到却答可订"）。 */
    public Map<String, String> roomStatus(String date, String roomType) {
        var byDate = inventory.get(date);
        if (byDate == null) {
            return Map.of("status", "UNKNOWN_DATE", "message", "该日期暂无房态数据（示例数据仅覆盖 10-30~11-01）");
        }
        Integer count = byDate.get(roomType);
        return Map.of("status", count == null ? "UNKNOWN_TYPE" : (count > 0 ? "AVAILABLE" : "SOLD_OUT"),
                "available", String.valueOf(count == null ? 0 : count));
    }

    public Map<String, String> price(String roomType) {
        var p = prices.get(roomType);
        if (p == null) {
            return Map.of("status", "UNKNOWN_TYPE", "message", "未知房型");
        }
        Object bf = p.get("breakfast");
        return Map.of("status", "OK", "roomType", roomType,
                "price", p.get("price") + "元/晚", "breakfast", (bf instanceof Integer n && n == 1) ? "含" : "不含");
    }

    public Map<String, String> booking(String orderId) {
        var b = bookings.get(orderId);
        if (b == null) {
            return Map.of("status", "NOT_FOUND", "message", "未找到订单 " + orderId + "（示例数据仅 B20261001/B20261002）");
        }
        return Map.of("status", "OK", "orderId", orderId, "guest", b.get("guest"),
                "roomType", b.get("roomType"), "orderStatus", b.get("status"));
    }

    /** 会员脱敏查询：仅返回等级与偏好摘要。 */
    public Map<String, String> member(String memberId) {
        var m = members.get(memberId);
        if (m == null) {
            return Map.of("status", "NOT_FOUND", "message", "未找到会员 " + memberId);
        }
        return Map.of("status", "OK", "memberId", memberId, "level", m.get("level"),
                "preferences", m.get("preferences"), "privacy", "已脱敏（仅等级与偏好摘要）");
    }
}
