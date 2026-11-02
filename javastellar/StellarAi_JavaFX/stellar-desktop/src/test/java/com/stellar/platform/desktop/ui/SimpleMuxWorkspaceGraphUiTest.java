package com.stellar.platform.desktop.ui;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.WorkspacePrecision;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
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
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signal Workspace and Graph for a processed simple-multiplex ASC recording.
 * The bytes match the MF4/BLF/ASC manifest in SimpleMuxRecordingParityTest.
 */
class SimpleMuxWorkspaceGraphUiTest {

    private static final long VIEW_END = 2_400_000_000L;
    private static final String DBC = """
            VERSION ""
            NS_ :
            BS_:
            BU_: ECU
            BO_ 1552 PowerMux: 8 ECU
             SG_ Selector M : 0|8@1+ (1,0) [0|3] "" ECU
             SG_ Voltage : 8|8@1+ (0.1,0) [0|25.5] "V" ECU
             SG_ Speed m0 : 16|16@1+ (0.5,0) [0|1000] "km/h" ECU
             SG_ Current m1 : 16|16@1- (0.1,-40) [-400|400] "A" ECU
             SG_ Torque m2 : 31|8@0+ (1,-10) [-10|245] "Nm" ECU
            VAL_ 1552 Selector 0 "Sleep" 1 "Drive" 2 "Charge" ;
            BA_DEF_ SG_ "GenSigInvalidValue" INT 0 0;
            BA_DEF_ SG_ "GenSigInactiveValue" INT 0 0;
            BA_ "GenSigInvalidValue" SG_ 1552 Speed 65535;
            BA_ "GenSigInactiveValue" SG_ 1552 Current 32767;
            """;

