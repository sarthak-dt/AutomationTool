package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.MeasurementFileParser;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ParserProvider;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.WarningConsumer;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: progressive inventory UI coalescing must never suppress the final READY
 * snapshot, including when a DBC is selected mid-index and the last batch adds no new IDs.
 */
class SourceInventoryLifecycleRegressionTest {

    @TempDir
    Path temp;

    @Test
    void finalReadyIsPublishedEvenWhenLastBatchAddsNoNewIdsAndDbcSelectedMidIndex() throws Exception {
        Path measurement = temp.resolve("large.asc");
        com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalAsc(measurement);
        Path dbcPath = temp.resolve("mid.dbc");
        writeMinimalDbc(dbcPath, 0x10, 0x20);

        int totalFrames = 2_500;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        SlowSyntheticReader reader = new SlowSyntheticReader(totalFrames, 0x10, started, allowFinish);
        ParserRegistry registry = new ParserRegistry(List.of(new FixedReaderProvider(reader)));
        DesktopAppService service = new DesktopAppService(new DesktopSession(registry));

        CountDownLatch readyLatch = new CountDownLatch(1);
        AtomicReference<CanSourceInventory> last = new AtomicReference<>();
        service.sourceInventoryModel().addListener(() -> {
            service.sourceInventoryModel().focusedInventory().ifPresent(inv -> {
                last.set(inv);
                if (inv.status() == CanInventoryStatus.INVENTORY_READY) {
                    readyLatch.countDown();
                }
            });
        });

        WorkspaceFile meas = service.addPath(measurement, DesktopAppService.PlatformUi.NOOP);
        assertEquals(FileKind.MEASUREMENT_ASC, meas.kind());

        assertTrue(started.await(10, TimeUnit.SECONDS), "reader must start emitting frames");
        WorkspaceFile dbc = service.addPath(dbcPath, DesktopAppService.PlatformUi.NOOP);
        service.selectDefinition(dbc.id());
        assertEquals(CanDefinitionMatchStatus.WAITING_FOR_SOURCE_INVENTORY,
                service.sourceInventoryModel().matchState().status());

        allowFinish.countDown();

        assertTrue(readyLatch.await(20, TimeUnit.SECONDS),
                "final READY must be published; last="
                        + (last.get() == null ? "null" : last.get().status() + " frames=" + last.get().totalCanFrames()));

        CanSourceInventory ready = service.sourceInventoryModel().focusedInventory().orElseThrow();
        assertEquals(CanInventoryStatus.INVENTORY_READY, ready.status());
        assertEquals(totalFrames, ready.totalCanFrames());
        assertEquals(1, ready.totalCanChannels());

        assertTrue(waitUntil(() -> {
            CanDefinitionMatchStatus st = service.sourceInventoryModel().matchState().status();
            return st == CanDefinitionMatchStatus.READY
                    || st == CanDefinitionMatchStatus.NO_MATCHING_MESSAGE_IDS;
        }, 5, TimeUnit.SECONDS), "matching must run after READY");
        assertTrue(service.sourceInventoryModel().matchState().summary().isPresent());
        assertTrue(service.sourceInventoryModel().matchState().summary().orElseThrow().dbcMessageCount() >= 1);
        assertEquals(totalFrames, ready.totalCanFrames(), "inventory must not be restarted by DBC select");
    }

    @Test
    void coalescedProgressThenImmediateReadyKeepsFinalFrameCount() {
        List<Runnable> queued = new ArrayList<>();
        CoalescingUiDispatcher dispatcher = new CoalescingUiDispatcher(queued::add, 150_000_000L);
        AtomicReference<CanInventoryStatus> status = new AtomicReference<>();
        AtomicReference<Long> frames = new AtomicReference<>(0L);

        dispatcher.submit(() -> {
            status.set(CanInventoryStatus.INDEXING_MESSAGE_IDS);
            frames.set(1_015_808L);
        });
        queued.getFirst().run();
        assertEquals(CanInventoryStatus.INDEXING_MESSAGE_IDS, status.get());

        dispatcher.submitImmediate(() -> {
            status.set(CanInventoryStatus.INVENTORY_READY);
            frames.set(1_051_734L);
        });
        assertTrue(queued.size() >= 2);
        queued.get(1).run();
        assertEquals(CanInventoryStatus.INVENTORY_READY, status.get());
        assertEquals(1_051_734L, frames.get());
    }

