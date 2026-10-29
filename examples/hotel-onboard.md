# 新商家接入指南（目标：< 1 天）

本文档把一个"新酒店商家"从零接入 EdgeHotel-Agent 的过程压缩为 5 步。
出口验证清单在文末，全部通过即视为接入完成。

## 前置条件

| 项 | 要求 |
|---|---|
| 主机 | 单台 Windows/Linux，8GB 显存（或 32GB 内存纯 CPU）、≥30GB 磁盘余量 |
| 推理服务 | LM Studio（Windows）或等效 OpenAI 兼容端点（Linux 可用 llama.cpp server） |
| 模型 | 骨干 30B MoE（工具编排）+ 9B（快路径）+ 嵌入 0.6B（RAG/记忆）；合计磁盘约 25GB |
| 运行时 | JDK 17 + Maven 3.9（或直接使用打包 jar） |
| 知识库 | 酒店手册/设施/入住须知 Markdown（`data/hotel-knowledge-base.md` 替换） |

## 5 步接入

### 1. 复制工程并改配置

```powershell
# 以新商家目录接入（例如 bj-guomao-hotel）
Copy-Item -Recurse D:\AI\edgehotel-agent D:\hotels\bj-guomao-hotel
```

编辑 `src/main/resources/application.yml`（商家专属配置项）：

```yaml
server:
  port: 8611          # EdgeHotel 系 86xx 段，避免撞已有项目（80xx-84xx 已被占用）
rag:
  embedding:
    model: "text-embedding-qwen__qwen3-embedding-0.6b"
agent:
  model: "qwen3-30b-a3b"        # 骨干；轻量商家可换 9B
complaint:
  model: "qwen/qwen3.5-9b"      # 快路径
app:
  db-path: "data/edgehotel.db"  # 每商家独立 SQLite 库（天然租户隔离）
```

### 2. 替换商家知识库

将本店手册整理为 Markdown（章节标题 + 条目），替换 `data/hotel-knowledge-base.md`（启动自动加载建索引，30 题评估集同步替换为店内 QA）。

### 3. 加载模型（LM Studio）

```powershell
lms load "qwen3-30b-a3b" --context-length 8192 --gpu 0.3 --ttl 1800 -y
lms load "qwen/qwen3.5-9b" --context-length 8192 --gpu 0.9 --ttl 1800 -y
lms load "text-embedding-qwen__qwen3-embedding-0.6b" --context-length 4096 --gpu 0.1 --ttl 900 -y
```

治理红线：同驻推理模型 ≤2（骨干+9B 已到上限），嵌入模型轻量可同驻；推理请求应用侧自动串行。

### 4. 启动

```powershell
$env:JAVA_HOME='D:\jdk\jdk-17.0.12'
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -f pom.xml spring-boot:run
# 或打包后: java -jar target/edgehotel-agent.jar
```

### 5. 接入控制台与收尾

- 打开 `http://localhost:8611/`（问答/审批门/台账/投诉识别/记忆 5 面板）
- 建首个测试会话：问房态 → 触发投诉 → 审批放行 → 写一条客人记忆 → 重启后 recall 验证跨会话

## 出口验证清单（全部通过 = 接入完成）

- [ ] `GET /api/health` 返回 OK
- [ ] 房态/房价/订单 3 个读工具可答（数据转述 + "以官方渠道为准"提示）
- [ ] 投诉识别对"空调坏了要投诉"判定为投诉（F1≥0.8）
- [ ] 投诉升级/话术生成被审批门拦截，`GET /api/approval/pending` 可见 AP 记录
- [ ] 未知日期房态查询触发 Verifier 警告且**不会**回答"可订"（静默失败防护）
- [ ] 写记忆 → `GET /api/memory/recall` 语义命中
- [ ] 一键回归：`python scripts/eval-regression.py` → 静默失败率 <5% PASS

## 常见坑（已踩平）

| 坑 | 处理 |
|---|---|
| LM Studio 请求带 `max_tokens` → 空 content | 所有请求一律省略该字段（框架已处理） |
| LM Studio 并发请求 → 500 | 应用侧 Semaphore(1) 串行化（框架已处理） |
| SQLite 共享连接被关闭 | `Db.open()` 按需开关（框架已处理） |
| 端口 80xx-84xx 已占用 | 新商家一律用 86xx 段 |


## M5/M6 增补（2026-10-30）

- **.env 接入**：复制 `.env.example` → `.env`，按商家填 `EH_TENANT_ID`（审计标记）与渠道/MCP 变量，`docker compose --env-file .env up -d`。
- **试点建议**：`EH_PREFLIGHT=enforce`（模型未加载/内存红线直接拒绝启动）；非本机部署必配 `EH_MCP_AUTH_TOKEN` 与 `EH_CHANNEL_SECRET`。
- **MCP Server 容器**：`docker compose --profile mcp --env-file .env up -d`（HTTP 8601，auth 冒烟 9/9）。
- **升级流程**：`scripts/build.ps1`（自动停应用+产物校验）→ 重新 up → `GET /api/health` + `GET /api/ops/overview` 确认；数据卷持久不受影响。
- **每日守卫**：按 `scripts/nightly-guard.ps1` 头注释注册计划任务（构建+冒烟+体检，日志落 logs/）。