    @TempDir
    Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void processedMuxRecordingShowsSelectorLabelsAndOmitsInactiveBranches() throws Exception {
        List<CanonicalSignal> signals = decodeAsc();
        assertEquals(100L, at(signals, "Speed", 100_000_000L).rawValue());
        assertEquals(50.0, at(signals, "Speed", 100_000_000L).physicalValue(), 0.0);
        assertTrue(signals.stream().noneMatch(s -> "Speed".equals(s.signalName()) && s.timestampNanos() == 300_000_000L));
        assertTrue(signals.stream().noneMatch(s -> "Current".equals(s.signalName()) && s.timestampNanos() == 100_000_000L));
        assertEquals("Sleep", at(signals, "Selector", 100_000_000L).textualValue());
        assertEquals(100_000_000L, at(signals, "Selector", 100_000_000L).timestampNanos());

        List<AlignedSampleRow> rows = List.of(
                row(0, signals, 100_000_000L),
                row(1, signals, 300_000_000L),
                row(2, signals, 400_000_000L),
                row(3, signals, 600_000_000L));

        JavaFxTestSupport.runOnFx("mux workspace", () -> {
            ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1366, 768));
            stage.show();
            pane.resize(1366, 768);
            pane.applyCss();
            pane.layout();
            Method rebuild = ActiveSignalWorkspacePane.class.getDeclaredMethod("rebuildColumns", List.class);
            rebuild.setAccessible(true);
            rebuild.invoke(pane, List.of(entry("Selector", ""), entry("Speed", "km/h")));
            pane.table().getItems().setAll(rows);
            pane.layout();

            Field toolbarField = ActiveSignalWorkspacePane.class.getDeclaredField("toolbar");
            toolbarField.setAccessible(true);
            HBox toolbar = (HBox) toolbarField.get(pane);
            double toolbarPref = toolbar.prefHeight(1366);
            assertTrue(toolbarPref > 16 && toolbarPref < 48, "toolbar prefHeight=" + toolbarPref);

            Set<String> physical = cellText(pane);
            assertTrue(physical.contains("0 = Sleep"), physical.toString());
            assertTrue(physical.contains("1 = Drive"), physical.toString());
            assertTrue(physical.contains("2 = Charge"), physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(50.0)), physical.toString());
            assertTrue(physical.contains(AlignedSampleRow.MISSING), physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(65535 * 0.5)), physical.toString());
            assertFalse(String.join("\n", physical).contains("65535 = invalid"), physical.toString());

            displayMode(pane).setValue(com.stellar.platform.desktop.presentation.WorkspaceDisplayMode.RAW);
            pane.layout();
            Set<String> raw = cellText(pane);
            assertTrue(raw.contains("0 (Sleep)"), raw.toString());
            assertTrue(raw.contains("1 (Drive)"), raw.toString());
            assertFalse(joinedButtons(pane).contains("Unknown Order"));
            stage.close();
        });

        List<SignalSamplePoint> selector = List.of(
                point(at(signals, "Selector", 100_000_000L)),
                point(at(signals, "Selector", 300_000_000L)),
                point(at(signals, "Selector", 400_000_000L)));
        List<SignalSamplePoint> speed = List.of(
                point(at(signals, "Speed", 100_000_000L)),
                point(at(signals, "Speed", 200_000_000L)));
        var selectorBuckets = MinMaxLodReducer.reduce(selector, 20);
        var speedBuckets = MinMaxLodReducer.reduce(speed, 20);
        assertEquals("Sleep", selectorBuckets.getFirst().categoricalLabel());
        assertEquals(100_000_000L, selectorBuckets.getFirst().firstTimestampNanos());
        assertEquals(null, speedBuckets.getFirst().categoricalLabel());

        Path historical = temp.resolve("historical-snapshot.png");
        byte[] evidence = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3, 4};
        Files.write(historical, evidence);

        JavaFxTestSupport.runOnFx("mux graph", () -> {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            SignalCatalogKey selectorKey = key("Selector");
            SignalCatalogKey speedKey = key("Speed");
            canvas.setTracePresentation(java.util.Map.of(selectorKey, "Selector", speedKey, "Speed"), java.util.Map.of());
            canvas.setMinSize(1200, 640);
            canvas.setPrefSize(1200, 640);
            Stage stage = new Stage();
            stage.setScene(new Scene(canvas, 1200, 640));
            stage.show();
            canvas.resize(1200, 640);
            canvas.prepareSharedViewport(0L, VIEW_END);
            canvas.setSeries(List.of(
                    new GraphRenderSeries(selectorKey, "", selectorBuckets.size(), true, 0L, VIEW_END, selectorBuckets),
                    new GraphRenderSeries(speedKey, "km/h", speedBuckets.size(), true, 0L, VIEW_END, speedBuckets)));
            canvas.setAnalysisOverlays(List.of(
                    new AutomotiveSignalCanvas.AnalysisOverlay(100_000_000L, null, "Sleep"),
                    new AutomotiveSignalCanvas.AnalysisOverlay(100_000_000L, 300_000_000L, "Drive")));
            canvas.layout();
            canvas.flushDeferredPaintForTest();

            assertArrayEquals(evidence, Files.readAllBytes(historical));
            List<String> labels = canvas.paintedStateLabelsForTest();
            assertTrue(labels.contains("Sleep"), labels.toString());
            assertTrue(labels.contains("Drive"), labels.toString());
            assertTrue(labels.contains("Charge"), labels.toString());
            assertFalse(labels.contains("50"), labels.toString());

            WritableImage image = canvas.snapshot(null, null);
            PixelReader reader = image.getPixelReader();
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            Color stateColor = GraphSeriesPalette.at(0);
            int xSleep = plotX(width, 100_000_000L);
            int xDrive = plotX(width, 300_000_000L);
            int yEarly = strokeY(reader, width, height, xSleep + 4, stateColor);
            int yHold = strokeY(reader, width, height, xSleep + (int) ((xDrive - xSleep) * 0.7), stateColor);
            assertTrue(yEarly > 0 && yHold > 0, "selector stroke missing");
            assertTrue(Math.abs(yEarly - yHold) <= 8, "selector interpolated early=" + yEarly + " hold=" + yHold);

            Color speedColor = GraphSeriesPalette.at(1);
            var tracks = canvas.stackedFramesForTest();
            assertEquals(2, tracks.size());
            int speedTop = (int) tracks.get(1).plotTop();
            int speedBottom = (int) tracks.get(1).plotBottom();
            int xSpeed = plotX(width, 150_000_000L);
            assertTrue(strokeY(reader, width, speedTop, speedBottom, xSpeed, speedColor) > 0,
                    "active speed branch missing");
            assertEquals(-1, strokeY(reader, width, speedTop, speedBottom, plotX(width, 350_000_000L), speedColor),
                    "speed was fabricated across the inactive interval");

            assertEquals(100_000_000L, canvas.analysisOverlaysForTest().getFirst().startNanos());
            assertEquals(300_000_000L, canvas.analysisOverlaysForTest().get(1).endNanos());
            assertNotEquals(-1, strokeY(reader, width, height, plotX(width, 100_000_000L),
                    Color.web("#0E7490")));
            stage.close();
        });
        assertArrayEquals(evidence, Files.readAllBytes(historical));
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        assertTrue(used < 1536L * 1024 * 1024);
    }

    private List<CanonicalSignal> decodeAsc() throws Exception {
        Path dbc = temp.resolve("power.dbc");
        Files.writeString(dbc, DBC, StandardCharsets.UTF_8);
        Path asc = temp.resolve("power.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000000 1 610 Rx d 8 00 7D 64 00 00 00 00 00
                  0.200000000 1 610 Rx d 8 00 7D 64 00 00 00 00 00
                  0.300000000 1 610 Rx d 8 01 7D 38 FF 00 00 00 00
                  0.400000000 1 610 Rx d 8 02 7D 00 32 00 00 00 00
                  0.600000000 1 610 Rx d 8 00 7D FF FF 00 00 00 00
                """, StandardCharsets.UTF_8);
        DbcDatabase database = new DbcParser().parse(dbc);
        List<CanonicalSignal> signals = new ArrayList<>();
        MeasurementDecodePipeline.createDefault().process(
                asc, database, ReaderContext.ofBatchSize(8), signals::addAll);
        return signals;
    }

    private static AlignedSampleRow row(int index, List<CanonicalSignal> signals, long timestamp) {
        CanonicalSignal selector = at(signals, "Selector", timestamp);
        CanonicalSignal speed = find(signals, "Speed", timestamp);
        return new AlignedSampleRow(
                index,
                timestamp,
                new Double[] {selector.physicalValue(), speed == null ? null : speed.physicalValue()},
                new Long[] {selector.rawValue(), speed == null ? null : speed.rawValue()},
                new String[] {selector.textualValue(), speed == null ? null : speed.textualValue()},
                new SignalValidity[] {selector.validity(), speed == null ? null : speed.validity()});
    }

    private static CanonicalSignal at(List<CanonicalSignal> signals, String name, long timestamp) {
        CanonicalSignal found = find(signals, name, timestamp);
        if (found == null) {
            throw new AssertionError(name + " missing at " + timestamp);
        }
        return found;
    }

    private static CanonicalSignal find(List<CanonicalSignal> signals, String name, long timestamp) {
        for (CanonicalSignal signal : signals) {
            if (name.equals(signal.signalName()) && signal.timestampNanos() == timestamp) {
                return signal;
            }
        }
        return null;
    }

    private static SignalSamplePoint point(CanonicalSignal signal) {
        return new SignalSamplePoint(
                signal.timestampNanos(), signal.physicalValue(), signal.rawValue(),
                signal.textualValue(), signal.validity());
    }

    private static int plotX(int width, long timestamp) {
        double left = 82;
        double right = Math.max(left + 1, width - 18);
        return (int) (left + (timestamp / (double) VIEW_END) * (right - left));
    }

    private static int strokeY(PixelReader reader, int width, int height, int x, Color color) {
        return strokeY(reader, width, 24, height - 16, x, color);
    }

    private static int strokeY(PixelReader reader, int width, int y0, int y1, int x, Color color) {
        int bestY = -1;
        double best = 0.45;
        for (int y = y0; y < y1; y++) {
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

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "PowerMux", 0x610L, 1, "power.asc", "power.dbc");
    }

    private static SignalCatalogEntry entry(String name, String unit) {
        return new SignalCatalogEntry(key(name), unit, 4L, 100_000_000L, 600_000_000L, 0.0, 50.0, "power.asc", "power.dbc");
    }
}
