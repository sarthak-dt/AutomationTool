package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopInvestigationService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportGenerationCancelStateTest {

    @Test
    void cancelGenerationIsEnabledOnlyWhileAReportIsRunning() throws Exception {
        assertTrue(InvestigationController.reportGenerateEnabled(true, false));
        assertFalse(InvestigationController.reportGenerateEnabled(true, true));
        assertTrue(InvestigationController.reportGenerateEnabled(true, false));
        assertFalse(InvestigationController.reportGenerateEnabled(false, false));

        String fxml = Files.readString(Path.of("src/main/resources/fxml/investigation-view.fxml"));
        assertFalse(fxml.contains("Cancel Generation"));
        assertFalse(fxml.contains("onCancelReport"));
        assertFalse(fxml.contains("Focus on Graph"));
        assertTrue(fxml.contains("text=\"Open Graph Analysis\""));
        assertTrue(fxml.contains("text=\"Generate PDF Report\""));

        DesktopInvestigationService service = new DesktopInvestigationService();
        var job = service.startReportJob();
        assertFalse(job.token().isCancellationRequested());
        service.cancelReport();
        assertTrue(job.token().isCancellationRequested());
        service.cancelReport();
        assertTrue(job.token().isCancellationRequested());
    }
}
