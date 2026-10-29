package com.edgehotel.agent.controller;

import com.edgehotel.agent.agent.AgentRunner;
import com.edgehotel.agent.agent.Tool;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具编排接口（M1-1）：POST /api/agent/ask
 * 输入 {question, userId?, sessionId?} → 模型规划 → 工具执行 → 回填 → 最终回答。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentRunner runner;

    public AgentController(AgentRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/ask")
    public Mono<Map<String, Object>> ask(@RequestBody Map<String, String> req) {
        String question = req.getOrDefault("question", "");
        if (question.isBlank()) {
            return Mono.just(Map.of("error", "question 不能为空"));
        }
        String userId = req.getOrDefault("userId", "agent-eval");
        String sessionId = req.getOrDefault("sessionId", "agent-" + System.nanoTime());
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", "你是极光酒店（Aurora Hotel）的官方 AI 助手。回答客人问题时，如果涉及房态、房价、订单状态，"
                    + "必须先用提供的工具查询（工具返回均标注“示例”，如实转述并提示以官方渠道为准）；不要编造价格与房态。"
                    + "日期一律使用今天（2026-10-29）或客人明确提供的日期，严禁虚构其他年份的日期；"
                    + "客人要求升级投诉/人工处理时，使用 complaint_escalate 工具；"
                    + "客人要求发送营销话术/欢迎语/活动文案时，使用 marketing_copy_generate 工具（生成草稿，发送前须审批）。");
            messages.add(sys);
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("role", "user");
            user.put("content", question);
            messages.add(user);
            AgentRunner.AgentTurn turn = runner.run(messages, new Tool.ToolContext(userId, sessionId));
            return Map.of(
                    "question", question,
                    "answer", turn.finalAnswer(),
                    "tool_calls", turn.toolCalls(),
                    "verifier_warnings", turn.verifierWarnings(),
                    "approvals", turn.approvals());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
