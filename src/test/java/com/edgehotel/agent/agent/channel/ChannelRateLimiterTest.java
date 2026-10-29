package com.edgehotel.agent.agent.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 频控窗口行为单元测试（限流语义：首放行、超限拒绝、0=关闭）。 */
class ChannelRateLimiterTest {

    @Test
    void 超限后拒绝() {
        ChannelRateLimiter limiter = new ChannelRateLimiter(2);
        assertTrue(limiter.allow("peer-a"));
        assertTrue(limiter.allow("peer-a"));
        assertFalse(limiter.allow("peer-a")); // 第 3 次/分钟 → 拒
    }

    @Test
    void 不同peer互不影响() {
        ChannelRateLimiter limiter = new ChannelRateLimiter(1);
        assertTrue(limiter.allow("peer-a"));
        assertFalse(limiter.allow("peer-a"));
        assertTrue(limiter.allow("peer-b"));
    }

    @Test
    void 零配置关闭限流() {
        ChannelRateLimiter limiter = new ChannelRateLimiter(0);
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.allow("peer-a"));
        }
    }
}
