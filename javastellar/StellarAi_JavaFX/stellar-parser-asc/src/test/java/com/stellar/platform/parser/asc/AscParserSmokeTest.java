package com.stellar.platform.parser.asc;

import com.stellar.platform.parser.spi.ParserProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AscParserSmokeTest {

    @Test
    void serviceLoaderDiscoversAscProvider() {
        boolean found = StreamSupport.stream(ServiceLoader.load(ParserProvider.class).spliterator(), false)
                .anyMatch(p -> AscParserProvider.PROVIDER_ID.equals(p.providerId()));
        assertTrue(found, "AscParserProvider must be registered via META-INF/services");
    }
}
