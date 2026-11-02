package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;

/**
 * Database-managed 1001+ identity allocation. Business code must not reference
 * sqlite_sequence or compute MAX(id)+1.
 */
public final class SqliteIdentitySequences {

    public static final long START_WITH = 1001L;
    public static final String PROJECTS = "projects";
    public static final String FILES = "files";
    public static final String MEASUREMENTS = "measurements";
    public static final String SIGNALS = "signals";
    public static final String RUNS = "runs";

    private static final Set<String> ENTITIES = Set.of(PROJECTS, FILES, MEASUREMENTS, SIGNALS, RUNS);

    private SqliteIdentitySequences() {
    }

    public static void bootstrap(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            for (String entity : ENTITIES) {
                String table = tableName(entity);
                s.execute("CREATE TABLE IF NOT EXISTS " + table + "("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT)");
                seedStartIfEmpty(s, table);
            }
        }
    }

    private static void seedStartIfEmpty(Statement s, String table) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next() && rs.getLong(1) > 0) {
                return;
            }
        }
        // AUTOINCREMENT remembers the highest inserted key. Seeding 1000 then deleting
        // makes the next generated id 1001 without application-level counters.
        s.executeUpdate("INSERT INTO " + table + "(id) VALUES(" + (START_WITH - 1) + ")");
        s.executeUpdate("DELETE FROM " + table + " WHERE id=" + (START_WITH - 1));
    }

    public static void adopt(Connection connection, String entity, long id) throws SQLException {
        if (id < START_WITH) {
            return;
        }
        String table = tableName(requireEntity(entity));
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO " + table + "(id) VALUES(?)")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public static long next(Connection connection, String entity) throws SQLException {
        String key = requireEntity(entity);
        String table = tableName(key);
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("INSERT INTO " + table + " DEFAULT VALUES");
            try (ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                if (!rs.next()) {
                    throw new SQLException("Identity allocation failed for " + key);
                }
                long id = rs.getLong(1);
                if (id < START_WITH) {
                    throw new SQLException("Identity sequence for " + key + " produced " + id);
                }
                return id;
            }
        }
    }

    private static String tableName(String entity) {
        return "identity_" + entity;
    }

    private static String requireEntity(String entity) {
        if (entity == null || !ENTITIES.contains(entity.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unknown identity entity: " + entity);
        }
        return entity.toLowerCase(Locale.ROOT);
    }
}
