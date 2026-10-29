# EdgeHotel MCP Server（HotelOps Template）

端侧酒店私域运营 Agent 的**自建 Java MCP Server**（独立打包、双传输：stdio + Streamable HTTP）。
对齐落地方案 §4.3 工具清单，供任意 MCP 客户端（Claude Desktop / 自建 Agent Runtime / 自研程序直连等）通过标准 MCP 协议调用酒店工具。

## 一、构建

```powershell
$env:JAVA_HOME = "D:\jdk\jdk-17.0.12"
& "D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd" -f pom.xml package
```

产物：`target/edgehotel-mcp-server.jar`（约 8.9MB，shade 全量可执行 jar）。
依赖：MCP Java SDK 0.17.2（`io.modelcontextprotocol.sdk:mcp`，BOM 管理）+ Jetty 12（EE10，Streamable HTTP 承载）。

## 二、启动方式（双传输 + 双数据源）

| 模式 | 命令 | 适用场景 |
|---|---|---|
| stdio（默认） | `java -Dfile.encoding=UTF-8 -jar target\edgehotel-mcp-server.jar [--pms demo\|aurora]` | 标准 MCP 客户端（Claude Desktop / Agent Runtime）拉起进程 |
| Streamable HTTP | `java -Dfile.encoding=UTF-8 -jar target\edgehotel-mcp-server.jar --http [端口] [--pms demo\|aurora] [--pms-url <base>]` | 跨进程/远程调用；默认端口 **8601**，端点 `http://localhost:8601/mcp` |

> Windows 下必须带 `-Dfile.encoding=UTF-8`，否则 stdin/stdout 中文乱码（JDK 默认系统代码页）。

### 数据源（MCP Bridge）

| 模式 | 参数 | 说明 |
|---|---|---|
| demo（默认） | `--pms demo` | 内置示例数据（房态 3 天×3 房型 / 订单 2 条 / 会员 2 条），离线联调用 |
| **真实 PMS（aurora）** | `--pms aurora --pms-url http://localhost:8100` | 桥接 aurora 酒店微服务：房态 inventory:8103 / 价格 product:8102 / 订单 order:8104 / 会员 member:8108（网关 8100 或服务直连） |

**桥接红线**：
- 输出脱敏：订单不返回 `guestMobile`；会员不返回 `idCard/phone/wxOpenid`（对齐落地方案数据合规）。
- 服务不可达 → 返回 `PMS_UNAVAILABLE`（如实告知"暂时查不到，请稍后再试或转人工"，杜绝编造，对齐静默失败防线）。
- 写工具（投诉/话术）由调用方审批门拦截，本 Server 仅提供语义与执行。

### stdio 客户端接入（Claude Desktop 示例，`claude_desktop_config.json`）

```json
{
  "mcpServers": {
    "edgehotel": {
      "command": "java",
      "args": ["-Dfile.encoding=UTF-8", "-jar", "D:\\AI\\edgehotel-agent\\mcp-server\\target\\edgehotel-mcp-server.jar", "--pms", "aurora", "--pms-url", "http://localhost:8100"]
    }
  }
}
```

### HTTP 接入（自研程序 / 远程调用）

```
POST http://localhost:8601/mcp
Content-Type: application/json
Accept: application/json, text/event-stream

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"my-client","version":"1.0"}}}
```

- initialize 响应头 `Mcp-Session-Id` 需带回后续请求（`Mcp-Session-Id: <sid>`）。
- 请求体即 MCP JSON-RPC（`tools/list` / `tools/call`），示例见 `scripts/mcp-smoke-http.py`。
- 端口 8601 对齐 ITGG 端口规划（EdgeHotel 系 86xx 段）。

## 三、工具清单（对齐落地方案 §4.3）

| 工具 | 类型/敏感度 | 说明 |
|---|---|---|
| `hotel_room_status` | 读 / 低 | 房态查询；未知日期返回 `UNKNOWN_DATE`（防"查不到却答可订"静默失败） |
| `hotel_price_quote` | 读 / 低 | 房型价格（含早餐） |
| `hotel_booking_lookup` | 读 / 低 | 按订单号查订单状态 |
| `member_profile_query` | 读 / 高（脱敏） | 会员等级+偏好摘要，**不含手机号/证件等隐私字段** |
| `complaint_escalate` | 写 / 高 | 投诉/人工升级工单；返回工单号供留痕 |
| `marketing_copy_generate` | 写 / 中 | 营销/关怀话术草稿（仅草稿不发送） |

**敏感写工具由调用方（Agent Runtime / 审批门）拦截**：本 Server 只提供工具语义与执行，审批语义保留在运行时层（与主工程 M1/M2 的 Approval Gate 一致）。

## 四、数据源

当前 `HotelData.java` 为**商家示例数据**（房态覆盖 2026-10-30 ~ 11-01，订单 B20261001/B20261002，会员 M-1001/M-1002）。
独立部署时替换为商家真实数据源（DB / JSON 文件 / PMS 桥接，见落地方案 M2 前置"MCP Bridge 对接真实 PMS"）。

## 五、验证

一键冒烟（stdio：MCP 协议 JSON-RPC over stdio：initialize → tools/list → 7 次 tools/call）：

```powershell
python scripts\mcp-smoke.py target\edgehotel-mcp-server.jar
```

HTTP 冒烟（先启动 `--http 8601`，再跑）：

```powershell
python scripts\mcp-smoke-http.py
```

aurora 真实 PMS 离线降级冒烟（aurora 服务未启动时跑，验证如实降级不编造）：

```powershell
# 先启动：java -Dfile.encoding=UTF-8 -jar target\edgehotel-mcp-server.jar --http 8601 --pms aurora
python scripts\mcp-smoke-aurora-offline.py
```

实测记录（2026-10-29）：
- **stdio demo 9/9 PASS** —— 握手、6 工具枚举、房态/未知日期防幻觉/价格/订单/会员脱敏/投诉工单/话术草稿全部通过。
- **HTTP demo 8/8 PASS** —— initialize（Mcp-Session-Id 会话）+ tools/list + 6 工具调用全部通过。
- **aurora 离线降级 4/4 PASS** —— 4 读工具在 PMS 不可达时均返回 `PMS_UNAVAILABLE` + 如实文案（不编造）。
- 已修复：`booking` 结果 Map 重复 key；价格早餐字段恒 false；启动日志曾污染 stdio stdout（改走 stderr）。
- 待实弹：aurora 服务启动后 `--pms aurora` 直连真实数据联调（端点/字段已按源码核对）。

## 六、治理对齐（ITGG 红线）

- 数据最小化：会员查询只暴露等级+偏好摘要；投诉工单仅含必要字段。
- 静默失败防线：房态未知日期显式 `UNKNOWN_DATE`，不编造"可订"。
- 端口规划：本 Server 为 stdio（进程内）通信，不占 HTTP 端口；若启用 Streamable HTTP 传输，预留 **8601**（EdgeHotel 系 86xx 段）。
