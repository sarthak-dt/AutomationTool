package com.stellar.platform.dbc;

import com.stellar.platform.dbc.decode.DecodeStatistics;
import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.asc.AscFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic ASC → AscFileReader → CanonicalCanFrame → Common DbcDecoder → CanonicalSignal.
 * DBC logic remains outside AscFileReader.
 */
class AscDbcIntegrationTest {

    @Test
    void ascFramesDecodeThroughCommonDbcDecoder() throws Exception {
        Path asc = Path.of(AscDbcIntegrationTest.class.getClassLoader()
                .getResource("asc/engine_sample.asc").toURI());
        Path dbc = Path.of(AscDbcIntegrationTest.class.getClassLoader()
                .getResource("dbc/common_decoder.dbc").toURI());

        List<CanonicalCanFrame> frames = new ArrayList<>();
        new AscFileReader().readFrames(asc, ReaderContext.ofBatchSize(16), frames::addAll, w -> {
        });

        assertEquals(3, frames.size());

        DbcDatabase database = new DbcParser().parse(dbc);
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        List<CanonicalSignal> signals = new ArrayList<>();
        DecodeStatistics stats = decoder.decodeBatch(frames, database, signals::add);

        assertEquals(3, stats.totalFrames());
        assertEquals(2, stats.matchedFrames());
        assertEquals(1, stats.unmatchedFrames());
        assertTrue(stats.decodedSignals() > 0);

        assertTrue(signals.stream().anyMatch(s ->
                "EngineSpeed".equals(s.signalName()) && Math.abs(s.physicalValue() - 2500.0) < 1e-9));
        assertTrue(signals.stream().anyMatch(s -> "IntelU12".equals(s.signalName())
                || "IntelS12".equals(s.signalName())));
    }
}
