package com.edgehotel.agent.agent.channel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 渠道计数器（复盘 P1：静默丢弃必须有声音）：渠道层每个决策点（接收/丢弃/回复）计数，
 * 经 GET /api/channel/stats 暴露——"确认收到但没处理"从此可观测。
 */
@Component
public class ChannelStats {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void inc(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }
}
