package com.stellar.platform.processing;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserContext;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ParserProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingCoreSmokeTest {

    @Test
    void registryAcceptsInjectedProvidersWithoutConcreteParserModules() {
        ParserRegistry registry = new ParserRegistry(List.of(new StubParserProvider()));
        assertEquals(1, registry.providers().size());
        assertEquals("stub", registry.createParsers().getFirst().formatKey());
    }

    @Test
    void openServiceSelectsSupportingParser() throws ParserException {
        MeasurementOpenService service = new MeasurementOpenService(
                new ParserRegistry(List.of(new StubParserProvider())));
        Optional<MeasurementDescriptor> opened = service.tryOpen(Path.of("demo.stub"));
        assertTrue(opened.isPresent());
        assertEquals("stub:demo.stub", opened.get().id().value());
    }

    @Test
    void loadFromServiceLoaderDoesNotThrow() {
        ParserRegistry registry = ParserRegistry.load();
        assertTrue(registry.providers().size() >= 0);
    }

    private static final class StubParserProvider implements ParserProvider {
        @Override
        public String providerId() {
            return "stub-provider";
        }

        @Override
        public MeasurementFileParser createParser() {
            return new MeasurementFileParser() {
                @Override
                public String formatKey() {
                    return "stub";
                }

                @Override
                public boolean supports(Path path) {
                    return path != null
                            && path.getFileName() != null
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".stub");
                }

                @Override
                public MeasurementDescriptor open(Path path, ParserContext context) throws ParserException {
                    String name = path.getFileName().toString();
                    return new MeasurementDescriptor(new MeasurementId("stub:" + name), name);
                }
            };
        }
    }
}
