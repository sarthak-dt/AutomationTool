package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsType;
import com.stellar.platform.analysis.advanced.EnvelopeMode;
import com.stellar.platform.analysis.advanced.FilterPhaseMode;
import com.stellar.platform.analysis.advanced.ResampleMethod;
import com.stellar.platform.analysis.advanced.SampleHandling;
import com.stellar.platform.analysis.advanced.WindowFunction;
import com.stellar.platform.analysis.advanced.WindowUnit;
import com.stellar.platform.analysis.advanced.WindowedStatistic;
import com.stellar.platform.analysis.advanced.CrossingMode;
import com.stellar.platform.desktop.StellarDesktopApp;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedAnalyticsLayoutTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void operationShowsOnlyItsFieldsAndPreservesSelections() throws Exception {
        AtomicReference<List<String>> lowPass = new AtomicReference<>();
        AtomicReference<List<String>> moving = new AtomicReference<>();
        AtomicReference<List<String>> fft = new AtomicReference<>();
        AtomicReference<List<String>> psd = new AtomicReference<>();
        AtomicReference<List<String>> lag = new AtomicReference<>();
        AtomicReference<List<String>> restored = new AtomicReference<>();
        AtomicReference<AdvancedAnalyticsType> restoredOp = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        JavaFxTestSupport.runOnFx("fields", () -> {
            AdvancedAnalyticsPane pane = new AdvancedAnalyticsPane();
            pane.setRunHandler(spec -> runs.incrementAndGet());
            pane.selectCategory(AdvancedAnalyticsPane.Category.TIME);
            pane.selectOperation(AdvancedAnalyticsType.LOW_PASS_FILTER);
            lowPass.set(pane.visibleFieldLabels());
            pane.selectOperation(AdvancedAnalyticsType.MOVING_AVERAGE);
            moving.set(pane.visibleFieldLabels());
            pane.selectCategory(AdvancedAnalyticsPane.Category.FREQUENCY);
            pane.selectOperation(AdvancedAnalyticsType.FFT_SPECTRUM);
            fft.set(pane.visibleFieldLabels());
            pane.selectOperation(AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY);
            psd.set(pane.visibleFieldLabels());
            pane.selectCategory(AdvancedAnalyticsPane.Category.COMPARISON);
            pane.selectOperation(AdvancedAnalyticsType.LAG_ANALYSIS);
            lag.set(pane.visibleFieldLabels());
            pane.selectCategory(AdvancedAnalyticsPane.Category.TIME);
            restored.set(pane.visibleFieldLabels());
            restoredOp.set(pane.selectedOperation());
        });
        assertTrue(lowPass.get().contains("Cutoff Frequency"));
        assertTrue(lowPass.get().contains("Filter Mode"));
        assertTrue(lowPass.get().contains("Order"));
        assertFalse(lowPass.get().contains("Maximum Lag"));
        assertFalse(lowPass.get().contains("FFT Size"));
        assertFalse(lowPass.get().contains("Welch Segment"));
        assertFalse(lowPass.get().contains("Alpha"));
        assertTrue(moving.get().contains("Window"));
        assertTrue(moving.get().contains("Window Unit"));
        assertFalse(moving.get().contains("Cutoff Frequency"));
        assertTrue(fft.get().contains("Window Function"));
        assertTrue(fft.get().contains("FFT Size"));
        assertFalse(fft.get().contains("Cutoff Frequency"));
        assertFalse(fft.get().contains("Maximum Lag"));
        assertFalse(fft.get().contains("Welch Segment"));
        assertTrue(psd.get().contains("Welch Segment"));
        assertTrue(psd.get().contains("Welch Overlap"));
        assertFalse(psd.get().contains("FFT Size"));
        assertTrue(lag.get().contains("Maximum Lag"));
        assertFalse(lag.get().contains("Cutoff Frequency"));
        assertFalse(lag.get().contains("FFT Size"));
        assertEquals(AdvancedAnalyticsType.MOVING_AVERAGE, restoredOp.get());
        assertEquals(moving.get(), restored.get());
        assertEquals(0, runs.get());
    }

    @Test
    void parameterValuesPassedToTheEngineStayTheSame() throws Exception {
        AtomicReference<AdvancedAnalyticsPane.AdvancedRunSpec> defaults = new AtomicReference<>();
        AtomicReference<AdvancedAnalyticsPane.AdvancedRunSpec> edited = new AtomicReference<>();
        AtomicReference<AdvancedAnalyticsPane.AdvancedRunSpec> hiddenStillPassed = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        JavaFxTestSupport.runOnFx("spec", () -> {
            AdvancedAnalyticsPane pane = new AdvancedAnalyticsPane();
            pane.setRunHandler(spec -> runs.incrementAndGet());
            defaults.set(pane.specFor(AdvancedAnalyticsType.LOW_PASS_FILTER));
            pane.editFilterParameters("10", "40", "4");
            pane.selectOperation(AdvancedAnalyticsType.MOVING_AVERAGE);
            edited.set(pane.currentSpec());
            hiddenStillPassed.set(pane.specFor(AdvancedAnalyticsType.LOW_PASS_FILTER));
        });
        AdvancedAnalyticsPane.AdvancedRunSpec spec = defaults.get();
        assertEquals(AdvancedAnalyticsType.LOW_PASS_FILTER, spec.type());
        assertEquals("VIEWPORT", spec.range());
        assertEquals(SampleHandling.ORIGINAL, spec.sampleHandling());
        assertEquals(ResampleMethod.LINEAR_INTERPOLATION, spec.resampleMethod());
        assertEquals(0d, spec.targetRateHz());
        assertEquals(WindowFunction.HANN, spec.window());
        assertEquals(0, spec.fftSize());
        assertEquals(256, spec.welchSegment());
        assertEquals(128, spec.welchOverlap());
        assertEquals(0d, spec.lowCutoffHz());
        assertEquals(0d, spec.highCutoffHz());
        assertEquals(2, spec.filterOrder());
        assertEquals(FilterPhaseMode.ZERO_PHASE, spec.phaseMode());
        assertEquals(100d, spec.smoothWindow());
        assertEquals(WindowUnit.MILLISECONDS, spec.smoothUnit());
        assertEquals(0.2d, spec.alpha());
        assertEquals(CrossingMode.ALL, spec.crossingMode());
        assertEquals(WindowedStatistic.RMS, spec.windowedStatistic());
        assertEquals(EnvelopeMode.ABS_PEAK, spec.envelopeMode());
        assertEquals(500_000_000L, spec.maxLagNanos());
        assertFalse(spec.persistDerived());
        assertEquals(AdvancedAnalyticsType.MOVING_AVERAGE, edited.get().type());
        assertEquals(100d, edited.get().smoothWindow());
        assertEquals(10d, hiddenStillPassed.get().lowCutoffHz());
        assertEquals(40d, hiddenStillPassed.get().highCutoffHz());
        assertEquals(4, hiddenStillPassed.get().filterOrder());
        assertEquals(0, runs.get());
    }

    @Test
    void labelsStayReadableAtLaptopWidths() throws Exception {
        JavaFxTestSupport.runOnFx("layout", () -> {
            AdvancedAnalyticsPane pane = new AdvancedAnalyticsPane();
            Stage stage = new Stage();
            Scene scene = new Scene(pane, 1366, 640);
            var css = StellarDesktopApp.class.getResource("/css/stellar-desktop.css");
            assertTrue(css != null);
            scene.getStylesheets().add(css.toExternalForm());
            stage.setScene(scene);
            pane.selectCategory(AdvancedAnalyticsPane.Category.TIME);
            pane.selectOperation(AdvancedAnalyticsType.LOW_PASS_FILTER);
            for (double width : new double[] {1366, 1100, 900}) {
                pane.setMinWidth(width);
                pane.setPrefWidth(width);
                pane.setMaxWidth(width);
                stage.setWidth(width);
                stage.setHeight(640);
                stage.show();
                pane.applyCss();
                pane.layout();
                double limit = ((Region) pane).getWidth();
                double maxX = 0;
                for (Node child : pane.getChildren()) {
                    if (!child.isManaged()) {
                        continue;
                    }
                    maxX = Math.max(maxX, child.getBoundsInParent().getMaxX());
                }
                assertTrue(maxX <= limit + 2, "horizontal overflow " + maxX + " inside " + limit);
                for (Node node : pane.lookupAll(".analytics-field-label")) {
                    if (node instanceof Labeled label && label.isVisible() && label.getText() != null) {
                        assertFalse(label.getText().contains("..."), label.getText());
                        assertFalse(label.getText().contains("\u2026"), label.getText());
                        assertTrue(label.getWidth() + 2 >= label.prefWidth(-1),
                                label.getText() + " compressed to " + label.getWidth());
                    }
                }
                for (Node node : pane.lookupAll(".analytics-unit")) {
                    if (node instanceof Labeled label && shown(label)) {
                        assertFalse(label.getText().contains("\u2026"), label.getText());
                        assertTrue(label.getWidth() + 1 >= label.prefWidth(-1), label.getText());
                    }
                }
                for (Node node : pane.lookupAll(".combo-box > .list-cell")) {
                    if (node instanceof Labeled label && label.isVisible() && label.getText() != null) {
                        assertFalse(label.getText().contains("\u2026"), label.getText());
                        assertFalse(label.getText().contains("_"), label.getText());
                    }
                }
                if (width == 1366d) {
                    pane.selectCategory(AdvancedAnalyticsPane.Category.FREQUENCY);
                    pane.selectOperation(AdvancedAnalyticsType.FFT_SPECTRUM);
                    pane.applyCss();
                    pane.layout();
                    pane.selectCategory(AdvancedAnalyticsPane.Category.COMPARISON);
                    pane.selectOperation(AdvancedAnalyticsType.LAG_ANALYSIS);
                    pane.applyCss();
                    pane.layout();
                    assertTrue(pane.visibleFieldLabels().contains("Maximum Lag"), pane.visibleFieldLabels().toString());
                    assertFalse(pane.visibleFieldLabels().contains("Cutoff Frequency"), pane.visibleFieldLabels().toString());
                    assertFalse(pane.visibleFieldLabels().contains("Target Rate"), pane.visibleFieldLabels().toString());
                    boolean sawMilliseconds = false;
                    for (Node node : pane.lookupAll(".analytics-unit")) {
                        if (!(node instanceof Labeled label) || !shown(label)) {
                            continue;
                        }
                        assertFalse(label.getText().contains("\u2026"), label.getText());
                        assertTrue(label.getWidth() + 1 >= label.prefWidth(-1),
                                label.getText() + " width " + label.getWidth());
                        sawMilliseconds |= "ms".equals(label.getText());
                    }
                    assertTrue(sawMilliseconds, pane.visibleFieldLabels().toString());
                    pane.selectCategory(AdvancedAnalyticsPane.Category.TIME);
                    pane.selectOperation(AdvancedAnalyticsType.LOW_PASS_FILTER);
                }
            }
            stage.close();
        });
    }

    private static boolean shown(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible() || !current.isManaged()) {
                return false;
            }
        }
        return true;
    }

}
