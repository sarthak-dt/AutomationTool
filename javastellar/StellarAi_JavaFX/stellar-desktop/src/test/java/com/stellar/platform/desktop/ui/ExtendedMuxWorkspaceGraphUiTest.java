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
 * Signal Workspace and Graph for an extended-multiplex ASC recording.
 * The bytes are the same ExtMux frames proved for MF4 and BLF.
 */
class ExtendedMuxWorkspaceGraphUiTest {

    private static final long VIEW_END = 1_200_000_000L;

    @TempDir
    Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void extendedRecordingShowsSelectorLabelsAndOnlyTheActiveLeaf() throws Exception {
        List<CanonicalSignal> signals = decodeAsc();
        assertEquals("Sleep", at(signals, "Group", 100_000_000L).textualValue());
        assertEquals(20.0, at(signals, "Torque", 100_000_000L).physicalValue(), 0.0);
        assertEquals(-60.0, at(signals, "Current", 200_000_000L).physicalValue(), 0.0);
        assertTrue(signals.stream().noneMatch(s -> "Speed".equals(s.signalName()) && s.timestampNanos() < 400_000_000L));
        assertEquals(40.0, at(signals, "Speed", 400_000_000L).physicalValue(), 0.0);
        assertEquals(41.0, at(signals, "Speed", 500_000_000L).physicalValue(), 0.0);

        JavaFxTestSupport.runOnFx("extended workspace", () -> {
            ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1366, 768));
            stage.show();
            pane.resize(1366, 768);
            pane.applyCss();
            pane.layout();
            Method rebuild = ActiveSignalWorkspacePane.class.getDeclaredMethod("rebuildColumns", List.class);
            rebuild.setAccessible(true);
            rebuild.invoke(pane, List.of(entry("Group", ""), entry("Speed", "km/h")));
            pane.table().getItems().setAll(List.of(
                    row(0, signals, 100_000_000L),
                    row(1, signals, 200_000_000L),
                    row(2, signals, 400_000_000L)));
            pane.layout();
            Field toolbarField = ActiveSignalWorkspacePane.class.getDeclaredField("toolbar");
            toolbarField.setAccessible(true);
            double toolbarPref = ((HBox) toolbarField.get(pane)).prefHeight(1366);
            assertTrue(toolbarPref > 16 && toolbarPref < 48, "toolbar prefHeight=" + toolbarPref);
            Set<String> physical = cellText(pane);
            assertTrue(physical.contains("0 = Sleep"), physical.toString());
            assertTrue(physical.contains("1 = Drive"), physical.toString());
            assertTrue(physical.contains("10 = Low"), physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(40.0)), physical.toString());
            assertTrue(physical.contains(AlignedSampleRow.MISSING), physical.toString());
            displayMode(pane).setValue(com.stellar.platform.desktop.presentation.WorkspaceDisplayMode.RAW);
            pane.layout();
            assertTrue(cellText(pane).contains("0 (Sleep)"));
            assertFalse(joinedButtons(pane).contains("Unknown Order"));
            stage.close();
        });

        var groupBuckets = MinMaxLodReducer.reduce(List.of(
                point(at(signals, "Group", 100_000_000L)),
                point(at(signals, "Group", 200_000_000L)),
                point(at(signals, "Group", 400_000_000L))), 20);
        var speedBuckets = MinMaxLodReducer.reduce(List.of(
                point(at(signals, "Speed", 400_000_000L)),
                point(at(signals, "Speed", 500_000_000L))), 20);
        assertEquals("Sleep", groupBuckets.getFirst().categoricalLabel());
        assertEquals(null, speedBuckets.getFirst().categoricalLabel());

        Path historical = temp.resolve("historical-snapshot.png");
        byte[] evidence = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 9, 8, 7};
        Files.write(historical, evidence);

        JavaFxTestSupport.runOnFx("extended graph", () -> {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            SignalCatalogKey group = key("Group");
            SignalCatalogKey speed = key("Speed");
            canvas.setTracePresentation(java.util.Map.of(group, "Group", speed, "Speed"), java.util.Map.of());
            canvas.setMinSize(1200, 640);
            canvas.setPrefSize(1200, 640);
            Stage stage = new Stage();
            stage.setScene(new Scene(canvas, 1200, 640));
            stage.show();
            canvas.resize(1200, 640);
            canvas.prepareSharedViewport(0L, VIEW_END);
            canvas.setSeries(List.of(
                    new GraphRenderSeries(group, "", groupBuckets.size(), true, 0L, VIEW_END, groupBuckets),
                    new GraphRenderSeries(speed, "km/h", speedBuckets.size(), true, 0L, VIEW_END, speedBuckets)));
            canvas.setAnalysisOverlays(List.of(
                    new AutomotiveSignalCanvas.AnalysisOverlay(100_000_000L, 200_000_000L, "Drive")));
            canvas.layout();
            canvas.flushDeferredPaintForTest();
            assertArrayEquals(evidence, Files.readAllBytes(historical));
            List<String> labels = canvas.paintedStateLabelsForTest();
            assertTrue(labels.contains("Sleep"), labels.toString());
            assertTrue(labels.contains("Drive"), labels.toString());
            assertFalse(labels.contains("40"), labels.toString());

            WritableImage image = canvas.snapshot(null, null);
            PixelReader reader = image.getPixelReader();
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int xSleep = plotX(width, 100_000_000L);
            int xDrive = plotX(width, 200_000_000L);
            Color state = GraphSeriesPalette.at(0);
            int early = strokeY(reader, width, 24, height - 16, xSleep + 4, state);
            int held = strokeY(reader, width, 24, height - 16, xSleep + (int) ((xDrive - xSleep) * 0.7), state);
            assertTrue(early > 0 && held > 0, "selector stroke missing");
            assertTrue(Math.abs(early - held) <= 8, "selector interpolated " + early + " " + held);

            var tracks = canvas.stackedFramesForTest();
            int top = (int) tracks.get(1).plotTop();
            int bottom = (int) tracks.get(1).plotBottom();
            Color speedColor = GraphSeriesPalette.at(1);
            assertEquals(-1, strokeY(reader, width, top, bottom, plotX(width, 200_000_000L), speedColor));
            assertTrue(strokeY(reader, width, top, bottom, plotX(width, 450_000_000L), speedColor) > 0);
            assertEquals(100_000_000L, canvas.analysisOverlaysForTest().getFirst().startNanos());
            assertEquals(200_000_000L, canvas.analysisOverlaysForTest().getFirst().endNanos());
            assertNotEquals(-1, strokeY(reader, width, 24, height - 16, plotX(width, 100_000_000L),
                    Color.web("#0E7490")));
            stage.close();
        });
        assertArrayEquals(evidence, Files.readAllBytes(historical));
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        assertTrue(used < 1536L * 1024 * 1024);
    }

    private List<CanonicalSignal> decodeAsc() throws Exception {
        Path dbc = temp.resolve("ext.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 1568 ExtMux: 8 ECU
                 SG_ Group M : 0|8@1+ (1,0) [0|255] "" ECU
                 SG_ Branch m1 : 8|8@1+ (1,0) [0|255] "" ECU
                 SG_ Voltage : 16|8@1+ (0.1,0) [0|25.5] "V" ECU
                 SG_ Current m2 : 24|16@1- (0.1,-40) [-400|400] "A" ECU
                 SG_ Speed m10 : 40|8@1+ (1,0) [0|255] "km/h" ECU
                 SG_ Torque m0 : 55|8@0+ (1,-10) [-10|245] "Nm" ECU
                 SG_ Flag m11 : 56|8@1+ (1,0) [0|255] "" ECU
                SG_MUL_VAL_ 1568 Branch Group 1-1;
                SG_MUL_VAL_ 1568 Current Group 1-1;
                SG_MUL_VAL_ 1568 Current Branch 2-2;
                SG_MUL_VAL_ 1568 Speed Group 10-12, 20-20;
                SG_MUL_VAL_ 1568 Torque Group 0-0;
                SG_MUL_VAL_ 1568 Flag Group 11-11;
                VAL_ 1568 Group 0 "Sleep" 1 "Drive" 10 "Low" 11 "Mid" 12 "High" 20 "Peak";
                """, StandardCharsets.UTF_8);
        Path asc = temp.resolve("ext.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000000 1 620 Rx d 8 00 09 64 00 00 00 1E 00
                  0.200000000 1 620 Rx d 8 01 02 64 38 FF 00 00 00
                  0.400000000 1 620 Rx d 8 0A 00 64 00 00 28 00 00
                  0.500000000 1 620 Rx d 8 0C 00 64 00 00 29 00 00
                """, StandardCharsets.UTF_8);
        DbcDatabase database = new DbcParser().parse(dbc);
        List<CanonicalSignal> signals = new ArrayList<>();
        MeasurementDecodePipeline.createDefault().process(asc, database, ReaderContext.ofBatchSize(8), signals::addAll);
        return signals;
    }

    private static AlignedSampleRow row(int index, List<CanonicalSignal> signals, long timestamp) {
        CanonicalSignal group = at(signals, "Group", timestamp);
        CanonicalSignal speed = find(signals, "Speed", timestamp);
        return new AlignedSampleRow(
                index, timestamp,
                new Double[] {group.physicalValue(), speed == null ? null : speed.physicalValue()},
                new Long[] {group.rawValue(), speed == null ? null : speed.rawValue()},
                new String[] {group.textualValue(), speed == null ? null : speed.textualValue()},
                new SignalValidity[] {group.validity(), speed == null ? null : speed.validity()});
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
        for (AlignedSampleRow row : pane.table().getItems()) {
            for (TableColumn<AlignedSampleRow, ?> column : pane.table().getColumns()) {
                var value = column.getCellObservableValue(row);
                if (value != null && value.getValue() != null && !String.valueOf(value.getValue()).isBlank()) {
                    text.add(String.valueOf(value.getValue()));
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
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "ExtMux", 0x620L, 1, "ext.asc", "ext.dbc");
    }

    private static SignalCatalogEntry entry(String name, String unit) {
        return new SignalCatalogEntry(key(name), unit, 4L, 100_000_000L, 500_000_000L, 0.0, 41.0, "ext.asc", "ext.dbc");
    }
}
