package com.edgehotel.agent.store;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单/话术草稿持久化（M2 遗留真实化）：投诉升级工单与营销话术草稿落 SQLite tickets 表。
 * 投诉=OPEN 留痕待人工跟进；话术=DRAFT 待审批发送。aurora 侧暂无工单 API，故本地落库。
 */
@Component
public class TicketStore {

    private final Db db;

    public TicketStore(Db db) {
        this.db = db;
    }

    /** 新建工单/草稿，返回 ref（TKT-/DRAFT- 前缀）。 */
    public String create(String kind, String sessionId, String guest, String location, String content, String status) {
        String ref = ("complaint".equals(kind) ? "TKT-" : "DRAFT-")
                + Long.toHexString(System.currentTimeMillis()).toUpperCase() + "-"
                + String.valueOf(1000 + (System.nanoTime() % 9000));
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tickets(kind, ref, session_id, guest, location, content, status, ts) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, kind);
            ps.setString(2, ref);
            ps.setString(3, sessionId);
            ps.setString(4, guest);
            ps.setString(5, location);
            ps.setString(6, content);
            ps.setString(7, status);
            ps.setString(8, OffsetDateTime.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("工单落库失败: " + e.getMessage(), e);
        }
        return ref;
    }

    /** 查询（kind/status 可空表示不过滤，默认倒序最近 50 条）。 */
    public List<Map<String, Object>> list(String kind, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT kind, ref, session_id, guest, location, content, status, ts FROM tickets WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind=?");
            params.add(kind);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            params.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT 50");
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", rs.getString("kind"));
                    m.put("ref", rs.getString("ref"));
                    m.put("guest", rs.getString("guest"));
                    m.put("location", rs.getString("location"));
                    m.put("content", rs.getString("content"));
                    m.put("status", rs.getString("status"));
                    m.put("ts", rs.getString("ts"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("工单查询失败: " + e.getMessage(), e);
        }
        return out;
    }
}
