package com.edgehotel.agent.memory;

/**
 * 记忆项（落地方案 memories 表）：scope（如 hotel=aurora / user=u-xxx）+ content + 来源步骤 + 时间。
 */
public record MemoryItem(String scopeType, String scopeId, String content, String sourceStep, String ts) {
}
