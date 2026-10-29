package com.edgehotel.agent.controller;

import com.edgehotel.agent.memory.MemoryItem;
import com.edgehotel.agent.memory.MemoryStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M2-2 跨会话记忆接口：
 * POST /api/memory/add   {scope_type, scope_id, content, source_step?} → {memory_id}
 * GET  /api/memory/recall?scope_type=&scope_id=&query=&top_k=        → {items:[...]}
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryStore store;

    public MemoryController(MemoryStore store) {
        this.store = store;
    }

    @PostMapping("/add")
    public Mono<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        String scopeType = body.getOrDefault("scope_type", "hotel");
        String scopeId = body.getOrDefault("scope_id", "aurora");
        String content = body.getOrDefault("content", "");
        if (content.isBlank()) {
            return Mono.just(Map.of("error", "content 不能为空"));
        }
        String sourceStep = body.getOrDefault("source_step", null);
        return Mono.fromCallable(() -> {
            long id = store.add(new MemoryItem(scopeType, scopeId, content, sourceStep, null));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("memory_id", id);
            return r;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/recall")
    public Mono<Map<String, Object>> recall(@RequestParam(defaultValue = "hotel") String scope_type,
                                            @RequestParam(defaultValue = "aurora") String scope_id,
                                            @RequestParam String query,
                                            @RequestParam(defaultValue = "3") int top_k) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return Mono.just(Map.of("error", "query 不能为空"));
        }
        return Mono.fromCallable(() -> {
            var items = store.recall(scope_type, scope_id, q, top_k);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scope", scope_type + ":" + scope_id);
            r.put("query", q);
            r.put("items", items);
            return r;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