    private static boolean waitUntil(Check check, long time, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(time);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return true;
            }
            Thread.sleep(20);
        }
        return check.ok();
    }

    private static void writeMinimalDbc(Path path, long... ids) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("VERSION \"\"\n\nNS_\nBS_:\n\n");
        for (long id : ids) {
            sb.append("BO_ ").append(id).append(" M_").append(id).append(": 8 Vector__XXX\n\n");
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static final class FixedReaderProvider implements ParserProvider {
        private final AutomotiveFileReader reader;

        private FixedReaderProvider(AutomotiveFileReader reader) {
            this.reader = reader;
        }

        @Override
        public String providerId() {
            return "test-slow-synthetic";
        }

        @Override
        public java.util.Optional<com.stellar.platform.parser.spi.ContentFormatMatch> probeContent(Path path) {
            return com.stellar.platform.parser.asc.AscContentProbe.probe(path);
        }

        @Override
        public MeasurementFileParser createParser() {
            return new MeasurementFileParser() {
                @Override
                public String formatKey() {
                    return "asc";
                }

                @Override
                public boolean supports(Path path) {
                    return false;
                }

                @Override
                public com.stellar.platform.domain.MeasurementDescriptor open(
                        Path path,
                        com.stellar.platform.parser.spi.ParserContext context
                ) throws ParserException {
                    throw new UnsupportedOperationException("not used");
                }
            };
        }

        @Override
        public Optional<AutomotiveFileReader> createReader() {
            return Optional.of(reader);
        }
    }

    private static final class SlowSyntheticReader implements AutomotiveFileReader {
        private final int totalFrames;
        private final long canId;
        private final CountDownLatch started;
        private final CountDownLatch allowFinish;

        private SlowSyntheticReader(
                int totalFrames,
                long canId,
                CountDownLatch started,
                CountDownLatch allowFinish
        ) {
            this.totalFrames = totalFrames;
            this.canId = canId;
            this.started = started;
            this.allowFinish = allowFinish;
        }

        @Override
        public String formatKey() {
            return "asc";
        }

        @Override
        public boolean supports(Path path) {
            return path != null && path.getFileName() != null
                    && path.getFileName().toString().toLowerCase().endsWith(".asc");
        }

        @Override
        public FileMetadata readMetadata(Path path) {
            return new FileMetadata("asc", path.getFileName().toString(), path.toString());
        }

        @Override
        public void readFrames(
                Path path,
                ReaderContext context,
                CanFrameConsumer frameConsumer,
                WarningConsumer warningConsumer
        ) throws ParserException {
            int batchSize = Math.max(1, context.batchSize());
            List<CanonicalCanFrame> batch = new ArrayList<>(batchSize);
            // Emit a first small progressive batch, pause for DBC attach, then finish.
            int firstBatch = Math.min(64, totalFrames);
            for (int i = 0; i < firstBatch; i++) {
                batch.add(frame(i));
            }
            try {
                frameConsumer.onBatch(List.copyOf(batch));
            } catch (Exception e) {
                throw new ParserException("batch failed", e);
            }
            batch.clear();
            started.countDown();
            try {
                if (!allowFinish.await(15, TimeUnit.SECONDS)) {
                    throw new ParserException("timed out waiting to finish inventory");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = firstBatch; i < totalFrames; i++) {
                if (context.cancellationToken().isCancellationRequested()) {
                    return;
                }
                batch.add(frame(i));
                if (batch.size() >= batchSize) {
                    try {
                        frameConsumer.onBatch(List.copyOf(batch));
                    } catch (Exception e) {
                        throw new ParserException("batch failed", e);
                    }
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                try {
                    frameConsumer.onBatch(batch);
                } catch (Exception e) {
                    throw new ParserException("batch failed", e);
                }
            }
        }

        private CanonicalCanFrame frame(int i) {
            return new CanonicalCanFrame(
                    i,
                    i,
                    1,
                    canId,
                    false,
                    FrameDirection.RX,
                    1,
                    new byte[]{1},
                    FrameQuality.OK,
                    SourceReference.ofLine(i + 1)
            );
        }
    }
}