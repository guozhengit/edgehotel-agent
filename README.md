# EdgeHotel-Agent

端侧酒店私域运营 Agent 运行时（**EdgeAgent Runtime × HotelOps Template**）。
在 8GB 显存 / 32GB 内存的笔记本级主机上，为单酒店商家提供**隐私 + 离线 + 可托付**的 AI 助手：RAG 问答、投诉识别、话术生成、跨会话记忆、敏感操作审批、静默失败防护。

> 差异化：数据不出本机、断网可用、每个失败轨迹自动沉淀为回归用例（"失败即资产"）。
> 里程碑：M0 技术验证 ✅ → M1 MVP Agent ✅ → M2 模板产品化 ✅ → M3 资产沉淀（进行中）。

---

## 架构

```
┌─ 交互层：商家 Web 控制台（static/index.html，同端口）│ REST API（/api/*）
├─ Agent 运行时（Java 17 / Spring Boot 3.5 / WebFlux）
│   Agent Core（编排循环：规划→工具→验证→审批）
│   ├─ ToolRegistry：工具注册/描述/Schema（读：房态/房价/订单；写：投诉升级/话术生成）
│   ├─ Approval Gate：敏感写操作默认拦截，100% 留痕（AP-xxxx，grant/deny 可审计）
│   ├─ Verifier：静默失败检测（EMPTY/空白 → 台账 + 回归用例自动入集）
│   ├─ Memory Store：跨会话记忆（向量召回，注入编排上下文）
│   └─ Eval Case Store：失败轨迹 → eval_cases 回归集（hash 去重、命中计数）
├─ 记忆/知识层：SQLite（edgehotel.db：approvals / silent_failures / memories / eval_cases）
│                + Qwen3-Embedding-0.6B（1024 维向量）
└─ 推理层：LM Studio（OpenAI 兼容 localhost:1234，模型可热切换）
```

## 组件边界（可独立引用/未来可拆包）

| 组件 | 包 | 接口/实现 | 依赖 |
|---|---|---|---|
| **memory**（跨会话记忆） | `com.edgehotel.agent.memory` | `MemoryStore` 接口 + `SqliteMemoryStore` | `store.Db`、`rag.EmbeddingClient` |
| **approval**（审批门） | `com.edgehotel.agent.agent.approval` | `ApprovalGate`（submit/grant/deny/decide） | `store.Db` |
| **verifier**（静默失败观测） | `com.edgehotel.agent.agent.verifier` | `Verifier` 接口 + `ToolVerifier`、`VerifyVerdict`、`SilentFailureLedger` | `store.Db` |
| **eval**（回归用例） | `com.edgehotel.agent.store` | `EvalCaseStore`（失败轨迹自动入库去重） | `store.Db` |
| 共享基础设施 | `store.Db` / `rag.EmbeddingClient` | SQLite 连接工厂 / LM Studio 嵌入客户端 | — |

组件只依赖共享基础设施（SQLite / Embedding 客户端），**不耦合酒店业务工具**；业务层（`agent` 包内工具 + AgentRunner）可整体替换为其他行业模板。

## 快速开始（本机）

**前置**：JDK 17（`D:\jdk\jdk-17.0.12`）、Maven 3.9、LM Studio 已加载模型：
- 骨干：`qwen3-30b-a3b`（工具编排）
- 快路径：`qwen/qwen3.5-9b`（投诉识别/日常问答）
- 嵌入：`text-embedding-qwen__qwen3-embedding-0.6b`（RAG/记忆）

```powershell
$env:JAVA_HOME='D:\jdk\jdk-17.0.12'
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' spring-boot:run
# 控制台: http://localhost:8600/  健康检查: http://localhost:8600/api/health
```

## API 清单

| 接口 | 说明 |
|---|---|
| `POST /api/agent/ask` | 工具编排问答（返回 answer / tool_calls / verifier_warnings / approvals） |
| `POST /api/rag/ask` / `GET /api/rag/retrieve` | RAG 问答 / 检索 |
| `POST /api/complaint/detect` | 投诉识别（9B 快路径，F1=1.0） |
| `GET /api/approval/pending\|all`、`POST /api/approval/{id}/grant\|deny` | 审批门查询与处置 |
| `GET /api/verifier/ledger` | 静默失败台账 |
| `GET /api/eval/cases` | 回归用例集（失败轨迹自动入库） |
| `POST /api/memory/add`、`GET /api/memory/recall` | 跨会话记忆写入 / 向量召回 |
| `GET /api/agent/tools` | 当前模板工具集检视（模板切换的结构验证入口） |
| `GET /api/tickets?kind=&status=` | 投诉工单/话术草稿查询（SQLite 落库留痕） |

