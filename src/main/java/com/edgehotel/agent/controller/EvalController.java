package com.edgehotel.agent.controller;

import com.edgehotel.agent.store.EvalCaseStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3-1 回归用例集接口：
 * GET /api/eval/cases → {total, cases:[...]}（失败轨迹自动入库，hash 去重）
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalCaseStore store;

    public EvalController(EvalCaseStore store) {
        this.store = store;
    }

    @GetMapping("/cases")
    public Mono<Map<String, Object>> cases() {
        return Mono.fromCallable(() -> {
            var list = store.list();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("total", list.size());
            r.put("cases", list);
            return r;
        });
    }
}
