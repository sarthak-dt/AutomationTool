package com.stellar.platform.desktop.ui;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.SampleDisplay;
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
 * Signal Workspace and Graph for labels loaded from a multiline {@code VAL_}
 * and a named {@code VAL_TABLE_} on a multiplex selector.
 */
class ValueDescriptionWorkspaceGraphUiTest {

    private static final long VIEW_END = 1_200_000_000L;

    @TempDir
    Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void decodedLabelsStayOnTheWorkspaceAndTheSelectorHolds() throws Exception {
        List<CanonicalSignal> signals = decodeAsc();
        CanonicalSignal sleep = at(signals, "HVBAT_STATE", 100_000_000L);
        CanonicalSignal ready = at(signals, "HVBAT_STATE", 200_000_000L);
        CanonicalSignal selector = at(signals, "Selector", 300_000_000L);
        assertEquals(0L, sleep.rawValue());
        assertEquals("Sleep", sleep.textualValue());
        assertEquals(3L, ready.rawValue());
        assertEquals("Ready", ready.textualValue());
        assertEquals(0L, selector.rawValue());
        assertEquals("Sleep", selector.textualValue());
        assertEquals(40.0, at(signals, "Speed", 300_000_000L).physicalValue(), 0.0);
        assertEquals(50.0, at(signals, "Speed", 360_000_000L).physicalValue(), 0.0);
        assertTrue(signals.stream().noneMatch(signal ->
                "Speed".equals(signal.signalName()) && signal.timestampNanos() == 400_000_000L));
        assertEquals("Drive", at(signals, "Selector", 400_000_000L).textualValue());

        JavaFxTestSupport.runOnFx("value workspace", () -> {
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
            pane.table().getItems().setAll(List.of(
                    row(0, signals, 300_000_000L),
                    row(1, signals, 400_000_000L),
                    row(2, signals, 360_000_000L)));
            pane.layout();
            Field toolbarField = ActiveSignalWorkspacePane.class.getDeclaredField("toolbar");
            toolbarField.setAccessible(true);
            double toolbarPref = ((HBox) toolbarField.get(pane)).prefHeight(1366);
            assertTrue(toolbarPref > 16 && toolbarPref < 48, "toolbar prefHeight=" + toolbarPref);
            Set<String> physical = cellText(pane);
            assertTrue(physical.contains("0 = Sleep"), physical.toString());
            assertTrue(physical.contains("1 = Drive"), physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(40.0)), physical.toString());
            assertTrue(physical.contains(AlignedSampleRow.MISSING), physical.toString());
            displayMode(pane).setValue(com.stellar.platform.desktop.presentation.WorkspaceDisplayMode.RAW);
            pane.layout();
            assertTrue(cellText(pane).contains("0 (Sleep)"), cellText(pane).toString());
            assertFalse(joinedButtons(pane).contains("Unknown Order"));
            stage.close();
        });

        var selectorBuckets = MinMaxLodReducer.reduce(List.of(
                point(selector),
                point(at(signals, "Selector", 360_000_000L)),
                point(at(signals, "Selector", 400_000_000L))), 20);
        var speedBuckets = MinMaxLodReducer.reduce(List.of(
                point(at(signals, "Speed", 300_000_000L)),
                point(at(signals, "Speed", 360_000_000L))), 20);
        assertEquals("Sleep", selectorBuckets.getFirst().categoricalLabel());
        assertEquals(0.0, selectorBuckets.getFirst().firstValue(), 0.0);

