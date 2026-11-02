package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Applies versioned migrations in order. Failures roll back; schema_info advances only on success.
 */
public final class SchemaMigrator {

    private final List<SchemaMigration> migrations;

    public SchemaMigrator(List<SchemaMigration> migrations) {
        this.migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
    }

    public int migrate(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        ensureSchemaInfo(connection);
        int current = readVersion(connection);
        for (SchemaMigration migration : migrations) {
            if (migration.targetVersion() <= current) {
                continue;
            }
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                migration.apply(connection);
                try (Statement s = connection.createStatement()) {
                    s.execute("DELETE FROM schema_info");
                    s.execute("INSERT INTO schema_info(version) VALUES(" + migration.targetVersion() + ")");
                }
                connection.commit();
                current = migration.targetVersion();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex instanceof SQLException sql ? sql
                        : new SQLException("Migration to v" + migration.targetVersion() + " failed", ex);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
        return current;
    }

    private static void ensureSchemaInfo(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_info(version INTEGER NOT NULL)");
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM schema_info LIMIT 1")) {
            if (!rs.next()) return 0;
            return rs.getInt(1);
        }
    }
}
