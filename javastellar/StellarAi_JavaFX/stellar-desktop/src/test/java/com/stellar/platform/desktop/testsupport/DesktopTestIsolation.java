package com.stellar.platform.desktop.testsupport;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;

import java.nio.file.Path;

/**
 * Clears process-wide Analysis Storage and session path state so a test cannot
 * inherit a configured root from an earlier class in the same JVM.
 */
public final class DesktopTestIsolation {

    private DesktopTestIsolation() {
    }

    public static void resetProcessState() {
        AnalysisStorageRoots.clear();
        System.clearProperty("stellarai.metadata.db");
        System.clearProperty(AnalysisStorageRoots.RUNTIME_TEMP_PROPERTY);
    }

    /**
     * Isolated application home with no inherited Analysis Storage root.
     * Auto-provision for {@code stellarai.home} stays available.
     */
    public static void isolateHome(Path home) {
        resetProcessState();
        System.setProperty("stellarai.home", home.toAbsolutePath().normalize().toString());
        System.clearProperty("stellarai.analysis.storage.disable-auto");
    }
}