        Path historical = temp.resolve("historical-snapshot.png");
        byte[] evidence = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 4, 3, 2};
        Files.write(historical, evidence);

        JavaFxTestSupport.runOnFx("value graph", () -> {
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            SignalCatalogKey selectorKey = key("Selector", 0x610L);
            SignalCatalogKey speedKey = key("Speed", 0x610L);
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
                    new AutomotiveSignalCanvas.AnalysisOverlay(300_000_000L, 400_000_000L, "Drive")));
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
            var tracks = canvas.stackedFramesForTest();
            int selectorTop = Math.max(24, (int) tracks.getFirst().plotTop() - 6);
            int selectorBottom = Math.min(height - 16, (int) tracks.getFirst().plotBottom() + 20);
            int xSleep = plotX(width, 300_000_000L);
            int xDrive = plotX(width, 400_000_000L);
            Color state = GraphSeriesPalette.at(0);
            int early = strokeY(reader, width, selectorTop, selectorBottom, xSleep + 8, state, 0.85);
            int held = strokeY(reader, width, selectorTop, selectorBottom,
                    xSleep + (int) ((xDrive - xSleep) * 0.55), state, 0.85);
            assertTrue(early > 0 && held > 0, "selector stroke missing");
            assertTrue(Math.abs(early - held) <= 8, "selector interpolated " + early + " " + held);

            int top = (int) tracks.get(1).plotTop();
            int bottom = (int) tracks.get(1).plotBottom();
            Color speedColor = GraphSeriesPalette.at(1);
            assertTrue(strokeY(reader, width, top, bottom, plotX(width, 400_000_000L), speedColor, 0.45) < 0);
            assertTrue(strokeY(reader, width, top, bottom, plotX(width, 330_000_000L), speedColor, 0.45) > 0);
            canvas.movePointerForTest((xSleep + xDrive) / 2.0, tracks.getFirst().top() + 30);
            AutomotiveSignalCanvas.HoverPoint hover = canvas.hoverPointProperty().get();
            assertTrue(hover != null && hover.categoricalLabel() != null, String.valueOf(hover));
            String pointer = SampleDisplay.provenance(
                    hover.signalName(), hover.measurement(), hover.unit(), hover.value(),
                    hover.categoricalLabel(), hover.pointerTimestampNanos(), hover.timestampNanos(),
                    hover.bucketStartNanos(), hover.bucketEndNanos(), hover.bucketSampleCount(),
                    hover.valueRole(), hover.validityName(),
                    "Sleep".equals(hover.categoricalLabel()) ? 0L : 1L);
            assertTrue(pointer.contains(" = " + hover.categoricalLabel()), pointer);
            assertEquals(300_000_000L, canvas.analysisOverlaysForTest().getFirst().startNanos());
            assertEquals(400_000_000L, canvas.analysisOverlaysForTest().getFirst().endNanos());
            assertNotEquals(-1, strokeY(reader, width, 24, height - 16, plotX(width, 300_000_000L),
                    Color.web("#0E7490"), 0.85));
            stage.close();
        });
        assertArrayEquals(evidence, Files.readAllBytes(historical));
        assertEquals("0 = Sleep", SampleDisplay.physical(0.0, 0L, "Sleep", SignalValidity.VALID, WorkspacePrecision.AUTO));
        assertEquals(WorkspacePrecision.AUTO.formatPhysical(40.0),
                SampleDisplay.physical(40.0, 40L, null, SignalValidity.VALID, WorkspacePrecision.AUTO));
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        assertTrue(used < 1536L * 1024 * 1024);
    }

    private List<CanonicalSignal> decodeAsc() throws Exception {
        Path dbc = temp.resolve("values.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                VAL_TABLE_ GroupState 0 "Sleep" 1 "Drive" ;
                BO_ 256 Battery: 1 ECU
                 SG_ HVBAT_STATE : 0|8@1+ (1,0) [0|15] "" ECU
                BO_ 1552 Power: 2 ECU
                 SG_ Selector M : 0|8@1+ (1,0) [0|3] "" ECU
                 SG_ Speed m0 : 8|8@1+ (1,0) [0|255] "km/h" ECU
                SGTYPE_ GroupType : 8@1+ (1,0) [0|1] "" 0 , GroupState ;
                SIG_TYPE_REF_ 1552 Selector : GroupType ;
                VAL_ 256 HVBAT_STATE 0 "Sleep"
                3 "Ready" ;
                """, StandardCharsets.UTF_8);
        Path asc = temp.resolve("values.asc");
        Files.writeString(asc, """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000000 1 100 Rx d 1 00
                  0.200000000 1 100 Rx d 1 03
                  0.300000000 1 610 Rx d 2 00 28
                  0.360000000 1 610 Rx d 2 00 32
                  0.400000000 1 610 Rx d 2 01 28
                """, StandardCharsets.UTF_8);
        DbcDatabase database = new DbcParser().parse(dbc);
        List<CanonicalSignal> signals = new ArrayList<>();
        MeasurementDecodePipeline.createDefault().process(asc, database, ReaderContext.ofBatchSize(8), signals::addAll);
        return signals;
    }

    private static AlignedSampleRow row(int index, List<CanonicalSignal> signals, long timestamp) {
        CanonicalSignal selector = at(signals, "Selector", timestamp);
        CanonicalSignal speed = find(signals, "Speed", timestamp);
        return new AlignedSampleRow(
                index, timestamp,
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

    private static int strokeY(PixelReader reader, int width, int y0, int y1, int x, Color color, double limit) {
        int bestY = -1;
        double best = limit;
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

    private static SignalCatalogKey key(String name, long canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "Power", canId, 1, "values.asc", "values.dbc");
    }

    private static SignalCatalogEntry entry(String name, String unit) {
        return new SignalCatalogEntry(
                key(name, 0x610L), unit, 3L, 300_000_000L, 400_000_000L, 0.0, 50.0, "values.asc", "values.dbc");
    }
}
