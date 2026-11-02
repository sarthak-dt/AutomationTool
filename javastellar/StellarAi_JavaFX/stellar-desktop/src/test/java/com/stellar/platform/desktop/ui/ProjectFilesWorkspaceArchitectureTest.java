package com.stellar.platform.desktop.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Files shell architecture regressions (FXML structure).
 */
class ProjectFilesWorkspaceArchitectureTest {

    @Test
    void fxmlKeepsPersistentNavAndRetiresProcessingChrome() throws Exception {
        try (InputStream in = ProjectFilesController.class.getResourceAsStream("/fxml/project-files-view.fxml")) {
            assertNotNull(in);
            String fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fxml.contains("<top>"), "chrome must be BorderPane top");
            assertTrue(fxml.contains("project-files-workspace-nav"));
            assertTrue(fxml.contains("fx:id=\"workspaceHost\""));
            assertTrue(fxml.contains("fx:id=\"sourcesWorkspace\""));
            assertTrue(fxml.contains("fx:id=\"sourceInventoryWorkspace\""));
            assertTrue(fxml.contains("fx:id=\"dbcMappingTablePane\""));
            int top = fxml.indexOf("<top>");
            int nav = fxml.indexOf("project-files-workspace-nav");
            int center = fxml.indexOf("<center>");
            assertTrue(top >= 0 && nav > top && nav < center);
            int processing = fxml.indexOf("fx:id=\"processingActionBar\"");
            assertTrue(processing >= 0);
            assertTrue(fxml.substring(processing, Math.min(fxml.length(), processing + 160))
                    .contains("managed=\"false\""));
            assertFalse(fxml.contains("Validate Selection"));
            assertFalse(fxml.contains("project-files-secondary-scroll"),
                    "legacy stacked secondary scroll must remain removed");
        }
    }
}