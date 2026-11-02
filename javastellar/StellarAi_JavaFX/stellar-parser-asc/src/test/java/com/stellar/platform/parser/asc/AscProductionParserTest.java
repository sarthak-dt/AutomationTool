package com.stellar.platform.parser.asc;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AscProductionParserTest {

    private final AscFileReader reader = new AscFileReader();

    @TempDir
    Path temp;

    @Test
    void headerOnlyYieldsNoFramesAndClearDiagnostic() throws Exception {
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();
        reader.readFrames(resource("asc/header_only.asc"), ReaderContext.ofBatchSize(8), frames::addAll, warnings::add);
        assertTrue(frames.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> "ASC_NO_VALID_FRAMES".equals(w.code())));
    }

    @Test
    void realBengaluruAscKeepsFramesAfterOneMalformedLine() throws Exception {
        Path path = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\ASC\\"
                + "ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_145851.asc");
        Assumptions.assumeTrue(Files.isRegularFile(path), "real ASC fixture is not on this machine");
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(512), frames::addAll, warnings::add);
        List<ProcessingWarning> malformed = warnings.stream()
                .filter(w -> "ASC_MALFORMED_LINE".equals(w.code()))
                .toList();
        assertEquals(1, malformed.size(), malformed.toString());
        assertEquals(8_792, frames.size());
        ProcessingWarning warning = malformed.getFirst();
        assertEquals(15L, warning.source().sourceLineNumber());
        assertTrue(warning.message().contains("extended CAN ID out of range"), warning.message());
        assertTrue(warning.message().contains("62000230"), warning.message());
        assertTrue(frames.stream().anyMatch(frame -> frame.source().sourceLineNumber() > warning.source().sourceLineNumber()),
                "frames after the malformed line must remain");
    }

    @Test
    void baseDecParsesIdsAndPayloadInDecimal() throws Exception {
        List<CanonicalCanFrame> frames = readAll(resource("asc/base_dec.asc"));
        assertEquals(2, frames.size());
        assertEquals(291L, frames.getFirst().canId());
        assertEquals(1, frames.getFirst().channel());
        assertArrayEquals(new byte[]{17, 34}, frames.getFirst().data());
        assertEquals(2047L, frames.get(1).canId());
        assertFalse(frames.get(1).extended());
        assertEquals(0, frames.get(1).dlc());
    }

    @Test
    void tabsMixedCaseAndRxTxAreAccepted() throws Exception {
        List<CanonicalCanFrame> frames = readAll(resource("asc/tabs_mixed_case.asc"));
        assertEquals(2, frames.size());
        assertEquals(0x7E8L, frames.get(0).canId());
        assertEquals(FrameDirection.RX, frames.get(0).direction());
        assertEquals(FrameDirection.TX, frames.get(1).direction());
        assertEquals((byte) 0xFF, frames.get(1).data()[0]);
    }

    @Test
    void crlfAndLfProduceTheSameFrames() throws Exception {
        String body = """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 123 Rx d 1 01
                """;
        Path lf = temp.resolve("lf.asc");
        Path crlf = temp.resolve("crlf.asc");
        Files.writeString(lf, body.replace("\n", "\n"));
        Files.writeString(crlf, body.replace("\n", "\r\n"));
        List<CanonicalCanFrame> a = readAll(lf);
        List<CanonicalCanFrame> b = readAll(crlf);
        assertEquals(1, a.size());
        assertEquals(a.getFirst().canId(), b.getFirst().canId());
        assertEquals(a.getFirst().timestampNanos(), b.getFirst().timestampNanos());
        assertArrayEquals(a.getFirst().data(), b.getFirst().data());
    }

    @Test
    void bomIsIgnored() throws Exception {
        Path path = temp.resolve("bom.asc");
        String body = "base hex timestamps absolute\n  0.001000 1 100 Rx d 1 AA\n";
        byte[] utf8 = body.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[utf8.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);
        Files.write(path, withBom);
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(1, frames.size());
        assertEquals(0x100L, frames.getFirst().canId());
    }

    @Test
    void remoteFrameIsSkippedNotFabricated() throws Exception {
        Path path = temp.resolve("remote.asc");
        Files.writeString(path, """
                base hex timestamps absolute
                  0.100000 1 123 Rx r 0
                  0.200000 1 124 Rx d 1 01
                """);
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(8), frames::addAll, warnings::add);
        assertEquals(1, frames.size());
        assertEquals(0x124L, frames.getFirst().canId());
        assertTrue(warnings.stream().anyMatch(w -> "ASC_UNSUPPORTED_FRAME".equals(w.code())));
    }

    @Test
    void invalidBaseFailsTheFile() throws Exception {
        Path path = temp.resolve("bad-base.asc");
        Files.writeString(path, "base oct timestamps absolute\n  0.1 1 1 Rx d 0\n");
        assertThrows(ParserException.class,
                () -> reader.readFrames(path, ReaderContext.ofBatchSize(8), batch -> { }, w -> { }));
    }

    @Test
    void malformedRecordsDoNotDropLaterValidFrames() throws Exception {
        Path path = temp.resolve("mixed.asc");
        Files.writeString(path, """
                base hex timestamps absolute
                  not-a-frame
                  0.010000 1 100 Rx d 1 01
                  0.020000 1 200 Rx d 9 01 02 03 04 05 06 07 08 09
                  0.030000 1 300 Rx d 1 FF
                  0.040000 notimestamp
                  0.050000 1 GGG Rx d 1 00
                """);
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(8), frames::addAll, warnings::add);
        assertEquals(2, frames.size());
        assertEquals(0x100L, frames.get(0).canId());
        assertEquals(0x300L, frames.get(1).canId());
        assertTrue(warnings.size() >= 3);
    }

    @Test
    void oversizedLineIsBounded() throws Exception {
        Path path = temp.resolve("huge-line.asc");
        String huge = "0.001000 1 100 Rx d 8 " + "AA ".repeat(AscLineReader.MAX_LINE_CHARS);
        Files.writeString(path, "base hex timestamps absolute\n" + huge + "\n  0.002000 1 200 Rx d 1 01\n");
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(8), frames::addAll, warnings::add);
        assertEquals(1, frames.size());
        assertEquals(0x200L, frames.getFirst().canId());
        assertTrue(warnings.stream().anyMatch(w -> "ASC_LINE_TOO_LONG".equals(w.code())));
    }

    @Test
    void localeCommaDecimalDoesNotAffectParsing() throws Exception {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            List<CanonicalCanFrame> frames = readAll(resource("asc/classical_basic.asc"));
            assertEquals(100_000_000L, frames.getFirst().timestampNanos());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void sameFileParsedTwiceIsDeterministic() throws Exception {
        Path path = resource("asc/classical_basic.asc");
        List<CanonicalCanFrame> first = readAll(path);
        List<CanonicalCanFrame> second = readAll(path);
        assertEquals(first, second);
    }

    @Test
    void concurrentFilesDoNotLeakHeaderState() throws Exception {
        Path hex = temp.resolve("hex.asc");
        Path dec = temp.resolve("dec.asc");
        Files.writeString(hex, "base hex timestamps absolute\n  0.001000 1 10 Rx d 1 AA\n");
        Files.writeString(dec, "base dec timestamps absolute\n  0.001000 1 16 Rx d 1 170\n");
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<List<CanonicalCanFrame>> hexTask = () -> readAll(hex);
            Callable<List<CanonicalCanFrame>> decTask = () -> readAll(dec);
            Future<List<CanonicalCanFrame>> hexFuture = pool.submit(hexTask);
            Future<List<CanonicalCanFrame>> decFuture = pool.submit(decTask);
            CanonicalCanFrame hexFrame = hexFuture.get().getFirst();
            CanonicalCanFrame decFrame = decFuture.get().getFirst();
            assertEquals(0x10L, hexFrame.canId());
            assertEquals(0xAA, hexFrame.data()[0] & 0xFF);
            assertEquals(16L, decFrame.canId());
            assertEquals(170, decFrame.data()[0] & 0xFF);
        }
    }

    @Test
    void dateHeaderAbsentStillParsesFrames() throws Exception {
        Path path = temp.resolve("no-date.asc");
        Files.writeString(path, """
                base hex timestamps absolute
                  0.123456 1 123 Rx d 8 11 22 33 44 55 66 77 88
                """);
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(1, frames.size());
        assertEquals(123_456_000L, frames.getFirst().timestampNanos());
        assertEquals(0x123L, frames.getFirst().canId());
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88},
                frames.getFirst().data());
    }

    @Test
    void hexPrefixAndDetachedXMarkExtended() throws Exception {
        Path path = temp.resolve("id-forms.asc");
        Files.writeString(path, """
                base hex timestamps absolute
                  0.001000 1 0x7E8 Rx d 1 00
                  0.002000 1 18DAF110x Rx d 1 FF
                  0.003000 1 18DAF110 x Tx d 1 AA
                """);
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(3, frames.size());
        assertEquals(0x7E8L, frames.get(0).canId());
        assertFalse(frames.get(0).extended());
        assertEquals(0x18DAF110L, frames.get(1).canId());
        assertTrue(frames.get(1).extended());
        assertEquals(0x18DAF110L, frames.get(2).canId());
        assertTrue(frames.get(2).extended());
        assertEquals(FrameDirection.TX, frames.get(2).direction());
        assertEquals((byte) 0xFF, frames.get(1).data()[0]);
        assertEquals((byte) 0xAA, frames.get(2).data()[0]);
    }

    @Test
    void twoChannelsKeepSameCanIdDistinct() throws Exception {
        Path path = temp.resolve("dual-ch.asc");
        Files.writeString(path, """
                base hex timestamps absolute
                  0.001000 1 123 Rx d 1 01
                  0.002000 2 123 Rx d 1 02
                """);
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(1, frames.get(0).channel());
        assertEquals(2, frames.get(1).channel());
        assertEquals(0x123L, frames.get(0).canId());
        assertEquals(0x123L, frames.get(1).canId());
        assertEquals(1, frames.get(0).data()[0]);
        assertEquals(2, frames.get(1).data()[0]);
    }

    private List<CanonicalCanFrame> readAll(Path path) throws ParserException {
        List<CanonicalCanFrame> frames = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(16), frames::addAll, w -> { });
        return frames;
    }

    private static Path resource(String name) throws Exception {
        var url = AscProductionParserTest.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalStateException("Missing test resource: " + name);
        }
        return Path.of(url.toURI());
    }
}
