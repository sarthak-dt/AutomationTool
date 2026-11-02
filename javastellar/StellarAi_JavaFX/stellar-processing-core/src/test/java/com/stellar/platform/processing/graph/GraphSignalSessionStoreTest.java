package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphSignalSessionStoreTest {
    @TempDir Path temp;

    @Test void previewAndLosslessGraphReceiveTheSameDecodedBatch() {
        GraphSignalSessionStore session = new GraphSignalSessionStore(temp, "m.mf4", "d.dbc");
        CanonicalSignal s1 = new CanonicalSignal("RPM", "Engine", 10, 0x123, 1, 800, 800L, "rpm", null, SourceReference.ofLine(1));
        CanonicalSignal s2 = new CanonicalSignal("RPM", "Engine", 20, 0x123, 1, 900, 900L, "rpm", null, SourceReference.ofLine(2));
        session.accept(List.of(s1, s2));
        assertEquals(1, session.snapshot().size());
        SignalCatalogKey key = session.snapshot().entries().getFirst().key();
        assertEquals(2, session.graphQuery().sampleCount(key));
        assertEquals(List.of(10L, 20L), session.graphQuery().queryRange(key, 0, 30).stream().map(p -> p.timestampNanos()).toList());
    }
}
