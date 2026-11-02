package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Schema v5 — case-insensitive unique project names via {@code normalized_name}.
 * <p>
 * Existing rows are preserved. If legacy DBs contain case/whitespace-only duplicate
 * display names, later rows get a deterministic disambiguated {@code normalized_name}
 * (and a display-name suffix) so the unique index can be created without deleting data.
 */
public final class MigrationV5UniqueProjectNames implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 5;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            try {
                s.execute("ALTER TABLE projects ADD COLUMN normalized_name TEXT NOT NULL DEFAULT ''");
            } catch (SQLException ignored) {
                // Column already present.
            }
        }

        // Backfill normalized_name from trimmed lower-case display name.
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT project_id, name, created_at FROM projects ORDER BY created_at ASC, project_id ASC")) {
            List<Row> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Row(
                        rs.getString("project_id"),
                        rs.getString("name"),
                        rs.getString("created_at")));
            }
            Map<String, Integer> seen = new HashMap<>();
            for (Row row : rows) {
                String base = normalize(row.name);
                if (base.isEmpty()) {
                    base = "untitled-project";
                }
                int count = seen.getOrDefault(base, 0);
                seen.put(base, count + 1);
                String normalized;
                String display = row.name == null ? "" : row.name.trim();
                if (count == 0) {
                    normalized = base;
                } else {
                    // Deterministic disambiguation — never delete/merge legacy duplicates.
                    String suffix = row.projectId.length() >= 8
                            ? row.projectId.substring(0, 8)
                            : row.projectId;
                    normalized = base + "|" + row.projectId;
                    if (!display.contains("(" + suffix + ")")) {
                        display = (display.isEmpty() ? "Untitled Project" : display) + " (" + suffix + ")";
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE projects SET name=?, normalized_name=? WHERE project_id=?")) {
                    ps.setString(1, display.isEmpty() ? "Untitled Project" : display);
                    ps.setString(2, normalized);
                    ps.setString(3, row.projectId);
                    ps.executeUpdate();
                }
            }
        }

        try (Statement s = connection.createStatement()) {
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_projects_normalized_name ON projects(normalized_name)");
        }
    }

    private static String normalize(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record Row(String projectId, String name, String createdAt) {
    }
}