## 行业模板（Runtime 复用性）

一个模板 = 一套 `Tool` Bean（`@ConditionalOnProperty` 条件装配）+ 一份 `TemplateDescriptor`（system prompt）。Runtime（AgentRunner / ApprovalGate / Verifier / Memory / eval）对模板零感知。

| 模板 | 启动 | 工具集 |
|---|---|---|
| `hotel`（默认） | 不带参数 | 房态 / 价格 / 订单 + 投诉升级、话术生成（2 敏感） |
| `restaurant` | `EH_TEMPLATE=restaurant` | 桌态 / 菜单价格 / 订单 / 会员脱敏 + 客诉升级、营销话术（2 敏感） |

新行业模板 = 在自己的包里实现 `Tool`（`ToolResult.standard/empty/error` 公开工厂）+ 一个 `TemplateDescriptor`，**不改任何 Runtime 代码**。验证：`GET /api/agent/tools` 检视（2026-10-30 实测双模板切换零泄漏，hotel 5 工具 / restaurant 6 工具）。

## MCP 数据源模式

`EH_MCP_ENABLED=true` 时，读类工具桥接 [mcp-server](mcp-server/README.md)（`EH_MCP_URL` 默认 `http://localhost:8601/mcp`）：
- name/description/inputSchema 以 MCP 侧为真源（数据源在 server 侧可切 demo / 外置 JSON / aurora 真实 PMS），主应用不再各存一份
- 写类工具（投诉/话术）按排除名单保留在运行时（审批门 + SQLite tickets 落库）
- 会话失效（server 重启）自动重初始化重试一次

## 私域渠道接入（M4）

通用 Webhook 入口 `POST /api/channel/webhook`：平台回调 → HMAC 验签（±5min 防重放）→ peer→确定性会话映射 → AgentRunner 同链路编排 → 文本回复。

```powershell
# 回环测试模式（不调模型，验证渠道全链路）
$env:EH_CHANNEL_ENABLED='true'; $env:EH_CHANNEL_ECHO='true'; $env:EH_CHANNEL_SECRET='test-secret-123'
# 真实模式（LLM 编排）：EH_CHANNEL_ECHO 不设或 false
```
请求头 `X-EdgeHotel-Timestamp` + `X-EdgeHotel-Signature`（HMAC-SHA256(secret, "{ts}.{rawBody}")）；载荷 `{peer, text, externalSessionId?}`（externalSessionId 细分同用户并发会话）。渠道关闭时端点 404 不暴露存在性。实测（2026-10-30）：回环 4/4 PASS（对签名/错签名/重放/缺参）。

**飞书适配器** `POST /api/channel/feishu/callback`（M4 第二增量）：复用官方 `FeishuCrypto`（AES 回调解密 + SHA-256 签名校验）——URL 验证（challenge 回显）/ v2 消息事件映射（open_id 为 peer，content 内嵌 JSON 二次解析，sender_type=app 自环防护）/ 消息落库审计 / echo 回环。全部可离线自测：`java scripts/GenFeishuFixture.java <encryptKey> '<明文JSON>'` 生成加密夹具（无需平台凭据），实测 5 场景（URL 验证/消息/错签名 401/自环防护/审计落库）全过。真实回复经飞书 IM API 出站需配 `EH_FEISHU_APP_ID/APP_SECRET`（缺省状态=pending_credentials，消息可审计）。

**企业微信适配器** `POST|GET /api/channel/wecom/callback`（M4 收尾）：复用官方 `WeComCrypto`（SHA-1 排序签名 + AES-CBC，EncodingAESKey 43 字符）——URL 验证（回解密明文）/ XML 信封解析（MsgType=text + FromUserName=peer）/ MsgId 幂等去重 / 消息落库审计 / echo 回环。离线自测：`java scripts/GenWeComFixture.java <token> <aesKey43> <receiveId> '<XML>'`，6 场景实测全过（URL 验证/消息/错签名 401/幂等/审计/计数器）。出站经企微 IM API 需 `EH_WECOM_CORP_ID/SECRET`（缺省 pending_credentials）。

