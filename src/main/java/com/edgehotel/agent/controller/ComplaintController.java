package com.edgehotel.agent.controller;

import com.edgehotel.agent.agent.complaint.ComplaintDetector;
import com.edgehotel.agent.agent.complaint.ComplaintResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * M1-2 投诉识别接口：POST /api/complaint/detect {text}
 * 返回 {is_complaint, category, confidence, reason}。
 */
@RestController
@RequestMapping("/api/complaint")
public class ComplaintController {

    private final ComplaintDetector detector;

    public ComplaintController(ComplaintDetector detector) {
        this.detector = detector;
    }

    @PostMapping("/detect")
    public Mono<Map<String, Object>> detect(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        if (text.isBlank()) {
            return Mono.just(Map.of("error", "text 不能为空"));
        }
        return Mono.fromCallable(() -> {
            ComplaintResult r = detector.detect(text);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("is_complaint", r.isComplaint());
            result.put("category", r.category());
            result.put("confidence", r.confidence());
            result.put("reason", r.reason());
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
