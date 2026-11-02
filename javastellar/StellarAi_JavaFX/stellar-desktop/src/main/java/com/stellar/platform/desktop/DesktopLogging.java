package com.stellar.platform.desktop;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Routes desktop logs to the configured Analysis Storage logs directory.
 * Never logs credentials or raw signal arrays.
 */
public final class DesktopLogging {

    private static final Logger LOG = Logger.getLogger(DesktopLogging.class.getName());
    private static volatile boolean configured;
    private static volatile FileHandler activeHandler;
    private static volatile Path activeLogFile;

    private DesktopLogging() {
    }

    public static synchronized void configureOnce() {
        if (configured) {
            return;
        }
        configured = true;
        try {
            new AnalysisStorageSettingsStore().loadUserSelectedRoot()
                    .ifPresent(AnalysisStorageRoots::setUserSelectedRoot);
        } catch (RuntimeException ex) {
            System.err.println("Unable to read Analysis Storage bootstrap: " + ex.getMessage());
        }
        bindConfiguredStorage();
    }

    /**
     * Points the file handler at the configured Analysis Storage logs directory.
     * A missing or unwritable configured root is reported and is not replaced with application home.
     */
    public static synchronized void bindConfiguredStorage() {
        if (!AnalysisStorageRoots.isConfigured()) {
            return;
        }
        try {
            Path logFile = AnalysisStorageRoots.logsRoot().resolve("stellar-desktop.log")
                    .toAbsolutePath().normalize();
            if (logFile.equals(activeLogFile)) {
                return;
            }
            Files.createDirectories(logFile.getParent());
            FileHandler handler = new FileHandler(logFile.toString(), true);
            handler.setFormatter(new SimpleFormatter());
            handler.setLevel(Level.INFO);
            Logger root = LogManager.getLogManager().getLogger("");
            if (root != null) {
                if (activeHandler != null) {
                    root.removeHandler(activeHandler);
                    activeHandler.close();
                }
                root.addHandler(handler);
            }
            activeHandler = handler;
            activeLogFile = logFile;
            LOG.info(() -> "Desktop log file: " + logFile);
        } catch (IOException | IllegalStateException ex) {
            System.err.println("Analysis Storage logs are unavailable: " + ex.getMessage());
        }
    }

    /** Releases the log file so tests can remove a temporary Analysis Storage tree. */
    public static synchronized void closeForTests() {
        Logger root = LogManager.getLogManager().getLogger("");
        if (root != null && activeHandler != null) {
            root.removeHandler(activeHandler);
        }
        if (activeHandler != null) {
            activeHandler.close();
            activeHandler = null;
        }
        activeLogFile = null;
    }
}
