package com.stellar.platform.desktop.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFilesLayoutTest {

    @Test
    void projectFilesFxmlDeclaresPersistentShellAndExclusiveWorkspaces() throws Exception {
        try (InputStream in = ProjectFilesController.class.getResourceAsStream("/fxml/project-files-view.fxml")) {
            assertNotNull(in);
            String fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fxml.contains("project-files-chrome"), "persistent chrome missing");
            assertTrue(fxml.contains("project-files-workspace-nav"), "workspace nav missing");
            assertTrue(fxml.contains("<top>"), "chrome must live in BorderPane top");
            assertTrue(fxml.contains("fx:id=\"workspaceHost\""), "workspace host missing");
            assertTrue(fxml.contains("fx:id=\"sourcesWorkspace\""), "sources workspace missing");
            assertTrue(fxml.contains("fx:id=\"sourceInventoryWorkspace\""), "inventory workspace missing");
            assertTrue(fxml.contains("fx:id=\"dbcMappingTablePane\""), "DBC mapping workspace missing");
            assertTrue(fxml.contains("onAction=\"#onShowSourcesWorkspace\""));
            assertTrue(fxml.contains("onAction=\"#onShowSourceInventoryWorkspace\""));
            assertTrue(fxml.contains("onAction=\"#onShowDbcMappingWorkspace\""));
            int top = fxml.indexOf("<top>");
            int nav = fxml.indexOf("project-files-workspace-nav");
            int center = fxml.indexOf("<center>");
            int host = fxml.indexOf("fx:id=\"workspaceHost\"");
            assertTrue(top >= 0 && nav > top && nav < center, "nav must be inside BorderPane top chrome");
            assertTrue(host > center, "workspace host must be in center");
            assertFalse(fxml.contains("Validate Selection"),
                    "Validate Selection must be retired from Sources toolbar for this phase");
            assertFalse(fxml.contains("Inspect Sources"),
                    "Inspect Sources is redundant on the Sources workspace");
            assertFalse(fxml.contains("text=\"Collapse\""),
                    "non-functional Collapse toolbar control must not be present");
            int processing = fxml.indexOf("fx:id=\"processingActionBar\"");
            assertTrue(processing >= 0);
            String processingSnippet = fxml.substring(processing, Math.min(fxml.length(), processing + 180));
            assertTrue(processingSnippet.contains("managed=\"false\""),
                    "ProcessingActionBar must be retired (managed=false)");
        }
    }
}
