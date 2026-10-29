package com.edgehotel.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;

/**
 * EdgeHotel MCP Server（HotelOps Template，独立打包）。
 *
 * <p>双传输：
 * <ul>
 *   <li>默认 stdio：`java -jar edgehotel-mcp-server.jar`（MCP 客户端拉起进程）</li>
 *   <li>Streamable HTTP：`java -jar edgehotel-mcp-server.jar --http [端口]`（默认 8601，路径 /mcp）。
 *       内网/非本机部署务必配 `--auth-token &lt;token&gt;` 启用 Bearer 门禁（HTTP 模式无 token 时启动会告警）</li>
 * </ul>
 *
 * <p>数据源（MCP Bridge）：
 * <ul>
 *   <li>`--pms demo`（默认）：内置示例数据（HotelData）</li>
 *   <li>`--pms aurora [--pms-url http://localhost:8100]`：桥接真实 aurora 酒店微服务
 *       （inventory 8103 房态 / product 8102 价格 / order 8104 订单 / member 8108 会员，脱敏输出）</li>
 * </ul>
 *
 * <p>范式对齐（2026-10-30）：
 * <ul>
 *   <li>inputSchema 按数据源模式声明完整 properties/required（demo 与 aurora 参数域不同，
 *       启动即定型，不再做运行期参数别名兜底）；</li>
 *   <li>工具执行失败（BAD_PARAM/PMS_UNAVAILABLE）返回 isError=true 的 CallToolResult，
 *       领域性"如实答案"（UNKNOWN_DATE/NOT_FOUND/SOLD_OUT 等）保持普通结果；</li>
 *   <li>全部工具声明 ToolAnnotations（readOnly/destructive/idempotent/openWorld 提示，
 *       供客户端决定确认交互）；</li>
 *   <li>工具结果同时输出 text（key=value，兼容旧客户端）与 structuredContent（结构化 JSON）。</li>
 * </ul>
 *
 * <p>对齐落地方案 §4.3 工具清单：读 3（房态/房价/订单）+ 写 2（投诉升级/话术生成）+ 会员脱敏查询。
 * 敏感写工具由调用方（Agent Runtime / 审批门）拦截；本 Server 仅提供工具语义与执行。
 */
public class McpServerMain {

    /** 数据源统一接口：demo（示例）与 aurora（真实 PMS）均实现四读能力。 */
    public interface PmsDataSource {
        Map<String, String> roomStatus(String dateOrSku, String roomTypeOrNull);

        Map<String, String> price(String skuOrRoomType);

        Map<String, String> booking(String orderNo);

        Map<String, String> member(String memberNo);
    }

