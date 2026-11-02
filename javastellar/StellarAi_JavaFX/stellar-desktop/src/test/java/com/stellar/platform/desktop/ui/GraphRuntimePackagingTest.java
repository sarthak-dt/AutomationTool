package com.stellar.platform.desktop.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build gate for the Analysis Workspace runtime package.
 * Prevents a green Maven build from shipping an old signals-view.fxml or
 * omitting the graph controller/analytics classes from the desktop runtime.
 */
final class GraphRuntimePackagingTest {

    @Test
    void runtimeSignalsWorkspaceContainsAnalysisNavigation() throws Exception {
        // The analysis entry point deliberately lives in the Active Signal Workspace
        // header, not the width-constrained processing context bar.
        assertNotNull(ActiveSignalWorkspacePane.class.getDeclaredField("analyzeSelectedButton"),
                "Active Signal Workspace must package Analyze Selected");
        assertNotNull(ActiveSignalWorkspacePane.class.getDeclaredMethod("setOnAnalyzeSelectedRequest", Runnable.class),
                "Analyze Selected callback must be wired by SignalsController");
        assertNotNull(SignalsController.class.getDeclaredMethod("onAnalyzeSelected"),
                "SignalsController must expose the graph navigation action");
    }

    @Test
    void runtimeGraphFxmlAndControllerArePackaged() throws Exception {
        assertNotNull(GraphController.class, "GraphController must compile into desktop runtime");
        assertNotNull(Class.forName("com.stellar.platform.desktop.ui.graph.EChartsAnalyticsPane"),
                "ECharts analytics pane must compile into desktop runtime");
        try (InputStream in = GraphController.class.getResourceAsStream("/fxml/graph-view.fxml")) {
            assertNotNull(in, "Runtime graph-view.fxml is missing");
            String fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fxml.contains("com.stellar.platform.desktop.ui.GraphController"),
                    "graph-view.fxml is not wired to GraphController");
        }
    }
}
