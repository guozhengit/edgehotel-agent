package com.edgehotel.agent.controller;

import com.edgehotel.agent.web.ChatRequest;
import com.edgehotel.agent.web.ChatResponse;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 对话入口：POST /api/chat（完整回答）+ GET /api/health。
 *
 * <p>HarnessAgent 在调用间无状态，同一实例并发处理不同 (userId, sessionId)；
 * 会话状态由 RuntimeContext 隔离，自动持久化到工作区。
 */
@RestController
public class ChatController {

    private final HarnessAgent agent;

    public ChatController(HarnessAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/api/chat")
    public Mono<ChatResponse> chat(@RequestBody ChatRequest req) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(req.sessionId() == null ? "default" : req.sessionId())
                .userId(req.userId() == null ? "anonymous" : req.userId())
                .build();

        return agent.streamEvents(req.message(), ctx)
                .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .collectList()
                .map(chunks -> new ChatResponse(String.join("", chunks)));
    }

    @GetMapping("/api/health")
    public Mono<String> health() {
        return Mono.just("OK");
    }
}
