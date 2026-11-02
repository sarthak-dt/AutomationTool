package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplexDecodingTest {

    private static DbcDatabase database;
    private static final DefaultDbcDecoder DECODER = new DefaultDbcDecoder();

    @BeforeAll
    static void load() throws Exception {
        database = new DbcParser().parse(Path.of(
                MultiplexDecodingTest.class.getClassLoader().getResource("dbc/common_decoder.dbc").toURI()));
    }

    @Test
    void emitsOnlyMux0Signals() {
        // Mode=0, Speed=42
        byte[] data = new byte[]{0x00, 42};
        DecodingResult result = DECODER.decode(frame(data), database);
        Set<String> names = result.signals().stream().map(s -> s.signalName()).collect(Collectors.toSet());
        assertTrue(names.contains("Mode"));
        assertTrue(names.contains("Speed"));
        assertEquals(false, names.contains("Temperature"));
        assertEquals(42.0, result.signals().stream()
                .filter(s -> s.signalName().equals("Speed")).findFirst().orElseThrow().physicalValue(), 1e-9);
    }

    @Test
    void emitsOnlyMux1Signals() {
        // Mode=1, Temperature raw 10 -> physical 10
        byte[] data = new byte[]{0x01, 10};
        DecodingResult result = DECODER.decode(frame(data), database);
        Set<String> names = result.signals().stream().map(s -> s.signalName()).collect(Collectors.toSet());
        assertTrue(names.contains("Mode"));
        assertTrue(names.contains("Temperature"));
        assertEquals(false, names.contains("Speed"));
    }

    private static CanonicalCanFrame frame(byte[] data) {
        return new CanonicalCanFrame(
                0, 0L, 1, 0x600, false, FrameDirection.RX, data.length, data,
                FrameQuality.OK, SourceReference.ofLine(1)
        );
    }
}
