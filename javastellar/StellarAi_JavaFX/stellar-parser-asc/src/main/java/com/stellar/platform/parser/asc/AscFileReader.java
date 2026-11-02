package com.stellar.platform.parser.asc;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Streaming Classical CAN ASC reader. Processes the file line-by-line and never loads it entirely.
 */
public final class AscFileReader implements AutomotiveFileReader {

    static final String FORMAT_KEY = "asc";

    static final String WARN_MALFORMED = "ASC_MALFORMED_LINE";
    static final String WARN_UNSUPPORTED_CAN_FD = "ASC_UNSUPPORTED_CAN_FD";
    static final String WARN_UNSUPPORTED_FRAME = "ASC_UNSUPPORTED_FRAME";
    static final String WARN_UNSUPPORTED_RECORD = "ASC_UNSUPPORTED_RECORD";
    static final String WARN_LINE_TOO_LONG = "ASC_LINE_TOO_LONG";
    static final String WARN_NO_VALID_FRAMES = "ASC_NO_VALID_FRAMES";

    private static final System.Logger LOG = System.getLogger(AscFileReader.class.getName());

    @Override
    public String formatKey() {
        return FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return AscContentProbe.parserCanRead(path);
    }

    @Override
    public FileMetadata readMetadata(Path path) throws ParserException {
        Path normalized = requireSupportedPath(path);
        return new FileMetadata(FORMAT_KEY, normalized.getFileName().toString(), normalized.toAbsolutePath().toString());
    }

    @Override
    public void readFrames(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        readFramesInternal(path, context, frameConsumer, warningConsumer);
    }

    AscParseSummary readFramesInternal(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        Path normalized = requireSupportedPath(path);
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frameConsumer, "frameConsumer");
        AscDiagnostics diagnostics = new AscDiagnostics(warningConsumer);
        AscParseSession session = new AscParseSession();
        List<CanonicalCanFrame> batch = new ArrayList<>(context.batchSize());
        String[] tokenBuf = new String[AscTokenizer.MAX_TOKENS];
        long sequenceNumber = 0L;
        long lineNumber = 0L;
        boolean sawContent = false;

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try (InputStream in = Files.newInputStream(normalized);
             Reader reader = new InputStreamReader(in, decoder);
             AscLineReader lines = new AscLineReader(reader)) {
            AscLineReader.Line line;
            while ((line = lines.next()) != null) {
                lineNumber++;
                if (context.cancellationToken().isCancellationRequested()) {
                    break;
                }
                String raw = line.text();
                if (lineNumber == 1L && !raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                    raw = raw.substring(1);
                }
                if (!raw.isBlank()) {
                    sawContent = true;
                }
                if (line.overflow()) {
                    diagnostics.warning(WARN_LINE_TOO_LONG,
                            "ASC line exceeds " + AscLineReader.MAX_LINE_CHARS + " characters and was skipped",
                            lineNumber, raw);
                    session.noteSkippedNonData();
                    continue;
                }
                ParseOutcome outcome = parseLine(raw, lineNumber, sequenceNumber, session, diagnostics, tokenBuf);
                switch (outcome.kind()) {
                    case SKIP -> {
                    }
                    case FRAME -> {
                        batch.add(outcome.frame());
                        session.noteFrame(outcome.frame().channel(), outcome.frame().timestampNanos());
                        sequenceNumber++;
                        if (batch.size() >= context.batchSize()) {
                            emitBatch(frameConsumer, batch);
                            batch.clear();
                        }
                    }
                    case SKIPPED_NON_DATA -> session.noteSkippedNonData();
                }
            }
            if (!batch.isEmpty()) {
                emitBatch(frameConsumer, batch);
                batch.clear();
            }
        } catch (ParserException e) {
            throw e;
        } catch (IOException e) {
            throw new ParserException("ASC file could not be parsed: " + normalized.getFileName(), e);
        }

        if (sawContent && session.validFrames() == 0L && !context.cancellationToken().isCancellationRequested()) {
            diagnostics.warning(WARN_NO_VALID_FRAMES,
                    "No valid CAN frames found",
                    lineNumber,
                    normalized.getFileName().toString());
        }

