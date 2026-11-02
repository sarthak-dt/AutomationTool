package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.SampleDisplay;
import com.stellar.platform.desktop.presentation.WorkspacePrecision;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.MinMaxLodReducer;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableColumn;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavaFX Signal Workspace and Graph for DBC value-table text.
 * Numeric samples stay numbers. The graph holds a state instead of drawing a diagonal.
 */
class DbcEnumeratedDisplayUiTest {

    private static final long VIEW_END = 120_000_000L;

    @TempDir
    Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void workspaceShowsRawCodeEqualsLabelAndKeepsUnknownInvalidAndAnalogNumeric() throws Exception {
        assertEquals("0 = Sleep", SampleDisplay.physical(
                0.0, 0L, "Sleep", SignalValidity.VALID, WorkspacePrecision.AUTO));
        assertEquals("1 = Initialization", SampleDisplay.physical(
                10.5, 1L, "Initialization", SignalValidity.VALID, WorkspacePrecision.AUTO));
        assertEquals(WorkspacePrecision.AUTO.formatPhysical(16.0), SampleDisplay.physical(
                16.0, 16L, null, SignalValidity.VALID, WorkspacePrecision.AUTO));
        assertEquals(WorkspacePrecision.AUTO.formatPhysical(255.0), SampleDisplay.physical(
                255.0, 255L, "invalid", SignalValidity.INVALID_RAW_CODE, WorkspacePrecision.AUTO));
        assertEquals("0 (Sleep)", SampleDisplay.raw(0L, "Sleep", WorkspacePrecision.AUTO));
        assertEquals("255 (invalid)", SampleDisplay.raw(255L, "invalid", WorkspacePrecision.AUTO));

        JavaFxTestSupport.runOnFx("workspace 1366", () -> {
            ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1366, 768));
            stage.show();
            pane.resize(1366, 768);
            pane.applyCss();
            pane.layout();

            Method rebuild = ActiveSignalWorkspacePane.class.getDeclaredMethod("rebuildColumns", List.class);
            rebuild.setAccessible(true);
            rebuild.invoke(pane, List.of(entry("HVBAT_STATE", ""), entry("Coolant", "degC")));
            pane.table().getItems().setAll(workspaceRows());
            pane.layout();

            Field toolbarField = ActiveSignalWorkspacePane.class.getDeclaredField("toolbar");
            toolbarField.setAccessible(true);
            HBox toolbar = (HBox) toolbarField.get(pane);
            pane.layout();
            double toolbarPref = toolbar.prefHeight(1366);
            assertTrue(toolbarPref > 16 && toolbarPref < 48,
                    "toolbar must stay one row at 1366, prefHeight=" + toolbarPref);

            Set<String> physical = cellText(pane);
            assertTrue(physical.contains("0 = Sleep"), physical.toString());
            assertTrue(physical.contains("3 = Ready"), physical.toString());
            assertTrue(physical.contains("7 = Reserved"), physical.toString());
            assertTrue(physical.contains("1 = Initialization"), physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(16.0)), physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(255.0)), physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(21.5)), physical.toString());
            assertFalse(physical.contains("16 = "), physical.toString());
            assertFalse(String.join("\n", physical).contains("255 = invalid"), physical.toString());

            displayMode(pane).setValue(com.stellar.platform.desktop.presentation.WorkspaceDisplayMode.RAW);
            pane.layout();
            Set<String> raw = cellText(pane);
            assertTrue(raw.contains("0 (Sleep)"), raw.toString());
            assertTrue(raw.contains("3 (Ready)"), raw.toString());
            assertTrue(raw.contains("16"), raw.toString());
            assertTrue(raw.contains("255 (invalid)"), raw.toString());
            assertFalse(joinedButtons(pane).contains("Unknown Order"));
            stage.close();
        });
    }

    @Test
    void graphHoldsStateDrawsAnalogAsALineAndLeavesAHistoricalSnapshotUntouched() throws Exception {
        SignalSamplePoint invalid = new SignalSamplePoint(
                15_000_000L, 255.0, 255L, "invalid", SignalValidity.INVALID_RAW_CODE);
        GraphBucket invalidBucket = MinMaxLodReducer.reduce(List.of(invalid), 4).getFirst();
        assertNull(invalidBucket.categoricalLabel());
        assertFalse(invalidBucket.firstUsable());

        List<GraphBucket> stateBuckets = MinMaxLodReducer.reduce(List.of(
                point(10_000_000L, 0.0, 0L, "Sleep"),
                point(20_000_000L, 3.0, 3L, "Ready"),
                point(90_000_000L, 3.0, 3L, "Ready")), 20);
        List<GraphBucket> analogBuckets = MinMaxLodReducer.reduce(List.of(
                point(10_000_000L, 20.0, 200L, null),
                point(20_000_000L, 40.0, 400L, null)), 20);
        assertEquals("Sleep", stateBuckets.getFirst().categoricalLabel());
        assertNull(analogBuckets.getFirst().categoricalLabel());
        assertEquals(0.0, stateBuckets.getFirst().firstValue(), 0.0);
        assertEquals(10_000_000L, stateBuckets.getFirst().firstTimestampNanos());

        Path historical = temp.resolve("historical-snapshot.png");
        byte[] evidence = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3, 4};
        Files.write(historical, evidence);

        JavaFxTestSupport.runOnFx("graph state", () -> {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            SignalCatalogKey state = key("HVBAT_STATE");
            SignalCatalogKey coolant = key("Coolant");
            canvas.setTracePresentation(java.util.Map.of(state, "HVBAT_STATE", coolant, "Coolant"), java.util.Map.of());
            canvas.setMinSize(1200, 640);
            canvas.setPrefSize(1200, 640);
            Stage stage = new Stage();
            stage.setScene(new Scene(canvas, 1200, 640));
            stage.show();
            canvas.resize(1200, 640);
            canvas.prepareSharedViewport(0L, VIEW_END);
            canvas.setSeries(List.of(
                    series(state, "", stateBuckets),
                    series(coolant, "degC", analogBuckets)));
            canvas.setAnalysisOverlays(List.of(
                    new AutomotiveSignalCanvas.AnalysisOverlay(10_000_000L, null, "Sleep"),
                    new AutomotiveSignalCanvas.AnalysisOverlay(10_000_000L, 20_000_000L, "Wake")));
            canvas.layout();
            canvas.flushDeferredPaintForTest();

            assertArrayEquals(evidence, Files.readAllBytes(historical));
            List<String> labels = canvas.paintedStateLabelsForTest();
            assertTrue(labels.contains("Sleep"), labels.toString());
            assertTrue(labels.contains("Ready"), labels.toString());
            assertFalse(labels.contains("20"), labels.toString());
            assertFalse(labels.contains("40"), labels.toString());

            WritableImage image = canvas.snapshot(null, null);
            assertTrue(image.getWidth() >= 1200);
            PixelReader reader = image.getPixelReader();
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            Color stateColor = GraphSeriesPalette.at(0);
            int x0 = plotX(width, 10_000_000L);
            int x1 = plotX(width, 20_000_000L);
            int yEarly = strokeY(reader, width, height, x0 + 4, stateColor);
            int yLate = strokeY(reader, width, height, x0 + (int) ((x1 - x0) * 0.7), stateColor);
            assertTrue(yEarly > 0 && yLate > 0, "state stroke missing early=" + yEarly + " late=" + yLate);
            assertTrue(Math.abs(yEarly - yLate) <= 8,
                    "state trace interpolated, earlyY=" + yEarly + " lateY=" + yLate);

            Color analogColor = GraphSeriesPalette.at(1);
            int analogEarly = strokeY(reader, width, height, x0 + 4, analogColor);
            int analogLate = strokeY(reader, width, height, x0 + (int) ((x1 - x0) * 0.7), analogColor);
            assertTrue(analogEarly > 0 && analogLate > 0, "analog stroke missing");
            assertTrue(analogLate + 12 < analogEarly,
                    "analog trace did not rise, earlyY=" + analogEarly + " lateY=" + analogLate);

            int gapX = plotX(width, 55_000_000L);
            assertEquals(-1, strokeY(reader, width, height, gapX, stateColor),
                    "state was held across the timestamp gap");

            var frames = canvas.stackedFramesForTest();
            assertEquals(2, frames.size());
            canvas.movePointerForTest((x0 + x1) / 2.0, frames.getFirst().top() + 40);
            AutomotiveSignalCanvas.HoverPoint hover = canvas.hoverPointProperty().get();
            assertTrue(hover != null, "no hover");
            assertEquals("HVBAT_STATE", hover.signalName());
            assertTrue(hover.timestampNanos() == 10_000_000L || hover.timestampNanos() == 20_000_000L,
                    "hover moved the sample time: " + hover.timestampNanos());
            assertTrue("Sleep".equals(hover.categoricalLabel()) || "Ready".equals(hover.categoricalLabel()),
                    hover.categoricalLabel());
            String pointer = SampleDisplay.provenance(
                    hover.signalName(), hover.measurement(), hover.unit(), hover.value(),
                    hover.categoricalLabel(), hover.pointerTimestampNanos(), hover.timestampNanos(),
                    hover.bucketStartNanos(), hover.bucketEndNanos(), hover.bucketSampleCount(),
                    hover.valueRole(), hover.validityName(),
                    "Sleep".equals(hover.categoricalLabel()) ? 0L : 3L);
            assertTrue(pointer.contains(" = "), pointer);
            assertTrue(pointer.contains(hover.categoricalLabel()), pointer);

            assertEquals(10_000_000L, canvas.analysisOverlaysForTest().getFirst().startNanos());
            assertEquals(20_000_000L, canvas.analysisOverlaysForTest().get(1).endNanos());
            assertNotEquals(-1, strokeY(reader, width, height, plotX(width, 10_000_000L),
                    javafx.scene.paint.Color.web("#0E7490")));
            assertFalse(joinedButtons(canvas).contains("Unknown Order"));
            stage.close();
        });
        assertArrayEquals(evidence, Files.readAllBytes(historical));
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        assertTrue(used < 1536L * 1024 * 1024);
    }

    private static int plotX(int width, long timestamp) {
        double left = 82;
        double right = Math.max(left + 1, width - 18);
        return (int) (left + (timestamp / (double) VIEW_END) * (right - left));
    }

    private static int strokeY(PixelReader reader, int width, int height, int x, Color color) {
        int bestY = -1;
        double best = 0.85;
        for (int y = 24; y < height - 16; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= width) {
                    continue;
                }
                Color pixel = reader.getColor(xx, y);
                double distance = Math.abs(pixel.getRed() - color.getRed())
                        + Math.abs(pixel.getGreen() - color.getGreen())
                        + Math.abs(pixel.getBlue() - color.getBlue());
                if (distance < best) {
                    best = distance;
                    bestY = y;
                }
            }
        }
        return bestY;
    }

    private static SignalSamplePoint point(long timestamp, double physical, long raw, String text) {
        return new SignalSamplePoint(timestamp, physical, raw, text, SignalValidity.VALID);
    }

    private static GraphRenderSeries series(SignalCatalogKey key, String unit, List<GraphBucket> buckets) {
        return new GraphRenderSeries(key, unit, buckets.size(), true, 0L, VIEW_END, buckets);
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "Battery", 256L, 1, "pack.mf4", "hvbat.dbc");
    }

    private static List<AlignedSampleRow> workspaceRows() {
        return List.of(
                row(0, new Double[] {0.0, 21.5}, new Long[] {0L, 215L},
                        new String[] {"Sleep", null},
                        new SignalValidity[] {SignalValidity.VALID, SignalValidity.VALID}),
                row(1, new Double[] {3.0, 22.0}, new Long[] {3L, 220L},
                        new String[] {"Ready", null},
                        new SignalValidity[] {SignalValidity.VALID, SignalValidity.VALID}),
                row(2, new Double[] {16.0, null}, new Long[] {16L, null},
                        new String[] {null, null},
                        new SignalValidity[] {SignalValidity.VALID, null}),
                row(3, new Double[] {7.0, null}, new Long[] {7L, null},
                        new String[] {"Reserved", null},
                        new SignalValidity[] {SignalValidity.VALID, null}),
                row(4, new Double[] {255.0, null}, new Long[] {255L, null},
                        new String[] {"invalid", null},
                        new SignalValidity[] {SignalValidity.INVALID_RAW_CODE, null}),
                row(5, new Double[] {10.5, null}, new Long[] {1L, null},
                        new String[] {"Initialization", null},
                        new SignalValidity[] {SignalValidity.VALID, null}));
    }

    private static AlignedSampleRow row(
            int index, Double[] physical, Long[] raw, String[] text, SignalValidity[] validity) {
        return new AlignedSampleRow(index, 1_000_000L * (index + 1), physical, raw, text, validity);
    }

    private static SignalCatalogEntry entry(String name, String unit) {
        return new SignalCatalogEntry(
                key(name.equals("Coolant") ? "Coolant" : "HVBAT_STATE"),
                unit, 6L, 1_000_000L, 6_000_000L, 0.0, 255.0, "pack.mf4", "hvbat.dbc");
    }

    private static Set<String> cellText(ActiveSignalWorkspacePane pane) {
        Set<String> text = new HashSet<>();
        for (Node node : pane.lookupAll(".table-cell")) {
            if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
                text.add(labeled.getText());
            }
        }
        for (AlignedSampleRow row : pane.table().getItems()) {
            for (TableColumn<AlignedSampleRow, ?> column : pane.table().getColumns()) {
                var value = column.getCellObservableValue(row);
                if (value != null && value.getValue() != null) {
                    String shown = String.valueOf(value.getValue());
                    if (!shown.isBlank()) {
                        text.add(shown);
                    }
                }
            }
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<com.stellar.platform.desktop.presentation.WorkspaceDisplayMode> displayMode(
            ActiveSignalWorkspacePane pane) {
        for (Node node : pane.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo
                    && combo.getValue() == com.stellar.platform.desktop.presentation.WorkspaceDisplayMode.PHYSICAL) {
                return (ComboBox<com.stellar.platform.desktop.presentation.WorkspaceDisplayMode>) combo;
            }
        }
        throw new AssertionError("physical display combo missing");
    }

    private static String joinedButtons(Node root) {
        List<String> labels = new ArrayList<>();
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Labeled labeled && labeled.getText() != null) {
                labels.add(labeled.getText());
            }
        }
        return String.join("|", labels);
    }
}
