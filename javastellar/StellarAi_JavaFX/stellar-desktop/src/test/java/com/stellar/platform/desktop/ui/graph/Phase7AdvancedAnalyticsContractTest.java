package com.stellar.platform.desktop.ui.graph;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7AdvancedAnalyticsContractTest {

    @Test
    void frequencyDomainLivesInsideEngineeringAnalysis() throws Exception {
        String pane = Files.readString(Path.of(
                "src/main/java/com/stellar/platform/desktop/ui/graph/EngineeringAnalysisPane.java"));
        String advanced = Files.readString(Path.of(
                "src/main/java/com/stellar/platform/desktop/ui/graph/AdvancedAnalyticsPane.java"));
        assertTrue(pane.contains("Frequency Domain"));
        assertTrue(pane.contains("Time Domain"));
        assertTrue(pane.contains("Comparison"));
        assertTrue(pane.contains("Advanced analytics"));
        assertTrue(advanced.contains("Hann") || advanced.contains("HANN"));
        assertTrue(advanced.contains("Save Derived Signal"));
        assertTrue(advanced.contains("measurements, not automatic diagnoses"));
    }
}
