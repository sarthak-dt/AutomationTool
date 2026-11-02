package com.stellar.platform.processing;

import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ContentFormatMatch;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeasurementFormatDetectorTest {

    @TempDir
    Path temp;

    @Test
    void contentMatchBeatsExtensionHint() throws Exception {
        Path file = temp.resolve("capture.mf4");
        Files.writeString(file, "not-used");
        ParserProvider asc = provider("asc", true);
        MeasurementFormatDetector detector = new MeasurementFormatDetector(List.of(asc));
        DetectedMeasurementFormat detected = detector.detect(file);
        assertEquals("asc", detected.content().formatKey());
        assertEquals("", detected.extensionHint());
        assertEquals("asc", detected.routingFormatKey());
    }

    @Test
    void readableUnknownFileDoesNotUseMeasurementExtension() throws Exception {
        Path file = temp.resolve("notes.mf4");
        Files.writeString(file, "plain text\n");
        MeasurementFormatDetector detector = new MeasurementFormatDetector(List.of());
        DetectedMeasurementFormat detected = detector.detect(file);
        assertFalse(detected.recognized());
        assertEquals("", detected.routingFormatKey());
        assertTrue(detected.unknownMessage().startsWith(
                "StellarAI could not determine the measurement structure automatically."));
    }

    @Test
    void missingFileKeepsExtensionHint() {
        MeasurementFormatDetector detector = new MeasurementFormatDetector(List.of());
        DetectedMeasurementFormat detected = detector.detect(temp.resolve("missing.blf"));
        assertEquals("blf", detected.routingFormatKey());
    }

    @Test
    void unsupportedRecognizedVersionDoesNotRoute() throws Exception {
        Path file = temp.resolve("old.dat");
        Files.writeString(file, "x");
        ParserProvider mdf3 = provider("mf4", false);
        DetectedMeasurementFormat detected = new MeasurementFormatDetector(List.of(mdf3)).detect(file);
        assertTrue(detected.recognized());
        assertFalse(detected.parserSupported());
        assertEquals("", detected.routingFormatKey());
        ParserRegistry registry = new ParserRegistry(List.of(mdf3));
        assertTrue(registry.resolve(file, detected).isEmpty());
    }

    private static ParserProvider provider(String formatKey, boolean supported) {
        ContentFormatMatch match = new ContentFormatMatch(
                formatKey, formatKey.toUpperCase(), supported ? "4.10" : "3.30", "", supported, null, null, null);
        return new ParserProvider() {
            @Override
            public String providerId() {
                return formatKey;
            }

            @Override
            public MeasurementFileParser createParser() {
                return new MeasurementFileParser() {
                    @Override
                    public String formatKey() {
                        return formatKey;
                    }

                    @Override
                    public boolean supports(Path path) {
                        return supported;
                    }

                    @Override
                    public com.stellar.platform.domain.MeasurementDescriptor open(
                            Path path,
                            com.stellar.platform.parser.spi.ParserContext context
                    ) {
                        return null;
                    }
                };
            }

            @Override
            public Optional<AutomotiveFileReader> createReader() {
                return Optional.empty();
            }

            @Override
            public Optional<ContentFormatMatch> probeContent(Path path) {
                return Optional.of(match);
            }
        };
    }
}
