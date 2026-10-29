package com.edgehotel.agent.controller;

import com.edgehotel.agent.agent.AgentRunner;
import com.edgehotel.agent.agent.TemplateDescriptor;
import com.edgehotel.agent.agent.Tool;
import com.edgehotel.agent.agent.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
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
 * GET /api/agent/tools：当前模板工具集检视（模板切换的结构验证入口）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentRunner runner;
    private final ToolRegistry registry;
    private final TemplateDescriptor template;

    public AgentController(AgentRunner runner, ToolRegistry registry, TemplateDescriptor template) {
        this.runner = runner;
        this.registry = registry;
        this.template = template;
    }

    /** 当前生效模板与工具集（含敏感标记）——换行业模板的结构验证入口。 */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("sensitive", t.isSensitive());
            m.put("description", t.description());
            out.add(m);
        }
        return Map.of("template", template.key(), "tools", out);
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
            sys.put("content", template.systemPrompt());
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
