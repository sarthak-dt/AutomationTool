package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalIdentityGraphSeriesQueryTest {

    @Test
    void sameRelativeTimestampStaysBoundToTheKeyMeasurement() {
        RecordingQuery delegate = new RecordingQuery();
        PhysicalIdentityGraphSeriesQuery query = new PhysicalIdentityGraphSeriesQuery(delegate);
        SignalCatalogKey rpmA = key("src-A", "RPM");
        SignalCatalogKey rpmB = key("src-B", "RPM");

        query.queryRange(rpmA, 100_000_000_000L, 100_000_000_000L);
        query.queryRange(rpmB, 100_000_000_000L, 100_000_000_000L);

        assertEquals(List.of("src-A", "src-B"), delegate.scopes);
        assertTrue(delegate.keys.get(0).equals(rpmA));
        assertTrue(delegate.keys.get(1).equals(rpmB));
    }

    private static SignalCatalogKey key(String source, String name) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "Engine", 0x100, 1, source, "");
    }

    private static final class RecordingQuery implements GraphSeriesQuery {
        private final List<String> scopes = new ArrayList<>();
        private final List<SignalCatalogKey> keys = new ArrayList<>();

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            keys.add(key);
            scopes.add("");
            return List.of();
        }

        @Override
        public List<SignalSamplePoint> queryRange(
                SignalCatalogKey key, long startNanos, long endNanos, String sourceScope) {
            keys.add(key);
            scopes.add(sourceScope);
            return List.of();
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return 0;
        }
    }
}
