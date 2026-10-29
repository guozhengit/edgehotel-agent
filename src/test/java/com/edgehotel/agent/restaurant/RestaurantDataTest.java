package com.edgehotel.agent.agent.restaurant;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 餐饮示例数据查询单元测试（含未知日期/未知菜品/未知订单/未知会员的 null 语义——静默失败防线的输入契约）。 */
class RestaurantDataTest {

    private final RestaurantData data = new RestaurantData();

    @Test
    void 桌态_已知日期返回分桌数据() {
        Map<String, Integer> tables = data.tables("2026-10-30", "19:00");
        assertEquals(0, tables.get("four")); // 19:00 四人桌剩 0（18:00 才是 2）
        assertEquals(1, tables.get("private"));
    }

    @Test
    void 桌态_缺省时段返回全天汇总() {
        Map<String, Integer> merged = data.tables("2026-10-30", null);
        // 18:00(4) + 19:00(1) + 20:00(6) = 11
        assertEquals(11, merged.get("deuce"));
        // 18:00(2) + 19:00(0) + 20:00(3) = 5
        assertEquals(5, merged.get("four"));
    }

    @Test
    void 桌态_未知日期返回null() {
        assertNull(data.tables("2024-01-01", null));
    }

    @Test
    void 菜品与订单与会员查询() {
        assertEquals(128, data.dish("金汤酸菜鱼").get("price"));
        assertEquals("已预订", data.order("R20261001").get("status"));
        assertEquals("黑金卡", data.member("M-2001").get("level"));
    }

    @Test
    void 未知名目返回null() {
        assertNull(data.dish("不存在的菜"));
        assertNull(data.order("R-不存在"));
        assertNull(data.member("M-不存在"));
    }

    @Test
    void 工具结果契约_空结果必须是EMPTY供verifier判定() {
        // 静默失败防线的输入契约：查询无结果必须走 ToolResult.empty()（EMPTY 状态）
        assertTrue(com.edgehotel.agent.agent.Tool.ToolResult.empty().status().equals("EMPTY"));
    }
}
