package com.stellar.platform.storage.metadata;

import com.stellar.platform.storage.api.ConfigMappingDefaultsStore;

import java.io.IOException;
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
import java.util.List;
import java.util.Objects;

/**
 * Product defaults in their own installation-level SQLite file.
 *
 * <p>They deliberately do not live in a project database: a default that only one project can see is
 * not a default. The table mirrors the project-scoped columns so the same
 * {@link SemanticSignalMappingMetadata} travels through both stores unchanged.
 */
public final class SqliteConfigMappingDefaultsStore implements ConfigMappingDefaultsStore {

    private final Path databasePath;
    private final Connection connection;
    private boolean closed;

    /** Opens product defaults under the configured Analysis Storage config directory. */
    public SqliteConfigMappingDefaultsStore() {
        this(com.stellar.platform.storage.analysis.AnalysisStorageRoots
                .configRoot()
                .resolve("config-mapping-defaults.db"));
    }

    public SqliteConfigMappingDefaultsStore(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        try {
            Path parent = this.databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.databasePath);
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS config_mapping_default(
                          config_group TEXT NOT NULL,
                          role TEXT NOT NULL,
                          mapping_mode TEXT NOT NULL,
                          signal_name TEXT NOT NULL,
                          source_type TEXT NOT NULL,
                          source_file TEXT NOT NULL,
                          message_name TEXT NOT NULL,
                          channel INTEGER NOT NULL,
                          manual_text TEXT NOT NULL,
                          product_context TEXT NOT NULL,
                          updated_at TEXT NOT NULL,
                          PRIMARY KEY(config_group, role))
                        """);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot open StellarAI configuration defaults database "
                    + this.databasePath, e);
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    @Override
    public synchronized void upsertDefaultMapping(SemanticSignalMappingMetadata mapping) {
        ensureOpen();
        Objects.requireNonNull(mapping, "mapping");
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO config_mapping_default(
                  config_group, role, mapping_mode, signal_name, source_type, source_file,
                  message_name, channel, manual_text, product_context, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(config_group, role) DO UPDATE SET
                  mapping_mode=excluded.mapping_mode,
                  signal_name=excluded.signal_name,
                  source_type=excluded.source_type,
                  source_file=excluded.source_file,
                  message_name=excluded.message_name,
                  channel=excluded.channel,
                  manual_text=excluded.manual_text,
                  product_context=excluded.product_context,
                  updated_at=excluded.updated_at
                """)) {
            ps.setString(1, mapping.configGroup());
            ps.setString(2, mapping.role());
            ps.setString(3, mapping.mappingMode());
            ps.setString(4, mapping.signalName());
            ps.setString(5, mapping.sourceType());
            ps.setString(6, mapping.sourceFile());
            ps.setString(7, mapping.messageName());
            ps.setInt(8, mapping.channel());
            ps.setString(9, mapping.manualText());
            ps.setString(10, mapping.schemaFingerprint());
            ps.setString(11, mapping.updatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot store configuration default", ex);
        }
    }

    @Override
    public synchronized List<SemanticSignalMappingMetadata> listDefaultMappings(String configGroup) {
        ensureOpen();
        String group = configGroup == null || configGroup.isBlank()
                ? SemanticSignalMappingMetadata.GROUP_MAP_ROUTE
                : configGroup;
        List<SemanticSignalMappingMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM config_mapping_default WHERE config_group=? ORDER BY role")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot list configuration defaults", ex);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void deleteDefaultMapping(String configGroup, String role) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM config_mapping_default WHERE config_group=? AND role=?")) {
            ps.setString(1, configGroup);
            ps.setString(2, role);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot delete configuration default", ex);
        }
    }

    @Override
    public synchronized void deleteDefaultMappings(String configGroup) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM config_mapping_default WHERE config_group=?")) {
            ps.setString(1, configGroup);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot delete configuration defaults", ex);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing a read-mostly configuration database must never fail a shutdown.
        }
    }

    private static SemanticSignalMappingMetadata read(ResultSet rs) throws SQLException {
        Instant updated;
        try {
            updated = Instant.parse(rs.getString("updated_at"));
        } catch (RuntimeException ex) {
            updated = Instant.now();
        }
        return new SemanticSignalMappingMetadata(
                "",
                "",
                rs.getString("product_context"),
                rs.getString("config_group"),
                SemanticSignalMappingMetadata.SCOPE_DEFAULT,
                rs.getString("role"),
                rs.getString("mapping_mode"),
                rs.getString("signal_name"),
                rs.getString("source_type"),
                rs.getString("source_file"),
                rs.getString("message_name"),
                rs.getInt("channel"),
                rs.getString("manual_text"),
                true,
                updated);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Configuration defaults store is closed");
        }
    }
}
