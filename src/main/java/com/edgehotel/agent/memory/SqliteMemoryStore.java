package com.edgehotel.agent.memory;

import com.edgehotel.agent.rag.EmbeddingClient;
import com.edgehotel.agent.store.Db;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 记忆实现（M2-2）：content 经 Qwen3-Embedding 转 1024 维向量存 BLOB；
 * recall 时对 query 向量与 scope 内全部记忆做余弦相似度排序取 topK。
 * 语义检索精度足以支撑"客人偏好/上次沟通结论"这类跨会话召回（M0-RAG 命中@1=86.7% 同引擎）。
 */
@Component
public class SqliteMemoryStore implements MemoryStore {

    private final Db db;
    private final EmbeddingClient embedding;

    public SqliteMemoryStore(Db db, EmbeddingClient embedding) {
        this.db = db;
        this.embedding = embedding;
    }

    @Override
    public long add(MemoryItem item) {
        // 优雅降级：embedding 模型不可用时仍落库（embedding=NULL），内容不丢；
        // recall 跳过 NULL 向量行，模型恢复后重新 add 即可补向量
        float[] vec;
        try {
            vec = embedding.embed(item.content());
        } catch (Exception e) {
            vec = null;
            org.slf4j.LoggerFactory.getLogger(SqliteMemoryStore.class)
                    .warn("记忆 embedding 失败（内容仍落库，向量待补）: {}", e.getMessage());
        }
        byte[] blob = vec == null ? null : toBytes(vec);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO memories(scope_type,scope_id,content,embedding,source_step,ts) VALUES(?,?,?,?,?,?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, item.scopeType());
            ps.setString(2, item.scopeId());
            ps.setString(3, item.content());
            ps.setBytes(4, blob);
            ps.setString(5, item.sourceStep());
            ps.setString(6, item.ts() == null
                    ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : item.ts());
            if (blob == null) {
                ps.setNull(4, java.sql.Types.BLOB);
            } else {
                ps.setBytes(4, blob);
            }
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getLong(1) : -1;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("记忆写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<MemoryItem> recall(String scopeType, String scopeId, String query, int topK) {
        float[] q;
        try {
            q = embedding.embed(query);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(SqliteMemoryStore.class)
                    .warn("记忆检索 embedding 失败，返回空结果（不阻断调用方）: {}", e.getMessage());
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content,embedding,source_step,ts FROM memories WHERE scope_type=? AND scope_id=? "
                             + "ORDER BY id DESC LIMIT 500")) {
            ps.setString(1, scopeType);
            ps.setString(2, scopeId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String content = rs.getString(1);
                float[] v = fromBytes(rs.getBytes(2)); // NULL 向量行（降级落库）跳过
                if (v == null) {
                    continue;
                }
                scored.add(new Scored(content, rs.getString(3), rs.getString(4), cosine(q, v)));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("记忆检索失败: " + e.getMessage(), e);
        }
        return scored.stream().sorted((a, b) -> Float.compare(b.score, a.score))
                .limit(topK)
                .map(s -> new MemoryItem(scopeType, scopeId, s.content, s.sourceStep, s.ts))
                .toList();
    }

    private record Scored(String content, String sourceStep, String ts, float score) {
    }

    private static float cosine(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static byte[] toBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    private static float[] fromBytes(byte[] b) {
        if (b == null) {
            return new float[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }
}
