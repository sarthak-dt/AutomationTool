package com.stellar.platform.processing;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedChannelDecodeFilterTest {

    @Test
    void onlySelectedChannelFramesReachDbcDecoderAndSignals() throws Exception {
        Path source = Path.of("synth-selected-channel.asc");
        String identity = ProcessingSelection.sourceIdentity(source);
        ProcessingSelection selection = ProcessingSelection.forSource(identity, List.of(2));

        DbcDatabase dbc = database(0x100, "Msg100", "SigA");
        AutomotiveFileReader reader = reader("ASC", List.of(
                frame(1, 1, 0x100, 10L),
                frame(2, 2, 0x100, 20L),
                frame(3, 1, 0x100, 30L),
                frame(4, 2, 0x100, 40L)
        ));

        List<CanonicalSignal> signals = new ArrayList<>();
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        ProcessingResult result = pipeline.process(
                reader, source, dbc, ReaderContext.ofBatchSize(64),
                batch -> signals.addAll(batch), ProcessingProgressListener.NOOP, selection);

        assertEquals(4, result.statistics().sourceFramesRead());
        assertEquals(2, result.statistics().framesSelectedForDecode());
        assertEquals(2, result.statistics().framesSkippedBySelection());
        assertEquals(2, result.statistics().dbcMatchedFrames());
        assertTrue(signals.stream().allMatch(s -> s.channel() == 2));
        assertEquals(2, signals.size());
        assertEquals(20L, signals.get(0).timestampNanos());
        assertEquals(40L, signals.get(1).timestampNanos());
    }

    @Test
    void selectedChannelSampleIndexAndOrderRemainStable() throws Exception {
        Path source = Path.of("synth-index.asc");
        String identity = ProcessingSelection.sourceIdentity(source);
        ProcessingSelection selection = ProcessingSelection.forSource(identity, Set.of(1));
        DbcDatabase dbc = database(0x55, "Msg", "S");

        List<CanonicalCanFrame> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            frames.add(frame(i + 1, 1, 0x55, (i + 1) * 100L));
            frames.add(frame(100 + i, 9, 0x55, (i + 1) * 100L + 1));
        }
        AutomotiveFileReader reader = reader("ASC", frames);

        List<CanonicalSignal> signals = new ArrayList<>();
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        ProcessingResult result = pipeline.process(
                reader, source, dbc, ReaderContext.ofBatchSize(32),
                batch -> signals.addAll(batch), ProcessingProgressListener.NOOP, selection);

        assertEquals(5, signals.size());
        assertEquals(5, result.statistics().dbcSignalsReceived());
        for (int i = 0; i < signals.size(); i++) {
            assertEquals((i + 1) * 100L, signals.get(i).timestampNanos());
            assertEquals(1, signals.get(i).channel());
        }
        assertEquals(5, signals.stream().map(CanonicalSignal::timestampNanos).distinct().count());
    }

    private static AutomotiveFileReader reader(String format, List<CanonicalCanFrame> frames) {
        return new AutomotiveFileReader() {
            @Override
            public String formatKey() {
                return format;
            }

            @Override
            public boolean supports(Path path) {
                return true;
            }

            @Override
            public FileMetadata readMetadata(Path path) {
                return new FileMetadata(format, path.getFileName().toString(), path.toString());
            }

            @Override
            public void readFrames(
                    Path path,
                    ReaderContext context,
                    CanFrameConsumer frameConsumer,
                    WarningConsumer warningConsumer
            ) throws ParserException {
                try {
                    frameConsumer.onBatch(frames);
                } catch (ParserException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ParserException("test reader failed", e);
                }
            }
        };
    }

    private static DbcDatabase database(long canId, String message, String signal) {
        return new DbcDatabase("", List.of(), List.of(
                new DbcMessage(
                        CanIdKey.fromFrame(canId, false),
                        message,
                        8,
                        "",
                        List.of(new DbcSignal(
                                signal, 0, 8, DbcByteOrder.INTEL, DbcValueType.UNSIGNED,
                                1, 0, 0, 255, "u", List.of(), DbcMultiplexRole.NONE, null, "", DbcValueTable.empty()
                        )),
                        ""
                )
        ), List.of());
    }

    private static CanonicalCanFrame frame(long seq, int channel, long canId, long ts) {
        return new CanonicalCanFrame(
                seq, ts, channel, canId, false, FrameDirection.RX, 1,
                new byte[]{0x2A}, FrameQuality.OK, SourceReference.ofLine(seq)
        );
    }
}
