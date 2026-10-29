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
 * <p>端点（服务直连；多服务端口映射，或网关单 base）：
 * <ul>
 *   <li>房态：GET /api/inventory/available/{skuId}    → aurora-inventory-service :8103</li>
 *   <li>价格：GET /api/product/sku/{id}/price         → aurora-product-service   :8102</li>
 *   <li>订单：GET /api/order/no/{orderNo}             → aurora-order-service     :8104</li>
 *   <li>会员：GET /api/member/no/{memberNo}           → aurora-member-service    :8108</li>
 * </ul>
 *
 * <p>构造：传「host 前缀（无端口）」→ 按 8102/8103/8104/8108 端口表自动路由；传「完整 base URL」
 * （网关或单服务）→ 四个查询都走该 base（网关需登录态，服务直连仅该服务可用，其余如实降级）。
 * 请求一律带 {@code X-Merchant-Id} 租户头（aurora 多租户：无租户上下文业务查询直接 500）。
 *
 * <p>脱敏红线：订单不返回 guestMobile；会员不返回 idCard/phone/wxOpenid（对齐落地方案数据合规）。
 * 服务不可达/异常/未授权 → 返回 PMS_UNAVAILABLE（如实说查不到，杜绝编造，对齐静默失败防线）。
 */
public class AuroraPmsClient implements McpServerMain.PmsDataSource {

    /** 服务直连端口表（0=inventory 1=product 2=order 3=member）。 */
    private static final int[] SERVICE_PORTS = {8103, 8102, 8104, 8108};

    private final String[] endpoints = new String[4];
    private final String tenant;
    private final HttpClient http;
    private final McpJsonMapper mapper;

    public AuroraPmsClient(String baseUrl, String tenant, McpJsonMapper mapper) {
        this.tenant = tenant == null || tenant.isBlank() ? "1" : tenant.trim();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        String base = baseUrl == null || baseUrl.isBlank() ? "http://localhost" : baseUrl.trim();
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (hasPort(base)) {
            // 完整 base（网关或单服务）：四查询共用
            for (int i = 0; i < 4; i++) {
                endpoints[i] = base;
            }
        } else {
            // host 前缀：按端口表路由各服务
            for (int i = 0; i < 4; i++) {
                endpoints[i] = base + ":" + SERVICE_PORTS[i];
            }
        }
    }

    private static boolean hasPort(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        String rest = url.substring(scheme + 3);
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return false;
        }
        int slash = rest.indexOf('/', colon);
        String port = slash < 0 ? rest.substring(colon + 1) : rest.substring(colon + 1, slash);
        return !port.isBlank();
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
            String body = get(0, "/api/inventory/available/" + skuId.trim());
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
            Map<String, Object> vo = getJson(1, "/api/product/sku/" + skuId.trim() + "/price");
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
            Map<String, Object> o = getJson(2, "/api/order/no/" + orderNo.trim());
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

    /** 会员：按会员号查询（服务端脱敏视图：phoneMasked/idCardMasked；不外返明文/OpenID）。 */
    @Override
    public Map<String, String> member(String memberNo) {
        if (blank(memberNo)) {
            return Map.of("status", "BAD_PARAM", "message", "缺少 memberNo");
        }
        try {
            Map<String, Object> m = getJson(3, "/api/member/no/" + memberNo.trim());
            if (isBlank(m.get("memberNo")) && m.get("id") == null) {
                return Map.of("status", "NOT_FOUND", "message", "未查询到会员 " + memberNo.trim());
            }
            Map<String, String> r = new LinkedHashMap<>();
            r.put("status", "OK");
            r.put("source", "aurora-member");
            putIfPresent(r, "memberNo", m.get("memberNo"));
            putIfPresent(r, "nickname", m.get("nickname"));
            putIfPresent(r, "level", m.get("level"));
            putIfPresent(r, "growthValue", m.get("growthValue"));
            putIfPresent(r, "points", m.get("points"));
            putIfPresent(r, "phoneMasked", m.get("phoneMasked"));
            putIfPresent(r, "storedBalance", m.get("storedBalance"));
            r.put("privacy", "已脱敏（服务端掩码视图，不含证件/手机号明文/微信OpenID）");
            return r;
        } catch (Exception e) {
            return unavailable("会员(" + memberNo + ")");
        }
    }

    private static void putIfPresent(Map<String, String> r, String key, Object v) {
        if (v != null && !v.toString().isBlank() && !"null".equalsIgnoreCase(v.toString())) {
            r.put(key, String.valueOf(v));
        }
    }

    private static boolean isBlank(Object v) {
        return v == null || v.toString().isBlank() || "null".equalsIgnoreCase(v.toString());
    }

    // ---------- 内部 ----------

    private String get(int idx, String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoints[idx] + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("X-Merchant-Id", tenant)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " @ " + path);
        }
        return resp.body();
    }

    private Map<String, Object> getJson(int idx, String path) throws IOException, InterruptedException {
        return mapper.readValue(get(idx, path), Map.class);
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
