package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineerEventInclusion;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph Add Event stores one engineering bookmark. The report includes that bookmark
 * and draws it on the waveform. The bookmark values stay as saved.
 */
class GraphEventReportJourneyTest {

    private static final long EVENT_NANOS = 5_000_000_000L;

    @TempDir Path temp;

    @Test
    void graphBookmarkIsIncludedEditedAndReopenedWithoutASecondCopy() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        try {
            service.newProject("Graph events");
            service.persistInvestigationEvent("Brake noise", "Braking", "left pad", EVENT_NANOS, "VehicleSpeed");
            service.persistInvestigationEvent("Parked", "Observation", "not selected", 1_000_000_000L, "EngineRPM");
            assertEquals(2, service.listEngineeringBookmarks().size());
            EngineeringBookmarkMetadata saved = service.listEngineeringBookmarks().stream()
                    .filter(bookmark -> "Brake noise".equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                    .findFirst().orElseThrow();
            assertEquals(EVENT_NANOS, saved.timestampNanos());
            assertEquals("VehicleSpeed", saved.signalName());
            assertEquals("Braking", saved.eventType());
            assertEquals("left pad", InvestigationEventCodec.noteOf(saved.observation()));
            assertFalse(saved.observation().contains(EngineerEventDocuments.SENTINEL));

            var created = service.createInvestigation("Graph report", "QA", "FULL");
            service.saveInvestigation(created.toBuilder().reportConfig(InvestigationReportConfig.engineeringV2()).build());
            var included = service.includeEngineerEvent(saved.bookmarkId());
            assertTrue(EngineerEventInclusion.linked(included.evidence(), saved.bookmarkId()));
            assertEquals(1, SignalReportLoader.includedEventSpans(
                    service.listEngineeringBookmarks(), included.evidence()).size());
            assertEquals(EVENT_NANOS / 1_000_000_000d, SignalReportLoader.includedEventSpans(
                    service.listEngineeringBookmarks(), included.evidence()).getFirst().startSeconds(), 0.0);

            Path html = service.generateInvestigationReport(
                    service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                    "Ada", temp.resolve("graph.html"));
            Path pdf = service.generateInvestigationPdfReport(
                    service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                    "Ada", temp.resolve("graph.pdf"));
            String text = Files.readString(html);
            assertTrue(text.contains("Brake noise"));
            assertTrue(text.contains("Braking"));
            assertTrue(text.contains("VehicleSpeed"));
            assertTrue(text.contains("left pad"));
            assertTrue(text.contains(EngineeringFormats.seconds(EVENT_NANOS)));
            assertTrue(text.contains("No graph snapshot is linked."));
            assertFalse(text.contains("Parked"));
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                String packed = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
                assertTrue(packed.contains("Brakenoise"));
                assertTrue(packed.contains("Braking"));
                assertTrue(packed.contains("VehicleSpeed"));
                assertTrue(packed.contains("leftpad"));
                assertTrue(packed.contains("5.000s"));
                assertFalse(packed.contains("Parked"));
            }
            assertEquals(EVENT_NANOS, service.findEngineeringBookmark(saved.bookmarkId()).orElseThrow().timestampNanos());

            assertTrue(service.updateInvestigationEvent(saved.bookmarkId(), "Edited brake", "Braking", "updated note"));
            assertEquals(2, service.listEngineeringBookmarks().size());
            assertEquals(EVENT_NANOS, service.findEngineeringBookmark(saved.bookmarkId()).orElseThrow().timestampNanos());
            String edited = Files.readString(service.generateInvestigationReport(
                    service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                    "Ada", temp.resolve("edited.html")));
            assertTrue(edited.contains("Edited brake"));
            assertTrue(edited.contains("updated note"));
            assertFalse(edited.contains("Brake noise"));
            assertEquals(1, count(edited, saved.bookmarkId()));

            service.excludeEngineerEvent(saved.bookmarkId());
            String removed = Files.readString(service.generateInvestigationReport(
                    service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                    "Ada", temp.resolve("removed.html")));
            assertFalse(removed.contains("Edited brake"));
            assertTrue(service.findEngineeringBookmark(saved.bookmarkId()).isPresent());

            String projectId = service.currentProjectId();
            service.closeProject();
            assertTrue(service.openSavedProject(projectId).opened());
            EngineeringBookmarkMetadata reopened = service.findEngineeringBookmark(saved.bookmarkId()).orElseThrow();
            assertEquals(EVENT_NANOS, reopened.timestampNanos());
            assertEquals("Edited brake", InvestigationEventCodec.titleOf(reopened.observation()));
            assertEquals("VehicleSpeed", reopened.signalName());
            assertEquals(2, service.listEngineeringBookmarks().size());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void includedPointIsDrawnAtTheStoredTimestampAndManyMarksStayOnThatAxis() {
        double[] times = {0d, 5d, 10d};
        double[] values = {1d, 2d, 3d};
        SignalReportRenderer.Track track = new SignalReportRenderer.Track("VehicleSpeed", "km/h", times, values);
        BufferedImage image = SignalReportRenderer.paint(List.of(track), List.of(
                new SignalReportRenderer.EventSpan("Brake noise", 5d, Double.NaN)));
        assertEquals(1d, values[0], 0d);
        assertEquals(2d, values[1], 0d);
        assertEquals(3d, values[2], 0d);
        SignalReportRenderer.TrackFrame frame = SignalReportRenderer.layout(List.of(track)).tracks().getFirst();
        int x = plotX(frame, 5d);
        assertTrue(columnHas(image, x, frame, 0xE6A817));
        assertFalse(columnHas(image, frame.plotLeft() + 2, frame, 0xE6A817));

        List<EngineeringBookmarkMetadata> bookmarks = new ArrayList<>();
        List<com.stellar.platform.analysis.investigation.EngineeringEvidenceItem> evidence = List.of();
        Instant now = Instant.EPOCH;
        for (int i = 0; i < 400; i++) {
            EngineeringBookmarkMetadata bookmark = new EngineeringBookmarkMetadata(
                    "event-" + i, "proj", "src", "", "VehicleSpeed", "VehicleSpeed",
                    i * 1_000_000_000L, "Braking", "Mark " + i, now, now);
            bookmarks.add(bookmark);
            evidence = EngineerEventInclusion.include(evidence, "inv", bookmark.bookmarkId(), bookmark.observation());
        }
        long started = System.nanoTime();
        List<SignalReportRenderer.EventSpan> spans = SignalReportLoader.includedEventSpans(bookmarks, evidence);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(400, spans.size());
        assertEquals(0d, spans.getFirst().startSeconds(), 0d);
        assertEquals(399d, spans.getLast().startSeconds(), 0d);
        assertTrue(elapsedMs < 1000L, "span build took " + elapsedMs + " ms");
        BufferedImage many = SignalReportRenderer.paint(List.of(track), spans);
        assertEquals(2d, values[1], 0d);
        assertTrue(many.getWidth() > 0);
    }

    private static int plotX(SignalReportRenderer.TrackFrame frame, double time) {
        double span = Math.max(1e-12, frame.xMax() - frame.xMin());
        double fraction = Math.max(0, Math.min(1, (time - frame.xMin()) / span));
        return frame.plotLeft() + (int) Math.round(fraction * (frame.plotWidth() - 1));
    }

    private static boolean columnHas(BufferedImage image, int x, SignalReportRenderer.TrackFrame frame, int rgb) {
        int target = rgb & 0xFFFFFF;
        for (int y = frame.plotTop(); y < frame.plotTop() + frame.plotHeight(); y++) {
            if ((image.getRGB(x, y) & 0xFFFFFF) == target) {
                return true;
            }
        }
        return false;
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0) {
            from = text.indexOf(token, from);
            if (from < 0) {
                break;
            }
            found++;
            from += token.length();
        }
        return found;
    }
}
