package com.edgehotel.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商家示例数据源（HotelOps Template）：房态/价格/订单/会员。
 *
 * <p>数据来源：
 * <ol>
 *   <li>默认：内置示例数据（无文件依赖）；</li>
 *   <li>外置：`--data <hotel-data.json>` 加载 JSON 覆盖（换真实商家数据不动代码）。
 *       JSON 结构：{inventory:{date:{roomType:可订数}}, prices:{roomType:{price,breakfast}},
 *       bookings:{orderNo:{guest,roomType,status}}, members:{memberNo:{level,preferences}}}</li>
 * </ol>
 */
public class HotelData implements McpServerMain.PmsDataSource {

    private Map<String, Map<String, Integer>> inventory = new LinkedHashMap<>();
    private Map<String, Map<String, Integer>> prices = new LinkedHashMap<>();
    private Map<String, Map<String, String>> bookings = new LinkedHashMap<>();
    private Map<String, Map<String, String>> members = new LinkedHashMap<>();

    public HotelData() {
        loadDefaults();
    }

    /** 从外部 JSON 文件加载（失败则回退内置并报错，不静默降级）。 */
    public HotelData(Path jsonFile, McpJsonMapper mapper) {
        loadDefaults();
        try {
            String body = Files.readString(jsonFile);
            Map<String, Object> root = mapper.readValue(body, Map.class);
            loadFrom(root);
        } catch (IOException e) {
            throw new IllegalStateException("加载酒店数据文件失败: " + jsonFile + "（已回退内置示例数据）", e);
        }
    }

    // ---------- 数据加载 ----------

    @SuppressWarnings("unchecked")
    private void loadFrom(Map<String, Object> root) {
        Object inv = root.get("inventory");
        if (inv instanceof Map<?, ?> m) {
            Map<String, Map<String, Integer>> invMap = new LinkedHashMap<>();
            m.forEach((date, types) -> {
                Map<String, Integer> t = new LinkedHashMap<>();
                if (types instanceof Map<?, ?> tm) {
                    tm.forEach((k, v) -> t.put(String.valueOf(k), ((Number) v).intValue()));
                }
                invMap.put(String.valueOf(date), t);
            });
            inventory = invMap;
        }
        Object pr = root.get("prices");
        if (pr instanceof Map<?, ?> m) {
            Map<String, Map<String, Integer>> prMap = new LinkedHashMap<>();
            m.forEach((type, fields) -> {
                Map<String, Integer> f = new LinkedHashMap<>();
                if (fields instanceof Map<?, ?> fm) {
                    fm.forEach((k, v) -> f.put(String.valueOf(k), ((Number) v).intValue()));
                }
                prMap.put(String.valueOf(type), f);
            });
            prices = prMap;
        }
        Object bk = root.get("bookings");
        if (bk instanceof Map<?, ?> m) {
            Map<String, Map<String, String>> bkMap = new LinkedHashMap<>();
            m.forEach((no, fields) -> {
                Map<String, String> f = new LinkedHashMap<>();
                if (fields instanceof Map<?, ?> fm) {
                    fm.forEach((k, v) -> f.put(String.valueOf(k), String.valueOf(v)));
                }
                bkMap.put(String.valueOf(no), f);
            });
            bookings = bkMap;
        }
        Object mb = root.get("members");
        if (mb instanceof Map<?, ?> m) {
            Map<String, Map<String, String>> mbMap = new LinkedHashMap<>();
            m.forEach((no, fields) -> {
                Map<String, String> f = new LinkedHashMap<>();
                if (fields instanceof Map<?, ?> fm) {
                    fm.forEach((k, v) -> f.put(String.valueOf(k), String.valueOf(v)));
                }
                mbMap.put(String.valueOf(no), f);
            });
            members = mbMap;
        }
    }

    private void loadDefaults() {
        inventory = new LinkedHashMap<>();
        inventory.put("2026-10-30", Map.of("standard", 12, "deluxe", 8, "suite", 2));
        inventory.put("2026-10-31", Map.of("standard", 9, "deluxe", 5, "suite", 0));
        inventory.put("2026-11-01", Map.of("standard", 15, "deluxe", 10, "suite", 3));

        prices = new LinkedHashMap<>();
        prices.put("standard", Map.of("price", 599, "breakfast", 0));
        prices.put("deluxe", Map.of("price", 899, "breakfast", 1));
        prices.put("suite", Map.of("price", 1599, "breakfast", 1));

        bookings = new LinkedHashMap<>();
        bookings.put("B20261001", Map.of("guest", "王先生", "roomType", "deluxe", "status", "已确认"));
        bookings.put("B20261002", Map.of("guest", "李女士", "roomType", "suite", "status", "已入住"));

        members = new LinkedHashMap<>();
        members.put("M-1001", Map.of("level", "铂金", "preferences", "高层无烟房 / 早餐素食 / 忌花生"));
        members.put("M-1002", Map.of("level", "金卡", "preferences", "安静朝向 / 行政房"));
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, String> roomStatus(String date, String roomType) {
        var byDate = inventory.get(date);
        if (byDate == null) {
            return Map.of("status", "UNKNOWN_DATE", "message", "该日期暂无房态数据（示例数据仅覆盖 10-30~11-01）");
        }
        Integer count = byDate.get(roomType);
        return Map.of("status", count == null ? "UNKNOWN_TYPE" : (count > 0 ? "AVAILABLE" : "SOLD_OUT"),
                "available", String.valueOf(count == null ? 0 : count));
    }

    @Override
    public Map<String, String> price(String roomType) {
        var p = prices.get(roomType);
        if (p == null) {
            return Map.of("status", "UNKNOWN_TYPE", "message", "未知房型");
        }
        Object bf = p.get("breakfast");
        return Map.of("status", "OK", "roomType", roomType,
                "price", p.get("price") + "元/晚", "breakfast", (bf instanceof Integer n && n == 1) ? "含" : "不含");
    }

    @Override
    public Map<String, String> booking(String orderId) {
        var b = bookings.get(orderId);
        if (b == null) {
            return Map.of("status", "NOT_FOUND", "message", "未找到订单 " + orderId + "（示例数据仅 B20261001/B20261002）");
        }
        return Map.of("status", "OK", "orderId", orderId, "guest", b.get("guest"),
                "roomType", b.get("roomType"), "orderStatus", b.get("status"));
    }

    @Override
    public Map<String, String> member(String memberId) {
        var m = members.get(memberId);
        if (m == null) {
            return Map.of("status", "NOT_FOUND", "message", "未找到会员 " + memberId);
        }
        return Map.of("status", "OK", "memberId", memberId, "level", m.get("level"),
                "preferences", m.get("preferences"), "privacy", "已脱敏（仅等级与偏好摘要）");
    }
}
