package com.edgehotel.agent.agent.approval;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 审批记录（落地方案 approvals 表的内存版）：状态 pending → granted | denied，operator/ts 全程留痕。
 */
public record ApprovalRecord(String id, String sessionId, String toolName, String argsSummary,
                             String status, String operator, String reason, String ts) {

    public static final String PENDING = "pending";
    public static final String GRANTED = "granted";
    public static final String DENIED = "denied";

    public ApprovalRecord withStatus(String newStatus, String op, String note) {
        return new ApprovalRecord(id, sessionId, toolName, argsSummary, newStatus,
                op, note, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
}
