package com.stellar.platform.processing.catalog;

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
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalCatalogChannelProvenanceTest {

    @Test
    void channelThreeOnlyProducesOnlyChannelThreeDbcSignals() throws Exception {
        List<CanonicalSignal> signals = decodeSelected(Set.of(3));
        assertFalse(signals.isEmpty());
        assertTrue(signals.stream().allMatch(s -> s.channel() == 3));
        assertEquals(0, signals.stream().filter(s -> s.channel() == 1 || s.channel() == 2).count());
    }

    @Test
    void channelOneAndThreeExcludeUnselectedChannelTwo() throws Exception {
        List<CanonicalSignal> signals = decodeSelected(Set.of(1, 3));
        Set<Integer> channels = signals.stream().map(CanonicalSignal::channel).collect(Collectors.toSet());
        assertTrue(channels.contains(1));
        assertTrue(channels.contains(3));
        assertFalse(channels.contains(2));
    }

    @Test
    void signalChannelProvenanceSurvivesDbcDecodingAndCatalog() throws Exception {
        Path source = Path.of("synth-provenance.asc");
        String identity = ProcessingSelection.sourceIdentity(source);
        ProcessingSelection selection = ProcessingSelection.forSource(identity, Set.of(1, 3));
        DbcDatabase dbc = database(0x100, "Msg100", "SharedSig");

        List<CanonicalSignal> emitted = new ArrayList<>();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("synth.asc", "veh.dbc", 100);
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        ProcessingResult result = pipeline.process(
                reader(List.of(
                        frame(1, 1, 0x100, 10L, (byte) 11),
                        frame(2, 2, 0x100, 20L, (byte) 22),
                        frame(3, 3, 0x100, 30L, (byte) 33)
                )),
                source, dbc, ReaderContext.ofBatchSize(32),
                batch -> {
                    emitted.addAll(batch);
                    store.accept(batch);
                },
                ProcessingProgressListener.NOOP, selection);

        assertEquals(2, result.statistics().framesSelectedForDecode());
        assertEquals(1, result.statistics().framesSkippedBySelection());
        assertEquals(2, emitted.size());
        assertEquals(11.0, emitted.get(0).physicalValue(), 1e-9);
        assertEquals(33.0, emitted.get(1).physicalValue(), 1e-9);
        assertEquals(10L, emitted.get(0).timestampNanos());
        assertEquals(30L, emitted.get(1).timestampNanos());

        SignalCatalog catalog = store.snapshot();
        assertEquals(2, catalog.size());
        SignalCatalogKey k1 = new SignalCatalogKey(
                "SharedSig", SignalSourceType.DBC_CAN, "Msg100", 0x100, 1, "synth.asc", "veh.dbc");
        SignalCatalogKey k3 = new SignalCatalogKey(
                "SharedSig", SignalSourceType.DBC_CAN, "Msg100", 0x100, 3, "synth.asc", "veh.dbc");
        assertNotEquals(k1, k3);
        assertEquals(1, catalog.find(k1).orElseThrow().sampleCount());
        assertEquals(1, catalog.find(k3).orElseThrow().sampleCount());
        assertEquals(1, catalog.find(k1).orElseThrow().channel());
        assertEquals(3, catalog.find(k3).orElseThrow().channel());
    }

    @Test
    void sameSignalNameOnTwoChannelsKeepsSeparateSampleSeries() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 50);
        store.accept(List.of(
                sig("Speed", "Engine", 0x100, 1, 100L, 10.0),
                sig("Speed", "Engine", 0x100, 1, 200L, 11.0),
                sig("Speed", "Engine", 0x100, 3, 150L, 20.0)
        ));
        SignalCatalog catalog = store.snapshot();
        assertEquals(2, catalog.size());
        SignalCatalogEntry ch1 = catalog.find(new SignalCatalogKey(
                "Speed", SignalSourceType.DBC_CAN, "Engine", 0x100, 1, "a.mf4", "b.dbc")).orElseThrow();
        SignalCatalogEntry ch3 = catalog.find(new SignalCatalogKey(
                "Speed", SignalSourceType.DBC_CAN, "Engine", 0x100, 3, "a.mf4", "b.dbc")).orElseThrow();
        assertEquals(2, ch1.sampleCount());
        assertEquals(1, ch3.sampleCount());
        assertEquals(10.0, ch1.minPhysical(), 1e-9);
        assertEquals(11.0, ch1.maxPhysical(), 1e-9);
        assertEquals(20.0, ch3.minPhysical(), 1e-9);
        assertEquals(100L, ch1.firstTimestampNanos());
        assertEquals(200L, ch1.lastTimestampNanos());
    }

    @Test
    void nativeMf4SignalsRemainDistinctAndDoNotRequireCanChannelSemantics() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 20);
        store.accept(List.of(
                new CanonicalSignal(
                        "Speed",
                        InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                        5L, 0L, 7, 42.0, null, "km/h", null, new SourceReference(1, 0)
                ),
                sig("Speed", "Engine", 0x100, 3, 5L, 9.0)
        ));
        SignalCatalog catalog = store.snapshot();
        assertEquals(2, catalog.size());
        assertEquals(1, catalog.nativeMf4SignalCount());
        assertEquals(1, catalog.dbcCanSignalCount());
        SignalCatalogEntry nativeEntry = catalog.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .findFirst()
                .orElseThrow();
        assertEquals(7, nativeEntry.channel());
    }

    private static List<CanonicalSignal> decodeSelected(Set<Integer> channels) throws Exception {
        Path source = Path.of("synth-multi.asc");
        String identity = ProcessingSelection.sourceIdentity(source);
        ProcessingSelection selection = ProcessingSelection.forSource(identity, channels);
        DbcDatabase dbc = database(0x55, "Msg", "S");
        List<CanonicalSignal> signals = new ArrayList<>();
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(
                new ParserRegistry(List.of()), new DefaultDbcDecoder());
        pipeline.process(
                reader(List.of(
                        frame(1, 1, 0x55, 10L, (byte) 1),
                        frame(2, 2, 0x55, 20L, (byte) 2),
                        frame(3, 3, 0x55, 30L, (byte) 3),
                        frame(4, 1, 0x55, 40L, (byte) 4),
                        frame(5, 3, 0x55, 50L, (byte) 5)
                )),
                source, dbc, ReaderContext.ofBatchSize(32),
                batch -> signals.addAll(batch),
                ProcessingProgressListener.NOOP, selection);
        return signals;
    }

    private static AutomotiveFileReader reader(List<CanonicalCanFrame> frames) {
        return new AutomotiveFileReader() {
            @Override
            public String formatKey() {
                return "ASC";
            }

            @Override
            public boolean supports(Path path) {
                return true;
            }

            @Override
            public FileMetadata readMetadata(Path path) {
                return new FileMetadata("ASC", path.getFileName().toString(), path.toString());
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

    private static CanonicalCanFrame frame(long seq, int channel, long canId, long ts, byte value) {
        return new CanonicalCanFrame(
                seq, ts, channel, canId, false, FrameDirection.RX, 1,
                new byte[]{value}, FrameQuality.OK, SourceReference.ofLine(seq)
        );
    }

    private static CanonicalSignal sig(
            String name, String message, long canId, int channel, long ts, double physical
    ) {
        return new CanonicalSignal(
                name, message, ts, canId, channel, physical, 1L, "", null, new SourceReference(1, ts)
        );
    }
}