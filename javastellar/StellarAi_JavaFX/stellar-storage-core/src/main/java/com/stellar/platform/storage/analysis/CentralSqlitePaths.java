package com.stellar.platform.storage.analysis;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Canonical location of the one StellarAI installation SQLite database.
 *
 * <pre>
 *   &lt;SelectedAnalysisStorage&gt;/StellarAIAnalysis/
 *     database/stellar.db
 *     projects/&lt;numericId&gt;/
 *     logs/
 * </pre>
 *
 * Business code must resolve the file through this type — never concatenate paths.
 */
public final class CentralSqlitePaths {

    public static final String DATABASE_DIRECTORY_NAME = "database";
    public static final String DATABASE_FILE_NAME = "stellar.db";
    public static final String LOGS_DIRECTORY_NAME = "logs";

    private CentralSqlitePaths() {
    }

    public static Path databasePath() {
        Path db = AnalysisStorageRoots.databaseRoot()
                .resolve(DATABASE_FILE_NAME)
                .toAbsolutePath()
                .normalize();
        AnalysisStorageIsolation.refuseOperatorWorkspace(db);
        return db;
    }

    public static Path databaseDirectory() {
        return databasePath().getParent();
    }

    public static Path logsDirectory() {
        return AnalysisStorageRoots.logsRoot();
    }

    public static Path ensureLayout() {
        try {
            AnalysisStorageRoots.ensureControlledDirectory();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Cannot create central Analysis Storage layout", ex);
        }
        return databasePath();
    }

    public static boolean databaseExists() {
        return Files.isRegularFile(databasePath());
    }
}
