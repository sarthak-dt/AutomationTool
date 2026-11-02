package com.stellar.platform.storage.metadata;

import com.stellar.platform.storage.sqlite.SqliteIdentitySequences;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Imports a legacy project-local {@code .stellar/stellar.db} into the central database.
 * The legacy file is opened read-only and is never modified.
 */
public final class LegacyProjectImporter {

    private static final List<String> PROJECT_SCOPED_TABLES = List.of(
            "project_files",
            "source_inventory",
            "channel_inventory",
            "channel_messages",
            "channel_mappings",
            "processing_runs",
            "processing_artifacts",
            "processing_jobs",
            "processing_batches",
            "processing_checkpoints",
            "processing_errors",
            "signal_catalog",
            "engineering_bookmarks",
            "evaluation_rules",
            "evaluation_results",
            "data_quality_findings",
            "semantic_signal_mapping",
            "map_route_state",
            "graph_investigation",
            "graph_annotations",
            "audit_log",
            "export_jobs",
            "analysis_runs",
            "analysis_findings",
            "derived_signal_definitions",
            "analysis_workspace",
            "diagnostic_rule_packs",
            "diagnostic_runs",
            "diagnostic_findings",
            "diagnostic_bindings",
            "diagnostic_workspace",
            "diagnostic_finding_reviews",
            "diagnostic_rule_preferences",
            "engineering_investigations",
            "investigation_reports",
            "investigation_workspace"
    );

    /**
     * Child tables scoped by a parent id rather than {@code project_id}.
     * Copied after the parent project-owned rows exist.
     */
    private static final List<ChildTable> CHILD_TABLES = List.of(
            new ChildTable("investigation_signals", "investigation_id", "engineering_investigations"),
            new ChildTable("investigation_evidence", "investigation_id", "engineering_investigations"),
            new ChildTable("investigation_observations", "investigation_id", "engineering_investigations")
    );

    private record ChildTable(String table, String parentKey, String parentTable) {
    }

    private LegacyProjectImporter() {
    }

    public record Result(boolean imported, String projectId, long numericId, int tablesCopied) {
    }

