package com.edgehotel.agent.memory;

import java.util.List;

/**
 * 跨会话记忆（落地方案 §4 MemoryStore）：
 * add 写入（ADD-only 抽取），recall 按向量相似度检索 topK。
 */
public interface MemoryStore {

    /** ADD-only：写入一条记忆（自动 embedding）。返回记忆 id。 */
    long add(MemoryItem item);

    /** 混合检索：query 语义相似 + 同 scope 过滤，返回 topK 条（按相似度降序）。 */
    List<MemoryItem> recall(String scopeType, String scopeId, String query, int topK);
}