**钉钉适配器** `POST /api/channel/dingtalk/callback`（M4 收尾）：复用官方 `DingTalkCallbackCrypto`（HMAC-SHA256 时间戳签名 + 可选 AES 信封）——peer 规则同官方（单聊 senderStaffId / 群聊 conversationId）、恒 200 语义（仅验签失败 401）、频控/审计/计数器接线。**出站走载荷内 sessionWebhook（平台签发地址）——三渠道中唯一零凭据开箱闭环**。离线自测：`java scripts/GenDingTalkFixture.java <appSecret>` 生成签名头，4 场景实测全过（单聊/群聊/非文本恒200/错签名 401）。

**渠道矩阵（4/4 全通）**：通用 Webhook / 飞书 / 企业微信 / 钉钉——验签、解密、事件映射、频控、审计、计数器统一模式，出站均为"配置即通、缺省 pending_credentials"（钉钉 sessionWebhook 零凭据直接闭环）。

## Docker 部署

```powershell
docker build -t edgehotel-agent:0.1 .        # 自包含构建（agentscope SNAPSHOT 经 m2-vendor/ 离线解析）
docker run -d -p 8600:8600 edgehotel-agent:0.1
# 或: docker compose up -d
```
实测（2026-10-30，Docker 29.9.2）：镜像 525MB，容器内启动 4.3s，health/tools/SQLite 数据卷（edgehotel-data:/app/data）全部验证通过。

## 新商家接入（< 1 天）

见 [`examples/hotel-onboard.md`](examples/hotel-onboard.md)：5 步接入 + 配置模板 + 出口验证清单。

## 数据与备份

- 数据落 `data/edgehotel.db`（SQLite 四表）；`.gitignore` 排除，备份走 `backup-edgehotel-db.ps1`（自动日期目录、保留 14 份）。
- 一键回归：`python scripts/eval-regression.py [--cases-only]` → 报告落 `data/regression-*.md`。

## 运维工具箱（M5/M6）

| 工具/端点 | 说明 |
|---|---|
| `GET /api/ops/overview` | 运行视图一屏聚合：模板/内存/模型加载态/渠道计数/工单存量 |
| `scripts/build.ps1` | 构建防线：停应用（防 jar 文件锁）→ mvn → 产物校验（大小/Main-Class/mtime），exit code 显式 |
| `scripts/load-model.ps1 -Model qwen3.5-9b` | 模型加载防线：可用内存/推理引擎红线前置拒绝 + 治理参数内置 |
| `scripts/nightly-guard.ps1` | 每日守卫：构建+MCP 冒烟+资源体检 → logs/（schtasks 注册命令见脚本头注释） |
| `scripts/sync-m2-vendor.py --verify` | vendor 完整性校验（构建前可跑） |
| 冒烟脚本参数化 | `mcp-smoke-http.py [base-url]` / `mcp-smoke-aurora-live.py [base-url]` |
| 频控 | 渠道 per-peer 每分钟限流 `EH_CHANNEL_RATE_LIMIT`（默认 30，超限 429） |
| 租户标记 | `EH_TENANT_ID` 贯穿 tickets/channel_messages 审计字段 |
| 备份恢复 | `backup-edgehotel-db.ps1` → 覆盖 data/edgehotel.db（2026-10-30 演练 PASS：标记行消失/数据完好） |
| 升级流程 | 停应用 → build.ps1 → 起新版 → /api/health + ops/overview 确认（数据卷不受影响） |

## 资源治理红线（ITGG 本机）

内存推理预算 ≤16GB、显存 ≤6.5GB、同驻推理模型 ≤2（日常 1）、单模型文件 ≤20GB；
LM Studio `max_tokens` 字段会导致空响应（一律省略）；推理请求应用侧串行（Semaphore(1)）。
详见 `ITGG资源治理与运行规范-2026-10.md` §八~§十。

## 验证记录（可审计）

- M1：`data/M1-1-工具层验证-2026-10-29.md`、`data/M1-2-防护三件套验证-2026-10-29.md`
- M2：`data/M2-验证记录-2026-10-29.md`
- RAG：`data/M0-RAG评估报告-2026-10-29.md`