        AscParseSummary summary = new AscParseSummary(normalized.getFileName().toString(), session, diagnostics);
        LOG.log(System.Logger.Level.INFO, summary::formatLogLine);
        return summary;
    }

    private Path requireSupportedPath(Path path) throws ParserException {
        if (path == null) {
            throw new ParserException("ASC path must not be null");
        }
        if (!supports(path) && !hasAscExtension(path)) {
            throw new ParserException("Not an ASC path: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new ParserException("ASC path is not a readable file: " + path);
        }
        return path;
    }

    private static boolean hasAscExtension(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".asc");
    }

    private static void emitBatch(CanFrameConsumer frameConsumer, List<CanonicalCanFrame> batch)
            throws ParserException {
        List<CanonicalCanFrame> immutable = List.copyOf(batch);
        try {
            frameConsumer.onBatch(immutable);
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("Frame consumer failed", e);
        }
    }

    private static ParseOutcome parseLine(
            String rawLine,
            long lineNumber,
            long sequenceNumber,
            AscParseSession session,
            AscDiagnostics diagnostics,
            String[] tokenBuf
    ) throws ParserException {
        if (rawLine == null) {
            return ParseOutcome.skip();
        }
        String line = stripInlineComment(rawLine).trim();
        if (line.isEmpty()) {
            return ParseOutcome.skip();
        }
        if (AscHeaderParser.handle(line, session)) {
            return ParseOutcome.skip();
        }
        if (AscHeaderParser.isIgnorableNonFrame(line)) {
            return ParseOutcome.skip();
        }

        int tokenCount = AscTokenizer.tokenize(line, tokenBuf);
        if (tokenCount == 0) {
            return ParseOutcome.skip();
        }

        if (AscCanRecordParser.containsIgnoreCase(tokenBuf, tokenCount, "CANFD")) {
            diagnostics.warning(WARN_UNSUPPORTED_CAN_FD,
                    "CAN FD ASC records are deferred (ambiguous Vector grammar variants)",
                    lineNumber, line);
            return ParseOutcome.skippedNonData();
        }

        if (!AscCanRecordParser.looksLikeFrameStart(tokenBuf[0])) {
            diagnostics.warning(WARN_MALFORMED, "Unrecognized ASC line", lineNumber, line);
            return ParseOutcome.skip();
        }

        try {
            CanonicalCanFrame frame = AscCanRecordParser.parseClassic(
                    tokenBuf, tokenCount, line, lineNumber, sequenceNumber, session.baseOrDefault());
            return ParseOutcome.frame(frame);
        } catch (AscCanRecordParser.UnsupportedFrameException e) {
            diagnostics.warning(WARN_UNSUPPORTED_FRAME, e.getMessage(), lineNumber, line);
            return ParseOutcome.skippedNonData();
        } catch (RuntimeException e) {
            diagnostics.warning(WARN_MALFORMED,
                    "Malformed ASC frame line (" + e.getMessage() + ")",
                    lineNumber, line);
            return ParseOutcome.skip();
        }
    }

    private static String stripInlineComment(String line) {
        int idx = line.indexOf("//");
        if (idx < 0) {
            return line;
        }
        String before = line.substring(0, idx).trim();
        if (before.isEmpty()) {
            return "";
        }
        if (Character.isDigit(before.charAt(0))) {
            return before;
        }
        return "";
    }

    private enum Kind {
        SKIP,
        FRAME,
        SKIPPED_NON_DATA
    }

    private record ParseOutcome(Kind kind, CanonicalCanFrame frame) {
        static ParseOutcome skip() {
            return new ParseOutcome(Kind.SKIP, null);
        }

        static ParseOutcome skippedNonData() {
            return new ParseOutcome(Kind.SKIPPED_NON_DATA, null);
        }

        static ParseOutcome frame(CanonicalCanFrame frame) {
            return new ParseOutcome(Kind.FRAME, frame);
        }
    }
}
