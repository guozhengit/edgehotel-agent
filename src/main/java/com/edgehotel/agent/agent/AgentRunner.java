package com.edgehotel.agent.agent;

import com.edgehotel.agent.agent.approval.ApprovalGate;
import com.edgehotel.agent.agent.verifier.SilentFailureLedger;
import com.edgehotel.agent.agent.verifier.ToolVerifier;
import com.edgehotel.agent.agent.verifier.VerifyVerdict;
import com.edgehotel.agent.memory.MemoryStore;
import com.edgehotel.agent.store.EvalCaseStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M1 工具编排循环（对齐落地方案 Agent Core：规划→工具→验证→审批）：
 * 调 LM Studio /v1/chat/completions（带 tools）→ 解析 tool_calls → 执行/拦截 → Verifier 校验 → 回填 → 再调。
 *
 * <p>M1-2 挂载：
 * - Approval Gate：isSensitive() 工具不直接执行，生成审批记录并回填"已提交人工审批"；
 * - Verifier：每条工具结果过校验，EMPTY/空白 → 静默失败台账 + 回填提示人工确认。
 */
@Component
public class AgentRunner {

    private final WebClient client;
    private final String model;
    private final ToolRegistry registry;
    private final int maxRounds;
    private final long chatTimeoutSeconds;
    private final ToolVerifier verifier;
    private final SilentFailureLedger ledger;
    private final ApprovalGate approvalGate;
    private final MemoryStore memoryStore;
    private final EvalCaseStore evalCaseStore;
    /** 推理限流（治理红线 §六.1：同一时刻仅 1 个推理请求，LM Studio 并发会叠加 KV cache 并可能 500）。 */
    private final java.util.concurrent.Semaphore inferenceLock = new java.util.concurrent.Semaphore(1);

