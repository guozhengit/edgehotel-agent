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

## 新商家接入（< 1 天）

见 [`examples/hotel-onboard.md`](examples/hotel-onboard.md)：5 步接入 + 配置模板 + 出口验证清单。

## 数据与备份

- 数据落 `data/edgehotel.db`（SQLite 四表）；`.gitignore` 排除，备份走 `backup-edgehotel-db.ps1`（自动日期目录、保留 14 份）。
- 一键回归：`python scripts/eval-regression.py [--cases-only]` → 报告落 `data/regression-*.md`。

## 资源治理红线（ITGG 本机）

内存推理预算 ≤16GB、显存 ≤6.5GB、同驻推理模型 ≤2（日常 1）、单模型文件 ≤20GB；
LM Studio `max_tokens` 字段会导致空响应（一律省略）；推理请求应用侧串行（Semaphore(1)）。
详见 `ITGG资源治理与运行规范-2026-10.md` §八~§十。

## 验证记录（可审计）

- M1：`data/M1-1-工具层验证-2026-10-29.md`、`data/M1-2-防护三件套验证-2026-10-29.md`
- M2：`data/M2-验证记录-2026-10-29.md`
- RAG：`data/M0-RAG评估报告-2026-10-29.md`
