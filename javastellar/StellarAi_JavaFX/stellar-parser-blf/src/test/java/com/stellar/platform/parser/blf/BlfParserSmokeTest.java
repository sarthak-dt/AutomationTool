package com.stellar.platform.parser.blf;

import com.stellar.platform.parser.spi.ParserProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BlfParserSmokeTest {

    @Test
    void serviceLoaderDiscoversBlfProvider() {
        boolean found = StreamSupport.stream(ServiceLoader.load(ParserProvider.class).spliterator(), false)
                .anyMatch(p -> BlfParserProvider.PROVIDER_ID.equals(p.providerId()));
        assertTrue(found, "BlfParserProvider must be registered via META-INF/services");
    }
}
