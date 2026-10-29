package com.edgehotel.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

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
 *   <li>Streamable HTTP：`java -jar edgehotel-mcp-server.jar --http [端口]`（默认 8601，路径 /mcp）</li>
 * </ul>
 *
 * <p>对齐落地方案 §4.3 工具清单：读 3（房态/房价/订单）+ 写 2（投诉升级/话术生成）+ 会员脱敏查询。
 * 敏感写工具由调用方（Agent Runtime / 审批门）拦截；本 Server 仅提供工具语义与执行。
 */
public class McpServerMain {

    public static void main(String[] args) throws Exception {
        HotelData data = new HotelData();
        McpJsonMapper mapper = ServiceLoader.load(McpJsonMapperSupplier.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 McpJsonMapper SPI 实现（需 mcp-json 依赖）"))
                .get();

        boolean httpMode = args.length > 0 && "--http".equals(args[0]);
        if (httpMode) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8601;
            startHttp(data, mapper, port);
        } else {
            startStdio(data, mapper);
        }
    }

    /** stdio 传输（默认）。build() 即启动会话循环；stdin EOF 自然退出。 */
    private static void startStdio(HotelData data, McpJsonMapper mapper) throws Exception {
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo("edgehotel-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools(data))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        new CountDownLatch(1).await();
    }

    /** Streamable HTTP 传输（--http [port]，默认 8601，路径 /mcp）。 */
    private static void startHttp(HotelData data, McpJsonMapper mapper, int port) throws Exception {
        HttpServletStreamableServerTransportProvider provider =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .mcpEndpoint("/mcp")
                        .build();
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("edgehotel-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools(data))
                .build();

        Server jetty = new Server(port);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(provider), "/mcp");
        jetty.setHandler(ctx);
        jetty.start();
        System.out.println("edgehotel-mcp-server (HTTP) listening on http://localhost:" + port + "/mcp");
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

    // ---------- 工具注册 ----------

    private static List<McpServerFeatures.SyncToolSpecification> tools(HotelData data) {
        return List.of(
                toolSpec("hotel_room_status",
                        "查询酒店指定日期与房型的可订房态。参数：date=YYYY-MM-DD，room_type=standard|deluxe|suite。示例数据仅覆盖 2026-10-30 至 2026-11-01；未知日期返回 UNKNOWN_DATE，务必如实转述，不得编造。",
                        (exchange, a) -> ok(data.roomStatus(str(a, "date"), str(a, "room_type")))),
                toolSpec("hotel_price_quote",
                        "查询酒店房型价格（含早餐信息）。参数：room_type=standard|deluxe|suite。示例数据。",
                        (exchange, a) -> ok(data.price(str(a, "room_type")))),
                toolSpec("hotel_booking_lookup",
                        "按订单号查询订单状态。参数：order_id。示例数据仅 B20261001/B20261002。",
                        (exchange, a) -> ok(data.booking(str(a, "order_id")))),
                toolSpec("member_profile_query",
                        "查询会员等级与偏好摘要（脱敏：不含手机号/证件等隐私字段）。参数：member_id。示例 M-1001/M-1002。",
                        (exchange, a) -> ok(data.member(str(a, "member_id")))),
                toolSpec("complaint_escalate",
                        "创建投诉/人工升级工单（敏感写操作，调用方须走审批门；返回工单号供留痕）。参数：guest_name、room_no、issue。",
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
                        "生成私域营销/关怀话术草稿（敏感写操作，调用方须走审批门；仅生成草稿不发送）。参数：scenario=welcome|departure|promotion，guest_name。",
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
                                                                    BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        // 宽松 inputSchema（仅声明 object 类型）；参数说明置于 description，工具内自行校验缺参
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), false, Map.of(), Map.of());
        McpSchema.Tool t = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(t)
                .callHandler((exchange, request) -> handler.apply(exchange, request.arguments()))
                .build();
    }

    private static McpSchema.CallToolResult ok(Map<String, String> result) {
        StringBuilder sb = new StringBuilder();
        result.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(sb.toString().trim())))
                .build();
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