    public static List<Result> importDatabase(SqliteProjectMetadataStore central, Path legacyDb) {
        if (central == null || legacyDb == null || !Files.isRegularFile(legacyDb)) {
            return List.of();
        }
        Path normalized = legacyDb.toAbsolutePath().normalize();
        List<Result> results = new ArrayList<>();
        String url = "jdbc:sqlite:file:" + normalized.toString().replace('\\', '/') + "?mode=ro";
        try (Connection legacy = DriverManager.getConnection(url)) {
            List<String> projectIds = listProjectIds(legacy);
            for (String projectId : projectIds) {
                results.add(central.importLegacyProject(normalized, projectId, legacy));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read legacy project database " + normalized, ex);
        }
        return List.copyOf(results);
    }

    static Result copyProject(
            Connection central,
            Connection legacy,
            Path legacyPath,
            String projectId
    ) throws SQLException {
        boolean known = alreadyImported(central, legacyPath, projectId) || projectExists(central, projectId);
        long numeric;
        if (projectExists(central, projectId)) {
            numeric = ensureNumeric(central, projectId);
        } else {
            numeric = SqliteIdentitySequences.next(central, SqliteIdentitySequences.PROJECTS);
            copyMatchingRows(central, legacy, "projects", "project_id", projectId);
            try (PreparedStatement upd = central.prepareStatement(
                    "UPDATE projects SET numeric_id=? WHERE project_id=?")) {
                upd.setLong(1, numeric);
                upd.setString(2, projectId);
                upd.executeUpdate();
            }
        }
        int copied = copyOwnedRows(central, legacy, projectId);
        if (!alreadyImported(central, legacyPath, projectId)) {
            recordImport(central, legacyPath, projectId, numeric);
        }
        return new Result(!known, projectId, numeric, copied);
    }

    private static int copyOwnedRows(Connection central, Connection legacy, String projectId)
            throws SQLException {
        int copied = 0;
        for (String table : PROJECT_SCOPED_TABLES) {
            if (tableExists(legacy, table) && tableExists(central, table)) {
                copied += copyMatchingRows(central, legacy, table, "project_id", projectId);
            }
        }
        for (ChildTable child : CHILD_TABLES) {
            if (tableExists(legacy, child.table()) && tableExists(central, child.table())
                    && tableExists(legacy, child.parentTable())) {
                copied += copyChildRows(central, legacy, child, projectId);
            }
        }
        return copied;
    }

    private static List<String> listProjectIds(Connection legacy) throws SQLException {
        List<String> ids = new ArrayList<>();
        if (!tableExists(legacy, "projects")) {
            return ids;
        }
        try (Statement s = legacy.createStatement();
             ResultSet rs = s.executeQuery("SELECT project_id FROM projects")) {
            while (rs.next()) {
                String id = rs.getString(1);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static boolean alreadyImported(Connection central, Path legacyPath, String projectId)
            throws SQLException {
        try (PreparedStatement ps = central.prepareStatement(
                "SELECT 1 FROM legacy_project_import WHERE legacy_db_path=? AND legacy_project_id=?")) {
            ps.setString(1, legacyPath.toString());
            ps.setString(2, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean projectExists(Connection central, String projectId) throws SQLException {
        try (PreparedStatement ps = central.prepareStatement(
                "SELECT 1 FROM projects WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long readNumeric(Connection central, String projectId) throws SQLException {
        try (PreparedStatement ps = central.prepareStatement(
                "SELECT numeric_id FROM projects WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static long ensureNumeric(Connection central, String projectId) throws SQLException {
        long existing = readNumeric(central, projectId);
        if (existing >= SqliteIdentitySequences.START_WITH) {
            return existing;
        }
        long numeric = SqliteIdentitySequences.next(central, SqliteIdentitySequences.PROJECTS);
        try (PreparedStatement upd = central.prepareStatement(
                "UPDATE projects SET numeric_id=? WHERE project_id=?")) {
            upd.setLong(1, numeric);
            upd.setString(2, projectId);
            upd.executeUpdate();
        }
        return numeric;
    }

    private static void recordImport(Connection central, Path legacyPath, String projectId, long numeric)
            throws SQLException {
        try (PreparedStatement ps = central.prepareStatement("""
                INSERT OR IGNORE INTO legacy_project_import(
                  legacy_db_path, legacy_project_id, numeric_project_id, imported_at)
                VALUES(?,?,?,?)
                """)) {
            ps.setString(1, legacyPath.toString());
            ps.setString(2, projectId);
            ps.setLong(3, numeric);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private static int copyMatchingRows(
            Connection central,
            Connection legacy,
            String table,
            String keyColumn,
            String keyValue
    ) throws SQLException {
        Set<String> legacyColumns = columns(legacy, table);
        if (!legacyColumns.contains(keyColumn)) {
            return 0;
        }
        List<String> columns = intersectingColumns(central, legacy, table);
        if (columns.isEmpty()) {
            return 0;
        }
        String colSql = String.join(",", columns);
        String placeholders = String.join(",", columns.stream().map(c -> "?").toList());
        int copied = 0;
        try (PreparedStatement select = legacy.prepareStatement(
                "SELECT " + colSql + " FROM " + table + " WHERE " + keyColumn + "=?");
             PreparedStatement insert = central.prepareStatement(
                     "INSERT OR IGNORE INTO " + table + "(" + colSql + ") VALUES(" + placeholders + ")")) {
            select.setString(1, keyValue);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= columns.size(); i++) {
                        insert.setObject(i, rs.getObject(i));
                    }
                    copied += insert.executeUpdate();
                }
            }
        }
        return copied;
    }

    private static int copyChildRows(
            Connection central,
            Connection legacy,
            ChildTable child,
            String projectId
    ) throws SQLException {
        List<String> columns = intersectingColumns(central, legacy, child.table());
        if (columns.isEmpty() || !columns.contains(child.parentKey())) {
            return 0;
        }
        String colSql = String.join(",", columns);
        String placeholders = String.join(",", columns.stream().map(c -> "?").toList());
        int copied = 0;
        String selectSql = "SELECT " + colSql + " FROM " + child.table()
                + " WHERE " + child.parentKey()
                + " IN (SELECT " + child.parentKey() + " FROM " + child.parentTable()
                + " WHERE project_id=?)";
        try (PreparedStatement select = legacy.prepareStatement(selectSql);
             PreparedStatement insert = central.prepareStatement(
                     "INSERT OR IGNORE INTO " + child.table() + "(" + colSql + ") VALUES(" + placeholders + ")")) {
            select.setString(1, projectId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= columns.size(); i++) {
                        insert.setObject(i, rs.getObject(i));
                    }
                    copied += insert.executeUpdate();
                }
            }
        }
        return copied;
    }

    private static List<String> intersectingColumns(Connection central, Connection legacy, String table)
            throws SQLException {
        Set<String> left = columns(legacy, table);
        Set<String> right = columns(central, table);
        List<String> out = new ArrayList<>();
        for (String column : left) {
            if (right.contains(column)
                    && !"numeric_id".equals(column)
                    && !column.endsWith("_pk")) {
                out.add(column);
            }
        }
        return out;
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
