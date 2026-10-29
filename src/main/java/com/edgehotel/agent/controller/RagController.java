package com.edgehotel.agent.controller;

import com.edgehotel.agent.rag.RagRetriever;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * RAG 接口（M0 收官）：
 * <ul>
 *   <li>GET /api/rag/retrieve?q=…&amp;k=3 — 检索 Top-K（评估命中率用）</li>
 *   <li>POST /api/rag/ask — 检索 + 注入上下文 → Agent 生成对客回答</li>
 * </ul>
 */
@RestController
public class RagController {

    private final RagRetriever retriever;
    private final HarnessAgent agent;

    public RagController(RagRetriever retriever, HarnessAgent agent) {
        this.retriever = retriever;
        this.agent = agent;
    }

    /** 检索接口：返回 Top-K 命中文档（含分数），供 30 题评估统计命中率。 */
    @GetMapping("/api/rag/retrieve")
    public Mono<Map<String, Object>> retrieve(@RequestParam("q") String q,
                                              @RequestParam(value = "k", defaultValue = "3") int k) {
        // embedding 检索含阻塞 IO（block()），必须脱离 netty 事件循环线程
        return Mono.fromCallable(() -> {
            List<RagRetriever.Hit> hits = retriever.retrieve(q, k);
            return Map.of(
                    "question", q,
                    "hits", hits.stream()
                            .map(h -> Map.of("id", h.id(), "title", h.title(), "score", Math.round(h.score() * 10000) / 10000.0))
                            .toList()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** RAG 问答：检索 Top-3 注入 prompt → Agent 生成回答。会话隔离：每次用唯一 eval sessionId。 */
    @PostMapping("/api/rag/ask")
    public Mono<Map<String, Object>> ask(@RequestBody Map<String, String> req) {
        String question = req.getOrDefault("question", "");
        if (question.isBlank()) {
            return Mono.just(Map.of("error", "question 不能为空"));
        }
        return Mono.fromCallable(() -> {
            String prompt = buildRagPrompt(question);
            List<String> hits = retriever.retrieve(question, 3).stream().map(RagRetriever.Hit::id).toList();
            return Map.of("prompt", prompt, "hits", hits);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(pre -> {
            String prompt = (String) pre.get("prompt");
            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId("rag-eval-" + System.nanoTime())
                    .userId("rag-eval")
                    .build();
            @SuppressWarnings("unchecked")
            List<String> hits = (List<String>) pre.get("hits");
            return agent.streamEvents(prompt, ctx)
                    .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                    .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                    .collectList()
                    .map(chunks -> Map.of(
                            "question", question,
                            "answer", String.join("", chunks),
                            "hits", hits));
        });
    }

    private String buildRagPrompt(String question) {
        List<RagRetriever.Hit> hits = retriever.retrieve(question, 3);
        StringBuilder kb = new StringBuilder();
        for (RagRetriever.Hit h : hits) {
            kb.append(h.id()).append(" ").append(h.title()).append("：").append(h.text()).append("\n");
        }
        return """
                你是极光酒店（Aurora Hotel）的官方 AI 助手。请基于下面提供的【酒店资料】回答客人问题。\
                要求：直接给出简洁、礼貌、可直接对客的回复；资料中没有的信息，明确说"资料未提供，建议咨询前台"；不要编造。
                【酒店资料】
                %s
                【客人问题】
                %s
                """.formatted(kb, question);
    }
}
