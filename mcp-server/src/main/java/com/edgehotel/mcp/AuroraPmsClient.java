package com.edgehotel.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Bridge：真实 PMS 数据源客户端（对接 aurora 酒店微服务 REST API）。
 *
 * <p>端点（服务直连；网关 8100 时 baseUrl 传网关地址亦可）：
 * <ul>
 *   <li>房态：GET /api/inventory/available/{skuId}    → aurora-inventory-service :8103</li>
 *   <li>价格：GET /api/product/sku/{id}/price         → aurora-product-service   :8102</li>
 *   <li>订单：GET /api/order/no/{orderNo}             → aurora-order-service     :8104</li>
 *   <li>会员：GET /api/member/no/{memberNo}           → aurora-member-service    :8108</li>
 * </ul>
 *
 * <p>脱敏红线：订单不返回 guestMobile；会员不返回 idCard/phone/wxOpenid（对齐落地方案数据合规）。
 * 服务不可达/异常 → 返回 PMS_UNAVAILABLE（如实说查不到，杜绝编造，对齐静默失败防线）。
 */
public class AuroraPmsClient implements McpServerMain.PmsDataSource {

    private final String baseUrl;
    private final HttpClient http;
    private final McpJsonMapper mapper;

    public AuroraPmsClient(String baseUrl, McpJsonMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public Map<String, String> roomStatus(String skuId, String ignored) {
        return roomStatus(skuId);
    }

    /** 房态：按 SKU 查可用数。 */
    public Map<String, String> roomStatus(String skuId) {
        if (blank(skuId)) {
            return Map.of("status", "BAD_PARAM", "message", "缺少 skuId（aurora 库存域 SKU ID）");
        }
        try {
            String body = get("/api/inventory/available/" + skuId.trim());
            Integer available = mapper.readValue(body, Integer.class);
            return Map.of("status", "OK", "source", "aurora-inventory",
                    "skuId", skuId.trim(), "available", String.valueOf(available));
        } catch (Exception e) {
            return unavailable("房态(" + skuId + ")");
        }
    }

    /** 价格：按 SKU 查权威定价。 */
    @Override
    public Map<String, String> price(String skuId) {
        if (blank(skuId)) {
            return Map.of("status", "BAD_PARAM", "message", "缺少 skuId");
        }
        try {
            Map<String, Object> vo = getJson("/api/product/sku/" + skuId.trim() + "/price");
            Map<String, String> r = new LinkedHashMap<>();
            r.put("status", "OK");
            r.put("source", "aurora-product");
            r.put("skuId", String.valueOf(vo.get("skuId")));
            r.put("skuName", String.valueOf(vo.get("skuName")));
            r.put("price", String.valueOf(vo.get("price")));
            r.put("supplierPrice", String.valueOf(vo.get("supplierPrice")));
            r.put("stockType", String.valueOf(vo.get("stockType")));
            return r;
        } catch (Exception e) {
            return unavailable("价格(" + skuId + ")");
        }
    }

    /** 订单：按订单号查询（脱敏：不返回 guestMobile）。 */
    @Override
    public Map<String, String> booking(String orderNo) {
        if (blank(orderNo)) {
            return Map.of("status", "BAD_PARAM", "message", "缺少 orderNo");
        }
        try {
            Map<String, Object> o = getJson("/api/order/no/" + orderNo.trim());
            Map<String, String> r = new LinkedHashMap<>();
            r.put("status", "OK");
            r.put("source", "aurora-order");
            r.put("orderNo", String.valueOf(o.get("orderNo")));
            r.put("orderStatus", String.valueOf(o.get("status")));
            r.put("guestName", String.valueOf(o.get("guestName")));
            r.put("checkInDate", String.valueOf(o.get("checkInDate")));
            r.put("checkOutDate", String.valueOf(o.get("checkOutDate")));
            r.put("roomTypeId", String.valueOf(o.get("roomTypeId")));
            r.put("nights", String.valueOf(o.get("nights")));
            r.put("roomCount", String.valueOf(o.get("roomCount")));
            r.put("roomPrice", String.valueOf(o.get("roomPrice")));
            r.put("payAmount", String.valueOf(o.get("payAmount")));
            r.put("privacy", "已脱敏（不含手机号等隐私字段）");
            return r;
        } catch (Exception e) {
            return unavailable("订单(" + orderNo + ")");
        }
    }

    /** 会员：按会员号查询（脱敏：不返回 idCard/phone/wxOpenid）。 */
    @Override
    public Map<String, String> member(String memberNo) {
        if (blank(memberNo)) {
            return Map.of("status", "BAD_PARAM", "message", "缺少 memberNo");
        }
        try {
            Map<String, Object> m = getJson("/api/member/no/" + memberNo.trim());
            Map<String, String> r = new LinkedHashMap<>();
            r.put("status", "OK");
            r.put("source", "aurora-member");
            r.put("memberNo", String.valueOf(m.get("memberNo")));
            r.put("nickname", String.valueOf(m.get("nickname")));
            r.put("level", String.valueOf(m.get("level")));
            r.put("growthValue", String.valueOf(m.get("growthValue")));
            r.put("points", String.valueOf(m.get("points")));
            r.put("memberStatus", String.valueOf(m.get("status")));
            r.put("privacy", "已脱敏（不含证件/手机号/微信OpenID）");
            return r;
        } catch (Exception e) {
            return unavailable("会员(" + memberNo + ")");
        }
    }

    // ---------- 内部 ----------

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " @ " + path);
        }
        return resp.body();
    }

    private Map<String, Object> getJson(String path) throws IOException, InterruptedException {
        return mapper.readValue(get(path), Map.class);
    }

    /** 统一降级：查不到如实说，不编造。 */
    private Map<String, String> unavailable(String what) {
        return Map.of("status", "PMS_UNAVAILABLE",
                "message", "真实 PMS(" + what + ") 不可达或查询失败——如实告知：暂时查不到，请稍后再试或转人工");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
