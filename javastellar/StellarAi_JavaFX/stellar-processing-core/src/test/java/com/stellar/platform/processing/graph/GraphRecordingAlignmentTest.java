package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRecordingAlignmentTest {

    @Test
    void overlappingStoredTimestampsDoNotMakeDifferentFilesOneRecording() {
        GraphRenderSeries left = series("Speed", "file-a", 0);
        GraphRenderSeries right = series("Speed", "file-b", 0);
        GraphRenderSeries coolant = series("Coolant", "file-a", 0);
        assertFalse(GraphRecordingAlignment.sameRecording(left, right));
        assertTrue(GraphRecordingAlignment.sameRecording(left, coolant));
        assertFalse(GraphRecordingAlignment.sameRecording(" ", "file-a"));
    }

    private static GraphRenderSeries series(String name, String file, long timestamp) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, SignalSourceType.DBC_CAN, "Vehicle", 0x100L, file, "one.dbc");
        GraphBucket bucket = new GraphBucket(timestamp, 1, timestamp, 1, timestamp, 2, timestamp, 1, 1);
        return new GraphRenderSeries(key, "km/h", 1, true, 0, 1_000, List.of(bucket));
    }
}
