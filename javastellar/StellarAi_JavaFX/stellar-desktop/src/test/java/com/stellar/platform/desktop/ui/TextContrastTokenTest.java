package com.stellar.platform.desktop.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextContrastTokenTest {

    @Test
    void productionCssDarkensReadableTextAndKeepsPlaceholdersLight() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/css/stellar-desktop.css"));
        String tokens = Files.readString(Path.of("src/main/resources/css/stellar-tokens.css"));
        for (String source : new String[] {css, tokens}) {
            assertTrue(source.contains("-stellar-text-body: #1E293B;"));
            assertTrue(source.contains("-stellar-text-secondary: #334155;"));
            assertTrue(source.contains("-stellar-text-muted: #475569;"));
            assertTrue(source.contains("-stellar-text-placeholder: #8A94A6;"));
            assertTrue(source.contains("-stellar-text: #0F172A;"));
        }
        assertTrue(css.contains("-fx-prompt-text-fill: -stellar-text-placeholder;"));
        assertFalse(css.contains("-fx-prompt-text-fill: -stellar-text-muted;"));
        assertTrue(css.contains(".signal-panel-collapse-button"));
        assertTrue(css.contains(".signal-panel-rail"));
        assertTrue(css.contains(".processing-elapsed"));
        assertTrue(css.contains(".workstation-page"));
        assertTrue(css.contains(".workstation-action-bar"));
        assertTrue(css.contains(".workstation-primary-button"));
        assertTrue(css.contains(".diagnostics-split"));
        assertTrue(css.contains(".processing-details-dialog"));
        assertTrue(css.contains("-stellar-success: #059669;"));
        assertTrue(css.contains("-stellar-warning: #B25A00;"));
    }

    @Test
    void usabilityChangesDoNotAddInlineCss() throws Exception {
        String[] files = {
                "src/main/java/com/stellar/platform/desktop/presentation/ProcessingElapsed.java",
                "src/main/java/com/stellar/platform/desktop/presentation/ProcessingJobTiming.java",
                "src/main/java/com/stellar/platform/desktop/ui/ProcessingWorkspacePane.java",
                "src/main/java/com/stellar/platform/desktop/ui/ProcessingDetailsDialog.java",
                "src/main/java/com/stellar/platform/desktop/presentation/ProcessingDetailsPresentation.java",
                "src/main/java/com/stellar/platform/desktop/ui/WorkstationLayoutMetrics.java",
                "src/main/java/com/stellar/platform/desktop/ui/graph/EngineeringDiagnosticsPane.java",
                "src/main/java/com/stellar/platform/desktop/ui/inventory/DecisionActionBar.java",
                "src/main/java/com/stellar/platform/desktop/ui/inventory/QuickDecodePane.java",
                "src/main/java/com/stellar/platform/desktop/ui/inventory/SourceInventoryWorkspace.java",
                "src/main/java/com/stellar/platform/desktop/ui/SignalsController.java",
                "src/main/java/com/stellar/platform/desktop/ui/signals/SignalBrowserPane.java",
                "src/main/java/com/stellar/platform/desktop/ui/signals/ActiveSignalWorkspacePane.java",
                "src/main/java/com/stellar/platform/desktop/presentation/SignalsScreenModel.java",
                "src/main/resources/fxml/graph-view.fxml"
        };
        for (String file : files) {
            String text = Files.readString(Path.of(file));
            assertFalse(text.contains("setStyle("), file);
            assertFalse(text.contains("style=\""), file);
        }
        String graph = Files.readString(Path.of(
                "src/main/java/com/stellar/platform/desktop/ui/GraphController.java"));
        int inline = 0;
        for (int from = 0; (from = graph.indexOf("setStyle(", from)) >= 0; from += 8) {
            inline++;
        }
        assertEquals(2, inline, "series color setStyle calls are pre-existing; do not add more");
    }
}
