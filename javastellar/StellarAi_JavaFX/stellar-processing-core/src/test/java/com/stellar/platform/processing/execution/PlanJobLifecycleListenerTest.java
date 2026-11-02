package com.stellar.platform.processing.execution;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcByteOrder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;
import com.stellar.platform.dbc.model.DbcValueType;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanJobLifecycleListenerTest {

    @Test
    void listenerInvokedBetweenJobsSequentially() {
        Path path = Path.of("src-a.asc");
        DbcDatabase dbc = database(0x100, "Msg", "Sig");
        List<CanonicalCanFrame> frames = List.of(
                frame(1, 1, 0x100, 10L, (byte) 1),
                frame(2, 2, 0x100, 20L, (byte) 2)
        );
        ProcessingJob job1 = job("A", 1, "def", 1);
        ProcessingJob job2 = job("A", 2, "def", 1);
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "p", Instant.now(), List.of(job1, job2), ProcessingPlanReadiness.READY, List.of());

        AtomicInteger starts = new AtomicInteger();
        AtomicInteger finishes = new AtomicInteger();
        List<Integer> startOrder = new ArrayList<>();
        List<Integer> finishOrder = new ArrayList<>();
        PlanJobLifecycleListener listener = new PlanJobLifecycleListener() {
            @Override
            public void onJobStarting(ProcessingJob job, int index, int total) {
                starts.incrementAndGet();
                startOrder.add(index);
                assertEquals(2, total);
            }

            @Override
            public void onJobFinished(IndependentJobResult result, int index, int total) {
                finishes.incrementAndGet();
                finishOrder.add(index);
            }
        };

        PlanExecutionContext ctx = PlanExecutionContext.builder(new MeasurementDecodePipeline(ParserRegistry.load()))
                .measurementPathResolver(id -> path)
                .definitionDatabaseResolver(id -> Optional.of(dbc))
                .readerResolver(p -> Optional.of(reader(frames)))
                .build();
        new ProjectPlanExecutor(ctx, new ProcessingResultRegistry()).execute(plan, listener);

        assertEquals(2, starts.get());
        assertEquals(2, finishes.get());
        assertEquals(List.of(0, 1), startOrder);
        assertEquals(List.of(0, 1), finishOrder);
        assertTrue(finishOrder.get(0) <= startOrder.get(1));
    }

    private static ProcessingJob job(String source, int channel, String def, long frames) {
        return new ProcessingJob(
                ProcessingJobKey.of(SourceChannelRef.of(source, channel)),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.CONFIRMED,
                def, "test.dbc",
                1, 1, 0, frames, 1.0,
                null, true, List.of()
        );
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

    private static CanonicalCanFrame frame(long seq, int channel, long canId, long ts, byte payload) {
        return new CanonicalCanFrame(
                seq, ts, channel, canId, false, FrameDirection.RX, 1,
                new byte[]{payload}, FrameQuality.OK, SourceReference.ofLine(seq)
        );
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
            public void readFrames(Path path, ReaderContext context, CanFrameConsumer frameConsumer,
                                   WarningConsumer warningConsumer) throws ParserException {
                try {
                    frameConsumer.onBatch(frames);
                } catch (ParserException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ParserException("reader failed", e);
                }
            }
        };
    }
}
