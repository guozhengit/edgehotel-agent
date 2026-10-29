package com.edgehotel.agent.agent.approval;

import com.edgehotel.agent.store.Db;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批门（M2-1 SQLite 版，API 与 M1-2 一致）：敏感写操作默认拦截 → pending 落库；
 * 人工 grant/deny 更新状态并留痕（operator/时间）。重启不丢，可审计。
 */
@Component
public class ApprovalGate {

    private final Db db;

    public ApprovalGate(Db db) {
        this.db = db;
    }

    /** 提交审批。返回审批 id（形如 AP-0001，自增前缀）。 */
    public String submit(String sessionId, String toolName, String argsSummary) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(CAST(SUBSTR(id,4) AS INTEGER)),0) AS n FROM approvals")) {
            ResultSet rs = ps.executeQuery();
            int n = rs.next() ? rs.getInt("n") : 0;
            String id = String.format("AP-%04d", n + 1);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO approvals(id,session_id,tool_name,args_summary,status,operator,reason,ts) VALUES(?,?,?,?,?,?,?,?)")) {
                ins.setString(1, id);
                ins.setString(2, sessionId);
                ins.setString(3, toolName);
                ins.setString(4, argsSummary);
                ins.setString(5, ApprovalRecord.PENDING);
                ins.setString(6, null);
                ins.setString(7, null);
                ins.setString(8, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                ins.executeUpdate();
            }
            return id;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("审批提交失败: " + e.getMessage(), e);
        }
    }

    /** 人工放行；仅 pending 可操作。返回是否成功。 */
    public boolean grant(String id, String operator) {
        return decide(id, operator, null, ApprovalRecord.GRANTED);
    }

    /** 人工拒绝；仅 pending 可操作。返回是否成功。 */
    public boolean deny(String id, String operator, String reason) {
        return decide(id, operator, reason, ApprovalRecord.DENIED);
    }

    private boolean decide(String id, String operator, String reason, String newStatus) {
        try (Connection c = db.open();
             PreparedStatement upd = c.prepareStatement(
                     "UPDATE approvals SET status=?, operator=?, reason=?, ts=? WHERE id=? AND status='pending'")) {
            upd.setString(1, newStatus);
            upd.setString(2, operator);
            upd.setString(3, reason == null ? "人工放行" : reason);
            upd.setString(4, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            upd.setString(5, id);
            return upd.executeUpdate() > 0;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("审批操作失败: " + e.getMessage(), e);
        }
    }

    /** 按 id 取审批记录（放行后执行工具用，e2e 发现的闭环缺口）。 */
    public ApprovalRecord find(String id) {
        return all().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    public List<ApprovalRecord> pending() {
        return query("status='pending'");
    }

    public List<ApprovalRecord> all() {
        return query("1=1");
    }

    private List<ApprovalRecord> query(String where) {
        List<ApprovalRecord> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,session_id,tool_name,args_summary,status,operator,reason,ts FROM approvals WHERE "
                             + where + " ORDER BY id DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new ApprovalRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("审批查询失败: " + e.getMessage(), e);
        }
    }

    /** 审批覆盖率（出口标准：100% 敏感操作有审批记录）。 */
    public long totalRecorded() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM approvals")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("审批计数失败: " + e.getMessage(), e);
        }
    }
}
