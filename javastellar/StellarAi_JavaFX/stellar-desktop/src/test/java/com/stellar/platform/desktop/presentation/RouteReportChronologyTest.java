package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.RouteSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteReportChronologyTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long EARLIER = 1_692_025_300_000_000_000L;
    private static final long LATER = 1_692_029_940_000_000_000L;

    @TempDir
    Path temp;

    @Test
    void reportUsesTheSameRecordingOrderAsTheMapRules() throws Exception {
        Path later = temp.resolve("later.mf4");
        Path earlier = temp.resolve("earlier.mf4");
        Files.write(later, header(LATER));
        Files.write(earlier, header(EARLIER));
        List<SignalCatalogEntry> catalog = List.of(
                entry("Latitude", "src-later", "later.mf4", 13.35),
                entry("Longitude", "src-later", "later.mf4", 77.64),
                entry("Latitude", "src-earlier", "earlier.mf4", 13.33),
                entry("Longitude", "src-earlier", "earlier.mf4", 77.63));
        RouteMapReportContext context = new ProjectRouteReportLoader().load(
                true, "", "All Measurements", 2, catalog, new MemoryQuery(samples()),
                List.of(), List.of(), null,
                Map.of("src-later", later, "src-earlier", earlier));
        List<String> ids = context.route().gpsSegments().stream().map(RouteSegment::measurementId).toList();
        assertEquals(List.of("src-earlier", "src-later"), ids);
        assertTrue(context.chronologyStatus().startsWith("Ordered by recording time"));
        assertEquals(context.route().gpsSampleCount(),
                context.route().gpsSegments().get(0).sampleCount()
                        + context.route().gpsSegments().get(1).sampleCount());
        assertTrue(context.route().gpsSegments().getFirst().firstTimestampNanos() < SECOND);
    }

    private static byte[] header(long startNanos) {
        ByteBuffer buffer = ByteBuffer.allocate(64 + 104).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("MDF     ".getBytes(StandardCharsets.US_ASCII));
        buffer.put("4.11    ".getBytes(StandardCharsets.US_ASCII));
        buffer.position(64);
        buffer.put("##HD".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(0);
        buffer.putLong(104);
        buffer.putLong(6);
        for (int i = 0; i < 6; i++) {
            buffer.putLong(0);
        }
        buffer.putLong(startNanos);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.put((byte) 0x02);
        return buffer.array();
    }

    private static SignalCatalogEntry entry(String name, String id, String file, double value) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, id, "");
        return new SignalCatalogEntry(key, "deg", 2, 0L, SECOND, value, value + 0.01, file, "");
    }

    private static Map<SignalCatalogKey, List<SignalSamplePoint>> samples() {
        return Map.of(
                key("Latitude", "src-later"), List.of(new SignalSamplePoint(0L, 13.35, null, null),
                        new SignalSamplePoint(SECOND, 13.36, null, null)),
                key("Longitude", "src-later"), List.of(new SignalSamplePoint(0L, 77.64, null, null),
                        new SignalSamplePoint(SECOND, 77.65, null, null)),
                key("Latitude", "src-earlier"), List.of(new SignalSamplePoint(0L, 13.33, null, null),
                        new SignalSamplePoint(SECOND, 13.34, null, null)),
                key("Longitude", "src-earlier"), List.of(new SignalSamplePoint(0L, 77.63, null, null),
                        new SignalSamplePoint(SECOND, 77.64, null, null)));
    }

    private static SignalCatalogKey key(String name, String id) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0, 0, id, "");
    }

    private static final class MemoryQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> samples;

        private MemoryQuery(Map<SignalCatalogKey, List<SignalSamplePoint>> samples) {
            this.samples = samples;
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            return samples.getOrDefault(key, List.of());
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "deg";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return samples.getOrDefault(key, List.of()).size();
        }
    }
}
