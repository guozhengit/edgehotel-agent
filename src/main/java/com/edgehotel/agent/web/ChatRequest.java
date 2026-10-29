package com.edgehotel.agent.web;

/**
 * 对话请求体。
 *
 * @param message   用户消息
 * @param sessionId 会话 ID（默认 "default"，同 ID 自动续聊）
 * @param userId    用户 ID（默认 "anonymous"）
 */
public record ChatRequest(String message, String sessionId, String userId) {
}
