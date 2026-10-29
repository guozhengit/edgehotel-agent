package com.edgehotel.agent.agent.verifier;

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
 * 静默失败台账（M2-1 SQLite 版）：每次非 NORMAL 校验结论落库（session/tool/category/root_cause/recovery/ts），
 * 供事后对账与失败轨迹回灌 eval。重启不丢。
 */
@Component
public class SilentFailureLedger {

    private final Db db;

    public SilentFailureLedger(Db db) {
        this.db = db;
    }

    /** 记录一条失败；返回记录 id。 */
    public long record(String sessionId, String toolName, VerifyVerdict verdict, String rootCause) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO silent_failures(session_id,tool_name,category,root_cause,recovery,ts) VALUES(?,?,?,?,?,?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sessionId);
            ps.setString(2, toolName);
            ps.setString(3, verdict.category());
            ps.setString(4, rootCause);
            ps.setString(5, "人工复核后答复");
            ps.setString(6, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getLong(1) : -1;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("失败台账写入失败: " + e.getMessage(), e);
        }
    }

    /** 累计静默失败数（empty_result/blank_output）。 */
    public long silentFailureCount() {
        return count("category IN ('empty_result','blank_output')");
    }

    public long totalCount() {
        return count("1=1");
    }

    private long count(String where) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM silent_failures WHERE " + where)) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("失败台账计数失败: " + e.getMessage(), e);
        }
    }

    public List<FailureEntry> recent(int limit) {
        List<FailureEntry> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,session_id,tool_name,category,root_cause,recovery,ts FROM silent_failures "
                             + "ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new FailureEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)));
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("失败台账查询失败: " + e.getMessage(), e);
        }
    }

    public record FailureEntry(long id, String sessionId, String toolName, String category,
                               String rootCause, String recovery, String ts) {
    }
}
