# EdgeHotel MCP Server（HotelOps Template）

端侧酒店私域运营 Agent 的**自建 Java MCP Server**（独立打包、stdio 传输）。
对齐落地方案 §4.3 工具清单，供任意 MCP 客户端（Claude Desktop / 自建 Agent Runtime / LM Studio 等）通过标准 MCP 协议调用酒店工具。

## 一、构建

```powershell
$env:JAVA_HOME = "D:\jdk\jdk-17.0.12"
& "D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd" -f pom.xml package
```

产物：`target/edgehotel-mcp-server.jar`（约 5.9MB，shade 全量可执行 jar）。
依赖：MCP Java SDK 0.17.2（`io.modelcontextprotocol.sdk:mcp`，BOM 管理）。

## 二、MCP 客户端接入

在 MCP 客户端中注册 stdio 服务器（Windows 注意编码参数）：

| 字段 | 值 |
|---|---|
| command | `java` |
| args | `-Dfile.encoding=UTF-8`、`-jar`、`D:\AI\edgehotel-agent\mcp-server\target\edgehotel-mcp-server.jar` |

> Windows 下若不指定 `-Dfile.encoding=UTF-8`，stdin/stdout 中文会乱码（JDK 默认系统代码页）。

Claude Desktop 示例（`claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "edgehotel": {
      "command": "java",
      "args": ["-Dfile.encoding=UTF-8", "-jar", "D:\\AI\\edgehotel-agent\\mcp-server\\target\\edgehotel-mcp-server.jar"]
    }
  }
}
```

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

一键冒烟（MCP 协议 JSON-RPC over stdio：initialize → tools/list → 7 次 tools/call）：

```powershell
python scripts\mcp-smoke.py target\edgehotel-mcp-server.jar
```

实测记录（2026-10-29）：**9/9 PASS** —— 握手、6 工具枚举、房态/未知日期防幻觉/价格/订单/会员脱敏/投诉工单/话术草稿全部通过。
已修复：`booking` 结果 Map 重复 key（`status`×2 → `orderStatus`）；价格早餐字段恒 false 判断（`"含早餐".equals(...)` → 整数比较）。

## 六、治理对齐（ITGG 红线）

- 数据最小化：会员查询只暴露等级+偏好摘要；投诉工单仅含必要字段。
- 静默失败防线：房态未知日期显式 `UNKNOWN_DATE`，不编造"可订"。
- 端口规划：本 Server 为 stdio（进程内）通信，不占 HTTP 端口；若启用 Streamable HTTP 传输，预留 **8601**（EdgeHotel 系 86xx 段）。
