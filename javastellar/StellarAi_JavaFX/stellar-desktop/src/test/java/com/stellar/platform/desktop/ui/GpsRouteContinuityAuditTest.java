package com.stellar.platform.desktop.ui;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.mf4.Mdf3SignalReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.map.GeoDistance;
import com.stellar.platform.processing.map.MapRouteBuildOptions;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.RouteLodIndex;
import com.stellar.platform.processing.map.ValidatedRoute;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only continuity audit of the two Xenon DAT recordings used on the GPS map.
 * Does not modify the measurement files.
 */
class GpsRouteContinuityAuditTest {

    private static final Path DAT = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\dat files 3.1\\.dat files 3.1");
    private static final String FILE_A =
            "Xenon new script C910P41 2020-02-03 12-39-52-680142 Partition 0.dat";
    private static final String FILE_C =
            "Xenon new script C910P41 2020-02-04 13-42-03-688072 Partition 0.dat";
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\gps-continuity.txt");

    @Test
    void xenonDatRoutesDoNotDrawAcrossRecordedBreaks() throws Exception {
        StringBuilder notes = new StringBuilder();
        notes.append("maxJumpMeters=").append(MapRouteBuildOptions.defaults().maxJumpMeters()).append('\n');
        notes.append("maxTimeGapNanos=").append(MapRouteBuildOptions.defaults().maxTimeGapNanos()).append('\n');
        Audit a = audit(DAT.resolve(FILE_A), notes);
        Audit c = audit(DAT.resolve(FILE_C), notes);
        notes.append("recordingsJoined=false builderDoesNotConnectFiles\n");
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, notes.toString());
        assertTrue(a.accepted >= 2);
        assertTrue(c.accepted >= 2);
        assertEquals(0, a.breaks, a.name);
        assertTrue(c.breaks >= 129, c.name + " breaks=" + c.breaks);
        assertFalse(a.drewAcrossBreak, a.name);
        assertFalse(c.drewAcrossBreak, c.name);
        assertTrue(a.maxUnbrokenStepMeters <= MapRouteBuildOptions.defaults().maxJumpMeters() + 0.01, a.name);
        assertTrue(c.maxUnbrokenStepMeters <= MapRouteBuildOptions.defaults().maxJumpMeters() + 0.01, c.name);
        assertTrue(a.maxUnbrokenKph <= MapRouteBuilder.MAX_GROUNDED_SPEED_KPH, a.name);
        assertTrue(c.maxUnbrokenKph <= MapRouteBuilder.MAX_GROUNDED_SPEED_KPH, c.name + " kph=" + c.maxUnbrokenKph);
    }

    private static Audit audit(Path file, StringBuilder notes) throws Exception {
        assertTrue(Files.isRegularFile(file), file.toString());
        List<CanonicalMeasurementSample> samples = new ArrayList<>();
        new Mdf3SignalReader().read(file, ReaderContext.ofBatchSize(256), samples::addAll, warning -> { });
        List<SignalSamplePoint> latitude = new ArrayList<>();
        List<SignalSamplePoint> longitude = new ArrayList<>();
        int invalidLat = 0;
        int invalidLon = 0;
        long previousLat = Long.MIN_VALUE;
        int latBackwards = 0;
        int latDuplicate = 0;
        for (CanonicalMeasurementSample sample : samples) {
            if ("Latitude".equals(sample.signalName())) {
                if (!GeoDistance.validLatitude(sample.physicalValue())) {
                    invalidLat++;
                }
                if (previousLat != Long.MIN_VALUE) {
                    if (sample.timestampNanos() < previousLat) {
                        latBackwards++;
                    } else if (sample.timestampNanos() == previousLat) {
                        latDuplicate++;
                    }
                }
                previousLat = sample.timestampNanos();
                latitude.add(new SignalSamplePoint(sample.timestampNanos(), sample.physicalValue(), null, null));
            } else if ("Longitude".equals(sample.signalName())) {
                if (!GeoDistance.validLongitude(sample.physicalValue())) {
                    invalidLon++;
                }
                longitude.add(new SignalSamplePoint(sample.timestampNanos(), sample.physicalValue(), null, null));
            }
        }
        ValidatedRoute validated = MapRouteBuilder.validateAndBuild(
                latitude, longitude, null, MapRouteBuildOptions.defaults());
        MapRouteModel model = validated.model();
        Stats steps = new Stats();
        Stats gaps = new Stats();
        int nonMonotonic = 0;
        int duplicateTime = 0;
        double maxUnbroken = 0;
        for (int i = 1; i < model.size(); i++) {
            double meters = GeoDistance.haversineMeters(
                    model.latitude(i - 1), model.longitude(i - 1), model.latitude(i), model.longitude(i));
            long dt = model.timestampNanos(i) - model.timestampNanos(i - 1);
            if (dt < 0) {
                nonMonotonic++;
            } else if (dt == 0) {
                duplicateTime++;
            }
            double kph = dt > 0 ? (meters / (dt / 1e9)) * 3.6 : Double.POSITIVE_INFINITY;
            if (model.segmentBreakBefore(i)) {
                gaps.add(meters, dt, kph);
                if (kph >= 300 && kph < 5_000 && meters < 1_000) {
                    notes.append(String.format(Locale.ROOT,
                            "fastGap i=%d meters=%.3f dtNs=%d kph=%.1f from=%.7f,%.7f to=%.7f,%.7f%n",
                            i, meters, dt, kph,
                            model.latitude(i - 1), model.longitude(i - 1),
                            model.latitude(i), model.longitude(i)));
                }
            } else {
                steps.add(meters, dt, kph);
                if (meters > maxUnbroken) {
                    maxUnbroken = meters;
                }
                if (kph >= 200) {
                    double back = i + 1 < model.size()
                            ? GeoDistance.haversineMeters(
                            model.latitude(i - 1), model.longitude(i - 1),
                            model.latitude(i + 1), model.longitude(i + 1))
                            : Double.NaN;
                    notes.append(String.format(Locale.ROOT,
                            "fastStep i=%d meters=%.3f dtNs=%d t0=%d t1=%d unit=relative-ns kph=%.1f from=%.7f,%.7f to=%.7f,%.7f returnMeters=%.3f%n",
                            i, meters, dt, model.timestampNanos(i - 1), model.timestampNanos(i), kph,
                            model.latitude(i - 1), model.longitude(i - 1),
                            model.latitude(i), model.longitude(i), back));
                }
            }
        }
        RouteLodIndex lod = new RouteLodIndex(model);
        int[] coarse = lod.indices(1e9);
        int drawn = 0;
        int drawnAcrossBreak = 0;
        double maxDrawnChord = 0;
        for (int i = 1; i < coarse.length; i++) {
            int from = coarse[i - 1];
            int to = coarse[i];
            if (model.hasBreakBetween(from, to)) {
                continue;
            }
            drawn++;
            double chord = GeoDistance.haversineMeters(
                    model.latitude(from), model.longitude(from), model.latitude(to), model.longitude(to));
            if (chord > maxDrawnChord) {
                maxDrawnChord = chord;
            }
            if (spansBreak(model, from, to)) {
                drawnAcrossBreak++;
            }
        }
        notes.append("file=").append(file.getFileName()).append('\n');
        notes.append(String.format(Locale.ROOT,
                "rawLat=%d rawLon=%d invalidLat=%d invalidLon=%d latBackwards=%d latDuplicate=%d%n",
                latitude.size(), longitude.size(), invalidLat, invalidLon, latBackwards, latDuplicate));
        notes.append(String.format(Locale.ROOT,
                "accepted=%d breaks=%d distanceMeters=%.3f firstT=%d lastT=%d first=%.7f,%.7f last=%.7f,%.7f%n",
                model.size(), gaps.count, model.totalDistanceMeters(),
                model.firstTimestampNanos(), model.lastTimestampNanos(),
                model.latitude(0), model.longitude(0),
                model.latitude(model.size() - 1), model.longitude(model.size() - 1)));
        notes.append("acceptedSteps ").append(steps).append('\n');
        notes.append("breaks ").append(gaps).append('\n');
        notes.append(String.format(Locale.ROOT,
                "acceptedTime nonMonotonic=%d duplicate=%d%n", nonMonotonic, duplicateTime));
        notes.append(String.format(Locale.ROOT,
                "coarseLodPoints=%d drawnChords=%d drawnAcrossBreak=%d maxDrawnChordMeters=%.3f%n",
                coarse.length, drawn, drawnAcrossBreak, maxDrawnChord));
        return new Audit(file.getFileName().toString(), model.size(), gaps.count, maxUnbroken, steps.maxKph,
                drawnAcrossBreak > 0);
    }

    private static boolean spansBreak(MapRouteModel model, int from, int to) {
        for (int i = from + 1; i <= to; i++) {
            if (model.segmentBreakBefore(i)) {
                return true;
            }
        }
        return false;
    }

    private record Audit(String name, int accepted, int breaks, double maxUnbrokenStepMeters,
                         double maxUnbrokenKph, boolean drewAcrossBreak) {
    }

    private static final class Stats {
        int count;
        double maxMeters;
        double sumMeters;
        long maxDt = Long.MIN_VALUE;
        long minPositiveDt = Long.MAX_VALUE;
        double maxKph;
        int over300kph;
        int over1000m;
        int over10km;
        int over100km;

        void add(double meters, long dt, double kph) {
            count++;
            sumMeters += meters;
            if (meters > maxMeters) {
                maxMeters = meters;
            }
            if (dt > maxDt) {
                maxDt = dt;
            }
            if (dt > 0 && dt < minPositiveDt) {
                minPositiveDt = dt;
            }
            if (kph > maxKph) {
                maxKph = kph;
            }
            if (kph > 300) {
                over300kph++;
            }
            if (meters > 1_000) {
                over1000m++;
            }
            if (meters > 10_000) {
                over10km++;
            }
            if (meters > 100_000) {
                over100km++;
            }
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "n=%d maxMeters=%.3f meanMeters=%.3f maxDtNs=%d minPositiveDtNs=%s maxKph=%.3f over300kph=%d over1000m=%d over10km=%d over100km=%d",
                    count,
                    maxMeters,
                    count == 0 ? 0 : sumMeters / count,
                    maxDt,
                    minPositiveDt == Long.MAX_VALUE ? "none" : Long.toString(minPositiveDt),
                    maxKph,
                    over300kph,
                    over1000m,
                    over10km,
                    over100km);
        }
    }
}
