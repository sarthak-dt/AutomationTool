package com.stellar.platform.parser.blf;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ParserProvider;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlfProductionParserTest {

    private final BlfFileReader reader = new BlfFileReader();

    @TempDir
    Path temp;

    @Test
    void serviceLoaderExposesStreamingReader() {
        ParserProvider provider = StreamSupport.stream(ServiceLoader.load(ParserProvider.class).spliterator(), false)
                .filter(p -> BlfParserProvider.PROVIDER_ID.equals(p.providerId()))
                .findFirst()
                .orElseThrow();
        assertTrue(provider.createReader().isPresent());
        assertEquals("blf", provider.createReader().orElseThrow().formatKey());
    }

    @Test
    void goldenClassicFramesMatchFieldByField() throws Exception {
        Path path = BlfTestFiles.goldenClassic(temp.resolve("golden.blf"));
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(3, frames.size());

        CanonicalCanFrame first = frames.get(0);
        assertEquals(1_000_000L, first.timestampNanos());
        assertEquals(1, first.channel());
        assertEquals(0x123L, first.canId());
        assertFalse(first.extended());
        assertEquals(2, first.dlc());
        assertEquals(2, first.payloadLength());
        assertArrayEquals(new byte[]{0x11, 0x22}, first.data());
        assertEquals(FrameDirection.RX, first.direction());

        CanonicalCanFrame middle = frames.get(1);
        assertEquals(2_000_000L, middle.timestampNanos());
        assertEquals(2, middle.channel());
        assertEquals(0x123L, middle.canId());
        assertFalse(middle.extended());
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, middle.data());

        CanonicalCanFrame last = frames.get(2);
        assertEquals(3_000_000L, last.timestampNanos());
        assertEquals(1, last.channel());
        assertEquals(0x18DAF110L, last.canId());
        assertTrue(last.extended());
        assertEquals(8, last.dlc());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, last.data());
    }

    @Test
    void realNedcPartitionProducesCanFrames() throws Exception {
        Path path = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\BLF\\"
                + "NEDC_STP_10002_ARAI 2023-07-20 13-50-56-074765 Partition 0.blf");
        Assumptions.assumeTrue(Files.isRegularFile(path), "real BLF fixture is not on this machine");
        List<CanonicalCanFrame> frames = new ArrayList<>();
        BlfParseSummary summary = reader.readFramesInternal(
                path, ReaderContext.ofBatchSize(512), frames::addAll, w -> { });
        assertEquals(204_239, frames.size());
        assertEquals(204_239, summary.frames());
        assertEquals(1, frames.getFirst().channel());
        assertEquals("6.6.1.0", summary.binLogVersion());
        assertEquals("2023-07-20T08:20:56.074", summary.recordingStart());
    }

    @Test
    void canObjectSplitAcrossContainersIsReassembled() throws Exception {
        byte[] frame = BlfTestFiles.object(
                BlfConstants.TYPE_CAN_MESSAGE, 1, BlfTestFiles.FLAG_TIME_ONE_NANS, 5_000L,
                BlfTestFiles.classicCan(1, 0, 0x321, new byte[]{9, 8, 7}));
        byte[] head = Arrays.copyOf(frame, 32);
        byte[] tail = Arrays.copyOfRange(frame, 32, frame.length);
        byte[] first = BlfTestFiles.object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                BlfTestFiles.logContainer(BlfConstants.COMPRESSION_ZLIB, head));
        byte[] second = BlfTestFiles.object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                BlfTestFiles.logContainer(BlfConstants.COMPRESSION_ZLIB, tail));
        Path path = BlfTestFiles.write(temp.resolve("split-container.blf"), BlfTestFiles.concat(first, second), 2);
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(1, frames.size());
        assertEquals(0x321L, frames.getFirst().canId());
        assertEquals(1, frames.getFirst().channel());
        assertArrayEquals(new byte[]{9, 8, 7}, Arrays.copyOf(frames.getFirst().data(), 3));
    }

    @Test
    void containerEndingMidObjectKeepsEarlierFrames() throws Exception {
        byte[] good = BlfTestFiles.object(
                BlfConstants.TYPE_CAN_MESSAGE, 1, BlfTestFiles.FLAG_TIME_ONE_NANS, 1_000L,
                BlfTestFiles.classicCan(1, 0, 0x10, new byte[]{1}));
        byte[] partial = Arrays.copyOf(BlfTestFiles.object(
                BlfConstants.TYPE_CAN_MESSAGE, 1, BlfTestFiles.FLAG_TIME_ONE_NANS, 2_000L,
                BlfTestFiles.classicCan(1, 0, 0x11, new byte[]{2})), 20);
        byte[] body = BlfTestFiles.object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                BlfTestFiles.logContainer(BlfConstants.COMPRESSION_ZLIB, BlfTestFiles.concat(good, partial)));
        Path path = BlfTestFiles.write(temp.resolve("tail-truncated.blf"), body, 1);
        List<CanonicalCanFrame> frames = new ArrayList<>();
        BlfParseSummary summary = reader.readFramesInternal(
                path, ReaderContext.ofBatchSize(8), frames::addAll, w -> { });
        assertEquals(1, frames.size());
        assertEquals(0x10L, frames.getFirst().canId());
        assertTrue(summary.malformed() >= 1);
    }

    @Test
    void oneByteGapBetweenContainersStillReadsBoth() throws Exception {
        byte[] firstInner = BlfTestFiles.object(
                BlfConstants.TYPE_CAN_MESSAGE, 1, BlfTestFiles.FLAG_TIME_ONE_NANS, 1_000L,
                BlfTestFiles.classicCan(1, 0, 0x21, new byte[]{1}));
        byte[] secondInner = BlfTestFiles.object(
                BlfConstants.TYPE_CAN_MESSAGE, 1, BlfTestFiles.FLAG_TIME_ONE_NANS, 2_000L,
                BlfTestFiles.classicCan(1, 0, 0x22, new byte[]{2}));
        byte[] first = BlfTestFiles.object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                BlfTestFiles.logContainer(BlfConstants.COMPRESSION_NONE, firstInner));
        byte[] second = BlfTestFiles.object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                BlfTestFiles.logContainer(BlfConstants.COMPRESSION_NONE, secondInner));
        Path path = BlfTestFiles.write(
                temp.resolve("gap.blf"),
                BlfTestFiles.concat(first, new byte[]{0}, second),
                2);
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(2, frames.size());
        assertEquals(0x21L, frames.get(0).canId());
        assertEquals(0x22L, frames.get(1).canId());
    }

    @Test
    void summaryCountsSkipNonDataAndPreserveRecordingStart() throws Exception {
        Path path = BlfTestFiles.goldenClassic(temp.resolve("summary.blf"));
        BlfParseSummary summary = reader.readFramesInternal(
                path, ReaderContext.ofBatchSize(8), batch -> { }, w -> { });
        assertEquals(3, summary.frames());
        assertEquals(1, summary.containers());
        assertEquals(2, summary.channels());
        assertEquals(3, summary.uniqueCanIds());
        assertEquals(2, summary.nonData());
        assertEquals(0, summary.malformed());
        assertEquals("1.3.0.0", summary.binLogVersion());
        assertEquals("2026-01-01T12:00:00.000", summary.recordingStart());
        assertEquals(1_000_000L, summary.firstTimestampNanos());
        assertEquals(3_000_000L, summary.lastTimestampNanos());
    }

    @Test
    void sameCanIdOnTwoChannelsStaysDistinct() throws Exception {
        Path path = BlfTestFiles.multiChannelSameId(temp.resolve("channels.blf"));
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(2, frames.size());
        assertEquals(0x123L, frames.get(0).canId());
        assertEquals(0x123L, frames.get(1).canId());
        assertEquals(1, frames.get(0).channel());
        assertEquals(2, frames.get(1).channel());
        assertEquals(1, frames.get(0).data()[0]);
        assertEquals(2, frames.get(1).data()[0]);
    }

    @Test
    void canFdPayloadsSurviveParserBoundary() throws Exception {
        Path path = BlfTestFiles.canFdMixed(temp.resolve("fd.blf"));
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(2, frames.size());
        assertEquals(16, frames.get(0).payloadLength());
        assertEquals(0x700L, frames.get(0).canId());
        assertFalse(frames.get(0).extended());
        assertEquals((byte) 0xA0, frames.get(0).data()[0]);
        assertEquals((byte) 0xAF, frames.get(0).data()[15]);
        assertEquals(64, frames.get(1).payloadLength());
        assertEquals(0x1ABCDEFL, frames.get(1).canId());
        assertTrue(frames.get(1).extended());
        assertEquals(3, frames.get(1).channel());
        assertEquals(0, frames.get(1).data()[0]);
        assertEquals(63, frames.get(1).data()[63] & 0xFF);
    }

    @Test
    void tenMicrosecondFlagConvertsWithoutFloatingPoint() throws Exception {
        Path path = BlfTestFiles.tenMicroTimestamps(temp.resolve("tenmics.blf"));
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(1, frames.size());
        assertEquals(1_000_000L, frames.getFirst().timestampNanos());
    }

    @Test
    void rtrIsSkippedAndUnknownObjectsDoNotFabricateFrames() throws Exception {
        Path path = BlfTestFiles.rtrAndUnknown(temp.resolve("skip.blf"));
        BlfParseSummary summary = reader.readFramesInternal(
                path, ReaderContext.ofBatchSize(4), batch -> { }, w -> { });
        List<CanonicalCanFrame> frames = readAll(path);
        assertEquals(1, frames.size());
        assertEquals(0x124L, frames.getFirst().canId());
        assertEquals(1, summary.unsupported());
        assertTrue(summary.nonData() >= 1);
    }

    @Test
    void metadataDoesNotLoadFrames() throws Exception {
        Path path = BlfTestFiles.goldenClassic(temp.resolve("meta.blf"));
        FileMetadata metadata = reader.readMetadata(path);
        assertEquals("blf", metadata.formatKey());
        assertEquals("meta.blf", metadata.fileName());
    }

    @Test
    void cancellationStopsWithoutFabricatingSuccessFrames() throws Exception {
        Path path = BlfTestFiles.manyClassic(temp.resolve("cancel.blf"), 400);
        AtomicBoolean cancel = new AtomicBoolean(false);
        List<CanonicalCanFrame> frames = new ArrayList<>();
        CancellationToken token = () -> {
            if (frames.size() >= 8) {
                cancel.set(true);
            }
            return cancel.get();
        };
        reader.readFrames(path, new ReaderContext(4, token), frames::addAll, w -> { });
        assertTrue(frames.size() < 400, "cancellation must stop the walk");
        assertTrue(frames.size() >= 8);
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        Path path = temp.resolve("empty.blf");
        Files.write(path, new byte[0]);
        assertThrows(ParserException.class, () -> readAll(path));
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        Path path = temp.resolve("wrong.blf");
        byte[] junk = new byte[80];
        java.util.Arrays.fill(junk, (byte) 'X');
        Files.write(path, junk);
        ParserException ex = assertThrows(ParserException.class, () -> readAll(path));
        assertTrue(ex.getMessage().contains("LOGG"), ex.getMessage());
    }

    @Test
    void truncatedHeaderIsRejected() throws Exception {
        Path path = temp.resolve("trunc-header.blf");
        Files.write(path, new byte[]{'L', 'O', 'G', 'G', 20, 0, 0, 0});
        assertThrows(ParserException.class, () -> readAll(path));
    }

    @Test
    void truncatedObjectIsRejected() throws Exception {
        byte[] header = BlfTestFiles.fileHeader(1, 200, 2026, 1, 1, 0, 0, 0, 0);
        byte[] truncated = new byte[header.length + 8];
        System.arraycopy(header, 0, truncated, 0, header.length);
        truncated[header.length] = 'L';
        truncated[header.length + 1] = 'O';
        truncated[header.length + 2] = 'B';
        truncated[header.length + 3] = 'J';
        Path path = temp.resolve("trunc-obj.blf");
        Files.write(path, truncated);
        assertThrows(ParserException.class, () -> readAll(path));
    }

    @Test
    void invalidObjectSizeIsRejected() throws Exception {
        byte[] header = BlfTestFiles.fileHeader(1, 200, 2026, 1, 1, 0, 0, 0, 0);
        byte[] object = BlfTestFiles.object(1, 1, BlfTestFiles.FLAG_TIME_ONE_NANS, 1, new byte[4]);
        object[8] = 2;
        object[9] = 0;
        object[10] = 0;
        object[11] = 0;
        Path path = temp.resolve("bad-size.blf");
        Files.write(path, BlfTestFiles.concat(header, object));
        assertThrows(ParserException.class, () -> readAll(path));
    }

    @Test
    void corruptZlibIsRecoverableAndDoesNotFabricateFrames() throws Exception {
        byte[] garbage = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        byte[] container = BlfTestFiles.object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0,
                BlfTestFiles.logContainer(BlfConstants.COMPRESSION_ZLIB, garbage));
        // overwrite compressed payload with non-zlib bytes after building a valid container header
        byte[] payload = new byte[16 + 8];
        payload[0] = 2;
        payload[8] = 64;
        System.arraycopy(new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88}, 0, payload, 16, 8);
        byte[] bad = BlfTestFiles.object(BlfConstants.TYPE_LOG_CONTAINER, 1, 0, 0, payload);
        Path path = BlfTestFiles.write(temp.resolve("bad-zlib.blf"), bad, 1);
        List<CanonicalCanFrame> frames = new ArrayList<>();
        BlfParseSummary summary = reader.readFramesInternal(
                path, ReaderContext.ofBatchSize(4), frames::addAll, w -> { });
        assertTrue(frames.isEmpty());
        assertTrue(summary.malformed() >= 1);
    }

    @Test
    void unexpectedEofOnDirectoryIsRejected() {
        assertThrows(ParserException.class, () -> reader.readFrames(
                temp, ReaderContext.ofBatchSize(4), batch -> { }, w -> { }));
    }

    @Test
    void canFdDlcMapping() {
        assertEquals(8, BlfConstants.canFdDlcToLength(8));
        assertEquals(12, BlfConstants.canFdDlcToLength(9));
        assertEquals(16, BlfConstants.canFdDlcToLength(10));
        assertEquals(20, BlfConstants.canFdDlcToLength(11));
        assertEquals(24, BlfConstants.canFdDlcToLength(12));
        assertEquals(32, BlfConstants.canFdDlcToLength(13));
        assertEquals(48, BlfConstants.canFdDlcToLength(14));
        assertEquals(64, BlfConstants.canFdDlcToLength(15));
        assertEquals(-1, BlfConstants.canFdDlcToLength(16));
    }

    @Test
    void largeGeneratedFileStaysBoundedAndCompletes() throws Exception {
        int framesWanted = 8_000;
        Path path = BlfTestFiles.manyClassic(temp.resolve("medium.blf"), framesWanted);
        long start = System.nanoTime();
        List<Integer> batchSizes = new ArrayList<>();
        BlfParseSummary summary = reader.readFramesInternal(
                path,
                ReaderContext.ofBatchSize(256),
                batch -> batchSizes.add(batch.size()),
                w -> { });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(framesWanted, summary.frames());
        assertTrue(batchSizes.stream().allMatch(s -> s <= 256));
        assertTrue(elapsedMs < 20_000, "8000-frame parse took " + elapsedMs + " ms");
        assertTrue(Files.size(path) < 2_000_000L);
    }

    private List<CanonicalCanFrame> readAll(Path path) throws Exception {
        List<CanonicalCanFrame> frames = new ArrayList<>();
        reader.readFrames(path, ReaderContext.ofBatchSize(8), frames::addAll, w -> { });
        return frames;
    }
}
