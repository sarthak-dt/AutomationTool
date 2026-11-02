package com.stellar.platform.processing;

import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregatedDiagnosticTest {

    @Test
    void aggregatesRepeatedDiagnostics() {
        SourceReference src = new SourceReference(1, 0);
        List<AggregatedDiagnostic> aggregated = AggregatedDiagnostic.aggregate(List.of(
                ProcessingDiagnostic.ofDbc("UNMATCHED_CAN_ID", "no message", src),
                ProcessingDiagnostic.ofDbc("UNMATCHED_CAN_ID", "no message for other id", src),
                ProcessingDiagnostic.ofReader("ASC_MALFORMED_LINE", "bad line", src)
        ));
        assertEquals(2, aggregated.size());
        assertEquals(2, aggregated.stream()
                .filter(d -> d.code().equals("UNMATCHED_CAN_ID"))
                .findFirst()
                .orElseThrow()
                .count());
        assertEquals(1, aggregated.stream()
                .filter(d -> d.code().equals("ASC_MALFORMED_LINE"))
                .findFirst()
                .orElseThrow()
                .count());
    }
}
