package com.stellar.platform.desktop.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class GraphEntryPointPackagingTest {
    @Test
    void mainShellPackagesPermanentAnalysisNavigation() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fxml/main-shell.fxml")) {
            assertNotNull(in, "main-shell.fxml must be packaged");
            String fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fxml.contains("fx:id=\"navGraphs\""), "Permanent Graphs navigation must be packaged");
            assertTrue(fxml.contains("onAction=\"#onNavGraphs\""), "Graphs navigation must be wired");
            assertTrue(fxml.contains("fx:id=\"navInvestigations\""), "Permanent Investigations navigation must be packaged");
            assertTrue(fxml.contains("onAction=\"#onNavInvestigations\""), "Investigations navigation must be wired");
            assertTrue(fxml.contains("fx:id=\"navSignals\""), "Permanent Signals navigation must be packaged");
            assertFalse(fxml.contains("navAnalysis"), "\"Analysis\" is presented as \"Graphs\" in V1");
        }
    }

    @Test
    void workspaceSourceKeepsContextualAnalyzeAction() throws Exception {
        var field = Class.forName("com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane")
                .getDeclaredField("analyzeSelectedButton");
        assertNotNull(field);
        var method = Class.forName("com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane")
                .getDeclaredMethod("setOnAnalyzeSelectedRequest", Runnable.class);
        assertNotNull(method);
    }

    @Test
    void graphControllerIsPackaged() {
        assertNotNull(GraphController.class);
    }
}
