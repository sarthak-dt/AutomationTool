package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveBrakingAnalyzerTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void continuousDecelerationIsOneBrakingEpisodeNotOneEventPerSample() {
        MapRouteModel route = deceleratingRoute(8, 80.0, 20.0);
        DriveBrakingSummary summary = new DriveBrakingAnalyzer()
                .analyze(null, null, route, 0, route.size() - 1, DriveSegmentThresholds.defaults());

        assertEquals(DriveBrakingSummary.Classification.INFERRED, summary.classification());
        assertEquals(1, summary.eventCount());
        assertEquals(1, summary.harshEventCount());
    }

    @Test
    void twoSeparatedDecelerationsAreTwoEpisodes() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        double lonValue = 77.59;
        double speedKph = 80;
        long t = 0;
        for (int episode = 0; episode < 2; episode++) {
            speedKph = 80;
            for (int i = 0; i < 6; i++) {
                lat.add(new SignalSamplePoint(t, 12.97, null, null));
                lon.add(new SignalSamplePoint(t, lonValue, null, null));
                t += SECOND;
                lonValue += (speedKph / 3.6) / 111_320.0;
                speedKph = i < 1 ? 80 : Math.max(0, speedKph - 20);
            }
            for (int i = 0; i < 4; i++) {
                lat.add(new SignalSamplePoint(t, 12.97, null, null));
                lon.add(new SignalSamplePoint(t, lonValue, null, null));
                t += SECOND;
                lonValue += (40.0 / 3.6) / 111_320.0;
            }
        }
        MapRouteModel route = MapRouteBuilder.build(lat, lon, null);
        DriveBrakingSummary summary = new DriveBrakingAnalyzer()
                .analyze(null, null, route, 0, route.size() - 1, DriveSegmentThresholds.defaults());

        assertEquals(2, summary.eventCount());
    }

    @Test
    void zeroDtDoesNotCreateABrakingEvent() {
        List<SignalSamplePoint> lat = List.of(
                new SignalSamplePoint(0, 12.97, null, null),
                new SignalSamplePoint(0, 12.98, null, null),
                new SignalSamplePoint(SECOND, 12.99, null, null));
        List<SignalSamplePoint> lon = List.of(
                new SignalSamplePoint(0, 77.59, null, null),
                new SignalSamplePoint(0, 77.60, null, null),
                new SignalSamplePoint(SECOND, 77.61, null, null));
        MapRouteModel route = MapRouteBuilder.build(lat, lon, null);
        DriveBrakingSummary summary = new DriveBrakingAnalyzer()
                .analyze(null, null, route, 0, route.size() - 1, DriveSegmentThresholds.defaults());

        assertTrue(summary.eventCount() <= 1);
    }

    private static MapRouteModel deceleratingRoute(int samples, double startKph, double dropPerSecond) {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        double lonValue = 77.59;
        double speedKph = startKph;
        for (int i = 0; i < samples; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 12.97, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, lonValue, null, null));
            lonValue += (speedKph / 3.6) / 111_320.0;
            speedKph = Math.max(0, speedKph - dropPerSecond);
        }
        return MapRouteBuilder.build(lat, lon, null);
    }
}
