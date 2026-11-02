package com.stellar.platform.testkit;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.parser.asc.AscParserProvider;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASC Path → ServiceLoader → ParserRegistry → AutomotiveFileReader → CanonicalCanFrame
 */
class AscParserRegistryIntegrationTest {

    @Test
    void serviceLoaderRegistryStreamsAscFrames() throws Exception {
        Path path = resource("asc/classical_basic.asc");

        ParserRegistry registry = ParserRegistry.load(AscParserProvider.class.getClassLoader());
        assertTrue(registry.providers().stream().anyMatch(p -> AscParserProvider.PROVIDER_ID.equals(p.providerId())));

        AutomotiveFileReader reader = registry.findReader(path)
                .orElseThrow(() -> new AssertionError("Expected ASC AutomotiveFileReader via ParserRegistry"));

        assertEquals("asc", reader.formatKey());
        assertTrue(reader.supports(path));

        List<CanonicalCanFrame> frames = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(2), frames::addAll, w -> {
        });

        assertFalse(frames.isEmpty());
        assertEquals(4, frames.size());
        assertEquals(0L, frames.getFirst().sequenceNumber());
        assertEquals(0x123L, frames.getFirst().canId());
        assertEquals(0x18FAF110L, frames.get(1).canId());
        assertTrue(frames.get(1).extended());
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = AscParserRegistryIntegrationTest.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalStateException("Missing test resource: " + name);
        }
        return Path.of(url.toURI());
    }
}
