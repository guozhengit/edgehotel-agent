package com.edgehotel.agent.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 渠道消息落库（M4-2，可审计）：所有渠道入站/出站消息记 channel_messages 表。
 * 对齐治理"数据留痕可审计"——渠道对话的每一跳可回放。
 */
@Component
public class ChannelMessageStore {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessageStore.class);

    private final Db db;

    public ChannelMessageStore(Db db) {
        this.db = db;
    }

    /** 记录一条渠道消息（direction=inbound|outbound）。 */
    public void record(String channel, String peer, String direction, String sessionId, String content, String status) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO channel_messages(channel, peer, direction, session_id, content, status, ts) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, channel);
            ps.setString(2, peer);
            ps.setString(3, direction);
            ps.setString(4, sessionId);
            ps.setString(5, content);
            ps.setString(6, status);
            ps.setString(7, OffsetDateTime.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            // 审计落库失败不阻断主消息流，但必须留痕失败本身
            log.warn("channel_messages 落库失败: {}", e.getMessage());
        }
    }

    /** 查询（channel/peer/direction 可空，倒序最近 100 条）。 */
    public List<Map<String, Object>> list(String channel, String peer, String direction) {
        StringBuilder sql = new StringBuilder(
                "SELECT channel, peer, direction, session_id, content, status, ts FROM channel_messages WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND channel=?");
            params.add(channel);
        }
        if (peer != null && !peer.isBlank()) {
            sql.append(" AND peer=?");
            params.add(peer);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction=?");
            params.add(direction);
        }
        sql.append(" ORDER BY id DESC LIMIT 100");
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("channel", rs.getString("channel"));
                    m.put("peer", rs.getString("peer"));
                    m.put("direction", rs.getString("direction"));
                    m.put("session_id", rs.getString("session_id"));
                    m.put("content", rs.getString("content"));
                    m.put("status", rs.getString("status"));
                    m.put("ts", rs.getString("ts"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("渠道消息查询失败: " + e.getMessage(), e);
        }
        return out;
    }
}
