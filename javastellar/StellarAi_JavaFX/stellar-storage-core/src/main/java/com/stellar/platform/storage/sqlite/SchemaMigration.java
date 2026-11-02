package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/** One incremental schema step. Applied inside a transaction by {@link SchemaMigrator}. */
public interface SchemaMigration {
    /** Target schema version after this migration succeeds. */
    int targetVersion();

    void apply(Connection connection) throws SQLException;
}
