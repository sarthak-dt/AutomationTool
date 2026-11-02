package com.stellar.platform.processing;

import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

/**
 * Discovers {@link ParserProvider} implementations via {@link ServiceLoader}.
 * Must not reference concrete MF4/ASC/BLF classes.
 */
public final class ParserRegistry {

    private final List<ParserProvider> providers;

    public ParserRegistry(List<ParserProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    /**
     * Loads all {@link ParserProvider} services visible to the given class loader.
     */
    public static ParserRegistry load(ClassLoader classLoader) {
        ServiceLoader<ParserProvider> loader = ServiceLoader.load(ParserProvider.class, classLoader);
        List<ParserProvider> discovered = StreamSupport.stream(loader.spliterator(), false).toList();
        return new ParserRegistry(discovered);
    }

    /**
     * Loads providers using the thread context class loader, falling back to this class's loader.
     */
    public static ParserRegistry load() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context == null) {
            context = ParserRegistry.class.getClassLoader();
        }
        return load(context);
    }

    public List<ParserProvider> providers() {
        return providers;
    }

    public List<MeasurementFileParser> createParsers() {
        return providers.stream().map(ParserProvider::createParser).toList();
    }

    /**
     * Creates streaming readers from providers that support {@link AutomotiveFileReader}.
     */
    public List<AutomotiveFileReader> createReaders() {
        return providers.stream()
                .map(ParserProvider::createReader)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Finds the streaming reader for the detected content format.
     * An extension hint is used only when the file cannot be read.
     */
    public Optional<AutomotiveFileReader> findReader(Path path) {
        Objects.requireNonNull(path, "path");
        return resolve(path, detect(path));
    }

    public DetectedMeasurementFormat detect(Path path) {
        return new MeasurementFormatDetector(providers).detect(path);
    }

    /**
     * Routes a file to the parser plugin for an already computed detection result.
     */
    public Optional<AutomotiveFileReader> resolve(Path path, DetectedMeasurementFormat detected) {
        Objects.requireNonNull(path, "path");
        if (detected == null) {
            return Optional.empty();
        }
        String key = detected.routingFormatKey();
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        for (AutomotiveFileReader reader : createReaders()) {
            if (key.equalsIgnoreCase(reader.formatKey()) && reader.supports(path)) {
                return Optional.of(reader);
            }
        }
        return Optional.empty();
    }
}
