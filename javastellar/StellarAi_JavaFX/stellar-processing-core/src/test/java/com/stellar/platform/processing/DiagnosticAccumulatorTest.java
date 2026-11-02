package com.stellar.platform.processing;

import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticAccumulatorTest {

    @Test
    void oneMillionIdenticalDiagnostics_collapseToOneCategory() {
        DiagnosticAccumulator acc = new DiagnosticAccumulator();
        SourceReference src = new SourceReference(1, 0);
        for (int i = 0; i < 1_000_000; i++) {
            acc.record("DBC", "UNMATCHED_CAN_ID",
                    "No DBC message for CAN ID 0x" + Integer.toHexString(i), src);
        }
        assertEquals(1, acc.uniqueCategories());
        assertEquals(1_000_000L, acc.totalOccurrences());
        List<AggregatedDiagnostic> snap = acc.snapshot();
        assertEquals(1, snap.size());
        assertEquals(1_000_000L, snap.getFirst().count());
        assertTrue(snap.getFirst().message().toLowerCase().contains("matching"));
    }

    @Test
    void aggregateFromList_isBoundedByUniqueCodes() {
        List<ProcessingDiagnostic> raw = new ArrayList<>();
        SourceReference src = new SourceReference(1, 0);
        for (int i = 0; i < 10_000; i++) {
            raw.add(ProcessingDiagnostic.ofDbc("UNMATCHED_CAN_ID", "id=" + i, src));
            raw.add(ProcessingDiagnostic.ofReader("RECOVERED_RECORD", "Recovered " + i, src));
        }
        List<AggregatedDiagnostic> aggregated = AggregatedDiagnostic.aggregate(raw);
        assertEquals(2, aggregated.size());
        long total = aggregated.stream().mapToLong(AggregatedDiagnostic::count).sum();
        assertEquals(20_000L, total);
    }
}
