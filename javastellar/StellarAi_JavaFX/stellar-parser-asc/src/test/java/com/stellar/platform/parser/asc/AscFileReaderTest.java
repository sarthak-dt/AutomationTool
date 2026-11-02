package com.stellar.platform.parser.asc;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AscFileReaderTest {

    private final AscFileReader reader = new AscFileReader();

    @Test
    void parsesClassicalCanFrames() throws Exception {
        Path path = resource("asc/classical_basic.asc");
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(10), frames::addAll, warnings::add);

        assertEquals(4, frames.size());
        assertTrue(warnings.isEmpty());

        CanonicalCanFrame first = frames.getFirst();
        assertEquals(0L, first.sequenceNumber());
        assertEquals(100_000_000L, first.timestampNanos());
        assertEquals(1, first.channel());
        assertEquals(0x123L, first.canId());
        assertFalse(first.extended());
        assertEquals(FrameDirection.RX, first.direction());
        assertEquals(8, first.dlc());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}, first.data());
        assertEquals(FrameQuality.OK, first.quality());
        assertEquals(7L, first.source().sourceLineNumber());
        assertEquals(-1L, first.source().sourceByteOffset());

        CanonicalCanFrame extended = frames.get(1);
        assertEquals(1L, extended.sequenceNumber());
        assertEquals(200_000_000L, extended.timestampNanos());
        assertEquals(2, extended.channel());
        assertEquals(0x18FAF110L, extended.canId());
        assertTrue(extended.extended());
        assertEquals(FrameDirection.TX, extended.direction());
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD}, extended.data());

        CanonicalCanFrame emptyPayload = frames.get(2);
        assertEquals(0, emptyPayload.dlc());
        assertEquals(0, emptyPayload.data().length);
        assertEquals(0x7FFL, emptyPayload.canId());

        CanonicalCanFrame last = frames.get(3);
        assertEquals(3L, last.sequenceNumber());
        assertEquals(1_500_000_000L, last.timestampNanos());
        assertEquals(FrameDirection.TX, last.direction());
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD}, last.data());
    }

    @Test
    void convertsTimestampSecondsToNanos() throws Exception {
        Path path = resource("asc/classical_basic.asc");
        List<CanonicalCanFrame> frames = readAll(path, 4);
        assertEquals(100_000_000L, frames.get(0).timestampNanos());
        assertEquals(1_500_000_000L, frames.get(3).timestampNanos());
    }

    @Test
    void batchesAccordingToReaderContext() throws Exception {
        Path path = resource("asc/batching.asc");
        List<Integer> batchSizes = new ArrayList<>();
        List<CanonicalCanFrame> frames = new ArrayList<>();

        reader.readFrames(path, ReaderContext.ofBatchSize(2), batch -> {
            batchSizes.add(batch.size());
            frames.addAll(batch);
        }, w -> {
        });

        assertEquals(List.of(2, 2, 1), batchSizes);
        assertEquals(5, frames.size());
        assertEquals(0L, frames.getFirst().sequenceNumber());
        assertEquals(4L, frames.getLast().sequenceNumber());
    }

    @Test
    void emitsWarningsForMalformedAndCanFdAndContinues() throws Exception {
        Path path = resource("asc/warnings_and_canfd.asc");
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();

        reader.readFrames(path, ReaderContext.ofBatchSize(10), frames::addAll, warnings::add);

        assertEquals(3, frames.size());
        assertEquals(0x100L, frames.get(0).canId());
        assertEquals(0x200L, frames.get(1).canId());
        assertEquals(0x400L, frames.get(2).canId());
        assertEquals(0L, frames.get(0).sequenceNumber());
        assertEquals(1L, frames.get(1).sequenceNumber());
        assertEquals(2L, frames.get(2).sequenceNumber());

        assertTrue(warnings.stream().anyMatch(w -> "ASC_UNSUPPORTED_CAN_FD".equals(w.code())));
        assertTrue(warnings.stream().anyMatch(w -> "ASC_MALFORMED_LINE".equals(w.code())));
        assertTrue(warnings.size() >= 3);
    }

    @Test
    void respectsCancellation() throws Exception {
        Path path = resource("asc/batching.asc");
        SimpleCancellationToken token = new SimpleCancellationToken();
        AtomicInteger batches = new AtomicInteger();
        List<CanonicalCanFrame> frames = new ArrayList<>();

        reader.readFrames(path, new ReaderContext(1, token), batch -> {
            frames.addAll(batch);
            batches.incrementAndGet();
            token.cancel();
        }, w -> {
        });

        assertEquals(1, batches.get());
        assertEquals(1, frames.size());
        assertEquals(0x10L, frames.getFirst().canId());
    }

    @Test
    void emptyFileYieldsNoFramesOrWarnings() throws Exception {
        Path path = resource("asc/empty.asc");
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<ProcessingWarning> warnings = new ArrayList<>();

        reader.readFrames(path, ReaderContext.ofBatchSize(8), frames::addAll, warnings::add);

        assertTrue(frames.isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void readMetadataReturnsAscDescriptor() throws Exception {
        Path path = resource("asc/classical_basic.asc");
        FileMetadata metadata = reader.readMetadata(path);
        assertEquals("asc", metadata.formatKey());
        assertEquals("classical_basic.asc", metadata.fileName());
        assertTrue(metadata.absolutePath().endsWith("classical_basic.asc"));
    }

    @Test
    void supportsOnlyAscExtension() {
        assertTrue(reader.supports(Path.of("demo.asc")));
        assertTrue(reader.supports(Path.of("DEMO.ASC")));
        assertFalse(reader.supports(Path.of("demo.blf")));
        assertFalse(reader.supports(null));
    }

    private List<CanonicalCanFrame> readAll(Path path, int batchSize) throws ParserException {
        List<CanonicalCanFrame> frames = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(batchSize), frames::addAll, w -> {
        });
        return frames;
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = AscFileReaderTest.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalStateException("Missing test resource: " + name);
        }
        return Path.of(url.toURI());
    }
}
