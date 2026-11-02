package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.spi.ParserProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Mf4ParserSmokeTest {

    @Test
    void serviceLoaderDiscoversMf4Provider() {
        boolean found = StreamSupport.stream(ServiceLoader.load(ParserProvider.class).spliterator(), false)
                .anyMatch(p -> Mf4ParserProvider.PROVIDER_ID.equals(p.providerId()));
        assertTrue(found, "Mf4ParserProvider must be registered via META-INF/services");
    }
}