    public static void main(String[] args) throws Exception {
        McpJsonMapper mapper = ServiceLoader.load(McpJsonMapperSupplier.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 McpJsonMapper SPI 实现（需 mcp-json 依赖）"))
                .get();

        // 参数解析：传输模式 + 数据源 + 鉴权
        boolean httpMode = false;
        int port = 8601;
        String pmsMode = "demo";
        String pmsUrl = "http://localhost";
        String tenant = "1";
        String dataJson = null;
        String authToken = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--http" -> {
                    httpMode = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        port = Integer.parseInt(args[++i]);
                    }
                }
                case "--pms" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        pmsMode = args[++i];
                    }
                }
                case "--pms-url" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        pmsUrl = args[++i];
                    }
                }
                case "--pms-tenant" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        tenant = args[++i];
                    }
                }
                case "--data" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        dataJson = args[++i];
                    }
                }
                case "--auth-token" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        authToken = args[++i];
                    }
                }
                default -> {
                    // 忽略未知参数
                }
            }
        }

        boolean aurora = "aurora".equalsIgnoreCase(pmsMode);
        PmsDataSource dataSource;
        if (aurora) {
            dataSource = new AuroraPmsClient(pmsUrl, tenant, mapper);
            System.err.println("[edgehotel-mcp] 数据源: aurora 真实 PMS @ " + pmsUrl + " (租户 X-Merchant-Id=" + tenant + ")");
        } else {
            dataSource = dataJson != null
                    ? new HotelData(java.nio.file.Path.of(dataJson), mapper)
                    : new HotelData();
            System.err.println("[edgehotel-mcp] 数据源: demo 示例数据"
                    + (dataJson != null ? "（外置 JSON: " + dataJson + "）" : "（--pms aurora 切换真实 PMS）"));
        }

        if (httpMode) {
            startHttp(dataSource, mapper, port, aurora, authToken);
        } else {
            startStdio(dataSource, mapper, aurora);
        }
    }

    /** stdio 传输（默认）。build() 即启动会话循环；stdin EOF 自然退出。 */
    private static void startStdio(PmsDataSource dataSource, McpJsonMapper mapper, boolean aurora) throws Exception {
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo("edgehotel-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools(dataSource, aurora))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        new CountDownLatch(1).await();
    }

    /** Streamable HTTP 传输（--http [port]，默认 8601，路径 /mcp；--auth-token 启用 Bearer 门禁）。 */
    private static void startHttp(PmsDataSource dataSource, McpJsonMapper mapper, int port,
                                  boolean aurora, String authToken) throws Exception {
        HttpServletStreamableServerTransportProvider provider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .mcpEndpoint("/mcp")
                        .build();
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("edgehotel-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools(dataSource, aurora))
                .build();

        Server jetty = new Server(port);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.setContextPath("/");
        if (authToken != null && !authToken.isBlank()) {
            // Bearer 门禁：/mcp 全部方法（POST JSON-RPC + GET SSE 流）先过 token 校验
            String expected = "Bearer " + authToken.trim();
            Filter auth = (req, resp, chain) -> {
                String header = ((jakarta.servlet.http.HttpServletRequest) req).getHeader("Authorization");
                if (!expected.equals(header)) {
                    ((jakarta.servlet.http.HttpServletResponse) resp).sendError(401, "unauthorized: missing or invalid Bearer token");
                    return;
                }
                chain.doFilter(req, resp);
            };
            ctx.addFilter(new FilterHolder(auth), "/mcp", EnumSet.of(DispatcherType.REQUEST));
            System.err.println("[edgehotel-mcp] (HTTP) 鉴权: Bearer token 已启用");
        } else {
            System.err.println("[edgehotel-mcp] (HTTP) 警告: 未配置 --auth-token，/mcp 无鉴权（仅建议本机回环使用）");
        }
        ctx.addServlet(new ServletHolder(provider), "/mcp");
        jetty.setHandler(ctx);
        jetty.start();
        System.err.println("[edgehotel-mcp] (HTTP) listening on http://localhost:" + port + "/mcp");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            try {
                jetty.stop();
            } catch (Exception ignored) {
                // 停机钩子内忽略二次异常
            }
        }));
        jetty.join();
    }

    // ---------- 工具注册（inputSchema 按数据源模式定型） ----------

    private static List<McpServerFeatures.SyncToolSpecification> tools(PmsDataSource dataSource, boolean aurora) {
        // 参数域：demo 用 date/room_type/order_id/member_id；aurora 用 skuId/order_no/member_no
        String roomKeyArg = aurora ? "skuId" : "date";
        String orderKeyArg = aurora ? "order_no" : "order_id";
        String memberKeyArg = aurora ? "member_no" : "member_id";

        return List.of(
                toolSpec("hotel_room_status",
                        aurora
                                ? "查询酒店指定 SKU 的可订房态。参数：skuId=库存域 SKU ID（如 1）。数据不可达返回 PMS_UNAVAILABLE，务必如实转述，不得编造。"
                                : "查询酒店指定日期与房型的可订房态。参数：date=YYYY-MM-DD，room_type=standard|deluxe|suite。未知日期返回 UNKNOWN_DATE，务必如实转述，不得编造。",
                        annotations("查询房态", true, false, true, false),
                        aurora
                                ? objSchema(Map.of("skuId", strProp("库存域 SKU ID，如 1")), List.of("skuId"))
                                : objSchema(Map.of(
                                        "date", strProp("查询日期，YYYY-MM-DD（示例数据覆盖 2026-10-30~11-01）"),
                                        "room_type", enumProp("房型，可选", List.of("standard", "deluxe", "suite"))),
                                  List.of("date")),
                        (exchange, a) -> ok(dataSource.roomStatus(str(a, roomKeyArg), str(a, "room_type")))),
                toolSpec("hotel_price_quote",
                        aurora
                                ? "查询酒店 SKU 权威定价。参数：skuId。"
                                : "查询酒店房型价格（含早餐标识）。参数：room_type=standard|deluxe|suite。",
                        annotations("查询价格", true, false, true, false),
                        aurora
                                ? objSchema(Map.of("skuId", strProp("库存域 SKU ID，如 1")), List.of("skuId"))
                                : objSchema(Map.of("room_type", enumProp("房型", List.of("standard", "deluxe", "suite"))),
                                  List.of("room_type")),
                        (exchange, a) -> ok(dataSource.price(str(a, aurora ? "skuId" : "room_type")))),
                toolSpec("hotel_booking_lookup",
                        aurora
                                ? "按订单号查询订单状态。参数：order_no。输出已脱敏（不含手机号）。"
                                : "按订单号查询订单状态。参数：order_id（示例订单：B20261001/B20261002）。",
                        annotations("查询订单", true, false, true, false),
                        objSchema(Map.of(orderKeyArg, strProp("订单号")), List.of(orderKeyArg)),
                        (exchange, a) -> ok(dataSource.booking(str(a, orderKeyArg)))),
                toolSpec("member_profile_query",
                        "查询会员等级与偏好摘要（脱敏：不含手机号/证件等隐私字段）。参数：" + memberKeyArg + "。",
                        annotations("查询会员", true, false, true, false),
                        objSchema(Map.of(memberKeyArg, strProp("会员号")), List.of(memberKeyArg)),
                        (exchange, a) -> ok(dataSource.member(str(a, memberKeyArg)))),
                toolSpec("complaint_escalate",
                        "创建投诉/人工升级工单（敏感写操作，调用方须走审批门；返回工单号供留痕）。参数：guest_name、issue、room_no（可选）。",
                        // 写语义：readOnly=false 让主流客户端弹确认；非破坏性但不可幂等（每次调用新建工单）
                        annotations("创建投诉工单", false, false, false, false),
                        objSchema(Map.of(
                                "guest_name", strProp("客人姓名"),
                                "issue", strProp("投诉问题简述"),
                                "room_no", strProp("房号，可选")), List.of("guest_name", "issue")),
                        (exchange, a) -> {
                            Map<String, String> r = new LinkedHashMap<>();
                            r.put("status", "ESCALATED");
                            r.put("ticket", "TK-" + (System.currentTimeMillis() % 100000));
                            r.put("guest", str(a, "guest_name"));
                            r.put("room_no", str(a, "room_no", "-"));
                            r.put("issue", str(a, "issue"));
                            r.put("message", "工单已创建，转人工跟进");
                            return ok(r);
                        }),
                toolSpec("marketing_copy_generate",
                        "生成私域营销/关怀话术草稿（敏感操作，调用方须走审批门；仅生成草稿不发送）。参数：scenario=welcome|departure|promotion，guest_name。",
                        // 保守注解：与运行时审批门一致，客户端按"需确认"处理
                        annotations("生成话术草稿", false, false, true, false),
                        objSchema(Map.of(
                                "scenario", enumProp("话术场景", List.of("welcome", "departure", "promotion")),
                                "guest_name", strProp("客人姓名")), List.of("scenario", "guest_name")),
                        (exchange, a) -> {
                            String scenario = str(a, "scenario");
                            String guest = str(a, "guest_name");
                            String draft = switch (scenario) {
                                case "welcome" -> guest + "您好，欢迎下榻极光酒店！已为您预留好房间，前台可随时办理入住，祝您入住愉快。";
                                case "departure" -> guest + "您好，感谢您选择极光酒店。期待您的再次光临，如需协助欢迎随时联系我们。";
                                case "promotion" -> guest + "您好，极光酒店本月推出会员专享房型优惠，登录官方小程序即可查看，期待您的光临！";
                                default -> "话术草稿（未识别的场景：" + scenario + "）。";
                            };
                            Map<String, String> r = new LinkedHashMap<>();
                            r.put("status", "DRAFT");
                            r.put("scenario", scenario);
                            r.put("draft", draft);
                            r.put("message", "仅生成草稿，发送前须审批");
                            return ok(r);
                        }));
    }

    private static McpServerFeatures.SyncToolSpecification toolSpec(String name, String description,
                                                                    McpSchema.ToolAnnotations annotations,
                                                                    McpSchema.JsonSchema schema,
                                                                    BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        McpSchema.Tool t = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .annotations(annotations)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(t)
                .callHandler((exchange, request) -> handler.apply(exchange, request.arguments()))
                .build();
    }

    /**
     * 统一结果包装：text（key=value，兼容所有客户端）+ structuredContent（结构化，供新协议客户端）。
     * 工具执行失败（BAD_PARAM/PMS_UNAVAILABLE）置 isError=true；领域性"如实答案"
     * （UNKNOWN_DATE/UNKNOWN_TYPE/NOT_FOUND/SOLD_OUT）保持普通结果——它们是有效回答而非执行失败。
     */
    private static McpSchema.CallToolResult ok(Map<String, String> result) {
        StringBuilder sb = new StringBuilder();
        result.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
        boolean failed = "BAD_PARAM".equals(result.get("status"))
                || "PMS_UNAVAILABLE".equals(result.get("status"));
        return McpSchema.CallToolResult.builder()
                .addContent(new McpSchema.TextContent(sb.toString().trim()))
                .structuredContent(result)
                .isError(failed)
                .build();
    }

    // ---------- schema / annotations 构造 ----------

    private static McpSchema.JsonSchema objSchema(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }

    private static Map<String, Object> strProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> enumProp(String description, List<String> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", values);
        return p;
    }

    private static McpSchema.ToolAnnotations annotations(String title, boolean readOnly, boolean destructive,
                                                         boolean idempotent, boolean openWorld) {
        return new McpSchema.ToolAnnotations(title, readOnly, destructive, idempotent, openWorld, false);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String str(Map<String, Object> args, String key, String def) {
        String s = str(args, key);
        return s.isEmpty() ? def : s;
    }
}
