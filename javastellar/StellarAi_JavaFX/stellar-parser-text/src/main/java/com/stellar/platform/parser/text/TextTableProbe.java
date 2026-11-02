package com.stellar.platform.parser.text;

import com.stellar.platform.parser.spi.ContentFormatMatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded text inspection. A file is accepted only when a timestamp column and at least one
 * numeric signal column are stable. Numbers alone are not enough.
 */
public final class TextTableProbe {

    static final String FORMAT_KEY = "text";
    private static final int MAX_SAMPLE_LINES = 40;
    private static final int MAX_SAMPLE_BYTES = 65_536;
    private static final Pattern STRUCTURED = Pattern.compile(
            "^([+-]?\\d+(?:\\.\\d+)?)(ns|us|ms|s)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAIR = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern ISO = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?");

    private TextTableProbe() {
    }

    public static Optional<ContentFormatMatch> probe(Path path) {
        return layout(path).map(TextTableProbe::match);
    }

    public static Optional<TextLayout> layout(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            Charset charset = charset(path);
            if (charset == null) {
                return Optional.empty();
            }
            List<String> lines = sampleLines(path, charset);
            if (lines.size() < 3) {
                return Optional.empty();
            }
            Optional<TextLayout> structured = structured(lines, charset);
            if (structured.isPresent()) {
                return structured;
            }
            return delimited(lines, charset);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static ContentFormatMatch match(TextLayout layout) {
        boolean structured = layout.kind() == TextLayout.Kind.STRUCTURED;
        return new ContentFormatMatch(
                FORMAT_KEY,
                structured ? "Structured text" : "Delimited text",
                "",
                "confidence:" + layout.confidence(),
                true,
                Boolean.FALSE,
                Boolean.TRUE,
                Boolean.FALSE
        );
    }

    private static Optional<TextLayout> structured(List<String> lines, Charset charset) {
        List<String[]> rows = new ArrayList<>();
        Double scale = null;
        Set<String> keys = null;
        for (String line : lines) {
            Matcher matcher = STRUCTURED.matcher(line.trim());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            double factor = nanos(matcher.group(2));
            if (factor < 0 || (scale != null && scale != factor)) {
                return Optional.empty();
            }
            scale = factor;
            Matcher pairs = PAIR.matcher(matcher.group(3));
            List<String> rowKeys = new ArrayList<>();
            while (pairs.find()) {
                rowKeys.add(pairs.group(1));
            }
            if (rowKeys.isEmpty()) {
                return Optional.empty();
            }
            if (keys == null) {
                keys = new LinkedHashSet<>(rowKeys);
            } else if (!keys.equals(new LinkedHashSet<>(rowKeys))) {
                return Optional.empty();
            }
            rows.add(rowKeys.toArray(String[]::new));
        }
        if (rows.size() < 3 || keys == null || keys.isEmpty()) {
            return Optional.empty();
        }
        List<TextLayout.SignalColumn> signals = new ArrayList<>();
        int index = 0;
        for (String key : keys) {
            signals.add(new TextLayout.SignalColumn(index++, key, "", false));
        }
        return Optional.of(new TextLayout(
                TextLayout.Kind.STRUCTURED,
                ' ',
                charset,
                0,
                scale,
                false,
                List.copyOf(signals),
                false,
                "HIGH"
        ));
    }

    private static Optional<TextLayout> delimited(List<String> lines, Charset charset) {
        if (lines.stream().anyMatch(line -> line.indexOf('"') >= 0)) {
            return Optional.empty();
        }
        char delimiter = 0;
        int columns = 0;
        for (char candidate : new char[] {'\t', ',', ';'}) {
            int count = consistentColumns(lines, candidate);
            if (count > columns) {
                delimiter = candidate;
                columns = count;
            }
        }
        boolean whitespace = false;
        if (delimiter == 0) {
            columns = consistentWhitespace(lines);
            if (columns < 2) {
                return Optional.empty();
            }
            whitespace = true;
            delimiter = ' ';
        }
        List<String[]> table = new ArrayList<>();
        for (String line : lines) {
            table.add(split(line, delimiter, whitespace));
        }
        String[] header = table.get(0);
        if (allNumeric(header)) {
            return Optional.empty();
        }
        List<String[]> data = table.subList(1, table.size());
        if (data.size() < 3) {
            return Optional.empty();
        }
        int timeColumn = -1;
        double scale = 0;
        boolean iso = false;
        for (int i = 0; i < header.length; i++) {
            TimeColumn time = timeColumn(header[i], column(data, i));
            if (time != null) {
                if (timeColumn >= 0) {
                    return Optional.empty();
                }
                timeColumn = i;
                scale = time.nanos;
                iso = time.iso;
            }
        }
        if (timeColumn < 0) {
            return Optional.empty();
        }
        List<TextLayout.SignalColumn> signals = new ArrayList<>();
        for (int i = 0; i < header.length; i++) {
            if (i == timeColumn) {
                continue;
            }
            List<String> values = column(data, i);
            HeaderCell cell = headerCell(header[i]);
            if (allBoolean(values)) {
                signals.add(new TextLayout.SignalColumn(i, cell.name, cell.unit, true));
            } else if (allNumeric(values.toArray(String[]::new))) {
                signals.add(new TextLayout.SignalColumn(i, cell.name, cell.unit, false));
            }
        }
        if (signals.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TextLayout(
                TextLayout.Kind.DELIMITED,
                delimiter,
                charset,
                timeColumn,
                scale,
                iso,
                List.copyOf(signals),
                true,
                whitespace ? "MEDIUM" : "HIGH"
        ));
    }

    private static TimeColumn timeColumn(String header, List<String> values) {
        HeaderCell cell = headerCell(header);
        String name = cell.name.toLowerCase(Locale.ROOT).replace(' ', '_');
        Double fromName = suffixScale(name);
        if (fromName != null && allNumeric(values.toArray(String[]::new))) {
            return new TimeColumn(fromName, false);
        }
        if (name.equals("time") || name.equals("timestamp")) {
            Double fromUnit = nanosBoxed(cell.unit);
            if (fromUnit != null && allNumeric(values.toArray(String[]::new))) {
                return new TimeColumn(fromUnit, false);
            }
            if (values.stream().allMatch(value -> ISO.matcher(value.trim()).matches())) {
                return new TimeColumn(1d, true);
            }
        }
        return null;
    }

    private static Double suffixScale(String name) {
        for (String suffix : new String[] {"_ns", "_us", "_ms", "_s"}) {
            if (name.endsWith(suffix) && (name.startsWith("time") || name.startsWith("timestamp"))) {
                return nanosBoxed(suffix.substring(1));
            }
        }
        return null;
    }

    private static HeaderCell headerCell(String raw) {
        String text = raw == null ? "" : raw.trim();
        String unit = "";
        int paren = text.lastIndexOf('(');
        int bracket = text.lastIndexOf('[');
        int mark = Math.max(paren, bracket);
        if (mark > 0 && (text.endsWith(")") || text.endsWith("]"))) {
            unit = text.substring(mark + 1, text.length() - 1).trim();
            text = text.substring(0, mark).trim();
        }
        if (text.isBlank()) {
            text = "column";
        }
        if ("-".equals(unit)) {
            unit = "";
        }
        return new HeaderCell(text, unit);
    }

    private static int consistentColumns(List<String> lines, char delimiter) {
        int count = -1;
        for (String line : lines) {
            if (line.indexOf(delimiter) < 0) {
                return -1;
            }
            int columns = split(line, delimiter, false).length;
            if (columns < 2 || (count >= 0 && columns != count)) {
                return -1;
            }
            count = columns;
        }
        return count;
    }

    private static int consistentWhitespace(List<String> lines) {
        int count = -1;
        for (String line : lines) {
            if (line.indexOf(',') >= 0 || line.indexOf(';') >= 0 || line.indexOf('\t') >= 0) {
                return -1;
            }
            int columns = line.trim().split("\\s+").length;
            if (columns < 2 || (count >= 0 && columns != count)) {
                return -1;
            }
            count = columns;
        }
        return count;
    }

    static String[] split(String line, char delimiter, boolean whitespace) {
        if (whitespace) {
            return line.trim().split("\\s+");
        }
        return line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1);
    }

