package com.edgehotel.agent.store;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * M3-1 失败轨迹→回归用例（落地方案 §5 eval_cases 表 / 设计原则 4"失败即资产"）：
 * AgentRunner 每次工具失败（静默/显式）自动写一条去重回归用例（trajectory_hash 幂等去重，
 * 命中次数递增）。回归脚本按该表反向验证失败是否仍复现。
 */
@Component
public class EvalCaseStore {

    public record EvalCase(long id, String trajectoryHash, String sessionId, String toolName,
                           String argsSummary, String category, String verdict, String recovery,
                           int hits, String ts) {
    }

    private final Db db;

    public EvalCaseStore(Db db) {
        this.db = db;
    }

    /** 失败轨迹自动入回归集；同轨迹（工具+参数，与具体会话无关）只计数不重复入库。 */
    public void record(String sessionId, String toolName, String argsSummary, String category,
                       String verdict, String recovery) {
        String hash = hash(toolName, argsSummary);
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO eval_cases(trajectory_hash,session_id,tool_name,args_summary,category,verdict,recovery,hits,ts) "
                            + "VALUES(?,?,?,?,?,?,?,1,?)"
                            + " ON CONFLICT(trajectory_hash) DO UPDATE SET hits=hits+1")) {
                ps.setString(1, hash);
                ps.setString(2, sessionId);
                ps.setString(3, toolName);
                ps.setString(4, argsSummary);
                ps.setString(5, category);
                ps.setString(6, verdict);
                ps.setString(7, recovery);
                ps.setString(8, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                ps.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("回归用例入库失败: " + e.getMessage(), e);
        }
    }

    public List<EvalCase> list() {
        List<EvalCase> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,trajectory_hash,session_id,tool_name,args_summary,category,verdict,recovery,hits,ts "
                             + "FROM eval_cases ORDER BY hits DESC, id DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new EvalCase(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getInt(9), rs.getString(10)));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("回归用例读取失败: " + e.getMessage(), e);
        }
        return out;
    }

    private static String hash(String toolName, String argsSummary) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest((toolName + "|" + argsSummary).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("轨迹哈希失败", e);
        }
    }
}