    public AgentRunner(ToolRegistry registry,
                       ToolVerifier verifier,
                       SilentFailureLedger ledger,
                       ApprovalGate approvalGate,
                       MemoryStore memoryStore,
                       EvalCaseStore evalCaseStore,
                       @Value("${agent.model}") String model,
                       @Value("${agent.base-url}") String baseUrl,
                       @Value("${agent.max-tool-rounds:3}") int maxRounds,
                       @Value("${agent.chat-timeout-seconds:120}") long chatTimeoutSeconds) {
        this.registry = registry;
        this.verifier = verifier;
        this.ledger = ledger;
        this.approvalGate = approvalGate;
        this.memoryStore = memoryStore;
        this.evalCaseStore = evalCaseStore;
        this.model = model;
        this.maxRounds = maxRounds;
        this.chatTimeoutSeconds = chatTimeoutSeconds;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    public record AgentTurn(List<Map<String, Object>> messages, List<String> toolCalls, String finalAnswer,
                            List<String> verifierWarnings, List<String> approvals) {
    }

    /** 执行编排循环。messages 初始含 system+user；返回最终文本与工具调用轨迹。 */
    public AgentTurn run(List<Map<String, Object>> messages, Tool.ToolContext ctx) {
        // M2-2 记忆注入：客人维度向量召回 topK，追加进 system 消息（记忆不可用不阻断主流程）
        injectMemory(messages, ctx);

        // 追加 /no_think：Qwen3 关思考（最后一个 user 消息尾部）
        Map<String, Object> last = messages.get(messages.size() - 1);
        Object content = last.get("content");
        if (content instanceof String s) {
            last = new java.util.LinkedHashMap<>(last);
            last.put("content", s + " /no_think");
            messages.set(messages.size() - 1, last);
        }

        List<String> toolCalls = new ArrayList<>();
        List<String> verifierWarnings = new ArrayList<>();
        List<String> approvals = new ArrayList<>();
        for (int round = 0; round < maxRounds; round++) {
            Map<String, Object> resp = chat(messages);
            Map<String, Object> choice = firstChoice(resp);
            Map<String, Object> msg = (Map<String, Object>) choice.get("message");
            List<Map<String, Object>> calls = (List<Map<String, Object>>) msg.get("tool_calls");
            if (calls == null || calls.isEmpty()) {
                Object contentObj = msg.get("content");
                String answer = contentObj == null ? "" : contentObj.toString();
                return new AgentTurn(messages, toolCalls, answer, verifierWarnings, approvals);
            }
            // 记录并回填工具调用
            Map<String, Object> assistantMsg = new java.util.LinkedHashMap<>(msg);
            messages.add(assistantMsg);
            for (Map<String, Object> call : calls) {
                Map<String, Object> fn = (Map<String, Object>) call.get("function");
                String name = String.valueOf(fn.get("name"));
                String argsJson = String.valueOf(fn.get("arguments"));
                String callId = String.valueOf(call.get("id"));
                toolCalls.add(name + "(" + argsJson + ")");
                Map<String, Object> toolMsg = new java.util.LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                var toolOpt = registry.find(name);
                if (toolOpt.isEmpty()) {
                    toolMsg.put("content", "工具不存在: " + name);
                } else if (toolOpt.get().isSensitive()) {
                    // Approval Gate：写/外发/费用类默认拦截，不直接执行（100% 留痕）
                    String apId = approvalGate.submit(ctx.sessionId(), name, argsJson);
                    approvals.add(apId + ":" + name + ":" + argsJson);
                    toolMsg.put("content", "【审批门】操作 " + name + " 属于敏感写操作，已提交人工审批（审批ID="
                            + apId + "）。在放行前不得继续执行该操作，请回复客人已转交人工跟进。");
                } else {
                    Tool.ToolResult result = executeTool(name, argsJson, ctx);
                    VerifyVerdict verdict = verifier.verify(name, result, ctx);
                    if (verdict.isFailure()) {
                        ledger.record(ctx.sessionId(), name, verdict, verdict.reason());
                        // M3-1 失败即资产：失败轨迹自动入回归用例集（hash 去重）
                        evalCaseStore.record(ctx.sessionId(), name, argsJson, verdict.category(),
                                verdict.verdict().name(), verdict.reason());
                        if (verdict.verdict() == VerifyVerdict.Verdict.SILENT_FAILURE) {
                            verifierWarnings.add(name + ":" + verdict.reason());
                        }
                        toolMsg.put("content", (result.ok() ? result.output() : "工具执行失败: " + result.error())
                                + " 【校验警告 " + verdict.category() + "】" + verdict.reason());
                    } else {
                        toolMsg.put("content", result.output());
                    }
                }
                messages.add(toolMsg);
            }
        }
        return new AgentTurn(messages, toolCalls, "工具调用轮数超限（" + maxRounds + "），请重试或换表述。",
                verifierWarnings, approvals);
    }

    /** 记忆注入：以 guest:userId 为 scope 召回客人历史记忆，追加到 system 消息尾部。 */
    private void injectMemory(List<Map<String, Object>> messages, Tool.ToolContext ctx) {
        try {
            var items = memoryStore.recall("guest", ctx.userId(), "客人历史偏好、诉求与沟通要点", 5);
            if (items == null || items.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder("\n【客人已知信息·记忆库（跨会话，供本次参考）】\n");
            for (var it : items) {
                sb.append("- ").append(it.content()).append("\n");
            }
            for (int i = 0; i < messages.size(); i++) {
                if ("system".equals(messages.get(i).get("role"))) {
                    Map<String, Object> sys = new java.util.LinkedHashMap<>(messages.get(i));
                    sys.put("content", String.valueOf(sys.get("content")) + sb);
                    messages.set(i, sys);
                    return;
                }
            }
        } catch (Exception e) {
            // 记忆引擎不可用时静默跳过，不阻断编排主流程
        }
    }

    private Tool.ToolResult executeTool(String name, String argsJson, Tool.ToolContext ctx) {        var toolOpt = registry.find(name);
        if (toolOpt.isEmpty()) {
            return Tool.ToolResult.error("工具不存在: " + name);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new ObjectMapper().readValue(argsJson, Map.class);
            return toolOpt.get().run(args, ctx);
        } catch (Exception e) {
            return Tool.ToolResult.error("工具参数解析失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chat(List<Map<String, Object>> messages) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "tools", registry.toOpenAITools(),
                "temperature", 0.3);
        try {
            inferenceLock.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("推理限流被中断", e);
        }
        try {
            // 带超时的 block：LM Studio 慢/挂起时不再无限期持有推理信号量（饿死后续请求）
            return client.post().uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(chatTimeoutSeconds));
        } catch (java.lang.IllegalStateException e) {
            throw new IllegalStateException("模型推理超时(" + chatTimeoutSeconds + "s，单轮)——请稍后重试或检查 LM Studio 状态", e);
        } finally {
            inferenceLock.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstChoice(Map<String, Object> resp) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        return choices.get(0);
    }
}
