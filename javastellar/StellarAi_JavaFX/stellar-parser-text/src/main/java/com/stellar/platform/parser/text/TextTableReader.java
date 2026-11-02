package com.stellar.platform.parser.text;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams a safely recognized text table into the shared measurement sample model.
 */
public final class TextTableReader implements NativeSignalCapableReader {

    private static final Pattern STRUCTURED = Pattern.compile(
            "^([+-]?\\d+(?:\\.\\d+)?)(ns|us|ms|s)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAIR = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=([+-]?\\d+(?:\\.\\d+)?)");

    @Override
    public String formatKey() {
        return TextTableProbe.FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return TextTableProbe.probe(path).isPresent();
    }

    @Override
    public FileMetadata readMetadata(Path path) throws ParserException {
        if (!supports(path)) {
            throw new ParserException("Not a recognized text measurement: " + path);
        }
        return new FileMetadata(formatKey(), path.getFileName().toString(), path.toAbsolutePath().toString());
    }

    @Override
    public void readFrames(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        readFramesAndSignals(path, context, frameConsumer, SignalBatchConsumer.NOOP, warningConsumer);
    }

    @Override
    public void readFramesAndSignals(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            SignalBatchConsumer nativeMeasurementConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        TextLayout layout = TextTableProbe.layout(path).orElseThrow(() ->
                new ParserException("StellarAI could not determine the measurement structure automatically."));
        ReaderContext ctx = context == null ? ReaderContext.ofBatchSize(256) : context;
        SignalBatchConsumer sink = nativeMeasurementConsumer == null ? SignalBatchConsumer.NOOP : nativeMeasurementConsumer;
        WarningConsumer warnings = warningConsumer == null ? warning -> { } : warningConsumer;
        List<CanonicalMeasurementSample> batch = new ArrayList<>();
        long emitted = 0;
        long lineNumber = 0;
        boolean headerPending = layout.header();
        try (BufferedReader reader = Files.newBufferedReader(path, layout.charset())) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if ((lineNumber & 255) == 0 && ctx.cancellationToken().isCancellationRequested()) {
                    throw new ParserException("Processing cancelled.");
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (headerPending) {
                    headerPending = false;
                    continue;
                }
                int before = batch.size();
                if (layout.kind() == TextLayout.Kind.STRUCTURED) {
                    appendStructured(layout, trimmed, lineNumber, batch, warnings);
                } else {
                    appendDelimited(layout, trimmed, lineNumber, batch, warnings);
                }
                if (batch.size() - before == 0) {
                    continue;
                }
                if (batch.size() >= ctx.batchSize()) {
                    emit(sink, batch);
                    emitted += batch.size();
                    batch.clear();
                }
            }
        } catch (IOException e) {
            throw new ParserException("Detected text measurement, but the file could not be read.", e);
        }
        if (!batch.isEmpty()) {
            int count = batch.size();
            emit(sink, batch);
            emitted += count;
        }
        if (emitted == 0) {
            throw new ParserException("Detected structured text, but timestamp semantics could not be determined.");
        }
    }

    private static void appendStructured(
            TextLayout layout,
            String line,
            long lineNumber,
            List<CanonicalMeasurementSample> batch,
            WarningConsumer warnings
    ) {
        Matcher matcher = STRUCTURED.matcher(line);
        if (!matcher.matches()) {
            warn(warnings, lineNumber, "Skipped a text row that did not match the structured pattern.");
            return;
        }
        double time = Double.parseDouble(matcher.group(1));
        long nanos = Math.round(time * layout.nanosPerUnit());
        Matcher pairs = PAIR.matcher(matcher.group(3));
        boolean any = false;
        while (pairs.find()) {
            any = true;
            batch.add(sample(pairs.group(1), nanos, 0, Double.parseDouble(pairs.group(2)), "", lineNumber));
        }
        if (!any) {
            warn(warnings, lineNumber, "Skipped a structured row with no numeric signals.");
        }
    }

    private static void appendDelimited(
            TextLayout layout,
            String line,
            long lineNumber,
            List<CanonicalMeasurementSample> batch,
            WarningConsumer warnings
    ) {
        boolean whitespace = layout.delimiter() == ' ';
        String[] cells = TextTableProbe.split(line, layout.delimiter(), whitespace);
        if (layout.timeColumn() >= cells.length) {
            warn(warnings, lineNumber, "Skipped a text row with a missing timestamp column.");
            return;
        }
        Long nanos = timestamp(layout, cells[layout.timeColumn()]);
        if (nanos == null) {
            warn(warnings, lineNumber, "Skipped a text row whose timestamp could not be read.");
            return;
        }
        for (TextLayout.SignalColumn signal : layout.signals()) {
            if (signal.index() >= cells.length) {
                continue;
            }
            String raw = cells[signal.index()].trim();
            Double value = signal.booleanValues() ? booleanValue(raw) : numeric(raw);
            if (value == null) {
                continue;
            }
            batch.add(sample(signal.name(), nanos, signal.index(), value, signal.unit(), lineNumber));
        }
    }

    private static CanonicalMeasurementSample sample(
            String name,
            long nanos,
            int channel,
            double value,
            String unit,
            long lineNumber
    ) {
        return new CanonicalMeasurementSample(
                name,
                nanos,
                channel,
                value,
                null,
                unit,
                null,
                new SourceReference(lineNumber, -1)
        );
    }

    private static Long timestamp(TextLayout layout, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (layout.isoTimestamp()) {
            String normalized = text.replace(' ', 'T');
            if (!normalized.endsWith("Z") && !normalized.matches(".*[+-]\\d\\d:?\\d\\d$")) {
                normalized = normalized + "Z";
            }
            try {
                Instant instant = Instant.parse(normalized);
                return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (!TextTableProbe.isNumber(text)) {
            return null;
        }
        return Math.round(Double.parseDouble(text) * layout.nanosPerUnit());
    }

    private static Double numeric(String raw) {
        return TextTableProbe.isNumber(raw) ? Double.parseDouble(raw.trim()) : null;
    }

    private static Double booleanValue(String raw) {
        String token = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (token) {
            case "true", "yes" -> 1d;
            case "false", "no" -> 0d;
            default -> null;
        };
    }

    private static void warn(WarningConsumer warnings, long line, String message) {
        warnings.onWarning(new ProcessingWarning("TEXT_ROW_SKIPPED", message, new SourceReference(line, -1)));
    }

    private static void emit(SignalBatchConsumer sink, List<CanonicalMeasurementSample> batch) throws ParserException {
        try {
            sink.onBatch(List.copyOf(batch));
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException(e.getMessage() == null ? "Signal consumer failed." : e.getMessage(), e);
        }
    }
}
