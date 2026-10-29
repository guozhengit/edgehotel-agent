package com.edgehotel.agent.store;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * SQLite 连接与建表（M2-1）：审批 / 失败台账 / 记忆 三张表，对应落地方案 §5 数据模型。
 * 库文件默认 data/edgehotel.db，启动时自动建目录与表（CREATE TABLE IF NOT EXISTS，幂等）。
 */
@Component
public class Db {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    private final String jdbcUrl;

    public Db(@Value("${app.db-path:data/edgehotel.db}") String dbPath) {
        try {
            Path p = Path.of(dbPath);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            this.jdbcUrl = "jdbc:sqlite:" + p.toAbsolutePath().normalize();
            initSchema();
            log.info("SQLite 就绪: {}", jdbcUrl);
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 初始化失败: " + e.getMessage(), e);
        }
    }

    private void initSchema() throws java.sql.SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS approvals (
                        id TEXT PRIMARY KEY,
                        session_id TEXT,
                        tool_name TEXT,
                        args_summary TEXT,
                        status TEXT,
                        operator TEXT,
                        reason TEXT,
                        ts TEXT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS silent_failures (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT,
                        tool_name TEXT,
                        category TEXT,
                        root_cause TEXT,
                        recovery TEXT,
                        ts TEXT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        scope_type TEXT,
                        scope_id TEXT,
                        content TEXT,
                        embedding BLOB,
                        source_step TEXT,
                        ts TEXT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS eval_cases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        trajectory_hash TEXT UNIQUE,
                        session_id TEXT,
                        tool_name TEXT,
                        args_summary TEXT,
                        category TEXT,
                        verdict TEXT,
                        recovery TEXT,
                        hits INTEGER DEFAULT 1,
                        ts TEXT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS tickets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind TEXT,
                        ref TEXT,
                        session_id TEXT,
                        guest TEXT,
                        location TEXT,
                        content TEXT,
                        status TEXT,
                        ts TEXT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS channel_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        channel TEXT,
                        peer TEXT,
                        direction TEXT,
                        session_id TEXT,
                        content TEXT,
                        status TEXT,
                        ts TEXT
                    )""");
            // M6-1 租户标记：存量库幂等补列（SQLite 无 ADD COLUMN IF NOT EXISTS）
            addColumnIfMissing(c, "tickets", "tenant");
            addColumnIfMissing(c, "channel_messages", "tenant");
            st.execute("CREATE INDEX IF NOT EXISTS idx_approvals_status ON approvals(status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_failures_category ON silent_failures(category)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories(scope_type, scope_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_eval_hash ON eval_cases(trajectory_hash)");
        }
    }

    /** 幂等补列：列已存在则忽略（SQLITE_ERROR 视为已存在）。 */
    private static void addColumnIfMissing(java.sql.Connection c, String table, String column) {
        try (java.sql.Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT");
        } catch (java.sql.SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("duplicate column")) {
                throw new IllegalStateException("补列失败 " + table + "." + column + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * 打开新连接（SQLite 单文件无连接池需求；调用方 try-with-resources 用完即关）。
     * 注意：禁止把本连接存为共享字段复用——会被 try-with-resources 关闭后失效。
     */
    public Connection open() throws java.sql.SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    @PreDestroy
    public void close() {
        // SQLite 连接按需开关，无全局连接可关；保留钩子便于未来切连接池。
    }
}
