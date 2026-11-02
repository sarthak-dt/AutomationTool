package com.stellar.platform.processing.export;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignedSignalCsvExporterTest {

    @TempDir
    Path temp;

    @Test
    void exportRowCountIsTheFullTimelineNotTheStorePage() throws Exception {
        int total = 5_000;
        SignalCatalogKey speed = key("VehicleSpeed", SignalSourceType.DBC_CAN, 1, 0x100L, "city.mf4", "body.dbc");
        Map<SignalCatalogKey, List<SignalSamplePoint>> series = new LinkedHashMap<>();
        series.put(speed, samples(total, 1L));
        AtomicInteger maxLimit = new AtomicInteger();
        MapStore store = new MapStore(series, 1_000, maxLimit, null);
        Path csv = temp.resolve("all.csv");

        AlignedSignalCsvExporter.ExportResult result = new AlignedSignalCsvExporter().export(
                store, List.of(entry(speed, "km/h")), AlignedSignalCsvExporter.ValueMode.PHYSICAL, csv, new AtomicBoolean());

        assertFalse(result.cancelled());
        assertEquals(total, result.rowCount());
        assertTrue(maxLimit.get() <= AlignedSignalCsvExporter.READ_PAGE);
        assertTrue(maxLimit.get() < total);
        List<String> lines = Files.readAllLines(csv);
        assertEquals(total + 1, lines.size());
        assertTrue(lines.getFirst().startsWith("index,timestamp_ns,"));
        assertTrue(lines.getFirst().contains("VehicleSpeed"));
        assertTrue(lines.getFirst().contains("city.mf4"));
        assertTrue(lines.getFirst().contains("km/h"));
        assertTrue(lines.get(1).startsWith("0,1,"));
        assertTrue(lines.get(total).startsWith((total - 1) + "," + total + ","));
    }

    @Test
    void missingSamplesStayBlankAndZeroIsKept() throws Exception {
        SignalCatalogKey a = key("Speed", SignalSourceType.DBC_CAN, 1, 0x100L, "a.mf4", "a.dbc");
        SignalCatalogKey b = key("Speed", SignalSourceType.DBC_CAN, 2, 0x200L, "b.mf4", "b.dbc");
        Map<SignalCatalogKey, List<SignalSamplePoint>> series = new LinkedHashMap<>();
        series.put(a, List.of(
                new SignalSamplePoint(1L, 0.0, 0L, null),
                new SignalSamplePoint(3L, 5.0, 5L, null)));
        series.put(b, List.of(new SignalSamplePoint(2L, 9.0, 9L, null)));
        Path csv = temp.resolve("gaps.csv");

        AlignedSignalCsvExporter.ExportResult result = new AlignedSignalCsvExporter().export(
                new MapStore(series, 100, new AtomicInteger(), null),
                List.of(entry(a, "km/h"), entry(b, "km/h")),
                AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                csv,
                new AtomicBoolean());

        assertEquals(3L, result.rowCount());
        List<String> lines = Files.readAllLines(csv);
        List<String> headers = AlignedSignalCsvExporter.columnHeaders(List.of(entry(a, "km/h"), entry(b, "km/h")));
        assertEquals(2, headers.size());
        assertTrue(headers.get(0).contains("a.mf4"));
        assertTrue(headers.get(1).contains("b.mf4"));
        assertFalse(headers.get(0).equals(headers.get(1)));
        assertEquals("0,1,0.0,", lines.get(1));
        assertEquals("1,2,,9.0", lines.get(2));
        assertEquals("2,3,5.0,", lines.get(3));
    }

    @Test
    void rawModeCategoricalAndInterruptedExport() throws Exception {
        SignalCatalogKey flag = key("BrakeActive", SignalSourceType.DBC_CAN, 1, 0x300L, "run.mf4", "body.dbc");
        SignalCatalogKey nativeSpeed = key("EngineSpeed", SignalSourceType.MF4_NATIVE, 3, 1L, "native.mf4", "");
        Map<SignalCatalogKey, List<SignalSamplePoint>> series = new LinkedHashMap<>();
        series.put(flag, List.of(new SignalSamplePoint(10L, Double.NaN, null, "FALSE")));
        series.put(nativeSpeed, List.of(new SignalSamplePoint(10L, 800.0, 800L, null)));
        Path raw = temp.resolve("raw.csv");
        AlignedSignalCsvExporter.ExportResult physical = new AlignedSignalCsvExporter().export(
                new MapStore(series, 100, new AtomicInteger(), null),
                List.of(entry(flag, ""), entry(nativeSpeed, "rpm")),
                AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                raw,
                new AtomicBoolean());
        assertEquals(1L, physical.rowCount());
        String line = Files.readAllLines(raw).get(1);
        assertEquals("0,10,FALSE,800.0", line);
        String header = Files.readAllLines(raw).getFirst();
        assertTrue(header.contains("native.mf4"));
        assertTrue(header.contains("rpm"));

        Path rawMode = temp.resolve("raw-mode.csv");
        new AlignedSignalCsvExporter().export(
                new MapStore(series, 100, new AtomicInteger(), null),
                List.of(entry(flag, ""), entry(nativeSpeed, "rpm")),
                AlignedSignalCsvExporter.ValueMode.RAW,
                rawMode,
                new AtomicBoolean());
        assertEquals("0,10,FALSE,800", Files.readAllLines(rawMode).get(1));

        AtomicBoolean cancel = new AtomicBoolean();
        SignalCatalogKey many = key("Long", SignalSourceType.DBC_CAN, 1, 1L, "long.mf4", "v.dbc");
        Map<SignalCatalogKey, List<SignalSamplePoint>> longSeries = Map.of(many, samples(5_000, 1L));
        Path interrupted = temp.resolve("partial.csv");
        AlignedSignalCsvExporter.ExportResult stopped = new AlignedSignalCsvExporter().export(
                new MapStore(longSeries, 1_000, new AtomicInteger(), cancel),
                List.of(entry(many, "u")),
                AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                interrupted,
                cancel);
        assertTrue(stopped.cancelled());
        assertFalse(Files.exists(interrupted));
    }

    @Test
    void enumeratedPhysicalCsvMatchesTheSameNumbersWithoutLabels() throws Exception {
        SignalCatalogKey state = key("HVBAT_STATE", SignalSourceType.DBC_CAN, 1, 256L, "pack.mf4", "hvbat.dbc");
        List<SignalSamplePoint> labeled = List.of(
                new SignalSamplePoint(10L, 0.0, 0L, "Sleep", SignalValidity.VALID),
                new SignalSamplePoint(20L, 3.0, 3L, "Ready", SignalValidity.VALID),
                new SignalSamplePoint(30L, 16.0, 16L, null, SignalValidity.VALID));
        List<SignalSamplePoint> numbers = List.of(
                new SignalSamplePoint(10L, 0.0, 0L, null, SignalValidity.VALID),
                new SignalSamplePoint(20L, 3.0, 3L, null, SignalValidity.VALID),
                new SignalSamplePoint(30L, 16.0, 16L, null, SignalValidity.VALID));
        Path withLabels = temp.resolve("labeled.csv");
        Path plain = temp.resolve("plain.csv");
        new AlignedSignalCsvExporter().export(
                new MapStore(Map.of(state, labeled), 100, new AtomicInteger(), null),
                List.of(entry(state, "")),
                AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                withLabels,
                new AtomicBoolean());
        new AlignedSignalCsvExporter().export(
                new MapStore(Map.of(state, numbers), 100, new AtomicInteger(), null),
                List.of(entry(state, "")),
                AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                plain,
                new AtomicBoolean());
        byte[] labeledBytes = Files.readAllBytes(withLabels);
        assertEquals(new String(Files.readAllBytes(plain), java.nio.charset.StandardCharsets.UTF_8),
                new String(labeledBytes, java.nio.charset.StandardCharsets.UTF_8));
        String text = new String(labeledBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(text.contains("Sleep"));
        assertFalse(text.contains("Ready"));
        assertTrue(text.contains("0.0"));
        assertTrue(text.contains("3.0"));
        assertTrue(text.contains("16.0"));
    }

    @Test
    void quotesCommasAndQuotesInNames() {
        assertEquals("plain", AlignedSignalCsvExporter.csv("plain"));
        assertEquals("\"a,b\"", AlignedSignalCsvExporter.csv("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", AlignedSignalCsvExporter.csv("say \"hi\""));
    }

    private static SignalCatalogKey key(
            String name, SignalSourceType type, int channel, long canId, String file, String dbc) {
        return new SignalCatalogKey(name, type, "Msg", canId, channel, file, dbc);
    }

    private static SignalCatalogEntry entry(SignalCatalogKey key, String unit) {
        return new SignalCatalogEntry(key, unit, 0L, null, null, null, null, key.sourceFile(), key.sourceDatabase());
    }

    private static List<SignalSamplePoint> samples(int count, long step) {
        List<SignalSamplePoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long ts = (i + 1L) * step;
            points.add(new SignalSamplePoint(ts, i, (long) i, null));
        }
        return List.copyOf(points);
    }

    private static final class MapStore implements SignalSeriesStore {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> series;
        private final int cap;
        private final AtomicInteger maxLimit;
        private final AtomicBoolean cancelOnLaterPage;

        private MapStore(
                Map<SignalCatalogKey, List<SignalSamplePoint>> series,
                int cap,
                AtomicInteger maxLimit,
                AtomicBoolean cancelOnLaterPage
        ) {
            this.series = series;
            this.cap = cap;
            this.maxLimit = maxLimit;
            this.cancelOnLaterPage = cancelOnLaterPage;
        }

        @Override
        public SignalCatalog snapshot() {
            return SignalCatalog.empty();
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey key, int offset, int limit) {
            maxLimit.accumulateAndGet(limit, Math::max);
            if (cancelOnLaterPage != null && offset > 0) {
                cancelOnLaterPage.set(true);
            }
            List<SignalSamplePoint> all = series.get(key);
            if (all == null) {
                return List.of();
            }
            int from = Math.min(all.size(), Math.max(0, offset));
            int to = Math.min(all.size(), from + Math.min(Math.max(1, limit), cap));
            return List.copyOf(all.subList(from, to));
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey key) {
            List<SignalSamplePoint> all = series.get(key);
            return all == null ? 0 : all.size();
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey key) {
            return 0L;
        }
    }
}