    private static List<String> column(List<String[]> rows, int index) {
        List<String> values = new ArrayList<>();
        for (String[] row : rows) {
            values.add(index < row.length ? row[index].trim() : "");
        }
        return values;
    }

    private static boolean allNumeric(String[] cells) {
        if (cells.length == 0) {
            return false;
        }
        for (String cell : cells) {
            if (!isNumber(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allBoolean(List<String> values) {
        if (values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            String token = value.trim().toLowerCase(Locale.ROOT);
            if (!token.equals("true") && !token.equals("false") && !token.equals("yes") && !token.equals("no")) {
                return false;
            }
        }
        return true;
    }

    static boolean isNumber(String raw) {
        if (raw == null) {
            return false;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return false;
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static double nanos(String unit) {
        Double boxed = nanosBoxed(unit);
        return boxed == null ? -1d : boxed;
    }

    private static Double nanosBoxed(String unit) {
        if (unit == null) {
            return null;
        }
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "s", "sec", "secs", "second", "seconds" -> 1_000_000_000d;
            case "ms", "msec", "millisecond", "milliseconds" -> 1_000_000d;
            case "us", "µs", "μs", "usec", "microsecond", "microseconds" -> 1_000d;
            case "ns", "nsec", "nanosecond", "nanoseconds" -> 1d;
            default -> null;
        };
    }

    private static Charset charset(Path path) throws IOException {
        byte[] prefix = new byte[4096];
        int n;
        try (InputStream in = Files.newInputStream(path)) {
            n = in.read(prefix);
        }
        if (n <= 0) {
            return null;
        }
        for (int i = 0; i < n; i++) {
            if (prefix[i] == 0) {
                return null;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(prefix, 0, n));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static List<String> sampleLines(Path path, Charset charset) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(new BoundedStream(in, MAX_SAMPLE_BYTES), charset))) {
            String line;
            while (lines.size() < MAX_SAMPLE_LINES && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private record HeaderCell(String name, String unit) {
    }

    private record TimeColumn(double nanos, boolean iso) {
    }

    private static final class BoundedStream extends InputStream {
        private final InputStream delegate;
        private int remaining;

        private BoundedStream(InputStream delegate, int max) {
            this.delegate = delegate;
            this.remaining = max;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = delegate.read(buffer, off, Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
