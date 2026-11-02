package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.processing.graph.SharedTimelineHit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlapMeasurementChooserTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void longNamesAndStaleChoicesStayExplicit() throws Exception {
        JavaFxTestSupport.runOnFx("overlap chooser", () -> {
            OverlapMeasurementChooser chooser = new OverlapMeasurementChooser();
            String longName = "utc-" + "project-measurement-".repeat(8) + "rear.dat";
            List<AutomotiveSignalCanvas.MeasurementChoice> choices = List.of(
                    choice("front", "front.dat", 0, 113),
                    choice("rear", longName, 1, 331),
                    choice("third", "third.dat", 2, 221));
            AtomicInteger applied = new AtomicInteger();
            AtomicReference<String> selected = new AtomicReference<>();
            chooser.show(null, 0, 0, choices, candidate -> {
                applied.incrementAndGet();
                selected.set(candidate.hit().measurementId());
            });
            assertEquals(3, chooser.labels().size());
            assertTrue(chooser.labels().get(1).contains(longName), chooser.labels().get(1));
            assertTrue(chooser.labels().get(1).contains("331.000 km/h"), chooser.labels().get(1));
            chooser.dismiss();
            chooser.choose("rear");
            assertEquals(0, applied.get());
            chooser.show(null, 0, 0, choices, candidate -> {
                applied.incrementAndGet();
                selected.set(candidate.hit().measurementId());
            });
            chooser.choose("rear");
            assertEquals(1, applied.get());
            assertEquals("rear", selected.get());
            chooser.choose("front");
            assertEquals(1, applied.get());
        });
    }

    private static AutomotiveSignalCanvas.MeasurementChoice choice(
            String id, String label, int segment, double value) {
        return new AutomotiveSignalCanvas.MeasurementChoice(
                new SharedTimelineHit.Hit(id, "VehicleSpeed", segment, 5L, 15L, 0L, 20L),
                value,
                "km/h",
                0,
                label);
    }
}
