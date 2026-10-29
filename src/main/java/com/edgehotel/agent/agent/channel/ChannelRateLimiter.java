package com.edgehotel.agent.agent.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道频控（M6-2）：per-peer 固定窗口限流，防单点滥用拖垮编排（对齐治理"同一时刻限流"思路）。
 * 窗口 60s；超限返回 false → 控制器 429。配置 channel.rate-limit-per-minute（0=关闭）。
 */
@Component
public class ChannelRateLimiter {

    private final int limitPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ChannelRateLimiter(@Value("${channel.rate-limit-per-minute:30}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    /** 允许该 peer 本分钟继续发消息？（计数递增在 compute 内完成，保证并发原子性） */
    public boolean allow(String peer) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long nowMinute = System.currentTimeMillis() / 60000;
        boolean[] allowed = {false};
        windows.compute(peer, (k, old) -> {
            Window w = old == null || old.minute != nowMinute ? new Window(nowMinute) : old;
            w.count++;
            allowed[0] = w.count <= limitPerMinute;
            return w;
        });
        // 懒清理：窗口表过大时移除上一分钟及更早的条目
        if (windows.size() > 1024) {
            windows.entrySet().removeIf(e -> e.getValue().minute < nowMinute);
        }
        return allowed[0];
    }

    private static final class Window {
        final long minute;
        int count;

        Window(long minute) {
            this.minute = minute;
        }
    }
}
