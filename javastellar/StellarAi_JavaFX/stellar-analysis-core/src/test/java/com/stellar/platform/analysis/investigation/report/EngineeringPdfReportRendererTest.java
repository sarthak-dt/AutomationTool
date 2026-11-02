package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringPdfReportRendererTest {

    @TempDir Path temp;

    @Test
    void pdfMatchesHtmlFactsEmbedsTheSnapshotAndPaginates() throws Exception {
        Path image = temp.resolve("shot.png");
        BufferedImage picture = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = picture.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 80, 40);
        graphics.dispose();
        ImageIO.write(picture, "png", image.toFile());

        String observation = "Δ 42.5 km/h — café brake note";
        StringBuilder longNote = new StringBuilder();
        for (int i = 0; i < 90; i++) {
            longNote.append("Line ").append(i).append(" wraps across the A4 page without clipping the engineering note. ");
        }
        EngineeringReportModel model = EngineeringReportModel.builder()
                .header(new EngineeringReportModel.Header(
                        "Coast", "Dat1", "Coast", Instant.parse("2026-10-21T12:00:00Z"), "1.0.3", "Ada"))
                .investigation(new EngineeringReportModel.InvestigationSection(
                        "Coast", "Driver report", InvestigationStatus.DRAFT, "0.030 s – 4718.623 s", "4718.593 s", 1))
                .observations(List.of(new EngineeringObservation(
                        "obs", "inv", null, null, "Brake", observation + "\n" + longNote, Instant.now(), Instant.now())))
                .evidence(List.of(
                        new EngineeringEvidenceItem("e1", "inv", EvidenceKind.GRAPH_SNAPSHOT, "snap",
                                0, false, "", "", 0L, "11", "11", Instant.now()),
                        new EngineeringEvidenceItem("e2", "inv", EvidenceKind.DIAGNOSTIC_FINDING, "find",
                                1, false, "", "", 0L, "Rule hit", "summary", Instant.now())))
                .figures(List.of(
                        new EngineeringReportModel.Figure(1, "11", "shot.png", "0.030 s – 4.000 s"),
                        new EngineeringReportModel.Figure(2, "Missing", "missing.png", "")))
                .signals(List.of(new EngineeringReportModel.SignalRow(
                        "VehicleSpeed", "km/h", "run.mf4", "0", "42.5", "10", "12", "42.5")))
                .conclusion("User conclusion")
                .build();

        Path pdf = temp.resolve("Coast.pdf");
        EngineeringPdfReportRenderer.write(model, pdf, temp);
        byte[] header = Files.readAllBytes(pdf);
        assertTrue(header.length > 5);
        assertEquals("%PDF", new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));

        String html = EngineeringHtmlReportRenderer.render(model);
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertTrue(document.getNumberOfPages() >= 2);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Coast"));
            assertTrue(text.contains("Driver report"));
            assertTrue(text.contains("DRAFT"));
            assertTrue(text.contains("0.030 s"));
            assertTrue(text.contains("Δ 42.5 km/h"));
            assertTrue(text.contains("café"));
            assertTrue(text.contains("km/h"));
            assertTrue(text.contains("11"));
            assertTrue(text.contains("User conclusion"));
            assertTrue(text.contains("Evidence image unavailable."));
            assertTrue(text.contains("DIAGNOSTIC_FINDING"));
            assertTrue(html.contains("Coast"));
            assertTrue(html.contains(observation));
            assertTrue(html.contains("DIAGNOSTIC_FINDING"));
            assertEquals(2, model.evidence().size());
            boolean embedded = false;
            for (PDPage page : document.getPages()) {
                if (page.getResources() == null) {
                    continue;
                }
                for (var name : page.getResources().getXObjectNames()) {
                    PDXObject object = page.getResources().getXObject(name);
                    if (object instanceof PDImageXObject) {
                        embedded = true;
                    }
                }
            }
            assertTrue(embedded);
        }
    }
}
